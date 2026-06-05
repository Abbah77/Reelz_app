package com.streamapp.data.model

data class Movie(
    val id: Int,
    val title: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseDate: String?,
    val voteAverage: Double,
    val voteCount: Int,
    val genres: List<Genre> = emptyList(),
    val runtime: Int? = null,
    val tagline: String? = null,
    val status: String? = null,
    val mediaType: String = "movie",
) {
    val posterUrl get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
    val backdropUrl get() = backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
    val year get() = releaseDate?.take(4) ?: ""
    val ratingFormatted get() = "%.1f".format(voteAverage)
}

data class TvShow(
    val id: Int,
    val name: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val firstAirDate: String?,
    val voteAverage: Double,
    val numberOfSeasons: Int = 1,
    val numberOfEpisodes: Int = 1,
    val genres: List<Genre> = emptyList(),
    val status: String? = null,
    val tagline: String? = null,
) {
    val posterUrl get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
    val backdropUrl get() = backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
    val year get() = firstAirDate?.take(4) ?: ""
    val ratingFormatted get() = "%.1f".format(voteAverage)
}

data class Genre(val id: Int, val name: String)

data class Episode(
    val id: Int,
    val showId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val overview: String?,
    val stillPath: String?,
    val airDate: String?,
    val runtime: Int?,
    var streamUrl: String? = null,        // filled by scanner
    var isUnlocked: Boolean = false,
) {
    val stillUrl get() = stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
    val label get() = "S${seasonNumber.toString().padStart(2,'0')}E${episodeNumber.toString().padStart(2,'0')}"
}

data class Season(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val posterPath: String?,
    val airDate: String?,
    val episodes: List<Episode> = emptyList(),
)

// Stream resolution result from the scanner
sealed class StreamResult {
    data class Found(val url: String, val source: String) : StreamResult()
    object NotFound : StreamResult()
    data class Error(val message: String) : StreamResult()
    object Loading : StreamResult()
}

// Combined media item for the feed (movies + shows together)
data class MediaItem(
    val id: Int,
    val title: String,
    val posterPath: String?,
    val backdropPath: String?,
    val voteAverage: Double,
    val mediaType: String,   // "movie" | "tv"
    val releaseDate: String? = null,
) {
    val posterUrl get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
    val backdropUrl get() = backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
    val year get() = releaseDate?.take(4) ?: ""
}

data class TmdbPage<T>(
    val page: Int,
    val results: List<T>,
    val totalPages: Int,
    val totalResults: Int,
)
