package com.reelz.ads

import com.reelz.BuildConfig

/**
 * Provides the VAST tag URL for IMA pre-roll ads.
 *
 * Replace [BuildConfig.AD_VAST_TAG_URL] with the VAST tag URL from your
 * ad network dashboard (Pangle / AppLovin MAX / etc).
 *
 * Example Pangle VAST URL format:
 *   https://pangle.io/api/vast?app_id=YOUR_APP_ID&placement_id=YOUR_PLACEMENT_ID
 *
 * Example AppLovin MAX VAST URL format:
 *   https://ms.applovin.com/vast?sdk_key=YOUR_SDK_KEY&ad_unit_id=YOUR_UNIT_ID
 */
object VastTagProvider {

    fun getPreRollVastUrl(): String = BuildConfig.AD_VAST_TAG_URL

    /**
     * Whether a pre-roll should be shown for this playback session.
     *
     * Rules (from design doc):
     *  - Movie playback (long content)
     *  - First play of the session
     *  - At least 30 minutes since the last pre-roll
     *
     * [isFirstPlayThisSession] is set by AdEngine / PlayerViewModel.
     * [minutesSinceLastPreRoll] is tracked in PlayerViewModel.
     */
    fun shouldShowPreRoll(
        isMovie: Boolean,
        isFirstPlayThisSession: Boolean,
        minutesSinceLastPreRoll: Long,
        isOfflinePlayback: Boolean,
        isResumingEpisode: Boolean,
        isQualitySwitch: Boolean,
    ): Boolean {
        if (isOfflinePlayback)    return false
        if (isResumingEpisode)    return false
        if (isQualitySwitch)      return false
        return isMovie && (isFirstPlayThisSession || minutesSinceLastPreRoll >= 30)
    }
}
