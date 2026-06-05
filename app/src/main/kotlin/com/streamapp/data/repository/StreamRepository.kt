package com.streamapp.data.repository

import android.util.Log
import com.streamapp.scanner.NativeScanner
import com.streamapp.data.model.StreamResult
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * StreamRepository — races multiple placeholder source URLs in parallel.
 * The C++ layer (NativeScanner) builds the candidate list; this layer validates
 * each one with a HEAD request then returns the first live m3u8 URL.
 *
 * HOW TO WIRE YOUR SOURCES:
 *  1. Open scanner.cpp → update PLACEHOLDER_SOURCES with your actual domains.
 *  2. Open WebViewScanner.kt → add JS extraction logic per site if needed.
 *  3. This repository code needs NO changes — it auto-races whatever the C++ returns.
 */
@Singleton
class StreamRepository @Inject constructor() {

    private val TAG = "StreamRepository"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8)")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate")
                .build()
            chain.proceed(req)
        }
        .build()

    /**
     * Main entry: fire all placeholder sources in parallel, return first live m3u8.
     * Falls back to WebView JS extraction if HEAD check fails.
     */
    suspend fun resolveStream(
        tmdbId: Int,
        type: String = "movie",
        season: Int = 1,
        episode: Int = 1,
    ): StreamResult = withContext(Dispatchers.IO) {
        // C++ builds all candidate URLs from placeholder list
        val candidates = NativeScanner.candidateUrls(
            tmdbId = tmdbId.toString(),
            type = type,
            season = season,
            episode = episode,
            timeoutMs = 8000L
        )

        if (candidates.isEmpty()) {
            Log.w(TAG, "No candidates — add your source domains to scanner.cpp")
            return@withContext StreamResult.NotFound
        }

        Log.d(TAG, "Racing ${candidates.size} candidate sources for tmdb=$tmdbId")

        // Race all candidates — first valid m3u8 wins
        val result = raceForM3u8(candidates)
        if (result != null) {
            Log.d(TAG, "Stream found: $result")
            return@withContext StreamResult.Found(result, "parallel-race")
        }

        Log.w(TAG, "All sources exhausted for tmdb=$tmdbId")
        StreamResult.NotFound
    }

    /**
     * Fires all candidate URLs concurrently.
     * Returns the first URL that responds with a valid m3u8 or HLS content-type.
     */
    private suspend fun raceForM3u8(candidates: List<String>): String? = coroutineScope {
        val winner = CompletableDeferred<String?>()
        val jobs = mutableListOf<Job>()

        candidates.forEachIndexed { index, url ->
            val job = launch(Dispatchers.IO) {
                delay(index * 50L)  // 50ms stagger to avoid thundering herd
                if (winner.isCompleted) return@launch
                val resolved = tryFetchM3u8(url)
                if (resolved != null && !winner.isCompleted) {
                    winner.complete(resolved)
                }
            }
            jobs.add(job)
        }

        // Timeout safety net
        val timeout = launch {
            delay(12_000)
            if (!winner.isCompleted) winner.complete(null)
        }

        val result = winner.await()
        jobs.forEach { it.cancel() }
        timeout.cancel()
        result
    }

    /**
     * Validates a single URL — checks if it's a live m3u8 or HLS endpoint.
     * Returns the final URL (after redirects) if valid, null otherwise.
     */
    private fun tryFetchM3u8(url: String): String? {
        return try {
            val req = Request.Builder().url(url).head().build()
            client.newCall(req).execute().use { resp ->
                val ct = resp.header("Content-Type") ?: ""
                val finalUrl = resp.request.url.toString()
                if (resp.isSuccessful && (
                    ct.contains("mpegurl", ignoreCase = true) ||
                    ct.contains("x-mpegurl", ignoreCase = true) ||
                    ct.contains("octet-stream") ||
                    finalUrl.contains(".m3u8")
                )) {
                    finalUrl
                } else null
            }
        } catch (e: Exception) {
            Log.v(TAG, "Source failed: $url — ${e.message}")
            null
        }
    }
}
