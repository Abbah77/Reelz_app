package com.axio.reelz.data.repository

import android.content.Context
import android.util.Log
import com.axio.reelz.core.database.DownloadDao
import com.axio.reelz.core.database.DownloadSubtitleDao
import com.axio.reelz.core.database.DownloadSubtitleRow
import com.axio.reelz.core.database.FileDao
import com.axio.reelz.core.database.FileRow
import com.axio.reelz.data.model.DownloadItem
import com.axio.reelz.data.model.DownloadStatus
import com.axio.reelz.data.model.FileItem
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.core.database.DownloadRow
import com.axio.reelz.data.model.Subtitle
import com.axio.reelz.media.download.ReelzDownloadEngine
import com.axio.reelz.media.download.ReelzDownloadService
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val dao:         DownloadDao,
    private val fileDao:     FileDao,
    private val subtitleDao: DownloadSubtitleDao,
    private val engine:      ReelzDownloadEngine,
    private val gson:        Gson,
) {
    private val tag = "DownloadRepository"
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Active downloads (from downloads table) ───────────────────────────────
    fun observeAll(): Flow<List<DownloadItem>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    // ── Files library (from files table) ─────────────────────────────────────
    fun observeFiles(): Flow<List<FileItem>> =
        fileDao.observeAll().map { rows -> rows.map { it.toFileItem() } }

    // ── Bullet-proof duplicate guard: queries BOTH tables ─────────────────────
    //
    //  For movies:  mediaId=X, season=0,  episode=0,  quality="720p"
    //  For TV:      mediaId=X, season=3,  episode=7,  quality="720p"
    //
    //  Returns true if this exact content+quality exists in EITHER table.
    //  This guarantees that file transfers (which write directly to files table)
    //  also prevent duplicate downloads.
    //
    suspend fun isAlreadyPresent(
        mediaId: String,
        season: Int = 0,
        episode: Int = 0,
        quality: String,
    ): Boolean = withContext(Dispatchers.IO) {
        // Check downloads table (active/paused/queued/remuxing)
        val inDownloads = dao.getForContent(mediaId, season, episode)
            .any { it.quality == quality }
        if (inDownloads) return@withContext true

        // Check files table (completed + file transferred)
        val inFiles = fileDao.getExact(mediaId, season, episode, quality) != null
        inFiles
    }

    // Legacy alias used by PlayerViewModel / DownloadSheet
    suspend fun isAlreadyDownloaded(
        id: String,
        season: Int = 0,
        episode: Int = 0,
        quality: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        if (quality.isBlank()) {
            // Any quality check
            val inDownloads = dao.getForContent(id, season, episode).isNotEmpty()
            val inFiles = fileDao.getForContent(id, season, episode).isNotEmpty()
            inDownloads || inFiles
        } else {
            isAlreadyPresent(id, season, episode, quality)
        }
    }

    // ── Get downloaded/filed items for a content (used by player) ─────────────
    suspend fun getDownloadedItems(
        id: String,
        season: Int = 0,
        episode: Int = 0,
    ): List<FileItem> = withContext(Dispatchers.IO) {
        fileDao.getForContent(id, season, episode).map { it.toFileItem() }
    }

    // ── Enqueue a new download ────────────────────────────────────────────────
    suspend fun enqueue(
        ctx: Context,
        id: String,
        title: String,
        posterUrl: String?,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        episodeName: String = "",
        quality: String = "720p",
        linkType: String = "mp4",
        streamUrl: String,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        // Bulletproof duplicate guard — check both tables
        if (isAlreadyPresent(id, season, episode, quality)) {
            // Return the existing download or file id
            dao.getForContent(id, season, episode)
                .firstOrNull { it.quality == quality }?.id
                ?: fileDao.getExact(id, season, episode, quality)?.id
                ?: UUID.randomUUID().toString()
        } else {
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

    // ── Delete active download ────────────────────────────────────────────────
    suspend fun delete(ctx: Context, item: DownloadItem) = withContext(Dispatchers.IO) {
        engine.cancel(item.id)
        val subtitleRows = subtitleDao.getForContent(item.mediaId, item.season, item.episode)
        subtitleRows.forEach { row -> try { File(row.localFilePath).delete() } catch (_: Exception) {} }
        subtitleDao.deleteForContent(item.mediaId, item.season, item.episode)
        dao.delete(item.id)
    }

    // ── Delete a file from permanent library ──────────────────────────────────
    suspend fun deleteFile(fileItem: FileItem) = withContext(Dispatchers.IO) {
        try { File(fileItem.filePath).delete() } catch (_: Exception) {}
        val subtitleRows = subtitleDao.getForContent(fileItem.mediaId, fileItem.season, fileItem.episode)
        subtitleRows.forEach { row -> try { File(row.localFilePath).delete() } catch (_: Exception) {} }
        subtitleDao.deleteForContent(fileItem.mediaId, fileItem.season, fileItem.episode)
        fileDao.delete(fileItem.id)
    }

    // ── Delete by exact quality from files table ──────────────────────────────
    suspend fun deleteFileByQuality(mediaId: String, season: Int, episode: Int, quality: String) =
        withContext(Dispatchers.IO) {
            val row = fileDao.getExact(mediaId, season, episode, quality) ?: return@withContext
            try { File(row.filePath).delete() } catch (_: Exception) {}
            fileDao.deleteExact(mediaId, season, episode, quality)
        }

    // ── Subtitle download ─────────────────────────────────────────────────────
    fun scheduleSubtitleDownload(
        downloadId: String,
        mediaId: String,
        season: Int,
        episode: Int,
        subtitles: List<Subtitle>,
    ) {
        if (subtitles.isEmpty()) return
        repoScope.launch {
            try {
                // Watch files table for the item to appear
                var attempts = 0
                while (attempts < 60) {
                    val exists = fileDao.getForContent(mediaId, season, episode).isNotEmpty()
                    if (exists) break
                    kotlinx.coroutines.delay(2000)
                    attempts++
                }
                subtitles.forEach { sub ->
                    downloadSubtitleSilently(downloadId, mediaId, season, episode, sub)
                }
            } catch (e: Exception) {
                Log.w(tag, "scheduleSubtitleDownload: ${e.message}")
            }
        }
    }

    suspend fun downloadSubtitleSilently(
        downloadId: String,
        mediaId: String,
        season: Int,
        episode: Int,
        subtitle: Subtitle,
    ) = withContext(Dispatchers.IO) {
        try {
            val existing = subtitleDao.getForContent(mediaId, season, episode)
            if (existing.any { it.language == subtitle.language }) return@withContext

            val subtitlesDir = engine.subtitlesDir(downloadId)
            val ext = subtitle.format.ifBlank { "srt" }
            val file = File(subtitlesDir, "${subtitle.language}.$ext")

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
        } catch (e: Exception) {
            Log.w(tag, "Silent subtitle download failed for ${subtitle.language}: ${e.message}")
        }
    }

    // ── Local playback path (reads from engine — always returns .mp4 path) ─────
    fun getLocalPlaybackPath(downloadId: String): String? =
        engine.getLocalPlaybackPath(downloadId)

    // ── Watch progress (writes to files table) ────────────────────────────────
    suspend fun updateWatchProgress(
        mediaId: String,
        season: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        fileDao.updateWatchProgress(mediaId, season, episode, positionMs, durationMs, System.currentTimeMillis())
    }

    suspend fun getDownload(id: String): DownloadItem? =
        withContext(Dispatchers.IO) { dao.get(id)?.toModel() }

    // ── FileRow → FileItem ────────────────────────────────────────────────────
    private fun FileRow.toFileItem() = FileItem(
        id             = id,
        mediaId        = mediaId,
        title          = title,
        posterUrl      = posterUrl,
        mediaType      = mediaType,
        season         = season,
        episode        = episode,
        episodeName    = episodeName,
        quality        = quality,
        filePath       = filePath,
        sizeBytes      = sizeBytes,
        durationMs     = durationMs,
        watchProgressMs = watchProgressMs,
        lastPlayedAt   = lastPlayedAt,
        addedAt        = addedAt,
    )

    // ── DownloadRow → DownloadItem ────────────────────────────────────────────
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
        createdAt        = createdAt,
        segmentsDone     = segmentsDone,
        totalSegments    = totalSegments,
        localPlaylistPath = localPlaylistPath,
    )
}
