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

data class BackendConfig(
    @SerializedName("backend_url") val backendUrl: String = "",
    @SerializedName("stream_url")  val streamUrl: String  = "",
    @SerializedName("shorts_url")  val shortsUrl: String  = "",
    // ── ADDED: shared secret sent as X-Reelz-Token on every backend request.
    // Rotate via config push without an app update.
    @SerializedName("app_token")   val appToken: String   = "",
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
    val id: String       = "",
    val key: String      = "",
    val weight: Int      = 10,
    val enabled: Boolean = true,
)

data class AdsConfig(
    val enabled: Boolean = false,
    @SerializedName("applovin_sdk_key")       val applovinSdkKey: String               = "",
    @SerializedName("mediation_provider")     val mediationProvider: String            = "max",
    val placements: AdPlacements                                                       = AdPlacements(),
    @SerializedName("interstitial_frequency") val interstitialFrequency: AdInterstitialFrequency = AdInterstitialFrequency(),
    val preroll: AdPrerollConfig                                                       = AdPrerollConfig(),
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

data class FeatureFlags(
    @SerializedName("subtitles_enabled")   val subtitlesEnabled: Boolean   = true,
    @SerializedName("downloads_enabled")   val downloadsEnabled: Boolean   = true,
    @SerializedName("transfer_enabled")    val transferEnabled: Boolean    = true,
    @SerializedName("shorts_enabled")      val shortsEnabled: Boolean      = true,
    @SerializedName("ads_enabled")         val adsEnabled: Boolean         = true,
    @SerializedName("force_maintenance")   val forceMaintenance: Boolean   = false,
    @SerializedName("maintenance_message") val maintenanceMessage: String  = "",
)

data class ShortsConfig(
    @SerializedName("archive_org")            val archiveOrg: ArchiveOrgConfig       = ArchiveOrgConfig(),
    @SerializedName("for_you_items")          val forYouItems: List<String>          = emptyList(),
    val categories: List<ShortCategory>                                              = emptyList(),
    @SerializedName("video_extensions")       val videoExtensions: List<String>      = listOf("mp4", "m4v", "mov", "webm"),
    @SerializedName("excluded_name_contains") val excludedNameContains: List<String> = listOf("thumb", "sample", ".ia.", "__ia_thumb"),
    @SerializedName("items_per_page")         val itemsPerPage: Int                  = 3,
)

data class ArchiveOrgConfig(
    @SerializedName("metadata_base_url")  val metadataBaseUrl: String  = "https://archive.org/metadata",
    @SerializedName("download_base_url")  val downloadBaseUrl: String  = "https://archive.org/download",
    @SerializedName("thumbnail_base_url") val thumbnailBaseUrl: String = "https://archive.org/services/img",
    @SerializedName("request_timeout_ms") val requestTimeoutMs: Long   = 12000,
)

data class ShortCategory(
    val label: String          = "",
    val items: List<String>    = emptyList(),
)

data class TiersConfig(
    val free: TierConfig    = TierConfig(
        maxResolutionHeight = -1,
        maxDownloadResolutionHeight = -1,
    ),
    val premium: TierConfig = TierConfig(
        maxResolution = "4K", maxResolutionHeight = -1, maxDownloads = -1,
        maxDownloadResolutionHeight = -1,
        adsEnabled = false, subtitlesManualSearch = true, backgroundPlay = true,
        simultaneousStreams = 2,
    ),
)

data class TierConfig(
    @SerializedName("max_resolution")                val maxResolution: String             = "Unlimited",
    @SerializedName("max_resolution_height")         val maxResolutionHeight: Int           = -1,
    @SerializedName("max_download_resolution_height") val maxDownloadResolutionHeight: Int  = -1,
    @SerializedName("max_downloads")                 val maxDownloads: Int                 = 5,
    @SerializedName("ads_enabled")                   val adsEnabled: Boolean               = true,
    @SerializedName("subtitles_manual_search")       val subtitlesManualSearch: Boolean    = false,
    @SerializedName("background_play")               val backgroundPlay: Boolean           = false,
    @SerializedName("simultaneous_streams")          val simultaneousStreams: Int          = 1,
)

data class PremiumConfig(
    val enabled: Boolean                                                        = false,
    @SerializedName("grace_period_days")         val gracePeriodDays: Int       = 1,
    @SerializedName("renew_warning_days_before") val renewWarningDaysBefore: Int = 3,
    @SerializedName("monthly_price_ngn")         val monthlyPriceNgn: Long      = 0,
    @SerializedName("yearly_price_ngn")          val yearlyPriceNgn: Long       = 0,
    @SerializedName("paystack_monthly_url")      val paystackMonthlyUrl: String  = "",
    @SerializedName("paystack_yearly_url")       val paystackYearlyUrl: String   = "",
    @SerializedName("payment_note")              val paymentNote: String         = "",
    @SerializedName("contact_to_subscribe")      val contactToSubscribe: String  = "",
    @SerializedName("manual_grants")             val manualGrants: List<ManualGrant> = emptyList(),
)

data class ManualGrant(
    val email: String                                  = "",
    val plan: String                                   = "",
    @SerializedName("expires_at_ms") val expiresAtMs: Long = 0L,
    val note: String                                   = "",
)
