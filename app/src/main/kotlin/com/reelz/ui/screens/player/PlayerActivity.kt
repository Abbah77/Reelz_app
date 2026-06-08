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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.reelz.data.model.MediaType
import com.reelz.ui.components.*
import com.reelz.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Custom player icons
private val IconArrowLeft: ImageVector get() = ImageVector.Builder("ArrowLeft", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconLock: ImageVector get() = ImageVector.Builder("Lock", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(7f, 11f); lineTo(7f, 7f); arcTo(5f, 5f, 0f, false, true, 17f, 7f); lineTo(17f, 11f)
        moveTo(5f, 11f); lineTo(19f, 11f); arcTo(2f, 2f, 0f, false, true, 21f, 13f); lineTo(21f, 20f)
        arcTo(2f, 2f, 0f, false, true, 19f, 22f); lineTo(5f, 22f); arcTo(2f, 2f, 0f, false, true, 3f, 20f)
        lineTo(3f, 13f); arcTo(2f, 2f, 0f, false, true, 5f, 11f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, fill = SolidColor(Color.Transparent))
}.build()

private val IconUnlock: ImageVector get() = ImageVector.Builder("Unlock", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(7f, 11f); lineTo(7f, 7f); arcTo(5f, 5f, 0f, false, true, 15.9f, 5.7f)
        moveTo(5f, 11f); lineTo(19f, 11f); arcTo(2f, 2f, 0f, false, true, 21f, 13f); lineTo(21f, 20f)
        arcTo(2f, 2f, 0f, false, true, 19f, 22f); lineTo(5f, 22f); arcTo(2f, 2f, 0f, false, true, 3f, 20f)
        lineTo(3f, 13f); arcTo(2f, 2f, 0f, false, true, 5f, 11f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, fill = SolidColor(Color.Transparent))
}.build()

private val IconPause: ImageVector get() = ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(6f, 4f); lineTo(6f, 20f); moveTo(18f, 4f); lineTo(18f, 20f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 2.5f, strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconReplay10: ImageVector get() = ImageVector.Builder("R10", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 5f); arcTo(8f, 8f, 0f, false, false, 4f, 13f); arcTo(8f, 8f, 0f, false, false, 20f, 13f)
        moveTo(12f, 5f); lineTo(9f, 2f); moveTo(12f, 5f); lineTo(15f, 2f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
    addPath(pathData = PathData { moveTo(10f, 11f); lineTo(10f, 16f); moveTo(13f, 11f); lineTo(14f, 11f); arcTo(1f, 2.5f, 0f, false, true, 14f, 16f); lineTo(13f, 16f); close() },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.3f, fill = SolidColor(Color.Transparent))
}.build()

private val IconForward10: ImageVector get() = ImageVector.Builder("F10", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 5f); arcTo(8f, 8f, 0f, false, true, 20f, 13f); arcTo(8f, 8f, 0f, false, true, 4f, 13f)
        moveTo(12f, 5f); lineTo(9f, 2f); moveTo(12f, 5f); lineTo(15f, 2f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
    addPath(pathData = PathData { moveTo(10f, 11f); lineTo(10f, 16f); moveTo(13f, 11f); lineTo(14f, 11f); arcTo(1f, 2.5f, 0f, false, true, 14f, 16f); lineTo(13f, 16f); close() },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.3f, fill = SolidColor(Color.Transparent))
}.build()

private val IconVolumeUp: ImageVector get() = ImageVector.Builder("VolUp", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(11f, 5f); lineTo(6f, 9f); lineTo(2f, 9f); lineTo(2f, 15f); lineTo(6f, 15f); lineTo(11f, 19f); close()
        moveTo(15.54f, 8.46f); arcTo(5f, 5f, 0f, false, true, 15.54f, 15.54f)
        moveTo(19.07f, 4.93f); arcTo(10f, 10f, 0f, false, true, 19.07f, 19.07f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, fill = SolidColor(Color.Transparent))
}.build()

private val IconVolumeOff: ImageVector get() = ImageVector.Builder("VolOff", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(11f, 5f); lineTo(6f, 9f); lineTo(2f, 9f); lineTo(2f, 15f); lineTo(6f, 15f); lineTo(11f, 19f); close()
        moveTo(23f, 9f); lineTo(17f, 15f); moveTo(17f, 9f); lineTo(23f, 15f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconError: ImageVector get() = ImageVector.Builder("Error", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
        moveTo(12f, 8f); lineTo(12f, 12f); moveTo(12f, 16f); lineTo(12f, 16.01f)
    }, stroke = SolidColor(Color(0xFFFF3B30)), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                PlayerScreen(vm = vm, tmdbId = tmdbId, mediaType = mediaType, season = season,
                    episode = episode, title = title, poster = poster, onBack = { finish() })
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
    val ctx    = LocalContext.current
    val ui     by vm.ui.collectAsState()
    val player by vm.exoPlayerFlow.collectAsState()
    val scope  = rememberCoroutineScope()

    LaunchedEffect(tmdbId, season, episode) { vm.init(ctx, tmdbId, mediaType, season, episode, title, poster) }
    LaunchedEffect(Unit) { while (true) { vm.pollPosition(); delay(500) } }
    LaunchedEffect(ui.showControls) {
        if (ui.showControls && ui.state is PlayerState.Playing) { delay(4_000); vm.hideControls() }
    }

    var showSpeedDialog   by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                if (!ui.isLocked) vm.toggleControls()
            }
    ) {
        // ExoPlayer surface
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

        // ── Buffering / Resolving overlay ────────────────────────────────
        AnimatedVisibility(
            ui.state is PlayerState.Resolving || ui.state is PlayerState.Buffering,
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CinematicSpinner(size = 56.dp)
                    Text(
                        if (ui.state is PlayerState.Resolving) "Finding best stream…" else "Buffering…",
                        color = White60, fontSize = 14.sp,
                    )
                    if (ui.sourceName.isNotBlank()) {
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(AmberGlass)
                                .border(1.dp, AmberBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text("via ${ui.sourceName}", color = Brand, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ── Error overlay ────────────────────────────────────────────────
        AnimatedVisibility(ui.state is PlayerState.Error, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(80.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(Error.copy(.2f), Color.Transparent)))
                            .border(1.dp, Error.copy(.4f), CircleShape))
                        Icon(IconError, null, tint = Error, modifier = Modifier.size(34.dp))
                    }
                    Text(
                        friendlyError((ui.state as? PlayerState.Error)?.msg ?: ""),
                        color = White80, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 22.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GhostButton("Go Back", onClick = onBack)
                        BrandButton("Retry", onClick = { vm.retry() })
                    }
                }
            }
        }

        // ── Controls overlay ─────────────────────────────────────────────
        AnimatedVisibility(
            ui.showControls && ui.state !is PlayerState.Resolving && ui.state !is PlayerState.Error,
            enter = fadeIn(tween(180)), exit = fadeOut(tween(300)),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Top gradient
                Box(Modifier.fillMaxWidth().height(130.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
                // Bottom gradient
                Box(Modifier.fillMaxWidth().height(160.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))

                // ── Top bar ────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(GlassMd)
                            .border(1.dp, GlassBorderMd, CircleShape).clickable(onClick = onBack),
                        Alignment.Center,
                    ) { Icon(IconArrowLeft, null, tint = White, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ui.title, color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                        if (ui.episodeLabel.isNotBlank()) Text(ui.episodeLabel, color = White60, fontSize = 12.sp)
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (ui.isLocked) AmberGlass else GlassMd)
                            .border(1.dp, if (ui.isLocked) AmberBorder else GlassBorderMd, CircleShape)
                            .clickable { vm.toggleLock() },
                        Alignment.Center,
                    ) {
                        Icon(if (ui.isLocked) IconLock else IconUnlock, null, tint = if (ui.isLocked) Brand else White, modifier = Modifier.size(18.dp))
                    }
                }

                // ── Center transport controls ───────────────────────────
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerControlBtn(icon = IconReplay10, size = 38.dp) { vm.seekBackward(10) }

                    // Play/Pause main button
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(
                                Brush.radialGradient(listOf(Brand.copy(.35f), Color.Transparent))
                            )
                            .border(2.dp, Brand.copy(.6f), CircleShape)
                            .background(GlassHeavy)
                            .clickable { vm.togglePlayPause() },
                        Alignment.Center,
                    ) {
                        Icon(
                            if (ui.state is PlayerState.Playing) IconPause else IconPlay,
                            null, tint = White, modifier = Modifier.size(32.dp),
                        )
                    }

                    PlayerControlBtn(icon = IconForward10, size = 38.dp) { vm.seekForward(10) }
                }

                // ── Bottom controls ────────────────────────────────────
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    if (ui.durationMs > 0) {
                        val progress = (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)
                        val buffered = (ui.bufferedMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)

                        // Buffered track
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(White.copy(.15f))) {
                            Box(Modifier.fillMaxWidth(buffered).fillMaxHeight().background(White.copy(.3f)))
                        }
                        Slider(
                            value = progress,
                            onValueChange = { vm.seekTo((it * ui.durationMs).toLong()) },
                            colors = SliderDefaults.colors(
                                thumbColor = Brand2,
                                activeTrackColor = Brush.horizontalGradient(listOf(Brand, Brand2)).let { Brand },
                                inactiveTrackColor = White.copy(.15f),
                            ),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(formatMs(ui.positionMs), color = White60, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            Text(formatMs(ui.durationMs), color = White60, fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (ui.sourceName.isNotBlank()) {
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(AmberGlass)
                                    .border(1.dp, AmberBorder, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text(ui.sourceName, color = Brand, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                        Spacer(Modifier.weight(1f))
                        // Mute
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(GlassMd)
                                .border(1.dp, GlassBorderMd, CircleShape).clickable { vm.toggleMute() },
                            Alignment.Center,
                        ) { Icon(if (ui.isMuted) IconVolumeOff else IconVolumeUp, null, tint = White, modifier = Modifier.size(18.dp)) }
                        // Speed
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(GlassMd)
                                .border(1.dp, GlassBorderMd, RoundedCornerShape(8.dp))
                                .clickable { showSpeedDialog = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) { Text("${ui.playbackSpeed}×", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        // Quality
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(GlassMd)
                                .border(1.dp, GlassBorderMd, RoundedCornerShape(8.dp))
                                .clickable { showQualityDialog = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) { Text(ui.selectedQuality, color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        // ── Lock overlay ─────────────────────────────────────────────────
        if (ui.isLocked) {
            Box(
                Modifier.fillMaxSize()
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            ) {
                Box(
                    Modifier.align(Alignment.CenterStart).padding(20.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(AmberGlass)
                        .border(1.dp, AmberBorder, RoundedCornerShape(100.dp))
                        .clickable { vm.toggleLock() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(IconUnlock, null, tint = Brand, modifier = Modifier.size(16.dp))
                        Text("Unlock", color = Brand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // Speed dialog
    if (showSpeedDialog) {
        PlayerOptionDialog(
            title    = "Playback Speed",
            options  = listOf("0.5×" to 0.5f, "0.75×" to 0.75f, "1×" to 1f, "1.25×" to 1.25f, "1.5×" to 1.5f, "2×" to 2f),
            selected = "${ui.playbackSpeed}×",
            onSelect = { _, v -> vm.setSpeed(v); showSpeedDialog = false },
            onDismiss = { showSpeedDialog = false },
        )
    }

    // Quality dialog
    if (showQualityDialog) {
        PlayerOptionDialog(
            title    = "Quality",
            options  = ui.availableQualities.map { it.label to it.label },
            selected = ui.selectedQuality,
            onSelect = { label, _ -> vm.setQuality(label); showQualityDialog = false },
            onDismiss = { showQualityDialog = false },
        )
    }
}

@Composable
private fun PlayerControlBtn(icon: ImageVector, size: Dp = 32.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size + 12.dp).clip(CircleShape).background(GlassMd)
            .border(1.dp, GlassBorderMd, CircleShape).clickable(onClick = onClick),
        Alignment.Center,
    ) { Icon(icon, null, tint = White, modifier = Modifier.size(size)) }
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
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(title, color = White, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
        },
        text = {
            Column {
                options.forEach { (label, value) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (label == selected) AmberGlass else Color.Transparent)
                            .clickable { onSelect(label, value) }
                            .padding(vertical = 12.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = label == selected, onClick = { onSelect(label, value) },
                            colors = RadioButtonDefaults.colors(selectedColor = Brand, unselectedColor = White40))
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = if (label == selected) Brand else White80, fontWeight = if (label == selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Brand, fontWeight = FontWeight.SemiBold) }
        },
    )
}

fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
