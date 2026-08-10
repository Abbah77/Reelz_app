package com.axio.reelz.scanner

import com.axio.reelz.data.model.MediaType
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import com.axio.reelz.remoteconfig.StreamSourceConfig
import javax.inject.Inject
import javax.inject.Singleton

data class StreamSource(
    val id: String = "",
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
    id        = id,
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
    private val stats: SourceStatsTracker,
) {
    /**
     * Returns the current enabled + sorted list of stream sources, with
     * persistently-unhealthy sources filtered out BEFORE they ever cost a
     * network call or WebView spin-up.
     *
     * This is the fix for sources like vidsrc.to's current tokenized
     * page-N.html segment scheme: it can never resolve on ExoPlayer no
     * matter how many times we retry it, yet without this filter every
     * user on the sequential/metered path pays its full timeout on every
     * single resolve, forever. [SourceStatsTracker] (previously built but
     * never wired up anywhere in the app) tracks persistent per-source
     * success/failure + timing stats; [SourceStatsTracker.shouldSkip]
     * skips a source once it has enough samples and a low enough success
     * rate — with a periodic re-probe so a fixed source is rediscovered
     * automatically instead of needing a remote config push.
     *
     * Also re-sorts by [SourceStatsTracker.Stats.score] (success rate
     * weighted by speed) ahead of remote-config priority, so a source that
     * is empirically fast and reliable on THIS device naturally rises to
     * the front of the sequential/metered path — remote config priority is
     * only the starting order for sources with no data yet.
     *
     * Called every time [StreamEngine] needs to start a race so it always
     * picks up the latest remote config AND the latest stats state without
     * restarting the app.
     */
    fun sorted(): List<StreamSource> {
        val all = remoteConfig.activeStreamSources()
        val healthy = all.filterNot { stats.shouldSkip(it.id) }

        // Safety net: if stats-based filtering would exclude EVERY source
        // (e.g. a transient outage across all providers), fall back to the
        // full unfiltered list rather than returning empty and guaranteeing
        // failure. Skipping is an optimization for the common case, never
        // allowed to make resolution strictly worse than no filtering.
        val candidates = healthy.ifEmpty { all }

        // Sources with real data (totalCount > 0) are sorted by empirical
        // score, best first. Sources with zero data keep remote config's
        // priority ordering and are placed after anything with a positive
        // score — untested sources shouldn't jump ahead of a proven-fast
        // one, but should still be tried before a proven-bad one (which
        // would already have been filtered out above, or scored low).
        return candidates
            .sortedWith(
                // hasData=true sorts before hasData=false (Kotlin's default
                // Boolean ordering is false < true, so descending puts the
                // sources we actually have evidence about first).
                compareByDescending<StreamSourceConfig> { stats.get(it.id).totalCount > 0 }
                    .thenByDescending { stats.get(it.id).score }
                    .thenBy { it.priority }
            )
            .map { it.toStreamSource() }
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
