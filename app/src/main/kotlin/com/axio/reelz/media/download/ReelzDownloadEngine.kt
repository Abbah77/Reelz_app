package com.axio.reelz.media.download

import android.content.Context
import android.util.Log
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
 * ReelzDownloadEngine — OkHttp download engine for MP4 and HLS.
 *
 * The backend hands the exact URL per quality:
 *   • MP4  → direct file URL → stream to disk with resume support
 *   • HLS  → quality-specific media playlist (not master) → download
 *            segments, write local index.m3u8 for offline ExoPlayer
 *
 * Disk layout:
 *   <externalFilesDir>/reelz_downloads/<downloadId>/
 *     movie.mp4              (MP4)
 *     movie.mp4.tmp          (MP4 in-progress — renamed on completion)
 *     segments/
 *       index.m3u8           (local playlist with absolute segment paths)
 *       seg000000.ts
 *       seg000001.ts  …
 */
@Singleton
class ReelzDownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
) {
    companion object {
        private const val TAG = "ReelzDownloadEngine"
        private const val PARALLEL_SEGMENTS = 4
        private const val SEGMENT_RETRY_MAX = 4
        private const val BUFFER_SIZE = 128 * 1024   // 128 KB
        private const val DOWNLOADS_DIR = "reelz_downloads"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pauseFlags = ConcurrentHashMap<String, AtomicBoolean>()

    // SupervisorJob so one failed download never cancels others.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        type: String,
        headers: Map<String, String> = emptyMap(),
        title: String = "",
    ) {
        if (activeJobs[downloadId]?.isActive == true) return
        pauseFlags[downloadId] = AtomicBoolean(false)

        val job = scope.launch {
            try {
                updateStatus(downloadId, DownloadStatus.DOWNLOADING)
                when (type.lowercase()) {
                    "hls" -> downloadHls(downloadId, url, headers)
                    else  -> downloadMp4(downloadId, url, headers)
                }
            } catch (e: CancellationException) {
                // Paused or cancelled intentionally — don't mark ERROR.
                Log.d(TAG, "[$downloadId] cancelled/paused")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[$downloadId] failed: ${e.message}", e)
                updateStatus(downloadId, DownloadStatus.ERROR)
            } finally {
                activeJobs.remove(downloadId)
            }
        }
        activeJobs[downloadId] = job
    }

    fun pause(downloadId: String) {
        pauseFlags[downloadId]?.set(true)
        activeJobs[downloadId]?.cancel()
        scope.launch { updateStatus(downloadId, DownloadStatus.PAUSED) }
    }

    fun cancel(downloadId: String) {
        pauseFlags[downloadId]?.set(true)
        activeJobs[downloadId]?.cancel()
        scope.launch { downloadDir(downloadId).deleteRecursively() }
    }

    // ── MP4 ───────────────────────────────────────────────────────────────────

    private suspend fun downloadMp4(
        downloadId: String,
        url: String,
        headers: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val outFile = File(downloadDir(downloadId), "movie.mp4")
        val tmpFile = File(downloadDir(downloadId), "movie.mp4.tmp")

        if (outFile.exists() && outFile.length() > 1024) {
            markDone(downloadId, outFile)
            return@withContext
        }

        // Probe server for resume support via HEAD + Accept-Ranges.
        val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L
        val acceptsRanges = try {
            val req = Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .head().build()
            client.newCall(req).execute().use { r ->
                r.isSuccessful &&
                r.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
            }
        } catch (_: Exception) { false }

        val resumeFrom = if (acceptsRanges && existingBytes > 0) existingBytes
                         else { tmpFile.delete(); 0L }

        val downloadedBytes = AtomicLong(resumeFrom)

        val request = Request.Builder().url(url)
            .apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
                if (resumeFrom > 0) addHeader("Range", "bytes=$resumeFrom-")
            }
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            error("HTTP ${response.code}")
        }

        val totalSize = when (response.code) {
            206  -> response.header("Content-Range")
                        ?.substringAfterLast('/')?.toLongOrNull()
                        ?: ((response.body?.contentLength() ?: 0L) + resumeFrom)
            else -> response.body?.contentLength() ?: 0L
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
                    // Write progress to DB every ~2 MB to reduce I/O churn.
                    if (done % (2 * 1024 * 1024) < BUFFER_SIZE) {
                        downloadDao.updateProgress(
                            id = downloadId, status = DownloadStatus.DOWNLOADING.name,
                            bytes = done, done = 0,
                            total = if (totalSize > 0) 1 else 0, playlist = "",
                        )
                    }
                }
                fos.flush()
            }
        } finally {
            body.close()
            response.close()
        }

        // Atomic rename — only a fully written file becomes the final output.
        if (!tmpFile.renameTo(outFile)) {
            tmpFile.copyTo(outFile, overwrite = true)
            tmpFile.delete()
        }

        markDone(downloadId, outFile)
        Log.i(TAG, "[$downloadId] MP4 done: ${outFile.absolutePath}")
    }

    // ── HLS ───────────────────────────────────────────────────────────────────
    //
    // The backend hands a quality-specific media playlist URL — NOT a master
    // playlist. We fetch it, parse segments, download them in parallel, then
    // write a local index.m3u8 pointing at the saved .ts files.

    private suspend fun downloadHls(
        downloadId: String,
        mediaPlaylistUrl: String,
        headers: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val segDir = segmentsDir(downloadId)

        Log.d(TAG, "[$downloadId] Fetching media playlist: $mediaPlaylistUrl")
        val playlistContent = fetchText(mediaPlaylistUrl, headers)
            ?: error("Failed to fetch HLS media playlist")

        val segments = parseSegments(playlistContent, mediaPlaylistUrl)
        if (segments.isEmpty()) error("No segments in media playlist")

        val total = segments.size
        Log.d(TAG, "[$downloadId] $total segments")

        val completedCount = AtomicLong(
            segments.count { seg ->
                File(segDir, segFilename(seg.index)).let { it.exists() && it.length() > 0 }
            }.toLong()
        )

        val semaphore = Semaphore(PARALLEL_SEGMENTS)

        // Only download segments that aren't already on disk (resume support).
        val jobs = segments
            .filter { seg -> !File(segDir, segFilename(seg.index)).let { it.exists() && it.length() > 0 } }
            .map { seg ->
                scope.async {
                    semaphore.withPermit {
                        checkPause(downloadId)
                        downloadSegment(seg, segDir, headers)
                        val done = completedCount.incrementAndGet()
                        val approxBytes = done * (segDir.listFiles()
                            ?.firstOrNull()?.length() ?: 512_000L)
                        downloadDao.updateProgress(
                            id = downloadId, status = DownloadStatus.DOWNLOADING.name,
                            bytes = approxBytes, done = done.toInt(),
                            total = total, playlist = "",
                        )
                    }
                }
            }

        try {
            jobs.awaitAll()
        } catch (e: CancellationException) {
            jobs.forEach { it.cancel() }
            throw e
        }

        val missing = segments.count {
            !File(segDir, segFilename(it.index)).let { f -> f.exists() && f.length() > 0 }
        }
        if (missing > 0) error("$missing segments failed")

        // Rewrite the playlist replacing remote URIs with local absolute paths.
        val localM3u8 = File(segDir, "index.m3u8")
        localM3u8.writeText(buildLocalPlaylist(playlistContent, segments, segDir))

        downloadDao.markDone(
            id = downloadId, status = DownloadStatus.DONE.name,
            path = localM3u8.absolutePath, at = System.currentTimeMillis(),
        )
        Log.i(TAG, "[$downloadId] HLS done: ${localM3u8.absolutePath}")
    }

    // ── HLS helpers ───────────────────────────────────────────────────────────

    data class Segment(val index: Int, val url: String)

    private fun parseSegments(content: String, baseUrl: String): List<Segment> {
        val base = baseUrl.substringBeforeLast('/')
        val lines = content.lines()
        val segments = mutableListOf<Segment>()
        var idx = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                // Skip any intermediate tags to find the URI line.
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

    private suspend fun downloadSegment(
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
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
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
                        tmp.delete(); throw e
                    }
                }
                return
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Seg ${seg.index} attempt $attempt failed: ${e.message}")
                if (attempt < SEGMENT_RETRY_MAX - 1) delay(300L * (1L shl attempt))
            }
        }
        throw lastError ?: IOException("Segment ${seg.index} failed after $SEGMENT_RETRY_MAX attempts")
    }

    /** Rewrite the m3u8 replacing remote segment URIs with local absolute file paths.
     *  All header and encryption tags (#EXT-X-KEY, etc.) are kept intact. */
    private fun buildLocalPlaylist(
        original: String,
        segments: List<Segment>,
        segDir: File,
    ): String {
        val sb = StringBuilder()
        val lines = original.lines()
        var segIdx = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                sb.appendLine(line)
                var j = i + 1
                while (j < lines.size && lines[j].trimStart().startsWith("#")) {
                    sb.appendLine(lines[j])
                    j++
                }
                if (j < lines.size && !lines[j].startsWith("#") && lines[j].isNotBlank()) {
                    sb.appendLine(File(segDir, segFilename(segIdx++)).absolutePath)
                    i = j + 1
                    continue
                }
            } else if (line.isNotBlank() || i < lines.lastIndex) {
                sb.appendLine(line)
            }
            i++
        }
        return sb.toString().trimEnd()
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url)
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) { Log.e(TAG, "fetchText HTTP ${resp.code}: $url"); null }
                    else resp.body?.string()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "fetchText: $url — ${e.message}"); null }
        }

    // ── Lifecycle helpers ─────────────────────────────────────────────────────

    private fun checkPause(downloadId: String) {
        if (pauseFlags[downloadId]?.get() == true) throw CancellationException("paused")
    }

    private suspend fun markDone(downloadId: String, file: File) {
        downloadDao.markDone(
            id = downloadId, status = DownloadStatus.DONE.name,
            path = file.absolutePath, at = System.currentTimeMillis(),
        )
    }

    private suspend fun updateStatus(downloadId: String, status: DownloadStatus) {
        try {
            val row = downloadDao.get(downloadId) ?: return
            downloadDao.updateProgress(
                id = downloadId, status = status.name,
                bytes = row.downloadedBytes, done = row.segmentsDone,
                total = row.totalSegments, playlist = row.localPlaylistPath,
            )
        } catch (e: Exception) {
            Log.w(TAG, "updateStatus: ${e.message}")
        }
    }

    /** Returns local path for offline ExoPlayer playback. */
    fun getLocalPlaybackPath(downloadId: String, type: String): String? =
        when (type.lowercase()) {
            "hls" -> File(segmentsDir(downloadId), "index.m3u8").takeIf { it.exists() }?.absolutePath
            else  -> File(downloadDir(downloadId), "movie.mp4").takeIf { it.exists() }?.absolutePath
        }
}
