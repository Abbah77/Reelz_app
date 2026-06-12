package com.reelz.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.applovin.mediation.MaxAd
import com.applovin.mediation.MaxAdListener
import com.applovin.mediation.MaxError
import com.applovin.mediation.MaxReward
import com.applovin.mediation.MaxRewardedAdListener
import com.applovin.mediation.ads.MaxAppOpenAd
import com.applovin.mediation.ads.MaxInterstitialAd
import com.applovin.mediation.ads.MaxRewardedAd
import com.applovin.sdk.AppLovinSdk
import com.applovin.sdk.AppLovinSdkConfiguration
import com.applovin.sdk.AppLovinSdkSettings
import com.reelz.remoteconfig.AdNetwork
import com.reelz.remoteconfig.RemoteConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AdEngine"

// ─────────────────────────────────────────────────────────────────────────────
// Native ad state (shared sealed class for both BrowseScreen and ShortsScreen)
// ─────────────────────────────────────────────────────────────────────────────

sealed class NativeAdState {
    object Loading : NativeAdState()
    data class Loaded(
        val headline: String,
        val body: String,
        val callToAction: String,
        val advertiserName: String,
        val clickUrl: String,
        val imageUrl: String,
        val iconUrl: String,
    ) : NativeAdState()
    object Failed : NativeAdState()
}

// ─────────────────────────────────────────────────────────────────────────────
// AdEngine — every ID, toggle and frequency value is read live from
// RemoteConfigRepository. Nothing about ad networks or ad unit IDs is
// hard-coded; the config has full authority, including the master on/off
// switch (ads.enabled) and per-placement toggles (ads.placements.*).
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class AdEngine @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Session state (reset on cold start) ──────────────────────────────────
    var interstitialShownCount: Int  = 0
    var lastInterstitialTimeMs: Long = 0L
    private var appOpenShownThisSession = false

    // ── Persistent-ish counters (could survive session, kept in memory here) ─
    var totalContentOpens: Int = 0
    var totalPlayTaps: Int     = 0

    // ── Preloaded ad objects ──────────────────────────────────────────────────
    private var loadedInterstitial: MaxInterstitialAd? = null
    private var loadedRewarded:     MaxRewardedAd?     = null
    private var loadedAppOpen:      MaxAppOpenAd?      = null

    var isInterstitialReady: Boolean = false
        private set
    var isRewardedReady: Boolean = false
        private set
    var isAppOpenReady: Boolean = false
        private set

    private lateinit var appContext: Context

    // ─────────────────────────────────────────────────────────────────────────
    // Config helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun adsConfig() = remoteConfig.adsConfig()
    private fun network(): AdNetwork? = remoteConfig.activeAdNetwork()

    /** True only when the remote config master switch AND feature flag both allow ads. */
    private fun adsEnabled(): Boolean = remoteConfig.areAdsEnabled()

    private fun interstitialAdUnitId(): String = network()?.interstitialId.orEmpty()
    private fun rewardedAdUnitId(): String     = network()?.rewardedId.orEmpty()
    private fun appOpenAdUnitId(): String      = network()?.appOpenId.orEmpty()
    private fun bannerAdUnitId(): String       = network()?.bannerId.orEmpty()
    private fun nativeAdUnitId(): String       = network()?.nativeId.orEmpty()

    // ─────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initializes the mediation SDK and preloads ad formats.
     * No-ops entirely if [adsEnabled] is false or no SDK key is configured yet —
     * safe to call even before the mediation SDK key has been set.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext

        if (!adsEnabled()) {
            Log.d(TAG, "Ads disabled via remote config — skipping SDK init")
            return
        }

        val sdkKey = adsConfig().applovinSdkKey
        if (sdkKey.isBlank()) {
            Log.d(TAG, "No mediation SDK key configured yet — skipping SDK init")
            return
        }

        val sdk = AppLovinSdk.getInstance(sdkKey, AppLovinSdkSettings(appContext), appContext)
        sdk.mediationProvider = adsConfig().mediationProvider.ifBlank { "max" }
        sdk.initializeSdk { _: AppLovinSdkConfiguration ->
            Log.d(TAG, "Mediation SDK initialized")
            preloadAll()
        }
    }

    private fun preloadAll() {
        val placements = adsConfig().placements
        if (placements.interstitialEnabled) preloadInterstitial()
        // preloadRewarded() is called from setActivity() once an Activity is available
        if (placements.appOpenEnabled) preloadAppOpen()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Preloaders
    // ─────────────────────────────────────────────────────────────────────────

    private fun preloadInterstitial() {
        if (!adsEnabled() || !adsConfig().placements.interstitialEnabled) return
        val unitId = interstitialAdUnitId()
        if (unitId.isBlank()) return

        val ad = MaxInterstitialAd(unitId, appContext)
        ad.setListener(object : MaxAdListener {
            override fun onAdLoaded(a: MaxAd) {
                loadedInterstitial = ad
                isInterstitialReady = true
                Log.d(TAG, "Interstitial ready")
            }
            override fun onAdLoadFailed(id: String, err: MaxError) {
                isInterstitialReady = false
                Log.w(TAG, "Interstitial failed: ${err.message}, retrying")
                scope.launch { delay(adsConfig().interstitialFrequency.retryDelayMs); preloadInterstitial() }
            }
            override fun onAdDisplayed(a: MaxAd)       {}
            override fun onAdHidden(a: MaxAd)           { preloadInterstitial() }
            override fun onAdClicked(a: MaxAd)          {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError) {}
        })
        ad.loadAd()
    }

    private var cachedActivity: Activity? = null

    fun setActivity(activity: Activity) {
        cachedActivity = activity
        if (!isRewardedReady && loadedRewarded == null) preloadRewarded()
    }

    private fun preloadRewarded() {
        if (!adsEnabled() || !adsConfig().placements.rewardedEnabled) return
        val unitId = rewardedAdUnitId()
        if (unitId.isBlank()) return

        val activity = cachedActivity ?: return
        val ad = MaxRewardedAd.getInstance(unitId, activity)
        ad.setListener(object : MaxRewardedAdListener {
            override fun onAdLoaded(a: MaxAd) {
                loadedRewarded = ad
                isRewardedReady = true
                Log.d(TAG, "Rewarded ready")
            }
            override fun onAdLoadFailed(id: String, err: MaxError) {
                isRewardedReady = false
                scope.launch { delay(adsConfig().interstitialFrequency.retryDelayMs); preloadRewarded() }
            }
            override fun onAdDisplayed(a: MaxAd)       {}
            override fun onAdHidden(a: MaxAd)           { preloadRewarded() }
            override fun onAdClicked(a: MaxAd)          {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError) {}
            override fun onUserRewarded(a: MaxAd, r: MaxReward)   {}
            override fun onRewardedVideoStarted(a: MaxAd)         {}
            override fun onRewardedVideoCompleted(a: MaxAd)       {}
        })
        ad.loadAd()
    }

    private fun preloadAppOpen() {
        if (!adsEnabled() || !adsConfig().placements.appOpenEnabled) return
        val unitId = appOpenAdUnitId()
        if (unitId.isBlank()) return

        val ad = MaxAppOpenAd(unitId, appContext)
        ad.setListener(object : MaxAdListener {
            override fun onAdLoaded(a: MaxAd) {
                loadedAppOpen = ad
                isAppOpenReady = true
                Log.d(TAG, "App open ready")
            }
            override fun onAdLoadFailed(id: String, err: MaxError) {
                isAppOpenReady = false
                scope.launch { delay(adsConfig().interstitialFrequency.retryDelayMs); preloadAppOpen() }
            }
            override fun onAdDisplayed(a: MaxAd)                    {}
            override fun onAdHidden(a: MaxAd)                       { isAppOpenReady = false; preloadAppOpen() }
            override fun onAdClicked(a: MaxAd)                      {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError)   {}
        })
        ad.loadAd()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frequency cap gate — every threshold comes from remote config
    // ─────────────────────────────────────────────────────────────────────────

    fun shouldShowInterstitial(): Boolean {
        if (!adsEnabled() || !adsConfig().placements.interstitialEnabled) return false
        val freq = adsConfig().interstitialFrequency
        val now = System.currentTimeMillis()
        return isInterstitialReady
            && totalContentOpens >= freq.contentOpensBeforeFirst
            && totalPlayTaps % freq.everyNPlays == 0
            && totalPlayTaps > 0
            && (now - lastInterstitialTimeMs) > freq.minMsBetween
            && interstitialShownCount < freq.maxPerSession
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Show functions — ALWAYS call onDismissed/onFailed so user is never blocked
    // ─────────────────────────────────────────────────────────────────────────

    fun showInterstitial(
        activity: Activity,
        onDismissed: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val ad = loadedInterstitial
        if (ad == null || !isInterstitialReady || !adsEnabled() || !adsConfig().placements.interstitialEnabled) {
            onFailed(); return
        }

        isInterstitialReady = false
        lastInterstitialTimeMs = System.currentTimeMillis()
        interstitialShownCount++

        ad.setListener(object : MaxAdListener {
            override fun onAdLoaded(a: MaxAd)   {}
            override fun onAdLoadFailed(id: String, err: MaxError) { onFailed() }
            override fun onAdDisplayed(a: MaxAd) {}
            override fun onAdHidden(a: MaxAd)    { onDismissed(); preloadInterstitial() }
            override fun onAdClicked(a: MaxAd)   {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError) { onFailed(); preloadInterstitial() }
        })
        ad.showAd(activity)
    }

    fun showRewarded(
        activity: Activity,
        onRewarded: () -> Unit,
        onSkipped: () -> Unit,
    ) {
        val ad = loadedRewarded
        if (ad == null || !isRewardedReady || !adsEnabled() || !adsConfig().placements.rewardedEnabled) {
            onSkipped(); return
        }

        var userEarnedReward = false
        isRewardedReady = false

        ad.setListener(object : MaxRewardedAdListener {
            override fun onAdLoaded(a: MaxAd)   {}
            override fun onAdLoadFailed(id: String, err: MaxError) { onSkipped() }
            override fun onAdDisplayed(a: MaxAd) {}
            override fun onAdHidden(a: MaxAd)    {
                if (userEarnedReward) onRewarded() else onSkipped()
                preloadRewarded()
            }
            override fun onAdClicked(a: MaxAd)          {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError) { onSkipped(); preloadRewarded() }
            override fun onUserRewarded(a: MaxAd, r: MaxReward)   { userEarnedReward = true }
            override fun onRewardedVideoStarted(a: MaxAd)         {}
            override fun onRewardedVideoCompleted(a: MaxAd)       {}
        })
        ad.showAd(activity)
    }

    fun showAppOpenIfReady(activity: Activity) {
        if (!adsEnabled() || !adsConfig().placements.appOpenEnabled) return
        if (appOpenShownThisSession) return
        val ad = loadedAppOpen ?: return
        if (!isAppOpenReady) return

        appOpenShownThisSession = true
        isAppOpenReady = false

        ad.setListener(object : MaxAdListener {
            override fun onAdLoaded(a: MaxAd)   {}
            override fun onAdLoadFailed(id: String, err: MaxError) {}
            override fun onAdDisplayed(a: MaxAd) {}
            override fun onAdHidden(a: MaxAd)    { preloadAppOpen() }
            override fun onAdClicked(a: MaxAd)   {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError) { preloadAppOpen() }
        })
        ad.showAd()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Banner / Native ad unit IDs — exposed for the composables that render them
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the configured banner ad unit ID, or null if banners are disabled/unset. */
    fun bannerAdUnitIdOrNull(): String? {
        if (!adsEnabled() || !adsConfig().placements.bannerEnabled) return null
        return bannerAdUnitId().takeIf { it.isNotBlank() }
    }

    /** Returns the configured native ad unit ID, or null if native ads are disabled/unset. */
    fun nativeAdUnitIdOrNull(): String? {
        if (!adsEnabled() || !adsConfig().placements.nativeEnabled) return null
        return nativeAdUnitId().takeIf { it.isNotBlank() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Native ad loader (on-demand, not preloaded)
    // ─────────────────────────────────────────────────────────────────────────

    fun loadNativeAd(
        onLoaded: (NativeAdState.Loaded) -> Unit,
        onFailed: () -> Unit,
    ) {
        val unitId = nativeAdUnitIdOrNull()
        if (unitId == null) {
            scope.launch { onFailed() }
            return
        }
        // Mediation native ads are loaded via MaxNativeAdLoader using [unitId].
        // Implementation stub — wire in your MaxNativeAdLoader here.
        // The NativeAdState.Loaded data class carries everything the composable needs.
        // For now, emit failure so the card collapses gracefully.
        scope.launch {
            delay(200) // simulate async
            onFailed()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAST tag URL for IMA pre-roll — fully config driven
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the configured VAST tag URL, or null if pre-roll ads are disabled/unset. */
    fun vastTagUrlOrNull(): String? {
        if (!adsEnabled() || !adsConfig().placements.prerollEnabled) return null
        return network()?.vastTagUrl?.takeIf { it.isNotBlank() }
    }

    /** Pre-roll timing/skip rules from remote config. */
    fun prerollConfig() = adsConfig().preroll

    // ─────────────────────────────────────────────────────────────────────────
    // Counters
    // ─────────────────────────────────────────────────────────────────────────

    fun incrementContentOpen() { totalContentOpens++ }

    fun incrementPlayTap() { totalPlayTaps++ }
}
