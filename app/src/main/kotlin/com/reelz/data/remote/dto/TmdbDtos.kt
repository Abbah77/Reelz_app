package com.reelz.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.reelz.data.model.*

// ── TMDB API DTOs ─────────────────────────────────────────────────────────────

data class TmdbPagedResponse<T>(
    @SerializedName("page")         val page: Int,
    @SerializedName("results")      val results: List<T>,
    @SerializedName("total_pages")  val totalPages: Int,
    @SerializedName("total_results")val totalResults: Int,
)

data class TmdbMediaDto(
    @SerializedName("id")            val id: Int,
    @SerializedName("title")         val title: String?,
    @SerializedName("name")          val name: String?,
    @SerializedName("overview")      val overview: String?,
    @SerializedName("poster_path")   val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("release_date")  val releaseDate: String?,
    @SerializedName("first_air_date")val firstAirDate: String?,
    @SerializedName("vote_average")  val voteAverage: Double,
    @SerializedName("vote_count")    val voteCount: Int,
    @SerializedName("popularity")    val popularity: Double,
    @SerializedName("genre_ids")     val genreIds: List<Int>?,
    @SerializedName("media_type")    val mediaType: String?,
    @SerializedName("adult")         val adult: Boolean = false,
) {
    fun toDomain(forcedType: MediaType? = null): Media {
        val resolvedType = forcedType ?: when (mediaType?.lowercase()) {
            "tv"    -> MediaType.TV
            "movie" -> MediaType.MOVIE
            else    -> if (name != null) MediaType.TV else MediaType.MOVIE
        }
        return Media(
            id           = id,
            tmdbId       = id,
            title        = title ?: name ?: "Unknown",
            overview     = overview ?: "",
            posterPath   = posterPath,
            backdropPath = backdropPath,
            releaseDate  = releaseDate ?: firstAirDate,
            voteAverage  = voteAverage,
            voteCount    = voteCount,
            popularity   = popularity,
            genreIds     = genreIds ?: emptyList(),
            mediaType    = resolvedType,
            adult        = adult,
        )
    }
}

data class TmdbMovieDetailDto(
    @SerializedName("id")             val id: Int,
    @SerializedName("title")          val title: String,
    @SerializedName("overview")       val overview: String?,
    @SerializedName("poster_path")    val posterPath: String?,
    @SerializedName("backdrop_path")  val backdropPath: String?,
    @SerializedName("release_date")   val releaseDate: String?,
    @SerializedName("vote_average")   val voteAverage: Double,
    @SerializedName("vote_count")     val voteCount: Int,
    @SerializedName("runtime")        val runtime: Int?,
    @SerializedName("genres")         val genres: List<TmdbGenreDto>?,
    @SerializedName("status")         val status: String?,
    @SerializedName("tagline")        val tagline: String?,
    @SerializedName("imdb_id")        val imdbId: String?,
    @SerializedName("credits")        val credits: TmdbCreditsDto?,
    @SerializedName("videos")         val videos: TmdbVideosDto?,
) {
    fun toDomain(): MediaDetail = MediaDetail(
        id               = id,
        tmdbId           = id,
        title            = title,
        overview         = overview ?: "",
        posterPath       = posterPath,
        backdropPath     = backdropPath,
        releaseDate      = releaseDate,
        voteAverage      = voteAverage,
        voteCount        = voteCount,
        runtime          = runtime,
        genres           = genres?.map { Genre(it.id, it.name) } ?: emptyList(),
        mediaType        = MediaType.MOVIE,
        status           = status,
        tagline          = tagline,
        imdbId           = imdbId,
        cast             = credits?.cast?.take(20)?.map { it.toDomain() } ?: emptyList(),
        trailerKey       = videos?.results
            ?.firstOrNull { it.type == "Trailer" && it.site == "YouTube" }?.key,
    )
}

data class TmdbTvDetailDto(
    @SerializedName("id")               val id: Int,
    @SerializedName("name")             val name: String,
    @SerializedName("overview")         val overview: String?,
    @SerializedName("poster_path")      val posterPath: String?,
    @SerializedName("backdrop_path")    val backdropPath: String?,
    @SerializedName("first_air_date")   val firstAirDate: String?,
    @SerializedName("vote_average")     val voteAverage: Double,
    @SerializedName("vote_count")       val voteCount: Int,
    @SerializedName("genres")           val genres: List<TmdbGenreDto>?,
    @SerializedName("status")           val status: String?,
    @SerializedName("tagline")          val tagline: String?,
    @SerializedName("number_of_seasons")    val numberOfSeasons: Int,
    @SerializedName("number_of_episodes")   val numberOfEpisodes: Int,
    @SerializedName("seasons")          val seasons: List<TmdbSeasonDto>?,
    @SerializedName("credits")          val credits: TmdbCreditsDto?,
    @SerializedName("videos")           val videos: TmdbVideosDto?,
    @SerializedName("external_ids")     val externalIds: TmdbExternalIdsDto?,
) {
    fun toDomain(): MediaDetail = MediaDetail(
        id               = id,
        tmdbId           = id,
        title            = name,
        overview         = overview ?: "",
        posterPath       = posterPath,
        backdropPath     = backdropPath,
        releaseDate      = firstAirDate,
        voteAverage      = voteAverage,
        voteCount        = voteCount,
        runtime          = null,
        genres           = genres?.map { Genre(it.id, it.name) } ?: emptyList(),
        mediaType        = MediaType.TV,
        status           = status,
        tagline          = tagline,
        numberOfSeasons  = numberOfSeasons,
        numberOfEpisodes = numberOfEpisodes,
        seasons          = seasons?.filter { it.seasonNumber > 0 }
                                   ?.map { it.toDomain() } ?: emptyList(),
        cast             = credits?.cast?.take(20)?.map { it.toDomain() } ?: emptyList(),
        trailerKey       = videos?.results
            ?.firstOrNull { it.type == "Trailer" && it.site == "YouTube" }?.key,
        imdbId           = externalIds?.imdbId,
    )
}

data class TmdbSeasonDetailDto(
    @SerializedName("id")          val id: Int,
    @SerializedName("name")        val name: String,
    @SerializedName("season_number") val seasonNumber: Int,
    @SerializedName("episodes")    val episodes: List<TmdbEpisodeDto>?,
)

data class TmdbEpisodeDto(
    @SerializedName("id")               val id: Int,
    @SerializedName("episode_number")   val episodeNumber: Int,
    @SerializedName("season_number")    val seasonNumber: Int,
    @SerializedName("name")             val name: String,
    @SerializedName("overview")         val overview: String?,
    @SerializedName("still_path")       val stillPath: String?,
    @SerializedName("air_date")         val airDate: String?,
    @SerializedName("runtime")          val runtime: Int?,
    @SerializedName("vote_average")     val voteAverage: Double,
) {
    fun toDomain() = Episode(
        id            = id,
        episodeNumber = episodeNumber,
        seasonNumber  = seasonNumber,
        name          = name,
        overview      = overview ?: "",
        stillPath     = stillPath,
        airDate       = airDate,
        runtime       = runtime,
        voteAverage   = voteAverage,
    )
}

data class TmdbGenreDto(
    @SerializedName("id")   val id: Int,
    @SerializedName("name") val name: String,
)

data class TmdbSeasonDto(
    @SerializedName("id")            val id: Int,
    @SerializedName("season_number") val seasonNumber: Int,
    @SerializedName("name")          val name: String,
    @SerializedName("episode_count") val episodeCount: Int,
    @SerializedName("poster_path")   val posterPath: String?,
    @SerializedName("overview")      val overview: String?,
    @SerializedName("air_date")      val airDate: String?,
) {
    fun toDomain() = Season(
        id           = id,
        seasonNumber = seasonNumber,
        name         = name,
        episodeCount = episodeCount,
        posterPath   = posterPath,
        overview     = overview,
        airDate      = airDate,
    )
}

data class TmdbCreditsDto(
    @SerializedName("cast") val cast: List<TmdbCastDto>?,
)

data class TmdbCastDto(
    @SerializedName("id")           val id: Int,
    @SerializedName("name")         val name: String,
    @SerializedName("character")    val character: String?,
    @SerializedName("profile_path") val profilePath: String?,
    @SerializedName("order")        val order: Int,
) {
    fun toDomain() = CastMember(
        id          = id,
        name        = name,
        character   = character ?: "",
        profilePath = profilePath,
        order       = order,
    )
}

data class TmdbVideosDto(
    @SerializedName("results") val results: List<TmdbVideoDto>?,
)

data class TmdbVideoDto(
    @SerializedName("key")  val key: String,
    @SerializedName("type") val type: String,
    @SerializedName("site") val site: String,
)

data class TmdbExternalIdsDto(
    @SerializedName("imdb_id") val imdbId: String?,
)
