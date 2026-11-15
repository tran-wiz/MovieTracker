@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.movietracker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val sharedIncomingText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractIncomingShare(intent)
        setContent {
            MovieTrackerTheme {
                MovieTrackerApp(
                    incomingShareText = sharedIncomingText.value,
                    onConsumeIncomingShare = { sharedIncomingText.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractIncomingShare(intent)
    }

    private fun extractIncomingShare(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) sharedIncomingText.value = text
            }
            Intent.ACTION_VIEW -> {
                val data = intent.dataString
                if (!data.isNullOrBlank()) sharedIncomingText.value = data
            }
        }
    }
}

@Composable
fun MovieTrackerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFFF5C518),
            onPrimary = Color(0xFF1A1600),
            secondary = Color(0xFF64B5F6),
            background = Color(0xFF101216),
            surface = Color(0xFF191C22),
            surfaceVariant = Color(0xFF242933)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFFD4A000),
            onPrimary = Color.White,
            secondary = Color(0xFF1976D2),
            background = Color(0xFFF6F7F9),
            surface = Color.White,
            surfaceVariant = Color(0xFFEBEEF3)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

data class NewSeasonCheckSummary(
    val checkedSeries: Int,
    val addedLabels: List<String>,
    val markedEnded: Int
)

private suspend fun runNewSeasonsCheck(
    db: MovieDatabaseHelper,
    tmdbKey: String,
    onProgress: (String) -> Unit = {}
): NewSeasonCheckSummary {
    val candidates = db.getSeriesCandidatesForNewSeasons()
    val today = CsvAndBackupHelper.todayIsoDate()
    val toInsert = mutableListOf<MovieItem>()
    val addedLabels = mutableListOf<String>()
    var markedEnded = 0

    candidates.forEachIndexed { idx, c ->
        withContext(Dispatchers.Main) {
            onProgress("Checking ${idx + 1}/${candidates.size}: ${c.title}")
        }
        val details = MediaApiClient.fetchTmdbSeriesDetails(c.imdbId, c.title, tmdbKey)
        if (details != null) {
            val newAiredSeasons = details.seasons.filter { s ->
                s.seasonNumber > c.maxSeasonNumber &&
                    s.episodeCount > 0 &&
                    s.airDate.length >= 10 &&
                    s.airDate <= today
            }
            for (s in newAiredSeasons) {
                toInsert.add(
                    MovieItem(
                        title = c.title,
                        type = "Series",
                        releaseYear = if (s.airYear > 0) s.airYear else c.releaseYear,
                        imdbId = c.imdbId,
                        imdbUrl = c.imdbUrl,
                        seasonNumber = s.seasonNumber,
                        status = MovieStatus.WATCHLIST,
                        imdbRating = c.imdbRating,
                        genres = c.genres,
                        plot = c.plot.ifBlank { details.plot },
                        posterUrl = c.posterUrl.ifBlank { details.posterUrl },
                        seriesEnded = details.isEndedOrCanceled
                    )
                )
                addedLabels.add("${c.title} S${s.seasonNumber.toString().padStart(2, '0')}")
            }
            if (details.isEndedOrCanceled) {
                db.markSeriesEnded(c.seasonIds)
                markedEnded++
            }
        }
        delay(100)
    }

    val actuallyInserted = db.insertNewSeasonsSafely(toInsert)
    val finalLabels = if (actuallyInserted > 0) addedLabels.take(actuallyInserted) else emptyList()
    return NewSeasonCheckSummary(
        checkedSeries = candidates.size,
        addedLabels = finalLabels,
        markedEnded = markedEnded
    )
}

private fun writeAutoBackupToFolder(context: Context, db: MovieDatabaseHelper, folderUriStr: String): Boolean {
    if (folderUriStr.isBlank()) return false
    return try {
        val treeUri = Uri.parse(folderUriStr)
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val fileName = "MovieTracker_AutoBackup.json"

        var existingDocUri: Uri? = null
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == fileName) {
                    existingDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                    break
                }
            }
        }

        val targetUri = existingDocUri ?: DocumentsContract.createDocument(
            context.contentResolver,
            parentDocUri,
            "application/json",
            fileName
        ) ?: return false

        val jsonText = CsvAndBackupHelper.exportToJson(db.getAllMovies())
        context.contentResolver.openOutputStream(targetUri, "wt")?.use {
            it.write(jsonText.toByteArray(Charsets.UTF_8))
        }
        true
    } catch (_: Exception) {
        false
    }
}

private fun shareBackupFileToDrive(context: Context, db: MovieDatabaseHelper) {
    val dir = File(context.cacheDir, "backups").apply { if (!exists()) mkdirs() }
    val outFile = File(dir, "MovieTracker_Backup_${CsvAndBackupHelper.todayIsoDate()}.json")
    outFile.writeText(CsvAndBackupHelper.exportToJson(db.getAllMovies()), Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, "com.example.movietracker.fileprovider", outFile)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, outFile.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Send backup to Google Drive"))
}

@Composable
fun MovieTrackerApp(
    incomingShareText: String?,
    onConsumeIncomingShare: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { MovieDatabaseHelper(context) }
    val prefs = remember { context.getSharedPreferences("movie_tracker_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var omdbKey by remember { mutableStateOf(prefs.getString("omdb_key", "") ?: "") }
    var tmdbKey by remember { mutableStateOf(prefs.getString("tmdb_key", "") ?: "") }
    var autoBackupEnabled by remember { mutableStateOf(prefs.getBoolean("auto_backup_enabled", false)) }
    var autoBackupUri by remember { mutableStateOf(prefs.getString("auto_backup_uri", "") ?: "") }
    var autoCheckNewSeasons by remember { mutableStateOf(prefs.getBoolean("auto_check_new_seasons", true)) }

    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<MovieStatus?>(null) }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var sortOption by remember { mutableStateOf(SortOption.UPDATED_DESC) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var allMovies by remember { mutableStateOf<List<MovieItem>>(emptyList()) }
    var entries by remember { mutableStateOf<List<LibraryEntry>>(emptyList()) }
    var stats by remember { mutableStateOf(MovieStats()) }

    var selectedMovie by remember { mutableStateOf<MovieItem?>(null) }
    var selectedSeriesKey by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addInitialQuery by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var hasRunDailySeasonCheck by remember { mutableStateOf(false) }

    fun triggerDataUpdated(runAutoBackup: Boolean = true) {
        refreshTrigger++
        if (runAutoBackup && autoBackupEnabled && autoBackupUri.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                writeAutoBackupToFolder(context, db, autoBackupUri)
            }
        }
    }

    LaunchedEffect(searchQuery, statusFilter, typeFilter, sortOption, refreshTrigger) {
        val (loadedAll, builtEntries, st) = withContext(Dispatchers.IO) {
            val raw = db.getAllMovies()
            val grouped = buildLibraryEntries(raw, searchQuery, statusFilter, typeFilter, sortOption)
            Triple(raw, grouped, db.getStats())
        }
        allMovies = loadedAll
        entries = builtEntries
        stats = st
    }

    LaunchedEffect(allMovies.isNotEmpty(), tmdbKey, autoCheckNewSeasons) {
        if (!hasRunDailySeasonCheck && allMovies.isNotEmpty() && autoCheckNewSeasons && tmdbKey.isNotBlank()) {
            hasRunDailySeasonCheck = true
            val lastCheck = prefs.getLong("last_new_season_check", 0L)
            val oneDayMs = 24L * 60L * 60L * 1000L
            if (System.currentTimeMillis() - lastCheck > oneDayMs) {
                val summary = withContext(Dispatchers.IO) {
                    runCatching { runNewSeasonsCheck(db, tmdbKey) }.getOrNull()
                }
                prefs.edit().putLong("last_new_season_check", System.currentTimeMillis()).apply()
                if (summary != null && summary.addedLabels.isNotEmpty()) {
                    triggerDataUpdated()
                    val preview = summary.addedLabels.take(3).joinToString(", ")
                    val more = if (summary.addedLabels.size > 3) " +${summary.addedLabels.size - 3} more" else ""
                    Toast.makeText(context, "New seasons added to Watchlist: $preview$more", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(incomingShareText) {
        if (!incomingShareText.isNullOrBlank()) {
            addInitialQuery = incomingShareText
            showAddDialog = true
            onConsumeIncomingShare()
        }
    }

    val activeSeriesGroup = remember(selectedSeriesKey, allMovies) {
        val key = selectedSeriesKey ?: return@remember null
        buildLibraryEntries(allMovies, "", null, "Series", SortOption.TITLE_ASC)
            .filterIsInstance<SeriesGroupEntry>()
            .firstOrNull { it.groupKey == key }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MovieTracker", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "${stats.total} entries • ${stats.watched} watched • ${stats.watchlist} watchlist",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(AppIcons.Settings, contentDescription = "Settings & Backup")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    addInitialQuery = ""
                    showAddDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(AppIcons.Add, contentDescription = "Add Movie")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("Search title, tt..., genre, year...") },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(AppIcons.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = statusFilter == null,
                        onClick = { statusFilter = null },
                        label = { Text("All") }
                    )
                }
                item {
                    FilterChip(
                        selected = statusFilter == MovieStatus.WATCHED,
                        onClick = {
                            statusFilter = if (statusFilter == MovieStatus.WATCHED) null else MovieStatus.WATCHED
                        },
                        label = { Text("Watched (${stats.watched})") }
                    )
                }
                item {
                    FilterChip(
                        selected = statusFilter == MovieStatus.WATCHLIST,
                        onClick = {
                            statusFilter = if (statusFilter == MovieStatus.WATCHLIST) null else MovieStatus.WATCHLIST
                        },
                        label = { Text("Watchlist (${stats.watchlist})") }
                    )
                }
                item {
                    FilterChip(
                        selected = statusFilter == MovieStatus.DROPPED,
                        onClick = {
                            statusFilter = if (statusFilter == MovieStatus.DROPPED) null else MovieStatus.DROPPED
                        },
                        label = { Text("Dropped (${stats.dropped})") }
                    )
                }
                listOf("Movie", "Series", "Documentary").forEach { t ->
                    item {
                        FilterChip(
                            selected = typeFilter == t,
                            onClick = { typeFilter = if (typeFilter == t) null else t },
                            label = { Text(t) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${entries.size} titles",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box {
                    TextButton(onClick = { showSortMenu = true }) {
                        Text("Sort: ${sortOption.label}", fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SortOption.entries.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt.label) },
                                onClick = {
                                    sortOption = opt
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (stats.total == 0) "Your library is empty" else "No matching titles",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (stats.total == 0) {
                                "Tap Settings (top right) to import CSV or connect Trakt, or tap + to add a title."
                            } else {
                                "Try a different search query or clear your filters."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (stats.total == 0) {
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { showSettingsDialog = true }) {
                                Text("Open Settings")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = entries,
                        key = {
                            when (it) {
                                is SingleLibraryEntry -> "m_${it.item.id}"
                                is SeriesGroupEntry -> "s_${it.groupKey}"
                            }
                        }
                    ) { entry ->
                        when (entry) {
                            is SingleLibraryEntry -> MovieRowCard(
                                movie = entry.item,
                                onClick = { selectedMovie = entry.item }
                            )
                            is SeriesGroupEntry -> SeriesGroupCard(
                                group = entry,
                                onClick = { selectedSeriesKey = entry.groupKey }
                            )
                        }
                    }
                }
            }
        }
    }

    activeSeriesGroup?.let { group ->
        SeriesDetailDialog(
            group = group,
            omdbKey = omdbKey,
            tmdbKey = tmdbKey,
            onDismiss = { selectedSeriesKey = null },
            onSelectSeason = { season -> selectedMovie = season },
            onQuickMarkWatched = { season ->
                scope.launch(Dispatchers.IO) {
                    db.updateMovie(
                        season.copy(
                            status = MovieStatus.WATCHED,
                            watchedDate = season.watchedDate.ifBlank { CsvAndBackupHelper.todayIsoDate() },
                            rewatchCount = maxOf(season.rewatchCount, 1)
                        )
                    )
                    withContext(Dispatchers.Main) {
                        triggerDataUpdated()
                    }
                }
            },
            onAddSeason = { nextNum ->
                scope.launch(Dispatchers.IO) {
                    db.insertMovie(
                        MovieItem(
                            title = group.title,
                            type = "Series",
                            releaseYear = group.releaseYear,
                            imdbId = group.imdbId,
                            imdbUrl = group.imdbUrl,
                            seasonNumber = nextNum,
                            status = MovieStatus.WATCHLIST,
                            imdbRating = group.imdbRating,
                            genres = group.genres,
                            plot = group.plot,
                            posterUrl = group.posterUrl
                        )
                    )
                    withContext(Dispatchers.Main) {
                        triggerDataUpdated()
                        Toast.makeText(context, "Added Season $nextNum", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSyncSeriesInfo = { res, tmdbDetails ->
                scope.launch(Dispatchers.IO) {
                    val today = CsvAndBackupHelper.todayIsoDate()
                    val existingNums = group.seasons.mapNotNull { it.seasonNumber }.toSet()
                    val endedFlag = tmdbDetails?.isEndedOrCanceled ?: group.seriesEnded
                    for (s in group.seasons) {
                        db.updateMovie(
                            s.copy(
                                imdbId = s.imdbId.ifBlank { res?.imdbId ?: "" },
                                imdbRating = res?.imdbRating ?: s.imdbRating,
                                genres = s.genres.ifBlank { res?.genres ?: "" },
                                plot = s.plot.ifBlank { res?.plot ?: tmdbDetails?.plot ?: "" },
                                posterUrl = res?.posterUrl?.ifBlank { null } ?: s.posterUrl.ifBlank { tmdbDetails?.posterUrl ?: "" },
                                seriesEnded = endedFlag
                            )
                        )
                    }
                    var addedSeasons = 0
                    val maxExisting = existingNums.maxOrNull() ?: 0
                    val tmdbSeasons = tmdbDetails?.seasons.orEmpty()
                    for (ts in tmdbSeasons) {
                        val shouldAdd = ts.seasonNumber !in existingNums &&
                            ts.episodeCount > 0 &&
                            (ts.seasonNumber <= maxExisting || (ts.airDate.length >= 10 && ts.airDate <= today))
                        if (shouldAdd) {
                            db.insertMovie(
                                MovieItem(
                                    title = group.title,
                                    type = "Series",
                                    releaseYear = if (ts.airYear > 0) ts.airYear else group.releaseYear,
                                    imdbId = group.imdbId.ifBlank { res?.imdbId ?: "" },
                                    imdbUrl = group.imdbUrl,
                                    seasonNumber = ts.seasonNumber,
                                    status = MovieStatus.WATCHLIST,
                                    imdbRating = res?.imdbRating ?: group.imdbRating,
                                    genres = res?.genres?.ifBlank { null } ?: group.genres,
                                    plot = res?.plot?.ifBlank { null } ?: group.plot.ifBlank { tmdbDetails?.plot ?: "" },
                                    posterUrl = res?.posterUrl?.ifBlank { null } ?: group.posterUrl.ifBlank { tmdbDetails?.posterUrl ?: "" },
                                    seriesEnded = endedFlag
                                )
                            )
                            addedSeasons++
                        }
                    }
                    withContext(Dispatchers.Main) {
                        triggerDataUpdated()
                        val ratingNote = if (res?.imdbRating != null) "IMDb: ${res.imdbRating}" else "No IMDb rating on OMDb yet"
                        val seasonNote = if (addedSeasons > 0) " • +$addedSeasons seasons" else ""
                        Toast.makeText(context, "Synced ($ratingNote)$seasonNote", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    selectedMovie?.let { movie ->
        MovieDetailDialog(
            movie = movie,
            omdbKey = omdbKey,
            tmdbKey = tmdbKey,
            onDismiss = { selectedMovie = null },
            onSave = { updated ->
                scope.launch(Dispatchers.IO) {
                    db.updateMovie(updated)
                    withContext(Dispatchers.Main) {
                        selectedMovie = null
                        triggerDataUpdated()
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDelete = { toDelete ->
                scope.launch(Dispatchers.IO) {
                    db.deleteMovie(toDelete.id)
                    withContext(Dispatchers.Main) {
                        selectedMovie = null
                        triggerDataUpdated()
                        Toast.makeText(context, "Deleted ${toDelete.displayTitle()}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showAddDialog) {
        AddOrShareMovieDialog(
            initialInput = addInitialQuery,
            db = db,
            omdbKey = omdbKey,
            tmdbKey = tmdbKey,
            onDismiss = { showAddDialog = false },
            onOpenExisting = { existing ->
                showAddDialog = false
                if (existing.type.equals("Series", true)) {
                    selectedSeriesKey = existing.seriesGroupKey()
                } else {
                    selectedMovie = existing
                }
            },
            onAddNew = { newMovie ->
                scope.launch(Dispatchers.IO) {
                    db.insertMovie(newMovie)
                    withContext(Dispatchers.Main) {
                        showAddDialog = false
                        triggerDataUpdated()
                        Toast.makeText(context, "Added ${newMovie.displayTitle()}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showSettingsDialog) {
        SettingsAndBackupDialog(
            db = db,
            prefs = prefs,
            omdbKey = omdbKey,
            tmdbKey = tmdbKey,
            autoBackupEnabled = autoBackupEnabled,
            autoBackupUri = autoBackupUri,
            autoCheckNewSeasons = autoCheckNewSeasons,
            onSaveKeys = { newOmdb, newTmdb ->
                omdbKey = newOmdb.trim()
                tmdbKey = newTmdb.trim()
                prefs.edit()
                    .putString("omdb_key", omdbKey)
                    .putString("tmdb_key", tmdbKey)
                    .apply()
                Toast.makeText(context, "API Keys saved", Toast.LENGTH_SHORT).show()
            },
            onToggleAutoCheckSeasons = { enabled ->
                autoCheckNewSeasons = enabled
                prefs.edit().putBoolean("auto_check_new_seasons", enabled).apply()
            },
            onUpdateAutoBackup = { enabled, uriStr ->
                autoBackupEnabled = enabled
                autoBackupUri = uriStr
                prefs.edit()
                    .putBoolean("auto_backup_enabled", enabled)
                    .putString("auto_backup_uri", uriStr)
                    .apply()
                if (enabled && uriStr.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        val ok = writeAutoBackupToFolder(context, db, uriStr)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                if (ok) "Auto-backup file updated!" else "Failed to write to folder",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            },
            onDataChanged = { triggerDataUpdated() },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
fun SeriesGroupCard(group: SeriesGroupEntry, onClick: () -> Unit) {
    val totalSeasons = group.seasons.size
    val progressRatio = if (totalSeasons > 0) group.watchedCount.toFloat() / totalSeasons.toFloat() else 0f
    val pillColor = when {
        group.activeWatchingNote.isNotBlank() -> Color(0xFF1565C0)
        group.watchedCount == totalSeasons && totalSeasons > 0 -> Color(0xFF2E7D32)
        group.droppedCount == totalSeasons && totalSeasons > 0 -> Color(0xFFC62828)
        else -> Color(0xFF1565C0)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PosterThumbnail(
                posterUrl = group.posterUrl,
                title = group.title,
                modifier = Modifier
                    .width(54.dp)
                    .height(80.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val yearText = if (group.releaseYear > 0) " • ${group.releaseYear}" else ""
                Text(
                    text = "Series$yearText • $totalSeasons ${if (totalSeasons == 1) "season" else "seasons"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (group.genres.isNotBlank()) {
                    Text(
                        text = group.genres,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progressRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF2E7D32),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val label = when {
                        group.activeWatchingNote.isNotBlank() -> group.activeWatchingNote
                        group.watchedCount == totalSeasons && totalSeasons > 0 -> "Watched all ($totalSeasons/$totalSeasons)"
                        group.droppedCount == totalSeasons && totalSeasons > 0 -> "Dropped"
                        else -> "${group.watchedCount}/$totalSeasons watched"
                    }
                    Surface(
                        color = pillColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = label,
                            color = pillColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (group.imdbRating != null || group.avgPersonalRating != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    if (group.imdbRating != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Star, null, tint = Color(0xFFF5C518), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(String.format(Locale.US, "%.1f", group.imdbRating), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    if (group.avgPersonalRating != null) {
                        Text(
                            text = "Me: ${String.format(Locale.US, "%.1f", group.avgPersonalRating)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailDialog(
    group: SeriesGroupEntry,
    omdbKey: String,
    tmdbKey: String,
    onDismiss: () -> Unit,
    onSelectSeason: (MovieItem) -> Unit,
    onQuickMarkWatched: (MovieItem) -> Unit,
    onAddSeason: (Int) -> Unit,
    onSyncSeriesInfo: (ApiMediaResult?, TmdbSeriesDetails?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSyncing by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            group.title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, "Close") }
                    }
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Row(verticalAlignment = Alignment.Top) {
                            PosterThumbnail(
                                posterUrl = group.posterUrl,
                                title = group.title,
                                modifier = Modifier
                                    .width(86.dp)
                                    .height(128.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                val yearLabel = if (group.releaseYear > 0) "${group.releaseYear} • " else ""
                                val endedLabel = if (group.seriesEnded) " • Ended" else ""
                                Text(
                                    "${yearLabel}${group.watchedCount}/${group.seasons.size} watched$endedLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (group.imdbRating != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(AppIcons.Star, null, tint = Color(0xFFF5C518), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("IMDb ${group.imdbRating} / 10", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                                if (group.genres.isNotBlank()) {
                                    Text(group.genres, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val url = group.imdbUrl.ifBlank {
                                        if (group.imdbId.isNotBlank()) "https://www.imdb.com/title/${group.imdbId}/" else ""
                                    }
                                    if (url.isNotBlank()) {
                                        OutlinedButton(
                                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("IMDb", fontSize = 12.sp)
                                        }
                                    }
                                    OutlinedButton(
                                        enabled = !isSyncing,
                                        onClick = {
                                            if (omdbKey.isBlank() && tmdbKey.isBlank()) {
                                                Toast.makeText(context, "Add OMDb/TMDB key in Settings first", Toast.LENGTH_SHORT).show()
                                                return@OutlinedButton
                                            }
                                            isSyncing = true
                                            scope.launch(Dispatchers.IO) {
                                                val res = MediaApiClient.enrichFromApis(
                                                    title = group.title,
                                                    year = group.releaseYear,
                                                    imdbId = group.imdbId,
                                                    isSeries = true,
                                                    tmdbId = null,
                                                    omdbKey = omdbKey,
                                                    tmdbKey = tmdbKey
                                                )
                                                val tmdbDetails = if (tmdbKey.isNotBlank()) {
                                                    MediaApiClient.fetchTmdbSeriesDetails(group.imdbId.ifBlank { res?.imdbId ?: "" }, group.title, tmdbKey)
                                                } else null
                                                withContext(Dispatchers.Main) {
                                                    isSyncing = false
                                                    onSyncSeriesInfo(res, tmdbDetails)
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(AppIcons.Refresh, null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (isSyncing) "..." else "Sync Seasons", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        if (group.plot.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(group.plot, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Seasons", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            val nextNum = (group.seasons.mapNotNull { it.seasonNumber }.maxOrNull() ?: 0) + 1
                            TextButton(onClick = { onAddSeason(nextNum) }) {
                                Text("+ Add S${nextNum.toString().padStart(2, '0')}")
                            }
                        }
                    }

                    items(group.seasons, key = { it.id }) { season ->
                        val sColor = when (season.status) {
                            MovieStatus.WATCHED -> Color(0xFF2E7D32)
                            MovieStatus.WATCHLIST -> Color(0xFF1565C0)
                            MovieStatus.DROPPED -> Color(0xFFC62828)
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSeason(season) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val sLabel = season.seasonNumber?.let { "Season ${it.toString().padStart(2, '0')}" } ?: "Season"
                                    val yr = if (season.releaseYear > 0) " (${season.releaseYear})" else ""
                                    Text("$sLabel$yr", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Spacer(Modifier.height(3.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val stText = if (season.status == MovieStatus.WATCHED && season.watchedDate.isNotBlank()) {
                                            "Watched • ${season.watchedDate}"
                                        } else season.status.label
                                        Surface(color = sColor.copy(alpha = 0.16f), shape = RoundedCornerShape(5.dp)) {
                                            Text(
                                                stText,
                                                color = sColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        if (season.episodeProgress.isNotBlank()) {
                                            Text(
                                                season.episodeProgress,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.secondary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        if (season.personalRating != null) {
                                            Text(
                                                "Me: ${season.personalRating.toInt()}/10",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (season.notes.isNotBlank()) {
                                        Text(
                                            season.notes,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (season.status != MovieStatus.WATCHED) {
                                    IconButton(onClick = { onQuickMarkWatched(season) }) {
                                        Icon(AppIcons.Check, "Mark Watched", tint = Color(0xFF2E7D32))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MovieRowCard(movie: MovieItem, onClick: () -> Unit) {
    val statusColor = when (movie.status) {
        MovieStatus.WATCHED -> Color(0xFF2E7D32)
        MovieStatus.WATCHLIST -> Color(0xFF1565C0)
        MovieStatus.DROPPED -> Color(0xFFC62828)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PosterThumbnail(
                posterUrl = movie.posterUrl,
                title = movie.title,
                modifier = Modifier
                    .width(54.dp)
                    .height(80.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = movie.displayTitle(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val yearText = if (movie.releaseYear > 0) " • ${movie.releaseYear}" else ""
                val rwText = if (movie.rewatchCount > 1) " • x${movie.rewatchCount}" else ""
                Text(
                    text = "${movie.type}$yearText$rwText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (movie.genres.isNotBlank()) {
                    Text(
                        text = movie.genres,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val dateLabel = if (movie.status == MovieStatus.WATCHED && movie.watchedDate.isNotBlank()) {
                        "${movie.status.label} • ${movie.watchedDate}"
                    } else {
                        movie.status.label
                    }
                    Surface(
                        color = statusColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = dateLabel,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    if (movie.imdbId.isNotBlank()) {
                        Text(
                            text = movie.imdbId,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (movie.imdbRating != null || movie.personalRating != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    if (movie.imdbRating != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                AppIcons.Star,
                                contentDescription = null,
                                tint = Color(0xFFF5C518),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = String.format(Locale.US, "%.1f", movie.imdbRating),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                    if (movie.personalRating != null) {
                        Text(
                            text = "Me: ${String.format(Locale.US, "%.0f", movie.personalRating)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PosterThumbnail(posterUrl: String, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(posterUrl) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(posterUrl) {
        if (posterUrl.isBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            loadOrDownloadPoster(context, posterUrl)
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

private fun loadOrDownloadPoster(context: Context, urlStr: String): Bitmap? {
    return try {
        val dir = File(context.filesDir, "posters").apply { if (!exists()) mkdirs() }
        val hash = MessageDigest.getInstance("MD5")
            .digest(urlStr.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(dir, "$hash.jpg")
        if (file.exists() && file.length() > 0) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
fun MovieDetailDialog(
    movie: MovieItem,
    omdbKey: String,
    tmdbKey: String,
    onDismiss: () -> Unit,
    onSave: (MovieItem) -> Unit,
    onDelete: (MovieItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf(movie.title) }
    var type by remember { mutableStateOf(movie.type) }
    var yearStr by remember { mutableStateOf(if (movie.releaseYear > 0) movie.releaseYear.toString() else "") }
    var seasonStr by remember { mutableStateOf(movie.seasonNumber?.toString() ?: "") }
    var imdbId by remember { mutableStateOf(movie.imdbId) }
    var status by remember { mutableStateOf(movie.status) }
    var watchedDate by remember { mutableStateOf(movie.watchedDate) }
    var rewatchCount by remember { mutableIntStateOf(movie.rewatchCount) }
    var imdbRating by remember { mutableStateOf(movie.imdbRating) }
    var personalRating by remember { mutableStateOf(movie.personalRating) }
    var genres by remember { mutableStateOf(movie.genres) }
    var plot by remember { mutableStateOf(movie.plot) }
    var posterUrl by remember { mutableStateOf(movie.posterUrl) }
    var episodeProgress by remember { mutableStateOf(movie.episodeProgress) }
    var notes by remember { mutableStateOf(movie.notes) }

    var syncMessage by remember { mutableStateOf("") }
    var isFetching by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Details", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, "Close") }
                    },
                    actions = {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(AppIcons.Delete, "Delete", tint = Color(0xFFE53935))
                        }
                    }
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        PosterThumbnail(
                            posterUrl = posterUrl,
                            title = title,
                            modifier = Modifier
                                .width(92.dp)
                                .height(136.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (imdbRating != null) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.Star, null, tint = Color(0xFFF5C518), modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("IMDb $imdbRating / 10", fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (genres.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(genres, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val url = movie.effectiveImdbUrl().ifBlank {
                                    if (imdbId.isNotBlank()) "https://www.imdb.com/title/$imdbId/" else ""
                                }
                                if (url.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("IMDb", fontSize = 12.sp)
                                    }
                                }
                                OutlinedButton(
                                    enabled = !isFetching,
                                    onClick = {
                                        if (omdbKey.isBlank() && tmdbKey.isBlank()) {
                                            Toast.makeText(context, "Add OMDb/TMDB key in Settings first", Toast.LENGTH_LONG).show()
                                            return@OutlinedButton
                                        }
                                        isFetching = true
                                        syncMessage = ""
                                        scope.launch(Dispatchers.IO) {
                                            val res = MediaApiClient.enrichFromApis(
                                                title = title,
                                                year = yearStr.toIntOrNull() ?: 0,
                                                imdbId = imdbId,
                                                isSeries = type.equals("Series", true),
                                                tmdbId = null,
                                                omdbKey = omdbKey,
                                                tmdbKey = tmdbKey
                                            )
                                            withContext(Dispatchers.Main) {
                                                isFetching = false
                                                if (res != null) {
                                                    if (res.imdbId.isNotBlank()) imdbId = res.imdbId
                                                    if (res.imdbRating != null) imdbRating = res.imdbRating
                                                    if (res.genres.isNotBlank()) genres = res.genres
                                                    if (res.plot.isNotBlank()) plot = res.plot
                                                    if (res.posterUrl.isNotBlank()) posterUrl = res.posterUrl
                                                    if (res.releaseYear > 0 && yearStr.isBlank()) yearStr = res.releaseYear.toString()
                                                    syncMessage = if (res.imdbRating != null) {
                                                        "Synced! IMDb: ${res.imdbRating} (tap Save to keep)"
                                                    } else {
                                                        "Synced info — OMDb has no IMDb rating for this title yet."
                                                    }
                                                } else {
                                                    syncMessage = "No match found on OMDb or TMDB."
                                                }
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(AppIcons.Refresh, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isFetching) "Syncing..." else "Sync", fontSize = 12.sp)
                                }
                            }
                            if (syncMessage.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(syncMessage, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }

                    if (plot.isNotBlank()) {
                        Text(plot, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    HorizontalDivider()

                    Text("Status", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MovieStatus.entries.forEach { st ->
                            FilterChip(
                                selected = status == st,
                                onClick = {
                                    status = st
                                    if (st == MovieStatus.WATCHED) {
                                        if (watchedDate.isBlank()) watchedDate = CsvAndBackupHelper.todayIsoDate()
                                        if (rewatchCount < 1) rewatchCount = 1
                                    }
                                },
                                label = { Text(st.label) }
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = watchedDate,
                            onValueChange = { watchedDate = it },
                            label = { Text("Watched Date (YYYY-MM-DD)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(onClick = {
                            status = MovieStatus.WATCHED
                            watchedDate = CsvAndBackupHelper.todayIsoDate()
                            if (rewatchCount < 1) rewatchCount = 1
                        }) {
                            Text("Today")
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Watches: $rewatchCount", fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { if (rewatchCount > 0) rewatchCount-- }) { Text("-1") }
                            FilledTonalButton(onClick = {
                                rewatchCount++
                                status = MovieStatus.WATCHED
                                watchedDate = CsvAndBackupHelper.todayIsoDate()
                            }) { Text("+1 Rewatch") }
                        }
                    }

                    Text(
                        "My Score: ${personalRating?.toInt()?.let { "$it/10" } ?: "Not rated"}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = personalRating == null,
                                onClick = { personalRating = null },
                                label = { Text("None") }
                            )
                        }
                        items((1..10).toList()) { score ->
                            FilterChip(
                                selected = personalRating?.toInt() == score,
                                onClick = { personalRating = score.toDouble() },
                                label = { Text("$score") }
                            )
                        }
                    }

                    if (type.equals("Series", true)) {
                        OutlinedTextField(
                            value = episodeProgress,
                            onValueChange = { episodeProgress = it },
                            label = { Text("Episode Progress (e.g. Watching: 5/12 eps)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = yearStr,
                            onValueChange = { yearStr = it.filter { ch -> ch.isDigit() }.take(4) },
                            label = { Text("Year") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = seasonStr,
                            onValueChange = { seasonStr = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = { Text("Season") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = imdbId,
                            onValueChange = { imdbId = CsvAndBackupHelper.extractImdbId(it).ifBlank { it } },
                            label = { Text("IMDb ID") },
                            singleLine = true,
                            modifier = Modifier.weight(1.2f)
                        )
                    }
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        onSave(
                            movie.copy(
                                title = title.trim().ifBlank { movie.title },
                                type = type,
                                releaseYear = yearStr.toIntOrNull() ?: 0,
                                seasonNumber = seasonStr.toIntOrNull(),
                                imdbId = imdbId.trim(),
                                status = status,
                                watchedDate = if (status == MovieStatus.WATCHED) watchedDate.trim() else "",
                                rewatchCount = rewatchCount,
                                imdbRating = imdbRating,
                                personalRating = personalRating,
                                genres = genres,
                                plot = plot,
                                posterUrl = posterUrl,
                                episodeProgress = episodeProgress.trim(),
                                notes = notes.trim()
                            )
                        )
                    }) {
                        Icon(AppIcons.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Title?") },
            text = { Text("Remove \"${movie.displayTitle()}\" from your library?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(movie)
                }) {
                    Text("Delete", color = Color(0xFFE53935))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            }
        )
    }
}

@Composable
fun AddOrShareMovieDialog(
    initialInput: String,
    db: MovieDatabaseHelper,
    omdbKey: String,
    tmdbKey: String,
    onDismiss: () -> Unit,
    onOpenExisting: (MovieItem) -> Unit,
    onAddNew: (MovieItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var queryText by remember { mutableStateOf(initialInput) }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<ApiMediaResult>>(emptyList()) }
    var existingMatches by remember { mutableStateOf<List<MovieItem>>(emptyList()) }
    var errorMsg by remember { mutableStateOf("") }

    var draftTitle by remember { mutableStateOf("") }
    var draftType by remember { mutableStateOf("Movie") }
    var draftYear by remember { mutableStateOf("") }
    var draftSeason by remember { mutableStateOf("") }
    var draftImdbId by remember { mutableStateOf("") }
    var draftImdbRating by remember { mutableStateOf<Double?>(null) }
    var draftGenres by remember { mutableStateOf("") }
    var draftPlot by remember { mutableStateOf("") }
    var draftPosterUrl by remember { mutableStateOf("") }
    var draftStatus by remember { mutableStateOf(MovieStatus.WATCHLIST) }
    var draftDate by remember { mutableStateOf("") }
    var draftNotes by remember { mutableStateOf("") }
    var showForm by remember { mutableStateOf(false) }

    fun runSearch(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val extractedId = CsvAndBackupHelper.extractImdbId(trimmed)
        isSearching = true
        errorMsg = ""
        scope.launch(Dispatchers.IO) {
            try {
                val foundInDb = if (extractedId.isNotBlank()) db.findByImdbId(extractedId) else emptyList()
                if (extractedId.isNotBlank()) {
                    val enriched = MediaApiClient.enrichFromApis(
                        title = "",
                        year = 0,
                        imdbId = extractedId,
                        isSeries = false,
                        tmdbId = null,
                        omdbKey = omdbKey,
                        tmdbKey = tmdbKey
                    )
                    withContext(Dispatchers.Main) {
                        existingMatches = foundInDb
                        isSearching = false
                        if (enriched != null) {
                            draftTitle = enriched.title
                            draftType = enriched.type
                            if (enriched.type == "Series" && draftSeason.isBlank()) draftSeason = "1"
                            draftYear = if (enriched.releaseYear > 0) enriched.releaseYear.toString() else ""
                            draftImdbId = enriched.imdbId
                            draftImdbRating = enriched.imdbRating
                            draftGenres = enriched.genres
                            draftPlot = enriched.plot
                            draftPosterUrl = enriched.posterUrl
                            showForm = true
                        } else {
                            draftImdbId = extractedId
                            showForm = true
                            if (omdbKey.isBlank() && tmdbKey.isBlank()) {
                                errorMsg = "No API key set. You can still enter details manually below."
                            }
                        }
                    }
                } else {
                    if (omdbKey.isBlank() && tmdbKey.isBlank()) {
                        withContext(Dispatchers.Main) {
                            isSearching = false
                            errorMsg = "Add an OMDb/TMDB key in Settings to search online, or enter manually."
                        }
                        return@launch
                    }
                    val tmdbRes = if (tmdbKey.isNotBlank()) MediaApiClient.searchTmdb(trimmed, tmdbKey) else emptyList()
                    val results = if (tmdbRes.isNotEmpty()) tmdbRes else MediaApiClient.searchOmdb(trimmed, omdbKey)
                    withContext(Dispatchers.Main) {
                        isSearching = false
                        searchResults = results
                        if (results.isEmpty()) errorMsg = "No results found on TMDB/OMDb."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSearching = false
                    errorMsg = "Network error: ${e.message ?: "Unknown"}"
                }
            }
        }
    }

    LaunchedEffect(initialInput) {
        if (initialInput.isNotBlank()) {
            runSearch(initialInput)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Add / Check Title", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, null) }
                    }
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = queryText,
                            onValueChange = { queryText = it },
                            label = { Text("Title or IMDb link (tt...)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch(queryText) }),
                            modifier = Modifier.weight(1f)
                        )
                        Button(enabled = !isSearching, onClick = { runSearch(queryText) }) {
                            Text(if (isSearching) "..." else "Find")
                        }
                    }

                    if (existingMatches.isNotEmpty()) {
                        Surface(
                            color = Color(0xFFFFA000).copy(alpha = 0.16f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.Warning, null, tint = Color(0xFFFFA000), modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Already in your library!",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE65100)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                existingMatches.forEach { ex ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${ex.displayTitle()} • ${ex.status.label}" +
                                                if (ex.watchedDate.isNotBlank()) " (${ex.watchedDate})" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { onOpenExisting(ex) }) {
                                            Text("Open")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (errorMsg.isNotBlank()) {
                        Text(errorMsg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    if (!showForm) {
                        TextButton(onClick = {
                            draftTitle = if (CsvAndBackupHelper.extractImdbId(queryText).isBlank()) queryText.trim() else ""
                            draftImdbId = CsvAndBackupHelper.extractImdbId(queryText)
                            showForm = true
                        }) {
                            Text("+ Enter details manually")
                        }
                    }

                    if (searchResults.isNotEmpty() && !showForm) {
                        Text("Results (tap to select):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        searchResults.take(12).forEach { res ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        isSearching = true
                                        scope.launch(Dispatchers.IO) {
                                            val enriched = MediaApiClient.enrichFromApis(
                                                title = res.title,
                                                year = res.releaseYear,
                                                imdbId = res.imdbId,
                                                isSeries = res.type == "Series",
                                                tmdbId = res.tmdbId,
                                                omdbKey = omdbKey,
                                                tmdbKey = tmdbKey
                                            ) ?: res
                                            val matches = db.findByImdbId(enriched.imdbId)
                                            withContext(Dispatchers.Main) {
                                                isSearching = false
                                                existingMatches = matches
                                                draftTitle = enriched.title
                                                draftType = enriched.type
                                                if (enriched.type == "Series" && draftSeason.isBlank()) draftSeason = "1"
                                                draftYear = if (enriched.releaseYear > 0) enriched.releaseYear.toString() else ""
                                                draftImdbId = enriched.imdbId
                                                draftImdbRating = enriched.imdbRating
                                                draftGenres = enriched.genres
                                                draftPlot = enriched.plot
                                                draftPosterUrl = enriched.posterUrl.ifBlank { res.posterUrl }
                                                showForm = true
                                            }
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PosterThumbnail(
                                        posterUrl = res.posterUrl,
                                        title = res.title,
                                        modifier = Modifier
                                            .width(42.dp)
                                            .height(62.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(res.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${res.type}" + if (res.releaseYear > 0) " • ${res.releaseYear}" else "",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (showForm) {
                        HorizontalDivider()
                        if (draftPosterUrl.isNotBlank() || draftImdbRating != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PosterThumbnail(
                                    posterUrl = draftPosterUrl,
                                    title = draftTitle,
                                    modifier = Modifier
                                        .width(64.dp)
                                        .height(94.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    if (draftImdbRating != null) {
                                        Text("IMDb ★ $draftImdbRating", fontWeight = FontWeight.Bold)
                                    }
                                    if (draftGenres.isNotBlank()) {
                                        Text(draftGenres, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (draftPlot.isNotBlank()) {
                                        Text(draftPlot, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = draftTitle,
                            onValueChange = { draftTitle = it },
                            label = { Text("Title *") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Movie", "Series", "Documentary").forEach { t ->
                                FilterChip(
                                    selected = draftType == t,
                                    onClick = {
                                        draftType = t
                                        if (t == "Series" && draftSeason.isBlank()) draftSeason = "1"
                                    },
                                    label = { Text(t) }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draftYear,
                                onValueChange = { draftYear = it.filter { c -> c.isDigit() }.take(4) },
                                label = { Text("Year") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            if (draftType == "Series") {
                                OutlinedTextField(
                                    value = draftSeason,
                                    onValueChange = { draftSeason = it.filter { c -> c.isDigit() }.take(2) },
                                    label = { Text("Season") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            OutlinedTextField(
                                value = draftImdbId,
                                onValueChange = { draftImdbId = CsvAndBackupHelper.extractImdbId(it).ifBlank { it } },
                                label = { Text("IMDb ID") },
                                singleLine = true,
                                modifier = Modifier.weight(1.2f)
                            )
                        }

                        Text("Save as:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MovieStatus.entries.forEach { st ->
                                FilterChip(
                                    selected = draftStatus == st,
                                    onClick = {
                                        draftStatus = st
                                        if (st == MovieStatus.WATCHED && draftDate.isBlank()) {
                                            draftDate = CsvAndBackupHelper.todayIsoDate()
                                        }
                                    },
                                    label = { Text(st.label) }
                                )
                            }
                        }
                        if (draftStatus == MovieStatus.WATCHED) {
                            OutlinedTextField(
                                value = draftDate,
                                onValueChange = { draftDate = it },
                                label = { Text("Watched Date (YYYY-MM-DD)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        OutlinedTextField(
                            value = draftNotes,
                            onValueChange = { draftNotes = it },
                            label = { Text("Notes (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (showForm) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("Close") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            if (draftTitle.isBlank()) {
                                Toast.makeText(context, "Please enter a title", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onAddNew(
                                MovieItem(
                                    title = draftTitle.trim(),
                                    type = draftType,
                                    releaseYear = draftYear.toIntOrNull() ?: 0,
                                    imdbId = draftImdbId.trim(),
                                    imdbUrl = if (draftImdbId.isNotBlank()) "https://www.imdb.com/title/${draftImdbId.trim()}/" else "",
                                    seasonNumber = if (draftType == "Series") (draftSeason.toIntOrNull() ?: 1) else null,
                                    status = draftStatus,
                                    watchedDate = if (draftStatus == MovieStatus.WATCHED) draftDate.trim() else "",
                                    rewatchCount = if (draftStatus == MovieStatus.WATCHED) 1 else 0,
                                    imdbRating = draftImdbRating,
                                    genres = draftGenres,
                                    plot = draftPlot,
                                    posterUrl = draftPosterUrl,
                                    notes = draftNotes.trim()
                                )
                            )
                        }) {
                            Icon(AppIcons.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsAndBackupDialog(
    db: MovieDatabaseHelper,
    prefs: android.content.SharedPreferences,
    omdbKey: String,
    tmdbKey: String,
    autoBackupEnabled: Boolean,
    autoBackupUri: String,
    autoCheckNewSeasons: Boolean,
    onSaveKeys: (String, String) -> Unit,
    onToggleAutoCheckSeasons: (Boolean) -> Unit,
    onUpdateAutoBackup: (Boolean, String) -> Unit,
    onDataChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var omdbInput by remember { mutableStateOf(omdbKey) }
    var tmdbInput by remember { mutableStateOf(tmdbKey) }
    var mergeDuplicatesOnImport by remember { mutableStateOf(true) }
    var statusBanner by remember { mutableStateOf("") }

    var traktClientId by remember { mutableStateOf(prefs.getString("trakt_client_id", "") ?: "") }
    var traktClientSecret by remember { mutableStateOf(prefs.getString("trakt_client_secret", "") ?: "") }
    var traktAccessToken by remember { mutableStateOf(prefs.getString("trakt_access_token", "") ?: "") }
    var traktRefreshToken by remember { mutableStateOf(prefs.getString("trakt_refresh_token", "") ?: "") }
    var traktExpiresAt by remember { mutableLongStateOf(prefs.getLong("trakt_expires_at", 0L)) }
    var pendingDeviceCode by remember { mutableStateOf<TraktDeviceCode?>(null) }
    var traktStatusText by remember { mutableStateOf("") }
    var traktBusy by remember { mutableStateOf(false) }

    var seasonCheckJob by remember { mutableStateOf<Job?>(null) }
    var seasonCheckProgress by remember { mutableStateOf("") }

    var enrichJob by remember { mutableStateOf<Job?>(null) }
    var enrichProgress by remember { mutableStateOf("") }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            onUpdateAutoBackup(true, uri.toString())
            statusBanner = "Auto-backup folder set!"
        } catch (e: Exception) {
            statusBanner = "Folder permission error: ${e.message}"
        }
    }

    val csvPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val parsed = context.contentResolver.openInputStream(uri)?.use {
                    CsvAndBackupHelper.parseGoogleSheetsCsv(it)
                } ?: emptyList()
                val summary = db.bulkImport(parsed, mergeDuplicates = mergeDuplicatesOnImport)
                withContext(Dispatchers.Main) {
                    statusBanner = "CSV imported: ${summary.added} added, ${summary.mergedOrSkipped} merged (${summary.totalParsed} rows)."
                    onDataChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusBanner = "CSV error: ${e.message}"
                }
            }
        }
    }

    val jsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val all = db.getAllMovies()
                val text = CsvAndBackupHelper.exportToJson(all)
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    statusBanner = "Backed up ${all.size} entries to JSON!"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusBanner = "Backup error: ${e.message}" }
            }
        }
    }

    val jsonImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val list = CsvAndBackupHelper.parseJsonBackup(text)
                val summary = db.bulkImport(list, mergeDuplicates = true)
                withContext(Dispatchers.Main) {
                    statusBanner = "Restored JSON: ${summary.added} added, ${summary.mergedOrSkipped} updated."
                    onDataChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusBanner = "JSON error: ${e.message}" }
            }
        }
    }

    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val all = db.getAllMovies()
                val text = CsvAndBackupHelper.exportToCsv(all)
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    statusBanner = "Exported ${all.size} entries to CSV!"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusBanner = "Export error: ${e.message}" }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            enrichJob?.cancel()
            seasonCheckJob?.cancel()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Settings & Data", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            enrichJob?.cancel()
                            seasonCheckJob?.cancel()
                            onDismiss()
                        }) { Icon(AppIcons.Close, null) }
                    }
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (statusBanner.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(statusBanner, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // 1. Trakt.tv 1-Way Sync
                    Text("1. Trakt.tv Sync (1-Way Pull)", fontWeight = FontWeight.Bold)
                    Text(
                        "Create a personal app at trakt.tv/oauth/applications/new (Redirect URI: urn:ietf:wg:oauth:2.0:oob). New PKCE apps only need Client ID.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = traktClientId,
                        onValueChange = { traktClientId = it },
                        label = { Text("Trakt Client ID *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = traktClientSecret,
                        onValueChange = { traktClientSecret = it },
                        label = { Text("Trakt Client Secret (optional - leave blank)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (traktStatusText.isNotBlank()) {
                        Text(traktStatusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
                    }

                    if (pendingDeviceCode != null) {
                        val dc = pendingDeviceCode!!
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Open trakt.tv/activate and enter code:", fontSize = 12.sp)
                                Text(dc.userCode, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUrl)))
                                }) {
                                    Text("Open trakt.tv/activate")
                                }
                            }
                        }
                    }

                    if (traktAccessToken.isBlank()) {
                        Button(
                            enabled = !traktBusy,
                            onClick = {
                                if (traktClientId.isBlank()) {
                                    traktStatusText = "Enter Trakt Client ID first."
                                    return@Button
                                }
                                prefs.edit()
                                    .putString("trakt_client_id", traktClientId.trim())
                                    .putString("trakt_client_secret", traktClientSecret.trim())
                                    .apply()
                                traktBusy = true
                                traktStatusText = "Requesting device code..."
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val dc = TraktClient.requestDeviceCode(traktClientId)
                                        withContext(Dispatchers.Main) {
                                            pendingDeviceCode = dc
                                            traktStatusText = "Waiting for approval on trakt.tv/activate..."
                                        }
                                        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
                                        var tokens: TraktTokens? = null
                                        while (System.currentTimeMillis() < deadline && tokens == null) {
                                            delay(dc.intervalSec.coerceAtLeast(5) * 1000L)
                                            tokens = TraktClient.pollDeviceToken(traktClientId, traktClientSecret, dc.deviceCode)
                                        }
                                        withContext(Dispatchers.Main) {
                                            traktBusy = false
                                            pendingDeviceCode = null
                                            if (tokens != null) {
                                                traktAccessToken = tokens.accessToken
                                                traktRefreshToken = tokens.refreshToken
                                                traktExpiresAt = tokens.expiresAtMillis
                                                prefs.edit()
                                                    .putString("trakt_access_token", tokens.accessToken)
                                                    .putString("trakt_refresh_token", tokens.refreshToken)
                                                    .putLong("trakt_expires_at", tokens.expiresAtMillis)
                                                    .apply()
                                                traktStatusText = "Connected to Trakt!"
                                            } else {
                                                traktStatusText = "Timed out. Try connecting again."
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            traktBusy = false
                                            pendingDeviceCode = null
                                            traktStatusText = "Trakt error: ${e.message}"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (traktBusy) "Connecting..." else "Connect Trakt")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !traktBusy,
                                onClick = {
                                    traktBusy = true
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            var token = traktAccessToken
                                            if (System.currentTimeMillis() > traktExpiresAt - 300_000L && traktRefreshToken.isNotBlank()) {
                                                withContext(Dispatchers.Main) { traktStatusText = "Refreshing Trakt token..." }
                                                val refreshed = TraktClient.refreshAccessToken(traktClientId, traktClientSecret, traktRefreshToken)
                                                token = refreshed.accessToken
                                                traktAccessToken = refreshed.accessToken
                                                traktRefreshToken = refreshed.refreshToken
                                                traktExpiresAt = refreshed.expiresAtMillis
                                                prefs.edit()
                                                    .putString("trakt_access_token", refreshed.accessToken)
                                                    .putString("trakt_refresh_token", refreshed.refreshToken)
                                                    .putLong("trakt_expires_at", refreshed.expiresAtMillis)
                                                    .apply()
                                            }
                                            val sum = TraktClient.pullOneWaySync(traktClientId, token, db) { msg ->
                                                scope.launch(Dispatchers.Main) { traktStatusText = msg }
                                            }
                                            withContext(Dispatchers.Main) {
                                                traktBusy = false
                                                traktStatusText = "Synced! Movies: +${sum.moviesAdded}/${sum.moviesUpdated}upd • Seasons: +${sum.seasonsAdded}/${sum.seasonsUpdated}upd • Watchlist: +${sum.watchlistAdded} • Ratings: ${sum.ratingsUpdated}"
                                                onDataChanged()
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                traktBusy = false
                                                traktStatusText = "Sync error: ${e.message}"
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (traktBusy) "Syncing..." else "Sync from Trakt Now")
                            }
                            OutlinedButton(
                                enabled = !traktBusy,
                                onClick = {
                                    traktAccessToken = ""
                                    traktRefreshToken = ""
                                    traktExpiresAt = 0L
                                    prefs.edit()
                                        .remove("trakt_access_token")
                                        .remove("trakt_refresh_token")
                                        .remove("trakt_expires_at")
                                        .apply()
                                    traktStatusText = "Disconnected."
                                }
                            ) { Text("Disconnect") }
                        }
                    }

                    HorizontalDivider()

                    // 2. OMDb & TMDB API Keys
                    Text("2. Free API Keys (IMDb Ratings & Posters)", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = omdbInput,
                        onValueChange = { omdbInput = it },
                        label = { Text("OMDb API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tmdbInput,
                        onValueChange = { tmdbInput = it },
                        label = { Text("TMDB API Key / Read Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onSaveKeys(omdbInput, tmdbInput) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save API Keys")
                    }

                    HorizontalDivider()

                    // 3. Auto-Check New Seasons
                    Text("3. New Seasons Tracker (TMDB)", fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-check new aired seasons daily", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Skips Dropped & Ended series. Adds new aired seasons to Watchlist.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoCheckNewSeasons,
                            onCheckedChange = onToggleAutoCheckSeasons
                        )
                    }
                    if (seasonCheckProgress.isNotBlank()) {
                        Text(seasonCheckProgress, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                    }
                    if (seasonCheckJob == null) {
                        FilledTonalButton(
                            onClick = {
                                val key = tmdbInput.trim()
                                if (key.isBlank()) {
                                    statusBanner = "Please enter and save your TMDB API Key first."
                                    return@FilledTonalButton
                                }
                                seasonCheckJob = scope.launch(Dispatchers.IO) {
                                    val sum = runNewSeasonsCheck(db, key) { msg ->
                                        seasonCheckProgress = msg
                                    }
                                    prefs.edit().putLong("last_new_season_check", System.currentTimeMillis()).apply()
                                    withContext(Dispatchers.Main) {
                                        seasonCheckJob = null
                                        val addedTxt = if (sum.addedLabels.isNotEmpty()) {
                                            "Added ${sum.addedLabels.size}: ${sum.addedLabels.take(4).joinToString(", ")}"
                                        } else {
                                            "No new aired seasons found"
                                        }
                                        seasonCheckProgress = "Checked ${sum.checkedSeries} active series • $addedTxt • Marked ${sum.markedEnded} ended."
                                        if (sum.addedLabels.isNotEmpty()) onDataChanged()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Check New Seasons Now")
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                seasonCheckJob?.cancel()
                                seasonCheckJob = null
                                seasonCheckProgress = "Stopped."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Stop Checking") }
                    }

                    HorizontalDivider()

                    // 4. Import Google Sheets CSV
                    Text("4. Import Google Sheets CSV", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = mergeDuplicatesOnImport,
                            onCheckedChange = { mergeDuplicatesOnImport = it }
                        )
                        Text("Merge duplicate IMDb ID + Season", style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(
                        onClick = { csvPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Select CSV File")
                    }

                    HorizontalDivider()

                    // 5. Batch Fetch
                    Text("5. Fetch Missing Ratings & Posters", fontWeight = FontWeight.Bold)
                    if (enrichProgress.isNotBlank()) {
                        Text(enrichProgress, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (enrichJob == null) {
                        FilledTonalButton(
                            onClick = {
                                if (omdbInput.isBlank() && tmdbInput.isBlank()) {
                                    statusBanner = "Please enter and save at least an OMDb API Key first."
                                    return@FilledTonalButton
                                }
                                enrichJob = scope.launch(Dispatchers.IO) {
                                    val targets = db.getMoviesNeedingEnrich(100)
                                    var updatedCount = 0
                                    targets.forEachIndexed { idx, item ->
                                        withContext(Dispatchers.Main) {
                                            enrichProgress = "Fetching ${idx + 1}/${targets.size}: ${item.title}"
                                        }
                                        val res = MediaApiClient.enrichFromApis(
                                            title = item.title,
                                            year = item.releaseYear,
                                            imdbId = item.imdbId,
                                            isSeries = item.type == "Series",
                                            tmdbId = null,
                                            omdbKey = omdbInput.trim(),
                                            tmdbKey = tmdbInput.trim()
                                        )
                                        if (res != null) {
                                            db.updateMovie(
                                                item.copy(
                                                    imdbId = item.imdbId.ifBlank { res.imdbId },
                                                    imdbRating = item.imdbRating ?: res.imdbRating,
                                                    genres = item.genres.ifBlank { res.genres },
                                                    plot = item.plot.ifBlank { res.plot },
                                                    posterUrl = item.posterUrl.ifBlank { res.posterUrl }
                                                )
                                            )
                                            updatedCount++
                                        }
                                        delay(150)
                                    }
                                    withContext(Dispatchers.Main) {
                                        enrichProgress = "Done: updated $updatedCount/${targets.size} entries!"
                                        enrichJob = null
                                        onDataChanged()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Fetch Next 100 Entries")
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                enrichJob?.cancel()
                                enrichJob = null
                                enrichProgress = "Stopped."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Stop")
                        }
                    }

                    HorizontalDivider()

                    // 6. Auto-Backup & Google Drive
                    Text("6. Auto-Backup & Google Drive", fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-backup to folder on change", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (autoBackupUri.isNotBlank()) "Folder selected (writes MovieTracker_AutoBackup.json)" else "No folder selected yet",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoBackupEnabled,
                            onCheckedChange = { checked ->
                                if (checked && autoBackupUri.isBlank()) {
                                    folderPickerLauncher.launch(null)
                                } else {
                                    onUpdateAutoBackup(checked, autoBackupUri)
                                }
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Pick Folder") }

                        Button(
                            onClick = { shareBackupFileToDrive(context, db) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Send to Drive") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                jsonExportLauncher.launch("MovieTracker_Backup_${CsvAndBackupHelper.todayIsoDate()}.json")
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Backup JSON") }

                        OutlinedButton(
                            onClick = { jsonImportLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Restore JSON") }
                    }
                    OutlinedButton(
                        onClick = {
                            csvExportLauncher.launch("MovieTracker_Export_${CsvAndBackupHelper.todayIsoDate()}.csv")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export to CSV")
                    }
                }
            }
        }
    }
}
