package com.axio.reelz.ui.screens.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.axio.reelz.ads.PlayerVideoAd
import com.axio.reelz.ads.VideoAdType
import com.axio.reelz.ads.VastTagProvider
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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

private val IconSettings: ImageVector
    get() = ImageVector.Builder("Settings", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                // cog outline
                moveTo(12f, 15f); arcTo(3f, 3f, 0f, false, true, 12f, 9f); arcTo(3f, 3f, 0f, false, true, 12f, 15f)
                moveTo(19.4f, 15f); arcTo(1.65f, 1.65f, 0f, false, false, 0.33f, 1.65f)
                lineTo(18f, 13f); arcTo(1.65f, 1.65f, 0f, false, false, 15.82f, 9.17f)
                lineTo(19.4f, 9f); arcTo(1.65f, 1.65f, 0f, false, false, 19.73f, 7.35f)
                lineTo(21f, 5f); lineTo(19f, 3f); lineTo(17f, 4.27f)
                arcTo(1.65f, 1.65f, 0f, false, false, 15.33f, 4.6f)
                lineTo(15f, 4.6f); arcTo(1.65f, 1.65f, 0f, false, false, 13.35f, 3f)
                lineTo(13f, 3f); lineTo(11f, 3f); lineTo(10.65f, 3f)
                arcTo(1.65f, 1.65f, 0f, false, false, 9f, 4.6f)
                lineTo(8.67f, 4.6f); arcTo(1.65f, 1.65f, 0f, false, false, 7f, 4.27f)
                lineTo(5f, 3f); lineTo(3f, 5f); lineTo(4.27f, 7f)
                arcTo(1.65f, 1.65f, 0f, false, false, 4.6f, 8.67f)
                lineTo(4.6f, 9f); arcTo(1.65f, 1.65f, 0f, false, false, 3f, 10.65f)
                lineTo(3f, 11f); lineTo(3f, 13f); lineTo(3f, 13.35f)
                arcTo(1.65f, 1.65f, 0f, false, false, 4.6f, 15f)
                lineTo(4.6f, 15.33f); arcTo(1.65f, 1.65f, 0f, false, false, 4.27f, 17f)
                lineTo(3f, 19f); lineTo(5f, 21f); lineTo(7f, 19.73f)
                arcTo(1.65f, 1.65f, 0f, false, false, 8.67f, 19.4f)
                lineTo(9f, 19.4f); arcTo(1.65f, 1.65f, 0f, false, false, 10.65f, 21f)
                lineTo(11f, 21f); lineTo(13f, 21f); lineTo(13.35f, 21f)
                arcTo(1.65f, 1.65f, 0f, false, false, 15f, 19.4f)
                lineTo(15.33f, 19.4f); arcTo(1.65f, 1.65f, 0f, false, false, 17f, 19.73f)
                lineTo(19f, 21f); lineTo(21f, 19f); lineTo(19.73f, 17f)
                arcTo(1.65f, 1.65f, 0f, false, false, 19.4f, 15.33f); close()
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            fill = SolidColor(Color.Transparent)
        )
    }.build()

/** Picture-in-Picture icon (two overlapping rectangles, small one top-right) */
private val IconPip: ImageVector
    get() = ImageVector.Builder("PiP", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData {
                moveTo(2f, 5f)
                arcTo(2f, 2f, 0f, false, true, 4f, 3f)
                lineTo(20f, 3f)
                arcTo(2f, 2f, 0f, false, true, 22f, 5f)
                lineTo(22f, 19f)
                arcTo(2f, 2f, 0f, false, true, 20f, 21f)
                lineTo(4f, 21f)
                arcTo(2f, 2f, 0f, false, true, 2f, 19f)
                close()
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
            fill = SolidColor(Color.Transparent)
        )
        addPath(
            pathData = PathData {
                moveTo(12f, 11f)
                arcTo(1f, 1f, 0f, false, true, 13f, 10f)
                lineTo(20f, 10f)
                arcTo(1f, 1f, 0f, false, true, 21f, 11f)
                lineTo(21f, 17f)
                arcTo(1f, 1f, 0f, false, true, 20f, 18f)
                lineTo(13f, 18f)
                arcTo(1f, 1f, 0f, false, true, 12f, 17f)
                close()
            },
            stroke = SolidColor(Color.White), strokeLineWidth = 1.3f,
            fill = SolidColor(Color.White.copy(alpha = 0.15f))
        )
    }.build()

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()

    // ── Local flag set the instant we call enterPictureInPictureMode() ────────
    // onPause() fires BEFORE onPictureInPictureModeChanged(), so we cannot rely
    // on vm.ui.value.isPipActive being true yet inside onPause(). This flag is
    // set synchronously in enterPipMode() so onPause() can read it immediately.
    private var isEnteringPip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val mediaId       = intent.getStringExtra("mediaId") ?: ""
        val typeStr       = intent.getStringExtra("mediaType") ?: "MOVIE"
        val season        = intent.getIntExtra("season", 0)
        val episode       = intent.getIntExtra("episode", 0)
        val title         = intent.getStringExtra("title") ?: ""
        val poster        = intent.getStringExtra("posterUrl")
        val mediaType     = if (typeStr == "TV") MediaType.TV else MediaType.MOVIE
        val streamUrl     = intent.getStringExtra("streamUrl")
        val streamIsHls   = intent.getBooleanExtra("streamIsHls", false)
        val streamReferer = intent.getStringExtra("streamReferer") ?: ""
        val streamOrigin  = intent.getStringExtra("streamOrigin") ?: ""
        val downloadId    = intent.getStringExtra("downloadId")
        val preferredQuality = intent.getStringExtra("preferredQuality")

        setContent {
            com.axio.reelz.ui.theme.ProvideDimensions {
                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Brand)) {
                    PlayerScreen(
                        vm            = vm,
                        id            = mediaId, mediaType = mediaType,
                        season        = season, episode = episode,
                        title         = title, poster = poster,
                        onBack        = { finish() },
                        onEnterPip    = { enterPipMode() },
                        streamUrl     = streamUrl, streamIsHls = streamIsHls,
                        streamReferer = streamReferer, streamOrigin = streamOrigin,
                        downloadId    = downloadId,
                        preferredQuality = preferredQuality,
                    )
                }
            }
        }
    }

    // ── Automatic PiP: triggered when user presses Home or switches apps ──────
    // Section 3: only enter PiP if global toggle is ON and playing.
    // Back button does NOT trigger this path.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: Android delivers a new Intent to the existing instance instead of
        // recreating the Activity. This is exactly what happens when the user taps the
        // PiP floating window — we simply ignore the re-delivery because the VM and
        // ExoPlayer are still alive and playing. No restart, no state loss.
        setIntent(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.shouldAutoPip()) {
            enterPipMode()
        }
    }

    // ── PiP lifecycle ─────────────────────────────────────────────────────────
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.onPipModeChanged(isInPictureInPictureMode)

        if (!isInPictureInPictureMode) {
            // ── Bug 3 fix: PiP was closed via the system X button ─────────────
            // When the user drags the floating window to the "X" dismiss zone,
            // the Activity goes from PiP → stopped without coming back to the
            // foreground. We detect this by checking isFinishing / lifecycle state
            // inside onStop (see below). Here we just restore system UI for the
            // normal "tap to return" case.
            WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // ── Bug 3 fix: kill ghost audio when PiP window is dismissed via X ────────
    // When the user drags the PiP window to the discard zone the Activity
    // receives onStop (not onPause + onResume). At that point isPipActive has
    // already been cleared by onPictureInPictureModeChanged(false), so we can
    // reliably detect the "closed while still in PiP flow" case.
    override fun onStop() {
        super.onStop()
        // If we're stopping but NOT coming back to foreground AND we were in PiP,
        // the user dismissed the floating window — stop playback immediately.
        if (!isChangingConfigurations && vm.wasInPipBeforeStop()) {
            vm.stopPlaybackAndRelease()
            finish()
        }
    }

    // ── Shared PiP entry helper ───────────────────────────────────────────────
    // Sets isEnteringPip = true BEFORE calling the system API so that onPause()
    // (which fires immediately after) knows not to pause the player.
    // We never call finish() here — Android keeps the Activity alive in the
    // back stack behind the floating window. Whatever screen was underneath
    // (detail page, settings, browse — anything) naturally becomes visible.
    // Tapping the floating window brings this Activity back to the foreground
    // without recreating it, so no restart ever happens.
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isEnteringPip = true
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPause() {
        super.onPause()
        // IMPORTANT: onPause fires BEFORE onPictureInPictureModeChanged(), so
        // vm.ui.value.isPipActive is still false here even when we are entering
        // PiP. We use the local isEnteringPip flag (set synchronously in
        // enterPipMode()) to guard correctly.
        val uiState = vm.ui.value
        val inOrEnteringPip = isEnteringPip || uiState.isPipActive
        if (!inOrEnteringPip && !vm.canBackgroundPlay()) {
            vm.exoPlayer?.pause()
        }
        // Reset the entering flag — by the time onResume fires it's no longer needed
        isEnteringPip = false
    }

    override fun onResume() {
        super.onResume()
        // Bug 4 fix: resume immediately when returning FROM PiP (user tapped window).
        // We only auto-play if the VM knows we were in PiP — not on every resume
        // (e.g. returning from settings, notifications, etc.) which would override
        // a user-intentional pause.
        if (vm.ui.value.isPipActive) {
            vm.exoPlayer?.let { if (it.mediaItemCount > 0) it.play() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vm.release(this)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Gesture hint type
// ─────────────────────────────────────────────────────────────────────────────

private enum class GestureType { NONE, VOLUME, BRIGHTNESS, SEEK }

// ─────────────────────────────────────────────────────────────────────────────
// Drawer width tiers (fractional screen width)
// ─────────────────────────────────────────────────────────────────────────────

private enum class DrawerWidthTier(val fraction: Float) {
    // Width values derived relative to base fractions — no hardcoded dp.
    // Device screen width comes from LocalConfiguration.current.screenWidthDp in PlayerSideDrawer.
    SPEED(0.15f),     // Speed: 50% narrower than old COMPACT (0.30 * 0.50 = 0.15)
    COMPACT(0.21f),   // Quality: 30% narrower (0.30 * 0.70 = 0.21)
    STANDARD(0.36f),  // Settings: unchanged
    WIDE(0.315f),     // Subtitles: 25% narrower (0.42 * 0.75 = 0.315)
}

// ─────────────────────────────────────────────────────────────────────────────
// Main player screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    id: String, mediaType: MediaType,
    season: Int, episode: Int,
    title: String, poster: String?,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    streamUrl: String? = null,
    streamIsHls: Boolean = false,
    streamReferer: String = "",
    streamOrigin: String = "",
    downloadId: String? = null,
    preferredQuality: String? = null,
) {
    val d       = LocalDimensions.current
    val ctx     = LocalContext.current
    val ui      by vm.ui.collectAsState()
    val player  by vm.exoPlayerFlow.collectAsState()
    val scope   = rememberCoroutineScope()
    val density = LocalDensity.current

    LaunchedEffect(id, season, episode) {
        vm.init(ctx, id, mediaType, season, episode, title, poster,
            streamUrl, streamIsHls, downloadId,
            preferredQuality = preferredQuality)
    }

    // ── Bug 1 fix: keep polling position even when in PiP mode ───────────────
    // The poll loop runs unconditionally — isPipActive does not gate it.
    LaunchedEffect(Unit) { while (true) { vm.pollPosition(); delay(500) } }

    LaunchedEffect(ui.showControls) {
        if (ui.showControls && ui.state is PlayerState.Playing &&
            !ui.showSubtitleDrawer && !ui.isSpeedDrawerOpen &&
            !ui.isQualityDrawerOpen && !ui.isSettingsDrawerOpen
        ) {
            delay(4_000)
            vm.hideControls()
        }
    }

    var gestureType      by remember { mutableStateOf(GestureType.NONE) }
    var gestureValue     by remember { mutableStateOf(0f) }
    var gestureAnchorPos by remember { mutableStateOf(0f) }
    val gestureVisible   by remember { derivedStateOf { gestureType != GestureType.NONE } }

    // ── Double-tap seek state ─────────────────────────────────────────────
    var doubleTapSeekDir  by remember { mutableStateOf(0) }  // -1=back, +1=fwd, 0=none
    var doubleTapCount    by remember { mutableStateOf(0) }  // number of taps accumulated
    val doubleTapAlpha    by animateFloatAsState(if (doubleTapSeekDir != 0) 1f else 0f, tween(120), label = "dtAlpha")
    LaunchedEffect(doubleTapSeekDir, doubleTapCount) {
        if (doubleTapSeekDir != 0) {
            delay(700)
            doubleTapSeekDir = 0
            doubleTapCount   = 0
        }
    }

    val audioManager = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume    = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }

    LaunchedEffect(gestureType, gestureValue) {
        if (gestureType != GestureType.NONE) {
            delay(1_200)
            gestureType = GestureType.NONE
        }
    }

    // ── Gesture modifier: disabled while in PiP ───────────────────────────────
    val gestureModifier = Modifier.pointerInput(ui.isLocked, ui.durationMs, ui.isPipActive) {
        if (ui.isLocked || ui.isPipActive) return@pointerInput
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
            .pointerInput(ui.isLocked, ui.isPipActive, ui.showSubtitleDrawer,
                          ui.isSpeedDrawerOpen, ui.isQualityDrawerOpen, ui.isSettingsDrawerOpen) {
                detectTapGestures(
                    onTap = {
                        if (ui.isPipActive) {
                            vm.onPipModeChanged(false)
                            vm.openSettingsDrawer()
                            return@detectTapGestures
                        }
                        if (!ui.isLocked && !ui.showSubtitleDrawer &&
                            !ui.isSpeedDrawerOpen && !ui.isQualityDrawerOpen && !ui.isSettingsDrawerOpen
                        ) vm.toggleControls()
                        else if (ui.showSubtitleDrawer) vm.closeSubtitleDrawer()
                    },
                    onDoubleTap = { offset ->
                        if (ui.isLocked || ui.isPipActive) return@detectTapGestures
                        val screenW = size.width.toFloat()
                        val seekDir  = if (offset.x < screenW / 2f) -1 else 1
                        val seekMs   = 10_000L * seekDir
                        vm.seekTo((vm.exoPlayer?.currentPosition ?: 0L) + seekMs)
                        // Accumulate double-tap count for same direction
                        if (doubleTapSeekDir == seekDir) doubleTapCount++
                        else { doubleTapSeekDir = seekDir; doubleTapCount = 1 }
                    },
                )
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

        // ── PiP clean mode: show nothing but the video ────────────────────
        // When isPipActive == true every overlay is hidden; the video surface
        // above is the only visible element. The tiny floating window renders
        // only the pure video frame — exactly like YouTube / Netflix PiP.
        // We return early from the rest of the composition tree.
        if (ui.isPipActive) {
            return@Box
        }

        // ─────────────────────────────────────────────────────────────────
        // Everything below is full-screen-only UI
        // ─────────────────────────────────────────────────────────────────

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

        // ── Video ad overlay — pre/mid/post roll ─────────────────────────
        // Renders over the player surface; IMA handles the actual ad video.
        // Silent on failure: onError() always calls preRollCompleted() so
        // playback is never blocked regardless of ad SDK state.
        if (ui.isPreRollPlaying && ui.preRollVastUrl != null) {
            PlayerVideoAd(
                vastUrl     = ui.preRollVastUrl!!,
                adType      = VideoAdType.PRE_ROLL,
                config      = com.axio.reelz.data.dto.AdPrerollConfig(),
                onCompleted = { vm.preRollCompleted() },
                onSkipped   = { vm.preRollCompleted() },
                onError     = { vm.preRollCompleted() },
                modifier    = Modifier.fillMaxSize(),
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
                        errorState?.msg?.takeIf { it.isNotBlank() }
                            ?: if (errorState?.isNetworkError == true)
                                "No internet connection."
                               else
                                "Couldn\'t load this content. Please try again.",
                        color = White80, fontSize = (d.textXl.value - 2).sp,
                        textAlign = TextAlign.Center, lineHeight = (d.textXl.value * 1.4f).sp,
                    )
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

        // ── Network stall spinner — shown mid-playback without pausing/hiding controls ──
        // Unlike Buffering state (initial load), this fires only when the player
        // stalls WHILE already playing. User did NOT pause intentionally.
        AnimatedVisibility(
            visible = ui.isNetworkStalling && ui.state is PlayerState.Playing,
            enter   = fadeIn(tween(300)),
            exit    = fadeOut(tween(600)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                Modifier
                    .size(d.avatarLg)
                    .clip(CircleShape)
                    .background(Color(0xAA000000))
                    .border(d.borderThin, GlassBorderMd, CircleShape),
                Alignment.Center,
            ) {
                CinematicSpinner(size = d.spinnerMd)
            }
        }

        // ── Double-tap seek feedback — modern ripple effect ──────────────────
        if (doubleTapAlpha > 0f) {
            val isForward = doubleTapSeekDir > 0
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(doubleTapAlpha),
            ) {
                // Semi-circle glow on the tapped side
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.42f)
                        .align(if (isForward) Alignment.CenterEnd else Alignment.CenterStart)
                        .background(
                            Brush.horizontalGradient(
                                colors = if (isForward)
                                    listOf(Color.Transparent, Brand.copy(.18f))
                                else
                                    listOf(Brand.copy(.18f), Color.Transparent),
                            )
                        )
                )
                // Label: "+10s" or "−10s" × tap count
                val label = if (isForward) "+${doubleTapCount * 10}s" else "−${doubleTapCount * 10}s"
                Column(
                    Modifier
                        .align(if (isForward) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = d.spaceXl + d.spaceMd),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(d.spaceXxs),
                ) {
                    // Animated chevrons  >>>  or  <<<
                    Row(horizontalArrangement = Arrangement.spacedBy((-d.spaceMd + d.spaceXxs))) {
                        repeat(3) { i ->
                            val chevAlpha by animateFloatAsState(
                                if (doubleTapSeekDir != 0) (1f - i * 0.28f) else 0f,
                                tween(80, delayMillis = i * 40), label = "chev$i"
                            )
                            Text(
                                if (isForward) "›" else "‹",
                                color = White.copy(alpha = chevAlpha),
                                fontSize = (d.textXxl.value + 4f).sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(
                        label,
                        color      = White,
                        fontSize   = d.textSm,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp,
                    )
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
                    && !ui.isLocked
                    && ui.state !is PlayerState.Resolving
                    && ui.state !is PlayerState.Buffering
                    && ui.state !is PlayerState.Error,
            enter    = fadeIn(tween(180)),
            exit     = fadeOut(tween(300)),
        ) {
            Box(Modifier.fillMaxSize()) {
                // ── Gradient scrims ───────────────────────────────────────
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
                    // Settings cog — top-right in full-screen mode
                    Box(
                        Modifier.size(d.buttonHeightMd - d.spaceXxs).clip(CircleShape)
                            .background(if (ui.isSettingsDrawerOpen) AmberGlass else GlassMd)
                            .border(d.borderThin, if (ui.isSettingsDrawerOpen) AmberBorder else GlassBorderMd, CircleShape)
                            .clickable { vm.openSettingsDrawer() },
                        Alignment.Center,
                    ) {
                        Icon(IconSettings, null, tint = if (ui.isSettingsDrawerOpen) Brand else White,
                            modifier = Modifier.size(d.iconMd - 2.dp))
                    }
                }

                // ── Center-Left Lock Button ───────────────────────────────
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = d.spaceMd)
                        .size(d.buttonHeightMd - d.spaceXxs)
                        .clip(CircleShape)
                        .background(GlassMd)
                        .border(d.borderThin, GlassBorderMd, CircleShape)
                        .clickable { vm.toggleLock() },
                    Alignment.Center,
                ) {
                    Icon(IconUnlock, null, tint = White, modifier = Modifier.size(d.iconMd - 2.dp))
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
                        .padding(
                            start  = d.spaceMd + d.spaceXs,
                            end    = d.spaceMd + d.spaceXs,
                            bottom = d.spaceMd,
                        ),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    // ── Minimal seek bar (Netflix/YouTube style) ──────────
                    if (ui.durationMs > 0) {
                        MinimalSeekBar(
                            positionMs  = ui.positionMs,
                            durationMs  = ui.durationMs,
                            bufferedMs  = ui.bufferedMs,
                            onSeek      = { vm.seekTo(it) },
                        )
                    }

                    Spacer(Modifier.height(d.spaceSm + d.spaceXxs))

                    // ── Bottom action row ─────────────────────────────────
                    // Order: [PiP or Mute] [CC] [Speed] [Quality]
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 2.dp, Alignment.End),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        // PiP icon (global toggle ON) OR Mute (global toggle OFF)
                        if (ui.isPipGloballyEnabled) {
                            Box(
                                Modifier.size(d.buttonHeightSm).clip(CircleShape)
                                    .background(GlassMd)
                                    .border(d.borderThin, GlassBorderMd, CircleShape)
                                    .clickable {
                                        if (vm.canManualPip()) {
                                            onEnterPip()
                                        }
                                    },
                                Alignment.Center,
                            ) {
                                Icon(
                                    IconPip, null,
                                    tint     = if (vm.canManualPip()) White else White40,
                                    modifier = Modifier.size(d.iconMd - 2.dp),
                                )
                            }
                        } else {
                            Box(
                                Modifier.size(d.buttonHeightSm).clip(CircleShape)
                                    .background(GlassMd)
                                    .border(d.borderThin, GlassBorderMd, CircleShape)
                                    .clickable { vm.toggleMute() },
                                Alignment.Center,
                            ) {
                                Icon(
                                    if (ui.isMuted) IconVolumeOff else IconVolumeUp, null,
                                    tint = White, modifier = Modifier.size(d.iconMd - 2.dp),
                                )
                            }
                        }

                        // Subtitles (CC)
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .background(if (ui.subtitlesEnabled) AmberGlass else GlassMd)
                                .border(d.borderThin, if (ui.subtitlesEnabled) AmberBorder else GlassBorderMd, RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .clickable { vm.openSubtitleDrawer() }
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
                                    "CC",
                                    color      = if (ui.subtitlesEnabled) Brand else White,
                                    fontSize   = d.textSm,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        // Speed
                        Box(
                            Modifier.clip(RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .background(if (ui.isSpeedDrawerOpen) AmberGlass else GlassMd)
                                .border(d.borderThin, if (ui.isSpeedDrawerOpen) AmberBorder else GlassBorderMd, RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .clickable { vm.openSpeedDrawer() }
                                .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + 1.dp)
                        ) {
                            Text(
                                ui.playbackSpeed.let { if (it == it.toLong().toFloat()) "${it.toLong()}×" else "${it}×" },
                                color = if (ui.isSpeedDrawerOpen) Brand else White,
                                fontSize = d.textSm, fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // Quality
                        Box(
                            Modifier.clip(RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .background(if (ui.isQualityDrawerOpen) AmberGlass else GlassMd)
                                .border(d.borderThin, if (ui.isQualityDrawerOpen) AmberBorder else GlassBorderMd, RoundedCornerShape(d.radiusSm + d.spaceXxs))
                                .clickable { vm.openQualityDrawer() }
                                .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + 1.dp)
                        ) {
                            Text(
                                ui.selectedQuality,
                                color = if (ui.isQualityDrawerOpen) Brand else White,
                                fontSize = d.textSm, fontWeight = FontWeight.SemiBold,
                            )
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
                    Modifier.align(Alignment.CenterStart).padding(start = d.spaceXl)
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
            onSearchOnline = { query -> vm.searchOnlineSubtitles(query) },
            onUpgradeToPremium = {
                val intent = android.content.Intent(ctx, com.axio.reelz.app.MainActivity::class.java).apply {
                    putExtra(com.axio.reelz.app.MainActivity.EXTRA_OPEN_PREMIUM, true)
                    flags = android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                ctx.startActivity(intent)
                (ctx as? android.app.Activity)?.finish()
            },
        )

        // ── Speed drawer ──────────────────────────────────────────────────
        PlayerSideDrawer(
            visible     = ui.isSpeedDrawerOpen,
            widthTier   = DrawerWidthTier.COMPACT,
            onDismiss   = { vm.closeSpeedDrawer() },
        ) {
            DrawerOptionList(
                title    = "Playback Speed",
                options  = listOf("0.5×" to 0.5f, "0.75×" to 0.75f, "1×" to 1f, "1.25×" to 1.25f, "1.5×" to 1.5f, "2×" to 2f),
                selected = ui.playbackSpeed.let { s -> if (s == s.toLong().toFloat()) "${s.toLong()}×" else "${s}×" },
                onSelect = { _, v -> vm.setSpeed(v); vm.closeSpeedDrawer() },
                onClose  = { vm.closeSpeedDrawer() },
            )
        }

        // ── Quality drawer ────────────────────────────────────────────────
        PlayerSideDrawer(
            visible   = ui.isQualityDrawerOpen,
            widthTier = DrawerWidthTier.COMPACT,
            onDismiss = { vm.closeQualityDrawer() },
        ) {
            DrawerOptionList(
                title    = "Quality",
                options  = ui.availableQualities.map { it.label to it.label },
                selected = ui.selectedQuality,
                onSelect = { label, _ -> vm.setQuality(label); vm.closeQualityDrawer() },
                onClose  = { vm.closeQualityDrawer() },
            )
        }

        // ── Settings drawer ───────────────────────────────────────────────
        PlayerSideDrawer(
            visible   = ui.isSettingsDrawerOpen,
            widthTier = DrawerWidthTier.STANDARD,
            onDismiss = { vm.closeSettingsDrawer() },
        ) {
            SettingsDrawerContent(
                ui      = ui,
                vm      = vm,
                onClose = { vm.closeSettingsDrawer() },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Minimal seek bar — Netflix / YouTube style
// A single thin line with current-time on the left and total-time on the right.
// No thumb is shown by default; a barely-visible thumb appears only on drag.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MinimalSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
) {
    val d        = LocalDimensions.current
    val density  = LocalDensity.current
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    var isDragging     by remember { mutableStateOf(false) }
    var dragFraction   by remember { mutableStateOf(progress) }

    // Keep dragFraction in sync when not dragging
    LaunchedEffect(progress, isDragging) { if (!isDragging) dragFraction = progress }

    // Track height expands slightly while dragging — MX Player style
    val trackH by animateDpAsState(
        targetValue   = if (isDragging) d.progressBarHeight * 1.9f else d.progressBarHeight,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
        label         = "trackH",
    )
    // Scrubber (thumb) — always visible, grows on drag
    val thumbSize by animateDpAsState(
        targetValue   = if (isDragging) d.spaceMd + d.spaceSm else d.spaceMd - d.spaceXxs,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label         = "thumbSz",
    )
    val thumbGlowAlpha by animateFloatAsState(if (isDragging) 0.25f else 0f, tween(180), label = "thumbGlow")

    // Scrub-time label — show elapsed time under thumb while dragging
    val scrubLabel = if (isDragging) formatMs((dragFraction * durationMs).toLong()) else null

    val touchTargetPx = with(density) { 36.dp.toPx() }

    Column(Modifier.fillMaxWidth()) {
        // ── Time labels ────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(positionMs), color = White60, fontSize = d.textXs, fontWeight = FontWeight.Medium)
            if (scrubLabel != null) {
                Text(scrubLabel, color = Brand, fontSize = d.textXs, fontWeight = FontWeight.SemiBold)
            }
            Text(formatMs(durationMs), color = White40, fontSize = d.textXs)
        }

        Spacer(Modifier.height(d.spaceXs))

        // ── Track + scrubber ──────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .height(with(density) { touchTargetPx.toDp() })
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd   = {
                            onSeek((dragFraction * durationMs).toLong())
                            isDragging = false
                        },
                        onDragCancel = { isDragging = false },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // Background track
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackH)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(trackH / 2))
                    .background(White.copy(alpha = 0.14f))
            ) {
                // Buffered layer
                Box(Modifier.fillMaxWidth(buffered).fillMaxHeight().background(White.copy(.25f)))
                // Played layer — brand gradient (uses dragFraction when scrubbing for live preview)
                Box(
                    Modifier
                        .fillMaxWidth(if (isDragging) dragFraction else progress)
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Brand, Brand2)))
                )
            }

            // Scrubber dot — always rendered, aligned to played position
            val displayFraction = if (isDragging) dragFraction else progress
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth(displayFraction), contentAlignment = Alignment.CenterEnd) {
                    // Glow halo (only while dragging)
                    if (thumbGlowAlpha > 0f) {
                        Box(
                            Modifier
                                .size(thumbSize * 2.4f)
                                .clip(CircleShape)
                                .background(Brand.copy(thumbGlowAlpha))
                        )
                    }
                    // Thumb dot
                    Box(
                        Modifier
                            .size(thumbSize)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border((d.progressBarHeight / 2).coerceAtMost(2.dp), Brand, CircleShape)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable side drawer — slides in from right with fade
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PlayerSideDrawer(
    visible: Boolean,
    widthTier: DrawerWidthTier,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val d = LocalDimensions.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp

    val bgAlpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(280),
        label         = "drawerScrimAlpha",
    )

    val offsetX by animateDpAsState(
        targetValue   = if (visible) 0.dp else screenWidthDp + d.spaceXl,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMedium,
        ),
        label = "drawerSlideX",
    )

    if (!visible && offsetX >= screenWidthDp) return

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.50f * bgAlpha)) }
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                onDismiss()
            }
    ) {
        Column(
            Modifier
                .fillMaxWidth(widthTier.fraction)
                .fillMaxHeight()
                .align(Alignment.CenterEnd)
                .offset(x = offsetX)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xCC050510), Color(0xEE080818)),
                    )
                )
                .border(
                    width = d.borderThin,
                    brush = Brush.verticalGradient(listOf(GlassBorderHv, GlassBorderMd, GlassBorder)),
                    shape = RoundedCornerShape(topStart = d.radiusLg, bottomStart = d.radiusLg),
                )
                .clip(RoundedCornerShape(topStart = d.radiusLg, bottomStart = d.radiusLg)),
            content = content,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Generic option list for Speed / Quality drawers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun <T> DrawerOptionList(
    title: String,
    options: List<Pair<String, T>>,
    selected: String,
    onSelect: (String, T) -> Unit,
    onClose: () -> Unit,
) {
    val d = LocalDimensions.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = d.spaceMd + d.spaceXs, end = d.spaceMd, top = d.spaceXl, bottom = d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, color = White, fontSize = d.textLg, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
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
        Box(Modifier.fillMaxWidth().height(d.borderThin).background(GlassBorder))
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm),
            verticalArrangement = Arrangement.spacedBy(d.spaceXs),
        ) {
            items(options) { (label, value) ->
                val isSelected = label == selected
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(d.radiusMd - d.spaceXs))
                        .background(if (isSelected) AmberGlass else Color.Transparent)
                        .border(d.borderThin, if (isSelected) AmberBorder else Color.Transparent, RoundedCornerShape(d.radiusMd - d.spaceXs))
                        .clickable { onSelect(label, value) }
                        .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceMd - d.spaceXxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
                ) {
                    if (isSelected) {
                        Icon(IconCheck, null, tint = Brand, modifier = Modifier.size(d.iconSm + 2.dp))
                    } else {
                        Box(Modifier.size(d.iconSm + 2.dp).clip(CircleShape).background(GlassMd))
                    }
                    Text(
                        label,
                        color      = if (isSelected) Brand else White80,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize   = d.textMd,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings drawer content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsDrawerContent(
    ui: PlayerUiState,
    vm: PlayerViewModel,
    onClose: () -> Unit,
) {
    val d = LocalDimensions.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = d.spaceMd + d.spaceXs, end = d.spaceMd, top = d.spaceXl, bottom = d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Settings", color = White, fontSize = d.textLg, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
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
        Box(Modifier.fillMaxWidth().height(d.borderThin).background(GlassBorder))

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceMd),
            verticalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            // Auto Miniplayer / Global PiP toggle
            item {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(d.radiusMd))
                        .background(GlassMd)
                        .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd))
                        .clickable { vm.setGlobalPipEnabled(!ui.isPipGloballyEnabled) }
                        .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceMd - d.spaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Auto Miniplayer",
                            color = White, fontSize = d.textMd, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (ui.isPipGloballyEnabled)
                                "Video floats when you leave the app"
                            else
                                "Video stops when you leave the app",
                            color = White40, fontSize = (d.textXxs.value + 1).sp,
                        )
                    }
                    Spacer(Modifier.width(d.spaceMd))
                    SubtitleTogglePill(enabled = ui.isPipGloballyEnabled)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Subtitle Drawer
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
    onSearchOnline: (String) -> Unit,
    onUpgradeToPremium: () -> Unit,
) {
    val d = LocalDimensions.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    var searchQuery      by remember { mutableStateOf("") }
    var showOffsetSection by remember { mutableStateOf(false) }

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

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.45f * bgAlpha)) }
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClose() }
    ) {
        Box(
            Modifier
                .fillMaxWidth(DrawerWidthTier.WIDE.fraction)
                .fillMaxHeight()
                .align(Alignment.CenterEnd)
                .offset(x = offsetX)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
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
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0x33050510), Color.Transparent)))
                        .padding(top = d.spaceXl, start = d.spaceMd + d.spaceXs, end = d.spaceMd + d.spaceXs, bottom = 0.dp)
                ) {
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
                            Text("Subtitles", color = White, fontSize = d.textLg, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
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

                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(d.radiusMd))
                            .background(GlassMd)
                            .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd))
                            .clickable { onToggleOff() }
                            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceMd - d.spaceXs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Subtitles", color = White, fontSize = d.textMd, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (ui.subtitlesEnabled) "On • ${ui.subtitleOptions.firstOrNull { it.language == ui.activeSubtitleLanguage }?.label ?: ""}"
                                else "Off",
                                color = if (ui.subtitlesEnabled) Brand else White40,
                                fontSize = (d.textXxs.value + 1).sp,
                            )
                        }
                        SubtitleTogglePill(enabled = ui.subtitlesEnabled)
                    }
                    Spacer(Modifier.height(d.spaceMd - d.spaceXs))

                    val searchBorderColor = if (searchQuery.isNotEmpty()) AmberBorder else GlassBorderMd
                    val searchBg          = if (searchQuery.isNotEmpty()) AmberGlass   else GlassSm
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                            .background(searchBg)
                            .border(d.borderThin, searchBorderColor, RoundedCornerShape(d.radiusMd - d.spaceXxs))
                            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                    ) {
                        Icon(IconSearch, null,
                            tint     = if (searchQuery.isNotEmpty()) Brand else White40,
                            modifier = Modifier.size(d.iconSm + 2.dp))
                        BasicTextField(
                            value         = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine    = true,
                            textStyle     = TextStyle(color = White, fontSize = d.textSm),
                            decorationBox = { inner ->
                                Box {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            if (ui.subtitleOptions.isNotEmpty()) "Filter or type a language…"
                                            else "Type a language (e.g. French)…",
                                            color = White40, fontSize = d.textSm,
                                        )
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier.weight(1f),
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

                Box(Modifier.fillMaxWidth().height(d.borderThin).background(GlassBorder))

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
                    verticalArrangement = Arrangement.spacedBy(d.spaceXs),
                ) {
                    item {
                        SubtitleRow(
                            label        = "Off",
                            language     = "off",
                            isActive     = !ui.subtitlesEnabled,
                            isPersistent = false,
                            isEnabled    = true,
                            onClick      = { onSelect("off") },
                        )
                    }

                    if (ui.subtitleOptions.isNotEmpty()) {
                        val filtered = ui.subtitleOptions.filter {
                            searchQuery.isBlank() ||
                            it.label.contains(searchQuery, ignoreCase = true) ||
                            it.language.contains(searchQuery, ignoreCase = true)
                        }
                        if (filtered.isNotEmpty()) {
                            item {
                                Text(
                                    if (ui.isOfflinePlayback) "DOWNLOADED" else "AVAILABLE",
                                    color         = White40,
                                    fontSize      = d.textXxs,
                                    fontWeight    = FontWeight.SemiBold,
                                    letterSpacing = 1.sp,
                                    modifier      = Modifier.padding(horizontal = d.spaceXs, vertical = d.spaceSm),
                                )
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
                        } else if (searchQuery.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(d.spaceSm))
                                Text("No match in loaded subtitles", color = White40, fontSize = d.textXs,
                                    modifier = Modifier.padding(horizontal = d.spaceXs))
                            }
                        }
                    }

                    // Show search / empty CTA only when:
                    // - No options loaded yet (stream had no subtitles), OR
                    // - User is actively typing a query not found in the local list.
                    val showSearchCta = ui.subtitleOptions.isEmpty() || searchQuery.isNotEmpty()
                    if (showSearchCta) {
                        item {
                            Spacer(Modifier.height(d.spaceMd))
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = d.spaceXs),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                            ) {
                                when {
                                    ui.isSubtitleSearching -> {
                                        CircularProgressIndicator(
                                            modifier    = Modifier.size(d.iconMd + d.spaceXxs),
                                            color       = Brand,
                                            strokeWidth = d.borderMed,
                                        )
                                        Text("Searching for subtitles…", color = White40, fontSize = d.textXs)
                                    }
                                    ui.subtitleSearchEmpty -> {
                                        Icon(Icons.Default.Search, contentDescription = null,
                                            tint = White40, modifier = Modifier.size(d.iconMd))
                                        Text(
                                            "No subtitles found${if (searchQuery.isNotEmpty()) " for \"$searchQuery\"" else ""}",
                                            color = White60, fontSize = d.textSm, fontWeight = FontWeight.Medium,
                                            textAlign = TextAlign.Center,
                                        )
                                        Text("Try a different language name or check the spelling",
                                            color = White40, fontSize = d.textXs, textAlign = TextAlign.Center,
                                            lineHeight = (d.textXs.value * 1.5f).sp)
                                        Box(
                                            Modifier.fillMaxWidth()
                                                .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .background(GlassMd)
                                                .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .clickable { onSearchOnline(searchQuery) }
                                                .padding(vertical = d.spaceSm + d.spaceXxs),
                                            Alignment.Center,
                                        ) { Text("Try again", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold) }
                                    }
                                    ui.subtitleUpsellMessage != null -> {
                                        // Only the ONLINE SEARCH feature requires Premium.
                                        // Stream subtitles (listed above) are free for everyone.
                                        Icon(Icons.Default.Search, contentDescription = null,
                                            tint = Brand.copy(.6f), modifier = Modifier.size(d.iconMd))
                                        Text(ui.subtitleUpsellMessage, color = White60, fontSize = d.textXs,
                                            textAlign = TextAlign.Center, lineHeight = (d.textXs.value * 1.45f).sp)
                                        Box(
                                            Modifier.fillMaxWidth()
                                                .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .background(AmberGlass)
                                                .border(d.borderThin, AmberBorder, RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .clickable { onUpgradeToPremium() }
                                                .padding(vertical = d.spaceSm + d.spaceXxs),
                                            Alignment.Center,
                                        ) { Text("Upgrade to Premium", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.Bold) }
                                    }
                                    else -> {
                                        if (ui.subtitleOptions.isEmpty() && !ui.isOfflinePlayback) {
                                            Text(
                                                "No subtitles came with this stream",
                                                color = White40, fontSize = d.textXs, textAlign = TextAlign.Center,
                                            )
                                        }
                                        // Search Online button — gating is enforced inside the ViewModel
                                        Box(
                                            Modifier.fillMaxWidth()
                                                .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .background(AmberGlass)
                                                .border(d.borderThin, AmberBorder, RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                                .clickable { onSearchOnline(searchQuery) }
                                                .padding(vertical = d.spaceSm + d.spaceXs),
                                            Alignment.Center,
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                                            ) {
                                                Icon(Icons.Default.Search, contentDescription = null,
                                                    tint = Brand, modifier = Modifier.size(d.iconSm + 2.dp))
                                                Text(
                                                    if (searchQuery.isNotEmpty()) "Search for \"$searchQuery\"" else "Search Online",
                                                    color = Brand, fontSize = d.textSm, fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                        if (searchQuery.isEmpty()) {
                                            Text("Search for subtitles in any language",
                                                color = White40, fontSize = d.textXxs, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(d.spaceXs))
                        }
                    }
                    item { Spacer(Modifier.height(d.spaceSm + d.spaceXxs)) }
                }

                Box(Modifier.fillMaxWidth().height(d.borderThin).background(GlassBorder))

                Column(
                    Modifier.fillMaxWidth()
                        .background(Color(0x22050510))
                        .padding(d.spaceMd - d.spaceXxs),
                    verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
                ) {
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
                            Text("Subtitle Timing", color = White60, fontSize = d.textXs, fontWeight = FontWeight.Medium)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
                        ) {
                            if (ui.subtitleOffsetMs != 0) {
                                val sign = if (ui.subtitleOffsetMs > 0) "+" else ""
                                Text("${sign}${ui.subtitleOffsetMs / 1000.0}s",
                                    color = Brand, fontSize = (d.textXxs.value + 1).sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(if (showOffsetSection) "▲" else "▼", color = White40, fontSize = d.textXxs)
                        }
                    }
                    AnimatedVisibility(visible = showOffsetSection) {
                        SubtitleOffsetControl(offsetMs = ui.subtitleOffsetMs, onChanged = onOffsetChange)
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
        Modifier.fillMaxWidth()
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
                label, color = textColor, fontSize = d.textSm,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (isPersistent && onToggle != null) {
            Spacer(Modifier.width(d.spaceSm))
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusPill))
                    .background(if (isEnabled) AmberGlass else GlassSm)
                    .border(d.borderThin, if (isEnabled) AmberBorder else GlassBorderMd, RoundedCornerShape(d.radiusPill))
                    .clickable { onToggle() }
                    .padding(horizontal = d.spaceSm + d.spaceXxs, vertical = d.spaceXxs + 1.dp),
            ) {
                Text(
                    if (isEnabled) "On" else "Off",
                    color = if (isEnabled) Brand else White40,
                    fontSize = (d.textXxs.value + 1).sp, fontWeight = FontWeight.SemiBold,
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
    // Proper pill toggle — track is proportional to d.spaceLg, thumb fills height minus 2*d.spaceXxs
    // Sizes computed from dimension tokens so it adapts across devices
    val trackH  = d.spaceLg + d.spaceXxs        // track height
    val trackW  = trackH * 1.9f                  // track width = 1.9× height (standard toggle ratio)
    val thumbSz = trackH - d.spaceXs             // thumb slightly smaller than track height
    val travel  = trackW - thumbSz - d.spaceXxs  // how far thumb travels

    val trackBg  by animateColorAsState(if (enabled) Brand.copy(.9f) else Color(0xFF3A3A3C), label = "trackBg")
    val thumbOff by animateDpAsState(if (enabled) travel else d.spaceXxs / 2, animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f), label = "thumb")

    Box(
        Modifier
            .width(trackW)
            .height(trackH)
            .clip(RoundedCornerShape(trackH / 2))
            .background(trackBg)
    ) {
        Box(
            Modifier
                .size(thumbSz)
                .offset(x = thumbOff, y = (trackH - thumbSz) / 2)
                .clip(CircleShape)
                .background(Color.White)
                .shadow(2.dp, CircleShape),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Subtitle timing offset control
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SubtitleOffsetControl(offsetMs: Int, onChanged: (Int) -> Unit) {
    val d = LocalDimensions.current
    Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val sign = if (offsetMs > 0) "+" else ""
            Text("${sign}${offsetMs / 1000.0}s",
                color = if (offsetMs == 0) White40 else Brand, fontSize = d.textXxl, fontWeight = FontWeight.Bold)
        }
        Text("Positive = delay   Negative = advance",
            color = White40, fontSize = d.textXxs, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spaceXs)) {
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .background(GlassMd)
                    .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .clickable { onChanged((offsetMs - 500).coerceAtLeast(-10_000)) }
                    .padding(vertical = d.spaceSm + d.spaceXxs),
                Alignment.Center,
            ) { Text("−0.5s", color = White80, fontSize = d.textXs, fontWeight = FontWeight.Medium) }
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .background(if (offsetMs != 0) AmberGlass else GlassMd)
                    .border(d.borderThin, if (offsetMs != 0) AmberBorder else GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .clickable { onChanged(0) }
                    .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
                Alignment.Center,
            ) { Text("Reset", color = if (offsetMs != 0) Brand else White40, fontSize = d.textXs, fontWeight = FontWeight.SemiBold) }
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .background(GlassMd)
                    .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXs))
                    .clickable { onChanged((offsetMs + 500).coerceAtMost(10_000)) }
                    .padding(vertical = d.spaceSm + d.spaceXxs),
                Alignment.Center,
            ) { Text("+0.5s", color = White80, fontSize = d.textXs, fontWeight = FontWeight.Medium) }
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
        Modifier.clip(RoundedCornerShape(d.radiusMd + d.spaceXs))
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
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
