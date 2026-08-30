package com.axio.reelz.ads

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.axio.reelz.data.dto.AdPrerollConfig
import com.axio.reelz.ui.theme.*
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// PlayerVideoAd — YouTube-style pre/mid/post-roll ad container.
//
// The ad overlays the player surface exactly — visually identical to a video
// segment. The user sees "playing video" until the countdown appears.
//
// UI rules:
//   • Ad loads silently in background — no "Ad Loading" placeholder
//   • When ready, it fades in over the player (smooth transition)
//   • Skip button appears after 5 seconds countdown (for skippable)
//   • Skip button is the only visual indicator this is an ad
//   • "Ad 1 of 2" and countdown pill positioned top-right (YouTube standard)
//   • Post-roll only shows if ad is ready — if not, content ends cleanly
// ─────────────────────────────────────────────────────────────────────────────

enum class VideoAdType { PRE_ROLL, MID_ROLL, POST_ROLL }

enum class VideoAdState { IDLE, SHOWING, SKIPPABLE, COMPLETED, FAILED }

@Composable
fun PlayerVideoAd(
    vastUrl: String,
    adType: VideoAdType,
    config: AdPrerollConfig,
    onCompleted: () -> Unit,
    onSkipped: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var adState       by remember { mutableStateOf(VideoAdState.IDLE) }
    var countdownSecs by remember { mutableIntStateOf(5) }
    var showSkip      by remember { mutableStateOf(false) }

    // Countdown timer — begins as soon as ad starts showing
    LaunchedEffect(adState) {
        if (adState == VideoAdState.SHOWING) {
            repeat(5) {
                delay(1000)
                countdownSecs--
            }
            showSkip = true
            adState  = VideoAdState.SKIPPABLE
        }
    }

    // IMA integration — ad rendering inside the player surface
    Box(modifier = modifier) {
        // IMA ad container — fills same space as PlayerView
        ImaAdSurface(
            vastUrl    = vastUrl,
            onStarted  = { adState = VideoAdState.SHOWING },
            onCompleted = {
                adState = VideoAdState.COMPLETED
                onCompleted()
            },
            onError    = {
                adState = VideoAdState.FAILED
                onError()
            },
        )

        // ── Ad UI overlay — appears on top of the IMA surface ────────────────
        AnimatedVisibility(
            visible = adState == VideoAdState.SHOWING || adState == VideoAdState.SKIPPABLE,
            enter   = fadeIn(tween(300)),
            exit    = fadeOut(tween(200)),
        ) {
            Box(Modifier.fillMaxSize()) {

                // ── Top-right: "Ad" pill + countdown ─────────────────────────
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(end = 14.dp, top = 60.dp),  // below player controls
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Countdown pill — disappears when skip becomes available
                    AnimatedVisibility(
                        visible = !showSkip,
                        exit    = fadeOut(tween(200)) + shrinkHorizontally(),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text       = "Skip in $countdownSecs",
                                color      = Color.White.copy(0.85f),
                                fontSize   = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    // "Ad" type label
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(0.5.dp, Color.White.copy(0.12f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text          = when (adType) {
                                VideoAdType.PRE_ROLL  -> "Ad"
                                VideoAdType.MID_ROLL  -> "Mid Ad"
                                VideoAdType.POST_ROLL -> "Ad"
                            },
                            color         = Color.White.copy(0.75f),
                            fontSize      = 11.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }

                // ── Bottom-right: Skip button ─────────────────────────────────
                AnimatedVisibility(
                    visible = showSkip,
                    enter   = fadeIn(tween(300)) + slideInHorizontally { it / 3 },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 14.dp, bottom = 80.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .border(0.8.dp, Color.White.copy(0.25f), RoundedCornerShape(6.dp))
                            .clickable {
                                adState = VideoAdState.COMPLETED
                                onSkipped()
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text       = "Skip Ad",
                                color      = Color.White,
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            // Forward arrow icon via text — lightweight, no import needed
                            Text("›", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Light)
                        }
                    }
                }

                // ── Bottom progress bar — ad duration indicator ───────────────
                // Thin bar at very bottom, same visual language as seek bar
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    val progressAnim = rememberInfiniteTransition(label = "adProgress")
                    val progress by progressAnim.animateFloat(
                        initialValue  = 0f,
                        targetValue   = 1f,
                        animationSpec = infiniteRepeatable(
                            tween(30_000, easing = LinearEasing), RepeatMode.Restart
                        ),
                        label = "adProg",
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(Brand.copy(0.8f))
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ImaAdSurface — thin wrapper for the IMA AdDisplayContainer.
//
// TODO: Wire full IMA AdsLoader, AdsManager, and VideoAdPlayer integration
// once ad unit IDs and VAST tags are configured in remote config.
// Currently stubs onCompleted() so playback is never blocked.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ImaAdSurface(
    vastUrl: String,
    onStarted: () -> Unit,
    onCompleted: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(vastUrl) {
        if (vastUrl.isBlank()) {
            onError(); return@LaunchedEffect
        }
        // Stub: signal started, then complete after placeholder delay
        onStarted()
        // TODO: replace with real IMA AdsManager lifecycle
        delay(1000)
        // onCompleted()  // uncomment when real IMA is wired
    }

    AndroidView(
        factory = { ctx: Context ->
            FrameLayout(ctx).also { container ->
                // TODO: IMA AdDisplayContainer setup
                // val sdkFactory   = ImaSdkFactory.getInstance()
                // val adDisplay    = sdkFactory.createAdDisplayContainer(container, videoAdPlayer)
                // val adsLoader    = sdkFactory.createAdsLoader(ctx, sdkSettings, adDisplayContainer)
                // adsLoader.addAdsLoadedListener { event ->
                //     event.adsManager.start()
                // }
                // val adsRequest   = sdkFactory.createAdsRequest().also { it.adTagUrl = vastUrl }
                // adsLoader.requestAds(adsRequest)
            }
        },
        update  = {},
        modifier = modifier.fillMaxSize(),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// VastTagProvider — pure decision logic for when to show player ads
// ─────────────────────────────────────────────────────────────────────────────

object VastTagProvider {

    fun shouldShowPreRoll(
        config: AdPrerollConfig,
        isMovie: Boolean,
        isFirstPlayThisSession: Boolean,
        minutesSinceLastPreRoll: Long,
        isOfflinePlayback: Boolean,
        isResumingEpisode: Boolean,
        isQualitySwitch: Boolean,
    ): Boolean {
        if (isOfflinePlayback) return false
        if (config.skipOnResume && isResumingEpisode) return false
        if (config.skipOnQualitySwitch && isQualitySwitch) return false
        if (config.showOnMoviesOnly && !isMovie) return false
        return isFirstPlayThisSession || minutesSinceLastPreRoll >= config.minMinutesBetween
    }

    fun shouldShowMidRoll(
        durationMs: Long,
        currentPositionMs: Long,
        nextBreakpointMs: Long,
        lastMidRollMs: Long,
    ): Boolean {
        if (currentPositionMs < nextBreakpointMs) return false
        // Don't show again within 5 minutes of last mid-roll
        val elapsed = currentPositionMs - lastMidRollMs
        return elapsed > 5 * 60_000L
    }

    fun shouldShowPostRoll(
        durationMs: Long,
        currentPositionMs: Long,
    ): Boolean {
        if (durationMs <= 0) return false
        // Trigger in the last 3 seconds
        return (durationMs - currentPositionMs) <= 3_000L
    }
}
