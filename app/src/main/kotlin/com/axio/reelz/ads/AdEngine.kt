package com.axio.reelz.ads

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
import com.applovin.mediation.nativeAds.MaxNativeAd
import com.applovin.mediation.nativeAds.MaxNativeAdLoader
import com.applovin.mediation.nativeAds.MaxNativeAdView
import com.applovin.sdk.AppLovinSdk
import com.applovin.sdk.AppLovinSdkConfiguration
import com.applovin.sdk.AppLovinSdkSettings
import com.axio.reelz.data.dto.AdPrerollConfig
import com.axio.reelz.data.repository.ConfigRepository
import com.axio.reelz.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AdEngine"

// ─────────────────────────────────────────────────────────────────────────────
// Native ad state — shared across all native placements
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
// Mid-roll schedule for video player
// ─────────────────────────────────────────────────────────────────────────────

data class MidRollSchedule(
    val shouldInsert: Boolean,
    val breakpointsMs: List<Long>,
)

// ─────────────────────────────────────────────────────────────────────────────
// AdEngine — single source of truth for every ad format.
//
// Config authority: RemoteConfigRepository. No hard-coded IDs or thresholds.
// Premium gate: adsEnabled() checks master switch + per-user premium status.
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class AdEngine @Inject constructor(
    private val configRepo: ConfigRepository,
    private val sessionRepo: UserRepository,
    private val appPrefs: com.axio.reelz.core.preferences.AppPreferencesStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Session-level counters ───────────────────────────────────────────────
    var interstitialShownCount: Int = 0
    private var appOpenShownThisSession = false
    private var backgroundedAtMs: Long  = 0L

    // ── Persistent counters (survive cold starts) ────────────────────────────
    private var _lastInterstitialTimeMs: Long = 0L
    private var _totalContentOpens: Int       = 0
    private var _totalPlayTaps: Int           = 0

    val lastInterstitialTimeMs: Long get() = _lastInterstitialTimeMs
    val totalContentOpens: Int        get() = _totalContentOpens
    val totalPlayTaps: Int            get() = _totalPlayTaps

    fun loadPersistedCounters() {
        scope.launch(Dispatchers.IO) {
            _lastInterstitialTimeMs = appPrefs.getLastInterstitialTimeMs()
            _totalContentOpens      = appPrefs.getTotalContentOpens()
            _totalPlayTaps          = appPrefs.getTotalPlayTaps()
        }
    }

    // ── Preloaded ad objects ─────────────────────────────────────────────────
    private var loadedInterstitial: MaxInterstitialAd? = null
    private var loadedRewarded:     MaxRewardedAd?     = null
    private var loadedAppOpen:      MaxAppOpenAd?      = null

    var isInterstitialReady: Boolean = false; private set
    var isRewardedReady: Boolean     = false; private set
    var isAppOpenReady: Boolean      = false; private set

    private lateinit var appContext: Context

    // ─────────────────────────────────────────────────────────────────────────
    // Config helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun ads() = configRepo.adsConfig()

    /** Single master gate — every placement calls this. */
    fun adsEnabled(): Boolean = configRepo.areAdsEnabled(isPremiumUser = sessionRepo.isPremium)

    fun shouldShowRemoveAdsBanner(): Boolean = adsEnabled()

    private fun bannerAdUnitId()       = ads().bannerId.orEmpty()
    private fun nativeAdUnitId()       = ads().nativeId.orEmpty()
    private fun interstitialAdUnitId() = ads().interstitialId.orEmpty()
    private fun rewardedAdUnitId()     = ads().rewardedId.orEmpty()

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (!adsEnabled()) { Log.d(TAG, "Ads disabled — skip init"); return }

        val sdkKey = ads().applovinSdkKey
        if (sdkKey.isBlank()) { Log.d(TAG, "No SDK key — skip init"); return }

        val sdk = AppLovinSdk.getInstance(sdkKey, AppLovinSdkSettings(appContext), appContext)
        sdk.mediationProvider = ads().mediationProvider.ifBlank { "max" }
        sdk.initializeSdk { _: AppLovinSdkConfiguration ->
            Log.d(TAG, "SDK ready")
            preloadAll()
        }
    }

    private fun preloadAll() {
        val p = ads().placements
        if (p.interstitialEnabled) preloadInterstitial()
        if (p.appOpenEnabled)      preloadAppOpen()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Preloaders
    // ─────────────────────────────────────────────────────────────────────────

    private fun preloadInterstitial() {
        if (!adsEnabled() || !ads().placements.interstitialEnabled) return
        val id = interstitialAdUnitId(); if (id.isBlank()) return

        val ad = MaxInterstitialAd(id, appContext)
        ad.setListener(object : MaxAdListener {
            override fun onAdLoaded(a: MaxAd)  { loadedInterstitial = ad; isInterstitialReady = true }
            override fun onAdLoadFailed(id: String, err: MaxError) {
                isInterstitialReady = false
                scope.launch { delay(ads().frequency.retryDelayMs); preloadInterstitial() }
            }
            override fun onAdDisplayed(a: MaxAd)                  {}
            override fun onAdHidden(a: MaxAd)                      { preloadInterstitial() }
            override fun onAdClicked(a: MaxAd)                     {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError)  {}
        })
        ad.loadAd()
    }

    private var cachedActivity: Activity? = null
    fun setActivity(activity: Activity) {
        cachedActivity = activity
        if (!isRewardedReady && loadedRewarded == null) preloadRewarded()
    }

    private fun preloadRewarded() {
        if (!adsEnabled() || !ads().placements.rewardedEnabled) return
        val id = rewardedAdUnitId(); if (id.isBlank()) return
        val activity = cachedActivity ?: return

        val ad = MaxRewardedAd.getInstance(id, activity)
        ad.setListener(object : MaxRewardedAdListener {
            override fun onAdLoaded(a: MaxAd) { loadedRewarded = ad; isRewardedReady = true }
            override fun onAdLoadFailed(id: String, err: MaxError) {
                isRewardedReady = false
                scope.launch { delay(ads().frequency.retryDelayMs); preloadRewarded() }
            }
            override fun onAdDisplayed(a: MaxAd)                    {}
            override fun onAdHidden(a: MaxAd)                        { preloadRewarded() }
            override fun onAdClicked(a: MaxAd)                       {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError)    {}
            override fun onUserRewarded(a: MaxAd, r: MaxReward)      {}
            override fun onRewardedVideoStarted(a: MaxAd)            {}
            override fun onRewardedVideoCompleted(a: MaxAd)          {}
        })
        ad.loadAd()
    }

    private fun preloadAppOpen() {
        if (!adsEnabled() || !ads().placements.appOpenEnabled) return
        val id = ads().appOpenId; if (id.isBlank()) return

        val ad = MaxAppOpenAd(id, appContext)
        ad.setListener(object : MaxAdListener {
            override fun onAdLoaded(a: MaxAd)  { loadedAppOpen = ad; isAppOpenReady = true }
            override fun onAdLoadFailed(id: String, err: MaxError) {
                isAppOpenReady = false
                scope.launch { delay(ads().frequency.retryDelayMs); preloadAppOpen() }
            }
            override fun onAdDisplayed(a: MaxAd)                    {}
            override fun onAdHidden(a: MaxAd)                        { isAppOpenReady = false; preloadAppOpen() }
            override fun onAdClicked(a: MaxAd)                       {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError)    {}
        })
        ad.loadAd()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // App open — fires on resume after ≥ 15 min in background
    // ─────────────────────────────────────────────────────────────────────────

    fun onAppBackground() { backgroundedAtMs = System.currentTimeMillis() }

    fun onAppForeground(activity: Activity) {
        if (!adsEnabled() || !ads().placements.appOpenEnabled) return
        val elapsed = System.currentTimeMillis() - backgroundedAtMs
        if (elapsed >= 15 * 60_000L && isAppOpenReady) showAppOpen(activity)
    }

    fun showAppOpenIfReady(activity: Activity) {
        if (!adsEnabled() || !ads().placements.appOpenEnabled) return
        if (appOpenShownThisSession) return
        showAppOpen(activity)
    }

    private fun showAppOpen(activity: Activity) {
        val ad = loadedAppOpen ?: return
        if (!isAppOpenReady) return
        appOpenShownThisSession = true; isAppOpenReady = false
        ad.setListener(object : MaxAdListener {
            override fun onAdLoaded(a: MaxAd)                    {}
            override fun onAdLoadFailed(id: String, e: MaxError) {}
            override fun onAdDisplayed(a: MaxAd)                 {}
            override fun onAdHidden(a: MaxAd)                    { preloadAppOpen() }
            override fun onAdClicked(a: MaxAd)                   {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError){ preloadAppOpen() }
        })
        ad.showAd()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Interstitial (Guest — psychological + mathematical timing)
    // ─────────────────────────────────────────────────────────────────────────

    fun shouldShowInterstitial(): Boolean {
        if (!adsEnabled() || !ads().placements.interstitialEnabled) return false
        val freq = ads().frequency
        val now  = System.currentTimeMillis()
        return isInterstitialReady
            && _totalContentOpens >= freq.contentOpensBeforeFirst
            && _totalPlayTaps % freq.everyNPlays == 0
            && _totalPlayTaps > 0
            && (now - _lastInterstitialTimeMs) > freq.minMsBetween
            && interstitialShownCount < freq.maxPerSession
    }

    fun showInterstitial(activity: Activity, onDismissed: () -> Unit, onFailed: () -> Unit) {
        val ad = loadedInterstitial
        if (ad == null || !isInterstitialReady || !adsEnabled() || !ads().placements.interstitialEnabled) {
            onFailed(); return
        }
        isInterstitialReady = false
        recordInterstitialShown()
        ad.setListener(object : MaxAdListener {
            override fun onAdLoaded(a: MaxAd)                    {}
            override fun onAdLoadFailed(id: String, e: MaxError) { onFailed() }
            override fun onAdDisplayed(a: MaxAd)                 {}
            override fun onAdHidden(a: MaxAd)                    { onDismissed(); preloadInterstitial() }
            override fun onAdClicked(a: MaxAd)                   {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError){ onFailed(); preloadInterstitial() }
        })
        ad.showAd(activity)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rewarded (Download gating)
    // ─────────────────────────────────────────────────────────────────────────

    fun showRewarded(activity: Activity, onRewarded: () -> Unit, onSkipped: () -> Unit) {
        val ad = loadedRewarded
        if (ad == null || !isRewardedReady || !adsEnabled() || !ads().placements.rewardedEnabled) {
            onSkipped(); return
        }
        var earned = false; isRewardedReady = false
        ad.setListener(object : MaxRewardedAdListener {
            override fun onAdLoaded(a: MaxAd)                    {}
            override fun onAdLoadFailed(id: String, e: MaxError) { onSkipped() }
            override fun onAdDisplayed(a: MaxAd)                 {}
            override fun onAdHidden(a: MaxAd)                    { if (earned) onRewarded() else onSkipped(); preloadRewarded() }
            override fun onAdClicked(a: MaxAd)                   {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError){ onSkipped(); preloadRewarded() }
            override fun onUserRewarded(a: MaxAd, r: MaxReward)  { earned = true }
            override fun onRewardedVideoStarted(a: MaxAd)        {}
            override fun onRewardedVideoCompleted(a: MaxAd)      {}
        })
        ad.showAd(activity)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ad unit ID accessors for composable ad slots
    // ─────────────────────────────────────────────────────────────────────────

    fun bannerAdUnitIdOrNull(): String? {
        if (!adsEnabled() || !ads().placements.bannerEnabled) return null
        return bannerAdUnitId().takeIf { it.isNotBlank() }
    }

    fun nativeAdUnitIdOrNull(): String? {
        if (!adsEnabled() || !ads().placements.nativeEnabled) return null
        return nativeAdUnitId().takeIf { it.isNotBlank() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Native ad loader (on-demand, fresh per placement)
    // ─────────────────────────────────────────────────────────────────────────

    fun loadNativeAd(onLoaded: (NativeAdState.Loaded) -> Unit, onFailed: () -> Unit) {
        val unitId = nativeAdUnitIdOrNull()
        if (unitId == null) { scope.launch(Dispatchers.Main) { onFailed() }; return }

        scope.launch(Dispatchers.Main) {
            val loader = MaxNativeAdLoader(unitId, appContext)
            loader.setNativeAdListener(object : com.applovin.mediation.nativeAds.MaxNativeAdListener() {
                override fun onNativeAdLoaded(view: MaxNativeAdView?, ad: MaxAd) {
                    val native: MaxNativeAd = ad.nativeAd ?: run { onFailed(); return }
                    val headline   = native.title.orEmpty()
                    val body       = native.body.orEmpty()
                    val cta        = native.callToAction.orEmpty().ifBlank { "Learn More" }
                    val icon       = native.icon?.uri?.toString().orEmpty()
                    val image      = native.mainImage?.uri?.toString().orEmpty()
                    val advertiser = native.advertiser.orEmpty()
                    if (headline.isBlank() || (icon.isBlank() && image.isBlank())) { onFailed(); return }
                    onLoaded(NativeAdState.Loaded(headline, body, cta, advertiser,
                        ad.adReviewCreativeId.orEmpty(), image, icon))
                }
                override fun onNativeAdLoadFailed(adUnitId: String, error: MaxError) { onFailed() }
                override fun onNativeAdClicked(ad: MaxAd) {}
                override fun onNativeAdExpired(ad: MaxAd) {}
            })
            loader.loadAd()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAST / IMA config
    // ─────────────────────────────────────────────────────────────────────────

    fun vastTagUrlOrNull(): String? {
        if (!adsEnabled() || !ads().placements.prerollEnabled) return null
        return ads().vastTagUrl.takeIf { it.isNotBlank() }
    }

    fun prerollConfig() = AdPrerollConfig(
        skipOnResume        = true,
        skipOnQualitySwitch = true,
        showOnMoviesOnly    = false,
        minMinutesBetween   = 30L,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Mid-roll schedule
    //  < 60 min  → no mid-roll
    //  60-120 min → 1 break at ~40 min
    //  120+ min   → break every 40 min
    // ─────────────────────────────────────────────────────────────────────────

    fun midRollSchedule(durationMs: Long): MidRollSchedule {
        if (!adsEnabled() || !ads().placements.prerollEnabled) return MidRollSchedule(false, emptyList())
        val durationMin = durationMs / 60_000L
        if (durationMin < 60) return MidRollSchedule(false, emptyList())

        val intervalMs  = 40 * 60_000L
        val breakpoints = mutableListOf<Long>()
        var t           = intervalMs
        while (t < durationMs - (10 * 60_000L)) { breakpoints.add(t); t += intervalMs }
        return MidRollSchedule(breakpoints.isNotEmpty(), breakpoints)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Counters
    // ─────────────────────────────────────────────────────────────────────────

    fun incrementContentOpen() {
        _totalContentOpens++
        scope.launch(Dispatchers.IO) { appPrefs.incrementContentOpens() }
    }

    fun incrementPlayTap() {
        _totalPlayTaps++
        scope.launch(Dispatchers.IO) { appPrefs.incrementPlayTaps() }
    }

    private fun recordInterstitialShown() {
        _lastInterstitialTimeMs = System.currentTimeMillis()
        interstitialShownCount++
        scope.launch(Dispatchers.IO) { appPrefs.setLastInterstitialTimeMs(_lastInterstitialTimeMs) }
    }
}
