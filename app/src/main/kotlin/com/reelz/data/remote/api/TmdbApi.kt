package com.reelz.data.remote.api

import com.reelz.data.remote.dto.*
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    // ── Movies ─────────────────────────────────────────────────────────────────
    @GET("trending/movie/week")
    suspend fun getTrendingMovies(@Query("page") page: Int = 1): TmdbPageDto<TmdbMovieDto>

    @GET("movie/popular")
    suspend fun getPopularMovies(@Query("page") page: Int = 1): TmdbPageDto<TmdbMovieDto>

    @GET("movie/top_rated")
    suspend fun getTopRatedMovies(@Query("page") page: Int = 1): TmdbPageDto<TmdbMovieDto>

    @GET("movie/now_playing")
    suspend fun getNowPlayingMovies(@Query("page") page: Int = 1): TmdbPageDto<TmdbMovieDto>

    @GET("movie/upcoming")
    suspend fun getUpcomingMovies(@Query("page") page: Int = 1): TmdbPageDto<TmdbMovieDto>

    @GET("movie/{id}")
    suspend fun getMovieDetail(
        @Path("id") id: Int,
    ): TmdbMovieDetailDto

    @GET("movie/{id}")
    suspend fun getMovieExtras(
        @Path("id") id: Int,
        @Query("append_to_response") append: String = "credits,videos,similar",
    ): TmdbMovieDetailDto

    // ── TV Shows ───────────────────────────────────────────────────────────────
    @GET("trending/tv/week")
    suspend fun getTrendingTv(@Query("page") page: Int = 1): TmdbPageDto<TmdbTvDto>

    @GET("tv/popular")
    suspend fun getPopularTv(@Query("page") page: Int = 1): TmdbPageDto<TmdbTvDto>

    @GET("tv/top_rated")
    suspend fun getTopRatedTv(@Query("page") page: Int = 1): TmdbPageDto<TmdbTvDto>

    @GET("tv/on_the_air")
    suspend fun getOnAirTv(@Query("page") page: Int = 1): TmdbPageDto<TmdbTvDto>

    @GET("tv/airing_today")
    suspend fun getAiringToday(@Query("page") page: Int = 1): TmdbPageDto<TmdbTvDto>

    @GET("tv/{id}")
    suspend fun getTvDetail(
        @Path("id") id: Int,
    ): TmdbTvDetailDto

    @GET("tv/{id}")
    suspend fun getTvExtras(
        @Path("id") id: Int,
        @Query("append_to_response") append: String = "credits,videos,similar,external_ids",
    ): TmdbTvDetailDto

    @GET("tv/{id}/season/{season}")
    suspend fun getSeasonDetail(
        @Path("id") id: Int,
        @Path("season") season: Int,
    ): TmdbSeasonDetailDto

    // ── Discover / Genre ───────────────────────────────────────────────────────
    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("with_genres")            genreId: Int? = null,
        @Query("sort_by")                sortBy: String = "popularity.desc",
        @Query("page")                   page: Int = 1,
        @Query("with_original_language") language: String? = null,
    ): TmdbPageDto<TmdbMovieDto>

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("with_genres")            genreId: Int? = null,
        @Query("sort_by")                sortBy: String = "popularity.desc",
        @Query("page")                   page: Int = 1,
        @Query("with_original_language") language: String? = null,
    ): TmdbPageDto<TmdbTvDto>

    @GET("genre/movie/list")
    suspend fun getMovieGenres(): TmdbGenreListDto

    @GET("genre/tv/list")
    suspend fun getTvGenres(): TmdbGenreListDto

    // ── Search ─────────────────────────────────────────────────────────────────
    @GET("search/movie")
    suspend fun searchMovies(@Query("query") q: String, @Query("page") page: Int = 1): TmdbPageDto<TmdbMovieDto>

    @GET("search/tv")
    suspend fun searchTv(@Query("query") q: String, @Query("page") page: Int = 1): TmdbPageDto<TmdbTvDto>

    // ── Anime-specific (Japanese animation genre = 16, language = ja) ──────────
    @GET("discover/tv")
    suspend fun getAnime(
        @Query("with_genres")            genreId: Int = 16,
        @Query("with_original_language") lang: String = "ja",
        @Query("sort_by")                sort: String = "popularity.desc",
        @Query("page")                   page: Int = 1,
    ): TmdbPageDto<TmdbTvDto>

    @GET("discover/movie")
    suspend fun getAnimeMovies(
        @Query("with_genres")            genreId: Int = 16,
        @Query("with_original_language") lang: String = "ja",
        @Query("sort_by")                sort: String = "popularity.desc",
        @Query("page")                   page: Int = 1,
    ): TmdbPageDto<TmdbMovieDto>
}
