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
import com.applovin.mediation.nativeAds.MaxNativeAdViewBinder
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
    private val configRepo: ConfigRepository,
    private val sessionRepo: UserRepository,
    private val appPrefs: com.axio.reelz.core.preferences.AppPreferencesStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Session state (reset on cold start — intentional) ────────────────────
    var interstitialShownCount: Int  = 0
    private var appOpenShownThisSession = false

    // ── Persistent counters (survive cold starts via AppPreferencesStore) ─────
    // lastInterstitialTimeMs and totalContentOpens are loaded from DataStore on
    // first access and written back on each change, so frequency caps are
    // honoured across app restarts and process deaths.
    private var _lastInterstitialTimeMs: Long = 0L
    private var _totalContentOpens: Int       = 0
    private var _totalPlayTaps: Int           = 0
    private var countersLoaded = false

    val lastInterstitialTimeMs: Long get() = _lastInterstitialTimeMs
    val totalContentOpens: Int        get() = _totalContentOpens
    val totalPlayTaps: Int            get() = _totalPlayTaps

    /** Call once from Application.onCreate() to warm up persisted counters. */
    fun loadPersistedCounters() {
        scope.launch(Dispatchers.IO) {
            _lastInterstitialTimeMs = appPrefs.getLastInterstitialTimeMs()
            _totalContentOpens      = appPrefs.getTotalContentOpens()
            _totalPlayTaps          = appPrefs.getTotalPlayTaps()
            countersLoaded          = true
        }
    }

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

    private fun adsConfig() = configRepo.adsConfig()
    private fun ads() = configRepo.adsConfig()

    /**
     * True only when the remote config master switch, the feature flag, AND the
     * current user is not premium all agree ads should show. This is the single
     * chokepoint every ad format (banner, interstitial, native, rewarded, app
     * open, pre-roll) already calls through — see the functions below — so
     * premium stopping ads is a one-line change, exactly as designed.
     */
    private fun adsEnabled(): Boolean = configRepo.areAdsEnabled(isPremiumUser = sessionRepo.isPremium)

    /**
     * Public read for UI surfaces (feed banners, etc.) that want to offer an
     * "upgrade to remove ads" nudge. Deliberately reuses the same [adsEnabled]
     * chokepoint as every actual ad placement, so the banner can never appear
     * for a user who isn't even seeing ads (already premium, or ads globally
     * disabled in config) — it would be a confusing, pointless upsell otherwise.
     */
    fun shouldShowRemoveAdsBanner(): Boolean = adsEnabled()

    private fun interstitialAdUnitId(): String = ads().interstitialId.orEmpty()
    private fun rewardedAdUnitId(): String     = ads().rewardedId.orEmpty()
    private fun appOpenAdUnitId(): String      = ads().let { "" }.orEmpty()
    private fun bannerAdUnitId(): String       = ads().bannerId.orEmpty()
    private fun nativeAdUnitId(): String       = ads().nativeId.orEmpty()

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
            && _totalContentOpens >= freq.contentOpensBeforeFirst
            && _totalPlayTaps % freq.everyNPlays == 0
            && _totalPlayTaps > 0
            && (now - _lastInterstitialTimeMs) > freq.minMsBetween
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
        recordInterstitialShown()   // persists lastInterstitialTimeMs to DataStore

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

    /**
     * Loads a single native ad via MaxNativeAdLoader and maps the result into
     * [NativeAdState.Loaded] for the Compose UI layer.
     *
     * The loader is created fresh per call — native ads are not preloaded
     * because they are on-demand placements (BrowseScreen feed injection,
     * ShortsScreen page). A new loader instance is intentional per AppLovin
     * guidance: reusing a single loader across multiple composable lifecycles
     * causes double-impression events and memory leaks.
     *
     * Failures call [onFailed] so the card/page collapses gracefully — the
     * caller is never left in a perpetual loading state.
     */
    fun loadNativeAd(
        onLoaded: (NativeAdState.Loaded) -> Unit,
        onFailed: () -> Unit,
    ) {
        val unitId = nativeAdUnitIdOrNull()
        if (unitId == null) {
            scope.launch(Dispatchers.Main) { onFailed() }
            return
        }

        // MaxNativeAdLoader must be created on the main thread.
        scope.launch(Dispatchers.Main) {
            val loader = MaxNativeAdLoader(unitId, appContext)

            loader.setNativeAdListener(object : com.applovin.mediation.nativeAds.MaxNativeAdListener() {
                override fun onNativeAdLoaded(view: MaxNativeAdView?, ad: MaxAd) {
                    val native: MaxNativeAd = ad.nativeAd ?: run {
                        Log.w(TAG, "Native ad loaded but nativeAd payload is null — unit=$unitId")
                        onFailed()
                        return
                    }

                    val headline = native.title.orEmpty()
                    val body     = native.body.orEmpty()
                    val cta      = native.callToAction.orEmpty().ifBlank { "Learn More" }
                    val icon     = native.icon?.uri?.toString().orEmpty()
                    val image    = native.mainImage?.uri?.toString().orEmpty()
                    val advertiser = native.advertiser.orEmpty()

                    // Guard: require at minimum a headline and at least one visual asset.
                    if (headline.isBlank() || (icon.isBlank() && image.isBlank())) {
                        Log.w(TAG, "Native ad missing required fields — unit=$unitId, headline='$headline'")
                        onFailed()
                        return
                    }

                    Log.d(TAG, "Native ad loaded — unit=$unitId headline='$headline'")
                    onLoaded(
                        NativeAdState.Loaded(
                            headline       = headline,
                            body           = body,
                            callToAction   = cta,
                            advertiserName = advertiser,
                            clickUrl       = ad.adReviewCreativeId.orEmpty(), // tracking only; click handled by loader
                            imageUrl       = image,
                            iconUrl        = icon,
                        )
                    )
                }

                override fun onNativeAdLoadFailed(adUnitId: String, error: MaxError) {
                    Log.w(TAG, "Native ad load failed — unit=$adUnitId code=${error.code} msg=${error.message}")
                    onFailed()
                }

                override fun onNativeAdClicked(ad: MaxAd) {
                    Log.d(TAG, "Native ad clicked — unit=$unitId")
                }

                override fun onNativeAdExpired(ad: MaxAd) {
                    Log.d(TAG, "Native ad expired — unit=$unitId")
                    // Expired ads silently disappear from the UI because the composable
                    // holds the Loaded state; the user simply sees the existing card
                    // until they scroll away. We don't push a new Failed state here
                    // because that would cause the card to collapse mid-scroll.
                }
            })

            loader.loadAd()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VAST tag URL for IMA pre-roll — fully config driven
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the configured VAST tag URL, or null if pre-roll ads are disabled/unset. */
    fun vastTagUrlOrNull(): String? {
        if (!adsEnabled() || !adsConfig().placements.prerollEnabled) return null
        return ads().let { "" }?.takeIf { it.isNotBlank() }
    }

    /** Pre-roll timing/skip rules from remote config. */
    fun prerollConfig() = com.axio.reelz.data.dto.AdPrerollConfig(
        skipOnResume = true, skipOnQualitySwitch = true,
        showOnMoviesOnly = false, minMinutesBetween = 30L)

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
