package com.reelz.scanner

import com.reelz.data.model.QualityTrack
import com.reelz.data.model.Subtitle

/**
 * Kotlin wrapper around the C++ JNI bridge (reelz_native.so).
 * All heavy parsing is done natively for maximum speed.
 */
object NativeBridge {

    init {
        System.loadLibrary("reelz_native")
    }

    // ── Raw JNI declarations ──────────────────────────────────────────────────
    private external fun parseBestVariantUrl(content: String, baseUrl: String): String
    private external fun parseSegmentUrls(content: String, baseUrl: String): String
    private external fun parseVariants(content: String, baseUrl: String): String
    private external fun parseSubtitles(content: String, baseUrl: String): String
    external fun forgeHeaders(referer: String, origin: String, mobile: Boolean, isXhr: Boolean): String
    external fun getUserAgent(mobile: Boolean): String

    // ── Public Kotlin-friendly API ────────────────────────────────────────────

    /** Returns the best (highest bandwidth) variant URL from a master playlist. */
    fun bestVariant(m3u8Content: String, baseUrl: String): String =
        parseBestVariantUrl(m3u8Content, baseUrl)

    /** Returns all segment URLs (for media playlists). */
    fun segments(m3u8Content: String, baseUrl: String): List<String> =
        parseSegmentUrls(m3u8Content, baseUrl)
            .trim().lines().filter { it.isNotBlank() }

    /** Returns all quality variants, sorted highest first. */
    fun variants(m3u8Content: String, baseUrl: String): List<QualityTrack> =
        parseVariants(m3u8Content, baseUrl)
            .trim().lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 4) return@mapNotNull null
                val url       = parts[0]
                val bandwidth = parts[1].toLongOrNull() ?: 0L
                val res       = parts[2]
                val height    = parts[3].toIntOrNull() ?: 0
                val label     = if (height > 0) "${height}p" else if (res.isNotBlank()) res else "Auto"
                QualityTrack(label, url, bandwidth)
            }

    /** Returns subtitle tracks from a master playlist. */
    fun subtitles(m3u8Content: String, baseUrl: String): List<Subtitle> =
        parseSubtitles(m3u8Content, baseUrl)
            .trim().lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 3) return@mapNotNull null
                Subtitle(url = parts[0], language = parts[1], label = parts[2])
            }

    /** Parse raw "Key: Value\r\n" header string into a map. */
    fun parseForgedHeaders(referer: String, origin: String, mobile: Boolean = true): Map<String, String> {
        val raw = forgeHeaders(referer, origin, mobile, false)
        return raw.lines()
            .filter { it.contains(": ") }
            .associate { line ->
                val idx = line.indexOf(": ")
                line.substring(0, idx) to line.substring(idx + 2).trimEnd()
            }
    }
}
