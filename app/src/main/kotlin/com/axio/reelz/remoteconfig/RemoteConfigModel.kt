package com.axio.reelz.remoteconfig

import com.google.gson.annotations.SerializedName

// ── Fully decoded config ──────────────────────────────────────────────────────

data class RemoteConfig(
    val meta: MetaConfig                                         = MetaConfig(),
    val tmdb: TmdbConfig?                                        = null,
    val ads: AdsConfig?                                          = null,
    @SerializedName("feature_flags") val featureFlags: FeatureFlags = FeatureFlags(),
    val shorts: ShortsConfig                                     = ShortsConfig(),
    val tiers: TiersConfig                                       = TiersConfig(),
    val premium: PremiumConfig                                   = PremiumConfig(),
    val backend: BackendConfig                                   = BackendConfig(),
)

/**
 * Backend connection config — comes from config.json, never hardcoded in the app.
 *
 * app_token: shared secret sent as X-Reelz-Token header on every backend request.
 * Rotate via remote config if abused — no app update needed.
 */
data class BackendConfig(
    @SerializedName("backend_url") val backendUrl: String = "",
    @SerializedName("stream_url")  val streamUrl: String  = "",
    @SerializedName("shorts_url")  val shortsUrl: String  = "",
    @SerializedName("app_token")   val appToken: String   = "",  // X-Reelz-Token
) {
    val normalizedUrl: String
        get() {
            val trimmed = backendUrl.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""
            return if (trimmed.startsWith("http://", ignoreCase = true) ||
                       trimmed.startsWith("https://", ignoreCase = true)
            ) trimmed else "https://$trimmed"
        }
}

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
    @SerializedName("max_per_session")            val maxPerSession: Int           = 10,
    @SerializedName("min_actions_between")        val minActionsBetween: Int       = 3,
)

data class AdPrerollConfig(
    val enabled: Boolean = false,
    @SerializedName("skip_after_ms")  val skipAfterMs: Int  = 5000,
    @SerializedName("vast_tag_url")   val vastTagUrl: String = "",
)

data class AdNetwork(
    val name: String      = "",
    val enabled: Boolean  = false,
    @SerializedName("banner_id")       val bannerId: String      = "",
    @SerializedName("interstitial_id") val interstitialId: String = "",
    @SerializedName("rewarded_id")     val rewardedId: String    = "",
    @SerializedName("native_id")       val nativeId: String      = "",
    @SerializedName("app_open_id")     val appOpenId: String     = "",
)

data class FeatureFlags(
    @SerializedName("ads_enabled")       val adsEnabled: Boolean       = false,
    @SerializedName("shorts_enabled")    val shortsEnabled: Boolean     = true,
    @SerializedName("transfer_enabled")  val transferEnabled: Boolean   = true,
    @SerializedName("download_enabled")  val downloadEnabled: Boolean   = true,
    @SerializedName("update_enabled")    val updateEnabled: Boolean     = true,
    @SerializedName("premium_enabled")   val premiumEnabled: Boolean    = true,
    @SerializedName("maintenance_mode")  val maintenanceMode: Boolean   = false,
    @SerializedName("maintenance_message") val maintenanceMessage: String = "We're under maintenance. Back shortly!",
)

data class ShortsConfig(
    @SerializedName("for_you_items")   val forYouItems: List<String>    = emptyList(),
    val categories: List<ShortsCategory> = emptyList(),
    @SerializedName("max_duration_s")  val maxDurationS: Int            = 60,
    @SerializedName("min_score")       val minScore: Int                = 100,
    @SerializedName("cache_ttl_ms")    val cacheTtlMs: Long             = 900_000L,
)

data class ShortsCategory(
    val label: String          = "",
    val items: List<String>    = emptyList(),
)

data class TiersConfig(
    val free: TierDef    = TierDef(),
    val premium: TierDef = TierDef(),
)

data class TierDef(
    @SerializedName("max_downloads")     val maxDownloads: Int      = 3,
    @SerializedName("max_quality")       val maxQuality: String     = "720p",
    @SerializedName("ads_enabled")       val adsEnabled: Boolean    = true,
    @SerializedName("offline_enabled")   val offlineEnabled: Boolean = false,
    @SerializedName("transfer_enabled")  val transferEnabled: Boolean = false,
)

data class PremiumConfig(
    @SerializedName("paystack_monthly_url") val paystackMonthlyUrl: String = "",
    @SerializedName("paystack_yearly_url")  val paystackYearlyUrl: String  = "",
    @SerializedName("monthly_price_ngn")    val monthlyPriceNgn: Int       = 1500,
    @SerializedName("yearly_price_ngn")     val yearlyPriceNgn: Int        = 12000,
    @SerializedName("manual_grants")        val manualGrants: List<String> = emptyList(),
)
