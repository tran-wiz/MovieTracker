@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.movietracker

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(18.dp),
            color = TraktSurface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(group.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                    .width(88.dp)
                                    .height(132.dp)
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
                                        Icon(AppIcons.Star, null, tint = ImdbGold, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("IMDb ${group.imdbRating} / 10", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                                if (group.genres.isNotBlank()) {
                                    Text(group.genres, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val imdbUrl = group.imdbUrl.ifBlank {
                                        if (group.imdbId.isNotBlank()) "https://www.imdb.com/title/${group.imdbId}/" else ""
                                    }
                                    if (imdbUrl.isNotBlank()) {
                                        item {
                                            OutlinedButton(
                                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(imdbUrl))) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("IMDb", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    item {
                                        OutlinedButton(
                                            onClick = {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(group.effectiveTraktUrl())))
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Trakt", fontSize = 12.sp)
                                        }
                                    }
                                    item {
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
                                            Icon(AppIcons.Refresh, null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (isSyncing) "..." else "Sync", fontSize = 12.sp)
                                        }
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
                            MovieStatus.WATCHED -> WatchedGreen
                            MovieStatus.WATCHLIST -> WatchlistBlue
                            MovieStatus.DROPPED -> DroppedRed
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSeason(season) },
                            colors = CardDefaults.cardColors(containerColor = TraktSurfaceVar)
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
                                                color = TraktPurple,
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
                                        Icon(AppIcons.Check, "Mark Watched", tint = WatchedGreen)
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
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(18.dp),
            color = TraktSurface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Details", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, "Close") }
                    },
                    actions = {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(AppIcons.Delete, "Delete", tint = DroppedRed)
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
                                    Icon(AppIcons.Star, null, tint = ImdbGold, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("IMDb $imdbRating / 10", fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (genres.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(genres, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val url = movie.effectiveImdbUrl().ifBlank {
                                    if (imdbId.isNotBlank()) "https://www.imdb.com/title/$imdbId/" else ""
                                }
                                if (url.isNotBlank()) {
                                    item {
                                        OutlinedButton(
                                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("IMDb", fontSize = 12.sp)
                                        }
                                    }
                                }
                                item {
                                    OutlinedButton(
                                        onClick = {
                                            val tUrl = movie.copy(imdbId = imdbId, title = title).effectiveTraktUrl()
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tUrl)))
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(AppIcons.OpenInNew, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Trakt", fontSize = 12.sp)
                                    }
                                }
                                item {
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
                                                            "Synced! IMDb: ${res.imdbRating} (tap Save)"
                                                        } else {
                                                            "Synced info — OMDb has no IMDb rating yet."
                                                        }
                                                    } else {
                                                        syncMessage = "No match found on OMDb or TMDB."
                                                    }
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(AppIcons.Refresh, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (isFetching) "..." else "Sync", fontSize = 12.sp)
                                    }
                                }
                            }
                            if (syncMessage.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(syncMessage, fontSize = 11.sp, color = TraktPurple)
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
                    Text("Delete", color = DroppedRed)
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
                                errorMsg = "No API key set. You can enter details manually below."
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
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(18.dp),
            color = TraktSurface
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
                            color = ImdbGold.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.Warning, null, tint = ImdbGold, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Already in your library!", fontWeight = FontWeight.Bold, color = ImdbGold)
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
                                        TextButton(onClick = { onOpenExisting(ex) }) { Text("Open") }
                                    }
                                }
                            }
                        }
                    }

                    if (errorMsg.isNotBlank()) {
                        Text(errorMsg, color = DroppedRed, style = MaterialTheme.typography.bodySmall)
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
                                colors = CardDefaults.cardColors(containerColor = TraktSurfaceVar)
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
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                        Text("IMDb ★ $draftImdbRating", fontWeight = FontWeight.Bold, color = ImdbGold)
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
