package com.example.movietracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class TraktDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val intervalSec: Int
)

data class TraktTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long
)

data class TraktWatchedMovie(
    val title: String,
    val year: Int,
    val imdbId: String,
    val plays: Int,
    val lastWatchedAt: String
)

data class TraktSeasonSyncItem(
    val showTitle: String,
    val showYear: Int,
    val imdbId: String,
    val seasonNumber: Int,
    val completedCount: Int,
    val airedCount: Int,
    val lastWatchedAt: String
)

data class TraktWatchlistItem(
    val title: String,
    val year: Int,
    val imdbId: String,
    val type: String,
    val seasonNumber: Int?
)

data class TraktRatingItem(
    val title: String,
    val imdbId: String,
    val type: String,
    val rating: Int
)

data class TraktSyncSummary(
    val moviesAdded: Int,
    val moviesUpdated: Int,
    val seasonsAdded: Int,
    val seasonsUpdated: Int,
    val watchlistAdded: Int,
    val ratingsUpdated: Int
) {
    fun hasChanges(): Boolean {
        return moviesAdded + moviesUpdated + seasonsAdded + seasonsUpdated + watchlistAdded + ratingsUpdated > 0
    }
}

object TraktClient {
    private const val BASE = "https://api.trakt.tv"

    private data class HttpResp(val code: Int, val body: String, val totalPages: Int)

    private fun request(
        method: String,
        path: String,
        clientId: String,
        accessToken: String? = null,
        jsonBody: JSONObject? = null
    ): HttpResp {
        val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("trakt-api-version", "2")
            setRequestProperty("trakt-api-key", clientId.trim())
            if (!accessToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            if (jsonBody != null) {
                doOutput = true
            }
        }
        try {
            if (jsonBody != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(jsonBody.toString())
                }
            }
            val code = conn.responseCode
            val totalPages = conn.getHeaderField("X-Pagination-Page-Count")?.toIntOrNull() ?: 1
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            } ?: ""
            return HttpResp(code, body, totalPages)
        } finally {
            conn.disconnect()
        }
    }

    fun requestDeviceCode(clientId: String): TraktDeviceCode {
        val resp = request(
            method = "POST",
            path = "/oauth/device/code",
            clientId = clientId,
            jsonBody = JSONObject().put("client_id", clientId.trim())
        )
        if (resp.code !in 200..299) {
            throw IllegalStateException("Trakt error ${resp.code}: ${resp.body}")
        }
        val j = JSONObject(resp.body)
        return TraktDeviceCode(
            deviceCode = j.getString("device_code"),
            userCode = j.getString("user_code"),
            verificationUrl = j.getString("verification_url"),
            expiresIn = j.optInt("expires_in", 600),
            intervalSec = j.optInt("interval", 5)
        )
    }

    fun pollDeviceToken(clientId: String, clientSecret: String, deviceCode: String): TraktTokens? {
        val body = JSONObject()
            .put("code", deviceCode)
            .put("client_id", clientId.trim())
            .apply {
                if (clientSecret.isNotBlank()) put("client_secret", clientSecret.trim())
            }
        val resp = request("POST", "/oauth/device/token", clientId, jsonBody = body)
        return when (resp.code) {
            200 -> parseTokenResponse(resp.body)
            400 -> null
            410 -> throw IllegalStateException("Code expired. Please try again.")
            418 -> throw IllegalStateException("Authorization denied.")
            else -> throw IllegalStateException("Trakt auth error ${resp.code}: ${resp.body}")
        }
    }

    fun refreshAccessToken(clientId: String, clientSecret: String, refreshToken: String): TraktTokens {
        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .put("client_id", clientId.trim())
            .put("grant_type", "refresh_token")
            .apply {
                if (clientSecret.isNotBlank()) put("client_secret", clientSecret.trim())
            }
        val resp = request("POST", "/oauth/token", clientId, jsonBody = body)
        if (resp.code !in 200..299) {
            throw IllegalStateException("Token refresh failed (${resp.code}). Please reconnect Trakt.")
        }
        return parseTokenResponse(resp.body)
    }

    private fun parseTokenResponse(jsonStr: String): TraktTokens {
        val j = JSONObject(jsonStr)
        val createdAt = j.optLong("created_at", System.currentTimeMillis() / 1000L)
        val expiresIn = j.optLong("expires_in", 7776000L)
        return TraktTokens(
            accessToken = j.getString("access_token"),
            refreshToken = j.getString("refresh_token"),
            expiresAtMillis = (createdAt + expiresIn) * 1000L
        )
    }

    fun pullOneWaySync(
        clientId: String,
        accessToken: String,
        db: MovieDatabaseHelper,
        onStatus: (String) -> Unit = {}
    ): TraktSyncSummary {
        onStatus("Fetching watched movies...")
        val watchedMovies = fetchWatchedMovies(clientId, accessToken)

        onStatus("Fetching watched shows...")
        val watchedSeasons = fetchWatchedSeasons(clientId, accessToken, onStatus)

        onStatus("Fetching Trakt watchlist...")
        val watchlist = fetchWatchlist(clientId, accessToken)

        onStatus("Fetching Trakt ratings...")
        val ratings = fetchRatings(clientId, accessToken)

        onStatus("Saving to local database...")
        return db.applyTraktOneWaySync(
            watchedMovies = watchedMovies,
            watchedSeasons = watchedSeasons,
            watchlistItems = watchlist,
            ratings = ratings
        )
    }

    private fun fetchWatchedMovies(clientId: String, token: String): List<TraktWatchedMovie> {
        val resp = request("GET", "/sync/watched/movies", clientId, token)
        if (resp.code !in 200..299) throw IllegalStateException("Movies sync failed (${resp.code})")
        val arr = JSONArray(resp.body)
        val list = mutableListOf<TraktWatchedMovie>()
        for (i in 0 until arr.length()) {
            val row = arr.getJSONObject(i)
            val m = row.optJSONObject("movie") ?: continue
            val ids = m.optJSONObject("ids")
            val imdb = ids?.optString("imdb")?.takeIf { it.startsWith("tt") }?.lowercase(Locale.ROOT) ?: ""
            list.add(
                TraktWatchedMovie(
                    title = m.optString("title"),
                    year = m.optInt("year", 0),
                    imdbId = imdb,
                    plays = row.optInt("plays", 1),
                    lastWatchedAt = row.optString("last_watched_at", "")
                )
            )
        }
        return list
    }

    private fun fetchWatchedSeasons(
        clientId: String,
        token: String,
        onStatus: (String) -> Unit
    ): List<TraktSeasonSyncItem> {
        val result = mutableListOf<TraktSeasonSyncItem>()
        var page = 1
        var totalPages = 1

        while (page <= totalPages) {
            val resp = request("GET", "/sync/watched/shows?page=$page&limit=100&extended=progress", clientId, token)
            if (resp.code !in 200..299) throw IllegalStateException("Shows sync failed (${resp.code})")
            totalPages = resp.totalPages
            val arr = JSONArray(resp.body)
            if (arr.length() == 0) break

            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                val show = row.optJSONObject("show") ?: continue
                val showTitle = show.optString("title")
                val showYear = show.optInt("year", 0)
                val showAiredTotal = show.optInt("aired_episodes", 0)
                val ids = show.optJSONObject("ids")
                val imdb = ids?.optString("imdb")?.takeIf { it.startsWith("tt") }?.lowercase(Locale.ROOT) ?: ""
                val traktId = ids?.optInt("trakt", 0) ?: 0
                val showLastWatched = row.optString("last_watched_at", "")

                val seasonsArr = row.optJSONArray("seasons") ?: JSONArray()
                var totalWatchedInPayload = 0
                for (sIdx in 0 until seasonsArr.length()) {
                    val sObj = seasonsArr.getJSONObject(sIdx)
                    if (sObj.optInt("number", 0) > 0) {
                        totalWatchedInPayload += sObj.optJSONArray("episodes")?.length() ?: 0
                    }
                }

                if (showAiredTotal > 0 && totalWatchedInPayload >= showAiredTotal) {
                    for (sIdx in 0 until seasonsArr.length()) {
                        val sObj = seasonsArr.getJSONObject(sIdx)
                        val sNum = sObj.optInt("number", 0)
                        if (sNum <= 0) continue
                        val eps = sObj.optJSONArray("episodes") ?: continue
                        var latest = showLastWatched
                        for (eIdx in 0 until eps.length()) {
                            val d = eps.getJSONObject(eIdx).optString("last_watched_at", "")
                            if (d > latest) latest = d
                        }
                        result.add(
                            TraktSeasonSyncItem(
                                showTitle = showTitle,
                                showYear = showYear,
                                imdbId = imdb,
                                seasonNumber = sNum,
                                completedCount = eps.length(),
                                airedCount = eps.length(),
                                lastWatchedAt = latest
                            )
                        )
                    }
                } else if (traktId > 0) {
                    onStatus("Checking season progress: $showTitle")
                    val progResp = request(
                        "GET",
                        "/shows/$traktId/progress/watched?hidden=false&specials=false&count_specials=false",
                        clientId,
                        token
                    )
                    if (progResp.code in 200..299) {
                        val progJson = JSONObject(progResp.body)
                        val progSeasons = progJson.optJSONArray("seasons") ?: JSONArray()
                        for (sIdx in 0 until progSeasons.length()) {
                            val ps = progSeasons.getJSONObject(sIdx)
                            val sNum = ps.optInt("number", 0)
                            if (sNum <= 0) continue
                            val aired = ps.optInt("aired", 0)
                            val completed = ps.optInt("completed", 0)
                            if (completed <= 0) continue
                            val epArr = ps.optJSONArray("episodes")
                            var latest = showLastWatched
                            if (epArr != null) {
                                for (eIdx in 0 until epArr.length()) {
                                    val d = epArr.getJSONObject(eIdx).optString("last_watched_at", "")
                                    if (d > latest) latest = d
                                }
                            }
                            result.add(
                                TraktSeasonSyncItem(
                                    showTitle = showTitle,
                                    showYear = showYear,
                                    imdbId = imdb,
                                    seasonNumber = sNum,
                                    completedCount = completed,
                                    airedCount = aired,
                                    lastWatchedAt = latest
                                )
                            )
                        }
                    }
                    Thread.sleep(100)
                }
            }
            page++
        }
        return result
    }

    private fun fetchWatchlist(clientId: String, token: String): List<TraktWatchlistItem> {
        val list = mutableListOf<TraktWatchlistItem>()
        for (typePath in listOf("movies", "shows", "seasons")) {
            val resp = request("GET", "/sync/watchlist/$typePath", clientId, token)
            if (resp.code !in 200..299) continue
            val arr = JSONArray(resp.body)
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                when (typePath) {
                    "movies" -> {
                        val m = row.optJSONObject("movie") ?: continue
                        val imdb = m.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }?.lowercase(Locale.ROOT) ?: ""
                        list.add(TraktWatchlistItem(m.optString("title"), m.optInt("year", 0), imdb, "Movie", null))
                    }
                    "shows" -> {
                        val s = row.optJSONObject("show") ?: continue
                        val imdb = s.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }?.lowercase(Locale.ROOT) ?: ""
                        list.add(TraktWatchlistItem(s.optString("title"), s.optInt("year", 0), imdb, "Series", 1))
                    }
                    "seasons" -> {
                        val s = row.optJSONObject("show") ?: continue
                        val sn = row.optJSONObject("season")?.optInt("number", 1) ?: 1
                        if (sn <= 0) continue
                        val imdb = s.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }?.lowercase(Locale.ROOT) ?: ""
                        list.add(TraktWatchlistItem(s.optString("title"), s.optInt("year", 0), imdb, "Series", sn))
                    }
                }
            }
        }
        return list
    }

    private fun fetchRatings(clientId: String, token: String): List<TraktRatingItem> {
        val list = mutableListOf<TraktRatingItem>()
        for (typePath in listOf("movies", "shows")) {
            val resp = request("GET", "/sync/ratings/$typePath", clientId, token)
            if (resp.code !in 200..299) continue
            val arr = JSONArray(resp.body)
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                val rating = row.optInt("rating", 0)
                if (rating <= 0) continue
                val mediaObj = if (typePath == "movies") row.optJSONObject("movie") else row.optJSONObject("show")
                if (mediaObj == null) continue
                val imdb = mediaObj.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }?.lowercase(Locale.ROOT) ?: ""
                list.add(
                    TraktRatingItem(
                        title = mediaObj.optString("title"),
                        imdbId = imdb,
                        type = if (typePath == "movies") "Movie" else "Series",
                        rating = rating
                    )
                )
            }
        }
        return list
    }
}
