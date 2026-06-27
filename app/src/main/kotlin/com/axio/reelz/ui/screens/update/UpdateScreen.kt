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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.axio.reelz.update.ApkUpdateManager
import com.axio.reelz.update.UpdateState
import com.axio.reelz.ui.theme.*

// ── Arrow-up icon drawn as vectors (no extra dep) ────────────────────────────
private val UpdateIconPath = androidx.compose.ui.graphics.vector.ImageVector.Builder(
    "UpdateArrow", 24.dp, 24.dp, 24f, 24f
).apply {
    addPath(
        pathData = androidx.compose.ui.graphics.vector.PathData {
            moveTo(12f, 4f); lineTo(12f, 16f)
            moveTo(7f, 9f);  lineTo(12f, 4f); lineTo(17f, 9f)
            moveTo(4f, 20f); lineTo(20f, 20f)
        },
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent),
    )
}.build()

/**
 * Shown when [forceUpdate] = true (user cannot skip) or as a dismissible
 * screen when a newer version is available but not mandatory.
 *
 * This composable is a **dumb observer** — all download/install logic lives
 * in [ApkUpdateManager]. It renders whatever [updateManager].state says.
 *
 * @param latestVersion  e.g. "2" — shown in the UI
 * @param downloadUrl    Direct APK download URL from remote config
 * @param changelog      Optional changelog text from remote config
 * @param forceUpdate    If true, no skip/later button is rendered
 * @param onSkip         Called when user taps "Later" (only if !forceUpdate)
 * @param updateManager  Injected via Hilt — owns the download/install state machine
 */
@Composable
fun UpdateScreen(
    latestVersion: String,
    downloadUrl: String,
    changelog: String = "",
    forceUpdate: Boolean = false,
    onSkip: () -> Unit = {},
    updateManager: ApkUpdateManager,
) {
    val updateState by updateManager.state.collectAsState()

    // Pulse animation on the icon badge
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue  = 1.18f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    // Entrance animations
    val iconAlpha  = remember { Animatable(0f) }
    val cardSlide  = remember { Animatable(60f) }
    val cardAlpha  = remember { Animatable(0f) }

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
        // Ambient glow behind icon
        Box(
            Modifier
                .size(280.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Brand.copy(0.12f), Color.Transparent)
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp),
        ) {

            // ── Update icon ──────────────────────────────────────────────────
            Box(
                Modifier
                    .size(88.dp)
                    .scale(pulseScale)
                    .alpha(iconAlpha.value)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(listOf(Brand.copy(0.25f), BrandDeep.copy(0.4f)))
                    )
                    .border(1.5.dp, Brush.linearGradient(listOf(Brand, Brand2)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = UpdateIconPath,
                    contentDescription = "Update available",
                    tint = Brand,
                    modifier = Modifier.size(36.dp),
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Card ─────────────────────────────────────────────────────────
            Box(
                Modifier
                    .offset(y = cardSlide.value.dp)
                    .alpha(cardAlpha.value)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(listOf(BgCard, BgRaised))
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(listOf(Brand.copy(0.35f), Brand2.copy(0.1f))),
                        RoundedCornerShape(20.dp),
                    )
                    .padding(28.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {

                    if (forceUpdate) {
                        Text(
                            "Update Required",
                            color      = Brand2,
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Text(
                        "New Version Available",
                        color      = White,
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        "Version $latestVersion is ready to install",
                        color    = White.copy(0.55f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )

                    if (changelog.isNotBlank()) {
                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider(color = White.copy(0.06f))
                        Spacer(Modifier.height(16.dp))

                        Text(
                            "What's new",
                            color      = White.copy(0.45f),
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                            modifier   = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            changelog,
                            color    = White.copy(0.75f),
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                    }

                    Spacer(Modifier.height(28.dp))

                    // ── Action area — driven entirely by updateState ───────────
                    when (val s = updateState) {

                        // ── Idle or Cancelled → show Download button ──────────
                        is UpdateState.Idle, is UpdateState.Cancelled -> {
                            val label = if (s is UpdateState.Cancelled) "Try Again" else "Download Update"
                            GradientButton(
                                label    = label,
                                onClick  = { updateManager.startUpdate(downloadUrl) },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            )
                        }

                        // ── Downloading → progress bar + cancel ───────────────
                        is UpdateState.Downloading -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Downloading… ${s.percent}%",
                                    color    = White.copy(0.7f),
                                    fontSize = 13.sp,
                                )
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress       = { s.percent / 100f },
                                    modifier       = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color          = Brand,
                                    trackColor     = White.copy(0.12f),
                                )
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = { updateManager.cancelDownload() }) {
                                    Text("Cancel", color = White.copy(0.4f), fontSize = 13.sp)
                                }
                            }
                        }

                        // ── AwaitingInstallConfirmation or Installing ──────────
                        is UpdateState.AwaitingInstallConfirmation,
                        is UpdateState.Installing -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                CircularProgressIndicator(
                                    color       = Brand,
                                    strokeWidth = 2.dp,
                                    modifier    = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (s is UpdateState.AwaitingInstallConfirmation)
                                        "Waiting for your confirmation…"
                                    else
                                        "Installing…",
                                    color    = White.copy(0.7f),
                                    fontSize = 13.sp,
                                )
                            }
                        }

                        // ── Success ───────────────────────────────────────────
                        is UpdateState.Success -> {
                            Text(
                                "✓ Update installed!",
                                color      = Brand,
                                fontSize   = 16.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign  = TextAlign.Center,
                                modifier   = Modifier.fillMaxWidth(),
                            )
                        }

                        // ── Failed ────────────────────────────────────────────
                        is UpdateState.Failed -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    s.reason,
                                    color    = Color(0xFFFF6B6B),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(12.dp))
                                GradientButton(
                                    label   = "Retry",
                                    onClick = { updateManager.startUpdate(downloadUrl) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                )
                            }
                        }
                    }

                    // ── Skip / Later button (optional update only) ────────────
                    val showSkip = !forceUpdate &&
                        updateState !is UpdateState.Downloading &&
                        updateState !is UpdateState.Installing &&
                        updateState !is UpdateState.AwaitingInstallConfirmation &&
                        updateState !is UpdateState.Success

                    if (showSkip) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick  = onSkip,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Later",
                                color    = White.copy(0.4f),
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Shared gradient button ────────────────────────────────────────────────────

@Composable
private fun GradientButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick  = onClick,
        modifier = modifier,
        shape    = RoundedCornerShape(14.dp),
        colors   = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(listOf(Brand, Brand2)),
                    RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color      = Color.White,
                fontSize   = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
