package com.reelz.ui.screens.shorts

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import com.reelz.ui.components.BrandButton
import com.reelz.ui.components.FullScreenLoader
import com.reelz.ui.screens.player.PlayerActivity
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
            } catch (_: Exception) {
                _ui.update { it.copy(isLoading = false) }
            }
        }
    }
}

@Composable
fun ShortsScreen(nav: NavController, vm: ShortsViewModel = hiltViewModel()) {
    val ui  by vm.ui.collectAsState()
    val ctx = LocalContext.current

    if (ui.isLoading) { FullScreenLoader(); return }
    if (ui.items.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Bg), Alignment.Center) {
            Text("Nothing here yet", color = White40)
        }
        return
    }

    val pagerState = rememberPagerState { ui.items.size }

    VerticalPager(
        state    = pagerState,
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) { page ->
        val media = ui.items[page]
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

@Composable
fun ShortsCard(media: Media, onWatch: () -> Unit, onDetail: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // Backdrop as bg
        AsyncImage(
            model = BuildConfig.TMDB_IMG_ORIGINAL + (media.backdropPath ?: media.posterPath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Gradient overlays
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color.Black.copy(.15f), Color.Transparent, Color.Black.copy(.8f)))
        ))

        // Right side actions (like Flutter feed card)
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 14.dp, bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ShortsAction(Icons.Default.Favorite,     "Like")
            ShortsAction(Icons.Default.Share,        "Share")
            ShortsAction(Icons.Default.BookmarkBorder,"Save")
            ShortsAction(Icons.Default.Info,         "Info", onClick = onDetail)
        }

        // Bottom content
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 80.dp, bottom = 90.dp),
        ) {
            // Poster + title
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(BgRaised)
                ) {
                    AsyncImage(BuildConfig.TMDB_IMG_W342 + media.posterPath, null, ContentScale.Crop, Modifier.fillMaxSize())
                }
                Column {
                    Text(media.title, color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (media.mediaType == MediaType.TV) "TV Series" else "Movie",
                        color = Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(media.overview, color = White60, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Spacer(Modifier.height(14.dp))
            BrandButton(
                text    = "Watch Now",
                onClick = onWatch,
                icon    = { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                small   = true,
            )
        }
    }
}

@Composable
fun ShortsAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(GlassMd)
                .border(1.dp, GlassBorderMd, CircleShape).clickable(onClick = onClick),
            Alignment.Center,
        ) { Icon(icon, null, tint = White, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = White60, fontSize = 10.sp)
    }
}
