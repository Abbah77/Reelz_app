package com.reelz.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ── Enums ─────────────────────────────────────────────────────────────────────
enum class MediaType   { MOVIE, TV }
enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, DONE, ERROR }
enum class TransferStatus { IDLE, CONNECTING, TRANSFERRING, DONE, ERROR }
enum class TransferDirection { SEND, RECEIVE }

// ── Core domain models ────────────────────────────────────────────────────────
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
    val genreIds: List<Int> = emptyList(),
    val mediaType: MediaType,
    val adult: Boolean = false,
    val originalLanguage: String = "en",
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
    val runtime: Int?,
    val genres: List<Genre>,
    val mediaType: MediaType,
    val status: String?,
    val tagline: String?,
    val seasons: List<Season> = emptyList(),
    val numberOfSeasons: Int = 0,
    val numberOfEpisodes: Int = 0,
    val cast: List<CastMember> = emptyList(),
    val trailerKey: String? = null,
    val imdbId: String? = null,
    val spokenLanguages: List<String> = emptyList(),
    val productionCountries: List<String> = emptyList(),
    val budget: Long = 0,
    val revenue: Long = 0,
    val similar: List<Media> = emptyList(),
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
    val runtime: Int?,
    val voteAverage: Double,
)

data class CastMember(
    val id: Int,
    val name: String,
    val character: String,
    val profilePath: String?,
    val order: Int,
)

data class HomeSection(val title: String, val items: List<Media>)

// ── Stream ────────────────────────────────────────────────────────────────────
data class StreamResult(
    val url: String,
    val isHls: Boolean,
    val quality: String = "Auto",
    val headers: Map<String, String> = emptyMap(),
    val referer: String = "",
    val origin: String = "",
    val sourceName: String = "",
    val subtitles: List<Subtitle> = emptyList(),
    val qualities: List<QualityTrack> = emptyList(),
)

data class Subtitle(val url: String, val language: String, val label: String)

data class QualityTrack(
    val label: String,
    val url: String,
    val bandwidth: Long = 0,
    /** Estimated file size in bytes — populated during M3U8 parsing */
    val estimatedSizeBytes: Long = 0,
)

// ── Room entities ─────────────────────────────────────────────────────────────
@Entity(tableName = "watchlist")
data class WatchlistItem(
    @PrimaryKey val tmdbId: Int,
    val title: String,
    val posterPath: String?,
    val mediaType: String,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "watch_history")
data class WatchHistory(
    @PrimaryKey val key: String,  // "{tmdbId}_{season}_{episode}"
    val tmdbId: Int,
    val title: String,
    val posterPath: String?,
    val mediaType: String,
    val season: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watchedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "liked_media")
data class LikedItem(
    @PrimaryKey val tmdbId: Int,
    val title: String,
    val posterPath: String?,
    val mediaType: String,
    val likedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "cached_media")
@TypeConverters(MediaConverters::class)
data class CachedMedia(
    @PrimaryKey val tmdbId: Int,
    val title: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseDate: String?,
    val voteAverage: Double,
    val popularity: Double,
    val genreIds: String = "[]",  // JSON list
    val mediaType: String,
    val cachedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "downloads")
@TypeConverters(MediaConverters::class)
data class DownloadItem(
    @PrimaryKey val id: String,  // UUID
    val tmdbId: Int,
    val title: String,
    val posterPath: String?,
    val mediaType: String,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeName: String = "",
    val quality: String = "720p",
    val filePath: String = "",
    val sizeBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: String = DownloadStatus.QUEUED.name,
    val streamUrl: String = "",
    val headers: String = "{}",  // JSON map
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,

    // ── Download tracking ──────────────────────────────────────────────
    /** Bytes/sec, updated every second during active download */
    val networkSpeedBps: Long = 0,
    /** For HLS: how many .ts segments have been saved to disk */
    val segmentsDone: Int = 0,
    /** For HLS: total segment count in the playlist */
    val totalSegments: Int = 0,
    /**
     * For HLS partial-play: path to the temporary segment directory.
     * Kept until download is DONE (then segments are merged and dir deleted).
     */
    val segmentDir: String = "",
    /**
     * Local .m3u8 playlist path that points at already-downloaded segments.
     * ExoPlayer can open this for offline partial-playback.
     */
    val localPlaylistPath: String = "",

    // ── NEW in v3 ──────────────────────────────────────────────────────
    /**
     * JSON-serialised List<QualityTrack>. Stored so resume can pick
     * the correct variant URL without re-parsing the master playlist.
     */
    val qualityTracksJson: String = "[]",
    /**
     * When true, the service MUST call engine.resolve() to get a fresh
     * CDN URL before downloading/resuming. The stored streamUrl has expired.
     * Set to true whenever status transitions to PAUSED.
     */
    val resolveRequired: Boolean = true,
)

@Entity(tableName = "transfer_history")
data class TransferRecord(
    @PrimaryKey val id: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val direction: String,  // SEND / RECEIVE
    val peerName: String,
    val peerIp: String,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
)

// ── Type converters ───────────────────────────────────────────────────────────
class MediaConverters {
    private val gson = Gson()
    @TypeConverter fun fromIntList(v: List<Int>?): String = gson.toJson(v ?: emptyList<Int>())
    @TypeConverter fun toIntList(v: String?): List<Int> =
        if (v.isNullOrBlank()) emptyList()
        else gson.fromJson(v, object : TypeToken<List<Int>>() {}.type)
    @TypeConverter fun fromMap(v: Map<String, String>?): String = gson.toJson(v ?: emptyMap<String,String>())
    @TypeConverter fun toMap(v: String?): Map<String, String> =
        if (v.isNullOrBlank()) emptyMap()
        else gson.fromJson(v, object : TypeToken<Map<String, String>>() {}.type)
}
