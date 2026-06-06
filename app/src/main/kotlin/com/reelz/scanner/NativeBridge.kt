package com.reelz.scanner

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kotlin wrapper around the C++ native layer (reelz_native.so).
 * Exposes fast, low-level URL building, M3U8 parsing, and header construction.
 */
@Singleton
class NativeBridge @Inject constructor() {

    init {
        System.loadLibrary("reelz_native")
    }

    /** Build an embed URL from base + TMDB ID. mediaType: 0=movie, 1=tv */
    external fun buildEmbedUrl(
        baseUrl:   String,
        tmdbId:    Int,
        mediaType: Int,
        season:    Int,
        episode:   Int,
    ): String

    /**
     * Parse an HLS master playlist and return the highest-quality variant URL.
     * Returns empty string if already a media playlist (pass-through to ExoPlayer).
     */
    external fun extractBestVariant(m3u8Content: String): String

    /**
     * Forge a pipe-delimited header string for the given source.
     * Format: "Referer|<val>||Origin|<val>||User-Agent|<val>"
     */
    external fun forgeHeaders(referer: String, origin: String, userAgent: String): String

    /**
     * Estimate total byte size needed to pre-buffer N episodes.
     * Based on avg ~4.5 min/episode × avgBitrateKbps.
     */
    external fun estimateBufferBytes(episodeCount: Int, avgBitrateKbps: Int): Long

    /**
     * Parse the pipe-delimited header string from [forgeHeaders] into a map.
     */
    fun parseHeaderString(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("||").mapNotNull { pair ->
            val parts = pair.split("|")
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    companion object {
        const val MEDIA_MOVIE = 0
        const val MEDIA_TV    = 1
    }
}
