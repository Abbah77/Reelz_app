package com.reelz.ui.theme

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

// ── Brand — warm cinematic amber/gold primary ─────────────────────────────────
val Brand      = Color(0xFFE8A020)   // rich amber gold
val Brand2     = Color(0xFFFFCC55)   // bright gold highlight
val BrandDeep  = Color(0xFF7A4A00)   // deep amber shadow
val BrandDim   = Color(0xFF4A2F00)   // darkest amber tint
val Primary    = Brand

// ── Accent palette ────────────────────────────────────────────────────────────
val Like       = Color(0xFFFF3D6E)   // vivid coral-rose
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

// ── Amber glass (brand-tinted glass) ─────────────────────────────────────────
val AmberGlass    = Color(0x1AE8A020)
val AmberBorder   = Color(0x33E8A020)

// ── Text ──────────────────────────────────────────────────────────────────────
val White    = Color(0xFFF8F4EE)   // warm white
val White90  = Color(0xE6F8F4EE)
val White80  = Color(0xCCF8F4EE)
val White60  = Color(0x99F8F4EE)
val White40  = Color(0x66F8F4EE)
val White20  = Color(0x33F8F4EE)
val White10  = Color(0x1AF8F4EE)
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
