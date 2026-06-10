package com.reelz.ui.screens.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.reelz.data.local.DownloadSubtitleDao
import com.reelz.data.model.DownloadSubtitle
import com.reelz.data.model.MediaType
import com.reelz.data.model.QualityTrack
import com.reelz.data.model.StreamResult
import com.reelz.data.model.Subtitle
import com.reelz.data.repository.MediaRepository
import com.reelz.data.repository.OpenSubtitlesRepository
import com.reelz.scanner.PrefetchState
import com.reelz.scanner.StreamEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Player states
// ─────────────────────────────────────────────────────────────────────────────

sealed class PlayerState {
    object Idle      : PlayerState()
    object Resolving : PlayerState()
    object Buffering : PlayerState()
    object Playing   : PlayerState()
    object Paused    : PlayerState()
    data class Error(val msg: String, val isNetworkError: Boolean = false) : PlayerState()
}

// ─────────────────────────────────────────────────────────────────────────────
// Network state
// ─────────────────────────────────────────────────────────────────────────────

sealed class NetworkState {
    object Connected    : NetworkState()
    object Disconnected : NetworkState()
    object Unknown      : NetworkState()
}

// ─────────────────────────────────────────────────────────────────────────────
// Subtitle UI model — unifies stream subtitles + downloaded subtitles
// ─────────────────────────────────────────────────────────────────────────────

data class SubtitleOption(
    val language: String,
    val label: String,
    /** For stream subtitles: remote URL. For downloaded: local file path. */
    val url: String,
    /** True = this subtitle is persisted to disk (downloaded video). */
    val isPersistent: Boolean = false,
    /** DB id — only set for persistent (DownloadSubtitle) entries. */
    val persistentId: Long = 0L,
    /** Only for persistent subtitles: toggle state. */
    val isEnabled: Boolean = true,
)

// ─────────────────────────────────────────────────────────────────────────────
// UI state
// ─────────────────────────────────────────────────────────────────────────────

data class PlayerUiState(
    val state: PlayerState                      = PlayerState.Idle,
    val networkState: NetworkState              = NetworkState.Unknown,
    val title: String                           = "",
    val episodeLabel: String                    = "",
    val durationMs: Long                        = 0L,
    val positionMs: Long                        = 0L,
    val bufferedMs: Long                        = 0L,
    val showControls: Boolean                   = true,
    val playbackSpeed: Float                    = 1f,
    val availableQualities: List<QualityTrack>  = listOf(QualityTrack("Auto", "")),
    val selectedQuality: String                 = "Auto",
    val isLocked: Boolean                       = false,
    val isMuted: Boolean                        = false,
    // ── Subtitle state ────────────────────────────────────────────────────────
    /** All available subtitle options (stream OR downloaded, never mixed). */
    val subtitleOptions: List<SubtitleOption>   = emptyList(),
    /** Language code of active subtitle. "off" means disabled. */
    val activeSubtitleLanguage: String          = "off",
    /** Whether subtitles are currently shown (user toggle). */
    val subtitlesEnabled: Boolean               = false,
    /** Whether this session is for a downloaded (offline) video. */
    val isOfflinePlayback: Boolean              = false,
    /** Subtitle timing offset in milliseconds — positive = delay, negative = advance. */
    val subtitleOffsetMs: Int                   = 0,
    /** Show the subtitle side drawer. */
    val showSubtitleDrawer: Boolean             = false,
    /** True while a user-initiated OpenSubtitles search is in-flight. */
    val isSubtitleSearching: Boolean            = false,
    /** True after a user-initiated search returned zero results. */
    val subtitleSearchEmpty: Boolean            = false,

    // ── Legacy compat ─────────────────────────────────────────────────────────
    val subtitles: List<com.reelz.data.model.Subtitle> = emptyList(),
    val selectedSubtitle: String                = "Off",
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val engine: StreamEngine,
    private val repo: MediaRepository,
    private val downloadSubtitleDao: DownloadSubtitleDao,
    private val openSubtitlesRepo: OpenSubtitlesRepository,
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
    private var currentDownloadId: String? = null
    private var lastResult: StreamResult? = null
    private var activeSourceName = ""
    private var trackSelector: DefaultTrackSelector? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Network monitoring
    // ─────────────────────────────────────────────────────────────────────────

    private fun startNetworkMonitor(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Set initial state
        val initial = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        _ui.update {
            it.copy(
                networkState = if (initial?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                    NetworkState.Connected else NetworkState.Disconnected
            )
        }

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _ui.update { it.copy(networkState = NetworkState.Connected) }
                // Auto-retry if we were in a network-error state
                val current = _ui.value.state
                if (current is PlayerState.Error && current.isNetworkError) {
                    retry()
                }
            }
            override fun onLost(network: Network) {
                _ui.update { it.copy(networkState = NetworkState.Disconnected) }
                // If playing a stream and we lose network, surface a friendly error
                if (!_ui.value.isOfflinePlayback) {
                    val s = _ui.value.state
                    if (s is PlayerState.Playing || s is PlayerState.Buffering) {
                        _ui.update {
                            it.copy(state = PlayerState.Error(
                                "No internet connection. Playback will resume when you're back online.",
                                isNetworkError = true
                            ))
                        }
                    }
                }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasNet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                _ui.update {
                    it.copy(networkState = if (hasNet) NetworkState.Connected else NetworkState.Disconnected)
                }
            }
        }

        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, networkCallback!!)
        } catch (_: Exception) {}
    }

    private fun stopNetworkMonitor(context: Context?) {
        networkCallback?.let { cb ->
            try {
                val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    fun init(
        context: Context,
        tmdbId: Int, mediaType: MediaType,
        season: Int, episode: Int,
        title: String, posterPath: String?,
        streamUrl: String? = null,
        streamIsHls: Boolean = false,
        streamReferer: String = "",
        streamOrigin: String = "",
        downloadId: String? = null,
    ) {
        currentTmdbId    = tmdbId
        currentType      = mediaType
        currentSeason    = season
        currentEpisode   = episode
        currentTitle     = title
        currentPoster    = posterPath
        currentDownloadId = downloadId

        val isOffline = downloadId != null
        val epLabel   = if (season > 0) "S${season} E${episode}" else ""

        _ui.update {
            it.copy(
                title             = title,
                episodeLabel      = epLabel,
                state             = PlayerState.Resolving,
                isOfflinePlayback = isOffline,
            )
        }

        startNetworkMonitor(context)
        resetPlayer()
        buildPlayer(context)

        viewModelScope.launch {
            // Load persistent subtitles for downloaded content
            if (isOffline && downloadId != null) {
                loadDownloadedSubtitles(tmdbId, season, episode)
            }

            when {
                streamUrl != null -> {
                    val result = StreamResult(
                        url        = streamUrl,
                        isHls      = streamIsHls,
                        headers    = emptyMap(),
                        referer    = streamReferer,
                        origin     = streamOrigin,
                        sourceName = "prefetched",
                    )
                    lastResult       = result
                    activeSourceName = result.sourceName
                    // ↓ Play FIRST — subtitles load in background, never block playback
                    playStream(result)
                    if (!isOffline) loadStreamSubtitles(result.subtitles)
                }

                else -> {
                    val prefetchKey = engine.prefetchState.value
                    if (prefetchKey is PrefetchState.Running || prefetchKey is PrefetchState.Ready) {
                        // Use first{} — collect{} on StateFlow never terminates (deadlock)
                        val terminal = engine.prefetchState
                            .filter { it is PrefetchState.Ready || it is PrefetchState.Failed }
                            .first()
                        when (terminal) {
                            is PrefetchState.Ready -> {
                                lastResult       = terminal.result
                                activeSourceName = terminal.result.sourceName
                                // ↓ Play FIRST — subtitles load in background
                                playStream(terminal.result)
                                if (!isOffline) loadStreamSubtitles(terminal.result.subtitles)
                            }
                            is PrefetchState.Failed -> resolveAndPlay(tmdbId, mediaType, season, episode, isOffline)
                            else -> {}
                        }
                    } else {
                        resolveAndPlay(tmdbId, mediaType, season, episode, isOffline)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Subtitle lifecycle management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stream subtitles: EPHEMERAL. Loaded into UI state only.
     * They are NEVER saved to disk. When the user quits, they vanish.
     * If user comes back (e.g. resumes same session), they'll reload from the stream result.
     */
    /**
     * Stream subtitles: EPHEMERAL. Loaded into UI state only.
     * They are NEVER saved to disk. When the user quits, they vanish.
     *
     * If the stream itself carries no subtitles, we fall back to OpenSubtitles
     * using the TMDB id so the user always gets proper subtitle options.
     */
    private fun loadStreamSubtitles(subtitles: List<Subtitle>) {
        if (subtitles.isNotEmpty()) {
            // Stream already carries embedded subtitles — use them directly, no network call needed
            val options = subtitles.map { sub ->
                SubtitleOption(
                    language     = sub.language,
                    label        = sub.label,
                    url          = sub.url,
                    isPersistent = false,
                )
            }
            _ui.update {
                it.copy(
                    subtitleOptions        = options,
                    subtitles              = subtitles,
                    activeSubtitleLanguage = "off",
                    subtitlesEnabled       = false,
                )
            }
        }
        // If the stream has no embedded subtitles we do NOT auto-call OpenSubtitles.
        // The user must open the subtitle drawer and tap "Search Online" themselves.
        // This avoids burning OpenSubtitles request quota on every video load.
    }

    /**
     * Downloaded subtitles: PERSISTENT. Loaded from Room DB.
     * They stay until the user explicitly deletes the video (or the subtitle).
     * User can toggle them on/off freely without losing them.
     */
    private suspend fun loadDownloadedSubtitles(tmdbId: Int, season: Int, episode: Int) {
        val saved = downloadSubtitleDao.getForContent(tmdbId, season, episode)
        val options = saved.map { sub ->
            SubtitleOption(
                language     = sub.language,
                label        = sub.label,
                url          = sub.localFilePath,
                isPersistent = true,
                persistentId = sub.id,
                isEnabled    = sub.isEnabled,
            )
        }
        // Restore last enabled subtitle if any
        val lastEnabled = options.firstOrNull { it.isEnabled }
        _ui.update {
            it.copy(
                subtitleOptions        = options,
                activeSubtitleLanguage = lastEnabled?.language ?: "off",
                subtitlesEnabled       = lastEnabled != null,
            )
        }
    }

    /**
     * USER-INITIATED: search OpenSubtitles for the current content.
     * Only called when the user explicitly taps "Search Online" in the subtitle drawer.
     * Shows a loading indicator, then populates options. Never called automatically.
     *
     * @param languages  ISO 639-1 codes. Empty list = all languages.
     */
    fun searchOnlineSubtitles(languages: List<String> = emptyList()) {
        val tmdbId  = currentTmdbId
        val type    = currentType
        val season  = currentSeason
        val episode = currentEpisode
        if (tmdbId <= 0) return

        _ui.update { it.copy(isSubtitleSearching = true, subtitleSearchEmpty = false) }

        viewModelScope.launch(Dispatchers.IO) {
            val fetched = openSubtitlesRepo.fetchSubtitles(
                tmdbId             = tmdbId,
                mediaType          = type,
                season             = season,
                episode            = episode,
                preferredLanguages = languages,
            )
            if (fetched.isNotEmpty()) {
                val options = fetched.map { sub ->
                    SubtitleOption(
                        language     = sub.language,
                        label        = sub.label,
                        url          = sub.url,
                        isPersistent = false,
                    )
                }
                _ui.update {
                    it.copy(
                        subtitleOptions        = options,
                        subtitles              = fetched,
                        activeSubtitleLanguage = "off",
                        subtitlesEnabled       = false,
                        isSubtitleSearching    = false,
                        subtitleSearchEmpty    = false,
                    )
                }
            } else {
                _ui.update { it.copy(isSubtitleSearching = false, subtitleSearchEmpty = true) }
            }
        }
    }

    /**
     * Call when user adds a subtitle to a downloaded video. Saves permanently.
     * DUPLICATE-SAFE: if the same language is already downloaded, skip silently.
     */
    fun addDownloadedSubtitle(sub: Subtitle, localFilePath: String) {
        val downloadId = currentDownloadId ?: return
        viewModelScope.launch {
            // ── Duplicate guard ─────────────────────────────────────────────
            // Check if this language is already saved for this content.
            val existing = downloadSubtitleDao.getForContent(currentTmdbId, currentSeason, currentEpisode)
            if (existing.any { it.language == sub.language }) {
                // Already downloaded — just activate it, don't re-download
                selectSubtitle(sub.language)
                return@launch
            }
            val entity = DownloadSubtitle(
                downloadId    = downloadId,
                tmdbId        = currentTmdbId,
                season        = currentSeason,
                episode       = currentEpisode,
                language      = sub.language,
                label         = sub.label,
                localFilePath = localFilePath,
                isEnabled     = true,
            )
            downloadSubtitleDao.insert(entity)
            // Reload
            loadDownloadedSubtitles(currentTmdbId, currentSeason, currentEpisode)
        }
    }

    /**
     * Toggle a persistent subtitle on or off WITHOUT deleting it.
     * This is the preferred UX: users want to turn off subs temporarily, not lose them.
     */
    fun togglePersistentSubtitle(option: SubtitleOption) {
        if (!option.isPersistent) return
        viewModelScope.launch {
            val newEnabled = !option.isEnabled
            downloadSubtitleDao.setEnabled(option.persistentId, newEnabled)

            val updated = _ui.value.subtitleOptions.map {
                if (it.persistentId == option.persistentId) it.copy(isEnabled = newEnabled) else it
            }
            _ui.update { it.copy(subtitleOptions = updated) }

            // If we just disabled the active subtitle, turn off display
            if (!newEnabled && _ui.value.activeSubtitleLanguage == option.language) {
                selectSubtitle("off")
            }
        }
    }

    /** Delete a persistent subtitle permanently (only when user explicitly deletes video or sub). */
    fun deletePersistentSubtitle(option: SubtitleOption) {
        if (!option.isPersistent) return
        viewModelScope.launch {
            downloadSubtitleDao.delete(option.persistentId)
            val updated = _ui.value.subtitleOptions.filter { it.persistentId != option.persistentId }
            _ui.update { it.copy(subtitleOptions = updated) }
            if (_ui.value.activeSubtitleLanguage == option.language) {
                selectSubtitle("off")
            }
        }
    }

    /** Select a subtitle language. Pass "off" to disable subtitles without losing selection. */
    fun selectSubtitle(language: String) {
        val option = _ui.value.subtitleOptions.firstOrNull { it.language == language }
        val enabled = language != "off" && option != null

        _ui.update {
            it.copy(
                activeSubtitleLanguage = if (enabled) language else "off",
                subtitlesEnabled       = enabled,
                selectedSubtitle       = option?.label ?: "Off",
            )
        }

        // Apply to ExoPlayer track selector
        trackSelector?.let { ts ->
            if (enabled) {
                ts.setParameters(
                    ts.buildUponParameters().setPreferredTextLanguage(language)
                )
            } else {
                ts.setParameters(
                    ts.buildUponParameters().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                )
            }
        }
    }

    /** Quick toggle: turn current subtitle on/off without losing the selected language. */
    fun toggleSubtitlesOnOff() {
        val current = _ui.value
        if (current.subtitlesEnabled) {
            // Turn off — remember active language
            selectSubtitle("off")
        } else {
            // Turn on — restore last selected language or pick first available
            val restore = current.subtitleOptions
                .firstOrNull { it.language == current.activeSubtitleLanguage }
                ?: current.subtitleOptions.firstOrNull()
            if (restore != null) {
                selectSubtitle(restore.language)
            }
        }
    }

    /** Adjust subtitle timing offset in milliseconds. Positive = delay, negative = advance. */
    fun setSubtitleOffset(offsetMs: Int) {
    _ui.update { it.copy(subtitleOffsetMs = offsetMs) }
    trackSelector?.let { ts ->
        // Offset is stored in UI state and applied in the subtitle overlay renderer
    }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drawer control
    // ─────────────────────────────────────────────────────────────────────────

    fun openSubtitleDrawer()  { _ui.update { it.copy(showSubtitleDrawer = true,  showControls = true) } }
    fun closeSubtitleDrawer() { _ui.update { it.copy(showSubtitleDrawer = false) } }

    // ─────────────────────────────────────────────────────────────────────────
    // Player lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private fun wipeCookies() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
    }

    private fun resetPlayer() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        exoPlayer?.release()
        exoPlayer = null
        trackSelector = null
    }

    // ── Singleton video cache — shared across all player instances in this process ──
    // 100 MB on-disk cache: recently watched segments play instantly on re-seek,
    // and the next-episode prefetch also fills this same cache.
    companion object {
        @Volatile private var _videoCache: SimpleCache? = null

        @OptIn(UnstableApi::class)
        fun getVideoCache(context: Context): SimpleCache {
            return _videoCache ?: synchronized(this) {
                _videoCache ?: SimpleCache(
                    File(context.cacheDir, "reelz_video_cache"),
                    LeastRecentlyUsedCacheEvictor(100L * 1024 * 1024),  // 100 MB
                ).also { _videoCache = it }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(context: Context) {
        val ts = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredTextLanguage("en")
                    // Start with lowest viable quality so first frame appears instantly,
                    // then let the bandwidth meter auto-upgrade within 1–2 seconds.
                    .setForceLowestBitrate(false)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
            )
        }
        trackSelector = ts

        // Bandwidth meter: measures real download speed → faster adaptive quality selection.
        // Without this, ExoPlayer guesses; with it, it picks the right tier on the first chunk.
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
            .setResetOnNetworkTypeChange(true)
            .build()

        // ── Buffer tuning for TikTok-style instant start ──────────────────────
        // minBufferMs = 800ms  → start playback after only 0.8s buffered (was 1.5s)
        // maxBufferMs = 60s    → keep 60s ahead in memory during playback
        // bufferForPlaybackMs = 300ms → resume from rebuffer after 300ms (was 500ms)
        // bufferForPlaybackAfterRebufferMs = 500ms → very quick rebuffer recovery
        val loadCtrl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(800, 60_000, 300, 500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES * 3)
            .build()

        // ── CacheDataSource: local disk cache acts as mini-CDN ───────────────
        // When ExoPlayer fetches HLS segments, they go to SimpleCache first.
        // Re-seeks and repeated plays are served from disk at memory speed.
        val videoCache    = getVideoCache(context)
        val upstreamDsf   = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(bandwidthMeter)   // feed real speeds to bandwidth meter
        val cacheDsf = CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(upstreamDsf)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)  // graceful degradation

        exoPlayer = ExoPlayer.Builder(context)
            .setTrackSelector(ts)
            .setLoadControl(loadCtrl)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDsf))
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
                            Player.STATE_ENDED -> _ui.update { it.copy(state = PlayerState.Idle) }
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
                        val distinct = qualities
                            .distinctBy { it.label }
                            .sortedWith(compareByDescending {
                                if (it.label == "Auto") Int.MAX_VALUE
                                else it.label.replace("p", "").toIntOrNull() ?: 0
                            })
                        _ui.update { it.copy(availableQualities = distinct) }
                    }
                })
                p.playWhenReady = true
            }
    }

    private suspend fun resolveAndPlay(
        tmdbId: Int, type: MediaType, season: Int, episode: Int,
        isOffline: Boolean = false,
    ) {
        val result = engine.resolve(tmdbId, type, season, episode)
        if (result == null) {
            val netConnected = _ui.value.networkState is NetworkState.Connected
            _ui.update {
                it.copy(state = PlayerState.Error(
                    msg = if (!netConnected)
                        "No internet connection. Connect to the internet and try again."
                    else
                        "No stream found. The source may be unavailable — try again shortly.",
                    isNetworkError = !netConnected,
                ))
            }
            return
        }
        lastResult       = result
        activeSourceName = result.sourceName
        // ↓ Play FIRST — subtitles load in background, never block playback
        playStream(result)
        if (!isOffline) loadStreamSubtitles(result.subtitles)
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

        // ── Cached data source: upstream HTTP → 100 MB SimpleCache on disk ──
        // Any segment already in cache is served instantly (0 network).
        // This is the core of TikTok-style speed: prefetch fills the cache,
        // the player reads from disk, network is only hit for cold segments.
        val upstreamDsf = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        val cachedDsf = CacheDataSource.Factory()
            .setCache(getVideoCache(appContext))
            .setUpstreamDataSourceFactory(upstreamDsf)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val source = if (result.isHls) {
            HlsMediaSource.Factory(cachedDsf)
                .setAllowChunklessPreparation(true)  // parse playlist without fetching first segment
                .createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(cachedDsf).createMediaSource(item)
        }

        viewModelScope.launch {
            val resumeMs = repo.getPosition(currentTmdbId, currentSeason, currentEpisode)
            p.setMediaSource(source)
            p.prepare()
            if (resumeMs > 5_000) p.seekTo(resumeMs)
            p.playWhenReady = true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Controls
    // ─────────────────────────────────────────────────────────────────────────

    fun togglePlayPause() {
        exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekTo(ms: Long)          { exoPlayer?.seekTo(ms) }
    fun seekForward(sec: Int = 10) { exoPlayer?.let { it.seekTo((it.currentPosition + sec * 1000L).coerceAtMost(it.duration)) } }
    fun seekBackward(sec: Int = 10) { exoPlayer?.let { it.seekTo((it.currentPosition - sec * 1000L).coerceAtLeast(0)) } }

    fun toggleControls() { _ui.update { it.copy(showControls = !it.showControls) } }
    fun showControls()   { _ui.update { it.copy(showControls = true)  } }
    fun hideControls()   { _ui.update { it.copy(showControls = false) } }
    fun toggleLock()     { _ui.update { it.copy(isLocked = !it.isLocked) } }

    fun toggleMute() {
        val p       = exoPlayer ?: return
        val newMute = !_ui.value.isMuted
        p.volume    = if (newMute) 0f else 1f
        _ui.update  { it.copy(isMuted = newMute) }
    }

    fun setMute(muted: Boolean) {
        exoPlayer?.volume = if (muted) 0f else 1f
        _ui.update { it.copy(isMuted = muted) }
    }

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
            ts.setParameters(
                ts.buildUponParameters()
                    .setMaxVideoSize(Int.MAX_VALUE, height)
                    .setMinVideoSize(0, (height - 80).coerceAtLeast(0))
            )
        }
    }

    fun pollPosition() {
        val p = exoPlayer ?: return
        val pos = p.currentPosition.coerceAtLeast(0)
        val dur = p.duration.coerceAtLeast(0)
        _ui.update {
            it.copy(
                positionMs = pos,
                bufferedMs = p.bufferedPosition.coerceAtLeast(0),
                durationMs = dur,
            )
        }
        if (dur > 0) {
            viewModelScope.launch {
                repo.saveProgress(
                    currentTmdbId, currentTitle, currentPoster,
                    currentType, currentSeason, currentEpisode, pos, dur,
                )
            }
        }
    }

    private fun handleError(context: Context, error: PlaybackException) {
        val failed = activeSourceName
        val netConnected = _ui.value.networkState is NetworkState.Connected

        // If no network, don't bother trying fallback — show network error immediately
        if (!netConnected && !_ui.value.isOfflinePlayback) {
            _ui.update {
                it.copy(state = PlayerState.Error(
                    "No internet connection. Playback will resume when you're back online.",
                    isNetworkError = true
                ))
            }
            return
        }

        _ui.update { it.copy(state = PlayerState.Resolving) }
        viewModelScope.launch {
            val fallback = engine.resolveWithFallback(
                currentTmdbId, currentType, currentSeason, currentEpisode, failed
            )
            if (fallback != null) {
                lastResult       = fallback
                activeSourceName = fallback.sourceName
                // ↓ Play FIRST — subtitles load in background
                playStream(fallback)
                if (!_ui.value.isOfflinePlayback) loadStreamSubtitles(fallback.subtitles)
            } else {
                val isNet = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                         || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                _ui.update {
                    it.copy(state = PlayerState.Error(
                        msg = friendlyError(error.message ?: "", isNet),
                        isNetworkError = isNet,
                    ))
                }
            }
        }
    }

    fun retry() {
        _ui.update { it.copy(state = PlayerState.Resolving) }
        viewModelScope.launch {
            withContext(Dispatchers.Main) { wipeCookies() }
            resolveAndPlay(currentTmdbId, currentType, currentSeason, currentEpisode, _ui.value.isOfflinePlayback)
        }
    }

    override fun onCleared() { release(null) }

    fun release(context: Context? = null) {
        stopNetworkMonitor(context)
        // IMPORTANT: stream subtitles are intentionally NOT saved here.
        // Only downloaded-video subtitles (DownloadSubtitle) are already in Room DB.
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        exoPlayer?.release()
        exoPlayer = null
        wipeCookies()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Friendly error mapping
// ─────────────────────────────────────────────────────────────────────────────

fun friendlyError(raw: String, isNetworkError: Boolean = false): String {
    if (isNetworkError) return "No internet connection. Check your connection and try again."
    return when {
        raw.contains("403") || raw.contains("forbidden", ignoreCase = true) ->
            "Access denied by the stream. Try a different source."
        raw.contains("404") || raw.contains("not found", ignoreCase = true) ->
            "Stream not found. It may have been moved or removed."
        raw.contains("timeout", ignoreCase = true) ->
            "Connection timed out. Check your internet and try again."
        raw.contains("ssl", ignoreCase = true) || raw.contains("certificate", ignoreCase = true) ->
            "Secure connection failed. Try again."
        raw.isBlank() -> "Playback failed. Please try again."
        else          -> "Playback error. Please try again."
    }
}
