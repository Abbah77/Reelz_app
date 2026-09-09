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
import kotlinx.coroutines.withContext
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

/** Per-language subtitle download state shown in the drawer. */
sealed class SubtitleDownloadState {
    object Idle        : SubtitleDownloadState()
    object Loading     : SubtitleDownloadState()
    object Done        : SubtitleDownloadState()   // downloaded — hide icon
    data class Error(val msg: String) : SubtitleDownloadState()
}

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
    val streamRequestId: String?               = null,   // ENGINE request_id — attached to player feedback
    val isPreRollPlaying: Boolean              = false,
    /**
     * True when the player is buffering WHILE already playing (network stall).
     * Unlike PlayerState.Buffering (which covers initial load), this specifically
     * indicates mid-playback stalling so the UI can show a non-intrusive spinner
     * without pausing or hiding controls — the user did NOT pause intentionally.
     */
    val isNetworkStalling: Boolean             = false,
    /** Per-language download state for the subtitle drawer. */
    val subtitleDownloadStates: Map<String, SubtitleDownloadState> = emptyMap(),
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
                    .map { item ->
                        // For HLS: use localPlaylistPath (local index.m3u8)
                        // For MP4: use filePath
                        val playPath = if (item.streamUrl.contains(".m3u8") && item.localPlaylistPath.isNotBlank())
                            item.localPlaylistPath else item.filePath
                        QualityTrack(item.quality, playPath)
                    }
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
                    // Prefer localPlaylistPath for HLS, fallback to filePath for MP4
                    val localPath = match?.let { item ->
                        if (item.streamUrl.contains(".m3u8") && item.localPlaylistPath.isNotBlank())
                            item.localPlaylistPath else item.filePath
                    }
                    localPath?.takeIf { it.isNotBlank() } ?: streamUrl
                } else streamUrl

                val track = StreamTrack(name = "Offline", url = resolvedUrl,
                    type = if (resolvedUrl.endsWith(".m3u8") || streamIsHls) "hls" else "mp4")
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
                _ui.update { it.copy(availableQualities = qualities, streamRequestId = stream.requestId) }
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
        val options = subtitles.map { sub ->
            val displayLabel = sub.label.takeIf { it.isNotBlank() } ?: sub.language
            SubtitleOption(sub.language, displayLabel, sub.url, isEnabled = sub.enabled)
        }
        val doneStates = options.associate { it.language to SubtitleDownloadState.Done as SubtitleDownloadState }
        val autoEnabled = options.firstOrNull { it.isEnabled }
        _ui.update { it.copy(
            subtitleOptions        = options,
            subtitles              = subtitles,
            activeSubtitleLanguage = autoEnabled?.language ?: "off",
            subtitlesEnabled       = autoEnabled != null,
            subtitleDownloadStates = doneStates,
        ) }
        // Attach auto-enabled subtitle to ExoPlayer without blocking stream start.
        // selectSubtitle runs on main thread and rebuilds the MediaItem after prepare().
        if (autoEnabled != null) {
            viewModelScope.launch(Dispatchers.Main) {
                val sub = subtitles.firstOrNull { it.language == autoEnabled.language }
                if (sub != null) applyExternalSubtitleToPlayer(sub)
            }
        }
    }

    private suspend fun loadDownloadedSubtitles(id: String, season: Int, episode: Int) {
        val saved = downloadSubtitleDao.getForContent(id, season, episode)
        val options = saved.map { row ->
            SubtitleOption(row.language, row.label, row.localFilePath,
                isPersistent = true, persistentId = row.id, isEnabled = row.isEnabled)
        }
        // Populate subtitles list with format info so selectSubtitle can build SubtitleConfiguration
        val subtitleModels = saved.map { row ->
            Subtitle(
                url      = row.localFilePath,
                language = row.language,
                enabled  = row.isEnabled,
                label    = row.label,
                format   = row.format.ifBlank { "srt" },
            )
        }
        val lastEnabled = options.firstOrNull { it.isEnabled }
        _ui.update { it.copy(
            subtitleOptions        = options,
            subtitles              = subtitleModels,
            subtitleDownloadStates = saved.associate { it.language to SubtitleDownloadState.Done as SubtitleDownloadState },
            activeSubtitleLanguage = lastEnabled?.language ?: "off",
            subtitlesEnabled       = lastEnabled != null,
        )}
        // If a subtitle was enabled before, re-attach it to the player
        if (lastEnabled != null) {
            val sub = subtitleModels.firstOrNull { it.language == lastEnabled.language }
            if (sub != null) {
                withContext(Dispatchers.Main) { applyExternalSubtitleToPlayer(sub) }
            }
        }
    }

    // ── Subtitle download for drawer ──────────────────────────────────────────

    /**
     * Called when user taps a download icon in the subtitle drawer.
     * Downloads the subtitle for the requested language, shows spinner during fetch,
     * updates state on completion (hide icon) or failure (restore icon + friendly error).
     * For stream playback: caches the subtitle file and renders it immediately.
     * For offline playback: persists to DB so it survives session.
     */
    fun downloadSubtitleForLanguage(language: String) {
        // Mark as loading
        _ui.update { it.copy(
            subtitleDownloadStates = it.subtitleDownloadStates + (language to SubtitleDownloadState.Loading)
        )}
        // Read duration on the main thread — ExoPlayer enforces thread affinity
        val dur = (_ui.value.durationMs).coerceAtLeast(0L)
        viewModelScope.launch(Dispatchers.IO) {
            val result = streamRepo.getSubtitles(
                id         = currentId,
                mediaType  = currentType,
                season     = currentSeason,
                episode    = currentEpisode,
                language   = language,
                durationMs = dur,
            )
            when (result) {
                is NetworkResult.Success -> {
                    val subs = result.data
                    if (subs.isEmpty()) {
                        _ui.update { it.copy(
                            subtitleDownloadStates = it.subtitleDownloadStates +
                                (language to SubtitleDownloadState.Error("Not available for this title"))
                        )}
                        return@launch
                    }
                    val sub = subs.firstOrNull { it.language == language } ?: subs.first()

                    if (_ui.value.isOfflinePlayback) {
                        addDownloadedSubtitle(sub, sub.url)
                    } else {
                        val displayLabel = sub.label.takeIf { it.isNotBlank() } ?: sub.language
                        val newOption = SubtitleOption(sub.language, displayLabel, sub.url, isEnabled = sub.enabled)
                        val existing = _ui.value.subtitleOptions.filter { it.language != sub.language }
                        val newSubs  = _ui.value.subtitles.filter { it.language != sub.language } + sub
                        _ui.update { it.copy(
                            subtitleOptions = existing + newOption,
                            subtitles       = newSubs,
                        )}
                        // Auto-select the just-downloaded language and attach it to ExoPlayer
                        withContext(Dispatchers.Main) {
                            applyExternalSubtitleToPlayer(sub)
                            selectSubtitle(sub.language)
                        }
                    }
                    _ui.update { it.copy(
                        subtitleDownloadStates = it.subtitleDownloadStates + (language to SubtitleDownloadState.Done)
                    )}
                }
                is NetworkResult.Error -> {
                    val friendly = when {
                        result.isNetworkError -> "No connection"
                        result.isNotFound     -> "Not available for this title"
                        else                  -> "Download failed — try again"
                    }
                    _ui.update { it.copy(
                        subtitleDownloadStates = it.subtitleDownloadStates +
                            (language to SubtitleDownloadState.Error(friendly))
                    )}
                }
                else -> {}
            }
        }
    }

    fun searchOnlineSubtitles(query: String = "") {
        // No-op — kept for compatibility; drawer now uses downloadSubtitleForLanguage directly.
    }

    // ── Subtitle → ExoPlayer wiring ───────────────────────────────────────────

    /**
     * Maps a subtitle format string to the correct MIME type for ExoPlayer's
     * SubtitleConfiguration. Must be called on the main thread (player access).
     */
    private fun subtitleMimeType(format: String): String = when (format.lowercase().trim()) {
        "vtt", "webvtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
        "ass", "ssa"    -> androidx.media3.common.MimeTypes.TEXT_SSA
        "ttml", "xml"   -> androidx.media3.common.MimeTypes.APPLICATION_TTML
        else             -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP // srt default
    }

    /**
     * Rebuilds the current MediaItem with the given external subtitle attached and
     * re-prepares the player at the current position. Must be called on the main thread.
     *
     * This is the correct way to add external SRT/VTT files to ExoPlayer — the
     * MediaItem.SubtitleConfiguration is baked into the media source, not applied
     * through the track selector (which only works for in-stream text tracks).
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun applyExternalSubtitleToPlayer(sub: Subtitle) {
        val p = exoPlayer ?: return
        val currentPos = p.currentPosition.coerceAtLeast(0L)
        val wasPlaying = p.isPlaying

        // Build the SubtitleConfiguration
        val subConfig = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
            .setMimeType(subtitleMimeType(sub.format))
            .setLanguage(sub.language)
            .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
            .build()

        // Rebuild the MediaItem with the subtitle attached
        val currentItem = p.currentMediaItem ?: return
        val newItem = currentItem.buildUpon()
            .setSubtitleConfigurations(listOf(subConfig))
            .build()

        // Re-prepare at same position
        p.setMediaItem(newItem, currentPos)
        p.prepare()
        p.playWhenReady = wasPlaying
    }

    /**
     * Removes all external subtitle configurations from the current MediaItem.
     * Used when user selects "Off".
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun clearExternalSubtitlesFromPlayer() {
        val p = exoPlayer ?: return
        val currentItem = p.currentMediaItem ?: return
        if (currentItem.localConfiguration?.subtitleConfigurations.isNullOrEmpty()) return
        val currentPos = p.currentPosition.coerceAtLeast(0L)
        val wasPlaying = p.isPlaying
        val newItem = currentItem.buildUpon().setSubtitleConfigurations(emptyList()).build()
        p.setMediaItem(newItem, currentPos)
        p.prepare()
        p.playWhenReady = wasPlaying
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

        val ts = trackSelector
        if (!enabled) {
            // Clear external subtitles and suppress in-stream auto-selection
            clearExternalSubtitlesFromPlayer()
            ts?.setParameters(ts.buildUponParameters()
                .setPreferredTextLanguage(null)
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED))
            return
        }

        // Check if this option is an external URL (srt/vtt/etc) vs an in-stream track
        val subUrl = option?.url ?: ""
        val isExternal = subUrl.startsWith("http://") || subUrl.startsWith("https://") ||
                         subUrl.startsWith("file://") ||
                         (!subUrl.startsWith("/") && subUrl.contains(".") && !subUrl.isBlank())
        val isLocalFile = subUrl.startsWith("/") || subUrl.startsWith("file://")

        if (isExternal || isLocalFile) {
            // External subtitle: embed as SubtitleConfiguration in the MediaItem
            val sub = _ui.value.subtitles.firstOrNull { it.language == language }
            if (sub != null) {
                applyExternalSubtitleToPlayer(sub)
            } else if (option != null) {
                // Fallback: construct minimal Subtitle from option (offline case uses file path)
                val inferredFormat = when {
                    subUrl.endsWith(".vtt", ignoreCase = true) -> "vtt"
                    subUrl.endsWith(".ass", ignoreCase = true) -> "ass"
                    subUrl.endsWith(".ssa", ignoreCase = true) -> "ssa"
                    else -> "srt"
                }
                val fallbackSub = Subtitle(url = if (isLocalFile && !subUrl.startsWith("file://")) "file://$subUrl" else subUrl,
                    language = language, enabled = true, label = option.label, format = inferredFormat)
                applyExternalSubtitleToPlayer(fallbackSub)
            }
            // Also set track selector language so in-stream tracks match if present
            ts?.setParameters(ts.buildUponParameters()
                .setPreferredTextLanguage(language)
                .setPreferredTextRoleFlags(0)
                .setIgnoredTextSelectionFlags(0))
        } else {
            // In-stream track: use track selector only
            ts?.setParameters(ts.buildUponParameters()
                .setPreferredTextLanguage(language)
                .setPreferredTextRoleFlags(0)
                .setIgnoredTextSelectionFlags(0))
        }
    }

    fun toggleSubtitlesOnOff() {
        val cur = _ui.value
        if (cur.subtitlesEnabled) {
            selectSubtitle("off")
        } else {
            // Resume the last active language, or fall back to the first available option.
            val target = cur.subtitleOptions
                .firstOrNull { it.language == cur.activeSubtitleLanguage && it.language != "off" }
                ?: cur.subtitleOptions.firstOrNull { it.language != "off" }
            if (target != null) selectSubtitle(target.language)
            // If no options are available the toggle is a no-op (toggle pill stays off).
        }
    }

    fun setSubtitleOffset(offsetMs: Int) {
        _ui.update { it.copy(subtitleOffsetMs = offsetMs) }
        // Offset is applied at render time via SubtitleView in PlayerActivity (addTextOutput).
        // For external subtitle files we also need to re-attach the subtitle so ExoPlayer
        // re-parses it — a seek to the current position forces cue re-delivery.
        viewModelScope.launch(Dispatchers.Main) {
            val p = exoPlayer ?: return@launch
            if (_ui.value.subtitlesEnabled && _ui.value.activeSubtitleLanguage != "off") {
                // A tiny relative seek forces the text renderer to re-deliver cues
                // so the new offset takes effect immediately without re-preparing.
                val pos = p.currentPosition.coerceAtLeast(0L)
                p.seekTo(pos)
            }
        }
    }

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
                            Player.STATE_BUFFERING -> {
                                // If we were already playing, this is a mid-playback network stall —
                                // don't switch to Buffering state (which hides controls) but instead
                                // set isNetworkStalling so the UI can show a non-intrusive spinner.
                                val wasPlaying = _ui.value.state is PlayerState.Playing
                                if (wasPlaying) {
                                    _ui.update { it.copy(isNetworkStalling = true) }
                                } else {
                                    _ui.update { it.copy(state = PlayerState.Buffering, isNetworkStalling = false) }
                                }
                            }
                            Player.STATE_READY -> {
                                _ui.update { it.copy(
                                    state = if (p.playWhenReady) PlayerState.Playing else PlayerState.Paused,
                                    durationMs = p.duration.coerceAtLeast(0),
                                    isNetworkStalling = false,
                                ) }
                            }
                            Player.STATE_ENDED -> _ui.update { it.copy(state = PlayerState.Idle, isNetworkStalling = false) }
                            else -> {}
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _ui.update { it.copy(
                            state = if (isPlaying) PlayerState.Playing else PlayerState.Paused,
                            isNetworkStalling = if (isPlaying) false else _ui.value.isNetworkStalling,
                        ) }
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
        val rawUrl = primary.url
        val url = if (!rawUrl.startsWith("file://") && !rawUrl.startsWith("http://") && !rawUrl.startsWith("https://"))
            "file://$rawUrl" else rawUrl
        val isLocalFile = url.startsWith("file://")

        // Embed any currently-active external subtitle into the MediaItem
        val activeLang = _ui.value.activeSubtitleLanguage
        val activeSub  = if (_ui.value.subtitlesEnabled && activeLang != "off")
            _ui.value.subtitles.firstOrNull { it.language == activeLang } else null

        val itemBuilder = MediaItem.Builder().setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle).build())

        if (activeSub != null) {
            val subUrl = if (!activeSub.url.startsWith("http") && !activeSub.url.startsWith("file://"))
                "file://${activeSub.url}" else activeSub.url
            val subConfig = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subUrl))
                .setMimeType(subtitleMimeType(activeSub.format))
                .setLanguage(activeSub.language)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            itemBuilder.setSubtitleConfigurations(listOf(subConfig))
        }

        val item = itemBuilder.build()

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
                val rawPath = match.filePath.takeIf { it.isNotBlank() } ?: return
                val url = if (!rawPath.startsWith("file://") && !rawPath.startsWith("http")) "file://$rawPath" else rawPath
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
