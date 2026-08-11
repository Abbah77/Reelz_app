package com.axio.reelz.ads

import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.applovin.mediation.MaxAd
import com.applovin.mediation.MaxAdViewAdListener
import com.applovin.mediation.MaxError
import com.applovin.mediation.ads.MaxAdView
import com.axio.reelz.ui.theme.Bg
import com.axio.reelz.ui.theme.BgCard
import com.axio.reelz.ui.theme.BgRaised
import com.axio.reelz.ui.theme.BgSurface
import com.axio.reelz.ui.theme.GlassBorderMd
import com.axio.reelz.ui.theme.Primary
import com.axio.reelz.ui.theme.White40
import com.axio.reelz.ui.theme.White60

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
// ─────────────────────────────────────────────────────────────────────────────
// Full-width native ad card — injected between BrowseScreen feed rows
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NativeAdCard(adEngine: AdEngine) {
    var adState by remember { mutableStateOf<NativeAdState>(NativeAdState.Loading) }
    var showBrowserSheet by remember { mutableStateOf(false) }
    var browserUrl by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        adEngine.loadNativeAd(
            onLoaded = { ad -> adState = ad },
            onFailed = { adState = NativeAdState.Failed },
        )
    }

    when (val state = adState) {
        is NativeAdState.Loading -> NativeAdSkeleton()
        is NativeAdState.Failed  -> Spacer(Modifier.height(0.dp)) // collapse silently
        is NativeAdState.Loaded  -> {
            NativeAdContent(
                ad = state,
                onCtaClick = {
                    routeAdUrl(context, state.clickUrl) { url ->
                        browserUrl = url
                        showBrowserSheet = true
                    }
                },
            )
        }
    }

    if (showBrowserSheet) {
        ReelzBrowserSheet(url = browserUrl, onDismiss = { showBrowserSheet = false })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Loaded card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NativeAdContent(
    ad: NativeAdState.Loaded,
    onCtaClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface)
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clickable(onClick = onCtaClick),
    ) {
        // ── "Sponsored" label ─────────────────────────────────────────────────
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text     = "Sponsored",
                color    = White60,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
            )
        }

        // ── Media image (16:9) ────────────────────────────────────────────────
        AsyncImage(
            model             = ad.imageUrl,
            contentDescription = "Ad",
            contentScale      = ContentScale.Crop,
            modifier          = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )

        // ── Advertiser info row ───────────────────────────────────────────────
        Row(
            modifier  = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model              = ad.iconUrl,
                contentDescription = "App icon",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Bg),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = ad.headline,
                    color      = Color.White,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (ad.body.isNotBlank()) {
                    Text(
                        text     = ad.body,
                        color    = White60,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Button(
                onClick = onCtaClick,
                colors  = ButtonDefaults.buttonColors(containerColor = Primary),
                shape   = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text       = ad.callToAction,
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shimmer skeleton while native ad loads
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NativeAdSkeleton() {
    val shimmerColors = listOf(
        Color.White.copy(alpha = 0.04f),
        Color.White.copy(alpha = 0.12f),
        Color.White.copy(alpha = 0.04f),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue   = 0f,
        targetValue    = 1000f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerX",
    )
    val brush = Brush.linearGradient(
        colors     = shimmerColors,
        start      = Offset(translateAnim - 200f, 0f),
        end        = Offset(translateAnim, 0f),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(brush)
        )
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(brush))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(brush))
                Box(Modifier.fillMaxWidth(0.9f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            }
        }
    }
}
// ─────────────────────────────────────────────────────────────────────────────
// Full-screen portrait ad page — replaces a reel page every 5 videos
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ShortsNativeAdPage(adEngine: AdEngine) {
    var adState by remember { mutableStateOf<NativeAdState>(NativeAdState.Loading) }
    var showBrowserSheet by remember { mutableStateOf(false) }
    var browserUrl by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        adEngine.loadNativeAd(
            onLoaded = { ad -> adState = ad },
            onFailed = { adState = NativeAdState.Failed },
        )
    }

    when (val state = adState) {
        is NativeAdState.Loading -> ShortsAdLoadingPage()
        is NativeAdState.Failed  -> ShortsAdFallbackPage()
        is NativeAdState.Loaded  -> {
            ShortsAdLoadedPage(
                ad = state,
                onCtaClick = {
                    routeAdUrl(context, state.clickUrl) { url ->
                        browserUrl = url
                        showBrowserSheet = true
                    }
                },
            )
        }
    }

    if (showBrowserSheet) {
        ReelzBrowserSheet(url = browserUrl, onDismiss = { showBrowserSheet = false })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Loaded page
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShortsAdLoadedPage(
    ad: NativeAdState.Loaded,
    onCtaClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        // Background image
        AsyncImage(
            model              = ad.imageUrl,
            contentDescription = "Ad",
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )

        // Gradient overlay from bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        startY = 300f,
                    )
                )
        )

        // "AD" badge top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(text = "AD", color = White60, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        // Bottom content
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Advertiser row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AsyncImage(
                    model              = ad.iconUrl,
                    contentDescription = "App icon",
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Bg),
                )
                Text(
                    text       = ad.advertiserName,
                    color      = Color.White,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Headline
            Text(
                text       = ad.headline,
                color      = Color.White,
                fontSize   = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
            )

            // Body
            if (ad.body.isNotBlank()) {
                Text(
                    text     = ad.body,
                    color    = White60,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // CTA button
            Button(
                onClick = onCtaClick,
                colors  = ButtonDefaults.buttonColors(containerColor = Primary),
                shape   = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text       = ad.callToAction,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White,
                    modifier   = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Loading / fallback pages
//
// UI/UX: these previously collapsed to a bare black Box — indistinguishable
// from a frozen feed or a video that failed to decode. A swipeable feed needs
// every page, including ad slots, to look intentional. Loading now mirrors the
// same shimmer technique used by NativeAdCard's card skeleton (just shaped for
// full-screen portrait), and the fallback explains itself instead of going dark.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun shortsShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shorts_ad_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1200f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shorts_ad_shimmer_x",
    )
    return Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.04f),
            Color.White.copy(alpha = 0.10f),
            Color.White.copy(alpha = 0.04f),
        ),
        start = Offset(translateAnim - 240f, 0f),
        end   = Offset(translateAnim, 0f),
    )
}

@Composable
private fun ShortsAdLoadingPage() {
    val brush = shortsShimmerBrush()
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(20.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(brush))
                Box(Modifier.fillMaxWidth(0.4f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            }
            Box(Modifier.fillMaxWidth(0.8f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            Box(Modifier.fillMaxWidth(0.55f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            Spacer(Modifier.height(2.dp))
            Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(brush))
        }
        // Small top-left "AD" placeholder so the shimmering page still reads as an ad slot, not stalled content.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(text = "AD", color = White40, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ShortsAdFallbackPage() {
    // An ad slot that failed to fill. Rather than going dark and looking broken,
    // say so plainly — this is a normal, expected outcome of ad mediation, not
    // an error in the app — and let the person keep swiping immediately.
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(BgRaised),
                contentAlignment = Alignment.Center,
            ) {
                Text("—", color = White40, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "No ad to show right now",
                color      = White60,
                fontSize   = 13.sp,
                textAlign  = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Swipe up to keep watching",
                color      = White40,
                fontSize   = 12.sp,
                textAlign  = TextAlign.Center,
            )
        }
    }
}
