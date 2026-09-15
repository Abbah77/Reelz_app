package com.axio.reelz.data.repository

import android.content.Context
import android.util.Log
import com.axio.reelz.core.database.DownloadDao
import com.axio.reelz.core.database.DownloadSubtitleDao
import com.axio.reelz.core.database.DownloadSubtitleRow
import com.axio.reelz.data.model.Subtitle
import java.io.File
import java.net.URL
import com.axio.reelz.core.database.DownloadRow
import com.axio.reelz.data.model.DownloadItem
import com.axio.reelz.data.model.DownloadStatus
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.media.download.ReelzDownloadEngine
import com.axio.reelz.media.download.ReelzDownloadService
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val dao:             DownloadDao,
    private val subtitleDao:     DownloadSubtitleDao,
    private val engine:          ReelzDownloadEngine,
    private val gson:            Gson,
    private val streamRepo:      StreamRepository,
) {
    private val tag = "DownloadRepository"
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // ── Observable list for Downloads screen ──────────────────────────────────
    fun observeAll(): Flow<List<DownloadItem>> = dao.observeAll().map { rows ->
        rows.map { it.toModel() }
    }

    // ── Check if already downloaded ───────────────────────────────────────────
    suspend fun isAlreadyDownloaded(
        id:      String,
        season:  Int    = 0,
        episode: Int    = 0,
        quality: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        dao.getForContent(id, season, episode)
            .any { it.quality == quality || quality.isBlank() }
    }

    suspend fun getDownloadedItems(
        id:      String,
        season:  Int = 0,
        episode: Int = 0,
    ): List<DownloadItem> = withContext(Dispatchers.IO) {
        dao.getForContent(id, season, episode).map { it.toModel() }
    }

    // ── Enqueue a new download ────────────────────────────────────────────────
    /**
     * @param linkType      "mp4" | "hls" — from DownloadLink.type (backend tells us)
     * @param streamUrl     The exact URL to download
     * @param headers       Effective headers (referer/origin/ua already merged by DTO layer)
     * @param expiresAtMs   Unix timestamp in ms when the URL expires (0 = unknown)
     */
    suspend fun enqueue(
        ctx:          Context,
        id:           String,
        title:        String,
        posterUrl:    String?,
        mediaType:    MediaType,
        season:       Int    = 0,
        episode:      Int    = 0,
        episodeName:  String = "",
        quality:      String = "720p",
        linkType:     String = "mp4",
        streamUrl:    String,
        headers:      Map<String, String> = emptyMap(),
        expiresAtMs:  Long   = 0L,
    ): String = withContext(Dispatchers.IO) {
        val existing = dao.getForContent(id, season, episode)
            .firstOrNull { it.quality == quality && it.status != DownloadStatus.ERROR.name }
        if (existing != null) return@withContext existing.id

        val downloadId = UUID.randomUUID().toString()
        dao.insert(
            DownloadRow(
                id           = downloadId,
                mediaId      = id,
                title        = title,
                posterUrl    = posterUrl,
                mediaType    = mediaType.name,
                season       = season,
                episode      = episode,
                episodeName  = episodeName,
                quality      = quality,
                streamUrl    = streamUrl,
                headersJson  = gson.toJson(headers),
                status       = DownloadStatus.QUEUED.name,
                expiresAtMs  = expiresAtMs,
            )
        )

        ReelzDownloadService.startDownload(
            ctx        = ctx,
            downloadId = downloadId,
            url        = streamUrl,
            type       = linkType,
            headers    = headers,
            title      = title,
        )

        downloadId
    }

    // ── Pause ─────────────────────────────────────────────────────────────────
    suspend fun pause(ctx: Context, item: DownloadItem) = withContext(Dispatchers.IO) {
        ReelzDownloadService.pauseDownload(ctx, item.id)
    }

    // ── Resume ────────────────────────────────────────────────────────────────
    /**
     * Resume a paused download.
     *
     * If the stored URL has expired (expiresAtMs > 0 and in the past), we call
     * freshGetDownloadLinks to get a new URL, update the DB row with the fresh
     * URL and its new expiresAtMs, then resume from downloadedBytes — no
     * progress is lost because we pass the saved byte offset to the engine.
     *
     * For HLS the engine resumes from segmentsDone, not bytes, so we just pass
     * the refreshed URL and the engine picks up where it left off.
     */
    suspend fun resume(ctx: Context, item: DownloadItem) = withContext(Dispatchers.IO) {
        val row = dao.get(item.id) ?: return@withContext

        @Suppress("UNCHECKED_CAST")
        val headers = runCatching {
            gson.fromJson(row.headersJson, Map::class.java) as Map<String, String>
        }.getOrDefault(emptyMap())

        val type = if (row.streamUrl.contains(".m3u8")) "hls" else "mp4"

        // Check if URL has expired
        val now = System.currentTimeMillis()
        val isExpired = row.expiresAtMs > 0 && now >= row.expiresAtMs

        val (resolvedUrl, resolvedHeaders) = if (isExpired) {
            Log.d(tag, "resume: URL expired for ${item.id}, fetching fresh URL")
            val freshResult = streamRepo.freshGetDownloadLinks(
                id        = row.mediaId,
                mediaType = runCatching { MediaType.valueOf(row.mediaType) }.getOrDefault(MediaType.MOVIE),
                season    = row.season,
                episode   = row.episode,
            )
            if (freshResult is com.axio.reelz.core.network.NetworkResult.Success) {
                val freshLinks = freshResult.data.first
                // Match the same quality label the user originally chose
                val match = freshLinks.firstOrNull { it.label == row.quality }
                    ?: freshLinks.firstOrNull()
                if (match != null) {
                    // Persist fresh URL + new expiry + updated headers into DB
                    dao.updateStreamUrl(
                        id          = row.id,
                        url         = match.url,
                        h           = gson.toJson(match.headers),
                        expiresAtMs = match.expiresAtMs,
                    )
                    Log.d(tag, "resume: fresh URL acquired for ${item.id}")
                    Pair(match.url, match.headers)
                } else {
                    Log.w(tag, "resume: no matching quality in fresh links, falling back to stored URL")
                    Pair(row.streamUrl, headers)
                }
            } else {
                Log.w(tag, "resume: fresh URL fetch failed, trying stored URL anyway")
                Pair(row.streamUrl, headers)
            }
        } else {
            Pair(row.streamUrl, headers)
        }

        ReelzDownloadService.startDownload(
            ctx           = ctx,
            downloadId    = item.id,
            url           = resolvedUrl,
            type          = type,
            headers       = resolvedHeaders,
            title         = row.title,
            // Byte offset for MP4 resume — engine sends Range: bytes=<resumeBytes>-
            resumeBytes   = if (type == "mp4") row.downloadedBytes else 0L,
        )
    }

    // ── Delete — single source of truth: movie + subtitles ───────────────────
    suspend fun delete(ctx: Context, item: DownloadItem) = withContext(Dispatchers.IO) {
        engine.cancel(item.id)
        // 1. Delete subtitle files from disk
        val subtitleRows = subtitleDao.getForContent(item.mediaId, item.season, item.episode)
        subtitleRows.forEach { row ->
            try { File(row.localFilePath).delete() } catch (_: Exception) {}
        }
        // 2. Delete subtitle rows from DB (by content identity — covers all qualities)
        subtitleDao.deleteForContent(item.mediaId, item.season, item.episode)
        // 3. Delete the download itself
        dao.delete(item.id)
    }

    // ── Trigger silent subtitle download when a movie/episode download finishes ──
    /**
     * Waits for the given download to reach DONE status, then silently downloads
     * all available subtitles. Called right after enqueue() when the backend
     * response included subtitle entries. Fire-and-forget — no UI involvement.
     */
    fun scheduleSubtitleDownload(
        downloadId: String,
        mediaId:    String,
        season:     Int,
        episode:    Int,
        subtitles:  List<com.axio.reelz.data.model.Subtitle>,
    ) {
        if (subtitles.isEmpty()) return
        repoScope.launch {
            try {
                // Wait for this download to reach DONE
                dao.observeAll()
                    .map { rows -> rows.firstOrNull { it.id == downloadId } }
                    .filter { row -> row?.status == com.axio.reelz.data.model.DownloadStatus.DONE.name }
                    .first()
                // Now download each subtitle silently
                subtitles.forEach { sub ->
                    downloadSubtitleSilently(downloadId, mediaId, season, episode, sub)
                }
            } catch (e: Exception) {
                Log.w(tag, "scheduleSubtitleDownload: ${e.message}")
            }
        }
    }

    // ── Download subtitle silently after content is done ──────────────────────
    /**
     * Called after a movie/episode download completes.
     * Downloads subtitle file to disk invisibly and persists to DB.
     * No UI involvement — completely silent.
     */
    suspend fun downloadSubtitleSilently(
        downloadId: String,
        mediaId: String,
        season: Int,
        episode: Int,
        subtitle: Subtitle,
    ) = withContext(Dispatchers.IO) {
        try {
            // Guard: don't re-download the same language
            val existing = subtitleDao.getForContent(mediaId, season, episode)
            if (existing.any { it.language == subtitle.language }) {
                Log.d(tag, "Subtitle ${subtitle.language} already saved — skipping")
                return@withContext
            }

            val subtitlesDir = engine.subtitlesDir(downloadId)
            val ext = subtitle.format.ifBlank { "srt" }
            val file = File(subtitlesDir, "${subtitle.language}.$ext")

            // Download subtitle file
            URL(subtitle.url).openStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }

            subtitleDao.insert(
                DownloadSubtitleRow(
                    downloadId    = downloadId,
                    mediaId       = mediaId,
                    season        = season,
                    episode       = episode,
                    language      = subtitle.language,
                    label         = subtitle.label.ifBlank { subtitle.language },
                    localFilePath = file.absolutePath,
                    format        = ext,
                    isEnabled     = true,
                )
            )
            Log.d(tag, "Subtitle ${subtitle.language} downloaded → ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(tag, "Silent subtitle download failed for ${subtitle.language}: ${e.message}")
        }
    }

    // ── Local playback path (for ExoPlayer offline) ───────────────────────────
    fun getLocalPlaybackPath(downloadId: String, type: String): String? =
        engine.getLocalPlaybackPath(downloadId, type)

    // ── Watch progress ────────────────────────────────────────────────────────
    suspend fun updateWatchProgress(
        mediaId:    String,
        season:     Int,
        episode:    Int,
        positionMs: Long,
        durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        dao.updateWatchProgress(
            id  = mediaId,
            s   = season,
            ep  = episode,
            pos = positionMs,
            dur = durationMs,
            at  = System.currentTimeMillis(),
        )
    }

    suspend fun getDownload(id: String): DownloadItem? =
        withContext(Dispatchers.IO) { dao.get(id)?.toModel() }

    // ── Row → Domain ──────────────────────────────────────────────────────────
    @Suppress("UNCHECKED_CAST")
    private fun DownloadRow.toModel() = DownloadItem(
        id              = id,
        mediaId         = mediaId,
        title           = title,
        posterUrl       = posterUrl,
        mediaType       = mediaType,
        season          = season,
        episode         = episode,
        episodeName     = episodeName,
        quality         = quality,
        filePath        = filePath,
        sizeBytes       = sizeBytes,
        downloadedBytes = downloadedBytes,
        status          = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.ERROR),
        streamUrl       = streamUrl,
        headers         = runCatching {
            gson.fromJson(headersJson, Map::class.java) as Map<String, String>
        }.getOrDefault(emptyMap()),
        createdAt          = createdAt,
        completedAt        = completedAt,
        segmentsDone       = segmentsDone,
        totalSegments      = totalSegments,
        watchProgressMs    = watchProgressMs,
        durationMs         = durationMs,
        lastPlayedAt       = lastPlayedAt,
        localPlaylistPath  = localPlaylistPath,
        expiresAtMs        = expiresAtMs,
    )
}
