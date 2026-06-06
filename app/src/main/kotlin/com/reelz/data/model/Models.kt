package com.reelz.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// ── Media type ────────────────────────────────────────────────────────────────
enum class MediaType { MOVIE, TV }

// ── Core domain model ─────────────────────────────────────────────────────────
data class Media(
    val id: Int,
    val tmdbId: Int,
    val title: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseDate: String?,
    val voteAverage: Double,
    val voteCount: Int,
    val popularity: Double,
    val genreIds: List<Int>,
    val mediaType: MediaType,
    val adult: Boolean = false,
)

data class MediaDetail(
    val id: Int,
    val tmdbId: Int,
    val title: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseDate: String?,
    val voteAverage: Double,
    val voteCount: Int,
    val runtime: Int?,                          // minutes for movies
    val genres: List<Genre>,
    val mediaType: MediaType,
    val status: String?,
    val tagline: String?,
    val seasons: List<Season> = emptyList(),    // TV only
    val numberOfSeasons: Int = 0,
    val numberOfEpisodes: Int = 0,
    val cast: List<CastMember> = emptyList(),
    val trailerKey: String? = null,
    val imdbId: String? = null,
)

data class Genre(val id: Int, val name: String)

data class Season(
    val id: Int,
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val posterPath: String?,
    val overview: String?,
    val airDate: String?,
)

data class Episode(
    val id: Int,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val name: String,
    val overview: String,
    val stillPath: String?,
    val airDate: String?,
    val runtime: Int?,        // minutes — used for pre-buffering calculation
    val voteAverage: Double,
)

data class CastMember(
    val id: Int,
    val name: String,
    val character: String,
    val profilePath: String?,
    val order: Int,
)

// ── Stream result ─────────────────────────────────────────────────────────────
data class StreamResult(
    val url: String,                  // .m3u8 or .mp4
    val isHls: Boolean,               // true = HLS, false = direct MP4
    val quality: String = "Auto",     // "1080p", "720p", "Auto" etc.
    val headers: Map<String, String> = emptyMap(),
    val referer: String = "",
    val origin: String = "",
    val sourceName: String = "",
    val subtitles: List<Subtitle> = emptyList(),
)

data class Subtitle(
    val url: String,
    val language: String,
    val label: String,
)

// ── Watchlist Room entity ─────────────────────────────────────────────────────
@Entity(tableName = "watchlist")
data class WatchlistItem(
    @PrimaryKey val tmdbId: Int,
    val title: String,
    val posterPath: String?,
    val mediaType: String,     // "MOVIE" | "TV"
    val addedAt: Long = System.currentTimeMillis(),
)

// ── Watch history Room entity ─────────────────────────────────────────────────
@Entity(tableName = "watch_history")
data class WatchHistory(
    @PrimaryKey val key: String,          // "{tmdbId}_{season}_{episode}"
    val tmdbId: Int,
    val title: String,
    val posterPath: String?,
    val mediaType: String,
    val season: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0,             // resume position
    val durationMs: Long = 0,
    val watchedAt: Long = System.currentTimeMillis(),
)

// ── Homepage sections ─────────────────────────────────────────────────────────
data class HomeSection(
    val title: String,
    val items: List<Media>,
)
