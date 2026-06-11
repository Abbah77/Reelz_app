package com.reelz.ads

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.reelz.ui.theme.Bg
import com.reelz.ui.theme.Primary
import com.reelz.ui.theme.Surface
import com.reelz.ui.theme.White60

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
            .background(Surface)
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
            .background(Surface),
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
