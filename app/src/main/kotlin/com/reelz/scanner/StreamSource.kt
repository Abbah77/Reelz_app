package com.reelz.scanner

import com.reelz.data.model.MediaType

/**
 * Descriptor for a single stream provider.
 * YOU replace the placeholder URLs with your real source endpoints.
 *
 * buildUrl()   → the embed/player page URL the WebView loads
 * headers      → HTTP headers sent with every request from this source
 * referer      → Referer header (many CDNs reject requests without it)
 * origin       → Origin header for CORS-protected endpoints
 * requiresJs   → if true, WebViewScanner is used; if false, direct OkHttp fetch
 * priority     → lower = tried first in the parallel race
 */
data class StreamSource(
    val name: String,
    val buildUrl: (tmdbId: Int, mediaType: MediaType, season: Int, episode: Int) -> String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String = "",
    val origin: String = "",
    val requiresJs: Boolean = true,
    val priority: Int = 0,
)

/**
 * ── SOURCE REGISTRY ────────────────────────────────────────────────────────────
 * Add / remove sources here.  Engine picks the fastest responder automatically.
 *
 * PLACEHOLDER PATTERN:
 *   "https://YOUR_SOURCE_1.com/embed/movie/{tmdbId}"
 *   "https://YOUR_SOURCE_2.com/tv/{tmdbId}/{season}/{episode}"
 *
 * Replace the placeholder strings with your actual provider URLs.
 * The engine handles everything else (JS extraction, header injection, HLS hand-off).
 */
object SourceRegistry {

    val ALL: List<StreamSource> = listOf(

        // ── Source 1 – placeholder (replace URL) ──────────────────────────────
        StreamSource(
            name       = "Source1",
            priority   = 0,
            requiresJs = true,
            buildUrl   = { tmdbId, type, season, episode ->
                if (type == MediaType.MOVIE)
                    "https://SOURCE_1_PLACEHOLDER.com/embed/movie/$tmdbId"
                else
                    "https://SOURCE_1_PLACEHOLDER.com/embed/tv/$tmdbId/$season/$episode"
            },
            referer    = "https://SOURCE_1_PLACEHOLDER.com/",
            origin     = "https://SOURCE_1_PLACEHOLDER.com",
            headers    = mapOf(
                "User-Agent"      to StreamHeaders.UA_CHROME_ANDROID,
                "Accept"          to StreamHeaders.ACCEPT_HTML,
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        ),

        // ── Source 2 – placeholder (replace URL) ──────────────────────────────
        StreamSource(
            name       = "Source2",
            priority   = 1,
            requiresJs = true,
            buildUrl   = { tmdbId, type, season, episode ->
                if (type == MediaType.MOVIE)
                    "https://SOURCE_2_PLACEHOLDER.com/movie/$tmdbId"
                else
                    "https://SOURCE_2_PLACEHOLDER.com/tv/$tmdbId-$season-$episode"
            },
            referer    = "https://SOURCE_2_PLACEHOLDER.com/",
            origin     = "https://SOURCE_2_PLACEHOLDER.com",
            headers    = mapOf(
                "User-Agent"      to StreamHeaders.UA_CHROME_ANDROID,
                "Accept"          to StreamHeaders.ACCEPT_HTML,
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        ),

        // ── Source 3 – placeholder (direct, no JS) ────────────────────────────
        StreamSource(
            name       = "Source3",
            priority   = 2,
            requiresJs = false,
            buildUrl   = { tmdbId, type, season, episode ->
                if (type == MediaType.MOVIE)
                    "https://SOURCE_3_PLACEHOLDER.com/api/movie?id=$tmdbId"
                else
                    "https://SOURCE_3_PLACEHOLDER.com/api/tv?id=$tmdbId&s=$season&e=$episode"
            },
            referer    = "https://SOURCE_3_PLACEHOLDER.com/",
            headers    = mapOf(
                "User-Agent" to StreamHeaders.UA_CHROME_ANDROID,
                "Accept"     to "application/json",
            ),
        ),

        // ── Source 4 – placeholder ────────────────────────────────────────────
        StreamSource(
            name       = "Source4",
            priority   = 3,
            requiresJs = true,
            buildUrl   = { tmdbId, type, season, episode ->
                if (type == MediaType.MOVIE)
                    "https://SOURCE_4_PLACEHOLDER.com/embed/$tmdbId"
                else
                    "https://SOURCE_4_PLACEHOLDER.com/embed/$tmdbId/$season/$episode"
            },
            referer    = "https://SOURCE_4_PLACEHOLDER.com/",
            origin     = "https://SOURCE_4_PLACEHOLDER.com",
            headers    = mapOf(
                "User-Agent" to StreamHeaders.UA_CHROME_DESKTOP,
                "Accept"     to StreamHeaders.ACCEPT_HTML,
            ),
        ),
    )

    /** Return sources sorted by priority (lowest first). */
    fun sorted() = ALL.sortedBy { it.priority }
}

/** Shared header constants — reuse to avoid typos. */
object StreamHeaders {
    const val UA_CHROME_ANDROID = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

    const val UA_CHROME_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

    const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;" +
        "q=0.9,image/avif,image/webp,*/*;q=0.8"

    const val ACCEPT_JSON = "application/json, text/plain, */*"
}
