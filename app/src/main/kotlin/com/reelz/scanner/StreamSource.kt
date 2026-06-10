package com.reelz.scanner

import com.reelz.data.model.MediaType
import com.reelz.remoteconfig.RemoteConfigRepository
import com.reelz.remoteconfig.StreamSourceConfig
import javax.inject.Inject
import javax.inject.Singleton

data class StreamSource(
    val name: String,
    val buildUrl: (tmdbId: Int, mediaType: MediaType, season: Int, episode: Int) -> String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String = "",
    val origin: String  = "",
    val requiresJs: Boolean = true,
    val priority: Int = 0,
)

/**
 * Converts a [StreamSourceConfig] from remote config into a runtime [StreamSource].
 * URL patterns use {tmdb_id}, {season}, {episode} placeholders.
 */
fun StreamSourceConfig.toStreamSource(): StreamSource = StreamSource(
    name      = name,
    priority  = priority,
    requiresJs = requiresJs,
    referer   = referer,
    origin    = origin,
    headers   = headers,
    buildUrl  = { id, type, s, e ->
        val pattern = if (type == MediaType.MOVIE) urlPatterns.movie else urlPatterns.tv
        pattern
            .replace("{tmdb_id}", id.toString())
            .replace("{season}",  s.toString())
            .replace("{episode}", e.toString())
    },
)

/**
 * Dynamic [SourceRegistry] backed by [RemoteConfigRepository].
 *
 * Falls back to the compile-time hard-coded list if remote config hasn't loaded yet,
 * so the app is never broken even on first launch with no network.
 */
@Singleton
class SourceRegistry @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
) {
    /**
     * Returns the current enabled + sorted list of stream sources.
     * Called every time [StreamEngine] needs to start a race so it always
     * picks up the latest remote config without restarting the app.
     */
    fun sorted(): List<StreamSource> {
        val remoteSources = remoteConfig.activeStreamSources()
        if (remoteSources.isNotEmpty()) {
            return remoteSources.map { it.toStreamSource() }
        }
        // Compile-time fallback — identical to what was previously hard-coded
        return FALLBACK_SOURCES
    }

    companion object {
        /** Hard-coded fallback list — only used when remote config is unavailable. */
        val FALLBACK_SOURCES: List<StreamSource> = listOf(
            StreamSource(
                name = "VidSrc", priority = 0,
                buildUrl = { id, type, s, e ->
                    if (type == MediaType.MOVIE) "https://vidsrc.to/embed/movie/$id"
                    else "https://vidsrc.to/embed/tv/$id/$s/$e"
                },
                referer = "https://vidsrc.to/", origin = "https://vidsrc.to",
                headers = mapOf(
                    "User-Agent" to StreamHeaders.UA_CHROME_ANDROID,
                    "Accept" to StreamHeaders.ACCEPT_HTML,
                    "Accept-Language" to "en-US,en;q=0.9",
                ),
            ),
            StreamSource(
                name = "VidLink", priority = 1,
                buildUrl = { id, type, s, e ->
                    if (type == MediaType.MOVIE) "https://vidlink.pro/movie/$id"
                    else "https://vidlink.pro/tv/$id/$s/$e"
                },
                referer = "https://vidlink.pro/", origin = "https://vidlink.pro",
                headers = mapOf(
                    "User-Agent" to StreamHeaders.UA_CHROME_ANDROID,
                    "Accept" to StreamHeaders.ACCEPT_HTML,
                    "Accept-Language" to "en-US,en;q=0.9",
                ),
            ),
            StreamSource(
                name = "VidSrc.me", priority = 2,
                buildUrl = { id, type, s, e ->
                    if (type == MediaType.MOVIE) "https://vidsrc.me/embed/movie?tmdb=$id"
                    else "https://vidsrc.me/embed/tv?tmdb=$id&season=$s&episode=$e"
                },
                referer = "https://vidsrc.me/", origin = "https://vidsrc.me",
                headers = mapOf("User-Agent" to StreamHeaders.UA_CHROME_ANDROID, "Accept" to StreamHeaders.ACCEPT_HTML),
            ),
            StreamSource(
                name = "2Embed", priority = 3,
                buildUrl = { id, type, s, e ->
                    if (type == MediaType.MOVIE) "https://www.2embed.cc/embed/$id"
                    else "https://www.2embed.cc/embedtv/$id&s=$s&e=$e"
                },
                referer = "https://www.2embed.cc/", origin = "https://www.2embed.cc",
                headers = mapOf("User-Agent" to StreamHeaders.UA_CHROME_ANDROID, "Accept" to StreamHeaders.ACCEPT_HTML),
            ),
            StreamSource(
                name = "EmbedSu", priority = 4,
                buildUrl = { id, type, s, e ->
                    if (type == MediaType.MOVIE) "https://embed.su/embed/movie/$id"
                    else "https://embed.su/embed/tv/$id/$s/$e"
                },
                referer = "https://embed.su/", origin = "https://embed.su",
                headers = mapOf("User-Agent" to StreamHeaders.UA_CHROME_ANDROID, "Accept" to StreamHeaders.ACCEPT_HTML),
            ),
        )
    }
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
