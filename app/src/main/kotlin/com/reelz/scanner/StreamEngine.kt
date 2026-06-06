package com.reelz.scanner

import android.content.Context
import com.reelz.data.model.MediaType
import com.reelz.data.model.StreamResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * StreamEngine
 * ────────────
 * Races ALL sources in parallel and returns the FIRST valid StreamResult.
 *
 * Flow:
 *  1. Sort sources by priority (lowest first).
 *  2. Fire every source concurrently via async{}.
 *  3. Use select{} style via Channel — whichever async job emits first wins.
 *  4. Cancel all other in-flight jobs immediately → zero wasted time.
 *  5. Return the winning StreamResult to the player.
 *
 * JS-based sources  → WebViewScanner (runs on Main thread as required by WebView)
 * Direct sources    → DirectScanner  (runs on IO thread via OkHttp)
 */
@Singleton
class StreamEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directScanner: DirectScanner,
) {

    /**
     * Resolve a stream for the given media.
     * @param tmdbId    TMDB ID
     * @param mediaType MOVIE or TV
     * @param season    season number (TV only, ignored for movies)
     * @param episode   episode number (TV only, ignored for movies)
     * @return first StreamResult found, or null if all sources failed/timed out
     */
    suspend fun resolve(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
    ): StreamResult? = coroutineScope {

        val sources = SourceRegistry.sorted()
        if (sources.isEmpty()) return@coroutineScope null

        // Channel receives the FIRST successful result then closes
        val resultChannel = kotlinx.coroutines.channels.Channel<StreamResult>(
            kotlinx.coroutines.channels.Channel.CONFLATED
        )

        val jobs = sources.map { source ->
            async(if (source.requiresJs) Dispatchers.Main else Dispatchers.IO) {
                try {
                    val url = source.buildUrl(tmdbId, mediaType, season, episode)
                    val result = if (source.requiresJs) {
                        WebViewScanner(context).scan(url, source)
                    } else {
                        directScanner.scan(url, source)
                    }
                    if (result != null) {
                        resultChannel.trySend(result)
                    }
                } catch (_: Exception) { /* source failed silently */ }
            }
        }

        // Await first result with global timeout
        val winner = withTimeoutOrNull(20_000L) {
            // Poll until one job sends to channel or all jobs complete
            var found: StreamResult? = null
            while (found == null && jobs.any { it.isActive }) {
                found = resultChannel.tryReceive().getOrNull()
                if (found == null) delay(50)
            }
            found ?: resultChannel.tryReceive().getOrNull()
        }

        // Cancel all remaining in-flight jobs
        jobs.forEach { it.cancel() }
        resultChannel.close()

        winner
    }

    /**
     * Resolve with fallback — tries primary resolve, then retries with next
     * available sources if the first result turns out to be unplayable.
     * Called by the player when ExoPlayer reports a playback error.
     */
    suspend fun resolveWithFallback(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        excludeSource: String = "",
    ): StreamResult? {
        val sources = SourceRegistry.sorted().filter { it.name != excludeSource }
        for (source in sources) {
            try {
                val url = source.buildUrl(tmdbId, mediaType, season, episode)
                val result = if (source.requiresJs) {
                    withContext(Dispatchers.Main) {
                        WebViewScanner(context).scan(url, source)
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        directScanner.scan(url, source)
                    }
                }
                if (result != null) return result
            } catch (_: Exception) { continue }
        }
        return null
    }
}
