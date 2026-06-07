package com.reelz.ui.screens.player

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.reelz.data.model.MediaType
import com.reelz.data.model.QualityTrack
import com.reelz.data.model.StreamResult
import com.reelz.data.repository.MediaRepository
import com.reelz.scanner.StreamEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class PlayerState {
    object Idle      : PlayerState()
    object Resolving : PlayerState()
    object Buffering : PlayerState()
    object Playing   : PlayerState()
    object Paused    : PlayerState()
    data class Error(val msg: String) : PlayerState()
}

data class PlayerUiState(
    val state: PlayerState       = PlayerState.Idle,
    val title: String            = "",
    val episodeLabel: String     = "",
    val durationMs: Long         = 0L,
    val positionMs: Long         = 0L,
    val bufferedMs: Long         = 0L,
    val showControls: Boolean    = true,
    val sourceName: String       = "",
    val playbackSpeed: Float     = 1f,
    val availableQualities: List<QualityTrack> = emptyList(),
    val selectedQuality: String  = "Auto",
    val isLocked: Boolean        = false,          // lock controls
    val isMuted: Boolean         = false,
    val subtitles: List<com.reelz.data.model.Subtitle> = emptyList(),
    val selectedSubtitle: String = "Off",
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val engine: StreamEngine,
    private val repo: MediaRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private val _exoPlayer = MutableStateFlow<ExoPlayer?>(null)
    val exoPlayerFlow: StateFlow<ExoPlayer?> = _exoPlayer.asStateFlow()
    var exoPlayer: ExoPlayer?
        get() = _exoPlayer.value
        private set(value) { _exoPlayer.value = value }

    private var currentTmdbId   = -1
    private var currentType     = MediaType.MOVIE
    private var currentSeason   = 0
    private var currentEpisode  = 0
    private var currentTitle    = ""
    private var currentPoster: String? = null
    private var lastResult: StreamResult? = null
    private var trackSelector: DefaultTrackSelector? = null

    @OptIn(UnstableApi::class)
    fun init(
        context: Context,
        tmdbId: Int, mediaType: MediaType,
        season: Int, episode: Int,
        title: String, posterPath: String?,
    ) {
        currentTmdbId   = tmdbId
        currentType     = mediaType
        currentSeason   = season
        currentEpisode  = episode
        currentTitle    = title
        currentPoster   = posterPath
        val epLabel = if (season > 0) "S${season} E${episode}" else ""
        _ui.update { it.copy(title = title, episodeLabel = epLabel, state = PlayerState.Resolving) }
        resetPlayer()
        buildPlayer(context)
        viewModelScope.launch {
            withContext(Dispatchers.Main) { wipeCookies() }
            resolveAndPlay(tmdbId, mediaType, season, episode)
        }
    }

    private fun wipeCookies() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
    }

    private fun resetPlayer() {
        exoPlayer?.stop(); exoPlayer?.clearMediaItems(); exoPlayer?.release()
        exoPlayer = null; trackSelector = null
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(context: Context) {
        val ts = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setPreferredTextLanguage("en"))
        }
        trackSelector = ts

        // UPGRADE P8: Reduce min buffer before playback from 12s → 2.5s.
        // Netflix uses ~2s. Users see video 8–10 seconds sooner.
        val loadCtrl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_500,    // min buffer before playback starts (was 12_000)
                50_000,   // max buffer
                500,      // min buffer to resume after rebuffer
                1_000,    // min buffer to resume after seek
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES * 2)
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setTrackSelector(ts)
            .setLoadControl(loadCtrl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> _ui.update { it.copy(state = PlayerState.Buffering) }
                            Player.STATE_READY     -> _ui.update { it.copy(
                                state      = if (p.playWhenReady) PlayerState.Playing else PlayerState.Paused,
                                durationMs = p.duration.coerceAtLeast(0),
                            )}
                            Player.STATE_ENDED     -> _ui.update { it.copy(state = PlayerState.Idle) }
                            else -> {}
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _ui.update { it.copy(state = if (isPlaying) PlayerState.Playing else PlayerState.Paused) }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        handleError(context, error)
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        // Extract available quality tracks from HLS
                        val qualities = mutableListOf(QualityTrack("Auto", ""))
                        tracks.groups.forEach { g ->
                            if (g.type == C.TRACK_TYPE_VIDEO) {
                                for (i in 0 until g.length) {
                                    val fmt = g.getTrackFormat(i)
                                    if (fmt.height > 0) {
                                        qualities.add(QualityTrack("${fmt.height}p", "", fmt.bitrate.toLong()))
                                    }
                                }
                            }
                        }
                        _ui.update { it.copy(availableQualities = qualities.distinctBy { it.label }.sortedByDescending { it.label }) }
                    }
                })
                p.playWhenReady = true
            }
    }

    private suspend fun resolveAndPlay(tmdbId: Int, type: MediaType, season: Int, episode: Int) {
        val result = engine.resolve(tmdbId, type, season, episode)
        if (result == null) {
            _ui.update { it.copy(state = PlayerState.Error("No stream found. Try a different source or check your network.")) }
            return
        }
        lastResult = result
        _ui.update { it.copy(sourceName = result.sourceName, subtitles = result.subtitles) }
        playStream(result)
    }

    @OptIn(UnstableApi::class)
    fun playStream(result: StreamResult) {
        val p = exoPlayer ?: return
        val headers = mutableMapOf<String, String>().apply {
            putAll(result.headers)
            if (result.referer.isNotBlank()) put("Referer", result.referer)
            if (result.origin.isNotBlank())  put("Origin",  result.origin)
        }
        val item = MediaItem.Builder()
            .setUri(result.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle).build())
            .build()
        val dsf = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        val source = if (result.isHls) {
            HlsMediaSource.Factory(dsf).setAllowChunklessPreparation(true).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dsf).createMediaSource(item)
        }
        viewModelScope.launch {
            val resumeMs = repo.getPosition(currentTmdbId, currentSeason, currentEpisode)
            p.setMediaSource(source)
            p.prepare()
            if (resumeMs > 5_000) p.seekTo(resumeMs)
            p.playWhenReady = true
        }
    }

    // ── Playback controls ─────────────────────────────────────────────────────
    fun togglePlayPause() { exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(ms: Long)  { exoPlayer?.seekTo(ms) }
    fun seekForward(sec: Int = 10)  { exoPlayer?.let { it.seekTo((it.currentPosition + sec * 1000L).coerceAtMost(it.duration)) } }
    fun seekBackward(sec: Int = 10) { exoPlayer?.let { it.seekTo((it.currentPosition - sec * 1000L).coerceAtLeast(0)) } }
    fun toggleControls()  { _ui.update { it.copy(showControls = !it.showControls) } }
    fun showControls()    { _ui.update { it.copy(showControls = true) } }
    fun hideControls()    { _ui.update { it.copy(showControls = false) } }
    fun toggleLock()      { _ui.update { it.copy(isLocked = !it.isLocked) } }
    fun toggleMute()      { exoPlayer?.let { p -> val m = !p.isDeviceMuted; p.setDeviceMuted(m, 0); _ui.update { it.copy(isMuted = m) } } }

    fun setSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
        _ui.update { it.copy(playbackSpeed = speed) }
    }

    @OptIn(UnstableApi::class)
    fun setQuality(label: String) {
        _ui.update { it.copy(selectedQuality = label) }
        val ts = trackSelector ?: return
        if (label == "Auto") {
            ts.setParameters(ts.buildUponParameters().clearVideoSizeConstraints())
        } else {
            val height = label.replace("p", "").toIntOrNull() ?: return
            ts.setParameters(ts.buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, height).setMinVideoSize(0, height - 80))
        }
    }

    fun pollPosition() {
        val p = exoPlayer ?: return
        val pos = p.currentPosition.coerceAtLeast(0)
        val dur = p.duration.coerceAtLeast(0)
        _ui.update { it.copy(positionMs = pos, bufferedMs = p.bufferedPosition.coerceAtLeast(0), durationMs = dur) }
        if (dur > 0) {
            viewModelScope.launch {
                repo.saveProgress(currentTmdbId, currentTitle, currentPoster, currentType, currentSeason, currentEpisode, pos, dur)
            }
        }
    }

    private fun handleError(context: Context, error: PlaybackException) {
        val failed = lastResult?.sourceName ?: ""
        _ui.update { it.copy(state = PlayerState.Resolving) }
        viewModelScope.launch {
            val fallback = engine.resolveWithFallback(currentTmdbId, currentType, currentSeason, currentEpisode, failed)
            if (fallback != null) {
                lastResult = fallback
                _ui.update { it.copy(sourceName = fallback.sourceName) }
                playStream(fallback)
            } else {
                _ui.update { it.copy(state = PlayerState.Error("Playback failed. Please try again.")) }
            }
        }
    }

    fun retry() {
        _ui.update { it.copy(state = PlayerState.Resolving) }
        viewModelScope.launch {
            withContext(Dispatchers.Main) { wipeCookies() }
            resolveAndPlay(currentTmdbId, currentType, currentSeason, currentEpisode)
        }
    }

    override fun onCleared() { release() }
    fun release() {
        exoPlayer?.stop(); exoPlayer?.clearMediaItems(); exoPlayer?.release()
        exoPlayer = null; wipeCookies()
    }
}
