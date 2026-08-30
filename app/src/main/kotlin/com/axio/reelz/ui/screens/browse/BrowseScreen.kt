package com.axio.reelz.ui.screens.browse

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.ads.GuestInterstitialEffect
import com.axio.reelz.ads.HeroBannerAd
import com.axio.reelz.ads.NativeAdCard
import com.axio.reelz.ads.NativeAdRowCard
import com.axio.reelz.app.Route
import com.axio.reelz.core.network.NetworkResult
import com.axio.reelz.data.model.*
import com.axio.reelz.data.repository.CatalogRepository
import com.axio.reelz.data.repository.LibraryRepository
import com.axio.reelz.data.repository.UserRepository
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import kotlin.math.abs

// ── Feed row sealed class ─────────────────────────────────────────────────────

sealed class FeedRow {
    data class Section(val section: FeedSection) : FeedRow()
    data class InfinitePage(val items: List<Media>, val page: Int) : FeedRow()
    object NativeAdPlacement : FeedRow()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: CatalogRepository,
    private val libraryRepo: LibraryRepository,
    private val userRepo: UserRepository,
) : androidx.lifecycle.ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val isBackgroundRefreshing: Boolean = false,
        val error: String? = null,
        val featured: List<Media> = emptyList(),
        val feedRows: List<FeedRow> = emptyList(),
        val genres: List<Genre> = emptyList(),
        val selectedGenreId: String? = null,
        val genreItems: List<Media> = emptyList(),
        val genrePage: Int = 1,
        val isGenreLoading: Boolean = false,
        val hasMoreGenrePages: Boolean = true,
        val continueWatching: List<com.axio.reelz.core.database.WatchProgressRow> = emptyList(),
        val isLoadingMore: Boolean = false,
        val isCacheLoaded: Boolean = false,
        val watchlistedIds: Set<String> = emptySet(),
        /** Non-null when a background/pull-to-refresh fails but cached content is still shown. */
        val refreshError: String? = null,
        val isPremium: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var infiniteCursor: String? = null
    private var isInfiniteExhausted = false

    init {
        initLoad()
        viewModelScope.launch {
            libraryRepo.observeWatchlist().collect { list ->
                _ui.update { it.copy(watchlistedIds = list.map { w -> w.mediaId }.toSet()) }
            }
        }
        viewModelScope.launch {
            libraryRepo.observeRecentProgress(10).collect { recent ->
                _ui.update { it.copy(continueWatching = recent) }
            }
        }
        viewModelScope.launch {
            userRepo.session.collect { session ->
                _ui.update { it.copy(isPremium = session?.isPremium == true) }
            }
        }
    }

    fun clearRefreshError() = _ui.update { it.copy(refreshError = null) }

    fun toggleHeroWatchlist(media: Media) {
        viewModelScope.launch { libraryRepo.toggleWatchlist(media) }
    }

    private fun initLoad() {
        viewModelScope.launch {
            isInfiniteExhausted = false
            infiniteCursor = null

            val cacheResult = repo.getFeed(forceRefresh = false)
            when (cacheResult) {
                is NetworkResult.Success -> {
                    val sections = cacheResult.data
                    val fromCache = cacheResult.fromCache

                    if (sections.isNotEmpty()) {
                        val genres = try {
                            val gr = repo.getGenres("movie")
                            if (gr is NetworkResult.Success) gr.data else emptyList()
                        } catch (_: Exception) { emptyList() }

                        _ui.update {
                            it.copy(
                                isLoading              = false,
                                isCacheLoaded          = true,
                                error                  = null,
                                featured               = pickFeatured(sections),
                                feedRows               = buildFeedRows(sections),
                                genres                 = genres,
                                isBackgroundRefreshing = fromCache,
                            )
                        }

                        if (fromCache) {
                            val freshResult = repo.getFeed(forceRefresh = true)
                            when (freshResult) {
                                is NetworkResult.Success -> {
                                    if (freshResult.data.isNotEmpty()) {
                                        _ui.update {
                                            it.copy(
                                                isBackgroundRefreshing = false,
                                                featured = pickFeatured(freshResult.data),
                                                feedRows = buildFeedRows(freshResult.data),
                                            )
                                        }
                                    } else {
                                        _ui.update { it.copy(isBackgroundRefreshing = false) }
                                    }
                                }
                                is NetworkResult.Error -> _ui.update {
                                    it.copy(
                                        isBackgroundRefreshing = false,
                                        refreshError = "Couldn't refresh — showing cached content.",
                                    )
                                }
                                else -> _ui.update { it.copy(isBackgroundRefreshing = false) }
                            }
                        }
                    } else {
                        _ui.update { it.copy(isLoading = true, error = null) }
                        loadFromNetwork()
                    }
                }
                is NetworkResult.Error -> _ui.update {
                    it.copy(isLoading = false, error = "Couldn't load content. Check your connection.")
                }
                NetworkResult.Loading -> _ui.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun loadFromNetwork() {
        val result = repo.getFeed(forceRefresh = true)
        val genres = try {
            val gr = repo.getGenres("movie")
            if (gr is NetworkResult.Success) gr.data else emptyList()
        } catch (_: Exception) { emptyList() }
        when (result) {
            is NetworkResult.Success -> _ui.update {
                it.copy(
                    isLoading     = false,
                    isCacheLoaded = true,
                    error         = null,
                    featured      = pickFeatured(result.data),
                    feedRows      = buildFeedRows(result.data),
                    genres        = genres,
                )
            }
            is NetworkResult.Error -> _ui.update {
                it.copy(isLoading = false, error = "Couldn't load content. Check your connection.")
            }
            NetworkResult.Loading -> _ui.update { it.copy(isLoading = false) }
        }
    }

    private fun pickFeatured(sections: List<FeedSection>): List<Media> =
        sections.firstOrNull()?.items?.take(6) ?: emptyList()

    private fun buildFeedRows(sections: List<FeedSection>): List<FeedRow> = buildList {
        // Ad pattern per spec:
        // Row 1 content → AD → skip row 3 → AD → skip row 5 → AD…
        // Translated to 0-indexed sections: inject ad after section 0, 2, 4, 6…
        // i.e. every even section index gets an ad AFTER it.
        sections.forEachIndexed { index, section ->
            if (section.items.isNotEmpty()) {
                add(FeedRow.Section(section))
                // Inject ad after every even-indexed section (0, 2, 4…)
                // This gives the "show, skip, show, skip…" rhythm
                if (index % 2 == 0) add(FeedRow.NativeAdPlacement)
            }
        }
    }

    fun load(forceRefresh: Boolean = true) {
        if (!forceRefresh) { initLoad(); return }
        viewModelScope.launch {
            _ui.update { it.copy(isRefreshing = true, error = null) }
            isInfiniteExhausted = false
            infiniteCursor = null
            val result = repo.getFeed(forceRefresh = true)
            when (result) {
                is NetworkResult.Success -> _ui.update {
                    it.copy(
                        isRefreshing  = false,
                        isCacheLoaded = true,
                        featured      = pickFeatured(result.data),
                        feedRows      = buildFeedRows(result.data),
                    )
                }
                is NetworkResult.Error -> _ui.update { it.copy(
                    isRefreshing = false,
                    // If we have cached content, use refreshError (inline banner) rather than
                    // replacing the whole screen with an error.
                    refreshError = if (it.feedRows.isNotEmpty()) result.message else null,
                    error        = if (it.feedRows.isEmpty()) result.message else null,
                ) }
                NetworkResult.Loading  -> _ui.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun loadMoreInfinite() {
        if (_ui.value.isLoadingMore || isInfiniteExhausted) return
        viewModelScope.launch {
            _ui.update { it.copy(isLoadingMore = true) }
            val existingIds = _ui.value.feedRows.flatMap { row ->
                when (row) {
                    is FeedRow.Section      -> row.section.items.map { it.id }
                    is FeedRow.InfinitePage -> row.items.map { it.id }
                    else                    -> emptyList()
                }
            }.toSet()
            val result = repo.discover(cursor = infiniteCursor)
            when (result) {
                is NetworkResult.Success -> {
                    val (items, nextCursor) = result.data
                    val fresh = items.filter { it.id !in existingIds }
                    if (fresh.isEmpty()) {
                        isInfiniteExhausted = true
                        _ui.update { it.copy(isLoadingMore = false) }
                        return@launch
                    }
                    val pageIndex = _ui.value.feedRows.count { it is FeedRow.InfinitePage } + 1
                    infiniteCursor = nextCursor
                    if (nextCursor == null) isInfiniteExhausted = true
                    _ui.update { st ->
                        val alreadyPresent = st.feedRows.any { it is FeedRow.InfinitePage && (it as FeedRow.InfinitePage).page == pageIndex }
                        if (alreadyPresent) return@update st.copy(isLoadingMore = false)
                        st.copy(feedRows = st.feedRows + FeedRow.InfinitePage(fresh, pageIndex), isLoadingMore = false)
                    }
                }
                else -> {
                    isInfiniteExhausted = true
                    _ui.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    fun selectGenre(genreId: String?) {
        val current = _ui.value.selectedGenreId
        if (genreId == current) {
            _ui.update { it.copy(selectedGenreId = null, genreItems = emptyList(), genrePage = 1, hasMoreGenrePages = true) }
            return
        }
        _ui.update { it.copy(selectedGenreId = genreId, genreItems = emptyList(), genrePage = 1, hasMoreGenrePages = true, isGenreLoading = true) }
        viewModelScope.launch {
            try {
                val result = repo.discover(cursor = null, genre = genreId)
                if (result is NetworkResult.Success) {
                    _ui.update { it.copy(genreItems = result.data.first, isGenreLoading = false) }
                } else {
                    _ui.update { it.copy(isGenreLoading = false) }
                }
            } catch (_: Exception) { _ui.update { it.copy(isGenreLoading = false) } }
        }
    }
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@Composable
fun BrowseScreen(
    nav: NavController,
    adEngine: AdEngine,
    viewModel: BrowseViewModel = hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
) {
    val d = LocalDimensions.current
    val ui by viewModel.ui.collectAsState()
    val density = LocalDensity.current

    var removeAdsBannerDismissed by remember { mutableStateOf(false) }

    // ── Collapsing app-bar measurements ──────────────────────────────────────
    var appBarHeightPx by remember { mutableStateOf(0f) }
    var collapseOffsetPx by remember { mutableStateOf(0f) }
    val collapseProgress = if (appBarHeightPx > 0f)
        (collapseOffsetPx / appBarHeightPx).coerceIn(0f, 1f) else 0f

    // ── Pull-to-refresh state ─────────────────────────────────────────────────
    var pullOverscrollPx by remember { mutableStateOf(0f) }
    val pullThresholdPx = with(density) { (d.avatarMd + d.spaceLg).toPx() }

    // ── NestedScrollConnection ────────────────────────────────────────────────
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy > 0 && collapseOffsetPx > 0f) {
                    val expand = minOf(dy, collapseOffsetPx)
                    collapseOffsetPx = (collapseOffsetPx - expand).coerceIn(0f, appBarHeightPx)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y < 0) {
                    collapseOffsetPx = (collapseOffsetPx - consumed.y).coerceIn(0f, appBarHeightPx)
                }
                val leftover = available.y
                if (leftover > 0 && !listState.canScrollBackward && collapseOffsetPx == 0f) {
                    pullOverscrollPx = (pullOverscrollPx + leftover * 0.5f).coerceIn(0f, pullThresholdPx * 1.6f)
                    return Offset(0f, leftover)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullOverscrollPx >= pullThresholdPx) { viewModel.load(forceRefresh = true) }
                pullOverscrollPx = 0f
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                pullOverscrollPx = 0f
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(ui.isRefreshing) { if (!ui.isRefreshing) pullOverscrollPx = 0f }

    // ── Infinite scroll trigger ───────────────────────────────────────────────
    val shouldLoadMore by remember {
        derivedStateOf {
            val info  = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) false
            else { val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0; lastVisible >= total - 8 }
        }
    }

    LaunchedEffect(shouldLoadMore, ui.isLoadingMore, ui.isGenreLoading) {
        if (shouldLoadMore && !ui.isLoadingMore && !ui.isGenreLoading) {
            if (ui.selectedGenreId != null) { /* genre pagination handled separately */ }
            else viewModel.loadMoreInfinite()
        }
    }

    fun goDetail(id: String, type: MediaType) = nav.navigate(Route.Detail.go(id, type))

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .nestedScroll(nestedScrollConnection)
    ) {
        // ── Scrollable content ────────────────────────────────────────────────
        LazyColumn(
            state   = listState,
            modifier = Modifier.fillMaxSize(),
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
                                Modifier.fillMaxWidth(0.45f).height(d.spaceLg - d.spaceXxs)
                                    .padding(start = d.screenHorizPad, top = d.spaceXl, bottom = d.spaceMd)
                                    .clip(RoundedCornerShape(d.spaceSm)).background(BgSurface)
                            )
                            SkeletonRowLoader()
                        }
                    }
                    item(key = "skeletonRow2") {
                        Column {
                            Box(
                                Modifier.fillMaxWidth(0.35f).height(d.spaceLg - d.spaceXxs)
                                    .padding(start = d.screenHorizPad, top = d.spaceXl, bottom = d.spaceMd)
                                    .clip(RoundedCornerShape(d.spaceSm)).background(BgSurface)
                            )
                            SkeletonRowLoader()
                        }
                    }
                }

                ui.error != null && !ui.isCacheLoaded -> item {
                    ErrorState(ui.error!!, onRetry = { viewModel.load(true) })
                }

                else -> {
                    // ── Guest interstitial — psychological timing ──────────────
                    item(key = "guestInterstitial") { GuestInterstitialEffect(adEngine) }

                    // ── Hero pager — content + 1 native ad slot every 15 min ──
                    if (ui.featured.isNotEmpty()) {
                        item(key = "hero") {
                            HeroBannerPager(
                                items          = ui.featured,
                                watchlistedIds = ui.watchlistedIds,
                                onWatchlist    = { viewModel.toggleHeroWatchlist(it) },
                                onClick        = { goDetail(it.id, it.mediaType) },
                                adEngine       = adEngine,
                            )
                        }
                    } else if (ui.isLoading) {
                        item(key = "heroBannerSkeleton") { SkeletonBannerLoader() }
                    }

                    // ── Remove ads upsell ──────────────────────────────────────
                    if (!removeAdsBannerDismissed && adEngine.shouldShowRemoveAdsBanner()) {
                        item(key = "removeAdsBanner") {
                            RemoveAdsBanner(
                                onUpgrade = { nav.navigate(Route.Premium.path) },
                                onDismiss = { removeAdsBannerDismissed = true },
                            )
                        }
                    }

                    // ── Genre grid mode ───────────────────────────────────────
                    if (ui.selectedGenreId != null) {
                        // Keep the bar visible in genre mode too
                        if (ui.genres.isNotEmpty()) {
                            item(key = "genreBar") {
                                StickyGlassGenreBar(
                                    genres     = ui.genres,
                                    selectedId = ui.selectedGenreId,
                                    onSelect   = { viewModel.selectGenre(it) },
                                )
                            }
                        }
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
                                                media   = m,
                                                onClick = { goDetail(m.id, m.mediaType) },
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
                        // ── Continue watching ─────────────────────────────────
                        if (ui.continueWatching.isNotEmpty()) {
                            item(key = "cwHeader") { SectionHeader("Continue Watching", "See All") }
                            item(key = "cwRow") {
                                LazyRow(
                                    contentPadding        = PaddingValues(horizontal = d.screenHorizPad),
                                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                                ) {
                                    items(ui.continueWatching, key = { it.mediaId + it.season + it.episode }) { h ->
                                        ContinueCard(h) {
                                            // Navigate to detail by mediaId; media type unknown from progress row alone
                                            nav.navigate(Route.Detail.go(h.mediaId, MediaType.MOVIE))
                                        }
                                    }
                                }
                            }
                        }

                        // ── Genre bar ─────────────────────────────────────────
                        if (ui.genres.isNotEmpty()) {
                            item(key = "genreBar") {
                                StickyGlassGenreBar(
                                    genres     = ui.genres,
                                    selectedId = ui.selectedGenreId,
                                    onSelect   = { viewModel.selectGenre(it) },
                                )
                            }
                        }

                        // ── Default feed ──────────────────────────────────────
                        ui.feedRows.forEachIndexed { feedRowIdx, row ->
                            when (row) {
                                is FeedRow.Section -> {
                                    item(key = "hdr_${row.section.id}") {
                                        SectionHeader(row.section.title, "See All")
                                    }
                                    item(key = "row_${row.section.id}") {
                                        // Determine if this section row gets an inline ad card
                                        val sectionIndex = ui.feedRows.indexOf(row)
                                        val hasInlineAd  = adEngine.shouldShowCardAdAtRow(sectionIndex)
                                        // Random slot within the row — stable per section ID so it
                                        // doesn't jump on recomposition
                                        val adSlotInRow  = (row.section.id.hashCode().and(0x7FFFFFFF) % maxOf(row.section.items.size, 1))
                                            .coerceIn(1, maxOf(row.section.items.size - 1, 1))

                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = d.screenHorizPad),
                                            horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                                        ) {
                                            row.section.items.forEachIndexed { itemIdx, m ->
                                                // Inject native ad at the random slot position
                                                if (hasInlineAd && itemIdx == adSlotInRow) {
                                                    item(key = "inline_ad_${row.section.id}") {
                                                        NativeAdRowCard(adEngine = adEngine)
                                                    }
                                                }
                                                item(key = m.id) {
                                                    MediaRowCard(m, onClick = { goDetail(m.id, m.mediaType) })
                                                }
                                            }
                                        }
                                    }
                                }
                                is FeedRow.NativeAdPlacement -> {
                                    // Full-width native ad section (between rows, not inside)
                                    // Only renders if inline card didn't already show for this row
                                    item(key = "native_ad_$feedRowIdx") { NativeAdCard(adEngine = adEngine) }
                                }
                                is FeedRow.InfinitePage -> {
                                    val label = if (row.page % 2 == 0) "More Movies" else "More Series"
                                    item(key = "inf_hdr_${row.page}") { SectionHeader(label, "") }
                                    item(key = "inf_row_${row.page}") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = d.screenHorizPad),
                                            horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                                        ) {
                                            items(row.items, key = { "${row.page}_${it.id}" }) { m ->
                                                MediaRowCard(m, onClick = { goDetail(m.id, m.mediaType) })
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (ui.isLoadingMore) { item(key = "loadMoreSkeleton") { LoadMoreSkeleton() } }
                    }

                    // No legacy AdBannerPlaceholder — banner ads live in SearchResultsBanner / FilesScreenBanner
                }
            }
        }

        // ── Sticky collapsing app bar ─────────────────────────────────────────
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

        // ── Pull-to-refresh pill indicator ────────────────────────────────────
        LaunchedEffect(pullOverscrollPx >= pullThresholdPx) {
            if (pullOverscrollPx >= pullThresholdPx && !ui.isRefreshing) {
                delay(150)
                if (pullOverscrollPx >= pullThresholdPx && !ui.isRefreshing) {
                    viewModel.load(forceRefresh = true)
                    pullOverscrollPx = 0f
                }
            }
        }

        val aboveThreshold   = pullOverscrollPx >= pullThresholdPx
        val showPillIndicator = pullOverscrollPx > 6f || ui.isRefreshing

        val pillRestingY = with(density) { (appBarHeightPx - collapseOffsetPx) + d.spaceMd.toPx() }
        val pillFollowY  = with(density) { (appBarHeightPx - collapseOffsetPx) + (pullOverscrollPx * 0.45f) }
        val pillTranslateY by animateFloatAsState(
            targetValue   = if (ui.isRefreshing || pullOverscrollPx == 0f) pillRestingY else pillFollowY,
            animationSpec = if (ui.isRefreshing) spring(dampingRatio = 0.55f, stiffness = 280f) else tween(0),
            label         = "ptrY",
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
            Box(contentAlignment = Alignment.Center, modifier = Modifier.graphicsLayer { translationY = pillTranslateY }) {
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
                            Text("Updating…", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
                        }
                        else -> {
                            Text(
                                if (aboveThreshold) "Release to refresh" else "Pull to refresh",
                                color      = if (aboveThreshold) Brand else White40,
                                fontSize   = d.textSm,
                                fontWeight = if (aboveThreshold) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Icon(
                                imageVector        = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint               = if (aboveThreshold) Brand else White40,
                                modifier           = Modifier
                                    .size(d.iconMd - 5.dp)
                                    .graphicsLayer { rotationZ = arrowAngle },
                            )
                        }
                    }
                }
            }
        }

        // ── Refresh error banner — shown when pull-to-refresh/background-refresh fails
        // but cached content is still displayed so we don't nuke the whole screen.
        AnimatedVisibility(
            visible = ui.refreshError != null,
            enter   = slideInVertically { -it } + fadeIn(),
            exit    = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = d.screenHorizPad, vertical = d.spaceLg),
        ) {
            InlineErrorBanner(
                message = ui.refreshError ?: "",
                onRetry = { viewModel.load(forceRefresh = true); viewModel.clearRefreshError() },
            )
        }
    }
}

// ── Collapsing Glass App Bar ──────────────────────────────────────────────────

@Composable
fun CollapsingGlassAppBar(
    collapseProgress: Float,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    val contentAlpha = (1f - collapseProgress * 1.8f).coerceIn(0f, 1f)
    val barAlpha     = (0.82f + 0.18f * (1f - collapseProgress)).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Brand.copy(.35f * (1f - collapseProgress)), Color.Transparent)
                    ),
                    start = Offset(0f, size.height),
                    end   = Offset(size.width, size.height),
                    strokeWidth = 0.8f,
                )
            }
            .graphicsLayer { alpha = barAlpha }
    ) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0xCC050510), Color(0x88050510), Color(0x00050510)))))
        Box(Modifier.matchParentSize().background(Color(0x09FFFFFF)))

        Column(
            Modifier.fillMaxWidth().statusBarsPadding().graphicsLayer { alpha = contentAlpha }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = d.appBarHorizPad, vertical = d.appBarVertPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
            ) {
                val inf   = rememberInfiniteTransition(label = "logoShimmer")
                val shimX by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), "lx")
                Text(
                    "REELZ",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(0f to Brand2, shimX to Color(0xFFB3D9FF), 1f to Brand)
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
                        .border(1.dp, Brush.horizontalGradient(listOf(Brand.copy(.4f), GlassBorderMd, Brand.copy(.2f))), RoundedCornerShape(d.radiusMd))
                        .clickable { onSearchClick() }
                        .padding(horizontal = d.searchBarHorizPad, vertical = d.searchBarVertPad),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(IconSearch, null, tint = Brand.copy(.7f), modifier = Modifier.size(d.iconMd - 4.dp))
                        Spacer(Modifier.width(d.spaceMd - d.spaceXxs))
                        Text("Search movies, series…", color = White40, fontSize = d.textMd)
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.clip(RoundedCornerShape(d.radiusSm)).background(BlueGlass)
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

// ── Premium genre bar ─────────────────────────────────────────────────────────

@Composable
fun StickyGlassGenreBar(
    genres     : List<Genre>,
    selectedId : String?,
    onSelect   : (String?) -> Unit,
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
        Text(
            text       = label,
            color      = if (selected) Color.White else White60,
            fontSize   = d.textXs,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines   = 1,
        )
    }
}

@Composable
fun PremiumGenreBar(
    genres: List<Genre>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
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
            Text("Browse by Genre", color = White60, fontSize = d.textSm, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        }
        LazyRow(
            contentPadding        = PaddingValues(horizontal = d.screenHorizPad, vertical = d.sectionVertPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp),
        ) {
            item { PremiumGenrePill("✦ All", selectedId == null) { onSelect(null) } }
            items(genres, key = { it.id }) { g -> PremiumGenrePill(g.name, selectedId == g.id) { onSelect(g.id) } }
        }
    }
}

@Composable
fun PremiumGenrePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val d = LocalDimensions.current
    val animBorder by animateColorAsState(if (selected) Brand else GlassBorder, tween(200), label = "pillBorder")
    val scale by animateFloatAsState(if (selected) 1.04f else 1f, spring(0.5f, 400f), label = "pillScale")
    Box(
        Modifier
            .scale(scale)
            .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
            .background(
                if (selected) Brush.linearGradient(listOf(BrandDeep, Brand.copy(.85f)))
                else Brush.linearGradient(listOf(BgSurface, BgRaised))
            )
            .border(d.borderThin, animBorder, RoundedCornerShape(d.radiusMd - d.spaceXxs))
            .clickable(onClick = onClick)
            .padding(horizontal = d.chipHorizPad + d.spaceXs, vertical = d.chipVertPad + d.spaceXs),
    ) {
        Text(text, color = if (selected) Color.White else White60, fontSize = d.textSm, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ── Load more skeleton ────────────────────────────────────────────────────────
// Shows a full section header + row skeleton so incoming content feels coherent.
// Replaces a bare spinner — user can see exactly what kind of content is coming.

@Composable
fun LoadMoreSkeleton(label: String = "Discovering more…") {
    val d = LocalDimensions.current
    val inf = rememberInfiniteTransition(label = "lmSkeleton")
    val pulse by inf.animateFloat(
        0.5f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        "lmPulse",
    )
    Column(Modifier.fillMaxWidth().padding(top = d.spaceXxs)) {
        // Section header skeleton
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = d.screenHorizPad, top = d.spaceXl, bottom = d.spaceMd, end = d.screenHorizPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            // Accent bar
            Box(
                Modifier.width(d.sectionAccentWidth).height(d.sectionAccentHeight)
                    .clip(RoundedCornerShape(d.spaceXxs))
                    .background(Brush.verticalGradient(listOf(Brand2.copy(pulse), Brand.copy(pulse))))
            )
            // Title shimmer
            Box(
                Modifier.width(140.dp).height(d.textXl.value.dp)
                    .clip(RoundedCornerShape(d.spaceXxs + 1.dp))
                    .background(BgSurface.copy(pulse))
            )
            Spacer(Modifier.weight(1f))
            // Tiny "loading" dot cluster
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0f, 200, 400).forEachIndexed { idx, _ ->
                    val dotInf = rememberInfiniteTransition(label = "dot$idx")
                    val dotPulse by dotInf.animateFloat(
                        0.3f, 1f,
                        infiniteRepeatable(tween(500, delayMillis = idx * 150), RepeatMode.Reverse),
                        "dp$idx"
                    )
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Brand.copy(dotPulse)))
                }
            }
        }
        // Row skeleton cards
        SkeletonRowLoader()
    }
}

// ── Hero banner pager ─────────────────────────────────────────────────────────

@Composable
fun HeroBannerPager(
    items: List<Media>,
    watchlistedIds: Set<String> = emptySet(),
    onWatchlist: (Media) -> Unit = {},
    onClick: (Media) -> Unit,
    adEngine: AdEngine? = null,
) {
    val d = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp

    // Build page list: inject native ad slide after every 3 content slides
    // but only if adEngine is present and ads are enabled
    val showNativeAd = adEngine?.adsEnabled() == true && adEngine.nativeAdUnitIdOrNull() != null
    // pageCount: content items + optional 1 ad slot (rotates via pager auto-scroll)
    val pageCount = items.size + (if (showNativeAd) 1 else 0)
    val adSlotIndex = if (showNativeAd && items.size >= 3) 3 else -1  // inject at position 3

    val pagerState = rememberPagerState { pageCount }

    // Auto-rotate every 4.5s for content, 15s dwell for ad slot
    LaunchedEffect(pagerState) {
        while (true) {
            val isAdSlot = pagerState.currentPage == adSlotIndex
            delay(if (isAdSlot) 15_000L else 4_500L)
            if (pagerState.pageCount > 0) {
                pagerState.animateScrollToPage(
                    (pagerState.currentPage + 1) % pagerState.pageCount,
                    animationSpec = tween(600, easing = FastOutSlowInEasing),
                )
            }
        }
    }

    Box(Modifier.fillMaxWidth()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            // Ad slot — renders native hero ad in place of a content slide
            if (page == adSlotIndex && adEngine != null) {
                val pageOffset = (pagerState.currentPage - page + pagerState.currentPageOffsetFraction).coerceIn(-1f, 1f)
                HeroBannerAd(
                    adEngine = adEngine,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha  = 1f - 0.12f * abs(pageOffset)
                            scaleX = 1f - 0.03f * abs(pageOffset)
                            scaleY = 1f - 0.03f * abs(pageOffset)
                        },
                )
                return@HorizontalPager
            }
            // Content slide — adjust index when ad slot is before this page
            val contentIndex = if (adSlotIndex >= 0 && page > adSlotIndex) page - 1 else page
            val media      = items.getOrNull(contentIndex) ?: return@HorizontalPager
            val pageOffset = (pagerState.currentPage - page + pagerState.currentPageOffsetFraction).coerceIn(-1f, 1f)
            val isWatchlisted = media.id in watchlistedIds

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
                    model              = media.posterUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
                // Multi-layer gradient overlay — same rich look as old app
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x10000000), 0.3f to Color(0x00000000), 0.65f to Color(0x99000000), 1f to Bg)))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Bg.copy(.35f), Color.Transparent, Color.Transparent, Bg.copy(.25f)))))
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Brand.copy(0.04f)), radius = 900f)))

                Column(Modifier.align(Alignment.BottomStart).padding(d.heroPadding)) {
                    // "FEATURED" badge with pulsing dot
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
                    // Metadata row: rating • year • media type
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                    ) {
                        if (media.rating > 0) RatingChip(media.rating)
                        (null as String?)?.let { year: String ->
                            Box(Modifier.size(d.spaceXxs + 1.dp).clip(androidx.compose.foundation.shape.CircleShape).background(White40))
                            Text(year.take(4), color = White60, fontSize = d.textMd)
                        }
                        Box(Modifier.size(d.spaceXxs + 1.dp).clip(androidx.compose.foundation.shape.CircleShape).background(White40))
                        Text(if (media.mediaType == MediaType.TV) "TV Series" else "Movie", color = White60, fontSize = d.textMd)
                    }
                    Spacer(Modifier.height(d.spaceLg))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrandButton(
                            text    = "Watch Now",
                            onClick = { onClick(media) },
                            icon    = { Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(d.iconMd - 4.dp)) },
                        )
                        GhostButton(
                            text    = if (isWatchlisted) "✓ Saved" else "+ Watchlist",
                            onClick = { onWatchlist(media) },
                        )
                    }
                }
            }
        }

        // Animated page indicators
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = d.screenHorizPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(items.size) { i ->
                val selected = pagerState.currentPage == i
                val width by animateDpAsState(
                    if (selected) d.pageIndicatorWidthSelected else d.pageIndicatorWidth,
                    spring(0.6f, 400f), label = "iw",
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(d.spaceXxs))
                        .width(width).height(d.pageIndicatorHeight)
                        .background(
                            if (selected) Brush.horizontalGradient(listOf(Brand2, Brand))
                            else Brush.horizontalGradient(listOf(White40, White40))
                        )
                )
            }
        }
    }
}

// ── Continue watching card ────────────────────────────────────────────────────

@Composable
fun ContinueCard(
    h: com.axio.reelz.core.database.WatchProgressRow,
    onClick: () -> Unit,
) {
    val d = LocalDimensions.current
    val progress = if (h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else 0f

    Column(Modifier.width(d.continueCardWidth).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().height(d.continueCardThumbHeight)
                .clip(RoundedCornerShape(d.radiusMd))
                .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
                .background(BgRaised)
        ) {
            if (!h.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model              = h.posterUrl,
                    contentDescription = h.title,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
            Box(Modifier.fillMaxSize().background(Color(0x55000000)), Alignment.Center) {
                Box(
                    Modifier.size(d.buttonHeightMd - d.spaceXs).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0x99000000))
                        .border(1.dp, White.copy(.3f), androidx.compose.foundation.shape.CircleShape),
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
        Text(
            h.title.ifBlank { h.mediaId },  // fallback to mediaId only if title wasn't saved yet
            color = White80, fontSize = d.textSm, maxLines = 1,
            overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
        )
        if (h.season > 0) Text("S${h.season} · E${h.episode}", color = Brand.copy(.8f), fontSize = d.textXxs, fontWeight = FontWeight.SemiBold)
    }
}
