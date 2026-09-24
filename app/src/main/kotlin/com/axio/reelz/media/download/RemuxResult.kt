package com.axio.reelz.media.download

import java.io.File

/**
 * Sealed result type returned by [HlsRemuxer.remux].
 *
 * Every HLS download ends in exactly one of these outcomes:
 *
 * | Outcome        | DB status  | remuxAttempted | filePath     |
 * |----------------|------------|----------------|--------------|
 * | Success        | DONE       | 1              | movie.mp4    |
 * | TransientFail  | REMUXING   | -1             | ""           |
 * | KeyExpired     | ERROR      | -2             | ""           |
 * | FfmpegBug      | DONE       | -3             | index.m3u8   |
 */
sealed class RemuxResult {

    /** FFmpeg produced movie.mp4 — happy path. */
    data class Success(val mp4File: File) : RemuxResult()

    /**
     * Network/OOM/disk error during remux — segments are intact, so the
     * engine leaves status=REMUXING and retries on the next app launch.
     */
    data class TransientFailure(val reason: String) : RemuxResult()

    /**
     * CDN returned 403/404 when FFmpeg tried to fetch the AES-128 key.
     * The local index.m3u8 still references the dead key URI — ExoPlayer
     * would hit the same error. Nothing on disk is playable. Mark ERROR.
     */
    data class KeyExpired(val reason: String) : RemuxResult()

    /**
     * FFmpeg exited non-zero for a reason unrelated to the key (corrupt
     * segment, unsupported codec, etc.). ExoPlayer's native HLS decoder
     * may still handle it — fall back to local index.m3u8, mark DONE.
     */
    data class FfmpegBug(val reason: String, val fallbackM3u8: File) : RemuxResult()
}
