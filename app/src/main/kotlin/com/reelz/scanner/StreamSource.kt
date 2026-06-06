package com.reelz.scanner

import com.reelz.data.model.MediaType

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

        // ── VidSrc.to ─────────────────────────────────────────────────────────
        StreamSource(
            name     = "VidSrc",
            priority = 0,
            buildUrl = { id, type, s, e ->
                if (type == MediaType.MOVIE) "https://vidsrc.to/embed/movie/$id"
                else "https://vidsrc.to/embed/tv/$id/$s/$e"
            },
            referer  = "https://vidsrc.to/",
            origin   = "https://vidsrc.to",
            headers  = mapOf(
                "User-Agent"      to StreamHeaders.UA_CHROME_ANDROID,
                "Accept"          to StreamHeaders.ACCEPT_HTML,
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        ),

        // ── VidLink.pro ───────────────────────────────────────────────────────
        StreamSource(
            name     = "VidLink",
            priority = 1,
            buildUrl = { id, type, s, e ->
                if (type == MediaType.MOVIE) "https://vidlink.pro/movie/$id"
                else "https://vidlink.pro/tv/$id/$s/$e"
            },
            referer  = "https://vidlink.pro/",
            origin   = "https://vidlink.pro",
            headers  = mapOf(
                "User-Agent"      to StreamHeaders.UA_CHROME_ANDROID,
                "Accept"          to StreamHeaders.ACCEPT_HTML,
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        ),

        // ── VidSrc.me ─────────────────────────────────────────────────────────
        StreamSource(
            name     = "VidSrc.me",
            priority = 2,
            buildUrl = { id, type, s, e ->
                if (type == MediaType.MOVIE) "https://vidsrc.me/embed/movie?tmdb=$id"
                else "https://vidsrc.me/embed/tv?tmdb=$id&season=$s&episode=$e"
            },
            referer  = "https://vidsrc.me/",
            origin   = "https://vidsrc.me",
            headers  = mapOf(
                "User-Agent"      to StreamHeaders.UA_CHROME_ANDROID,
                "Accept"          to StreamHeaders.ACCEPT_HTML,
            ),
        ),

        // ── 2embed.cc ─────────────────────────────────────────────────────────
        StreamSource(
            name     = "2Embed",
            priority = 3,
            buildUrl = { id, type, s, e ->
                if (type == MediaType.MOVIE) "https://www.2embed.cc/embed/$id"
                else "https://www.2embed.cc/embedtv/$id&s=$s&e=$e"
            },
            referer  = "https://www.2embed.cc/",
            origin   = "https://www.2embed.cc",
            headers  = mapOf(
                "User-Agent" to StreamHeaders.UA_CHROME_ANDROID,
                "Accept"     to StreamHeaders.ACCEPT_HTML,
            ),
        ),

        // ── Embed.su ──────────────────────────────────────────────────────────
        StreamSource(
            name     = "EmbedSu",
            priority = 4,
            buildUrl = { id, type, s, e ->
                if (type == MediaType.MOVIE) "https://embed.su/embed/movie/$id"
                else "https://embed.su/embed/tv/$id/$s/$e"
            },
            referer  = "https://embed.su/",
            origin   = "https://embed.su",
            headers  = mapOf(
                "User-Agent" to StreamHeaders.UA_CHROME_ANDROID,
                "Accept"     to StreamHeaders.ACCEPT_HTML,
            ),
        ),
    )

    fun sorted() = ALL.sortedBy { it.priority }
}

object StreamHeaders {
    const val UA_CHROME_ANDROID =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

    const val UA_CHROME_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

    const val ACCEPT_HTML =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

    const val ACCEPT_JSON = "application/json, text/plain, */*"
}
