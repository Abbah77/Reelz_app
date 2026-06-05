package com.streamapp.ui.screens.detail

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.streamapp.data.model.*
import com.streamapp.ui.components.MediaCard
import com.streamapp.ui.theme.*

@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    if (state.loading) {
        Box(Modifier.fillMaxSize().background(Surface900)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Primary)
        }
        return
    }

    if (state.error != null) {
        Box(Modifier.fillMaxSize().background(Surface900)) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error ?: "Error", color = Error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.load() }) { Text("Retry") }
            }
        }
        return
    }

    val backdropUrl  = state.movie?.backdropUrl ?: state.show?.backdropUrl
    val title        = state.movie?.title ?: state.show?.name ?: ""
    val overview     = state.movie?.overview ?: state.show?.overview ?: ""
    val rating       = state.movie?.voteAverage ?: state.show?.voteAverage ?: 0.0
    val year         = state.movie?.year ?: state.show?.year ?: ""
    val genres       = state.movie?.genres ?: state.show?.genres ?: emptyList()
    val tagline      = state.movie?.tagline ?: state.show?.tagline ?: ""
    val isMovie      = state.mediaType == "movie"
    val streamResult = state.streamResult

    Box(Modifier.fillMaxSize().background(Surface900)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 90.dp),
        ) {
            // Backdrop
            item {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    AsyncImage(
                        model = backdropUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                colors = listOf(Black.copy(alpha = 0.3f), Color.Transparent),
                            )
                        )
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Surface900),
                                startY = 500f,
                            )
                        )
                    )
                    if (isMovie) {
                        val streamReady = streamResult is StreamResult.Found
                        FloatingActionButton(
                            onClick = {
                                val url = (streamResult as? StreamResult.Found)?.url ?: return@FloatingActionButton
                                onPlay(url)
                            },
                            containerColor = if (streamReady) Primary else Surface700,
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.Center).size(64.dp),
                        ) {
                            if (streamResult is StreamResult.Loading) {
                                CircularProgressIndicator(Modifier.size(24.dp), color = White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = White, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }

            // Title + meta
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (tagline.isNotEmpty()) {
                        Text(tagline, style = MaterialTheme.typography.bodySmall, color = PrimaryLight)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(title, style = MaterialTheme.typography.displaySmall, color = White)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (rating > 0) {
                            Surface(color = Gold.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "★ ${"%.1f".format(rating)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Gold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                        if (year.isNotEmpty()) Text(year, style = MaterialTheme.typography.bodySmall, color = White60)
                        if (!isMovie) Text(
                            "${state.show?.numberOfSeasons} Season(s)",
                            style = MaterialTheme.typography.bodySmall, color = White60,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        genres.take(3).forEach { genre ->
                            Surface(
                                color = Surface700,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Stroke),
                            ) {
                                Text(
                                    genre.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = White60,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val url = (streamResult as? StreamResult.Found)?.url ?: return@Button
                                onPlay(url)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isMovie) "Watch Now" else "Play S1 E1")
                        }
                        OutlinedButton(
                            onClick = {},
                            border = BorderStroke(1.dp, Stroke),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = White),
                        ) {
                            Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Watchlist", Modifier.size(20.dp))
                        }
                        OutlinedButton(
                            onClick = {},
                            border = BorderStroke(1.dp, Stroke),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = White),
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share", Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Overview", style = MaterialTheme.typography.headlineSmall, color = White)
                    Spacer(Modifier.height(6.dp))
                    Text(overview, style = MaterialTheme.typography.bodyMedium, color = White60, lineHeight = 22.sp)
                }
            }

            // Episodes (TV)
            if (!isMovie && state.currentSeason != null) {
                item { Spacer(Modifier.height(20.dp)) }
                item {
                    EpisodeSection(
                        season = state.currentSeason!!,
                        totalSeasons = state.show?.numberOfSeasons ?: 1,
                        onSeasonSelect = { viewModel.loadSeason(it) },
                        onEpisodePlay = { ep ->
                            viewModel.playEpisode(ep)
                            val url = (viewModel.state.value.streamResult as? StreamResult.Found)?.url
                            if (url != null) onPlay(url)
                        },
                    )
                }
            }

            // Recommendations
            if (state.recommendations.isNotEmpty()) {
                item {
                    Text(
                        "More Like This",
                        style = MaterialTheme.typography.headlineSmall,
                        color = White,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.recommendations) { rec ->
                            MediaCard(
                                item = rec,
                                onClick = { onItemClick(rec) },
                                modifier = Modifier.width(130.dp),
                            )
                        }
                    }
                }
            }
        }

        // Back button
        IconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun EpisodeSection(
    season: Season,
    totalSeasons: Int,
    onSeasonSelect: (Int) -> Unit,
    onEpisodePlay: (Episode) -> Unit,
) {
    // Use Int state — mutableIntStateOf is available in Compose 1.5+ (BOM 2024.10 ships 1.7)
    var selectedSeason by remember { mutableIntStateOf(season.seasonNumber) }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("Episodes", style = MaterialTheme.typography.headlineSmall, color = White)
        Spacer(Modifier.height(10.dp))

        if (totalSeasons > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(totalSeasons) { idx ->
                    val sNum = idx + 1
                    FilterChip(
                        selected = sNum == selectedSeason,
                        onClick  = { selectedSeason = sNum; onSeasonSelect(sNum) },
                        label    = { Text("Season $sNum", style = MaterialTheme.typography.labelMedium) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary,
                            selectedLabelColor     = White,
                            containerColor         = Surface700,
                            labelColor             = White60,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = sNum == selectedSeason,
                            selectedBorderColor = Color.Transparent,
                            borderColor         = Stroke,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        season.episodes.forEach { ep ->
            EpisodeRow(episode = ep, onPlay = { onEpisodePlay(ep) })
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, onPlay: () -> Unit) {
    Surface(
        color  = Surface800,
        shape  = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Stroke),
        modifier = Modifier.fillMaxWidth().clickable { onPlay() },
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .height(65.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface700),
                contentAlignment = Alignment.Center,
            ) {
                if (episode.stillUrl != null) {
                    AsyncImage(
                        model = episode.stillUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = White.copy(alpha = 0.8f), modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(episode.label, style = MaterialTheme.typography.labelSmall, color = PrimaryLight)
                Text(
                    episode.name,
                    style    = MaterialTheme.typography.titleMedium,
                    color    = White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!episode.overview.isNullOrEmpty()) {
                    Text(
                        episode.overview,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = White60,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
