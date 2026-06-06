package com.reelz.ui.screens.watchlist

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.reelz.BuildConfig
import com.reelz.data.model.*
import com.reelz.data.repository.MediaRepository
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────
@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val repo: MediaRepository,
) : ViewModel() {
    val items: StateFlow<List<WatchlistItem>> =
        repo.getWatchlist().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(tmdbId: Int) { viewModelScope.launch { repo.removeFromWatchlist(tmdbId) } }
}

// ── Screen ────────────────────────────────────────────────────────────────────
@Composable
fun WatchlistScreen(
    onMediaClick: (Int, MediaType) -> Unit,
    vm: WatchlistViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsState()

    Column(Modifier.fillMaxSize().background(Surface900)) {
        Spacer(Modifier.height(52.dp))
        Text(
            "My Watchlist",
            color = White,
            fontWeight = FontWeight.Black,
            fontSize = 24.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.BookmarkBorder, null, tint = White20, modifier = Modifier.size(64.dp))
                    Text("Your watchlist is empty", color = White40, fontSize = 14.sp)
                    Text("Tap the bookmark icon on any title to save it", color = White20, fontSize = 12.sp)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items.size) { i ->
                    val item = items[i]
                    val type = if (item.mediaType == "TV") MediaType.TV else MediaType.MOVIE
                    WatchlistCard(item, onClick = { onMediaClick(item.tmdbId, type) }, onRemove = { vm.remove(item.tmdbId) })
                }
            }
        }
    }
}

@Composable
private fun WatchlistCard(item: WatchlistItem, onClick: () -> Unit, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(0.67f)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface700)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = BuildConfig.TMDB_IMG_W500 + item.posterPath,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Remove button
        IconButton(
            onClick  = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(32.dp)
                .background(Black.copy(.6f), RoundedCornerShape(bottomStart = 8.dp)),
        ) {
            Icon(Icons.Default.Close, "Remove", tint = White60, modifier = Modifier.size(14.dp))
        }
        // Type badge
        Box(
            modifier = Modifier.align(Alignment.BottomStart)
                .background(Black.copy(.7f))
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(item.title, color = White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        }
    }
}
