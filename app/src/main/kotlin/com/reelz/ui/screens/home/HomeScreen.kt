package com.reelz.ui.screens.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.reelz.BuildConfig
import com.reelz.data.model.*
import com.reelz.ui.components.*
import com.reelz.ui.theme.*

@Composable
fun HomeScreen(
    onMediaClick: (Int, MediaType) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()

    Box(Modifier.fillMaxSize().background(Surface900)) {
        when {
            ui.isLoading  -> FullScreenLoader()
            ui.error != null -> ErrorState(ui.error!!, onRetry = vm::refresh)
            else -> HomeContent(ui, onMediaClick)
        }
    }
}

@Composable
private fun HomeContent(ui: HomeUiState, onMediaClick: (Int, MediaType) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        // ── Featured hero ──────────────────────────────────────────────────────
        ui.featured?.let { featured ->
            item { HeroSection(featured, onMediaClick) }
        }

        // ── Continue Watching ──────────────────────────────────────────────────
        if (ui.continueWatching.isNotEmpty()) {
            item { SectionHeader("Continue Watching") }
            item { ContinueWatchingRow(ui.continueWatching, onMediaClick) }
        }

        // ── Dynamic sections — each section = 2 items (header + row) ──────────
        ui.sections.forEach { section ->
            item(key = "header_${section.title}") { SectionHeader(section.title) }
            item(key = "row_${section.title}")    { MediaRow(section.items, onMediaClick) }
        }
    }
}

// ── Hero / Featured ───────────────────────────────────────────────────────────
@Composable
private fun HeroSection(media: Media, onMediaClick: (Int, MediaType) -> Unit) {
    Box(Modifier.fillMaxWidth().height(520.dp)) {
        AsyncImage(
            model = BuildConfig.TMDB_IMG_ORIGINAL + media.backdropPath,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to Color.Black.copy(0.1f),
                    0.5f to Color.Black.copy(0.3f),
                    1.0f to Surface900,
                )
            )
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
        ) {
            Text(
                "REELZ",
                color = Primary,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                media.title,
                color = White,
                fontWeight = FontWeight.Black,
                fontSize = 28.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RatingChip(media.voteAverage)
                Text("•", color = White40)
                Text(media.releaseDate?.take(4) ?: "", color = White60, fontSize = 13.sp)
                Text("•", color = White40)
                Text(
                    if (media.mediaType == MediaType.TV) "TV Series" else "Movie",
                    color = White60, fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                media.overview,
                color = White60,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onMediaClick(media.tmdbId, media.mediaType) },
                colors  = ButtonDefaults.buttonColors(containerColor = Primary),
                shape   = RoundedCornerShape(8.dp),
                modifier = Modifier.height(46.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("Watch Now", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

// ── Section header ────────────────────────────────────────────────────────────
@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        color = White,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

// ── Horizontal media row ──────────────────────────────────────────────────────
@Composable
fun MediaRow(items: List<Media>, onMediaClick: (Int, MediaType) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.tmdbId }) { media ->
            MediaCard(media, onMediaClick)
        }
    }
}

@Composable
fun MediaCard(media: Media, onClick: (Int, MediaType) -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable { onClick(media.tmdbId, media.mediaType) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(178.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface700),
        ) {
            AsyncImage(
                model = BuildConfig.TMDB_IMG_W500 + media.posterPath,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(.7f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    "★ ${"%.1f".format(media.voteAverage)}",
                    color = Gold,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            media.title,
            color = White80,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
        )
    }
}

// ── Continue watching row ─────────────────────────────────────────────────────
@Composable
private fun ContinueWatchingRow(
    history: List<com.reelz.data.model.WatchHistory>,
    onMediaClick: (Int, MediaType) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(history, key = { it.key }) { h ->
            val type = if (h.mediaType == "TV") MediaType.TV else MediaType.MOVIE
            val progress = if (h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else 0f
            Column(
                modifier = Modifier
                    .width(160.dp)
                    .clickable { onMediaClick(h.tmdbId, type) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface700),
                ) {
                    AsyncImage(
                        model = BuildConfig.TMDB_IMG_W500 + h.posterPath,
                        contentDescription = h.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(.3f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PlayCircle, null, tint = White.copy(.85f), modifier = Modifier.size(32.dp))
                    }
                    LinearProgressIndicator(
                        progress    = { progress },
                        modifier    = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                        color       = Primary,
                        trackColor  = White20,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(h.title, color = White80, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (h.season > 0)
                    Text("S${h.season} E${h.episode}", color = White40, fontSize = 11.sp)
            }
        }
    }
}

// ── Rating chip ───────────────────────────────────────────────────────────────
@Composable
fun RatingChip(rating: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Star, null, tint = Gold, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text("${"%.1f".format(rating)}", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
