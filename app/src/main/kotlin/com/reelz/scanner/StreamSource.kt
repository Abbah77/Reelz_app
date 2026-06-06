package com.reelz.scanner

import com.reelz.data.model.MediaType

/**
 * StreamSource descriptor.
 * buildUrl() → the embed page the WebView loads
 * requiresJs → true = WebViewScanner, false = DirectScanner (OkHttp)
 * priority   → lower number = tried first in the parallel race
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

object SourceRegistry {

    val ALL: List<StreamSource> = listOf(

        // ── vidsrc.to ─────────────────────────────────────────────────────────
        // Pattern: /embed/movie/{tmdbId}  |  /embed/tv/{tmdbId}/{season}/{episode}
        StreamSource(
            name       = "VidSrc",
            priority   = 0,
            requiresJs = true,
            buildUrl   = { tmdbId, type, season, episode ->
                if (type == MediaType.MOVIE)
                    "https://vidsrc.to/embed/movie/$tmdbId"
                else
                    "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode"
            },
            referer    = "https://vidsrc.to/",
            origin     = "https://vidsrc.to",
            headers    = mapOf(
                "User-Agent"      to StreamHeaders.UA_CHROME_ANDROID,
                "Accept"          to StreamHeaders.ACCEPT_HTML,
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        ),

        // ── vidlink.pro ───────────────────────────────────────────────────────
        // Pattern: /movie/{tmdbId}  |  /tv/{tmdbId}/{season}/{episode}
        // NOTE: vidlink uses TMDB IDs directly (not IMDB tt-ids for most titles)
        StreamSource(
            name       = "VidLink",
            priority   = 1,
            requiresJs = true,
            buildUrl   = { tmdbId, type, season, episode ->
                if (type == MediaType.MOVIE)
                    "https://vidlink.pro/movie/$tmdbId"
                else
                    "https://vidlink.pro/tv/$tmdbId/$season/$episode"
            },
            referer    = "https://vidlink.pro/",
            origin     = "https://vidlink.pro",
            headers    = mapOf(
                "User-Agent"      to StreamHeaders.UA_CHROME_ANDROID,
                "Accept"          to StreamHeaders.ACCEPT_HTML,
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        ),

        // ── Source 3 placeholder — add your next provider here ────────────────
        // Common patterns to try:
        //   "https://PROVIDER.com/embed/movie/$tmdbId"
        //   "https://PROVIDER.com/embed/$tmdbId"
        //   "https://PROVIDER.com/movie/$tmdbId"
        //
        // StreamSource(
        //     name       = "Source3",
        //     priority   = 2,
        //     requiresJs = true,
        //     buildUrl   = { tmdbId, type, season, episode ->
        //         if (type == MediaType.MOVIE)
        //             "https://PROVIDER3.com/embed/movie/$tmdbId"
        //         else
        //             "https://PROVIDER3.com/embed/tv/$tmdbId/$season/$episode"
        //     },
        //     referer = "https://PROVIDER3.com/",
        //     origin  = "https://PROVIDER3.com",
        //     headers = mapOf("User-Agent" to StreamHeaders.UA_CHROME_ANDROID),
        // ),

        // ── Source 4 placeholder ──────────────────────────────────────────────
        // StreamSource(
        //     name       = "Source4",
        //     priority   = 3,
        //     requiresJs = true,
        //     buildUrl   = { tmdbId, type, season, episode ->
        //         if (type == MediaType.MOVIE)
        //             "https://PROVIDER4.com/embed/movie/$tmdbId"
        //         else
        //             "https://PROVIDER4.com/embed/tv/$tmdbId/$season/$episode"
        //     },
        //     referer = "https://PROVIDER4.com/",
        //     origin  = "https://PROVIDER4.com",
        //     headers = mapOf("User-Agent" to StreamHeaders.UA_CHROME_ANDROID),
        // ),
    )

    fun sorted() = ALL.sortedBy { it.priority }
}

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
