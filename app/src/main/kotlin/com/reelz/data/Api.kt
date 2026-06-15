package com.reelz.data

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path

// ── Backend models ────────────────────────────────────────────────────────────

data class MediaItem(
    val id: String = "",
    val title: String = "",
    val thumbnail: String = "",
    @SerializedName("poster") val poster: String = "",
    val year: String = "",
    val type: String = "",   // "movie" or "tv"
    val description: String = "",
    val genre: String = "",
)

data class StreamResponse(
    val url: String = "",
    val type: String = "",                         // "hls" or "mp4"
    val headers: Map<String, String> = emptyMap(),
    val referer: String = "",
)

data class TvDetail(
    val id: String = "",
    val title: String = "",
    val thumbnail: String = "",
    val poster: String = "",
    val seasons: List<SeasonInfo> = emptyList(),
)

data class SeasonInfo(
    val season: Int = 1,
    @SerializedName("episode_count") val episodeCount: Int = 1,
)

// ── Retrofit interface ────────────────────────────────────────────────────────

interface ReelzApi {

    /** List all available movies */
    @GET("movies")
    suspend fun getMovies(): List<MediaItem>

    /** List all available TV shows */
    @GET("tv")
    suspend fun getTvShows(): List<MediaItem>

    /** Stream a movie by its id */
    @GET("movie/{id}")
    suspend fun getMovieStream(@Path("id") id: String): StreamResponse

    /** Stream a TV episode */
    @GET("tv/{id}/{season}/{episode}")
    suspend fun getTvStream(
        @Path("id") id: String,
        @Path("season") season: Int,
        @Path("episode") episode: Int,
    ): StreamResponse

    /** TV show details (seasons) */
    @GET("tv/{id}")
    suspend fun getTvDetail(@Path("id") id: String): TvDetail
}
