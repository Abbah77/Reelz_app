package com.axio.reelz.ads

import android.app.Activity
import android.util.Log
import android.widget.FrameLayout
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.unity3d.services.banners.BannerErrorInfo
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize
import com.axio.reelz.ui.theme.*

private const val TAG = "ReelzBannerAd"

private enum class BannerAdState { LOADING, LOADED, FAILED }

// ─────────────────────────────────────────────────────────────────────────────
// ReelzBannerAd — Unity Ads BannerView wrapped in a Compose AndroidView.
//
// BannerView is an Android View that self-loads; we just create it, attach
// a listener, call load(), and let it render inside the AndroidView slot.
// Silent failure: FAILED = zero height, no dead whitespace.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ReelzBannerAd(
    adUnitId: String,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
) {
    var state by remember(adUnitId) { mutableStateOf(BannerAdState.LOADING) }
    val bannerRef = remember(adUnitId) { mutableStateOf<BannerView?>(null) }
    // BannerView requires an Activity — get it from the composition context
    val activity = LocalContext.current as? Activity

    DisposableEffect(adUnitId) {
        onDispose {
            bannerRef.value?.destroy()
            bannerRef.value = null
        }
    }

    // No Activity context available — nothing to show
    if (activity == null) return

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
            if (state == BannerAdState.LOADING) {
                Text(
                    text       = "Ad",
                    color      = White40,
                    fontSize   = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory  = { context ->
                    val container = FrameLayout(context)
                    // BannerView requires an Activity — we confirmed above it's non-null
                    val banner = BannerView(
                        activity,
                        adUnitId,
                        UnityBannerSize(320, 50),
                    )
                    banner.listener = object : BannerView.IListener {
                        override fun onBannerLoaded(bannerAdView: BannerView) {
                            Log.d(TAG, "Banner loaded: $adUnitId")
                            state = BannerAdState.LOADED
                        }
                        override fun onBannerFailedToLoad(
                            bannerAdView: BannerView,
                            errorInfo: BannerErrorInfo,
                        ) {
                            Log.w(TAG, "Banner failed: $adUnitId — ${errorInfo.errorMessage}")
                            state = BannerAdState.FAILED
                        }
                        override fun onBannerShown(bannerAdView: BannerView)           {}
                        override fun onBannerClick(bannerAdView: BannerView)           {}
                        override fun onBannerLeftApplication(bannerAdView: BannerView) {}
                    }
                    banner.load()
                    bannerRef.value = banner
                    container.addView(banner)
                    container
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SearchResultsBanner — horizontal strip at end of search results.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SearchResultsBanner(adEngine: AdEngine, modifier: Modifier = Modifier) {
    val unitId = adEngine.bannerAdUnitIdOrNull() ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
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
// FilesScreenBanner — shown in the files screen when no active downloads.
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
        ReelzBannerAd(
            adUnitId = unitId,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            height   = 50.dp,
        )
    }
}
