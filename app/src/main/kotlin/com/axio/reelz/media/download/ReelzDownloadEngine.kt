package com.axio.reelz.media.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.axio.reelz.core.database.DownloadDao
import com.axio.reelz.core.database.FileDao
import com.axio.reelz.core.database.FileRow
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
 * ReelzDownloadEngine — v2: two-table architecture.
 *
 * MP4 flow:
 *   1. Download to <downloadId>/movie.mp4.tmp
 *   2. Atomic rename → movie.mp4
 *   3. Insert FileRow into "files" table
 *   4. Delete DownloadRow from "downloads" table
 *
 * HLS flow:
 *   1. Download all .ts segments to <downloadId>/segments/
 *   2. Remux segments → clean .mp4 using FFmpeg concat via MediaMuxer
 *   3. Insert FileRow into "files" table
 *   4. Delete .ts segments + index.m3u8 (cleanup)
 *   5. Delete DownloadRow from "downloads" table
 *
 * The "downloads" table never contains DONE rows.
 * The "files" table only contains valid, playable .mp4 files.
 */
@Singleton
class ReelzDownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val fileDao: FileDao,
) {
    companion object {
        private const val TAG = "ReelzDownloadEngine"
        private const val PARALLEL_SEGMENTS = 8
        private const val SEGMENT_RETRY_MAX = 6
        private const val BUFFER_SIZE = 512 * 1024
        private const val PROGRESS_FLUSH_BYTES = 1 * 1024 * 1024L
        private const val DOWNLOADS_DIR = "reelz_downloads"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs  = ConcurrentHashMap<String, Job>()
    private val pauseFlags  = ConcurrentHashMap<String, AtomicBoolean>()
    private val pausedIds   = ConcurrentHashMap.newKeySet<String>()

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
                val type = if (row.streamUrl.contains(".m3u8", ignoreCase = true)) "hls" else "mp4"
                @Suppress("UNCHECKED_CAST")
                val headers = runCatching {
                    com.google.gson.Gson().fromJson(row.headersJson, Map::class.java) as Map<String, String>
                }.getOrDefault(emptyMap())
                start(id, row.streamUrl, type, headers, row.title, autoResume = true)
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

    private fun segmentsDir(downloadId: String): File =
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
        autoResume: Boolean = false,
    ) {
        if (activeJobs[downloadId]?.isActive == true) return

        pauseFlags[downloadId] = AtomicBoolean(false)
        pausedIds.remove(downloadId)

        val job = engineScope.launch {
            try {
                updateStatus(downloadId, "DOWNLOADING")
                when (type.lowercase()) {
                    "hls" -> downloadHls(downloadId, url, headers)
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
                    updateStatus(downloadId, "PAUSED")
                    Log.d(TAG, "[$downloadId] network error → PAUSED for auto-resume")
                } else {
                    updateStatus(downloadId, "ERROR")
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
        engineScope.launch { updateStatus(downloadId, "PAUSED") }
    }

    fun cancel(downloadId: String) {
        pauseFlags[downloadId]?.set(true)
        pausedIds.remove(downloadId)
        activeJobs[downloadId]?.cancel()
        engineScope.launch { downloadDir(downloadId).deleteRecursively() }
    }

    // ── MP4 ───────────────────────────────────────────────────────────────────

    private suspend fun downloadMp4(
        downloadId: String,
        url: String,
        headers: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val row = downloadDao.get(downloadId)
            ?: error("Download row not found for $downloadId")

        val outFile = File(downloadDir(downloadId), "movie.mp4")
        val tmpFile = File(downloadDir(downloadId), "movie.mp4.tmp")

        // Resume: file already fully downloaded
        if (outFile.exists() && outFile.length() > 1024) {
            commitToFilesTable(downloadId, row, outFile)
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
                id = downloadId, status = "DOWNLOADING",
                bytes = resumeFrom, done = 0, total = 0, playlist = "",
                sizeBytes = totalSize,
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
                            id = downloadId, status = "DOWNLOADING",
                            bytes = done, done = 0, total = 0, playlist = "",
                            sizeBytes = totalSize,
                        )
                    }
                }
                fos.flush()
            }
        } finally {
            body.close()
            response.close()
        }

        // Atomic rename — renameTo() silently returns false on some OEM builds
        // (Xiaomi/MIUI, Samsung One UI 6) when tmp and out are on different mount
        // points. Log the fallback so Crashlytics catches the pattern in the wild.
        if (!tmpFile.renameTo(outFile)) {
            Log.w(TAG, "[$downloadId] renameTo() failed (cross-mount?), falling back to copyTo+delete")
            tmpFile.copyTo(outFile, overwrite = true)
            tmpFile.delete()
        }

        Log.i(TAG, "[$downloadId] MP4 done: ${outFile.absolutePath} (${outFile.length()} bytes)")
        commitToFilesTable(downloadId, row, outFile)
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

    // ── HLS → remux to .mp4 ──────────────────────────────────────────────────
    //
    // 1. Fetch the quality-specific media playlist
    // 2. Download all .ts segments in parallel
    // 3. Concatenate all segments into a single clean .mp4 using MediaMuxer
    // 4. Commit to files table, delete segments

    private suspend fun downloadHls(
        downloadId: String,
        mediaPlaylistUrl: String,
        headers: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val row = downloadDao.get(downloadId)
            ?: error("Download row not found for $downloadId")

        val segDir  = segmentsDir(downloadId)
        val outFile = File(downloadDir(downloadId), "movie.mp4")

        // Already remuxed — just commit (resume after crash)
        if (outFile.exists() && outFile.length() > 1024) {
            commitToFilesTable(downloadId, row, outFile)
            return@withContext
        }

        Log.d(TAG, "[$downloadId] Fetching HLS playlist: $mediaPlaylistUrl")
        val playlistContent = fetchTextWithRetry(mediaPlaylistUrl, headers)
            ?: error("Failed to fetch HLS media playlist after retries")

        val segments = parseSegments(playlistContent, mediaPlaylistUrl)
        if (segments.isEmpty()) error("HLS playlist has no segments — check URL")

        val total = segments.size
        Log.d(TAG, "[$downloadId] $total segments to download")

        // R5 fix: track cumulative bytes from actual segment sizes as they complete,
        // instead of multiplying done * estimateSegmentSize() which can exceed the real
        // total early in a download (wildly-off estimate → progress bar jumps past 100%).
        val completedCount = AtomicLong(
            segments.count { seg ->
                File(segDir, segFilename(seg.index)).let { it.exists() && it.length() > 0 }
            }.toLong()
        )
        val completedBytes = AtomicLong(
            segments.sumOf { seg ->
                File(segDir, segFilename(seg.index)).let { if (it.exists()) it.length() else 0L }
            }
        )

        downloadDao.updateProgress(
            id = downloadId, status = "DOWNLOADING",
            bytes = completedBytes.get(),
            done  = completedCount.get().toInt(),
            total = total,
            playlist = "",
            sizeBytes = 0L,
        )

        val semaphore = Semaphore(PARALLEL_SEGMENTS)
        val pendingSegments = segments.filter { seg ->
            !File(segDir, segFilename(seg.index)).let { it.exists() && it.length() > 0 }
        }

        coroutineScope {
            val jobs = pendingSegments.map { seg ->
                async {
                    semaphore.withPermit {
                        checkPause(downloadId)
                        downloadSegmentWithRetry(seg, segDir, headers)
                        val segFile = File(segDir, segFilename(seg.index))
                        val done  = completedCount.incrementAndGet()
                        val bytes = completedBytes.addAndGet(segFile.length())
                        downloadDao.updateProgress(
                            id = downloadId, status = "DOWNLOADING",
                            bytes = bytes, done = done.toInt(),
                            total = total, playlist = "",
                            sizeBytes = 0L,
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

        // ── Remux: concatenate .ts segments → clean .mp4 ─────────────────────
        Log.d(TAG, "[$downloadId] Remuxing $total segments → mp4")
        updateStatus(downloadId, "REMUXING")

        val tsFiles = segments.map { seg -> File(segDir, segFilename(seg.index)) }
        remuxTsToMp4(tsFiles, outFile)

        Log.i(TAG, "[$downloadId] Remux done: ${outFile.absolutePath} (${outFile.length()} bytes)")

        // Cleanup raw segments now that we have the clean mp4
        segDir.deleteRecursively()

        commitToFilesTable(downloadId, row, outFile)
    }

    // ── Remux: .ts segments → .mp4 using MediaMuxer ──────────────────────────
    //
    // Rules:
    //  1. One MediaExtractor per segment — track indices are per-segment local.
    //     We remap by MIME type each time (video/* → videoMuxerTrack, audio/* → audioMuxerTrack).
    //  2. Tracks are added to the muxer from segment 0 only, then muxer.start().
    //  3. NO double-selectTrack. Select once, read all samples, release extractor.
    //  4. Timestamp continuity: HLS .ts segments already carry continuous 90kHz PCR
    //     timestamps. We keep them as-is for segment 0, then for each subsequent
    //     segment we subtract the segment's first PTS and add (lastPts + frameDuration)
    //     so the timeline is gapless. This avoids negative PTS which breaks ExoPlayer.
    //  5. MediaMuxer requires writeSampleData on the main/worker thread but NOT
    //     under coroutine IO — called from a plain blocking function, so fine.

    private fun remuxTsToMp4(tsFiles: List<File>, outFile: File) {
        // R3 fix: MediaMuxer on API 26 (Android 8.0) throws IOException if the output
        // path is on external storage (getExternalFilesDir()). For API < 28 we write
        // the mux to internal storage first, then move the result to the final location.
        val tmpOut = if (android.os.Build.VERSION.SDK_INT < 28) {
            File(context.filesDir, "mux_${outFile.nameWithoutExtension}.tmp").also { it.delete() }
        } else {
            File(outFile.parent, "movie.mp4.tmp")
        }
        tmpOut.delete()

        val muxer = android.media.MediaMuxer(
            tmpOut.absolutePath,
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )

        var videoMuxerTrack = -1
        var audioMuxerTrack = -1
        var muxerStarted    = false

        // Running last-seen PTS per track for timestamp stitching
        var lastVideoPts = -1L
        var lastAudioPts = -1L

        // Estimated frame duration (filled once we see ≥2 video frames in seg 0)
        var videoPtsDelta = 33_333L   // default ~30fps in µs
        var audioPtsDelta = 21_333L   // default ~AAC 1024 samples @ 48kHz in µs

        val bufferInfo = android.media.MediaCodec.BufferInfo()
        // R6 fix: 2MB is insufficient for high-bitrate 4K keyframes. We'll resize readBuf
        // per segment after probing KEY_MAX_INPUT_SIZE. Start with a 2MB default.
        var readBuf    = java.nio.ByteBuffer.allocate(2 * 1024 * 1024)

        for ((segIdx, tsFile) in tsFiles.withIndex()) {
            if (!tsFile.exists() || tsFile.length() == 0L) {
                Log.w(TAG, "Segment $segIdx missing or empty — skipping")
                continue
            }

            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(tsFile.absolutePath)

                // R6 fix: query KEY_MAX_INPUT_SIZE across all tracks and grow readBuf if needed
                var maxInputSize = readBuf.capacity()
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    if (fmt.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val trackMax = fmt.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (trackMax > maxInputSize) maxInputSize = trackMax
                    }
                }
                if (maxInputSize > readBuf.capacity()) {
                    Log.d(TAG, "Seg $segIdx: growing readBuf ${readBuf.capacity()} → $maxInputSize bytes")
                    readBuf = java.nio.ByteBuffer.allocate(maxInputSize)
                }

                // ── Build per-segment track map: mimePrefix → (extIdx, muxerTrack) ──
                data class TrackEntry(val extIdx: Int, val muxerTrack: Int)
                var videoEntry: TrackEntry? = null
                var audioEntry: TrackEntry? = null

                for (i in 0 until extractor.trackCount) {
                    val fmt  = extractor.getTrackFormat(i)
                    val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    when {
                        mime.startsWith("video/") && videoEntry == null -> {
                            if (!muxerStarted) {
                                videoMuxerTrack = muxer.addTrack(fmt)
                            }
                            if (videoMuxerTrack >= 0) videoEntry = TrackEntry(i, videoMuxerTrack)
                        }
                        mime.startsWith("audio/") && audioEntry == null -> {
                            if (!muxerStarted) {
                                audioMuxerTrack = muxer.addTrack(fmt)
                            }
                            if (audioMuxerTrack >= 0) audioEntry = TrackEntry(i, audioMuxerTrack)
                        }
                    }
                }

                // Start muxer after adding all tracks from seg 0.
                // R7 fix: some HLS streams have a silent first segment (pre-roll or bumper
                // with video only). If segment 0 has no audio we scan up to 3 more segments
                // before calling muxer.start() so audio is not silently dropped.
                if (!muxerStarted) {
                    if (videoMuxerTrack < 0 && audioMuxerTrack < 0) {
                        Log.w(TAG, "Segment 0: no A/V tracks — aborting remux")
                        muxer.release(); tmpOut.delete(); return
                    }
                    if (audioMuxerTrack < 0 && segIdx == 0) {
                        // Audio not found in seg 0 — scan the next 3 segments to look for it
                        for (lookAhead in 1..3) {
                            val lookFile = tsFiles.getOrNull(lookAhead)
                                ?: break
                            if (!lookFile.exists() || lookFile.length() == 0L) continue
                            val lookExtractor = android.media.MediaExtractor()
                            try {
                                lookExtractor.setDataSource(lookFile.absolutePath)
                                for (i in 0 until lookExtractor.trackCount) {
                                    val fmt  = lookExtractor.getTrackFormat(i)
                                    val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                                    if (mime.startsWith("audio/") && audioMuxerTrack < 0) {
                                        audioMuxerTrack = muxer.addTrack(fmt)
                                        Log.d(TAG, "Audio track found in look-ahead seg$lookAhead, muxerTrack=$audioMuxerTrack")
                                        break
                                    }
                                }
                            } finally {
                                lookExtractor.release()
                            }
                            if (audioMuxerTrack >= 0) break
                        }
                    }
                    muxer.start()
                    muxerStarted = true
                }

                // Select only the tracks we care about
                videoEntry?.let { extractor.selectTrack(it.extIdx) }
                audioEntry?.let { extractor.selectTrack(it.extIdx) }

                if (videoEntry == null && audioEntry == null) {
                    Log.w(TAG, "Segment $segIdx: no matching tracks — skipping")
                    continue
                }

                // ── Compute segment base PTS (first PTS seen in this segment) ──
                // HLS segments carry absolute PCR timestamps. To make the output
                // timeline start at 0 (for seg 0) and be gapless (for seg N>0)
                // we shift all PTS by: offset = targetStart - segBasePts
                // where targetStart = lastKnownPts + delta

                var segVideoBase = Long.MAX_VALUE
                var segAudioBase = Long.MAX_VALUE

                // Peek first PTS without consuming samples (extractor is at start)
                // R2 fix: On API 26-27 some MediaExtractor implementations for H.264 TS
                // don't honour seekTo(0) correctly — the extractor may stay at the last
                // read position. We seek once BEFORE track selection, then verify that
                // sampleTime is actually 0 (or close to it). If not, we use 0 as the
                // safe default so the PTS offset calculation is never corrupted.
                if (videoEntry != null || audioEntry != null) {
                    extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                    // Verify seek actually landed at the start; if not, treat segBase = 0.
                    val seekVerified = extractor.sampleTime.let { it < 0 || it < 1_000_000L }

                    if (seekVerified) {
                        // Read up to 16 samples just to find first PTS per track
                        var peeked = 0
                        while (peeked < 16 && (segVideoBase == Long.MAX_VALUE || segAudioBase == Long.MAX_VALUE)) {
                            val tidx = extractor.sampleTrackIndex
                            val pts  = extractor.sampleTime
                            if (pts >= 0) {
                                when {
                                    videoEntry != null && tidx == videoEntry.extIdx && segVideoBase == Long.MAX_VALUE ->
                                        segVideoBase = pts
                                    audioEntry != null && tidx == audioEntry.extIdx && segAudioBase == Long.MAX_VALUE ->
                                        segAudioBase = pts
                                }
                            }
                            if (!extractor.advance()) break
                            peeked++
                        }
                    } else {
                        // seekTo(0) did not land at start (pre-API-28 OEM bug) — use 0 as
                        // the safe default for segBase so PTS offsets are not corrupted.
                        Log.w(TAG, "Seg $segIdx: seekTo(0) returned sampleTime=${extractor.sampleTime} (OEM bug); defaulting segBase=0")
                        segVideoBase = 0L
                        segAudioBase = 0L
                    }

                    // Rewind to start for the actual read loop
                    extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }

                val segBase = when {
                    segVideoBase != Long.MAX_VALUE -> segVideoBase
                    segAudioBase != Long.MAX_VALUE -> segAudioBase
                    else                           -> 0L
                }

                // For seg 0: target = 0 (start timeline at 0)
                // For seg N: target = lastPts + estimated frame duration
                val videoTarget = when {
                    segIdx == 0 -> 0L
                    lastVideoPts >= 0 -> lastVideoPts + videoPtsDelta
                    lastAudioPts >= 0 -> lastAudioPts + audioPtsDelta
                    else -> 0L
                }
                val audioTarget = when {
                    segIdx == 0 -> 0L
                    lastAudioPts >= 0 -> lastAudioPts + audioPtsDelta
                    lastVideoPts >= 0 -> lastVideoPts + videoPtsDelta
                    else -> 0L
                }

                val videoSegBase = if (segVideoBase != Long.MAX_VALUE) segVideoBase else segBase
                val audioSegBase = if (segAudioBase != Long.MAX_VALUE) segAudioBase else segBase

                val videoOffset = videoTarget - videoSegBase
                val audioOffset = audioTarget - audioSegBase

                // Track delta calibration for seg 0
                var prevVideoPts = -1L
                var prevAudioPts = -1L

                // ── Read and mux all samples ──────────────────────────────────
                while (true) {
                    readBuf.clear()
                    val sampleSize = extractor.readSampleData(readBuf, 0)
                    if (sampleSize < 0) break

                    val extTrackIdx = extractor.sampleTrackIndex
                    val rawPts      = extractor.sampleTime
                    val flags       = extractor.sampleFlags

                    val (muxTrack, adjustedPts) = when {
                        videoEntry != null && extTrackIdx == videoEntry.extIdx -> {
                            val pts = (rawPts + videoOffset).coerceAtLeast(
                                if (lastVideoPts >= 0) lastVideoPts + 1 else 0L
                            )
                            // Calibrate delta from first two frames of seg 0
                            if (segIdx == 0 && prevVideoPts >= 0 && pts > prevVideoPts) {
                                val d = pts - prevVideoPts
                                if (d in 8_000..100_000) videoPtsDelta = d
                            }
                            prevVideoPts = pts
                            lastVideoPts = pts
                            Pair(videoEntry.muxerTrack, pts)
                        }
                        audioEntry != null && extTrackIdx == audioEntry.extIdx -> {
                            val pts = (rawPts + audioOffset).coerceAtLeast(
                                if (lastAudioPts >= 0) lastAudioPts + 1 else 0L
                            )
                            if (segIdx == 0 && prevAudioPts >= 0 && pts > prevAudioPts) {
                                val d = pts - prevAudioPts
                                if (d in 5_000..50_000) audioPtsDelta = d
                            }
                            prevAudioPts = pts
                            lastAudioPts = pts
                            Pair(audioEntry.muxerTrack, pts)
                        }
                        else -> {
                            extractor.advance(); continue
                        }
                    }

                    bufferInfo.offset             = 0
                    bufferInfo.size               = sampleSize
                    bufferInfo.flags              = flags
                    bufferInfo.presentationTimeUs = adjustedPts

                    try {
                        muxer.writeSampleData(muxTrack, readBuf, bufferInfo)
                    } catch (e: Exception) {
                        Log.w(TAG, "writeSampleData seg$segIdx: ${e.message}")
                    }

                    extractor.advance()
                }

            } finally {
                extractor.release()
            }
        }

        if (!muxerStarted) {
            muxer.release(); tmpOut.delete()
            throw java.io.IOException("No valid segments could be remuxed")
        }

        muxer.stop()
        muxer.release()

        // Move tmpOut → outFile. On API < 28 tmpOut is on internal storage while outFile
        // may be on external, so renameTo() will always return false across mount points —
        // fall back to copy+delete in that case (same pattern used for MP4 downloads).
        if (!tmpOut.renameTo(outFile)) {
            Log.w(TAG, "remux renameTo() failed (API<28 cross-mount expected), copying instead")
            tmpOut.copyTo(outFile, overwrite = true)
            tmpOut.delete()
        }

        if (!outFile.exists() || outFile.length() < 1024) {
            throw java.io.IOException("Remux produced empty/missing output: ${outFile.absolutePath}")
        }

        Log.i(TAG, "Remux complete: ${outFile.name} = ${outFile.length() / 1_048_576}MB")
    }

    // ── Commit to files table + delete from downloads ─────────────────────────

    private suspend fun commitToFilesTable(
        downloadId: String,
        row: com.axio.reelz.core.database.DownloadRow,
        mp4File: File,
    ) = withContext(Dispatchers.IO) {
        val fileRow = FileRow(
            id          = downloadId,
            mediaId     = row.mediaId,
            title       = row.title,
            posterUrl   = row.posterUrl,
            mediaType   = row.mediaType,
            season      = row.season,
            episode     = row.episode,
            episodeName = row.episodeName,
            quality     = row.quality,
            filePath    = mp4File.absolutePath,
            sizeBytes   = mp4File.length(),
            durationMs  = 0L,
            addedAt     = System.currentTimeMillis(),
        )
        // Use IGNORE: if same (mediaId,season,episode,quality) already exists, keep it
        val inserted = fileDao.insertIfNew(fileRow)
        if (inserted == -1L) {
            // Exact duplicate — update path in case file moved
            fileDao.upsert(fileRow)
        }
        // Remove from downloads — this row is no longer needed
        downloadDao.delete(downloadId)
        Log.i(TAG, "[$downloadId] committed to files table, removed from downloads")
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
                        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
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
            null
        }

    // ── Lifecycle helpers ─────────────────────────────────────────────────────

    private fun checkPause(downloadId: String) {
        if (pauseFlags[downloadId]?.get() == true) throw CancellationException("paused")
    }

    private suspend fun updateStatus(downloadId: String, status: String) {
        try {
            val row = downloadDao.get(downloadId) ?: return
            downloadDao.updateProgress(
                id        = downloadId,
                status    = status,
                bytes     = row.downloadedBytes,
                done      = row.segmentsDone,
                total     = row.totalSegments,
                playlist  = row.localPlaylistPath,
                sizeBytes = row.sizeBytes,
            )
        } catch (e: Exception) {
            Log.w(TAG, "updateStatus: ${e.message}")
        }
    }

    /** Returns local path for offline playback — always .mp4 from files table. */
    fun getLocalPlaybackPath(downloadId: String): String? =
        File(downloadDir(downloadId), "movie.mp4").takeIf { it.exists() }?.absolutePath
}
