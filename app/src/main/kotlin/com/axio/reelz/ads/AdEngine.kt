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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AdEngine"

// ─────────────────────────────────────────────────────────────────────────────
// Unity Ads IDs — backend DTO fields take priority when non-blank so you can
// override remotely without a new release.
// ─────────────────────────────────────────────────────────────────────────────
private const val UNITY_GAME_ID         = "800380914"
private const val UNITY_BANNER_ID       = "BP_Banner_Android"
private const val UNITY_INTERSTITIAL_ID = "BP_Interstitial_Android"
private const val UNITY_REWARDED_ID     = "BP_Rewarded_Android"

// ─────────────────────────────────────────────────────────────────────────────
// Native ad state — kept for NativeAdCard / HeroBannerAd composables.
// Unity has no native format; composables silently collapse via Failed.
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

data class MidRollSchedule(
    val shouldInsert: Boolean,
    val breakpointsMs: List<Long>,
)

// ─────────────────────────────────────────────────────────────────────────────
// AdEngine — Unity Ads (banner · interstitial · rewarded)
// Unsupported formats (native, app-open, preroll) are no-op stubs.
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class AdEngine @Inject constructor(
    private val configRepo: ConfigRepository,
    private val sessionRepo: UserRepository,
    private val appPrefs: com.axio.reelz.core.preferences.AppPreferencesStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Session counters ─────────────────────────────────────────────────────
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

    // ── Ready flags ──────────────────────────────────────────────────────────
    var isInterstitialReady: Boolean = false; private set
    var isRewardedReady: Boolean     = false; private set
    val isAppOpenReady: Boolean      = false   // Unity has no app-open format

    private var cachedActivity: Activity? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Config helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun ads() = configRepo.adsConfig()

    fun adsEnabled(): Boolean = configRepo.areAdsEnabled(isPremiumUser = sessionRepo.isPremium)

    fun shouldShowRemoveAdsBanner(): Boolean = adsEnabled()

    private fun bannerAdUnitId()       = ads().bannerId.takeIf { it.isNotBlank() } ?: UNITY_BANNER_ID
    private fun interstitialAdUnitId() = ads().interstitialId.takeIf { it.isNotBlank() } ?: UNITY_INTERSTITIAL_ID
    private fun rewardedAdUnitId()     = ads().rewardedId.takeIf { it.isNotBlank() } ?: UNITY_REWARDED_ID

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        if (!adsEnabled()) { Log.d(TAG, "Ads disabled — skip init"); return }

        UnityAds.initialize(
            context.applicationContext,
            UNITY_GAME_ID,
            false,   // testMode — flip to true during development
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
            }
        )
    }

    private fun preloadAll() {
        if (ads().placements.interstitialEnabled) preloadInterstitial()
        if (ads().placements.rewardedEnabled)     preloadRewarded()
        // Banner loads on-demand inside ReelzBannerAd — no pre-load needed.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Interstitial
    // ─────────────────────────────────────────────────────────────────────────

    private fun preloadInterstitial() {
        if (!adsEnabled() || !ads().placements.interstitialEnabled) return
        UnityAds.load(interstitialAdUnitId(), object : IUnityAdsLoadListener {
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
                scope.launch { delay(ads().frequency.retryDelayMs); preloadInterstitial() }
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
        UnityAds.load(rewardedAdUnitId(), object : IUnityAdsLoadListener {
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
                scope.launch { delay(ads().frequency.retryDelayMs); preloadRewarded() }
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // App-open — Unity has no app-open format, no-ops.
    // ─────────────────────────────────────────────────────────────────────────

    fun onAppBackground() { backgroundedAtMs = System.currentTimeMillis() }
    fun onAppForeground(activity: Activity) { /* no-op */ }
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
        isInterstitialReady = false
        recordInterstitialShown()
        UnityAds.show(activity, interstitialAdUnitId(), UnityAdsShowOptions(),
            object : IUnityAdsShowListener {
                override fun onUnityAdsShowComplete(
                    placementId: String,
                    state: UnityAds.UnityAdsShowCompletionState,
                ) { onDismissed(); preloadInterstitial() }
                override fun onUnityAdsShowFailure(
                    placementId: String,
                    error: UnityAds.UnityAdsShowError,
                    message: String,
                ) { onFailed(); preloadInterstitial() }
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
        isRewardedReady = false
        var earned = false
        UnityAds.show(activity, rewardedAdUnitId(), UnityAdsShowOptions(),
            object : IUnityAdsShowListener {
                override fun onUnityAdsShowComplete(
                    placementId: String,
                    state: UnityAds.UnityAdsShowCompletionState,
                ) {
                    if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) earned = true
                    if (earned) onRewarded() else onSkipped()
                    preloadRewarded()
                }
                override fun onUnityAdsShowFailure(
                    placementId: String,
                    error: UnityAds.UnityAdsShowError,
                    message: String,
                ) { onSkipped(); preloadRewarded() }
                override fun onUnityAdsShowStart(placementId: String)  {}
                override fun onUnityAdsShowClick(placementId: String)  {}
            })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Banner helpers (used by ReelzBannerAd)
    // ─────────────────────────────────────────────────────────────────────────

    fun bannerAdUnitIdOrNull(): String? {
        if (!adsEnabled() || !ads().placements.bannerEnabled) return null
        return bannerAdUnitId()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Native — Unity has no native format; always returns null/Failed.
    // ─────────────────────────────────────────────────────────────────────────

    fun nativeAdUnitIdOrNull(): String? = null
    fun shouldShowCardAdAtRow(rowIndex: Int): Boolean = false
    fun loadNativeAd(onLoaded: (NativeAdState.Loaded) -> Unit, onFailed: () -> Unit) {
        scope.launch(Dispatchers.Main) { onFailed() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Preroll / VAST — not supported by Unity Ads.
    // ─────────────────────────────────────────────────────────────────────────

    fun vastTagUrlOrNull(): String? = null
    fun prerollConfig() = AdPrerollConfig(
        skipOnResume        = true,
        skipOnQualitySwitch = true,
        showOnMoviesOnly    = false,
        minMinutesBetween   = 30L,
    )
    fun midRollSchedule(durationMs: Long): MidRollSchedule = MidRollSchedule(false, emptyList())

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
