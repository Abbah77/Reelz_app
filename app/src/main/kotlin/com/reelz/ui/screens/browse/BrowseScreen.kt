package com.reelz.ui.screens.browse

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.reelz.BuildConfig
import com.reelz.data.model.*
import com.reelz.data.repository.MediaRepository
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

// ── ViewModel ─────────────────────────────────────────────────────────────────

sealed class FeedRow {
    data class Section(val section: HomeSection) : FeedRow()
    data class InfinitePage(val items: List<Media>, val page: Int) : FeedRow()
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: MediaRepository,
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
        val isCacheLoaded: Boolean = false,   // true once cache was shown
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var infinitePage = 1
    private var isInfiniteExhausted = false
    private var categorySections: List<HomeSection> = emptyList()
    private var categorySectionsEmitted = false

    init {
        // 1. Load cache instantly (forceRefresh=false)
        load(forceRefresh = false)
        viewModelScope.launch {
            repo.getHistory().collect { h ->
                _ui.update { it.copy(continueWatching = h) }
            }
        }
    }

    fun load(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            if (forceRefresh) {
                // Pull-to-refresh: show subtle "refreshing" state, keep old content visible
                _ui.update { it.copy(isRefreshing = true, error = null) }
            } else {
                _ui.update { it.copy(isLoading = true, error = null) }
            }
            infinitePage = 1
            isInfiniteExhausted = false
            categorySectionsEmitted = false
            try {
                val sections = repo.getHomeSections(forceRefresh)
                categorySections = sections
                val featured = sections.firstOrNull()?.items?.take(6) ?: emptyList()
                val genres   = try { repo.getMovieGenres() } catch (_: Exception) { emptyList() }
                val feedRows = sections.map { FeedRow.Section(it) }
                _ui.update {
                    it.copy(
                        isLoading    = false,
                        isRefreshing = false,
                        feedRows     = feedRows,
                        featured     = featured,
                        genres       = genres,
                        isCacheLoaded = true,
                    )
                }
                categorySectionsEmitted = true
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, isRefreshing = false, error = e.message ?: "Failed to load") }
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
                    genreItems       = it.genreItems + items,
                    genrePage        = nextPage,
                    hasMoreGenrePages = items.isNotEmpty(),
                    isGenreLoading   = false,
                ) }
            } catch (_: Exception) { _ui.update { it.copy(isGenreLoading = false) } }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────
@Composable
fun BrowseScreen(nav: NavController, vm: BrowseViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()

    // Pull-to-refresh drag state
    var pullOffset by remember { mutableStateOf(0f) }
    val pullThreshold = 80f
    val isPulling = pullOffset > 0f
    val pullProgress = (pullOffset / pullThreshold).coerceIn(0f, 1f)
    val pullScale by animateFloatAsState(if (pullProgress > 0.9f) 1.2f else 1f, spring(0.4f, 600f), label = "ps")

    fun goDetail(id: Int, type: MediaType) = nav.navigate(Route.Detail.go(id, type))

    // Infinite scroll trigger
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { pullOffset = 0f },
                    onDragEnd = {
                        if (pullOffset >= pullThreshold) vm.load(forceRefresh = true)
                        pullOffset = 0f
                    },
                    onDragCancel = { pullOffset = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        val atTop = !listState.canScrollBackward
                        if (atTop && dragAmount > 0) {
                            pullOffset = (pullOffset + dragAmount * 0.5f).coerceAtMost(pullThreshold * 1.5f)
                        } else {
                            pullOffset = 0f
                        }
                    }
                )
            }
    ) {
        LazyColumn(
            state  = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp),
        ) {
            // ── Glass App Bar ────────────────────────────────────────────────
            item(key = "appbar") {
                GlassAppBar(
                    onSearchClick = { nav.navigate(Route.Search.path) }
                )
            }

            when {
                // Initial skeleton loading — show instantly from cache
                ui.isLoading && !ui.isCacheLoaded -> {
                    item(key = "skeletonBanner") { SkeletonBannerLoader() }
                    item(key = "skeletonRow1") {
                        Column {
                            Box(Modifier.width(180.dp).height(20.dp).padding(start=16.dp, top=28.dp, bottom=12.dp)
                                .clip(RoundedCornerShape(4.dp)).background(BgSurface))
                            SkeletonRowLoader()
                        }
                    }
                    item(key = "skeletonRow2") {
                        Column {
                            Box(Modifier.width(140.dp).height(20.dp).padding(start=16.dp, top=28.dp, bottom=12.dp)
                                .clip(RoundedCornerShape(4.dp)).background(BgSurface))
                            SkeletonRowLoader()
                        }
                    }
                }

                ui.error != null && !ui.isCacheLoaded -> item {
                    ErrorState(ui.error!!, onRetry = { vm.load(true) })
                }

                else -> {
                    // ── Hero pager ──────────────────────────────────────────
                    if (ui.featured.isNotEmpty()) {
                        item(key = "hero") {
                            HeroBannerPager(ui.featured, onClick = { goDetail(it.tmdbId, it.mediaType) })
                        }
                    } else if (ui.isLoading) {
                        item(key = "heroBannerSkeleton") { SkeletonBannerLoader() }
                    }

                    // ── TOP Genre chips (premium horizontal bar) ───────────
                    if (ui.genres.isNotEmpty()) {
                        item(key = "genreBar") {
                            PremiumGenreBar(
                                genres = ui.genres,
                                selectedId = ui.selectedGenreId,
                                onSelect = { vm.selectGenre(it) }
                            )
                        }
                    }

                    // ── Genre mode: paginated grid ─────────────────────────
                    if (ui.selectedGenreId != null) {
                        if (ui.genreItems.isEmpty() && ui.isGenreLoading) {
                            item(key="genreSkeletonRow") { SkeletonRowLoader() }
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
                                item(key="genreLoadMore") { LoadMoreSkeleton() }
                            }
                        }
                    } else {
                        // ── Default feed ───────────────────────────────────
                        if (ui.continueWatching.isNotEmpty()) {
                            item(key="cwHeader") { SectionHeader("Continue Watching", "See All") }
                            item(key="cwRow") {
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

                        ui.feedRows.forEach { row ->
                            when (row) {
                                is FeedRow.Section -> {
                                    item(key = "hdr_${row.section.title}") {
                                        SectionHeader(row.section.title, "See All")
                                    }
                                    item(key = "row_${row.section.title}") {
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
                            }
                        }

                        if (ui.isLoadingMore) {
                            item(key="loadMoreSkeleton") { LoadMoreSkeleton() }
                        }
                    }

                    item(key="adBanner") { AdBannerPlaceholder(Modifier.padding(vertical = 10.dp)) }
                }
            }
        }

        // ── Pull-to-refresh indicator ────────────────────────────────────────
        AnimatedVisibility(
            visible = isPulling || ui.isRefreshing,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit  = fadeOut() + scaleOut(targetScale = 0.7f),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .scale(if (ui.isRefreshing) 1f else pullScale)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(BrandDeep, Bg)))
                    .border(1.dp, BlueBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (ui.isRefreshing) {
                    CinematicSpinner(size = 22.dp)
                } else {
                    // Arrow icon drawn manually
                    Text("↓", color = Brand, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Glass App Bar ─────────────────────────────────────────────────────────────
@Composable
fun GlassAppBar(onSearchClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                // Bottom separator
                drawLine(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, Brand.copy(.3f), Color.Transparent)),
                    start = Offset(0f, size.height),
                    end   = Offset(size.width, size.height),
                    strokeWidth = 0.8f,
                )
            }
    ) {
        // Layered glass effect
        Box(Modifier.matchParentSize().background(
            Brush.verticalGradient(listOf(Color(0xC8050510), Color(0x88050510), Color(0x00050510)))
        ))
        Box(Modifier.matchParentSize().background(Color(0x07FFFFFF)))

        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            // Logo + icons row
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Reelz logo with electric blue gradient
                val inf = rememberInfiniteTransition(label = "logoShimmer")
                val shimX by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), "lx")
                Text(
                    "REELZ",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0f to Brand2,
                                shimX to Color(0xFFB3D9FF),
                                1f to Brand,
                            )
                        ),
                        fontWeight = FontWeight.Black,
                        fontSize = 26.sp,
                        letterSpacing = 4.sp,
                    ),
                )
                Spacer(Modifier.weight(1f))
                // Notification bell placeholder
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(GlassSm)
                        .border(1.dp, GlassBorderMd, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🔔", fontSize = 16.sp)
                }
            }

            // ── Horizontal search bar (like MovieBox) ─────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0x18FFFFFF), Color(0x0AFFFFFF)))
                    )
                    .border(
                        1.dp,
                        Brush.horizontalGradient(listOf(Brand.copy(.4f), GlassBorderMd, Brand.copy(.2f))),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onSearchClick() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(IconSearch, null, tint = Brand.copy(.8f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Search movies, series, actors…",
                        color = White40,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                    )
                    Spacer(Modifier.weight(1f))
                    // Filter hint badge
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(BlueGlass)
                            .border(1.dp, BlueBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("Filter", color = Brand, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ── Premium genre bar (visually distinct from the one below the banner) ────────
@Composable
fun PremiumGenreBar(
    genres: List<Genre>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
) {
    Column {
        // Section divider label
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(Brand2, Brand))))
            Spacer(Modifier.width(8.dp))
            Text("Browse by Genre", color = White60, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                PremiumGenrePill("✦ All", selectedId == null) { onSelect(null) }
            }
            items(genres) { g ->
                PremiumGenrePill(g.name, selectedId == g.id) { onSelect(g.id) }
            }
        }
    }
}

@Composable
fun PremiumGenrePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val animBorder by animateColorAsState(
        if (selected) Brand else GlassBorder,
        tween(200), label = "pillBorder"
    )
    val scale by animateFloatAsState(
        if (selected) 1.04f else 1f,
        spring(0.5f, 400f), label = "pillScale"
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

// ── Load more skeleton (replaces spinner) ─────────────────────────────────────
@Composable
fun LoadMoreSkeleton() {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        SkeletonRowLoader()
    }
}

// ── Hero banner pager — glass redesign ───────────────────────────────────────
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
            val media = items[page]
            val pageOffset = (pagerState.currentPage - page + pagerState.currentPageOffsetFraction).coerceIn(-1f, 1f)

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(screenH * 0.6f)
                    .clickable { onClick(media) }
                    .graphicsLayer {
                        alpha  = 1f - 0.12f * abs(pageOffset)
                        scaleX = 1f - 0.03f * abs(pageOffset)
                        scaleY = 1f - 0.03f * abs(pageOffset)
                    }
            ) {
                AsyncImage(
                    model = BuildConfig.TMDB_IMG_ORIGINAL + media.backdropPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Multi-layer gradient overlay for glass depth
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f   to Color(0x10000000),
                        0.3f to Color(0x00000000),
                        0.65f to Color(0x99000000),
                        1f   to Bg,
                    )
                ))
                Box(Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Bg.copy(.35f), Color.Transparent, Color.Transparent, Bg.copy(.25f))
                    )
                ))
                // Blue vignette edge tint
                Box(Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        listOf(Color.Transparent, Brand.copy(0.04f)),
                        radius = 900f,
                    )
                ))

                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    // Glass badge
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
                        color = White,
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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
                        color = White60,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

        // Dot indicators — glass style
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
                        .width(width)
                        .height(5.dp)
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

// ── Continue watching card ────────────────────────────────────────────────────
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
                model = BuildConfig.TMDB_IMG_W342 + h.posterPath,
                contentDescription = h.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color(0x55000000)), Alignment.Center) {
                Box(
                    Modifier
                        .size(40.dp).clip(CircleShape)
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
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(Brush.horizontalGradient(listOf(Brand, Brand2)))
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(h.title, color = White80, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        if (h.season > 0) Text("S${h.season} · E${h.episode}", color = Brand.copy(.8f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}
