package com.streamapp.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Palette ───────────────────────────────────────────────────────────────────
val Black         = Color(0xFF000000)
val Surface900    = Color(0xFF0A0A0F)
val Surface800    = Color(0xFF12121A)
val Surface700    = Color(0xFF1C1C28)
val Surface600    = Color(0xFF252535)
val Stroke        = Color(0xFF2A2A3D)
val Primary       = Color(0xFF6C5CE7)
val PrimaryLight  = Color(0xFF9B8FF5)
val Accent        = Color(0xFFE84393)
val AccentOrange  = Color(0xFFFF6B35)
val Gold          = Color(0xFFFFD700)
val White         = Color(0xFFFFFFFF)
val White80       = Color(0xCCFFFFFF)
val White60       = Color(0x99FFFFFF)
val White40       = Color(0x66FFFFFF)
val White20       = Color(0x33FFFFFF)
val White10       = Color(0x1AFFFFFF)
val Success       = Color(0xFF00D68F)
val Error         = Color(0xFFFF4757)

val DarkColorScheme = darkColorScheme(
    primary          = Primary,
    onPrimary        = White,
    primaryContainer = Color(0xFF3D3480),
    secondary        = Accent,
    onSecondary      = White,
    background       = Surface900,
    onBackground     = White,
    surface          = Surface800,
    onSurface        = White,
    surfaceVariant   = Surface700,
    onSurfaceVariant = White60,
    outline          = Stroke,
    error            = Error,
)

@Composable
fun StreamAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content,
    )
}
