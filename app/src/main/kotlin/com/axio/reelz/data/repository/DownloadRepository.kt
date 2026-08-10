package com.axio.reelz.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.axio.reelz.data.local.DownloadDao
import com.axio.reelz.data.model.*
import com.axio.reelz.service.DownloadService
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val dao: DownloadDao,
    private val gson: Gson,
) {
    fun getAll(): Flow<List<DownloadItem>> = dao.getAll()

    /**
     * UPGRADE P15: qualityTracks stored in the DB so resume logic can pick the correct
     * variant URL without re-parsing. resolveRequired = true by default so the service
     * always gets a fresh CDN URL (UPGRADE P11 / BUG 1 fix).
     */
    suspend fun enqueue(
        ctx: Context,
        tmdbId: Int,
        title: String,
        posterPath: String?,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        episodeName: String = "",
        quality: String = "720p",
        streamUrl: String,
        headers: Map<String, String> = emptyMap(),
        qualityTracks: List<QualityTrack> = emptyList(),
    ): String {
        val id = UUID.randomUUID().toString()
        dao.insert(
            DownloadItem(
                id               = id,
                tmdbId           = tmdbId,
                title            = title,
                posterPath       = posterPath,
                mediaType        = mediaType.name,
                season           = season,
                episode          = episode,
                episodeName      = episodeName,
                quality          = quality,
                streamUrl        = streamUrl,
                headers          = gson.toJson(headers),
                status           = DownloadStatus.QUEUED.name,
                qualityTracksJson = gson.toJson(qualityTracks),
                resolveRequired  = true,  // always re-resolve for fresh CDN URL
            )
        )
        DownloadService.start(ctx, id)
        return id
    }

    /**
     * FIX RESUME: Persist the current segment progress back to DB BEFORE
     * starting the service. This ensures the service picks up from where
     * it left off without re-downloading any already-completed segments.
     *
     * Also sets resolveRequired = true so the CDN URL is refreshed.
     */
    suspend fun resume(ctx: Context, item: DownloadItem) {
        // markPaused sets resolveRequired = 1 (fresh URL on resume)
        dao.markPaused(item.id)
        DownloadService.start(ctx, item.id)
    }

    /** Cancel an active download and mark it PAUSED so it can be resumed later. */
    suspend fun pause(ctx: Context, item: DownloadItem) {
        DownloadService.pause(ctx, item.id)
        // DB is updated to PAUSED inside DownloadService.ACTION_PAUSE handler,
        // but set it here too as a safety net in case the service isn't running.
        dao.markPaused(item.id)
    }

    suspend fun delete(ctx: Context, item: DownloadItem) {
        // Pause first to cancel any active download job cleanly
        DownloadService.pause(ctx, item.id)

        if (item.filePath.isNotBlank()) {
            try { java.io.File(item.filePath).delete() } catch (_: Exception) {}
        }
        if (item.segmentDir.isNotBlank()) {
            try { java.io.File(item.segmentDir).deleteRecursively() } catch (_: Exception) {}
        }
        dao.delete(item.id)
    }

    suspend fun getDownload(id: String): DownloadItem? = dao.get(id)
}
