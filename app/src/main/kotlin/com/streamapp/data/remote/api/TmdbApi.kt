package com.streamapp.data.remote.api

import com.streamapp.data.remote.dto.*
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    @GET("trending/all/week")
    suspend fun trending(@Query("api_key") key: String): TmdbPageDto<MultiSearchResultDto>

    @GET("movie/popular")
    suspend fun popularMovies(@Query("api_key") key: String, @Query("page") page: Int = 1): TmdbPageDto<MovieDto>

    @GET("tv/popular")
    suspend fun popularTv(@Query("api_key") key: String, @Query("page") page: Int = 1): TmdbPageDto<TvDto>

    @GET("movie/top_rated")
    suspend fun topRatedMovies(@Query("api_key") key: String, @Query("page") page: Int = 1): TmdbPageDto<MovieDto>

    @GET("tv/top_rated")
    suspend fun topRatedTv(@Query("api_key") key: String, @Query("page") page: Int = 1): TmdbPageDto<TvDto>

    @GET("movie/{id}")
    suspend fun movieDetail(@Path("id") id: Int, @Query("api_key") key: String): MovieDto

    @GET("tv/{id}")
    suspend fun tvDetail(@Path("id") id: Int, @Query("api_key") key: String): TvDto

    @GET("tv/{id}/season/{season}")
    suspend fun seasonDetail(
        @Path("id") id: Int,
        @Path("season") season: Int,
        @Query("api_key") key: String
    ): SeasonDetailDto

    @GET("search/multi")
    suspend fun searchMulti(
        @Query("api_key") key: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("include_adult") adult: Boolean = false
    ): TmdbPageDto<MultiSearchResultDto>

    @GET("movie/{id}/recommendations")
    suspend fun movieRecommendations(@Path("id") id: Int, @Query("api_key") key: String): TmdbPageDto<MovieDto>

    @GET("tv/{id}/recommendations")
    suspend fun tvRecommendations(@Path("id") id: Int, @Query("api_key") key: String): TmdbPageDto<TvDto>

    @GET("movie/{id}/similar")
    suspend fun similarMovies(@Path("id") id: Int, @Query("api_key") key: String): TmdbPageDto<MovieDto>

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("api_key") key: String,
        @Query("with_genres") genreId: Int? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1
    ): TmdbPageDto<MovieDto>

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("api_key") key: String,
        @Query("with_genres") genreId: Int? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("page") page: Int = 1
    ): TmdbPageDto<TvDto>
}
