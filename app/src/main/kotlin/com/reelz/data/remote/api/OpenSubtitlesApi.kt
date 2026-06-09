package com.reelz.data.remote.api

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

// ─────────────────────────────────────────────────────────────────────────────
// OpenSubtitles REST API v1
// Docs: https://opensubtitles.stoplight.io/docs/opensubtitles-api
// Free tier: 5 req/s, 200 downloads/day, 40 searches/day
// ─────────────────────────────────────────────────────────────────────────────

data class OsSearchResponse(
    val total_count: Int = 0,
    val data: List<OsSubtitleItem> = emptyList(),
)

data class OsSubtitleItem(
    val id: String = "",
    val attributes: OsAttributes = OsAttributes(),
)

data class OsAttributes(
    val language: String = "",
    val release: String = "",
    val files: List<OsFile> = emptyList(),
    val ai_translated: Boolean = false,
    val machine_translated: Boolean = false,
    val download_count: Int = 0,
    val ratings: Float = 0f,
    val votes: Int = 0,
)

data class OsFile(
    val file_id: Int = 0,
    val file_name: String? = null,
)

data class OsDownloadRequest(
    val file_id: Int,
    val sub_format: String = "srt",
)

data class OsDownloadResponse(
    val link: String = "",
    val file_name: String = "",
    val requests: Int = 0,
    val remaining: Int = 0,
    val message: String = "",
    val reset_time: String = "",
    val reset_time_utc: String = "",
)

interface OpenSubtitlesApi {

    /**
     * Search subtitles by TMDB id.
     * For movies: type=movie, tmdb_id=<id>
     * For TV: type=episode, tmdb_id=<show_id>, season_number=<s>, episode_number=<ep>
     */
    @GET("subtitles")
    suspend fun searchSubtitles(
        @Header("Api-Key")       apiKey: String,
        @Header("User-Agent")    userAgent: String,
        @Query("tmdb_id")        tmdbId: Int,
        @Query("type")           type: String,           // "movie" | "episode"
        @Query("languages")      languages: String? = null, // e.g. "en,fr" — null = all
        @Query("season_number")  season: Int? = null,
        @Query("episode_number") episode: Int? = null,
        @Query("order_by")       orderBy: String = "download_count",
        @Query("order_direction") orderDirection: String = "desc",
    ): OsSearchResponse

    /**
     * Get a temporary download URL for a subtitle file.
     * This costs 1 download credit per call.
     * Use searchSubtitles first, then pass file_id from OsFile.
     */
    @retrofit2.http.POST("download")
    suspend fun requestDownload(
        @Header("Api-Key")    apiKey: String,
        @Header("User-Agent") userAgent: String,
        @retrofit2.http.Body  body: OsDownloadRequest,
    ): OsDownloadResponse
}
