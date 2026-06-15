package com.reelz.data.remote.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

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
    val remaining: Int = 0,
    val message: String = "",
)

interface OpenSubtitlesApi {

    @GET("subtitles")
    suspend fun searchSubtitles(
        @Header("Api-Key")        apiKey: String,
        @Header("User-Agent")     userAgent: String,
        @Query("tmdb_id")         tmdbId: Int,
        @Query("type")            type: String,
        @Query("languages")       languages: String? = null,
        @Query("season_number")   season: Int? = null,
        @Query("episode_number")  episode: Int? = null,
        @Query("order_by")        orderBy: String = "download_count",
        @Query("order_direction") orderDirection: String = "desc",
    ): OsSearchResponse

    @POST("download")
    suspend fun requestDownload(
        @Header("Api-Key")    apiKey: String,
        @Header("User-Agent") userAgent: String,
        @Body                 body: OsDownloadRequest,
    ): OsDownloadResponse
}
