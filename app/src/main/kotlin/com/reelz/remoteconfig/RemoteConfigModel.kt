package com.reelz.remoteconfig

import com.google.gson.annotations.SerializedName

// ── Fully decoded config ──────────────────────────────────────────────────────

data class RemoteConfig(
    val meta: MetaConfig                                        = MetaConfig(),
    val tmdb: TmdbConfig?                                       = null,
    val subtitles: SubtitlesConfig?                             = null,
    val ads: AdsConfig?                                         = null,
    @SerializedName("stream_sources") val streamSources: List<StreamSourceConfig> = emptyList(),
    @SerializedName("user_agents")    val userAgents: UserAgentsConfig             = UserAgentsConfig(),
    val scanner: ScannerConfig                                  = ScannerConfig(),
    @SerializedName("feature_flags") val featureFlags: FeatureFlags               = FeatureFlags(),
    val shorts: ShortsConfig                                    = ShortsConfig(),
    val tiers: TiersConfig                                      = TiersConfig(),
    val premium: PremiumConfig                                  = PremiumConfig(),
    val backend: BackendConfig                                  = BackendConfig(),
)

/**
 * Backend connection config — comes from config.json, never hardcoded in the app.
 * The app reads only backend_url from here; all secrets live server-side only.
 */
data class BackendConfig(
    @SerializedName("backend_url") val backendUrl: String = "",
)

data class MetaConfig(
    @SerializedName("schema_version")     val schemaVersion: Int    = 1,
    @SerializedName("config_version")     val configVersion: Int    = 1,
    @SerializedName("min_app_version")    val minAppVersion: Int    = 1,
    @SerializedName("latest_app_version") val latestAppVersion: Int = 1,
    @SerializedName("latest_apk_url")     val latestApkUrl: String  = "",
    val changelog: String = "",
)

data class TmdbConfig(
    val keys: List<ApiKey> = emptyList(),
    @SerializedName("base_url")     val baseUrl: String     = "https://api.themoviedb.org/3",
    @SerializedName("img_w500")     val imgW500: String     = "https://image.tmdb.org/t/p/w500",
    @SerializedName("img_w342")     val imgW342: String     = "https://image.tmdb.org/t/p/w342",
    @SerializedName("img_original") val imgOriginal: String = "https://image.tmdb.org/t/p/original",
)

data class ApiKey(
    val id: String      = "",
    val key: String     = "",
    val weight: Int     = 10,
    val enabled: Boolean = true,
)

data class SubtitlesConfig(
    val providers: List<SubtitleProvider> = emptyList(),
)

data class SubtitleProvider(
    val id: String        = "",
    val name: String      = "",
    val enabled: Boolean  = false,
    val keys: List<ApiKey> = emptyList(),
    @SerializedName("base_url")    val baseUrl: String   = "",
    @SerializedName("user_agent")  val userAgent: String = "",
    @SerializedName("daily_limit") val dailyLimit: Int   = 20,
)

data class AdsConfig(
    val enabled: Boolean = false,
    @SerializedName("applovin_sdk_key")   val applovinSdkKey: String      = "",
    @SerializedName("mediation_provider") val mediationProvider: String   = "max",
    val placements: AdPlacements                                          = AdPlacements(),
    @SerializedName("interstitial_frequency") val interstitialFrequency: AdInterstitialFrequency = AdInterstitialFrequency(),
    val preroll: AdPrerollConfig                                          = AdPrerollConfig(),
    val networks: List<AdNetwork> = emptyList(),
)

data class AdPlacements(
    @SerializedName("banner_enabled")       val bannerEnabled: Boolean       = true,
    @SerializedName("interstitial_enabled") val interstitialEnabled: Boolean = true,
    @SerializedName("rewarded_enabled")     val rewardedEnabled: Boolean     = true,
    @SerializedName("native_enabled")       val nativeEnabled: Boolean       = true,
    @SerializedName("app_open_enabled")     val appOpenEnabled: Boolean      = true,
    @SerializedName("preroll_enabled")      val prerollEnabled: Boolean      = true,
)

data class AdInterstitialFrequency(
    @SerializedName("min_ms_between")             val minMsBetween: Long           = 180_000L,
    @SerializedName("max_per_session")            val maxPerSession: Int           = 6,
    @SerializedName("content_opens_before_first") val contentOpensBeforeFirst: Int = 2,
    @SerializedName("every_n_plays")              val everyNPlays: Int             = 2,
    @SerializedName("retry_delay_ms")             val retryDelayMs: Long           = 30_000L,
)

data class AdPrerollConfig(
    @SerializedName("show_on_movies_only")    val showOnMoviesOnly: Boolean    = true,
    @SerializedName("min_minutes_between")    val minMinutesBetween: Long      = 30,
    @SerializedName("skip_on_resume")         val skipOnResume: Boolean        = true,
    @SerializedName("skip_on_quality_switch") val skipOnQualitySwitch: Boolean = true,
)

data class AdNetwork(
    val id: String       = "",
    val enabled: Boolean = false,
    @SerializedName("banner_id")       val bannerId: String       = "",
    @SerializedName("interstitial_id") val interstitialId: String = "",
    @SerializedName("rewarded_id")     val rewardedId: String     = "",
    @SerializedName("native_id")       val nativeId: String       = "",
    @SerializedName("app_open_id")     val appOpenId: String      = "",
    @SerializedName("vast_tag_url")    val vastTagUrl: String     = "",
)

data class StreamSourceConfig(
    val id: String       = "",
    val name: String     = "",
    val enabled: Boolean = false,
    val priority: Int    = 99,
    @SerializedName("requires_js")  val requiresJs: Boolean  = false,
    @SerializedName("url_patterns") val urlPatterns: UrlPatterns = UrlPatterns(),
    val headers: Map<String, String> = emptyMap(),
    val referer: String = "",
    val origin: String  = "",
)

data class UrlPatterns(
    val movie: String = "",
    val tv: String    = "",
)

data class UserAgentsConfig(
    @SerializedName("chrome_android")  val chromeAndroid: String  = "",
    @SerializedName("chrome_desktop")  val chromeDesktop: String  = "",
    @SerializedName("firefox_android") val firefoxAndroid: String = "",
)

data class ScannerConfig(
    @SerializedName("direct_timeout_ms")  val directTimeoutMs: Long  = 2000,
    @SerializedName("webview_timeout_ms") val webviewTimeoutMs: Long = 18000,
    @SerializedName("global_timeout_ms")  val globalTimeoutMs: Long  = 25000,
    @SerializedName("stagger_ms")         val staggerMs: Long        = 150,
    @SerializedName("m3u8_pattern")       val m3u8Pattern: String    = "",
    @SerializedName("mp4_pattern")        val mp4Pattern: String     = "",
)

data class FeatureFlags(
    @SerializedName("subtitles_enabled")   val subtitlesEnabled: Boolean   = true,
    @SerializedName("downloads_enabled")   val downloadsEnabled: Boolean   = true,
    @SerializedName("transfer_enabled")    val transferEnabled: Boolean    = true,
    @SerializedName("shorts_enabled")      val shortsEnabled: Boolean      = true,
    @SerializedName("ads_enabled")         val adsEnabled: Boolean         = true,
    @SerializedName("force_maintenance")   val forceMaintenance: Boolean   = false,
    @SerializedName("maintenance_message") val maintenanceMessage: String  = "",
)

// ── Shorts / discovery feed config ────────────────────────────────────────────

data class ShortsConfig(
    @SerializedName("feed_base_url")     val feedBaseUrl: String             = "",
    @SerializedName("feed_referer")      val feedReferer: String             = "",
    @SerializedName("feed_origin")       val feedOrigin: String              = "",
    @SerializedName("for_you_subs")      val forYouSubs: String              = "",
    val categories: List<ShortCategory>                                      = emptyList(),
)

data class ShortCategory(
    val label: String = "",
    val subs: String  = "",
)

// ── Premium tiers ──────────────────────────────────────────────────────────

data class TiersConfig(
    val free: TierConfig    = TierConfig(maxResolutionHeight = 480),
    val premium: TierConfig = TierConfig(
        maxResolution = "4K", maxResolutionHeight = 2160, maxDownloads = -1,
        adsEnabled = false, subtitlesManualSearch = true, backgroundPlay = true,
        simultaneousStreams = 2,
    ),
)

data class TierConfig(
    @SerializedName("max_resolution")          val maxResolution: String         = "480p",
    @SerializedName("max_resolution_height")   val maxResolutionHeight: Int       = 480,
    /** -1 is the sentinel for unlimited. Never trips the cap. */
    @SerializedName("max_downloads")           val maxDownloads: Int              = 5,
    @SerializedName("ads_enabled")             val adsEnabled: Boolean            = true,
    @SerializedName("subtitles_manual_search") val subtitlesManualSearch: Boolean = false,
    @SerializedName("background_play")         val backgroundPlay: Boolean        = false,
    @SerializedName("simultaneous_streams")    val simultaneousStreams: Int       = 1,
)

data class PremiumConfig(
    val enabled: Boolean                                                       = false,
    @SerializedName("grace_period_days")        val gracePeriodDays: Int        = 1,
    @SerializedName("renew_warning_days_before") val renewWarningDaysBefore: Int = 3,
    @SerializedName("monthly_price_ngn")         val monthlyPriceNgn: Long       = 0,
    @SerializedName("yearly_price_ngn")          val yearlyPriceNgn: Long        = 0,
    /**
     * Paystack Payment Page / Payment Link URLs (e.g. https://paystack.com/pay/your-link),
     * one per plan. Created from the Paystack dashboard — Payments → Payment Pages —
     * no backend or server-side integration required for this no-backend v1 app.
     * Opened in the in-app browser sheet (ReelzBrowserSheet) already used for ad clicks.
     * Left blank, the matching button on PremiumScreen disables itself with a
     * "Subscriptions opening soon" message instead of crashing or opening nothing.
     */
    @SerializedName("paystack_monthly_url") val paystackMonthlyUrl: String      = "",
    @SerializedName("paystack_yearly_url")  val paystackYearlyUrl: String       = "",
    /** Shown under the Paystack buttons — e.g. "Payments are processed securely by Paystack". */
    @SerializedName("payment_note")         val paymentNote: String             = "",
    /**
     * Legacy free-text contact line (was the WhatsApp-era field). No longer rendered
     * by PremiumScreen — kept only so older cached config blobs deserialize cleanly
     * during the transition and the field is never silently dropped from history.
     */
    @SerializedName("contact_to_subscribe") val contactToSubscribe: String      = "",
    /**
     * V1 grant mechanism: no backend, no Firebase. You (the dev) add a row here by
     * email after confirming payment in the Paystack dashboard. The app only ever
     * READS this list — matched case-insensitively against the signed-in Google
     * email. Swap this for a Firebase/Paystack-webhook-backed source later without
     * touching any other file: just provide a different UserSessionRepository.SessionSource.
     */
    @SerializedName("manual_grants") val manualGrants: List<ManualGrant>        = emptyList(),
)

data class ManualGrant(
    val email: String                                 = "",
    val plan: String                                  = "",
    @SerializedName("expires_at_ms") val expiresAtMs: Long = 0L,
    val note: String                                  = "",
)
