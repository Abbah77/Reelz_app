package com.axio.reelz.ads

import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.applovin.mediation.MaxAd
import com.applovin.mediation.MaxAdViewAdListener
import com.applovin.mediation.MaxError
import com.applovin.mediation.ads.MaxAdView
import com.axio.reelz.ui.theme.*

private const val TAG = "ReelzBannerAd"

private enum class BannerAdState { LOADING, LOADED, FAILED }

// ─────────────────────────────────────────────────────────────────────────────
// ReelzBannerAd — clean adaptive banner that blends with its host screen.
//
// Design principle: banner ads should look like a designed UI element, not
// a foreign object dropped into the screen. This is achieved by:
//   • Container uses the same surface colour as the host screen
//   • Rounded corners match the app's radius system
//   • Subtle border consistent with all glass-surface elements
//   • "Ad" disclosure is minimal and consistent with native ad style
//   • Silent failure: FAILED = zero height, no dead whitespace
//   • Silent loading: LOADING shows a neutral height-matching strip
//
// Placements:
//   • End of search results (horizontal strip)
//   • Files screen when no active downloads (inline card)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ReelzBannerAd(
    adUnitId: String,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
) {
    var state by remember(adUnitId) { mutableStateOf(BannerAdState.LOADING) }
    var adViewRef: MaxAdView? = null

    DisposableEffect(adUnitId) {
        onDispose {
            adViewRef?.destroy()
            adViewRef = null
        }
    }

    AnimatedVisibility(
        visible = state != BannerAdState.FAILED,
        enter   = fadeIn(tween(300)),
        exit    = fadeOut(tween(200)) + shrinkVertically(tween(200)),
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(10.dp))
                // Loading state: subtle surface strip; Loaded: transparent to let ad show through
                .background(
                    when (state) {
                        BannerAdState.LOADING -> BgSurface
                        else                  -> Color.Transparent
                    }
                )
                .border(
                    width = 0.5.dp,
                    color = if (state == BannerAdState.LOADED) GlassBorder else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // "Ad" label during load — disappears once real ad fills the space
            if (state == BannerAdState.LOADING) {
                Text(
                    text      = "Ad",
                    color     = White40,
                    fontSize  = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory  = { context ->
                    MaxAdView(adUnitId, context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        setListener(object : MaxAdViewAdListener {
                            override fun onAdLoaded(ad: MaxAd) {
                                Log.d(TAG, "Banner loaded unit=$adUnitId")
                                state = BannerAdState.LOADED
                            }
                            override fun onAdLoadFailed(id: String, error: MaxError) {
                                Log.w(TAG, "Banner failed unit=$adUnitId: ${error.message}")
                                state = BannerAdState.FAILED
                            }
                            override fun onAdDisplayFailed(ad: MaxAd, e: MaxError) {
                                state = BannerAdState.FAILED
                            }
                            override fun onAdDisplayed(ad: MaxAd) {}
                            override fun onAdHidden(ad: MaxAd)    {}
                            override fun onAdClicked(ad: MaxAd)   {}
                            override fun onAdExpanded(ad: MaxAd)  {}
                            override fun onAdCollapsed(ad: MaxAd) {}
                        })
                        adViewRef = this
                        loadAd()
                    }
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SearchResultsBanner — horizontal strip at the end of search results.
// Blends with the screen bottom, styled to feel like a natural content divider.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SearchResultsBanner(adEngine: AdEngine, modifier: Modifier = Modifier) {
    val unitId = adEngine.bannerAdUnitIdOrNull() ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Separator with label
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f).height(0.5.dp).background(GlassBorder))
            Text("Sponsored", color = White40, fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
            Box(Modifier.weight(1f).height(0.5.dp).background(GlassBorder))
        }
        ReelzBannerAd(adUnitId = unitId, height = 50.dp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FilesScreenBanner — shown in the files/downloads screen when no active jobs.
// Card style matches the empty state card shape.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FilesScreenBanner(adEngine: AdEngine, modifier: Modifier = Modifier) {
    val unitId = adEngine.bannerAdUnitIdOrNull() ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .border(0.5.dp, GlassBorder, RoundedCornerShape(12.dp))
            .padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sponsored", color = White40, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        }
        ReelzBannerAd(adUnitId = unitId, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp), height = 50.dp)
    }
}
