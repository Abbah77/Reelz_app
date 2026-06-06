package com.reelz.data.repository

import android.content.Context
import com.reelz.data.local.DownloadDao
import com.reelz.data.model.*
import com.reelz.service.DownloadService
import com.google.gson.Gson
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
    ): String {
        val id = UUID.randomUUID().toString()
        dao.insert(
            DownloadItem(
                id            = id,
                tmdbId        = tmdbId,
                title         = title,
                posterPath    = posterPath,
                mediaType     = mediaType.name,
                season        = season,
                episode       = episode,
                episodeName   = episodeName,
                quality       = quality,
                streamUrl     = streamUrl,
                headers       = gson.toJson(headers),
                status        = DownloadStatus.QUEUED.name,
            )
        )
        DownloadService.start(ctx, id)
        return id
    }

    suspend fun delete(ctx: Context, item: DownloadItem) {
        if (item.filePath.isNotBlank()) {
            try { java.io.File(item.filePath).delete() } catch (_: Exception) {}
        }
        dao.delete(item.id)
    }

    suspend fun getDownload(id: String): DownloadItem? = dao.get(id)
}
