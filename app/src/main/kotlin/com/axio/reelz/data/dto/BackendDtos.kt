package com.axio.reelz.data.dto

import com.axio.reelz.data.model.*
import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
//  Backend DTOs — Schema v4
//
//  ENVELOPE RULE: Every endpoint now returns the standard envelope:
//    { "ok": true, "data": {...}, "error": null, "cache_ttl_ms": 3600000 }
//
//  ApiResponse<T> wraps every call. Repositories unwrap .data before mapping.
//  cache_ttl_ms is read from the envelope root — NOT from inside data objects.
//  expires_at_ms (stream/download link expiry) lives inside data — it is
//  content metadata, not response metadata.
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
    @SerializedName("poster_url")  val posterUrl: String? = null,
    val rating: Double = 0.0,
    @SerializedName("media_type") val mediaType: String = "movie",
) {
    fun toModel() = Media(
        id        = id,
        title     = title,
        posterUrl = posterUrl,
        rating    = rating,
        mediaType = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE,
    )
}

// ── Feed ──────────────────────────────────────────────────────────────────────
data class FeedSectionDto(
    val id: String = "",
    val title: String = "",
    val layout: String = "row",
    val items: List<MediaDto> = emptyList(),
    @SerializedName("has_more")    val hasMore: Boolean = false,
    @SerializedName("next_cursor") val nextCursor: String? = null,
) {
    fun toModel() = FeedSection(
        id         = id,
        title      = title,
        layout     = layout,
        items      = items.map { it.toModel() },
        hasMore    = hasMore,
        nextCursor = nextCursor,
    )
}

// data field inside ApiResponse<FeedData>
data class FeedData(
    val sections: List<FeedSectionDto> = emptyList(),
)

// ── Paginated content list ─────────────────────────────────────────────────────
// data field inside ApiResponse<PagedData>
data class PagedData(
    val items: List<MediaDto> = emptyList(),
    @SerializedName("has_more")    val hasMore: Boolean = false,
    @SerializedName("next_cursor") val nextCursor: String? = null,
)

// ── Detail ────────────────────────────────────────────────────────────────────
data class SeasonDto(
    @SerializedName("season_number") val seasonNumber: Int = 0,
) {
    fun toModel() = Season(seasonNumber = seasonNumber)
}

data class CastMemberDto(
    val name: String = "",
    val character: String = "",
    @SerializedName("photo_url") val photoUrl: String? = null,
) {
    fun toModel() = CastMember(name = name, character = character, photoUrl = photoUrl)
}

// data field inside ApiResponse<MediaDetailDto>
data class MediaDetailDto(
    val id: String = "",
    val title: String = "",
    val tagline: String? = null,
    val overview: String = "",
    @SerializedName("poster_url")    val posterUrl: String? = null,
    @SerializedName("backdrop_url")  val backdropUrl: String? = null,
    @SerializedName("release_year")  val releaseYear: String? = null,
    val rating: Double = 0.0,
    val runtime: Int? = null,
    @SerializedName("media_type")    val mediaType: String = "movie",
    @SerializedName("maturity_rating") val maturityRating: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    @SerializedName("trailer_url")   val trailerUrl: String? = null,
    val cast: List<CastMemberDto> = emptyList(),
    val seasons: List<SeasonDto> = emptyList(),
    val similar: List<MediaDto> = emptyList(),
) {
    fun toModel() = MediaDetail(
        id             = id,
        title          = title,
        tagline        = tagline,
        overview       = overview,
        posterUrl      = posterUrl,
        backdropUrl    = backdropUrl,
        releaseYear    = releaseYear,
        rating         = rating,
        runtime        = runtime,
        mediaType      = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE,
        maturityRating = maturityRating,
        genres         = genres,
        status         = status,
        trailerUrl     = trailerUrl,
        cast           = cast.map { it.toModel() },
        seasons        = seasons.map { it.toModel() },
        similar        = similar.map { it.toModel() },
    )
}

// ── Episodes ──────────────────────────────────────────────────────────────────
data class EpisodeDto(
    val id: String = "",
    @SerializedName("episode_number") val episodeNumber: Int = 0,
    @SerializedName("season_number")  val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerializedName("still_url") val stillUrl: String? = null,
    val runtime: Int? = null,
) {
    fun toModel() = Episode(
        id            = id,
        episodeNumber = episodeNumber,
        seasonNumber  = seasonNumber,
        name          = name,
        overview      = overview,
        stillUrl      = stillUrl,
        runtime       = runtime,
    )
}

// data field inside ApiResponse<SeasonData>
data class SeasonData(
    val episodes: List<EpisodeDto> = emptyList(),
)

// ── Genre list ────────────────────────────────────────────────────────────────
data class GenreDto(val id: String = "", val name: String = "") {
    fun toModel() = Genre(id = id, name = name)
}

// data field inside ApiResponse<GenresData>
data class GenresData(
    val genres: List<GenreDto> = emptyList(),
)

// ── Stream ────────────────────────────────────────────────────────────────────
data class StreamSubtitleDto(
    val url: String = "",
    val language: String = "en",
    val enabled: Boolean = false,
) {
    fun toModel() = Subtitle(url = url, language = language, enabled = enabled)
}

data class StreamItemDto(
    val name: String = "",
    val url: String = "",
    val type: String = "hls",
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<StreamSubtitleDto> = emptyList(),
) {
    fun toModel() = StreamTrack(
        name      = name,
        url       = url,
        type      = type,
        headers   = headers,
        subtitles = subtitles.map { it.toModel() },
    )
}

// data field inside ApiResponse<StreamData>
// expires_at_ms lives here — it is content metadata (link expiry), not response metadata
data class StreamData(
    val streams: List<StreamItemDto> = emptyList(),
    @SerializedName("expires_at_ms") val expiresAtMs: Long = 0L,
) {
    fun toModel() = StreamResult(
        streams     = streams.map { it.toModel() },
        expiresAtMs = expiresAtMs,
    )
}

// ── Download links ────────────────────────────────────────────────────────────
data class DownloadLinkDto(
    val label: String = "Auto",
    val type: String = "mp4",           // "mp4" | "hls"
    val url: String = "",
    val language: String = "English",
    @SerializedName("size_bytes") val sizeBytes: Long = 0,
    val premium: Boolean = false,
) {
    fun toModel() = DownloadLink(
        label     = label,
        type      = type,
        url       = url,
        language  = language,
        sizeBytes = sizeBytes,
        premium   = premium,
    )
}

// data field inside ApiResponse<DownloadData>
// expires_at_ms lives here — it is content metadata (link expiry), not response metadata
data class DownloadData(
    val links: List<DownloadLinkDto> = emptyList(),
    @SerializedName("expires_at_ms") val expiresAtMs: Long = 0L,
)

// ── Subtitles ─────────────────────────────────────────────────────────────────
data class SubtitleDto(
    val url: String = "",
    val language: String = "en",
    val enabled: Boolean = false,
) {
    fun toModel() = Subtitle(url = url, language = language, enabled = enabled)
}

// data field inside ApiResponse<SubtitlesData>
data class SubtitlesData(
    val subtitles: List<SubtitleDto> = emptyList(),
)

// ── Shorts ────────────────────────────────────────────────────────────────────
data class ShortVideoDto(
    val id: String = "",
    val title: String = "",
    val source: String? = null,
    val url: String = "",
    val thumbnail: String? = null,
) {
    fun toModel() = ShortVideo(
        id        = id,
        title     = title,
        source    = source,
        url       = url,
        thumbnail = thumbnail,
    )
}

// data field inside ApiResponse<ShortsData>
data class ShortsData(
    val items: List<ShortVideoDto> = emptyList(),
    @SerializedName("has_more")    val hasMore: Boolean = false,
    @SerializedName("next_cursor") val nextCursor: String? = null,
)

// ── App Config ────────────────────────────────────────────────────────────────
// data field inside ApiResponse<AppConfigDto>
data class AppConfigDto(
    val version: Int = 1,
    @SerializedName("min_app_version")    val minAppVersion: Int = 1,
    @SerializedName("latest_app_version") val latestAppVersion: Int = 1,
    @SerializedName("latest_apk_url")     val latestApkUrl: String = "",
    @SerializedName("force_maintenance")  val forceMaintenance: Boolean = false,
    @SerializedName("maintenance_message") val maintenanceMessage: String = "",
    @SerializedName("shorts_enabled")     val shortsEnabled: Boolean = true,
    @SerializedName("downloads_enabled")  val downloadsEnabled: Boolean = true,
    @SerializedName("search_min_chars")   val searchMinChars: Int = 2,
    @SerializedName("guest_streaming_enabled") val guestStreamingEnabled: Boolean = true,
    val premium: PremiumConfigDto = PremiumConfigDto(),
    val ads: AdsConfigDto = AdsConfigDto(),
)

data class PremiumConfigDto(
    val enabled: Boolean = false,
    @SerializedName("monthly_price")         val monthlyPrice: Long = 0,
    @SerializedName("paystack_monthly_url")  val paystackMonthlyUrl: String = "",
    @SerializedName("paystack_yearly_url")   val paystackYearlyUrl: String = "",
)

data class AdPlacementsDto(
    @SerializedName("banner_enabled")       val bannerEnabled: Boolean = true,
    @SerializedName("interstitial_enabled") val interstitialEnabled: Boolean = true,
    @SerializedName("native_enabled")       val nativeEnabled: Boolean = true,
    @SerializedName("preroll_enabled")      val prerollEnabled: Boolean = false,
    @SerializedName("rewarded_enabled")     val rewardedEnabled: Boolean = false,
    @SerializedName("app_open_enabled")     val appOpenEnabled: Boolean = false,
)

data class AdFrequencyDto(
    @SerializedName("content_opens_before_first") val contentOpensBeforeFirst: Int = 3,
    @SerializedName("every_n_plays")              val everyNPlays: Int = 3,
    @SerializedName("min_ms_between")             val minMsBetween: Long = 60_000L,
    @SerializedName("max_per_session")            val maxPerSession: Int = 10,
    @SerializedName("retry_delay_ms")             val retryDelayMs: Long = 30_000L,
)

data class AdsConfigDto(
    val enabled: Boolean = false,
    @SerializedName("applovin_sdk_key")    val applovinSdkKey: String = "",
    @SerializedName("banner_id")           val bannerId: String = "",
    @SerializedName("interstitial_id")     val interstitialId: String = "",
    @SerializedName("rewarded_id")         val rewardedId: String = "",
    @SerializedName("native_id")           val nativeId: String = "",
    @SerializedName("mediation_provider")  val mediationProvider: String = "max",
    val placements: AdPlacementsDto = AdPlacementsDto(),
    val frequency: AdFrequencyDto = AdFrequencyDto(),
) {
    val interstitialFrequency: AdFrequencyDto get() = frequency
}

// ── Auth ──────────────────────────────────────────────────────────────────────
// data field inside ApiResponse<AuthData>
data class AuthData(
    @SerializedName("user_id")               val userId: String = "",
    @SerializedName("access_token")          val accessToken: String = "",
    @SerializedName("refresh_token")         val refreshToken: String = "",
    @SerializedName("expires_at_ms")         val expiresAtMs: Long = 0L,
    val premium: Boolean = false,
    @SerializedName("premium_expires_at_ms") val premiumExpiresAtMs: Long = 0L,
)

// ── Token refresh ─────────────────────────────────────────────────────────────
// data field inside ApiResponse<RefreshData>
data class RefreshData(
    @SerializedName("access_token")  val accessToken: String = "",
    @SerializedName("expires_at_ms") val expiresAtMs: Long = 0L,
)

// ── Sync ──────────────────────────────────────────────────────────────────────
// data field inside ApiResponse<SyncData>
data class SyncData(
    // empty — server returns {} as acknowledgement
    val placeholder: String? = null,
)

// ── Payment init ──────────────────────────────────────────────────────────────
// data field inside ApiResponse<PaymentData>
data class PaymentData(
    @SerializedName("authorization_url") val authorizationUrl: String = "",
    val reference: String = "",
)

// ── Ad preroll config — UI-only stub for AdEngine/VastTagProvider compatibility ──
data class AdPrerollConfig(
    val skipOnResume: Boolean = true,
    val skipOnQualitySwitch: Boolean = true,
    val showOnMoviesOnly: Boolean = false,
    val minMinutesBetween: Long = 30L,
)
