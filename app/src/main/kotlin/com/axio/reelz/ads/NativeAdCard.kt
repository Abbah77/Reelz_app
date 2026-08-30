package com.axio.reelz.ads

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
// NativeAdCard — injected between feed rows, blends 100% with MediaRowCard style.
//
// Design goals (TikTok / Instagram native-ad standard):
//   • Same visual weight and card dimensions as real content cards
//   • Media image fills same space as a movie poster
//   • "Sponsored" label is the only disclosure — subtle, present, honest
//   • CTA button is the only interactive surface — no accidental taps
//   • Shimmer skeleton matches loaded geometry exactly — zero layout shift
//   • Silent failure: Failed state = zero height, no dead space visible
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NativeAdCard(adEngine: AdEngine) {
    var adState by remember { mutableStateOf<NativeAdState>(NativeAdState.Loading) }
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
        is NativeAdState.Loading -> NativeAdCardSkeleton()
        is NativeAdState.Failed  -> { /* Collapse silently — zero dead space */ }
        is NativeAdState.Loaded  -> NativeAdCardContent(
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
// Loaded state — pixel-perfect match with MediaRowCard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NativeAdCardContent(
    ad: NativeAdState.Loaded,
    onCtaClick: () -> Unit,
) {
    val d = com.axio.reelz.ui.theme.LocalDimensions.current

    // Outer wrapper matches the full row width including padding — same as a real feed row
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = d.spaceMd - d.spaceXxs),
    ) {
        // ── Row header — "Sponsored" line styled exactly like a section header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = d.screenHorizPad, vertical = d.sectionVertPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            // Accent bar identical to SectionHeader accent
            Box(
                Modifier
                    .width(d.sectionAccentWidth)
                    .height(d.sectionAccentHeight)
                    .clip(RoundedCornerShape(d.spaceXxs))
                    .background(Brush.verticalGradient(listOf(Brand2, Brand)))
            )
            Text(
                text       = ad.headline,
                color      = White,
                fontSize   = d.textMd + 1.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f),
            )
            // "Sponsored" badge — compact, non-intrusive
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text          = "Sponsored",
                    color         = White40,
                    fontSize      = 9.sp,
                    fontWeight    = FontWeight.Medium,
                    letterSpacing = 0.3.sp,
                )
            }
        }

        // ── Horizontal scroll row — same LazyRow shape as MediaRowCard rows ──
        // Single featured ad card that matches the poster card dimensions
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
        ) {
            Spacer(Modifier.width(d.screenHorizPad))

            // Primary media card — same width as MediaRowCard
            Box(
                modifier = Modifier
                    .width(d.cardPosterWidth)
                    .clickable(onClick = onCtaClick),
            ) {
                // Poster image — same aspect ratio as content cards
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.68f)
                        .clip(RoundedCornerShape(d.radiusMd))
                        .background(BgRaised),
                ) {
                    if (ad.imageUrl.isNotBlank()) {
                        AsyncImage(
                            model              = ad.imageUrl,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    } else if (ad.iconUrl.isNotBlank()) {
                        AsyncImage(
                            model              = ad.iconUrl,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                    // Bottom gradient scrim — same as MediaRowCard
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.6f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.72f),
                            )
                        )
                    )
                    // Advertiser icon — bottom-left like content badges
                    if (ad.iconUrl.isNotBlank() && ad.imageUrl.isNotBlank()) {
                        AsyncImage(
                            model              = ad.iconUrl,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(BgCard),
                        )
                    }
                }

                // Card info below — matches MediaRowCard text layout
                Column(
                    modifier = Modifier.padding(top = d.spaceSm),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text       = ad.headline,
                        color      = White80,
                        fontSize   = d.textSm,
                        fontWeight = FontWeight.Medium,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                        lineHeight = (d.textSm.value * 1.35f).sp,
                    )
                    if (ad.advertiserName.isNotBlank()) {
                        Text(
                            text     = ad.advertiserName,
                            color    = Brand.copy(0.7f),
                            fontSize = d.textXxs,
                        )
                    }
                }
            }

            // ── Wide info card — sits next to the poster like a "wide card" variant ──
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .clickable(onClick = onCtaClick),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.68f)
                        .clip(RoundedCornerShape(d.radiusMd))
                        .background(BgSurface)
                        .border(0.5.dp, GlassBorder, RoundedCornerShape(d.radiusMd))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text       = ad.headline,
                            color      = White,
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines   = 2,
                            overflow   = TextOverflow.Ellipsis,
                            lineHeight = 20.sp,
                        )
                        if (ad.body.isNotBlank()) {
                            Text(
                                text       = ad.body,
                                color      = White60,
                                fontSize   = 12.sp,
                                maxLines   = 4,
                                overflow   = TextOverflow.Ellipsis,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                    Button(
                        onClick        = onCtaClick,
                        colors         = ButtonDefaults.buttonColors(containerColor = Brand),
                        shape          = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier       = Modifier.height(34.dp),
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

            Spacer(Modifier.width(d.screenHorizPad))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Skeleton — matches loaded layout geometry for zero-shift appearance
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NativeAdCardSkeleton() {
    val d = com.axio.reelz.ui.theme.LocalDimensions.current
    val brush = shimmerBrush()

    Column(Modifier.fillMaxWidth().padding(vertical = d.spaceMd - d.spaceXxs)) {
        // Header skeleton
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = d.screenHorizPad, vertical = d.sectionVertPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            Box(Modifier.width(d.sectionAccentWidth).height(d.sectionAccentHeight).clip(RoundedCornerShape(d.spaceXxs)).background(brush))
            Box(Modifier.width(140.dp).height(13.dp).clip(RoundedCornerShape(4.dp)).background(brush))
        }
        // Card row skeleton
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
        ) {
            Box(Modifier.width(d.cardPosterWidth).aspectRatio(0.68f).clip(RoundedCornerShape(d.radiusMd)).background(brush))
            Box(Modifier.width(220.dp).aspectRatio(0.68f).clip(RoundedCornerShape(d.radiusMd)).background(brush))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared shimmer brush
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun shimmerBrush(): Brush {
    val sweep  = 900f
    val trans  = rememberInfiniteTransition(label = "shimmer")
    val offset by trans.animateFloat(
        initialValue  = -sweep,
        targetValue   = sweep * 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label         = "shimmerX",
    )
    return Brush.linearGradient(
        colors = listOf(Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.03f)),
        start  = Offset(offset, 0f),
        end    = Offset(offset + sweep, 0f),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// NativeAdRowCard — single-card variant that slots into a LazyRow at any index.
//
// Pixel-perfect match of MediaRowCard — same width (cardRowWidth), same height,
// same corner radius, same border. The ONLY visible difference:
//   • Title shows the ad headline (not a movie title)
//   • A tiny "Sponsored" label replaces the genre/year metadata
// This is the TikTok/Instagram approach: the card IS the feed.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NativeAdRowCard(adEngine: AdEngine) {
    var adState    by remember { mutableStateOf<NativeAdState>(NativeAdState.Loading) }
    var showBrowser by remember { mutableStateOf(false) }
    var browserUrl  by remember { mutableStateOf("") }
    val context = LocalContext.current
    val d = com.axio.reelz.ui.theme.LocalDimensions.current

    LaunchedEffect(adEngine) {
        adEngine.loadNativeAd(
            onLoaded = { ad -> adState = ad },
            onFailed = { adState = NativeAdState.Failed },
        )
    }

    when (val state = adState) {
        is NativeAdState.Loading -> {
            // Loading skeleton — exact MediaRowCard dimensions
            val brush = shimmerBrush()
            Column(Modifier.width(d.cardRowWidth)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(d.cardRowHeight)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(d.radiusMd))
                        .background(brush)
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(d.spaceSm))
                Box(Modifier.fillMaxWidth(0.8f).height(11.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(brush))
                androidx.compose.foundation.layout.Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth(0.5f).height(9.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(brush))
            }
        }
        is NativeAdState.Failed -> { /* Silent — slot simply disappears */ }
        is NativeAdState.Loaded -> {
            Column(
                modifier = Modifier
                    .width(d.cardRowWidth)
                    .clickable {
                        routeAdUrl(context, state.clickUrl) { url -> browserUrl = url; showBrowser = true }
                    },
            ) {
                // Card image — same dimensions as MediaRowCard poster
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(d.cardRowHeight)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(d.radiusMd))
                        .border(
                            com.axio.reelz.ui.theme.LocalDimensions.current.borderThin,
                            GlassBorder,
                            androidx.compose.foundation.shape.RoundedCornerShape(d.radiusMd),
                        )
                        .background(BgRaised),
                ) {
                    val imgUrl = state.imageUrl.ifBlank { state.iconUrl }
                    if (imgUrl.isNotBlank()) {
                        AsyncImage(
                            model              = imgUrl,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                    // Bottom gradient + "Sponsored" badge — same position as rating chips on real cards
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(0.7f))
                        )
                    )
                    Text(
                        text          = "Sponsored",
                        color         = White40,
                        fontSize      = 9.sp,
                        fontWeight    = FontWeight.Medium,
                        letterSpacing = 0.2.sp,
                        modifier      = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 6.dp, bottom = 5.dp),
                    )
                }

                androidx.compose.foundation.layout.Spacer(Modifier.height(d.spaceSm))

                // Title — styled identically to MediaRowCard title
                Text(
                    text       = state.headline,
                    color      = White80,
                    fontSize   = d.textSm,
                    fontWeight = FontWeight.Medium,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    lineHeight = (d.textSm.value * 1.35f).sp,
                )
            }

            if (showBrowser) {
                ReelzBrowserSheet(url = browserUrl, onDismiss = { showBrowser = false })
            }
        }
    }
}
