package com.axio.reelz.data.local

import android.content.Context
import java.io.File

/**
 * DownloadPaths — single source of truth for every file path in the download system.
 *
 * STORAGE CHOICE: getExternalFilesDir() (app-private external storage)
 * ─────────────────────────────────────────────────────────────────────
 * Why NOT true internal (filesDir):
 *   • Lives on the same partition as the OS and app. A user downloading
 *     10 × 2GB movies fills their phone and breaks Android itself.
 *
 * Why NOT public Downloads/:
 *   • Any app can read files there. Users could sideload our content.
 *
 * Why getExternalFilesDir():
 *   • On Android 10+ the system prevents other apps from accessing these
 *     locations and they are encrypted on-device.
 *   • Android 11+ blocked ACTION_OPEN_DOCUMENT_TREE from browsing
 *     Android/data/ — so even file-manager apps cannot see our folder.
 *   • No storage permissions needed (API 19+).
 *   • Supports SD cards via getExternalFilesDirs() (plural).
 *   • Deleted cleanly on app uninstall — no orphan video files left behind.
 *
 * PATH DESIGN: matches your proposed hierarchy, with a quality subfolder
 * added to support multi-resolution downloads of the same content.
 *
 *   [root]/
 *   ├── movies/
 *   │   └── tmdb_19995/
 *   │       ├── metadata.json          ← written once on DONE (recovery only)
 *   │       ├── poster.jpg
 *   │       ├── 1080p/
 *   │       │   ├── video.mp4          (MP4 path)
 *   │       │   ├── master.m3u8        (HLS playlist)
 *   │       │   └── segments/          (HLS .ts files)
 *   │       └── subtitles/
 *   │           ├── en.srt
 *   │           └── fr.srt
 *   │
 *   └── tv/
 *       └── tmdb_10283/
 *           ├── show.json
 *           ├── poster.jpg
 *           └── season_01/
 *               ├── season.json
 *               └── episode_01/
 *                   ├── metadata.json
 *                   ├── thumbnail.jpg  ← episode still from TMDB
 *                   ├── 1080p/
 *                   │   ├── video.mp4 or master.m3u8
 *                   │   └── segments/
 *                   └── subtitles/
 *
 * RULE: Room/SQLite is the live source of truth for all state and progress.
 *       The filesystem holds the actual bytes.
 *       metadata.json / show.json are recovery tombstones written at
 *       completion — the app NEVER reads them at runtime, only Room.
 */
object DownloadPaths {

    // ── Storage root ──────────────────────────────────────────────────────────

    /**
     * Returns the preferred storage root. Prefers the SD card if one is
     * mounted; falls back to internal app-private external storage.
     * Both paths are inside Android/data/com.axio.reelz/files/ — inaccessible
     * to other apps and file pickers on Android 10+.
     */
    fun root(ctx: Context): File {
        val volumes = ctx.getExternalFilesDirs(null)
        // Index 0 = primary (emulated internal), index 1+ = removable SD cards.
        // Pick the first mounted, writable volume. Fall back to index 0.
        val preferred = volumes.firstOrNull { it != null && it.canWrite() }
            ?: ctx.getExternalFilesDir(null)
            ?: ctx.filesDir   // last resort: true internal (rare edge case)
        return File(preferred, "downloads").also { it.mkdirs() }
    }

    // ── Movie paths ───────────────────────────────────────────────────────────

    /** downloads/movies/tmdb_19995/ */
    fun movieDir(ctx: Context, tmdbId: Int): File =
        File(root(ctx), "movies/tmdb_$tmdbId").also { it.mkdirs() }

    /** downloads/movies/tmdb_19995/1080p/ */
    fun movieQualityDir(ctx: Context, tmdbId: Int, quality: String): File =
        File(movieDir(ctx, tmdbId), sanitizeQuality(quality)).also { it.mkdirs() }

    /** downloads/movies/tmdb_19995/1080p/video.mp4 */
    fun movieMp4(ctx: Context, tmdbId: Int, quality: String): File =
        File(movieQualityDir(ctx, tmdbId, quality), "video.mp4")

    /** downloads/movies/tmdb_19995/1080p/master.m3u8 */
    fun moviePlaylist(ctx: Context, tmdbId: Int, quality: String): File =
        File(movieQualityDir(ctx, tmdbId, quality), "master.m3u8")

    /** downloads/movies/tmdb_19995/1080p/segments/ */
    fun movieSegmentsDir(ctx: Context, tmdbId: Int, quality: String): File =
        File(movieQualityDir(ctx, tmdbId, quality), "segments").also { it.mkdirs() }

    /** downloads/movies/tmdb_19995/poster.jpg */
    fun moviePoster(ctx: Context, tmdbId: Int): File =
        File(movieDir(ctx, tmdbId), "poster.jpg")

    /** downloads/movies/tmdb_19995/subtitles/ */
    fun movieSubtitlesDir(ctx: Context, tmdbId: Int): File =
        File(movieDir(ctx, tmdbId), "subtitles").also { it.mkdirs() }

    /** downloads/movies/tmdb_19995/subtitles/en.srt */
    fun movieSubtitle(ctx: Context, tmdbId: Int, language: String, ext: String = "srt"): File =
        File(movieSubtitlesDir(ctx, tmdbId), "$language.$ext")

    /** downloads/movies/tmdb_19995/metadata.json — written once on completion */
    fun movieMetadata(ctx: Context, tmdbId: Int): File =
        File(movieDir(ctx, tmdbId), "metadata.json")

    // ── TV show paths ─────────────────────────────────────────────────────────

    /** downloads/tv/tmdb_10283/ */
    fun showDir(ctx: Context, tmdbId: Int): File =
        File(root(ctx), "tv/tmdb_$tmdbId").also { it.mkdirs() }

    /** downloads/tv/tmdb_10283/poster.jpg */
    fun showPoster(ctx: Context, tmdbId: Int): File =
        File(showDir(ctx, tmdbId), "poster.jpg")

    /** downloads/tv/tmdb_10283/show.json */
    fun showMetadata(ctx: Context, tmdbId: Int): File =
        File(showDir(ctx, tmdbId), "show.json")

    /** downloads/tv/tmdb_10283/season_01/ */
    fun seasonDir(ctx: Context, tmdbId: Int, season: Int): File =
        File(showDir(ctx, tmdbId), "season_%02d".format(season)).also { it.mkdirs() }

    /** downloads/tv/tmdb_10283/season_01/season.json */
    fun seasonMetadata(ctx: Context, tmdbId: Int, season: Int): File =
        File(seasonDir(ctx, tmdbId, season), "season.json")

    /** downloads/tv/tmdb_10283/season_01/episode_01/ */
    fun episodeDir(ctx: Context, tmdbId: Int, season: Int, episode: Int): File =
        File(seasonDir(ctx, tmdbId, season), "episode_%02d".format(episode)).also { it.mkdirs() }

    /** downloads/tv/tmdb_10283/season_01/episode_01/1080p/ */
    fun episodeQualityDir(ctx: Context, tmdbId: Int, season: Int, episode: Int, quality: String): File =
        File(episodeDir(ctx, tmdbId, season, episode), sanitizeQuality(quality)).also { it.mkdirs() }

    /** downloads/tv/tmdb_10283/season_01/episode_01/1080p/video.mp4 */
    fun episodeMp4(ctx: Context, tmdbId: Int, season: Int, episode: Int, quality: String): File =
        File(episodeQualityDir(ctx, tmdbId, season, episode, quality), "video.mp4")

    /** downloads/tv/tmdb_10283/season_01/episode_01/1080p/master.m3u8 */
    fun episodePlaylist(ctx: Context, tmdbId: Int, season: Int, episode: Int, quality: String): File =
        File(episodeQualityDir(ctx, tmdbId, season, episode, quality), "master.m3u8")

    /** downloads/tv/tmdb_10283/season_01/episode_01/1080p/segments/ */
    fun episodeSegmentsDir(ctx: Context, tmdbId: Int, season: Int, episode: Int, quality: String): File =
        File(episodeQualityDir(ctx, tmdbId, season, episode, quality), "segments").also { it.mkdirs() }

    /** downloads/tv/tmdb_10283/season_01/episode_01/thumbnail.jpg — TMDB episode still */
    fun episodeThumbnail(ctx: Context, tmdbId: Int, season: Int, episode: Int): File =
        File(episodeDir(ctx, tmdbId, season, episode), "thumbnail.jpg")

    /** downloads/tv/tmdb_10283/season_01/episode_01/subtitles/ */
    fun episodeSubtitlesDir(ctx: Context, tmdbId: Int, season: Int, episode: Int): File =
        File(episodeDir(ctx, tmdbId, season, episode), "subtitles").also { it.mkdirs() }

    /** downloads/tv/tmdb_10283/season_01/episode_01/subtitles/en.srt */
    fun episodeSubtitle(ctx: Context, tmdbId: Int, season: Int, episode: Int, language: String, ext: String = "srt"): File =
        File(episodeSubtitlesDir(ctx, tmdbId, season, episode), "$language.$ext")

    /** downloads/tv/tmdb_10283/season_01/episode_01/metadata.json */
    fun episodeMetadata(ctx: Context, tmdbId: Int, season: Int, episode: Int): File =
        File(episodeDir(ctx, tmdbId, season, episode), "metadata.json")

    // ── Unified helpers ───────────────────────────────────────────────────────

    /**
     * Returns the correct video output file for a DownloadItem — regardless of
     * whether it is a movie or TV episode. Used by DownloadService when starting
     * a new download and checking completion.
     */
    fun videoFile(ctx: Context, tmdbId: Int, mediaType: String, season: Int, episode: Int, quality: String, isHls: Boolean): File {
        return if (mediaType.equals("TV", ignoreCase = true) || season > 0) {
            if (isHls) episodePlaylist(ctx, tmdbId, season, episode, quality)
            else episodeMp4(ctx, tmdbId, season, episode, quality)
        } else {
            if (isHls) moviePlaylist(ctx, tmdbId, quality)
            else movieMp4(ctx, tmdbId, quality)
        }
    }

    /**
     * Returns the segments directory for a DownloadItem (HLS downloads only).
     */
    fun segmentsDir(ctx: Context, tmdbId: Int, mediaType: String, season: Int, episode: Int, quality: String): File {
        return if (mediaType.equals("TV", ignoreCase = true) || season > 0)
            episodeSegmentsDir(ctx, tmdbId, season, episode, quality)
        else
            movieSegmentsDir(ctx, tmdbId, quality)
    }

    /**
     * Returns the correct subtitle file path for a downloaded subtitle,
     * routing to movie or episode subtitles folder automatically.
     */
    fun subtitleFile(ctx: Context, tmdbId: Int, mediaType: String, season: Int, episode: Int, language: String, ext: String = "srt"): File {
        return if (mediaType.equals("TV", ignoreCase = true) || season > 0)
            episodeSubtitle(ctx, tmdbId, season, episode, language, ext)
        else
            movieSubtitle(ctx, tmdbId, language, ext)
    }

    /**
     * Strips characters unsafe for directory names from a quality label.
     * "1080p" → "1080p", "4K UHD" → "4K_UHD", "Auto" → "auto"
     */
    private fun sanitizeQuality(quality: String): String =
        quality.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_").lowercase()
            .ifBlank { "default" }
}
