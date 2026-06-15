package com.reelz.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Colors ────────────────────────────────────────────────────────────────────
val Bg        = Color(0xFF05050A)
val BgCard    = Color(0xFF0B0B12)
val BgRaised  = Color(0xFF111119)
val BgSurface = Color(0xFF18181F)

val Brand     = Color(0xFF0A84FF)
val Brand2    = Color(0xFF40A8FF)
val BrandDim  = Color(0xFF001840)

val Gold      = Color(0xFFFFCC44)
val Like      = Color(0xFFFF2D55)
val Teal      = Color(0xFF00E5CC)

val Glass         = Color(0x0DFFFFFF)
val GlassBorder   = Color(0x0FFFFFFF)
val GlassBorderMd = Color(0x1AFFFFFF)

val White    = Color(0xFFF4F6FF)
val White90  = Color(0xE6F4F6FF)
val White80  = Color(0xCCF4F6FF)
val White60  = Color(0x99F4F6FF)
val White40  = Color(0x66F4F6FF)

val Error    = Color(0xFFFF3B30)
val Success  = Color(0xFF2DD36F)

// ── Typography ────────────────────────────────────────────────────────────────
val ReelzTypography = Typography(
    displayLarge  = TextStyle(fontWeight = FontWeight.Black,    fontSize = 48.sp, letterSpacing = (-2).sp,   color = White),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 28.sp, letterSpacing = (-0.5).sp, color = White),
    headlineMedium= TextStyle(fontWeight = FontWeight.Bold,     fontSize = 22.sp, letterSpacing = (-0.3).sp, color = White),
    titleLarge    = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 18.sp, color = White),
    titleMedium   = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = White),
    titleSmall    = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = White),
    bodyLarge     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 15.sp, lineHeight = 23.sp, color = White80),
    bodyMedium    = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 13.sp, lineHeight = 20.sp, color = White80),
    bodySmall     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 11.sp, lineHeight = 16.sp, color = White60),
    labelLarge    = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.8.sp, color = White60),
    labelSmall    = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.5.sp, color = White40),
)

// ── Theme ─────────────────────────────────────────────────────────────────────
private val ReelzColorScheme = darkColorScheme(
    primary          = Brand,
    onPrimary        = Color(0xFF1A0F00),
    primaryContainer = BrandDim,
    secondary        = Teal,
    background       = Bg,
    surface          = BgCard,
    surfaceVariant   = BgRaised,
    onBackground     = White,
    onSurface        = White,
    outline          = GlassBorderMd,
    error            = Error,
)

@Composable
fun ReelzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ReelzColorScheme,
        typography  = ReelzTypography,
        content     = content,
    )
}
