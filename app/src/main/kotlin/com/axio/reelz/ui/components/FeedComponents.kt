package com.axio.reelz.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
// Shared shimmer brush — single infinite transition, shared across skeletons
// so they all move in sync (feels cohesive, not chaotic).
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun rememberShimmerBrush(durationMs: Int = 1000): Brush {
    val inf = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslate by inf.animateFloat(
        initialValue  = -600f,
        targetValue   = 1800f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerX",
    )
    return Brush.linearGradient(
        colors = listOf(
            BgRaised,
            BgSurface.copy(alpha = 0.9f),
            Color(0xFF22222C),
            BgSurface.copy(alpha = 0.9f),
            BgRaised,
        ),
        start = Offset(shimmerTranslate - 400f, 0f),
        end   = Offset(shimmerTranslate + 400f, 0f),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Banner skeleton — full-height hero placeholder with metadata lines
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SkeletonBannerLoader() {
    val d = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val shimmer = rememberShimmerBrush(1200)

    Box(
        Modifier
            .fillMaxWidth()
            .height(screenH * d.heroImageRatio)
            .background(BgCard)
    ) {
        // Main backdrop skeleton with shimmer
        Box(
            Modifier
                .fillMaxSize()
                .background(shimmer)
        )
        // Gradient overlay to make bottom content area feel realistic
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f   to Color.Transparent,
                        0.5f to Color.Transparent,
                        0.72f to BgCard.copy(alpha = 0.6f),
                        1f   to BgCard,
                    )
                )
        )
        // Bottom metadata skeleton
        Column(
            Modifier.align(Alignment.BottomStart).padding(d.heroPadding),
            verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXs),
        ) {
            // "FEATURED" badge shape
            Box(
                Modifier.width(80.dp).height(d.spaceLg - d.spaceXxs)
                    .clip(RoundedCornerShape(d.spaceSm)).background(shimmer)
            )
            // Title — two lines to match real content
            Box(
                Modifier.fillMaxWidth(0.72f).height((d.textHero.value + 4).dp)
                    .clip(RoundedCornerShape(d.spaceSm)).background(shimmer)
            )
            Box(
                Modifier.fillMaxWidth(0.52f).height((d.textHero.value + 4).dp)
                    .clip(RoundedCornerShape(d.spaceSm)).background(shimmer)
            )
            // Meta row — rating · year · type
            Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                Box(Modifier.width(36.dp).height(d.spaceMd).clip(RoundedCornerShape(d.spaceXs)).background(shimmer))
                Box(Modifier.width(28.dp).height(d.spaceMd).clip(RoundedCornerShape(d.spaceXs)).background(shimmer))
                Box(Modifier.width(48.dp).height(d.spaceMd).clip(RoundedCornerShape(d.spaceXs)).background(shimmer))
            }
            Spacer(Modifier.height(d.spaceXs))
            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                Box(
                    Modifier.width(130.dp).height(d.buttonHeightMd)
                        .clip(RoundedCornerShape(d.radiusPill)).background(BgSurface)
                )
                Box(
                    Modifier.width(110.dp).height(d.buttonHeightMd)
                        .clip(RoundedCornerShape(d.radiusPill)).background(BgRaised)
                )
            }
        }
        // Page indicator dots
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = d.screenHorizPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
        ) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(d.spaceXxs))
                        .width(if (i == 0) 18.dp else 6.dp)
                        .height(4.dp)
                        .background(if (i == 0) Brand.copy(0.5f) else White10)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Row skeleton — horizontal scroll of cards with realistic shape/text lines
// Matches the exact layout of MediaRowCard so the transition is seamless.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SkeletonRowLoader() {
    val d = LocalDimensions.current
    val shimmer = rememberShimmerBrush(950)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad),
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
    ) {
        repeat(5) { idx ->
            SkeletonRowCard(
                shimmer = shimmer,
                widthFraction = 1f, // controlled by width below
                modifier = Modifier.width(d.cardRowWidth),
            )
        }
    }
}

@Composable
private fun SkeletonRowCard(
    shimmer: Brush,
    widthFraction: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
        // Poster thumbnail
        Box(
            Modifier
                .fillMaxWidth(widthFraction)
                .height(d.cardRowHeight)
                .clip(RoundedCornerShape(d.radiusMd))
                .background(shimmer)
        )
        // Title line
        Box(
            Modifier.fillMaxWidth(0.88f).height(d.spaceSm + d.spaceXxs)
                .clip(RoundedCornerShape(d.spaceXxs + 1.dp)).background(shimmer)
        )
        // Subtitle line (year · type)
        Box(
            Modifier.fillMaxWidth(0.60f).height(d.spaceSm)
                .clip(RoundedCornerShape(d.spaceXxs + 1.dp)).background(shimmer)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Grid skeleton card — used in Explore / Search 3-column grid while loading.
// Matches MediaPosterCard aspect ratio (0.65f) for seamless swap.
// Staggered phaseOffset gives a ripple-wave feeling.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SkeletonGridCard(modifier: Modifier = Modifier, phaseOffset: Float = 0f) {
    val inf = rememberInfiniteTransition(label = "skGrid")
    val shimmerX by inf.animateFloat(
        initialValue  = -600f + phaseOffset * 300f,
        targetValue   = 1800f + phaseOffset * 300f,
        animationSpec = infiniteRepeatable(
            tween(1100, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "skGridX",
    )
    val shimmer = Brush.linearGradient(
        colors = listOf(BgRaised, BgSurface.copy(0.9f), Color(0xFF22222C), BgSurface.copy(0.9f), BgRaised),
        start  = Offset(shimmerX - 400f, 0f),
        end    = Offset(shimmerX + 400f, 0f),
    )
    val d = LocalDimensions.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
        // Poster area (flexible height from aspect ratio)
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(d.radiusMd)).background(shimmer)
        )
        // Title
        Box(
            Modifier.fillMaxWidth(0.85f).height(d.spaceSm + d.spaceXxs)
                .clip(RoundedCornerShape(d.spaceXxs + 1.dp)).background(shimmer)
        )
        // Subtitle
        Box(
            Modifier.fillMaxWidth(0.58f).height(d.spaceSm)
                .clip(RoundedCornerShape(d.spaceXxs + 1.dp)).background(shimmer)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Full-grid skeleton — used for initial Explore load (shows 9 cards = 3 rows)
// so the screen doesn't feel empty before data arrives.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SkeletonGridLoader(count: Int = 9, columns: Int = 3) {
    val d = LocalDimensions.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = d.spaceMd - d.spaceXxs),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        val rows = (count + columns - 1) / columns
        repeat(rows) { rowIdx ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
            ) {
                repeat(columns) { colIdx ->
                    val globalIdx = rowIdx * columns + colIdx
                    if (globalIdx < count) {
                        SkeletonGridCard(
                            modifier    = Modifier.weight(1f).aspectRatio(0.65f),
                            phaseOffset = globalIdx * 0.2f,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Search skeleton — grid of cards that match SearchScreen layout exactly
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SkeletonSearchResults(count: Int = 9) {
    val d = LocalDimensions.current
    Column(
        Modifier.fillMaxWidth().padding(top = d.spaceSm),
    ) {
        // "X results" line placeholder
        Box(
            Modifier.width(90.dp).height(d.spaceMd).margin(d.screenHorizPad, d.sectionVertPad)
                .clip(RoundedCornerShape(d.spaceXxs + 1.dp)).background(BgSurface)
        )
        Spacer(Modifier.height(d.spaceSm))
        SkeletonGridLoader(count = count, columns = 3)
    }
}

private fun Modifier.margin(horizontal: Dp, vertical: Dp) =
    this.padding(horizontal = horizontal, vertical = vertical)

// ─────────────────────────────────────────────────────────────────────────────
// Premium cinematic loading spinner (kept for non-card contexts)
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
    val inf    = rememberInfiniteTransition(label = "spinner")
    val angle1 by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), "a1")
    val angle2 by inf.animateFloat(360f, 0f, infiniteRepeatable(tween(900, easing = LinearEasing)), "a2")
    val pulse  by inf.animateFloat(0.6f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "p")

    Canvas(modifier.size(size)) {
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
    val d   = LocalDimensions.current
    val inf = rememberInfiniteTransition(label = "dot")
    val scale by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "sc")
    val glow  by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "gl")
    Box(modifier = modifier.size(d.spaceXs + d.spaceXxs), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().scale(scale + 0.4f).clip(CircleShape).background(Brand.copy(glow * 0.3f)))
        Box(Modifier.fillMaxSize(0.7f).scale(scale).clip(CircleShape).background(Brand))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error state — improved with clearer messaging and context-aware icons
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    val isNetworkError = message.contains("internet", true) ||
        message.contains("network", true) ||
        message.contains("connect", true)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(d.spaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Icon with glowing ring
        Box(contentAlignment = Alignment.Center) {
            // Outer glow ring
            Box(
                Modifier.size(d.avatarLg + d.spaceLg + d.spaceMd)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(listOf(Error.copy(.08f), Color.Transparent))
                    )
            )
            // Inner ring
            Box(
                Modifier.size(d.avatarLg + d.spaceLg)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(listOf(Error.copy(.15f), Color.Transparent))
                    )
                    .border(1.dp, Error.copy(.3f), CircleShape)
            )
            Icon(
                if (isNetworkError) IconWifiOff else IconMovieSlate,
                contentDescription = null,
                tint     = Error.copy(.85f),
                modifier = Modifier.size(d.iconLg),
            )
        }

        Spacer(Modifier.height(d.spaceXl))

        Text(
            if (isNetworkError) "No Connection" else "Something went wrong",
            color      = White,
            fontSize   = d.textXl,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center,
        )
        Spacer(Modifier.height(d.spaceSm))
        Text(
            friendlyError(message),
            color    = White40,
            fontSize = d.textMd,
            textAlign = TextAlign.Center,
            lineHeight = (d.textMd.value * 1.6f).sp,
        )

        if (onRetry != null) {
            Spacer(Modifier.height(d.spaceXxl))
            BrandButton("Try Again", onRetry)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Inline error banner — non-full-screen, e.g. for partial load failures
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun InlineErrorBanner(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad, vertical = d.spaceSm)
            .clip(RoundedCornerShape(d.radiusMd))
            .background(Error.copy(.1f))
            .border(1.dp, Error.copy(.3f), RoundedCornerShape(d.radiusMd))
            .padding(horizontal = d.spaceLg, vertical = d.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Icon(IconWifiOff, null, tint = Error.copy(.8f), modifier = Modifier.size(d.iconMd - 2.dp))
        Text(
            friendlyError(message),
            color    = White60,
            fontSize = d.textSm,
            modifier = Modifier.weight(1f),
            lineHeight = (d.textSm.value * 1.5f).sp,
        )
        if (onRetry != null) {
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = d.spaceSm, vertical = 0.dp),
            ) {
                Text("Retry", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// "Typing…" search indicator — replaces error-while-typing
// Shows a subtle animated indicator that search is in progress
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SearchingIndicator(modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    val inf = rememberInfiniteTransition(label = "searchTyping")
    val dot1Alpha by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), "d1")
    val dot2Alpha by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500, delayMillis = 166), RepeatMode.Reverse), "d2")
    val dot3Alpha by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500, delayMillis = 333), RepeatMode.Reverse), "d3")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad, vertical = d.spaceSm)
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BlueGlass)
            .border(1.dp, BlueBorder, RoundedCornerShape(d.radiusMd))
            .padding(horizontal = d.spaceLg, vertical = d.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Icon(IconSearch, null, tint = Brand.copy(.7f), modifier = Modifier.size(d.iconSm + 2.dp))
        Text("Searching", color = White60, fontSize = d.textSm, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(dot1Alpha, dot2Alpha, dot3Alpha).forEach { alpha ->
                Box(Modifier.size(5.dp).clip(CircleShape).background(Brand.copy(alpha)))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state — improved with context-aware messaging
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun EmptySearchState(
    query: String,
    hasActiveFilters: Boolean,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    Box(modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
        ) {
            // Animated icon ring
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(d.avatarLg + d.spaceXl + d.spaceMd).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(BgSurface, Color.Transparent)))
                        .border(1.dp, GlassBorderMd, CircleShape)
                )
                Icon(IconMovieSlate, null, tint = White20, modifier = Modifier.size(d.iconXl - 2.dp))
            }
            Spacer(Modifier.height(d.spaceSm))
            Text("No results for", color = White40, fontSize = d.textMd)
            Text(
                "\"$query\"",
                color      = White60,
                fontSize   = d.textXxl,
                fontWeight = FontWeight.Bold,
            )
            if (hasActiveFilters) {
                Spacer(Modifier.height(d.spaceXs))
                Text(
                    "Try removing some filters to see more results.",
                    color     = White40,
                    fontSize  = d.textSm,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(d.spaceSm))
                TextButton(onClick = onClearFilters) {
                    Text("Clear filters", color = Brand, fontSize = d.textMd, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(
                    "Check the spelling or try a different term.",
                    color     = White40,
                    fontSize  = d.textSm,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun EmptyExploreState(
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    Box(modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(d.avatarLg + d.spaceXl).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Brand.copy(.1f), Color.Transparent)))
                        .border(1.dp, Brand.copy(.25f), CircleShape)
                )
                Icon(IconCompass, null, tint = Brand.copy(.7f), modifier = Modifier.size(d.iconXl - 4.dp))
            }
            Text(
                "Nothing matches yet",
                color      = White60,
                fontSize   = d.textXl,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Try widening your filters\nor switching to a different mood.",
                color     = White40,
                fontSize  = d.textMd,
                textAlign = TextAlign.Center,
                lineHeight = (d.textMd.value * 1.6f).sp,
            )
            Spacer(Modifier.height(d.spaceXs))
            TextButton(onClick = onClear) {
                Text("Reset filters", color = Brand, fontWeight = FontWeight.SemiBold, fontSize = d.textMd)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Friendly error text mapping
// ─────────────────────────────────────────────────────────────────────────────
fun friendlyError(raw: String): String = when {
    raw.contains("403")              -> "This content isn't available right now."
    raw.contains("404")              -> "Content not found. It may have moved or been removed."
    raw.contains("500")              -> "Server hiccup — please try again in a moment."
    raw.contains("timeout", true)    -> "Taking too long. Check your connection and retry."
    raw.contains("network", true) ||
    raw.contains("connect", true) ||
    raw.contains("internet", true)   -> "No internet. Please check your network."
    raw.contains("stream", true) ||
    raw.contains("source", true)     -> "Couldn't find a working stream. Trying another source."
    raw.contains("initialize", true) -> "Playback couldn't start. Please try again."
    else                             -> "Something went wrong. Please try again."
}

// ─────────────────────────────────────────────────────────────────────────────
// Skeleton detail loader
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SkeletonDetailLoader() {
    val d       = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val shimmer = rememberShimmerBrush(1000)

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
    ) {
        item {
            Box(
                Modifier.fillMaxWidth().height(screenH * 0.42f).background(shimmer)
            )
        }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad, vertical = d.spaceLg),
                verticalArrangement = Arrangement.spacedBy(d.spaceMd),
            ) {
                Box(Modifier.width(80.dp).height(d.spaceMd + 2.dp).clip(RoundedCornerShape(d.radiusSm)).background(shimmer))
                Box(Modifier.fillMaxWidth(0.85f).height(d.textHero.value.dp + 4.dp).clip(RoundedCornerShape(d.spaceSm)).background(shimmer))
                Box(Modifier.fillMaxWidth(0.55f).height(d.textHero.value.dp + 4.dp).clip(RoundedCornerShape(d.spaceSm)).background(shimmer))
                Spacer(Modifier.height(d.spaceXs))
                Row(horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                    Box(Modifier.width(50.dp).height(d.spaceMd).clip(RoundedCornerShape(d.spaceXs)).background(shimmer))
                    Box(Modifier.width(60.dp).height(d.spaceMd).clip(RoundedCornerShape(d.spaceXs)).background(shimmer))
                    Box(Modifier.width(40.dp).height(d.spaceMd).clip(RoundedCornerShape(d.spaceXs)).background(shimmer))
                }
                Spacer(Modifier.height(d.spaceXs))
                Row(horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                    Box(Modifier.weight(1f).height(d.buttonHeightMd).clip(RoundedCornerShape(d.radiusPill)).background(shimmer))
                    Box(Modifier.weight(0.6f).height(d.buttonHeightMd).clip(RoundedCornerShape(d.radiusPill)).background(shimmer))
                }
                Spacer(Modifier.height(d.spaceMd))
                repeat(3) {
                    Box(Modifier.fillMaxWidth(if (it == 2) 0.7f else 1f).height(d.spaceSm + 2.dp).clip(RoundedCornerShape(d.spaceXs)).background(shimmer))
                }
                Spacer(Modifier.height(d.spaceLg))
                Box(Modifier.fillMaxWidth(0.3f).height(d.spaceMd + 2.dp).clip(RoundedCornerShape(d.spaceXs)).background(shimmer))
                Spacer(Modifier.height(d.spaceSm))
                Row(horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                    repeat(4) {
                        Column(
                            Modifier.width(72.dp),
                            verticalArrangement  = Arrangement.spacedBy(d.spaceSm),
                            horizontalAlignment  = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(72.dp).clip(CircleShape).background(shimmer))
                            Box(Modifier.fillMaxWidth(0.8f).height(d.spaceSm).clip(RoundedCornerShape(d.spaceXxs)).background(shimmer))
                        }
                    }
                }
            }
        }
    }
}
