package com.reelz.data.remote.api

import com.reelz.data.remote.dto.*
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    // ── Trending ───────────────────────────────────────────────────────────────
    @GET("trending/all/week")
    suspend fun getTrending(
        @Query("page") page: Int = 1,
    ): TmdbPagedResponse<TmdbMediaDto>

    @GET("trending/movie/week")
    suspend fun getTrendingMovies(@Query("page") page: Int = 1): TmdbPagedResponse<TmdbMediaDto>

    @GET("trending/tv/week")
    suspend fun getTrendingTv(@Query("page") page: Int = 1): TmdbPagedResponse<TmdbMediaDto>

    // ── Discover ───────────────────────────────────────────────────────────────
    @GET("movie/popular")
    suspend fun getPopularMovies(@Query("page") page: Int = 1): TmdbPagedResponse<TmdbMediaDto>

    @GET("tv/popular")
    suspend fun getPopularTv(@Query("page") page: Int = 1): TmdbPagedResponse<TmdbMediaDto>

    @GET("movie/top_rated")
    suspend fun getTopRatedMovies(@Query("page") page: Int = 1): TmdbPagedResponse<TmdbMediaDto>

    @GET("tv/top_rated")
    suspend fun getTopRatedTv(@Query("page") page: Int = 1): TmdbPagedResponse<TmdbMediaDto>

    @GET("movie/now_playing")
    suspend fun getNowPlaying(@Query("page") page: Int = 1): TmdbPagedResponse<TmdbMediaDto>

    @GET("tv/on_the_air")
    suspend fun getOnTheAir(@Query("page") page: Int = 1): TmdbPagedResponse<TmdbMediaDto>

    // ── Detail ─────────────────────────────────────────────────────────────────
    @GET("movie/{id}")
    suspend fun getMovieDetail(
        @Path("id") id: Int,
        @Query("append_to_response") append: String = "credits,videos,external_ids",
    ): TmdbMovieDetailDto

    @GET("tv/{id}")
    suspend fun getTvDetail(
        @Path("id") id: Int,
        @Query("append_to_response") append: String = "credits,videos,external_ids",
    ): TmdbTvDetailDto

    @GET("tv/{id}/season/{season_number}")
    suspend fun getSeasonDetail(
        @Path("id") id: Int,
        @Path("season_number") seasonNumber: Int,
    ): TmdbSeasonDetailDto

    // ── Search ─────────────────────────────────────────────────────────────────
    @GET("search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false,
    ): TmdbPagedResponse<TmdbMediaDto>

    // ── Similar / Recommendations ──────────────────────────────────────────────
    @GET("movie/{id}/recommendations")
    suspend fun getMovieRecommendations(@Path("id") id: Int): TmdbPagedResponse<TmdbMediaDto>

    @GET("tv/{id}/recommendations")
    suspend fun getTvRecommendations(@Path("id") id: Int): TmdbPagedResponse<TmdbMediaDto>

    // ── Genres ─────────────────────────────────────────────────────────────────
    @GET("genre/movie/list")
    suspend fun getMovieGenres(): GenreListDto

    @GET("genre/tv/list")
    suspend fun getTvGenres(): GenreListDto

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("with_genres") genreId: Int,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1,
    ): TmdbPagedResponse<TmdbMediaDto>
}

data class GenreListDto(
    @com.google.gson.annotations.SerializedName("genres") val genres: List<com.reelz.data.remote.dto.TmdbGenreDto>,
)
