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
    val networks: List<AdNetwork> = emptyList(),
)

data class AdNetwork(
    val id: String       = "",
    val enabled: Boolean = false,
    @SerializedName("banner_id")       val bannerId: String       = "",
    @SerializedName("interstitial_id") val interstitialId: String = "",
    @SerializedName("rewarded_id")     val rewardedId: String     = "",
    @SerializedName("native_id")       val nativeId: String       = "",
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

// ── Shorts / Reddit feed config ───────────────────────────────────────────────

data class ShortsConfig(
    @SerializedName("reddit_base")       val redditBase: String              = "https://old.reddit.com",
    @SerializedName("for_you_subs")      val forYouSubs: String              =
        "nextfuckinglevel+oddlysatisfying+funny+aww+BeAmazed+interestingasfuck+Unexpected+Damnthatsinteresting+sports+NatureIsFuckingLit",
    val categories: List<ShortCategory>                                      = defaultCategories(),
)

data class ShortCategory(
    val label: String = "",
    val subs: String  = "",
)

private fun defaultCategories() = listOf(
    ShortCategory("🔥 Hot",        "nextfuckinglevel+oddlysatisfying+Unexpected+interestingasfuck+BeAmazed"),
    ShortCategory("😂 Funny",      "funny+facepalm+Whatcouldgowrong+therewasanattempt"),
    ShortCategory("😮 WOW",        "nextfuckinglevel+BeAmazed+interestingasfuck+Damnthatsinteresting"),
    ShortCategory("😌 Satisfying", "oddlysatisfying+ASMR+powerwashingporn+Perfectfit"),
    ShortCategory("🐾 Animals",    "aww+AnimalsBeingBros+rarepuppers+NatureIsFuckingLit"),
    ShortCategory("⚽ Sports",     "sports+soccer+nba+MMA+skateboarding"),
    ShortCategory("🎨 Art",        "Art+blackmagicfuckery+specializedtools+crafts"),
    ShortCategory("🌍 Nature",     "NatureIsFuckingLit+EarthPorn+interestingasfuck+Outdoors"),
)
