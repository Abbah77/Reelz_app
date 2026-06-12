package com.reelz.ads

import com.reelz.remoteconfig.AdPrerollConfig

/**
 * Pure decision logic for IMA pre-roll ads.
 *
 * The VAST tag URL itself is no longer compiled in — it comes from
 * [AdEngine.vastTagUrlOrNull] (sourced from the active network's
 * `vast_tag_url` in remote config). All timing/skip rules below come
 * from [AdPrerollConfig] (`ads.preroll` in remote config).
 */
object VastTagProvider {

    /**
     * Whether a pre-roll should be shown for this playback session,
     * based entirely on remote-config-driven [config].
     */
    fun shouldShowPreRoll(
        config: AdPrerollConfig,
        isMovie: Boolean,
        isFirstPlayThisSession: Boolean,
        minutesSinceLastPreRoll: Long,
        isOfflinePlayback: Boolean,
        isResumingEpisode: Boolean,
        isQualitySwitch: Boolean,
    ): Boolean {
        if (isOfflinePlayback) return false
        if (config.skipOnResume && isResumingEpisode) return false
        if (config.skipOnQualitySwitch && isQualitySwitch) return false
        if (config.showOnMoviesOnly && !isMovie) return false
        return isFirstPlayThisSession || minutesSinceLastPreRoll >= config.minMinutesBetween
    }
}
