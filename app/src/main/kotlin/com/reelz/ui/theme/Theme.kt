package com.reelz.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Black         = Color(0xFF000000)
val Surface900    = Color(0xFF080810)
val Surface800    = Color(0xFF0F0F1A)
val Surface700    = Color(0xFF181828)
val Surface600    = Color(0xFF222235)
val Stroke        = Color(0xFF2E2E45)
val Primary       = Color(0xFFE50914)
val PrimaryDark   = Color(0xFFB20710)
val PrimaryLight  = Color(0xFFFF4F59)
val Accent        = Color(0xFFFF6B00)
val Gold          = Color(0xFFFFCC00)
val White         = Color(0xFFFFFFFF)
val White90       = Color(0xE6FFFFFF)
val White80       = Color(0xCCFFFFFF)
val White60       = Color(0x99FFFFFF)
val White40       = Color(0x66FFFFFF)
val White20       = Color(0x33FFFFFF)
val White10       = Color(0x1AFFFFFF)
val Success       = Color(0xFF00C853)
val Error         = Color(0xFFFF1744)

val DarkColorScheme = darkColorScheme(
    primary          = Primary,
    onPrimary        = White,
    primaryContainer = PrimaryDark,
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
fun ReelzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = ReelzTypography,
        content     = content,
    )
}
