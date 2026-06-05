package com.streamapp.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.streamapp.data.model.MediaItem
import com.streamapp.ui.theme.*

@Composable
fun MediaCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showRating: Boolean = true,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "scale")

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .aspectRatio(2f / 3f)
    ) {
        // Poster
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Bottom gradient + info
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Black.copy(alpha = 0.9f)),
                        startY = 0f, endY = Float.POSITIVE_INFINITY
                    )
                )
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showRating && item.voteAverage > 0) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, tint = Gold, modifier = Modifier.size(10.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "%.1f".format(item.voteAverage),
                            style = MaterialTheme.typography.labelSmall,
                            color = White60,
                        )
                        if (item.year.isNotEmpty()) {
                            Text(" · ${item.year}", style = MaterialTheme.typography.labelSmall, color = White40)
                        }
                    }
                }
            }
        }

        // TV badge
        if (item.mediaType == "tv") {
            Surface(
                color = Primary.copy(alpha = 0.85f),
                shape = RoundedCornerShape(bottomStart = 0.dp, topStart = 4.dp, topEnd = 4.dp, bottomEnd = 4.dp),
                modifier = Modifier.padding(6.dp).align(Alignment.TopStart)
            ) {
                Text("TV", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), color = White)
            }
        }
    }
}

@Composable
fun MediaCardWide(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .width(180.dp)
            .height(110.dp)
    ) {
        AsyncImage(
            model = item.backdropUrl ?: item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Black.copy(alpha = 0.85f))
                    )
                )
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
        ) {
            Text(item.title, style = MaterialTheme.typography.labelMedium, color = White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.voteAverage > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, tint = Gold, modifier = Modifier.size(9.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("%.1f".format(item.voteAverage),
                        style = MaterialTheme.typography.labelSmall, color = White60)
                }
            }
        }
    }
}
