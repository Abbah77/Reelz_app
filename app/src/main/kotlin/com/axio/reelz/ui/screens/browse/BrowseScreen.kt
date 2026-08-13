package com.axio.reelz.ui.screens.browse

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.ads.NativeAdCard
import com.axio.reelz.app.Route
import com.axio.reelz.core.network.NetworkResult
import com.axio.reelz.data.model.Media
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.data.repository.CatalogRepository
import com.axio.reelz.data.repository.LibraryRepository
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Feed row sealed class ────────────────────────────────────────────────────

sealed class FeedRow {
    data class Section(val section: com.axio.reelz.data.model.FeedSection) : FeedRow()
    data class InfinitePage(val items: List<Media>, val page: Int) : FeedRow()
    object NativeAdPlacement : FeedRow()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: CatalogRepository,
    private val libraryRepo: LibraryRepository,
) : androidx.lifecycle.ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val isBackgroundRefreshing: Boolean = false,
        val error: String? = null,
        val featured: List<Media> = emptyList(),
        val feedRows: List<FeedRow> = emptyList(),
        val isLoadingMore: Boolean = false,
        val watchlistedIds: Set<String> = emptySet(),
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
    }

    private fun initLoad() {
        viewModelScope.launch {
            isInfiniteExhausted = false
            infiniteCursor      = null

            // Step 1: cache-first (instant)
            val cacheResult = repo.getFeed(forceRefresh = false)

            when (cacheResult) {
                is NetworkResult.Success -> {
                    val sections  = cacheResult.data
                    val fromCache = cacheResult.fromCache

                    if (sections.isNotEmpty()) {
                        _ui.update {
                            it.copy(
                                isLoading              = false,
                                error                  = null,
                                featured               = pickFeatured(sections),
                                feedRows               = buildFeedRows(sections),
                                isBackgroundRefreshing = fromCache,
                            )
                        }

                        // Step 2: silent background refresh if data was stale
                        if (fromCache) {
                            val freshResult = repo.getFeed(forceRefresh = true)
                            when (freshResult) {
                                is NetworkResult.Success -> {
                                    if (freshResult.data.isNotEmpty()) {
                                        _ui.update {
                                            it.copy(
                                                isBackgroundRefreshing = false,
                                                featured               = pickFeatured(freshResult.data),
                                                feedRows               = buildFeedRows(freshResult.data),
                                            )
                                        }
                                    } else {
                                        _ui.update { it.copy(isBackgroundRefreshing = false) }
                                    }
                                }
                                else -> _ui.update { it.copy(isBackgroundRefreshing = false) }
                            }
                        }
                    } else {
                        // Empty cache → full network load
                        _ui.update { it.copy(isLoading = true, error = null) }
                        loadFromNetwork()
                    }
                }
                is NetworkResult.Error -> {
                    _ui.update {
                        it.copy(
                            isLoading = false,
                            error     = "Couldn't load content. Check your connection.",
                        )
                    }
                }
                NetworkResult.Loading -> _ui.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun loadFromNetwork() {
        val result = repo.getFeed(forceRefresh = true)
        when (result) {
            is NetworkResult.Success -> _ui.update {
                it.copy(
                    isLoading = false,
                    error     = null,
                    featured  = pickFeatured(result.data),
                    feedRows  = buildFeedRows(result.data),
                )
            }
            is NetworkResult.Error -> _ui.update {
                it.copy(
                    isLoading = false,
                    error     = "Couldn't load content. Check your connection.",
                )
            }
            NetworkResult.Loading -> _ui.update { it.copy(isLoading = false) }
        }
    }

    private fun pickFeatured(sections: List<com.axio.reelz.data.model.FeedSection>): List<Media> =
        sections.firstOrNull()?.items?.take(5) ?: emptyList()

    private fun buildFeedRows(sections: List<com.axio.reelz.data.model.FeedSection>): List<FeedRow> =
        buildList {
            sections.forEachIndexed { index, section ->
                if (section.items.isNotEmpty()) {
                    add(FeedRow.Section(section))
                    if ((index + 1) % 3 == 0) add(FeedRow.NativeAdPlacement)
                }
            }
        }

    fun load(forceRefresh: Boolean = true) {
        if (!forceRefresh) { initLoad(); return }
        viewModelScope.launch {
            _ui.update { it.copy(isRefreshing = true, error = null) }
            isInfiniteExhausted = false
            infiniteCursor      = null

            val result = repo.getFeed(forceRefresh = true)
            when (result) {
                is NetworkResult.Success -> _ui.update {
                    it.copy(
                        isRefreshing = false,
                        featured     = pickFeatured(result.data),
                        feedRows     = buildFeedRows(result.data),
                    )
                }
                is NetworkResult.Error -> _ui.update {
                    it.copy(isRefreshing = false, error = result.message)
                }
                NetworkResult.Loading -> _ui.update { it.copy(isRefreshing = false) }
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
                        val alreadyPresent = st.feedRows.any {
                            it is FeedRow.InfinitePage && it.page == pageIndex
                        }
                        if (alreadyPresent) return@update st.copy(isLoadingMore = false)
                        st.copy(
                            feedRows      = st.feedRows + FeedRow.InfinitePage(fresh, pageIndex),
                            isLoadingMore = false,
                        )
                    }
                }
                else -> {
                    isInfiniteExhausted = true
                    _ui.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }
}

// ── BrowseScreen composable ───────────────────────────────────────────────────

@Composable
fun BrowseScreen(
    nav: NavController,
    adEngine: AdEngine,
    viewModel: BrowseViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
) {
    val ui by viewModel.ui.collectAsState()
    val d  = LocalDimensions.current

    // Trigger infinite scroll when near bottom
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0 && lastVisible >= total - 4) {
                    viewModel.loadMoreInfinite()
                }
            }
    }

    LazyColumn(
        state    = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        // ── Skeleton loading ──────────────────────────────────────────────────
        if (ui.isLoading) {
            item(key = "sk_banner") { SkeletonBannerLoader() }
            item(key = "sk_header_1") { BrowseSkeletonSectionHeader() }
            item(key = "sk_row_1") { SkeletonRowLoader() }
            item(key = "sk_header_2") { BrowseSkeletonSectionHeader() }
            item(key = "sk_row_2") { SkeletonRowLoader() }
            item(key = "sk_header_3") { BrowseSkeletonSectionHeader() }
            item(key = "sk_row_3") { SkeletonRowLoader() }
            return@LazyColumn
        }

        // ── Error state ───────────────────────────────────────────────────────
        ui.error?.let { err ->
            item(key = "error") {
                ErrorState(
                    message  = err,
                    onRetry  = { viewModel.load() },
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
            return@LazyColumn
        }

        // ── Background refresh indicator ──────────────────────────────────────
        if (ui.isBackgroundRefreshing) {
            item(key = "bg_refresh") {
                LinearProgressIndicator(
                    modifier   = Modifier.fillMaxWidth().height(2.dp),
                    color      = Brand,
                    trackColor = Color.Transparent,
                )
            }
        }

        // ── Hero banner ───────────────────────────────────────────────────────
        if (ui.featured.isNotEmpty()) {
            item(key = "hero") {
                HeroBannerCarousel(
                    items   = ui.featured,
                    onClick = { media -> nav.navigate(Route.Detail.go(media.id, media.mediaType)) },
                )
            }
        }

        // ── Feed content ──────────────────────────────────────────────────────
        items(
            count = ui.feedRows.size,
            key   = { index ->
                when (val row = ui.feedRows[index]) {
                    is FeedRow.Section        -> "section_${row.section.id}"
                    is FeedRow.InfinitePage   -> "page_${row.page}"
                    FeedRow.NativeAdPlacement -> "native_$index"
                }
            },
        ) { index ->
            when (val row = ui.feedRows[index]) {
                is FeedRow.Section -> {
                    SectionHeader(title = row.section.title)
                    LazyRow(
                        contentPadding        = PaddingValues(horizontal = d.screenHorizPad, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - 2.dp),
                    ) {
                        items(row.section.items, key = { it.id }) { media ->
                            MediaRowCard(
                                media   = media,
                                onClick = { nav.navigate(Route.Detail.go(media.id, media.mediaType)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(d.spaceSm))
                }

                is FeedRow.InfinitePage -> {
                    SectionHeader(title = "More to Explore")
                    LazyRow(
                        contentPadding        = PaddingValues(horizontal = d.screenHorizPad, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - 2.dp),
                    ) {
                        items(row.items, key = { it.id }) { media ->
                            MediaRowCard(
                                media   = media,
                                onClick = { nav.navigate(Route.Detail.go(media.id, media.mediaType)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(d.spaceSm))
                }

                FeedRow.NativeAdPlacement -> NativeAdCard(adEngine = adEngine)
            }
        }

        // ── Pagination loader ─────────────────────────────────────────────────
        if (ui.isLoadingMore) {
            item(key = "loading_more") {
                Box(
                    modifier         = Modifier.fillMaxWidth().padding(d.spaceLg),
                    contentAlignment = Alignment.Center,
                ) { SmallSpinner() }
            }
        }

        item(key = "bottom_pad") { Spacer(Modifier.height(100.dp)) }
    }
}

// ── Hero Banner Carousel ──────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroBannerCarousel(
    items: List<Media>,
    onClick: (Media) -> Unit,
) {
    val d       = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val pager   = rememberPagerState { items.size }

    // Auto-advance
    LaunchedEffect(items.size) {
        if (items.size > 1) {
            while (true) {
                delay(4500)
                pager.animateScrollToPage((pager.currentPage + 1) % items.size)
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(screenH * d.heroImageRatio)
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val media = items[page]
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { onClick(media) }
            ) {
                AsyncImage(
                    model              = media.backdropUrl ?: media.posterUrl,
                    contentDescription = media.title,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.5f to Color.Transparent,
                                1f to Bg,
                            )
                        )
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(d.heroPadding),
                    verticalArrangement = Arrangement.spacedBy(d.spaceSm),
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(d.radiusSm))
                            .background(
                                if (media.mediaType == MediaType.TV)
                                    Brush.horizontalGradient(listOf(Brand.copy(.85f), Brand2.copy(.85f)))
                                else
                                    Brush.horizontalGradient(listOf(Color(0xCCE8A020), Color(0xCCFFB830)))
                            )
                            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceXxs)
                    ) {
                        Text(
                            if (media.mediaType == MediaType.TV) "TV SERIES" else "MOVIE",
                            color      = Color.White,
                            fontSize   = d.textXxs,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                        )
                    }
                    Text(
                        media.title,
                        color      = Color.White,
                        fontSize   = d.textHero,
                        fontWeight = FontWeight.Black,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                        lineHeight = (d.textHero.value * 1.1f).sp,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        media.releaseYear?.let {
                            Text(it, color = White60, fontSize = d.textSm)
                        }
                        if (media.rating > 0) RatingChip(media.rating)
                    }
                }
            }
        }

        // Page indicators
        if (items.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = d.spaceLg),
                horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                items.indices.forEach { i ->
                    val sel = i == pager.currentPage
                    Box(
                        Modifier
                            .height(d.spaceXs)
                            .width(if (sel) d.spaceLg else d.spaceXs + 2.dp)
                            .clip(RoundedCornerShape(d.spaceXxs))
                            .background(if (sel) Brand else White40)
                    )
                }
            }
        }
    }
}

// ── Skeleton section header ───────────────────────────────────────────────────

@Composable
private fun BrowseSkeletonSectionHeader() {
    val d   = LocalDimensions.current
    val inf = rememberInfiniteTransition(label = "skHdr")
    val off by inf.animateFloat(
        -1.5f, 2.5f,
        infiniteRepeatable(tween(950, easing = LinearEasing)),
        label = "skHdrOff",
    )
    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to BgRaised,
            (off * 0.4f + 0.3f).coerceIn(0f, 1f) to BgSurface,
            1f to BgRaised,
        ),
        start = Offset.Zero,
        end   = Offset(Float.POSITIVE_INFINITY, 0f),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad, vertical = d.spaceMd),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
    ) {
        Box(Modifier.width(d.sectionAccentWidth).height(d.sectionAccentHeight).clip(RoundedCornerShape(2.dp)).background(brush))
        Box(Modifier.width(120.dp).height(d.spaceMd).clip(RoundedCornerShape(d.spaceXs)).background(brush))
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(48.dp).height(d.spaceSm).clip(RoundedCornerShape(d.spaceXs)).background(brush))
    }
}
