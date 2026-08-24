package com.axio.reelz.media.player

import com.axio.reelz.data.model.QualityTrack
import com.axio.reelz.data.model.Subtitle

// ─────────────────────────────────────────────────────────────────────────────
//  PlayerState — all sealed classes for player state.
//  Extracted from PlayerViewModel so state types are importable without
//  pulling in the ViewModel itself.
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
