package com.axio.reelz.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions


// ─────────────────────────────────────────────────────────────────────────────
// Section header row — accent bar + title + optional "See All"
// Standard variant used for generic sections
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
// Personalized section header — psychology-driven
// These replace generic "Popular Movies" labels with emotionally resonant ones.
// Examples:
//   type = "because"   → "Because you watched Interstellar"
//   type = "finish"    → "You might finish this tonight"
//   type = "taste"     → "Your kind of sci-fi"
//   type = "newep"     → "New episodes for you"
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PersonalizedSectionHeader(
    title: String,
    subtitle: String? = null,
    icon: String = "✦",
    accentColor: Color = Brand,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    // Subtle shimmer on the icon to draw attention without screaming
    val inf = rememberInfiniteTransition(label = "pshIcon")
    val pulse by inf.animateFloat(
        0.7f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pshPulse",
    )

    Column(
        modifier = modifier.fillMaxWidth().padding(
            start = d.screenHorizPad,
            top   = d.spaceXl,
            end   = d.screenHorizPad,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pulsing accent icon
            Text(
                icon,
                color    = accentColor.copy(alpha = pulse),
                fontSize = (d.textMd.value + 1).sp,
            )
            Spacer(Modifier.width(d.spaceSm))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color         = White,
                    fontWeight    = FontWeight.Bold,
                    fontSize      = d.textXl,
                    letterSpacing = (-0.2).sp,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color    = accentColor.copy(alpha = 0.75f),
                        fontSize = d.textXs,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp,
                    )
                }
            }
            if (action != null && onAction != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(d.radiusSm))
                        .clickable(onClick = onAction)
                        .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceXs),
                ) {
                    Text(action, color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(d.spaceMd))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Watch Streak Badge — psychology / retention
// Shows "🔥 5-day streak" when user has watched content multiple days in a row.
// Plugged into the Continue Watching section so it's front and center.
// The streak number comes from the ViewModel (count from WatchProgress DB).
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun WatchStreakBadge(
    streakDays: Int,
    modifier: Modifier = Modifier,
) {
    if (streakDays < 2) return  // Only show when streak is worth celebrating

    val d = LocalDimensions.current
    val inf = rememberInfiniteTransition(label = "streakBadge")
    val glowAlpha by inf.animateFloat(
        0.5f, 1f,
        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "streakGlow",
    )

    val label = when {
        streakDays >= 30 -> "🏆 ${streakDays}-day streak!"
        streakDays >= 7  -> "🔥 ${streakDays}-day streak"
        else             -> "🔥 ${streakDays} days in a row"
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusPill))
            .background(StreakGlass)
            .border(1.dp, StreakBorder, RoundedCornerShape(d.radiusPill))
            .padding(horizontal = d.spaceMd, vertical = d.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
    ) {
        // Pulsing dot
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(StreakOrange.copy(alpha = glowAlpha))
        )
        Text(
            label,
            color      = StreakOrange,
            fontSize   = d.textXxs,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.3.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// "Finish Tonight" pill — psychology / completion
// Shown on cards where user has ~30-60 min left ("You can finish this tonight!")
// Maps to the "Zeigarnik effect" — people remember and want to complete things
// they started. This converts partial watchers into completers.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun FinishTonightPill(
    minutesLeft: Int,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    val label = if (minutesLeft < 60) "${minutesLeft}m left" else "${minutesLeft / 60}h ${minutesLeft % 60}m left"

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusPill))
            .background(FinishNight.copy(0.15f))
            .border(1.dp, FinishNight.copy(0.35f), RoundedCornerShape(d.radiusPill))
            .padding(horizontal = d.spaceSm + 2.dp, vertical = d.spaceXxs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp),
    ) {
        Text("🌙", fontSize = (d.textXxs.value + 1).sp)
        Text(
            label,
            color      = FinishNight,
            fontSize   = d.textXxs,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state — cinematic personality
// Each context gets its own witty message. The goal: the user smiles instead
// of leaving. Emotional connection = retention.
// ─────────────────────────────────────────────────────────────────────────────
enum class EmptyContext {
    SEARCH, WATCHLIST, DOWNLOADS, CONTINUE_WATCHING, FEED
}

@Composable
fun CinematicEmptyState(
    context: EmptyContext,
    onAction: (() -> Unit)? = null,
    actionLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    val (emoji, headline, body) = when (context) {
        EmptyContext.SEARCH ->
            Triple("🎬", "Nothing yet", "Type a title, actor, or genre\nand we'll find it.")
        EmptyContext.WATCHLIST ->
            Triple("🍿", "Your watchlist awaits", "Tap the bookmark on anything\nyou want to remember.")
        EmptyContext.DOWNLOADS ->
            Triple("📲", "Nothing saved offline", "Download your favourites\nto watch without Wi-Fi.")
        EmptyContext.CONTINUE_WATCHING ->
            Triple("▶️", "Pick something great", "Your in-progress titles\nwill appear here.")
        EmptyContext.FEED ->
            Triple("🌌", "Loading your universe…", "Great things are coming.\nHang tight.")
    }

    val inf = rememberInfiniteTransition(label = "emptyFloat")
    val float by inf.animateFloat(
        -6f, 6f,
        infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "emptyY",
    )

    Column(
        modifier = modifier.fillMaxWidth().padding(d.spaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Floating emoji with glow ring
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(d.avatarLg + d.spaceXxl)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(BlueGlow, Color.Transparent)))
            )
            Text(
                emoji,
                fontSize = (d.textHero.value + 8f).sp,
                modifier = Modifier.graphicsLayer { translationY = float },
            )
        }
        Spacer(Modifier.height(d.spaceLg))
        Text(
            headline,
            color      = White,
            fontWeight = FontWeight.Bold,
            fontSize   = d.textXl,
            textAlign  = TextAlign.Center,
        )
        Spacer(Modifier.height(d.spaceSm))
        Text(
            body,
            color     = White40,
            fontSize  = d.textMd,
            textAlign = TextAlign.Center,
            lineHeight = (d.textMd.value * 1.55f).sp,
        )
        if (onAction != null && actionLabel != null) {
            Spacer(Modifier.height(d.spaceXl))
            BrandButton(actionLabel, onAction)
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
// Skeleton grid card — staggered wave shimmer
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SkeletonGridCard(modifier: Modifier = Modifier, phaseOffset: Float = 0f) {
    val inf = rememberInfiniteTransition(label = "skGrid")
    val offset by inf.animateFloat(
        initialValue  = -1.5f + phaseOffset,
        targetValue   = 2.5f  + phaseOffset,
        animationSpec = infiniteRepeatable(tween(1050, easing = LinearEasing)),
        label         = "skGridOff",
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
    val d = LocalDimensions.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(d.radiusMd)).background(shimmerBrush)
        )
        Box(Modifier.fillMaxWidth(0.85f).height(d.spaceSm + d.spaceXxs).clip(RoundedCornerShape(d.spaceXs)).background(shimmerBrush))
        Box(Modifier.fillMaxWidth(0.6f).height(d.spaceSm).clip(RoundedCornerShape(d.spaceXs)).background(shimmerBrush))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Cinematic full-screen loader
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

    androidx.compose.foundation.Canvas(modifier.size(size)) {
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
                .border(1.dp, Error.copy(.35f), CircleShape))
            androidx.compose.material3.Icon(IconWifiOff, null, tint = Error.copy(.8f), modifier = Modifier.size(d.iconLg))
        }
        Spacer(Modifier.height(d.spaceXl))
        Text(friendlyError(message), color = White60, fontSize = d.textLg,
            textAlign = TextAlign.Center, lineHeight = (d.textLg.value * 1.55f).sp)
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
// Load more skeleton
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun LoadMoreSkeleton() {
    val d = LocalDimensions.current
    Box(Modifier.fillMaxWidth().padding(vertical = d.spaceMd - d.spaceXxs)) { SkeletonRowLoader() }
}
