package com.axio.reelz.ads

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import coil.compose.AsyncImage
import com.axio.reelz.ui.theme.Bg
import com.axio.reelz.ui.theme.BgCard
import com.axio.reelz.ui.theme.BgRaised
import com.axio.reelz.ui.theme.Primary
import com.axio.reelz.ui.theme.White40
import com.axio.reelz.ui.theme.White60

// ─────────────────────────────────────────────────────────────────────────────
// Full-screen portrait ad page — replaces a reel page every N videos.
//
// Design intent: feel like a beautifully art-directed content card rather
// than an interruption. The background image fills the screen; the bottom
// panel uses a deep gradient scrim so text is always legible; the CTA is
// prominent but not aggressive. Users who swipe through quickly still see a
// polished, branded moment — not a black box or broken page.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ShortsNativeAdPage(adEngine: AdEngine) {
    // Key on adEngine so a fresh instance (e.g. after process restore) reruns.
    var adState by remember { mutableStateOf<NativeAdState>(NativeAdState.Loading) }
    var showBrowserSheet by remember { mutableStateOf(false) }
    var browserUrl by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(adEngine) {
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
// Loaded page — editorial, cinema-style layout
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
        // Full-bleed background image
        AsyncImage(
            model              = ad.imageUrl,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )

        // Two-layer gradient for depth:
        // • Top scrim — makes AD badge + status bar legible
        // • Bottom scrim — makes text panel legible over any image
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.50f),
                            0.20f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1.00f to Color.Black.copy(alpha = 0.92f),
                        ),
                    )
                )
        )

        // ── "AD" badge — top-right, respectful disclosure ──────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 14.dp, top = 10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text       = "AD",
                color      = Color.White.copy(alpha = 0.80f),
                fontSize   = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }

        // ── Bottom content panel ───────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Advertiser identity row
            Row(
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                AsyncImage(
                    model              = ad.iconUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(BgCard),
                )
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text       = "Sponsored",
                        color      = White40,
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.4.sp,
                    )
                    Text(
                        text       = ad.advertiserName,
                        color      = Color.White,
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Headline — large, high-contrast, the hero text
            Text(
                text       = ad.headline,
                color      = Color.White,
                fontSize   = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 26.sp,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.padding(bottom = 6.dp),
            )

            // Body — supporting copy, softer
            if (ad.body.isNotBlank()) {
                Text(
                    text       = ad.body,
                    color      = White60,
                    fontSize   = 14.sp,
                    lineHeight = 20.sp,
                    maxLines   = 3,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.padding(bottom = 16.dp),
                )
            } else {
                Spacer(Modifier.height(16.dp))
            }

            // CTA — full-width, solid Primary brand colour, clear label
            Button(
                onClick  = onCtaClick,
                colors   = ButtonDefaults.buttonColors(containerColor = Primary),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text(
                    text       = ad.callToAction,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White,
                    letterSpacing = 0.2.sp,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared shimmer brush for loading states
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun shortsShimmerBrush(): Brush {
    val sweepPx = 1200f
    val transition = rememberInfiniteTransition(label = "shorts_ad_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue  = -sweepPx,
        targetValue   = sweepPx * 2f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shorts_ad_shimmer_x",
    )
    return Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.03f),
            Color.White.copy(alpha = 0.09f),
            Color.White.copy(alpha = 0.03f),
        ),
        start = Offset(translateAnim, 0f),
        end   = Offset(translateAnim + sweepPx, 0f),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Loading page — mirrors the loaded layout so no layout shift on load
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShortsAdLoadingPage() {
    val brush = shortsShimmerBrush()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        // Shimmer background fill
        Box(Modifier.fillMaxSize().background(brush))

        // AD badge placeholder — keeps the slot recognisable as an ad during load
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 14.dp, top = 10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.Black.copy(alpha = 0.30f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text("AD", color = White40, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }

        // Bottom skeleton mirrors the loaded panel geometry
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Icon + name row skeleton
            Row(
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(brush))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(60.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(brush))
                    Box(Modifier.width(110.dp).height(13.dp).clip(RoundedCornerShape(3.dp)).background(brush))
                }
            }

            // Headline skeleton
            Box(Modifier.fillMaxWidth(0.82f).height(20.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.60f).height(20.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            Spacer(Modifier.height(6.dp))

            // Body skeleton
            Box(Modifier.fillMaxWidth(0.90f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(0.70f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            Spacer(Modifier.height(16.dp))

            // CTA skeleton
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(brush)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Fallback page — ad slot that failed to fill. Intentionally calm and minimal.
// Shows just enough to explain the empty screen and invite the swipe.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShortsAdFallbackPage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            // Minimal dash icon — looks intentional, not broken
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(BgRaised),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = "—",
                    color      = White40,
                    fontSize   = 22.sp,
                    fontWeight = FontWeight.Light,
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text      = "Nothing to show here",
                color     = White60,
                fontSize  = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Text(
                text      = "Swipe to keep watching",
                color     = White40,
                fontSize  = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
