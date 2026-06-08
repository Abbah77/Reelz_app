package com.reelz.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.reelz.BuildConfig
import com.reelz.data.model.*
import com.reelz.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// Custom SVG-style icon vectors
// ─────────────────────────────────────────────────────────────────────────────

val IconPlay: ImageVector get() = ImageVector.Builder("Play", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(
        pathData = PathData {
            moveTo(8f, 5.5f); lineTo(8f, 18.5f); lineTo(19f, 12f); close()
        },
        fill = SolidColor(Color.White),
    )
}.build()

val IconSearch: ImageVector get() = ImageVector.Builder("Search", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(10.5f, 3f)
        arcTo(7.5f, 7.5f, 0f, false, false, 10.5f, 18f)
        arcTo(7.5f, 7.5f, 0f, false, false, 10.5f, 3f); close()
    }, fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f)
    addPath(pathData = PathData { moveTo(16.5f, 16.5f); lineTo(21f, 21f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round)
}.build()

val IconStar: ImageVector get() = ImageVector.Builder("Star", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f)
        lineTo(14.6f, 8.26f); lineTo(21.5f, 9.27f); lineTo(16.75f, 13.9f)
        lineTo(17.97f, 20.8f); lineTo(12f, 17.6f); lineTo(6.03f, 20.8f)
        lineTo(7.25f, 13.9f); lineTo(2.5f, 9.27f); lineTo(9.4f, 8.26f); close()
    }, fill = SolidColor(Color(0xFFFFCC44)))
}.build()

val IconWifiOff: ImageVector get() = ImageVector.Builder("WifiOff", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(2f, 2f); lineTo(22f, 22f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round)
    addPath(pathData = PathData {
        moveTo(10.7f, 6.3f)
        arcTo(10.5f, 10.5f, 0f, false, true, 21f, 9f)
        moveTo(3f, 9f)
        arcTo(10.5f, 10.5f, 0f, false, true, 7.45f, 6.4f)
        moveTo(6.6f, 12.6f)
        arcTo(7f, 7f, 0f, false, true, 17.5f, 12.6f)
        moveTo(12f, 21f); lineTo(12f, 21.01f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round)
}.build()

val IconMovieSlate: ImageVector get() = ImageVector.Builder("MovieSlate", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(4f, 6f); lineTo(4f, 20f)
        arcTo(1f, 1f, 0f, false, false, 5f, 21f); lineTo(19f, 21f)
        arcTo(1f, 1f, 0f, false, false, 20f, 20f); lineTo(20f, 8f); lineTo(4f, 8f)
        moveTo(4f, 8f); lineTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 8f)
        moveTo(8f, 4f); lineTo(8f, 8f); moveTo(12f, 4f); lineTo(12f, 8f); moveTo(16f, 4f); lineTo(16f, 8f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

val IconDownloadCloud: ImageVector get() = ImageVector.Builder("DLCloud", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(8f, 17f); lineTo(12f, 21f); lineTo(16f, 17f)
        moveTo(12f, 21f); lineTo(12f, 11f)
        moveTo(20.9f, 14.7f)
        arcTo(4f, 4f, 0f, false, false, 17.5f, 8f)
        arcTo(6f, 6f, 0f, false, false, 5f, 11.3f)
        arcTo(4f, 4f, 0f, false, false, 6f, 19f); lineTo(8f, 19f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

val IconSwap: ImageVector get() = ImageVector.Builder("Swap", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(7f, 16f); lineTo(3f, 12f); lineTo(7f, 8f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(17f, 8f); lineTo(21f, 12f); lineTo(17f, 16f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

val IconUser: ImageVector get() = ImageVector.Builder("User", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(4f, 4f, 0f, false, false, 12f, 10f); arcTo(4f, 4f, 0f, false, false, 12f, 2f); close()
        moveTo(4f, 22f); arcTo(8f, 8f, 0f, false, true, 20f, 22f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round,
       fill = SolidColor(Color.Transparent))
}.build()

val IconCompass: ImageVector get() = ImageVector.Builder("Compass", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, fill = SolidColor(Color.Transparent))
    addPath(pathData = PathData {
        moveTo(16.24f, 7.76f); lineTo(14.12f, 14.12f); lineTo(7.76f, 16.24f); lineTo(9.88f, 9.88f); close()
    }, fill = SolidColor(Color(0xFFE8A020)))
}.build()

val IconPlayCircle: ImageVector get() = ImageVector.Builder("PlayCircle", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, fill = SolidColor(Color.Transparent))
    addPath(pathData = PathData { moveTo(10f, 8f); lineTo(16f, 12f); lineTo(10f, 16f); close() },
        fill = SolidColor(Color.White))
}.build()

// ─────────────────────────────────────────────────────────────────────────────
// Glass card container
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    borderColor: Color = GlassBorderMd,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(GlassMd)
            .border(1.dp, borderColor, RoundedCornerShape(radius)),
        content = { content() },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Premium amber gradient button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    small: Boolean = false,
) {
    val shimmer = rememberInfiniteTransition(label = "btnShimmer")
    val shimmerX by shimmer.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "sx"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to BrandDeep,
                        0.4f to Brand,
                        shimmerX to Brand2.copy(alpha = 0.9f),
                        1f to Brand,
                    )
                )
            )
            .border(1.dp, Brand2.copy(.35f), RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (small) 16.dp else 24.dp,
                vertical   = if (small) 10.dp else 14.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(7.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge.copy(
                    color      = Color(0xFF1A0F00),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize   = if (small) 12.sp else 14.sp,
                    letterSpacing = 0.3.sp,
                ),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Outlined ghost button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(AmberGlass)
            .border(1.dp, AmberBorder, RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(6.dp))
            Text(text, color = Brand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Movie/TV poster card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MediaPosterCard(
    media: Media,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showRating: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, GlassBorderMd, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = BuildConfig.TMDB_IMG_W342 + media.posterPath,
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Cinematic gradient
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f   to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f   to Color(0xEE05050A),
                )
            )
        )
        if (showRating && media.voteAverage > 0) {
            // Rating pill — top right
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000))
                    .border(1.dp, AmberBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(IconStar, null, tint = Gold, modifier = Modifier.size(9.dp))
                Spacer(Modifier.width(3.dp))
                Text("${"%.1f".format(media.voteAverage)}", color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            media.title,
            style = MaterialTheme.typography.labelSmall.copy(color = White, fontSize = 10.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 7.dp, vertical = 6.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Horizontal media card (wider, for rows)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MediaRowCard(
    media: Media,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(124.dp).clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(185.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, GlassBorderMd, RoundedCornerShape(14.dp))
                .background(BgRaised),
        ) {
            AsyncImage(
                model = BuildConfig.TMDB_IMG_W342 + media.posterPath,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Bottom gradient
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC05050A))
            ))
            if (media.voteAverage > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xCC000000))
                        .border(1.dp, AmberBorder, RoundedCornerShape(5.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconStar, null, tint = Gold, modifier = Modifier.size(9.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("${"%.1f".format(media.voteAverage)}", color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(media.title, color = White80, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
        if (media.mediaType == MediaType.TV) {
            Spacer(Modifier.height(2.dp))
            Text("TV Series", color = Brand, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section header with optional "See All" action
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, top = 28.dp, bottom = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Amber accent bar
        Box(
            Modifier
                .width(3.dp).height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(Brand2, Brand)))
        )
        Spacer(Modifier.width(9.dp))
        Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = (-0.2).sp)
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(action, color = Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Premium cinematic loading spinner (orbital rings)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun FullScreenLoader() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CinematicSpinner(size = 56.dp)
    }
}

@Composable
fun CinematicSpinner(size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "spinner")
    val angle1 by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), "a1")
    val angle2 by inf.animateFloat(360f, 0f, infiniteRepeatable(tween(900, easing = LinearEasing)), "a2")
    val pulse  by inf.animateFloat(0.6f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "p")

    Canvas(modifier.size(size)) {
        val r1 = size.toPx() / 2f
        val r2 = r1 * 0.62f
        val cx = r1; val cy = r1
        val stroke1 = Stroke(width = r1 * 0.09f, cap = StrokeCap.Round)
        val stroke2 = Stroke(width = r1 * 0.06f, cap = StrokeCap.Round)

        // Outer arc — gold
        drawArc(
            brush = Brush.sweepGradient(listOf(Color(0xFFE8A020), Color(0xFFFFCC55), Color.Transparent)),
            startAngle = angle1,
            sweepAngle = 240f,
            useCenter = false,
            style = stroke1,
            topLeft = Offset(cx - r1 + stroke1.width / 2, cy - r1 + stroke1.width / 2),
            size = androidx.compose.ui.geometry.Size(
                (r1 - stroke1.width / 2) * 2, (r1 - stroke1.width / 2) * 2
            ),
        )
        // Inner arc — teal
        drawArc(
            brush = Brush.sweepGradient(listOf(Color(0xFF00E5CC), Color.Transparent)),
            startAngle = angle2,
            sweepAngle = 180f,
            useCenter = false,
            style = stroke2,
            topLeft = Offset(cx - r2 + stroke2.width / 2, cy - r2 + stroke2.width / 2),
            size = androidx.compose.ui.geometry.Size(
                (r2 - stroke2.width / 2) * 2, (r2 - stroke2.width / 2) * 2
            ),
        )
        // Centre dot
        drawCircle(
            color = Brand.copy(alpha = pulse),
            radius = r1 * 0.1f,
            center = Offset(cx, cy),
        )
    }
}

// Inline small spinner
@Composable
fun SmallSpinner(modifier: Modifier = Modifier) {
    CinematicSpinner(size = 26.dp, modifier = modifier)
}

@Composable
fun PulsingDot(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "dot")
    val scale by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "sc")
    val glow  by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "gl")
    Box(
        modifier = modifier.size(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().scale(scale + 0.4f).clip(CircleShape).background(Brand.copy(glow * 0.3f)))
        Box(Modifier.size(7.dp).scale(scale).clip(CircleShape).background(Brand))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error state
// ─────────────────────────────────────────────────────────────────────────────
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
        // Error icon inside glowing ring
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(72.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Error.copy(.15f), Color.Transparent)))
                    .border(1.dp, Error.copy(.35f), CircleShape)
            )
            Icon(IconWifiOff, null, tint = Error.copy(.8f), modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            friendlyError(message),
            color = White60,
            fontSize = 15.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 23.sp,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(28.dp))
            BrandButton("Try Again", onRetry)
        }
    }
}

fun friendlyError(raw: String): String = when {
    raw.contains("403")              -> "This content isn't available right now."
    raw.contains("404")              -> "Content not found. It may have moved or been removed."
    raw.contains("500")              -> "Server hiccup — please try again in a moment."
    raw.contains("timeout", true)    -> "Taking too long. Check your connection and retry."
    raw.contains("network", true) ||
    raw.contains("connect", true)    -> "No internet. Please check your network."
    raw.contains("stream", true) ||
    raw.contains("source", true)     -> "Couldn't find a working stream. Trying another source."
    raw.contains("initialize", true) -> "Playback couldn't start. Please try again."
    else                             -> "Something went wrong. Please try again."
}

// ─────────────────────────────────────────────────────────────────────────────
// Rating chip
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RatingChip(rating: Double, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22FFD700))
            .border(1.dp, Gold.copy(.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IconStar, null, tint = Gold, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text("${"%.1f".format(rating)}", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Genre pill
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GenrePill(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit = {},
) {
    val bgBrush = if (selected)
        Brush.horizontalGradient(listOf(BrandDeep, Brand.copy(.85f)))
    else
        Brush.horizontalGradient(listOf(BgRaised, BgSurface))

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(bgBrush)
            .border(1.dp, if (selected) Brand.copy(.55f) else GlassBorderMd, RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            color      = if (selected) Color(0xFF1A0F00) else White60,
            fontSize   = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Drag handle for bottom sheets
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(listOf(Brand.copy(.4f), Brand2.copy(.4f), Brand.copy(.4f))))
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shimmer skeleton card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ShimmerCard(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "shimmer")
    val offset by inf.animateFloat(
        -1f, 2f, infiniteRepeatable(tween(1200, easing = LinearEasing)), "off"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f           to BgRaised,
                        offset * 0.5f to BgSurface,
                        1f           to BgRaised,
                    ),
                    start = Offset.Zero,
                    end   = Offset(Float.POSITIVE_INFINITY, 0f),
                )
            )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Ad placeholder
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AdBannerPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(52.dp)
            .background(BgCard)
            .border(BorderStroke(1.dp, GlassBorderMd)),
        contentAlignment = Alignment.Center,
    ) {
        Text("Advertisement", color = White20, fontSize = 10.sp, letterSpacing = 2.sp)
    }
}
