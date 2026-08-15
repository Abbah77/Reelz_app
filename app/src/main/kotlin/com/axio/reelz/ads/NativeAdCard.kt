package com.axio.reelz.ads

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.axio.reelz.ui.theme.BgCard
import com.axio.reelz.ui.theme.BgSurface
import com.axio.reelz.ui.theme.Primary
import com.axio.reelz.ui.theme.White60

// ─────────────────────────────────────────────────────────────────────────────
// Full-width native ad card — injected between BrowseScreen feed rows
//
// Design intent: polished, native-feeling card that blends with the feed
// rather than screaming "advertisement". Clean hierarchy:
//   1. Full-bleed 16:9 media image draws the eye
//   2. Icon + headline + body give context
//   3. CTA button is the only action — tap-target is the button, not the card,
//      to prevent accidental clicks on scroll
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NativeAdCard(adEngine: AdEngine) {
    // Stable key so LaunchedEffect doesn't re-fire on recomposition.
    // Using adEngine identity as the key means a new AdEngine (if ever swapped)
    // correctly triggers a reload, but normal recompositions don't.
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
        is NativeAdState.Loading -> NativeAdSkeleton()
        is NativeAdState.Failed  -> { /* Collapse silently — no dead space */ }
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
// Loaded card — clean, editorial look
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NativeAdContent(
    ad: NativeAdState.Loaded,
    onCtaClick: () -> Unit,
) {
    // Subtle elevation separates the card from the feed without a harsh border
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(
                elevation = 8.dp,
                shape     = RoundedCornerShape(16.dp),
                ambientColor  = Color.Black.copy(alpha = 0.5f),
                spotColor     = Color.Black.copy(alpha = 0.5f),
            )
            .clip(RoundedCornerShape(16.dp))
            .background(BgSurface)
            // Single outer border, barely visible — creates card edge without neon outline
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.07f),
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        // ── Media image (16:9) ─────────────────────────────────────────────
        // No clipping needed — parent column's clip already handles the radius.
        // Top corners are rounded by the parent; image fills top of card.
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model              = ad.imageUrl,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )

            // Subtle scrim so the "Sponsored" chip is always legible
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.40f), Color.Transparent)
                        )
                    )
            )

            // "Sponsored" label — top-left, understated
            Text(
                text       = "Sponsored",
                color      = Color.White.copy(alpha = 0.75f),
                fontSize   = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                modifier   = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp, top = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // ── Advertiser info row + CTA ──────────────────────────────────────
        Row(
            modifier  = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // App icon with soft background so transparent icons still read
            AsyncImage(
                model              = ad.iconUrl,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgCard),
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // CTA — the ONLY clickable element; card body is intentionally non-tappable
            // to prevent accidental opens during scroll
            Button(
                onClick = onCtaClick,
                colors  = ButtonDefaults.buttonColors(containerColor = Primary),
                shape   = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    text       = ad.callToAction,
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White,
                    maxLines   = 1,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shimmer skeleton — matches loaded card geometry exactly so there's no
// layout jump when the real ad arrives. Screen-width-aware shimmer travel.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NativeAdSkeleton() {
    // Use a large fixed sweep so shimmer is visible across any screen width
    // without needing BoxWithConstraints overhead.
    val sweepPx = 900f
    val transition = rememberInfiniteTransition(label = "native_ad_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue  = -sweepPx,
        targetValue   = sweepPx * 2f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "native_ad_shimmer_x",
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.03f),
            Color.White.copy(alpha = 0.10f),
            Color.White.copy(alpha = 0.03f),
        ),
        start = Offset(translateAnim, 0f),
        end   = Offset(translateAnim + sweepPx, 0f),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BgSurface),
    ) {
        // Media placeholder — same 16:9 ratio as loaded card
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(brush)
        )

        Row(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon placeholder
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(brush)
            )

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth(0.58f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.85f)
                        .height(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
            }

            // CTA placeholder
            Box(
                Modifier
                    .width(72.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(brush)
            )
        }
    }
}
