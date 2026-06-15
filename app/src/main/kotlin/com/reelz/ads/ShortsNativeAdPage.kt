package com.reelz.ads

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
import com.reelz.ui.theme.White60

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
// Fallback / loading pages — user can always swipe past
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShortsAdLoadingPage() {
    Box(Modifier.fillMaxSize().background(Bg))
}

@Composable
private fun ShortsAdFallbackPage() {
    // Collapse to empty dark page — user just swipes past it
    Box(Modifier.fillMaxSize().background(Bg))
}
