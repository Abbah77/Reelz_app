package com.reelz.ads

import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.reelz.ui.theme.BgCard
import com.reelz.ui.theme.GlassBorderMd

private const val TAG = "DetailBannerAd"

private enum class BannerState { LOADING, LOADED, FAILED }

/**
 * Standard 320×50 banner pinned to the bottom of DetailScreen.
 *
 * The banner is created in [AndroidView] factory and destroyed via [DisposableEffect]
 * when the composable leaves the tree — prevents memory leaks.
 *
 * UI/UX: a banner that is loading or has failed used to reserve a permanently
 * visible empty 50dp strip — easy to mistake for a broken layout. Now it shows
 * a brief, subtle card while loading and collapses to nothing on failure, so the
 * screen never displays "dead air" where an ad almost was.
 */
@Composable
fun DetailBannerAd(
    adUnitId: String,
    modifier: Modifier = Modifier,
) {
    var adView by remember { mutableStateOf<MaxAdView?>(null) }
    var state by remember(adUnitId) { mutableStateOf(BannerState.LOADING) }

    DisposableEffect(adUnitId) {
        onDispose {
            adView?.destroy()
            adView = null
            Log.d(TAG, "Banner destroyed")
        }
    }

    AnimatedVisibility(
        visible = state != BannerState.FAILED,
        enter   = fadeIn(),
        exit    = fadeOut() + shrinkVertically(),
    ) {
        val isLoading = state == BannerState.LOADING
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(if (isLoading) BgCard else Color.Transparent)
                .border(BorderStroke(1.dp, if (isLoading) GlassBorderMd else Color.Transparent)),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(50.dp),
                factory  = { context ->
                    MaxAdView(adUnitId, context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        setListener(object : MaxAdViewAdListener {
                            override fun onAdLoaded(ad: MaxAd)  { Log.d(TAG, "Banner loaded"); state = BannerState.LOADED }
                            override fun onAdLoadFailed(id: String, error: MaxError) {
                                Log.w(TAG, "Banner failed: ${error.message}")
                                state = BannerState.FAILED
                            }
                            override fun onAdDisplayed(ad: MaxAd)           {}
                            override fun onAdHidden(ad: MaxAd)               {}
                            override fun onAdClicked(ad: MaxAd)              {}
                            override fun onAdExpanded(ad: MaxAd)             {}
                            override fun onAdCollapsed(ad: MaxAd)            {}
                            override fun onAdDisplayFailed(ad: MaxAd, e: MaxError) { state = BannerState.FAILED }
                        })
                        adView = this
                        loadAd()
                    }
                },
            )
        }
    }
}
