package com.reelz.ui.screens.player

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.reelz.data.model.MediaType
import com.reelz.ui.components.friendlyError
import com.reelz.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource

@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val tmdbId    = intent.getIntExtra("tmdbId", -1)
        val typeStr   = intent.getStringExtra("mediaType") ?: "MOVIE"
        val season    = intent.getIntExtra("season", 0)
        val episode   = intent.getIntExtra("episode", 0)
        val title     = intent.getStringExtra("title") ?: ""
        val poster    = intent.getStringExtra("posterPath")
        val mediaType = if (typeStr == "TV") MediaType.TV else MediaType.MOVIE

        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Brand)) {
                PlayerScreen(
                    vm        = vm,
                    tmdbId    = tmdbId,
                    mediaType = mediaType,
                    season    = season,
                    episode   = episode,
                    title     = title,
                    poster    = poster,
                    onBack    = { finish() },
                )
            }
        }
    }

    override fun onPause()   { super.onPause();   vm.exoPlayer?.pause() }
    override fun onResume()  { super.onResume();  vm.exoPlayer?.let { if (it.mediaItemCount > 0) it.play() } }
    override fun onDestroy() { super.onDestroy(); vm.release() }
}

@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    tmdbId: Int, mediaType: MediaType,
    season: Int, episode: Int,
    title: String, poster: String?,
    onBack: () -> Unit,
) {
    val ctx   = LocalContext.current
    val ui    by vm.ui.collectAsState()
    val player by vm.exoPlayerFlow.collectAsState()
    val scope = rememberCoroutineScope()

    // Init player once
    LaunchedEffect(tmdbId, season, episode) {
        vm.init(ctx, tmdbId, mediaType, season, episode, title, poster)
    }

    // Poll position every 500ms
    LaunchedEffect(Unit) {
        while (true) { vm.pollPosition(); delay(500) }
    }

    // Auto-hide controls after 4s
    LaunchedEffect(ui.showControls) {
        if (ui.showControls && ui.state is PlayerState.Playing) {
            delay(4_000)
            vm.hideControls()
        }
    }

    // Dialogs
    var showSpeedDialog   by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                if (!ui.isLocked) vm.toggleControls()
            }
    ) {
        // ── ExoPlayer surface ────────────────────────────────────────────
        key(player) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                    }
                },
                update = { pv -> pv.player = vm.exoPlayer },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ── Resolving overlay ────────────────────────────────────────────
        AnimatedVisibility(
            ui.state is PlayerState.Resolving || ui.state is PlayerState.Buffering,
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Brand, strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (ui.state is PlayerState.Resolving) "Finding best stream…" else "Buffering…",
                        color = White60, fontSize = 13.sp,
                    )
                    if (ui.sourceName.isNotBlank()) Text("via ${ui.sourceName}", color = White40, fontSize = 11.sp)
                }
            }
        }

        // ── Error overlay ────────────────────────────────────────────────
        AnimatedVisibility(ui.state is PlayerState.Error, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(.85f)), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Error, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        friendlyError((ui.state as? PlayerState.Error)?.msg ?: ""),
                        color = White80, fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onBack, border = BorderStroke(1.dp, GlassBorderMd)) {
                            Text("Go Back", color = White60)
                        }
                        Button(onClick = { vm.retry() }, colors = ButtonDefaults.buttonColors(containerColor = Brand)) {
                            Text("Retry")
                        }
                    }
                }
            }
        }

        // ── Controls overlay (only when not locked + controls visible) ────
        AnimatedVisibility(
            ui.showControls && ui.state !is PlayerState.Resolving && ui.state !is PlayerState.Error,
            enter = fadeIn(tween(180)), exit = fadeOut(tween(300)),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Top gradient
                Box(Modifier.fillMaxWidth().height(120.dp).background(
                    Brush.verticalGradient(listOf(Color.Black.copy(.7f), Color.Transparent))
                ))
                // Bottom gradient
                Box(Modifier.fillMaxWidth().height(140.dp).align(Alignment.BottomCenter).background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.8f)))
                ))

                // ── Top bar ────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = White)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(ui.title, color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                        if (ui.episodeLabel.isNotBlank()) Text(ui.episodeLabel, color = White60, fontSize = 12.sp)
                    }
                    // Lock
                    IconButton(onClick = { vm.toggleLock() }) {
                        Icon(Icons.Default.Lock, null, tint = if (ui.isLocked) Brand else White)
                    }
                }

                // ── Center transport ───────────────────────────────────
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.seekBackward(10) }) {
                        Icon(Icons.Default.Replay10, null, tint = White, modifier = Modifier.size(36.dp))
                    }
                    Box(
                        Modifier.size(64.dp).clip(CircleShape)
                            .background(White.copy(.15f))
                            .clickable { vm.togglePlayPause() },
                        Alignment.Center,
                    ) {
                        Icon(
                            if (ui.state is PlayerState.Playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null, tint = White, modifier = Modifier.size(34.dp),
                        )
                    }
                    IconButton(onClick = { vm.seekForward(10) }) {
                        Icon(Icons.Default.Forward10, null, tint = White, modifier = Modifier.size(36.dp))
                    }
                }

                // ── Bottom controls ────────────────────────────────────
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // Progress bar
                    if (ui.durationMs > 0) {
                        Column {
                            // Buffered track behind seek bar
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(White.copy(.2f))) {
                                val buffPct = (ui.bufferedMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)
                                Box(Modifier.fillMaxWidth(buffPct).fillMaxHeight().background(White.copy(.35f)))
                            }
                            Slider(
                                value = (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f),
                                onValueChange = { vm.seekTo((it * ui.durationMs).toLong()) },
                                colors = SliderDefaults.colors(thumbColor = Brand, activeTrackColor = Brand, inactiveTrackColor = White.copy(.2f)),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Text(formatMs(ui.positionMs), color = White60, fontSize = 11.sp)
                                Spacer(Modifier.weight(1f))
                                Text(formatMs(ui.durationMs), color = White60, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Bottom action row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Source label
                        if (ui.sourceName.isNotBlank()) {
                            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Brand.copy(.18f)).border(1.dp, Brand.copy(.4f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                                Text(ui.sourceName, color = Brand, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // Mute
                        IconButton(onClick = { vm.toggleMute() }) {
                            Icon(if (ui.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null, tint = White, modifier = Modifier.size(22.dp))
                        }
                        // Speed
                        TextButton(onClick = { showSpeedDialog = true }) {
                            Text("${ui.playbackSpeed}x", color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        // Quality
                        TextButton(onClick = { showQualityDialog = true }) {
                            Text(ui.selectedQuality, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ── Locked overlay ───────────────────────────────────────────────
        if (ui.isLocked) {
            Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}) {
                Box(
                    Modifier.align(Alignment.CenterStart).padding(16.dp)
                        .clip(RoundedCornerShape(100.dp)).background(GlassMd)
                        .border(1.dp, GlassBorderMd, RoundedCornerShape(100.dp))
                        .clickable { vm.toggleLock() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LockOpen, null, tint = White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Unlock", color = White, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // ── Speed dialog ──────────────────────────────────────────────────────
    if (showSpeedDialog) {
        PlayerOptionDialog(
            title = "Playback Speed",
            options = listOf("0.5x" to 0.5f, "0.75x" to 0.75f, "1x" to 1f, "1.25x" to 1.25f, "1.5x" to 1.5f, "2x" to 2f),
            selected = "${ui.playbackSpeed}x",
            onSelect = { _, v -> vm.setSpeed(v); showSpeedDialog = false },
            onDismiss = { showSpeedDialog = false },
        )
    }

    // ── Quality dialog ────────────────────────────────────────────────────
    if (showQualityDialog) {
        PlayerOptionDialog(
            title = "Quality",
            options = ui.availableQualities.map { it.label to it.label },
            selected = ui.selectedQuality,
            onSelect = { label, _ -> vm.setQuality(label); showQualityDialog = false },
            onDismiss = { showQualityDialog = false },
        )
    }
}

@Composable
fun <T> PlayerOptionDialog(
    title: String,
    options: List<Pair<String, T>>,
    selected: String,
    onSelect: (String, T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgCard,
        title = { Text(title, color = White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(label, value) }.padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = label == selected, onClick = { onSelect(label, value) },
                            colors = RadioButtonDefaults.colors(selectedColor = Brand))
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = if (label == selected) Brand else White80)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Brand) } },
    )
}

fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
