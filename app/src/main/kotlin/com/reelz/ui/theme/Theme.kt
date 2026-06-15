package com.reelz.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ReelzColorScheme = darkColorScheme(
    primary          = Brand,
    onPrimary        = Color(0xFF1A0F00),
    primaryContainer = BrandDim,
    secondary        = Teal,
    tertiary         = Violet,
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
