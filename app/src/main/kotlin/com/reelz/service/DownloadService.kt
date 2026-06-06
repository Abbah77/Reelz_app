package com.reelz.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.reelz.data.local.DownloadDao
import com.reelz.data.model.DownloadItem
import com.reelz.data.model.DownloadStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import okhttp3.*
import java.io.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var downloadDao: DownloadDao

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val CHANNEL_ID    = "reelz_downloads"
        const val EXTRA_DL_ID   = "dl_id"
        fun start(ctx: Context, dlId: String) {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).putExtra(EXTRA_DL_ID, dlId))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("Downloads", "Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val dlId = intent?.getStringExtra(EXTRA_DL_ID) ?: return START_NOT_STICKY
        scope.launch { processDownload(dlId) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun processDownload(dlId: String) {
        val item = downloadDao.get(dlId) ?: return
        val outputDir = File(filesDir, "downloads").also { it.mkdirs() }
        val outputFile = File(outputDir, "${dlId}.mp4")

        downloadDao.updateProgress(dlId, DownloadStatus.DOWNLOADING.name, 0L)
        updateNotif("Downloading", item.title)

        try {
            if (item.streamUrl.contains(".m3u8", true)) {
                downloadHls(item, outputFile, dlId)
            } else {
                downloadDirect(item, outputFile, dlId)
            }
            downloadDao.markDone(dlId, DownloadStatus.DONE.name, outputFile.absolutePath, System.currentTimeMillis())
            updateNotif("Complete", "${item.title} downloaded")
        } catch (e: Exception) {
            outputFile.delete()
            downloadDao.updateProgress(dlId, DownloadStatus.ERROR.name, 0L)
            updateNotif("Failed", "Could not download ${item.title}")
        }
    }

    private suspend fun downloadDirect(item: DownloadItem, output: File, dlId: String) {
        val headers = com.google.gson.Gson().fromJson(item.headers, Map::class.java) as? Map<String, String> ?: emptyMap()
        val req = Request.Builder().url(item.streamUrl).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        val resp = client.newCall(req).execute()
        val body = resp.body ?: throw IOException("Empty response")
        val total = body.contentLength()
        var downloaded = 0L
        FileOutputStream(output).use { fos ->
            body.byteStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    fos.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) downloadDao.updateProgress(dlId, DownloadStatus.DOWNLOADING.name, downloaded)
                }
            }
        }
    }

    private suspend fun downloadHls(item: DownloadItem, output: File, dlId: String) {
        // Parse m3u8 and download segments, then merge with ffmpeg-style concat
        val headers = com.google.gson.Gson().fromJson(item.headers, Map::class.java) as? Map<String, String> ?: emptyMap()
        val m3u8Content = fetchText(item.streamUrl, headers)
        val baseUrl = item.streamUrl.substringBeforeLast("/") + "/"

        // Extract segment URLs
        val segments = m3u8Content.lines()
            .filter { !it.startsWith("#") && it.isNotBlank() }
            .map { if (it.startsWith("http")) it else baseUrl + it }

        if (segments.isEmpty()) throw IOException("No segments found in playlist")

        val segDir = File(filesDir, "tmp_${dlId}").also { it.mkdirs() }
        val total = segments.size.toLong()
        var done  = 0L

        // Download all segments
        segments.forEachIndexed { i, segUrl ->
            val segFile = File(segDir, "seg_$i.ts")
            if (!segFile.exists()) {
                val req = Request.Builder().url(segUrl).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()
                val resp = client.newCall(req).execute()
                val body = resp.body ?: return@forEachIndexed
                segFile.writeBytes(body.bytes())
            }
            done++
            downloadDao.updateProgress(dlId, DownloadStatus.DOWNLOADING.name, done * 100 / total)
        }

        // Concat all .ts segments into output.mp4
        FileOutputStream(output).use { fos ->
            segments.indices.forEach { i ->
                val f = File(segDir, "seg_$i.ts")
                if (f.exists()) fos.write(f.readBytes())
            }
        }
        segDir.deleteRecursively()
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()
        return client.newCall(req).execute().body?.string() ?: ""
    }

    private fun buildNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).build()

    private fun updateNotif(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(1, buildNotification(title, text))
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }
}
