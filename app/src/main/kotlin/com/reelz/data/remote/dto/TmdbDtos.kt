package com.reelz.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TmdbPageDto<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @SerializedName("total_pages")   val totalPages: Int = 1,
    @SerializedName("total_results") val totalResults: Int = 0,
)

data class TmdbMovieDto(
    val id: Int = 0,
    val title: String = "",
    val overview: String = "",
    @SerializedName("poster_path")   val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("release_date")  val releaseDate: String? = null,
    @SerializedName("vote_average")  val voteAverage: Double = 0.0,
    @SerializedName("vote_count")    val voteCount: Int = 0,
    val popularity: Double = 0.0,
    @SerializedName("genre_ids")     val genreIds: List<Int> = emptyList(),
    val adult: Boolean = false,
    @SerializedName("original_language") val originalLanguage: String = "en",
)

data class TmdbTvDto(
    val id: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerializedName("poster_path")    val posterPath: String? = null,
    @SerializedName("backdrop_path")  val backdropPath: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("vote_average")   val voteAverage: Double = 0.0,
    @SerializedName("vote_count")     val voteCount: Int = 0,
    val popularity: Double = 0.0,
    @SerializedName("genre_ids")      val genreIds: List<Int> = emptyList(),
    @SerializedName("original_language") val originalLanguage: String = "en",
)

data class TmdbMovieDetailDto(
    val id: Int = 0,
    val title: String = "",
    val overview: String = "",
    @SerializedName("poster_path")    val posterPath: String? = null,
    @SerializedName("backdrop_path")  val backdropPath: String? = null,
    @SerializedName("release_date")   val releaseDate: String? = null,
    @SerializedName("vote_average")   val voteAverage: Double = 0.0,
    @SerializedName("vote_count")     val voteCount: Int = 0,
    val runtime: Int? = null,
    val genres: List<TmdbGenreDto> = emptyList(),
    val status: String? = null,
    val tagline: String? = null,
    val budget: Long = 0,
    val revenue: Long = 0,
    @SerializedName("imdb_id") val imdbId: String? = null,
    val credits: TmdbCreditsDto? = null,
    val videos: TmdbVideosDto? = null,
    @SerializedName("spoken_languages") val spokenLanguages: List<TmdbLanguageDto> = emptyList(),
    @SerializedName("production_countries") val productionCountries: List<TmdbCountryDto> = emptyList(),
    val similar: TmdbPageDto<TmdbMovieDto>? = null,
    @SerializedName("original_language") val originalLanguage: String = "en",
)

data class TmdbTvDetailDto(
    val id: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerializedName("poster_path")    val posterPath: String? = null,
    @SerializedName("backdrop_path")  val backdropPath: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("vote_average")   val voteAverage: Double = 0.0,
    @SerializedName("vote_count")     val voteCount: Int = 0,
    val genres: List<TmdbGenreDto> = emptyList(),
    val status: String? = null,
    val tagline: String? = null,
    val seasons: List<TmdbSeasonDto> = emptyList(),
    @SerializedName("number_of_seasons")  val numberOfSeasons: Int = 0,
    @SerializedName("number_of_episodes") val numberOfEpisodes: Int = 0,
    val credits: TmdbCreditsDto? = null,
    val videos: TmdbVideosDto? = null,
    @SerializedName("external_ids") val externalIds: TmdbExternalIds? = null,
    val similar: TmdbPageDto<TmdbTvDto>? = null,
    @SerializedName("original_language") val originalLanguage: String = "en",
)

data class TmdbGenreDto(val id: Int = 0, val name: String = "")

data class TmdbSeasonDto(
    val id: Int = 0,
    @SerializedName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    @SerializedName("episode_count") val episodeCount: Int = 0,
    @SerializedName("poster_path")   val posterPath: String? = null,
    val overview: String? = null,
    @SerializedName("air_date")      val airDate: String? = null,
)

data class TmdbEpisodeDto(
    val id: Int = 0,
    @SerializedName("episode_number") val episodeNumber: Int = 0,
    @SerializedName("season_number")  val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerializedName("still_path")    val stillPath: String? = null,
    @SerializedName("air_date")      val airDate: String? = null,
    val runtime: Int? = null,
    @SerializedName("vote_average")  val voteAverage: Double = 0.0,
)

data class TmdbSeasonDetailDto(
    val id: Int = 0,
    val episodes: List<TmdbEpisodeDto> = emptyList(),
)

data class TmdbCreditsDto(
    val cast: List<TmdbCastDto> = emptyList(),
)

data class TmdbCastDto(
    val id: Int = 0,
    val name: String = "",
    val character: String = "",
    @SerializedName("profile_path") val profilePath: String? = null,
    val order: Int = 0,
)

data class TmdbVideosDto(val results: List<TmdbVideoDto> = emptyList())

data class TmdbVideoDto(
    val id: String = "",
    val key: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
)

data class TmdbExternalIds(@SerializedName("imdb_id") val imdbId: String? = null)

data class TmdbLanguageDto(
    @SerializedName("english_name") val englishName: String = "",
)

data class TmdbCountryDto(
    val name: String = "",
)

data class TmdbGenreListDto(val genres: List<TmdbGenreDto> = emptyList())
