package com.reelz.ui.screens.detail

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
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
import com.reelz.ui.components.*
import com.reelz.ui.screens.player.PlayerActivity
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repo: MediaRepository,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val error: String? = null,
        val detail: MediaDetail? = null,
        val episodes: List<Episode> = emptyList(),
        val selectedSeason: Int = 1,
        val isInWatchlist: Boolean = false,
        val isLiked: Boolean = false,
        val isEpisodesLoading: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var currentMedia: Media? = null

    fun load(tmdbId: Int, mediaType: MediaType) {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            try {
                val detail      = repo.getDetail(tmdbId, mediaType)
                val inWatchlist = repo.isInWatchlist(tmdbId)
                val liked       = repo.isLiked(tmdbId)
                currentMedia = Media(
                    id = detail.tmdbId, tmdbId = detail.tmdbId, title = detail.title,
                    overview = detail.overview, posterPath = detail.posterPath,
                    backdropPath = detail.backdropPath, releaseDate = detail.releaseDate,
                    voteAverage = detail.voteAverage, voteCount = detail.voteCount,
                    popularity = 0.0, mediaType = mediaType,
                )
                _ui.update { it.copy(isLoading = false, detail = detail, isInWatchlist = inWatchlist, isLiked = liked) }
                // Auto-load first season episodes for TV
                if (mediaType == MediaType.TV && detail.seasons.isNotEmpty()) {
                    loadEpisodes(tmdbId, 1)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun selectSeason(tmdbId: Int, season: Int) {
        _ui.update { it.copy(selectedSeason = season) }
        loadEpisodes(tmdbId, season)
    }

    private fun loadEpisodes(tmdbId: Int, season: Int) {
        viewModelScope.launch {
            _ui.update { it.copy(isEpisodesLoading = true) }
            try {
                val eps = repo.getSeasonEpisodes(tmdbId, season)
                _ui.update { it.copy(episodes = eps, isEpisodesLoading = false) }
            } catch (_: Exception) {
                _ui.update { it.copy(isEpisodesLoading = false) }
            }
        }
    }

    fun toggleWatchlist() {
        val m = currentMedia ?: return
        viewModelScope.launch {
            val now = repo.toggleWatchlist(m)
            _ui.update { it.copy(isInWatchlist = now) }
        }
    }

    fun toggleLike() {
        val m = currentMedia ?: return
        viewModelScope.launch {
            val now = repo.toggleLike(m)
            _ui.update { it.copy(isLiked = now) }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────
@Composable
fun DetailScreen(
    tmdbId: Int,
    mediaType: MediaType,
    nav: NavController,
    vm: DetailViewModel = hiltViewModel(),
) {
    val ui  by vm.ui.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(tmdbId) { vm.load(tmdbId, mediaType) }

    fun launchPlayer(season: Int = 0, episode: Int = 0, epName: String = "") {
        val d = ui.detail ?: return
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
            putExtra("tmdbId",     d.tmdbId)
            putExtra("mediaType",  d.mediaType.name)
            putExtra("season",     season)
            putExtra("episode",    episode)
            putExtra("title",      if (epName.isNotBlank()) epName else d.title)
            putExtra("posterPath", d.posterPath)
        })
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when {
            ui.isLoading -> FullScreenLoader()
            ui.error != null -> ErrorState(ui.error!!, onRetry = { vm.load(tmdbId, mediaType) })
            ui.detail != null -> DetailContent(
                ui          = ui,
                onBack      = { nav.popBackStack() },
                onPlayMovie = { launchPlayer() },
                onPlayEpisode = { s, e, name -> launchPlayer(s, e, name) },
                onSeasonSelect = { vm.selectSeason(tmdbId, it) },
                onWatchlist = { vm.toggleWatchlist() },
                onLike      = { vm.toggleLike() },
                onSimilarClick = { id, type -> nav.navigate(com.reelz.ui.Route.Detail.go(id, type)) },
            )
        }
    }
}

@Composable
private fun DetailContent(
    ui: DetailViewModel.UiState,
    onBack: () -> Unit,
    onPlayMovie: () -> Unit,
    onPlayEpisode: (Int, Int, String) -> Unit,
    onSeasonSelect: (Int) -> Unit,
    onWatchlist: () -> Unit,
    onLike: () -> Unit,
    onSimilarClick: (Int, MediaType) -> Unit,
) {
    val detail  = ui.detail!!
    val isMovie = detail.mediaType == MediaType.MOVIE

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {

        // ── Backdrop hero ──────────────────────────────────────────────────
        item {
            Box(Modifier.fillMaxWidth().height(420.dp)) {
                AsyncImage(
                    model = BuildConfig.TMDB_IMG_ORIGINAL + detail.backdropPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(.45f), Bg))
                ))
                // Back button
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.statusBarsPadding().padding(8.dp)
                        .clip(CircleShape).background(Color.Black.copy(.5f))
                ) { Icon(Icons.Default.ArrowBack, null, tint = White) }

                // Poster + meta
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AsyncImage(
                            model = BuildConfig.TMDB_IMG_W342 + detail.posterPath,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(90.dp).height(134.dp)
                                .clip(RoundedCornerShape(12.dp)).background(BgRaised),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(detail.title, color = White, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!detail.tagline.isNullOrBlank())
                                Text(detail.tagline, color = White60, fontSize = 12.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RatingChip(detail.voteAverage)
                                Text("•", color = White40)
                                Text(detail.releaseDate?.take(4) ?: "", color = White60, fontSize = 13.sp)
                                if (detail.runtime != null) { Text("•", color = White40); Text(formatRuntime(detail.runtime), color = White60, fontSize = 13.sp) }
                                if (!isMovie) { Text("•", color = White40); Text("${detail.numberOfSeasons}S", color = White60, fontSize = 13.sp) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                detail.genres.take(3).forEach { g ->
                                    Box(
                                        Modifier.clip(RoundedCornerShape(5.dp)).background(BgSurface)
                                            .border(1.dp, GlassBorderMd, RoundedCornerShape(5.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) { Text(g.name, color = White60, fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Action row ─────────────────────────────────────────────────────
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isMovie) {
                    BrandButton(
                        text     = "Watch Now",
                        onClick  = onPlayMovie,
                        modifier = Modifier.weight(1f),
                        icon     = { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp)) },
                    )
                }
                // Watchlist button
                OutlinedButton(
                    onClick  = onWatchlist,
                    shape    = RoundedCornerShape(100.dp),
                    border   = BorderStroke(1.dp, if (ui.isInWatchlist) Brand else GlassBorderMd),
                    modifier = Modifier.height(48.dp).let { if (isMovie) it else it.weight(1f) },
                ) {
                    Icon(
                        if (ui.isInWatchlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        null,
                        tint = if (ui.isInWatchlist) Brand else White60,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (ui.isInWatchlist) "Saved" else "Save", color = if (ui.isInWatchlist) Brand else White60)
                }
                // Like button
                OutlinedButton(
                    onClick = onLike,
                    shape   = RoundedCornerShape(100.dp),
                    border  = BorderStroke(1.dp, if (ui.isLiked) Like else GlassBorderMd),
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(
                        if (ui.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null,
                        tint = if (ui.isLiked) Like else White60,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // ── Overview ───────────────────────────────────────────────────────
        item {
            var expanded by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Overview", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    detail.overview,
                    color = White60,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail.overview.length > 150) {
                    Text(
                        if (expanded) "Show less" else "Show more",
                        color = Brand,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(top = 4.dp),
                    )
                }
            }
        }

        // ── Movie metadata ─────────────────────────────────────────────────
        if (isMovie && (detail.runtime != null || detail.status != null)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    detail.runtime?.let { MetaChip("Runtime", formatRuntime(it)) }
                    detail.status?.let   { MetaChip("Status", it) }
                    if (detail.voteCount > 0) MetaChip("Votes", "${detail.voteCount}")
                }
            }
        }

        // ── TV: Season selector + episodes ─────────────────────────────────
        if (!isMovie && detail.seasons.isNotEmpty()) {
            item {
                SectionHeader("Episodes")
                // Season tabs
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(detail.seasons) { s ->
                        val sel = ui.selectedSeason == s.seasonNumber
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .background(if (sel) Brand else GlassMd)
                                .border(1.dp, if (sel) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(100.dp))
                                .clickable { onSeasonSelect(s.seasonNumber) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "S${s.seasonNumber}",
                                color = if (sel) Color.White else White60,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            if (ui.isEpisodesLoading) {
                item { Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CircularProgressIndicator(color = Brand) } }
            } else {
                items(ui.episodes, key = { it.id }) { ep ->
                    EpisodeRow(
                        episode = ep,
                        onClick = { onPlayEpisode(ep.seasonNumber, ep.episodeNumber, ep.name) },
                    )
                }
            }
        }

        // ── Cast ───────────────────────────────────────────────────────────
        if (detail.cast.isNotEmpty()) {
            item { SectionHeader("Cast") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(detail.cast, key = { it.id }) { c ->
                        CastCard(c)
                    }
                }
            }
        }

        // ── Similar ────────────────────────────────────────────────────────
        if (detail.similar.isNotEmpty()) {
            item { SectionHeader("More Like This") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(detail.similar, key = { it.tmdbId }) { m ->
                        com.reelz.ui.components.MediaRowCard(m) { onSimilarClick(m.tmdbId, m.mediaType) }
                    }
                }
            }
        }
    }
}

// ── Episode row ───────────────────────────────────────────────────────────────
@Composable
fun EpisodeRow(episode: Episode, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.width(110.dp).height(64.dp).clip(RoundedCornerShape(8.dp)).background(BgRaised),
        ) {
            if (episode.stillPath != null) {
                AsyncImage(
                    model = BuildConfig.TMDB_IMG_W342 + episode.stillPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(.25f)), Alignment.Center) {
                Icon(Icons.Default.PlayCircle, null, tint = White.copy(.8f), modifier = Modifier.size(26.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text("E${episode.episodeNumber} · ${episode.name}", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(episode.overview.ifBlank { "No description." }, color = White60, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            episode.runtime?.let {
                Spacer(Modifier.height(3.dp))
                Text("${it}m", color = White40, fontSize = 10.sp)
            }
        }
        Icon(Icons.Default.PlayArrow, null, tint = Brand, modifier = Modifier.size(20.dp))
    }
    Divider(color = GlassBorder, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
}

// ── Cast card ─────────────────────────────────────────────────────────────────
@Composable
fun CastCard(cast: CastMember) {
    Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(BgRaised)) {
            if (cast.profilePath != null) {
                AsyncImage(
                    model = BuildConfig.TMDB_IMG_W342 + cast.profilePath,
                    contentDescription = cast.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.Person, null, tint = White40, modifier = Modifier.fillMaxSize().padding(12.dp))
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(cast.name, color = White80, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 13.sp)
        Text(cast.character, color = White40, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ── Meta chip ─────────────────────────────────────────────────────────────────
@Composable
fun MetaChip(label: String, value: String) {
    Column {
        Text(label, color = White40, fontSize = 10.sp)
        Text(value, color = White80, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

fun formatRuntime(minutes: Int): String {
    val h = minutes / 60; val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
