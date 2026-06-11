package com.reelz.ads

import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.applovin.mediation.MaxAd
import com.applovin.mediation.MaxAdViewAdListener
import com.applovin.mediation.MaxError
import com.applovin.mediation.ads.MaxAdView

private const val TAG = "DetailBannerAd"

/**
 * Standard 320×50 banner pinned to the bottom of DetailScreen.
 *
 * The banner is created in [AndroidView] factory and destroyed via [DisposableEffect]
 * when the composable leaves the tree — prevents memory leaks.
 */
@Composable
fun DetailBannerAd(
    adUnitId: String,
    modifier: Modifier = Modifier,
) {
    var adView by remember { mutableStateOf<MaxAdView?>(null) }

    DisposableEffect(adUnitId) {
        onDispose {
            adView?.destroy()
            adView = null
            Log.d(TAG, "Banner destroyed")
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        factory  = { context ->
            MaxAdView(adUnitId, context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setListener(object : MaxAdViewAdListener {
                    override fun onAdLoaded(ad: MaxAd)  { Log.d(TAG, "Banner loaded") }
                    override fun onAdLoadFailed(id: String, error: MaxError) {
                        Log.w(TAG, "Banner failed: ${error.message}")
                    }
                    override fun onAdDisplayed(ad: MaxAd)           {}
                    override fun onAdHidden(ad: MaxAd)               {}
                    override fun onAdClicked(ad: MaxAd)              {}
                    override fun onAdExpanded(ad: MaxAd)             {}
                    override fun onAdCollapsed(ad: MaxAd)            {}
                    override fun onAdDisplayFailed(ad: MaxAd, e: MaxError) {}
                })
                adView = this
                loadAd()
            }
        },
    )
}
