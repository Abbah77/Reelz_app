package com.axio.reelz.media.player

import androidx.media3.common.C
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.axio.reelz.core.network.NetworkResult
import com.axio.reelz.core.database.DownloadSubtitleDao
import com.axio.reelz.core.database.DownloadSubtitleRow
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.data.model.Subtitle
import com.axio.reelz.data.repository.StreamRepository
import com.axio.reelz.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * SubtitleManager — subtitle loading and track selection logic.
 *
 * Extracted from PlayerViewModel. Owns:
 *  - Loading stream subtitles from the resolved stream result
 *  - Loading downloaded (persistent) subtitles from Room
 *  - Online subtitle search via StreamRepository
 *  - Track selection via DefaultTrackSelector
 *  - Persistent subtitle CRUD via DownloadSubtitleDao
 *
 * Dependency direction: SubtitleManager → (DownloadSubtitleDao | StreamRepository).
 * Never references UI or Activity.
 */
class SubtitleManager(
    private val streamRepo: StreamRepository,
    private val userRepo: UserRepository,
    private val downloadSubtitleDao: DownloadSubtitleDao,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SubtitleManagerState())
    val state: StateFlow<SubtitleManagerState> = _state.asStateFlow()

    // Injected by PlayerManager after ExoPlayer is built
    var trackSelector: DefaultTrackSelector? = null

    // Current media context — set by PlayerManager when playback starts
    private var currentId      = ""
    private var currentType    = MediaType.MOVIE
    private var currentSeason  = 0
    private var currentEpisode = 0
    private var currentDownloadId: String? = null

    fun setMediaContext(
        id: String,
        type: MediaType,
        season: Int,
        episode: Int,
        downloadId: String? = null,
    ) {
        currentId        = id
        currentType      = type
        currentSeason    = season
        currentEpisode   = episode
        currentDownloadId = downloadId
    }

    // ── Load subtitles from stream result ─────────────────────────────────────

    fun loadStreamSubtitles(subtitles: List<Subtitle>) {
        val options = subtitles.map { SubtitleOption(it.language, it.language, it.url, isEnabled = it.enabled) }
        _state.update { it.copy(subtitleOptions = options, subtitles = subtitles,
            activeSubtitleLanguage = "off", subtitlesEnabled = false) }
    }

    // ── Load downloaded subtitles from Room ───────────────────────────────────

    fun loadDownloadedSubtitles() {
        scope.launch(Dispatchers.IO) {
            val saved = downloadSubtitleDao.getForContent(currentId, currentSeason, currentEpisode)
            val options = saved.map { SubtitleOption(it.language, it.label, it.localFilePath,
                isPersistent = true, persistentId = it.id, isEnabled = it.isEnabled) }
            val lastEnabled = options.firstOrNull { it.isEnabled }
            _state.update { it.copy(subtitleOptions = options,
                activeSubtitleLanguage = lastEnabled?.language ?: "off",
                subtitlesEnabled = lastEnabled != null) }
        }
    }

    // ── Online subtitle search ─────────────────────────────────────────────────

    fun searchOnlineSubtitles(query: String = "") {
        if (!userRepo.isPremium) {
            _state.update { it.copy(subtitleUpsellMessage =
                "Manual subtitle search is a Premium feature. Upgrade to search any language.") }
            return
        }
        val langs = if (query.isBlank()) {
            val locale = java.util.Locale.getDefault().language.ifBlank { "en" }
            listOf("en", locale).distinct()
        } else {
            query.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        }
        _state.update { it.copy(isSubtitleSearching = true, subtitleSearchEmpty = false, subtitleUpsellMessage = null) }
        scope.launch(Dispatchers.IO) {
            val result = streamRepo.getSubtitles(currentId, currentType, currentSeason, currentEpisode, langs)
            val subs = (result as? NetworkResult.Success)?.data ?: emptyList()
            if (subs.isNotEmpty()) {
                val options = subs.map { s -> SubtitleOption(s.language, s.language, s.url, isEnabled = s.enabled) }
                val currentLang = _state.value.activeSubtitleLanguage
                _state.update { it.copy(
                    subtitleOptions     = options,
                    subtitles           = subs,
                    isSubtitleSearching = false,
                    subtitleSearchEmpty = false,
                    activeSubtitleLanguage = if (options.any { o -> o.language == currentLang }) currentLang else "off",
                    subtitlesEnabled = _state.value.subtitlesEnabled && options.any { o -> o.language == currentLang },
                ) }
            } else {
                _state.update { it.copy(isSubtitleSearching = false, subtitleSearchEmpty = true) }
            }
        }
    }

    // ── Persistent subtitle CRUD ──────────────────────────────────────────────

    fun addDownloadedSubtitle(sub: Subtitle, localFilePath: String) {
        val downloadId = currentDownloadId ?: return
        scope.launch(Dispatchers.IO) {
            val existing = downloadSubtitleDao.getForContent(currentId, currentSeason, currentEpisode)
            if (existing.any { it.language == sub.language }) {
                selectSubtitle(sub.language)
                return@launch
            }
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
            loadDownloadedSubtitles()
        }
    }

    fun togglePersistentSubtitle(option: SubtitleOption) {
        if (!option.isPersistent) return
        scope.launch(Dispatchers.IO) {
            val newEnabled = !option.isEnabled
            downloadSubtitleDao.setEnabled(option.persistentId, newEnabled)
            val updated = _state.value.subtitleOptions.map {
                if (it.persistentId == option.persistentId) it.copy(isEnabled = newEnabled) else it }
            _state.update { it.copy(subtitleOptions = updated) }
            if (!newEnabled && _state.value.activeSubtitleLanguage == option.language) selectSubtitle("off")
        }
    }

    fun deletePersistentSubtitle(option: SubtitleOption) {
        if (!option.isPersistent) return
        scope.launch(Dispatchers.IO) {
            downloadSubtitleDao.deleteForDownload(option.persistentId.toString())
            val updated = _state.value.subtitleOptions.filter { it.persistentId != option.persistentId }
            _state.update { it.copy(subtitleOptions = updated) }
            if (_state.value.activeSubtitleLanguage == option.language) selectSubtitle("off")
        }
    }

    // ── Track selection ───────────────────────────────────────────────────────

    fun selectSubtitle(language: String) {
        val option  = _state.value.subtitleOptions.firstOrNull { it.language == language }
        val enabled = language != "off" && option != null
        _state.update { it.copy(
            activeSubtitleLanguage = if (enabled) language else "off",
            subtitlesEnabled       = enabled,
            selectedSubtitle       = option?.label ?: "Off",
        ) }
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
        val cur = _state.value
        if (cur.subtitlesEnabled) selectSubtitle("off")
        else {
            val target = cur.subtitleOptions.firstOrNull { it.language == cur.activeSubtitleLanguage }
                ?: cur.subtitleOptions.firstOrNull()
            if (target != null) selectSubtitle(target.language)
        }
    }

    fun setSubtitleOffset(offsetMs: Int) {
        _state.update { it.copy(subtitleOffsetMs = offsetMs) }
    }

    fun reset() {
        _state.value = SubtitleManagerState()
        trackSelector = null
    }
}

data class SubtitleManagerState(
    val subtitleOptions: List<SubtitleOption>  = emptyList(),
    val activeSubtitleLanguage: String         = "off",
    val subtitlesEnabled: Boolean              = false,
    val subtitleOffsetMs: Int                  = 0,
    val showSubtitleDrawer: Boolean            = false,
    val isSubtitleSearching: Boolean           = false,
    val subtitleSearchEmpty: Boolean           = false,
    val subtitleUpsellMessage: String?         = null,
    val subtitles: List<Subtitle>              = emptyList(),
    val selectedSubtitle: String               = "Off",
)
