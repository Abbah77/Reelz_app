package com.axio.reelz.media.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.common.MediaItem
import android.net.Uri
import com.axio.reelz.core.database.DownloadDao
import com.axio.reelz.data.model.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ReelzDownloadEngine — MP4-only download engine (HLS removed).
 *
 * Design:
 *   • MP4  → Range-resumable HTTP download with atomic tmp→final rename.
 *   • HLS  → OkHttp downloads all .ts segments in parallel (8 workers),
 *             then Media3 Transformer remuxes segments/ → movie.mp4 (~3-5s, no re-encode).
 *             After remux, segments/ is deleted.  UI shows REMUXING status during remux.
 *   • Network-aware: auto-resumes paused/failed downloads when connectivity is restored.
 *   • Progress: DB updated every ~1 MB for MP4; per-segment for HLS download phase.
 *
 * Disk layout:
 *   <externalFilesDir>/reelz_downloads/<downloadId>/
 *     movie.mp4              (final file — present when DONE)
 *     movie.mp4.tmp          (in-progress MP4, renamed on completion)
 *     segments/              (HLS only — deleted after remux)
 *       seg000000.ts
 *       seg000001.ts  …
 *     subtitles/
 *       en.srt
 */
@Singleton
class ReelzDownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
) {
    companion object {
        private const val TAG = "ReelzDownloadEngine"

        /** Parallel segment workers for HLS. */
        private const val PARALLEL_SEGMENTS = 8

        /** Per-segment retry attempts with exponential backoff. */
        private const val SEGMENT_RETRY_MAX = 6

        /** Read/write buffer — 512 KB gives good throughput. */
        private const val BUFFER_SIZE = 512 * 1024

        /** DB progress flush interval in bytes (MP4 path). */
        private const val PROGRESS_FLUSH_BYTES = 1 * 1024 * 1024L // 1 MB

        private const val DOWNLOADS_DIR = "reelz_downloads"
    }

    // ── OkHttp client ─────────────────────────────────────────────────────────
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ── State ─────────────────────────────────────────────────────────────────
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs  = ConcurrentHashMap<String, Job>()
    private val pauseFlags  = ConcurrentHashMap<String, AtomicBoolean>()
    private val pausedIds   = ConcurrentHashMap.newKeySet<String>()

    // ── Network awareness ─────────────────────────────────────────────────────
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                engineScope.launch { resumePausedByNetwork() }
            }
        })
    }

    private suspend fun resumePausedByNetwork() {
        val networkPaused = pausedIds.toSet().filter { id ->
            !activeJobs[id]?.isActive.let { it ?: false }
        }
        if (networkPaused.isEmpty()) return
        Log.d(TAG, "Network restored — auto-resuming ${networkPaused.size} downloads")
        networkPaused.forEach { id ->
            val row = downloadDao.get(id) ?: return@forEach
            if (row.status == DownloadStatus.PAUSED.name || row.status == DownloadStatus.ERROR.name) {
                @Suppress("UNCHECKED_CAST")
                val headers = runCatching {
                    com.google.gson.Gson().fromJson(row.headersJson, Map::class.java) as Map<String, String>
                }.getOrDefault(emptyMap())
                start(id, row.streamUrl, "mp4", headers, row.title, autoResume = true)
            }
        }
    }

    // ── Directories ───────────────────────────────────────────────────────────
    private fun rootDir(): File {
        val ext = context.getExternalFilesDir(null)
        val dir = if (ext != null) File(ext, DOWNLOADS_DIR) else File(context.filesDir, DOWNLOADS_DIR)
        dir.mkdirs()
        return dir
    }

    fun downloadDir(downloadId: String): File =
        File(rootDir(), downloadId).also { it.mkdirs() }

    fun segmentsDir(downloadId: String): File =
        File(downloadDir(downloadId), "segments").also { it.mkdirs() }

    fun subtitlesDir(downloadId: String): File =
        File(downloadDir(downloadId), "subtitles").also { it.mkdirs() }

    // ── Public API ────────────────────────────────────────────────────────────
    fun start(
        downloadId: String,
        url: String,
        type: String,           // "mp4" | "hls" — both handled; HLS gets remuxed to MP4
        headers: Map<String, String> = emptyMap(),
        title: String = "",
        autoResume: Boolean = false,
    ) {
        if (activeJobs[downloadId]?.isActive == true) return

        pauseFlags[downloadId] = AtomicBoolean(false)
        pausedIds.remove(downloadId)

        val job = engineScope.launch {
            try {
                updateStatus(downloadId, DownloadStatus.DOWNLOADING)
                when (type.lowercase()) {
                    "hls" -> downloadHlsThenRemux(downloadId, url, headers)
                    else  -> downloadMp4(downloadId, url, headers)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "[$downloadId] cancelled/paused")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[$downloadId] failed: ${e.message}", e)
                val isNetworkError = e is java.net.UnknownHostException ||
                        e is java.net.ConnectException ||
                        e is java.net.SocketException ||
                        e is java.net.SocketTimeoutException
                if (isNetworkError) {
                    pausedIds.add(downloadId)
                    updateStatus(downloadId, DownloadStatus.PAUSED)
                    Log.d(TAG, "[$downloadId] network error → PAUSED for auto-resume")
                } else {
                    updateStatus(downloadId, DownloadStatus.ERROR)
                }
            } finally {
                activeJobs.remove(downloadId)
            }
        }
        activeJobs[downloadId] = job
    }

    fun pause(downloadId: String) {
        pauseFlags[downloadId]?.set(true)
        pausedIds.add(downloadId)
        activeJobs[downloadId]?.cancel()
        engineScope.launch { updateStatus(downloadId, DownloadStatus.PAUSED) }
    }

    fun cancel(downloadId: String) {
        pauseFlags[downloadId]?.set(true)
        pausedIds.remove(downloadId)
        activeJobs[downloadId]?.cancel()
        engineScope.launch { downloadDir(downloadId).deleteRecursively() }
    }

    // ── MP4 direct download ───────────────────────────────────────────────────
    private suspend fun downloadMp4(
        downloadId: String,
        url: String,
        headers: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val outFile = File(downloadDir(downloadId), "movie.mp4")
        val tmpFile = File(downloadDir(downloadId), "movie.mp4.tmp")

        // Already finished
        if (outFile.exists() && outFile.length() > 1024) {
            markDone(downloadId, outFile)
            return@withContext
        }

        val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L
        val acceptsRanges = probeRangeSupport(url, headers)
        val resumeFrom = if (acceptsRanges && existingBytes > 0) existingBytes
                         else { tmpFile.delete(); 0L }

        val downloadedBytes = AtomicLong(resumeFrom)
        var lastFlush = downloadedBytes.get()

        val request = Request.Builder().url(url)
            .apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
                if (!headers.containsKey("User-Agent")) {
                    addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    )
                }
                if (resumeFrom > 0) addHeader("Range", "bytes=$resumeFrom-")
            }
            .build()

        val response = executeWithRetry(request)
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            error("HTTP ${response.code} for MP4")
        }

        val totalSize = when (response.code) {
            206  -> response.header("Content-Range")
                        ?.substringAfterLast('/')?.toLongOrNull()
                        ?: ((response.body?.contentLength() ?: 0L) + resumeFrom)
            else -> response.body?.contentLength() ?: 0L
        }

        if (totalSize > 0) {
            downloadDao.updateProgress(
                id = downloadId, status = DownloadStatus.DOWNLOADING.name,
                bytes = resumeFrom, sizeBytes = totalSize,
            )
        }

        val body = response.body ?: run { response.close(); error("Empty body") }
        try {
            FileOutputStream(tmpFile, response.code == 206 && resumeFrom > 0).use { fos ->
                val buf = ByteArray(BUFFER_SIZE)
                var read: Int
                while (body.byteStream().read(buf).also { read = it } != -1) {
                    checkPause(downloadId)
                    fos.write(buf, 0, read)
                    val done = downloadedBytes.addAndGet(read.toLong())
                    if (done - lastFlush >= PROGRESS_FLUSH_BYTES) {
                        lastFlush = done
                        downloadDao.updateProgress(
                            id = downloadId, status = DownloadStatus.DOWNLOADING.name,
                            bytes = done, sizeBytes = totalSize,
                        )
                    }
                }
                fos.flush()
            }
        } finally {
            body.close()
            response.close()
        }

        // Atomic rename
        if (!tmpFile.renameTo(outFile)) {
            tmpFile.copyTo(outFile, overwrite = true)
            tmpFile.delete()
        }

        markDone(downloadId, outFile, totalSizeOverride = outFile.length())
        Log.i(TAG, "[$downloadId] MP4 done: ${outFile.absolutePath} (${outFile.length()} bytes)")
    }

    private fun probeRangeSupport(url: String, headers: Map<String, String>): Boolean = try {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .head().build()
        client.newCall(req).execute().use { r ->
            r.isSuccessful &&
            r.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
        }
    } catch (_: Exception) { false }

    // ── HLS: download segments + Media3 remux → movie.mp4 ────────────────────
    //
    // Flow:
    //   1. Fetch quality-specific media playlist from backend URL.
    //   2. Parse all #EXTINF segment URIs.
    //   3. Download segments in parallel (8 workers) into segments/ folder.
    //      Completed .ts files survive pause — resume skips them.
    //   4. Mark REMUXING, run Media3 Transformer to stitch → movie.mp4.
    //   5. Delete segments/ folder. Mark DONE.
    //   6. If remux fails: status → ERROR, segments/ stays so retry goes straight
    //      to remux (skips re-download).

    private suspend fun downloadHlsThenRemux(
        downloadId: String,
        mediaPlaylistUrl: String,
        headers: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val segDir = segmentsDir(downloadId)
        val outFile = File(downloadDir(downloadId), "movie.mp4")

        // If final file already exists, just mark done (handles restart after crash during rename)
        if (outFile.exists() && outFile.length() > 1024) {
            markDone(downloadId, outFile)
            return@withContext
        }

        // ── Check if segments are already downloaded (resume after remux failure) ──
        val existingSegments = segDir.listFiles()
            ?.filter { it.name.matches(Regex("seg\\d+\\.ts")) }
            ?: emptyList()

        val needsDownload = existingSegments.isEmpty() ||
            existingSegments.any { it.length() == 0L }

        if (needsDownload) {
            Log.d(TAG, "[$downloadId] Fetching HLS playlist: $mediaPlaylistUrl")
            val playlistContent = fetchTextWithRetry(mediaPlaylistUrl, headers)
                ?: error("Failed to fetch HLS media playlist after retries")

            val segments = parseSegments(playlistContent, mediaPlaylistUrl)
            if (segments.isEmpty()) error("HLS playlist has no segments")

            val total = segments.size
            Log.d(TAG, "[$downloadId] $total segments to download")

            val completedCount = AtomicLong(
                segments.count { seg ->
                    File(segDir, segFilename(seg.index)).let { it.exists() && it.length() > 0 }
                }.toLong()
            )

            downloadDao.updateProgress(
                id = downloadId, status = DownloadStatus.DOWNLOADING.name,
                bytes = completedCount.get() * estimateSegmentSize(segDir),
                sizeBytes = 0L,
            )

            val semaphore = kotlinx.coroutines.sync.Semaphore(PARALLEL_SEGMENTS)
            val pendingSegments = segments.filter { seg ->
                !File(segDir, segFilename(seg.index)).let { it.exists() && it.length() > 0 }
            }

            coroutineScope {
                val jobs = pendingSegments.map { seg ->
                    async {
                        semaphore.withPermit {
                            checkPause(downloadId)
                            downloadSegmentWithRetry(seg, segDir, headers)
                            val done = completedCount.incrementAndGet()
                            val approxBytes = done * estimateSegmentSize(segDir)
                            downloadDao.updateProgress(
                                id = downloadId, status = DownloadStatus.DOWNLOADING.name,
                                bytes = approxBytes, sizeBytes = 0L,
                            )
                        }
                    }
                }
                jobs.awaitAll()
            }

            val missing = segments.count {
                !File(segDir, segFilename(it.index)).let { f -> f.exists() && f.length() > 0 }
            }
            if (missing > 0) error("$missing HLS segments failed to download")
        } else {
            Log.d(TAG, "[$downloadId] Segments already downloaded — skipping to remux")
        }

        // ── Remux: segments/ → movie.mp4 via Media3 Transformer ──────────────
        Log.d(TAG, "[$downloadId] Starting remux")
        downloadDao.updateRemuxProgress(downloadId, 0)

        remuxSegmentsToMp4(downloadId, segDir, outFile)

        // Clean up segments folder
        segDir.deleteRecursively()
        Log.d(TAG, "[$downloadId] Segments deleted")

        markDone(downloadId, outFile, totalSizeOverride = outFile.length())
        Log.i(TAG, "[$downloadId] HLS→MP4 done: ${outFile.absolutePath} (${outFile.length()} bytes)")
    }

    /**
     * Remux .ts segment files into a single movie.mp4 using Media3 Transformer.
     * No re-encode — equivalent to `ffmpeg -c copy`. Takes ~3-5 seconds.
     */
    private suspend fun remuxSegmentsToMp4(
        downloadId: String,
        segDir: File,
        outFile: File,
    ) = withContext(Dispatchers.Main) {
        // Collect all .ts files in order
        val tsFiles = segDir.listFiles()
            ?.filter { it.name.matches(Regex("seg\\d+\\.ts")) }
            ?.sortedBy { it.name }
            ?: error("No .ts segments found in ${segDir.absolutePath}")

        if (tsFiles.isEmpty()) error("No .ts segments to remux")

        val editedItems = tsFiles.map { tsFile ->
            EditedMediaItem.Builder(
                MediaItem.fromUri(Uri.fromFile(tsFile))
            ).build()
        }

        val composition = Composition.Builder(
            EditedMediaItemSequence(editedItems)
        ).build()

        val outPath = outFile.absolutePath
        // Delete any partial output from a previous attempt
        outFile.delete()

        val progressHolder = ProgressHolder()
        var remuxError: Exception? = null
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    completed.complete(Unit)
                }
                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    remuxError = exportException
                    completed.complete(Unit)
                }
            })
            .build()

        transformer.start(composition, outPath)

        // Poll progress until done
        while (!completed.isCompleted) {
            delay(500)
            val progress = transformer.getProgress(progressHolder)
            if (progress == Transformer.PROGRESS_STATE_AVAILABLE) {
                val pct = progressHolder.progress
                downloadDao.updateRemuxProgress(downloadId, pct)
                Log.d(TAG, "[$downloadId] Remux progress: $pct%")
            }
        }

        completed.await()

        remuxError?.let {
            outFile.delete()
            throw IOException("Media3 Transformer remux failed: ${it.message}", it)
        }

        if (!outFile.exists() || outFile.length() == 0L) {
            throw IOException("Remux produced empty output file")
        }

        downloadDao.updateRemuxProgress(downloadId, 100)
        Log.d(TAG, "[$downloadId] Remux complete: ${outFile.length()} bytes")
    }

    // ── HLS helpers ───────────────────────────────────────────────────────────

    private data class Segment(val index: Int, val url: String)

    private fun parseSegments(content: String, baseUrl: String): List<Segment> {
        val base = baseUrl.substringBeforeLast('/')
        val lines = content.lines()
        val segments = mutableListOf<Segment>()
        var idx = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                var j = i + 1
                while (j < lines.size && lines[j].trimStart().startsWith("#")) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (uri.isNotBlank() && !uri.startsWith("#")) {
                        segments.add(Segment(idx++, resolveUrl(uri, base, baseUrl)))
                        i = j + 1
                        continue
                    }
                }
            }
            i++
        }
        return segments
    }

    private fun resolveUrl(uri: String, base: String, fullBaseUrl: String): String = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> uri
        uri.startsWith("/") -> {
            val proto = fullBaseUrl.substringBefore("://")
            val host  = fullBaseUrl.substringAfter("://").substringBefore("/")
            "$proto://$host$uri"
        }
        else -> "$base/$uri"
    }

    private fun segFilename(index: Int) = "seg%06d.ts".format(index)

    private fun estimateSegmentSize(segDir: File): Long =
        segDir.listFiles()?.filter { it.name.endsWith(".ts") && it.length() > 0 }
            ?.let { files -> if (files.isNotEmpty()) files.sumOf { it.length() } / files.size else 512_000L }
            ?: 512_000L

    private suspend fun downloadSegmentWithRetry(
        seg: Segment,
        segDir: File,
        headers: Map<String, String>,
    ) {
        val outFile = File(segDir, segFilename(seg.index))
        if (outFile.exists() && outFile.length() > 0) return

        var lastError: Exception? = null
        for (attempt in 0 until SEGMENT_RETRY_MAX) {
            try {
                val request = Request.Builder().url(seg.url)
                    .apply {
                        headers.forEach { (k, v) -> addHeader(k, v) }
                        if (!headers.containsKey("User-Agent")) {
                            addHeader(
                                "User-Agent",
                                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            )
                        }
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code} for segment ${seg.index}")
                    val body = response.body ?: error("Empty segment body")
                    val tmp = File(segDir, "${segFilename(seg.index)}.tmp")
                    try {
                        tmp.outputStream().buffered(BUFFER_SIZE).use { out ->
                            body.byteStream().buffered(BUFFER_SIZE).copyTo(out)
                        }
                        if (!tmp.renameTo(outFile)) {
                            tmp.copyTo(outFile, overwrite = true)
                            tmp.delete()
                        }
                    } catch (e: Exception) {
                        tmp.delete()
                        throw e
                    }
                }
                return
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Seg ${seg.index} attempt $attempt failed: ${e.message}")
                if (attempt < SEGMENT_RETRY_MAX - 1) {
                    delay(300L * (1L shl attempt.coerceAtMost(4)))
                }
            }
        }
        throw lastError ?: IOException("Segment ${seg.index} failed after $SEGMENT_RETRY_MAX attempts")
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────
    private fun executeWithRetry(request: Request, maxAttempts: Int = 4): Response {
        var lastError: Exception? = null
        for (attempt in 0 until maxAttempts) {
            try {
                return client.newCall(request).execute()
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1) Thread.sleep(500L * (1L shl attempt))
            }
        }
        throw lastError ?: IOException("Request failed after $maxAttempts attempts")
    }

    private suspend fun fetchTextWithRetry(url: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (attempt in 0..4) {
                try {
                    val req = Request.Builder().url(url)
                        .apply {
                            headers.forEach { (k, v) -> addHeader(k, v) }
                            // Add a browser-like User-Agent if not already set — some CDNs
                            // reject bare OkHttp requests that lack one
                            if (!headers.containsKey("User-Agent")) {
                                addHeader(
                                    "User-Agent",
                                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                )
                            }
                        }
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            // Log the real status so we can diagnose CDN blocks vs network errors
                            val errBody = resp.body?.string()?.take(300) ?: ""
                            Log.w(TAG, "fetchText attempt $attempt: HTTP ${resp.code} for $url — $errBody")
                            lastError = IOException("HTTP ${resp.code}")
                        } else {
                            return@withContext resp.body?.string()
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "fetchText attempt $attempt: $url — ${e.message}")
                }
                if (attempt < 4) delay(400L * (1L shl attempt))
            }
            Log.e(TAG, "fetchText gave up after 5 attempts for $url — last error: ${lastError?.message}")
            null
        }

    // ── Lifecycle helpers ─────────────────────────────────────────────────────
    private fun checkPause(downloadId: String) {
        if (pauseFlags[downloadId]?.get() == true) throw CancellationException("paused")
    }

    private suspend fun markDone(
        downloadId: String,
        file: File,
        totalSizeOverride: Long = 0L,
    ) {
        val sz = if (totalSizeOverride > 0) totalSizeOverride else file.length()
        downloadDao.markDone(
            id        = downloadId,
            path      = file.absolutePath,
            at        = System.currentTimeMillis(),
            sizeBytes = sz,
        )
    }

    private suspend fun updateStatus(downloadId: String, status: DownloadStatus) {
        try {
            val row = downloadDao.get(downloadId) ?: return
            downloadDao.updateProgress(
                id        = downloadId,
                status    = status.name,
                bytes     = row.downloadedBytes,
                sizeBytes = row.sizeBytes,
            )
        } catch (e: Exception) {
            Log.w(TAG, "updateStatus: ${e.message}")
        }
    }

    /** Returns the local mp4 path for offline ExoPlayer playback. Always MP4 now. */
    fun getLocalPlaybackPath(downloadId: String, type: String): String? =
        File(downloadDir(downloadId), "movie.mp4").takeIf { it.exists() }?.absolutePath
}
