package com.axio.reelz.ads

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.axio.reelz.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// RewardedDownloadAd — appears immediately when user taps a download quality.
//
// Flow:
//   1. User taps quality → download sheet immediately adds to queue
//   2. This composable fires — shows a polished "Watch a short video" prompt
//   3. If user watches → rewarded, download continues
//   4. If SDK error or skip → download still starts (non-blocking UX)
//
// Design: feels like a premium "unlock" experience, not a chore.
// The dialog uses the same glass-surface system as other bottom sheets.
// ─────────────────────────────────────────────────────────────────────────────

enum class RewardedAdDialogState { PROMPT, LOADING, DONE }

@Composable
fun RewardedDownloadAdDialog(
    adEngine: AdEngine,
    onRewarded: () -> Unit,
    onSkipped: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var dialogState by remember { mutableStateOf(RewardedAdDialogState.PROMPT) }

    Dialog(
        onDismissRequest = {
            if (dialogState != RewardedAdDialogState.LOADING) {
                onSkipped(); onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress    = dialogState != RewardedAdDialogState.LOADING,
            dismissOnClickOutside = dialogState != RewardedAdDialogState.LOADING,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BgSurface)
                .border(0.5.dp, GlassBorderMd, RoundedCornerShape(20.dp))
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (dialogState) {
                RewardedAdDialogState.PROMPT -> RewardedPrompt(
                    onWatch = {
                        dialogState = RewardedAdDialogState.LOADING
                        val activity = context as? Activity
                        if (activity == null) { onSkipped(); onDismiss(); return@RewardedPrompt }
                        adEngine.showRewarded(
                            activity   = activity,
                            onRewarded = { dialogState = RewardedAdDialogState.DONE; onRewarded() },
                            onSkipped  = { onSkipped(); onDismiss() },
                        )
                    },
                    onSkip  = { onSkipped(); onDismiss() },
                )
                RewardedAdDialogState.LOADING -> RewardedLoading()
                RewardedAdDialogState.DONE    -> {
                    LaunchedEffect(Unit) { kotlinx.coroutines.delay(800); onDismiss() }
                    RewardedDone()
                }
            }
        }
    }
}

@Composable
private fun RewardedPrompt(onWatch: () -> Unit, onSkip: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Icon — download + play visual
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(BrandDeep, Brand.copy(0.6f))))
                .border(1.dp, BlueBorder, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Download icon represented as text symbol — lightweight
            Text("⬇", fontSize = 28.sp)
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text       = "Unlock Download",
            color      = White,
            fontSize   = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text      = "Watch a short video to unlock\nyour download for free",
            color     = White60,
            fontSize  = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )

        Spacer(Modifier.height(24.dp))

        // Watch button — primary CTA
        Button(
            onClick  = onWatch,
            colors   = ButtonDefaults.buttonColors(containerColor = Brand),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Text(
                text       = "Watch & Download",
                fontSize   = 15.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Skip link — subtle, always available
        Text(
            text      = "Skip and download anyway",
            color     = White40,
            fontSize  = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier  = Modifier.clickable(onClick = onSkip),
        )
    }
}

@Composable
private fun RewardedLoading() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(vertical = 12.dp),
    ) {
        CircularProgressIndicator(color = Brand, strokeWidth = 2.5.dp, modifier = Modifier.size(36.dp))
        Text("Loading ad…", color = White60, fontSize = 13.sp)
    }
}

@Composable
private fun RewardedDone() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 12.dp),
    ) {
        Text("✓", fontSize = 36.sp, color = Success)
        Text(
            text      = "Download unlocked!",
            color     = White,
            fontSize  = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
