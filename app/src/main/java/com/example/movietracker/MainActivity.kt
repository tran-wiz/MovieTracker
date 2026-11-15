kotlin
@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.movietracker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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

    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<MovieStatus?>(null) }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var sortOption by remember { mutableStateOf(SortOption.UPDATED_DESC) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var movies by remember { mutableStateOf<List<MovieItem>>(emptyList()) }
    var stats by remember { mutableStateOf(MovieStats()) }

    var selectedMovie by remember { mutableStateOf<MovieItem?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addInitialQuery by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery, statusFilter, typeFilter, sortOption, refreshTrigger) {
        val loaded = withContext(Dispatchers.IO) {
            db.queryMovies(searchQuery, statusFilter, typeFilter, sortOption)
        }
        val st = withContext(Dispatchers.IO) { db.getStats() }
        movies = loaded
        stats = st
    }

    LaunchedEffect(incomingShareText) {
        if (!incomingShareText.isNullOrBlank()) {
            addInitialQuery = incomingShareText
            showAddDialog = true
            onConsumeIncomingShare()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MovieTracker", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "${stats.total} titles • ${stats.watched} watched • ${stats.watchlist} watchlist",
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
                        label = { Text("All (${stats.total})") }
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
                    "${movies.size} showing",
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

            if (movies.isEmpty()) {
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
                                "Tap Settings (top right) to import your Google Sheets CSV, or tap + to add a title."
                            } else {
                                "Try a different search query or clear your filters."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (stats.total == 0) {
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { showSettingsDialog = true }) {
                                Text("Import CSV")
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
                    items(movies, key = { it.id }) { movie ->
                        MovieRowCard(
                            movie = movie,
                            onClick = { selectedMovie = movie }
                        )
                    }
                }
            }
        }
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
                        refreshTrigger++
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDelete = { toDelete ->
                scope.launch(Dispatchers.IO) {
                    db.deleteMovie(toDelete.id)
                    withContext(Dispatchers.Main) {
                        selectedMovie = null
                        refreshTrigger++
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
                selectedMovie = existing
            },
            onAddNew = { newMovie ->
                scope.launch(Dispatchers.IO) {
                    db.insertMovie(newMovie)
                    withContext(Dispatchers.Main) {
                        showAddDialog = false
                        refreshTrigger++
                        Toast.makeText(context, "Added ${newMovie.displayTitle()}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showSettingsDialog) {
        SettingsAndBackupDialog(
            db = db,
            omdbKey = omdbKey,
            tmdbKey = tmdbKey,
            onSaveKeys = { newOmdb, newTmdb ->
                omdbKey = newOmdb.trim()
                tmdbKey = newTmdb.trim()
                prefs.edit()
                    .putString("omdb_key", omdbKey)
                    .putString("tmdb_key", tmdbKey)
                    .apply()
                Toast.makeText(context, "API Keys saved", Toast.LENGTH_SHORT).show()
            },
            onDataChanged = { refreshTrigger++ },
            onDismiss = { showSettingsDialog = false }
        )
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
    var notes by remember { mutableStateOf(movie.notes) }

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
                        IconButton(onClick = onDismiss) {
                            Icon(AppIcons.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(AppIcons.Delete, contentDescription = "Delete", tint = Color(0xFFE53935))
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
                                        onClick = {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        },
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
                                                    Toast.makeText(context, "Synced from IMDb/TMDB", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "No new info found", Toast.LENGTH_SHORT).show()
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
                                    onClick = { draftType = t },
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
                                    seasonNumber = draftSeason.toIntOrNull(),
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
    omdbKey: String,
    tmdbKey: String,
    onSaveKeys: (String, String) -> Unit,
    onDataChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var omdbInput by remember { mutableStateOf(omdbKey) }
    var tmdbInput by remember { mutableStateOf(tmdbKey) }
    var mergeDuplicatesOnImport by remember { mutableStateOf(true) }
    var statusBanner by remember { mutableStateOf("") }

    var enrichJob by remember { mutableStateOf<Job?>(null) }
    var enrichProgress by remember { mutableStateOf("") }

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
                    statusBanner = "Backed up ${all.size} titles to JSON!"
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
                    statusBanner = "Exported ${all.size} titles to CSV!"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusBanner = "Export error: ${e.message}" }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            enrichJob?.cancel()
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

                    Text("1. Free API Keys (IMDb Ratings & Posters)", fontWeight = FontWeight.Bold)
                    Text(
                        "• OMDb Key: free (1,000/day) at omdbapi.com/apikey.aspx\n• TMDB Key: free at themoviedb.org/settings/api",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

                    Text("2. Import Google Sheets CSV", fontWeight = FontWeight.Bold)
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

                    Text("3. Fetch Missing Ratings & Posters", fontWeight = FontWeight.Bold)
                    Text(
                        "Updates up to 100 titles per run (respects OMDb's 1,000/day free limit).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                        enrichProgress = "Done: updated $updatedCount/${targets.size} titles!"
                                        enrichJob = null
                                        onDataChanged()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Fetch Next 100 Titles")
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

                    Text("4. Backup & Export", fontWeight = FontWeight.Bold)
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
