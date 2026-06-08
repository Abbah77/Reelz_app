package com.reelz.ui.screens.shorts

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

// Custom action icons as SVG paths
private val IconHeart: ImageVector get() = ImageVector.Builder("Heart", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(20.84f, 4.61f)
        arcTo(5.5f, 5.5f, 0f, false, false, 12f, 8.5f)
        arcTo(5.5f, 5.5f, 0f, false, false, 3.16f, 4.61f)
        arcTo(5.5f, 5.5f, 0f, false, false, 12f, 20f)
        arcTo(5.5f, 5.5f, 0f, false, false, 20.84f, 4.61f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, fill = SolidColor(Color.Transparent))
}.build()

private val IconShare: ImageVector get() = ImageVector.Builder("Share", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(13f, 5f); lineTo(20f, 12f); lineTo(13f, 19f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconBookmark: ImageVector get() = ImageVector.Builder("Bookmark", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(12f, 16f); lineTo(5f, 21f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, fill = SolidColor(Color.Transparent))
}.build()

private val IconInfoCircle: ImageVector get() = ImageVector.Builder("Info", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
        moveTo(12f, 8f); lineTo(12f, 8.01f)
        moveTo(12f, 11f); lineTo(12f, 16f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round,
       fill = SolidColor(Color.Transparent))
}.build()

@HiltViewModel
class ShortsViewModel @Inject constructor(
    private val repo: MediaRepository,
) : ViewModel() {
    data class UiState(val items: List<Media> = emptyList(), val isLoading: Boolean = true)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                val trending = repo.getHomeSections(false).firstOrNull()?.items ?: emptyList()
                _ui.update { it.copy(items = trending, isLoading = false) }
            } catch (_: Exception) { _ui.update { it.copy(isLoading = false) } }
        }
    }
}

@Composable
fun ShortsScreen(nav: NavController, vm: ShortsViewModel = hiltViewModel()) {
    val ui  by vm.ui.collectAsState()
    val ctx = LocalContext.current

    if (ui.isLoading) {
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) { CinematicSpinner(size = 52.dp) }
        return
    }
    if (ui.items.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
            Text("Nothing here yet", color = White40, fontSize = 16.sp)
        }
        return
    }

    val pagerState = rememberPagerState { ui.items.size }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val media = ui.items[page]
            val pageOffset = (pagerState.currentPage - page + pagerState.currentPageOffsetFraction).coerceIn(-1f, 1f)

            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha  = 1f - 0.3f * kotlin.math.abs(pageOffset)
                    scaleX = 1f - 0.05f * kotlin.math.abs(pageOffset)
                    scaleY = 1f - 0.05f * kotlin.math.abs(pageOffset)
                }
            ) {
                ShortsCard(
                    media   = media,
                    onWatch = {
                        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                            putExtra("tmdbId",    media.tmdbId)
                            putExtra("mediaType", media.mediaType.name)
                            putExtra("season",    0)
                            putExtra("episode",   0)
                            putExtra("title",     media.title)
                            putExtra("posterPath",media.posterPath)
                        })
                    },
                    onDetail = { nav.navigate(com.reelz.ui.Route.Detail.go(media.tmdbId, media.mediaType)) },
                )
            }
        }

        // Scroll hint — vertical dots
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 6.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val visible = minOf(ui.items.size, 5)
            val startIdx = (pagerState.currentPage - 2).coerceAtLeast(0)
            repeat(visible) { i ->
                val idx = startIdx + i
                val selected = idx == pagerState.currentPage
                val height by animateDpAsState(if (selected) 20.dp else 5.dp, spring(0.6f, 400f), label = "dh")
                Box(
                    Modifier.width(3.dp).height(height).clip(RoundedCornerShape(2.dp))
                        .background(if (selected) Brand else White20)
                )
            }
        }
    }
}

@Composable
fun ShortsCard(media: Media, onWatch: () -> Unit, onDetail: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = BuildConfig.TMDB_IMG_ORIGINAL + (media.backdropPath ?: media.posterPath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Cinema vignette layers
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0x55000000), Color.Transparent, Color.Transparent, Color(0xCC000000)))
        ))
        Box(Modifier.fillMaxSize().background(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent, Color(0x33000000)))
        ))

        // Right-side action buttons
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ShortsAction(IconHeart, "Like")
            ShortsAction(IconShare, "Share")
            ShortsAction(IconBookmark, "Save")
            ShortsAction(IconInfoCircle, "Info", onClick = onDetail)
        }

        // Bottom info
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 80.dp, bottom = 100.dp),
        ) {
            // Poster + title row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(54.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, GlassBorderMd, RoundedCornerShape(10.dp))
                        .background(BgRaised)
                ) {
                    AsyncImage(
                        model = BuildConfig.TMDB_IMG_W342 + media.posterPath,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column {
                    Text(
                        media.title, color = White, fontWeight = FontWeight.Bold,
                        fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(Brand))
                        Text(
                            if (media.mediaType == MediaType.TV) "TV Series" else "Movie",
                            color = Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        )
                        if (media.voteAverage > 0) {
                            Text("·", color = White40)
                            Text("${"%.1f".format(media.voteAverage)} ★", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                media.overview,
                color = White.copy(.7f),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(16.dp))
            BrandButton(
                text    = "Watch Now",
                onClick = onWatch,
                icon    = { Icon(IconPlay, null, tint = Color(0xFF1A0F00), modifier = Modifier.size(16.dp)) },
                small   = true,
            )
        }
    }
}

@Composable
fun ShortsAction(icon: ImageVector, label: String, onClick: () -> Unit = {}) {
    var pressed by remember { mutableStateOf(false) }
    val scale   by animateFloatAsState(if (pressed) 1.25f else 1f, spring(0.35f, 600f), label = "sc")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(GlassMd)
                .border(1.dp, GlassBorderMd, CircleShape)
                .clickable {
                    pressed = true
                    onClick()
                },
            Alignment.Center,
        ) { Icon(icon, null, tint = White, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = White.copy(.6f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }

    // Reset pressed
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(300)
            pressed = false
        }
    }
}
