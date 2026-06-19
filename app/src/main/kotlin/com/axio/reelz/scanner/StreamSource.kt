package com.axio.reelz.scanner

import com.axio.reelz.data.model.MediaType
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import com.axio.reelz.remoteconfig.StreamSourceConfig
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
 * Remote config has full authority over stream sources — names, URL
 * patterns, headers, referer/origin and enabled/priority are all defined
 * in `stream_sources` and never hard-coded in the app.
 */
@Singleton
class SourceRegistry @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
) {
    /**
     * Returns the current enabled + sorted list of stream sources.
     * Called every time [StreamEngine] needs to start a race so it always
     * picks up the latest remote config without restarting the app.
     *
     * Returns an empty list if remote config hasn't loaded yet — the app
     * gates on config readiness before reaching the player, so this should
     * not happen in practice.
     */
    fun sorted(): List<StreamSource> =
        remoteConfig.activeStreamSources().map { it.toStreamSource() }
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
