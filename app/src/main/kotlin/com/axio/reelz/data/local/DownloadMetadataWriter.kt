package com.axio.reelz.data.local

import android.content.Context
import com.google.gson.GsonBuilder
import com.axio.reelz.data.model.DownloadItem
import com.axio.reelz.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DownloadMetadataWriter
 * ──────────────────────
 * Writes recovery JSON files to disk AFTER a download is marked DONE in Room.
 *
 * RULE: Room is the live source of truth. These JSON files are tombstones
 * written once — they exist so a future "reconciliation scan" can rebuild
 * Room entries if the database is ever wiped (rare, but happens on some
 * Android versions after a bad OTA).
 *
 * The app NEVER reads these files at runtime. Always read from Room/SQLite.
 *
 * JSON schema is intentionally minimal and forward-compatible — only the
 * fields needed for a recovery scan. Full playback state lives in Room.
 */
@Singleton
class DownloadMetadataWriter @Inject constructor() {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Write the appropriate metadata file for a completed download.
     * Automatically routes to movie or TV episode metadata based on item type.
     * Safe to call multiple times — idempotent, just overwrites.
     */
    suspend fun write(ctx: Context, item: DownloadItem) = withContext(Dispatchers.IO) {
        try {
            val isMovie = item.mediaType.equals(MediaType.MOVIE.name, ignoreCase = true)
                || item.season == 0

            if (isMovie) {
                writeMovieMetadata(ctx, item)
            } else {
                writeEpisodeMetadata(ctx, item)
                writeShowStubIfAbsent(ctx, item)
                writeSeasonStubIfAbsent(ctx, item)
            }
        } catch (e: Exception) {
            // Never crash the download flow for a JSON write failure.
            // Room already has the full record — the JSON is optional.
            android.util.Log.w("DownloadMetadataWriter", "Failed to write metadata for ${item.id}: ${e.message}")
        }
    }

    // ── Movie ─────────────────────────────────────────────────────────────────

    private fun writeMovieMetadata(ctx: Context, item: DownloadItem) {
        val file = DownloadPaths.movieMetadata(ctx, item.tmdbId)
        val isHls = item.filePath.endsWith(".m3u8", ignoreCase = true)
        val meta = MovieMetadata(
            tmdbId    = item.tmdbId,
            title     = item.title,
            type      = "movie",
            quality   = item.quality,
            format    = if (isHls) "hls" else "mp4",
            video     = if (isHls) null else "video.mp4",
            playlist  = if (isHls) "master.m3u8" else null,
        )
        file.writeText(gson.toJson(meta))
    }

    // ── TV Episode ────────────────────────────────────────────────────────────

    private fun writeEpisodeMetadata(ctx: Context, item: DownloadItem) {
        val file = DownloadPaths.episodeMetadata(ctx, item.tmdbId, item.season, item.episode)
        val isHls = item.filePath.endsWith(".m3u8", ignoreCase = true)
        val meta = EpisodeMetadata(
            tmdbId      = item.tmdbId,
            title       = item.episodeName.ifBlank { item.title },
            showTitle   = item.title,
            type        = "episode",
            season      = item.season,
            episode     = item.episode,
            quality     = item.quality,
            format      = if (isHls) "hls" else "mp4",
            video       = if (isHls) null else "video.mp4",
            playlist    = if (isHls) "master.m3u8" else null,
            thumbnail   = if (DownloadPaths.episodeThumbnail(ctx, item.tmdbId, item.season, item.episode).exists())
                              "thumbnail.jpg" else null,
        )
        file.writeText(gson.toJson(meta))
    }

    /** Write show.json once — never overwrite if already present (the first write wins). */
    private fun writeShowStubIfAbsent(ctx: Context, item: DownloadItem) {
        val file = DownloadPaths.showMetadata(ctx, item.tmdbId)
        if (file.exists()) return
        val stub = ShowMetadata(
            tmdbId    = item.tmdbId,
            showTitle = item.title,
            type      = "tv",
            poster    = if (DownloadPaths.showPoster(ctx, item.tmdbId).exists()) "poster.jpg" else null,
        )
        file.writeText(gson.toJson(stub))
    }

    /** Write season.json once — never overwrite if already present. */
    private fun writeSeasonStubIfAbsent(ctx: Context, item: DownloadItem) {
        val file = DownloadPaths.seasonMetadata(ctx, item.tmdbId, item.season)
        if (file.exists()) return
        val stub = SeasonMetadata(
            tmdbId = item.tmdbId,
            season = item.season,
        )
        file.writeText(gson.toJson(stub))
    }

    // ── Data classes (JSON schema) ─────────────────────────────────────────────

    private data class MovieMetadata(
        val tmdbId   : Int,
        val title    : String,
        val type     : String,   // always "movie"
        val quality  : String,
        val format   : String,   // "mp4" or "hls"
        val video    : String?,  // "video.mp4" if MP4, null if HLS
        val playlist : String?,  // "master.m3u8" if HLS, null if MP4
    )

    private data class EpisodeMetadata(
        val tmdbId    : Int,
        val title     : String,  // episode name
        val showTitle : String,
        val type      : String,  // always "episode"
        val season    : Int,
        val episode   : Int,
        val quality   : String,
        val format    : String,
        val video     : String?,
        val playlist  : String?,
        val thumbnail : String?, // "thumbnail.jpg" if downloaded, null otherwise
    )

    private data class ShowMetadata(
        val tmdbId    : Int,
        val showTitle : String,
        val type      : String,  // always "tv"
        val poster    : String?, // "poster.jpg" if downloaded, null otherwise
    )

    private data class SeasonMetadata(
        val tmdbId : Int,
        val season : Int,
    )
}
