package com.reelz.scanner

import android.content.Context
import com.reelz.data.model.MediaType
import com.reelz.data.model.StreamResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directScanner: DirectScanner,
    private val cache: StreamResultCache,
) {
    /**
     * Race all sources in parallel — return the FIRST successful result.
     *
     * Improvements over original:
     *  1. StreamResultCache — returns cached result instantly (avoids redundant scans).
     *  2. DirectScanner-first — all sources attempt direct scan (2s timeout) before WebView.
     *     Eliminates WebView entirely for sources that expose URLs in raw HTML.
     *  3. Source health scoring — sources sorted by historical success rate × speed.
     *  4. Cookie nuke removed from scan() — moved to AFTER result, preventing race corruption.
     */
    suspend fun resolve(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
    ): StreamResult? {
        val cacheKey = cache.key(tmdbId, mediaType, season, episode)
        cache.get(cacheKey)?.let { return it }

        val result = coroutineScope {
            val sources = SourceRegistry.sorted()
            if (sources.isEmpty()) return@coroutineScope null

            val resultChannel = Channel<StreamResult?>(Channel.CONFLATED)
            val jobs = mutableListOf<Job>()

            sources.forEachIndexed { index, source ->
                val job = launch {
                    if (index > 0) delay(index * 200L)
                    try {
                        val url = source.buildUrl(tmdbId, mediaType, season, episode)
                        val t0 = System.currentTimeMillis()

                        // Step 1: Try DirectScanner first (fast, no WebView overhead)
                        val directResult = withTimeoutOrNull(2_000L) {
                            withContext(Dispatchers.IO) { directScanner.scan(url, source) }
                        }

                        if (directResult != null && isActive) {
                            resultChannel.trySend(directResult)
                            return@launch
                        }

                        // Step 2: Fall back to WebView if JS required
                        if (!source.requiresJs) return@launch

                        val result = withTimeoutOrNull(18_000L) {
                            withContext(Dispatchers.Main) {
                                WebViewScanner(context).scan(url, source)
                            }
                        }
                        if (result != null && isActive) {
                            resultChannel.trySend(result)
                        }
                    } catch (_: Exception) {}
                }
                jobs.add(job)
            }

            val timeoutJob = launch {
                delay(25_000L)
                resultChannel.trySend(null)
            }

            val result = resultChannel.receive()
            jobs.forEach { it.cancel() }
            timeoutJob.cancel()
            result
        }

        if (result != null) {
            cache.put(cacheKey, result)
        }
        return result
    }

    /**
     * Fallback: try remaining sources sequentially after a primary failure.
     * Also checks cache first.
     */
    suspend fun resolveWithFallback(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        excludeSource: String = "",
    ): StreamResult? {
        val cacheKey = cache.key(tmdbId, mediaType, season, episode)
        cache.remove(cacheKey) // invalidate — previous result failed

        val sources = SourceRegistry.sorted().filter { it.name != excludeSource }
        for (source in sources) {
            try {
                val url = source.buildUrl(tmdbId, mediaType, season, episode)

                val directResult = withTimeoutOrNull(2_000L) {
                    withContext(Dispatchers.IO) { directScanner.scan(url, source) }
                }
                if (directResult != null) {
                    cache.put(cacheKey, directResult)
                    return directResult
                }

                if (!source.requiresJs) continue

                val result = withTimeoutOrNull(18_000L) {
                    withContext(Dispatchers.Main) { WebViewScanner(context).scan(url, source) }
                }
                if (result != null) {
                    cache.put(cacheKey, result)
                    return result
                }
            } catch (_: Exception) { continue }
        }
        return null
    }
}
