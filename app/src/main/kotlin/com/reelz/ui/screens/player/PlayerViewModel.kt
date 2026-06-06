package com.reelz.ui.screens.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.reelz.data.model.MediaType
import com.reelz.data.model.StreamResult
import com.reelz.data.repository.MediaRepository
import com.reelz.scanner.StreamEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

sealed class PlayerState {
    object Idle      : PlayerState()
    object Resolving : PlayerState()       // scanning sources
    object Buffering : PlayerState()       // ExoPlayer buffering
    object Playing   : PlayerState()
    object Paused    : PlayerState()
    data class Error(val msg: String, val retryable: Boolean = true) : PlayerState()
}

data class PlayerUiState(
    val state: PlayerState     = PlayerState.Idle,
    val title: String          = "",
    val episode: String        = "",
    val durationMs: Long       = 0L,
    val positionMs: Long       = 0L,
    val bufferedMs: Long       = 0L,
    val isFullscreen: Boolean  = true,
    val showControls: Boolean  = true,
    val sourceName: String     = "",
    val quality: String        = "Auto",
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val engine: StreamEngine,
    private val repo: MediaRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    var exoPlayer: ExoPlayer? = null
        private set

    private var currentTmdbId   = -1
    private var currentType     = MediaType.MOVIE
    private var currentSeason   = 0
    private var currentEpisode  = 0
    private var currentTitle    = ""
    private var currentPoster: String? = null
    private var lastResult: StreamResult? = null

    // ── Init ───────────────────────────────────────────────────────────────────
    @OptIn(UnstableApi::class)
    fun init(
        context:    Context,
        tmdbId:     Int,
        mediaType:  MediaType,
        season:     Int,
        episode:    Int,
        title:      String,
        posterPath: String?,
    ) {
        currentTmdbId  = tmdbId
        currentType    = mediaType
        currentSeason  = season
        currentEpisode = episode
        currentTitle   = title
        currentPoster  = posterPath

        _ui.update { it.copy(title = title, state = PlayerState.Resolving) }
        buildPlayer(context)
        resolveAndPlay(tmdbId, mediaType, season, episode)
    }

    // ── Build ExoPlayer with tuned load control ────────────────────────────────
    @OptIn(UnstableApi::class)
    private fun buildPlayer(context: Context) {
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setPreferredTextLanguage("en"))
        }

        // Aggressive pre-buffer: 15 s min, 60 s max — smooth HLS playback
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs          */ 15_000,
                /* maxBufferMs          */ 60_000,
                /* bufferForPlaybackMs  */  1_500,   // start play after 1.5 s buffered
                /* bufferForPlaybackAfterRebufferMs */ 3_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)

        exoPlayer = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { player ->
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> _ui.update { it.copy(state = PlayerState.Buffering) }
                            Player.STATE_READY     -> _ui.update { it.copy(
                                state      = if (player.playWhenReady) PlayerState.Playing else PlayerState.Paused,
                                durationMs = player.duration.coerceAtLeast(0),
                            )}
                            Player.STATE_ENDED     -> onPlaybackEnded()
                            Player.STATE_IDLE      -> {}
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _ui.update { it.copy(
                            state = if (isPlaying) PlayerState.Playing else PlayerState.Paused
                        )}
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        handlePlayerError(context, error)
                    }
                })
                player.playWhenReady = true
            }
    }

    // ── Resolve stream and hand to ExoPlayer ──────────────────────────────────
    private fun resolveAndPlay(tmdbId: Int, type: MediaType, season: Int, episode: Int) {
        viewModelScope.launch {
            val result = engine.resolve(tmdbId, type, season, episode)
            if (result == null) {
                _ui.update { it.copy(state = PlayerState.Error("No stream found. Check your sources.", retryable = true)) }
                return@launch
            }
            lastResult = result
            _ui.update { it.copy(sourceName = result.sourceName, quality = result.quality) }
            playStream(result)
        }
    }

    // ── Hand StreamResult to ExoPlayer ────────────────────────────────────────
    @OptIn(UnstableApi::class)
    private fun playStream(result: StreamResult) {
        val player = exoPlayer ?: return

        // Build headers map for ExoPlayer data source
        val headers = mutableMapOf<String, String>().apply {
            putAll(result.headers)
            if (result.referer.isNotBlank()) put("Referer", result.referer)
            if (result.origin.isNotBlank())  put("Origin",  result.origin)
        }

        val mediaItem = MediaItem.Builder()
            .setUri(result.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(currentTitle)
                    .build()
            )
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSource = if (result.isHls) {
            HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)   // faster start
                .createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(httpDataSourceFactory)
                .createMediaSource(mediaItem)
        }

        // Restore resume position
        viewModelScope.launch {
            val resumeMs = repo.getPosition(currentTmdbId, currentSeason, currentEpisode)
            player.setMediaSource(mediaSource)
            player.prepare()
            if (resumeMs > 3_000) player.seekTo(resumeMs)
            player.playWhenReady = true
        }
    }

    // ── Playback controls ──────────────────────────────────────────────────────
    fun togglePlayPause() {
        val p = exoPlayer ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekTo(ms: Long) { exoPlayer?.seekTo(ms) }

    fun seekForward()  { exoPlayer?.let { it.seekTo((it.currentPosition + 10_000).coerceAtMost(it.duration)) } }
    fun seekBackward() { exoPlayer?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } }

    fun pause()  { exoPlayer?.pause() }
    fun resume() { exoPlayer?.play()  }

    fun toggleControls() { _ui.update { it.copy(showControls = !it.showControls) } }
    fun showControls()   { _ui.update { it.copy(showControls = true)  } }
    fun hideControls()   { _ui.update { it.copy(showControls = false) } }

    // ── Position polling (called every second from UI) ─────────────────────────
    fun pollPosition() {
        val p = exoPlayer ?: return
        _ui.update { it.copy(
            positionMs = p.currentPosition.coerceAtLeast(0),
            bufferedMs = p.bufferedPosition.coerceAtLeast(0),
            durationMs = p.duration.coerceAtLeast(0),
        )}
        // Auto-save progress every poll
        if (p.duration > 0) {
            viewModelScope.launch {
                repo.saveProgress(
                    tmdbId     = currentTmdbId,
                    title      = currentTitle,
                    posterPath = currentPoster,
                    mediaType  = currentType,
                    season     = currentSeason,
                    episode    = currentEpisode,
                    positionMs = p.currentPosition,
                    durationMs = p.duration,
                )
            }
        }
    }

    // ── Error handling with auto-fallback ─────────────────────────────────────
    private fun handlePlayerError(context: Context, error: PlaybackException) {
        val failedSource = lastResult?.sourceName ?: ""
        _ui.update { it.copy(state = PlayerState.Resolving) }
        viewModelScope.launch {
            val fallback = engine.resolveWithFallback(
                tmdbId        = currentTmdbId,
                mediaType     = currentType,
                season        = currentSeason,
                episode       = currentEpisode,
                excludeSource = failedSource,
            )
            if (fallback != null) {
                lastResult = fallback
                _ui.update { it.copy(sourceName = fallback.sourceName) }
                playStream(fallback)
            } else {
                _ui.update { it.copy(state = PlayerState.Error("Playback failed: ${error.message}", retryable = true)) }
            }
        }
    }

    fun retry() {
        _ui.update { it.copy(state = PlayerState.Resolving) }
        resolveAndPlay(currentTmdbId, currentType, currentSeason, currentEpisode)
    }

    private fun onPlaybackEnded() {
        // Could auto-play next episode here — handled in UI layer
        _ui.update { it.copy(state = PlayerState.Idle) }
    }

    override fun onCleared() { release() }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
