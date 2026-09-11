package com.axio.reelz.data.model

// ─────────────────────────────────────────────────────────────────────────────
//  Domain Models — Reelz Schema v4  (two-table download architecture)
//
//  Rules:
//   • App only knows the BACKEND. All business logic lives on the server.
//   • Image URLs are full absolute URLs supplied by the backend.
//   • Guest == Free user. Login is opt-in.
//
//  Download flow:
//   DownloadItem  — tracks active/paused/queued/remuxing downloads (downloads table)
//   FileItem      — a clean, playable .mp4 in the user's permanent library (files table)
// ─────────────────────────────────────────────────────────────────────────────

enum class MediaType   { MOVIE, TV }
// REMUXING = HLS segments downloaded, now converting to .mp4 with MediaMuxer
enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, REMUXING, ERROR }
enum class TransferStatus { IDLE, CONNECTING, TRANSFERRING, DONE, ERROR }
enum class TransferDirection { SEND, RECEIVE }

// ── Media card — schema v3: id, title, poster_url, rating, media_type ─────────
data class Media(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val rating: Double,
    val mediaType: MediaType,
    /** Genre IDs — not in the media card schema but populated by Explore/Search for client-side filtering. */
    val genres: List<String> = emptyList(),
)

// ── Detail screen ─────────────────────────────────────────────────────────────
data class MediaDetail(
    val id: String,
    val title: String,
    val tagline: String?,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val releaseYear: String?,
    val rating: Double,
    val runtime: Int?,
    val mediaType: MediaType,
    val maturityRating: String?,
    val genres: List<String>,
    val status: String?,
    val trailerUrl: String?,
    val cast: List<CastMember> = emptyList(),
    val seasons: List<Season> = emptyList(),
    val similar: List<Media> = emptyList(),
)

// Season — only season_number per schema v3
data class Season(
    val seasonNumber: Int,
)

// Episode — schema v3: id, episode_number, season_number, name, overview, still_url, runtime
data class Episode(
    val id: String,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val name: String,
    val overview: String,
    val stillUrl: String?,
    val runtime: Int?,
)

// Cast member
data class CastMember(
    val name: String,
    val character: String,
    val photoUrl: String?,
    /** Stable key for Compose lazy list — composed from name+character since backend doesn't send an id. */
    val id: String = "$name|$character",
)

// ── Feed ──────────────────────────────────────────────────────────────────────
data class FeedSection(
    val id: String,
    val title: String,
    val layout: String = "row",
    val items: List<Media>,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

// ── Explore ───────────────────────────────────────────────────────────────────
data class Genre(val id: String, val name: String)

// ── Stream — schema v3: streams[], expires_at_ms ──────────────────────────────
data class Subtitle(
    val url: String,
    val language: String,
    val enabled: Boolean,
    /** Display label — defaults to language code if not provided by backend. */
    val label: String = language,
    /** File format: "srt" | "vtt" | "ass" | "ssa" | "sub" | "sbv" | "lrc" */
    val format: String = "srt",
)

data class StreamTrack(
    val name: String,
    val url: String,
    val type: String,     // "hls" | "mp4"
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<Subtitle> = emptyList(),
)

data class StreamResult(
    val streams: List<StreamTrack>,
    val expiresAtMs: Long,
) {
    val primaryStream: StreamTrack? get() = streams.firstOrNull()
    val isHls: Boolean get() = primaryStream?.type == "hls"
}

// ── Downloads — schema v3: label, url, language, size_bytes, premium ─────────
data class DownloadLink(
    val label: String,
    val type: String = "mp4",     // "mp4" | "hls" — tells downloader how to handle
    val url: String,
    val language: String,
    val sizeBytes: Long,
    val premium: Boolean,  // shows lock badge; backend enforces server-side
)

// ── Shorts — schema v3: id, title, source, url, thumbnail ────────────────────
data class ShortVideo(
    val id: String,
    val title: String,
    val source: String?,
    val url: String,
    val thumbnail: String?,
)

// ── Download item (TEMPORARY — tracks active downloads in "downloads" table) ──
//
// This is the working domain model for the downloads table.
// A DownloadItem is DELETED when the download completes (success → FileItem in "files").
// The "downloads" table never holds DONE rows.
//
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
    val segmentsDone: Int = 0,
    val totalSegments: Int = 0,
    val localPlaylistPath: String = "",
)

// ── File item (PERMANENT — clean .mp4 in user's library, "files" table) ───────
//
// FileItem is the domain model for a fully downloaded, remuxed, playable .mp4.
// Every row in the "files" table is a valid, complete mp4 file.
// Both direct MP4 downloads and HLS→remux flows produce a FileItem.
// File transfer received files also become FileItems.
//
data class FileItem(
    val id: String,
    val mediaId: String,
    val title: String,
    val posterUrl: String?,
    val mediaType: String,          // "MOVIE" | "TV"
    val season: Int = 0,
    val episode: Int = 0,
    val episodeName: String = "",
    val quality: String = "720p",
    val filePath: String,           // absolute path to the .mp4 file
    val sizeBytes: Long = 0,
    val durationMs: Long = 0,
    val watchProgressMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
)

// ── User session ──────────────────────────────────────────────────────────────
data class UserSession(
    val uid: String,
    val isPremium: Boolean = false,
    val premiumExpiresAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val cachedAtMs: Long = System.currentTimeMillis(),
    val accessToken: String = "",
    val refreshToken: String = "",
    // Profile info from Google SDK (not from backend)
    val name: String = "",
    val email: String = "",
    val photoUrl: String? = null,
)

// ── Watch progress (local-only) ───────────────────────────────────────────────
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

// ── Quality track — for player resolution picker and download sheet ────────────
// Represents one selectable quality option in the player or download sheet UI.
// Built from StreamTrack.name/url (player) or DownloadLink.label/url (downloads).
data class QualityTrack(
    val label: String,
    val url: String,
    val bitrate: Long = 0L,
    val estimatedSizeBytes: Long = 0L,
) {
    /** Parses the numeric height from labels like "1080p", "720p". Returns 0 if not parseable. */
    fun resolutionHeight(): Int {
        val match = Regex("(\\d{3,4})p").find(label) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }
}
