package com.axio.reelz.ui.screens.update

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalConfiguration
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions

@Composable
fun MaintenanceScreen(
    message: String = "We're making things better. Check back soon!",
    onRetry: () -> Unit = {},
) {
    val d = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    // Slow rotate on the wrench icon ring
    val rotation = rememberInfiniteTransition(label = "spin")
    val angle by rotation.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "angle",
    )

    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(600)) }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(alpha.value)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF050510), Color(0xFF080820), Color(0xFF050510))
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Ambient glow
        Box(
            Modifier
                .size(screenH * 0.38f)
                .background(
                    Brush.radialGradient(listOf(Violet.copy(0.10f), Color.Transparent))
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(d.spaceXxl),
        ) {
            // Rotating ring + gear icon
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(d.avatarLg + d.spaceXxl + d.spaceXs)
                        .rotate(angle)
                        .border(
                            2.dp,
                            Brush.sweepGradient(
                                listOf(
                                    Violet.copy(0f),
                                    Violet.copy(0.7f),
                                    Violet,
                                    Violet.copy(0f),
                                )
                            ),
                            CircleShape,
                        )
                )
                Box(
                    Modifier
                        .size(d.avatarLg + d.spaceLg)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(listOf(Violet.copy(0.18f), BgCard))
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚙️", fontSize = d.textHero + 6.sp)
                }
            }

            Spacer(Modifier.height(d.buttonHeightSm - d.spaceXxs))

            Text(
                "Under Maintenance",
                color      = White,
                fontSize = d.textXxl,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
            )

            Spacer(Modifier.height(d.spaceMd - d.spaceXxs))

            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusMd))
                    .background(BgCard)
                    .border(1.dp, Violet.copy(0.2f), RoundedCornerShape(d.radiusMd))
                    .padding(horizontal = d.spaceXl - d.spaceXs, vertical = d.spaceLg),
            ) {
                Text(
                    message.ifBlank { "We're making things better. Check back soon!" },
                    color     = White.copy(0.7f),
                    fontSize = d.textMd,
                    lineHeight = (d.textXl.value * 1.3f).sp,
                    textAlign  = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(d.spaceXxl))

            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(d.avatarMd + d.spaceSm),
                shape  = RoundedCornerShape(d.radiusMd),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(Violet, Brand))
                ),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = White),
            ) {
                Text("Try Again", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
