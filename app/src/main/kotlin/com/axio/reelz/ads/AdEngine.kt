package com.axio.reelz.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.axio.reelz.data.dto.AdPrerollConfig
import com.axio.reelz.data.repository.ConfigRepository
import com.axio.reelz.data.repository.UserRepository
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions
import com.unity3d.mediation.banner.BannerAdLoadOptions
import com.unity3d.mediation.banner.BannerAdPosition
import com.unity3d.mediation.banner.BannerAdSize
import com.unity3d.mediation.banner.BannerView
import com.unity3d.mediation.banner.IBannerAdLoadListener
import com.unity3d.mediation.banner.IBannerAdShowListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AdEngine"

// ─────────────────────────────────────────────────────────────────────────────
// Unity Ads IDs — these are the active placement IDs.
// The backend DTO fields (bannerId, interstitialId, rewardedId) take priority
// when non-blank, so you can override these remotely without an app update.
// ─────────────────────────────────────────────────────────────────────────────
private const val UNITY_GAME_ID          = "800380914"
private const val UNITY_BANNER_ID        = "BP_Banner_Android"
private const val UNITY_INTERSTITIAL_ID  = "BP_Interstitial_Android"
private const val UNITY_REWARDED_ID      = "BP_Rewarded_Android"

// ─────────────────────────────────────────────────────────────────────────────
// Native ad state — kept for NativeAdCard / HeroBannerAd composables.
// Unity Ads has no native format; these composables will silently collapse
// via NativeAdState.Failed until you add a native-capable network.
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
// Mid-roll schedule (kept for future use with a VAST/IMA-capable network)
// ─────────────────────────────────────────────────────────────────────────────

data class MidRollSchedule(
    val shouldInsert: Boolean,
    val breakpointsMs: List<Long>,
)

// ─────────────────────────────────────────────────────────────────────────────
// AdEngine — Unity Ads implementation.
//
// Supported formats : Banner · Interstitial · Rewarded
// Stubbed formats   : Native · App-Open · Preroll/VAST
//   (stubs are no-ops / silent failures so no existing call site breaks)
//
// To add AppLovin MAX or another network alongside Unity, wire them in here
// using the same mediationProvider flag in the backend DTO.
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
    private var backgroundedAtMs: Long = 0L

    // ── Persistent counters ──────────────────────────────────────────────────
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

    // ── Unity ad ready flags ─────────────────────────────────────────────────
    var isInterstitialReady: Boolean = false; private set
    var isRewardedReady: Boolean     = false; private set
    val isAppOpenReady: Boolean      = false            // Unity has no app-open format

    private var cachedActivity: Activity? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Config helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun ads() = configRepo.adsConfig()

    /** Single master gate — every placement checks this first. */
    fun adsEnabled(): Boolean = configRepo.areAdsEnabled(isPremiumUser = sessionRepo.isPremium)

    fun shouldShowRemoveAdsBanner(): Boolean = adsEnabled()

    /**
     * Returns the effective placement ID — remote DTO value wins over the
     * hard-coded Unity fallback so you can A/B test or override without a
     * new release.
     */
    private fun bannerAdUnitId()       = ads().bannerId.takeIf { it.isNotBlank() } ?: UNITY_BANNER_ID
    private fun interstitialAdUnitId() = ads().interstitialId.takeIf { it.isNotBlank() } ?: UNITY_INTERSTITIAL_ID
    private fun rewardedAdUnitId()     = ads().rewardedId.takeIf { it.isNotBlank() } ?: UNITY_REWARDED_ID
    private fun nativeAdUnitId()       = ads().nativeId.orEmpty()     // no Unity native

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        if (!adsEnabled()) { Log.d(TAG, "Ads disabled — skip init"); return }

        val testMode = false   // set true during development
        UnityAds.initialize(context.applicationContext, UNITY_GAME_ID, testMode,
            object : IUnityAdsInitializationListener {
                override fun onInitializationComplete() {
                    Log.d(TAG, "Unity Ads initialised")
                    preloadAll()
                }
                override fun onInitializationFailed(
                    error: UnityAds.UnityAdsInitializationError,
                    message: String,
                ) {
                    Log.w(TAG, "Unity Ads init failed: $error — $message")
                }
            })
    }

    private fun preloadAll() {
        if (ads().placements.interstitialEnabled) preloadInterstitial()
        if (ads().placements.rewardedEnabled)     preloadRewarded()
        // Banner loads on demand inside ReelzBannerAd composable — no pre-load needed.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Interstitial
    // ─────────────────────────────────────────────────────────────────────────

    private fun preloadInterstitial() {
        if (!adsEnabled() || !ads().placements.interstitialEnabled) return
        val id = interstitialAdUnitId()

        UnityAds.load(id, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String) {
                Log.d(TAG, "Interstitial loaded: $placementId")
                isInterstitialReady = true
            }
            override fun onUnityAdsFailedToLoad(
                placementId: String,
                error: UnityAds.UnityAdsLoadError,
                message: String,
            ) {
                Log.w(TAG, "Interstitial load failed: $error — $message")
                isInterstitialReady = false
                scope.launch {
                    delay(ads().frequency.retryDelayMs)
                    preloadInterstitial()
                }
            }
        })
    }

    fun setActivity(activity: Activity) {
        cachedActivity = activity
        if (!isRewardedReady) preloadRewarded()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rewarded
    // ─────────────────────────────────────────────────────────────────────────

    private fun preloadRewarded() {
        if (!adsEnabled() || !ads().placements.rewardedEnabled) return
        val id = rewardedAdUnitId()

        UnityAds.load(id, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String) {
                Log.d(TAG, "Rewarded loaded: $placementId")
                isRewardedReady = true
            }
            override fun onUnityAdsFailedToLoad(
                placementId: String,
                error: UnityAds.UnityAdsLoadError,
                message: String,
            ) {
                Log.w(TAG, "Rewarded load failed: $error — $message")
                isRewardedReady = false
                scope.launch {
                    delay(ads().frequency.retryDelayMs)
                    preloadRewarded()
                }
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // App-open — Unity has no app-open format; these are no-ops.
    // ─────────────────────────────────────────────────────────────────────────

    fun onAppBackground() { backgroundedAtMs = System.currentTimeMillis() }
    fun onAppForeground(activity: Activity) { /* no-op: Unity has no app-open */ }
    fun showAppOpenIfReady(activity: Activity) { /* no-op */ }

    // ─────────────────────────────────────────────────────────────────────────
    // Interstitial — show
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
        if (!isInterstitialReady || !adsEnabled() || !ads().placements.interstitialEnabled) {
            onFailed(); return
        }
        val id = interstitialAdUnitId()
        isInterstitialReady = false
        recordInterstitialShown()

        UnityAds.show(activity, id, UnityAdsShowOptions(),
            object : IUnityAdsShowListener {
                override fun onUnityAdsShowComplete(
                    placementId: String,
                    state: UnityAds.UnityAdsShowCompletionState,
                ) {
                    onDismissed()
                    preloadInterstitial()
                }
                override fun onUnityAdsShowFailure(
                    placementId: String,
                    error: UnityAds.UnityAdsShowError,
                    message: String,
                ) {
                    Log.w(TAG, "Interstitial show failed: $error — $message")
                    onFailed()
                    preloadInterstitial()
                }
                override fun onUnityAdsShowStart(placementId: String)  {}
                override fun onUnityAdsShowClick(placementId: String)  {}
            })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rewarded — show
    // ─────────────────────────────────────────────────────────────────────────

    fun showRewarded(activity: Activity, onRewarded: () -> Unit, onSkipped: () -> Unit) {
        if (!isRewardedReady || !adsEnabled() || !ads().placements.rewardedEnabled) {
            onSkipped(); return
        }
        val id = rewardedAdUnitId()
        isRewardedReady = false
        var earned = false

        UnityAds.show(activity, id, UnityAdsShowOptions(),
            object : IUnityAdsShowListener {
                override fun onUnityAdsShowComplete(
                    placementId: String,
                    state: UnityAds.UnityAdsShowCompletionState,
                ) {
                    // COMPLETED = user watched to end = rewarded
                    if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
                        earned = true
                    }
                    if (earned) onRewarded() else onSkipped()
                    preloadRewarded()
                }
                override fun onUnityAdsShowFailure(
                    placementId: String,
                    error: UnityAds.UnityAdsShowError,
                    message: String,
                ) {
                    Log.w(TAG, "Rewarded show failed: $error — $message")
                    onSkipped()
                    preloadRewarded()
                }
                override fun onUnityAdsShowStart(placementId: String)  {}
                override fun onUnityAdsShowClick(placementId: String)  {}
            })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Banner helpers (used by ReelzBannerAd composable)
    // ─────────────────────────────────────────────────────────────────────────

    fun bannerAdUnitIdOrNull(): String? {
        if (!adsEnabled() || !ads().placements.bannerEnabled) return null
        return bannerAdUnitId()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Native — Unity has no native format; always returns null/Failed.
    // Composables (HeroBannerAd, NativeAdCard) silently collapse on Failed.
    // ─────────────────────────────────────────────────────────────────────────

    fun nativeAdUnitIdOrNull(): String? = null   // no Unity native

    fun shouldShowCardAdAtRow(rowIndex: Int): Boolean = false  // native not available

    fun loadNativeAd(onLoaded: (NativeAdState.Loaded) -> Unit, onFailed: () -> Unit) {
        scope.launch(Dispatchers.Main) { onFailed() }  // native not available
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAST / Preroll — stubbed; Unity has no VAST/IMA support.
    // ─────────────────────────────────────────────────────────────────────────

    fun vastTagUrlOrNull(): String? = null  // not supported

    fun prerollConfig() = AdPrerollConfig(
        skipOnResume        = true,
        skipOnQualitySwitch = true,
        showOnMoviesOnly    = false,
        minMinutesBetween   = 30L,
    )

    fun midRollSchedule(durationMs: Long): MidRollSchedule =
        MidRollSchedule(false, emptyList())   // not supported

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
        scope.launch(Dispatchers.IO) {
            appPrefs.setLastInterstitialTimeMs(_lastInterstitialTimeMs)
        }
    }
}
