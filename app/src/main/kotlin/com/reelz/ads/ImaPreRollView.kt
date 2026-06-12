package com.reelz.ads

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ads.interactivemedia.v3.api.*
import com.google.ads.interactivemedia.v3.api.player.AdMediaInfo
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate

private const val TAG = "ImaPreRollView"

/**
 * A composable that plays a VAST pre-roll ad using the IMA SDK.
 *
 * The IMA SDK is given a [VideoAdPlayer] backed by a [VideoView]. When the ad finishes
 * (complete, skip, or error), [onAdCompleted] or [onAdError] is called, which the
 * [PlayerViewModel] uses to start actual content playback.
 *
 * This composable takes the full screen — place it on top of the player composable.
 */
@Composable
fun ImaPreRollView(
    vastUrl: String,
    onAdCompleted: () -> Unit,
    onAdError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Stable callbacks — capture latest ref without re-creating the view
    val onCompletedRef = rememberUpdatedState(onAdCompleted)
    val onErrorRef     = rememberUpdatedState(onAdError)

    AndroidView(
        modifier = modifier,
        factory  = { ctx ->
            buildImaAdView(
                context     = ctx,
                vastUrl     = vastUrl,
                onCompleted = { onCompletedRef.value() },
                onError     = { onErrorRef.value() },
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Android view factory — keeps Compose-specific code out
// ─────────────────────────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
private fun buildImaAdView(
    context: Context,
    vastUrl: String,
    onCompleted: () -> Unit,
    onError: () -> Unit,
): FrameLayout {
    val root = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    // VideoView — IMA renders the ad creative here
    val videoView = VideoView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    root.addView(videoView)

    // ── IMA SDK setup ─────────────────────────────────────────────────────────
    val sdkFactory  = ImaSdkFactory.getInstance()
    val sdkSettings = sdkFactory.createImaSdkSettings().apply {
        language = "en"
    }
    val videoAdPlayer = buildVideoAdPlayer(videoView, onCompleted, onError)
    val adDisplayContainer = sdkFactory.createAdDisplayContainer().apply {
        adContainer = root
        setPlayer(videoAdPlayer)
    }
    val adsLoader = sdkFactory.createAdsLoader(context, sdkSettings, adDisplayContainer)

    adsLoader.addAdsLoadedListener { event: AdsManagerLoadedEvent ->
        val adsManager = event.adsManager
        adsManager.addAdEventListener { adEvent: AdEvent ->
            Log.d(TAG, "Ad event: ${adEvent.type}")
            when (adEvent.type) {
                AdEvent.AdEventType.LOADED            -> adsManager.start()
                AdEvent.AdEventType.CONTENT_PAUSE_REQUESTED -> {}
                AdEvent.AdEventType.CONTENT_RESUME_REQUESTED -> onCompleted()
                AdEvent.AdEventType.ALL_ADS_COMPLETED -> onCompleted()
                AdEvent.AdEventType.SKIPPED           -> onCompleted()
                else -> {}
            }
        }
        adsManager.addAdErrorListener { adErrorEvent: AdErrorEvent ->
            Log.w(TAG, "Ad error: ${adErrorEvent.error.message}")
            onError()
        }
        adsManager.init()
    }

    adsLoader.addAdErrorListener { adErrorEvent: AdErrorEvent ->
        Log.w(TAG, "Ads loader error: ${adErrorEvent.error.message}")
        onError()
    }

    val request: AdsRequest = sdkFactory.createAdsRequest().apply {
        adTagUrl = vastUrl
    }
    adsLoader.requestAds(request)

    return root
}

// ─────────────────────────────────────────────────────────────────────────────
// VideoAdPlayer implementation — bridges IMA → VideoView
// ─────────────────────────────────────────────────────────────────────────────

private fun buildVideoAdPlayer(
    videoView: VideoView,
    onCompleted: () -> Unit,
    onError: () -> Unit,
): VideoAdPlayer {
    val callbacks = mutableListOf<VideoAdPlayer.VideoAdPlayerCallback>()
    var currentAdInfo: AdMediaInfo? = null

    videoView.setOnPreparedListener { mp ->
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        callbacks.forEach { it.onPlay(currentAdInfo!!) }
    }
    videoView.setOnCompletionListener {
        callbacks.forEach { it.onEnded(currentAdInfo!!) }
        onCompleted()
    }
    videoView.setOnErrorListener { _, _, _ ->
        callbacks.forEach { currentAdInfo?.let { info -> it.onError(info) } }
        onError()
        true
    }

    return object : VideoAdPlayer {
        override fun playAd(adMediaInfo: AdMediaInfo) {
            currentAdInfo = adMediaInfo
            videoView.setVideoURI(Uri.parse(adMediaInfo.url))
            videoView.requestFocus()
            videoView.start()
        }
        override fun loadAd(adMediaInfo: AdMediaInfo, adPodInfo: com.google.ads.interactivemedia.v3.api.AdPodInfo) {
            currentAdInfo = adMediaInfo
            videoView.setVideoURI(Uri.parse(adMediaInfo.url))
        }
        override fun stopAd(adMediaInfo: AdMediaInfo) {
            videoView.stopPlayback()
        }
        override fun pauseAd(adMediaInfo: AdMediaInfo) {
            videoView.pause()
            callbacks.forEach { it.onPause(adMediaInfo) }
        }
        override fun addCallback(cb: VideoAdPlayer.VideoAdPlayerCallback) {
            callbacks.add(cb)
        }
        override fun removeCallback(cb: VideoAdPlayer.VideoAdPlayerCallback) {
            callbacks.remove(cb)
        }
        override fun getAdProgress(): VideoProgressUpdate {
            val duration = videoView.duration.toLong()
            val current  = videoView.currentPosition.toLong()
            return if (duration <= 0) VideoProgressUpdate.VIDEO_TIME_NOT_READY
            else VideoProgressUpdate(current, duration)
        }
        override fun getVolume(): Int = 100
        override fun release() {
            callbacks.clear()
            videoView.stopPlayback()
        }
    }
}
