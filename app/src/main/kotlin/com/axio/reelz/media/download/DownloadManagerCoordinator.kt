package com.axio.reelz.media.download

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.axio.reelz.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor

/**
 * DownloadManagerCoordinator — Media3 DownloadManager coordinator.
 *
 * Replaces the bespoke HLS segment-download engine. Responsibilities:
 *  - Singleton Media3 DownloadManager (offline storage + cache)
 *  - Notification building for the foreground service
 *  - Helpers to enqueue, cancel, and remove downloads
 *
 * The old custom engine (Semaphore(4) + M3U8 parser + exponential backoff +
 * segment checkpointing) is deleted. Media3's DownloadManager handles all of
 * that natively and has been tested by Google across millions of devices.
 *
 * Dependency direction: DownloadManagerCoordinator → Media3. Never touches UI.
 */
@UnstableApi
object DownloadManagerCoordinator {

    private const val DOWNLOAD_CONTENT_DIRECTORY = "reelz_downloads"
    private const val MAX_PARALLEL_DOWNLOADS = 3

    @Volatile private var _downloadManager: DownloadManager? = null
    @Volatile private var _downloadCache: SimpleCache? = null

    fun getDownloadManager(context: Context): DownloadManager =
        _downloadManager ?: synchronized(this) {
            _downloadManager ?: buildDownloadManager(context).also { _downloadManager = it }
        }

    fun getDownloadCache(context: Context): SimpleCache =
        _downloadCache ?: synchronized(this) {
            _downloadCache ?: buildDownloadCache(context).also { _downloadCache = it }
        }

    private fun buildDownloadManager(context: Context): DownloadManager {
        val cache = getDownloadCache(context)
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        return DownloadManager(
            context,
            cache,
            dataSourceFactory,
            Executor(Runnable::run),
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            // Media3 handles resume, backoff, and progress natively
        }
    }

    private fun buildDownloadCache(context: Context): SimpleCache {
        val downloadDir = File(context.getExternalFilesDir(null), DOWNLOAD_CONTENT_DIRECTORY)
            ?: File(context.filesDir, DOWNLOAD_CONTENT_DIRECTORY)
        downloadDir.mkdirs()
        return SimpleCache(downloadDir, NoOpCacheEvictor())
    }

    // ── Enqueue a download ────────────────────────────────────────────────────

    suspend fun enqueue(
        context: Context,
        url: String,
        mediaId: String,
        title: String,
        mimeType: String = MimeTypes.APPLICATION_M3U8,
    ) = withContext(Dispatchers.IO) {
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId(mediaId)
            .setMimeType(mimeType)
            .build()

        val downloadHelper = DownloadHelper.forMediaItem(context, mediaItem)
        try {
            downloadHelper.prepare(object : DownloadHelper.Callback {
                override fun onPrepared(helper: DownloadHelper) {
                    val request: DownloadRequest = helper.getDownloadRequest(mediaId, null)
                    DownloadManager.sendAddDownload(context, ReelzDownloadService::class.java, request, false)
                    helper.release()
                }
                override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                    // Fall back to progressive download for direct MP4
                    val progressiveItem = MediaItem.Builder()
                        .setUri(url).setMediaId(mediaId).setMimeType(MimeTypes.VIDEO_MP4).build()
                    val req = DownloadRequest.Builder(mediaId, progressiveItem.localConfiguration!!.uri).build()
                    DownloadManager.sendAddDownload(context, ReelzDownloadService::class.java, req, false)
                    helper.release()
                }
            })
        } catch (e: Exception) {
            downloadHelper.release()
            throw e
        }
    }

    // ── Cancel / remove a download ────────────────────────────────────────────

    fun cancel(context: Context, mediaId: String) {
        DownloadManager.sendRemoveDownload(context, ReelzDownloadService::class.java, mediaId, false)
    }

    fun removeAll(context: Context) {
        DownloadManager.sendRemoveAllDownloads(context, ReelzDownloadService::class.java, false)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    fun buildNotification(context: Context, downloads: List<Download>): Notification {
        val active = downloads.filter { it.state == Download.STATE_DOWNLOADING }
        val progress = if (active.isNotEmpty()) {
            (active.sumOf { it.percentDownloaded.toDouble() } / active.size).toInt()
        } else 0

        return NotificationCompat.Builder(context, "reelz_downloads")
            .setSmallIcon(R.drawable.ic_reelz_logo)
            .setContentTitle(context.getString(R.string.download_notification_title))
            .setContentText(
                if (active.isNotEmpty())
                    context.getString(R.string.download_notification_progress, active.size, progress)
                else
                    context.getString(R.string.download_notification_complete)
            )
            .setProgress(100, progress, active.isEmpty())
            .setOngoing(active.isNotEmpty())
            .build()
    }
}
