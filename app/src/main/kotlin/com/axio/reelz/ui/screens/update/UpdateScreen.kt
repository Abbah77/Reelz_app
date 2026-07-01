package com.axio.reelz.ui.screens.update

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions

@Composable
fun UpdateScreen(
    latestVersion : String,
    downloadUrl   : String,
    changelog     : String  = "",
    forceUpdate   : Boolean = false,
    onSkip        : () -> Unit = {},
) {
    val d = LocalDimensions.current
    val context = LocalContext.current

    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(
        initialValue  = 1f,
        targetValue   = 1.18f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "pulseScale",
    )

    val iconAlpha = remember { Animatable(0f) }
    val cardSlide = remember { Animatable(60f) }
    val cardAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        iconAlpha.animateTo(1f, tween(500))
        cardSlide.animateTo(0f, spring(0.7f, 500f))
        cardAlpha.animateTo(1f, tween(400))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF050510), Color(0xFF080820), Color(0xFF050510))
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(d.cardPosterWidth + d.spaceXxl * 5f)
                .background(Brush.radialGradient(listOf(Brand.copy(0.12f), Color.Transparent)))
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier            = Modifier.padding(d.spaceXl),
        ) {

            // ── Icon ─────────────────────────────────────────────────────────
            Box(
                Modifier
                    .size(d.avatarLg + d.spaceXxl)
                    .scale(pulseScale)
                    .alpha(iconAlpha.value)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Brand.copy(0.25f), BrandDeep.copy(0.4f))))
                    .border(d.borderMed, Brush.linearGradient(listOf(Brand, Brand2)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", color = Brand, fontSize = d.textHero + 6.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(d.spaceXxl - d.spaceXs))

            // ── Card ─────────────────────────────────────────────────────────
            Box(
                Modifier
                    .offset(y = cardSlide.value.dp)
                    .alpha(cardAlpha.value)
                    .clip(RoundedCornerShape(d.radiusLg))
                    .background(Brush.verticalGradient(listOf(BgCard, BgRaised)))
                    .border(
                        1.dp,
                        Brush.linearGradient(listOf(Brand.copy(0.35f), Brand2.copy(0.1f))),
                        RoundedCornerShape(d.radiusLg),
                    )
                    .padding(d.spaceXxl - d.spaceXs),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {

                    if (forceUpdate) {
                        Text(
                            "UPDATE REQUIRED",
                            color         = Brand2,
                            fontSize = d.textXs,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Spacer(Modifier.height(d.spaceSm + d.spaceXxs))
                    }

                    Text(
                        "New Version Available",
                        color      = White,
                        fontSize = d.textXxl,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                    )

                    Spacer(Modifier.height(d.spaceSm))

                    Text(
                        "Version $latestVersion is ready to install",
                        color     = White.copy(0.55f),
                        fontSize = d.textMd,
                        textAlign = TextAlign.Center,
                    )

                    if (changelog.isNotBlank()) {
                        Spacer(Modifier.height(d.spaceXl - d.spaceXs))
                        HorizontalDivider(color = White.copy(0.06f))
                        Spacer(Modifier.height(d.spaceLg))
                        Text(
                            "WHAT'S NEW",
                            color         = White.copy(0.4f),
                            fontSize = d.textXs,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            modifier      = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(d.spaceSm + d.spaceXxs))
                        Text(
                            changelog,
                            color      = White.copy(0.75f),
                            fontSize = d.textMd,
                            lineHeight = (d.textMd.value * 1.5f).sp,
                        )
                    }

                    Spacer(Modifier.height(d.spaceXxl - d.spaceXs))

                    // ── Download button — opens browser, user installs normally ──
                    Button(
                        onClick = {
                            // Simple and battle-tested — hands the URL to Chrome/browser.
                            // Works on every Android version and every manufacturer.
                            // User downloads APK, taps notification, Android installs it.
                            // Same approach used by Telegram, WhatsApp, and most sideloaded apps.
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                        modifier        = Modifier.fillMaxWidth().height(d.buttonHeightMd + d.spaceSm),
                        shape           = RoundedCornerShape(d.radiusMd),
                        colors          = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding  = PaddingValues(0.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(listOf(Brand, Brand2)),
                                    RoundedCornerShape(d.radiusMd),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Download Update",
                                color      = Color.White,
                                fontSize = d.textLg,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (!forceUpdate) {
                        Spacer(Modifier.height(d.spaceMd - d.spaceXxs))
                        TextButton(
                            onClick  = onSkip,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Later", color = White.copy(0.4f), fontSize = d.textMd)
                        }
                    }
                }
            }
        }
    }
}
