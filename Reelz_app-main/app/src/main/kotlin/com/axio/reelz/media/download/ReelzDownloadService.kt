package com.axio.reelz.media.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.axio.reelz.R
import com.axio.reelz.core.database.DownloadDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * ReelzDownloadService — Foreground service keeping downloads alive.
 *
 * The actual download logic lives in [ReelzDownloadEngine].
 * This service only:
 *  1. Keeps the process alive while downloads are active
 *  2. Shows a persistent notification with progress
 *  3. Resumes in-progress downloads after process death
 *
 * Started via [start] / [pause] / [cancel] companion actions.
 */
@AndroidEntryPoint
class ReelzDownloadService : Service() {

    @Inject lateinit var engine: ReelzDownloadEngine
    @Inject lateinit var downloadDao: DownloadDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CHANNEL_ID           = "reelz_downloads"
        const val NOTIFICATION_ID      = 1001

        const val ACTION_START  = "com.axio.reelz.download.START"
        const val ACTION_PAUSE  = "com.axio.reelz.download.PAUSE"
        const val ACTION_CANCEL = "com.axio.reelz.download.CANCEL"
        const val ACTION_RESUME_ALL = "com.axio.reelz.download.RESUME_ALL"

        const val EXTRA_DOWNLOAD_ID = "downloadId"
        const val EXTRA_URL         = "url"
        const val EXTRA_TYPE        = "type"      // "mp4" | "hls"
        const val EXTRA_HEADERS     = "headers"   // JSON string
        const val EXTRA_TITLE       = "title"

        fun startDownload(
            ctx:        Context,
            downloadId: String,
            url:        String,
            type:       String,
            headers:    Map<String, String> = emptyMap(),
            title:      String = "",
        ) {
            val intent = Intent(ctx, ReelzDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_TITLE, title)
                // Flatten headers as "key=value\nkey2=value2"
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
        startForeground(NOTIFICATION_ID, buildNotification("Starting downloads…", 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id      = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return START_NOT_STICKY
                val url     = intent.getStringExtra(EXTRA_URL)         ?: return START_NOT_STICKY
                val type    = intent.getStringExtra(EXTRA_TYPE)        ?: "mp4"
                val title   = intent.getStringExtra(EXTRA_TITLE)       ?: ""
                val headers = parseHeaders(intent.getStringExtra(EXTRA_HEADERS))
                engine.start(id, url, type, headers, title)
                observeProgress()
            }
            ACTION_PAUSE  -> engine.pause(intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: "")
            ACTION_CANCEL -> engine.cancel(intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: "")
            ACTION_RESUME_ALL -> resumeAllPaused()
        }
        return START_STICKY
    }

    private fun resumeAllPaused() {
        scope.launch {
            val paused = downloadDao.getByStatus("PAUSED") + downloadDao.getByStatus("QUEUED")
            paused.forEach { row ->
                engine.start(row.id, row.streamUrl, "hls", emptyMap(), row.title)
            }
        }
    }

    private fun observeProgress() {
        scope.launch {
            downloadDao.observeAll().collect { rows ->
                val active  = rows.filter { it.status == "DOWNLOADING" }
                val done    = rows.count  { it.status == "DONE" }
                val totalSeg = active.sumOf { it.totalSegments }
                val doneSeg  = active.sumOf { it.segmentsDone }
                val progress = if (totalSeg > 0) (doneSeg * 100 / totalSeg) else 0
                val msg = when {
                    active.isNotEmpty() -> "${active.size} downloading ($progress%)"
                    done > 0            -> "$done download(s) complete"
                    else                -> "Downloads ready"
                }
                updateNotification(msg, progress, active.isNotEmpty())
                if (active.isEmpty()) stopSelf()
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

    private fun buildNotification(text: String, progress: Int, isActive: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reelz_logo)
            .setContentTitle("Reelz Downloads")
            .setContentText(text)
            .setProgress(100, progress, !isActive && progress == 0)
            .setOngoing(isActive)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String, progress: Int, isActive: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, progress, isActive))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
