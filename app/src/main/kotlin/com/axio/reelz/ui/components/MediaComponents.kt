package com.axio.reelz.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.axio.reelz.data.model.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions


@Composable
fun MediaPosterCard(
    media: Media,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "cardScale"
    )
    val elevation by animateFloatAsState(
        targetValue = if (pressed) 2f else 12f,
        animationSpec = spring(stiffness = 400f),
        label = "elevation"
    )
    val rotateX by animateFloatAsState(
        targetValue = if (pressed) 6f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "rotX"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                rotationX = rotateX
                shadowElevation = elevation
                shape = RoundedCornerShape(d.radiusMd)
                clip = false
                cameraDistance = 8f * density
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(d.radiusMd))
                .border(d.borderThin, if (pressed) BlueBorder else GlassBorder, RoundedCornerShape(d.radiusMd))
                .background(BgRaised)
        ) {
            // SubcomposeAsyncImage: each card independently manages its own image state.
            // Cards show their own skeleton shimmer while Coil fetches the poster —
            // no "dump all grey boxes at once" — each card transitions on its own timeline.
            SubcomposeAsyncImage(
                model = media.posterUrl,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Loading,
                    is AsyncImagePainter.State.Empty -> PosterSkeletonShimmer(Modifier.fillMaxSize())
                    is AsyncImagePainter.State.Error -> Box(
                        Modifier.fillMaxSize().background(BgRaised),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(d.iconLg).clip(CircleShape).background(GlassMd))
                    }
                    else -> SubcomposeAsyncImageContent()
                }
            }
            // Gradient scrim — applied on top of the image for bottom-text legibility
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC05050A))
            ))
            if (pressed) {
                Box(Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Brand.copy(0.15f), Color.Transparent))
                ))
            }
            if (media.voteAverage > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(d.ratingBadgePad)
                        .clip(RoundedCornerShape(d.radiusSm))
                        .background(Color(0xCC000000))
                        .border(d.borderThin, BlueBorder, RoundedCornerShape(d.radiusSm))
                        .padding(horizontal = d.spaceXs, vertical = d.spaceXxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconStar, null, tint = Gold, modifier = Modifier.size(d.ratingIconSize))
                    Spacer(Modifier.width(d.spaceXxs))
                    Text("${"%.1f".format(media.voteAverage)}", color = Gold, fontSize = d.ratingFontSize, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(d.spaceSm))
        Text(
            media.title,
            color      = White80,
            fontSize   = d.textXs,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
            lineHeight = (d.textXs.value * 1.4f).sp,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.padding(horizontal = d.spaceXxs),
        )
        if (media.mediaType == MediaType.TV) {
            Spacer(Modifier.height(d.spaceXxs))
            Text(
                "TV Series",
                color         = Brand,
                fontSize      = d.textXxs,
                fontWeight    = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier      = Modifier.padding(horizontal = d.spaceXxs),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Media row card (horizontal list)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MediaRowCard(media: Media, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "rowScale"
    )

    Column(
        modifier = modifier
            .width(d.cardRowWidth)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                cameraDistance = 8f * density
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(d.cardRowHeight)
                .clip(RoundedCornerShape(d.radiusMd))
                .border(d.borderThin, if (pressed) BlueBorder else GlassBorder, RoundedCornerShape(d.radiusMd))
                .background(BgRaised)
        ) {
            SubcomposeAsyncImage(
                model = media.posterUrl,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Loading,
                    is AsyncImagePainter.State.Empty -> PosterSkeletonShimmer(Modifier.fillMaxSize())
                    is AsyncImagePainter.State.Error -> Box(Modifier.fillMaxSize().background(BgRaised))
                    else -> SubcomposeAsyncImageContent()
                }
            }
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xDD05050A))
            ))
            if (pressed) {
                Box(Modifier.fillMaxSize().background(Brand.copy(0.08f)))
            }
            if (media.voteAverage > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(d.spaceSm)
                        .clip(RoundedCornerShape(d.radiusSm))
                        .background(Color(0xBB000000))
                        .padding(horizontal = d.spaceXs, vertical = d.spaceXxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconStar, null, tint = Gold, modifier = Modifier.size(d.ratingIconSize))
                    Spacer(Modifier.width(d.spaceXxs))
                    Text("${"%.1f".format(media.voteAverage)}", color = Gold, fontSize = d.ratingFontSize, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(d.spaceSm))
        Text(
            media.title,
            color      = White80,
            fontSize   = d.textSm,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
            lineHeight = (d.textSm.value * 1.35f).sp,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.padding(horizontal = d.spaceXxs),
        )
        if (media.mediaType == MediaType.TV) {
            Spacer(Modifier.height(d.spaceXxs))
            Text(
                "TV Series",
                color         = Brand,
                fontSize      = d.textXxs,
                fontWeight    = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier      = Modifier.padding(horizontal = d.spaceXxs),
            )
        }
    }
}


@Composable
fun RatingChip(rating: Double, modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusSm))
            .background(Color(0x22FFD700))
            .border(d.borderThin, Gold.copy(.3f), RoundedCornerShape(d.radiusSm))
            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IconStar, null, tint = Gold, modifier = Modifier.size(d.iconSm))
        Spacer(Modifier.width(d.spaceXs))
        Text("${"%.1f".format(rating)}", color = Gold, fontSize = d.textMd, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Genre pill — with blue active state
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GenrePill(text: String, selected: Boolean = false, onClick: () -> Unit = {}) {
    val d = LocalDimensions.current
    val bgBrush = if (selected)
        Brush.horizontalGradient(listOf(BrandDeep, Brand.copy(.9f)))
    else
        Brush.horizontalGradient(listOf(BgRaised, BgSurface))

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(d.radiusPill))
            .background(bgBrush)
            .border(d.borderThin, if (selected) Brand.copy(.6f) else GlassBorderMd, RoundedCornerShape(d.radiusPill))
            .clickable(onClick = onClick)
            .padding(horizontal = d.chipHorizPad + d.spaceXs, vertical = d.chipVertPad + d.spaceXs),
    ) {
        Text(
            text,
            color      = if (selected) Color.White else White60,
            fontSize   = d.textSm,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

