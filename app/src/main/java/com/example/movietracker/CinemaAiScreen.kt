@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.movietracker

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun CinemaAiDiscoverScreen(
    viewModel: CinemaAiViewModel,
    allMovies: List<MovieItem>,
    omdbKey: String,
    tmdbKey: String,
    addingTitles: Set<String>,
    onAddToWatchlist: (AiMovieSuggestion) -> Unit,
    onOpenAddDialogWithTitle: (String) -> Unit,
    onOpenExistingEntry: (MovieItem) -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedMode by viewModel.selectedMode.collectAsStateWithLifecycle()
    var questionText by remember { mutableStateOf("") }

    val tasteSummary = remember(allMovies) {
        val ratedCount = allMovies.count { it.personalRating != null }
        val watchedCount = allMovies.count { it.status == MovieStatus.WATCHED }
        val topGenres = allMovies
            .filter { (it.personalRating ?: 0.0) >= 7.5 || it.status == MovieStatus.WATCHED }
            .flatMap { it.genres.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .joinToString(", ") { it.key }

        when {
            ratedCount > 0 && topGenres.isNotBlank() ->
                "Based on $ratedCount rated titles • Top: $topGenres"
            watchedCount > 0 && topGenres.isNotBlank() ->
                "Based on $watchedCount watched titles • Top: $topGenres"
            allMovies.isNotEmpty() ->
                "Using ${allMovies.size} titles in your library + web trends"
            else ->
                "Rate watched titles to sharpen your personal recommendations"
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadForMyTasteIfIdle(allMovies, omdbKey, tmdbKey)
    }

    fun submitQuestion(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        keyboardController?.hide()
        viewModel.askWithMyTaste(trimmed, allMovies, omdbKey, tmdbKey)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
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
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.Sparkles,
                                    contentDescription = null,
                                    tint = ImdbGold,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "For My Taste",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tasteSummary,
                                fontSize = 12.sp,
                                color = Color(0xFFD1D5DB),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = {
                                viewModel.loadForMyTaste(
                                    mode = selectedMode,
                                    userLibrary = allMovies,
                                    omdbKey = omdbKey,
                                    tmdbKey = tmdbKey,
                                    forceRefresh = true
                                )
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("ai_refresh_button")
                        ) {
                            Icon(
                                AppIcons.Refresh,
                                contentDescription = "Refresh recommendations",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(TasteMode.entries, key = { it.name }) { mode ->
                        val selected = selectedMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = {
                                viewModel.loadForMyTaste(
                                    mode = mode,
                                    userLibrary = allMovies,
                                    omdbKey = omdbKey,
                                    tmdbKey = tmdbKey
                                )
                            },
                            label = { Text(mode.label, fontSize = 12.sp) },
                            modifier = Modifier.testTag("ai_taste_mode_${mode.name.lowercase(Locale.ROOT)}")
                        )
                    }
                }
            }

            when (val state = uiState) {
                is CinemaAiUiState.Idle -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = TraktSurface),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    AppIcons.Sparkles,
                                    contentDescription = null,
                                    tint = TraktPurple,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Ready to find movies & series tailored to your ratings.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                is CinemaAiUiState.Loading -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = TraktSurface),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = TraktPurple
                                )
                                Column {
                                    Text(
                                        "Finding picks for \"${state.heading}\"...",
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        "Matching your ratings with trending releases",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                is CinemaAiUiState.Streaming -> {
                    item {
                        AiBriefingCard(
                            heading = state.heading,
                            bodyText = state.text,
                            isStreaming = true,
                            sources = emptyList(),
                            searchEntryHtml = null,
                            onOpenUrl = { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            }
                        )
                    }
                }

                is CinemaAiUiState.Error -> {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DroppedRed.copy(alpha = 0.14f)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, DroppedRed.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        AppIcons.Warning,
                                        contentDescription = null,
                                        tint = DroppedRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Couldn't load recommendations",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    state.message,
                                    fontSize = 12.sp,
                                    color = Color(0xFFFCA5A5)
                                )
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = {
                                        if (questionText.isNotBlank()) {
                                            submitQuestion(questionText)
                                        } else {
                                            viewModel.loadForMyTaste(
                                                mode = selectedMode,
                                                userLibrary = allMovies,
                                                omdbKey = omdbKey,
                                                tmdbKey = tmdbKey,
                                                forceRefresh = true
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("ai_retry_button")
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }

                is CinemaAiUiState.Success -> {
                    item {
                        AiBriefingCard(
                            heading = state.heading,
                            bodyText = state.summaryText,
                            isStreaming = false,
                            sources = state.sources,
                            searchEntryHtml = state.searchEntryHtml,
                            onOpenUrl = { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            }
                        )
                    }

                    if (state.suggestions.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Recommended Picks (${state.suggestions.size})",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    "Tap to save to Watchlist",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        items(
                            items = state.suggestions,
                            key = { "${it.title.lowercase(Locale.ROOT)}#${it.releaseYear}#${it.type}" }
                        ) { suggestion ->
                            val existingMatch = remember(suggestion, allMovies) {
                                findMatchingMovieInLibrary(suggestion, allMovies)
                            }
                            val key = "${suggestion.title.lowercase(Locale.ROOT)}#${suggestion.releaseYear}"
                            val isAdding = key in addingTitles

                            AiMovieSuggestionCard(
                                suggestion = suggestion,
                                existingMatch = existingMatch,
                                isAdding = isAdding,
                                onAddToWatchlist = { onAddToWatchlist(suggestion) },
                                onOpenAddDialog = { onOpenAddDialogWithTitle(suggestion.title) },
                                onOpenExisting = { existing -> onOpenExistingEntry(existing) }
                            )
                        }
                    }
                }
            }
        }

        // Compact input bar to refine taste or ask for a specific mood/genre
        Surface(
            color = Color(0xFF15161E),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = questionText,
                    onValueChange = { questionText = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_ask_input"),
                    placeholder = {
                        Text("Ask for a mood or genre (e.g. short thriller)...", fontSize = 13.sp)
                    },
                    leadingIcon = {
                        Icon(
                            AppIcons.Sparkles,
                            contentDescription = null,
                            tint = TraktPurple,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (questionText.isNotEmpty()) {
                            IconButton(onClick = { questionText = "" }) {
                                Icon(AppIcons.Close, contentDescription = "Clear prompt")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitQuestion(questionText) })
                )

                FilledIconButton(
                    onClick = { submitQuestion(questionText) },
                    enabled = questionText.isNotBlank() && uiState !is CinemaAiUiState.Loading,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = TraktPurple,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("ai_ask_send_button")
                ) {
                    Icon(
                        AppIcons.Send,
                        contentDescription = "Get tailored picks",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AiBriefingCard(
    heading: String,
    bodyText: String,
    isStreaming: Boolean,
    sources: List<WebSource>,
    searchEntryHtml: String?,
    onOpenUrl: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TraktSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    heading,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AnimatedVisibility(visible = isStreaming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = TraktPurple
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = bodyText,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Color(0xFFE5E7EB)
            )

            if (sources.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Sources",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sources.forEachIndexed { idx, src ->
                        Surface(
                            color = TraktSurfaceVar,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .clickable { onOpenUrl(src.uri) }
                                .testTag("ai_source_chip_$idx")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    AppIcons.OpenInNew,
                                    contentDescription = null,
                                    tint = WatchlistBlue,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    src.title,
                                    fontSize = 11.sp,
                                    color = Color(0xFF93C5FD),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            if (!searchEntryHtml.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                SearchEntryPointWebView(htmlContent = searchEntryHtml, onOpenUrl = onOpenUrl)
            }
        }
    }
}

@Composable
private fun SearchEntryPointWebView(
    htmlContent: String,
    onOpenUrl: (String) -> Unit
) {
    val wrappedHtml = remember(htmlContent) {
        """
        <html>
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <style>
              body { margin: 0; padding: 0; background: transparent; color: #E5E7EB; font-family: sans-serif; }
            </style>
          </head>
          <body>$htmlContent</body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString().orEmpty()
                        if (url.startsWith("http")) {
                            onOpenUrl(url)
                            return true
                        }
                        return false
                    }
                }
                loadDataWithBaseURL(null, wrappedHtml, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, wrappedHtml, "text/html", "UTF-8", null)
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp, max = 96.dp)
    )
}

@Composable
private fun AiMovieSuggestionCard(
    suggestion: AiMovieSuggestion,
    existingMatch: MovieItem?,
    isAdding: Boolean,
    onAddToWatchlist: () -> Unit,
    onOpenAddDialog: () -> Unit,
    onOpenExisting: (MovieItem) -> Unit
) {
    val cardTag = "ai_suggestion_card_${suggestion.title.lowercase(Locale.ROOT).replace(" ", "_")}"

    Card(
        colors = CardDefaults.cardColors(containerColor = TraktSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(cardTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            PosterThumbnail(
                posterUrl = suggestion.posterUrl,
                title = suggestion.title,
                modifier = Modifier
                    .width(64.dp)
                    .height(94.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = suggestion.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (suggestion.imdbRating != null) {
                        Spacer(Modifier.width(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Star, null, tint = ImdbGold, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(
                                String.format(Locale.US, "%.1f", suggestion.imdbRating),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ImdbGold
                            )
                        }
                    }
                }

                val meta = buildList {
                    add(suggestion.type)
                    if (suggestion.releaseYear > 0) add(suggestion.releaseYear.toString())
                    if (suggestion.genres.isNotBlank()) add(suggestion.genres.split(",").first().trim())
                }.joinToString(" • ")

                Text(
                    text = meta,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val highlight = suggestion.reason.ifBlank { suggestion.plot }
                if (highlight.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = highlight,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFFD1D5DB),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (existingMatch != null) {
                        val (statusText, statusColor) = when (existingMatch.status) {
                            MovieStatus.WATCHLIST -> "In Watchlist" to WatchlistBlue
                            MovieStatus.WATCHED -> "Watched" to WatchedGreen
                            MovieStatus.DROPPED -> "Dropped" to DroppedRed
                        }
                        FilledTonalButton(
                            onClick = { onOpenExisting(existingMatch) },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = statusColor.copy(alpha = 0.2f),
                                contentColor = statusColor
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("ai_open_existing_${suggestion.title}")
                        ) {
                            Icon(AppIcons.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(statusText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = onAddToWatchlist,
                            enabled = !isAdding,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WatchlistBlue,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("ai_add_watchlist_${suggestion.title}")
                        ) {
                            if (isAdding) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(AppIcons.Bookmark, null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(if (isAdding) "Adding..." else "+ Watchlist", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onOpenAddDialog,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("ai_edit_add_${suggestion.title}")
                        ) {
                            Icon(AppIcons.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Options", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun findMatchingMovieInLibrary(
    suggestion: AiMovieSuggestion,
    allMovies: List<MovieItem>
): MovieItem? {
    if (suggestion.imdbId.isNotBlank()) {
        allMovies.firstOrNull {
            it.imdbId.equals(suggestion.imdbId, ignoreCase = true)
        }?.let { return it }
    }
    val normTitle = suggestion.title.trim().lowercase(Locale.ROOT)
    return allMovies.firstOrNull { item ->
        item.title.trim().lowercase(Locale.ROOT) == normTitle &&
            (suggestion.releaseYear == 0 || item.releaseYear == 0 || kotlin.math.abs(item.releaseYear - suggestion.releaseYear) <= 1)
    }
}
