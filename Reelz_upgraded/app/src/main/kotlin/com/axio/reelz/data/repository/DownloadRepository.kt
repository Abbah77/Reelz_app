package com.axio.reelz.data.repository

import android.content.Context
import com.axio.reelz.core.database.DownloadDao
import com.axio.reelz.core.database.DownloadRow
import com.axio.reelz.data.model.DownloadItem
import com.axio.reelz.data.model.DownloadStatus
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.data.model.QualityTrack
import com.axio.reelz.media.download.ReelzDownloadService
import androidx.media3.exoplayer.offline.DownloadService as Media3DS
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val dao: DownloadDao,
    private val gson: Gson,
) {
    // ── Observable list for Downloads screen ──────────────────────────────────
    fun observeAll(): Flow<List<DownloadItem>> = dao.observeAll().map { rows ->
        rows.map { it.toModel() }
    }

    // ── Check if already downloaded ───────────────────────────────────────────
    suspend fun isAlreadyDownloaded(
        id: String,
        season: Int = 0,
        episode: Int = 0,
        quality: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        dao.getForContent(id, season, episode)
            .any { it.quality == quality || quality.isBlank() }
    }

    suspend fun getDownloadedItems(
        id: String,
        season: Int = 0,
        episode: Int = 0,
    ): List<DownloadItem> = withContext(Dispatchers.IO) {
        dao.getForContent(id, season, episode).map { it.toModel() }
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
        streamUrl: String,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        // Duplicate guard — same quality of same content must not be enqueued twice.
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
            )
        )
        Media3DS.sendResumeDownloads(ctx, ReelzDownloadService::class.java, false)
        downloadId
    }

    // ── Pause ─────────────────────────────────────────────────────────────────
    suspend fun pause(ctx: Context, item: DownloadItem) = withContext(Dispatchers.IO) {
        Media3DS.sendPauseDownloads(ctx, ReelzDownloadService::class.java, false)
        dao.markPaused(item.id)
    }

    // ── Resume ────────────────────────────────────────────────────────────────
    suspend fun resume(ctx: Context, item: DownloadItem) = withContext(Dispatchers.IO) {
        dao.markPaused(item.id)  // ensure state is PAUSED before service starts
        Media3DS.sendResumeDownloads(ctx, ReelzDownloadService::class.java, false)
    }

    // ── Delete ────────────────────────────────────────────────────────────────
    suspend fun delete(ctx: Context, item: DownloadItem) = withContext(Dispatchers.IO) {
        Media3DS.sendPauseDownloads(ctx, ReelzDownloadService::class.java, false)
        if (item.filePath.isNotBlank()) {
            runCatching { java.io.File(item.filePath).delete() }
        }
        dao.delete(item.id)
    }

    // ── Watch progress for downloaded content ─────────────────────────────────
    suspend fun updateWatchProgress(
        mediaId: String, season: Int, episode: Int,
        positionMs: Long, durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        dao.updateWatchProgress(
            id  = mediaId, s = season, ep = episode,
            pos = positionMs, dur = durationMs,
            at  = System.currentTimeMillis(),
        )
    }

    suspend fun getDownload(id: String): DownloadItem? =
        withContext(Dispatchers.IO) { dao.get(id)?.toModel() }

    // ── Row → Domain ──────────────────────────────────────────────────────────
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
        headers         = runCatching { gson.fromJson(headersJson, Map::class.java) as Map<String,String> }.getOrDefault(emptyMap()),
        createdAt       = createdAt,
        completedAt     = completedAt,
        segmentsDone    = segmentsDone,
        totalSegments   = totalSegments,
        watchProgressMs = watchProgressMs,
        durationMs      = durationMs,
        lastPlayedAt    = lastPlayedAt,
    )
}
