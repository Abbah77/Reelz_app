package com.axio.reelz.ui.screens.update

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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

@Composable
fun UpdateScreen(
    latestVersion : String,
    downloadUrl   : String,
    changelog     : String = "",
    forceUpdate   : Boolean = false,
    onSkip        : () -> Unit = {},
    updateManager : ApkUpdateManager,
) {
    val updateState by updateManager.state.collectAsState()
    val debugLog    by updateManager.debugLog.collectAsState()

    // Show debug panel — set to false before releasing to users
    var showDebug by remember { mutableStateOf(true) }

    // Pulse on icon
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(
        1f, 1.15f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ps",
    )

    // Entrance
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
            .background(Brush.verticalGradient(listOf(Color(0xFF050510), Color(0xFF080820), Color(0xFF050510)))),
    ) {
        // ── Main content ───────────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {

            // Glow
            Box(
                Modifier
                    .size(260.dp)
                    .background(Brush.radialGradient(listOf(Brand.copy(0.1f), Color.Transparent)))
            )

            Spacer(Modifier.height((-200).dp))

            // Icon
            Box(
                Modifier
                    .size(88.dp)
                    .scale(pulseScale)
                    .alpha(iconAlpha.value)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Brand.copy(0.25f), BrandDeep.copy(0.4f))))
                    .border(1.5.dp, Brush.linearGradient(listOf(Brand, Brand2)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", color = Brand, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(28.dp))

            // Card
            Box(
                Modifier
                    .offset(y = cardSlide.value.dp)
                    .alpha(cardAlpha.value)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.verticalGradient(listOf(BgCard, BgRaised)))
                    .border(1.dp, Brush.linearGradient(listOf(Brand.copy(0.35f), Brand2.copy(0.1f))), RoundedCornerShape(20.dp))
                    .padding(28.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {

                    if (forceUpdate) {
                        Text("UPDATE REQUIRED", color = Brand2, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Spacer(Modifier.height(8.dp))
                    }

                    Text("New Version Available", color = Color.White,
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

                    Spacer(Modifier.height(6.dp))

                    Text("Version $latestVersion is ready to install",
                        color = Color.White.copy(0.55f), fontSize = 14.sp, textAlign = TextAlign.Center)

                    if (changelog.isNotBlank()) {
                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider(color = Color.White.copy(0.06f))
                        Spacer(Modifier.height(16.dp))
                        Text("WHAT'S NEW", color = Color.White.copy(0.4f), fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                            modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(changelog, color = Color.White.copy(0.75f),
                            fontSize = 13.sp, lineHeight = 20.sp)
                    }

                    Spacer(Modifier.height(28.dp))

                    // ── State-driven action area ───────────────────────────────
                    when (val s = updateState) {

                        is UpdateState.Idle, is UpdateState.Cancelled -> {
                            val label = if (s is UpdateState.Cancelled) "Try Again" else "Download Update"
                            GradientButton(label, { updateManager.startUpdate(downloadUrl) },
                                Modifier.fillMaxWidth().height(52.dp))
                        }

                        is UpdateState.Downloading -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                // Bytes info
                                val byteInfo = if (s.totalBytes > 0) {
                                    val dl  = "%.1f".format(s.bytesDownloaded / 1_048_576f)
                                    val tot = "%.1f".format(s.totalBytes / 1_048_576f)
                                    "$dl MB / $tot MB"
                                } else {
                                    val dl = "%.1f".format(s.bytesDownloaded / 1_048_576f)
                                    "$dl MB downloaded"
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("Downloading…", color = Color.White.copy(0.7f), fontSize = 13.sp)
                                    Text("${s.percent}%", color = Brand, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress    = { s.percent / 100f },
                                    modifier    = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color       = Brand,
                                    trackColor  = Color.White.copy(0.12f),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(byteInfo, color = Color.White.copy(0.45f), fontSize = 12.sp)
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = { updateManager.cancelDownload() }) {
                                    Text("Cancel", color = Color.White.copy(0.4f), fontSize = 13.sp)
                                }
                            }
                        }

                        is UpdateState.AwaitingInstallConfirmation, is UpdateState.Installing -> {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(color = Brand, strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (s is UpdateState.AwaitingInstallConfirmation)
                                        "Waiting for your confirmation…"
                                    else "Installing…",
                                    color = Color.White.copy(0.7f), fontSize = 13.sp,
                                )
                            }
                        }

                        is UpdateState.Success -> {
                            Text("✓ Update installed!", color = Brand,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }

                        is UpdateState.Failed -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(s.reason, color = Color(0xFFFF6B6B),
                                    fontSize = 13.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(12.dp))
                                GradientButton("Retry", { updateManager.startUpdate(downloadUrl) },
                                    Modifier.fillMaxWidth().height(48.dp))
                            }
                        }
                    }

                    // Skip / Later
                    val showSkip = !forceUpdate &&
                        updateState !is UpdateState.Downloading &&
                        updateState !is UpdateState.Installing &&
                        updateState !is UpdateState.AwaitingInstallConfirmation &&
                        updateState !is UpdateState.Success
                    if (showSkip) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                            Text("Later", color = Color.White.copy(0.4f), fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Debug toggle button
            TextButton(onClick = { showDebug = !showDebug }) {
                Text(
                    if (showDebug) "Hide Debug Log" else "Show Debug Log",
                    color = Color.White.copy(0.25f), fontSize = 11.sp,
                )
            }
        }

        // ── Debug overlay ──────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showDebug,
            enter   = slideInVertically { it } + fadeIn(),
            exit    = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            DebugPanel(entries = debugLog, updateState = updateState)
        }
    }
}

@Composable
private fun DebugPanel(
    entries     : List<com.axio.reelz.update.DebugEntry>,
    updateState : UpdateState,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to latest entry
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .background(
                Color(0xEE0A0A1A),
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            )
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(Brand.copy(0.3f), Brand2.copy(0.1f))),
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            )
            .padding(12.dp),
    ) {
        // Header row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🐛 Debug Log", color = Brand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            // State badge
            val (badgeColor, badgeLabel) = when (updateState) {
                is UpdateState.Idle                       -> Color(0xFF666666) to "IDLE"
                is UpdateState.Downloading                -> Color(0xFF3B82F6) to "DL ${updateState.percent}%"
                is UpdateState.AwaitingInstallConfirmation -> Color(0xFFF59E0B) to "AWAITING"
                is UpdateState.Installing                 -> Color(0xFF8B5CF6) to "INSTALLING"
                is UpdateState.Cancelled                  -> Color(0xFF6B7280) to "CANCELLED"
                is UpdateState.Failed                     -> Color(0xFFEF4444) to "FAILED"
                is UpdateState.Success                    -> Color(0xFF10B981) to "SUCCESS"
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor.copy(0.2f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(badgeLabel, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Color.White.copy(0.06f))
        Spacer(Modifier.height(6.dp))

        if (entries.isEmpty()) {
            Text("No events yet — tap Download to begin",
                color = Color.White.copy(0.3f), fontSize = 11.sp)
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                items(entries) { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            entry.time,
                            color    = Color.White.copy(0.35f),
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(
                            entry.message,
                            color    = when {
                                entry.message.startsWith("ERROR") || entry.message.startsWith("FATAL") ->
                                    Color(0xFFFF6B6B)
                                entry.message.contains("SUCCESS") || entry.message.contains("PASSED") ->
                                    Color(0xFF10B981)
                                entry.message.contains("WARN") || entry.message.contains("FAILED") ->
                                    Color(0xFFF59E0B)
                                else -> Color.White.copy(0.7f)
                            },
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            lineHeight = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GradientButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Brand, Brand2)), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}
