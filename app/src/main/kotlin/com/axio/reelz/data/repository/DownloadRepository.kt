package com.axio.reelz.data.repository

import android.content.Context
import android.util.Log
import com.axio.reelz.core.database.CompletedMediaDao
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
    private val completedMediaDao: CompletedMediaDao,
    private val engine:          ReelzDownloadEngine,
    private val gson:            Gson,
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
        // Check permanent library first (completed_media)
        val inLibrary = completedMediaDao.getForContent(id, season, episode)
            .any { quality.isBlank() || it.quality.equals(quality, ignoreCase = true) }
        if (inLibrary) return@withContext true
        // Fall back to active download job queue
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
     * @param linkType  "mp4" | "hls" — from DownloadLink.type (backend tells us)
     * @param streamUrl The exact URL to download (mp4 direct URL or quality-specific index.m3u8)
     */
    suspend fun enqueue(
        ctx:         Context,
        id:          String,
        title:       String,
        posterUrl:   String?,
        mediaType:   MediaType,
        season:      Int    = 0,
        episode:     Int    = 0,
        episodeName: String = "",
        quality:     String = "720p",
        linkType:    String = "mp4",     // "mp4" | "hls"
        streamUrl:   String,
        headers:     Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        // Duplicate guard 1 — already in permanent library (same quality)
        val inLibrary = completedMediaDao.getExact(id, season, episode, quality)
        if (inLibrary != null) return@withContext inLibrary.id

        // Duplicate guard 2 — same quality already in job queue (not errored)
        val existing = dao.getForContent(id, season, episode)
            .firstOrNull { it.quality == quality && it.status != DownloadStatus.ERROR.name }
        if (existing != null) return@withContext existing.id

        val downloadId = UUID.randomUUID().toString()
        dao.insert(
            DownloadRow(
                id          = downloadId,
                mediaId     = id,
                title       = title,
                posterUrl   = posterUrl,
                mediaType   = mediaType.name,
                season      = season,
                episode     = episode,
                episodeName = episodeName,
                quality     = quality,
                streamUrl   = streamUrl,
                headersJson = gson.toJson(headers),
                status      = DownloadStatus.QUEUED.name,
            )
        )

        // Kick off the download via service (keeps alive in background)
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
    suspend fun resume(ctx: Context, item: DownloadItem) = withContext(Dispatchers.IO) {
        val row = dao.get(item.id) ?: return@withContext
        @Suppress("UNCHECKED_CAST")
        val headers = runCatching {
            gson.fromJson(row.headersJson, Map::class.java) as Map<String, String>
        }.getOrDefault(emptyMap())

        // Infer type from URL or stored metadata
        val type = if (row.streamUrl.contains(".m3u8")) "hls" else "mp4"

        ReelzDownloadService.startDownload(
            ctx        = ctx,
            downloadId = item.id,
            url        = row.streamUrl,
            type       = type,
            headers    = headers,
            title      = row.title,
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
                // Wait for this download to reach DONE in either table.
                // completed_media is the primary source for new-style remuxed downloads.
                // downloads table is the fallback for legacy HLS-only content.
                dao.observeAll()
                    .map { rows -> rows.firstOrNull { it.id == downloadId } }
                    .filter { row -> row?.status == com.axio.reelz.data.model.DownloadStatus.DONE.name }
                    .first()
                // Now download each subtitle to the permanent library location
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

            // Save to permanent library dir so subtitles survive job-folder deletion
            val subtitlesDir = engine.subtitleLibraryDir(mediaId, season)
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
    )
}
