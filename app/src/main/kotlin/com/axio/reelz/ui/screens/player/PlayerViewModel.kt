package com.axio.reelz.ui.screens.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.ads.VastTagProvider
import com.axio.reelz.core.preferences.AppPreferencesStore
import com.axio.reelz.core.database.DownloadSubtitleDao
import com.axio.reelz.core.database.DownloadSubtitleRow
import com.axio.reelz.data.model.*
import com.axio.reelz.data.repository.StreamRepository
import com.axio.reelz.data.repository.UserRepository
import com.axio.reelz.core.network.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

// ── Player states ─────────────────────────────────────────────────────────────

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
    val state: PlayerState                     = PlayerState.Idle,
    val networkState: NetworkState             = NetworkState.Unknown,
    val title: String                          = "",
    val episodeLabel: String                   = "",
    val durationMs: Long                       = 0L,
    val positionMs: Long                       = 0L,
    val bufferedMs: Long                       = 0L,
    val showControls: Boolean                  = true,
    val playbackSpeed: Float                   = 1f,
    val availableQualities: List<QualityTrack> = listOf(QualityTrack("Auto", "")),
    val selectedQuality: String                = "Auto",
    val isLocked: Boolean                      = false,
    val isMuted: Boolean                       = false,
    val isSpeedDrawerOpen: Boolean             = false,
    val isQualityDrawerOpen: Boolean           = false,
    val isSettingsDrawerOpen: Boolean          = false,
    val isSubtitlesDrawerOpen: Boolean         = false,
    val isPipGloballyEnabled: Boolean          = true,
    val isPipActive: Boolean                   = false,
    val subtitleOptions: List<SubtitleOption>  = emptyList(),
    val activeSubtitleLanguage: String         = "off",
    val subtitlesEnabled: Boolean              = false,
    val isOfflinePlayback: Boolean             = false,
    val subtitleOffsetMs: Int                  = 0,
    val showSubtitleDrawer: Boolean            = false,
    val isSubtitleSearching: Boolean           = false,
    val subtitleSearchEmpty: Boolean           = false,
    val subtitleUpsellMessage: String?         = null,
    val subtitles: List<Subtitle>              = emptyList(),
    val selectedSubtitle: String               = "Off",
    val preRollVastUrl: String?                = null,
    val isPreRollPlaying: Boolean              = false,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val streamRepo: StreamRepository,
    private val libraryRepo: com.axio.reelz.data.repository.LibraryRepository,
    private val downloadRepo: com.axio.reelz.data.repository.DownloadRepository,
    private val downloadSubtitleDao: DownloadSubtitleDao,
    private val adEngine: AdEngine,
    private val sessionRepo: UserRepository,
    private val pipPrefs: com.axio.reelz.core.preferences.AppPreferencesStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private val _exoPlayer = MutableStateFlow<ExoPlayer?>(null)
    val exoPlayerFlow: StateFlow<ExoPlayer?> = _exoPlayer.asStateFlow()
    var exoPlayer: ExoPlayer?
        get() = _exoPlayer.value
        private set(value) { _exoPlayer.value = value }

    // ── Current media context ─────────────────────────────────────────────────
    private var currentId       = ""
    private var currentType     = MediaType.MOVIE
    private var currentSeason   = 0
    private var currentEpisode  = 0
    private var currentTitle    = ""
    private var currentPoster: String? = null
    private var currentDownloadId: String? = null
    private var lastResult: StreamResult? = null
    private var fallbackIndex = 0
    private var isFirstPlayThisSession = true
    private var lastPreRollTimeMinutes = -30L
    private var trackSelector: DefaultTrackSelector? = null
    private var isOnMeteredConnection = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var offlineDownloads: List<DownloadItem> = emptyList()
    private var preferredOfflineQuality: String = ""
    private var _wasInPipBeforeStop = false
    private var silentRetryCount = 0
    private val MAX_SILENT_RETRIES = 3
    private var errorHandlerJob: Job? = null

    init {
        viewModelScope.launch {
            pipPrefs.isPipEnabled.collect { enabled ->
                _ui.update { it.copy(isPipGloballyEnabled = enabled) }
            }
        }
    }

    // Premium gating — simplified: reads from session repo
    private fun maxResolutionHeight(): Int = if (sessionRepo.isPremium) Int.MAX_VALUE else 720
    fun canBackgroundPlay(): Boolean = sessionRepo.isPremium

    // ── PiP ──────────────────────────────────────────────────────────────────

    fun onPipModeChanged(isInPipMode: Boolean) {
        if (isInPipMode) {
            _wasInPipBeforeStop = true
        } else {
            _wasInPipBeforeStop = false
            exoPlayer?.let { p -> if (p.mediaItemCount > 0 && !p.isPlaying) p.play() }
        }
        _ui.update { it.copy(isPipActive = isInPipMode) }
    }

    fun wasInPipBeforeStop(): Boolean = _wasInPipBeforeStop

    fun stopPlaybackAndRelease() {
        exoPlayer?.stop(); exoPlayer?.release(); exoPlayer = null
        _wasInPipBeforeStop = false
        _ui.update { it.copy(isPipActive = false, state = PlayerState.Idle) }
    }

    fun shouldAutoPip(): Boolean = _ui.value.isPipGloballyEnabled && _ui.value.state is PlayerState.Playing
    fun canManualPip(): Boolean  = _ui.value.isPipGloballyEnabled && _ui.value.state is PlayerState.Playing

    fun setGlobalPipEnabled(enabled: Boolean) {
        viewModelScope.launch { pipPrefs.setPipEnabled(enabled) }
        _ui.update { it.copy(isPipGloballyEnabled = enabled) }
    }

    // ── Drawers ───────────────────────────────────────────────────────────────

    fun openSpeedDrawer()     { closeAllDrawers(); _ui.update { it.copy(isSpeedDrawerOpen    = true, showControls = true) } }
    fun closeSpeedDrawer()    { _ui.update { it.copy(isSpeedDrawerOpen    = false) } }
    fun openQualityDrawer()   { closeAllDrawers(); _ui.update { it.copy(isQualityDrawerOpen  = true, showControls = true) } }
    fun closeQualityDrawer()  { _ui.update { it.copy(isQualityDrawerOpen  = false) } }
    fun openSettingsDrawer()  { closeAllDrawers(); _ui.update { it.copy(isSettingsDrawerOpen = true, showControls = true) } }
    fun closeSettingsDrawer() { _ui.update { it.copy(isSettingsDrawerOpen = false) } }
    fun openSubtitleDrawer()  { closeAllDrawers(); _ui.update { it.copy(showSubtitleDrawer   = true, showControls = true) } }
    fun closeSubtitleDrawer() { _ui.update { it.copy(showSubtitleDrawer   = false, subtitleUpsellMessage = null) } }

    private fun closeAllDrawers() = _ui.update { it.copy(
        isSpeedDrawerOpen    = false,
        isQualityDrawerOpen  = false,
        isSettingsDrawerOpen = false,
        showSubtitleDrawer   = false,
    )}

    // ── Network monitoring ────────────────────────────────────────────────────

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

    // ── Init ──────────────────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    fun init(
        context: Context,
        id: String,
        mediaType: MediaType,
        season: Int, episode: Int,
        title: String, posterUrl: String?,
        streamUrl: String? = null,
        streamIsHls: Boolean = false,
        downloadId: String? = null,
        preferredQuality: String? = null,
    ) {
        currentId         = id
        currentType       = mediaType
        currentSeason     = season
        currentEpisode    = episode
        currentTitle      = title
        currentPoster     = posterUrl
        currentDownloadId = downloadId
        preferredOfflineQuality = preferredQuality ?: ""

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
            if (isOffline) {
                loadDownloadedSubtitles(id, season, episode)
                val allDownloads = downloadRepo.getDownloadedItems(id, season, episode)
                offlineDownloads = allDownloads
                val offlineQualities = allDownloads
                    .filter { it.status == DownloadStatus.DONE }
                    .sortedByDescending { it.sizeBytes }
                    .map { QualityTrack(it.quality, it.filePath) }
                val startQuality = preferredOfflineQuality.takeIf { q ->
                    q.isNotBlank() && offlineQualities.any { it.label == q }
                } ?: offlineQualities.firstOrNull()?.label ?: ""
                _ui.update { it.copy(availableQualities = offlineQualities, selectedQuality = startQuality) }
            }

            if (streamUrl != null) {
                // Pre-fetched URL passed in (e.g. from Detail screen pre-resolve)
                val resolvedUrl = if (isOffline && offlineDownloads.isNotEmpty()) {
                    val preferred = _ui.value.selectedQuality
                    val match = offlineDownloads.firstOrNull { it.quality == preferred && it.status == DownloadStatus.DONE }
                        ?: offlineDownloads.firstOrNull { it.status == DownloadStatus.DONE }
                    match?.filePath?.takeIf { it.isNotBlank() } ?: streamUrl
                } else streamUrl

                val track = StreamTrack(name = "Offline", url = resolvedUrl,
                    type = if (streamIsHls) "hls" else "mp4")
                val result = StreamResult(streams = listOf(track),
                    expiresAtMs = Long.MAX_VALUE)
                lastResult = result
                playStream(result)
            } else {
                resolveAndPlay(id, mediaType, title, season, episode, isOffline)
            }
        }
    }

    // ── Resolve ───────────────────────────────────────────────────────────────

    private suspend fun resolveAndPlay(
        id: String, type: MediaType, title: String,
        season: Int, episode: Int, isOffline: Boolean = false,
        isQualitySwitch: Boolean = false,
    ) {
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
        fallbackIndex = 0

        val result = streamRepo.resolveStream(id, type, season, episode)
        when (result) {
            is NetworkResult.Success -> {
                val stream = result.data
                lastResult = stream
                // Build quality tracks from streams (one per language track)
                val qualities = stream.streams.map { t ->
                    QualityTrack(label = t.name, url = t.url)
                }.ifEmpty { listOf(QualityTrack("Auto", "")) }
                _ui.update { it.copy(availableQualities = qualities) }
                // Subtitles from primary stream track
                val subs = stream.primaryStream?.subtitles ?: emptyList()
                if (subs.isNotEmpty()) loadStreamSubtitles(subs)
                playStream(stream)
                silentRetryCount = 0
            }
            is NetworkResult.Error -> {
                if (result.isNotFound) {
                    _ui.update { it.copy(state = PlayerState.Error(
                        "Reelz doesn't have this title yet. Try again later.")) }
                    return
                }
                val netOk = _ui.value.networkState is NetworkState.Connected
                if (!netOk) {
                    _ui.update { it.copy(state = PlayerState.Error(
                        "No internet connection. Connect and try again.", isNetworkError = true)) }
                    return
                }
                if (silentRetryCount < MAX_SILENT_RETRIES) {
                    silentRetryCount++
                    delay(300L * silentRetryCount)
                    resolveAndPlay(id, type, title, season, episode, isOffline)
                } else {
                    _ui.update { it.copy(state = PlayerState.Error("Couldn't load stream. Tap to try again.")) }
                }
            }
            else -> {}
        }
    }

    // ── Subtitle handling ─────────────────────────────────────────────────────

    private fun loadStreamSubtitles(subtitles: List<Subtitle>) {
        val options = subtitles.map { SubtitleOption(it.language, it.language, it.url, isEnabled = it.enabled) }
        _ui.update { it.copy(subtitleOptions = options, subtitles = subtitles,
            activeSubtitleLanguage = "off", subtitlesEnabled = false) }
    }

    private suspend fun loadDownloadedSubtitles(id: String, season: Int, episode: Int) {
        val saved = downloadSubtitleDao.getForContent(id, season, episode)
        val options = saved.map { SubtitleOption(it.language, it.label, it.localFilePath,
            isPersistent = true, persistentId = it.id, isEnabled = it.isEnabled) }
        val lastEnabled = options.firstOrNull { it.isEnabled }
        _ui.update { it.copy(subtitleOptions = options,
            activeSubtitleLanguage = lastEnabled?.language ?: "off",
            subtitlesEnabled = lastEnabled != null) }
    }

    fun searchOnlineSubtitles(query: String = "") {
        if (!sessionRepo.isPremium) {
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
            val result = streamRepo.getSubtitles(currentId, currentType, currentSeason, currentEpisode, langs)
            val subs = (result as? NetworkResult.Success)?.data ?: emptyList()
            if (subs.isNotEmpty()) {
                val options = subs.map { s -> SubtitleOption(s.language, s.language, s.url, isEnabled = s.enabled) }
                val currentLang = _ui.value.activeSubtitleLanguage
                _ui.update { it.copy(
                    subtitleOptions     = options,
                    subtitles           = subs,
                    isSubtitleSearching = false,
                    subtitleSearchEmpty = false,
                    activeSubtitleLanguage = if (options.any { o -> o.language == currentLang }) currentLang else "off",
                    subtitlesEnabled = _ui.value.subtitlesEnabled && options.any { o -> o.language == currentLang },
                )}
            } else {
                _ui.update { it.copy(isSubtitleSearching = false, subtitleSearchEmpty = true) }
            }
        }
    }

    fun addDownloadedSubtitle(sub: Subtitle, localFilePath: String) {
        val downloadId = currentDownloadId ?: return
        viewModelScope.launch {
            val existing = downloadSubtitleDao.getForContent(currentId, currentSeason, currentEpisode)
            if (existing.any { it.language == sub.language }) { selectSubtitle(sub.language); return@launch }
            downloadSubtitleDao.insert(DownloadSubtitleRow(
                downloadId    = downloadId,
                mediaId       = currentId,
                season        = currentSeason,
                episode       = currentEpisode,
                language      = sub.language,
                label         = sub.label,
                localFilePath = localFilePath,
                isEnabled     = true,
            ))
            loadDownloadedSubtitles(currentId, currentSeason, currentEpisode)
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
            downloadSubtitleDao.deleteForDownload(option.persistentId.toString())
            val updated = _ui.value.subtitleOptions.filter { it.persistentId != option.persistentId }
            _ui.update { it.copy(subtitleOptions = updated) }
            if (_ui.value.activeSubtitleLanguage == option.language) selectSubtitle("off")
        }
    }

    fun selectSubtitle(language: String) {
        val option  = _ui.value.subtitleOptions.firstOrNull { it.language == language }
        val enabled = language != "off" && option != null
        _ui.update { it.copy(
            activeSubtitleLanguage = if (enabled) language else "off",
            subtitlesEnabled       = enabled,
            selectedSubtitle       = option?.label ?: "Off",
        )}
        trackSelector?.let { ts ->
            val params = ts.buildUponParameters()
            if (enabled) {
                ts.setParameters(params.setPreferredTextLanguage(language).setIgnoredTextSelectionFlags(0))
            } else {
                ts.setParameters(params.setPreferredTextLanguage(null)
                    .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED))
            }
        }
    }

    fun toggleSubtitlesOnOff() {
        val cur = _ui.value
        if (cur.subtitlesEnabled) selectSubtitle("off")
        else {
            val target = cur.subtitleOptions.firstOrNull { it.language == cur.activeSubtitleLanguage }
                ?: cur.subtitleOptions.firstOrNull()
            if (target != null) selectSubtitle(target.language)
        }
    }

    fun setSubtitleOffset(offsetMs: Int) { _ui.update { it.copy(subtitleOffsetMs = offsetMs) } }

    // ── Player build ──────────────────────────────────────────────────────────

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
                            Player.STATE_ENDED     -> _ui.update { it.copy(state = PlayerState.Idle) }
                            else -> {}
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _ui.update { it.copy(state = if (isPlaying) PlayerState.Playing else PlayerState.Paused) }
                    }
                    override fun onPlayerError(error: PlaybackException) { handleError(error) }
                    override fun onTracksChanged(tracks: Tracks) {
                        val mh = maxResolutionHeight()
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
                        _ui.update { it.copy(availableQualities = qualities.distinctBy { q -> q.label }
                            .sortedByDescending { q -> if (q.label == "Auto") Int.MAX_VALUE else q.label.replace("p","").toIntOrNull() ?: 0 }) }
                    }
                })
                p.playWhenReady = true
            }
    }

    @OptIn(UnstableApi::class)
    fun playStream(result: StreamResult) {
        val p = exoPlayer ?: return
        val primary = result.primaryStream ?: return
        val url = primary.url
        val isLocalFile = url.startsWith("file://")
        val item = MediaItem.Builder().setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle).build()).build()

        val mediaDsf = if (isLocalFile) {
            DefaultDataSource.Factory(appContext)
        } else {
            val upstreamDsf = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(primary.headers)
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

        viewModelScope.launch {
            val resumeMs = libraryRepo.getProgress(currentId, currentSeason, currentEpisode)?.positionMs ?: 0L
            p.setMediaSource(source); p.prepare()
            if (resumeMs > 5_000) p.seekTo(resumeMs)
            p.playWhenReady = true
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    fun togglePlayPause() { exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(ms: Long)  { exoPlayer?.seekTo(ms) }
    fun seekForward(sec: Int = 10)  { exoPlayer?.let { it.seekTo((it.currentPosition + sec * 1000L).coerceAtMost(it.duration)) } }
    fun seekBackward(sec: Int = 10) { exoPlayer?.let { it.seekTo((it.currentPosition - sec * 1000L).coerceAtLeast(0)) } }
    fun toggleControls() { _ui.update { it.copy(showControls = !it.showControls) } }
    fun showControls()   { _ui.update { it.copy(showControls = true) } }
    fun hideControls()   { _ui.update { it.copy(showControls = false) } }
    fun toggleLock()     { _ui.update { it.copy(isLocked = !it.isLocked) } }
    fun toggleMute()     { val m = !_ui.value.isMuted; exoPlayer?.volume = if (m) 0f else 1f; _ui.update { it.copy(isMuted = m) } }
    fun setMute(muted: Boolean) { exoPlayer?.volume = if (muted) 0f else 1f; _ui.update { it.copy(isMuted = muted) } }
    fun setSpeed(speed: Float) { exoPlayer?.setPlaybackSpeed(speed); _ui.update { it.copy(playbackSpeed = speed) } }

    @OptIn(UnstableApi::class)
    fun setQuality(label: String) {
        _ui.update { it.copy(selectedQuality = label) }
        if (_ui.value.isOfflinePlayback && offlineDownloads.isNotEmpty()) {
            preferredOfflineQuality = label
            val match = offlineDownloads.firstOrNull { it.quality == label && it.status == DownloadStatus.DONE }
                ?: offlineDownloads.firstOrNull { it.status == DownloadStatus.DONE }
            if (match != null) {
                val url = match.filePath.takeIf { it.isNotBlank() } ?: return
                val savedPos = exoPlayer?.currentPosition ?: 0L
                val track = StreamTrack(name = label, url = url,
                    type = if (url.contains(".m3u8", ignoreCase = true)) "hls" else "mp4")
                val result = StreamResult(streams = listOf(track), expiresAtMs = Long.MAX_VALUE)
                lastResult = result
                viewModelScope.launch {
                    playStream(result)
                    if (savedPos > 0) exoPlayer?.seekTo(savedPos)
                    downloadRepo.updateWatchProgress(currentId, currentSeason, currentEpisode, savedPos, exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L)
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
        val p = exoPlayer ?: return
        val pos = p.currentPosition.coerceAtLeast(0)
        val dur = p.duration.coerceAtLeast(0)
        _ui.update { it.copy(positionMs = pos, bufferedMs = p.bufferedPosition.coerceAtLeast(0), durationMs = dur) }
        if (dur > 0) {
            viewModelScope.launch {
                libraryRepo.saveProgress(currentId, currentSeason, currentEpisode, pos, dur, currentTitle, currentPoster)
            }
        }
    }

    // ── Error handler ─────────────────────────────────────────────────────────

    private fun handleError(error: PlaybackException) {
        errorHandlerJob?.cancel()
        errorHandlerJob = viewModelScope.launch { handleErrorInternal(error) }
    }

    private suspend fun handleErrorInternal(error: PlaybackException) {
        Log.w("PlayerVM", "Playback error: ${error.errorCodeName} — ${error.message}")
        val netOk = _ui.value.networkState is NetworkState.Connected
        if (!netOk && !_ui.value.isOfflinePlayback) {
            _ui.update { it.copy(state = PlayerState.Error(
                "No internet connection. Playback will resume when you're back online.",
                isNetworkError = true)) }
            return
        }
        val ladder = lastResult?.streams ?: emptyList()
        val nextIndex = fallbackIndex + 1
        if (nextIndex < ladder.size && ladder[nextIndex].url.isNotBlank()) {
            fallbackIndex = nextIndex
            val next = ladder[nextIndex]
            val fallback = StreamResult(streams = listOf(next),
                expiresAtMs = lastResult?.expiresAtMs ?: Long.MAX_VALUE)
            lastResult = fallback
            _ui.update { it.copy(state = PlayerState.Buffering) }
            playStream(fallback)
            return
        }
        streamRepo.invalidate(currentId, currentType, currentSeason, currentEpisode)
        if (silentRetryCount < MAX_SILENT_RETRIES) {
            silentRetryCount++
            _ui.update { it.copy(state = PlayerState.Buffering) }
            delay(200L * silentRetryCount)
            resolveAndPlay(currentId, currentType, currentTitle, currentSeason, currentEpisode, _ui.value.isOfflinePlayback)
        } else {
            silentRetryCount = 0
            _ui.update { it.copy(state = PlayerState.Error("Couldn't load stream. Tap to try again.")) }
        }
    }

    fun preRollCompleted() {
        _ui.update { it.copy(preRollVastUrl = null, isPreRollPlaying = false) }
        viewModelScope.launch {
            resolveAndPlay(currentId, currentType, currentTitle, currentSeason, currentEpisode)
        }
    }

    fun retry() {
        silentRetryCount = 0; fallbackIndex = 0
        streamRepo.invalidate(currentId, currentType, currentSeason, currentEpisode)
        _ui.update { it.copy(state = PlayerState.Resolving) }
        viewModelScope.launch {
            resolveAndPlay(currentId, currentType, currentTitle, currentSeason, currentEpisode, _ui.value.isOfflinePlayback)
        }
    }

    override fun onCleared() { release(null) }

    fun release(context: Context? = null) {
        errorHandlerJob?.cancel()
        stopNetworkMonitor(context)
        val p = exoPlayer
        if (p != null && _ui.value.isOfflinePlayback && currentId.isNotBlank()) {
            val posMs = p.currentPosition.coerceAtLeast(0L)
            val durMs = p.duration.coerceAtLeast(0L)
            kotlinx.coroutines.runBlocking {
                try { downloadRepo.updateWatchProgress(currentId, currentSeason, currentEpisode, posMs, durMs) }
                catch (_: Exception) {}
            }
        }
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
