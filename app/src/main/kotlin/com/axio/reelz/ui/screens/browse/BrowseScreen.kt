package com.axio.reelz.ui.screens.browse

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.ads.NativeAdCard
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.axio.reelz.BuildConfig
import com.axio.reelz.data.local.WatchlistDao
import com.axio.reelz.data.model.*
import com.axio.reelz.data.repository.MediaRepository
import com.axio.reelz.ui.Route
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
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
    data class Section(val section: HomeSection) : FeedRow()
    data class InfinitePage(val items: List<Media>, val page: Int) : FeedRow()
    object NativeAdPlacement : FeedRow()
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: MediaRepository,
    private val watchlistDao: WatchlistDao,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,           // true only on very first launch with no cache
        val isRefreshing: Boolean = false,        // true only during user-triggered pull-to-refresh
        val isBackgroundRefreshing: Boolean = false, // silent refresh while cache is already shown
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
        // Set of tmdbIds currently in the watchlist — used by the hero banner button
        val watchlistedIds: Set<Int> = emptySet(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var infinitePage = 1
    private var isInfiniteExhausted = false
    private var categorySections: List<HomeSection> = emptyList()
    private var categorySectionsEmitted = false

    init {
        initLoad()
        viewModelScope.launch {
            repo.getHistory().collect { h ->
                _ui.update { it.copy(continueWatching = h) }
            }
        }
        // Keep watchlist set in sync so hero banner button reflects current state instantly
        viewModelScope.launch {
            watchlistDao.getAll().collect { list ->
                _ui.update { it.copy(watchlistedIds = list.map { w -> w.tmdbId }.toSet()) }
            }
        }
    }

    /** Toggle a media item in/out of the watchlist from the hero banner. */
    fun toggleHeroWatchlist(media: Media) {
        viewModelScope.launch {
            val existing = watchlistDao.get(media.tmdbId)
            if (existing != null) {
                watchlistDao.delete(media.tmdbId)
            } else {
                watchlistDao.insert(
                    WatchlistItem(
                        tmdbId    = media.tmdbId,
                        title     = media.title,
                        posterPath = media.posterPath,
                        mediaType = media.mediaType.name,
                    )
                )
            }
        }
    }

    /**
     * Stale-while-revalidate strategy:
     *  Phase 1 — show cached data instantly if available (no spinner, no delay).
     *  Phase 2 — refresh from network in the background; silently update UI when done.
     *  If there is no cache at all, show the full loading state and fetch from network.
     */
    private fun initLoad() {
        viewModelScope.launch {
            infinitePage = 1
            isInfiniteExhausted = false
            categorySectionsEmitted = false

            val hasCached = try { repo.hasCachedData() } catch (_: Exception) { false }

            if (hasCached) {
                // ── Phase 1: instant cache display ────────────────────────────
                try {
                    val cached  = repo.getHomeSectionsFromCacheOnly()
                    val genres  = try { repo.getMovieGenres() } catch (_: Exception) { emptyList() }
                    categorySections = cached
                    _ui.update {
                        it.copy(
                            isLoading            = false,
                            isCacheLoaded        = true,
                            featured             = cached.firstOrNull()?.items?.take(6) ?: emptyList(),
                            feedRows             = buildFeedRows(cached),
                            genres               = genres,
                            isBackgroundRefreshing = true,   // show subtle top indicator
                        )
                    }
                    categorySectionsEmitted = true
                } catch (_: Exception) {
                    // Cache read failed — fall through to network
                    _ui.update { it.copy(isLoading = true, isBackgroundRefreshing = false) }
                }

                // ── Phase 2: silent background network refresh ─────────────
                try {
                    val fresh  = repo.getHomeSectionsFromNetwork()
                    val genres = try { repo.getMovieGenres() } catch (_: Exception) { _ui.value.genres }
                    categorySections = fresh
                    _ui.update {
                        it.copy(
                            isLoading              = false,
                            isCacheLoaded          = true,
                            isBackgroundRefreshing = false,
                            featured               = fresh.firstOrNull()?.items?.take(6) ?: emptyList(),
                            feedRows               = buildFeedRows(fresh),
                            genres                 = genres.ifEmpty { it.genres },
                        )
                    }
                    categorySectionsEmitted = true
                } catch (_: Exception) {
                    // Network failed — cache content is already visible; no error banner needed.
                    _ui.update { it.copy(isBackgroundRefreshing = false) }
                }
            } else {
                // ── No cache: full loading state until network responds ────
                _ui.update { it.copy(isLoading = true, error = null) }
                try {
                    val sections = repo.getHomeSectionsFromNetwork()
                    val genres   = try { repo.getMovieGenres() } catch (_: Exception) { emptyList() }
                    categorySections = sections
                    _ui.update {
                        it.copy(
                            isLoading     = false,
                            isCacheLoaded = true,
                            featured      = sections.firstOrNull()?.items?.take(6) ?: emptyList(),
                            feedRows      = buildFeedRows(sections),
                            genres        = genres,
                        )
                    }
                    categorySectionsEmitted = true
                } catch (e: Exception) {
                    _ui.update {
                        it.copy(isLoading = false, error = friendlyBrowseError(e))
                    }
                }
            }
        }
    }

    /** User-triggered pull-to-refresh: show the indicator, then fetch fresh data. */
    fun load(forceRefresh: Boolean = true) {
        if (!forceRefresh) { initLoad(); return }
        viewModelScope.launch {
            _ui.update { it.copy(isRefreshing = true, error = null) }
            infinitePage = 1
            isInfiniteExhausted = false
            categorySectionsEmitted = false
            try {
                val sections = repo.getHomeSectionsFromNetwork()
                val genres   = try { repo.getMovieGenres() } catch (_: Exception) { _ui.value.genres }
                categorySections = sections
                _ui.update {
                    it.copy(
                        isRefreshing  = false,
                        isCacheLoaded = true,
                        featured      = sections.firstOrNull()?.items?.take(6) ?: emptyList(),
                        feedRows      = buildFeedRows(sections),
                        genres        = genres.ifEmpty { it.genres },
                    )
                }
                categorySectionsEmitted = true
            } catch (e: Exception) {
                _ui.update {
                    it.copy(isRefreshing = false, error = friendlyBrowseError(e))
                }
            }
        }
    }

    private fun buildFeedRows(sections: List<HomeSection>): List<FeedRow> {
        val rawRows = sections.map { FeedRow.Section(it) }
        return buildList {
            rawRows.forEachIndexed { index, row ->
                add(row)
                if ((index + 1) % 3 == 0) add(FeedRow.NativeAdPlacement)
            }
        }
    }

    fun loadMoreInfinite() {
        if (_ui.value.isLoadingMore || isInfiniteExhausted) return
        viewModelScope.launch {
            _ui.update { it.copy(isLoadingMore = true) }
            try {
                val nextPage = infinitePage + 1
                val items: List<Media> = if (nextPage % 2 == 0) {
                    repo.discoverMovies(genreId = null, page = nextPage)
                } else {
                    repo.discoverTv(genreId = null, page = nextPage)
                }
                if (items.isEmpty()) {
                    isInfiniteExhausted = true
                    _ui.update { it.copy(isLoadingMore = false) }
                    return@launch
                }
                infinitePage = nextPage
                val newRow = FeedRow.InfinitePage(items, nextPage)
                _ui.update { st ->
                    st.copy(feedRows = st.feedRows + newRow, isLoadingMore = false)
                }
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
                val items = repo.discoverMovies(genreId, page = 1)
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
                val items = repo.discoverMovies(st.selectedGenreId, page = nextPage)
                _ui.update { it.copy(
                    genreItems        = it.genreItems + items,
                    genrePage         = nextPage,
                    hasMoreGenrePages = items.isNotEmpty(),
                    isGenreLoading    = false,
                ) }
            } catch (_: Exception) { _ui.update { it.copy(isGenreLoading = false) } }
        }
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
    val d = LocalDimensions.current
    val ui by vm.ui.collectAsState()
    val density = LocalDensity.current

    // Dismissed for this composition only (i.e. this app session/screen visit) —
    // intentionally not persisted, so it's a gentle nudge rather than a one-time
    // banner that vanishes forever after a single accidental tap of the X.
    var removeAdsBannerDismissed by remember { mutableStateOf(false) }

    // ── Collapsing app-bar measurements ──────────────────────────────────────
    // We measure the bar height on first layout so we know how far to collapse.
    var appBarHeightPx by remember { mutableStateOf(0f) }
    // How much the bar has been collapsed (0 = fully expanded, appBarHeightPx = fully hidden)
    var collapseOffsetPx by remember { mutableStateOf(0f) }
    val collapseProgress = if (appBarHeightPx > 0f)
        (collapseOffsetPx / appBarHeightPx).coerceIn(0f, 1f)
    else 0f

    // ── Pull-to-refresh state (managed in NestedScrollConnection) ────────────
    var pullOverscrollPx by remember { mutableStateOf(0f) }
    val pullThresholdPx = with(density) { (d.avatarMd + d.spaceLg).toPx() }

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
    // derivedStateOf is more efficient than snapshotFlow for scroll-position reads:
    // it only recomposes when the boolean result actually flips, and it re-reads
    // whenever listState.layoutInfo changes (every scroll frame).
    //
    // Threshold: last-visible index >= total - 8.  Each section is 2 LazyColumn
    // items (header + row), so "total - 8" gives ~4 sections of pre-load headroom.
    // This means loading starts well before the user reaches the visible end —
    // no hard-swipe needed.
    val shouldLoadMore by remember {
        derivedStateOf {
            val info  = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) false
            else {
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 8
            }
        }
    }

    // Re-run whenever shouldLoadMore flips OR isLoadingMore/isGenreLoading settle to false.
    // This ensures a fresh check after each page finishes loading, so if the user is
    // still near the bottom the next page kicks off automatically.
    LaunchedEffect(shouldLoadMore, ui.isLoadingMore, ui.isGenreLoading) {
        if (shouldLoadMore && !ui.isLoadingMore && !ui.isGenreLoading) {
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
                bottom = d.spaceXxl * 3.1f,
            ),
        ) {
            when {
                ui.isLoading && !ui.isCacheLoaded -> {
                    item(key = "skeletonBanner") { SkeletonBannerLoader() }
                    item(key = "skeletonRow1") {
                        Column {
                            Box(
                                Modifier.fillMaxWidth(0.45f).height(d.textLg)
                                    .padding(start = d.screenHorizPad, top = d.spaceXl, bottom = d.spaceMd)
                                    .clip(RoundedCornerShape(d.spaceSm)).background(BgSurface)
                            )
                            SkeletonRowLoader()
                        }
                    }
                    item(key = "skeletonRow2") {
                        Column {
                            Box(
                                Modifier.fillMaxWidth(0.35f).height(d.textLg)
                                    .padding(start = d.screenHorizPad, top = d.spaceXl, bottom = d.spaceMd)
                                    .clip(RoundedCornerShape(d.spaceSm)).background(BgSurface)
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
                            HeroBannerPager(
                                items           = ui.featured,
                                watchlistedIds  = ui.watchlistedIds,
                                onWatchlist     = { vm.toggleHeroWatchlist(it) },
                                onClick         = { goDetail(it.tmdbId, it.mediaType) },
                            )
                        }
                    } else if (ui.isLoading) {
                        item(key = "heroBannerSkeleton") { SkeletonBannerLoader() }
                    }

                    // ── Remove ads upsell — config-gated, session-dismissible ──
                    if (!removeAdsBannerDismissed && adEngine.shouldShowRemoveAdsBanner()) {
                        item(key = "removeAdsBanner") {
                            RemoveAdsBanner(
                                onUpgrade  = { nav.navigate(com.axio.reelz.ui.Route.Premium.path) },
                                onDismiss  = { removeAdsBannerDismissed = true },
                            )
                        }
                    }

                    // ── Genre bar (scrolls with content under collapsing bar) ──
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
                                        columns = GridCells.Fixed(if (d.isTablet) 4 else 3),
                                        modifier = Modifier.fillMaxWidth().heightIn(max = (d.cardPosterHeight + d.spaceXxl) * 7),
                                        contentPadding = PaddingValues(horizontal = d.screenHorizPad, vertical = d.sectionVertPad),
                                        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                                        verticalArrangement   = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
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
                                    contentPadding = PaddingValues(horizontal = d.screenHorizPad),
                                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
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
                                is FeedRow.Section -> {
                                    item(key = "hdr_${row.section.title}") {
                                        SectionHeader(row.section.title, "See All")
                                    }
                                    item(key = "row_${row.section.title}") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = d.screenHorizPad),
                                            horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                                        ) {
                                            items(row.section.items, key = { it.tmdbId }) { m ->
                                                MediaRowCard(m, onClick = { goDetail(m.tmdbId, m.mediaType) })
                                            }
                                        }
                                    }
                                }
                                is FeedRow.NativeAdPlacement -> {
                                    item(key = "native_ad_$feedRowIdx") {
                                        NativeAdCard(adEngine = adEngine)
                                    }
                                }
                                is FeedRow.InfinitePage -> {
                                    val label = if (row.page % 2 == 0) "More Movies" else "More Series"
                                    item(key = "inf_hdr_${row.page}") { SectionHeader(label, "") }
                                    item(key = "inf_row_${row.page}") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = d.screenHorizPad),
                                            horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                                        ) {
                                            items(row.items, key = { "${row.page}_${it.tmdbId}" }) { m ->
                                                MediaRowCard(m, onClick = { goDetail(m.tmdbId, m.mediaType) })
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (ui.isLoadingMore) {
                            item(key = "loadMoreSkeleton") { LoadMoreSkeleton() }
                        }
                    }

                    item(key = "adBanner") { AdBannerPlaceholder(Modifier.padding(vertical = d.spaceMd - d.spaceXxs)) }
                }
            }
        }

        // ── Sticky header: app bar + genre strip move as one unit ────────────
        // Both sit in a Column inside one Box. We measure the Column's combined
        // height as appBarHeightPx, then translate it upward by collapseOffsetPx.
        // That means everything — logo, search AND genre chips — hides/reveals
        // together with a single scroll gesture.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .onGloballyPositioned { coords ->
                    val h = coords.size.height.toFloat()
                    if (h != appBarHeightPx) appBarHeightPx = h
                }
                .graphicsLayer { translationY = -collapseOffsetPx }
        ) {
            CollapsingGlassAppBar(
                collapseProgress = collapseProgress,
                onSearchClick    = { nav.navigate(Route.Search.path) },
            )
        }

        // ── Background-refresh shimmer bar ────────────────────────────────────
        if (ui.isBackgroundRefreshing) {
            val inf   = rememberInfiniteTransition(label = "bgRefresh")
            val sweep by inf.animateFloat(
                0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), "sweep"
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopCenter)
                    .graphicsLayer { translationY = appBarHeightPx - collapseOffsetPx }
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                (sweep - 0.35f).coerceIn(0f, 1f) to Color.Transparent,
                                sweep.coerceIn(0f, 1f)           to Brand.copy(0.9f),
                                (sweep + 0.35f).coerceIn(0f, 1f) to Color.Transparent,
                            )
                        )
                    )
            )
        }

        // ── Pull-to-refresh: pill indicator that follows the finger ──────────
        //
        // UX stages:
        //   pulling below threshold → "Pull to refresh"  (ghost pill, arrow down)
        //   pulling above threshold → "Release to refresh" (lit pill, arrow flips)
        //   finger released / fling → spinner + "Updating…"
        //
        // The pill's Y position tracks pullOverscrollPx in real-time (no animation)
        // so it feels physically connected to the finger. Only when the user releases
        // does it spring to its resting position (or animate out).

        // Slow-release fix: if the pull sits above threshold for 150ms without a
        // fling event (i.e. the user lifted slowly), we trigger refresh ourselves.
        LaunchedEffect(pullOverscrollPx >= pullThresholdPx) {
            if (pullOverscrollPx >= pullThresholdPx && !ui.isRefreshing) {
                delay(150)
                if (pullOverscrollPx >= pullThresholdPx && !ui.isRefreshing) {
                    vm.load(forceRefresh = true)
                    pullOverscrollPx = 0f
                }
            }
        }

        val aboveThreshold   = pullOverscrollPx >= pullThresholdPx
        val showPillIndicator = pullOverscrollPx > 6f || ui.isRefreshing

        // While refreshing: spring the pill to a fixed resting spot just below header.
        // While pulling:    follow the finger exactly (no interpolation = zero lag).
        val pillRestingY = with(density) {
            (appBarHeightPx - collapseOffsetPx) + d.spaceMd.toPx()
        }
        val pillFollowY = with(density) {
            (appBarHeightPx - collapseOffsetPx) + (pullOverscrollPx * 0.45f)
        }
        val pillTranslateY by animateFloatAsState(
            targetValue    = if (ui.isRefreshing || pullOverscrollPx == 0f) pillRestingY else pillFollowY,
            animationSpec  = if (ui.isRefreshing)
                spring(dampingRatio = 0.55f, stiffness = 280f)
            else
                tween(durationMillis = 0),   // instant while dragging
            label          = "ptrY",
        )
        val arrowAngle by animateFloatAsState(
            if (aboveThreshold) 180f else 0f,
            spring(dampingRatio = 0.45f, stiffness = 380f),
            label = "ptrArrow",
        )

        AnimatedVisibility(
            visible  = showPillIndicator,
            enter    = fadeIn(tween(120)) + slideInVertically(tween(180, easing = EaseOutBack)) { -it / 2 },
            exit     = fadeOut(tween(180)) + slideOutVertically(tween(160)) { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier.graphicsLayer { translationY = pillTranslateY },
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                    modifier              = Modifier
                        .clip(RoundedCornerShape(d.radiusPill))
                        .background(
                            Brush.linearGradient(
                                if (aboveThreshold || ui.isRefreshing)
                                    listOf(BrandDeep.copy(.97f), Color(0xFF091525).copy(.97f))
                                else
                                    listOf(Bg.copy(.92f), BgSurface.copy(.92f))
                            )
                        )
                        .border(
                            width = d.borderThin,
                            brush = Brush.linearGradient(
                                if (aboveThreshold || ui.isRefreshing)
                                    listOf(Brand.copy(.85f), Brand2.copy(.6f))
                                else
                                    listOf(GlassBorder, GlassBorder)
                            ),
                            shape = RoundedCornerShape(d.radiusPill),
                        )
                        .padding(horizontal = d.heroPadding - d.spaceXs, vertical = d.spaceMd - d.spaceXxs),
                ) {
                    when {
                        ui.isRefreshing -> {
                            CinematicSpinner(size = d.spinnerSm, color = Brand)
                            Text(
                                "Updating…",
                                color      = Brand,
                                fontSize   = d.textSm,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        else -> {
                            Text(
                                if (aboveThreshold) "Release to refresh" else "Pull to refresh",
                                color      = if (aboveThreshold) Brand else White40,
                                fontSize   = d.textSm,
                                fontWeight = if (aboveThreshold) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Icon(
                                imageVector          = Icons.Default.KeyboardArrowDown,
                                contentDescription   = null,
                                tint                 = if (aboveThreshold) Brand else White40,
                                modifier             = Modifier
                                    .size(d.iconMd - 5.dp)
                                    .graphicsLayer { rotationZ = arrowAngle },
                            )
                        }
                    }
                }
            }
        }
    }   // end root Box
}       // end BrowseScreen

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
    val d = LocalDimensions.current
    val contentAlpha    = (1f - collapseProgress * 1.8f).coerceIn(0f, 1f)
    val barAlpha        = (0.82f + 0.18f * (1f - collapseProgress)).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
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
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(Color(0xCC050510), Color(0x88050510), Color(0x00050510))
                )
            )
        )
        Box(Modifier.matchParentSize().background(Color(0x09FFFFFF)))

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .graphicsLayer { alpha = contentAlpha }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = d.appBarHorizPad, vertical = d.appBarVertPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
            ) {
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
                        fontWeight    = FontWeight.Black,
                        fontSize      = d.textXxl,
                        letterSpacing = 4.sp,
                    ),
                )

                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(d.radiusMd))
                        .background(Brush.linearGradient(listOf(Color(0x18FFFFFF), Color(0x0AFFFFFF))))
                        .border(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(Brand.copy(.4f), GlassBorderMd, Brand.copy(.2f))
                            ),
                            RoundedCornerShape(d.radiusMd),
                        )
                        .clickable { onSearchClick() }
                        .padding(horizontal = d.searchBarHorizPad, vertical = d.searchBarVertPad),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(IconSearch, null, tint = Brand.copy(.7f), modifier = Modifier.size(d.iconMd - 4.dp))
                        Spacer(Modifier.width(d.spaceMd - d.spaceXxs))
                        Text("Search movies, series…", color = White40, fontSize = d.textMd)
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(d.radiusSm))
                                .background(BlueGlass)
                                .border(1.dp, BlueBorder, RoundedCornerShape(d.radiusSm))
                                .padding(horizontal = d.spaceSm + 1.dp, vertical = d.spaceXxs + 1.dp),
                        ) {
                            Text("Filter", color = Brand, fontSize = d.textXxs, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ── Sticky glass genre strip (lives in the sticky header, not in scroll content) ─
//
//  Thin (42dp tall), frosted-glass background, single horizontal row of chips.
//  No title label — space is tight and the chips are self-explanatory.
//  The bottom edge has a very faint separator so it reads as distinct from content.
//
@Composable
fun StickyGlassGenreBar(
    genres     : List<Genre>,
    selectedId : Int?,
    onSelect   : (Int?) -> Unit,
) {
    val d = LocalDimensions.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xCC050510), Color(0xAA05050E))
                )
            )
            .drawBehind {
                drawLine(
                    brush       = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color(0x33FFFFFF), Color.Transparent)
                    ),
                    start       = Offset(0f, size.height),
                    end         = Offset(size.width, size.height),
                    strokeWidth = 0.8f,
                )
            }
    ) {
        LazyRow(
            contentPadding        = PaddingValues(horizontal = d.screenHorizPad, vertical = d.chipVertPad + d.spaceXs),
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp),
            modifier              = Modifier.fillMaxWidth(),
        ) {
            item {
                StickyGenreChip(label = "✦ All", selected = selectedId == null) { onSelect(null) }
            }
            items(genres, key = { it.id }) { g ->
                StickyGenreChip(label = g.name, selected = selectedId == g.id) { onSelect(g.id) }
            }
        }
    }
}

@Composable
fun StickyGenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val d = LocalDimensions.current
    val borderColor by animateColorAsState(
        if (selected) Brand else Color(0x28FFFFFF),
        tween(180), label = "chipBorder",
    )
    val scale by animateFloatAsState(
        if (selected) 1.05f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 420f), label = "chipScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier         = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(d.radiusPill))
            .background(
                if (selected)
                    Brush.linearGradient(listOf(BrandDeep, Brand.copy(.82f)))
                else
                    SolidColor(Color(0x14FFFFFF))
            )
            .border(1.dp, borderColor, RoundedCornerShape(d.radiusPill))
            .clickable(onClick = onClick)
            .padding(horizontal = d.chipHorizPad, vertical = d.chipVertPad),
    ) {
    val d = LocalDimensions.current
        Text(
            text       = label,
            color      = if (selected) Color.White else White60,
            fontSize   = d.textXs,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines   = 1,
        )
    }
}

// ── Premium genre bar (kept for reference / other screens) ────────────────────
@Composable
fun PremiumGenreBar(
    genres: List<Genre>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
) {
    val d = LocalDimensions.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad, vertical = d.sectionVertPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(d.sectionAccentWidth).height(d.sectionAccentHeight)
                    .clip(RoundedCornerShape(d.spaceXxs))
                    .background(Brush.verticalGradient(listOf(Brand2, Brand)))
            )
            Spacer(Modifier.width(d.spaceSm + d.spaceXxs))
            Text(
                "Browse by Genre",
                color         = White60,
                fontSize      = d.textSm,
                fontWeight    = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = d.screenHorizPad, vertical = d.sectionVertPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp),
        ) {
            item { PremiumGenrePill("✦ All", selectedId == null) { onSelect(null) } }
            items(genres) { g -> PremiumGenrePill(g.name, selectedId == g.id) { onSelect(g.id) } }
        }
    }
}

@Composable
fun PremiumGenrePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val d = LocalDimensions.current
    val animBorder by animateColorAsState(
        if (selected) Brand else GlassBorder, tween(200), label = "pillBorder"
    )
    val scale by animateFloatAsState(
        if (selected) 1.04f else 1f, spring(0.5f, 400f), label = "pillScale"
    )
    Box(
        Modifier
            .scale(scale)
            .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
            .background(
                if (selected)
                    Brush.linearGradient(listOf(BrandDeep, Brand.copy(.85f)))
                else
                    Brush.linearGradient(listOf(BgSurface, BgRaised))
            )
            .border(d.borderThin, animBorder, RoundedCornerShape(d.radiusMd - d.spaceXxs))
            .clickable(onClick = onClick)
            .padding(horizontal = d.chipHorizPad + d.spaceXs, vertical = d.chipVertPad + d.spaceXs),
    ) {
    val d = LocalDimensions.current
        Text(
            text,
            color      = if (selected) Color.White else White60,
            fontSize   = d.textSm,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ── Load more skeleton ─────────────────────────────────────────────────────────
@Composable
fun LoadMoreSkeleton() {
    val d = LocalDimensions.current
    Box(Modifier.fillMaxWidth().padding(vertical = d.spaceMd - d.spaceXxs)) { SkeletonRowLoader() }
    val d = LocalDimensions.current
}

// ── Hero banner pager ──────────────────────────────────────────────────────────
@Composable
fun HeroBannerPager(
    items: List<Media>,
    watchlistedIds: Set<Int> = emptySet(),
    onWatchlist: (Media) -> Unit = {},
    val d = LocalDimensions.current
    onClick: (Media) -> Unit,
) {
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

    val d = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp

    Box(Modifier.fillMaxWidth()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            val media      = items[page]
            val pageOffset = (pagerState.currentPage - page + pagerState.currentPageOffsetFraction)
                .coerceIn(-1f, 1f)
            val isWatchlisted = media.tmdbId in watchlistedIds

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(screenH * d.heroImageRatio)
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

                Column(Modifier.align(Alignment.BottomStart).padding(d.heroPadding)) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(d.radiusSm))
                            .background(BlueGlass)
                            .border(1.dp, BlueBorder, RoundedCornerShape(d.radiusSm))
                            .padding(horizontal = d.spaceMd, vertical = d.spaceXs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
                    ) {
                        PulsingDot(Modifier.size(d.spaceXs + 1.dp))
                        Text("FEATURED", color = Brand, fontSize = d.textXxs, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    }
                    Spacer(Modifier.height(d.spaceMd))
                    Text(
                        media.title,
                        color         = White,
                        fontWeight    = FontWeight.Black,
                        fontSize      = d.textHero,
                        maxLines      = 2,
                        overflow      = TextOverflow.Ellipsis,
                        letterSpacing = (-0.5).sp,
                        lineHeight    = (d.textHero.value * 1.25f).sp,
                    )
                    Spacer(Modifier.height(d.spaceSm + d.spaceXxs))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                    ) {
                        RatingChip(media.voteAverage)
                        Box(Modifier.size(d.spaceXxs + 1.dp).clip(CircleShape).background(White40))
                        Text(media.releaseDate?.take(4) ?: "", color = White60, fontSize = d.textMd)
                        Box(Modifier.size(d.spaceXxs + 1.dp).clip(CircleShape).background(White40))
                        Text(
                            if (media.mediaType == MediaType.TV) "TV Series" else "Movie",
                            color = White60, fontSize = d.textMd,
                        )
                    }
                    Spacer(Modifier.height(d.spaceSm))
                    Text(
                        media.overview,
                        color      = White60,
                        fontSize   = d.textMd,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                        lineHeight = (d.textMd.value * 1.55f).sp,
                    )
                    Spacer(Modifier.height(d.spaceLg))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrandButton(
                            text  = "Watch Now",
                            onClick = { onClick(media) },
                            icon  = { Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(d.iconMd - 4.dp)) },
                        )
                        GhostButton(
                            text    = if (isWatchlisted) "✓ Saved" else "+ Watchlist",
                            onClick = { onWatchlist(media) },
                        )
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = d.screenHorizPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(items.size) { i ->
                val selected = pagerState.currentPage == i
                val width by animateDpAsState(if (selected) d.pageIndicatorWidthSelected else d.pageIndicatorWidth, spring(0.6f, 400f), label = "iw")
                Box(
                    Modifier
                        .clip(RoundedCornerShape(d.spaceXxs))
                        .width(width).height(d.pageIndicatorHeight)
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
    val d = LocalDimensions.current
    val progress = if (h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else 0f

    Column(Modifier.width(d.continueCardWidth).clickable(onClick = onClick)) {
    val d = LocalDimensions.current
        Box(
            Modifier.fillMaxWidth().height(d.continueCardThumbHeight)
                .clip(RoundedCornerShape(d.radiusMd))
                .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
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
                    Modifier.size(d.buttonHeightMd - d.spaceXs).clip(CircleShape)
                        .background(Color(0x99000000))
                        .border(1.dp, White.copy(.3f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(IconPlay, null, tint = White, modifier = Modifier.size(d.iconMd - 2.dp))
                }
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(d.progressBarHeight).background(White20))
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth(progress).height(d.progressBarHeight)
                    .background(Brush.horizontalGradient(listOf(Brand, Brand2)))
            )
        }
        Spacer(Modifier.height(d.spaceSm))
        Text(h.title, color = White80, fontSize = d.textSm, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        if (h.season > 0) Text("S${h.season} · E${h.episode}", color = Brand.copy(.8f), fontSize = d.textXxs, fontWeight = FontWeight.SemiBold)
    }
}

private fun friendlyBrowseError(e: Exception): String {
    val msg = e.message?.lowercase() ?: ""
    return when {
        msg.contains("unable to resolve host") ||
        msg.contains("no route to host") ||
        msg.contains("network") ||
        msg.contains("timeout") ||
        msg.contains("connect") -> "No internet connection. Check your connection and try again."
        msg.contains("404") -> "Content couldn't be loaded. Pull down to try again."
        msg.contains("500") ||
        msg.contains("502") ||
        msg.contains("503") -> "The server is temporarily unavailable. Pull down to retry."
        else -> "Couldn't load content. Pull down to try again."
    }
}
