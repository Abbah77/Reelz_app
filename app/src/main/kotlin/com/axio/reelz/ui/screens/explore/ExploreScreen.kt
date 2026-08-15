package com.axio.reelz.ui.screens.explore

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.axio.reelz.data.model.*
import com.axio.reelz.app.Route
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import javax.inject.Inject
import java.util.Calendar

// ─────────────────────────────────────────────────────────────────────────────
// Explore is the discovery engine — distinct from Home (editorial rows) and
// Search (name lookup). It's for "I don't know what I want yet" moments:
// filter-first browsing across TMDB's full discover surface, plus one-tap
// mood presets that pre-fill filter combinations for people who don't want
// to fiddle with sliders.
// ─────────────────────────────────────────────────────────────────────────────

private const val CURRENT_YEAR = 2026 // fallback; real value read from Calendar at runtime

data class ExploreFilters(
    val mediaType: String = "MOVIE",          // "MOVIE" or "TV" — Explore is single-type per query (clearer mental model)
    val genreIds: Set<String> = emptySet(),    // multi-select, AND-matched
    val sortBy: String = "popularity.desc",
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val ratingFrom: Float? = null,
    val runtimeFrom: Int? = null,              // movies only
    val runtimeTo: Int? = null,                // movies only
    val language: String? = null,              // ISO 639-1: "ko","hi","tr","ja","zh","fr","en"
    val originCountry: String? = null,         // ISO 3166-1: "NG","ZA","US","GB","KR"
) {
    val isDefault: Boolean get() =
        genreIds.isEmpty() && sortBy == "popularity.desc" && yearFrom == null &&
        yearTo == null && ratingFrom == null && runtimeFrom == null && runtimeTo == null &&
        language == null && originCountry == null
}

/** A one-tap mood preset — pre-fills filters so users can dive in without configuring anything. */
data class MoodPreset(
    val label: String,
    val emoji: String,
    val apply: (ExploreFilters) -> ExploreFilters,
)

val moodPresets = listOf(
    MoodPreset("Hidden Gems", "💎") { it.copy(sortBy = "vote_average.desc", ratingFrom = 7.5f) },
    MoodPreset("This Decade", "🆕") { it.copy(yearFrom = 2020, sortBy = "popularity.desc") },
    MoodPreset("All-Time Greats", "🏆") { it.copy(sortBy = "vote_average.desc", ratingFrom = 8f) },
    MoodPreset("Quick Watch", "⚡") { it.copy(runtimeTo = 100, sortBy = "popularity.desc") },
    MoodPreset("Trending", "🔥") { it.copy(sortBy = "popularity.desc") },
    MoodPreset("Most Talked About", "💬") { it.copy(sortBy = "vote_count.desc") },
)

val sortOptions = listOf(
    "Most Popular"   to "popularity.desc",
    "Top Rated"      to "vote_average.desc",
    "Newest First"   to "primary_release_date.desc",
    "Oldest First"   to "primary_release_date.asc",
    "Most Discussed" to "vote_count.desc",
)

/** Explicit type instead of nested nullable Pairs — avoids Kotlin type-inference
 *  ambiguity when mixing literal `null` with `Int` values in a single list literal. */
data class RuntimeOption(val label: String, val from: Int?, val to: Int?)

val runtimeOptions = listOf(
    RuntimeOption("Any",        null, null),
    RuntimeOption("Under 90m",  null, 90),
    RuntimeOption("90–120m",    90,   120),
    RuntimeOption("2h+",        120,  null),
)

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val repo: com.axio.reelz.data.repository.CatalogRepository,
) : ViewModel() {

    data class UiState(
        val filters: ExploreFilters = ExploreFilters(),
        val genres: List<Genre> = emptyList(),
        val results: List<Media> = emptyList(),
        val page: Int = 1,
        val isLoading: Boolean = true,
        val isLoadingMore: Boolean = false,
        val isBackgroundRefreshing: Boolean = false,
        val hasMore: Boolean = true,
        val error: String? = null,
        val activeMood: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val result = repo.getGenres("movie")
                if (result is com.axio.reelz.core.network.NetworkResult.Success) {
                    _ui.update { it.copy(genres = result.data) }
                }
            } catch (_: Exception) {}
        }
        runQuery()
    }

    /**
     * Cache-first query strategy:
     *
     *  1. Query Room immediately — zero latency, zero TMDB calls.
     *     If >= 15 results: show them, fire TMDB silently in background to patch/extend.
     *     If < 15 results:  show what cache has (may be 0), show loading indicator, fire TMDB.
     *
     *  2. TMDB fires with exponential-backoff retry (0ms -> 500ms -> 1500ms) to survive
     *     rate-limit bursts from the 21 concurrent Home batch calls on app start.
     *
     *  3. Filters that cache CANNOT answer (runtimeFrom/To, originCountry) always trigger
     *     a full TMDB call regardless of cache size — those fields are not stored in CachedMedia.
     *
     *  loadMore() always goes to TMDB — the cache only covers one "page" of results.
     */
    private fun runQuery(resetPage: Boolean = true) {
        viewModelScope.launch {
            val f = _ui.value.filters
            _ui.update { it.copy(error = null, page = if (resetPage) 1 else it.page) }

            if (resetPage) {
                // ── Tier 1: query cache ───────────────────────────────────────
                // Skip cache for filters we cannot answer locally.
                val cacheUnsupported = f.runtimeFrom != null || f.runtimeTo != null || f.originCountry != null
                val cacheResults: List<Media> = if (!cacheUnsupported) {
                    try {
                        emptyList()
                    } catch (_: Exception) { emptyList() }
                } else emptyList()

                val cacheAdequate = cacheResults.size >= 15

                if (cacheResults.isNotEmpty()) {
                    // Show cache immediately — no spinner for the user
                    _ui.update {
                        it.copy(
                            results                = cacheResults,
                            isLoading              = false,
                            isBackgroundRefreshing = cacheAdequate,
                        )
                    }
                } else {
                    // No cache — show full loading state
                    _ui.update { it.copy(isLoading = true) }
                }

                // ── Tier 2: TMDB (silent if cache was adequate, visible otherwise) ──
                val retryDelaysMs = longArrayOf(0L, 500L, 1500L)
                var lastException: Exception? = null
                for (delayMs in retryDelaysMs) {
                    if (delayMs > 0) delay(delayMs)
                    try {
                        val items = fetchFromTmdb(f, page = 1)
                        _ui.update {
                            it.copy(
                                results                = items,
                                page                   = 1,
                                isLoading              = false,
                                isLoadingMore          = false,
                                isBackgroundRefreshing = false,
                                hasMore                = items.isNotEmpty(),
                            )
                        }
                        return@launch
                    } catch (e: Exception) {
                        lastException = e
                    }
                }

                // All retries exhausted
                if (cacheResults.isNotEmpty()) {
                    // Cache is still showing — swallow the TMDB error silently
                    _ui.update { it.copy(isLoading = false, isBackgroundRefreshing = false) }
                } else {
                    _ui.update {
                        it.copy(
                            isLoading              = false,
                            isLoadingMore          = false,
                            isBackgroundRefreshing = false,
                            error                  = friendlyExploreError(lastException!!),
                        )
                    }
                }

            } else {
                // ── loadMore: cache-first, TMDB as fallback ───────────────────
                // Strategy:
                //   1. Try the Explore cache (Room) for items user hasn't seen yet.
                //      If ≥ 12 new items found → show instantly, no TMDB call.
                //   2. If cache is thin → fall through to TMDB with retry.
                //   3. On TMDB failure but cache had results → silently swallow error.
                //   4. On total failure (no cache + TMDB down) → show non-breaking error.
                val shownIds = _ui.value.results.map { it.id }.toSet()
                val cacheUnsupported = f.runtimeFrom != null || f.runtimeTo != null || f.originCountry != null

                val cacheMore: List<Media> = if (!cacheUnsupported) {
                    try {
                        emptyList<Media>().filter { it.id !in shownIds }
                    } catch (_: Exception) { emptyList() }
                } else emptyList()

                if (cacheMore.size >= 12) {
                    // Enough cache to serve a full visual "page" — no TMDB call
                    _ui.update {
                        it.copy(
                            results       = (it.results + cacheMore)
                                .associateBy<Media, String> { item -> item.id }.values.toList(),
                            isLoading     = false,
                            isLoadingMore = false,
                            hasMore       = true,
                        )
                    }
                    return@launch
                }

                // Cache insufficient → TMDB with exponential-backoff retry
                val retryDelaysMs = longArrayOf(0L, 500L, 1500L)
                var lastException: Exception? = null
                for (delayMs in retryDelaysMs) {
                    if (delayMs > 0) delay(delayMs)
                    try {
                        val page = _ui.value.page
                        val items = fetchFromTmdb(f, page)
                        _ui.update {
                            it.copy(
                                // Deduplicate: TMDB ranked pages can overlap when rankings shift
                                // between calls. associateBy keeps the freshest entry per tmdbId.
                                results       = (it.results + items)
                                    .associateBy<Media, String> { item -> item.id }
                                    .values.toList(),
                                page          = page,
                                isLoading     = false,
                                isLoadingMore = false,
                                hasMore       = items.isNotEmpty(),
                            )
                        }
                        return@launch
                    } catch (e: Exception) {
                        lastException = e
                    }
                }
                // All retries exhausted
                if (cacheMore.isNotEmpty()) {
                    // Show what cache gave us, swallow TMDB error silently
                    _ui.update {
                        it.copy(
                            results       = (it.results + cacheMore)
                                .associateBy<Media, String> { item -> item.id }.values.toList(),
                            isLoading     = false,
                            isLoadingMore = false,
                            hasMore       = false,
                        )
                    }
                } else {
                    // Nothing to show at all — show a dismissable error, no crash
                    _ui.update {
                        it.copy(
                            isLoading     = false,
                            isLoadingMore = false,
                            error         = friendlyExploreError(lastException!!),
                        )
                    }
                }
            }
        }
    }

    /** Execute the TMDB discover call for the given filters and page. */
    private suspend fun fetchFromTmdb(f: ExploreFilters, page: Int): List<Media> {
        val mediaType = if (f.mediaType == "MOVIE") "movie" else "tv"
        val genre = f.genreIds.firstOrNull()
        // Map dot-notation sort values to backend word-form values
        val sortBy = when (f.sortBy) {
            "popularity.desc"             -> "popularity"
            "vote_average.desc"           -> "rating"
            "primary_release_date.desc",
            "first_air_date.desc"         -> "newest"
            "primary_release_date.asc",
            "first_air_date.asc"          -> "oldest"
            "vote_count.desc"             -> "rating"   // best approximation
            else                          -> "popularity"
        }
        val result = repo.discover(
            mediaType = mediaType,
            genre     = genre,
            language  = f.language,
            sortBy    = sortBy,
            yearFrom  = f.yearFrom,
            yearTo    = f.yearTo,
            ratingMin = f.ratingFrom,
            cursor    = null,
            limit     = 20,
        )
        return when (result) {
            is com.axio.reelz.core.network.NetworkResult.Success -> result.data.first
            else -> emptyList()
        }
    }

    fun loadMore() {
        val st = _ui.value
        if (st.isLoading || st.isLoadingMore || !st.hasMore) return
        // Atomically flip isLoadingMore and bump the page so a second call that arrives
        // before recomposition sees isLoadingMore=true and bails out.
        _ui.update { it.copy(isLoadingMore = true, page = it.page + 1) }
        runQuery(resetPage = false)
    }

    fun setMediaType(type: String) {
        _ui.update { it.copy(filters = it.filters.copy(mediaType = type), activeMood = null) }
        runQuery()
    }

    fun toggleGenre(id: String) {
        val current = _ui.value.filters.genreIds
        val updated = if (id in current) current - id else current + id
        _ui.update { it.copy(filters = it.filters.copy(genreIds = updated), activeMood = null) }
        runQuery()
    }

    fun setSort(sort: String) {
        _ui.update { it.copy(filters = it.filters.copy(sortBy = sort), activeMood = null) }
        runQuery()
    }

    fun setYearRange(from: Int?, to: Int?) {
        _ui.update { it.copy(filters = it.filters.copy(yearFrom = from, yearTo = to), activeMood = null) }
        runQuery()
    }

    fun setMinRating(rating: Float?) {
        _ui.update { it.copy(filters = it.filters.copy(ratingFrom = rating), activeMood = null) }
        runQuery()
    }

    fun setRuntime(from: Int?, to: Int?) {
        _ui.update { it.copy(filters = it.filters.copy(runtimeFrom = from, runtimeTo = to), activeMood = null) }
        runQuery()
    }

    /**
     * Mood preset — always serves cache first (instant, no spinner), then patches silently.
     * Mood presets use only filters the cache can answer, so cache hits are near-certain
     * after the first app session.
     */
    fun applyMood(preset: MoodPreset) {
        _ui.update {
            it.copy(
                filters    = preset.apply(ExploreFilters(mediaType = it.filters.mediaType)),
                activeMood = preset.label,
            )
        }
        runQuery()
    }

    fun clearFilters() {
        _ui.update { it.copy(filters = ExploreFilters(mediaType = it.filters.mediaType), activeMood = null) }
        runQuery()
    }
}


@Composable
fun ExploreScreen(nav: NavController, vm: ExploreViewModel = hiltViewModel()) {
    val d = LocalDimensions.current
    val ui by vm.ui.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }

    val activeFilterCount = with(ui.filters) {
        genreIds.size + listOf(yearFrom, yearTo, ratingFrom, runtimeFrom, runtimeTo).count { it != null } +
            (if (sortBy != "popularity.desc") 1 else 0)
    }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {

        // ── Header — same blue brand identity, compass mark for Explore ──────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.heroPadding - d.spaceSm, vertical = d.spaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Explore",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp
                    )
                )
                Text(
                    "Find your next watch",
                    color = Brand.copy(.85f), fontSize = d.textSm, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(IconCompass, null, tint = Brand.copy(.8f), modifier = Modifier.size(d.iconLg))
        }

        // ── Background-refresh indicator — thin animated sweep under header ─
        // Visible only when cache is displayed and TMDB is silently patching it.
        // Mirrors the BrowseScreen indicator: 2dp line, barely intrusive.
        if (ui.isBackgroundRefreshing) {
            val inf   = androidx.compose.animation.core.rememberInfiniteTransition(label = "exploreRefresh")
            val sweep by inf.animateFloat(
                0f, 1f,
                androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(1400,
                        easing = androidx.compose.animation.core.LinearEasing)
                ),
                label = "exploreSweep",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colorStops = arrayOf(
                                (sweep - 0.35f).coerceIn(0f, 1f) to androidx.compose.ui.graphics.Color.Transparent,
                                sweep.coerceIn(0f, 1f)           to Brand.copy(0.9f),
                                (sweep + 0.35f).coerceIn(0f, 1f) to androidx.compose.ui.graphics.Color.Transparent,
                            )
                        )
                    )
            )
        }

        // ── Movie / TV switch — primary axis, large and obvious ─────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
        ) {
            TypeSwitchPill("Movies", ui.filters.mediaType == "MOVIE", Modifier.weight(1f)) { vm.setMediaType("MOVIE") }
            TypeSwitchPill("TV Shows", ui.filters.mediaType == "TV", Modifier.weight(1f)) { vm.setMediaType("TV") }
        }

        Spacer(Modifier.height(d.spaceLg - d.spaceXxs))

        // ── Mood presets — one-tap discovery, lowers the barrier to explore ──
        Text(
            "Quick picks",
            color = White40, fontSize = d.textXs, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = d.heroPadding - d.spaceXs),
        )
        Spacer(Modifier.height(d.spaceSm + d.spaceXxs))
        LazyRow(
            contentPadding = PaddingValues(horizontal = d.screenHorizPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
        ) {
            items(moodPresets) { mood ->
                MoodChip(mood, ui.activeMood == mood.label) { vm.applyMood(mood) }
            }
        }

        Spacer(Modifier.height(d.spaceMd - d.spaceXxs))

        // ── Filter bar — genre chips (scrollable) + filter button ───────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp),
            ) {
                items(ui.genres) { g ->
                    SmallFilterChip(g.name, g.id in ui.filters.genreIds) { vm.toggleGenre(g.id) }
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusMd - d.spaceSm))
                    .background(if (activeFilterCount > 0) Brand.copy(.18f) else GlassMd)
                    .border(1.dp, if (activeFilterCount > 0) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceSm))
                    .clickable { showFilterSheet = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceXs + 1.dp)) {
                    Icon(IconFilter, null, tint = if (activeFilterCount > 0) Brand else White60, modifier = Modifier.size(d.iconSm + 3.dp))
                    if (activeFilterCount > 0) {
                        Box(Modifier.size(d.iconMd - 4.dp).clip(CircleShape).background(Brand), Alignment.Center) {
                            Text("$activeFilterCount", color = Color.White, fontSize = d.textXxs, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(d.spaceMd))

        // ── Results grid ───────────────────────────────────────────────────
        // isLoading = true only when cache was empty AND TMDB hasn't returned yet.
        // isBackgroundRefreshing = true means cache is shown, TMDB is patching — no spinner.
        when {
            ui.isLoading && !ui.isBackgroundRefreshing -> Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(d.spaceSm))
                SkeletonGridLoader(count = 9, columns = 3)
            }
            ui.error != null && ui.results.isEmpty() -> ErrorState(ui.error!!, onRetry = { vm.applyMood(moodPresets[4]) })
            ui.results.isEmpty() -> EmptyExploreState(onClear = vm::clearFilters)
            else -> {
                val gridState = rememberLazyGridState()
                LaunchedEffect(gridState, ui.results.size) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .collect { lastVisible ->
                            if (lastVisible >= ui.results.size - 6) vm.loadMore()
                        }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = d.spaceMd - d.spaceXxs, vertical = d.sectionVertPad),
                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
                    verticalArrangement   = Arrangement.spacedBy(d.spaceMd),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(ui.results, key = { it.id }) { m ->
                        MediaPosterCard(
                            media   = m,
                            onClick = { nav.navigate(Route.Detail.go(m.id, m.mediaType)) },
                            modifier = Modifier.aspectRatio(0.65f),
                        )
                    }
                    // ── Infinite scroll: skeleton grid cards while TMDB loads next page ──
                    // Three skeleton cards (one full row) shimmer independently with
                    // staggered phase offsets — gives a ripple-wave feel without
                    // jarring the user with a spinner that hides what's coming next.
                    if (ui.isLoadingMore) {
                        items(3, key = { "skGrid_$it" }) { idx ->
                            SkeletonGridCard(
                                modifier = Modifier.aspectRatio(0.65f),
                                phaseOffset = idx * 0.33f,
                            )
                        }
                    }
                    item(span = { GridItemSpan(3) }) { Spacer(Modifier.height(d.avatarLg + d.spaceLg)) }
                }
            }
        }
    }

    if (showFilterSheet) {
        ExploreFilterSheet(
            filters      = ui.filters,
            currentYear  = currentYear,
            onYearRange  = vm::setYearRange,
            onRating     = vm::setMinRating,
            onRuntime    = vm::setRuntime,
            onSort       = vm::setSort,
            onClear      = vm::clearFilters,
            onDismiss    = { showFilterSheet = false },
        )
    }
}

// ExploreEmptyState moved to FeedComponents.kt as EmptyExploreState for shared reuse

// ── Type switch (Movies / TV) — large pill, primary navigation axis ─────────
@Composable
fun TypeSwitchPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val d = LocalDimensions.current
    Box(
        modifier
            .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
            .background(
                if (selected) Brush.horizontalGradient(listOf(Brand.copy(.85f), Brand2.copy(.85f)))
                else Brush.horizontalGradient(listOf(BgRaised, BgSurface))
            )
            .border(1.dp, if (selected) Brand.copy(.6f) else GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXxs))
            .clickable(onClick = onClick)
            .padding(vertical = d.spaceMd + 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.White else White60, fontSize = d.textMd,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

// ── Mood preset chip ──────────────────────────────────────────────────────────
@Composable
fun MoodChip(mood: MoodPreset, selected: Boolean, onClick: () -> Unit) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .clip(RoundedCornerShape(d.radiusPill))
            .background(
                if (selected) Brush.horizontalGradient(listOf(Brand.copy(.3f), Brand2.copy(.3f)))
                else Brush.horizontalGradient(listOf(BgRaised, BgSurface))
            )
            .border(1.dp, if (selected) Brand.copy(.6f) else GlassBorderMd, RoundedCornerShape(d.radiusPill))
            .clickable(onClick = onClick)
            .padding(horizontal = d.screenHorizPad - d.spaceXxs, vertical = d.spaceMd - d.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
    ) {
        Text(mood.emoji, fontSize = d.textMd)
        Text(mood.label, color = if (selected) White else White60, fontSize = d.textSm,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

// ── Filter bottom sheet — year range, rating floor, runtime, sort ───────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreFilterSheet(
    filters: ExploreFilters,
    currentYear: Int,
    onYearRange: (Int?, Int?) -> Unit,
    onRating: (Float?) -> Unit,
    onRuntime: (Int?, Int?) -> Unit,
    onSort: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val d = LocalDimensions.current
    var yearRange by remember(filters.yearFrom, filters.yearTo) {
        mutableStateOf((filters.yearFrom ?: 1970).toFloat()..(filters.yearTo ?: currentYear).toFloat())
    }
    var ratingFloor by remember(filters.ratingFrom) { mutableStateOf(filters.ratingFrom ?: 0f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        dragHandle = { Box(Modifier.padding(top = d.spaceMd).width(d.shimmerBarWidth).height(d.shimmerBarHeight).clip(RoundedCornerShape(2.dp)).background(GlassBorderHv)) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = d.heroPadding - d.spaceSm, vertical = d.spaceMd - d.spaceXxs).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(d.spaceXl - d.spaceXs),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(IconFilter, null, tint = Brand, modifier = Modifier.size(d.iconMd - 2.dp))
                Spacer(Modifier.width(d.spaceSm + d.spaceXxs))
                Text("Refine results", color = White, fontWeight = FontWeight.Bold, fontSize = (d.textXl.value - 1).sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Reset", color = White60, fontSize = d.textMd) }
            }

            // Sort
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
                Text("Sort by", color = White60, fontSize = d.textXs, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp)) {
                    items(sortOptions) { (label, value) ->
                        SmallFilterChip(label, filters.sortBy == value, accent = Brand) { onSort(value) }
                    }
                }
            }

            // Year range
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Release year", color = White60, fontSize = d.textXs, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    Text("${yearRange.start.toInt()} – ${yearRange.endInclusive.toInt()}", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.Bold)
                }
                RangeSlider(
                    value = yearRange,
                    onValueChange = { yearRange = it },
                    onValueChangeFinished = { onYearRange(yearRange.start.toInt(), yearRange.endInclusive.toInt()) },
                    valueRange = 1950f..currentYear.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Brand, activeTrackColor = Brand, inactiveTrackColor = GlassMd,
                    ),
                )
            }

            // Rating floor
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Minimum rating", color = White60, fontSize = d.textXs, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp)) {
                        Icon(IconStar, null, tint = Gold, modifier = Modifier.size(d.iconSm))
                        Text(if (ratingFloor > 0f) "%.1f+".format(ratingFloor) else "Any", color = Gold, fontSize = d.textSm, fontWeight = FontWeight.Bold)
                    }
                }
                Slider(
                    value = ratingFloor,
                    onValueChange = { ratingFloor = it },
                    onValueChangeFinished = { onRating(if (ratingFloor > 0f) ratingFloor else null) },
                    valueRange = 0f..9f,
                    steps = 17,
                    colors = SliderDefaults.colors(
                        thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = GlassMd,
                    ),
                )
            }

            // Runtime (movies only — visually de-emphasized for TV but harmless if shown)
            if (filters.mediaType == "MOVIE") {
                Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
                    Text("Runtime", color = White60, fontSize = d.textXs, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp)) {
                        items(runtimeOptions) { option ->
                            val isSelected = filters.runtimeFrom == option.from && filters.runtimeTo == option.to
                            SmallFilterChip(option.label, isSelected, accent = Brand) { onRuntime(option.from, option.to) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(d.spaceSm + d.spaceXxs))
        }
    }
}

// ── Local filter chip — accent color defaults to Brand, kept overridable ───
@Composable
fun SmallFilterChip(label: String, selected: Boolean, accent: Color = Brand, onClick: () -> Unit) {
    val d = LocalDimensions.current
    Box(
        Modifier
            .clip(RoundedCornerShape(d.radiusSm + d.spaceXxs))
            .background(if (selected) Brush.horizontalGradient(listOf(accent.copy(.5f), accent.copy(.8f)))
                        else Brush.horizontalGradient(listOf(BgSurface, BgOverlay)))
            .border(1.dp, if (selected) accent.copy(.6f) else GlassBorder, RoundedCornerShape(d.radiusSm + d.spaceXxs))
            .clickable(onClick = onClick)
            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + 1.dp),
    ) {
        Text(label, color = if (selected) Color.White else White60, fontSize = d.textSm,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun friendlyExploreError(e: Exception): String {
    val msg = e.message?.lowercase() ?: ""
    return when {
        msg.contains("unable to resolve host") ||
        msg.contains("network") ||
        msg.contains("timeout") ||
        msg.contains("connect") -> "No internet connection. Check your connection and try again."
        else -> "Couldn't load content. Pull down to try again."
    }
}
