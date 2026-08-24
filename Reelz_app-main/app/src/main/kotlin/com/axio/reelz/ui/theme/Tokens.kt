package com.axio.reelz.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Deep cinematic base surfaces ─────────────────────────────────────────────
val Bg        = Color(0xFF05050A)   // near-black void
val BgCard    = Color(0xFF0B0B12)   // card base
val BgRaised  = Color(0xFF111119)   // raised surface
val BgSurface = Color(0xFF18181F)   // interactive surface
val BgOverlay = Color(0xFF1E1E28)   // top-most overlay

// Legacy aliases
val Surface900 = Bg
val Surface800 = BgCard
val Surface700 = BgRaised
val Surface600 = BgSurface

// ── Brand — Electric Blue (was gold/amber) ─────────────────────────────────
val Brand      = Color(0xFF0A84FF)   // electric blue primary
val Brand2     = Color(0xFF40A8FF)   // bright blue highlight
val BrandDeep  = Color(0xFF003780)   // deep blue shadow
val BrandDim   = Color(0xFF001840)   // darkest blue tint
val Primary    = Brand

// ── Accent palette ────────────────────────────────────────────────────────────
val Like       = Color(0xFFFF2D55)   // vivid red-rose love
val Teal       = Color(0xFF00E5CC)   // electric teal
val Violet     = Color(0xFF9B5CF6)   // deep violet
val Gold       = Color(0xFFFFCC44)   // pure gold for ratings
val Iris       = Color(0xFF5B7FFF)   // indigo iris

// ── Glass system ──────────────────────────────────────────────────────────────
val Glass         = Color(0x0DFFFFFF)
val GlassSm       = Color(0x08FFFFFF)
val GlassMd       = Color(0x14FFFFFF)
val GlassHeavy    = Color(0x26FFFFFF)
val GlassBorder   = Color(0x0FFFFFFF)
val GlassBorderMd = Color(0x1AFFFFFF)
val GlassBorderHv = Color(0x33FFFFFF)

// ── Blue glass (brand-tinted glass) ──────────────────────────────────────────
val AmberGlass    = Color(0x1A0A84FF)   // was amber — now electric blue tint
val AmberBorder   = Color(0x330A84FF)   // was amber border — now blue

// Blue-specific glass
val BlueGlass     = Color(0x1A0A84FF)
val BlueBorder    = Color(0x330A84FF)
val BlueGlow      = Color(0x4D0A84FF)

// ── Text ──────────────────────────────────────────────────────────────────────
val White    = Color(0xFFF4F6FF)   // cool white (fits blue theme)
val White90  = Color(0xE6F4F6FF)
val White80  = Color(0xCCF4F6FF)
val White60  = Color(0x99F4F6FF)
val White40  = Color(0x66F4F6FF)
val White20  = Color(0x33F4F6FF)
val White10  = Color(0x1AF4F6FF)
val TextPrimary   = White
val TextSecondary = White80
val TextTertiary  = White60
val Stroke    = GlassBorderMd

// ── Status ────────────────────────────────────────────────────────────────────
val Success   = Color(0xFF2DD36F)
val Warning   = Color(0xFFFF9A00)
val Error     = Color(0xFFFF3B30)

// ── Typography ────────────────────────────────────────────────────────────────
val ReelzTypography = androidx.compose.material3.Typography(
    displayLarge  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black,   fontSize = 48.sp, letterSpacing = (-2).sp,   color = White),
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black,   fontSize = 36.sp, letterSpacing = (-1.5).sp, color = White),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,    fontSize = 28.sp, letterSpacing = (-0.5).sp, color = White),
    headlineMedium= TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,    fontSize = 22.sp, letterSpacing = (-0.3).sp, color = White),
    titleLarge    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,    fontSize = 18.sp, color = White),
    titleMedium   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,fontSize = 15.sp, color = White),
    titleSmall    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,fontSize = 13.sp, color = White),
    bodyLarge     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,  fontSize = 15.sp, lineHeight = 23.sp, color = White80),
    bodyMedium    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,  fontSize = 13.sp, lineHeight = 20.sp, color = White80),
    bodySmall     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,  fontSize = 11.sp, lineHeight = 16.sp, color = White60),
    labelLarge    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,fontSize = 13.sp, letterSpacing = (0.8).sp, color = White60),
    labelMedium   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,fontSize = 11.sp, letterSpacing = (0.6).sp, color = White60),
    labelSmall    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,fontSize = 10.sp, letterSpacing = (0.5).sp, color = White40),
)
