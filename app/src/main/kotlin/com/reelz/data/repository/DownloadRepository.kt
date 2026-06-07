package com.reelz.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.reelz.data.local.DownloadDao
import com.reelz.data.model.*
import com.reelz.service.DownloadService
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

    suspend fun resume(ctx: Context, item: DownloadItem) {
        // BUG 1 fix: mark resolveRequired before restarting
        dao.markPaused(item.id)  // sets resolveRequired = 1
        DownloadService.start(ctx, item.id)
    }

    suspend fun delete(ctx: Context, item: DownloadItem) {
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
