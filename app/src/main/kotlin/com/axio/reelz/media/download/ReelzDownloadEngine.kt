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
import okio.*
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ReelzDownloadEngine — High-speed OkHttp download engine for MP4 and HLS.
 *
 * Architecture:
 *   - MP4: single OkHttp call with streaming write + resume via Range header
 *   - HLS: fetch index.m3u8 → parse segments → parallel download of .ts files
 *          → write local index.m3u8 pointing to local .ts files
 *          → ExoPlayer reads the local index.m3u8 for offline playback
 *
 * Hierarchy on disk:
 *   <externalFilesDir>/reelz_downloads/
 *     <downloadId>/
 *       meta.json              (mp4 or hls metadata)
 *       movie.mp4              (for mp4 type)
 *       segments/              (for hls type)
 *         index.m3u8           (local playlist pointing to .ts files)
 *         seg000000.ts
 *         seg000001.ts
 *         ...
 *       subtitles/
 *         en.srt
 *
 * Pause/Resume:
 *   - MP4: tracks downloaded bytes; resumes with Range: bytes=N-
 *   - HLS: tracks completed segments; skips already-downloaded .ts files
 *
 * ExoPlayer offline:
 *   - MP4: MediaItem.fromUri(File("movie.mp4").toUri())
 *   - HLS: MediaItem.fromUri(File("segments/index.m3u8").toUri()) — ExoPlayer reads
 *          the local playlist which references local .ts files directly
 */
@Singleton
class ReelzDownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
) {
    companion object {
        private const val TAG = "ReelzDownloadEngine"

        private const val PARALLEL_SEGMENTS = 6          // concurrent .ts downloads
        private const val SEGMENT_RETRY_MAX = 3
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S    = 30L
        private const val WRITE_TIMEOUT_S   = 30L
        private const val BUFFER_SIZE       = 128 * 1024 // 128 KB

        // Root download directory
        private const val DOWNLOADS_DIR = "reelz_downloads"
    }

    // OkHttp client — shared, connection pool for speed
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .build()

    // Active job handles — for pause/cancel
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pauseFlags = ConcurrentHashMap<String, AtomicBoolean>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Directory helpers ─────────────────────────────────────────────────────

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

    /**
     * Start or resume a download. Non-blocking — launches coroutine and returns.
     * Progress is written to the DB via [downloadDao].
     */
    fun start(
        downloadId: String,
        url:        String,
        type:       String,          // "mp4" | "hls"
        headers:    Map<String, String> = emptyMap(),
        title:      String = "",
    ) {
        if (activeJobs[downloadId]?.isActive == true) return  // already running
        pauseFlags[downloadId] = AtomicBoolean(false)

        val job = scope.launch {
            try {
                updateStatus(downloadId, DownloadStatus.DOWNLOADING)
                when (type.lowercase()) {
                    "hls" -> downloadHls(downloadId, url, headers)
                    else  -> downloadMp4(downloadId, url, headers)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "[$downloadId] cancelled")
                // Don't mark as error on cancellation — it was paused or cancelled intentionally
            } catch (e: Exception) {
                Log.e(TAG, "[$downloadId] failed: ${e.message}")
                updateStatus(downloadId, DownloadStatus.ERROR)
            } finally {
                activeJobs.remove(downloadId)
            }
        }
        activeJobs[downloadId] = job
    }

    /** Pause an active download gracefully. */
    fun pause(downloadId: String) {
        pauseFlags[downloadId]?.set(true)
        activeJobs[downloadId]?.cancel()
        scope.launch { updateStatus(downloadId, DownloadStatus.PAUSED) }
    }

    /** Cancel and remove a download's files. */
    fun cancel(downloadId: String) {
        pauseFlags[downloadId]?.set(true)
        activeJobs[downloadId]?.cancel()
        scope.launch {
            downloadDir(downloadId).deleteRecursively()
            downloadDao.delete(downloadId)
        }
    }

    // ── MP4 Downloader ────────────────────────────────────────────────────────

    private suspend fun downloadMp4(
        downloadId: String,
        url:        String,
        headers:    Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val outFile = File(downloadDir(downloadId), "movie.mp4")
        val existingBytes = if (outFile.exists()) outFile.length() else 0L
        val downloadedBytes = AtomicLong(existingBytes)

        val request = Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
                if (existingBytes > 0) addHeader("Range", "bytes=$existingBytes-")
            }
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            error("HTTP ${response.code}")
        }

        val totalSize = response.header("Content-Length")?.toLongOrNull()
            ?.let { it + existingBytes } ?: 0L

        val body   = response.body ?: error("Empty body")
        val source = body.source()

        // Append if resuming (206), otherwise overwrite
        val append = response.code == 206 && existingBytes > 0
        FileOutputStream(outFile, append).use { fos ->
            val buf = ByteArray(BUFFER_SIZE)
            var read: Int
            while (source.read(buf).also { read = it } != -1) {
                checkPause(downloadId)
                fos.write(buf, 0, read)
                val done = downloadedBytes.addAndGet(read.toLong())
                downloadDao.updateProgress(
                    id       = downloadId,
                    status   = DownloadStatus.DOWNLOADING.name,
                    bytes    = done,
                    done     = 0,
                    total    = 0,
                    playlist = "",
                )
            }
        }
        response.close()

        val playlistPath = outFile.absolutePath
        downloadDao.markDone(
            id     = downloadId,
            status = DownloadStatus.DONE.name,
            path   = playlistPath,
            at     = System.currentTimeMillis(),
        )
        Log.i(TAG, "[$downloadId] MP4 done: $playlistPath")
    }

    // ── HLS Downloader ────────────────────────────────────────────────────────

    private suspend fun downloadHls(
        downloadId: String,
        indexUrl:   String,
        headers:    Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val segDir = segmentsDir(downloadId)

        // 1. Fetch the quality-specific index.m3u8
        Log.d(TAG, "[$downloadId] Fetching index: $indexUrl")
        val playlistContent = fetchText(indexUrl, headers)
            ?: error("Failed to fetch HLS index")

        // 2. Parse segment URLs
        val segments = parseSegments(playlistContent, indexUrl)
        if (segments.isEmpty()) error("No segments found in playlist")

        val total = segments.size
        Log.d(TAG, "[$downloadId] ${total} segments to download")

        // 3. Download segments in parallel (with resume — skip existing)
        val semaphore = Semaphore(PARALLEL_SEGMENTS)
        val completedCount = AtomicLong(
            segments.count { File(segDir, segmentFilename(it.index)).exists() }.toLong()
        )

        segments
            .filter { !File(segDir, segmentFilename(it.index)).exists() }
            .map { seg ->
                scope.async {
                    semaphore.withPermit {
                        checkPause(downloadId)
                        downloadSegment(seg, segDir, headers, SEGMENT_RETRY_MAX)
                        val done = completedCount.incrementAndGet()
                        val approxBytes = done * (segDir.listFiles()?.firstOrNull()?.length() ?: 0L)
                        downloadDao.updateProgress(
                            id       = downloadId,
                            status   = DownloadStatus.DOWNLOADING.name,
                            bytes    = approxBytes,
                            done     = done.toInt(),
                            total    = total,
                            playlist = "",
                        )
                    }
                }
            }
            .awaitAll()

        // 4. Verify all segments exist
        val missingCount = segments.count { !File(segDir, segmentFilename(it.index)).exists() }
        if (missingCount > 0) error("$missingCount segments failed to download")

        // 5. Write local index.m3u8 pointing to local .ts files
        val localPlaylist = buildLocalPlaylist(playlistContent, segments, segDir)
        val localM3u8 = File(segDir, "index.m3u8")
        localM3u8.writeText(localPlaylist)

        // 6. Mark done — ExoPlayer will load local index.m3u8
        downloadDao.markDone(
            id     = downloadId,
            status = DownloadStatus.DONE.name,
            path   = localM3u8.absolutePath,
            at     = System.currentTimeMillis(),
        )
        Log.i(TAG, "[$downloadId] HLS done: ${localM3u8.absolutePath}")
    }

    // ── HLS Helpers ───────────────────────────────────────────────────────────

    data class Segment(val index: Int, val url: String, val durationLine: String)

    private fun parseSegments(content: String, baseUrl: String): List<Segment> {
        val base = baseUrl.substringBeforeLast('/')
        val lines = content.lines()
        val segments = mutableListOf<Segment>()
        var idx = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXTINF") -> {
                    // next non-comment line is the segment URI
                    var j = i + 1
                    while (j < lines.size && lines[j].startsWith("#")) j++
                    if (j < lines.size) {
                        val uri = lines[j].trim()
                        if (uri.isNotEmpty() && !uri.startsWith("#")) {
                            val fullUrl = if (uri.startsWith("http")) uri else "$base/$uri"
                            segments.add(Segment(idx++, fullUrl, line))
                            i = j + 1
                            continue
                        }
                    }
                }
            }
            i++
        }
        return segments
    }

    private fun segmentFilename(index: Int): String = "seg%06d.ts".format(index)

    private suspend fun downloadSegment(
        seg:      Segment,
        segDir:   File,
        headers:  Map<String, String>,
        maxRetry: Int,
    ) {
        val outFile = File(segDir, segmentFilename(seg.index))
        if (outFile.exists() && outFile.length() > 0) return  // resume: skip

        repeat(maxRetry) { attempt ->
            try {
                val request = Request.Builder()
                    .url(seg.url)
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty body")
                    outFile.outputStream().use { body.byteStream().copyTo(it, BUFFER_SIZE) }
                }
                return  // success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Segment ${seg.index} attempt $attempt failed: ${e.message}")
                if (attempt == maxRetry - 1) throw e
                delay(500L * (attempt + 1))
            }
        }
    }

    /**
     * Rebuild the m3u8 playlist replacing remote segment URLs with local file paths.
     * ExoPlayer's FileDataSource can read file:// URIs in a local m3u8.
     */
    private fun buildLocalPlaylist(
        original: String,
        segments: List<Segment>,
        segDir:   File,
    ): String {
        val sb = StringBuilder()
        val lines = original.lines()
        var segIdx = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXTINF") -> {
                    sb.appendLine(line)
                    // Skip original URI line(s) and replace with local path
                    var j = i + 1
                    while (j < lines.size && lines[j].startsWith("#")) {
                        sb.appendLine(lines[j])
                        j++
                    }
                    if (j < lines.size && !lines[j].startsWith("#") && lines[j].isNotBlank()) {
                        val localFile = File(segDir, segmentFilename(segIdx++))
                        sb.appendLine(localFile.absolutePath)
                        i = j + 1
                        continue
                    }
                }
                line.startsWith("#EXT-X-KEY") -> {
                    // Keep encryption tags as-is; encrypted HLS will still work locally
                    sb.appendLine(line)
                }
                else -> sb.appendLine(line)
            }
            i++
        }
        return sb.toString()
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url)
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .build()
                client.newCall(req).execute().use { it.body?.string() }
            } catch (e: Exception) {
                Log.e(TAG, "fetchText failed: $url — ${e.message}")
                null
            }
        }

    // ── Lifecycle helpers ─────────────────────────────────────────────────────

    private fun checkPause(downloadId: String) {
        if (pauseFlags[downloadId]?.get() == true) throw CancellationException("paused")
    }

    private suspend fun updateStatus(downloadId: String, status: DownloadStatus) {
        try {
            val row = downloadDao.get(downloadId) ?: return
            downloadDao.updateProgress(
                id       = downloadId,
                status   = status.name,
                bytes    = row.downloadedBytes,
                done     = row.segmentsDone,
                total    = row.totalSegments,
                playlist = row.localPlaylistPath,
            )
        } catch (e: Exception) {
            Log.w(TAG, "updateStatus failed: ${e.message}")
        }
    }

    /** Returns the local playback URI for ExoPlayer.
     *  MP4: file path string
     *  HLS: local index.m3u8 path
     */
    fun getLocalPlaybackPath(downloadId: String, type: String): String? {
        return when (type.lowercase()) {
            "hls" -> {
                val f = File(segmentsDir(downloadId), "index.m3u8")
                if (f.exists()) f.absolutePath else null
            }
            else -> {
                val f = File(downloadDir(downloadId), "movie.mp4")
                if (f.exists()) f.absolutePath else null
            }
        }
    }
}
