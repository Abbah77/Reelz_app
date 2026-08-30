package com.axio.reelz.ads

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.axio.reelz.ui.theme.*
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// AppOpenAdOverlay — full-screen overlay on app resume after 15+ min background.
//
// Design:
//   • Fades in smoothly over the app (not a jarring takeover)
//   • The MaxAppOpenAd renders through the SDK's own Activity overlay
//   • This composable is the fallback when SDK isn't rendering directly
//   • Close button appears after 3 seconds — user is never trapped
//   • "REELZ" branding stays visible — feels like part of the app
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AppOpenAdOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClose by remember { mutableStateOf(false) }
    var alpha     by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        // Fade in over 400ms
        val start = System.currentTimeMillis()
        while (alpha < 1f) {
            delay(16)
            val elapsed = (System.currentTimeMillis() - start).toFloat()
            alpha = (elapsed / 400f).coerceIn(0f, 1f)
        }
        // Close button after 3 seconds
        delay(3_000)
        showClose = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(Bg),
    ) {
        // Full-screen ad content area — MaxAppOpenAd SDK renders here via Activity
        // This composable provides the branded wrapper/chrome

        // Top branding strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text          = "REELZ",
                color         = White60,
                fontSize      = 16.sp,
                fontWeight    = FontWeight.Black,
                letterSpacing = 3.sp,
            )
            // "Ad" label
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(GlassMd)
                    .border(0.5.dp, GlassBorder, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text          = "Ad",
                    color         = White60,
                    fontSize      = 10.sp,
                    fontWeight    = FontWeight.Medium,
                    letterSpacing = 0.3.sp,
                )
            }
        }

        // Close button — bottom right, appears after delay
        AnimatedVisibility(
            visible  = showClose,
            enter    = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 32.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassHeavy)
                    .border(0.8.dp, GlassBorderMd, RoundedCornerShape(12.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = "Continue to App →",
                    color      = White,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
