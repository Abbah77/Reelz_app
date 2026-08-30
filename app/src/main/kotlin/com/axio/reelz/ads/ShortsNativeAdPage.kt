package com.axio.reelz.ads

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.axio.reelz.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// ShortsNativeAdPage — full-screen ad that appears every 10 clips.
//
// UX goal: visually indistinguishable from a real reel until the user sees
// the "Sponsored" label — exactly how TikTok does it. No jarring transition,
// no black screens, no "ad break" banner.
//
// Layout mirrors a real ShortsPage:
//   • Full-bleed background image (same as video thumbnail)
//   • Same right-side action column position (empty — ad has no video actions)
//   • Bottom-left info panel (same position as creator info)
//   • "Sponsored" chip replaces channel name — honest, not aggressive
//   • CTA button appears where the "Watch Now" area would be
//
// Failure handling: Failed state forwards immediately to next page (swipe)
// by rendering nothing — the pager simply sees a page with no content
// and the user naturally swipes to next real content.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ShortsNativeAdPage(adEngine: AdEngine) {
    var adState    by remember { mutableStateOf<NativeAdState>(NativeAdState.Loading) }
    var showBrowser by remember { mutableStateOf(false) }
    var browserUrl  by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(adEngine) {
        adEngine.loadNativeAd(
            onLoaded = { ad -> adState = ad },
            onFailed = { adState = NativeAdState.Failed },
        )
    }

    when (val state = adState) {
        is NativeAdState.Loading -> ShortsAdLoading()
        is NativeAdState.Failed  -> { /* Silent — swipe naturally passes through */ }
        is NativeAdState.Loaded  -> ShortsAdLoaded(
            ad         = state,
            onCtaClick = {
                routeAdUrl(context, state.clickUrl) { url ->
                    browserUrl = url; showBrowser = true
                }
            },
        )
    }

    if (showBrowser) {
        ReelzBrowserSheet(url = browserUrl, onDismiss = { showBrowser = false })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Loaded — feels exactly like a real short clip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShortsAdLoaded(
    ad: NativeAdState.Loaded,
    onCtaClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        // Full-bleed creative — same as real reel background
        if (ad.imageUrl.isNotBlank()) {
            AsyncImage(
                model              = ad.imageUrl,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else {
            // Fallback: brand-tinted gradient when no image
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(BrandDeep, BgCard))
                )
            )
        }

        // Gradient layers — same two-layer system as real reel pages
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Black.copy(alpha = 0.30f),  // top scrim
                        0.22f to Color.Transparent,
                        0.52f to Color.Transparent,
                        1.00f to Color.Black.copy(alpha = 0.88f),  // bottom scrim
                    )
                )
            )
        )

        // ── Right-side column — mirrors real reel action column position ──────
        // Empty for ads (no like/comment actions) but keeps visual spacing identical
        // so the overall page weight feels the same when user swipes in
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .navigationBarsPadding()
                .padding(end = 14.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Advertiser avatar — replaces the creator avatar in the same position
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(BgSurface)
                    .border(1.5.dp, Color.White.copy(0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (ad.iconUrl.isNotBlank()) {
                    AsyncImage(
                        model              = ad.iconUrl,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize().clip(CircleShape),
                    )
                } else {
                    Text("AD", color = White60, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            // "Sponsored" replaces the action icons — clear but not aggressive
            Text(
                text          = "Sponsored",
                color         = White60,
                fontSize       = 10.sp,
                fontWeight    = FontWeight.Medium,
                letterSpacing = 0.3.sp,
            )
        }

        // ── Bottom info panel — mirrors real reel bottom panel position ───────
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 80.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Advertiser name row — same position and weight as creator row
            Row(
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                if (ad.iconUrl.isNotBlank()) {
                    AsyncImage(
                        model              = ad.iconUrl,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgCard),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (ad.advertiserName.isNotBlank()) {
                        Text(
                            text       = ad.advertiserName,
                            color      = Color.White,
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // "Sponsored" sub-label
                    Text(
                        text          = "Sponsored",
                        color         = White60,
                        fontSize       = 11.sp,
                        letterSpacing = 0.2.sp,
                    )
                }
            }

            // Headline — styled as the reel caption / title
            Text(
                text       = ad.headline,
                color      = Color.White,
                fontSize   = 15.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 21.sp,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.padding(bottom = 4.dp),
            )

            // Body — supporting copy, softer (mirrors reel description)
            if (ad.body.isNotBlank()) {
                Text(
                    text       = ad.body,
                    color      = White60,
                    fontSize   = 13.sp,
                    lineHeight = 18.sp,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.padding(bottom = 14.dp),
                )
            } else {
                Spacer(Modifier.height(14.dp))
            }

            // CTA — the only explicit "ad" element, styled as a clean action button
            Button(
                onClick  = onCtaClick,
                colors   = ButtonDefaults.buttonColors(containerColor = Brand),
                shape    = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(44.dp),
            ) {
                Text(
                    text       = ad.callToAction,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White,
                    letterSpacing = 0.1.sp,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Loading — mirrors real reel loading skeleton (no AD indication until loaded)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShortsAdLoading() {
    val sweep = 1200f
    val trans = rememberInfiniteTransition(label = "sh_ad_load")
    val x by trans.animateFloat(
        initialValue  = -sweep,
        targetValue   = sweep * 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label         = "sh_x",
    )
    val brush = Brush.linearGradient(
        colors = listOf(BgCard, BgRaised, BgCard),
        start  = Offset(x, 0f),
        end    = Offset(x + sweep, 0f),
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        // Full background shimmer
        Box(Modifier.fillMaxSize().background(brush))

        // Bottom skeleton mirrors real panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 80.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Avatar + name skeleton
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(BgSurface.copy(0.6f)))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(90.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(BgSurface.copy(0.6f)))
                    Box(Modifier.width(60.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(BgSurface.copy(0.4f)))
                }
            }
            Box(Modifier.fillMaxWidth(0.8f).height(15.dp).clip(RoundedCornerShape(4.dp)).background(BgSurface.copy(0.5f)))
            Box(Modifier.fillMaxWidth(0.6f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(BgSurface.copy(0.4f)))
            Box(Modifier.fillMaxWidth(0.72f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(BgSurface.copy(0.3f)))
        }
    }
}
