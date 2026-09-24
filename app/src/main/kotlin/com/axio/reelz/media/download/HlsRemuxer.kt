package com.axio.reelz.media.download

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HlsRemuxer — wraps ffmpeg-kit to remux a local HLS playlist into MP4.
 *
 * Input:  segments/index.m3u8  (local .ts paths + remote #EXT-X-KEY URI)
 * Output: movie.mp4            (produced in the download root dir)
 *
 * FFmpeg reads the #EXT-X-KEY URI directly from the m3u8 and fetches the
 * AES-128 key over HTTPS in one pass — no manual decryption code needed.
 * The protocol whitelist must include file, http, https, tcp, tls, crypto
 * because the m3u8 mixes file:// (local .ts segments) with https:// (key URI).
 *
 * Failure classification:
 *   • Key-fetch errors (403/404/410 on key URI)  → [RemuxResult.KeyExpired]
 *   • Network/OOM/disk/process errors             → [RemuxResult.TransientFailure]
 *   • FFmpeg codec/container bugs                 → [RemuxResult.FfmpegBug]
 */
@Singleton
class HlsRemuxer @Inject constructor() {

    companion object {
        private const val TAG = "HlsRemuxer"

        /**
         * FFmpeg log patterns that indicate the AES-128 key fetch failed.
         * Matched case-insensitively against the full FFmpeg log output.
         */
        private val KEY_FETCH_ERRORS = listOf(
            "Server returned 4",   // 403, 404, 410 on key URI
            "key_uri",             // FFmpeg HLS demuxer log for key fetch failures
            "failed to open segment",
            "Invalid data found when processing input",
        )

        /**
         * Patterns that indicate a transient infrastructure failure
         * (network blip, OOM, disk full) rather than a codec/container bug.
         * These are safe to retry — segments are intact on disk.
         */
        private val TRANSIENT_ERRORS = listOf(
            "Connection refused",
            "Network is unreachable",
            "No space left on device",
            "Out of memory",
            "Cannot allocate memory",
            "Input/output error",
            "Broken pipe",
            "Connection timed out",
            "Host is unreachable",
        )
    }

    /**
     * Run FFmpeg synchronously on the calling coroutine thread.
     * Always call from a background dispatcher (Dispatchers.IO).
     *
     * @param localM3u8  The local index.m3u8 written by [ReelzDownloadEngine]
     *                   with file:// segment paths and the original https:// key URI.
     * @param outputMp4  Destination for the remuxed MP4 file.
     * @return           One of the [RemuxResult] subtypes.
     */
    fun remux(localM3u8: File, outputMp4: File): RemuxResult {
        // Clean up any stale partial output from a previous attempt.
        outputMp4.delete()

        val cmd = buildCommand(localM3u8, outputMp4)
        Log.d(TAG, "FFmpeg start: $cmd")

        val session = FFmpegKit.execute(cmd)
        val rc      = session.returnCode
        val logs    = session.allLogsAsString ?: ""

        return when {
            ReturnCode.isSuccess(rc) && outputMp4.exists() && outputMp4.length() > 0 -> {
                Log.i(TAG, "FFmpeg success → ${outputMp4.absolutePath} (${outputMp4.length()} bytes)")
                RemuxResult.Success(outputMp4)
            }

            isKeyExpired(logs) -> {
                val reason = extractKeyError(logs)
                Log.w(TAG, "FFmpeg key expired: $reason")
                outputMp4.delete()
                RemuxResult.KeyExpired(reason)
            }

            isTransient(logs, rc) -> {
                val reason = extractTransientReason(logs, rc)
                Log.w(TAG, "FFmpeg transient failure: $reason")
                outputMp4.delete()
                RemuxResult.TransientFailure(reason)
            }

            else -> {
                // FFmpeg bug — corrupt segment, unsupported codec, bad container, etc.
                val reason = extractFfmpegBugReason(logs, rc)
                Log.w(TAG, "FFmpeg bug (ExoPlayer fallback): $reason")
                outputMp4.delete()
                RemuxResult.FfmpegBug(reason, localM3u8)
            }
        }
    }

    // ── Command builder ───────────────────────────────────────────────────────

    private fun buildCommand(localM3u8: File, outputMp4: File): String =
        // -protocol_whitelist: required because m3u8 mixes file:// and https://
        // -allowed_extensions: allow .ts segment extensions via the HLS demuxer
        // -c copy: pure remux — no re-encode, very fast, no quality loss
        // -movflags +faststart: moves moov atom to front for progressive playback
        // -loglevel warning: suppress verbose segment-by-segment noise
        "-protocol_whitelist file,http,https,tcp,tls,crypto " +
        "-allowed_extensions ALL " +
        "-i \"${localM3u8.absolutePath}\" " +
        "-c copy " +
        "-movflags +faststart " +
        "-loglevel warning " +
        "\"${outputMp4.absolutePath}\""

    // ── Log classifiers ───────────────────────────────────────────────────────

    private fun isKeyExpired(logs: String): Boolean =
        KEY_FETCH_ERRORS.any { logs.contains(it, ignoreCase = true) }

    private fun isTransient(logs: String, rc: ReturnCode?): Boolean {
        // FFmpeg killed by OS signal (SIGKILL from OOM killer, etc.)
        if (ReturnCode.isCancel(rc)) return true
        return TRANSIENT_ERRORS.any { logs.contains(it, ignoreCase = true) }
    }

    private fun extractKeyError(logs: String): String =
        logs.lines()
            .firstOrNull { line -> KEY_FETCH_ERRORS.any { line.contains(it, ignoreCase = true) } }
            ?.trim()
            ?.take(200)
            ?: "AES key fetch failed"

    private fun extractTransientReason(logs: String, rc: ReturnCode?): String {
        if (ReturnCode.isCancel(rc)) return "FFmpeg cancelled by OS (signal ${rc?.value})"
        return logs.lines()
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(200)
            ?: "Transient failure (exit ${rc?.value})"
    }

    private fun extractFfmpegBugReason(logs: String, rc: ReturnCode?): String =
        logs.lines()
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(200)
            ?: "FFmpeg failed (exit ${rc?.value})"
}
