package com.axio.reelz.media.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.axio.reelz.R
import com.axio.reelz.core.database.DownloadDao
import com.axio.reelz.data.model.DownloadStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * ReelzDownloadService — Foreground service that keeps downloads alive.
 *
 * START_STICKY: Android restarts the service after process death so downloads
 * survive app switching and memory pressure.
 *
 * Notification is live: updated from a DB Flow observer and dismissed when
 * all downloads complete or are cancelled.
 */
@AndroidEntryPoint
class ReelzDownloadService : Service() {

    @Inject lateinit var engine: ReelzDownloadEngine
    @Inject lateinit var downloadDao: DownloadDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observing = false

    companion object {
        const val CHANNEL_ID      = "reelz_downloads"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START      = "com.axio.reelz.download.START"
        const val ACTION_PAUSE      = "com.axio.reelz.download.PAUSE"
        const val ACTION_CANCEL     = "com.axio.reelz.download.CANCEL"
        const val ACTION_RESUME_ALL = "com.axio.reelz.download.RESUME_ALL"

        const val EXTRA_DOWNLOAD_ID  = "downloadId"
        const val EXTRA_URL          = "url"
        const val EXTRA_TYPE         = "type"
        const val EXTRA_HEADERS      = "headers"
        const val EXTRA_TITLE        = "title"
        const val EXTRA_RESUME_BYTES = "resumeBytes"

        fun startDownload(
            ctx: Context,
            downloadId: String,
            url: String,
            type: String,
            headers: Map<String, String> = emptyMap(),
            title: String = "",
            resumeBytes: Long = 0L,
        ) {
            val intent = Intent(ctx, ReelzDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_RESUME_BYTES, resumeBytes)
                putExtra(EXTRA_HEADERS, headers.entries.joinToString("\n") { "${it.key}=${it.value}" })
            }
            ctx.startForegroundService(intent)
        }

        fun pauseDownload(ctx: Context, downloadId: String) {
            ctx.startService(Intent(ctx, ReelzDownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            })
        }

        fun cancelDownload(ctx: Context, downloadId: String) {
            ctx.startService(Intent(ctx, ReelzDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            })
        }

        fun resumeAll(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ReelzDownloadService::class.java).apply {
                action = ACTION_RESUME_ALL
            })
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting downloads…", 0, false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id          = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return START_STICKY
                val url         = intent.getStringExtra(EXTRA_URL)         ?: return START_STICKY
                val type        = intent.getStringExtra(EXTRA_TYPE)        ?: "mp4"
                val title       = intent.getStringExtra(EXTRA_TITLE)       ?: ""
                val headers     = parseHeaders(intent.getStringExtra(EXTRA_HEADERS))
                val resumeBytes = intent.getLongExtra(EXTRA_RESUME_BYTES, 0L)
                engine.start(id, url, type, headers, title, resumeBytes = resumeBytes)
                ensureObserving()
            }
            ACTION_PAUSE -> {
                engine.pause(intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: "")
                ensureObserving()
            }
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: ""
                engine.cancel(id)
                scope.launch { downloadDao.delete(id) }
                ensureObserving()
            }
            ACTION_RESUME_ALL -> {
                resumeAllPaused()
                ensureObserving()
            }
        }
        return START_STICKY
    }

    private fun resumeAllPaused() {
        scope.launch {
            val paused = downloadDao.getByStatus("PAUSED") + downloadDao.getByStatus("QUEUED")
            paused.forEach { row ->
                val type = if (row.streamUrl.contains(".m3u8", ignoreCase = true)) "hls" else "mp4"
                @Suppress("UNCHECKED_CAST")
                val headers = runCatching {
                    com.google.gson.Gson().fromJson(row.headersJson, Map::class.java) as Map<String, String>
                }.getOrDefault(emptyMap())
                // Pass the saved byte offset so MP4 downloads resume from where they
                // stopped rather than restarting from byte 0. HLS ignores resumeBytes
                // and resumes from segmentsDone instead (handled inside the engine).
                engine.start(
                    downloadId  = row.id,
                    url         = row.streamUrl,
                    type        = type,
                    headers     = headers,
                    title       = row.title,
                    resumeBytes = if (type == "mp4") row.downloadedBytes else 0L,
                )
            }
        }
    }

    private fun ensureObserving() {
        if (observing) return
        observing = true
        scope.launch {
            downloadDao.observeAll().collect { rows ->
                val active   = rows.filter { it.status == DownloadStatus.DOWNLOADING.name }
                val paused   = rows.filter { it.status == DownloadStatus.PAUSED.name }
                val queued   = rows.filter { it.status == DownloadStatus.QUEUED.name }
                val done     = rows.count  { it.status == DownloadStatus.DONE.name }

                val totalSeg = active.sumOf { it.totalSegments }
                val doneSeg  = active.sumOf { it.segmentsDone }
                val progress = if (totalSeg > 0) (doneSeg * 100 / totalSeg) else 0

                val msg = when {
                    active.isNotEmpty() -> {
                        val pct = if (active.size == 1) " ($progress%)" else ""
                        "${active.size} downloading$pct"
                    }
                    queued.isNotEmpty() -> "${queued.size} queued"
                    paused.isNotEmpty() -> "${paused.size} paused"
                    done > 0            -> "$done download(s) complete"
                    else                -> "Downloads ready"
                }

                val isActive = active.isNotEmpty() || queued.isNotEmpty()

                if (rows.isEmpty()) {
                    cancelNotification()
                    stopSelf()
                } else if (!isActive && paused.isEmpty()) {
                    updateNotification(msg, 100, false)
                    delay(3_000)
                    cancelNotification()
                    stopSelf()
                } else {
                    updateNotification(msg, progress, isActive)
                }
            }
        }
    }

    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
        }.toMap()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Reelz download progress"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String, progress: Int, isActive: Boolean): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "downloads")
        }
        val pendingIntent = if (openIntent != null) {
            PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reelz_logo)
            .setContentTitle("Reelz Downloads")
            .setContentText(text)
            .setProgress(100, progress, isActive && progress == 0)
            .setOngoing(isActive)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
    }

    private fun updateNotification(text: String, progress: Int, isActive: Boolean) {
        runCatching {
            val notification = buildNotification(text, progress, isActive)
            if (isActive) {
                startForeground(NOTIFICATION_ID, notification)
            } else {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun cancelNotification() {
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
