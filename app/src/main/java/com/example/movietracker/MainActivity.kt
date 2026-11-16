@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.movietracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class AppTab(val label: String) {
    HOME("Home"),
    WATCHLIST("Watchlist"),
    LIBRARY("Library"),
    SEARCH("Discover"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {
    private val sharedIncomingText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
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
        setIntent(intent)
        configureAppCheck(this, intent)
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
    val scheme = darkColorScheme(
        primary = TraktPurple,
        onPrimary = Color.White,
        secondary = ImdbGold,
        onSecondary = Color(0xFF141100),
        background = TraktBg,
        onBackground = Color(0xFFF3F4F6),
        surface = TraktSurface,
        onSurface = Color(0xFFF3F4F6),
        surfaceVariant = TraktSurfaceVar,
        onSurfaceVariant = Color(0xFF9CA3AF)
    )
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

    var currentTab by remember { mutableStateOf(AppTab.HOME) }
    var omdbKey by remember { mutableStateOf(prefs.getString("omdb_key", "") ?: "") }
    var tmdbKey by remember { mutableStateOf(prefs.getString("tmdb_key", "") ?: "") }
    var autoBackupEnabled by remember { mutableStateOf(prefs.getBoolean("auto_backup_enabled", false)) }
    var autoBackupUri by remember { mutableStateOf(prefs.getString("auto_backup_uri", "") ?: "") }
    var autoCheckNewSeasons by remember { mutableStateOf(prefs.getBoolean("auto_check_new_seasons", true)) }
    var autoTraktSync by remember { mutableStateOf(prefs.getBoolean("auto_trakt_sync", true)) }

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var allMovies by remember { mutableStateOf<List<MovieItem>>(emptyList()) }
    var stats by remember { mutableStateOf(MovieStats()) }

    var sortOption by remember { mutableStateOf(SortOption.UPDATED_DESC) }
    var sortAscending by remember { mutableStateOf(false) }
    var isGridMode by remember { mutableStateOf(true) }
    var libraryStatusFilter by remember { mutableStateOf<MovieStatus?>(null) }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    var selectedMovie by remember { mutableStateOf<MovieItem?>(null) }
    var selectedSeriesKey by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addInitialQuery by remember { mutableStateOf("") }

    var hasRunStartupChecks by remember { mutableStateOf(false) }
    var homeSyncing by remember { mutableStateOf(false) }
    var discoverSubTab by remember { mutableIntStateOf(0) }
    var addingAiTitles by remember { mutableStateOf<Set<String>>(emptySet()) }
    val cinemaAiViewModel: CinemaAiViewModel = viewModel()

    fun triggerDataUpdated(runAutoBackup: Boolean = true) {
        refreshTrigger++
        if (runAutoBackup && autoBackupEnabled && autoBackupUri.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                writeAutoBackupToFolder(context, db, autoBackupUri)
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        val (loadedAll, st) = withContext(Dispatchers.IO) {
            db.getAllMovies() to db.getStats()
        }
        allMovies = loadedAll
        stats = st
    }

    // Tự động đồng bộ Trakt (mỗi 3 giờ) & kiểm tra mùa mới (mỗi 24 giờ) khi mở app
    LaunchedEffect(Unit) {
        if (hasRunStartupChecks) return@LaunchedEffect
        hasRunStartupChecks = true
        val now = System.currentTimeMillis()
        var changed = false

        val traktConnected = !prefs.getString("trakt_access_token", "").isNullOrBlank()
        val lastTrakt = prefs.getLong("last_trakt_sync", 0L)
        if (traktConnected && autoTraktSync && now - lastTrakt > 3L * 60L * 60L * 1000L) {
            homeSyncing = true
            val sum = withContext(Dispatchers.IO) {
                runCatching { executeTraktPullWithRefresh(prefs, db) }.getOrNull()
            }
            homeSyncing = false
            if (sum != null && sum.hasChanges()) {
                changed = true
                Toast.makeText(
                    context,
                    "Trakt synced: +${sum.moviesAdded + sum.seasonsAdded + sum.watchlistAdded} new, ${sum.moviesUpdated + sum.seasonsUpdated} updated",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val lastSeasonCheck = prefs.getLong("last_new_season_check", 0L)
        val hasData = withContext(Dispatchers.IO) { db.getStats().total > 0 }
        if (hasData && autoCheckNewSeasons && tmdbKey.isNotBlank() && now - lastSeasonCheck > 24L * 60L * 60L * 1000L) {
            val sum = withContext(Dispatchers.IO) {
                runCatching { runNewSeasonsCheck(db, tmdbKey) }.getOrNull()
            }
            prefs.edit().putLong("last_new_season_check", System.currentTimeMillis()).apply()
            if (sum != null && sum.addedLabels.isNotEmpty()) {
                changed = true
                val preview = sum.addedLabels.take(3).joinToString(", ")
                Toast.makeText(context, "New seasons added: $preview", Toast.LENGTH_LONG).show()
            }
        }

        if (changed) triggerDataUpdated()
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
        buildLibraryEntries(allMovies, "", null, "Series", SortOption.TITLE_ASC, true)
            .filterIsInstance<SeriesGroupEntry>()
            .firstOrNull { it.groupKey == key }
    }

    fun openEntry(entry: LibraryEntry) {
        when (entry) {
            is SingleLibraryEntry -> selectedMovie = entry.item
            is SeriesGroupEntry -> selectedSeriesKey = entry.groupKey
        }
    }

    Scaffold(
        containerColor = TraktBg,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF13141B),
                contentColor = Color.White,
                tonalElevation = 0.dp
            ) {
                val navItems = listOf(
                    AppTab.HOME to AppIcons.Home,
                    AppTab.WATCHLIST to AppIcons.Bookmark,
                    AppTab.LIBRARY to AppIcons.Library,
                    AppTab.SEARCH to AppIcons.Sparkles,
                    AppTab.SETTINGS to AppIcons.Settings
                )
                navItems.forEach { (tab, icon) ->
                    val selected = currentTab == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = tab },
                        icon = { Icon(icon, contentDescription = tab.label, modifier = Modifier.size(22.dp)) },
                        label = { Text(tab.label, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TraktPurple,
                            selectedTextColor = TraktPurple,
                            unselectedIconColor = Color(0xFF8E92A4),
                            unselectedTextColor = Color(0xFF8E92A4),
                            indicatorColor = TraktPurple.copy(alpha = 0.16f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AppTab.HOME -> HomeTabScreen(
                    allMovies = allMovies,
                    stats = stats,
                    traktConnected = !prefs.getString("trakt_access_token", "").isNullOrBlank(),
                    isSyncing = homeSyncing,
                    onManualTraktSync = {
                        if (homeSyncing) return@HomeTabScreen
                        homeSyncing = true
                        scope.launch(Dispatchers.IO) {
                            val res = runCatching { executeTraktPullWithRefresh(prefs, db) }
                            withContext(Dispatchers.Main) {
                                homeSyncing = false
                                res.onSuccess { sum ->
                                    triggerDataUpdated()
                                    val msg = if (sum.hasChanges()) {
                                        "Synced: +${sum.moviesAdded + sum.seasonsAdded + sum.watchlistAdded} new, ${sum.moviesUpdated + sum.seasonsUpdated} updated"
                                    } else "Already up to date with Trakt"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    Toast.makeText(context, "Sync error: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    onQuickAdd = {
                        addInitialQuery = ""
                        showAddDialog = true
                    },
                    onOpenEntry = ::openEntry,
                    onGoToSettings = { currentTab = AppTab.SETTINGS },
                    onGoToWatchlist = { currentTab = AppTab.WATCHLIST },
                    onOpenAiDiscover = {
                        discoverSubTab = 0
                        currentTab = AppTab.SEARCH
                    }
                )

                AppTab.WATCHLIST -> CollectionTabScreen(
                    title = "Watchlist",
                    allMovies = allMovies,
                    fixedStatus = MovieStatus.WATCHLIST,
                    libraryStatusFilter = null,
                    onLibraryStatusChange = {},
                    typeFilter = typeFilter,
                    onTypeFilterChange = { typeFilter = it },
                    sortOption = sortOption,
                    sortAscending = sortAscending,
                    onSortChange = { opt ->
                        sortOption = opt
                        sortAscending = opt.defaultAscending
                    },
                    onToggleSortDirection = { sortAscending = !sortAscending },
                    isGridMode = isGridMode,
                    onToggleGridMode = { isGridMode = !isGridMode },
                    onOpenEntry = ::openEntry,
                    onQuickAdd = {
                        addInitialQuery = ""
                        showAddDialog = true
                    }
                )

                AppTab.LIBRARY -> CollectionTabScreen(
                    title = "Library",
                    allMovies = allMovies,
                    fixedStatus = null,
                    libraryStatusFilter = libraryStatusFilter,
                    onLibraryStatusChange = { libraryStatusFilter = it },
                    typeFilter = typeFilter,
                    onTypeFilterChange = { typeFilter = it },
                    sortOption = sortOption,
                    sortAscending = sortAscending,
                    onSortChange = { opt ->
                        sortOption = opt
                        sortAscending = opt.defaultAscending
                    },
                    onToggleSortDirection = { sortAscending = !sortAscending },
                    isGridMode = isGridMode,
                    onToggleGridMode = { isGridMode = !isGridMode },
                    onOpenEntry = ::openEntry,
                    onQuickAdd = {
                        addInitialQuery = ""
                        showAddDialog = true
                    }
                )

                AppTab.SEARCH -> DiscoverAndSearchTabScreen(
                    subTabIndex = discoverSubTab,
                    onSubTabChange = { discoverSubTab = it },
                    cinemaAiViewModel = cinemaAiViewModel,
                    allMovies = allMovies,
                    omdbKey = omdbKey,
                    tmdbKey = tmdbKey,
                    addingAiTitles = addingAiTitles,
                    onAddAiSuggestionToWatchlist = { suggestion ->
                        val key = "${suggestion.title.lowercase(Locale.ROOT)}#${suggestion.releaseYear}"
                        if (key in addingAiTitles) return@DiscoverAndSearchTabScreen
                        addingAiTitles = addingAiTitles + key
                        scope.launch(Dispatchers.IO) {
                            val enriched = if ((omdbKey.isNotBlank() || tmdbKey.isNotBlank()) &&
                                (suggestion.posterUrl.isBlank() || suggestion.imdbId.isBlank())
                            ) {
                                runCatching {
                                    MediaApiClient.enrichFromApis(
                                        title = suggestion.title,
                                        year = suggestion.releaseYear,
                                        imdbId = suggestion.imdbId,
                                        isSeries = suggestion.type == "Series",
                                        tmdbId = suggestion.tmdbId,
                                        omdbKey = omdbKey,
                                        tmdbKey = tmdbKey
                                    )
                                }.getOrNull()
                            } else null

                            val finalImdbId = enriched?.imdbId?.ifBlank { null } ?: suggestion.imdbId
                            val newItem = MovieItem(
                                title = enriched?.title?.ifBlank { null } ?: suggestion.title,
                                type = enriched?.type ?: suggestion.type,
                                releaseYear = enriched?.releaseYear?.takeIf { it > 0 } ?: suggestion.releaseYear,
                                imdbId = finalImdbId,
                                imdbUrl = if (finalImdbId.isNotBlank()) "https://www.imdb.com/title/$finalImdbId/" else "",
                                seasonNumber = if ((enriched?.type ?: suggestion.type) == "Series") 1 else null,
                                status = MovieStatus.WATCHLIST,
                                imdbRating = enriched?.imdbRating ?: suggestion.imdbRating,
                                genres = enriched?.genres?.ifBlank { null } ?: suggestion.genres,
                                plot = enriched?.plot?.ifBlank { null } ?: suggestion.plot,
                                posterUrl = enriched?.posterUrl?.ifBlank { null } ?: suggestion.posterUrl,
                                notes = if (suggestion.reason.isNotBlank()) "AI Discover: ${suggestion.reason}" else ""
                            )
                            db.insertMovie(newItem)
                            withContext(Dispatchers.Main) {
                                addingAiTitles = addingAiTitles - key
                                triggerDataUpdated()
                                Toast.makeText(context, "Added \"${newItem.title}\" to Watchlist", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onOpenExistingMovie = { existing ->
                        if (existing.type.equals("Series", true)) {
                            selectedSeriesKey = existing.seriesGroupKey()
                        } else {
                            selectedMovie = existing
                        }
                    },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    typeFilter = typeFilter,
                    onTypeFilterChange = { typeFilter = it },
                    sortOption = sortOption,
                    sortAscending = sortAscending,
                    onOpenEntry = ::openEntry,
                    onSearchOnline = { query ->
                        addInitialQuery = query
                        showAddDialog = true
                    }
                )

                AppTab.SETTINGS -> SettingsTabScreen(
                    db = db,
                    prefs = prefs,
                    omdbKey = omdbKey,
                    tmdbKey = tmdbKey,
                    autoBackupEnabled = autoBackupEnabled,
                    autoBackupUri = autoBackupUri,
                    autoCheckNewSeasons = autoCheckNewSeasons,
                    autoTraktSync = autoTraktSync,
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
                    onToggleAutoTraktSync = { enabled ->
                        autoTraktSync = enabled
                        prefs.edit().putBoolean("auto_trakt_sync", enabled).apply()
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
                                        if (ok) "Auto-backup updated!" else "Failed to write to folder",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    onDataChanged = { triggerDataUpdated() }
                )
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
                    withContext(Dispatchers.Main) { triggerDataUpdated() }
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
                    for (ts in tmdbDetails?.seasons.orEmpty()) {
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
}

@Composable
fun HomeTabScreen(
    allMovies: List<MovieItem>,
    stats: MovieStats,
    traktConnected: Boolean,
    isSyncing: Boolean,
    onManualTraktSync: () -> Unit,
    onQuickAdd: () -> Unit,
    onOpenEntry: (LibraryEntry) -> Unit,
    onGoToSettings: () -> Unit,
    onGoToWatchlist: () -> Unit,
    onOpenAiDiscover: () -> Unit
) {
    val continueWatching = remember(allMovies) {
        buildLibraryEntries(allMovies, "", null, "Series", SortOption.UPDATED_DESC, false)
            .filterIsInstance<SeriesGroupEntry>()
            .filter { it.isCurrentlyWatching }
            .take(15)
    }
    val recentlyWatched = remember(allMovies) {
        buildLibraryEntries(allMovies, "", MovieStatus.WATCHED, null, SortOption.WATCHED_DATE_DESC, false)
            .take(15)
    }
    val watchlistPicks = remember(allMovies) {
        buildLibraryEntries(allMovies, "", MovieStatus.WATCHLIST, null, SortOption.UPDATED_DESC, false)
            .take(15)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("MovieTracker", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text(
                        "${stats.watched} watched • ${stats.watchlist} watchlist • ${stats.total} total",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (traktConnected) {
                        IconButton(enabled = !isSyncing, onClick = onManualTraktSync) {
                            Icon(AppIcons.Refresh, "Sync Trakt", tint = if (isSyncing) TraktPurple else Color.White)
                        }
                    }
                    IconButton(onClick = onQuickAdd) {
                        Icon(AppIcons.Add, "Add Title", tint = Color.White)
                    }
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                TraktPurple.copy(alpha = 0.28f),
                                WatchlistBlue.copy(alpha = 0.16f),
                                TraktSurface
                            )
                        )
                    )
                    .border(1.dp, TraktPurple.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenAiDiscover)
                    .padding(16.dp)
                    .testTag("home_ai_discover_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Sparkles, null, tint = ImdbGold, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("For My Taste", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Personalized picks from your ratings, library & current trends.",
                            fontSize = 12.sp,
                            color = Color(0xFFD1D5DB)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    FilledTonalButton(
                        onClick = onOpenAiDiscover,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = TraktPurple.copy(alpha = 0.3f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("See Picks", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (allMovies.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = TraktSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Welcome to MovieTracker", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Import your Google Sheets CSV, restore a JSON backup, or connect Trakt in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = onGoToSettings) { Text("Go to Settings") }
                    }
                }
            }
        } else {
            if (continueWatching.isNotEmpty()) {
                item {
                    SectionHeader("Continue Watching")
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(continueWatching, key = { "cw_${it.groupKey}" }) { group ->
                            val badge = when {
                                group.activeWatchingNote.isNotBlank() -> group.activeWatchingNote
                                group.nextWatchlistSeason != null -> "Next: S${group.nextWatchlistSeason.toString().padStart(2, '0')}"
                                else -> "${group.watchedCount}/${group.seasons.size}"
                            }
                            HomePosterCard(group, badge, TraktPurple) { onOpenEntry(group) }
                        }
                    }
                }
            }

            if (recentlyWatched.isNotEmpty()) {
                item {
                    SectionHeader("Recently Watched")
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(recentlyWatched, key = {
                            when (it) {
                                is SingleLibraryEntry -> "rw_m_${it.item.id}"
                                is SeriesGroupEntry -> "rw_s_${it.groupKey}"
                            }
                        }) { entry ->
                            val sub = entry.sortWatchedDate.ifBlank {
                                if (entry is SeriesGroupEntry) "${entry.watchedCount}/${entry.seasons.size} seasons" else "Watched"
                            }
                            HomePosterCard(entry, sub, WatchedGreen) { onOpenEntry(entry) }
                        }
                    }
                }
            }

            if (watchlistPicks.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Watchlist", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        TextButton(onClick = onGoToWatchlist) { Text("See all", fontSize = 12.sp) }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(watchlistPicks, key = {
                            when (it) {
                                is SingleLibraryEntry -> "wl_m_${it.item.id}"
                                is SeriesGroupEntry -> "wl_s_${it.groupKey}"
                            }
                        }) { entry ->
                            val sub = when (entry) {
                                is SingleLibraryEntry -> if (entry.item.releaseYear > 0) "${entry.item.releaseYear}" else entry.item.type
                                is SeriesGroupEntry -> "${entry.seasons.size} seasons"
                            }
                            HomePosterCard(entry, sub, WatchlistBlue) { onOpenEntry(entry) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun HomePosterCard(
    entry: LibraryEntry,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(116.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.67f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            PosterThumbnail(entry.entryPosterUrl, entry.entryTitle, Modifier.fillMaxSize())
            if (entry.sortImdbRating != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(AppIcons.Star, null, tint = ImdbGold, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(
                            String.format(Locale.US, "%.1f", entry.sortImdbRating),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = entry.entryTitle,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = accentColor,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun CollectionTabScreen(
    title: String,
    allMovies: List<MovieItem>,
    fixedStatus: MovieStatus?,
    libraryStatusFilter: MovieStatus?,
    onLibraryStatusChange: (MovieStatus?) -> Unit,
    typeFilter: String?,
    onTypeFilterChange: (String?) -> Unit,
    sortOption: SortOption,
    sortAscending: Boolean,
    onSortChange: (SortOption) -> Unit,
    onToggleSortDirection: () -> Unit,
    isGridMode: Boolean,
    onToggleGridMode: () -> Unit,
    onOpenEntry: (LibraryEntry) -> Unit,
    onQuickAdd: () -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
    val activeStatus = fixedStatus ?: libraryStatusFilter

    val entries = remember(allMovies, activeStatus, typeFilter, sortOption, sortAscending) {
        buildLibraryEntries(allMovies, "", activeStatus, typeFilter, sortOption, sortAscending)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleGridMode) {
                    Icon(if (isGridMode) AppIcons.ListView else AppIcons.GridView, "Toggle View", tint = Color.White)
                }
                IconButton(onClick = onQuickAdd) {
                    Icon(AppIcons.Add, "Add", tint = Color.White)
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (fixedStatus == null) {
                item {
                    FilterChip(selected = libraryStatusFilter == null, onClick = { onLibraryStatusChange(null) }, label = { Text("All") })
                }
                listOf(MovieStatus.WATCHED, MovieStatus.WATCHLIST, MovieStatus.DROPPED).forEach { st ->
                    item {
                        FilterChip(
                            selected = libraryStatusFilter == st,
                            onClick = { onLibraryStatusChange(if (libraryStatusFilter == st) null else st) },
                            label = { Text(st.label) }
                        )
                    }
                }
            }
            listOf("Movie", "Series", "Documentary").forEach { t ->
                item {
                    FilterChip(
                        selected = typeFilter == t,
                        onClick = { onTypeFilterChange(if (typeFilter == t) null else t) },
                        label = { Text(if (t == "Documentary") "Doc" else t) }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${entries.size} titles", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { showSortMenu = true }) {
                        Text("Sort: ${sortOption.label}", fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        SortOption.entries.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt.label) },
                                onClick = {
                                    onSortChange(opt)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = onToggleSortDirection, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (sortAscending) AppIcons.ArrowUp else AppIcons.ArrowDown,
                        contentDescription = if (sortAscending) "Ascending" else "Descending",
                        tint = TraktPurple,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No titles here yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (isGridMode) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = entries,
                    key = {
                        when (it) {
                            is SingleLibraryEntry -> "g_m_${it.item.id}"
                            is SeriesGroupEntry -> "g_s_${it.groupKey}"
                        }
                    }
                ) { entry ->
                    TraktPosterGridTile(entry = entry, onClick = { onOpenEntry(entry) })
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = entries,
                    key = {
                        when (it) {
                            is SingleLibraryEntry -> "l_m_${it.item.id}"
                            is SeriesGroupEntry -> "l_s_${it.groupKey}"
                        }
                    }
                ) { entry ->
                    when (entry) {
                        is SingleLibraryEntry -> MovieRowCard(movie = entry.item, onClick = { onOpenEntry(entry) })
                        is SeriesGroupEntry -> SeriesGroupCard(group = entry, onClick = { onOpenEntry(entry) })
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoverAndSearchTabScreen(
    subTabIndex: Int,
    onSubTabChange: (Int) -> Unit,
    cinemaAiViewModel: CinemaAiViewModel,
    allMovies: List<MovieItem>,
    omdbKey: String,
    tmdbKey: String,
    addingAiTitles: Set<String>,
    onAddAiSuggestionToWatchlist: (AiMovieSuggestion) -> Unit,
    onOpenExistingMovie: (MovieItem) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    typeFilter: String?,
    onTypeFilterChange: (String?) -> Unit,
    sortOption: SortOption,
    sortAscending: Boolean,
    onOpenEntry: (LibraryEntry) -> Unit,
    onSearchOnline: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = subTabIndex == 0,
                onClick = { onSubTabChange(0) },
                leadingIcon = {
                    Icon(AppIcons.Sparkles, null, modifier = Modifier.size(16.dp))
                },
                label = { Text("For My Taste", fontWeight = FontWeight.SemiBold) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("discover_subtab_ai")
            )
            FilterChip(
                selected = subTabIndex == 1,
                onClick = { onSubTabChange(1) },
                leadingIcon = {
                    Icon(AppIcons.Search, null, modifier = Modifier.size(16.dp))
                },
                label = { Text("Library & Web", fontWeight = FontWeight.SemiBold) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("discover_subtab_search")
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (subTabIndex == 0) {
                CinemaAiDiscoverScreen(
                    viewModel = cinemaAiViewModel,
                    allMovies = allMovies,
                    omdbKey = omdbKey,
                    tmdbKey = tmdbKey,
                    addingTitles = addingAiTitles,
                    onAddToWatchlist = onAddAiSuggestionToWatchlist,
                    onOpenAddDialogWithTitle = onSearchOnline,
                    onOpenExistingEntry = onOpenExistingMovie
                )
            } else {
                SearchTabScreen(
                    allMovies = allMovies,
                    searchQuery = searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    typeFilter = typeFilter,
                    onTypeFilterChange = onTypeFilterChange,
                    sortOption = sortOption,
                    sortAscending = sortAscending,
                    onOpenEntry = onOpenEntry,
                    onSearchOnline = onSearchOnline
                )
            }
        }
    }
}

@Composable
fun SearchTabScreen(
    allMovies: List<MovieItem>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    typeFilter: String?,
    onTypeFilterChange: (String?) -> Unit,
    sortOption: SortOption,
    sortAscending: Boolean,
    onOpenEntry: (LibraryEntry) -> Unit,
    onSearchOnline: (String) -> Unit
) {
    val entries = remember(allMovies, searchQuery, typeFilter, sortOption, sortAscending) {
        buildLibraryEntries(allMovies, searchQuery, null, typeFilter, sortOption, sortAscending)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (searchQuery.isBlank()) "Your library is empty" else "No match in your library",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        if (searchQuery.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { onSearchOnline(searchQuery) }) {
                                Text("Search TMDB / IMDb for \"${searchQuery.trim()}\"")
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = entries,
                        key = {
                            when (it) {
                                is SingleLibraryEntry -> "s_m_${it.item.id}"
                                is SeriesGroupEntry -> "s_s_${it.groupKey}"
                            }
                        }
                    ) { entry ->
                        TraktPosterGridTile(entry = entry, onClick = { onOpenEntry(entry) })
                    }
                }
            }
        }

        Surface(
            color = Color(0xFF15161E),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (searchQuery.isNotBlank()) {
                    FilledTonalButton(
                        onClick = { onSearchOnline(searchQuery) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = TraktPurple.copy(alpha = 0.22f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(AppIcons.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Search online & Add \"${searchQuery.trim()}\"", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = typeFilter == null,
                            onClick = { onTypeFilterChange(null) },
                            label = { Text("All (${entries.size})") }
                        )
                    }
                    listOf("Series" to "Series", "Movie" to "Movies", "Documentary" to "Docs").forEach { (value, label) ->
                        item {
                            FilterChip(
                                selected = typeFilter == value,
                                onClick = { onTypeFilterChange(if (typeFilter == value) null else value) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search shows & movies...") },
                    leadingIcon = { Icon(AppIcons.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) { Icon(AppIcons.Close, "Clear") }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (entries.isEmpty() && searchQuery.isNotBlank()) onSearchOnline(searchQuery)
                    })
                )
            }
        }
    }
}

@Composable
fun TraktPosterGridTile(entry: LibraryEntry, onClick: () -> Unit) {
    val isSeries = entry is SeriesGroupEntry
    val badgeIcon = when {
        entry.isCurrentlyWatching -> AppIcons.Play
        entry.entryStatus == MovieStatus.WATCHED -> AppIcons.Check
        entry.entryStatus == MovieStatus.WATCHLIST -> AppIcons.Bookmark
        else -> AppIcons.Close
    }
    val badgeColor = when {
        entry.isCurrentlyWatching -> TraktPurple
        entry.entryStatus == MovieStatus.WATCHED -> WatchedGreen
        entry.entryStatus == MovieStatus.WATCHLIST -> WatchlistBlue
        else -> DroppedRed
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.67f)
                .clip(RoundedCornerShape(10.dp))
                .background(TraktSurfaceVar)
        ) {
            PosterThumbnail(entry.entryPosterUrl, entry.entryTitle, Modifier.fillMaxSize())
            if (entry.entryPosterUrl.isBlank()) {
                Text(
                    text = entry.entryTitle,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 6.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xE614151C))
                    .border(1.2.dp, badgeColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(badgeIcon, null, tint = badgeColor, modifier = Modifier.size(13.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isSeries) AppIcons.Tv else AppIcons.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
            val meta = when (entry) {
                is SeriesGroupEntry -> {
                    val y = if (entry.releaseYear > 0) "${entry.releaseYear} • " else ""
                    "$y${entry.watchedCount}/${entry.seasons.size}s"
                }
                is SingleLibraryEntry -> {
                    val y = if (entry.item.releaseYear > 0) "${entry.item.releaseYear}" else entry.item.type
                    val r = entry.item.imdbRating?.let { " • ★${String.format(Locale.US, "%.1f", it)}" } ?: ""
                    "$y$r"
                }
            }
            Text(
                text = meta,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = entry.entryTitle,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SeriesGroupCard(group: SeriesGroupEntry, onClick: () -> Unit) {
    val totalSeasons = group.seasons.size
    val progressRatio = if (totalSeasons > 0) group.watchedCount.toFloat() / totalSeasons.toFloat() else 0f
    val pillColor = when {
        group.activeWatchingNote.isNotBlank() -> TraktPurple
        group.watchedCount == totalSeasons && totalSeasons > 0 -> WatchedGreen
        group.droppedCount == totalSeasons && totalSeasons > 0 -> DroppedRed
        else -> WatchlistBlue
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = TraktSurface)
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
                    color = WatchedGreen,
                    trackColor = TraktSurfaceVar
                )
                Spacer(Modifier.height(5.dp))
                val label = when {
                    group.activeWatchingNote.isNotBlank() -> "Watching: ${group.activeWatchingNote}"
                    group.watchedCount == totalSeasons && totalSeasons > 0 -> "Watched all ($totalSeasons/$totalSeasons)"
                    group.droppedCount == totalSeasons && totalSeasons > 0 -> "Dropped"
                    else -> "${group.watchedCount}/$totalSeasons watched"
                }
                Surface(color = pillColor.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
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
            if (group.imdbRating != null || group.avgPersonalRating != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    if (group.imdbRating != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Star, null, tint = ImdbGold, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(String.format(Locale.US, "%.1f", group.imdbRating), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    if (group.avgPersonalRating != null) {
                        Text(
                            text = "Me: ${String.format(Locale.US, "%.1f", group.avgPersonalRating)}",
                            fontSize = 11.sp,
                            color = TraktPurple,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MovieRowCard(movie: MovieItem, onClick: () -> Unit) {
    val statusColor = when (movie.status) {
        MovieStatus.WATCHED -> WatchedGreen
        MovieStatus.WATCHLIST -> WatchlistBlue
        MovieStatus.DROPPED -> DroppedRed
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = TraktSurface)
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
                    Surface(color = statusColor.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            text = dateLabel,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    if (movie.imdbId.isNotBlank()) {
                        Text(movie.imdbId, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Icon(AppIcons.Star, null, tint = ImdbGold, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(String.format(Locale.US, "%.1f", movie.imdbRating), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    if (movie.personalRating != null) {
                        Text(
                            text = "Me: ${String.format(Locale.US, "%.0f", movie.personalRating)}",
                            fontSize = 11.sp,
                            color = TraktPurple,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
