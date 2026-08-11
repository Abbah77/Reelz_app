package com.axio.reelz.ui.components

// SectionHeader, SkeletonBannerLoader, SkeletonRowLoader, SkeletonGridCard,
// FullScreenLoader, CinematicSpinner, SmallSpinner, PulsingDot, ErrorState
// Extracted from CommonComponents.kt
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
// Skeleton grid card — used in Explore 3-column grid while loading more.
// Each skeleton card matches the aspect ratio of a real poster card (0.65f).
// Staggered animation phase gives a ripple-wave feel without needing a
// coordinator — each card independently animates with a small offset delay.
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
// Premium cinematic loading spinner (kept for reuse)
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
                .border(d.borderThin, Error.copy(.35f), CircleShape))
            Icon(IconWifiOff, null, tint = Error.copy(.8f), modifier = Modifier.size(d.iconLg))
        }
        Spacer(Modifier.height(d.spaceXl))
        Text(friendlyError(message), color = White60, fontSize = d.textLg,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = (d.textLg.value * 1.55f).sp)
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
// Rating chip
// ─────────────────────────────────────────────────────────────────────────────
