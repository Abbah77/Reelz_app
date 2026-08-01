package com.axio.reelz.ui.screens.player

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
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
import com.axio.reelz.data.local.DownloadSubtitleDao
import com.axio.reelz.data.model.DownloadSubtitle
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.data.model.QualityTrack
import com.axio.reelz.data.model.StreamResult
import com.axio.reelz.data.model.Subtitle
import com.axio.reelz.data.repository.MediaRepository
import com.axio.reelz.stream.BackendStreamRepository
import com.axio.reelz.stream.searchSubtitles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.ads.VastTagProvider
import com.axio.reelz.remoteconfig.PremiumGate
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

sealed class NetworkState {
    object Connected    : NetworkState()
    object Disconnected : NetworkState()
    object Unknown      : NetworkState()
}

data class SubtitleOption(
    val language: String,
    val label: String,
    val url: String,
    val isPersistent: Boolean = false,
    val persistentId: Long = 0L,
    val isEnabled: Boolean = true,
)

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
    val subtitleOptions: List<SubtitleOption>   = emptyList(),
    val activeSubtitleLanguage: String          = "off",
    val subtitlesEnabled: Boolean               = false,
    val isOfflinePlayback: Boolean              = false,
    val subtitleOffsetMs: Int                   = 0,
    val showSubtitleDrawer: Boolean             = false,
    val isSubtitleSearching: Boolean            = false,
    val subtitleSearchEmpty: Boolean            = false,
    val subtitleUpsellMessage: String?          = null,
    val subtitles: List<Subtitle>               = emptyList(),
    val selectedSubtitle: String                = "Off",
    val preRollVastUrl: String?                 = null,
    val isPreRollPlaying: Boolean               = false,
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel — thin client with bulletproof error handling + URL cache
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val streamRepo: BackendStreamRepository,
    private val repo: MediaRepository,
    private val downloadSubtitleDao: DownloadSubtitleDao,
    private val adEngine: AdEngine,
    private val premiumGate: PremiumGate,
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
    private var currentImdbId: String? = null
    private var currentYear: Int? = null
    private var currentPoster: String? = null
    private var currentDownloadId: String? = null
    private var lastResult: StreamResult? = null
    private var fallbackIndex = 0
    private var isFirstPlayThisSession = true
    private var lastPreRollTimeMinutes = -30L
    private var trackSelector: DefaultTrackSelector? = null
    private var isOnMeteredConnection = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ── Silent-retry state ────────────────────────────────────────────────────
    // When a URL or fetch fails with a transient/blocked error the app retries
    // invisibly. We cap silent retries to avoid an infinite loop if the backend
    // is genuinely down — after MAX_SILENT_RETRIES we show a friendly message.
    private var silentRetryCount = 0
    private val MAX_SILENT_RETRIES = 3

    // Debounce job so rapid errors (e.g. during HLS segment load) don't spawn
    // multiple parallel re-fetch coroutines.
    private var errorHandlerJob: Job? = null

    fun canBackgroundPlay(): Boolean = premiumGate.canBackgroundPlay()

    // ─────────────────────────────────────────────────────────────────────────
    // Network monitoring
    // ─────────────────────────────────────────────────────────────────────────

    private fun startNetworkMonitor(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val initial = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        _ui.update {
            it.copy(networkState = if (initial?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                NetworkState.Connected else NetworkState.Disconnected)
        }
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _ui.update { it.copy(networkState = NetworkState.Connected) }
                val s = _ui.value.state
                if (s is PlayerState.Error && s.isNetworkError) retry()
            }
            override fun onLost(network: Network) {
                _ui.update { it.copy(networkState = NetworkState.Disconnected) }
                if (!_ui.value.isOfflinePlayback) {
                    val s = _ui.value.state
                    if (s is PlayerState.Playing || s is PlayerState.Buffering) {
                        _ui.update { it.copy(state = PlayerState.Error(
                            "No internet connection. Playback will resume when you're back online.",
                            isNetworkError = true)) }
                    }
                }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _ui.update { it.copy(networkState =
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                        NetworkState.Connected else NetworkState.Disconnected) }
            }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkCallback!!)
        } catch (_: Exception) {}
    }

    private fun stopNetworkMonitor(context: Context?) {
        networkCallback?.let { cb ->
            try { (context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
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
        imdbId: String? = null,
        year: Int? = null,
    ) {
        currentTmdbId     = tmdbId
        currentType       = mediaType
        currentSeason     = season
        currentEpisode    = episode
        currentTitle      = title
        currentImdbId     = imdbId
        currentYear       = year
        currentPoster     = posterPath
        currentDownloadId = downloadId

        // Reset retry counters on every fresh init
        silentRetryCount = 0
        fallbackIndex    = 0
        errorHandlerJob?.cancel()

        val isOffline = downloadId != null
        val epLabel   = if (season > 0) "S${season} E${episode}" else ""

        _ui.update { it.copy(
            title             = title,
            episodeLabel      = epLabel,
            state             = PlayerState.Resolving,
            isOfflinePlayback = isOffline,
        )}

        startNetworkMonitor(context)
        resetPlayer()
        buildPlayer(context)

        viewModelScope.launch {
            if (isOffline && downloadId != null) loadDownloadedSubtitles(tmdbId, season, episode)

            if (streamUrl != null) {
                // Offline/pre-resolved URL — play immediately, no cache needed
                val result = StreamResult(
                    url = streamUrl, isHls = streamIsHls,
                    headers = emptyMap(), referer = streamReferer, origin = streamOrigin,
                    sourceName = "prefetched",
                )
                lastResult = result
                playStream(result)
                if (!isOffline) loadStreamSubtitles(result.subtitles)
            } else {
                resolveAndPlay(tmdbId, mediaType, title, season, episode, imdbId, year, isOffline)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core resolve — cache-first, then backend
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun resolveAndPlay(
        tmdbId: Int, type: MediaType, title: String,
        season: Int, episode: Int,
        imdbId: String?, year: Int?,
        isOffline: Boolean = false,
        isQualitySwitch: Boolean = false,
    ) {
        // Pre-roll gate
        val minutesSince = System.currentTimeMillis() / 60_000L - lastPreRollTimeMinutes
        val vastUrl = adEngine.vastTagUrlOrNull()
        if (vastUrl != null && VastTagProvider.shouldShowPreRoll(
                config = adEngine.prerollConfig(),
                isMovie = type == MediaType.MOVIE,
                isFirstPlayThisSession = isFirstPlayThisSession,
                minutesSinceLastPreRoll = minutesSince,
                isOfflinePlayback = isOffline,
                isResumingEpisode = season > 0 && episode > 1,
                isQualitySwitch = isQualitySwitch,
            )) {
            lastPreRollTimeMinutes = System.currentTimeMillis() / 60_000L
            isFirstPlayThisSession = false
            _ui.update { it.copy(preRollVastUrl = vastUrl, isPreRollPlaying = true) }
            return
        }
        isFirstPlayThisSession = false

        // Reset fallback index on every fresh backend call
        fallbackIndex = 0

        val result = streamRepo.resolveFirst(
            tmdbId    = tmdbId,
            mediaType = type,
            title     = title,
            season    = season,
            episode   = episode,
            imdbId    = imdbId,
            year      = year,
            onStream = { track ->
                Log.d("PlayerVM", "Fallback URL ready: [${track.label}]")
            },
            onDownload = { track ->
                val current = _ui.value.availableQualities.toMutableList()
                if (current.none { it.url == track.url }) {
                    current.add(track)
                    _ui.update { it.copy(availableQualities = current) }
                }
            },
            onSubtitle = { sub ->
                val option = SubtitleOption(sub.language, sub.label, sub.url)
                val current = _ui.value.subtitleOptions.toMutableList()
                if (current.none { it.url == sub.url }) {
                    current.add(option)
                    _ui.update { it.copy(subtitleOptions = current, subtitles = current.map {
                        com.axio.reelz.data.model.Subtitle(it.url, it.language, it.label)
                    })}
                }
            },
        )

        if (result == null) {
            val errType = streamRepo.lastErrorType

            // 404 / NOT_FOUND is the only case we show to the user — everything
            // else we retry silently (or fall through to network-error UI).
            if (errType == BackendStreamRepository.StreamError.NOT_FOUND) {
                _ui.update { it.copy(state = PlayerState.Error(
                    "Reelz doesn't have this title yet. Try again later.",
                    isNetworkError = false,
                ))}
                return
            }

            val netOk = _ui.value.networkState is NetworkState.Connected
            if (!netOk) {
                _ui.update { it.copy(state = PlayerState.Error(
                    "No internet connection. Connect and try again.",
                    isNetworkError = true,
                ))}
                return
            }

            // Transient / blocked error — retry silently up to MAX_SILENT_RETRIES
            if (silentRetryCount < MAX_SILENT_RETRIES) {
                silentRetryCount++
                Log.d("PlayerVM", "Silent retry #$silentRetryCount (errType=$errType)")
                // Brief pause to avoid hammering the backend instantly
                delay(300L * silentRetryCount)
                resolveAndPlay(tmdbId, type, title, season, episode, imdbId, year, isOffline)
            } else {
                // Give up silently — show a vague "try again" without revealing internals
                _ui.update { it.copy(state = PlayerState.Error(
                    "Couldn't load stream. Tap to try again.",
                    isNetworkError = false,
                ))}
            }
            return
        }

        // Success — reset retry counter
        silentRetryCount = 0
        lastResult = result
        playStream(result)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Subtitle handling
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadStreamSubtitles(subtitles: List<Subtitle>) {
        if (subtitles.isEmpty()) return
        val options = subtitles.map { SubtitleOption(it.language, it.label, it.url) }
        _ui.update { it.copy(subtitleOptions = options, subtitles = subtitles,
            activeSubtitleLanguage = "off", subtitlesEnabled = false) }
    }

    private suspend fun loadDownloadedSubtitles(tmdbId: Int, season: Int, episode: Int) {
        val saved = downloadSubtitleDao.getForContent(tmdbId, season, episode)
        val options = saved.map { SubtitleOption(it.language, it.label, it.localFilePath,
            isPersistent = true, persistentId = it.id, isEnabled = it.isEnabled) }
        val lastEnabled = options.firstOrNull { it.isEnabled }
        _ui.update { it.copy(subtitleOptions = options,
            activeSubtitleLanguage = lastEnabled?.language ?: "off",
            subtitlesEnabled = lastEnabled != null) }
    }

    fun searchOnlineSubtitles(query: String = "") {
        if (!premiumGate.canManualSubtitleSearch()) {
            _ui.update { it.copy(subtitleUpsellMessage =
                "Manual subtitle search is a Premium feature. Upgrade to search any language.") }
            return
        }
        val langs = if (query.isBlank()) {
            val locale = java.util.Locale.getDefault().language.ifBlank { "en" }
            listOf("en", locale).distinct()
        } else {
            query.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        }
        _ui.update { it.copy(isSubtitleSearching = true, subtitleSearchEmpty = false, subtitleUpsellMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val subs: List<Subtitle> = streamRepo.searchSubtitles(
                tmdbId    = currentTmdbId,
                mediaType = currentType,
                season    = currentSeason,
                episode   = currentEpisode,
                languages = langs,
            )
            if (subs.isNotEmpty()) {
                val options = subs.map { s -> SubtitleOption(s.language, s.label, s.url) }
                val currentLang = _ui.value.activeSubtitleLanguage
                _ui.update { it.copy(
                    subtitleOptions     = options,
                    subtitles           = subs,
                    isSubtitleSearching = false,
                    subtitleSearchEmpty = false,
                    activeSubtitleLanguage = if (options.any { o -> o.language == currentLang })
                        currentLang else "off",
                    subtitlesEnabled = _ui.value.subtitlesEnabled &&
                        options.any { o -> o.language == currentLang },
                )}
            } else {
                _ui.update { it.copy(isSubtitleSearching = false, subtitleSearchEmpty = true) }
            }
        }
    }

    fun addDownloadedSubtitle(sub: Subtitle, localFilePath: String) {
        val downloadId = currentDownloadId ?: return
        viewModelScope.launch {
            val existing = downloadSubtitleDao.getForContent(currentTmdbId, currentSeason, currentEpisode)
            if (existing.any { it.language == sub.language }) { selectSubtitle(sub.language); return@launch }
            downloadSubtitleDao.insert(DownloadSubtitle(
                downloadId = downloadId, tmdbId = currentTmdbId,
                season = currentSeason, episode = currentEpisode,
                language = sub.language, label = sub.label,
                localFilePath = localFilePath, isEnabled = true))
            loadDownloadedSubtitles(currentTmdbId, currentSeason, currentEpisode)
        }
    }

    fun togglePersistentSubtitle(option: SubtitleOption) {
        if (!option.isPersistent) return
        viewModelScope.launch {
            val newEnabled = !option.isEnabled
            downloadSubtitleDao.setEnabled(option.persistentId, newEnabled)
            val updated = _ui.value.subtitleOptions.map {
                if (it.persistentId == option.persistentId) it.copy(isEnabled = newEnabled) else it }
            _ui.update { it.copy(subtitleOptions = updated) }
            if (!newEnabled && _ui.value.activeSubtitleLanguage == option.language) selectSubtitle("off")
        }
    }

    fun deletePersistentSubtitle(option: SubtitleOption) {
        if (!option.isPersistent) return
        viewModelScope.launch {
            downloadSubtitleDao.delete(option.persistentId)
            val updated = _ui.value.subtitleOptions.filter { it.persistentId != option.persistentId }
            _ui.update { it.copy(subtitleOptions = updated) }
            if (_ui.value.activeSubtitleLanguage == option.language) selectSubtitle("off")
        }
    }

    fun selectSubtitle(language: String) {
        val option = _ui.value.subtitleOptions.firstOrNull { it.language == language }
        val enabled = language != "off" && option != null
        val resolvedLanguage = if (enabled) language else "off"
        _ui.update { it.copy(
            activeSubtitleLanguage = resolvedLanguage,
            subtitlesEnabled       = enabled,
            selectedSubtitle       = option?.label ?: "Off",
        )}
        trackSelector?.let { ts ->
            val params = ts.buildUponParameters()
            if (enabled) {
                ts.setParameters(params
                    .setPreferredTextLanguage(language)
                    .setIgnoredTextSelectionFlags(0))
            } else {
                ts.setParameters(params
                    .setPreferredTextLanguage(null)
                    .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED))
            }
        }
    }

    fun toggleSubtitlesOnOff() {
        val cur = _ui.value
        if (cur.subtitlesEnabled) {
            selectSubtitle("off")
        } else {
            val target = cur.subtitleOptions.firstOrNull { it.language == cur.activeSubtitleLanguage }
                ?: cur.subtitleOptions.firstOrNull()
            if (target != null) selectSubtitle(target.language)
        }
    }

    fun setSubtitleOffset(offsetMs: Int) { _ui.update { it.copy(subtitleOffsetMs = offsetMs) } }
    fun openSubtitleDrawer()  { _ui.update { it.copy(showSubtitleDrawer = true, showControls = true) } }
    fun closeSubtitleDrawer() { _ui.update { it.copy(showSubtitleDrawer = false, subtitleUpsellMessage = null) } }

    // ─────────────────────────────────────────────────────────────────────────
    // Player build / lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private fun resetPlayer() {
        exoPlayer?.stop(); exoPlayer?.clearMediaItems(); exoPlayer?.release()
        exoPlayer = null; trackSelector = null
    }

    companion object {
        @Volatile private var _videoCache: SimpleCache? = null

        @OptIn(UnstableApi::class)
        fun getVideoCache(context: Context): SimpleCache =
            _videoCache ?: synchronized(this) {
                _videoCache ?: SimpleCache(
                    File(context.cacheDir, "reelz_video_cache"),
                    LeastRecentlyUsedCacheEvictor(100L * 1024 * 1024),
                ).also { _videoCache = it }
            }
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(context: Context) {
        val maxHeight = premiumGate.maxResolutionHeight().let { if (it <= 0) Int.MAX_VALUE else it }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isMetered = try { cm?.isActiveNetworkMetered == true } catch (_: Exception) { true }
        isOnMeteredConnection = isMetered

        val effectiveMaxHeight  = if (isMetered) minOf(maxHeight, 480) else maxHeight
        val effectiveMaxBitrate = if (isMetered) 1_200_000 else Int.MAX_VALUE

        val ts = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters()
                .setPreferredTextLanguage("en")
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setMaxVideoSize(Int.MAX_VALUE, effectiveMaxHeight)
                .setMaxVideoBitrate(effectiveMaxBitrate))
        }
        trackSelector = ts

        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).setResetOnNetworkTypeChange(true).build()
        val loadCtrl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 60_000, 500, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES * 4)
            .build()

        val videoCache  = getVideoCache(context)
        val upstreamDsf = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(4_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(bandwidthMeter)
        val cacheDsf = CacheDataSource.Factory()
            .setCache(videoCache).setUpstreamDataSourceFactory(upstreamDsf)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        exoPlayer = ExoPlayer.Builder(context)
            .setTrackSelector(ts).setLoadControl(loadCtrl).setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDsf))
            .build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> _ui.update { it.copy(state = PlayerState.Buffering) }
                            Player.STATE_READY     -> _ui.update { it.copy(
                                state = if (p.playWhenReady) PlayerState.Playing else PlayerState.Paused,
                                durationMs = p.duration.coerceAtLeast(0)) }
                            Player.STATE_ENDED -> _ui.update { it.copy(state = PlayerState.Idle) }
                            else -> {}
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _ui.update { it.copy(state = if (isPlaying) PlayerState.Playing else PlayerState.Paused) }
                    }
                    override fun onPlayerError(error: PlaybackException) { handleError(error) }
                    override fun onTracksChanged(tracks: Tracks) {
                        val mh = premiumGate.maxResolutionHeight().let { if (it <= 0) Int.MAX_VALUE else it }
                        val qualities = mutableListOf(QualityTrack("Auto", ""))
                        tracks.groups.forEach { g ->
                            if (g.type == C.TRACK_TYPE_VIDEO) {
                                for (i in 0 until g.length) {
                                    val fmt = g.getTrackFormat(i)
                                    if (fmt.height > 0 && fmt.height <= mh)
                                        qualities.add(QualityTrack("${fmt.height}p", "", fmt.bitrate.toLong()))
                                }
                            }
                        }
                        _ui.update { it.copy(availableQualities = qualities.distinctBy { q -> q.label }
                            .sortedByDescending { q ->
                                if (q.label == "Auto") Int.MAX_VALUE
                                else q.label.replace("p","").toIntOrNull() ?: 0 }) }
                    }
                })
                p.playWhenReady = true
            }
    }

    @OptIn(UnstableApi::class)
    fun playStream(result: StreamResult) {
        val p = exoPlayer ?: return
        val isLocalFile = result.url.startsWith("file://")
        val item = MediaItem.Builder().setUri(result.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle).build()).build()

        val mediaDsf = if (isLocalFile) {
            DefaultDataSource.Factory(appContext)
        } else {
            val headers = mutableMapOf<String, String>().apply {
                putAll(result.headers)
                if (result.referer.isNotBlank()) put("Referer", result.referer)
                if (result.origin.isNotBlank())  put("Origin",  result.origin)
            }
            val upstreamDsf = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(4_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true)
            CacheDataSource.Factory()
                .setCache(getVideoCache(appContext)).setUpstreamDataSourceFactory(upstreamDsf)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        val source = if (result.isHls)
            HlsMediaSource.Factory(mediaDsf).setAllowChunklessPreparation(true).createMediaSource(item)
        else
            ProgressiveMediaSource.Factory(mediaDsf).createMediaSource(item)

        viewModelScope.launch {
            val resumeMs = repo.getPosition(currentTmdbId, currentSeason, currentEpisode)
            p.setMediaSource(source); p.prepare()
            if (resumeMs > 5_000) p.seekTo(resumeMs)
            p.playWhenReady = true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Controls
    // ─────────────────────────────────────────────────────────────────────────

    fun togglePlayPause() { exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(ms: Long)  { exoPlayer?.seekTo(ms) }
    fun seekForward(sec: Int = 10)  { exoPlayer?.let { it.seekTo((it.currentPosition + sec * 1000L).coerceAtMost(it.duration)) } }
    fun seekBackward(sec: Int = 10) { exoPlayer?.let { it.seekTo((it.currentPosition - sec * 1000L).coerceAtLeast(0)) } }
    fun toggleControls()  { _ui.update { it.copy(showControls = !it.showControls) } }
    fun showControls()    { _ui.update { it.copy(showControls = true) } }
    fun hideControls()    { _ui.update { it.copy(showControls = false) } }
    fun toggleLock()      { _ui.update { it.copy(isLocked = !it.isLocked) } }
    fun toggleMute()      { val newMute = !_ui.value.isMuted; exoPlayer?.volume = if (newMute) 0f else 1f; _ui.update { it.copy(isMuted = newMute) } }
    fun setMute(muted: Boolean) { exoPlayer?.volume = if (muted) 0f else 1f; _ui.update { it.copy(isMuted = muted) } }
    fun setSpeed(speed: Float)  { exoPlayer?.setPlaybackSpeed(speed); _ui.update { it.copy(playbackSpeed = speed) } }

    @OptIn(UnstableApi::class)
    fun setQuality(label: String) {
        _ui.update { it.copy(selectedQuality = label) }
        val ts = trackSelector ?: return
        val maxHeight = premiumGate.maxResolutionHeight().let { if (it <= 0) Int.MAX_VALUE else it }
        if (label == "Auto") {
            val autoHeight  = if (isOnMeteredConnection) minOf(maxHeight, 480) else maxHeight
            val autoBitrate = if (isOnMeteredConnection) 1_200_000 else Int.MAX_VALUE
            ts.setParameters(ts.buildUponParameters()
                .clearVideoSizeConstraints().setMaxVideoSize(Int.MAX_VALUE, autoHeight).setMaxVideoBitrate(autoBitrate))
        } else {
            val requested = label.replace("p", "").toIntOrNull() ?: return
            val height = requested.coerceAtMost(maxHeight)
            ts.setParameters(ts.buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, height).setMinVideoSize(0, (height - 80).coerceAtLeast(0))
                .setMaxVideoBitrate(Int.MAX_VALUE))
        }
    }

    fun pollPosition() {
        val p = exoPlayer ?: return
        val pos = p.currentPosition.coerceAtLeast(0)
        val dur = p.duration.coerceAtLeast(0)
        _ui.update { it.copy(positionMs = pos, bufferedMs = p.bufferedPosition.coerceAtLeast(0), durationMs = dur) }
        if (dur > 0) {
            viewModelScope.launch {
                repo.saveProgress(currentTmdbId, currentTitle, currentPoster,
                    currentType, currentSeason, currentEpisode, pos, dur)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bulletproof error handler
    //
    // Priority order:
    //   1. No network → show network error (resume auto when reconnected)
    //   2. Fallback ladder has a next URL → swap silently, NO UI change
    //   3. Ladder exhausted → invalidate cache, re-fetch backend silently
    //      (up to MAX_SILENT_RETRIES) → only show UI after all retries fail
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleError(error: PlaybackException) {
        // Debounce: cancel any in-flight error handler before spawning a new one.
        // This prevents rapid HLS segment errors from firing 10 parallel re-fetches.
        errorHandlerJob?.cancel()
        errorHandlerJob = viewModelScope.launch { handleErrorInternal(error) }
    }

    private suspend fun handleErrorInternal(error: PlaybackException) {
        Log.w("PlayerVM", "Playback error: ${error.errorCodeName} — ${error.message}")

        // ── 1. Network gone → wait for reconnect ──────────────────────────
        val netOk = _ui.value.networkState is NetworkState.Connected
        if (!netOk && !_ui.value.isOfflinePlayback) {
            _ui.update { it.copy(state = PlayerState.Error(
                "No internet connection. Playback will resume when you're back online.",
                isNetworkError = true)) }
            return
        }

        // ── 2. Try next URL in the fallback ladder ─────────────────────────
        val ladder = lastResult?.qualities ?: emptyList()
        val nextIndex = fallbackIndex + 1
        if (nextIndex < ladder.size) {
            val nextTrack = ladder[nextIndex]
            if (nextTrack.url.isNotBlank()) {
                Log.d("PlayerVM", "Silent fallback: trying ladder[$nextIndex] ${nextTrack.label}")
                fallbackIndex = nextIndex
                val current = lastResult!!
                val isHls = nextTrack.url.contains(".m3u8", ignoreCase = true) ||
                        (!nextTrack.url.contains("/api/v1/torrent/") && current.isHls)
                val fallbackResult = current.copy(
                    url     = nextTrack.url,
                    isHls   = isHls,
                    quality = nextTrack.label,
                )
                lastResult = fallbackResult
                // Stay in Buffering — user sees spinner, not error
                _ui.update { it.copy(state = PlayerState.Buffering) }
                playStream(fallbackResult)
                return
            }
        }

        // ── 3. Ladder exhausted → invalidate cache + re-fetch backend ──────
        Log.d("PlayerVM", "Fallback ladder exhausted (tried ${fallbackIndex + 1}/${ladder.size})")
        // Invalidate so the next resolveFirst hits the backend fresh
        streamRepo.invalidateCache(currentTmdbId, currentType, currentSeason, currentEpisode)

        if (silentRetryCount < MAX_SILENT_RETRIES) {
            silentRetryCount++
            Log.d("PlayerVM", "Silent backend re-fetch #$silentRetryCount")
            // Stay in Buffering state — user sees spinner, no error flash
            _ui.update { it.copy(state = PlayerState.Buffering) }
            // Small back-off so we don't hammer the backend on a flaky connection
            delay(200L * silentRetryCount)
            resolveAndPlay(
                currentTmdbId, currentType, currentTitle,
                currentSeason, currentEpisode, currentImdbId, currentYear,
                _ui.value.isOfflinePlayback,
            )
        } else {
            // All retries exhausted — NOW show the user a message
            silentRetryCount = 0
            _ui.update { it.copy(state = PlayerState.Error(
                "Couldn't load stream. Tap to try again.",
                isNetworkError = false,
            ))}
        }
    }

    fun preRollCompleted() {
        _ui.update { it.copy(preRollVastUrl = null, isPreRollPlaying = false) }
        viewModelScope.launch {
            resolveAndPlay(currentTmdbId, currentType, currentTitle,
                currentSeason, currentEpisode, currentImdbId, currentYear)
        }
    }

    fun retry() {
        silentRetryCount = 0
        fallbackIndex    = 0
        streamRepo.invalidateCache(currentTmdbId, currentType, currentSeason, currentEpisode)
        _ui.update { it.copy(state = PlayerState.Resolving) }
        viewModelScope.launch {
            resolveAndPlay(currentTmdbId, currentType, currentTitle,
                currentSeason, currentEpisode, currentImdbId, currentYear,
                _ui.value.isOfflinePlayback)
        }
    }

    override fun onCleared() { release(null) }

    fun release(context: Context? = null) {
        errorHandlerJob?.cancel()
        stopNetworkMonitor(context)
        exoPlayer?.stop(); exoPlayer?.clearMediaItems(); exoPlayer?.release()
        exoPlayer = null
    }
}

fun friendlyError(raw: String, isNetworkError: Boolean = false): String {
    if (isNetworkError) return "No internet connection. Check your connection and try again."
    return when {
        raw.contains("403") || raw.contains("forbidden", ignoreCase = true) -> "Access denied. Try again shortly."
        raw.contains("404") || raw.contains("not found", ignoreCase = true) -> "Stream not found. It may have been moved."
        raw.contains("timeout", ignoreCase = true) -> "Connection timed out. Check your internet."
        raw.isBlank() -> "Playback failed. Please try again."
        else          -> "Playback error. Please try again."
    }
}
