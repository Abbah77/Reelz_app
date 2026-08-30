package com.axio.reelz.ads

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// GuestInterstitialController — psychological + mathematical interstitial timing.
//
// Rules (per spec):
//   • Appears on: Browse, Explore, Detail, Files, etc.
//   • Never appears on: Player, ShortsPlayer
//   • Not aggressive — psychological gaps, not every screen
//   • Frequency is governed entirely by AdEngine.shouldShowInterstitial()
//     which checks: content opens, play taps, time gap, session cap
//
// Usage: call GuestInterstitialEffect() inside any eligible screen composable.
// The composable handles the timing check and triggers the SDK show() call.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Drop this into any eligible screen's composable body.
 *
 * Checks frequency gates on a short delay (avoids showing mid-transition),
 * then fires the interstitial if conditions are met.
 *
 * Screens that should include this:
 *   - BrowseScreen ✓
 *   - ExploreScreen ✓
 *   - DetailScreen ✓
 *   - FilesScreen ✓
 *   - SearchScreen ✓
 *
 * Screens that must NOT include this:
 *   - PlayerActivity (video is playing)
 *   - ShortsScreen (shorts is playing)
 */
@Composable
fun GuestInterstitialEffect(adEngine: AdEngine) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        // Small delay: let the screen settle before firing — avoids jarring interruption
        // mid-navigation-transition. 800ms is imperceptible to the user but prevents
        // the ad from overlapping the enter animation.
        delay(800)

        if (adEngine.shouldShowInterstitial()) {
            val activity = context as? Activity ?: return@LaunchedEffect
            adEngine.showInterstitial(
                activity    = activity,
                onDismissed = { /* User dismissed — continue normally */ },
                onFailed    = { /* SDK error — silently skip, never block user */ },
            )
        }
    }
}
