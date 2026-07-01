package com.axio.reelz.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// ReelzDimensions — adaptive layout tokens
//
// Every dp/sp value that would otherwise be hard-coded lives here.
// They scale proportionally based on the screen's shortest side (sw).
//
//   Compact  sw < 360  →  small/cheap phones (Itel, Nokia 2-series, Galaxy A03)
//   Normal   360–399   →  most mid-range phones (Galaxy A-series, most Xiaomi)
//   Large    400–599   →  big/flagship phones (Galaxy S Ultra, Pixel 7 Pro)
//   Tablet   ≥ 600     →  7-10" tablets
//
// Usage: val d = LocalDimensions.current
//        Modifier.size(d.iconMd)
// ─────────────────────────────────────────────────────────────────────────────

data class ReelzDimensions(
    // ── Screen class ──────────────────────────────────────────────────────────
    val isTablet: Boolean,
    val isCompact: Boolean,

    // ── Icon sizes ────────────────────────────────────────────────────────────
    val iconXs:   Dp,   // tiny inline icon (rating star)
    val iconSm:   Dp,   // small icon (badge, pill icon)
    val iconMd:   Dp,   // standard nav / action icon
    val iconLg:   Dp,   // prominent action icon
    val iconXl:   Dp,   // hero / fab icon

    // ── Spacing ───────────────────────────────────────────────────────────────
    val spaceXxs: Dp,
    val spaceXs:  Dp,
    val spaceSm:  Dp,
    val spaceMd:  Dp,
    val spaceLg:  Dp,
    val spaceXl:  Dp,
    val spaceXxl: Dp,

    // ── Corner radii ──────────────────────────────────────────────────────────
    val radiusSm:  Dp,
    val radiusMd:  Dp,
    val radiusLg:  Dp,
    val radiusPill: Dp,

    // ── Card dimensions ───────────────────────────────────────────────────────
    val cardPosterWidth:   Dp,
    val cardPosterHeight:  Dp,
    val cardRowWidth:      Dp,
    val cardRowHeight:     Dp,
    val continueCardWidth: Dp,
    val continueCardThumbHeight: Dp,

    // ── Typography (sp) ───────────────────────────────────────────────────────
    val textXxs:  TextUnit,
    val textXs:   TextUnit,
    val textSm:   TextUnit,
    val textMd:   TextUnit,
    val textLg:   TextUnit,
    val textXl:   TextUnit,
    val textXxl:  TextUnit,
    val textHero: TextUnit,

    // ── Component sizing ──────────────────────────────────────────────────────
    val buttonHeightSm: Dp,
    val buttonHeightMd: Dp,
    val buttonHorizPadSm: Dp,
    val buttonHorizPadMd: Dp,
    val buttonVertPadSm: Dp,
    val buttonVertPadMd: Dp,

    val chipHorizPad: Dp,
    val chipVertPad:  Dp,

    val avatarSm: Dp,
    val avatarMd: Dp,
    val avatarLg: Dp,

    val navIconSize: Dp,
    val navFontSize: TextUnit,

    val appBarHorizPad: Dp,
    val appBarVertPad:  Dp,

    val screenHorizPad: Dp,
    val sectionVertPad: Dp,

    val heroImageRatio: Float,   // height = screenH * ratio
    val heroPadding: Dp,

    val shimmerBarWidth: Dp,
    val shimmerBarHeight: Dp,

    val spinnerSm: Dp,
    val spinnerMd: Dp,
    val spinnerLg: Dp,

    val ratingBadgePad: Dp,
    val ratingIconSize: Dp,
    val ratingFontSize: TextUnit,

    val sectionAccentWidth: Dp,
    val sectionAccentHeight: Dp,

    val progressBarHeight: Dp,
    val pageIndicatorWidth: Dp,
    val pageIndicatorWidthSelected: Dp,
    val pageIndicatorHeight: Dp,

    val searchBarVertPad: Dp,
    val searchBarHorizPad: Dp,

    val borderThin: Dp,
    val borderMed: Dp,
)

// ─────────────────────────────────────────────────────────────────────────────
// Factory — builds tokens from shortest screen width
// ─────────────────────────────────────────────────────────────────────────────
private fun buildDimensions(sw: Int): ReelzDimensions {
    // scale factor: 1.0 at sw=360, interpolated up/down
    val s = when {
        sw < 320  -> 0.82f
        sw < 360  -> 0.90f
        sw < 400  -> 1.00f
        sw < 480  -> 1.10f
        sw < 600  -> 1.18f
        sw < 840  -> 1.30f   // 7" tablet
        else      -> 1.45f   // 10" tablet
    }
    val isTablet  = sw >= 600
    val isCompact = sw < 360

    fun Dp.s() = (this.value * s).dp
    fun TextUnit.s() = (this.value * s).sp

    return ReelzDimensions(
        isTablet  = isTablet,
        isCompact = isCompact,

        iconXs  = (9.dp).s(),
        iconSm  = (12.dp).s(),
        iconMd  = (20.dp).s(),
        iconLg  = (26.dp).s(),
        iconXl  = (36.dp).s(),

        spaceXxs = (2.dp).s(),
        spaceXs  = (4.dp).s(),
        spaceSm  = (6.dp).s(),
        spaceMd  = (10.dp).s(),
        spaceLg  = (16.dp).s(),
        spaceXl  = (24.dp).s(),
        spaceXxl = (32.dp).s(),

        radiusSm   = (6.dp).s(),
        radiusMd   = (12.dp).s(),
        radiusLg   = (20.dp).s(),
        radiusPill = 100.dp,

        cardPosterWidth   = (115.dp).s(),
        cardPosterHeight  = (168.dp).s(),
        cardRowWidth      = (115.dp).s(),
        cardRowHeight     = (168.dp).s(),
        continueCardWidth = (150.dp).s(),
        continueCardThumbHeight = (86.dp).s(),

        textXxs  = (9.sp).s(),
        textXs   = (11.sp).s(),
        textSm   = (12.sp).s(),
        textMd   = (13.sp).s(),
        textLg   = (15.sp).s(),
        textXl   = (17.sp).s(),
        textXxl  = (22.sp).s(),
        textHero = (26.sp).s(),

        buttonHeightSm = (36.dp).s(),
        buttonHeightMd = (44.dp).s(),
        buttonHorizPadSm = (14.dp).s(),
        buttonHorizPadMd = (20.dp).s(),
        buttonVertPadSm  = (8.dp).s(),
        buttonVertPadMd  = (12.dp).s(),

        chipHorizPad = (13.dp).s(),
        chipVertPad  = (5.dp).s(),

        avatarSm = (32.dp).s(),
        avatarMd = (48.dp).s(),
        avatarLg = (64.dp).s(),

        navIconSize = (22.dp).s(),
        navFontSize = (10.sp).s(),

        appBarHorizPad = (16.dp).s(),
        appBarVertPad  = (10.dp).s(),

        screenHorizPad = (14.dp).s(),
        sectionVertPad = (6.dp).s(),

        heroImageRatio = when {
            isTablet  -> 0.38f
            isCompact -> 0.52f
            else      -> 0.46f
        },
        heroPadding = (18.dp).s(),

        shimmerBarWidth  = (36.dp).s(),
        shimmerBarHeight = (4.dp).s(),

        spinnerSm = (14.dp).s(),
        spinnerMd = (26.dp).s(),
        spinnerLg = (44.dp).s(),

        ratingBadgePad  = (4.dp).s(),
        ratingIconSize  = (8.dp).s(),
        ratingFontSize  = (9.sp).s(),

        sectionAccentWidth  = (3.dp).s(),
        sectionAccentHeight = (15.dp).s(),

        progressBarHeight = (3.dp).s(),
        pageIndicatorWidth = (5.dp).s(),
        pageIndicatorWidthSelected = (20.dp).s(),
        pageIndicatorHeight = (5.dp).s(),

        searchBarVertPad  = (9.dp).s(),
        searchBarHorizPad = (12.dp).s(),

        borderThin = 1.dp,
        borderMed  = (1.5.dp).s(),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Composition Local
// ─────────────────────────────────────────────────────────────────────────────
val LocalDimensions = staticCompositionLocalOf<ReelzDimensions> {
    error("No ReelzDimensions provided — wrap your root with ProvideDimensions")
}

@Composable
fun ProvideDimensions(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val sw  = minOf(cfg.screenWidthDp, cfg.screenHeightDp)
    val dim = buildDimensions(sw)
    CompositionLocalProvider(LocalDimensions provides dim, content = content)
}
