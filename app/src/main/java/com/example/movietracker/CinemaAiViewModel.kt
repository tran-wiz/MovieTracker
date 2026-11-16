package com.example.movietracker

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.GroundingMetadata
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val AI_TAG = "CinemaAiViewModel"

enum class TasteMode(val label: String) {
    HOT_FOR_ME("Trending & For Me"),
    DEEP_MATCH("Best Taste Match")
}

data class WebSource(
    val title: String,
    val uri: String
)

data class AiMovieSuggestion(
    val title: String,
    val type: String,
    val releaseYear: Int,
    val reason: String,
    val posterUrl: String = "",
    val imdbId: String = "",
    val imdbRating: Double? = null,
    val genres: String = "",
    val plot: String = "",
    val tmdbId: Int? = null
)

sealed interface CinemaAiUiState {
    data object Idle : CinemaAiUiState
    data class Loading(val heading: String) : CinemaAiUiState
    data class Streaming(val heading: String, val text: String) : CinemaAiUiState
    data class Success(
        val heading: String,
        val summaryText: String,
        val suggestions: List<AiMovieSuggestion>,
        val sources: List<WebSource>,
        val searchEntryHtml: String? = null
    ) : CinemaAiUiState
    data class Error(val message: String) : CinemaAiUiState
}

class CinemaAiViewModel : ViewModel() {

    private val aiBackend by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI())
    }

    private val groundedModel by lazy {
        aiBackend.generativeModel(
            modelName = "gemini-3.8-flash",
            tools = listOf(Tool.googleSearch())
        )
    }

    private val plainModel by lazy {
        aiBackend.generativeModel(
            modelName = "gemini-3.8-flash"
        )
    }

    private val liteModel by lazy {
        aiBackend.generativeModel(
            modelName = "gemini-3.5-flash-lite"
        )
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { ex ->
            if (cont.isActive) cont.resumeWithException(ex)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }

    private val suggestionsSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "items" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "title" to Schema.string(description = "Official English or international title of the movie or TV series"),
                            "type" to Schema.enumeration(
                                values = listOf("Movie", "Series"),
                                description = "Whether the title is a Movie or a TV Series"
                            ),
                            "releaseYear" to Schema.integer(description = "4-digit release year, or 0 if unknown"),
                            "reason" to Schema.string(description = "Brief 1-sentence reason why this matches the user's ratings/taste")
                        )
                    ),
                    description = "Distinct movies or TV series recommended in the briefing"
                )
            )
        )
    }

    private val extractorModel by lazy {
        aiBackend.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = suggestionsSchema
            }
        )
    }

    private val _uiState = MutableStateFlow<CinemaAiUiState>(CinemaAiUiState.Idle)
    val uiState: StateFlow<CinemaAiUiState> = _uiState.asStateFlow()

    private val _selectedMode = MutableStateFlow(TasteMode.HOT_FOR_ME)
    val selectedMode: StateFlow<TasteMode> = _selectedMode.asStateFlow()

    private val modeCache = mutableMapOf<TasteMode, CinemaAiUiState.Success>()
    private var activeJob: Job? = null

    fun loadForMyTasteIfIdle(
        userLibrary: List<MovieItem>,
        omdbKey: String,
        tmdbKey: String
    ) {
        if (_uiState.value is CinemaAiUiState.Idle) {
            loadForMyTaste(_selectedMode.value, userLibrary, omdbKey, tmdbKey)
        }
    }

    fun loadForMyTaste(
        mode: TasteMode,
        userLibrary: List<MovieItem>,
        omdbKey: String,
        tmdbKey: String,
        forceRefresh: Boolean = false
    ) {
        _selectedMode.value = mode
        if (!forceRefresh) {
            modeCache[mode]?.let { cached ->
                _uiState.value = cached
                return
            }
        }

        val prompt = buildForMyTastePrompt(mode = mode, userLibrary = userLibrary, customRequest = null)
        runTasteQuery(
            heading = mode.label,
            basePrompt = prompt,
            includeWebTrending = (mode == TasteMode.HOT_FOR_ME),
            omdbKey = omdbKey,
            tmdbKey = tmdbKey,
            cacheMode = mode
        )
    }

    fun askWithMyTaste(
        question: String,
        userLibrary: List<MovieItem>,
        omdbKey: String,
        tmdbKey: String
    ) {
        val clean = question.trim()
        if (clean.isEmpty()) return

        val prompt = buildForMyTastePrompt(
            mode = _selectedMode.value,
            userLibrary = userLibrary,
            customRequest = clean
        )
        runTasteQuery(
            heading = clean,
            basePrompt = prompt,
            includeWebTrending = true,
            omdbKey = omdbKey,
            tmdbKey = tmdbKey,
            cacheMode = null
        )
    }

    private fun buildTasteProfileBlock(userLibrary: List<MovieItem>): String {
        if (userLibrary.isEmpty()) {
            return "User library is currently empty (no watched/rated titles yet)."
        }

        val favorites = userLibrary
            .filter {
                (it.personalRating != null && it.personalRating >= 7.5) ||
                    it.rewatchCount > 1 ||
                    (it.status == MovieStatus.WATCHED && it.personalRating == null)
            }
            .sortedWith(
                compareByDescending<MovieItem> { it.personalRating ?: 6.5 }
                    .thenByDescending { it.rewatchCount }
            )
            .distinctBy { it.title.trim().lowercase(Locale.ROOT) }
            .take(14)
            .joinToString("; ") { item ->
                val yearPart = if (item.releaseYear > 0) " (${item.releaseYear})" else ""
                val scorePart = item.personalRating?.let {
                    String.format(Locale.US, " [My Rating: %.1f/10]", it)
                } ?: if (item.rewatchCount > 1) " [Rewatched ${item.rewatchCount}x]" else " [Watched]"
                "${item.title}$yearPart$scorePart"
            }

        val avoidList = userLibrary
            .filter {
                it.status == MovieStatus.DROPPED ||
                    (it.personalRating != null && it.personalRating < 6.0)
            }
            .sortedBy { it.personalRating ?: 5.0 }
            .distinctBy { it.title.trim().lowercase(Locale.ROOT) }
            .take(10)
            .joinToString("; ") { item ->
                val tag = if (item.status == MovieStatus.DROPPED) {
                    "Dropped"
                } else {
                    String.format(Locale.US, "Low Rating: %.1f/10", item.personalRating)
                }
                "${item.title} [$tag]"
            }

        val weightedGenres = mutableMapOf<String, Double>()
        for (item in userLibrary) {
            if (item.genres.isBlank()) continue
            val weight = when {
                item.status == MovieStatus.DROPPED -> -1.5
                item.personalRating != null && item.personalRating >= 8.0 -> 3.0
                item.personalRating != null && item.personalRating >= 6.5 -> 1.5
                item.personalRating != null && item.personalRating < 5.5 -> -1.0
                item.status == MovieStatus.WATCHED -> 1.0
                else -> 0.5
            }
            item.genres.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { genre ->
                    weightedGenres[genre] = (weightedGenres[genre] ?: 0.0) + weight
                }
        }
        val topGenres = weightedGenres.entries
            .filter { it.value > 0.0 }
            .sortedByDescending { it.value }
            .take(6)
            .joinToString(", ") { it.key }

        val existingTitles = userLibrary
            .map { it.title.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(60)
            .joinToString(", ")

        return buildString {
            if (topGenres.isNotBlank()) {
                appendLine("- Top preferred genres (weighted by user ratings): $topGenres")
            }
            if (favorites.isNotBlank()) {
                appendLine("- Loved / High-rated watched titles: $favorites")
            }
            if (avoidList.isNotBlank()) {
                appendLine("- Disliked / Dropped titles (AVOID recommending similar styles): $avoidList")
            }
            if (existingTitles.isNotBlank()) {
                appendLine("- Already in user's library/watchlist/dropped (DO NOT recommend these again): $existingTitles")
            }
        }.trim()
    }

    private fun buildForMyTastePrompt(
        mode: TasteMode,
        userLibrary: List<MovieItem>,
        customRequest: String?
    ): String {
        val tasteProfile = buildTasteProfileBlock(userLibrary)
        val hasLibrary = userLibrary.isNotEmpty()

        return buildString {
            appendLine("You are the personal recommendation curator inside MovieTracker.")
            appendLine("Your goal is to recommend 5 to 6 movies or TV series tailored specifically to this user's taste.")
            appendLine()
            appendLine("User Taste Profile:")
            appendLine(tasteProfile)
            appendLine()

            if (!customRequest.isNullOrBlank()) {
                appendLine("Custom User Request: $customRequest")
                appendLine("Reply in the same language as the Custom User Request (Vietnamese if asked in Vietnamese, otherwise match its language).")
                appendLine("Tailor the answer strictly to the user's rating taste above while avoiding anything similar to their Dropped/low-rated titles and never repeating titles already in their library.")
            } else {
                appendLine("Write in concise, natural Vietnamese mixed with original international movie/series titles (with release year, e.g. Title (2024)) so titles are easy to save to Watchlist.")
                when (mode) {
                    TasteMode.HOT_FOR_ME -> {
                        if (hasLibrary) {
                            appendLine("Task: Use Google Search / trending context to find currently trending or recent (2024-2026) movies and TV series that strongly match the user's high-rated titles and preferred genres, while avoiding styles similar to their Dropped/low-rated entries. Explain briefly why each pick fits (e.g. linking to a title they rated highly) and why it's buzzing now.")
                        } else {
                            appendLine("Task: Recommend 5 to 6 standout trending movies and TV series right now across diverse acclaimed genres to help the user start their Watchlist.")
                        }
                    }
                    TasteMode.DEEP_MATCH -> {
                        if (hasLibrary) {
                            appendLine("Task: Prioritize pure taste compatibility over hype. Recommend 5 to 6 movies or TV series (including modern classics or hidden gems) that best match the user's highest personal ratings, pacing, and genres, while avoiding styles like their Dropped/low-rated entries. Explain clearly which of their rated titles makes each pick a great match.")
                        } else {
                            appendLine("Task: Recommend 5 to 6 universally acclaimed must-watch movies and TV series across top genres to kick off the user's Watchlist.")
                        }
                    }
                }
            }
            appendLine()
            appendLine("Format cleanly with bullet points (•) and exact official English/international titles + (Year). Avoid markdown tables.")
        }
    }

    private fun runTasteQuery(
        heading: String,
        basePrompt: String,
        includeWebTrending: Boolean,
        omdbKey: String,
        tmdbKey: String,
        cacheMode: TasteMode?
    ) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.value = CinemaAiUiState.Loading(heading)
            val accumulated = StringBuilder()
            var latestMetadata: GroundingMetadata? = null
            val diagnostics = mutableListOf<String>()

            try {
                // Step 1: Verify App Check token first so attestation/network blocks surface fast
                val appCheckStart = System.currentTimeMillis()
                try {
                    val tokenResult = withTimeout(15_000L) {
                        FirebaseAppCheck.getInstance().getAppCheckToken(false).awaitTask()
                    }
                    val elapsed = System.currentTimeMillis() - appCheckStart
                    diagnostics.add("AppCheck OK (${elapsed}ms, len=${tokenResult.token.length})")
                } catch (e: TimeoutCancellationException) {
                    Log.e(AI_TAG, "App Check token fetch timed out after 15s", e)
                    _uiState.value = CinemaAiUiState.Error(
                        "App Check token timed out after 15s. Please check emulator internet connection and retry."
                    )
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(AI_TAG, "App Check token retrieval failed", e)
                    _uiState.value = CinemaAiUiState.Error(
                        "App Check error: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    )
                    return@launch
                }

                val tmdbContext = if (includeWebTrending && tmdbKey.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        runCatching { MediaApiClient.fetchTmdbTrendingContext(tmdbKey) }.getOrDefault("")
                    }
                } else ""

                val prompt = if (tmdbContext.isNotBlank()) {
                    "$basePrompt\n\nLive TMDB Trending Context (cross-check with user's taste):\n$tmdbContext"
                } else basePrompt

                // Step 2: Grounded Gemini 3.8 Flash (when trending web context is desired)
                var groundedSucceeded = false
                if (includeWebTrending) {
                    try {
                        val t0 = System.currentTimeMillis()
                        val groundedResponse = withTimeout(30_000L) {
                            groundedModel.generateContent(prompt)
                        }
                        val groundedText = groundedResponse.text.orEmpty().trim()
                        if (groundedText.isNotEmpty()) {
                            accumulated.append(groundedText)
                            latestMetadata = groundedResponse.candidates.firstOrNull()?.groundingMetadata
                            _uiState.value = CinemaAiUiState.Streaming(
                                heading = heading,
                                text = groundedText
                            )
                            groundedSucceeded = true
                            diagnostics.add("Search+3.8-flash OK (${System.currentTimeMillis() - t0}ms)")
                        } else {
                            diagnostics.add("Search+3.8-flash empty")
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(AI_TAG, "Grounded search timed out after 30s; falling back to gemini-3.5-flash-lite", e)
                        diagnostics.add("Search+3.8-flash: timeout 30s")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(AI_TAG, "Grounded search failed (${e.message}); falling back to gemini-3.5-flash-lite", e)
                        diagnostics.add("Search+3.8-flash: ${e.javaClass.simpleName}: ${e.localizedMessage ?: e.message}")
                    }
                }

                // Step 3: Fast Gemini 3.5 Flash-Lite
                var textReady = groundedSucceeded
                if (!textReady) {
                    accumulated.clear()
                    try {
                        val t0 = System.currentTimeMillis()
                        val liteResponse = withTimeout(30_000L) {
                            liteModel.generateContent(prompt)
                        }
                        val liteText = liteResponse.text.orEmpty().trim()
                        if (liteText.isNotEmpty()) {
                            accumulated.append(liteText)
                            _uiState.value = CinemaAiUiState.Streaming(
                                heading = heading,
                                text = liteText
                            )
                            textReady = true
                            diagnostics.add("3.5-flash-lite OK (${System.currentTimeMillis() - t0}ms)")
                        } else {
                            diagnostics.add("3.5-flash-lite empty")
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(AI_TAG, "gemini-3.5-flash-lite timed out after 30s; retrying with gemini-3.8-flash", e)
                        diagnostics.add("3.5-flash-lite: timeout 30s")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(AI_TAG, "gemini-3.5-flash-lite failed (${e.message}); retrying with gemini-3.8-flash", e)
                        diagnostics.add("3.5-flash-lite: ${e.javaClass.simpleName}: ${e.localizedMessage ?: e.message}")
                    }
                }

                // Step 4: Standard Gemini 3.8 Flash fallback
                if (!textReady) {
                    accumulated.clear()
                    try {
                        val t0 = System.currentTimeMillis()
                        val plainResponse = withTimeout(45_000L) {
                            plainModel.generateContent(prompt)
                        }
                        val plainText = plainResponse.text.orEmpty().trim()
                        if (plainText.isNotEmpty()) {
                            accumulated.append(plainText)
                            _uiState.value = CinemaAiUiState.Streaming(
                                heading = heading,
                                text = plainText
                            )
                            textReady = true
                            diagnostics.add("3.8-flash OK (${System.currentTimeMillis() - t0}ms)")
                        } else {
                            diagnostics.add("3.8-flash empty")
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.e(AI_TAG, "All Gemini models timed out: ${diagnostics.joinToString(" | ")}", e)
                        diagnostics.add("3.8-flash: timeout 45s")
                        _uiState.value = CinemaAiUiState.Error(
                            "AI request timed out.\n${diagnostics.joinToString("\n")}"
                        )
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(AI_TAG, "Standard Gemini 3.8 Flash failed: ${diagnostics.joinToString(" | ")}", e)
                        diagnostics.add("3.8-flash: ${e.javaClass.simpleName}: ${e.localizedMessage ?: e.message}")
                        _uiState.value = CinemaAiUiState.Error(
                            diagnostics.joinToString("\n")
                        )
                        return@launch
                    }
                }

                val summaryText = accumulated.toString().trim()
                if (summaryText.isBlank()) {
                    _uiState.value = CinemaAiUiState.Error(
                        "AI returned an empty response.\n${diagnostics.joinToString("\n")}"
                    )
                    return@launch
                }

                val sources = if (groundedSucceeded) {
                    latestMetadata?.groundingChunks
                        ?.mapNotNull { chunk ->
                            val web = chunk.web ?: return@mapNotNull null
                            val uri = web.uri?.trim().orEmpty()
                            if (uri.isBlank()) null
                            else WebSource(title = web.title?.ifBlank { uri } ?: uri, uri = uri)
                        }
                        ?.distinctBy { it.uri }
                        ?.take(6)
                        ?: emptyList()
                } else emptyList()

                val searchHtml = if (groundedSucceeded) {
                    latestMetadata?.searchEntryPoint?.renderedContent
                } else null

                val extracted = extractMovieSuggestions(summaryText)
                val enriched = enrichSuggestionsWithApis(extracted, omdbKey, tmdbKey)

                val resultState = CinemaAiUiState.Success(
                    heading = heading,
                    summaryText = summaryText,
                    suggestions = enriched,
                    sources = sources,
                    searchEntryHtml = searchHtml
                )
                if (cacheMode != null) {
                    modeCache[cacheMode] = resultState
                }
                _uiState.value = resultState
            } catch (e: TimeoutCancellationException) {
                Log.e(AI_TAG, "Cinema AI query timed out: ${diagnostics.joinToString(" | ")}", e)
                _uiState.value = CinemaAiUiState.Error(
                    "AI request timed out.\n${diagnostics.joinToString("\n")}"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(AI_TAG, "Cinema AI query failed", e)
                _uiState.value = CinemaAiUiState.Error(
                    e.localizedMessage ?: e.javaClass.simpleName
                )
            }
        }
    }

    private suspend fun extractMovieSuggestions(briefingText: String): List<AiMovieSuggestion> {
        return try {
            val extractionPrompt = """
                Extract up to 8 distinct movies or TV series recommended in the text below.
                Only include real movies or TV series (never actors, directors, or genres by themselves).
                Use their standard English/international title for TMDB/IMDb lookup.

                Text:
                $briefingText
            """.trimIndent()

            val response = withTimeout(30_000L) {
                extractorModel.generateContent(extractionPrompt)
            }
            val rawJson = response.text ?: return extractTitlesLocally(briefingText)
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("items") ?: return extractTitlesLocally(briefingText)
            val list = mutableListOf<AiMovieSuggestion>()
            val seen = mutableSetOf<String>()

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("title").trim()
                if (title.isBlank()) continue
                val key = title.lowercase(Locale.ROOT)
                if (!seen.add(key)) continue
                val type = if (obj.optString("type").equals("Series", ignoreCase = true)) "Series" else "Movie"
                val year = obj.optInt("releaseYear", 0).takeIf { it in 1900..2100 } ?: 0
                val reason = obj.optString("reason").trim()
                list.add(
                    AiMovieSuggestion(
                        title = title,
                        type = type,
                        releaseYear = year,
                        reason = reason
                    )
                )
            }
            list.ifEmpty { extractTitlesLocally(briefingText) }
        } catch (e: TimeoutCancellationException) {
            Log.w(AI_TAG, "Structured title extraction timed out; using local fallback parser", e)
            extractTitlesLocally(briefingText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(AI_TAG, "Structured title extraction failed; using local fallback parser", e)
            extractTitlesLocally(briefingText)
        }
    }

    private fun extractTitlesLocally(briefingText: String): List<AiMovieSuggestion> {
        val pattern = Regex("""(?m)(?:^|\*\*|\•|\-|\d+\.)\s*\*{0,2}([A-Z0-9][^\n(:*]{1,55}?)\*{0,2}\s*\((\d{4})\)""")
        val seen = mutableSetOf<String>()
        val results = mutableListOf<AiMovieSuggestion>()
        for (match in pattern.findAll(briefingText)) {
            val title = match.groupValues[1].trim().trimEnd(':', '-', ' ')
            val year = match.groupValues[2].toIntOrNull() ?: 0
            if (title.length < 2 || year !in 1900..2100) continue
            val key = title.lowercase(Locale.ROOT)
            if (!seen.add(key)) continue
            val lineStart = briefingText.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            val lineEnd = briefingText.indexOf('\n', match.range.last).let { if (it < 0) briefingText.length else it }
            val lineText = briefingText.substring(lineStart, lineEnd).trim()
            val isSeries = lineText.contains("series", ignoreCase = true) ||
                lineText.contains("season", ignoreCase = true) ||
                lineText.contains("mùa", ignoreCase = true)
            results.add(
                AiMovieSuggestion(
                    title = title,
                    type = if (isSeries) "Series" else "Movie",
                    releaseYear = year,
                    reason = lineText.take(140)
                )
            )
            if (results.size >= 8) break
        }
        return results
    }

    private suspend fun enrichSuggestionsWithApis(
        suggestions: List<AiMovieSuggestion>,
        omdbKey: String,
        tmdbKey: String
    ): List<AiMovieSuggestion> {
        if (suggestions.isEmpty() || (omdbKey.isBlank() && tmdbKey.isBlank())) {
            return suggestions
        }
        return withContext(Dispatchers.IO) {
            suggestions.mapIndexed { index, item ->
                if (index >= 6) return@mapIndexed item
                runCatching {
                    val searchHit = if (tmdbKey.isNotBlank()) {
                        val hits = MediaApiClient.searchTmdb(item.title, tmdbKey)
                        hits.firstOrNull {
                            it.type.equals(item.type, ignoreCase = true) &&
                                (item.releaseYear == 0 || it.releaseYear == 0 || kotlin.math.abs(it.releaseYear - item.releaseYear) <= 1)
                        } ?: hits.firstOrNull()
                    } else null

                    val enriched = MediaApiClient.enrichFromApis(
                        title = searchHit?.title ?: item.title,
                        year = searchHit?.releaseYear?.takeIf { it > 0 } ?: item.releaseYear,
                        imdbId = searchHit?.imdbId.orEmpty(),
                        isSeries = item.type == "Series",
                        tmdbId = searchHit?.tmdbId,
                        omdbKey = omdbKey,
                        tmdbKey = tmdbKey
                    ) ?: searchHit

                    if (enriched != null) {
                        item.copy(
                            title = enriched.title.ifBlank { item.title },
                            releaseYear = enriched.releaseYear.takeIf { it > 0 } ?: item.releaseYear,
                            posterUrl = enriched.posterUrl.ifBlank { searchHit?.posterUrl.orEmpty() },
                            imdbId = enriched.imdbId,
                            imdbRating = enriched.imdbRating,
                            genres = enriched.genres,
                            plot = enriched.plot,
                            tmdbId = enriched.tmdbId ?: searchHit?.tmdbId
                        )
                    } else item
                }.getOrDefault(item)
            }
        }
    }
}
