package com.axio.reelz.data.dto

import com.axio.reelz.data.model.*
import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
//  Backend DTOs — shape of every response from YOUR VPS
//
//  Naming convention: Dto suffix = raw wire shape, never exposed to UI.
//  All toModel() mappers live here so nothing upstream knows about JSON.
// ─────────────────────────────────────────────────────────────────────────────

// ── Standard envelope ─────────────────────────────────────────────────────────
data class ApiResponse<T>(
    val ok: Boolean = false,
    val data: T? = null,
    val error: String? = null,
    @SerializedName("cache_ttl_ms") val cacheTtlMs: Long? = null,
)

// ── Media card (list item) ────────────────────────────────────────────────────
data class MediaDto(
    val id: String = "",
    val title: String = "",
    @SerializedName("poster_url")   val posterUrl: String? = null,
    @SerializedName("backdrop_url") val backdropUrl: String? = null,
    @SerializedName("release_year") val releaseYear: String? = null,
    val rating: Double = 0.0,
    @SerializedName("media_type")   val mediaType: String = "movie",
    val genres: List<String> = emptyList(),
    val language: String = "en",
    @SerializedName("section_tag")  val sectionTag: String = "",
) {
    fun toModel() = Media(
        id = id,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        releaseYear = releaseYear,
        rating = rating,
        mediaType = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE,
        genres = genres,
        language = language,
        sectionTag = sectionTag,
    )
}

// ── Feed response ─────────────────────────────────────────────────────────────
data class FeedSectionDto(
    val id: String = "",
    val title: String = "",
    val items: List<MediaDto> = emptyList(),
    @SerializedName("has_more")     val hasMore: Boolean = false,
    @SerializedName("next_cursor")  val nextCursor: String? = null,
) {
    fun toModel() = FeedSection(
        id = id,
        title = title,
        items = items.map { it.toModel() },
        hasMore = hasMore,
        nextCursor = nextCursor,
    )
}

data class FeedResponseDto(
    val sections: List<FeedSectionDto> = emptyList(),
    @SerializedName("cache_ttl_ms") val cacheTtlMs: Long = 3_600_000L, // default 1 h
)

// ── Paginated content list ─────────────────────────────────────────────────────
data class PagedResponseDto(
    val items: List<MediaDto> = emptyList(),
    @SerializedName("has_more")     val hasMore: Boolean = false,
    @SerializedName("next_cursor")  val nextCursor: String? = null,
    @SerializedName("total_count")  val totalCount: Int? = null,
    @SerializedName("cache_ttl_ms") val cacheTtlMs: Long = 1_800_000L, // default 30 min
)

// ── Detail ────────────────────────────────────────────────────────────────────
data class SeasonDto(
    val id: String = "",
    @SerializedName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    @SerializedName("episode_count") val episodeCount: Int = 0,
    @SerializedName("poster_url")    val posterUrl: String? = null,
    val overview: String? = null,
    @SerializedName("air_date")      val airDate: String? = null,
) {
    fun toModel() = Season(
        id = id,
        seasonNumber = seasonNumber,
        name = name,
        episodeCount = episodeCount,
        posterUrl = posterUrl,
        overview = overview,
        airDate = airDate,
    )
}

data class CastMemberDto(
    val id: String = "",
    val name: String = "",
    val character: String = "",
    @SerializedName("photo_url") val photoUrl: String? = null,
    val order: Int = 0,
) {
    fun toModel() = CastMember(id = id, name = name, character = character,
        photoUrl = photoUrl, order = order)
}

data class MediaDetailDto(
    val id: String = "",
    val title: String = "",
    val overview: String = "",
    @SerializedName("poster_url")     val posterUrl: String? = null,
    @SerializedName("backdrop_url")   val backdropUrl: String? = null,
    @SerializedName("release_year")   val releaseYear: String? = null,
    val rating: Double = 0.0,
    val runtime: Int? = null,
    @SerializedName("media_type")     val mediaType: String = "movie",
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val tagline: String? = null,
    val seasons: List<SeasonDto> = emptyList(),
    val cast: List<CastMemberDto> = emptyList(),
    @SerializedName("trailer_url")    val trailerUrl: String? = null,
    val similar: List<MediaDto> = emptyList(),
    @SerializedName("imdb_id")        val imdbId: String? = null,
    @SerializedName("spoken_languages") val spokenLanguages: List<String> = emptyList(),
    @SerializedName("cache_ttl_ms")   val cacheTtlMs: Long = 3_600_000L,
) {
    fun toModel() = MediaDetail(
        id = id, title = title, overview = overview,
        posterUrl = posterUrl, backdropUrl = backdropUrl,
        releaseYear = releaseYear, rating = rating,
        runtime = runtime,
        mediaType = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE,
        genres = genres, status = status, tagline = tagline,
        seasons = seasons.map { it.toModel() },
        cast = cast.map { it.toModel() },
        trailerUrl = trailerUrl,
        similar = similar.map { it.toModel() },
        imdbId = imdbId, spokenLanguages = spokenLanguages,
    )
}

// ── Episodes ──────────────────────────────────────────────────────────────────
data class EpisodeDto(
    val id: String = "",
    @SerializedName("episode_number") val episodeNumber: Int = 0,
    @SerializedName("season_number")  val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerializedName("still_url")     val stillUrl: String? = null,
    @SerializedName("air_date")      val airDate: String? = null,
    val runtime: Int? = null,
    val rating: Double = 0.0,
) {
    fun toModel() = Episode(
        id = id, episodeNumber = episodeNumber, seasonNumber = seasonNumber,
        name = name, overview = overview, stillUrl = stillUrl,
        airDate = airDate, runtime = runtime, rating = rating,
    )
}

data class SeasonDetailDto(
    val id: String = "",
    val episodes: List<EpisodeDto> = emptyList(),
    @SerializedName("cache_ttl_ms") val cacheTtlMs: Long = 86_400_000L, // 24 h
)

// ── Genre list ────────────────────────────────────────────────────────────────
data class GenreDto(val id: String = "", val name: String = "") {
    fun toModel() = Genre(id = id, name = name)
}


// ── Stream response ───────────────────────────────────────────────────────────
data class StreamQualityDto(
    val label: String = "Auto",
    val url: String = "",
    val bandwidth: Long = 0,
    @SerializedName("size_bytes") val sizeBytes: Long = 0,
) {
    fun toTrack() = QualityTrack(label = label, url = url,
        bandwidth = bandwidth, estimatedSizeBytes = sizeBytes)
}

data class SubtitleDto(
    val url: String = "",
    val language: String = "en",
    val label: String = "English",
) {
    fun toModel() = Subtitle(url = url, language = language, label = label)
}

data class StreamResponseDto(
    val ok: Boolean = false,
    @SerializedName("stream_url")  val streamUrl: String = "",
    @SerializedName("is_hls")      val isHls: Boolean = true,
    val quality: String = "Auto",
    val headers: Map<String, String> = emptyMap(),
    @SerializedName("source_name") val sourceName: String = "",
    val qualities: List<StreamQualityDto> = emptyList(),
    val subtitles: List<SubtitleDto> = emptyList(),
    @SerializedName("cache_ttl_ms") val cacheTtlMs: Long = 240_000L, // 4 min default
) {
    fun toModel() = StreamResult(
        url = streamUrl,
        isHls = isHls,
        quality = quality,
        headers = headers,
        sourceName = sourceName,
        qualities = qualities.map { it.toTrack() },
        subtitles = subtitles.map { it.toModel() },
    )
}

// ── Download links ─────────────────────────────────────────────────────────────
data class DownloadLinkDto(
    val label: String = "Auto",
    val url: String = "",
    val language: String = "English",
    @SerializedName("size_bytes") val sizeBytes: Long = 0,
) {
    fun toTrack() = QualityTrack(label = label, url = url, estimatedSizeBytes = sizeBytes)
}

data class DownloadLinksResponseDto(
    val ok: Boolean = false,
    val links: List<DownloadLinkDto> = emptyList(),
    val premium: Boolean = false,
    /**
     * The resolution cap (in pixel height) that the backend applied when
     * building [links]. 0 means no cap was applied.
     * The app uses this ONLY to show a lock badge on qualities the user
     * can't access yet — it never enforces its own cap.
     */
    @SerializedName("max_resolution") val maxResolution: Int = 0,
)

// ── Config (replaces RemoteConfig — fetched from YOUR backend, not GitHub) ────
data class AppConfigDto(
    val version: Int = 1,
    @SerializedName("backend_token") val backendToken: String = "",
    @SerializedName("shorts_enabled") val shortsEnabled: Boolean = true,
    @SerializedName("downloads_enabled") val downloadsEnabled: Boolean = true,
    @SerializedName("force_maintenance") val forceMaintenance: Boolean = false,
    @SerializedName("maintenance_message") val maintenanceMessage: String = "",
    @SerializedName("min_app_version") val minAppVersion: Int = 1,
    @SerializedName("latest_app_version") val latestAppVersion: Int = 1,
    @SerializedName("latest_apk_url") val latestApkUrl: String = "",
    val premium: PremiumConfigDto = PremiumConfigDto(),
    val ads: AdsConfigDto = AdsConfigDto(),
    /**
     * Resolution cap (pixel height) for free users. 0 = no cap.
     * Sent by the backend — the app never computes this itself.
     */
    @SerializedName("download_max_resolution_free") val downloadMaxResolutionFree: Int = 0,
    /**
     * Resolution cap (pixel height) for premium users. 0 = no cap.
     * Sent by the backend — the app never computes this itself.
     */
    @SerializedName("download_max_resolution_premium") val downloadMaxResolutionPremium: Int = 0,
)

data class PremiumConfigDto(
    val enabled: Boolean = false,
    @SerializedName("monthly_price") val monthlyPrice: Long = 0,
    @SerializedName("paystack_monthly_url") val paystackMonthlyUrl: String = "",
    @SerializedName("paystack_yearly_url") val paystackYearlyUrl: String = "",
)

data class AdPlacementsDto(
    @SerializedName("banner_enabled")       val bannerEnabled: Boolean = true,
    @SerializedName("interstitial_enabled") val interstitialEnabled: Boolean = true,
    @SerializedName("rewarded_enabled")     val rewardedEnabled: Boolean = true,
    @SerializedName("native_enabled")       val nativeEnabled: Boolean = true,
    @SerializedName("app_open_enabled")     val appOpenEnabled: Boolean = false,
    @SerializedName("preroll_enabled")      val prerollEnabled: Boolean = false,
)

data class AdFrequencyDto(
    @SerializedName("min_content_opens")       val minContentOpens: Int = 3,
    @SerializedName("min_interval_ms")         val minIntervalMs: Long = 60_000L,
    @SerializedName("retry_delay_ms")          val retryDelayMs: Long = 30_000L,
    // Aliases used by AdEngine.shouldShowInterstitial()
    @SerializedName("content_opens_before_first") val contentOpensBeforeFirst: Int = 3,
    @SerializedName("every_n_plays")           val everyNPlays: Int = 3,
    @SerializedName("min_ms_between")          val minMsBetween: Long = 60_000L,
    @SerializedName("max_per_session")         val maxPerSession: Int = 10,
)

data class AdPrerollConfig(
    @SerializedName("skip_on_resume")         val skipOnResume: Boolean = true,
    @SerializedName("skip_on_quality_switch") val skipOnQualitySwitch: Boolean = true,
    @SerializedName("show_on_movies_only")    val showOnMoviesOnly: Boolean = false,
    @SerializedName("min_minutes_between")    val minMinutesBetween: Long = 30L,
)

/** Domain alias that AdEngine uses for the active network's ad unit IDs */
data class AdNetwork(
    @SerializedName("banner_id")       val bannerId: String = "",
    @SerializedName("interstitial_id") val interstitialId: String = "",
    @SerializedName("rewarded_id")     val rewardedId: String = "",
    @SerializedName("native_id")       val nativeId: String = "",
    @SerializedName("app_open_id")     val appOpenId: String = "",
    @SerializedName("vast_tag_url")    val vastTagUrl: String = "",
)

data class AdsConfigDto(
    val enabled: Boolean = false,
    @SerializedName("applovin_sdk_key")       val applovinSdkKey: String = "",
    @SerializedName("mediation_provider")     val mediationProvider: String = "max",
    @SerializedName("banner_id")              val bannerId: String = "",
    @SerializedName("interstitial_id")        val interstitialId: String = "",
    @SerializedName("rewarded_id")            val rewardedId: String = "",
    @SerializedName("native_id")              val nativeId: String = "",
    @SerializedName("app_open_id")            val appOpenId: String = "",
    @SerializedName("vast_tag_url")           val vastTagUrl: String = "",
    val placements: AdPlacementsDto = AdPlacementsDto(),
    @SerializedName("interstitial_frequency") val interstitialFrequency: AdFrequencyDto = AdFrequencyDto(),
    val preroll: AdPrerollConfig = AdPrerollConfig(),
    val network: AdNetwork? = null,
)

// ── Auth ──────────────────────────────────────────────────────────────────────
data class AuthResponseDto(
    val ok: Boolean = false,
    @SerializedName("user_id")      val userId: String = "",
    @SerializedName("access_token") val accessToken: String = "",
    val premium: Boolean = false,
    val status: String = "none",
    @SerializedName("expires_at_ms") val expiresAtMs: Long = 0L,
    val name: String = "",
    val email: String = "",
    @SerializedName("photo_url") val photoUrl: String? = null,
)

// ── Search ────────────────────────────────────────────────────────────────────
data class SearchResponseDto(
    val items: List<MediaDto> = emptyList(),
    @SerializedName("has_more")    val hasMore: Boolean = false,
    @SerializedName("next_cursor") val nextCursor: String? = null,
    @SerializedName("cache_ttl_ms") val cacheTtlMs: Long = 300_000L, // 5 min
)

// ── App config extension — backend URL management ─────────────────────────────
// The backend URL is bootstrapped via BuildConfig (set at build time or via
// a one-time setup screen). After the first successful config fetch, the app
// stores the URL in Room and reads it on subsequent launches.
//
// You update it by pushing a new AppConfigDto from YOUR backend — the
// DynamicUrlInterceptor in AppModule picks it up on the next request.
fun AppConfigDto.backendBaseUrl(): String =
    "https://your-vps.example.com"   // override at build time via BuildConfig.BACKEND_URL

data class PaymentInitDto(
    val authorizationUrl: String = "",
    val reference: String = "",
)
