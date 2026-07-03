package com.axio.reelz.ui.screens.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.google.ads.interactivemedia.v3.api.AdErrorEvent
import com.google.ads.interactivemedia.v3.api.AdEvent
import com.google.ads.interactivemedia.v3.api.AdsLoader
import com.google.ads.interactivemedia.v3.api.AdsManagerLoadedEvent
import com.google.ads.interactivemedia.v3.api.AdsRequest
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.ads.ImaPreRollView
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.draw.drawBehind

// ─────────────────────────────────────────────────────────────────────────────
// Custom vector icons
// ─────────────────────────────────────────────────────────────────────────────

private val IconArrowLeft: ImageVector
    get() = ImageVector.Builder("ArrowLeft", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(19f, 12f); lineTo(5f, 12f)
                moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconLock: ImageVector
    get() = ImageVector.Builder("Lock", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(7f, 11f); lineTo(7f, 7f)
                arcTo(5f, 5f, 0f, false, true, 17f, 7f); lineTo(17f, 11f)
                moveTo(5f, 11f); lineTo(19f, 11f)
                arcTo(2f, 2f, 0f, false, true, 21f, 13f); lineTo(21f, 20f)
                arcTo(2f, 2f, 0f, false, true, 19f, 22f); lineTo(5f, 22f)
                arcTo(2f, 2f, 0f, false, true, 3f, 20f); lineTo(3f, 13f)
                arcTo(2f, 2f, 0f, false, true, 5f, 11f)
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.7f,
            fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconUnlock: ImageVector
    get() = ImageVector.Builder("Unlock", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(7f, 11f); lineTo(7f, 7f)
                arcTo(5f, 5f, 0f, false, true, 15.9f, 5.7f)
                moveTo(5f, 11f); lineTo(19f, 11f)
                arcTo(2f, 2f, 0f, false, true, 21f, 13f); lineTo(21f, 20f)
                arcTo(2f, 2f, 0f, false, true, 19f, 22f); lineTo(5f, 22f)
                arcTo(2f, 2f, 0f, false, true, 3f, 20f); lineTo(3f, 13f)
                arcTo(2f, 2f, 0f, false, true, 5f, 11f)
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.7f,
            fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconPause: ImageVector
    get() = ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(6f, 4f); lineTo(6f, 20f)
                moveTo(18f, 4f); lineTo(18f, 20f)
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 2.5f,
            strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconVolumeUp: ImageVector
    get() = ImageVector.Builder("VolUp", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(11f, 5f); lineTo(6f, 9f); lineTo(2f, 9f)
                lineTo(2f, 15f); lineTo(6f, 15f); lineTo(11f, 19f); close()
                moveTo(15.54f, 8.46f); arcTo(5f, 5f, 0f, false, true, 15.54f, 15.54f)
                moveTo(19.07f, 4.93f); arcTo(10f, 10f, 0f, false, true, 19.07f, 19.07f)
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
            fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconVolumeOff: ImageVector
    get() = ImageVector.Builder("VolOff", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(11f, 5f); lineTo(6f, 9f); lineTo(2f, 9f)
                lineTo(2f, 15f); lineTo(6f, 15f); lineTo(11f, 19f); close()
                moveTo(23f, 9f); lineTo(17f, 15f)
                moveTo(17f, 9f); lineTo(23f, 15f)
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconBrightness: ImageVector
    get() = ImageVector.Builder("Bright", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(12f, 1f); lineTo(12f, 3f)
                moveTo(12f, 21f); lineTo(12f, 23f)
                moveTo(4.22f, 4.22f); lineTo(5.64f, 5.64f)
                moveTo(18.36f, 18.36f); lineTo(19.78f, 19.78f)
                moveTo(1f, 12f); lineTo(3f, 12f)
                moveTo(21f, 12f); lineTo(23f, 12f)
                moveTo(4.22f, 19.78f); lineTo(5.64f, 18.36f)
                moveTo(18.36f, 5.64f); lineTo(19.78f, 4.22f)
                moveTo(12f, 17f); arcTo(5f, 5f, 0f, false, true, 12f, 7f); arcTo(5f, 5f, 0f, false, true, 12f, 17f)
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconError: ImageVector
    get() = ImageVector.Builder("Error", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f)
                arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
                moveTo(12f, 8f); lineTo(12f, 12f)
                moveTo(12f, 16f); lineTo(12f, 16.01f)
            },
            stroke = SolidColor(Color(0xFFFF3B30)), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconSubtitles: ImageVector
    get() = ImageVector.Builder("Subs", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(3f, 5f); lineTo(21f, 5f)
                arcTo(2f, 2f, 0f, false, true, 21f, 19f); lineTo(3f, 19f)
                arcTo(2f, 2f, 0f, false, true, 3f, 5f); close()
                moveTo(7f, 12f); lineTo(11f, 12f)
                moveTo(13f, 12f); lineTo(17f, 12f)
                moveTo(7f, 15f); lineTo(13f, 15f)
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconSearch: ImageVector
    get() = ImageVector.Builder("Search", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(11f, 11f); arcTo(7f, 7f, 0f, false, true, 4f, 11f); arcTo(7f, 7f, 0f, false, true, 11f, 4f); arcTo(7f, 7f, 0f, false, true, 18f, 11f); arcTo(7f, 7f, 0f, false, true, 11f, 11f)
                moveTo(16f, 16f); lineTo(20f, 20f)
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconClose: ImageVector
    get() = ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(18f, 6f); lineTo(6f, 18f)
                moveTo(6f, 6f); lineTo(18f, 18f)
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconCheck: ImageVector
    get() = ImageVector.Builder("Check", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(20f, 6f); lineTo(9f, 17f); lineTo(4f, 12f)
            },
            stroke = SolidColor(Color(0xFF0A84FF)), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconWifi: ImageVector
    get() = ImageVector.Builder("Wifi", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(5f, 12.55f); arcTo(11f, 11f, 0f, false, true, 19f, 12.55f)
                moveTo(1.42f, 9f); arcTo(16f, 16f, 0f, false, true, 22.58f, 9f)
                moveTo(8.53f, 16.11f); arcTo(6f, 6f, 0f, false, true, 15.47f, 16.11f)
                moveTo(12f, 20f); lineTo(12f, 20.01f)
            },
            stroke = SolidColor(Color(0xFFFF9A00)), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent)
        )
    }.build()

private val IconTimerOff: ImageVector
    get() = ImageVector.Builder("TimerOff", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(12f, 6f); arcTo(9f, 9f, 0f, false, true, 21f, 15f); arcTo(9f, 9f, 0f, false, true, 12f, 24f); arcTo(9f, 9f, 0f, false, true, 3f, 15f); arcTo(9f, 9f, 0f, false, true, 12f, 6f)
                moveTo(9f, 1f); lineTo(15f, 1f)
                moveTo(12f, 6f); lineTo(12f, 2f)
                moveTo(12f, 10f); lineTo(12f, 15f); lineTo(15f, 18f)
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent)
        )
    }.build()

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val tmdbId        = intent.getIntExtra("tmdbId", -1)
        val typeStr       = intent.getStringExtra("mediaType") ?: "MOVIE"
        val season        = intent.getIntExtra("season", 0)
        val episode       = intent.getIntExtra("episode", 0)
        val title         = intent.getStringExtra("title") ?: ""
        val poster        = intent.getStringExtra("posterPath")
        val mediaType     = if (typeStr == "TV") MediaType.TV else MediaType.MOVIE
        val streamUrl     = intent.getStringExtra("streamUrl")
        val streamIsHls   = intent.getBooleanExtra("streamIsHls", false)
        val streamReferer = intent.getStringExtra("streamReferer") ?: ""
        val streamOrigin  = intent.getStringExtra("streamOrigin") ?: ""
        val downloadId    = intent.getStringExtra("downloadId")

        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Brand)) {
                PlayerScreen(
                    vm          = vm,
                    tmdbId      = tmdbId, mediaType = mediaType,
                    season      = season, episode = episode,
                    title       = title, poster = poster,
                    onBack      = { finish() },
                    streamUrl   = streamUrl, streamIsHls = streamIsHls,
                    streamReferer = streamReferer, streamOrigin = streamOrigin,
                    downloadId  = downloadId,
                )
            }
        }
    }

    override fun onPause()   {
        super.onPause()
        // Premium users keep playing when the screen locks or another app briefly
        // covers this one (background_play tier limit). Free users still pause —
        // this preserves the original behavior for them exactly as before.
        if (!vm.canBackgroundPlay()) vm.exoPlayer?.pause()
    }
    override fun onResume()  { super.onResume();  vm.exoPlayer?.let { if (it.mediaItemCount > 0) it.play() } }
    override fun onDestroy() { super.onDestroy(); vm.release(this) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Gesture hint type
// ─────────────────────────────────────────────────────────────────────────────

private enum class GestureType { NONE, VOLUME, BRIGHTNESS, SEEK }

// ─────────────────────────────────────────────────────────────────────────────
// Main player screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    tmdbId: Int, mediaType: MediaType,
    season: Int, episode: Int,
    title: String, poster: String?,
    onBack: () -> Unit,
    streamUrl: String? = null,
    streamIsHls: Boolean = false,
    streamReferer: String = "",
    streamOrigin: String = "",
    downloadId: String? = null,
) {
    val d = LocalDimensions.current
    val ctx     = LocalContext.current
    val ui      by vm.ui.collectAsState()
    val player  by vm.exoPlayerFlow.collectAsState()
    val scope   = rememberCoroutineScope()
    val density = LocalDensity.current

    LaunchedEffect(tmdbId, season, episode) {
        vm.init(ctx, tmdbId, mediaType, season, episode, title, poster,
            streamUrl, streamIsHls, streamReferer, streamOrigin, downloadId)
    }
    LaunchedEffect(Unit) { while (true) { vm.pollPosition(); delay(500) } }
    LaunchedEffect(ui.showControls) {
        if (ui.showControls && ui.state is PlayerState.Playing && !ui.showSubtitleDrawer) {
            delay(4_000)
            vm.hideControls()
        }
    }

    var showSpeedDialog   by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }

    var gestureType      by remember { mutableStateOf(GestureType.NONE) }
    var gestureValue     by remember { mutableStateOf(0f) }
    var gestureAnchorPos by remember { mutableStateOf(0f) }
    val gestureVisible   by remember { derivedStateOf { gestureType != GestureType.NONE } }

    val audioManager = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume    = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }

    LaunchedEffect(gestureType, gestureValue) {
        if (gestureType != GestureType.NONE) {
            delay(1_200)
            gestureType = GestureType.NONE
        }
    }

    val gestureModifier = Modifier.pointerInput(ui.isLocked, ui.durationMs) {
        if (ui.isLocked) return@pointerInput
        var dragStartX      = 0f
        var dragStartY      = 0f
        var dragTotalX      = 0f
        var dragTotalY      = 0f
        var activeGesture   = GestureType.NONE
        var startVolume     = 0f
        var startBrightness = 0f
        var startPositionMs = 0L
        val screenWidth     = size.width.toFloat()
        val screenHeight    = size.height.toFloat()
        val LOCK_THRESHOLD  = with(density) { 12.dp.toPx() }

        detectDragGestures(
            onDragStart = { offset ->
                dragStartX      = offset.x
                dragStartY      = offset.y
                dragTotalX      = 0f
                dragTotalY      = 0f
                activeGesture   = GestureType.NONE
                startVolume     = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                startBrightness = try {
                    Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                } catch (_: Exception) { 0.5f }
                startPositionMs = vm.exoPlayer?.currentPosition ?: 0L
            },
            onDrag = { change, dragAmount ->
                change.consume()
                dragTotalX += dragAmount.x
                dragTotalY += dragAmount.y
                if (activeGesture == GestureType.NONE) {
                    val moved = abs(dragTotalX) > LOCK_THRESHOLD || abs(dragTotalY) > LOCK_THRESHOLD
                    if (moved) {
                        activeGesture = when {
                            abs(dragTotalX) > abs(dragTotalY) * 1.5f -> GestureType.SEEK
                            dragStartX < screenWidth / 2f             -> GestureType.BRIGHTNESS
                            else                                      -> GestureType.VOLUME
                        }
                        gestureType      = activeGesture
                        gestureAnchorPos = when (activeGesture) {
                            GestureType.VOLUME     -> startVolume
                            GestureType.BRIGHTNESS -> startBrightness
                            else                   -> 0f
                        }
                    }
                }
                when (activeGesture) {
                    GestureType.VOLUME -> {
                        val delta  = -dragTotalY / screenHeight
                        val newVol = (startVolume + delta * 1.5f).coerceIn(0f, 1f)
                        gestureValue = newVol
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (newVol * maxVolume).toInt(), 0)
                        if (newVol > 0f && ui.isMuted) vm.setMute(false)
                        if (newVol == 0f && !ui.isMuted) vm.setMute(true)
                    }
                    GestureType.BRIGHTNESS -> {
                        val delta     = -dragTotalY / screenHeight
                        val newBright = (startBrightness + delta * 1.5f).coerceIn(0.01f, 1f)
                        gestureValue  = newBright
                        try {
                            val activity = ctx as? Activity
                            val lp = activity?.window?.attributes
                            lp?.screenBrightness = newBright
                            activity?.window?.attributes = lp
                        } catch (_: Exception) {}
                    }
                    GestureType.SEEK -> {
                        if (ui.durationMs > 0) {
                            val secondsDelta = dragTotalX / (size.width / 2f) * 90f
                            val newPos = (startPositionMs / 1000f + secondsDelta).coerceIn(0f, ui.durationMs / 1000f)
                            gestureValue = secondsDelta
                            vm.seekTo((newPos * 1000f).toLong())
                        }
                    }
                    else -> {}
                }
            },
            onDragEnd   = {
                activeGesture = GestureType.NONE
                scope.launch { delay(800); if (gestureType != GestureType.NONE) gestureType = GestureType.NONE }
            },
            onDragCancel = { activeGesture = GestureType.NONE; gestureType = GestureType.NONE },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(gestureModifier)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                if (!ui.isLocked && !ui.showSubtitleDrawer) vm.toggleControls()
                else if (ui.showSubtitleDrawer) vm.closeSubtitleDrawer()
            }
    ) {
        // ── Video surface ─────────────────────────────────────────────────
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
                update  = { pv -> pv.player = vm.exoPlayer },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ── Network offline banner ────────────────────────────────────────
        AnimatedVisibility(
            visible = ui.networkState is NetworkState.Disconnected && !ui.isOfflinePlayback,
            enter   = slideInVertically { -it } + fadeIn(),
            exit    = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = d.buttonHeightMd + d.spaceXl),
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(Color(0xCC1A1000))
                    .border(d.borderThin, Color(0x55FF9A00), RoundedCornerShape(d.radiusPill))
                    .padding(horizontal = d.spaceMd, vertical = d.spaceSm + d.spaceXxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
            ) {
                Icon(IconWifi, null, tint = Warning, modifier = Modifier.size(d.iconMd - 4.dp))
                Text("No internet connection", color = Warning, fontSize = d.textSm, fontWeight = FontWeight.Medium)
            }
        }

        // ── IMA Pre-roll ad overlay ───────────────────────────────────────
        // Rendered as an AndroidView on top of ExoPlayer while isPreRollPlaying = true.
        // When the ad finishes or errors we call vm.preRollCompleted() which clears the
        // flag and starts actual content playback.
        if (ui.isPreRollPlaying && ui.preRollVastUrl != null) {
            val vastUrl = ui.preRollVastUrl!!
            ImaPreRollView(
                vastUrl         = vastUrl,
                onAdCompleted   = { vm.preRollCompleted() },
                onAdError       = { vm.preRollCompleted() },
                modifier        = Modifier.fillMaxSize(),
            )
        }

        // ── Buffering / Resolving overlay ─────────────────────────────────
        AnimatedVisibility(
            visible = ui.state is PlayerState.Resolving || ui.state is PlayerState.Buffering,
            enter   = fadeIn(), exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(d.spaceLg - d.spaceXs),
                ) {
                    CinematicSpinner(size = d.spaceXxl * 1.75f)
                    Text(
                        if (ui.state is PlayerState.Resolving) "Finding best stream…" else "Buffering…",
                        color = White60, fontSize = d.textLg,
                    )
                }
            }
        }

        // ── Error overlay ─────────────────────────────────────────────────
        AnimatedVisibility(
            visible = ui.state is PlayerState.Error,
            enter   = fadeIn(), exit = fadeOut(),
        ) {
            val errorState = ui.state as? PlayerState.Error
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(d.spaceMd + d.spaceXs),
                    modifier = Modifier.padding(d.spaceXxl + d.spaceXs),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(d.avatarLg + d.spaceXl - d.spaceXs).clip(CircleShape)
                                .background(Brush.radialGradient(listOf(Error.copy(.2f), Color.Transparent)))
                                .border(d.borderThin, Error.copy(.4f), CircleShape)
                        )
                        Icon(IconError, null, tint = Error, modifier = Modifier.size(d.iconXl - 2.dp))
                    }
                    Text(
                        errorState?.msg ?: "",
                        color = White80, fontSize = (d.textXl.value - 2).sp,
                        textAlign = TextAlign.Center, lineHeight = (d.textXl.value * 1.4f).sp,
                    )
                    // Extra note for network errors
                    if (errorState?.isNetworkError == true) {
                        Text(
                            "Playback will resume automatically when connection is restored.",
                            color = White40, fontSize = d.textSm, textAlign = TextAlign.Center,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(d.spaceMd + d.spaceXs)) {
                        GhostButton("Go Back", onClick = onBack)
                        if (errorState?.isNetworkError != true) {
                            BrandButton("Retry", onClick = { vm.retry() })
                        }
                    }
                }
            }
        }

        // ── Gesture indicator ─────────────────────────────────────────────
        AnimatedVisibility(
            visible  = gestureVisible,
            enter    = fadeIn(tween(100)),
            exit     = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            GestureIndicator(type = gestureType, value = gestureValue, anchorValue = gestureAnchorPos)
        }

        // ── Controls overlay ──────────────────────────────────────────────
        AnimatedVisibility(
            visible  = ui.showControls
                    && ui.state !is PlayerState.Resolving
                     && ui.state !is PlayerState.Buffering
                    && ui.state !is PlayerState.Error,
            enter    = fadeIn(tween(180)),
            exit     = fadeOut(tween(300)),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Scrims
                Box(
                    Modifier.fillMaxWidth().height(d.spaceXxl * 4.4f)
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                )
                Box(
                    Modifier.fillMaxWidth().height(d.spaceXxl * 5.6f).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                )

                // ── Top bar ───────────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = d.spaceMd, vertical = d.spaceSm + d.spaceXxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(d.buttonHeightMd - d.spaceXxs).clip(CircleShape)
                            .background(GlassMd)
                            .border(d.borderThin, GlassBorderMd, CircleShape)
                            .clickable(onClick = onBack),
                        Alignment.Center,
                    ) {
                        Icon(IconArrowLeft, null, tint = White, modifier = Modifier.size(d.iconMd))
                    }
                    Spacer(Modifier.width(d.spaceMd))
                    Column(Modifier.weight(1f)) {
                        Text(ui.title, color = White, fontWeight = FontWeight.Bold, fontSize = d.textLg, maxLines = 1)
                        if (ui.episodeLabel.isNotBlank()) {
                            Text(ui.episodeLabel, color = White60, fontSize = d.textMd)
                        }
                    }
                    Box(
                        Modifier.size(d.buttonHeightMd - d.spaceXxs).clip(CircleShape)
                            .background(if (ui.isLocked) AmberGlass else GlassMd)
                            .border(d.borderThin, if (ui.isLocked) AmberBorder else GlassBorderMd, CircleShape)
                            .clickable { vm.toggleLock() },
                        Alignment.Center,
                    ) {
                        Icon(
                            if (ui.isLocked) IconLock else IconUnlock, null,
                            tint = if (ui.isLocked) Brand else White,
                            modifier = Modifier.size(d.iconMd - 2.dp),
                        )
                    }
                }

                // ── Center play/pause ─────────────────────────────────────
                Box(
                    Modifier.align(Alignment.Center).size(d.avatarLg + d.spaceLg)
                        .clip(CircleShape)
                        .background(GlassHeavy)
                        .border(
                            width = d.borderMed,
                            brush = Brush.linearGradient(listOf(Brand.copy(.7f), Brand2.copy(.3f))),
                            shape = CircleShape,
                        )
                        .clickable { vm.togglePlayPause() },
                    Alignment.Center,
                ) {
                    Icon(
                        if (ui.state is PlayerState.Playing) IconPause else IconPlay,
                        null, tint = White,
                        modifier = Modifier.size(d.iconXl - 6.dp)
                            .padding(start = if (ui.state !is PlayerState.Playing) d.spaceXxs + 1.dp else 0.dp),
                    )
                }

                // ── Bottom strip ──────────────────────────────────────────
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = d.spaceMd + d.spaceXs, vertical = d.spaceMd - d.spaceXs),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    if (ui.durationMs > 0) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = d.spaceXs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(formatMs(ui.positionMs), color = White60, fontSize = d.textXs)
                            Text(formatMs(ui.durationMs), color = White60, fontSize = d.textXs)
                        }
                    }

                    if (ui.durationMs > 0) {
                        val progress = (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)
                        val buffered = (ui.bufferedMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(d.buttonHeightSm), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.fillMaxWidth().height(d.progressBarHeight)
                                    .clip(RoundedCornerShape(d.spaceXxs))
                                    .background(White.copy(alpha = 0.15f))
                            ) {
                                Box(Modifier.fillMaxWidth(buffered).fillMaxHeight().background(White.copy(alpha = 0.28f)))
                                Box(
                                    Modifier.fillMaxWidth(progress).fillMaxHeight()
                                        .background(Brush.horizontalGradient(listOf(Brand, Brand2)))
                                )
                            }
                            Slider(
                                value          = progress,
                                onValueChange  = { vm.seekTo((it * ui.durationMs).toLong()) },
                                colors         = SliderDefaults.colors(
                                    thumbColor            = Brand2,
                                    activeTrackColor      = Color.Transparent,
                                    inactiveTrackColor    = Color.Transparent,
                                    disabledThumbColor    = Color.Transparent,
                                    disabledActiveTrackColor = Color.Transparent,
                                ),
                                modifier       = Modifier.fillMaxWidth().height(d.buttonHeightSm),
                            )
                        }
                    }

                    Spacer(Modifier.height(d.spaceSm))

                    // Bottom action row
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 2.dp, Alignment.End),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        // Mute
                        Box(
                            Modifier.size(d.buttonHeightSm).clip(CircleShape)
                                .background(GlassMd)
                                .border(d.borderThin, GlassBorderMd, CircleShape)
                                .clickable { vm.toggleMute() },
                            Alignment.Center,
                        ) {
                            Icon(if (ui.isMuted) IconVolumeOff else IconVolumeUp, null, tint = White, modifier = Modifier.size(d.iconMd - 2.dp))
                        }

                        // Subtitles button — highlighted when active
                        val hasSubtitles = ui.subtitleOptions.isNotEmpty()
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .background(if (ui.subtitlesEnabled) AmberGlass else GlassMd)
                                .border(d.borderThin, if (ui.subtitlesEnabled) AmberBorder else GlassBorderMd, RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .clickable {
                                    if (hasSubtitles) vm.openSubtitleDrawer()
                                    else vm.openSubtitleDrawer()
                                }
                                .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + 1.dp)
                        ) {
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(d.spaceXs + 1.dp),
                            ) {
                                Icon(
                                    IconSubtitles, null,
                                    tint     = if (ui.subtitlesEnabled) Brand else White,
                                    modifier = Modifier.size(d.iconSm + 4.dp),
                                )
                                Text(
                                    if (ui.subtitlesEnabled) "CC" else "CC",
                                    color      = if (ui.subtitlesEnabled) Brand else White,
                                    fontSize   = d.textSm,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        // Speed
                        Box(
                            Modifier.clip(RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .background(GlassMd)
                                .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .clickable { showSpeedDialog = true }
                                .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + 1.dp)
                        ) {
                            Text(
                                ui.playbackSpeed.let { if (it == it.toLong().toFloat()) "${it.toLong()}×" else "${it}×" },
                                color = White, fontSize = d.textSm, fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // Quality
                        Box(
                            Modifier.clip(RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .background(GlassMd)
                                .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .clickable { showQualityDialog = true }
                                .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + 1.dp)
                        ) {
                            Text(ui.selectedQuality, color = White, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ── Lock overlay ──────────────────────────────────────────────────
        if (ui.isLocked) {
            Box(
                Modifier.fillMaxSize()
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            ) {
                Box(
                    Modifier.align(Alignment.CenterStart).padding(d.spaceXl)
                        .clip(RoundedCornerShape(d.radiusPill))
                        .background(AmberGlass)
                        .border(d.borderThin, AmberBorder, RoundedCornerShape(d.radiusPill))
                        .clickable { vm.toggleLock() }
                        .padding(horizontal = d.spaceMd, vertical = d.spaceSm + d.spaceXxs),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceXs + 1.dp),
                    ) {
                        Icon(IconUnlock, null, tint = Brand, modifier = Modifier.size(d.iconSm + 4.dp))
                        Text("Unlock", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ── Subtitle drawer ───────────────────────────────────────────────
        SubtitleDrawer(
            visible     = ui.showSubtitleDrawer,
            ui          = ui,
            onClose     = { vm.closeSubtitleDrawer() },
            onSelect    = { vm.selectSubtitle(it) },
            onToggleOff = { vm.toggleSubtitlesOnOff() },
            onTogglePersistent = { vm.togglePersistentSubtitle(it) },
            onOffsetChange = { vm.setSubtitleOffset(it) },
            onSearchOnline = { vm.searchOnlineSubtitles() },
            onUpgradeToPremium = {
                // PlayerActivity is a separate Activity from the main NavHost, so the
                // only way back to a Compose-navigable screen is to finish this one
                // and relaunch MainActivity with an extra it reads once on create.
                // REORDER_TO_FRONT + SINGLE_TOP brings the existing MainActivity
                // instance forward (with all its state intact) instead of stacking
                // a blank duplicate on top of it.
                val intent = android.content.Intent(ctx, com.axio.reelz.ui.MainActivity::class.java).apply {
                    putExtra(com.axio.reelz.ui.MainActivity.EXTRA_OPEN_PREMIUM, true)
                    flags = android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                ctx.startActivity(intent)
                (ctx as? android.app.Activity)?.finish()
            },
        )
    }

    // ── Speed dialog ──────────────────────────────────────────────────────
    if (showSpeedDialog) {
        PlayerOptionDialog(
            title    = "Playback Speed",
            options  = listOf("0.5×" to 0.5f, "0.75×" to 0.75f, "1×" to 1f, "1.25×" to 1.25f, "1.5×" to 1.5f, "2×" to 2f),
            selected = ui.playbackSpeed.let { s -> if (s == s.toLong().toFloat()) "${s.toLong()}×" else "${s}×" },
            onSelect  = { _, v -> vm.setSpeed(v); showSpeedDialog = false },
            onDismiss = { showSpeedDialog = false },
        )
    }

    // ── Quality dialog ────────────────────────────────────────────────────
    if (showQualityDialog) {
        PlayerOptionDialog(
            title     = "Quality",
            options   = ui.availableQualities.map { it.label to it.label },
            selected  = ui.selectedQuality,
            onSelect  = { label, _ -> vm.setQuality(label); showQualityDialog = false },
            onDismiss = { showQualityDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Subtitle Drawer — 40% right-side panel, glass over video
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SubtitleDrawer(
    visible: Boolean,
    ui: PlayerUiState,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onToggleOff: () -> Unit,
    onTogglePersistent: (SubtitleOption) -> Unit,
    onOffsetChange: (Int) -> Unit,
    onSearchOnline: () -> Unit,
    onUpgradeToPremium: () -> Unit,
) {
    val d = LocalDimensions.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val drawerWidth   = if (d.isTablet) 0.32f else 0.40f   // fraction of screen width
    var searchQuery   by remember { mutableStateOf("") }
    var showOffsetSection by remember { mutableStateOf(false) }

    // Animate slide-in from right — offscreen distance scales with actual screen width
    // so the drawer always starts fully hidden, on any device size.
    val offscreenX = screenWidthDp + d.spaceXl
    val offsetX by animateDpAsState(
        targetValue = if (visible) 0.dp else offscreenX,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "drawerSlide"
    )
    val bgAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300),
        label = "drawerBg"
    )

    if (!visible && offsetX >= offscreenX) return

    // Glassmorphism backdrop (dim left 60% softly)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Color.Black.copy(alpha = 0.45f * bgAlpha))
            }
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                onClose()
            }
    ) {
        // The drawer panel
        Box(
            Modifier
                .fillMaxWidth(drawerWidth)
                .fillMaxHeight()
                .align(Alignment.CenterEnd)
                .offset(x = offsetX)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { /* absorb */ }
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xCC050510), Color(0xEE080818)),
                        startX = 0f, endX = Float.POSITIVE_INFINITY,
                    )
                )
                .border(
                    width = d.borderThin,
                    brush = Brush.verticalGradient(listOf(GlassBorderHv, GlassBorderMd, GlassBorder)),
                    shape = RoundedCornerShape(topStart = d.radiusLg, bottomStart = d.radiusLg),
                )
                .clip(RoundedCornerShape(topStart = d.radiusLg, bottomStart = d.radiusLg))
        ) {
            Column(Modifier.fillMaxSize()) {

                // ── Header ────────────────────────────────────────────────
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0x33050510), Color.Transparent)
                            )
                        )
                        .padding(top = d.spaceXl, start = d.spaceMd + d.spaceXs, end = d.spaceMd + d.spaceXs, bottom = 0.dp)
                ) {
                    // Title row
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                        ) {
                            Box(
                                Modifier.size(d.avatarSm).clip(CircleShape)
                                    .background(AmberGlass)
                                    .border(d.borderThin, AmberBorder, CircleShape),
                                Alignment.Center,
                            ) {
                                Icon(IconSubtitles, null, tint = Brand, modifier = Modifier.size(d.iconSm + 3.dp))
                            }
                            Text(
                                "Subtitles",
                                color      = White,
                                fontSize   = d.textLg,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp,
                            )
                        }
                        Box(
                            Modifier.size(d.avatarSm - d.spaceXs).clip(CircleShape)
                                .background(GlassMd)
                                .border(d.borderThin, GlassBorderMd, CircleShape)
                                .clickable { onClose() },
                            Alignment.Center,
                        ) {
                            Icon(IconClose, null, tint = White60, modifier = Modifier.size(d.iconSm + 2.dp))
                        }
                    }

                    Spacer(Modifier.height(d.spaceMd + d.spaceXs))

                    // ── Subtitle ON/OFF toggle ────────────────────────────
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(d.radiusMd))
                            .background(GlassMd)
                            .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd))
                            .clickable { onToggleOff() }
                            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceMd - d.spaceXs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                "Subtitles",
                                color = White, fontSize = d.textMd, fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (ui.subtitlesEnabled) "On • ${ui.subtitleOptions.firstOrNull { it.language == ui.activeSubtitleLanguage }?.label ?: ""}"
                                else "Off",
                                color = if (ui.subtitlesEnabled) Brand else White40,
                                fontSize = (d.textXxs.value + 1).sp,
                            )
                        }
                        // Pill toggle
                        SubtitleTogglePill(enabled = ui.subtitlesEnabled)
                    }

                    Spacer(Modifier.height(d.spaceMd - d.spaceXs))

                    // ── Search bar ────────────────────────────────────────
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                            .background(GlassSm)
                            .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXxs))
                            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                    ) {
                        Icon(IconSearch, null, tint = White40, modifier = Modifier.size(d.iconSm + 2.dp))
                        BasicTextField(
                            value         = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine    = true,
                            textStyle     = TextStyle(color = White, fontSize = d.textSm),
                            decorationBox = { inner ->
                                Box {
                                    if (searchQuery.isEmpty()) {
                                        Text("Search language…", color = White40, fontSize = d.textSm)
                                    }
                                    inner()
                                }
                            },
                            modifier      = Modifier.weight(1f),
                        )
                        if (searchQuery.isNotEmpty()) {
                            Box(
                                Modifier.size(d.iconMd - 2.dp).clip(CircleShape)
                                    .background(GlassMd)
                                    .clickable { searchQuery = "" },
                                Alignment.Center,
                            ) {
                                Icon(IconClose, null, tint = White60, modifier = Modifier.size(d.iconXs + 1.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(d.spaceSm + d.spaceXxs))
                }

                // Subtle separator
                Box(Modifier.fillMaxWidth().height(d.borderThin).background(GlassBorder))

                // ── Scrollable content ────────────────────────────────────
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
                    verticalArrangement = Arrangement.spacedBy(d.spaceXs),
                ) {

                    // "Off" option
                    item {
                        SubtitleRow(
                            label     = "Off",
                            language  = "off",
                            isActive  = !ui.subtitlesEnabled,
                            isPersistent = false,
                            isEnabled = true,
                            onClick   = { onSelect("off") },
                        )
                    }

                    if (ui.subtitleOptions.isNotEmpty()) {
                        // Section header
                        item {
                            Text(
                                if (ui.isOfflinePlayback) "Downloaded" else "Available",
                                color    = White40,
                                fontSize = d.textXxs,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = d.spaceXs, vertical = d.spaceSm),
                            )
                        }

                        val filtered = ui.subtitleOptions.filter {
                            searchQuery.isBlank() ||
                            it.label.contains(searchQuery, ignoreCase = true) ||
                            it.language.contains(searchQuery, ignoreCase = true)
                        }

                        items(filtered) { option ->
                            SubtitleRow(
                                label        = option.label,
                                language     = option.language,
                                isActive     = ui.subtitlesEnabled && ui.activeSubtitleLanguage == option.language,
                                isPersistent = option.isPersistent,
                                isEnabled    = option.isEnabled,
                                onClick      = { onSelect(option.language) },
                                onToggle     = if (option.isPersistent) ({ onTogglePersistent(option) }) else null,
                            )
                        }
                    } else if (searchQuery.isEmpty()) {
                        // No subtitles loaded yet — show Search Online CTA
                        item {
                            Spacer(Modifier.height(d.spaceSm + d.spaceXxs))
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = d.spaceXs),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                            ) {
                                when {
                                    ui.isSubtitleSearching -> {
                                        // Searching spinner
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(d.iconMd),
                                            color    = Brand,
                                            strokeWidth = d.borderMed,
                                        )
                                        Text(
                                            "Searching…",
                                            color    = White40,
                                            fontSize = d.textXs,
                                        )
                                    }
                                    ui.subtitleSearchEmpty -> {
                                        // Search done, nothing found
                                        Text(
                                            "No subtitles found",
                                            color    = White40,
                                            fontSize = d.textSm,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .background(GlassMd)
                                                .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .clickable { onSearchOnline() }
                                                .padding(vertical = d.spaceSm + d.spaceXxs),
                                            Alignment.Center,
                                        ) {
                                            Text(
                                                "Try again",
                                                color      = Brand,
                                                fontSize   = d.textSm,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                    ui.subtitleUpsellMessage != null -> {
                                        // Free tier tried manual search — show upgrade nudge, never an error
                                        Text(
                                            ui.subtitleUpsellMessage,
                                            color      = White60,
                                            fontSize   = d.textXs,
                                            textAlign  = androidx.compose.ui.text.style.TextAlign.Center,
                                            lineHeight = (d.textXs.value * 1.45f).sp,
                                        )
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .background(AmberGlass)
                                                .border(d.borderThin, AmberBorder, RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .clickable { onUpgradeToPremium() }
                                                .padding(vertical = d.spaceSm + d.spaceXxs),
                                            Alignment.Center,
                                        ) {
                                            Text(
                                                "Upgrade to Premium",
                                                color      = Brand,
                                                fontSize   = d.textSm,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    else -> {
                                        // Default: user hasn't searched yet
                                        Text(
                                            if (ui.isOfflinePlayback) "Search for subtitles to download"
                                            else "Search OpenSubtitles for this title",
                                            color    = White40,
                                            fontSize = d.textXs,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .background(AmberGlass)
                                                .border(d.borderThin, AmberBorder, RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .clickable { onSearchOnline() }
                                                .padding(vertical = d.spaceSm + d.spaceXs),
                                            Alignment.Center,
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                                            ) {
                                                Icon(
                                                    Icons.Default.Search,
                                                    contentDescription = null,
                                                    tint     = Brand,
                                                    modifier = Modifier.size(d.iconSm + 2.dp),
                                                )
                                                Text(
                                                    "Search Online",
                                                    color      = Brand,
                                                    fontSize   = d.textSm,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(d.spaceXs))
                        }
                    }

                    // Gap
                    item { Spacer(Modifier.height(d.spaceSm + d.spaceXxs)) }
                }

                // ── Divider ───────────────────────────────────────────────
                Box(Modifier.fillMaxWidth().height(d.borderThin).background(GlassBorder))

                // ── Timing offset section ─────────────────────────────────
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x22050510))
                        .padding(d.spaceMd - d.spaceXxs),
                    verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
                ) {
                    // Section toggle
                    Row(
                        Modifier.fillMaxWidth().clickable { showOffsetSection = !showOffsetSection },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                        ) {
                            Icon(IconTimerOff, null, tint = White60, modifier = Modifier.size(d.iconSm + 1.dp))
                            Text(
                                "Subtitle Timing",
                                color = White60, fontSize = d.textXs, fontWeight = FontWeight.Medium,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
                        ) {
                            if (ui.subtitleOffsetMs != 0) {
                                val sign = if (ui.subtitleOffsetMs > 0) "+" else ""
                                Text(
                                    "${sign}${ui.subtitleOffsetMs / 1000.0}s",
                                    color = Brand, fontSize = (d.textXxs.value + 1).sp, fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                if (showOffsetSection) "▲" else "▼",
                                color = White40, fontSize = d.textXxs,
                            )
                        }
                    }

                    AnimatedVisibility(visible = showOffsetSection) {
                        SubtitleOffsetControl(
                            offsetMs   = ui.subtitleOffsetMs,
                            onChanged  = onOffsetChange,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Subtitle row item
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SubtitleRow(
    label: String,
    language: String,
    isActive: Boolean,
    isPersistent: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
    onToggle: (() -> Unit)? = null,
    dimmed: Boolean = false,
) {
    val d = LocalDimensions.current
    val bg     = if (isActive) AmberGlass else Color.Transparent
    val border = if (isActive) AmberBorder else Color.Transparent
    val textColor = when {
        isActive -> Brand
        dimmed   -> White40
        !isEnabled && isPersistent -> White40
        else     -> White80
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd - d.spaceXs))
            .background(bg)
            .border(d.borderThin, border, RoundedCornerShape(d.radiusMd - d.spaceXs))
            .clickable { onClick() }
            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
            modifier = Modifier.weight(1f),
        ) {
            if (isActive) {
                Icon(IconCheck, null, tint = Brand, modifier = Modifier.size(d.iconSm + 2.dp))
            } else {
                Box(Modifier.size(d.iconSm + 2.dp).clip(CircleShape).background(GlassMd))
            }
            Text(
                label,
                color      = textColor,
                fontSize   = d.textSm,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }

        if (isPersistent && onToggle != null) {
            Spacer(Modifier.width(d.spaceSm))
            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(if (isEnabled) AmberGlass else GlassSm)
                    .border(d.borderThin, if (isEnabled) AmberBorder else GlassBorderMd, RoundedCornerShape(d.radiusPill))
                    .clickable { onToggle() }
                    .padding(horizontal = d.spaceSm + d.spaceXxs, vertical = d.spaceXxs + 1.dp),
            ) {
                Text(
                    if (isEnabled) "On" else "Off",
                    color      = if (isEnabled) Brand else White40,
                    fontSize   = (d.textXxs.value + 1).sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Subtitle toggle pill
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SubtitleTogglePill(enabled: Boolean) {
    val d = LocalDimensions.current
    val trackColor  by animateColorAsState(if (enabled) Brand else GlassBorderMd, label = "track")
    // Thumb travel = track width - thumb size - 2*padding; scaled to d
    val thumbTravel = d.spaceXl - d.spaceSm   // ~16 sp
    val thumbOffset by animateDpAsState(if (enabled) thumbTravel else 0.dp, label = "thumb")
    val trackW = d.spaceXxl - d.spaceXs
    val trackH = d.spaceLg
    val thumbSz = trackH - d.spaceSm

    Box(
        Modifier
            .width(trackW).height(trackH)
            .clip(RoundedCornerShape(trackH / 2))
            .background(trackColor.copy(alpha = 0.35f))
            .border(d.borderThin, trackColor, RoundedCornerShape(trackH / 2))
    ) {
        Box(
            Modifier
                .size(thumbSz)
                .offset(x = d.spaceXs + thumbOffset, y = d.spaceXs)
                .clip(CircleShape)
                .background(if (enabled) Brand else White40)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Subtitle timing offset control
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SubtitleOffsetControl(
    offsetMs: Int,
    onChanged: (Int) -> Unit,
) {
    val d = LocalDimensions.current

    Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sign = if (offsetMs > 0) "+" else ""
            val secs = offsetMs / 1000.0
            Text(
                "${sign}${secs}s",
                color      = if (offsetMs == 0) White40 else Brand,
                fontSize   = d.textXxl,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "Positive = delay   Negative = advance",
            color    = White40, fontSize = d.textXxs,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
        ) {
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .background(GlassMd)
                    .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .clickable { onChanged((offsetMs - 500).coerceAtLeast(-10_000)) }
                    .padding(vertical = d.spaceSm + d.spaceXxs),
                Alignment.Center,
            ) {
                Text("−0.5s", color = White80, fontSize = d.textXs, fontWeight = FontWeight.Medium)
            }

            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .background(if (offsetMs != 0) AmberGlass else GlassMd)
                    .border(d.borderThin, if (offsetMs != 0) AmberBorder else GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .clickable { onChanged(0) }
                    .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
                Alignment.Center,
            ) {
                Text("Reset", color = if (offsetMs != 0) Brand else White40, fontSize = d.textXs, fontWeight = FontWeight.SemiBold)
            }

            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .background(GlassMd)
                    .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .clickable { onChanged((offsetMs + 500).coerceAtMost(10_000)) }
                    .padding(vertical = d.spaceSm + d.spaceXxs),
                Alignment.Center,
            ) {
                Text("+0.5s", color = White80, fontSize = d.textXs, fontWeight = FontWeight.Medium)
            }
        }

        Slider(
            value         = offsetMs.toFloat(),
            onValueChange = { onChanged(it.roundToInt()) },
            valueRange    = -10_000f..10_000f,
            steps         = 39,
            colors        = SliderDefaults.colors(
                thumbColor         = Brand2,
                activeTrackColor   = Brand.copy(.5f),
                inactiveTrackColor = GlassBorderMd,
            ),
            modifier = Modifier.fillMaxWidth().height(d.buttonHeightSm - d.spaceSm),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Gesture indicator bubble
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GestureIndicator(type: GestureType, value: Float, anchorValue: Float) {
    val d = LocalDimensions.current
    val icon = when (type) {
        GestureType.VOLUME     -> if (value > 0f) IconVolumeUp else IconVolumeOff
        GestureType.BRIGHTNESS -> IconBrightness
        GestureType.SEEK       -> IconArrowLeft
        else                   -> return
    }
    val label = when (type) {
        GestureType.VOLUME     -> "${(value * 100).toInt()}%"
        GestureType.BRIGHTNESS -> "${(value * 100).toInt()}%"
        GestureType.SEEK       -> { val s = value.toInt(); val sign = if (s >= 0) "+" else ""; "$sign${s}s" }
        else -> ""
    }
    val barFraction = when (type) {
        GestureType.VOLUME, GestureType.BRIGHTNESS -> value.coerceIn(0f, 1f)
        GestureType.SEEK -> ((value + 90f) / 180f).coerceIn(0f, 1f)
        else -> 0f
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(d.radiusMd + d.spaceXs))
            .background(Color(0xCC000000))
            .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd + d.spaceXs))
            .padding(horizontal = d.spaceXl, vertical = d.spaceLg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
        ) {
            Icon(icon, null, tint = White, modifier = Modifier.size(d.iconLg))
            Text(label, color = White, fontSize = d.textXl, fontWeight = FontWeight.Bold)
            Box(
                Modifier.width(d.spaceXxl * 3.1f).height(d.progressBarHeight)
                    .clip(RoundedCornerShape(d.spaceXxs))
                    .background(White.copy(.2f))
            ) {
                Box(
                    Modifier.fillMaxWidth(barFraction).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Brand, Brand2)))
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared option dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun <T> PlayerOptionDialog(
    title: String,
    options: List<Pair<String, T>>,
    selected: String,
    onSelect: (String, T) -> Unit,
    onDismiss: () -> Unit,
) {
    val d = LocalDimensions.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgCard,
        shape            = RoundedCornerShape(d.radiusLg),
        title = {
            Text(title, color = White, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
        },
        text = {
            Column {
                options.forEach { (label, value) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(d.radiusMd - d.spaceXs))
                            .background(if (label == selected) AmberGlass else Color.Transparent)
                            .clickable { onSelect(label, value) }
                            .padding(vertical = d.spaceMd - d.spaceXxs, horizontal = d.spaceMd - d.spaceXxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = label == selected,
                            onClick  = { onSelect(label, value) },
                            colors   = RadioButtonDefaults.colors(
                                selectedColor   = Brand,
                                unselectedColor = White40,
                            ),
                        )
                        Spacer(Modifier.width(d.spaceSm + d.spaceXxs))
                        Text(
                            label,
                            color      = if (label == selected) Brand else White80,
                            fontWeight = if (label == selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize   = d.textMd,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Brand, fontWeight = FontWeight.SemiBold, fontSize = d.textMd)
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
