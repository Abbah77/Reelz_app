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
import com.reelz.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// Frequency cap constants
// ─────────────────────────────────────────────────────────────────────────────

private const val MIN_MS_BETWEEN_INTERSTITIALS = 180_000L   // 3 minutes
private const val MAX_INTERSTITIALS_PER_SESSION = 6
private const val CONTENT_OPENS_BEFORE_FIRST_AD = 2
private const val INTERSTITIAL_EVERY_N_PLAYS     = 2
private const val RETRY_DELAY_MS                 = 30_000L

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
// AdEngine
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class AdEngine @Inject constructor() {

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
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        appContext = context.applicationContext
        AppLovinSdk.getInstance(appContext).apply {
            mediationProvider = "max"
            initializeSdk { _: AppLovinSdkConfiguration ->
                Log.d(TAG, "AppLovin SDK initialized")
                preloadAll()
            }
        }
    }

    private fun preloadAll() {
        preloadInterstitial()
        preloadRewarded()
        preloadAppOpen()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Preloaders
    // ─────────────────────────────────────────────────────────────────────────

    private fun preloadInterstitial() {
        val ad = MaxInterstitialAd(BuildConfig.AD_INTERSTITIAL_ID, appContext)
        ad.setListener(object : MaxAdListener {
            override fun onAdLoaded(a: MaxAd) {
                loadedInterstitial = ad
                isInterstitialReady = true
                Log.d(TAG, "Interstitial ready")
            }
            override fun onAdLoadFailed(id: String, err: MaxError) {
                isInterstitialReady = false
                Log.w(TAG, "Interstitial failed: ${err.message}, retrying in 30s")
                scope.launch { delay(RETRY_DELAY_MS); preloadInterstitial() }
            }
            override fun onAdDisplayed(a: MaxAd)       {}
            override fun onAdHidden(a: MaxAd)           { preloadInterstitial() }
            override fun onAdClicked(a: MaxAd)          {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError) {}
        })
        ad.loadAd()
    }

    private fun preloadRewarded() {
        val ad = MaxRewardedAd.getInstance(BuildConfig.AD_REWARDED_ID, appContext)
        ad.setListener(object : MaxRewardedAdListener {
            override fun onAdLoaded(a: MaxAd) {
                loadedRewarded = ad
                isRewardedReady = true
                Log.d(TAG, "Rewarded ready")
            }
            override fun onAdLoadFailed(id: String, err: MaxError) {
                isRewardedReady = false
                scope.launch { delay(RETRY_DELAY_MS); preloadRewarded() }
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
        val ad = MaxAppOpenAd(BuildConfig.AD_APP_OPEN_ID, appContext)
        ad.setListener(object : MaxAdListener {
            override fun onAdLoaded(a: MaxAd) {
                loadedAppOpen = ad
                isAppOpenReady = true
                Log.d(TAG, "App open ready")
            }
            override fun onAdLoadFailed(id: String, err: MaxError) {
                isAppOpenReady = false
                scope.launch { delay(RETRY_DELAY_MS); preloadAppOpen() }
            }
            override fun onAdDisplayed(a: MaxAd)                    {}
            override fun onAdHidden(a: MaxAd)                       { isAppOpenReady = false; preloadAppOpen() }
            override fun onAdClicked(a: MaxAd)                      {}
            override fun onAdDisplayFailed(a: MaxAd, e: MaxError)   {}
        })
        ad.loadAd()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frequency cap gate
    // ─────────────────────────────────────────────────────────────────────────

    fun shouldShowInterstitial(): Boolean {
        val now = System.currentTimeMillis()
        return isInterstitialReady
            && totalContentOpens >= CONTENT_OPENS_BEFORE_FIRST_AD
            && totalPlayTaps % INTERSTITIAL_EVERY_N_PLAYS == 0
            && totalPlayTaps > 0
            && (now - lastInterstitialTimeMs) > MIN_MS_BETWEEN_INTERSTITIALS
            && interstitialShownCount < MAX_INTERSTITIALS_PER_SESSION
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
        if (ad == null || !isInterstitialReady) { onFailed(); return }

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
        if (ad == null || !isRewardedReady) { onSkipped(); return }

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
        ad.showAd(activity)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Native ad loader (on-demand, not preloaded)
    // ─────────────────────────────────────────────────────────────────────────

    fun loadNativeAd(
        onLoaded: (NativeAdState.Loaded) -> Unit,
        onFailed: () -> Unit,
    ) {
        // AppLovin MAX native ads use MaxNativeAdLoader.
        // Implementation stub — wire in your MaxNativeAdLoader here.
        // The NativeAdState.Loaded data class carries everything the composable needs.
        // For now, emit failure so the card collapses gracefully.
        scope.launch {
            delay(200) // simulate async
            onFailed()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAST tag URL for IMA pre-roll
    // ─────────────────────────────────────────────────────────────────────────

    fun getVastTagUrl(): String = BuildConfig.AD_VAST_TAG_URL

    // ─────────────────────────────────────────────────────────────────────────
    // Counters
    // ─────────────────────────────────────────────────────────────────────────

    fun incrementContentOpen() { totalContentOpens++ }

    fun incrementPlayTap() { totalPlayTaps++ }
}
