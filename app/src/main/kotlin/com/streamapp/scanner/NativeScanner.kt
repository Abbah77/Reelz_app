package com.streamapp.scanner

/**
 * Kotlin wrapper around the C++ JNI scanner layer.
 * The native layer builds candidate URLs in parallel; Kotlin fetches + validates them.
 */
object NativeScanner {
    init {
        System.loadLibrary("streamscanner")
    }

    /**
     * Returns newline-delimited candidate URLs from all placeholder sources (built in C++).
     * [type] = "movie" | "tv"
     */
    external fun scanSources(
        tmdbId: String,
        type: String,
        season: Int,
        episode: Int,
        timeoutMs: Long
    ): String

    /** Parse an m3u8 playlist and return the best-quality stream URL. */
    external fun parseM3u8(content: String): String

    /** Extract the first m3u8 URL from raw HTML/JS content. */
    external fun extractM3u8FromHtml(html: String): String

    /** Convenience: split native result into list */
    fun candidateUrls(
        tmdbId: String,
        type: String = "movie",
        season: Int = 1,
        episode: Int = 1,
        timeoutMs: Long = 8000L
    ): List<String> {
        val raw = scanSources(tmdbId, type, season, episode, timeoutMs)
        return raw.split("\n").filter { it.isNotBlank() }
    }
}
