package com.axio.reelz.data.remote.api

import com.axio.reelz.data.dto.*
import retrofit2.Response
import retrofit2.http.*

// ─────────────────────────────────────────────────────────────────────────────
//  ReelzApi — Schema v3
//
//  Endpoint access:
//    PUBLIC (no auth): /config, /api/v1/feed, /api/v1/discover, /api/v1/genres,
//                      /api/v1/search, /api/v1/media/{id}, /api/v1/shorts,
//                      /auth/google
//    AUTH OPTIONAL:    /api/v1/stream, /api/v1/download, /api/v1/subtitles
//    AUTH REQUIRED:    /auth/sync, /auth/refresh, /payment/init
//
//  The app always sends Bearer token when available to enable server-side logging.
// ─────────────────────────────────────────────────────────────────────────────

interface ReelzApi {

    // ── App config ────────────────────────────────────────────────────────────
    @GET("config")
    suspend fun getAppConfig(): Response<AppConfigDto>

    // ── Home feed ─────────────────────────────────────────────────────────────
    @GET("api/v1/feed")
    suspend fun getFeed(
        @Query("refresh") refresh: Int = 0,
    ): Response<FeedResponseDto>

    @GET("api/v1/feed/{sectionId}")
    suspend fun getFeedSection(
        @Path("sectionId")  sectionId: String,
        @Query("cursor")    cursor: String? = null,
        @Query("limit")     limit: Int = 20,
    ): Response<PagedResponseDto>

    // ── Explore / Discover ────────────────────────────────────────────────────
    @GET("api/v1/discover")
    suspend fun discover(
        @Query("type")   mediaType: String = "movie",
        @Query("genre")  genre: String? = null,
        @Query("sort")   sortBy: String = "popularity",
        @Query("cursor") cursor: String? = null,
        @Query("limit")  limit: Int = 20,
    ): Response<PagedResponseDto>

    // ── Genres ────────────────────────────────────────────────────────────────
    @GET("api/v1/genres")
    suspend fun getGenres(
        @Query("type") mediaType: String = "movie",
    ): Response<GenresResponseDto>

    // ── Search ────────────────────────────────────────────────────────────────
    @GET("api/v1/search")
    suspend fun search(
        @Query("q")      query: String,
        @Query("type")   mediaType: String? = null,
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

    // ── Stream (auth optional — send token when available) ────────────────────
    @POST("api/v1/stream")
    suspend fun resolveStream(
        @Body request: StreamRequestBody,
    ): Response<StreamResponseDto>

    // ── Download (auth optional — send token when available) ──────────────────
    @POST("api/v1/download")
    suspend fun getDownloadLinks(
        @Body request: StreamRequestBody,
    ): Response<DownloadLinksResponseDto>

    // ── Subtitles (auth optional) ─────────────────────────────────────────────
    @POST("api/v1/subtitles")
    suspend fun getSubtitles(
        @Body request: SubtitleRequestBody,
    ): Response<SubtitlesResponseDto>

    // ── Shorts ────────────────────────────────────────────────────────────────
    @GET("api/v1/shorts")
    suspend fun getShorts(
        @Query("cursor") cursor: String? = null,
        @Query("limit")  limit: Int = 10,
    ): Response<ShortsResponseDto>

    // ── Auth — Google sign-in ─────────────────────────────────────────────────
    @POST("auth/google")
    suspend fun authWithGoogle(
        @Body body: GoogleAuthBody,
    ): Response<AuthResponseDto>

    // ── Auth — Token refresh ──────────────────────────────────────────────────
    // Header: Authorization: Bearer <refresh_token>
    @POST("auth/refresh")
    suspend fun refreshToken(
        @Header("Authorization") bearerRefreshToken: String,
    ): Response<RefreshResponseDto>

    // ── Auth — Sync watch history ─────────────────────────────────────────────
    // Requires access_token. Watchlist is local-only (Room DB).
    @POST("auth/sync")
    suspend fun syncHistory(
        @Header("Authorization") bearerToken: String,
        @Body body: SyncBody,
    ): Response<SyncResponseDto>

    // ── Payment ───────────────────────────────────────────────────────────────
    @FormUrlEncoded
    @POST("payment/init")
    suspend fun initPayment(
        @Field("plan") plan: String,
    ): Response<PaymentInitDto>
}

// ── Request bodies ────────────────────────────────────────────────────────────

data class StreamRequestBody(
    val id: String,
    val type: String,      // "movie" | "tv"
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

// Sync body — history only, watchlist is local
data class SyncBody(
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

// ── Search response ────────────────────────────────────────────────────────────
data class SearchResponseDto(
    val items: List<MediaDto> = emptyList(),
    @com.google.gson.annotations.SerializedName("has_more")    val hasMore: Boolean = false,
    @com.google.gson.annotations.SerializedName("next_cursor") val nextCursor: String? = null,
    @com.google.gson.annotations.SerializedName("cache_ttl_ms") val cacheTtlMs: Long = 300_000L,
)
