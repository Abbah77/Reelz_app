package com.streamapp.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.streamapp.data.model.*

data class MovieDto(
    val id: Int,
    val title: String?,
    val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("vote_count") val voteCount: Int?,
    val genres: List<GenreDto>?,
    val runtime: Int?,
    val tagline: String?,
    val status: String?,
    @SerializedName("media_type") val mediaType: String?,
) {
    fun toDomain() = Movie(
        id = id,
        title = title ?: "Untitled",
        overview = overview ?: "",
        posterPath = posterPath,
        backdropPath = backdropPath,
        releaseDate = releaseDate,
        voteAverage = voteAverage ?: 0.0,
        voteCount = voteCount ?: 0,
        genres = genres?.map { it.toDomain() } ?: emptyList(),
        runtime = runtime,
        tagline = tagline,
        status = status,
        mediaType = mediaType ?: "movie",
    )
}

data class TvDto(
    val id: Int,
    val name: String?,
    val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("first_air_date") val firstAirDate: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("number_of_seasons") val numberOfSeasons: Int?,
    @SerializedName("number_of_episodes") val numberOfEpisodes: Int?,
    val genres: List<GenreDto>?,
    val status: String?,
    val tagline: String?,
) {
    fun toDomain() = TvShow(
        id = id,
        name = name ?: "Untitled",
        overview = overview ?: "",
        posterPath = posterPath,
        backdropPath = backdropPath,
        firstAirDate = firstAirDate,
        voteAverage = voteAverage ?: 0.0,
        numberOfSeasons = numberOfSeasons ?: 1,
        numberOfEpisodes = numberOfEpisodes ?: 1,
        genres = genres?.map { it.toDomain() } ?: emptyList(),
        status = status,
        tagline = tagline,
    )
}

data class GenreDto(val id: Int, val name: String) {
    fun toDomain() = Genre(id, name)
}

data class EpisodeDto(
    val id: Int,
    @SerializedName("show_id") val showId: Int?,
    @SerializedName("season_number") val seasonNumber: Int?,
    @SerializedName("episode_number") val episodeNumber: Int?,
    val name: String?,
    val overview: String?,
    @SerializedName("still_path") val stillPath: String?,
    @SerializedName("air_date") val airDate: String?,
    val runtime: Int?,
) {
    fun toDomain(showId: Int) = Episode(
        id = id,
        showId = showId,
        seasonNumber = seasonNumber ?: 1,
        episodeNumber = episodeNumber ?: 1,
        name = name ?: "Episode $episodeNumber",
        overview = overview,
        stillPath = stillPath,
        airDate = airDate,
        runtime = runtime,
    )
}

data class SeasonDetailDto(
    @SerializedName("season_number") val seasonNumber: Int?,
    val name: String?,
    @SerializedName("episode_count") val episodeCount: Int?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("air_date") val airDate: String?,
    val episodes: List<EpisodeDto>?,
)

data class TmdbPageDto<T>(
    val page: Int?,
    val results: List<T>?,
    @SerializedName("total_pages") val totalPages: Int?,
    @SerializedName("total_results") val totalResults: Int?,
)

data class MultiSearchResultDto(
    val id: Int?,
    val title: String?,
    val name: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("media_type") val mediaType: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("first_air_date") val firstAirDate: String?,
) {
    fun toMediaItem() = MediaItem(
        id = id ?: 0,
        title = title ?: name ?: "Untitled",
        posterPath = posterPath,
        backdropPath = backdropPath,
        voteAverage = voteAverage ?: 0.0,
        mediaType = mediaType ?: "movie",
        releaseDate = releaseDate ?: firstAirDate,
    )
}

fun MovieDto.toMediaItem() = MediaItem(
    id = id,
    title = title ?: "Untitled",
    posterPath = posterPath,
    backdropPath = backdropPath,
    voteAverage = voteAverage ?: 0.0,
    mediaType = "movie",
    releaseDate = releaseDate,
)

fun TvDto.toMediaItem() = MediaItem(
    id = id,
    title = name ?: "Untitled",
    posterPath = posterPath,
    backdropPath = backdropPath,
    voteAverage = voteAverage ?: 0.0,
    mediaType = "tv",
    releaseDate = firstAirDate,
)
