package com.axio.reelz.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.axio.reelz.BuildConfig
import com.axio.reelz.data.model.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions

// ─────────────────────────────────────────────────────────────────────────────
// Icon vectors  (Fluent UI / Lucide / FontAwesome / Bootstrap quality)
// Colors:
//   • Nav inactive  → Color.White (full opacity, tint applied at call site via White60)
//   • Nav active    → Color.White (full opacity, tint applied at call site via Brand)
//   • Heart outline → Color.White stroke
//   • Heart filled  → Instagram red #FF2D55 (matches Instagram, universal heart colour)
//   • Star          → Amber #FFCC44
//   • All other UI icons → Color.White so tint works at call site
// ─────────────────────────────────────────────────────────────────────────────

// ── Play (FontAwesome solid triangle — industry standard) ─────────────────────
val IconPlay: ImageVector get() = ImageVector.Builder("Play", 24.dp, 24.dp, 448f, 512f).apply {
    addPath(pathData = PathData {
        moveTo(424.4f, 214.7f)
        lineTo(72.4f, 6.6f)
        curveTo(43.8f, -10.3f, 0f, 6.1f, 0f, 47.9f)
        verticalLineTo(464f)
        curveTo(0f, 501.5f, 40.7f, 524.1f, 72.4f, 505.3f)
        lineTo(424.4f, 297.3f)
        curveTo(455.8f, 278.8f, 455.9f, 233.2f, 424.4f, 214.7f)
        close()
    }, fill = SolidColor(Color.White))
}.build()

// ── Search (Feather — clean circle + line, stroke-based) ─────────────────────
val IconSearch: ImageVector get() = ImageVector.Builder("Search", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(19f, 11f)
        arcTo(8f, 8f, 0f, false, true, 11f, 19f)
        arcTo(8f, 8f, 0f, false, true, 3f, 11f)
        arcTo(8f, 8f, 0f, false, true, 19f, 11f)
        close()
    }, fill = SolidColor(Color.Transparent),
       stroke = SolidColor(Color.White), strokeLineWidth = 2f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    addPath(pathData = PathData { moveTo(21f, 21f); lineTo(16.65f, 16.65f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

// ── Star (5-point filled — amber, used for ratings) ──────────────────────────
val IconStar: ImageVector get() = ImageVector.Builder("Star", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f)
        lineTo(15.09f, 8.26f); lineTo(22f, 9.27f)
        lineTo(17f, 14.14f); lineTo(18.18f, 21.02f)
        lineTo(12f, 17.77f); lineTo(5.82f, 21.02f)
        lineTo(7f, 14.14f); lineTo(2f, 9.27f)
        lineTo(8.91f, 8.26f); close()
    }, fill = SolidColor(Color(0xFFFFCC44)))
}.build()

// ── Heart outline (Bootstrap — white stroke, used before liked) ───────────────
val IconHeart: ImageVector get() = ImageVector.Builder("Heart", 24.dp, 24.dp, 16f, 16f).apply {
    addPath(pathData = PathData {
        moveTo(8f, 2.748f)
        lineToRelative(-0.717f, -0.737f)
        curveTo(5.6f, 0.281f, 2.514f, 0.878f, 1.4f, 3.053f)
        curveToRelative(-0.523f, 1.023f, -0.641f, 2.5f, 0.314f, 4.385f)
        curveToRelative(0.92f, 1.815f, 2.834f, 3.989f, 6.286f, 6.357f)
        curveToRelative(3.452f, -2.368f, 5.365f, -4.542f, 6.286f, -6.357f)
        curveToRelative(0.955f, -1.886f, 0.838f, -3.362f, 0.314f, -4.385f)
        curveTo(13.486f, 0.878f, 10.4f, 0.28f, 8.717f, 2.01f)
        close()
        moveTo(8f, 15f)
        curveTo(-7.333f, 4.868f, 3.279f, -3.04f, 7.824f, 1.143f)
        quadToRelative(0.09f, 0.083f, 0.176f, 0.171f)
        arcToRelative(3f, 3f, 0f, false, true, 0.176f, -0.17f)
        curveTo(12.72f, -3.042f, 23.333f, 4.867f, 8f, 15f)
    }, fill = SolidColor(Color.Transparent),
       stroke = SolidColor(Color.White), strokeLineWidth = 0.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()

// ── Heart filled (Bootstrap — Instagram red, universal "liked" colour) ─────────
val IconHeartFilled: ImageVector get() = ImageVector.Builder("HeartFilled", 24.dp, 24.dp, 16f, 16f).apply {
    addPath(pathData = PathData {
        moveTo(8f, 1.314f)
        curveTo(12.438f, -3.248f, 23.534f, 4.735f, 8f, 15f)
        curveTo(-7.534f, 4.736f, 3.562f, -3.248f, 8f, 1.314f)
    }, fill = SolidColor(Color(0xFFFF2D55)))
}.build()

// ── Home outline (Fluent UI — Microsoft design system, very clean) ─────────────
val IconHome: ImageVector get() = ImageVector.Builder("Home", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(10.5495f, 2.53189f)
        curveTo(11.3874f, 1.82531f, 12.6126f, 1.82531f, 13.4505f, 2.5319f)
        lineTo(20.2005f, 8.224f)
        curveTo(20.7074f, 8.65152f, 21f, 9.2809f, 21f, 9.94406f)
        lineTo(21f, 19.2539f)
        curveTo(21f, 20.2204f, 20.2165f, 21.0039f, 19.25f, 21.0039f)
        horizontalLineTo(15.75f)
        curveTo(14.7835f, 21.0039f, 14f, 20.2204f, 14f, 19.2539f)
        lineTo(14f, 14.2468f)
        curveTo(14f, 14.1088f, 13.8881f, 13.9968f, 13.75f, 13.9968f)
        horizontalLineTo(10.25f)
        curveTo(10.1119f, 13.9968f, 9.99999f, 14.1088f, 9.99999f, 14.2468f)
        lineTo(9.99999f, 19.2539f)
        curveTo(9.99999f, 20.2204f, 9.2165f, 21.0039f, 8.25f, 21.0039f)
        horizontalLineTo(4.75f)
        curveTo(3.7835f, 21.0039f, 3f, 20.2204f, 3f, 19.2539f)
        verticalLineTo(9.94406f)
        curveTo(3f, 9.2809f, 3.29255f, 8.65152f, 3.79952f, 8.224f)
        lineTo(10.5495f, 2.53189f)
        close()
        moveTo(12.4835f, 3.6786f)
        curveTo(12.2042f, 3.44307f, 11.7958f, 3.44307f, 11.5165f, 3.6786f)
        lineTo(4.76651f, 9.37071f)
        curveTo(4.59752f, 9.51321f, 4.5f, 9.72301f, 4.5f, 9.94406f)
        lineTo(4.5f, 19.2539f)
        curveTo(4.5f, 19.392f, 4.61193f, 19.5039f, 4.75f, 19.5039f)
        horizontalLineTo(8.25f)
        curveTo(8.38807f, 19.5039f, 8.49999f, 19.392f, 8.49999f, 19.2539f)
        lineTo(8.49999f, 14.2468f)
        curveTo(8.49999f, 13.2803f, 9.2835f, 12.4968f, 10.25f, 12.4968f)
        horizontalLineTo(13.75f)
        curveTo(14.7165f, 12.4968f, 15.5f, 13.2803f, 15.5f, 14.2468f)
        lineTo(15.5f, 19.2539f)
        curveTo(15.5f, 19.392f, 15.6119f, 19.5039f, 15.75f, 19.5039f)
        horizontalLineTo(19.25f)
        curveTo(19.3881f, 19.5039f, 19.5f, 19.392f, 19.5f, 19.2539f)
        lineTo(19.5f, 9.94406f)
        curveTo(19.5f, 9.72301f, 19.4025f, 9.51321f, 19.2335f, 9.37071f)
        lineTo(12.4835f, 3.6786f)
        close()
    }, fill = SolidColor(Color.White))
}.build()

// ── Home filled (Fluent UI filled variant) ────────────────────────────────────
val IconHomeFilled: ImageVector get() = ImageVector.Builder("HomeFilled", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(13.4508f, 2.53318f)
        curveTo(12.6128f, 1.82618f, 11.3872f, 1.82618f, 10.5492f, 2.53318f)
        lineTo(3.79916f, 8.22772f)
        curveTo(3.29241f, 8.65523f, 3f, 9.28447f, 3f, 9.94747f)
        verticalLineTo(19.2526f)
        curveTo(3f, 20.2191f, 3.7835f, 21.0026f, 4.75f, 21.0026f)
        horizontalLineTo(7.75f)
        curveTo(8.7165f, 21.0026f, 9.5f, 20.2191f, 9.5f, 19.2526f)
        verticalLineTo(15.25f)
        curveTo(9.5f, 14.5707f, 10.0418f, 14.018f, 10.7169f, 14.0004f)
        horizontalLineTo(13.2831f)
        curveTo(13.9582f, 14.018f, 14.5f, 14.5707f, 14.5f, 15.25f)
        verticalLineTo(19.2526f)
        curveTo(14.5f, 20.2191f, 15.2835f, 21.0026f, 16.25f, 21.0026f)
        horizontalLineTo(19.25f)
        curveTo(20.2165f, 21.0026f, 21f, 20.2191f, 21f, 19.2526f)
        verticalLineTo(9.94747f)
        curveTo(21f, 9.28447f, 20.7076f, 8.65523f, 20.2008f, 8.22772f)
        lineTo(13.4508f, 2.53318f)
        close()
    }, fill = SolidColor(Color.White))
}.build()

// ── Shorts / Reels (YouTube Shorts official shape)
// Outline: same icon at reduced stroke — no separate outline exists officially,
// so we use the filled shape at 70% opacity via White60 tint at call site.
// This is exactly what YouTube does on their own app. ─────────────────────────
val IconReel: ImageVector get() = ImageVector.Builder("Shorts", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(18.931f, 9.99f)
        lineToRelative(-1.441f, -0.601f)
        lineToRelative(1.717f, -0.913f)
        arcToRelative(4.48f, 4.48f, 0f, false, false, 1.874f, -6.078f)
        arcToRelative(4.506f, 4.506f, 0f, false, false, -6.09f, -1.874f)
        lineTo(4.792f, 5.929f)
        arcToRelative(4.504f, 4.504f, 0f, false, false, -2.402f, 4.193f)
        arcToRelative(4.521f, 4.521f, 0f, false, false, 2.666f, 3.904f)
        curveToRelative(0.036f, 0.012f, 1.442f, 0.6f, 1.442f, 0.6f)
        lineToRelative(-1.706f, 0.901f)
        arcToRelative(4.51f, 4.51f, 0f, false, false, -2.369f, 3.967f)
        arcTo(4.528f, 4.528f, 0f, false, false, 6.93f, 24f)
        curveToRelative(0.725f, 0f, 1.437f, -0.174f, 2.08f, -0.508f)
        lineToRelative(10.21f, -5.406f)
        arcToRelative(4.494f, 4.494f, 0f, false, false, 2.39f, -4.192f)
        arcToRelative(4.525f, 4.525f, 0f, false, false, -2.678f, -3.904f)
        close()
        moveTo(9.597f, 15.19f)
        verticalLineTo(8.824f)
        lineToRelative(6.007f, 3.184f)
        close()
    }, fill = SolidColor(Color.White))
}.build()

// Filled = same icon, full white — tint at call site makes it Brand blue when active
val IconReelFilled: ImageVector get() = IconReel

// ── Profile outline (FontAwesome — industry standard, universally recognised) ──
val IconUser: ImageVector get() = ImageVector.Builder("Profile", 24.dp, 24.dp, 448f, 512f).apply {
    addPath(pathData = PathData {
        moveTo(313.6f, 304f)
        curveToRelative(-28.7f, 0f, -42.5f, 16f, -89.6f, 16f)
        curveToRelative(-47.1f, 0f, -60.8f, -16f, -89.6f, -16f)
        curveTo(60.2f, 304f, 0f, 364.2f, 0f, 438.4f)
        verticalLineTo(464f)
        curveToRelative(0f, 26.5f, 21.5f, 48f, 48f, 48f)
        horizontalLineToRelative(352f)
        curveToRelative(26.5f, 0f, 48f, -21.5f, 48f, -48f)
        verticalLineToRelative(-25.6f)
        curveToRelative(0f, -74.2f, -60.2f, -134.4f, -134.4f, -134.4f)
        close()
        moveTo(400f, 464f)
        horizontalLineTo(48f)
        verticalLineToRelative(-25.6f)
        curveToRelative(0f, -47.6f, 38.8f, -86.4f, 86.4f, -86.4f)
        curveToRelative(14.6f, 0f, 38.3f, 16f, 89.6f, 16f)
        curveToRelative(51.7f, 0f, 74.9f, -16f, 89.6f, -16f)
        curveToRelative(47.6f, 0f, 86.4f, 38.8f, 86.4f, 86.4f)
        verticalLineTo(464f)
        close()
        moveTo(224f, 288f)
        curveTo(303.5f, 288f, 368f, 223.5f, 368f, 144f)
        reflectiveCurveTo(303.5f, 0f, 224f, 0f)
        reflectiveCurveTo(80f, 64.5f, 80f, 144f)
        reflectiveCurveToRelative(64.5f, 144f, 144f, 144f)
        close()
        moveTo(224f, 48f)
        curveToRelative(52.9f, 0f, 96f, 43.1f, 96f, 96f)
        reflectiveCurveToRelative(-43.1f, 96f, -96f, 96f)
        reflectiveCurveToRelative(-96f, -43.1f, -96f, -96f)
        reflectiveCurveToRelative(43.1f, -96f, 96f, -96f)
        close()
    }, fill = SolidColor(Color.White))
}.build()

// ── Profile filled (FontAwesome solid) ───────────────────────────────────────
val IconUserFilled: ImageVector get() = ImageVector.Builder("ProfileFilled", 24.dp, 24.dp, 448f, 512f).apply {
    addPath(pathData = PathData {
        moveTo(224f, 256f)
        curveTo(294.7f, 256f, 352f, 198.7f, 352f, 128f)
        reflectiveCurveTo(294.7f, 0f, 224f, 0f)
        reflectiveCurveTo(96f, 57.3f, 96f, 128f)
        reflectiveCurveToRelative(57.3f, 128f, 128f, 128f)
        close()
        moveTo(313.6f, 288f)
        horizontalLineToRelative(-16.7f)
        curveToRelative(-22.2f, 10.2f, -46.9f, 16f, -72.9f, 16f)
        reflectiveCurveToRelative(-50.6f, -5.8f, -72.9f, -16f)
        horizontalLineToRelative(-16.7f)
        curveTo(60.2f, 288f, 0f, 348.2f, 0f, 422.4f)
        verticalLineTo(464f)
        curveTo(0f, 490.5f, 21.5f, 512f, 48f, 512f)
        horizontalLineToRelative(352f)
        curveToRelative(26.5f, 0f, 48f, -21.5f, 48f, -48f)
        verticalLineToRelative(-41.6f)
        curveToRelative(0f, -74.2f, -60.2f, -134.4f, -134.4f, -134.4f)
        close()
    }, fill = SolidColor(Color.White))
}.build()

// ── Wi-Fi Off (Lucide — clean diagnostic icon) ────────────────────────────────
val IconWifiOff: ImageVector get() = ImageVector.Builder("WifiOff", 24.dp, 24.dp, 24f, 24f).apply {
    // Diagonal slash
    addPath(pathData = PathData { moveTo(2f, 2f); lineTo(22f, 22f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
        fill = SolidColor(Color.Transparent))
    // Arcs clipped by slash
    addPath(pathData = PathData {
        moveTo(8.5f, 16.5f)
        arcToRelative(5f, 5f, 0f, false, true, 7f, 0f)
        moveTo(5f, 12.5f)
        arcToRelative(9f, 9f, 0f, false, true, 5.2f, -2.5f)
        moveTo(14.4f, 9.9f)
        arcToRelative(9f, 9f, 0f, false, true, 4.6f, 2.6f)
        moveTo(2f, 8.82f)
        arcToRelative(15f, 15f, 0f, false, true, 4.49f, -2.33f)
        moveTo(12f, 20f)
        lineTo(12f, 20.01f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

// ── Movie Slate (Lucide clapperboard — clean film icon) ───────────────────────
val IconMovieSlate: ImageVector get() = ImageVector.Builder("MovieSlate", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(20f, 20f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
        horizontalLineTo(6f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        verticalLineTo(4f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
        horizontalLineTo(18f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
        close()
        moveTo(4f, 8f)
        lineTo(20f, 8f)
        moveTo(8f, 2f)
        lineTo(8f, 8f)
        moveTo(12f, 2f)
        lineTo(12f, 8f)
        moveTo(16f, 2f)
        lineTo(16f, 8f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

// ── Downloads outline (Lucide — arrow into tray, clean and modern) ────────────
val IconDownloadCloud: ImageVector get() = ImageVector.Builder("Downloads", 24.dp, 24.dp, 24f, 24f).apply {
    // Tray base
    addPath(pathData = PathData {
        moveTo(21f, 15f)
        verticalLineTo(19f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
        horizontalLineTo(5f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        verticalLineTo(15f)
    }, fill = SolidColor(Color.Transparent),
       stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    // Arrow stem
    addPath(pathData = PathData { moveTo(12f, 3f); lineTo(12f, 15f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
    // Arrow head
    addPath(pathData = PathData { moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

// ── Downloads filled (filled tray + solid arrow) ──────────────────────────────
val IconDownloadCloudFilled: ImageVector get() = ImageVector.Builder("DownloadsFilled", 24.dp, 24.dp, 24f, 24f).apply {
    // Filled tray
    addPath(pathData = PathData {
        moveTo(2f, 14f)
        horizontalLineTo(22f)
        verticalLineTo(19f)
        arcToRelative(3f, 3f, 0f, false, true, -3f, 3f)
        horizontalLineTo(5f)
        arcToRelative(3f, 3f, 0f, false, true, -3f, -3f)
        close()
    }, fill = SolidColor(Color.White))
    // Arrow (on top of tray — app bg colour to punch through)
    addPath(pathData = PathData {
        moveTo(12f, 2f)
        lineTo(12f, 14f)
        moveTo(7f, 9f)
        lineTo(12f, 14f)
        lineTo(17f, 9f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 2f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

// ── Swap / Transfer (two arrows — used in transfer screen header) ──────────────
val IconSwap: ImageVector get() = ImageVector.Builder("Swap", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(7f, 16f); lineTo(3f, 12f); lineTo(7f, 8f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(17f, 8f); lineTo(21f, 12f); lineTo(17f, 16f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

// ── Explore outline (Tabler grid — universally means "browse") ────────────────
val IconCompass: ImageVector get() = ImageVector.Builder("Explore", 24.dp, 24.dp, 24f, 24f).apply {
    listOf(
        PathData { moveTo(4f,5f); arcToRelative(1f,1f,0f,false,true,1f,-1f); horizontalLineToRelative(4f); arcToRelative(1f,1f,0f,false,true,1f,1f); verticalLineToRelative(4f); arcToRelative(1f,1f,0f,false,true,-1f,1f); horizontalLineToRelative(-4f); arcToRelative(1f,1f,0f,false,true,-1f,-1f); close() },
        PathData { moveTo(14f,5f); arcToRelative(1f,1f,0f,false,true,1f,-1f); horizontalLineToRelative(4f); arcToRelative(1f,1f,0f,false,true,1f,1f); verticalLineToRelative(4f); arcToRelative(1f,1f,0f,false,true,-1f,1f); horizontalLineToRelative(-4f); arcToRelative(1f,1f,0f,false,true,-1f,-1f); close() },
        PathData { moveTo(4f,15f); arcToRelative(1f,1f,0f,false,true,1f,-1f); horizontalLineToRelative(4f); arcToRelative(1f,1f,0f,false,true,1f,1f); verticalLineToRelative(4f); arcToRelative(1f,1f,0f,false,true,-1f,1f); horizontalLineToRelative(-4f); arcToRelative(1f,1f,0f,false,true,-1f,-1f); close() },
        PathData { moveTo(14f,15f); arcToRelative(1f,1f,0f,false,true,1f,-1f); horizontalLineToRelative(4f); arcToRelative(1f,1f,0f,false,true,1f,1f); verticalLineToRelative(4f); arcToRelative(1f,1f,0f,false,true,-1f,1f); horizontalLineToRelative(-4f); arcToRelative(1f,1f,0f,false,true,-1f,-1f); close() },
    ).forEach { pd ->
        addPath(pathData = pd, fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

// ── Explore filled (Tabler grid filled) ──────────────────────────────────────
val IconCompassFilled: ImageVector get() = ImageVector.Builder("ExploreFilled", 24.dp, 24.dp, 24f, 24f).apply {
    listOf(
        PathData { moveTo(9f,3f); arcToRelative(2f,2f,0f,false,true,2f,2f); verticalLineToRelative(4f); arcToRelative(2f,2f,0f,false,true,-2f,2f); horizontalLineToRelative(-4f); arcToRelative(2f,2f,0f,false,true,-2f,-2f); verticalLineToRelative(-4f); arcToRelative(2f,2f,0f,false,true,2f,-2f); close() },
        PathData { moveTo(19f,3f); arcToRelative(2f,2f,0f,false,true,2f,2f); verticalLineToRelative(4f); arcToRelative(2f,2f,0f,false,true,-2f,2f); horizontalLineToRelative(-4f); arcToRelative(2f,2f,0f,false,true,-2f,-2f); verticalLineToRelative(-4f); arcToRelative(2f,2f,0f,false,true,2f,-2f); close() },
        PathData { moveTo(9f,13f); arcToRelative(2f,2f,0f,false,true,2f,2f); verticalLineToRelative(4f); arcToRelative(2f,2f,0f,false,true,-2f,2f); horizontalLineToRelative(-4f); arcToRelative(2f,2f,0f,false,true,-2f,-2f); verticalLineToRelative(-4f); arcToRelative(2f,2f,0f,false,true,2f,-2f); close() },
        PathData { moveTo(19f,13f); arcToRelative(2f,2f,0f,false,true,2f,2f); verticalLineToRelative(4f); arcToRelative(2f,2f,0f,false,true,-2f,2f); horizontalLineToRelative(-4f); arcToRelative(2f,2f,0f,false,true,-2f,-2f); verticalLineToRelative(-4f); arcToRelative(2f,2f,0f,false,true,2f,-2f); close() },
    ).forEach { pd -> addPath(pathData = pd, fill = SolidColor(Color.White)) }
}.build()

// ── Play circle (used on movie cards) ─────────────────────────────────────────
val IconPlayCircle: ImageVector get() = ImageVector.Builder("PlayCircle", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f)
        arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
    }, fill = SolidColor(Color.Transparent),
       stroke = SolidColor(Color.White), strokeLineWidth = 1.6f)
    addPath(pathData = PathData { moveTo(10f, 8f); lineTo(16f, 12f); lineTo(10f, 16f); close() },
        fill = SolidColor(Color.White))
}.build()

// ── Filter / funnel (Lucide) ──────────────────────────────────────────────────
val IconFilter: ImageVector get() = ImageVector.Builder("Filter", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(22f, 3f); horizontalLineTo(2f); lineTo(10f, 12.46f); verticalLineTo(19f)
        lineTo(14f, 21f); verticalLineTo(12.46f); close()
    }, fill = SolidColor(Color.Transparent),
       stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()

// ── History / recent (clock) — Lucide-style ───────────────────────────────────
val IconHistory: ImageVector get() = ImageVector.Builder("History", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        // Clock face
        moveTo(21f, 12f)
        curveTo(21f, 16.9706f, 16.9706f, 21f, 12f, 21f)
        curveTo(7.02944f, 21f, 3f, 16.9706f, 3f, 12f)
        curveTo(3f, 7.02944f, 7.02944f, 3f, 12f, 3f)
        curveTo(16.9706f, 3f, 21f, 7.02944f, 21f, 12f)
        close()
    }, fill = SolidColor(Color.Transparent),
       stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    addPath(pathData = PathData {
        // Clock hands, pointing to a "past" position to read as "recent/history"
        moveTo(12f, 7f); verticalLineTo(12f); lineTo(15f, 14f)
    }, fill = SolidColor(Color.Transparent),
       stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()

// ── Chevron down (Lucide) ─────────────────────────────────────────────────────
val IconChevronDown: ImageVector get() = ImageVector.Builder("ChevDown", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

// ─────────────────────────────────────────────────────────────────────────────
// Glass card container
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = LocalDimensions.current.radiusLg,
    borderColor: Color = GlassBorderMd,
    content: @Composable () -> Unit,
) {
    val d = LocalDimensions.current
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
    color: Color = Brand,
) {
    val d = LocalDimensions.current
    val shimmer = rememberInfiniteTransition(label = "btnShimmer")
    val shimmerX by shimmer.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "sx"
    )
    val colorDeep   = color.copy(alpha = color.alpha * 0.5f)
    val colorBright = color.copy(alpha = minOf(color.alpha * 1.15f, 1f))

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusPill))
            .background(
                if (enabled) {
                    Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to colorDeep,
                            0.4f to color,
                            shimmerX to colorBright.copy(alpha = 0.9f),
                            1f to color,
                        )
                    )
                } else {
                    Brush.linearGradient(listOf(GlassMd, GlassMd))
                }
            )
            .border(d.borderThin, if (enabled) colorBright.copy(.4f) else GlassBorderMd, RoundedCornerShape(d.radiusPill))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = if (small) d.buttonHorizPadSm else d.buttonHorizPadMd,
                vertical   = if (small) d.buttonVertPadSm  else d.buttonVertPadMd,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(d.spaceSm))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge.copy(
                    color         = if (enabled) Color.White else White40,
                    fontWeight    = FontWeight.ExtraBold,
                    fontSize      = if (small) d.textSm else d.textMd,
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
    val d = LocalDimensions.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusPill))
            .background(GlassMd)
            .border(d.borderThin, GlassBorderHv, RoundedCornerShape(d.radiusPill))
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (small) d.buttonHorizPadSm else d.buttonHorizPadMd,
                vertical   = if (small) d.buttonVertPadSm  else d.buttonVertPadMd,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(d.spaceSm))
            Text(
                text,
                color      = White80,
                fontWeight = FontWeight.SemiBold,
                fontSize   = if (small) d.textSm else d.textMd,
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
    val d = LocalDimensions.current
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
                shape = RoundedCornerShape(d.radiusMd)
                clip = false
                cameraDistance = 8f * density
            }
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
                .clip(RoundedCornerShape(d.radiusMd))
                .border(d.borderThin, if (pressed) BlueBorder else GlassBorder, RoundedCornerShape(d.radiusMd))
                .background(BgRaised)
        ) {
            AsyncImage(
                model = BuildConfig.TMDB_IMG_W342 + media.posterPath,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC05050A))
            ))
            if (pressed) {
                Box(Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Brand.copy(0.15f), Color.Transparent))
                ))
            }
            if (media.voteAverage > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(d.ratingBadgePad)
                        .clip(RoundedCornerShape(d.radiusSm))
                        .background(Color(0xCC000000))
                        .border(d.borderThin, BlueBorder, RoundedCornerShape(d.radiusSm))
                        .padding(horizontal = d.spaceXs, vertical = d.spaceXxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconStar, null, tint = Gold, modifier = Modifier.size(d.ratingIconSize))
                    Spacer(Modifier.width(d.spaceXxs))
                    Text("${"%.1f".format(media.voteAverage)}", color = Gold, fontSize = d.ratingFontSize, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(d.spaceSm))
        Text(
            media.title,
            color      = White80,
            fontSize   = d.textXs,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
            lineHeight = (d.textXs.value * 1.4f).sp,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.padding(horizontal = d.spaceXxs),
        )
        if (media.mediaType == MediaType.TV) {
            Spacer(Modifier.height(d.spaceXxs))
            Text(
                "TV Series",
                color         = Brand,
                fontSize      = d.textXxs,
                fontWeight    = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier      = Modifier.padding(horizontal = d.spaceXxs),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Media row card (horizontal list)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MediaRowCard(media: Media, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "rowScale"
    )

    Column(
        modifier = modifier
            .width(d.cardRowWidth)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                cameraDistance = 8f * density
            }
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
                .height(d.cardRowHeight)
                .clip(RoundedCornerShape(d.radiusMd))
                .border(d.borderThin, if (pressed) BlueBorder else GlassBorder, RoundedCornerShape(d.radiusMd))
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
                        .padding(d.spaceSm)
                        .clip(RoundedCornerShape(d.radiusSm))
                        .background(Color(0xBB000000))
                        .padding(horizontal = d.spaceXs, vertical = d.spaceXxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconStar, null, tint = Gold, modifier = Modifier.size(d.ratingIconSize))
                    Spacer(Modifier.width(d.spaceXxs))
                    Text("${"%.1f".format(media.voteAverage)}", color = Gold, fontSize = d.ratingFontSize, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(d.spaceSm))
        Text(
            media.title,
            color      = White80,
            fontSize   = d.textSm,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
            lineHeight = (d.textSm.value * 1.35f).sp,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.padding(horizontal = d.spaceXxs),
        )
        if (media.mediaType == MediaType.TV) {
            Spacer(Modifier.height(d.spaceXxs))
            Text(
                "TV Series",
                color         = Brand,
                fontSize      = d.textXxs,
                fontWeight    = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier      = Modifier.padding(horizontal = d.spaceXxs),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section header row — accent bar + title + optional "See All"
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    Row(
        modifier = modifier.fillMaxWidth().padding(
            start  = d.screenHorizPad,
            top    = d.spaceXl,
            bottom = d.spaceMd,
            end    = d.screenHorizPad,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(d.sectionAccentWidth)
                .height(d.sectionAccentHeight)
                .clip(RoundedCornerShape(d.spaceXxs))
                .background(Brush.verticalGradient(listOf(Brand2, Brand)))
        )
        Spacer(Modifier.width(d.spaceMd - d.spaceXs))
        Text(
            title,
            color         = White,
            fontWeight    = FontWeight.Bold,
            fontSize      = d.textXl,
            letterSpacing = (-0.2).sp,
        )
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(d.radiusSm))
                    .clickable(onClick = onAction)
                    .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceXxs),
            ) {
                Text(action, color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Skeleton shimmer loading — replaces spinner
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SkeletonBannerLoader() {
    val d = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val inf = rememberInfiniteTransition(label = "skBanner")
    val offset by inf.animateFloat(
        -1.5f, 2.5f, infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing)), "skOff"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(screenH * d.heroImageRatio)
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
        Column(Modifier.align(Alignment.BottomStart).padding(d.heroPadding), verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXs)) {
            Box(Modifier.fillMaxWidth(0.22f).height(d.spaceLg - d.spaceXxs).clip(RoundedCornerShape(d.spaceXs)).background(BgRaised))
            Box(Modifier.fillMaxWidth(0.7f).height(d.textHero.value.dp + 4.dp).clip(RoundedCornerShape(d.spaceSm)).background(BgRaised))
            Box(Modifier.fillMaxWidth(0.45f).height(d.spaceMd).clip(RoundedCornerShape(d.spaceXs)).background(BgRaised))
            Row(horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                Box(Modifier.fillMaxWidth(0.38f).height(d.buttonHeightMd).clip(RoundedCornerShape(d.radiusPill)).background(BgSurface))
                Box(Modifier.fillMaxWidth(0.30f).height(d.buttonHeightMd).clip(RoundedCornerShape(d.radiusPill)).background(BgRaised))
            }
        }
    }
}

@Composable
fun SkeletonRowLoader() {
    val d = LocalDimensions.current
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
        Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad),
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        repeat(4) {
            Column(Modifier.width(d.cardRowWidth), verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                Box(Modifier.width(d.cardRowWidth).height(d.cardRowHeight).clip(RoundedCornerShape(d.radiusMd)).background(shimmerBrush))
                Box(Modifier.fillMaxWidth(0.88f).height(d.spaceSm + d.spaceXxs).clip(RoundedCornerShape(d.spaceXs)).background(shimmerBrush))
                Box(Modifier.fillMaxWidth(0.65f).height(d.spaceSm).clip(RoundedCornerShape(d.spaceXs)).background(shimmerBrush))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Premium cinematic loading spinner (kept for reuse)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun FullScreenLoader() {
    val d = LocalDimensions.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CinematicSpinner(size = d.spinnerLg)
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
fun SmallSpinner(modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    CinematicSpinner(size = d.spinnerMd, modifier = modifier)
}

@Composable
fun PulsingDot(modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    val inf = rememberInfiniteTransition(label = "dot")
    val scale by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "sc")
    val glow  by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "gl")
    Box(modifier = modifier.size(d.spaceXs + d.spaceXxs), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().scale(scale + 0.4f).clip(CircleShape).background(Brand.copy(glow * 0.3f)))
        Box(Modifier.fillMaxSize(0.7f).scale(scale).clip(CircleShape).background(Brand))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error state
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    Column(
        modifier = modifier.fillMaxSize().padding(d.spaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(d.avatarLg + d.spaceLg).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Error.copy(.15f), Color.Transparent)))
                .border(d.borderThin, Error.copy(.35f), CircleShape))
            Icon(IconWifiOff, null, tint = Error.copy(.8f), modifier = Modifier.size(d.iconLg))
        }
        Spacer(Modifier.height(d.spaceXl))
        Text(friendlyError(message), color = White60, fontSize = d.textLg,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = (d.textLg.value * 1.55f).sp)
        if (onRetry != null) {
            Spacer(Modifier.height(d.spaceXxl))
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
    val d = LocalDimensions.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusSm))
            .background(Color(0x22FFD700))
            .border(d.borderThin, Gold.copy(.3f), RoundedCornerShape(d.radiusSm))
            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IconStar, null, tint = Gold, modifier = Modifier.size(d.iconSm))
        Spacer(Modifier.width(d.spaceXs))
        Text("${"%.1f".format(rating)}", color = Gold, fontSize = d.textMd, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Genre pill — with blue active state
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GenrePill(text: String, selected: Boolean = false, onClick: () -> Unit = {}) {
    val d = LocalDimensions.current
    val bgBrush = if (selected)
        Brush.horizontalGradient(listOf(BrandDeep, Brand.copy(.9f)))
    else
        Brush.horizontalGradient(listOf(BgRaised, BgSurface))

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(d.radiusPill))
            .background(bgBrush)
            .border(d.borderThin, if (selected) Brand.copy(.6f) else GlassBorderMd, RoundedCornerShape(d.radiusPill))
            .clickable(onClick = onClick)
            .padding(horizontal = d.chipHorizPad + d.spaceXs, vertical = d.chipVertPad + d.spaceXs),
    ) {
        Text(
            text,
            color      = if (selected) Color.White else White60,
            fontSize   = d.textSm,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Drag handle for bottom sheets
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    Box(modifier = modifier.fillMaxWidth().padding(top = d.spaceMd), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(d.shimmerBarWidth)
                .height(d.shimmerBarHeight)
                .clip(RoundedCornerShape(d.spaceXxs))
                .background(Brush.horizontalGradient(listOf(Brand.copy(.4f), Brand2.copy(.4f), Brand.copy(.4f))))
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shimmer skeleton card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ShimmerCard(modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    val inf = rememberInfiniteTransition(label = "shimmer")
    val offset by inf.animateFloat(
        -1f, 2f, infiniteRepeatable(tween(1200, easing = LinearEasing)), "off"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusMd))
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
    val d = LocalDimensions.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(d.buttonHeightMd + d.spaceMd)
            .background(BgCard)
            .border(BorderStroke(d.borderThin, GlassBorderMd)),
        contentAlignment = Alignment.Center,
    ) {
        Text("Advertisement", color = White20, fontSize = d.textXxs, letterSpacing = 2.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// "Remove ads — go Premium" feed banner
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RemoveAdsBanner(
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    val shimmer = rememberInfiniteTransition(label = "removeAdsShimmer")
    val shimmerX by shimmer.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "rax"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad, vertical = d.spaceSm)
            .clip(RoundedCornerShape(d.radiusLg))
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f       to BrandDeep,
                        0.45f    to Brand.copy(alpha = 0.9f),
                        shimmerX to Brand2.copy(alpha = 0.55f),
                        1f       to BrandDeep,
                    )
                )
            )
            .border(d.borderThin, Brand2.copy(.35f), RoundedCornerShape(d.radiusLg))
            .padding(vertical = d.spaceMd + d.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { onUpgrade() }
                .padding(start = d.screenHorizPad, end = d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✦", fontSize = d.textXl)
            Spacer(Modifier.width(d.spaceMd))
            Column {
                Text(
                    "Remove ads",
                    color      = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize   = (d.textMd.value + 1).sp,
                )
                Text(
                    "Go Premium for an uninterrupted, ad-free experience",
                    color    = Color.White.copy(alpha = 0.8f),
                    fontSize = d.textXs,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.padding(end = d.spaceMd).size(d.buttonHeightSm - d.spaceSm)) {
            Icon(Icons.Filled.Close, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(d.iconSm + 2.dp))
        }
    }
}
