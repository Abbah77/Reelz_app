package com.axio.reelz.data.remote.api

import com.axio.reelz.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

// ─────────────────────────────────────────────────────────────────────────────
//  ReelzApi — the ONLY network interface the app ever calls.
//
//  Every endpoint is on YOUR VPS. The app has zero knowledge of TMDB,
//  archive.org, or any other third-party data source.
//
//  URL convention:
//    /api/v1/...   → content (feed, detail, search, stream, subtitles)
//    /auth/...     → authentication
//    /config       → app config (replaces remote-config CDN)
//
//  All responses are wrapped in ApiResponse<T> for uniform error handling,
//  except streaming endpoints which return their own wrapper.
// ─────────────────────────────────────────────────────────────────────────────

interface ReelzApi {

    // ── App config — first call on every app launch ───────────────────────────
    // Returns feature flags, ad config, premium pricing, min version.
    // The app caches this locally; backend sets Cache-Control accordingly.
    @GET("config")
    suspend fun getAppConfig(): Response<AppConfigDto>

    // ── Home feed — all sections in one shot (efficient) ──────────────────────
    // Backend decides section order, TTLs, and personalization.
    // Use ?refresh=1 to bypass server-side caching (pull-to-refresh).
    @GET("api/v1/feed")
    suspend fun getFeed(
        @Query("refresh") refresh: Int = 0,
    ): Response<FeedResponseDto>

    // ── Single section — for lazy loading or section-specific refresh ─────────
    @GET("api/v1/feed/{sectionId}")
    suspend fun getFeedSection(
        @Path("sectionId")   sectionId: String,
        @Query("cursor")     cursor: String? = null,
        @Query("limit")      limit: Int = 20,
    ): Response<PagedResponseDto>

    // ── Explore / Discover ────────────────────────────────────────────────────
    @GET("api/v1/discover")
    suspend fun discover(
        @Query("type")       mediaType: String = "movie",  // "movie" | "tv"
        @Query("genre")      genre: String? = null,
        @Query("language")   language: String? = null,
        @Query("sort")       sortBy: String = "popularity",
        @Query("year_from")  yearFrom: Int? = null,
        @Query("year_to")    yearTo: Int? = null,
        @Query("rating_min") ratingMin: Float? = null,
        @Query("cursor")     cursor: String? = null,
        @Query("limit")      limit: Int = 20,
    ): Response<PagedResponseDto>

    // ── Genre list ────────────────────────────────────────────────────────────
    @GET("api/v1/genres")
    suspend fun getGenres(
        @Query("type") mediaType: String = "movie",
    ): Response<List<GenreDto>>

    // ── Search ────────────────────────────────────────────────────────────────
    @GET("api/v1/search")
    suspend fun search(
        @Query("q")      query: String,
        @Query("type")   mediaType: String? = null,   // null = both
        @Query("cursor") cursor: String? = null,
        @Query("limit")  limit: Int = 20,
    ): Response<SearchResponseDto>

    // ── Media detail ──────────────────────────────────────────────────────────
    @GET("api/v1/media/{id}")
    suspend fun getDetail(
        @Path("id") id: String,
    ): Response<MediaDetailDto>

    // ── Season episodes ───────────────────────────────────────────────────────
    @GET("api/v1/media/{id}/season/{season}")
    suspend fun getSeasonEpisodes(
        @Path("id")     id: String,
        @Path("season") season: Int,
    ): Response<SeasonDetailDto>

    // ── Stream resolution ─────────────────────────────────────────────────────
    // POST so the request body isn't cached by CDN/proxy layers and
    // we can include auth headers without them appearing in server logs.
    @POST("api/v1/stream")
    suspend fun resolveStream(
        @Body request: StreamRequestBody,
    ): Response<StreamResponseDto>

    // ── Download links ────────────────────────────────────────────────────────
    @POST("api/v1/download")
    suspend fun getDownloadLinks(
        @Body request: StreamRequestBody,
    ): Response<DownloadLinksResponseDto>

    // ── Subtitles ─────────────────────────────────────────────────────────────
    @POST("api/v1/subtitles")
    suspend fun getSubtitles(
        @Body request: SubtitleRequestBody,
    ): Response<SubtitlesResponseDto>

    // ── Shorts feed ───────────────────────────────────────────────────────────
    @GET("api/v1/shorts")
    suspend fun getShorts(
        @Query("cursor") cursor: String? = null,
        @Query("limit")  limit: Int = 10,
    ): Response<ShortsResponseDto>

    // ── Auth ──────────────────────────────────────────────────────────────────
    @POST("auth/google")
    suspend fun authWithGoogle(
        @Body body: GoogleAuthBody,
    ): Response<AuthResponseDto>

    @POST("auth/refresh")
    suspend fun refreshSession(
        @Header("Authorization") bearerToken: String,
    ): Response<AuthResponseDto>

    @POST("auth/sync")
    suspend fun syncUserData(
        @Header("Authorization") bearerToken: String,
        @Body body: SyncBody,
    ): Response<SyncResponseDto>
}

// ── Request bodies ────────────────────────────────────────────────────────────

data class StreamRequestBody(
    val id: String,
    val type: String,           // "movie" | "tv"
    val title: String,
    val season: Int = 0,
    val episode: Int = 0,
)

data class SubtitleRequestBody(
    val id: String,
    val type: String,
    val season: Int = 0,
    val episode: Int = 0,
    val languages: List<String> = listOf("en"),
)

data class GoogleAuthBody(
    val id_token: String,
)

data class SyncBody(
    val watchlist: List<String> = emptyList(),
    val history: List<HistorySyncItem> = emptyList(),
)

data class HistorySyncItem(
    val id: String,
    val season: Int = 0,
    val episode: Int = 0,
    val position_ms: Long = 0,
    val duration_ms: Long = 0,
    val watched_at: Long = 0,
)

// ── Additional response DTOs that belong here ──────────────────────────────────

data class SubtitlesResponseDto(
    val ok: Boolean = false,
    val subtitles: List<com.axio.reelz.data.remote.dto.SubtitleDto> = emptyList(),
)

data class ShortsResponseDto(
    val items: List<ShortVideoDto> = emptyList(),
    val has_more: Boolean = false,
    val next_cursor: String? = null,
)

data class ShortVideoDto(
    val id: String = "",
    val title: String = "",
    val author: String = "",
    val hls_url: String = "",
    val fallback_url: String = "",
    val thumbnail: String = "",
    val duration: Int = 0,
    val width: Int = 1080,
    val height: Int = 1920,
) {
    fun toModel() = com.axio.reelz.data.model.ShortVideo(
        id = id, title = title, author = author,
        hlsUrl = hls_url, fallbackUrl = fallback_url,
        thumbnail = thumbnail, duration = duration,
        width = width, height = height,
    )
}

data class SyncResponseDto(
    val ok: Boolean = false,
    val watchlist: List<com.axio.reelz.data.remote.dto.MediaDto> = emptyList(),
    val history: List<SyncHistoryItem> = emptyList(),
)

data class SyncHistoryItem(
    val id: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val position_ms: Long = 0,
    val duration_ms: Long = 0,
    val watched_at: Long = 0,
)
