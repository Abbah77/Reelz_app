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

@Singleton
class StreamRepository @Inject constructor() {

    private val TAG = "StreamRepository"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")
                .header("Accept", "*/*")
                .build()
            chain.proceed(req)
        }
        .build()

    suspend fun resolveStream(
        tmdbId: Int,
        type: String = "movie",
        season: Int = 1,
        episode: Int = 1,
    ): StreamResult = withContext(Dispatchers.IO) {
        val candidates = try {
            NativeScanner.candidateUrls(
                tmdbId   = tmdbId.toString(),
                type     = type,
                season   = season,
                episode  = episode,
                timeoutMs = 8000L,
            )
        } catch (e: UnsatisfiedLinkError) {
            // NDK not available (e.g. emulator without ABI) — return NotFound gracefully
            Log.w(TAG, "Native library not loaded: ${e.message}")
            return@withContext StreamResult.NotFound
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No candidates — add source domains to scanner.cpp PLACEHOLDER_SOURCES")
            return@withContext StreamResult.NotFound
        }

        Log.d(TAG, "Racing ${candidates.size} sources for tmdb=$tmdbId")
        val url = raceForM3u8(candidates)
        if (url != null) StreamResult.Found(url, "parallel-race")
        else StreamResult.NotFound
    }

    private suspend fun raceForM3u8(candidates: List<String>): String? = coroutineScope {
        val winner = CompletableDeferred<String?>()
        val jobs   = mutableListOf<Job>()

        candidates.forEachIndexed { index, url ->
            val job = launch(Dispatchers.IO) {
                delay(index * 50L)
                if (winner.isCompleted) return@launch
                val resolved = tryFetchM3u8(url)
                if (resolved != null && !winner.isCompleted) {
                    winner.complete(resolved)
                }
            }
            jobs.add(job)
        }

        val timeout = launch {
            delay(12_000L)
            if (!winner.isCompleted) winner.complete(null)
        }

        val result = winner.await()
        jobs.forEach { it.cancel() }
        timeout.cancel()
        result
    }

    private fun tryFetchM3u8(url: String): String? {
        return try {
            val req = Request.Builder().url(url).head().build()
            client.newCall(req).execute().use { resp ->
                val ct       = resp.header("Content-Type") ?: ""
                val finalUrl = resp.request.url.toString()
                val isM3u8 = ct.contains("mpegurl", ignoreCase = true) ||
                             ct.contains("x-mpegurl", ignoreCase = true) ||
                             finalUrl.contains(".m3u8", ignoreCase = true)
                if (resp.isSuccessful && isM3u8) finalUrl else null
            }
        } catch (e: Exception) {
            Log.v(TAG, "Source failed: $url — ${e.message}")
            null
        }
    }
}
