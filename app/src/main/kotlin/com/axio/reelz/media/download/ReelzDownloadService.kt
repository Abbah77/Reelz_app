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
        startForeground(NOTIFICATION_ID, buildNotification("Starting downloads…", 0, false))
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
                // Infer type from URL — never hardcode "hls" for all resumes.
                val type = when {
                    row.streamUrl.contains(".m3u8", ignoreCase = true) -> "hls"
                    row.streamUrl.contains(".mp4",  ignoreCase = true) -> "mp4"
                    else -> "mp4"  // safe default — engine handles both
                }
                @Suppress("UNCHECKED_CAST")
                val headers = runCatching {
                    com.google.gson.Gson().fromJson(row.headersJson, Map::class.java) as Map<String, String>
                }.getOrDefault(emptyMap())
                engine.start(row.id, row.streamUrl, type, headers, row.title)
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

    private fun buildNotification(text: String, progress: Int, isActive: Boolean): Notification {
        // Tapping the notification opens the app's downloads section
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "downloads")
        }
        val pendingIntent = if (openIntent != null) {
            android.app.PendingIntent.getActivity(
                this, 0, openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reelz_logo)
            .setContentTitle("Reelz Downloads")
            .setContentText(text)
            .setProgress(100, progress, !isActive && progress == 0)
            .setOngoing(isActive)
            .setOnlyAlertOnce(true)
            // Show notification even if permission was not explicitly granted (silent channel)
            // The foreground service itself keeps the download alive regardless.
            .setSilent(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
    }

    private fun updateNotification(text: String, progress: Int, isActive: Boolean) {
        // If POST_NOTIFICATIONS was denied we cannot show the notification bar update,
        // but the foreground service — and therefore the download — keeps running.
        // Android guarantees the startForeground() call works even without the
        // POST_NOTIFICATIONS permission (the mandatory foreground-service notification
        // is exempt from that permission on API 33+).
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text, progress, isActive))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
