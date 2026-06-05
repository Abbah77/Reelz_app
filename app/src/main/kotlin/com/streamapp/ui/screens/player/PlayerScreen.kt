package com.streamapp.ui.screens.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.streamapp.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    streamUrl: String,
    title: String = "",
    episodeLabel: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().also { player ->
            val dataSourceFactory = DefaultDataSource.Factory(context)
            val mediaSource = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(streamUrl))
            } else {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(streamUrl))
            }
            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true
        }
    }

    var isPlaying   by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    var position    by remember { mutableStateOf(0L) }
    var duration    by remember { mutableStateOf(0L) }
    var buffering   by remember { mutableStateOf(true) }
    var speed       by remember { mutableStateOf(1f) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            position = exoPlayer.currentPosition
            duration = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4000)
            showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { offset ->
                        val centerX = size.width / 2f
                        if (offset.x < centerX) {
                            exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                        } else {
                            exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L)
                        }
                    },
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Primary,
                strokeWidth = 3.dp,
            )
        }

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit  = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
            ) {
                // ── Top bar ───────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        if (title.isNotEmpty()) {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = White, maxLines = 1)
                        }
                        if (episodeLabel.isNotEmpty()) {
                            Text(episodeLabel, style = MaterialTheme.typography.bodySmall, color = White60)
                        }
                    }
                    Box {
                        TextButton(onClick = { showSpeedMenu = true }) {
                            Text("${speed}x", style = MaterialTheme.typography.labelLarge, color = White)
                        }
                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false },
                            containerColor = Surface800,
                        ) {
                            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Text("${s}x", color = if (s == speed) Primary else White)
                                    },
                                    onClick = {
                                        speed = s
                                        exoPlayer.setPlaybackSpeed(s)
                                        showSpeedMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = White)
                    }
                }

                // ── Center controls ───────────────────────────────────────────
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                        },
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.Filled.Replay10, contentDescription = "Rewind 10s", tint = White, modifier = Modifier.size(36.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Primary)
                            .clickable { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = White,
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    IconButton(
                        onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L) },
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.Filled.Forward10, contentDescription = "Forward 10s", tint = White, modifier = Modifier.size(36.dp))
                    }
                }

                // ── Bottom seek bar ───────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    val progress = if (duration > 0L) position.toFloat() / duration.toFloat() else 0f
                    Slider(
                        value = progress.coerceIn(0f, 1f),
                        onValueChange = { v -> exoPlayer.seekTo((v * duration).toLong()) },
                        colors = SliderDefaults.colors(
                            thumbColor        = Primary,
                            activeTrackColor  = Primary,
                            inactiveTrackColor = White20,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = White60)
                        Text(formatTime(duration),  style = MaterialTheme.typography.labelSmall, color = White40)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
