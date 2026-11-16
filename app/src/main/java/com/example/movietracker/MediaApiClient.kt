package com.example.movietracker

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class ApiMediaResult(
    val title: String,
    val type: String,
    val releaseYear: Int,
    val imdbId: String,
    val imdbRating: Double? = null,
    val genres: String = "",
    val plot: String = "",
    val posterUrl: String = "",
    val tmdbId: Int? = null,
    val totalSeasons: Int? = null
)

data class TmdbSeasonInfo(
    val seasonNumber: Int,
    val episodeCount: Int,
    val airDate: String
) {
    val airYear: Int get() = airDate.take(4).toIntOrNull() ?: 0
}

data class TmdbSeriesDetails(
    val status: String,
    val seasons: List<TmdbSeasonInfo>,
    val posterUrl: String = "",
    val plot: String = ""
) {
    val isEndedOrCanceled: Boolean
        get() = status.equals("Ended", ignoreCase = true) || status.equals("Canceled", ignoreCase = true)
}

object MediaApiClient {

    private fun httpGet(urlStr: String, bearerToken: String? = null): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            if (!bearerToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: $body")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun omdbClean(value: String): String {
        val t = value.trim()
        return if (t.equals("N/A", ignoreCase = true)) "" else t
    }

    fun fetchOmdbByImdbId(imdbId: String, omdbKey: String): ApiMediaResult? {
        if (omdbKey.isBlank() || imdbId.isBlank()) return null
        val encodedId = URLEncoder.encode(imdbId.trim(), "UTF-8")
        val encodedKey = URLEncoder.encode(omdbKey.trim(), "UTF-8")
        val json = JSONObject(httpGet("https://www.omdbapi.com/?apikey=$encodedKey&i=$encodedId&plot=short"))
        if (json.optString("Response") != "True") return null
        return parseOmdbDetailJson(json)
    }

    fun fetchOmdbByTitle(title: String, year: Int, omdbKey: String): ApiMediaResult? {
        if (omdbKey.isBlank() || title.isBlank()) return null
        val encodedTitle = URLEncoder.encode(title.trim(), "UTF-8")
        val encodedKey = URLEncoder.encode(omdbKey.trim(), "UTF-8")
        val yearParam = if (year > 1900) "&y=$year" else ""
        val json = JSONObject(httpGet("https://www.omdbapi.com/?apikey=$encodedKey&t=$encodedTitle$yearParam&plot=short"))
        if (json.optString("Response") != "True") return null
        return parseOmdbDetailJson(json)
    }

    private fun parseOmdbDetailJson(json: JSONObject): ApiMediaResult {
        val rawType = omdbClean(json.optString("Type"))
        val type = if (rawType.equals("series", ignoreCase = true)) "Series" else "Movie"
        val year = omdbClean(json.optString("Year")).take(4).toIntOrNull() ?: 0
        val rating = omdbClean(json.optString("imdbRating")).toDoubleOrNull()
        val seasons = omdbClean(json.optString("totalSeasons")).toIntOrNull()
        return ApiMediaResult(
            title = omdbClean(json.optString("Title")),
            type = type,
            releaseYear = year,
            imdbId = omdbClean(json.optString("imdbID")).lowercase(Locale.ROOT),
            imdbRating = rating,
            genres = omdbClean(json.optString("Genre")),
            plot = omdbClean(json.optString("Plot")),
            posterUrl = omdbClean(json.optString("Poster")),
            totalSeasons = seasons
        )
    }

    fun searchOmdb(query: String, omdbKey: String): List<ApiMediaResult> {
        if (omdbKey.isBlank() || query.isBlank()) return emptyList()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val encodedKey = URLEncoder.encode(omdbKey.trim(), "UTF-8")
        val json = JSONObject(httpGet("https://www.omdbapi.com/?apikey=$encodedKey&s=$encodedQuery"))
        if (json.optString("Response") != "True") return emptyList()
        val arr = json.optJSONArray("Search") ?: return emptyList()
        val list = mutableListOf<ApiMediaResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = omdbClean(o.optString("Type"))
            if (t == "game") continue
            list.add(
                ApiMediaResult(
                    title = omdbClean(o.optString("Title")),
                    type = if (t.equals("series", ignoreCase = true)) "Series" else "Movie",
                    releaseYear = omdbClean(o.optString("Year")).take(4).toIntOrNull() ?: 0,
                    imdbId = omdbClean(o.optString("imdbID")).lowercase(Locale.ROOT),
                    posterUrl = omdbClean(o.optString("Poster"))
                )
            )
        }
        return list
    }

    private fun tmdbGet(pathWithParams: String, tmdbKeyOrToken: String): JSONObject {
        val token = tmdbKeyOrToken.trim()
        val isBearer = token.length > 45 || token.contains('.')
        val url = if (isBearer) {
            "https://api.themoviedb.org/3$pathWithParams"
        } else {
            val sep = if (pathWithParams.contains('?')) "&" else "?"
            "https://api.themoviedb.org/3$pathWithParams${sep}api_key=${URLEncoder.encode(token, "UTF-8")}"
        }
        return JSONObject(httpGet(url, bearerToken = if (isBearer) token else null))
    }

    fun searchTmdb(query: String, tmdbKey: String): List<ApiMediaResult> {
        if (tmdbKey.isBlank() || query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = tmdbGet("/search/multi?query=$q&include_adult=false", tmdbKey)
        val arr = json.optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<ApiMediaResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mediaType = o.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue
            val isTv = mediaType == "tv"
            val title = if (isTv) o.optString("name") else o.optString("title")
            val dateStr = if (isTv) o.optString("first_air_date") else o.optString("release_date")
            val posterPath = o.optString("poster_path")
            val posterUrl = if (posterPath.isNotBlank() && posterPath != "null") {
                "https://image.tmdb.org/t/p/w342$posterPath"
            } else ""
            list.add(
                ApiMediaResult(
                    title = title,
                    type = if (isTv) "Series" else "Movie",
                    releaseYear = dateStr.take(4).toIntOrNull() ?: 0,
                    imdbId = "",
                    plot = o.optString("overview").takeIf { it != "null" } ?: "",
                    posterUrl = posterUrl,
                    tmdbId = o.optInt("id")
                )
            )
        }
        return list
    }

    fun fetchTmdbExternalImdbId(tmdbId: Int, isSeries: Boolean, tmdbKey: String): String {
        if (tmdbKey.isBlank()) return ""
        val endpoint = if (isSeries) "/tv/$tmdbId/external_ids" else "/movie/$tmdbId/external_ids"
        return try {
            val json = tmdbGet(endpoint, tmdbKey)
            json.optString("imdb_id").takeIf { it.startsWith("tt") }?.lowercase(Locale.ROOT) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun findTmdbByImdbId(imdbId: String, tmdbKey: String): ApiMediaResult? {
        if (tmdbKey.isBlank() || imdbId.isBlank()) return null
        return try {
            val json = tmdbGet("/find/${imdbId.trim()}?external_source=imdb_id", tmdbKey)
            val movieArr = json.optJSONArray("movie_results")
            val tvArr = json.optJSONArray("tv_results")
            when {
                movieArr != null && movieArr.length() > 0 -> {
                    val o = movieArr.getJSONObject(0)
                    val posterPath = o.optString("poster_path")
                    ApiMediaResult(
                        title = o.optString("title"),
                        type = "Movie",
                        releaseYear = o.optString("release_date").take(4).toIntOrNull() ?: 0,
                        imdbId = imdbId.lowercase(Locale.ROOT),
                        plot = o.optString("overview").takeIf { it != "null" } ?: "",
                        posterUrl = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w342$posterPath" else "",
                        tmdbId = o.optInt("id")
                    )
                }
                tvArr != null && tvArr.length() > 0 -> {
                    val o = tvArr.getJSONObject(0)
                    val posterPath = o.optString("poster_path")
                    ApiMediaResult(
                        title = o.optString("name"),
                        type = "Series",
                        releaseYear = o.optString("first_air_date").take(4).toIntOrNull() ?: 0,
                        imdbId = imdbId.lowercase(Locale.ROOT),
                        plot = o.optString("overview").takeIf { it != "null" } ?: "",
                        posterUrl = if (posterPath.isNotBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w342$posterPath" else "",
                        tmdbId = o.optInt("id")
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchTmdbSeriesDetails(imdbId: String, title: String, tmdbKey: String): TmdbSeriesDetails? {
        if (tmdbKey.isBlank()) return null
        val tvId = if (imdbId.isNotBlank()) {
            findTmdbByImdbId(imdbId, tmdbKey)?.takeIf { it.type == "Series" }?.tmdbId
        } else {
            searchTmdb(title, tmdbKey).firstOrNull { it.type == "Series" }?.tmdbId
        } ?: return null

        return try {
            val json = tmdbGet("/tv/$tvId", tmdbKey)
            val status = json.optString("status", "")
            val posterPath = json.optString("poster_path")
            val posterUrl = if (posterPath.isNotBlank() && posterPath != "null") {
                "https://image.tmdb.org/t/p/w342$posterPath"
            } else ""
            val plot = json.optString("overview").takeIf { it != "null" } ?: ""
            val arr = json.optJSONArray("seasons") ?: return TmdbSeriesDetails(status, emptyList(), posterUrl, plot)
            val list = mutableListOf<TmdbSeasonInfo>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val num = s.optInt("season_number", 0)
                if (num <= 0) continue
                val rawDate = s.optString("air_date", "")
                val airDate = if (rawDate.equals("null", true)) "" else rawDate.trim()
                list.add(
                    TmdbSeasonInfo(
                        seasonNumber = num,
                        episodeCount = s.optInt("episode_count", 0),
                        airDate = airDate
                    )
                )
            }
            TmdbSeriesDetails(status = status, seasons = list.sortedBy { it.seasonNumber }, posterUrl = posterUrl, plot = plot)
        } catch (_: Exception) {
            null
        }
    }

    fun enrichFromApis(
        title: String,
        year: Int,
        imdbId: String,
        isSeries: Boolean,
        tmdbId: Int?,
        omdbKey: String,
        tmdbKey: String
    ): ApiMediaResult? {
        var resolvedImdbId = imdbId.trim().lowercase(Locale.ROOT)
        if (resolvedImdbId.isBlank() && tmdbId != null && tmdbKey.isNotBlank()) {
            resolvedImdbId = fetchTmdbExternalImdbId(tmdbId, isSeries, tmdbKey)
        }

        val omdbData = when {
            resolvedImdbId.isNotBlank() && omdbKey.isNotBlank() ->
                runCatching { fetchOmdbByImdbId(resolvedImdbId, omdbKey) }.getOrNull()
            title.isNotBlank() && omdbKey.isNotBlank() ->
                runCatching { fetchOmdbByTitle(title, year, omdbKey) }.getOrNull()
            else -> null
        }

        val finalImdbId = resolvedImdbId.ifBlank { omdbData?.imdbId.orEmpty() }
        val tmdbData = if (finalImdbId.isNotBlank() && tmdbKey.isNotBlank()) {
            findTmdbByImdbId(finalImdbId, tmdbKey)
        } else null

        if (omdbData == null && tmdbData == null) return null

        return ApiMediaResult(
            title = omdbData?.title?.ifBlank { null } ?: tmdbData?.title ?: title,
            type = omdbData?.type ?: tmdbData?.type ?: if (isSeries) "Series" else "Movie",
            releaseYear = omdbData?.releaseYear?.takeIf { it > 0 } ?: tmdbData?.releaseYear ?: year,
            imdbId = finalImdbId,
            imdbRating = omdbData?.imdbRating,
            genres = omdbData?.genres.orEmpty(),
            plot = omdbData?.plot?.ifBlank { null } ?: tmdbData?.plot.orEmpty(),
            posterUrl = tmdbData?.posterUrl?.ifBlank { null } ?: omdbData?.posterUrl.orEmpty(),
            totalSeasons = omdbData?.totalSeasons
        )
    }

    fun fetchTmdbTrendingContext(tmdbKey: String): String {
        if (tmdbKey.isBlank()) return ""
        val lines = mutableListOf<String>()
        runCatching {
            val mediaJson = tmdbGet("/trending/all/week", tmdbKey)
            val arr = mediaJson.optJSONArray("results")
            if (arr != null && arr.length() > 0) {
                lines.add("Live TMDB Trending Movies & Series this week:")
                for (i in 0 until minOf(arr.length(), 10)) {
                    val o = arr.getJSONObject(i)
                    val mt = o.optString("media_type")
                    if (mt != "movie" && mt != "tv") continue
                    val isTv = mt == "tv"
                    val title = if (isTv) o.optString("name") else o.optString("title")
                    val year = (if (isTv) o.optString("first_air_date") else o.optString("release_date")).take(4)
                    val rating = o.optDouble("vote_average", 0.0)
                    val label = if (isTv) "Series" else "Movie"
                    lines.add("- $title ($year, $label, TMDB ${String.format(Locale.US, "%.1f", rating)})")
                }
            }
        }
        runCatching {
            val peopleJson = tmdbGet("/trending/person/week", tmdbKey)
            val arr = peopleJson.optJSONArray("results")
            if (arr != null && arr.length() > 0) {
                lines.add("Live TMDB Trending People (Actors/Directors) this week:")
                for (i in 0 until minOf(arr.length(), 8)) {
                    val p = arr.getJSONObject(i)
                    val name = p.optString("name")
                    val dept = p.optString("known_for_department")
                    val knownForArr = p.optJSONArray("known_for")
                    val knownTitles = mutableListOf<String>()
                    if (knownForArr != null) {
                        for (j in 0 until minOf(knownForArr.length(), 3)) {
                            val k = knownForArr.getJSONObject(j)
                            val kt = k.optString("title").ifBlank { k.optString("name") }
                            if (kt.isNotBlank()) knownTitles.add(kt)
                        }
                    }
                    val kf = if (knownTitles.isNotEmpty()) " — known for: ${knownTitles.joinToString(", ")}" else ""
                    if (name.isNotBlank()) lines.add("- $name ($dept)$kf")
                }
            }
        }
        return lines.joinToString("\n")
    }
}
