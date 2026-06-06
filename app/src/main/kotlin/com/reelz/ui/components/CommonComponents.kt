package com.reelz.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.reelz.BuildConfig
import com.reelz.data.model.*
import com.reelz.ui.theme.*

// ── Glass card container ──────────────────────────────────────────────────────
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(GlassMd)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(radius)),
        content = { content() },
    )
}

// ── Brand gradient button (matches Flutter's "Watch Now" button) ──────────────
@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    small: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(Brush.horizontalGradient(listOf(BrandDeep, Brand, Brand2)))
            .clickable(onClick = onClick)
            .padding(horizontal = if (small) 16.dp else 22.dp, vertical = if (small) 9.dp else 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (small) 12.sp else 14.sp,
                ),
            )
        }
    }
}

// ── Movie/TV card (matches Flutter's _MovieTile) ──────────────────────────────
@Composable
fun MediaPosterCard(
    media: Media,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showRating: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = BuildConfig.TMDB_IMG_W342 + media.posterPath,
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Gradient overlay
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to Color(0xCC07070B),
                )
            )
        )
        if (showRating && media.voteAverage > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(.7f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    "★ ${"%.1f".format(media.voteAverage)}",
                    color = Gold,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            media.title,
            style = MaterialTheme.typography.labelSmall.copy(color = White, fontSize = 10.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 6.dp, vertical = 5.dp),
        )
    }
}

// ── Horizontal media card (wider, for rows) ───────────────────────────────────
@Composable
fun MediaRowCard(
    media: Media,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(120.dp).clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(178.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BgRaised),
        ) {
            AsyncImage(
                model = BuildConfig.TMDB_IMG_W342 + media.posterPath,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (media.voteAverage > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(.7f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text("★ ${"%.1f".format(media.voteAverage)}", color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(media.title, color = White80, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        if (media.mediaType == MediaType.TV) {
            Text("TV Series", color = Brand, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Section header ────────────────────────────────────────────────────────────
@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, top = 24.dp, bottom = 10.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                color = Brand,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

// ── Loading states ────────────────────────────────────────────────────────────
@Composable
fun FullScreenLoader() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Brand, strokeWidth = 3.dp)
    }
}

@Composable
fun PulsingDot(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "dot")
    val scale by inf.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "scale",
    )
    Box(
        modifier = modifier
            .size(10.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(Brand),
    )
}

// ── Error state ───────────────────────────────────────────────────────────────
@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.WifiOff, null, tint = White40, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            friendlyError(message),
            color = White60,
            fontSize = 15.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 22.sp,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(24.dp))
            BrandButton("Try Again", onRetry)
        }
    }
}

/** Convert raw HTTP/technical errors to friendly messages */
fun friendlyError(raw: String): String = when {
    raw.contains("403")              -> "This content isn't available right now. Try a different source."
    raw.contains("404")              -> "Content not found. It may have moved or been removed."
    raw.contains("500")              -> "The server had a hiccup. Please try again in a moment."
    raw.contains("timeout", true)    -> "This is taking too long. Check your connection and try again."
    raw.contains("network", true) ||
    raw.contains("connect", true)    -> "No internet connection. Please check your network."
    raw.contains("stream", true) ||
    raw.contains("source", true)     -> "Couldn't find a working stream. We'll try another source."
    raw.contains("initialize", true) -> "Playback couldn't start. Please try again."
    else                             -> "Something went wrong. Please try again."
}

// ── Rating chip ───────────────────────────────────────────────────────────────
@Composable
fun RatingChip(rating: Double, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Star, null, tint = Gold, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text("${"%.1f".format(rating)}", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Genre pill ────────────────────────────────────────────────────────────────
@Composable
fun GenrePill(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(
                if (selected) Brush.horizontalGradient(listOf(BrandDeep, Brand))
                else Brush.horizontalGradient(listOf(GlassMd, GlassMd))
            )
            .border(
                1.dp,
                if (selected) Brand.copy(.5f) else GlassBorderMd,
                RoundedCornerShape(100.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            color = if (selected) Color.White else White60,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ── Bottom sheet drag handle ──────────────────────────────────────────────────
@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(GlassMd),
        )
    }
}

// ── Skeleton shimmer for loading cards ────────────────────────────────────────
@Composable
fun ShimmerCard(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "shimmer")
    val alpha by inf.animateFloat(
        0.3f, 0.7f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BgRaised.copy(alpha = alpha)),
    )
}

// ── Ad placeholder banner (slot for AppLovin/AdMob) ──────────────────────────
@Composable
fun AdBannerPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(BgCard),
        contentAlignment = Alignment.Center,
    ) {
        // TODO: Replace with real AdView using AndroidView composable
        // AdMob example:
        // AndroidView(factory = { ctx ->
        //     AdView(ctx).apply {
        //         adUnitId = BuildConfig.AD_BANNER_ID
        //         adSize   = AdSize.BANNER
        //         loadAd(AdRequest.Builder().build())
        //     }
        // })
        Text("Advertisement", color = White20, fontSize = 11.sp)
    }
}
