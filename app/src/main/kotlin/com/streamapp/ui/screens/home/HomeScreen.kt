package com.streamapp.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.streamapp.data.model.MediaItem
import com.streamapp.ui.components.HeroCarousel
import com.streamapp.ui.components.MediaCard
import com.streamapp.ui.components.MediaCardWide
import com.streamapp.ui.theme.*

@Composable
fun HomeScreen(
    onItemClick: (MediaItem) -> Unit,
    onSearchClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Surface900)) {
        when (val s = state) {
            is HomeUiState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Primary
                )
            }
            is HomeUiState.Error -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(s.msg, color = White60, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.load() }) { Text("Retry") }
                }
            }
            is HomeUiState.Success -> {
                AnimatedVisibility(visible = true, enter = fadeIn()) {
                    HomeContent(data = s.data, onItemClick = onItemClick, onSearchClick = onSearchClick)
                }
            }
        }
    }
}

@Composable
private fun HomeContent(
    data: com.streamapp.data.repository.HomeData,
    onItemClick: (MediaItem) -> Unit,
    onSearchClick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 90.dp),
    ) {
        // Top bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Good Evening 👋", style = MaterialTheme.typography.bodySmall, color = White60)
                    Text("What to watch?", style = MaterialTheme.typography.headlineMedium, color = White)
                }
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Filled.Search, null, tint = White, modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Notifications, null, tint = White, modifier = Modifier.size(24.dp))
                }
            }
        }

        // Hero carousel — trending
        item {
            HeroCarousel(
                items = data.trending.take(6),
                onItemClick = onItemClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Category pills
        item {
            CategoryRow()
        }

        // Popular Movies
        item {
            SectionHeader("🔥 Popular Movies") {}
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(data.popularMovies) { item ->
                    MediaCard(item = item, onClick = { onItemClick(item) }, modifier = Modifier.width(130.dp))
                }
            }
        }

        // Popular TV
        item { SectionHeader("📺 Popular Series") {} }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(data.popularTv) { item ->
                    MediaCard(item = item, onClick = { onItemClick(item) }, modifier = Modifier.width(130.dp))
                }
            }
        }

        // Top Rated — wide cards
        item { SectionHeader("⭐ Top Rated") {} }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(data.topRated) { item ->
                    MediaCardWide(item = item, onClick = { onItemClick(item) })
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun CategoryRow() {
    val categories = listOf("All", "Action", "Comedy", "Drama", "Horror", "Sci-Fi", "Romance", "Thriller")
    var selected by remember { mutableStateOf("All") }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(categories) { cat ->
            FilterChip(
                selected = cat == selected,
                onClick = { selected = cat },
                label = { Text(cat, style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Primary,
                    selectedLabelColor = White,
                    containerColor = Surface700,
                    labelColor = White60,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = cat == selected,
                    selectedBorderColor = Color.Transparent,
                    borderColor = Stroke,
                )
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = White)
        TextButton(onClick = onSeeAll) {
            Text("See all", style = MaterialTheme.typography.labelLarge, color = PrimaryLight)
        }
    }
}
