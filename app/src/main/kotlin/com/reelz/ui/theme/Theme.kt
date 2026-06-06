package com.reelz.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ReelzColors = darkColorScheme(
    primary          = Brand,
    onPrimary        = White,
    primaryContainer = BrandDeep,
    secondary        = Brand2,
    onSecondary      = White,
    background       = Bg,
    onBackground     = White,
    surface          = BgCard,
    onSurface        = White,
    surfaceVariant   = BgRaised,
    onSurfaceVariant = White60,
    error            = Error,
    onError          = White,
    outline          = GlassBorderMd,
)

@Composable
fun ReelzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ReelzColors,
        typography  = ReelzTypography,
        content     = content,
    )
}
