package com.reelz.ui.screens.detail

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.reelz.BuildConfig
import com.reelz.data.model.*
import com.reelz.ui.screens.home.MediaCard
import com.reelz.ui.screens.home.RatingChip
import com.reelz.ui.screens.home.SectionHeader
import com.reelz.ui.screens.player.PlayerActivity
import com.reelz.ui.theme.*

@Composable
fun DetailScreen(
    tmdbId: Int,
    mediaType: MediaType,
    onBack: () -> Unit,
    onMediaClick: (Int, MediaType) -> Unit,
    vm: DetailViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(tmdbId) { vm.load(tmdbId, mediaType) }

    fun launchPlayer(season: Int = 0, episode: Int = 0) {
        val detail = ui.detail ?: return
        context.startActivity(
            Intent(context, PlayerActivity::class.java).apply {
                putExtra("tmdbId",     detail.tmdbId)
                putExtra("mediaType",  detail.mediaType.name)
                putExtra("season",     season)
                putExtra("episode",    episode)
                putExtra("title",      detail.title)
                putExtra("posterPath", detail.posterPath)
            }
        )
    }

    Box(Modifier.fillMaxSize().background(Surface900)) {
        when {
            ui.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
            ui.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(ui.error!!, color = White60)
                    Button(
                        onClick = { vm.load(tmdbId, mediaType) },
                        colors  = ButtonDefaults.buttonColors(containerColor = Primary),
                    ) { Text("Retry") }
                }
            }
            ui.detail != null -> DetailContent(
                ui            = ui,
                onBack        = onBack,
                onMediaClick  = onMediaClick,
                onPlayMovie   = { launchPlayer() },
                onPlayEpisode = { s, e -> launchPlayer(s, e) },
                onSeasonSelect = { vm.selectSeason(tmdbId, it) },
                onWatchlist   = vm::toggleWatchlist,
            )
        }
    }
}

@Composable
private fun DetailContent(
    ui: DetailUiState,
    onBack: () -> Unit,
    onMediaClick: (Int, MediaType) -> Unit,
    onPlayMovie: () -> Unit,
    onPlayEpisode: (Int, Int) -> Unit,
    onSeasonSelect: (Int) -> Unit,
    onWatchlist: () -> Unit,
) {
    val detail = ui.detail!!
    val isMovie = detail.mediaType == MediaType.MOVIE

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {

        // ── Backdrop + hero ────────────────────────────────────────────────────
        item {
            Box(Modifier.fillMaxWidth().height(420.dp)) {
                AsyncImage(
                    model = BuildConfig.TMDB_IMG_ORIGINAL + detail.backdropPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(.4f), Surface900))
                ))
                IconButton(
                    onClick  = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .clip(CircleShape).background(Color.Black.copy(.5f))
                ) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = White)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AsyncImage(
                            model = BuildConfig.TMDB_IMG_W500 + detail.posterPath,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(90.dp).height(134.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Surface700),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(detail.title, color = White, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 2)
                            if (!detail.tagline.isNullOrBlank())
                                Text(detail.tagline, color = White60, fontSize = 12.sp, fontStyle = FontStyle.Italic, maxLines = 1)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RatingChip(detail.voteAverage)
                                Text("•", color = White40)
                                Text(detail.releaseDate?.take(4) ?: "", color = White60, fontSize = 13.sp)
                                if (detail.runtime != null) {
                                    Text("•", color = White40)
                                    Text(formatRuntime(detail.runtime), color = White60, fontSize = 13.sp)
                                }
                                if (!isMovie) {
                                    Text("•", color = White40)
                                    Text("${detail.numberOfSeasons}S", color = White60, fontSize = 13.sp)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                detail.genres.take(3).forEach { g ->
                                    Surface(color = Surface600, shape = RoundedCornerShape(4.dp)) {
                                        Text(
                                            g.name,
                                            color = White60,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Action buttons ─────────────────────────────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isMovie) {
                    Button(
                        onClick  = onPlayMovie,
                        colors   = ButtonDefaults.buttonColors(containerColor = Primary),
                        shape    = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Watch Now", fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick  = onWatchlist,
                    shape    = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(48.dp).let { if (isMovie) it else it.weight(1f) },
                    border   = BorderStroke(1.dp, if (ui.isInWatchlist) Primary else Stroke),
                ) {
                    Icon(
                        if (ui.isInWatchlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        null,
                        tint     = if (ui.isInWatchlist) Primary else White60,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (ui.isInWatchlist) "Saved" else "Watchlist",
                        color = if (ui.isInWatchlist) Primary else White60,
                    )
                }
            }
        }

        // ── Overview ───────────────────────────────────────────────────────────
        item {
            var expanded by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Overview", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    detail.overview,
                    color    = White60,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail.overview.length > 200) {
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (expanded) "Show less" else "Read more", color = Primary, fontSize = 13.sp)
                    }
                }
            }
        }

        // ── TV: Season selector ────────────────────────────────────────────────
        if (!isMovie && detail.seasons.isNotEmpty()) {
            item {
                SectionHeader("Seasons")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(detail.seasons, key = { it.seasonNumber }) { season ->
                        val sel = season.seasonNumber == ui.selectedSeason
                        FilterChip(
                            selected = sel,
                            onClick  = { onSeasonSelect(season.seasonNumber) },
                            label    = { Text("Season ${season.seasonNumber}") },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary,
                                selectedLabelColor     = White,
                            ),
                        )
                    }
                }
            }
        }

        // ── TV: Episodes ───────────────────────────────────────────────────────
        if (!isMovie) {
            if (ui.episodes.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Primary, modifier = Modifier.size(28.dp))
                    }
                }
            } else {
                items(ui.episodes, key = { it.id }) { ep ->
                    EpisodeRow(ep, onClick = { onPlayEpisode(ep.seasonNumber, ep.episodeNumber) })
                    HorizontalDivider(color = Stroke.copy(.3f), modifier = Modifier.padding(start = 148.dp, end = 16.dp))
                }
            }
        }

        // ── Cast ───────────────────────────────────────────────────────────────
        if (detail.cast.isNotEmpty()) {
            item {
                SectionHeader("Cast")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(detail.cast, key = { it.id }) { member -> CastCard(member) }
                }
            }
        }

        // ── Recommendations ────────────────────────────────────────────────────
        if (ui.recommendations.isNotEmpty()) {
            item { SectionHeader("You May Also Like") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(ui.recommendations, key = { it.tmdbId }) { m -> MediaCard(m, onMediaClick) }
                }
            }
        }
    }
}

// ── Episode row ───────────────────────────────────────────────────────────────
@Composable
private fun EpisodeRow(ep: Episode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(120.dp).height(68.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Surface700),
            contentAlignment = Alignment.Center,
        ) {
            if (ep.stillPath != null) {
                AsyncImage(
                    model = BuildConfig.TMDB_IMG_W500 + ep.stillPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Icon(Icons.Default.PlayCircle, null, tint = White.copy(.8f), modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("E${ep.episodeNumber}", color = White40, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (ep.runtime != null) Text("  ${ep.runtime}m", color = White40, fontSize = 11.sp)
            }
            Text(ep.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ep.overview, color = White60, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        }
    }
}

// ── Cast card ─────────────────────────────────────────────────────────────────
@Composable
private fun CastCard(member: CastMember) {
    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = BuildConfig.TMDB_IMG_W500 + member.profilePath,
            contentDescription = member.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(CircleShape).background(Surface700),
        )
        Spacer(Modifier.height(4.dp))
        Text(member.name,      color = White80, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(member.character, color = White40, fontSize = 9.sp,  maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatRuntime(minutes: Int): String {
    val h = minutes / 60; val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
