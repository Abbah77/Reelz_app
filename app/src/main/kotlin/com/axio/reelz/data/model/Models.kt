package com.axio.reelz.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ── Gson helpers (R8-safe) ────────────────────────────────────────────────────
// Using inline reified so the TypeToken subclass is materialized at each
// call-site during compilation, rather than as an anonymous inner class whose
// generic signature R8 might erase in release builds.
internal inline fun <reified T> Gson.fromJsonSafe(json: String?): T? =
    if (json.isNullOrBlank()) null
    else fromJson(json, object : TypeToken<T>() {}.type)

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

// ── Catalog page — the unified scroll unit ─────────────────────────────────────
// Represents one page of the infinite feed regardless of its source.
// The UI never asks "is this cache or TMDB?" — it just renders items.
sealed class CatalogPage {
    /** Items that were already in Room (served offline-first, ≤ 10ms) */
    data class FromCache(val items: List<Media>, val pageIndex: Int) : CatalogPage()
    /** Items fetched fresh from TMDB and then written to Room */
    data class FromNetwork(val items: List<Media>, val pageIndex: Int) : CatalogPage()
    /** No more items available (cache AND network exhausted) */
    object Exhausted : CatalogPage()
}

// ── Shorts ────────────────────────────────────────────────────────────────────
data class ShortVideo(
    val id: String,
    val title: String,
    val author: String,
    val community: String,
    val hlsUrl: String,
    val audioUrl: String?,
    val fallbackUrl: String,
    val thumbnail: String,
    val ups: Int,
    val duration: Int,
    val hasAudio: Boolean,
    val width: Int,
    val height: Int,
)

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
    val estimatedSizeBytes: Long = 0,
    val isSizeExact: Boolean = false,
)

// ── Persistent Download Subtitle ─────────────────────────────────────────────
@Entity(tableName = "download_subtitles")
data class DownloadSubtitle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val downloadId: String,
    val tmdbId: Int,
    val season: Int = 0,
    val episode: Int = 0,
    val language: String,
    val label: String,
    val localFilePath: String,
    val isEnabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
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

@Entity(tableName = "recent_searches")
data class RecentSearch(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "saved_videos")
data class SavedVideoItem(
    @PrimaryKey val tmdbId: Int,
    val title: String,
    val posterPath: String?,
    val mediaType: String,
    val savedAt: Long = System.currentTimeMillis(),
)

/**
 * Central metadata store — the heart of the local-first catalog.
 *
 * Stores everything needed to display a media card without a network call.
 * Two independent caches use this table:
 *   source = "catalog"  → home feed + explore (target: ~10K rows)
 *   source = "search"   → search-opened items (capped at 200)
 *
 * Coil manages poster bytes separately (~350MB disk cache).
 * Room only stores the URL/path — never raw image bytes.
 *
 * Row lifecycle:
 *   INSERT/REPLACE on TMDB fetch → grows toward ~10K target
 *   Soft eviction when > 10,500 → oldest-by-lastAccessedAt trimmed back to 10K
 *   Hard cap enforced in EvictionWorker (daily WorkManager job)
 */
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
    val genreIds: String = "[]",              // JSON list — Coil parses, not SQLite
    val mediaType: String,
    val originalLanguage: String = "en",      // Explore language filter
    val voteCount: Int = 0,                   // MetaChip display + sort
    val section: String = "trending",         // which home section this belongs to
    val sectionCachedAt: Long = 0L,           // when this section batch was fetched
    val source: String = "catalog",           // "catalog" | "search"
    val lastAccessedAt: Long = System.currentTimeMillis(), // LRU eviction key
    val cachedAt: Long = System.currentTimeMillis(),
    // ── Infinite scroll pagination cursor ─────────────────────────────────────
    // catalogPage tracks which TMDB discover page this item came from so the
    // scroll engine knows exactly where to resume TMDB calls after cache runs dry.
    val catalogPage: Int = 0,
)

/**
 * Per-section tap counts for personalized feed ordering.
 * Scoring: score = (taps × 0.7) + (recency × 30)
 * New users see DEFAULT_ORDER until they interact.
 */
@Entity(tableName = "section_weights")
data class SectionWeight(
    @PrimaryKey val sectionId: String,
    val taps: Int = 0,
    val lastTappedAt: Long = 0L,
    val manualOrder: Int = 999,
)

/**
 * Tracks the highest TMDB discover page already fetched so the infinite
 * scroll engine knows where to resume after cache runs dry. One row per
 * media type ("movie" | "tv"). Persists across sessions.
 */
@Entity(tableName = "catalog_page_cursor")
data class CatalogPageCursor(
    @PrimaryKey val mediaType: String,   // "movie" | "tv"
    val nextPage: Int = 1,               // next TMDB page to fetch
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "downloads")
@TypeConverters(MediaConverters::class)
data class DownloadItem(
    @PrimaryKey val id: String,
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
    val headers: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val networkSpeedBps: Long = 0,
    val segmentsDone: Int = 0,
    val totalSegments: Int = 0,
    val segmentDir: String = "",
    val localPlaylistPath: String = "",
    val qualityTracksJson: String = "[]",
    val resolveRequired: Boolean = true,
    val watchProgressMs: Long = 0,
    val durationMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val lastSelectedQuality: String = "",
)

@Entity(tableName = "transfer_history")
data class TransferRecord(
    @PrimaryKey val id: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val direction: String,
    val peerName: String,
    val peerIp: String,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "user_session")
data class UserSession(
    @PrimaryKey val uid: String,
    val name: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val isPremium: Boolean = false,
    val plan: String = "",
    val expiresAtMs: Long = 0L,
    val subscribedAtMs: Long = 0L,
    val cachedAtMs: Long = System.currentTimeMillis(),
)

// ── Genre cache ───────────────────────────────────────────────────────────────
@Entity(tableName = "cached_genres")
data class CachedGenre(
    @PrimaryKey val id: Int,
    val name: String,
    val mediaType: String = "movie",
    val cachedAtMs: Long = System.currentTimeMillis(),
)

// ── Type converters ───────────────────────────────────────────────────────────
class MediaConverters {
    private val gson = Gson()
    @TypeConverter fun fromIntList(v: List<Int>?): String = gson.toJson(v ?: emptyList<Int>())
    @TypeConverter fun toIntList(v: String?): List<Int> =
        gson.fromJsonSafe<List<Int>>(v) ?: emptyList()
    @TypeConverter fun fromMap(v: Map<String, String>?): String = gson.toJson(v ?: emptyMap<String,String>())
    @TypeConverter fun toMap(v: String?): Map<String, String> =
        gson.fromJsonSafe<Map<String, String>>(v) ?: emptyMap()
}
