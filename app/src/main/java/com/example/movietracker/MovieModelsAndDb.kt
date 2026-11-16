package com.example.movietracker

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MovieStatus(val code: String, val label: String) {
    WATCHED("WATCHED", "Watched"),
    WATCHLIST("WATCHLIST", "Watchlist"),
    DROPPED("DROPPED", "Dropped");

    companion object {
        fun fromCode(code: String?): MovieStatus {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: WATCHLIST
        }
    }
}

enum class SortOption(val label: String, val defaultAscending: Boolean) {
    UPDATED_DESC("Updated", false),
    WATCHED_DATE_DESC("Watched Date", false),
    IMDB_RATING_DESC("IMDb Rating", false),
    PERSONAL_RATING_DESC("My Score", false),
    YEAR_DESC("Year", false),
    TITLE_ASC("Title", true)
}

data class MovieItem(
    val id: Long = 0L,
    val title: String,
    val type: String = "Movie",
    val releaseYear: Int = 0,
    val imdbId: String = "",
    val imdbUrl: String = "",
    val seasonNumber: Int? = null,
    val status: MovieStatus = MovieStatus.WATCHLIST,
    val watchedDate: String = "",
    val rewatchCount: Int = 0,
    val imdbRating: Double? = null,
    val personalRating: Double? = null,
    val genres: String = "",
    val plot: String = "",
    val posterUrl: String = "",
    val notes: String = "",
    val episodeProgress: String = "",
    val seriesEnded: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun displayTitle(): String {
        return if (seasonNumber != null && seasonNumber > 0) {
            "$title • S${seasonNumber.toString().padStart(2, '0')}"
        } else {
            title
        }
    }

    fun effectiveImdbUrl(): String {
        if (imdbUrl.isNotBlank()) return imdbUrl
        if (imdbId.isNotBlank()) return "https://www.imdb.com/title/$imdbId/"
        return ""
    }

    fun effectiveTraktUrl(): String {
        return if (imdbId.isNotBlank()) {
            "https://trakt.tv/search/imdb/$imdbId"
        } else {
            "https://trakt.tv/search?query=${URLEncoder.encode(title, "UTF-8")}"
        }
    }

    fun seriesGroupKey(): String {
        return if (imdbId.isNotBlank()) {
            "imdb:${imdbId.trim().lowercase(Locale.ROOT)}"
        } else {
            "title:${title.trim().lowercase(Locale.ROOT)}"
        }
    }
}

sealed interface LibraryEntry {
    val sortUpdatedAt: Long
    val sortWatchedDate: String
    val sortImdbRating: Double?
    val sortPersonalRating: Double?
    val sortYear: Int
    val sortTitle: String
    val entryPosterUrl: String
    val entryTitle: String
    val entryStatus: MovieStatus
    val isCurrentlyWatching: Boolean
}

data class SingleLibraryEntry(val item: MovieItem) : LibraryEntry {
    override val sortUpdatedAt: Long get() = item.updatedAt
    override val sortWatchedDate: String get() = item.watchedDate
    override val sortImdbRating: Double? get() = item.imdbRating
    override val sortPersonalRating: Double? get() = item.personalRating
    override val sortYear: Int get() = item.releaseYear
    override val sortTitle: String get() = item.title
    override val entryPosterUrl: String get() = item.posterUrl
    override val entryTitle: String get() = item.title
    override val entryStatus: MovieStatus get() = item.status
    override val isCurrentlyWatching: Boolean get() = false
}

data class SeriesGroupEntry(
    val groupKey: String,
    val title: String,
    val releaseYear: Int,
    val imdbId: String,
    val imdbUrl: String,
    val imdbRating: Double?,
    val avgPersonalRating: Double?,
    val genres: String,
    val plot: String,
    val posterUrl: String,
    val seasons: List<MovieItem>,
    val watchedCount: Int,
    val watchlistCount: Int,
    val droppedCount: Int,
    val activeWatchingNote: String,
    val nextWatchlistSeason: Int?,
    val seriesEnded: Boolean,
    override val sortUpdatedAt: Long,
    override val sortWatchedDate: String
) : LibraryEntry {
    override val sortImdbRating: Double? get() = imdbRating
    override val sortPersonalRating: Double? get() = avgPersonalRating
    override val sortYear: Int get() = releaseYear
    override val sortTitle: String get() = title
    override val entryPosterUrl: String get() = posterUrl
    override val entryTitle: String get() = title
    override val isCurrentlyWatching: Boolean
        get() = activeWatchingNote.isNotBlank() || (watchedCount > 0 && watchlistCount > 0)
    override val entryStatus: MovieStatus
        get() = when {
            watchedCount == seasons.size && seasons.isNotEmpty() -> MovieStatus.WATCHED
            droppedCount == seasons.size && seasons.isNotEmpty() -> MovieStatus.DROPPED
            watchlistCount > 0 -> MovieStatus.WATCHLIST
            watchedCount > 0 -> MovieStatus.WATCHED
            else -> MovieStatus.DROPPED
        }

    fun effectiveTraktUrl(): String {
        return if (imdbId.isNotBlank()) {
            "https://trakt.tv/search/imdb/$imdbId"
        } else {
            "https://trakt.tv/search?query=${URLEncoder.encode(title, "UTF-8")}"
        }
    }
}

data class SeriesCheckCandidate(
    val groupKey: String,
    val title: String,
    val imdbId: String,
    val imdbUrl: String,
    val releaseYear: Int,
    val maxSeasonNumber: Int,
    val imdbRating: Double?,
    val genres: String,
    val plot: String,
    val posterUrl: String,
    val seasonIds: List<Long>
)

fun buildLibraryEntries(
    allItems: List<MovieItem>,
    search: String,
    statusFilter: MovieStatus?,
    typeFilter: String?,
    sortOption: SortOption,
    sortAscending: Boolean = sortOption.defaultAscending
): List<LibraryEntry> {
    val entries = mutableListOf<LibraryEntry>()
    val seriesBuckets = linkedMapOf<String, MutableList<MovieItem>>()

    for (item in allItems) {
        if (item.type.equals("Series", ignoreCase = true)) {
            seriesBuckets.getOrPut(item.seriesGroupKey()) { mutableListOf() }.add(item)
        } else {
            entries.add(SingleLibraryEntry(item))
        }
    }

    for ((key, rawList) in seriesBuckets) {
        val seasons = rawList.sortedWith(
            compareBy<MovieItem> { it.seasonNumber ?: Int.MAX_VALUE }.thenBy { it.id }
        )
        val first = seasons.first()
        val watchedCount = seasons.count { it.status == MovieStatus.WATCHED }
        val watchlistCount = seasons.count { it.status == MovieStatus.WATCHLIST }
        val droppedCount = seasons.count { it.status == MovieStatus.DROPPED }

        val activeProgress = seasons.firstOrNull {
            it.episodeProgress.startsWith("Watching:", ignoreCase = true) && it.status != MovieStatus.WATCHED
        }?.let {
            val sLabel = it.seasonNumber?.let { n -> "S${n.toString().padStart(2, '0')} " } ?: ""
            "$sLabel${it.episodeProgress.removePrefix("Watching:").trim()}"
        } ?: ""

        val nextWlSeason = seasons.firstOrNull { it.status == MovieStatus.WATCHLIST }?.seasonNumber

        val personalScores = seasons.mapNotNull { it.personalRating }
        val avgPersonal = if (personalScores.isNotEmpty()) personalScores.average() else null
        val minYear = seasons.mapNotNull { it.releaseYear.takeIf { y -> y > 0 } }.minOrNull() ?: 0

        entries.add(
            SeriesGroupEntry(
                groupKey = key,
                title = first.title,
                releaseYear = minYear,
                imdbId = seasons.firstNotNullOfOrNull { it.imdbId.ifBlank { null } } ?: "",
                imdbUrl = seasons.firstNotNullOfOrNull { it.imdbUrl.ifBlank { null } } ?: "",
                imdbRating = seasons.firstNotNullOfOrNull { it.imdbRating },
                avgPersonalRating = avgPersonal,
                genres = seasons.firstNotNullOfOrNull { it.genres.ifBlank { null } } ?: "",
                plot = seasons.firstNotNullOfOrNull { it.plot.ifBlank { null } } ?: "",
                posterUrl = seasons.firstNotNullOfOrNull { it.posterUrl.ifBlank { null } } ?: "",
                seasons = seasons,
                watchedCount = watchedCount,
                watchlistCount = watchlistCount,
                droppedCount = droppedCount,
                activeWatchingNote = activeProgress,
                nextWatchlistSeason = nextWlSeason,
                seriesEnded = seasons.any { it.seriesEnded },
                sortUpdatedAt = seasons.maxOf { it.updatedAt },
                sortWatchedDate = seasons.mapNotNull { it.watchedDate.ifBlank { null } }.maxOrNull() ?: ""
            )
        )
    }

    val q = search.trim().lowercase(Locale.ROOT)
    val filtered = entries.filter { entry ->
        val matchesType = when (typeFilter) {
            null -> true
            "Series" -> entry is SeriesGroupEntry
            else -> entry is SingleLibraryEntry && entry.item.type.equals(typeFilter, ignoreCase = true)
        }
        if (!matchesType) return@filter false

        val matchesStatus = when (statusFilter) {
            null -> true
            else -> when (entry) {
                is SingleLibraryEntry -> entry.item.status == statusFilter
                is SeriesGroupEntry -> entry.seasons.any { it.status == statusFilter }
            }
        }
        if (!matchesStatus) return@filter false

        if (q.isEmpty()) return@filter true
        when (entry) {
            is SingleLibraryEntry -> {
                val m = entry.item
                m.title.lowercase(Locale.ROOT).contains(q) ||
                    m.imdbId.lowercase(Locale.ROOT).contains(q) ||
                    m.genres.lowercase(Locale.ROOT).contains(q) ||
                    m.notes.lowercase(Locale.ROOT).contains(q) ||
                    m.releaseYear.toString().contains(q)
            }
            is SeriesGroupEntry -> {
                entry.title.lowercase(Locale.ROOT).contains(q) ||
                    entry.imdbId.lowercase(Locale.ROOT).contains(q) ||
                    entry.genres.lowercase(Locale.ROOT).contains(q) ||
                    entry.releaseYear.toString().contains(q) ||
                    entry.seasons.any { s ->
                        s.notes.lowercase(Locale.ROOT).contains(q) ||
                            s.episodeProgress.lowercase(Locale.ROOT).contains(q)
                    }
            }
        }
    }

    return when (sortOption) {
        SortOption.UPDATED_DESC -> {
            if (sortAscending) filtered.sortedBy { it.sortUpdatedAt }
            else filtered.sortedByDescending { it.sortUpdatedAt }
        }
        SortOption.WATCHED_DATE_DESC -> {
            val comp = compareBy<LibraryEntry> { it.sortWatchedDate.isBlank() }
            val ordered = if (sortAscending) {
                comp.thenBy { it.sortWatchedDate }
            } else {
                comp.thenByDescending { it.sortWatchedDate }
            }
            filtered.sortedWith(ordered.thenByDescending { it.sortUpdatedAt })
        }
        SortOption.IMDB_RATING_DESC -> {
            val comp = compareBy<LibraryEntry> { it.sortImdbRating == null }
            val ordered = if (sortAscending) {
                comp.thenBy { it.sortImdbRating ?: 0.0 }
            } else {
                comp.thenByDescending { it.sortImdbRating ?: 0.0 }
            }
            filtered.sortedWith(ordered.thenBy { it.sortTitle.lowercase(Locale.ROOT) })
        }
        SortOption.PERSONAL_RATING_DESC -> {
            val comp = compareBy<LibraryEntry> { it.sortPersonalRating == null }
            val ordered = if (sortAscending) {
                comp.thenBy { it.sortPersonalRating ?: 0.0 }
            } else {
                comp.thenByDescending { it.sortPersonalRating ?: 0.0 }
            }
            filtered.sortedWith(ordered.thenBy { it.sortTitle.lowercase(Locale.ROOT) })
        }
        SortOption.YEAR_DESC -> {
            val comp = compareBy<LibraryEntry> { it.sortYear <= 0 }
            val ordered = if (sortAscending) {
                comp.thenBy { it.sortYear }
            } else {
                comp.thenByDescending { it.sortYear }
            }
            filtered.sortedWith(ordered.thenBy { it.sortTitle.lowercase(Locale.ROOT) })
        }
        SortOption.TITLE_ASC -> {
            if (sortAscending) filtered.sortedBy { it.sortTitle.lowercase(Locale.ROOT) }
            else filtered.sortedByDescending { it.sortTitle.lowercase(Locale.ROOT) }
        }
    }
}

data class MovieStats(
    val total: Int = 0,
    val watched: Int = 0,
    val watchlist: Int = 0,
    val dropped: Int = 0
)

data class ImportSummary(
    val added: Int,
    val mergedOrSkipped: Int,
    val totalParsed: Int
)

class MovieDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "movie_tracker.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_MOVIES = "movies"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_MOVIES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                type TEXT NOT NULL DEFAULT 'Movie',
                release_year INTEGER NOT NULL DEFAULT 0,
                imdb_id TEXT NOT NULL DEFAULT '',
                imdb_url TEXT NOT NULL DEFAULT '',
                season_number INTEGER,
                status TEXT NOT NULL DEFAULT 'WATCHLIST',
                watched_date TEXT NOT NULL DEFAULT '',
                rewatch_count INTEGER NOT NULL DEFAULT 0,
                imdb_rating REAL,
                personal_rating REAL,
                genres TEXT NOT NULL DEFAULT '',
                plot TEXT NOT NULL DEFAULT '',
                poster_url TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                episode_progress TEXT NOT NULL DEFAULT '',
                series_ended INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_movies_title ON $TABLE_MOVIES(title)")
        db.execSQL("CREATE INDEX idx_movies_imdb ON $TABLE_MOVIES(imdb_id)")
        db.execSQL("CREATE INDEX idx_movies_status ON $TABLE_MOVIES(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_MOVIES ADD COLUMN episode_progress TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE $TABLE_MOVIES ADD COLUMN series_ended INTEGER NOT NULL DEFAULT 0") }
        }
    }

    private fun MovieItem.toContentValues(): ContentValues {
        return ContentValues().apply {
            put("title", title.trim())
            put("type", type.trim().ifEmpty { "Movie" })
            put("release_year", releaseYear)
            put("imdb_id", imdbId.trim())
            put("imdb_url", imdbUrl.trim())
            if (seasonNumber != null && seasonNumber > 0) {
                put("season_number", seasonNumber)
            } else {
                putNull("season_number")
            }
            put("status", status.code)
            put("watched_date", watchedDate.trim())
            put("rewatch_count", rewatchCount)
            if (imdbRating != null) put("imdb_rating", imdbRating) else putNull("imdb_rating")
            if (personalRating != null) put("personal_rating", personalRating) else putNull("personal_rating")
            put("genres", genres.trim())
            put("plot", plot.trim())
            put("poster_url", posterUrl.trim())
            put("notes", notes.trim())
            put("episode_progress", episodeProgress.trim())
            put("series_ended", if (seriesEnded) 1 else 0)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }
    }

    private fun Cursor.toMovieItem(): MovieItem {
        val seasonIdx = getColumnIndexOrThrow("season_number")
        val imdbRatIdx = getColumnIndexOrThrow("imdb_rating")
        val persRatIdx = getColumnIndexOrThrow("personal_rating")
        val epProgIdx = getColumnIndex("episode_progress")
        val endedIdx = getColumnIndex("series_ended")
        return MovieItem(
            id = getLong(getColumnIndexOrThrow("id")),
            title = getString(getColumnIndexOrThrow("title")),
            type = getString(getColumnIndexOrThrow("type")),
            releaseYear = getInt(getColumnIndexOrThrow("release_year")),
            imdbId = getString(getColumnIndexOrThrow("imdb_id")),
            imdbUrl = getString(getColumnIndexOrThrow("imdb_url")),
            seasonNumber = if (isNull(seasonIdx)) null else getInt(seasonIdx),
            status = MovieStatus.fromCode(getString(getColumnIndexOrThrow("status"))),
            watchedDate = getString(getColumnIndexOrThrow("watched_date")),
            rewatchCount = getInt(getColumnIndexOrThrow("rewatch_count")),
            imdbRating = if (isNull(imdbRatIdx)) null else getDouble(imdbRatIdx),
            personalRating = if (isNull(persRatIdx)) null else getDouble(persRatIdx),
            genres = getString(getColumnIndexOrThrow("genres")),
            plot = getString(getColumnIndexOrThrow("plot")),
            posterUrl = getString(getColumnIndexOrThrow("poster_url")),
            notes = getString(getColumnIndexOrThrow("notes")),
            episodeProgress = if (epProgIdx >= 0 && !isNull(epProgIdx)) getString(epProgIdx) else "",
            seriesEnded = endedIdx >= 0 && !isNull(endedIdx) && getInt(endedIdx) == 1,
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
        )
    }

    fun insertMovie(item: MovieItem): Long {
        val now = System.currentTimeMillis()
        val toInsert = item.copy(createdAt = now, updatedAt = now)
        return writableDatabase.insert(TABLE_MOVIES, null, toInsert.toContentValues())
    }

    fun updateMovie(item: MovieItem) {
        val toUpdate = item.copy(updatedAt = System.currentTimeMillis())
        writableDatabase.update(TABLE_MOVIES, toUpdate.toContentValues(), "id = ?", arrayOf(item.id.toString()))
    }

    fun deleteMovie(id: Long) {
        writableDatabase.delete(TABLE_MOVIES, "id = ?", arrayOf(id.toString()))
    }

    fun findByImdbId(imdbId: String): List<MovieItem> {
        if (imdbId.isBlank()) return emptyList()
        val list = mutableListOf<MovieItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_MOVIES WHERE LOWER(imdb_id) = LOWER(?) ORDER BY season_number ASC",
            arrayOf(imdbId.trim())
        ).use { c ->
            while (c.moveToNext()) list.add(c.toMovieItem())
        }
        return list
    }

    fun getAllMovies(): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_MOVIES ORDER BY updated_at DESC, id DESC",
            null
        ).use { c ->
            while (c.moveToNext()) list.add(c.toMovieItem())
        }
        return list
    }

    fun getStats(): MovieStats {
        var total = 0
        var watched = 0
        var watchlist = 0
        var dropped = 0
        readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM $TABLE_MOVIES GROUP BY status",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val status = c.getString(0)
                val count = c.getInt(1)
                total += count
                when (status) {
                    MovieStatus.WATCHED.code -> watched += count
                    MovieStatus.WATCHLIST.code -> watchlist += count
                    MovieStatus.DROPPED.code -> dropped += count
                }
            }
        }
        return MovieStats(total, watched, watchlist, dropped)
    }

    fun getMoviesNeedingEnrich(limit: Int): List<MovieItem> {
        val list = mutableListOf<MovieItem>()
        readableDatabase.rawQuery(
            """
            SELECT * FROM $TABLE_MOVIES
            WHERE (imdb_rating IS NULL OR poster_url = '' OR genres = '')
            AND (imdb_id != '' OR title != '')
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) list.add(c.toMovieItem())
        }
        return list
    }

    fun getSeriesCandidatesForNewSeasons(): List<SeriesCheckCandidate> {
        val groups = buildLibraryEntries(getAllMovies(), "", null, "Series", SortOption.UPDATED_DESC)
            .filterIsInstance<SeriesGroupEntry>()
        val candidates = mutableListOf<SeriesCheckCandidate>()
        for (g in groups) {
            if (g.seriesEnded) continue
            val latestSeason = g.seasons.maxByOrNull { it.seasonNumber ?: 0 } ?: continue
            if (latestSeason.status == MovieStatus.DROPPED || g.droppedCount == g.seasons.size) continue
            val maxSeason = g.seasons.mapNotNull { it.seasonNumber }.maxOrNull() ?: 1
            candidates.add(
                SeriesCheckCandidate(
                    groupKey = g.groupKey,
                    title = g.title,
                    imdbId = g.imdbId,
                    imdbUrl = g.imdbUrl,
                    releaseYear = g.releaseYear,
                    maxSeasonNumber = maxSeason,
                    imdbRating = g.imdbRating,
                    genres = g.genres,
                    plot = g.plot,
                    posterUrl = g.posterUrl,
                    seasonIds = g.seasons.map { it.id }
                )
            )
        }
        return candidates
    }

    fun markSeriesEnded(seasonIds: List<Long>) {
        if (seasonIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply { put("series_ended", 1) }
            for (id in seasonIds) {
                db.update(TABLE_MOVIES, cv, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertNewSeasonsSafely(items: List<MovieItem>): Int {
        if (items.isEmpty()) return 0
        var inserted = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingKeys = getAllMovies().map { m ->
                val s = m.seasonNumber ?: 0
                if (m.imdbId.isNotBlank()) "${m.imdbId.lowercase(Locale.ROOT)}#$s"
                else "${m.title.trim().lowercase(Locale.ROOT)}#$s"
            }.toMutableSet()

            for (item in items) {
                val s = item.seasonNumber ?: 0
                val key = if (item.imdbId.isNotBlank()) {
                    "${item.imdbId.lowercase(Locale.ROOT)}#$s"
                } else {
                    "${item.title.trim().lowercase(Locale.ROOT)}#$s"
                }
                if (key in existingKeys) continue
                val now = System.currentTimeMillis()
                val rowId = db.insert(TABLE_MOVIES, null, item.copy(createdAt = now, updatedAt = now).toContentValues())
                if (rowId != -1L) {
                    existingKeys.add(key)
                    inserted++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun bulkImport(items: List<MovieItem>, mergeDuplicates: Boolean): ImportSummary {
        var added = 0
        var merged = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingByKey = mutableMapOf<String, MovieItem>()
            if (mergeDuplicates) {
                getAllMovies().forEach { existing ->
                    val key = duplicateKey(existing)
                    if (key != null) existingByKey[key] = existing
                }
            }

            for (item in items) {
                val key = if (mergeDuplicates) duplicateKey(item) else null
                val existing = if (key != null) existingByKey[key] else null
                if (existing != null) {
                    val mergedNotes = listOf(existing.notes, item.notes)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" | ")
                    val updated = existing.copy(
                        status = if (item.status == MovieStatus.WATCHED) MovieStatus.WATCHED else existing.status,
                        watchedDate = item.watchedDate.ifBlank { existing.watchedDate },
                        rewatchCount = maxOf(existing.rewatchCount, item.rewatchCount),
                        imdbRating = existing.imdbRating ?: item.imdbRating,
                        personalRating = existing.personalRating ?: item.personalRating,
                        genres = existing.genres.ifBlank { item.genres },
                        plot = existing.plot.ifBlank { item.plot },
                        posterUrl = existing.posterUrl.ifBlank { item.posterUrl },
                        episodeProgress = item.episodeProgress.ifBlank { existing.episodeProgress },
                        seriesEnded = existing.seriesEnded || item.seriesEnded,
                        notes = mergedNotes,
                        updatedAt = System.currentTimeMillis()
                    )
                    db.update(TABLE_MOVIES, updated.toContentValues(), "id = ?", arrayOf(existing.id.toString()))
                    existingByKey[key!!] = updated
                    merged++
                } else {
                    val now = System.currentTimeMillis()
                    val rowId = db.insert(TABLE_MOVIES, null, item.copy(createdAt = now, updatedAt = now).toContentValues())
                    if (rowId != -1L && key != null) {
                        existingByKey[key] = item.copy(id = rowId)
                    }
                    added++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ImportSummary(added = added, mergedOrSkipped = merged, totalParsed = items.size)
    }

    fun applyTraktOneWaySync(
        watchedMovies: List<TraktWatchedMovie>,
        watchedSeasons: List<TraktSeasonSyncItem>,
        watchlistItems: List<TraktWatchlistItem>,
        ratings: List<TraktRatingItem>
    ): TraktSyncSummary {
        var moviesAdded = 0
        var moviesUpdated = 0
        var seasonsAdded = 0
        var seasonsUpdated = 0
        var watchlistAdded = 0
        var ratingsUpdated = 0

        val db = writableDatabase
        db.beginTransaction()
        try {
            val all = getAllMovies().toMutableList()
            val byImdbAndSeason = mutableMapOf<String, MovieItem>()
            val byTitleAndSeason = mutableMapOf<String, MovieItem>()

            fun indexItem(m: MovieItem) {
                val s = m.seasonNumber ?: 0
                if (m.imdbId.isNotBlank()) {
                    byImdbAndSeason["${m.imdbId.lowercase(Locale.ROOT)}#$s"] = m
                }
                byTitleAndSeason["${m.title.trim().lowercase(Locale.ROOT)}#$s"] = m
            }
            all.forEach(::indexItem)

            fun findMatch(imdbId: String, title: String, season: Int): MovieItem? {
                if (imdbId.isNotBlank()) {
                    byImdbAndSeason["${imdbId.lowercase(Locale.ROOT)}#$season"]?.let { return it }
                }
                return byTitleAndSeason["${title.trim().lowercase(Locale.ROOT)}#$season"]
            }

            for (wm in watchedMovies) {
                val existing = findMatch(wm.imdbId, wm.title, 0)
                val dateIso = wm.lastWatchedAt.take(10)
                if (existing != null) {
                    val newCount = maxOf(existing.rewatchCount, wm.plays, 1)
                    val newDate = if (dateIso.isNotBlank() && (existing.watchedDate.isBlank() || dateIso > existing.watchedDate)) {
                        dateIso
                    } else existing.watchedDate
                    if (existing.status != MovieStatus.WATCHED || newCount != existing.rewatchCount || newDate != existing.watchedDate) {
                        val updated = existing.copy(
                            imdbId = existing.imdbId.ifBlank { wm.imdbId },
                            imdbUrl = existing.imdbUrl.ifBlank { if (wm.imdbId.isNotBlank()) "https://www.imdb.com/title/${wm.imdbId}/" else "" },
                            status = MovieStatus.WATCHED,
                            watchedDate = newDate,
                            rewatchCount = newCount,
                            updatedAt = System.currentTimeMillis()
                        )
                        db.update(TABLE_MOVIES, updated.toContentValues(), "id = ?", arrayOf(existing.id.toString()))
                        indexItem(updated)
                        moviesUpdated++
                    }
                } else {
                    val now = System.currentTimeMillis()
                    val item = MovieItem(
                        title = wm.title,
                        type = "Movie",
                        releaseYear = wm.year,
                        imdbId = wm.imdbId,
                        imdbUrl = if (wm.imdbId.isNotBlank()) "https://www.imdb.com/title/${wm.imdbId}/" else "",
                        status = MovieStatus.WATCHED,
                        watchedDate = dateIso,
                        rewatchCount = maxOf(wm.plays, 1),
                        createdAt = now,
                        updatedAt = now
                    )
                    val id = db.insert(TABLE_MOVIES, null, item.toContentValues())
                    if (id != -1L) {
                        indexItem(item.copy(id = id))
                        moviesAdded++
                    }
                }
            }

            for (ws in watchedSeasons) {
                val existing = findMatch(ws.imdbId, ws.showTitle, ws.seasonNumber)
                val dateIso = ws.lastWatchedAt.take(10)
                val isCompleted = ws.airedCount > 0 && ws.completedCount >= ws.airedCount
                val progressText = if (ws.airedCount > 0) {
                    if (isCompleted) "${ws.completedCount}/${ws.airedCount} eps"
                    else "Watching: ${ws.completedCount}/${ws.airedCount} eps"
                } else {
                    "${ws.completedCount} eps watched"
                }

                if (existing != null) {
                    if (existing.status == MovieStatus.DROPPED && !isCompleted) continue
                    val targetStatus = if (isCompleted) MovieStatus.WATCHED else existing.status
                    val newDate = if (isCompleted && dateIso.isNotBlank() && (existing.watchedDate.isBlank() || dateIso > existing.watchedDate)) {
                        dateIso
                    } else existing.watchedDate
                    val newRw = if (isCompleted) maxOf(existing.rewatchCount, 1) else existing.rewatchCount

                    if (existing.status != targetStatus || existing.episodeProgress != progressText || existing.watchedDate != newDate) {
                        val updated = existing.copy(
                            imdbId = existing.imdbId.ifBlank { ws.imdbId },
                            imdbUrl = existing.imdbUrl.ifBlank { if (ws.imdbId.isNotBlank()) "https://www.imdb.com/title/${ws.imdbId}/" else "" },
                            status = targetStatus,
                            watchedDate = newDate,
                            rewatchCount = newRw,
                            episodeProgress = progressText,
                            updatedAt = System.currentTimeMillis()
                        )
                        db.update(TABLE_MOVIES, updated.toContentValues(), "id = ?", arrayOf(existing.id.toString()))
                        indexItem(updated)
                        seasonsUpdated++
                    }
                } else {
                    val now = System.currentTimeMillis()
                    val item = MovieItem(
                        title = ws.showTitle,
                        type = "Series",
                        releaseYear = ws.showYear,
                        imdbId = ws.imdbId,
                        imdbUrl = if (ws.imdbId.isNotBlank()) "https://www.imdb.com/title/${ws.imdbId}/" else "",
                        seasonNumber = ws.seasonNumber,
                        status = if (isCompleted) MovieStatus.WATCHED else MovieStatus.WATCHLIST,
                        watchedDate = if (isCompleted) dateIso else "",
                        rewatchCount = if (isCompleted) 1 else 0,
                        episodeProgress = progressText,
                        createdAt = now,
                        updatedAt = now
                    )
                    val id = db.insert(TABLE_MOVIES, null, item.toContentValues())
                    if (id != -1L) {
                        indexItem(item.copy(id = id))
                        seasonsAdded++
                    }
                }
            }

            for (wl in watchlistItems) {
                val seasonNum = if (wl.type == "Series") (wl.seasonNumber ?: 1) else 0
                val existing = if (wl.type == "Series") {
                    findMatch(wl.imdbId, wl.title, seasonNum)
                        ?: all.firstOrNull {
                            it.type == "Series" &&
                                ((wl.imdbId.isNotBlank() && it.imdbId.equals(wl.imdbId, true)) ||
                                    it.title.equals(wl.title, true))
                        }
                } else {
                    findMatch(wl.imdbId, wl.title, 0)
                }

                if (existing == null) {
                    val now = System.currentTimeMillis()
                    val item = MovieItem(
                        title = wl.title,
                        type = wl.type,
                        releaseYear = wl.year,
                        imdbId = wl.imdbId,
                        imdbUrl = if (wl.imdbId.isNotBlank()) "https://www.imdb.com/title/${wl.imdbId}/" else "",
                        seasonNumber = if (wl.type == "Series") seasonNum else null,
                        status = MovieStatus.WATCHLIST,
                        createdAt = now,
                        updatedAt = now
                    )
                    val id = db.insert(TABLE_MOVIES, null, item.toContentValues())
                    if (id != -1L) {
                        val saved = item.copy(id = id)
                        all.add(saved)
                        indexItem(saved)
                        watchlistAdded++
                    }
                }
            }

            for (rt in ratings) {
                if (rt.type == "Movie") {
                    val existing = findMatch(rt.imdbId, rt.title, 0)
                    if (existing != null && existing.personalRating == null) {
                        val updated = existing.copy(personalRating = rt.rating.toDouble(), updatedAt = System.currentTimeMillis())
                        db.update(TABLE_MOVIES, updated.toContentValues(), "id = ?", arrayOf(existing.id.toString()))
                        indexItem(updated)
                        ratingsUpdated++
                    }
                } else {
                    val matches = getAllMovies().filter {
                        it.type == "Series" &&
                            ((rt.imdbId.isNotBlank() && it.imdbId.equals(rt.imdbId, true)) ||
                                it.title.equals(rt.title, true)) &&
                            it.personalRating == null
                    }
                    for (m in matches) {
                        val updated = m.copy(personalRating = rt.rating.toDouble(), updatedAt = System.currentTimeMillis())
                        db.update(TABLE_MOVIES, updated.toContentValues(), "id = ?", arrayOf(m.id.toString()))
                        ratingsUpdated++
                    }
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return TraktSyncSummary(
            moviesAdded = moviesAdded,
            moviesUpdated = moviesUpdated,
            seasonsAdded = seasonsAdded,
            seasonsUpdated = seasonsUpdated,
            watchlistAdded = watchlistAdded,
            ratingsUpdated = ratingsUpdated
        )
    }

    private fun duplicateKey(item: MovieItem): String? {
        if (item.imdbId.isBlank()) return null
        val season = item.seasonNumber ?: 0
        return "${item.imdbId.lowercase(Locale.ROOT)}#$season"
    }
}

object CsvAndBackupHelper {
    private val imdbRegex = Regex("(tt\\d{5,10})", RegexOption.IGNORE_CASE)
    private val seasonRegex = Regex("^(.+?)(?:\\s*-\\s*|\\s+)S(\\d{1,2})(?:\\s*-\\s*(.+))?$", RegexOption.IGNORE_CASE)
    private val rewatchRegex = Regex("^x(\\d+)$", RegexOption.IGNORE_CASE)

    fun extractImdbId(text: String): String {
        return imdbRegex.find(text)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT) ?: ""
    }

    fun todayIsoDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    fun normalizeDate(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("x", ignoreCase = true)) return ""
        val patterns = listOf("d-MMM-yyyy", "dd-MMM-yyyy", "dd/MM/yyyy", "d/M/yyyy", "yyyy-MM-dd")
        val outFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.ENGLISH).apply { isLenient = false }
                val parsed = fmt.parse(trimmed)
                if (parsed != null) return outFmt.format(parsed)
            } catch (_: Exception) {
            }
        }
        return trimmed
    }

    fun parseGoogleSheetsCsv(inputStream: InputStream): List<MovieItem> {
        val items = mutableListOf<MovieItem>()
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            var lineIndex = 0
            while (true) {
                val rawLine = reader.readLine() ?: break
                val line = if (lineIndex == 0) rawLine.removePrefix("\uFEFF") else rawLine
                lineIndex++
                if (line.isBlank()) continue

                val cols = parseCsvLine(line)
                if (cols.isEmpty()) continue
                val firstCol = cols[0].trim()
                if (lineIndex == 1 && firstCol.equals("Title", ignoreCase = true)) continue
                if (firstCol.isEmpty()) continue

                val rawType = cols.getOrNull(1)?.trim().orEmpty().ifEmpty { "Movie" }
                val normalizedType = when {
                    rawType.equals("Series", ignoreCase = true) -> "Series"
                    rawType.equals("Documentary", ignoreCase = true) -> "Documentary"
                    else -> "Movie"
                }
                val releaseYear = cols.getOrNull(2)?.trim()?.take(4)?.toIntOrNull() ?: 0
                val imdbUrl = cols.getOrNull(3)?.trim().orEmpty()
                val imdbId = extractImdbId(imdbUrl)
                val rawDate = cols.getOrNull(4)?.trim().orEmpty()

                val status = when {
                    rawDate.equals("x", ignoreCase = true) -> MovieStatus.DROPPED
                    rawDate.isEmpty() -> MovieStatus.WATCHLIST
                    else -> MovieStatus.WATCHED
                }
                val watchedDate = if (status == MovieStatus.WATCHED) normalizeDate(rawDate) else ""

                var rewatchCount = if (status == MovieStatus.WATCHED) 1 else 0
                val extraNotes = mutableListOf<String>()
                for (idx in 5 until cols.size) {
                    val cell = cols[idx].trim()
                    if (cell.isEmpty()) continue
                    val rwMatch = rewatchRegex.matchEntire(cell)
                    if (rwMatch != null) {
                        val n = rwMatch.groupValues[1].toIntOrNull()
                        if (n != null && n > 0) rewatchCount = maxOf(rewatchCount, n)
                    } else {
                        extraNotes.add(cell)
                    }
                }

                var cleanTitle = firstCol
                var seasonNum: Int? = null
                if (normalizedType == "Series") {
                    val m = seasonRegex.matchEntire(firstCol)
                    if (m != null) {
                        val base = m.groupValues[1].trim()
                        val s = m.groupValues[2].toIntOrNull()
                        val tail = m.groupValues[3].trim()
                        if (base.isNotEmpty() && s != null) {
                            cleanTitle = if (tail.isNotEmpty()) "$base ($tail)" else base
                            seasonNum = s
                        }
                    }
                }

                items.add(
                    MovieItem(
                        title = cleanTitle,
                        type = normalizedType,
                        releaseYear = releaseYear,
                        imdbId = imdbId,
                        imdbUrl = imdbUrl,
                        seasonNumber = seasonNum,
                        status = status,
                        watchedDate = watchedDate,
                        rewatchCount = rewatchCount,
                        notes = extraNotes.joinToString(" | ")
                    )
                )
            }
        }
        return items
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    sb.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        result.add(sb.toString())
                        sb.setLength(0)
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    fun exportToJson(movies: List<MovieItem>): String {
        val arr = JSONArray()
        for (m in movies) {
            arr.put(JSONObject().apply {
                put("title", m.title)
                put("type", m.type)
                put("releaseYear", m.releaseYear)
                put("imdbId", m.imdbId)
                put("imdbUrl", m.imdbUrl)
                if (m.seasonNumber != null) put("seasonNumber", m.seasonNumber) else put("seasonNumber", JSONObject.NULL)
                put("status", m.status.code)
                put("watchedDate", m.watchedDate)
                put("rewatchCount", m.rewatchCount)
                if (m.imdbRating != null) put("imdbRating", m.imdbRating) else put("imdbRating", JSONObject.NULL)
                if (m.personalRating != null) put("personalRating", m.personalRating) else put("personalRating", JSONObject.NULL)
                put("genres", m.genres)
                put("plot", m.plot)
                put("posterUrl", m.posterUrl)
                put("notes", m.notes)
                put("episodeProgress", m.episodeProgress)
                put("seriesEnded", m.seriesEnded)
                put("createdAt", m.createdAt)
                put("updatedAt", m.updatedAt)
            })
        }
        return JSONObject().apply {
            put("app", "MovieTracker")
            put("version", 3)
            put("exportedAt", System.currentTimeMillis())
            put("movies", arr)
        }.toString(2)
    }

    fun parseJsonBackup(jsonText: String): List<MovieItem> {
        val root = JSONObject(jsonText)
        val arr = root.getJSONArray("movies")
        val list = mutableListOf<MovieItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                MovieItem(
                    title = o.optString("title", ""),
                    type = o.optString("type", "Movie"),
                    releaseYear = o.optInt("releaseYear", 0),
                    imdbId = o.optString("imdbId", ""),
                    imdbUrl = o.optString("imdbUrl", ""),
                    seasonNumber = if (o.isNull("seasonNumber")) null else o.optInt("seasonNumber"),
                    status = MovieStatus.fromCode(o.optString("status", "WATCHLIST")),
                    watchedDate = o.optString("watchedDate", ""),
                    rewatchCount = o.optInt("rewatchCount", 0),
                    imdbRating = if (o.isNull("imdbRating")) null else o.optDouble("imdbRating"),
                    personalRating = if (o.isNull("personalRating")) null else o.optDouble("personalRating"),
                    genres = o.optString("genres", ""),
                    plot = o.optString("plot", ""),
                    posterUrl = o.optString("posterUrl", ""),
                    notes = o.optString("notes", ""),
                    episodeProgress = o.optString("episodeProgress", ""),
                    seriesEnded = o.optBoolean("seriesEnded", false),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                )
            )
        }
        return list
    }

    fun exportToCsv(movies: List<MovieItem>): String {
        val sb = StringBuilder()
        sb.appendLine("Title,Type,Release,IMDB link,Watched date,Rewatch,IMDb Rating,Personal Rating,Genres,Progress,Notes")
        for (m in movies) {
            val fullTitle = if (m.seasonNumber != null && m.seasonNumber > 0) {
                "${m.title} - S${m.seasonNumber.toString().padStart(2, '0')}"
            } else {
                m.title
            }
            val dateCol = when (m.status) {
                MovieStatus.DROPPED -> "x"
                MovieStatus.WATCHLIST -> ""
                MovieStatus.WATCHED -> m.watchedDate
            }
            val rwCol = if (m.rewatchCount > 1) "x${m.rewatchCount}" else ""
            val row = listOf(
                fullTitle,
                m.type,
                if (m.releaseYear > 0) m.releaseYear.toString() else "",
                m.effectiveImdbUrl(),
                dateCol,
                rwCol,
                m.imdbRating?.toString() ?: "",
                m.personalRating?.toString() ?: "",
                m.genres,
                m.episodeProgress,
                m.notes
            ).joinToString(",") { csvEscape(it) }
            sb.appendLine(row)
        }
        return sb.toString()
    }

    private fun csvEscape(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n')
        return if (needsQuotes) "\"${value.replace("\"", "\"\"")}\"" else value
    }
}
