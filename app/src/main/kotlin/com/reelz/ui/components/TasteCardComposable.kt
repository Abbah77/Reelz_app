package com.reelz.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.reelz.brain.TasteCard
import com.reelz.ui.theme.*

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  TasteCardComposable
 *
 *  A "Your Taste DNA" card shown in ProfileScreen.
 *
 *  Psychology: People LOVE discovering things about themselves.
 *  Spotify Wrapped works not because it's data — but because it's
 *  a mirror. This card is that mirror for Reelz.
 *
 *  It shows:
 *  - Top genres with confidence bars
 *  - Preferred languages
 *  - Total content watched
 *  - Current mood / vibe
 *
 *  Add to ProfileScreen.kt where you have the user info section.
 *  Usage:
 *
 *      val tasteCard by tasteEngine.profile.collectAsState()
 *      TasteProfileCard(tasteEngine.getTasteCard())
 * ════════════════════════════════════════════════════════════════════════════
 */

// Genre key → friendly display name + emoji
private fun genreDisplay(key: String): Pair<String, String> = when (key) {
    "action"       -> "Action"       to "💥"
    "anime"        -> "Anime"        to "⚔️"
    "comedy"       -> "Comedy"       to "😂"
    "crime"        -> "Crime"        to "🕵️"
    "documentary"  -> "Docs"         to "🌍"
    "drama"        -> "Drama"        to "🎬"
    "fantasy"      -> "Fantasy"      to "🧙"
    "horror"       -> "Horror"       to "😱"
    "mystery"      -> "Mystery"      to "🔍"
    "romance"      -> "Romance"      to "💕"
    "scifi"        -> "Sci-Fi"       to "🚀"
    "thriller"     -> "Thriller"     to "😬"
    "lang_hindi"   -> "Bollywood"    to "🎭"
    "lang_korean"  -> "K-Drama"      to "🌸"
    "lang_japanese"-> "Japanese"     to "🎌"
    "lang_english" -> "English"      to "🎬"
    "lang_chinese" -> "Chinese"      to "🐉"
    "lang_spanish" -> "Spanish"      to "💃"
    "lang_turkish" -> "Turkish"      to "🌙"
    else           -> key.replace("_", " ").replaceFirstChar { it.uppercase() } to "🎥"
}

@Composable
fun TasteProfileCard(tasteCard: TasteCard) {
    if (!tasteCard.isOnboarded && tasteCard.totalWatched < 3) return // Don't show empty card

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF1A1040), Color(0xFF0D0D1A)))
            )
            .border(
                1.dp,
                Brush.linearGradient(listOf(Brand.copy(0.4f), GlassBorderMd, Brand2.copy(0.3f))),
                RoundedCornerShape(20.dp),
            )
            .padding(20.dp),
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Your Taste DNA",
                    color = White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.3).sp,
                )
                Text(
                    "${tasteCard.totalWatched} interactions tracked",
                    color = White40,
                    fontSize = 11.sp,
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BlueGlass)
                    .border(1.dp, BlueBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("🧬 DNA", color = Brand, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Mood label
        if (tasteCard.dominantMood != null) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brand.copy(0.12f))
                    .border(1.dp, Brand.copy(0.3f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    "Vibe right now: ${tasteCard.dominantMood}",
                    color = Brand,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Top Genres", color = White60, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(10.dp))

        // Genre bars
        val maxScore = tasteCard.topGenres.firstOrNull()?.second?.takeIf { it > 0 } ?: 1f
        tasteCard.topGenres.forEachIndexed { i, (key, score) ->
            val (label, emoji) = genreDisplay(key)
            val pct = (score / maxScore).coerceIn(0f, 1f)

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, color = White80, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${(score).toInt()}",
                            color = Brand.copy(0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Progress bar
                    Box(
                        Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(White.copy(0.06f))
                    ) {
                        val barColor = when (i) {
                            0 -> Brush.horizontalGradient(listOf(Brand, Brand2))
                            1 -> Brush.horizontalGradient(listOf(Brand2, Color(0xFF00D4AA)))
                            2 -> Brush.horizontalGradient(listOf(Color(0xFF00D4AA), Color(0xFF00BCD4)))
                            else -> Brush.horizontalGradient(listOf(White40, White40))
                        }
                        Box(
                            Modifier.fillMaxWidth(pct).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(barColor)
                        )
                    }
                }
            }
        }

        // Language preferences
        if (tasteCard.topLanguages.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Language Vibe", color = White60, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tasteCard.topLanguages.forEach { (key, _) ->
                    val (label, emoji) = genreDisplay(key)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(White.copy(0.06f))
                            .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text("$emoji $label", color = White80, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
