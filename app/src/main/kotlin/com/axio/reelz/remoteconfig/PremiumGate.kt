package com.axio.reelz.remoteconfig

import com.axio.reelz.data.model.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class UserState {
    GUEST,
    FREE_USER,
    PREMIUM_ACTIVE,
    PREMIUM_GRACE,
    PREMIUM_EXPIRED,
}

/**
 * Single source of truth for "what is this user allowed to do right now".
 * Computed once whenever the session or config changes, held in memory, read by
 * every feature (player, downloads, ads, subtitles, profile). No feature ever
 * recomputes this itself — see the Golden Rules in the premium system design:
 *
 *   Rule 6 — Fail safe toward free, not toward premium. If session is null, if
 *            config hasn't loaded, if anything is ambiguous: default to FREE.
 *   Rule 7 — One state object, computed once, re-computed only when inputs change.
 */
@Singleton
class PremiumGate @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
) {
    private val _state = MutableStateFlow(UserState.GUEST)
    val state: StateFlow<UserState> = _state.asStateFlow()

    private var _session: UserSession? = null

    /** Recomputes state from the given session + current config. Call after any session change. */
    fun update(session: UserSession?) {
        _session = session
        _state.value = computeState(session)
    }

    private fun computeState(session: UserSession?): UserState {
        if (session == null) return UserState.GUEST
        if (!session.isPremium) return UserState.FREE_USER

        val now = System.currentTimeMillis()
        val expiresAt = session.expiresAtMs
        if (expiresAt <= 0L) return UserState.FREE_USER // malformed grant — fail safe toward free

        val gracePeriodMs = remoteConfig.premiumConfig().gracePeriodDays.coerceAtLeast(0) * 24L * 60L * 60L * 1000L

        return when {
            expiresAt > now                   -> UserState.PREMIUM_ACTIVE
            now - expiresAt <= gracePeriodMs   -> UserState.PREMIUM_GRACE
            else                               -> UserState.PREMIUM_EXPIRED
        }
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    fun currentState(): UserState = _state.value
    /** The session object PremiumGate is currently computed from, if any. */
    fun currentSession(): UserSession? = _session
    /** True for ACTIVE and GRACE — both unlock premium tier limits. */
    fun isPremium(): Boolean = currentState() == UserState.PREMIUM_ACTIVE || currentState() == UserState.PREMIUM_GRACE
    fun isInGrace(): Boolean = currentState() == UserState.PREMIUM_GRACE
    fun isExpired(): Boolean = currentState() == UserState.PREMIUM_EXPIRED
    fun isSignedIn(): Boolean = currentState() != UserState.GUEST

    // ── Tier limits — reads config based on current state, zero network ────────

    private fun activeTier() = if (isPremium()) remoteConfig.tiersConfig().premium else remoteConfig.tiersConfig().free

    fun maxResolutionHeight(): Int = activeTier().maxResolutionHeight
    fun maxDownloadResolutionHeight(): Int = activeTier().maxDownloadResolutionHeight
    fun maxDownloads(): Int = activeTier().maxDownloads
    fun adsEnabled(): Boolean = activeTier().adsEnabled
    fun canManualSubtitleSearch(): Boolean = activeTier().subtitlesManualSearch
    fun canBackgroundPlay(): Boolean = activeTier().backgroundPlay

    /**
     * Whether a track of this pixel height may be STREAMED by the current
     * tier. -1 (or any <= 0) means unlimited — streaming quality is not a
     * free/premium differentiator in this app; the ad-supported model is
     * the monetization lever for streaming instead. Kept as a real check
     * (not just removed) so a future config change re-introducing a
     * streaming cap doesn't require another code change, only a config
     * value change.
     */
    fun isStreamResolutionAllowed(heightPx: Int): Boolean {
        val cap = maxResolutionHeight()
        if (cap <= 0) return true
        return heightPx <= cap
    }

    /**
     * Whether a track of this pixel height may be DOWNLOADED by the current
     * tier. This is the real free/premium gate — downloads are offline, so
     * no ad can be served there. Driven entirely by remote config
     * (max_download_resolution_height); defaults to -1 (unlimited) so you
     * can loosen or tighten this remotely without an app update.
     */
    fun isDownloadResolutionAllowed(heightPx: Int): Boolean {
        val cap = maxDownloadResolutionHeight()
        if (cap <= 0) return true // unlimited — including "movie only has 1-2 renditions" cases
        return heightPx <= cap
    }

    // ── Expiry info for UI ───────────────────────────────────────────────────

    fun expiresAt(): Long = _session?.expiresAtMs ?: 0L

    /** Negative if already past expiry. */
    fun daysUntilExpiry(): Int {
        val expiresAt = expiresAt()
        if (expiresAt <= 0L) return 0
        val diffMs = expiresAt - System.currentTimeMillis()
        return Math.floorDiv(diffMs, 24L * 60L * 60L * 1000L).toInt()
    }

    fun shouldShowRenewBanner(): Boolean {
        if (isInGrace()) return true
        if (currentState() != UserState.PREMIUM_ACTIVE) return false
        return daysUntilExpiry() <= remoteConfig.premiumConfig().renewWarningDaysBefore
    }
}
