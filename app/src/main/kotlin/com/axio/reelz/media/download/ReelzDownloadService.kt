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
import javax.inject.Inject

/**
 * ReelzDownloadService — Foreground service keeping downloads alive.
 *
 * KEY FIXES:
 *  1. Uses START_STICKY so Android restarts the service after process death —
 *     downloads are NOT paused when the user switches apps.
 *  2. Notification is live: progress observer updates it every time the DB
 *     changes, and the notification is fully dismissed (cancelNotification)
 *     when all downloads are done or when a user cancels the last download.
 *  3. Cancelled downloads: engine.cancel() deletes the DB row via the DAO,
 *     which triggers the Flow to re-emit without that row. When activeJobs
 *     becomes empty the notification is cancelled and stopSelf() is called.
 */
@AndroidEntryPoint
class ReelzDownloadService : Service() {

    @Inject lateinit var engine: ReelzDownloadEngine
    @Inject lateinit var downloadDao: DownloadDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track whether we have started observing — only start one observer loop.
    private var observing = false

    companion object {
        const val CHANNEL_ID      = "reelz_downloads"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START      = "com.axio.reelz.download.START"
        const val ACTION_PAUSE      = "com.axio.reelz.download.PAUSE"
        const val ACTION_CANCEL     = "com.axio.reelz.download.CANCEL"
        const val ACTION_RESUME_ALL = "com.axio.reelz.download.RESUME_ALL"

        const val EXTRA_DOWNLOAD_ID = "downloadId"
        const val EXTRA_URL         = "url"
        const val EXTRA_TYPE        = "type"
        const val EXTRA_HEADERS     = "headers"
        const val EXTRA_TITLE       = "title"

        fun startDownload(
            ctx: Context,
            downloadId: String,
            url: String,
            type: String,
            headers: Map<String, String> = emptyMap(),
            title: String = "",
        ) {
            val intent = Intent(ctx, ReelzDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_TITLE, title)
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
        // Must call startForeground immediately on creation.
        startForeground(NOTIFICATION_ID, buildNotification("Starting downloads…", 0, false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id      = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return START_STICKY
                val url     = intent.getStringExtra(EXTRA_URL)         ?: return START_STICKY
                val type    = intent.getStringExtra(EXTRA_TYPE)        ?: "mp4"
                val title   = intent.getStringExtra(EXTRA_TITLE)       ?: ""
                val headers = parseHeaders(intent.getStringExtra(EXTRA_HEADERS))
                engine.start(id, url, type, headers, title)
                ensureObserving()
            }
            ACTION_PAUSE -> {
                engine.pause(intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: "")
                ensureObserving()
            }
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: ""
                // Cancel engine job and delete the DB row so the observer
                // re-emits without it — this triggers cleanup automatically.
                engine.cancel(id)
                scope.launch {
                    downloadDao.delete(id)
                }
                ensureObserving()
            }
            ACTION_RESUME_ALL -> {
                resumeAllPaused()
                ensureObserving()
            }
        }
        // START_STICKY: Android will restart this service if the process is killed,
        // which means downloads survive app switching and memory pressure.
        return START_STICKY
    }

    private fun resumeAllPaused() {
        scope.launch {
            val paused = downloadDao.getByStatus("PAUSED") + downloadDao.getByStatus("QUEUED")
            paused.forEach { row ->
                val type = when {
                    row.streamUrl.contains(".m3u8", ignoreCase = true) -> "hls"
                    else -> "mp4"
                }
                @Suppress("UNCHECKED_CAST")
                val headers = runCatching {
                    com.google.gson.Gson().fromJson(row.headersJson, Map::class.java) as Map<String, String>
                }.getOrDefault(emptyMap())
                engine.start(row.id, row.streamUrl, type, headers, row.title)
            }
        }
    }

    /**
     * Start the DB observer exactly once. The observer drives the notification
     * and decides when to stop the service.
     */
    private fun ensureObserving() {
        if (observing) return
        observing = true
        scope.launch {
            downloadDao.observeAll().collect { rows ->
                val active   = rows.filter { it.status == "DOWNLOADING" }
                val paused   = rows.filter { it.status == "PAUSED" }
                val queued   = rows.filter { it.status == "QUEUED" }
                val done     = rows.count  { it.status == "DONE" }
                val hasAny   = rows.isNotEmpty()

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

                if (!hasAny) {
                    // No rows at all (all cancelled/cleared) → dismiss notification and stop.
                    cancelNotification()
                    stopSelf()
                } else if (!isActive && paused.isEmpty()) {
                    // Everything is done — update notification once then dismiss after delay.
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
            .setOngoing(isActive)          // sticky only while actively downloading
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
    }

    private fun updateNotification(text: String, progress: Int, isActive: Boolean) {
        runCatching {
            val notification = buildNotification(text, progress, isActive)
            // Keep startForeground in sync so the foreground state matches.
            if (isActive) {
                startForeground(NOTIFICATION_ID, notification)
            } else {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification)
            }
        }
    }

    /** Fully dismiss the notification — called when all downloads are gone. */
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
