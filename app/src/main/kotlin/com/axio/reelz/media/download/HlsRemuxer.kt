package com.axio.reelz.media.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * HlsRemuxer — uses Media3 Transformer to remux a local HLS playlist into MP4.
 *
 * Replaces the old FFmpeg-kit implementation with the already-bundled
 * androidx.media3:media3-transformer dependency (zero extra APK size).
 *
 * Input:  segments/index.m3u8  (local .ts paths written by ReelzDownloadEngine)
 * Output: movie.mp4            (produced in the download root dir)
 *
 * Transformer runs a copy-only (no re-encode) remux pass — same quality,
 * same speed as FFmpeg's `-c copy` mode.
 *
 * This is a suspend fun because Transformer is callback-based on the main
 * thread; we bridge it to a coroutine with suspendCancellableCoroutine.
 *
 * Failure classification mirrors the old FFmpeg version:
 *   • Key / DRM errors  → [RemuxResult.KeyExpired]
 *   • Transient errors  → [RemuxResult.TransientFailure]
 *   • Other codec/container bugs → [RemuxResult.FfmpegBug] (name kept for DB compat)
 */
@Singleton
class HlsRemuxer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "HlsRemuxer"

        // Error message fragments that indicate an AES-128 key fetch failure.
        private val KEY_FETCH_ERRORS = listOf(
            "cleartext",
            "drm",
            "key",
            "403",
            "404",
            "410",
            "unauthorized",
        )

        // Error message fragments that indicate a transient infra problem (safe to retry).
        private val TRANSIENT_ERRORS = listOf(
            "timeout",
            "connection",
            "network",
            "unreachable",
            "no space",
            "out of memory",
            "i/o error",
            "broken pipe",
        )
    }

    /**
     * Remux [localM3u8] → [outputMp4] using Media3 Transformer.
     *
     * Must be called from a coroutine (suspends until Transformer finishes).
     * Transformer internally dispatches to its own threads; the calling
     * coroutine is suspended without blocking a thread pool thread.
     *
     * @param localM3u8  The local index.m3u8 written by [ReelzDownloadEngine]
     *                   with file:// absolute .ts segment paths.
     * @param outputMp4  Destination MP4 file (deleted and re-created on each call).
     */
    suspend fun remux(localM3u8: File, outputMp4: File): RemuxResult {
        // Clean up any stale partial output.
        outputMp4.delete()

        Log.d(TAG, "Transformer start: ${localM3u8.absolutePath} → ${outputMp4.absolutePath}")

        return suspendCancellableCoroutine { cont ->
            // Transformer must be created and started on the main thread.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val transformer = Transformer.Builder(context)
                    .setTransformationRequest(
                        TransformationRequest.Builder()
                            .build()                // copy-only, no re-encode
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult,
                        ) {
                            if (outputMp4.exists() && outputMp4.length() > 0) {
                                Log.i(TAG, "Transformer success → ${outputMp4.absolutePath} " +
                                        "(${outputMp4.length()} bytes)")
                                cont.resume(RemuxResult.Success(outputMp4))
                            } else {
                                Log.w(TAG, "Transformer completed but output missing/empty")
                                cont.resume(
                                    RemuxResult.TransientFailure("Output file missing after transform")
                                )
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            outputMp4.delete()
                            val msg = exportException.message?.lowercase() ?: ""
                            val result = when {
                                isKeyError(msg)      -> {
                                    Log.w(TAG, "Transformer key error: ${exportException.message}")
                                    RemuxResult.KeyExpired(exportException.message ?: "AES key fetch failed")
                                }
                                isTransientError(msg) -> {
                                    Log.w(TAG, "Transformer transient: ${exportException.message}")
                                    RemuxResult.TransientFailure(exportException.message ?: "Transient failure")
                                }
                                else -> {
                                    // Codec/container issue — ExoPlayer HLS fallback may still work.
                                    Log.w(TAG, "Transformer bug (ExoPlayer fallback): ${exportException.message}")
                                    RemuxResult.FfmpegBug(
                                        exportException.message ?: "Transformer failed",
                                        localM3u8,
                                    )
                                }
                            }
                            cont.resume(result)
                        }
                    })
                    .build()

                val mediaItem = MediaItem.fromUri(Uri.fromFile(localM3u8))
                transformer.start(mediaItem, outputMp4.absolutePath)

                // Cancel Transformer if the coroutine is cancelled (e.g. download paused).
                cont.invokeOnCancellation {
                    transformer.cancel()
                    outputMp4.delete()
                    Log.d(TAG, "Transformer cancelled")
                }
            }
        }
    }

    // ── Error classifiers ─────────────────────────────────────────────────────

    private fun isKeyError(msg: String): Boolean =
        KEY_FETCH_ERRORS.any { msg.contains(it) }

    private fun isTransientError(msg: String): Boolean =
        TRANSIENT_ERRORS.any { msg.contains(it) }
}
