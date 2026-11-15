kotlin
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

enum class SortOption(val label: String) {
    UPDATED_DESC("Recent"),
    WATCHED_DATE_DESC("Watched Date"),
    IMDB_RATING_DESC("IMDb Rating"),
    PERSONAL_RATING_DESC("My Score"),
    YEAR_DESC("Release Year"),
    TITLE_ASC("Title A–Z")
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
        private const val DATABASE_VERSION = 1
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
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_movies_title ON $TABLE_MOVIES(title)")
        db.execSQL("CREATE INDEX idx_movies_imdb ON $TABLE_MOVIES(imdb_id)")
        db.execSQL("CREATE INDEX idx_movies_status ON $TABLE_MOVIES(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }
    }

    private fun Cursor.toMovieItem(): MovieItem {
        val seasonIdx = getColumnIndexOrThrow("season_number")
        val imdbRatIdx = getColumnIndexOrThrow("imdb_rating")
        val persRatIdx = getColumnIndexOrThrow("personal_rating")
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

    fun queryMovies(
        search: String,
        statusFilter: MovieStatus?,
        typeFilter: String?,
        sortOption: SortOption
    ): List<MovieItem> {
        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (search.isNotBlank()) {
            val q = "%${search.trim()}%"
            whereClauses.add("(title LIKE ? OR imdb_id LIKE ? OR genres LIKE ? OR notes LIKE ? OR CAST(release_year AS TEXT) LIKE ?)")
            repeat(5) { args.add(q) }
        }
        if (statusFilter != null) {
            whereClauses.add("status = ?")
            args.add(statusFilter.code)
        }
        if (!typeFilter.isNullOrBlank()) {
            whereClauses.add("LOWER(type) = LOWER(?)")
            args.add(typeFilter)
        }

        val whereSql = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}"
        val orderSql = when (sortOption) {
            SortOption.UPDATED_DESC -> "ORDER BY updated_at DESC, id DESC"
            SortOption.WATCHED_DATE_DESC -> "ORDER BY CASE WHEN watched_date = '' THEN 1 ELSE 0 END, watched_date DESC, id DESC"
            SortOption.IMDB_RATING_DESC -> "ORDER BY CASE WHEN imdb_rating IS NULL THEN 1 ELSE 0 END, imdb_rating DESC, title ASC"
            SortOption.PERSONAL_RATING_DESC -> "ORDER BY CASE WHEN personal_rating IS NULL THEN 1 ELSE 0 END, personal_rating DESC, title ASC"
            SortOption.YEAR_DESC -> "ORDER BY release_year DESC, title ASC"
            SortOption.TITLE_ASC -> "ORDER BY title COLLATE NOCASE ASC, COALESCE(season_number, 0) ASC"
        }

        val list = mutableListOf<MovieItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_MOVIES $whereSql $orderSql",
            args.toTypedArray()
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
                put("createdAt", m.createdAt)
                put("updatedAt", m.updatedAt)
            })
        }
        return JSONObject().apply {
            put("app", "MovieTracker")
            put("version", 1)
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
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                )
            )
        }
        return list
    }

    fun exportToCsv(movies: List<MovieItem>): String {
        val sb = StringBuilder()
        sb.appendLine("Title,Type,Release,IMDB link,Watched date,Rewatch,IMDb Rating,Personal Rating,Genres,Notes")
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
