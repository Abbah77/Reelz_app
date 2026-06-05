package com.streamapp.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.streamapp.data.model.MediaItem
import com.streamapp.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun HeroCarousel(
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(pagerState) {
        while (true) {
            delay(5000)
            val next = (pagerState.currentPage + 1) % items.size
            pagerState.animateScrollToPage(next, animationSpec = tween(600))
        }
    }

    Box(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
        ) { page ->
            HeroPage(
                item = items[page],
                onClick = { onItemClick(items[page]) },
            )
        }

        // Page dots
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(items.size) { idx ->
                val selected = pagerState.currentPage == idx
                val width by animateDpAsState(
                    targetValue = if (selected) 20.dp else 5.dp,
                    label = "dot_width",
                )
                Box(
                    modifier = Modifier
                        .height(5.dp)
                        .width(width)
                        .clip(CircleShape)
                        .background(if (selected) Primary else White40),
                )
            }
        }
    }
}

@Composable
private fun HeroPage(item: MediaItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(480.dp)
            .clickable { onClick() },
    ) {
        AsyncImage(
            model = item.backdropUrl ?: item.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Top fade
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(Black.copy(alpha = 0.25f), Color.Transparent),
                    startY = 0f, endY = 200f,
                )
            )
        )
        // Bottom fade
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Surface900),
                    startY = 280f,
                )
            )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            if (item.mediaType == "tv") {
                Surface(
                    color = Primary.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        "SERIES",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = White,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            Text(
                item.title,
                style = MaterialTheme.typography.displaySmall,
                color = White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.voteAverage > 0) {
                    Surface(
                        color = Gold.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "★ ${"%.1f".format(item.voteAverage)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Gold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (item.year.isNotEmpty()) {
                    Text(item.year, style = MaterialTheme.typography.bodySmall, color = White60)
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play Now", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = onClick,
                    border = BorderStroke(1.dp, White40),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = White),
                ) {
                    Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Watchlist", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
