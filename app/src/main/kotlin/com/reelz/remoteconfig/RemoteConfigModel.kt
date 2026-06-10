package com.reelz.remoteconfig

import com.google.gson.annotations.SerializedName

// ── Top-level envelope ────────────────────────────────────────────────────────

/** The raw JSON envelope: { "v": 1, "d": "<base64>" }  */
data class EncryptedConfigEnvelope(
    @SerializedName("v") val version: Int,
    @SerializedName("d") val data: String,
)

// ── Fully decoded config ──────────────────────────────────────────────────────

data class RemoteConfig(
    val meta: MetaConfig,
    val tmdb: TmdbConfig,
    val subtitles: SubtitlesConfig,
    val ads: AdsConfig,
    @SerializedName("stream_sources") val streamSources: List<StreamSourceConfig>,
    @SerializedName("user_agents")    val userAgents: UserAgentsConfig,
    val scanner: ScannerConfig,
    @SerializedName("feature_flags") val featureFlags: FeatureFlags,
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
    val keys: List<ApiKey>,
    @SerializedName("base_url")     val baseUrl: String     = "https://api.themoviedb.org/3",
    @SerializedName("img_w500")     val imgW500: String     = "https://image.tmdb.org/t/p/w500",
    @SerializedName("img_w342")     val imgW342: String     = "https://image.tmdb.org/t/p/w342",
    @SerializedName("img_original") val imgOriginal: String = "https://image.tmdb.org/t/p/original",
)

data class ApiKey(
    val id: String,
    val key: String,
    val weight: Int     = 10,
    val enabled: Boolean = true,
)

data class SubtitlesConfig(
    val providers: List<SubtitleProvider>,
)

data class SubtitleProvider(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val keys: List<ApiKey>,
    @SerializedName("base_url")    val baseUrl: String   = "",
    @SerializedName("user_agent")  val userAgent: String = "",
    @SerializedName("daily_limit") val dailyLimit: Int   = 20,
)

data class AdsConfig(
    val enabled: Boolean,
    val networks: List<AdNetwork>,
)

data class AdNetwork(
    val id: String,
    val enabled: Boolean,
    @SerializedName("banner_id")       val bannerId: String       = "",
    @SerializedName("interstitial_id") val interstitialId: String = "",
    @SerializedName("rewarded_id")     val rewardedId: String     = "",
    @SerializedName("native_id")       val nativeId: String       = "",
)

data class StreamSourceConfig(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    @SerializedName("requires_js")  val requiresJs: Boolean,
    @SerializedName("url_patterns") val urlPatterns: UrlPatterns,
    val headers: Map<String, String> = emptyMap(),
    val referer: String = "",
    val origin: String  = "",
)

data class UrlPatterns(
    val movie: String,
    val tv: String,
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
