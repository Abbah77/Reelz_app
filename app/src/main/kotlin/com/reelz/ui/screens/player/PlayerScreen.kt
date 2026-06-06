package com.reelz.ui.screens.player

import androidx.compose.animation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.reelz.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    // Poll position every 1 s
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            vm.pollPosition()
        }
    }

    // Auto-hide controls after 4 s
    LaunchedEffect(ui.showControls) {
        if (ui.showControls) {
            delay(4_000)
            vm.hideControls()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                vm.toggleControls()
            }
    ) {

        // ── ExoPlayer surface ─────────────────────────────────────────────────
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = {
                PlayerView(context).apply {
                    player = vm.exoPlayer
                    useController = false        // we draw our own controls
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            update = { pv -> pv.player = vm.exoPlayer }
        )

        // ── State overlays ────────────────────────────────────────────────────
        when (val s = ui.state) {
            is PlayerState.Resolving -> ScanningOverlay()
            is PlayerState.Buffering -> BufferingOverlay()
            is PlayerState.Error     -> ErrorOverlay(s.msg, s.retryable, vm::retry, onBack)
            else -> {}
        }

        // ── Controls overlay ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = ui.showControls &&
                ui.state !is PlayerState.Resolving &&
                ui.state !is PlayerState.Error,
            enter   = fadeIn(),
            exit    = fadeOut(),
        ) {
            PlayerControls(
                ui      = ui,
                onBack  = onBack,
                onPlayPause  = vm::togglePlayPause,
                onSeekForward  = vm::seekForward,
                onSeekBackward = vm::seekBackward,
                onSeek  = vm::seekTo,
            )
        }
    }
}

// ── Scanning overlay ──────────────────────────────────────────────────────────
@Composable
private fun ScanningOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Primary, strokeWidth = 3.dp, modifier = Modifier.size(52.dp))
            Text("Finding best stream…", color = White80, fontSize = 15.sp)
            Text("Racing all sources in parallel", color = White40, fontSize = 12.sp)
        }
    }
}

// ── Buffering overlay (small spinner, non-blocking) ────────────────────────────
@Composable
private fun BufferingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(40.dp))
    }
}

// ── Error overlay ─────────────────────────────────────────────────────────────
@Composable
private fun ErrorOverlay(msg: String, retryable: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Error, modifier = Modifier.size(56.dp))
            Text("Playback Error", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(msg, color = White60, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, border = BorderStroke(1.dp, Stroke)) {
                    Text("Go Back", color = White60)
                }
                if (retryable) {
                    Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

// ── Player controls overlay ───────────────────────────────────────────────────
@Composable
private fun PlayerControls(
    ui: PlayerUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {

        // Top gradient + back + title
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(.8f), Color.Transparent)))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = White)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(ui.title, color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                    if (ui.episode.isNotBlank())
                        Text(ui.episode, color = White60, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                if (ui.sourceName.isNotBlank()) {
                    Surface(
                        color = Primary.copy(.15f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(
                            ui.sourceName,
                            color = Primary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // Centre play/pause + seek buttons
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSeekBackward, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Replay10, contentDescription = "-10s", tint = White, modifier = Modifier.size(36.dp))
            }
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(White.copy(.15f))
                    .clickable { onPlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (ui.state is PlayerState.Playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = White,
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onSeekForward, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Forward10, contentDescription = "+10s", tint = White, modifier = Modifier.size(36.dp))
            }
        }

        // Bottom gradient + seekbar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.85f))))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Time labels
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(ui.positionMs), color = White80, fontSize = 12.sp)
                Text(formatMs(ui.durationMs), color = White40, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))

            // Seek slider
            val progress = if (ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs.toFloat() else 0f
            val buffered = if (ui.durationMs > 0) ui.bufferedMs.toFloat() / ui.durationMs.toFloat() else 0f

            Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                // Buffered track
                LinearProgressIndicator(
                    progress    = { buffered },
                    modifier    = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color       = White20,
                    trackColor  = White10,
                )
                // Playback slider
                Slider(
                    value        = progress,
                    onValueChange = { f -> onSeek((f * ui.durationMs).toLong()) },
                    modifier     = Modifier.fillMaxWidth(),
                    colors       = SliderDefaults.colors(
                        thumbColor             = Primary,
                        activeTrackColor       = Primary,
                        inactiveTrackColor     = Color.Transparent,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1_000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}
