package com.reelz.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Base surfaces (matches Flutter tokens.dart) ───────────────────────────────
val Bg        = Color(0xFF07070B)
val BgCard    = Color(0xFF0E0E14)
val BgRaised  = Color(0xFF14141C)
val BgSurface = Color(0xFF1C1C26)

// Legacy aliases used in old screens
val Surface900 = Bg
val Surface800 = BgCard
val Surface700 = BgRaised
val Surface600 = BgSurface

// ── Brand ─────────────────────────────────────────────────────────────────────
val Brand      = Color(0xFF2196F3)   // electric blue
val Brand2     = Color(0xFF64B5F6)   // sky blue
val BrandDeep  = Color(0xFF0D47A1)   // deep navy
val Primary    = Brand

// ── Accent ────────────────────────────────────────────────────────────────────
val Like       = Color(0xFFFF4F7B)
val Gold       = Color(0xFFFFBB38)
val Teal       = Color(0xFF00D9B8)

// ── Glass system ──────────────────────────────────────────────────────────────
val Glass         = Color(0x0CFFFFFF)
val GlassSm       = Color(0x08FFFFFF)
val GlassMd       = Color(0x18FFFFFF)
val GlassHeavy    = Color(0x2AFFFFFF)
val GlassBorder   = Color(0x10FFFFFF)
val GlassBorderMd = Color(0x20FFFFFF)
val GlassBorderHv = Color(0x35FFFFFF)

// ── Text ──────────────────────────────────────────────────────────────────────
val White    = Color(0xFFF5F5FF)
val White80  = Color(0xCCF5F5FF)
val White60  = Color(0x99F5F5FF)
val White40  = Color(0x66F5F5FF)
val White20  = Color(0x33F5F5FF)
val TextPrimary   = White
val TextSecondary = White80
val TextTertiary  = White60
val Stroke    = GlassBorderMd

// ── Status colors ─────────────────────────────────────────────────────────────
val Success   = Color(0xFF4CAF50)
val Warning   = Color(0xFFFF9800)
val Error     = Color(0xFFEF5350)

// ── Typography helpers (system fonts — no external font dependency needed) ────
val ReelzTypography = androidx.compose.material3.Typography(
    displayLarge  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black,   fontSize = 48.sp, letterSpacing = (-1).sp, color = White),
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black,   fontSize = 36.sp, letterSpacing = (-0.5).sp, color = White),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,    fontSize = 28.sp, color = White),
    headlineMedium= TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,    fontSize = 22.sp, color = White),
    titleLarge    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,    fontSize = 18.sp, color = White),
    titleMedium   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,fontSize = 15.sp, color = White),
    titleSmall    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,fontSize = 13.sp, color = White),
    bodyLarge     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,  fontSize = 15.sp, lineHeight = 22.sp, color = White80),
    bodyMedium    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,  fontSize = 13.sp, lineHeight = 19.sp, color = White80),
    bodySmall     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,  fontSize = 11.sp, lineHeight = 16.sp, color = White60),
    labelLarge    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,fontSize = 13.sp, letterSpacing = (0.5).sp, color = White60),
    labelMedium   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,fontSize = 11.sp, letterSpacing = (0.4).sp, color = White60),
    labelSmall    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,fontSize = 10.sp, letterSpacing = (0.3).sp, color = White40),
)
