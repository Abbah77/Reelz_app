package com.axio.reelz.data.model

// ─────────────────────────────────────────────────────────────────────────────
//  Domain Models — Reelz v3
//
//  Rules:
//   • The app only knows the BACKEND. No TMDB shapes here.
//   • Image URLs are full absolute URLs supplied by the backend.
//   • All cache TTLs live on the server; the app honours Cache-Control /
//     X-Cache-TTL headers or the cacheTtlMs field in the response body.
//   • "Smart" = only cache what the UI actually needs; nothing bulky.
// ─────────────────────────────────────────────────────────────────────────────

// ── Enums ─────────────────────────────────────────────────────────────────────
enum class MediaType   { MOVIE, TV }
enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, DONE, ERROR }
enum class TransferStatus { IDLE, CONNECTING, TRANSFERRING, DONE, ERROR }
enum class TransferDirection { SEND, RECEIVE }

// ── Core card model (what every list/grid item needs) ─────────────────────────
data class Media(
    val id: String,              // backend-assigned stable ID
    val title: String,
    val posterUrl: String?,      // absolute URL, backend-signed if needed
    val backdropUrl: String?,
    val releaseYear: String?,
    val rating: Double,
    val mediaType: MediaType,
    val genres: List<String> = emptyList(),
    val language: String = "en",
    val sectionTag: String = "",   // which feed section this came from
)

// ── Detail model (everything for the detail screen) ───────────────────────────
data class MediaDetail(
    val id: String,
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val releaseYear: String?,
    val rating: Double,
    val runtime: Int?,            // minutes for movies
    val mediaType: MediaType,
    val genres: List<String>,
    val status: String?,
    val tagline: String?,
    val seasons: List<Season> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val trailerUrl: String? = null,
    val similar: List<Media> = emptyList(),
    val imdbId: String? = null,
    val spokenLanguages: List<String> = emptyList(),
)

data class Season(
    val id: String,
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val posterUrl: String?,
    val overview: String?,
    val airDate: String?,
)

data class Episode(
    val id: String,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val name: String,
    val overview: String,
    val stillUrl: String?,
    val airDate: String?,
    val runtime: Int?,
    val rating: Double,
)

data class CastMember(
    val id: String,
    val name: String,
    val character: String,
    val photoUrl: String?,
    val order: Int,
)

// ── Feed — what the home screen receives ──────────────────────────────────────
data class FeedSection(
    val id: String,           // stable section key e.g. "trending"
    val title: String,        // display label e.g. "🔥 Trending Now"
    val items: List<Media>,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

// ── Explore filter options from backend ───────────────────────────────────────
data class Genre(val id: String, val name: String)

// ── Stream result ─────────────────────────────────────────────────────────────
data class StreamResult(
    val url: String,
    val isHls: Boolean,
    val quality: String = "Auto",
    val headers: Map<String, String> = emptyMap(),
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
)

// ── Shorts ────────────────────────────────────────────────────────────────────
data class ShortVideo(
    val id: String,
    val title: String,
    val author: String,
    val hlsUrl: String,
    val fallbackUrl: String,
    val thumbnail: String,
    val duration: Int,
    val width: Int,
    val height: Int,
)

// ── Download ──────────────────────────────────────────────────────────────────
data class DownloadItem(
    val id: String,
    val mediaId: String,
    val title: String,
    val posterUrl: String?,
    val mediaType: String,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeName: String = "",
    val quality: String = "720p",
    val filePath: String = "",
    val sizeBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val streamUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val segmentsDone: Int = 0,
    val totalSegments: Int = 0,
    val watchProgressMs: Long = 0,
    val durationMs: Long = 0,
    val lastPlayedAt: Long = 0,
)

// ── User session ──────────────────────────────────────────────────────────────
data class UserSession(
    val uid: String,
    val name: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val isPremium: Boolean = false,
    val plan: String = "",
    val expiresAtMs: Long = 0L,
    val cachedAtMs: Long = System.currentTimeMillis(),
)

// ── Watched progress (local-only, not synced) ─────────────────────────────────
data class WatchProgress(
    val mediaId: String,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long = System.currentTimeMillis(),
) {
    val percentWatched: Float
        get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val isFinished: Boolean
        get() = percentWatched >= 0.90f
}
