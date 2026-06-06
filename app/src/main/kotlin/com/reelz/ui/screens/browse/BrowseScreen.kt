package com.reelz.ui.screens.browse

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
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
import com.reelz.ui.screens.player.PlayerActivity
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
        val featured: List<Media> = emptyList(),   // for hero pager
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
                _ui.update { it.copy(
                    isLoading = false,
                    sections  = sections,
                    featured  = featured,
                    genres    = genres,
                ) }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun selectGenre(genreId: Int?) {
        val current = _ui.value.selectedGenreId
        if (genreId == current) {
            _ui.update { it.copy(selectedGenreId = null, genreItems = emptyList()) }
            return
        }
        _ui.update { it.copy(selectedGenreId = genreId, isGenreLoading = true) }
        viewModelScope.launch {
            try {
                val items = repo.discoverMovies(genreId)
                _ui.update { it.copy(genreItems = items, isGenreLoading = false) }
            } catch (_: Exception) {
                _ui.update { it.copy(isGenreLoading = false) }
            }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────
@Composable
fun BrowseScreen(nav: NavController, vm: BrowseViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current

    fun goDetail(id: Int, type: MediaType) = nav.navigate(Route.Detail.go(id, type))

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(bottom = 90.dp),
    ) {
        // ── App bar ─────────────────────────────────────────────────────────
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Reelz",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = Brand,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp,
                    ),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { nav.navigate(Route.Search.path) }) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(GlassMd)
                            .border(1.dp, GlassBorderMd, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Search, null, tint = White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        when {
            ui.isLoading -> item { FullScreenLoader() }
            ui.error != null -> item {
                ErrorState(ui.error!!, onRetry = { vm.load(true) })
            }
            else -> {
                // ── Hero pager (auto + manual) ─────────────────────────────
                if (ui.featured.isNotEmpty()) {
                    item { HeroBannerPager(ui.featured, onClick = { goDetail(it.tmdbId, it.mediaType) }) }
                }

                // ── Genre filter pills ─────────────────────────────────────
                if (ui.genres.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item { GenrePill("All", ui.selectedGenreId == null) { vm.selectGenre(null) } }
                            items(ui.genres) { g ->
                                GenrePill(g.name, ui.selectedGenreId == g.id) { vm.selectGenre(g.id) }
                            }
                        }
                    }
                }

                // ── Genre grid (when filter active) ───────────────────────
                if (ui.selectedGenreId != null) {
                    if (ui.isGenreLoading) {
                        item { Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) { CircularProgressIndicator(color = Brand) } }
                    } else {
                        item {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 800.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement   = Arrangement.spacedBy(8.dp),
                                userScrollEnabled = false,
                            ) {
                                items(ui.genreItems.take(18)) { m ->
                                    MediaPosterCard(
                                        media    = m,
                                        onClick  = { goDetail(m.tmdbId, m.mediaType) },
                                        modifier = Modifier.aspectRatio(0.65f),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ── Continue Watching ──────────────────────────────────
                    if (ui.continueWatching.isNotEmpty()) {
                        item { SectionHeader("Continue Watching") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                        item(key = "hdr_${section.title}") { SectionHeader(section.title) }
                        item(key = "row_${section.title}") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(section.items, key = { it.tmdbId }) { m ->
                                    MediaRowCard(m, onClick = { goDetail(m.tmdbId, m.mediaType) })
                                }
                            }
                        }
                    }
                }

                // ── Ad banner ──────────────────────────────────────────────
                item { AdBannerPlaceholder(Modifier.padding(vertical = 8.dp)) }
            }
        }
    }
}

// ── Hero auto-scrolling banner (like MovieBox) ─────────────────────────────────
@Composable
fun HeroBannerPager(items: List<Media>, onClick: (Media) -> Unit) {
    val pagerState = rememberPagerState { items.size }
    val ctx = LocalContext.current

    // Auto-scroll every 4s
    LaunchedEffect(pagerState) {
        while (true) {
            delay(4_000)
            if (pagerState.pageCount > 0) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % pagerState.pageCount)
            }
        }
    }

    Box(Modifier.fillMaxWidth()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            val media = items[page]
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(LocalConfiguration.current.screenHeightDp.dp * 0.55f)
                    .clickable { onClick(media) }
            ) {
                AsyncImage(
                    model = BuildConfig.TMDB_IMG_ORIGINAL + media.backdropPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Gradient
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(.1f), Color.Black.copy(.3f), Bg))
                ))
                // Content
                Column(
                    Modifier.align(Alignment.BottomStart).padding(20.dp)
                ) {
                    Text("REELZ", color = Brand, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 3.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(media.title, color = White, fontWeight = FontWeight.Black, fontSize = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RatingChip(media.voteAverage)
                        Text("•", color = White40)
                        Text(media.releaseDate?.take(4) ?: "", color = White60, fontSize = 13.sp)
                        Text("•", color = White40)
                        Text(if (media.mediaType == MediaType.TV) "TV Series" else "Movie", color = White60, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(media.overview, color = White60, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                    Spacer(Modifier.height(14.dp))
                    BrandButton(
                        text  = "Watch Now",
                        onClick = { onClick(media) },
                        icon  = { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        }

        // Page indicators
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(items.size) { i ->
                val selected = pagerState.currentPage == i
                Box(
                    Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .width(if (selected) 18.dp else 6.dp)
                        .height(3.dp)
                        .background(if (selected) Brand else White40)
                )
            }
        }
    }
}

// ── Continue watching card ────────────────────────────────────────────────────
@Composable
fun ContinueCard(h: WatchHistory, onClick: () -> Unit) {
    val progress = if (h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else 0f
    Column(Modifier.width(160.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(10.dp)).background(BgRaised)) {
            AsyncImage(
                model = BuildConfig.TMDB_IMG_W342 + h.posterPath,
                contentDescription = h.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(.3f)), Alignment.Center) {
                Icon(Icons.Default.PlayCircle, null, tint = White.copy(.85f), modifier = Modifier.size(32.dp))
            }
            LinearProgressIndicator(
                progress    = { progress },
                modifier    = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color       = Brand,
                trackColor  = White20,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(h.title, color = White80, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (h.season > 0) Text("S${h.season} E${h.episode}", color = White40, fontSize = 11.sp)
    }
}
