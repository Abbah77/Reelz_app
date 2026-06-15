package com.reelz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.reelz.AppContainer
import com.reelz.data.MediaItem
import com.reelz.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── ViewModel ─────────────────────────────────────────────────────────────────

class BrowseViewModel : ViewModel() {

    private val _movies  = MutableStateFlow<List<MediaItem>>(emptyList())
    private val _tvShows = MutableStateFlow<List<MediaItem>>(emptyList())
    private val _loading = MutableStateFlow(true)
    private val _error   = MutableStateFlow<String?>(null)
    private val _tab     = MutableStateFlow(0)   // 0 = Movies, 1 = TV

    val movies  = _movies.asStateFlow()
    val tvShows = _tvShows.asStateFlow()
    val loading = _loading.asStateFlow()
    val error   = _error.asStateFlow()
    val tab     = _tab.asStateFlow()

    init { load() }

    fun setTab(t: Int) { _tab.value = t }

    fun retry() { load() }

    private fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value   = null
            try {
                val m = runCatching { AppContainer.api.getMovies() }.getOrElse { emptyList() }
                val t = runCatching { AppContainer.api.getTvShows() }.getOrElse { emptyList() }
                _movies.value  = m
                _tvShows.value = t
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load"
            } finally {
                _loading.value = false
            }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun BrowseScreen(
    vm: BrowseViewModel,
    onItemClick: (MediaItem) -> Unit,
) {
    val movies  by vm.movies.collectAsState()
    val tvShows by vm.tvShows.collectAsState()
    val loading by vm.loading.collectAsState()
    val error   by vm.error.collectAsState()
    val tab     by vm.tab.collectAsState()

    val items = if (tab == 0) movies else tvShows

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ReelzLite", style = MaterialTheme.typography.headlineMedium, color = Brand)
        }

        // Tabs
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BgCard),
        ) {
            listOf("Movies" to Icons.Default.Movie, "TV Shows" to Icons.Default.Tv)
                .forEachIndexed { i, (label, icon) ->
                    val selected = tab == i
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Brand else Color.Transparent)
                            .clickable { vm.setTab(i) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = if (selected) Color.White else White60, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.titleSmall,
                            color = if (selected) Color.White else White60)
                    }
                }
        }

        Spacer(Modifier.height(12.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Could not load content", color = White60)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.retry() }) { Text("Retry") }
                }
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No content available", color = White60)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { item ->
                    MediaCard(item = item, onClick = { onItemClick(item) })
                }
            }
        }
    }
}

@Composable
private fun MediaCard(item: MediaItem, onClick: () -> Unit) {
    val thumb = item.thumbnail.ifBlank { item.poster }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(BgCard)
        ) {
            if (thumb.isNotBlank()) {
                AsyncImage(
                    model = thumb,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (item.type == "tv") Icons.Default.Tv else Icons.Default.Movie,
                        null, tint = White40, modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            color = White80,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
