package com.reelz.ui.screens.browse

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.reelz.ads.AdEngine
import com.reelz.ads.NativeAdCard
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.reelz.BuildConfig
import com.reelz.data.model.*
import com.reelz.data.repository.MediaRepository
import com.reelz.brain.TasteEngine
import com.reelz.brain.TmdbGenreMap
import com.reelz.brain.UserAction
import com.reelz.ui.Route
import com.reelz.ui.components.*
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.roundToInt

// ── ViewModel ─────────────────────────────────────────────────────────────────

sealed class FeedRow {
    // ── Existing rows ──────────────────────────────────────────────────────────
    data class Section(val section: HomeSection) : FeedRow()
    data class InfinitePage(val items: List<Media>, val page: Int) : FeedRow()
    data class MoodRow(val mood: String, val items: List<Media>) : FeedRow()
    object NativeAdPlacement : FeedRow()

    // ── Discovery rows (Anti-Filter-Bubble Architecture) ───────────────────────

    /** Zone 2: Social proof — same for all users. Pure FOMO. */
    data class TrendingRow(val items: List<Media>) : FeedRow()

    /** Zone 2: Quality + scarcity. High rating, low vote count. */
    data class HiddenGems(val items: List<Media>) : FeedRow()

    /** Zone 2: Language and cultural discovery. */
    data class WorldCinema(val items: List<Media>) : FeedRow()

    /** Zone 2: Trusted external authority signal. */
    data class AwardWinners(val items: List<Media>) : FeedRow()

    /**
     * Zone 3: Labeled exploration. Honest about what it is.
     * "You've Never Tried Horror — Here's the Best"
     */
    data class BraveExplore(
        val genreLabel: String,
        val headline: String,
        val items: List<Media>,
    ) : FeedRow()
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: MediaRepository,
    private val tasteEngine: TasteEngine,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val featured: List<Media> = emptyList(),
        val feedRows: List<FeedRow> = emptyList(),
        val genres: List<Genre> = emptyList(),
        val selectedGenreId: Int? = null,
        val genreItems: List<Media> = emptyList(),
        val genrePage: Int = 1,
        val isGenreLoading: Boolean = false,
        val hasMoreGenrePages: Boolean = true,
        val continueWatching: List<WatchHistory> = emptyList(),
        val isLoadingMore: Boolean = false,
        val isCacheLoaded: Boolean = false,
        val moodLabel: String? = null,  // e.g., "😱 Scary tonight"
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var infinitePage = 1
    private var isInfiniteExhausted = false
    private var categorySectionsEmitted = false

    init {
        load(forceRefresh = false)

        viewModelScope.launch {
            repo.getHistory().collect { h ->
                _ui.update { it.copy(continueWatching = h) }
            }
        }

        // React to taste profile changes (e.g., after onboarding completes)
        viewModelScope.launch {
            tasteEngine.profile.drop(1).collect { // drop(1) = skip initial value
                try {
                    // Re-sort existing feed if profile changes significantly
                    _ui.update { state ->
                        val reranked = rerankFeed(state.feedRows)
                        state.copy(feedRows = reranked)
                    }
                } catch (_: Exception) {
                    // Re-ranking is a nice-to-have — never let it break the feed
                }
            }
        }
    }

    fun load(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            if (forceRefresh) _ui.update { it.copy(isRefreshing = true, error = null) }
            else _ui.update { it.copy(isLoading = true, error = null) }

            infinitePage = 1
            isInfiniteExhausted = false
            categorySectionsEmitted = false

            try {
                // ── Parallel fetches ──────────────────────────────────────────
                // Home sections + discovery data all in parallel — no sequential bottleneck.
                val sectionsDeferred     = async { repo.getHomeSections(forceRefresh) }
                val genresDeferred       = async { runCatching { repo.getMovieGenres() }.getOrElse { emptyList() } }
                val trendingDeferred     = async { runCatching { repo.getTrending() }.getOrElse { emptyList() } }
                val hiddenGemsDeferred   = async { runCatching { repo.getHiddenGems() }.getOrElse { emptyList() } }
                val worldCinemaDeferred  = async { runCatching { repo.getWorldCinema(tasteEngine.getUnexploredGenres()) }.getOrElse { emptyList() } }
                val awardsDeferred       = async { runCatching { repo.getAwardWinners() }.getOrElse { emptyList() } }

                val sections        = sectionsDeferred.await()
                val genres          = genresDeferred.await()
                val trendingItems   = trendingDeferred.await()
                val hiddenGems      = hiddenGemsDeferred.await()
                    .sortedByDescending { tasteEngine.discoveryScore(it) }
                val worldCinema     = worldCinemaDeferred.await()
                    .sortedByDescending { tasteEngine.discoveryScore(it) }
                val awardItems      = awardsDeferred.await()

                // ── Taste-aware section reordering ────────────────────────────
                val rankedSections = rankSections(sections)

                // ── Mood row ──────────────────────────────────────────────────
                val tasteCard = tasteEngine.getTasteCard()
                val moodRow: FeedRow.MoodRow? = if (
                    tasteCard.isOnboarded && tasteCard.totalWatched >= 5 && tasteCard.dominantMood != null
                ) {
                    val moodGenreKey = moodKeyFromLabel(tasteCard.dominantMood)
                    val moodItems = sections.flatMap { it.items }
                        .filter { media ->
                            val genreMap = if (media.mediaType == MediaType.TV)
                                TmdbGenreMap.tvGenres else TmdbGenreMap.movieGenres
                            moodGenreKey in media.genreIds.mapNotNull { genreMap[it] }
                        }
                        .take(20)
                        .let { tasteEngine.rankMedia(it) }
                    if (moodItems.isNotEmpty()) FeedRow.MoodRow(tasteCard.dominantMood, moodItems) else null
                } else null

                // ── Hero banner: taste-ranked top items ───────────────────────
                val allItems = sections.flatMap { it.items }
                val featured = tasteEngine.rankMedia(allItems).take(6)
                    .ifEmpty { sections.firstOrNull()?.items?.take(6) ?: emptyList() }

                // ── Smart feed: 3-zone Discovery Architecture ─────────────────
                val maturity  = tasteEngine.tasteMaturity()
                val diversity = tasteEngine.genreDiversityScore()
                val unexplored = tasteEngine.getUnexploredGenres()

                val feedRows = buildSmartFeed(
                    sections         = rankedSections,
                    moodRow          = moodRow,
                    trendingItems    = trendingItems,
                    hiddenGems       = hiddenGems,
                    worldCinemaItems = worldCinema,
                    awardItems       = awardItems,
                    maturity         = maturity,
                    diversity        = diversity,
                    unexploredGenres = unexplored,
                )

                _ui.update {
                    it.copy(
                        isLoading    = false,
                        isRefreshing = false,
                        feedRows     = feedRows,
                        featured     = featured,
                        genres       = genres,
                        isCacheLoaded = true,
                        moodLabel    = tasteCard.dominantMood,
                    )
                }
                categorySectionsEmitted = true

            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, isRefreshing = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    /**
     * Three-Zone Discovery Feed Builder.
     *
     * Zone 1 (Comfort 40%)   — known genres, user feels understood
     * Zone 2 (Warm 35%)      — adjacent genres, quality signals, social proof
     * Zone 3 (Brave 25%)     — unexplored, honest labels, chosen freely
     *
     * Ratios shift dynamically based on taste maturity + bubble detection.
     */
    private fun buildSmartFeed(
        sections: List<HomeSection>,
        moodRow: FeedRow.MoodRow?,
        trendingItems: List<Media>,
        hiddenGems: List<Media>,
        worldCinemaItems: List<Media>,
        awardItems: List<Media>,
        maturity: Float,
        diversity: Float,
        unexploredGenres: List<String>,
    ): List<FeedRow> = buildList {

        // ════════════════════════════════════════════════════
        // ZONE 1 — COMFORT (personalised, user feels seen)
        // ════════════════════════════════════════════════════

        // Mood row — personal, time-aware, always leads
        moodRow?.let { add(it) }

        // Top 2 personalised genre sections
        sections.getOrNull(0)?.let { add(FeedRow.Section(it)) }
        sections.getOrNull(1)?.let { add(FeedRow.Section(it)) }

        // ════════════════════════════════════════════════════
        // ZONE 2 — WARM DISCOVERY (quality signals + social proof)
        // ════════════════════════════════════════════════════

        // Trending — social FOMO, identical for all users (Netflix's secret weapon)
        if (trendingItems.isNotEmpty()) add(FeedRow.TrendingRow(trendingItems))

        // Third personalised section
        sections.getOrNull(2)?.let { add(FeedRow.Section(it)) }

        // Hidden Gems — treasure hunt psychology
        if (hiddenGems.isNotEmpty()) add(FeedRow.HiddenGems(hiddenGems))

        add(FeedRow.NativeAdPlacement)

        // Fourth personalised section
        sections.getOrNull(3)?.let { add(FeedRow.Section(it)) }

        // World Cinema — cultural discovery
        if (worldCinemaItems.isNotEmpty()) add(FeedRow.WorldCinema(worldCinemaItems))

        // Award Winners — trusted authority bypasses "algorithm chose this" feeling
        if (awardItems.isNotEmpty()) add(FeedRow.AwardWinners(awardItems))

        // ════════════════════════════════════════════════════
        // ZONE 3 — BRAVE EXPLORATION (honest labels, user's choice)
        // Show when mature enough OR when in a narrow bubble
        // ════════════════════════════════════════════════════

        val showExplore = maturity > 0.3f || diversity < 0.3f
        if (showExplore && unexploredGenres.isNotEmpty()) {
            val genre = unexploredGenres.first()
            val genreLabel = genre.replaceFirstChar { it.uppercase() }
            // Items will be populated dynamically from existing section data
            val braveItems = sections.flatMap { it.items }
                .filter { media ->
                    val genreMap = if (media.mediaType == MediaType.TV)
                        TmdbGenreMap.tvGenres else TmdbGenreMap.movieGenres
                    genre in media.genreIds.mapNotNull { genreMap[it] }
                }
                .sortedByDescending { tasteEngine.discoveryScore(it) }
                .take(15)
            if (braveItems.isNotEmpty()) {
                add(FeedRow.BraveExplore(
                    genreLabel = genreLabel,
                    headline   = "You've Never Tried $genreLabel — Here's the Best",
                    items      = braveItems,
                ))
            }
        }

        add(FeedRow.NativeAdPlacement)

        // Remaining personalised sections after exploration
        sections.drop(4).forEachIndexed { i, s ->
            add(FeedRow.Section(s))
            if ((i + 1) % 3 == 0) add(FeedRow.NativeAdPlacement)
        }
    }

    // ── Rank sections by user taste ───────────────────────────────────────────
    private fun rankSections(sections: List<HomeSection>): List<HomeSection> {
        val profile = tasteEngine.profile.value
        if (profile.totalInteractions < 5) return sections // Not enough data yet

        // Score each section by averaging the taste scores of its items
        return sections.sortedByDescending { section ->
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            section.items.take(5).map { media ->
                profile.scoreMedia(
                    genreIds         = media.genreIds,
                    originalLanguage = media.originalLanguage,
                    mediaType        = media.mediaType.name,
                    isAnime          = TmdbGenreMap.isAnime(media.originalLanguage, media.genreIds),
                    currentHour      = hour,
                    voteAverage      = media.voteAverage,
                    popularity       = media.popularity,
                )
            }.average()
        }.map { section ->
            // Also re-rank items within each section
            section.copy(items = tasteEngine.rankMedia(section.items))
        }
    }

    // ── Re-rank existing feed without re-fetching ─────────────────────────────
    private fun rerankFeed(rows: List<FeedRow>): List<FeedRow> {
        return rows.map { row ->
            when (row) {
                is FeedRow.Section      -> row.copy(section = row.section.copy(items = tasteEngine.rankMedia(row.section.items)))
                is FeedRow.InfinitePage -> row.copy(items = tasteEngine.rankMedia(row.items))
                is FeedRow.MoodRow      -> row.copy(items = tasteEngine.rankMedia(row.items))
                is FeedRow.TrendingRow  -> row // Trending intentionally bypasses taste — don't re-rank
                is FeedRow.HiddenGems   -> row.copy(items = row.items.sortedByDescending { tasteEngine.discoveryScore(it) })
                is FeedRow.WorldCinema  -> row.copy(items = row.items.sortedByDescending { tasteEngine.discoveryScore(it) })
                is FeedRow.AwardWinners -> row // Award winners rank by quality, not taste
                is FeedRow.BraveExplore -> row.copy(items = row.items.sortedByDescending { tasteEngine.discoveryScore(it) })
                else                    -> row
            }
        }
    }

    // ── Infinite scroll — taste-biased discovery ──────────────────────────────
    fun loadMoreInfinite() {
        if (_ui.value.isLoadingMore || isInfiniteExhausted) return
        viewModelScope.launch {
            _ui.update { it.copy(isLoadingMore = true) }
            try {
                val nextPage = infinitePage + 1
                val profile = tasteEngine.profile.value

                // Pick the media type based on what user tends to watch more
                val tvScore = profile.genres["anime"]?.effectiveScore?.toDouble() ?: 0.0

                val items: List<Media> = when {
                    // If user loves anime, inject anime pages
                    tvScore > 30.0 && nextPage % 3 == 0 -> repo.getAnime(nextPage)
                    nextPage % 2 == 0 -> repo.discoverMovies(genreId = null, page = nextPage)
                    else -> repo.discoverTv(genreId = null, page = nextPage)
                }

                if (items.isEmpty()) {
                    isInfiniteExhausted = true
                    _ui.update { it.copy(isLoadingMore = false) }
                    return@launch
                }

                infinitePage = nextPage
                // Rank the page before inserting
                val ranked = tasteEngine.rankMedia(items)
                val newRow = FeedRow.InfinitePage(ranked, nextPage)
                _ui.update { st -> st.copy(feedRows = st.feedRows + newRow, isLoadingMore = false) }

            } catch (_: Exception) {
                _ui.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun selectGenre(genreId: Int?) {
        val current = _ui.value.selectedGenreId
        if (genreId == current) {
            _ui.update { it.copy(selectedGenreId = null, genreItems = emptyList(), genrePage = 1, hasMoreGenrePages = true) }
            return
        }
        _ui.update { it.copy(selectedGenreId = genreId, genreItems = emptyList(), genrePage = 1, hasMoreGenrePages = true, isGenreLoading = true) }
        viewModelScope.launch {
            try {
                val items = tasteEngine.rankMedia(repo.discoverMovies(genreId, page = 1))
                _ui.update { it.copy(genreItems = items, isGenreLoading = false) }
            } catch (_: Exception) { _ui.update { it.copy(isGenreLoading = false) } }
        }
    }

    fun loadMoreGenre() {
        val st = _ui.value
        if (st.isGenreLoading || !st.hasMoreGenrePages) return
        viewModelScope.launch {
            _ui.update { it.copy(isGenreLoading = true) }
            try {
                val nextPage = st.genrePage + 1
                val items = tasteEngine.rankMedia(repo.discoverMovies(st.selectedGenreId, page = nextPage))
                _ui.update { it.copy(
                    genreItems        = it.genreItems + items,
                    genrePage         = nextPage,
                    hasMoreGenrePages = items.isNotEmpty(),
                    isGenreLoading    = false,
                ) }
            } catch (_: Exception) { _ui.update { it.copy(isGenreLoading = false) } }
        }
    }

    // ── Track user actions from Browse screen ─────────────────────────────────
    fun onMediaLiked(media: Media) {
        tasteEngine.track(media, UserAction.LIKE)
    }

    fun onMediaSaved(media: Media) {
        tasteEngine.track(media, UserAction.SAVE_WATCHLIST)
    }

    fun onMediaRemovedFromWatchlist(media: Media) {
        tasteEngine.track(media, UserAction.REMOVE_WATCHLIST)
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun moodKeyFromLabel(label: String) = when {
        label.contains("horror", ignoreCase = true) || label.contains("Scary") -> "horror"
        label.contains("laugh") || label.contains("Comedy") -> "comedy"
        label.contains("badass") || label.contains("Action") -> "action"
        label.contains("Romantic") || label.contains("Romance") -> "romance"
        label.contains("edge") || label.contains("Thriller") -> "thriller"
        label.contains("Mind") || label.contains("Sci") -> "scifi"
        label.contains("Emotional") || label.contains("Drama") -> "drama"
        label.contains("Anime") -> "anime"
        label.contains("Crime") -> "crime"
        else -> "drama"
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────
//
//  Architecture choices made here:
//  1. Collapsing app bar – floated over content via Box overlay, driven by
//     NestedScrollConnection reading dy from the LazyColumn.
//  2. Pull-to-refresh – handled through the same NestedScrollConnection
//     so it cooperates with LazyColumn (fixes the old pointerInput conflict).
//  3. Genre chips – remain inside the LazyColumn so they scroll naturally
//     under the collapsing bar (correct for collapsing appbar mode).
//  4. TikTok Home button – lives in AppNavigation, talks to shared VM.

@Composable
fun BrowseScreen(
    nav: NavController,
    adEngine: AdEngine,
    vm: BrowseViewModel = hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
) {
    val ui by vm.ui.collectAsState()
    val density = LocalDensity.current

    // ── Collapsing app-bar measurements ──────────────────────────────────────
    // We measure the bar height on first layout so we know how far to collapse.
    var appBarHeightPx by remember { mutableStateOf(0f) }
    // How much the bar has been collapsed (0 = fully expanded, appBarHeightPx = fully hidden)
    var collapseOffsetPx by remember { mutableStateOf(0f) }
    val collapseProgress = if (appBarHeightPx > 0f)
        (collapseOffsetPx / appBarHeightPx).coerceIn(0f, 1f)
    else 0f

    // ── Pull-to-refresh state (managed in NestedScrollConnection) ────────────
    // Positive when user overscrolls upward at the top
    var pullOverscrollPx by remember { mutableStateOf(0f) }
    val pullThresholdPx = with(density) { 72.dp.toPx() }
    val pullProgress = (pullOverscrollPx / pullThresholdPx).coerceIn(0f, 1f)
    val pullIndicatorScale by animateFloatAsState(
        if (pullProgress > 0.85f) 1.15f else 0.85f + 0.15f * pullProgress,
        spring(dampingRatio = 0.5f, stiffness = 400f), label = "pullScale"
    )

    // ── NestedScrollConnection ────────────────────────────────────────────────
    //  KEY BEHAVIOUR:
    //  • onPreScroll  UP   → snap bar open immediately (even 1px up restores bar)
    //  • onPostScroll DOWN → collapse bar AFTER content scrolled (moves together)
    //  • onPostScroll UP at top → accumulate pull-to-refresh overscroll
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                // Upward finger → snap bar back open, do NOT consume so content scrolls too
                if (dy > 0 && collapseOffsetPx > 0f) {
                    val expand = minOf(dy, collapseOffsetPx)
                    collapseOffsetPx = (collapseOffsetPx - expand).coerceIn(0f, appBarHeightPx)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Content scrolled DOWN → collapse bar by same amount (moves with content)
                if (consumed.y < 0) {
                    collapseOffsetPx = (collapseOffsetPx - consumed.y).coerceIn(0f, appBarHeightPx)
                }
                // Pull-to-refresh: overscroll when fully expanded + at top
                val leftover = available.y
                if (leftover > 0 && !listState.canScrollBackward && collapseOffsetPx == 0f) {
                    pullOverscrollPx = (pullOverscrollPx + leftover * 0.5f)
                        .coerceIn(0f, pullThresholdPx * 1.6f)
                    return Offset(0f, leftover)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullOverscrollPx >= pullThresholdPx) {
                    vm.load(forceRefresh = true)
                }
                pullOverscrollPx = 0f
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                pullOverscrollPx = 0f
                return Velocity.Zero
            }
        }
    }

    // Reset overscroll when refreshing completes
    LaunchedEffect(ui.isRefreshing) {
        if (!ui.isRefreshing) pullOverscrollPx = 0f
    }

    // ── Infinite scroll trigger ───────────────────────────────────────────────
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val total  = layout.totalItemsCount
            val last   = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 3
        }.distinctUntilChanged().filter { it }.collect {
            if (ui.selectedGenreId != null) vm.loadMoreGenre()
            else vm.loadMoreInfinite()
        }
    }

    fun goDetail(id: Int, type: MediaType) = nav.navigate(Route.Detail.go(id, type))

    // ── Root Box: content + floating overlay elements ─────────────────────────
    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .nestedScroll(nestedScrollConnection)
    ) {

        // ── Scrollable content ──────────────────────────────────────────────
        LazyColumn(
            state  = listState,
            modifier = Modifier.fillMaxSize(),
            // Top padding = full appbar height so first item clears the bar when expanded
            contentPadding = PaddingValues(
                top    = with(density) { appBarHeightPx.toDp() } + 4.dp,
                bottom = 100.dp,
            ),
        ) {
            when {
                ui.isLoading && !ui.isCacheLoaded -> {
                    item(key = "skeletonBanner") { SkeletonBannerLoader() }
                    item(key = "skeletonRow1") {
                        Column {
                            Box(
                                Modifier.width(180.dp).height(20.dp)
                                    .padding(start = 16.dp, top = 28.dp, bottom = 12.dp)
                                    .clip(RoundedCornerShape(4.dp)).background(BgSurface)
                            )
                            SkeletonRowLoader()
                        }
                    }
                    item(key = "skeletonRow2") {
                        Column {
                            Box(
                                Modifier.width(140.dp).height(20.dp)
                                    .padding(start = 16.dp, top = 28.dp, bottom = 12.dp)
                                    .clip(RoundedCornerShape(4.dp)).background(BgSurface)
                            )
                            SkeletonRowLoader()
                        }
                    }
                }

                ui.error != null && !ui.isCacheLoaded -> item {
                    ErrorState(ui.error!!, onRetry = { vm.load(true) })
                }

                else -> {
                    // ── Hero pager ────────────────────────────────────────────
                    if (ui.featured.isNotEmpty()) {
                        item(key = "hero") {
                            HeroBannerPager(ui.featured, onClick = { goDetail(it.tmdbId, it.mediaType) })
                        }
                    } else if (ui.isLoading) {
                        item(key = "heroBannerSkeleton") { SkeletonBannerLoader() }
                    }

                    // ── Genre chips (flow under collapsing bar) ───────────────
                    if (ui.genres.isNotEmpty()) {
                        item(key = "genreBar") {
                            PremiumGenreBar(
                                genres     = ui.genres,
                                selectedId = ui.selectedGenreId,
                                onSelect   = { vm.selectGenre(it) },
                            )
                        }
                    }

                    // ── Genre grid mode ───────────────────────────────────────
                    if (ui.selectedGenreId != null) {
                        if (ui.genreItems.isEmpty() && ui.isGenreLoading) {
                            item(key = "genreSkeletonRow") { SkeletonRowLoader() }
                        } else {
                            val chunks = ui.genreItems.chunked(18)
                            chunks.forEachIndexed { idx, chunk ->
                                item(key = "genre_chunk_$idx") {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(3),
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 1200.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement   = Arrangement.spacedBy(10.dp),
                                        userScrollEnabled = false,
                                    ) {
                                        items(chunk) { m ->
                                            MediaPosterCard(
                                                media    = m,
                                                onClick  = { goDetail(m.tmdbId, m.mediaType) },
                                                modifier = Modifier.aspectRatio(0.65f),
                                            )
                                        }
                                    }
                                }
                            }
                            if (ui.isGenreLoading) {
                                item(key = "genreLoadMore") { LoadMoreSkeleton() }
                            }
                        }
                    } else {
                        // ── Default feed ──────────────────────────────────────
                        if (ui.continueWatching.isNotEmpty()) {
                            item(key = "cwHeader") { SectionHeader("Continue Watching", "See All") }
                            item(key = "cwRow") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(ui.continueWatching, key = { it.key }) { h ->
                                        ContinueCard(h) {
                                            val type = if (h.mediaType == "TV") MediaType.TV else MediaType.MOVIE
                                            goDetail(h.tmdbId, type)
                                        }
                                    }
                                }
                            }
                        }

                        ui.feedRows.forEachIndexed { feedRowIdx, row ->
                            when (row) {
                                is FeedRow.MoodRow -> {
                                    item(key = "mood_row_$feedRowIdx") {
                                        com.reelz.ui.components.MoodRow(
                                            mood = row.mood,
                                            items = row.items,
                                            onItemClick = { m -> goDetail(m.tmdbId, m.mediaType) },
                                        )
                                    }
                                }
                                is FeedRow.Section -> {
                                    item(key = "hdr_${row.section.title}_$feedRowIdx") {
                                        SectionHeader(row.section.title, "See All")
                                    }
                                    item(key = "row_${row.section.title}_$feedRowIdx") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            items(row.section.items, key = { it.tmdbId }) { m ->
                                                MediaRowCard(m, onClick = { goDetail(m.tmdbId, m.mediaType) })
                                            }
                                        }
                                    }
                                }
                                is FeedRow.NativeAdPlacement -> {
                                    // ✅ FIX: use feedRowIdx so every ad placement has a unique key
                                    item(key = "native_ad_$feedRowIdx") {
                                        NativeAdCard(adEngine = adEngine)
                                    }
                                }
                                is FeedRow.InfinitePage -> {
                                    val label = if (row.page % 2 == 0) "More Movies" else "More Series"
                                    item(key = "inf_hdr_${row.page}") { SectionHeader(label, "") }
                                    item(key = "inf_row_${row.page}") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            items(row.items, key = { "${row.page}_${it.tmdbId}" }) { m ->
                                                MediaRowCard(m, onClick = { goDetail(m.tmdbId, m.mediaType) })
                                            }
                                        }
                                    }
                                }
                                is FeedRow.TrendingRow -> {
                                    item(key = "trending_hdr_$feedRowIdx") {
                                        SectionHeader("Top 10 This Week", "")
                                    }
                                    item(key = "trending_row_$feedRowIdx") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                                        ) {
                                            itemsIndexed(row.items.take(10)) { idx, m ->
                                                TrendingNumberCard(
                                                    rank    = idx + 1,
                                                    media   = m,
                                                    onClick = { goDetail(m.tmdbId, m.mediaType) },
                                                )
                                            }
                                        }
                                    }
                                }
                                is FeedRow.HiddenGems -> {
                                    item(key = "gems_hdr_$feedRowIdx") {
                                        SectionHeader("💎 Hidden Gems Worth Your Time", "")
                                    }
                                    item(key = "gems_row_$feedRowIdx") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            items(row.items, key = { "gem_${it.tmdbId}" }) { m ->
                                                HiddenGemCard(m, onClick = { goDetail(m.tmdbId, m.mediaType) })
                                            }
                                        }
                                    }
                                }
                                is FeedRow.WorldCinema -> {
                                    item(key = "world_hdr_$feedRowIdx") {
                                        SectionHeader("🌍 From Around the World", "")
                                    }
                                    item(key = "world_row_$feedRowIdx") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            items(row.items, key = { "world_${it.tmdbId}" }) { m ->
                                                WorldCinemaCard(m, onClick = { goDetail(m.tmdbId, m.mediaType) })
                                            }
                                        }
                                    }
                                }
                                is FeedRow.AwardWinners -> {
                                    item(key = "awards_hdr_$feedRowIdx") {
                                        SectionHeader("⭐ Critics' Picks", "")
                                    }
                                    item(key = "awards_row_$feedRowIdx") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            items(row.items, key = { "award_${it.tmdbId}" }) { m ->
                                                AwardWinnerCard(m, onClick = { goDetail(m.tmdbId, m.mediaType) })
                                            }
                                        }
                                    }
                                }
                                is FeedRow.BraveExplore -> {
                                    item(key = "brave_$feedRowIdx") {
                                        BraveExploreSection(
                                            headline = row.headline,
                                            items    = row.items,
                                            onClick  = { m -> goDetail(m.tmdbId, m.mediaType) },
                                        )
                                    }
                                }
                            }
                        }

                        if (ui.isLoadingMore) {
                            item(key = "loadMoreSkeleton") { LoadMoreSkeleton() }
                        }
                    }

                    item(key = "adBanner") { AdBannerPlaceholder(Modifier.padding(vertical = 10.dp)) }
                }
            }
        }

        // ── Collapsing glass app bar (floats over content) ───────────────────
        CollapsingGlassAppBar(
            collapseProgress = collapseProgress,
            onSearchClick    = { nav.navigate(Route.Search.path) },
            modifier         = Modifier
                .align(Alignment.TopCenter)
                .onGloballyPositioned { coords ->
                    if (appBarHeightPx == 0f) appBarHeightPx = coords.size.height.toFloat()
                }
                .graphicsLayer {
                    // Translate upward as bar collapses
                    translationY = -collapseOffsetPx
                },
        )

        // ── Pull-to-refresh indicator ─────────────────────────────────────────
        val showPullIndicator = pullOverscrollPx > 4f || ui.isRefreshing
        AnimatedVisibility(
            visible  = showPullIndicator,
            enter    = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.6f),
            exit     = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.6f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = with(density) { appBarHeightPx.toDp() } + 8.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .scale(if (ui.isRefreshing) 1f else pullIndicatorScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(listOf(BrandDeep.copy(0.95f), Bg.copy(0.85f)))
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(listOf(Brand.copy(0.8f), Brand2.copy(0.5f))),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (ui.isRefreshing) {
                    CinematicSpinner(size = 22.dp, color = Brand)
                } else {
                    // Animated arrow that rotates as pull progresses
                    val arrowRotation by animateFloatAsState(
                        if (pullProgress > 0.9f) 180f else pullProgress * 160f,
                        tween(100), label = "arrowRot"
                    )
                    Text(
                        "↓",
                        color      = Brand,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Black,
                        modifier   = Modifier.graphicsLayer { rotationZ = arrowRotation },
                    )
                }
            }
        }
    }
}

// ── Collapsing Glass App Bar ───────────────────────────────────────────────────
//
//  collapseProgress = 0  →  fully expanded (logo + search bar visible)
//  collapseProgress = 1  →  fully collapsed (only status bar remains, bar hidden)
//
@Composable
fun CollapsingGlassAppBar(
    collapseProgress: Float,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Elements fade / scale based on collapse
    val contentAlpha    = (1f - collapseProgress * 1.8f).coerceIn(0f, 1f)
    val barAlpha        = (0.82f + 0.18f * (1f - collapseProgress)).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // Bottom separator line fades as bar collapses
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Brand.copy(.35f * (1f - collapseProgress)),
                            Color.Transparent,
                        )
                    ),
                    start = Offset(0f, size.height),
                    end   = Offset(size.width, size.height),
                    strokeWidth = 0.8f,
                )
            }
            .graphicsLayer { alpha = barAlpha }
    ) {
        // Layered glass background
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(Color(0xCC050510), Color(0x88050510), Color(0x00050510))
                )
            )
        )
        Box(Modifier.matchParentSize().background(Color(0x09FFFFFF)))

        // Bar content
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .graphicsLayer { alpha = contentAlpha }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Shimmer logo
                val inf   = rememberInfiniteTransition(label = "logoShimmer")
                val shimX by inf.animateFloat(
                    0f, 1f,
                    infiniteRepeatable(tween(3000, easing = LinearEasing)),
                    "lx",
                )
                Text(
                    "REELZ",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0f    to Brand2,
                                shimX to Color(0xFFB3D9FF),
                                1f    to Brand,
                            )
                        ),
                        fontWeight   = FontWeight.Black,
                        fontSize     = 24.sp,
                        letterSpacing = 4.sp,
                    ),
                )

                // Search bar
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(Color(0x18FFFFFF), Color(0x0AFFFFFF))))
                        .border(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(Brand.copy(.4f), GlassBorderMd, Brand.copy(.2f))
                            ),
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { onSearchClick() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(IconSearch, null, tint = Brand.copy(.7f), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Search movies, series…", color = White40, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(BlueGlass)
                                .border(1.dp, BlueBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        ) {
                            Text("Filter", color = Brand, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ── Premium genre bar (flows in scroll content under the collapsing bar) ───────
@Composable
fun PremiumGenreBar(
    genres: List<Genre>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(3.dp).height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(listOf(Brand2, Brand)))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Browse by Genre",
                color      = White60,
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { PremiumGenrePill("✦ All", selectedId == null) { onSelect(null) } }
            items(genres) { g -> PremiumGenrePill(g.name, selectedId == g.id) { onSelect(g.id) } }
        }
    }
}

@Composable
fun PremiumGenrePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val animBorder by animateColorAsState(
        if (selected) Brand else GlassBorder, tween(200), label = "pillBorder"
    )
    val scale by animateFloatAsState(
        if (selected) 1.04f else 1f, spring(0.5f, 400f), label = "pillScale"
    )
    Box(
        Modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected)
                    Brush.linearGradient(listOf(BrandDeep, Brand.copy(.85f)))
                else
                    Brush.linearGradient(listOf(BgSurface, BgRaised))
            )
            .border(1.dp, animBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            color      = if (selected) Color.White else White60,
            fontSize   = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ── Load more skeleton ─────────────────────────────────────────────────────────
@Composable
fun LoadMoreSkeleton() {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) { SkeletonRowLoader() }
}

// ── Hero banner pager ──────────────────────────────────────────────────────────
@Composable
fun HeroBannerPager(items: List<Media>, onClick: (Media) -> Unit) {
    val pagerState = rememberPagerState { items.size }

    LaunchedEffect(pagerState) {
        while (true) {
            delay(4_500)
            if (pagerState.pageCount > 0) {
                pagerState.animateScrollToPage(
                    (pagerState.currentPage + 1) % pagerState.pageCount,
                    animationSpec = tween(600, easing = FastOutSlowInEasing),
                )
            }
        }
    }

    val screenH = LocalConfiguration.current.screenHeightDp.dp

    Box(Modifier.fillMaxWidth()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            val media      = items[page]
            val pageOffset = (pagerState.currentPage - page + pagerState.currentPageOffsetFraction)
                .coerceIn(-1f, 1f)

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(screenH * 0.48f)
                    .clickable { onClick(media) }
                    .graphicsLayer {
                        alpha  = 1f - 0.12f * abs(pageOffset)
                        scaleX = 1f - 0.03f * abs(pageOffset)
                        scaleY = 1f - 0.03f * abs(pageOffset)
                    }
            ) {
                AsyncImage(
                    model            = BuildConfig.TMDB_IMG_ORIGINAL + media.backdropPath,
                    contentDescription = null,
                    contentScale     = ContentScale.Crop,
                    modifier         = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f    to Color(0x10000000),
                        0.3f  to Color(0x00000000),
                        0.65f to Color(0x99000000),
                        1f    to Bg,
                    )
                ))
                Box(Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Bg.copy(.35f), Color.Transparent, Color.Transparent, Bg.copy(.25f))
                    )
                ))
                Box(Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Color.Transparent, Brand.copy(0.04f)), radius = 900f)
                ))

                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(BlueGlass)
                            .border(1.dp, BlueBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        PulsingDot(Modifier.size(5.dp))
                        Text("FEATURED", color = Brand, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        media.title,
                        color      = White,
                        fontWeight = FontWeight.Black,
                        fontSize   = 28.sp,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                        letterSpacing = (-0.5).sp,
                        lineHeight = 34.sp,
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RatingChip(media.voteAverage)
                        Box(Modifier.size(3.dp).clip(CircleShape).background(White40))
                        Text(media.releaseDate?.take(4) ?: "", color = White60, fontSize = 13.sp)
                        Box(Modifier.size(3.dp).clip(CircleShape).background(White40))
                        Text(
                            if (media.mediaType == MediaType.TV) "TV Series" else "Movie",
                            color = White60, fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        media.overview,
                        color    = White60,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrandButton(
                            text  = "Watch Now",
                            onClick = { onClick(media) },
                            icon  = { Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(16.dp)) },
                        )
                        GhostButton(text = "+ Watchlist", onClick = {})
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(items.size) { i ->
                val selected = pagerState.currentPage == i
                val width by animateDpAsState(if (selected) 22.dp else 5.dp, spring(0.6f, 400f), label = "iw")
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .width(width).height(5.dp)
                        .background(
                            if (selected)
                                Brush.horizontalGradient(listOf(Brand2, Brand))
                            else
                                Brush.horizontalGradient(listOf(White40, White40))
                        )
                )
            }
        }
    }
}

// ── Continue watching card ─────────────────────────────────────────────────────
@Composable
fun ContinueCard(h: WatchHistory, onClick: () -> Unit) {
    val progress = if (h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else 0f

    Column(Modifier.width(168.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().height(96.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, GlassBorderMd, RoundedCornerShape(12.dp))
                .background(BgRaised)
        ) {
            AsyncImage(
                model            = BuildConfig.TMDB_IMG_W342 + h.posterPath,
                contentDescription = h.title,
                contentScale     = ContentScale.Crop,
                modifier         = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color(0x55000000)), Alignment.Center) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(Color(0x99000000))
                        .border(1.dp, White.copy(.3f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(IconPlay, null, tint = White, modifier = Modifier.size(18.dp))
                }
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(White20))
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth(progress).height(3.dp)
                    .background(Brush.horizontalGradient(listOf(Brand, Brand2)))
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(h.title, color = White80, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        if (h.season > 0) Text("S${h.season} · E${h.episode}", color = Brand.copy(.8f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  DISCOVERY ROW COMPOSABLES
//  Each row has a distinct visual identity to match its psychological purpose.
// ══════════════════════════════════════════════════════════════════════════════

// ── Trending: Netflix Top-10 style numbered posters ───────────────────────────
//  The number overlaps the poster — big, bold, unmistakable.
//  Psychology: Social FOMO. "Everyone's watching this."
@Composable
fun TrendingNumberCard(
    rank: Int,
    media: Media,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(140.dp)
            .height(200.dp)
            .clickable(onClick = onClick)
    ) {
        // Poster (shifted right to make room for number)
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(115.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
        ) {
            AsyncImage(
                model              = BuildConfig.TMDB_IMG_W342 + media.posterPath,
                contentDescription = media.title,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
            // Subtle gradient at bottom
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0x99000000))
                )
            )
        }

        // Rank number — huge, stroked, overlapping bottom-left
        Text(
            text       = rank.toString(),
            modifier   = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-2).dp, y = 6.dp),
            style      = MaterialTheme.typography.headlineLarge.copy(
                fontSize     = 72.sp,
                fontWeight   = FontWeight.Black,
                letterSpacing = (-4).sp,
                brush = Brush.linearGradient(
                    listOf(Color.White.copy(0.95f), Color.White.copy(0.55f))
                ),
            ),
        )
    }
}

// ── Hidden Gems: quality poster with gem badge ────────────────────────────────
//  Psychology: Treasure hunt. "High quality, not yet discovered."
@Composable
fun HiddenGemCard(media: Media, onClick: () -> Unit) {
    Box(
        Modifier
            .width(130.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model              = BuildConfig.TMDB_IMG_W342 + media.posterPath,
            contentDescription = media.title,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxWidth().aspectRatio(0.67f),
        )
        // Gradient overlay
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))
            )
        )
        // Gem badge — top right corner
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1A3A5C), Color(0xFF0E6BA8))))
                .border(1.dp, Color(0xFF4FC3F7).copy(0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text("💎 GEM", color = Color(0xFF90CAF9), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        // Rating at bottom
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("★", color = Color(0xFFFFD700), fontSize = 10.sp)
            Text(
                String.format("%.1f", media.voteAverage),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ── World Cinema: poster with language flag pill ──────────────────────────────
//  Psychology: Adventure framing. "From around the world."
@Composable
fun WorldCinemaCard(media: Media, onClick: () -> Unit) {
    val langFlag = languageToFlag(media.originalLanguage)
    Box(
        Modifier
            .width(130.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model              = BuildConfig.TMDB_IMG_W342 + media.posterPath,
            contentDescription = media.title,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxWidth().aspectRatio(0.67f),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))
            )
        )
        // Language flag badge — top right
        if (langFlag.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000))
                    .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(langFlag, fontSize = 12.sp)
            }
        }
        // Title at bottom
        Text(
            text     = media.title,
            color    = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
    }
}

private fun languageToFlag(lang: String) = when (lang) {
    "ja" -> "🇯🇵"
    "ko" -> "🇰🇷"
    "hi" -> "🇮🇳"
    "fr" -> "🇫🇷"
    "es" -> "🇪🇸"
    "de" -> "🇩🇪"
    "it" -> "🇮🇹"
    "pt" -> "🇧🇷"
    "tr" -> "🇹🇷"
    "zh" -> "🇨🇳"
    "ar" -> "🇸🇦"
    "th" -> "🇹🇭"
    "ru" -> "🇷🇺"
    else -> ""
}

// ── Award Winners: poster with gold star badge ────────────────────────────────
//  Psychology: Trusted authority removes the "algorithm chose this" feeling.
@Composable
fun AwardWinnerCard(media: Media, onClick: () -> Unit) {
    Box(
        Modifier
            .width(130.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model              = BuildConfig.TMDB_IMG_W342 + media.posterPath,
            contentDescription = media.title,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxWidth().aspectRatio(0.67f),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xDD000000))
            )
        )
        // Gold badge — top right
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3D2B00), Color(0xFF5C3D00))))
                .border(1.dp, Color(0xFFFFD700).copy(0.7f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text("⭐ ACCLAIMED", color = Color(0xFFFFD700), fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        // Rating bottom left
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("★", color = Color(0xFFFFD700), fontSize = 10.sp)
            Text(
                String.format("%.1f", media.voteAverage),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ── Brave Explore: Zone 3 — landscape cards, warm accent ─────────────────────
//  Psychology: Growth framing. Honest label + distinct visual = "chosen freely."
//  The different card orientation signals to the user: this is different territory.
@Composable
fun BraveExploreSection(
    headline: String,
    items: List<Media>,
    onClick: (Media) -> Unit,
) {
    Column {
        // Section header with warm amber accent (signals "different")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(3.dp).height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFFFF8C00), Color(0xFFFF6B00))))
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "STEP OUTSIDE YOUR COMFORT ZONE",
                    color = Color(0xFFFF8C00).copy(0.8f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Text(
                    headline,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Landscape cards — visually different from the portrait posters above
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { "brave_${it.tmdbId}" }) { m ->
                Box(
                    Modifier
                        .width(220.dp)
                        .height(130.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            1.dp,
                            Brush.linearGradient(listOf(Color(0x55FF8C00), Color(0x22FF6B00))),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onClick(m) }
                ) {
                    AsyncImage(
                        model              = BuildConfig.TMDB_IMG_W342 + (m.backdropPath ?: m.posterPath),
                        contentDescription = m.title,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.5f to Color(0x55000000),
                                1f to Color(0xDD000000),
                            )
                        )
                    )
                    // Amber left accent line
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .width(3.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFFFF8C00), Color(0xFFFF6B00))))
                    )
                    Column(
                        Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 8.dp, bottom = 10.dp)
                    ) {
                        Text(m.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("★", color = Color(0xFFFFD700), fontSize = 9.sp)
                            Text(String.format("%.1f", m.voteAverage), color = Color.White.copy(0.7f), fontSize = 9.sp)
                            Box(Modifier.size(2.dp).clip(CircleShape).background(Color.White.copy(0.3f)))
                            Text(m.releaseDate?.take(4) ?: "", color = Color.White.copy(0.5f), fontSize = 9.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
