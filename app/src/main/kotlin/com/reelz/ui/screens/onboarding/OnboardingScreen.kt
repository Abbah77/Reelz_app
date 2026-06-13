package com.reelz.ui.screens.onboarding

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelz.brain.TasteEngine
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cold start onboarding — shown ONCE when no taste profile exists.
 * Takes ~5 seconds. User picks 2–5 genres they like.
 * Seeds the profile so recommendations start strong, not cold.
 *
 * Psychology behind the design:
 * - Emoji + short label > technical genre names (anime not "Animation")
 * - "Pick what you enjoy" not "Tell us your preferences" (active, not clinical)
 * - Minimum 2 picks enforced — prevents empty profiles
 * - Large tap targets — no frustration
 */

data class GenrePick(
    val key: String,
    val label: String,
    val emoji: String,
    val color: Color,
)

private val GENRE_OPTIONS = listOf(
    GenrePick("anime",       "Anime",       "⚔️",  Color(0xFF7C4DFF)),
    GenrePick("action",      "Action",      "💥",  Color(0xFFFF5722)),
    GenrePick("horror",      "Horror",      "😱",  Color(0xFF212121)),
    GenrePick("lang_hindi",  "Bollywood",   "🎭",  Color(0xFFE91E63)),
    GenrePick("lang_korean", "K-Drama",     "🌸",  Color(0xFF9C27B0)),
    GenrePick("comedy",      "Comedy",      "😂",  Color(0xFFFF9800)),
    GenrePick("scifi",       "Sci-Fi",      "🚀",  Color(0xFF00BCD4)),
    GenrePick("romance",     "Romance",     "💕",  Color(0xFFE91E63)),
    GenrePick("thriller",    "Thriller",    "😬",  Color(0xFF607D8B)),
    GenrePick("crime",       "Crime",       "🕵️",  Color(0xFF795548)),
    GenrePick("drama",       "Drama",       "🎬",  Color(0xFF3F51B5)),
    GenrePick("documentary", "Docs",        "🌍",  Color(0xFF4CAF50)),
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val tasteEngine: TasteEngine,
) : ViewModel() {
    val selected = mutableStateListOf<String>()

    fun toggle(key: String) {
        if (selected.contains(key)) selected.remove(key)
        else if (selected.size < 6) selected.add(key) // Max 6 picks
    }

    fun confirm(onDone: () -> Unit) {
        if (selected.size < 2) return
        viewModelScope.launch {
            tasteEngine.applyOnboardingPicks(selected.toList())
            onDone()
        }
    }
}

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val canConfirm = vm.selected.size >= 2

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        // Subtle background glow
        Box(
            Modifier
                .size(400.dp)
                .offset(y = (-60).dp)
                .blur(120.dp)
                .background(Brand.copy(0.08f), CircleShape)
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            // Header
            Text(
                "What do you enjoy?",
                color = White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick ${if (canConfirm) "more or" else "at least 2"} genres",
                color = White40,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            // Genre grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(GENRE_OPTIONS) { pick ->
                    val isSelected = vm.selected.contains(pick.key)
                    GenrePickCard(pick, isSelected) { vm.toggle(pick.key) }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Confirm button
            AnimatedVisibility(
                visible = canConfirm,
                enter = fadeIn() + slideInVertically { it / 2 },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.horizontalGradient(listOf(Brand, Brand2)))
                        .clickable { vm.confirm(onDone) }
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Let's go →",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Skip (guest mode still works)
            TextButton(onClick = {
                // Mark as onboarded with no picks — pure cold start
                vm.confirm(onDone)
            }) {
                Text("Skip for now", color = White40, fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GenrePickCard(pick: GenrePick, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        if (selected) 1.05f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "card_scale",
    )
    val bgAlpha by animateFloatAsState(
        if (selected) 0.2f else 0.06f,
        tween(150),
        label = "bg_alpha",
    )

    Box(
        Modifier
            .scale(scale)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(pick.color.copy(bgAlpha))
            .border(
                1.5.dp,
                if (selected) pick.color.copy(0.8f) else GlassBorder,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(pick.emoji, fontSize = 32.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                pick.label,
                color = if (selected) Color.White else White60,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }

        // Checkmark overlay
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(pick.color),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}
