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
import com.axio.reelz.ui.theme.*

@Composable
fun MaintenanceScreen(
    message: String = "We're making things better. Check back soon!",
    onRetry: () -> Unit = {},
) {
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
                .size(300.dp)
                .background(
                    Brush.radialGradient(listOf(Violet.copy(0.10f), Color.Transparent))
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            // Rotating ring + gear icon
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(100.dp)
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
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(listOf(Violet.copy(0.18f), BgCard))
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚙️", fontSize = 32.sp)
                }
            }

            Spacer(Modifier.height(36.dp))

            Text(
                "Under Maintenance",
                color      = White,
                fontSize   = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))

            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgCard)
                    .border(1.dp, Violet.copy(0.2f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    message.ifBlank { "We're making things better. Check back soon!" },
                    color     = White.copy(0.7f),
                    fontSize  = 14.sp,
                    lineHeight = 22.sp,
                    textAlign  = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(32.dp))

            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape  = RoundedCornerShape(14.dp),
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
