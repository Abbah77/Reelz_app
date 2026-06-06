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
) {
    /**
     * Race all sources in parallel — return the FIRST successful result.
     * This is the key fix for slow loading: we no longer wait sequentially.
     */
    suspend fun resolve(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
    ): StreamResult? = coroutineScope {
        val sources = SourceRegistry.sorted()
        if (sources.isEmpty()) return@coroutineScope null

        val resultChannel = Channel<StreamResult?>(Channel.CONFLATED)
        val jobs = mutableListOf<Job>()

        sources.forEachIndexed { index, source ->
            val job = launch {
                // Stagger start by priority to avoid all hitting network at once
                if (index > 0) delay(index * 200L)
                try {
                    val url = source.buildUrl(tmdbId, mediaType, season, episode)
                    val result = withTimeoutOrNull(18_000L) {
                        if (source.requiresJs) {
                            withContext(Dispatchers.Main) {
                                // Each WebView gets its own clean instance
                                WebViewScanner(context).scan(url, source)
                            }
                        } else {
                            withContext(Dispatchers.IO) {
                                directScanner.scan(url, source)
                            }
                        }
                    }
                    if (result != null && isActive) {
                        resultChannel.trySend(result)
                    }
                } catch (_: Exception) {}
            }
            jobs.add(job)
        }

        // Overall timeout
        val timeoutJob = launch {
            delay(25_000L)
            resultChannel.trySend(null)
        }

        val result = resultChannel.receive()
        // Cancel all remaining work once we have a result
        jobs.forEach { it.cancel() }
        timeoutJob.cancel()
        result
    }

    /**
     * Fallback: try all sources except the failed one, sequentially.
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
                val result = withTimeoutOrNull(18_000L) {
                    if (source.requiresJs) {
                        withContext(Dispatchers.Main) { WebViewScanner(context).scan(url, source) }
                    } else {
                        withContext(Dispatchers.IO)   { directScanner.scan(url, source) }
                    }
                }
                if (result != null) return result
            } catch (_: Exception) { continue }
        }
        return null
    }
}
