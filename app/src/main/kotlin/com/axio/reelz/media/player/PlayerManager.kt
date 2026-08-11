package com.axio.reelz.media.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.axio.reelz.data.model.DownloadStatus
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.data.model.QualityTrack
import com.axio.reelz.data.model.StreamResult
import com.axio.reelz.data.repository.LibraryRepository
import com.axio.reelz.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "PlayerManager"

/**
 * PlayerManager — ExoPlayer setup, teardown, and playback control.
 *
 * Extracted from PlayerViewModel. Owns:
 *  - ExoPlayer construction (track selector, load control, bandwidth meter, cache)
 *  - Network connectivity monitoring
 *  - Playback control (play/pause/seek/speed/quality/mute)
 *  - Position polling
 *  - Error handling with fallback + silent retry logic
 *  - PiP-aware stop/resume
 *
 * PlayerViewModel coordinates PlayerManager + SubtitleManager + StreamRepository.
 * PlayerActivity only observes PlayerViewModel state — never touches PlayerManager directly.
 *
 * Dependency direction: PlayerManager → (LibraryRepository | UserRepository). Never touches UI.
 */
@OptIn(UnstableApi::class)
class PlayerManager(
    private val appContext: Context,
    private val libraryRepo: LibraryRepository,
    private val userRepo: UserRepository,
    private val scope: CoroutineScope,
    private val onError: (PlaybackException) -> Unit,
    private val onTracksChanged: (List<QualityTrack>) -> Unit,
    private val onNetworkChanged: (NetworkState) -> Unit,
) {
    // ── ExoPlayer instance ────────────────────────────────────────────────────

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow: StateFlow<ExoPlayer?> = _player.asStateFlow()
    val player: ExoPlayer? get() = _player.value

    // ── Playback UI state (position, duration, buffered) ─────────────────────

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // ── Network monitoring ────────────────────────────────────────────────────

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    var isOnMeteredConnection = false
        private set

    // ── Current media context ─────────────────────────────────────────────────

    private var currentId      = ""
    private var currentSeason  = 0
    private var currentEpisode = 0
    private var currentTitle   = ""
    private var trackSelector: DefaultTrackSelector? = null

    // ── Offline state ─────────────────────────────────────────────────────────

    var offlineDownloads: List<com.axio.reelz.data.model.DownloadItem> = emptyList()
    var preferredOfflineQuality: String = ""

    // ── PiP ───────────────────────────────────────────────────────────────────

    private var _wasInPipBeforeStop = false
    fun wasInPipBeforeStop() = _wasInPipBeforeStop
    fun setWasInPip(value: Boolean) { _wasInPipBeforeStop = value }

    // ── Max resolution (premium gate) ─────────────────────────────────────────

    fun maxResolutionHeight(): Int = if (userRepo.isPremium) Int.MAX_VALUE else 720
    fun canBackgroundPlay(): Boolean = userRepo.isPremium

    // ── Network monitor ───────────────────────────────────────────────────────

    fun startNetworkMonitor() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnMeteredConnection = try { cm.isActiveNetworkMetered } catch (_: Exception) { true }
                onNetworkChanged(NetworkState.Connected)
            }
            override fun onLost(network: Network) {
                onNetworkChanged(NetworkState.Disconnected)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                isOnMeteredConnection = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        networkCallback = cb
        try { cm.registerNetworkCallback(req, cb) } catch (_: Exception) {}
    }

    fun stopNetworkMonitor() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let {
            try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
    }

    // ── Player build ──────────────────────────────────────────────────────────

    fun buildPlayer(context: Context, subtitleManager: SubtitleManager) {
        val maxHeight = maxResolutionHeight()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        isOnMeteredConnection = try { cm?.isActiveNetworkMetered == true } catch (_: Exception) { true }

        val effectiveMaxHeight  = if (isOnMeteredConnection) minOf(maxHeight, 480) else maxHeight
        val effectiveMaxBitrate = if (isOnMeteredConnection) 1_200_000 else Int.MAX_VALUE

        val ts = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters()
                .setPreferredTextLanguage("en")
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setMaxVideoSize(Int.MAX_VALUE, effectiveMaxHeight)
                .setMaxVideoBitrate(effectiveMaxBitrate))
        }
        trackSelector = ts
        subtitleManager.trackSelector = ts

        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).setResetOnNetworkTypeChange(true).build()
        val loadCtrl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 60_000, 500, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES * 4)
            .build()

        val videoCache = getVideoCache(context)
        val upstreamDsf = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(4_000).setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true).setTransferListener(bandwidthMeter)
        val cacheDsf = CacheDataSource.Factory()
            .setCache(videoCache).setUpstreamDataSourceFactory(upstreamDsf)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val mh = maxHeight
        _player.value = ExoPlayer.Builder(context)
            .setTrackSelector(ts).setLoadControl(loadCtrl).setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDsf))
            .build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> _playbackState.update { it.copy(playerState = PlayerState.Buffering) }
                            Player.STATE_READY     -> _playbackState.update { it.copy(
                                playerState = if (p.playWhenReady) PlayerState.Playing else PlayerState.Paused,
                                durationMs = p.duration.coerceAtLeast(0)) }
                            Player.STATE_ENDED     -> _playbackState.update { it.copy(playerState = PlayerState.Idle) }
                            else -> {}
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _playbackState.update { it.copy(playerState = if (isPlaying) PlayerState.Playing else PlayerState.Paused) }
                    }
                    override fun onPlayerError(error: PlaybackException) { onError(error) }
                    override fun onTracksChanged(tracks: Tracks) {
                        val qualities = mutableListOf(QualityTrack("Auto", ""))
                        tracks.groups.forEach { g ->
                            if (g.type == C.TRACK_TYPE_VIDEO) {
                                for (i in 0 until g.length) {
                                    val fmt = g.getTrackFormat(i)
                                    if (fmt.height > 0 && fmt.height <= mh) {
                                        val label = when {
                                            fmt.height >= 2000 -> "2160p"
                                            fmt.height >= 900  -> "1080p"
                                            fmt.height >= 600  -> "720p"
                                            fmt.height >= 420  -> "480p"
                                            fmt.height >= 300  -> "360p"
                                            else               -> "240p"
                                        }
                                        qualities.add(QualityTrack(label, "", fmt.bitrate.toLong()))
                                    }
                                }
                            }
                        }
                        onTracksChanged(qualities.distinctBy { it.label }
                            .sortedByDescending { if (it.label == "Auto") Int.MAX_VALUE else it.label.replace("p","").toIntOrNull() ?: 0 })
                    }
                })
                p.playWhenReady = true
            }
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    fun setMediaContext(id: String, season: Int, episode: Int, title: String) {
        currentId = id; currentSeason = season; currentEpisode = episode; currentTitle = title
    }

    fun playStream(result: StreamResult) {
        val p = _player.value ?: return
        val isLocalFile = result.url.startsWith("file://")
        val item = MediaItem.Builder().setUri(result.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle).build()).build()

        val mediaDsf = if (isLocalFile) {
            DefaultDataSource.Factory(appContext)
        } else {
            val upstreamDsf = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(result.headers)
                .setConnectTimeoutMs(4_000).setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true)
            CacheDataSource.Factory()
                .setCache(getVideoCache(appContext)).setUpstreamDataSourceFactory(upstreamDsf)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        val source = if (result.isHls)
            HlsMediaSource.Factory(mediaDsf).setAllowChunklessPreparation(true).createMediaSource(item)
        else
            ProgressiveMediaSource.Factory(mediaDsf).createMediaSource(item)

        scope.launch {
            val resumeMs = libraryRepo.getProgress(currentId, currentSeason, currentEpisode)?.positionMs ?: 0L
            p.setMediaSource(source); p.prepare()
            if (resumeMs > 5_000) p.seekTo(resumeMs)
            p.playWhenReady = true
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    fun togglePlayPause() { _player.value?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(ms: Long)  { _player.value?.seekTo(ms) }
    fun seekForward(sec: Int = 10)  { _player.value?.let { it.seekTo((it.currentPosition + sec * 1000L).coerceAtMost(it.duration)) } }
    fun seekBackward(sec: Int = 10) { _player.value?.let { it.seekTo((it.currentPosition - sec * 1000L).coerceAtLeast(0)) } }
    fun toggleMute(isMuted: Boolean) { _player.value?.volume = if (isMuted) 0f else 1f }
    fun setSpeed(speed: Float) { _player.value?.setPlaybackSpeed(speed) }

    fun setQuality(label: String, isOfflinePlayback: Boolean) {
        if (isOfflinePlayback && offlineDownloads.isNotEmpty()) {
            preferredOfflineQuality = label
            val match = offlineDownloads.firstOrNull { it.quality == label && it.status == DownloadStatus.DONE }
                ?: offlineDownloads.firstOrNull { it.status == DownloadStatus.DONE }
            if (match != null) {
                val url = match.filePath.takeIf { it.isNotBlank() } ?: return
                val savedPos = _player.value?.currentPosition ?: 0L
                val result = StreamResult(url = url, isHls = url.contains(".m3u8", ignoreCase = true))
                scope.launch {
                    playStream(result)
                    if (savedPos > 0) _player.value?.seekTo(savedPos)
                    libraryRepo.saveProgress(currentId, currentSeason, currentEpisode, savedPos,
                        _player.value?.duration?.coerceAtLeast(0L) ?: 0L)
                }
            }
            return
        }
        val ts = trackSelector ?: return
        val mh = maxResolutionHeight()
        if (label == "Auto") {
            val autoH = if (isOnMeteredConnection) minOf(mh, 480) else mh
            ts.setParameters(ts.buildUponParameters().clearVideoSizeConstraints().setMaxVideoSize(Int.MAX_VALUE, autoH))
        } else {
            val requested = label.replace("p","").toIntOrNull() ?: return
            val h = requested.coerceAtMost(mh)
            ts.setParameters(ts.buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, h)
                .setMinVideoSize(0, (h - 80).coerceAtLeast(0)).setMaxVideoBitrate(Int.MAX_VALUE))
        }
    }

    fun pollPosition() {
        val p = _player.value ?: return
        val pos = p.currentPosition.coerceAtLeast(0)
        val dur = p.duration.coerceAtLeast(0)
        _playbackState.update { it.copy(
            positionMs = pos,
            bufferedMs = p.bufferedPosition.coerceAtLeast(0),
            durationMs = dur,
        ) }
        if (dur > 0) {
            scope.launch { libraryRepo.saveProgress(currentId, currentSeason, currentEpisode, pos, dur) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun stop() {
        _player.value?.stop()
    }

    fun reset() {
        _player.value?.stop()
        _player.value?.clearMediaItems()
        _player.value?.release()
        _player.value = null
        trackSelector = null
    }

    fun release(saveProgress: Boolean = false) {
        stopNetworkMonitor()
        val p = _player.value
        if (p != null && saveProgress && currentId.isNotBlank()) {
            val posMs = p.currentPosition.coerceAtLeast(0L)
            val durMs = p.duration.coerceAtLeast(0L)
            kotlinx.coroutines.runBlocking {
                try { libraryRepo.saveProgress(currentId, currentSeason, currentEpisode, posMs, durMs) }
                catch (_: Exception) {}
            }
        }
        p?.stop(); p?.clearMediaItems(); p?.release()
        _player.value = null
    }

    // ── Video cache singleton ─────────────────────────────────────────────────

    companion object {
        @Volatile private var _videoCache: SimpleCache? = null

        fun getVideoCache(context: Context): SimpleCache =
            _videoCache ?: synchronized(this) {
                _videoCache ?: SimpleCache(
                    File(context.cacheDir, "reelz_video_cache"),
                    LeastRecentlyUsedCacheEvictor(100L * 1024 * 1024),
                ).also { _videoCache = it }
            }
    }
}

data class PlaybackState(
    val playerState: PlayerState = PlayerState.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
)
