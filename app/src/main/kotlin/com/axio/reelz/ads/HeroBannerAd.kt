package com.axio.reelz.ads

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.axio.reelz.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// HeroBannerAd — native hero placement, appears in the hero pager rotation.
//
// Design: pixel-perfect match of HeroBannerPager slides.
//   • Full-bleed poster fills the same hero image height
//   • Same multi-layer gradient overlay system
//   • Same bottom info panel layout: badge, title, metadata row, buttons
//   • "Sponsored" badge replaces the "FEATURED" pulsing badge — same style
//   • CTA button occupies the "Watch Now" position
//   • Ghost "Learn More" button in the Watchlist position
//   • The user may not even know it's an ad until they see "Sponsored" badge
//
// Frequency: injected once per hero pager set, rotates every 15 minutes
// via the hero pager's auto-scroll. AdEngine controls eligibility.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HeroBannerAd(
    adEngine: AdEngine,
    modifier: Modifier = Modifier,
) {
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
        is NativeAdState.Loading -> HeroAdSkeleton(modifier)
        is NativeAdState.Failed  -> { /* Silent collapse — pager just skips this slot */ }
        is NativeAdState.Loaded  -> HeroAdContent(
            ad       = state,
            modifier = modifier,
            onCta    = { routeAdUrl(context, state.clickUrl) { url -> browserUrl = url; showBrowser = true } },
        )
    }

    if (showBrowser) {
        ReelzBrowserSheet(url = browserUrl, onDismiss = { showBrowser = false })
    }
}

@Composable
private fun HeroAdContent(
    ad: NativeAdState.Loaded,
    onCta: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d       = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(screenH * d.heroImageRatio)
            .clickable(onClick = onCta),
    ) {
        // Full-bleed creative image — same slot as movie poster in real hero
        AsyncImage(
            model              = ad.imageUrl.ifBlank { ad.iconUrl },
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )

        // Gradient overlays — identical to HeroBannerPager
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x10000000), 0.3f to Color(0x00000000), 0.65f to Color(0x99000000), 1f to Bg)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Bg.copy(.35f), Color.Transparent, Color.Transparent, Bg.copy(.25f)))))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Brand.copy(0.03f)), radius = 900f)))

        Column(Modifier.align(Alignment.BottomStart).padding(d.heroPadding)) {
            // "Sponsored" badge — same style as "FEATURED" badge
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(d.radiusSm))
                    .background(BlueGlass)
                    .border(1.dp, BlueBorder, RoundedCornerShape(d.radiusSm))
                    .padding(horizontal = d.spaceMd, vertical = d.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
            ) {
                // Small static dot instead of pulsing dot — signals "ad" subtly
                Box(
                    Modifier
                        .size(d.spaceXs + 1.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Brand.copy(0.7f))
                )
                Text(
                    "SPONSORED",
                    color         = Brand,
                    fontSize      = d.textXxs,
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
            }

            Spacer(Modifier.height(d.spaceMd))

            // Headline — same style as media title
            Text(
                text          = ad.headline,
                color         = White,
                fontWeight    = FontWeight.Black,
                fontSize      = d.textHero,
                maxLines      = 2,
                overflow      = TextOverflow.Ellipsis,
                letterSpacing = (-0.5).sp,
                lineHeight    = (d.textHero.value * 1.25f).sp,
            )

            Spacer(Modifier.height(d.spaceSm + d.spaceXxs))

            // Metadata row — advertiser name + type
            if (ad.body.isNotBlank()) {
                Text(
                    text     = ad.body,
                    color    = White60,
                    fontSize = d.textMd,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(d.spaceSm))
            }

            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
            ) {
                if (ad.advertiserName.isNotBlank()) {
                    Text(
                        text       = ad.advertiserName,
                        color      = White60,
                        fontSize   = d.textMd,
                    )
                    Box(Modifier.size(d.spaceXxs + 1.dp).clip(androidx.compose.foundation.shape.CircleShape).background(White40))
                }
                Text("Advertisement", color = White40, fontSize = d.textMd)
            }

            Spacer(Modifier.height(d.spaceLg))

            // Action row — CTA replaces "Watch Now", "Learn More" replaces Watchlist
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // BrandButton equivalent
                Button(
                    onClick  = onCta,
                    colors   = ButtonDefaults.buttonColors(containerColor = Brand),
                    shape    = RoundedCornerShape(d.radiusMd),
                    modifier = Modifier.height(d.buttonHeightMd),
                    contentPadding = PaddingValues(horizontal = d.heroPadding),
                ) {
                    Text(
                        text       = ad.callToAction,
                        color      = Color.White,
                        fontSize   = d.textMd,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // Ghost button equivalent
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(d.radiusMd))
                        .background(GlassMd)
                        .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
                        .clickable(onClick = onCta)
                        .padding(horizontal = d.heroPadding - 4.dp, vertical = 10.dp),
                ) {
                    Text(
                        text       = "Learn More",
                        color      = White80,
                        fontSize   = d.textMd,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroAdSkeleton(modifier: Modifier = Modifier) {
    val d       = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val brush   = shimmerBrush()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(screenH * d.heroImageRatio)
            .background(BgCard)
            .background(brush),
    ) {
        Column(
            Modifier.align(Alignment.BottomStart).padding(d.heroPadding),
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            Box(Modifier.width(100.dp).height(20.dp).clip(RoundedCornerShape(d.radiusSm)).background(BgSurface.copy(0.7f)))
            Box(Modifier.fillMaxWidth(0.75f).height(28.dp).clip(RoundedCornerShape(6.dp)).background(BgSurface.copy(0.5f)))
            Box(Modifier.fillMaxWidth(0.5f).height(28.dp).clip(RoundedCornerShape(6.dp)).background(BgSurface.copy(0.4f)))
            Box(Modifier.fillMaxWidth(0.55f).height(15.dp).clip(RoundedCornerShape(4.dp)).background(BgSurface.copy(0.3f)))
            Spacer(Modifier.height(d.spaceSm))
            Row(horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                Box(Modifier.width(120.dp).height(d.buttonHeightMd).clip(RoundedCornerShape(d.radiusMd)).background(Brand.copy(0.3f)))
                Box(Modifier.width(100.dp).height(d.buttonHeightMd).clip(RoundedCornerShape(d.radiusMd)).background(BgSurface.copy(0.4f)))
            }
        }
    }
}
