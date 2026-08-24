package com.axio.reelz.ads

import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.applovin.mediation.MaxAd
import com.applovin.mediation.MaxAdViewAdListener
import com.applovin.mediation.MaxError
import com.applovin.mediation.ads.MaxAdView
import com.axio.reelz.ui.theme.BgCard

private const val TAG = "DetailBannerAd"

private enum class BannerState { LOADING, LOADED, FAILED }

/**
 * Standard 320×50 banner pinned to the bottom of DetailScreen.
 *
 * Lifecycle: MaxAdView is created in AndroidView's factory and destroyed in
 * DisposableEffect.onDispose — prevents the view from leaking after the
 * composable leaves the composition.
 *
 * States:
 * • LOADING — shows a subtle dark placeholder strip (same 50 dp height as
 *   the loaded banner). Prevents layout jump when the ad arrives.
 * • LOADED  — the MaxAdView is visible and the placeholder is gone.
 * • FAILED  — the entire 50 dp strip collapses via AnimatedVisibility so
 *   there is no dead "empty ad space" visible in the UI.
 *
 * Fix vs. previous: adView ref is held in a plain `var` captured via the
 * DisposableEffect closure rather than a Compose `mutableStateOf`, avoiding
 * a race where `factory` writes the ref after `onDispose` already ran.
 */
@Composable
fun DetailBannerAd(
    adUnitId: String,
    modifier: Modifier = Modifier,
) {
    var state by remember(adUnitId) { mutableStateOf(BannerState.LOADING) }

    // Hold the MaxAdView outside Compose state so destroy() is always called
    // once and never triggers an extra recomposition.
    var adViewRef: MaxAdView? = null

    DisposableEffect(adUnitId) {
        onDispose {
            adViewRef?.destroy()
            adViewRef = null
            Log.d(TAG, "Banner destroyed for unit=$adUnitId")
        }
    }

    AnimatedVisibility(
        visible = state != BannerState.FAILED,
        enter   = fadeIn(),
        exit    = fadeOut() + shrinkVertically(),
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(50.dp)
                // Placeholder bg during load — blends with bottom sheet rather than showing white
                .background(if (state == BannerState.LOADING) BgCard else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                factory  = { context ->
                    MaxAdView(adUnitId, context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        setListener(object : MaxAdViewAdListener {
                            override fun onAdLoaded(ad: MaxAd) {
                                Log.d(TAG, "Banner loaded unit=$adUnitId")
                                state = BannerState.LOADED
                            }
                            override fun onAdLoadFailed(id: String, error: MaxError) {
                                Log.w(TAG, "Banner load failed unit=$adUnitId: ${error.message}")
                                state = BannerState.FAILED
                            }
                            override fun onAdDisplayFailed(ad: MaxAd, e: MaxError) {
                                Log.w(TAG, "Banner display failed unit=$adUnitId: ${e.message}")
                                state = BannerState.FAILED
                            }
                            // No-op lifecycle events — MaxAdView manages its own refresh
                            override fun onAdDisplayed(ad: MaxAd)  {}
                            override fun onAdHidden(ad: MaxAd)      {}
                            override fun onAdClicked(ad: MaxAd)     {}
                            override fun onAdExpanded(ad: MaxAd)    {}
                            override fun onAdCollapsed(ad: MaxAd)   {}
                        })
                        adViewRef = this
                        loadAd()
                    }
                },
            )
        }
    }
}
