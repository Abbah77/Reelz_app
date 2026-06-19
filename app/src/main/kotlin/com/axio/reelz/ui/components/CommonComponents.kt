package com.axio.reelz.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import com.axio.reelz.BuildConfig
import com.axio.reelz.data.model.*
import com.axio.reelz.ui.theme.*

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

// ── Heart / Like icon (filled red heart) ─────────────────────────────────────
val IconHeart: ImageVector get() = ImageVector.Builder("Heart", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 21f)
        curveTo(12f, 21f, 3f, 14f, 3f, 8f)
        arcTo(4.5f, 4.5f, 0f, false, true, 12f, 6.1f)
        arcTo(4.5f, 4.5f, 0f, false, true, 21f, 8f)
        curveTo(21f, 14f, 12f, 21f, 12f, 21f); close()
    }, fill = SolidColor(Color.Transparent),
       stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()

val IconHeartFilled: ImageVector get() = ImageVector.Builder("HeartFilled", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 21f)
        curveTo(12f, 21f, 3f, 14f, 3f, 8f)
        arcTo(4.5f, 4.5f, 0f, false, true, 12f, 6.1f)
        arcTo(4.5f, 4.5f, 0f, false, true, 21f, 8f)
        curveTo(21f, 14f, 12f, 21f, 12f, 21f); close()
    }, fill = SolidColor(Color(0xFFFF2D55)),
       stroke = SolidColor(Color(0xFFFF2D55)), strokeLineWidth = 1f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()

// ── Home icons ─────────────────────────────────────────────────────────────────
val IconHome: ImageVector get() = ImageVector.Builder("Home", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(3f, 9.5f); lineTo(12f, 3f); lineTo(21f, 9.5f)
        lineTo(21f, 20f); arcTo(1f, 1f, 0f, false, true, 20f, 21f)
        lineTo(15f, 21f); lineTo(15f, 16f); lineTo(9f, 16f); lineTo(9f, 21f)
        lineTo(4f, 21f); arcTo(1f, 1f, 0f, false, true, 3f, 20f); close()
    }, fill = SolidColor(Color.Transparent),
       stroke = SolidColor(Color.White), strokeLineWidth = 1.7f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()

val IconHomeFilled: ImageVector get() = ImageVector.Builder("HomeFilled", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(3f, 9.5f); lineTo(12f, 3f); lineTo(21f, 9.5f)
        lineTo(21f, 20f); arcTo(1f, 1f, 0f, false, true, 20f, 21f)
        lineTo(15f, 21f); lineTo(15f, 15f); arcTo(1f, 1f, 0f, false, false, 14f, 14f)
        lineTo(10f, 14f); arcTo(1f, 1f, 0f, false, false, 9f, 15f)
        lineTo(9f, 21f); lineTo(4f, 21f)
        arcTo(1f, 1f, 0f, false, true, 3f, 20f); close()
    }, fill = SolidColor(Color(0xFF0A84FF)))
}.build()

// ── Reel / Shorts icons ────────────────────────────────────────────────────────
val IconReel: ImageVector get() = ImageVector.Builder("Reel", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f)
        arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
        moveTo(2f, 12f); lineTo(22f, 12f)
        moveTo(12f, 2f); arcTo(5f, 10f, 0f, false, false, 12f, 22f)
        arcTo(5f, 10f, 0f, false, false, 12f, 2f)
    }, fill = SolidColor(Color.Transparent),
       stroke = SolidColor(Color.White), strokeLineWidth = 1.6f)
}.build()

val IconReelFilled: ImageVector get() = ImageVector.Builder("ReelFilled", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f)
        arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
    }, fill = SolidColor(Color(0xFF0A84FF)))
    addPath(pathData = PathData {
        moveTo(10f, 8.5f); lineTo(16f, 12f); lineTo(10f, 15.5f); close()
    }, fill = SolidColor(Color.White))
}.build()

// ── User icons ────────────────────────────────────────────────────────────────
val IconUser: ImageVector get() = ImageVector.Builder("User", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(4f, 4f, 0f, false, false, 12f, 10f)
        arcTo(4f, 4f, 0f, false, false, 12f, 2f); close()
        moveTo(4f, 22f); arcTo(8f, 8f, 0f, false, true, 20f, 22f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f,
       strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

val IconUserFilled: ImageVector get() = ImageVector.Builder("UserFilled", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(4f, 4f, 0f, false, false, 12f, 10f)
        arcTo(4f, 4f, 0f, false, false, 12f, 2f); close()
    }, fill = SolidColor(Color(0xFF0A84FF)))
    addPath(pathData = PathData {
        moveTo(4f, 22f); arcTo(8f, 8f, 0f, false, true, 20f, 22f)
    }, stroke = SolidColor(Color(0xFF0A84FF)), strokeLineWidth = 2f,
       strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
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

val IconCompass: ImageVector get() = ImageVector.Builder("Compass", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, fill = SolidColor(Color.Transparent))
    addPath(pathData = PathData {
        moveTo(16.24f, 7.76f); lineTo(14.12f, 14.12f); lineTo(7.76f, 16.24f); lineTo(9.88f, 9.88f); close()
    }, fill = SolidColor(Color(0xFF0A84FF)))
}.build()

val IconPlayCircle: ImageVector get() = ImageVector.Builder("PlayCircle", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, fill = SolidColor(Color.Transparent))
    addPath(pathData = PathData { moveTo(10f, 8f); lineTo(16f, 12f); lineTo(10f, 16f); close() },
        fill = SolidColor(Color.White))
}.build()

val IconFilter: ImageVector get() = ImageVector.Builder("Filter", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(3f, 6f); lineTo(21f, 6f)
        moveTo(7f, 12f); lineTo(17f, 12f)
        moveTo(10f, 18f); lineTo(14f, 18f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round)
}.build()

val IconChevronDown: ImageVector get() = ImageVector.Builder("ChevDown", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

val IconClose: ImageVector get() = ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(18f, 6f); lineTo(6f, 18f); moveTo(6f, 6f); lineTo(18f, 18f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round)
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
// Premium electric-blue gradient button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    small: Boolean = false,
    enabled: Boolean = true,
) {
    val shimmer = rememberInfiniteTransition(label = "btnShimmer")
    val shimmerX by shimmer.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "sx"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(
                if (enabled) {
                    Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to BrandDeep,
                            0.4f to Brand,
                            shimmerX to Brand2.copy(alpha = 0.9f),
                            1f to Brand,
                        )
                    )
                } else {
                    Brush.linearGradient(listOf(GlassMd, GlassMd))
                }
            )
            .border(1.dp, if (enabled) Brand2.copy(.4f) else GlassBorderMd, RoundedCornerShape(100.dp))
            .clickable(enabled = enabled, onClick = onClick)
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
                    color      = if (enabled) Color.White else White40,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize   = if (small) 12.sp else 14.sp,
                    letterSpacing = 0.3.sp,
                ),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ghost button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    icon: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(GlassMd)
            .border(1.dp, GlassBorderHv, RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (small) 14.dp else 20.dp,
                vertical   = if (small) 9.dp else 13.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(7.dp))
            Text(
                text,
                color      = White80,
                fontWeight = FontWeight.SemiBold,
                fontSize   = if (small) 12.sp else 14.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3D tilt poster card — with tap/press 3D effect
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MediaPosterCard(
    media: Media,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "cardScale"
    )
    val elevation by animateFloatAsState(
        targetValue = if (pressed) 2f else 12f,
        animationSpec = spring(stiffness = 400f),
        label = "elevation"
    )
    val rotateX by animateFloatAsState(
        targetValue = if (pressed) 6f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "rotX"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                rotationX = rotateX
                shadowElevation = elevation
                shape = RoundedCornerShape(14.dp)
                // clip = false so shadow shape is respected but title text below image is NOT clipped
                clip = false
                cameraDistance = 8f * density
            }
            // No outer Column clip — image Box below carries its own RoundedCornerShape clip,
            // and title text must not be cut by the column's bottom corners.
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))          // image clipped here — correct
                .border(1.dp, if (pressed) BlueBorder else GlassBorder, RoundedCornerShape(14.dp))
                .background(BgRaised)
        ) {
            AsyncImage(
                model = BuildConfig.TMDB_IMG_W342 + media.posterPath,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Bottom fade
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC05050A))
            ))
            // Blue glow on press
            if (pressed) {
                Box(Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Brand.copy(0.15f), Color.Transparent))
                ))
            }
            if (media.voteAverage > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xCC000000))
                        .border(1.dp, BlueBorder, RoundedCornerShape(5.dp))
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
        Text(
            media.title,
            color = White80,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        if (media.mediaType == MediaType.TV) {
            Spacer(Modifier.height(2.dp))
            Text(
                "TV Series",
                color = Brand,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Media row card (horizontal list)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MediaRowCard(media: Media, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "rowScale"
    )

    Column(
        modifier = modifier
            .width(130.dp)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                cameraDistance = 8f * density
            }
            // No Column-level clip: the image Box below has its own RoundedCornerShape clip.
            // Clipping the whole Column rounded the bottom corners of the Column, visually
            // cutting into the first letter of the title text that sits below the image.
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, if (pressed) BlueBorder else GlassBorder, RoundedCornerShape(12.dp))
                .background(BgRaised)
        ) {
            AsyncImage(
                model = BuildConfig.TMDB_IMG_W342 + media.posterPath,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xDD05050A))
            ))
            if (pressed) {
                Box(Modifier.fillMaxSize().background(Brand.copy(0.08f)))
            }
            if (media.voteAverage > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xBB000000))
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconStar, null, tint = Gold, modifier = Modifier.size(9.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${"%.1f".format(media.voteAverage)}", color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            media.title,
            color = White80,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
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
        // Blue accent bar
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
// Skeleton shimmer loading — replaces spinner
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SkeletonBannerLoader() {
    val inf = rememberInfiniteTransition(label = "skBanner")
    val offset by inf.animateFloat(
        -1.5f, 2.5f, infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing)), "skOff"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(400.dp)
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to BgCard,
                        (offset * 0.4f + 0.3f).coerceIn(0f, 1f) to BgSurface,
                        1f to BgCard,
                    ),
                    start = Offset.Zero,
                    end   = Offset(Float.POSITIVE_INFINITY, 0f),
                )
            )
    ) {
        // Skeleton content overlay
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(80.dp).height(18.dp).clip(RoundedCornerShape(4.dp)).background(BgRaised))
            Box(Modifier.width(240.dp).height(28.dp).clip(RoundedCornerShape(6.dp)).background(BgRaised))
            Box(Modifier.width(160.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(BgRaised))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(110.dp).height(40.dp).clip(RoundedCornerShape(100.dp)).background(BgSurface))
                Box(Modifier.width(90.dp).height(40.dp).clip(RoundedCornerShape(100.dp)).background(BgRaised))
            }
        }
    }
}

@Composable
fun SkeletonRowLoader() {
    val inf = rememberInfiniteTransition(label = "skRow")
    val offset by inf.animateFloat(
        -1.5f, 2.5f, infiniteRepeatable(tween(900, easing = LinearEasing)), "off"
    )
    val shimmerBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to BgRaised,
            (offset * 0.4f + 0.3f).coerceIn(0f, 1f) to BgSurface,
            1f to BgRaised,
        ),
        start = Offset.Zero,
        end   = Offset(Float.POSITIVE_INFINITY, 0f),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) {
            Column(Modifier.width(130.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(130.dp).height(190.dp).clip(RoundedCornerShape(12.dp)).background(shimmerBrush))
                Box(Modifier.width(110.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
                Box(Modifier.width(80.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Premium cinematic loading spinner (kept for reuse)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun FullScreenLoader() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CinematicSpinner(size = 56.dp)
    }
}

@Composable
fun CinematicSpinner(size: Dp = 44.dp, modifier: Modifier = Modifier, color: Color = Brand) {
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

        drawArc(
            brush = Brush.sweepGradient(listOf(color, color.copy(0.5f), Color.Transparent)),
            startAngle = angle1, sweepAngle = 240f, useCenter = false, style = stroke1,
            topLeft = Offset(cx - r1 + stroke1.width / 2, cy - r1 + stroke1.width / 2),
            size = androidx.compose.ui.geometry.Size((r1 - stroke1.width / 2) * 2, (r1 - stroke1.width / 2) * 2),
        )
        drawArc(
            brush = Brush.sweepGradient(listOf(Color(0xFF00E5CC), Color.Transparent)),
            startAngle = angle2, sweepAngle = 180f, useCenter = false, style = stroke2,
            topLeft = Offset(cx - r2 + stroke2.width / 2, cy - r2 + stroke2.width / 2),
            size = androidx.compose.ui.geometry.Size((r2 - stroke2.width / 2) * 2, (r2 - stroke2.width / 2) * 2),
        )
        drawCircle(color = color.copy(alpha = pulse), radius = r1 * 0.1f, center = Offset(cx, cy))
    }
}

@Composable
fun SmallSpinner(modifier: Modifier = Modifier) { CinematicSpinner(size = 26.dp, modifier = modifier) }

@Composable
fun PulsingDot(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "dot")
    val scale by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "sc")
    val glow  by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "gl")
    Box(modifier = modifier.size(10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().scale(scale + 0.4f).clip(CircleShape).background(Brand.copy(glow * 0.3f)))
        Box(Modifier.size(7.dp).scale(scale).clip(CircleShape).background(Brand))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error state
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(72.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Error.copy(.15f), Color.Transparent)))
                .border(1.dp, Error.copy(.35f), CircleShape))
            Icon(IconWifiOff, null, tint = Error.copy(.8f), modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(friendlyError(message), color = White60, fontSize = 15.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 23.sp)
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
// Genre pill — with blue active state
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GenrePill(text: String, selected: Boolean = false, onClick: () -> Unit = {}) {
    val bgBrush = if (selected)
        Brush.horizontalGradient(listOf(BrandDeep, Brand.copy(.9f)))
    else
        Brush.horizontalGradient(listOf(BgRaised, BgSurface))

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(bgBrush)
            .border(1.dp, if (selected) Brand.copy(.6f) else GlassBorderMd, RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            color      = if (selected) Color.White else White60,
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

// ─────────────────────────────────────────────────────────────────────────────
// "Remove ads — go Premium" feed banner
//
// A real, visible entry point to the ad-free upsell on the feeds people spend
// the most time in (Browse, Shorts) — previously the only path to Premium was
// the Profile tab card or stumbling onto a download/resolution lock. Dismissed
// per-session (not persisted) so it isn't a permanent nag on every app open,
// but also never disappears forever after one tap of the X.
//
// Callers are expected to gate this with AdEngine.shouldShowRemoveAdsBanner()
// before placing it in a feed — never shown to premium users or when ads are
// globally off in config, since the pitch would be pointless either way.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RemoveAdsBanner(
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shimmer = rememberInfiniteTransition(label = "removeAdsShimmer")
    val shimmerX by shimmer.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "rax"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f            to BrandDeep,
                        0.45f         to Brand.copy(alpha = 0.9f),
                        shimmerX      to Brand2.copy(alpha = 0.55f),
                        1f            to BrandDeep,
                    )
                )
            )
            .border(1.dp, Brand2.copy(.35f), RoundedCornerShape(16.dp))
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Upgrade tap target — sparkle + copy, its own explicit clickable area
        // rather than an ambient click on the whole row, so the dismiss button
        // below is an unambiguous sibling target, never a "carve-out" inside it.
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { onUpgrade() }
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✦", fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Remove ads",
                    color      = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp,
                )
                Text(
                    "Go Premium for an uninterrupted, ad-free experience",
                    color    = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.padding(end = 8.dp).size(28.dp)) {
            Icon(IconClose, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
        }
    }
}
