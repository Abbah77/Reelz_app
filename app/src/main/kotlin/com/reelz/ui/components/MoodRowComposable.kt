package com.reelz.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.reelz.BuildConfig
import com.reelz.data.model.Media
import com.reelz.ui.theme.*

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  MoodRowComposable
 *
 *  The "Vibe Tonight" row — shown at the TOP of the feed when the engine
 *  detects what the user is likely in the mood for right now.
 *
 *  This is the most personal row in the feed. It changes based on:
 *  - Time of day (night = horror, morning = comedy)
 *  - User's historical time patterns (what they actually watch at this hour)
 *
 *  Add this to BrowseScreen.kt's LazyColumn, before the main section rows.
 *
 *  In BrowseScreen's LazyColumn items block:
 *
 *      items(feedRows, key = { ... }) { row ->
 *          when (row) {
 *              is FeedRow.MoodRow -> MoodRow(row.mood, row.items, onClick = { ... })
 *              is FeedRow.Section -> ...
 *              ...
 *          }
 *      }
 * ════════════════════════════════════════════════════════════════════════════
 */

@Composable
fun MoodRow(
    mood: String,
    items: List<Media>,
    onItemClick: (Media) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // Header with pulsing glow — this row is special
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Pulsing accent bar
                Box(
                    Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.verticalGradient(listOf(Brand2, Brand)))
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Vibe Tonight",
                        color = White40,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        mood,
                        color = White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.3).sp,
                    )
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brand.copy(0.12f))
                    .border(1.dp, Brand.copy(0.3f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("For You", color = Brand, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items.take(15)) { media ->
                MoodCard(media, onClick = { onItemClick(media) })
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MoodCard(media: Media, onClick: () -> Unit) {
    Column(
        Modifier
            .width(130.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(185.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, GlassBorderMd, RoundedCornerShape(14.dp))
                .background(BgRaised)
        ) {
            AsyncImage(
                model = BuildConfig.TMDB_IMG_W342 + media.posterPath,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Bottom gradient
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.6f to Color.Transparent,
                        1f   to Color(0xCC000000),
                    )
                )
            )
            // Rating badge
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    "⭐ ${"%.1f".format(media.voteAverage)}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            media.title,
            color = White80,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
        )
        Text(
            media.releaseDate?.take(4) ?: "",
            color = White40,
            fontSize = 10.sp,
        )
    }
}
