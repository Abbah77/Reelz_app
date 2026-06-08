package com.reelz.ui.screens.browse

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
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

// ── ViewModel ─────────────────────────────────────────────────────────────────
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: MediaRepository,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val error: String? = null,
        val featured: List<Media> = emptyList(),
        val sections: List<HomeSection> = emptyList(),
        val genres: List<Genre> = emptyList(),
        val selectedGenreId: Int? = null,
        val genreItems: List<Media> = emptyList(),
        val isGenreLoading: Boolean = false,
        val continueWatching: List<WatchHistory> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        load(forceRefresh = false)
        viewModelScope.launch {
            repo.getHistory().collect { h ->
                _ui.update { it.copy(continueWatching = h) }
            }
        }
    }

    fun load(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            try {
                val sections = repo.getHomeSections(forceRefresh)
                val featured = sections.firstOrNull()?.items?.take(6) ?: emptyList()
                val genres   = try { repo.getMovieGenres() } catch (_: Exception) { emptyList() }
                _ui.update { it.copy(isLoading = false, sections = sections, featured = featured, genres = genres) }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun selectGenre(genreId: Int?) {
        val current = _ui.value.selectedGenreId
        if (genreId == current) { _ui.update { it.copy(selectedGenreId = null, genreItems = emptyList()) }; return }
        _ui.update { it.copy(selectedGenreId = genreId, isGenreLoading = true) }
        viewModelScope.launch {
            try {
                val items = repo.discoverMovies(genreId)
                _ui.update { it.copy(genreItems = items, isGenreLoading = false) }
            } catch (_: Exception) { _ui.update { it.copy(isGenreLoading = false) } }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────
@Composable
fun BrowseScreen(nav: NavController, vm: BrowseViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()

    fun goDetail(id: Int, type: MediaType) = nav.navigate(Route.Detail.go(id, type))

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        // ── App bar ─────────────────────────────────────────────────────────
        item {
            Box(
                Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(listOf(Bg, Bg.copy(0f)))
                )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Wordmark with gradient
                    Text(
                        "REELZ",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            brush = Brush.horizontalGradient(listOf(Brand2, Brand, Brand.copy(.8f))),
                            fontWeight = FontWeight.Black,
                            fontSize = 26.sp,
                            letterSpacing = 3.sp,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    // Search button
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(GlassMd)
                            .border(1.dp, AmberBorder, CircleShape)
                            .clickable { nav.navigate(Route.Search.path) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(IconSearch, null, tint = White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        when {
            ui.isLoading -> item { Box(Modifier.fillMaxWidth().height(400.dp), Alignment.Center) { FullScreenLoader() } }
            ui.error != null -> item { ErrorState(ui.error!!, onRetry = { vm.load(true) }) }
            else -> {
                // ── Hero pager ─────────────────────────────────────────────
                if (ui.featured.isNotEmpty()) {
                    item { HeroBannerPager(ui.featured, onClick = { goDetail(it.tmdbId, it.mediaType) }) }
                }

                // ── Genre filter pills ─────────────────────────────────────
                if (ui.genres.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item { GenrePill("All", ui.selectedGenreId == null) { vm.selectGenre(null) } }
                            items(ui.genres) { g ->
                                GenrePill(g.name, ui.selectedGenreId == g.id) { vm.selectGenre(g.id) }
                            }
                        }
                    }
                }

                // ── Genre grid ─────────────────────────────────────────────
                if (ui.selectedGenreId != null) {
                    if (ui.isGenreLoading) {
                        item { Box(Modifier.fillMaxWidth().height(240.dp), Alignment.Center) { CinematicSpinner() } }
                    } else {
                        item {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 800.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement   = Arrangement.spacedBy(10.dp),
                                userScrollEnabled = false,
                            ) {
                                items(ui.genreItems.take(18)) { m ->
                                    MediaPosterCard(
                                        media   = m,
                                        onClick = { goDetail(m.tmdbId, m.mediaType) },
                                        modifier = Modifier.aspectRatio(0.65f),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ── Continue Watching ──────────────────────────────────
                    if (ui.continueWatching.isNotEmpty()) {
                        item { SectionHeader("Continue Watching", "See All") }
                        item {
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

                    // ── Dynamic sections ───────────────────────────────────
                    ui.sections.forEach { section ->
                        item(key = "hdr_${section.title}") {
                            SectionHeader(section.title, "See All")
                        }
                        item(key = "row_${section.title}") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(section.items, key = { it.tmdbId }) { m ->
                                    MediaRowCard(m, onClick = { goDetail(m.tmdbId, m.mediaType) })
                                }
                            }
                        }
                    }
                }

                item { AdBannerPlaceholder(Modifier.padding(vertical = 10.dp)) }
            }
        }
    }
}

// ── Hero banner pager ─────────────────────────────────────────────────────────
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
                    .height(screenH * 0.58f)
                    .clickable { onClick(media) }
                    .graphicsLayer {
                        alpha       = 1f - 0.15f * kotlin.math.abs(pageOffset)
                        scaleX      = 1f - 0.04f * kotlin.math.abs(pageOffset)
                        scaleY      = 1f - 0.04f * kotlin.math.abs(pageOffset)
                    }
            ) {
                AsyncImage(
                    model = BuildConfig.TMDB_IMG_ORIGINAL + media.backdropPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Multi-layer cinematic gradient
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f    to Color(0x22000000),
                        0.4f  to Color(0x00000000),
                        0.7f  to Color(0x88000000),
                        1f    to Bg,
                    )
                ))
                // Side vignette
                Box(Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Bg.copy(.4f), Color.Transparent, Color.Transparent, Bg.copy(.3f))
                    )
                ))

                // Content
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    // Badge
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AmberGlass)
                            .border(1.dp, AmberBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                            icon  = { Icon(IconPlay, null, tint = Color(0xFF1A0F00), modifier = Modifier.size(16.dp)) },
                        )
                        GhostButton(
                            text  = "+ Watchlist",
                            onClick = {},
                        )
                    }
                }
            }
        }

        // Pill indicators
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
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
            // Progress bar at bottom
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
