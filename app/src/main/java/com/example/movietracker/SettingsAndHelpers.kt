@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.movietracker

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

internal val TraktBg = Color(0xFF0D0D12)
internal val TraktSurface = Color(0xFF171820)
internal val TraktSurfaceVar = Color(0xFF232531)
internal val TraktPurple = Color(0xFF8B5CF6)
internal val ImdbGold = Color(0xFFF5C518)
internal val WatchedGreen = Color(0xFF22C55E)
internal val WatchlistBlue = Color(0xFF3B82F6)
internal val DroppedRed = Color(0xFFEF4444)

data class NewSeasonCheckSummary(
    val checkedSeries: Int,
    val addedLabels: List<String>,
    val markedEnded: Int
)

internal suspend fun runNewSeasonsCheck(
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

internal fun executeTraktPullWithRefresh(
    prefs: SharedPreferences,
    db: MovieDatabaseHelper,
    onStatus: (String) -> Unit = {}
): TraktSyncSummary {
    val clientId = prefs.getString("trakt_client_id", "") ?: ""
    val clientSecret = prefs.getString("trakt_client_secret", "") ?: ""
    var accessToken = prefs.getString("trakt_access_token", "") ?: ""
    val refreshToken = prefs.getString("trakt_refresh_token", "") ?: ""
    val expiresAt = prefs.getLong("trakt_expires_at", 0L)

    if (clientId.isBlank() || accessToken.isBlank()) {
        throw IllegalStateException("Trakt not connected.")
    }
    if (System.currentTimeMillis() > expiresAt - 300_000L && refreshToken.isNotBlank()) {
        onStatus("Refreshing Trakt token...")
        val refreshed = TraktClient.refreshAccessToken(clientId, clientSecret, refreshToken)
        accessToken = refreshed.accessToken
        prefs.edit()
            .putString("trakt_access_token", refreshed.accessToken)
            .putString("trakt_refresh_token", refreshed.refreshToken)
            .putLong("trakt_expires_at", refreshed.expiresAtMillis)
            .apply()
    }
    val summary = TraktClient.pullOneWaySync(clientId, accessToken, db, onStatus)
    prefs.edit().putLong("last_trakt_sync", System.currentTimeMillis()).apply()
    return summary
}

internal fun writeAutoBackupToFolder(context: Context, db: MovieDatabaseHelper, folderUriStr: String): Boolean {
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

internal fun shareBackupFileToDrive(context: Context, db: MovieDatabaseHelper) {
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
            modifier = modifier.background(TraktSurfaceVar),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Movie,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.25f),
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
fun SettingsTabScreen(
    db: MovieDatabaseHelper,
    prefs: SharedPreferences,
    omdbKey: String,
    tmdbKey: String,
    autoBackupEnabled: Boolean,
    autoBackupUri: String,
    autoCheckNewSeasons: Boolean,
    autoTraktSync: Boolean,
    onSaveKeys: (String, String) -> Unit,
    onToggleAutoCheckSeasons: (Boolean) -> Unit,
    onToggleAutoTraktSync: (Boolean) -> Unit,
    onUpdateAutoBackup: (Boolean, String) -> Unit,
    onDataChanged: () -> Unit
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
                withContext(Dispatchers.Main) { statusBanner = "CSV error: ${e.message}" }
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
                withContext(Dispatchers.Main) { statusBanner = "Backed up ${all.size} entries to JSON!" }
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
                withContext(Dispatchers.Main) { statusBanner = "Exported ${all.size} entries to CSV!" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusBanner = "Export error: ${e.message}" }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings & Data", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)

        if (statusBanner.isNotBlank()) {
            Surface(
                color = TraktPurple.copy(alpha = 0.18f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(statusBanner, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        // 1. Trakt.tv 1-Way Sync
        Text("1. Trakt.tv Sync (1-Way Pull)", fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Create a personal app at trakt.tv/oauth/applications/new (Redirect URI: urn:ietf:wg:oauth:2.0:oob). New PKCE apps only need Client ID.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-sync Trakt on app open", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("Syncs silently every 3 hours when connected.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = autoTraktSync, onCheckedChange = onToggleAutoTraktSync)
        }
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
            Text(traktStatusText, fontSize = 12.sp, color = TraktPurple, fontWeight = FontWeight.Medium)
        }

        if (pendingDeviceCode != null) {
            val dc = pendingDeviceCode!!
            Surface(
                color = TraktPurple.copy(alpha = 0.16f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Open trakt.tv/activate and enter code:", fontSize = 12.sp)
                    Text(dc.userCode, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUrl)))
                    }) { Text("Open trakt.tv/activate") }
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
            ) { Text(if (traktBusy) "Connecting..." else "Connect Trakt") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !traktBusy,
                    onClick = {
                        traktBusy = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val sum = executeTraktPullWithRefresh(prefs, db) { msg ->
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
                ) { Text(if (traktBusy) "Syncing..." else "Sync from Trakt Now") }
                OutlinedButton(
                    enabled = !traktBusy,
                    onClick = {
                        traktAccessToken = ""
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
        Text("2. Free API Keys (IMDb Ratings & Posters)", fontWeight = FontWeight.Bold, color = Color.White)
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
        Button(onClick = { onSaveKeys(omdbInput, tmdbInput) }, modifier = Modifier.fillMaxWidth()) {
            Text("Save API Keys")
        }

        HorizontalDivider()

        // 3. New Seasons Tracker
        Text("3. New Seasons Tracker (TMDB)", fontWeight = FontWeight.Bold, color = Color.White)
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
            Switch(checked = autoCheckNewSeasons, onCheckedChange = onToggleAutoCheckSeasons)
        }
        if (seasonCheckProgress.isNotBlank()) {
            Text(seasonCheckProgress, fontSize = 12.sp, color = TraktPurple, fontWeight = FontWeight.SemiBold)
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
                        val sum = runNewSeasonsCheck(db, key) { msg -> seasonCheckProgress = msg }
                        prefs.edit().putLong("last_new_season_check", System.currentTimeMillis()).apply()
                        withContext(Dispatchers.Main) {
                            seasonCheckJob = null
                            val addedTxt = if (sum.addedLabels.isNotEmpty()) {
                                "Added ${sum.addedLabels.size}: ${sum.addedLabels.take(4).joinToString(", ")}"
                            } else "No new aired seasons found"
                            seasonCheckProgress = "Checked ${sum.checkedSeries} active series • $addedTxt • Marked ${sum.markedEnded} ended."
                            if (sum.addedLabels.isNotEmpty()) onDataChanged()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Check New Seasons Now") }
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

        // 4. Import CSV
        Text("4. Import Google Sheets CSV", fontWeight = FontWeight.Bold, color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = mergeDuplicatesOnImport, onCheckedChange = { mergeDuplicatesOnImport = it })
            Text("Merge duplicate IMDb ID + Season", style = MaterialTheme.typography.bodySmall)
        }
        FilledTonalButton(
            onClick = { csvPickerLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Select CSV File") }

        HorizontalDivider()

        // 5. Batch Fetch
        Text("5. Fetch Missing Ratings & Posters", fontWeight = FontWeight.Bold, color = Color.White)
        if (enrichProgress.isNotBlank()) {
            Text(enrichProgress, fontWeight = FontWeight.SemiBold, color = TraktPurple)
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
            ) { Text("Fetch Next 100 Entries") }
        } else {
            OutlinedButton(
                onClick = {
                    enrichJob?.cancel()
                    enrichJob = null
                    enrichProgress = "Stopped."
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Stop") }
        }

        HorizontalDivider()

        // 6. Auto-Backup & Google Drive
        Text("6. Auto-Backup & Google Drive", fontWeight = FontWeight.Bold, color = Color.White)
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
            OutlinedButton(onClick = { folderPickerLauncher.launch(null) }, modifier = Modifier.weight(1f)) {
                Text("Pick Folder")
            }
            Button(onClick = { shareBackupFileToDrive(context, db) }, modifier = Modifier.weight(1f)) {
                Text("Send to Drive")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { jsonExportLauncher.launch("MovieTracker_Backup_${CsvAndBackupHelper.todayIsoDate()}.json") },
                modifier = Modifier.weight(1f)
            ) { Text("Backup JSON") }
            OutlinedButton(
                onClick = { jsonImportLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f)
            ) { Text("Restore JSON") }
        }
        OutlinedButton(
            onClick = { csvExportLauncher.launch("MovieTracker_Export_${CsvAndBackupHelper.todayIsoDate()}.csv") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export to CSV") }

        Spacer(Modifier.height(16.dp))
    }
}
