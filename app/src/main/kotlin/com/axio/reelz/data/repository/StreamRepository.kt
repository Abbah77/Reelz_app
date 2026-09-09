package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.data.model.*
import com.axio.reelz.data.remote.api.ReelzApi
import com.axio.reelz.data.remote.api.StreamRequestBody
import com.axio.reelz.data.remote.api.SubtitleRequestBody
import com.axio.reelz.core.network.NetworkResult
import com.axio.reelz.core.network.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * StreamRepository — Schema v4
 *
 * ENVELOPE RULE: Every call returns ApiResponse<T>.
 * Pattern: unwrap envelope.data, read expires_at_ms from inside data
 * (it is content metadata — link expiry), read cache_ttl_ms from
 * envelope root (null for stream/download — URLs are never app-cached).
 *
 * Auth is optional for all three endpoints — guests get identical service.
 * Token is sent when available to enable server-side history logging.
 */
@Singleton
class StreamRepository @Inject constructor(
    private val api: ReelzApi,
) {
    private val tag = "StreamRepository"

    private data class StreamEntry(
        val result: StreamResult,
        val storedAt: Long = System.currentTimeMillis(),
    ) {
        fun isAlive() = System.currentTimeMillis() < result.expiresAtMs
    }

    private val streamCache = mutableMapOf<String, StreamEntry>()

    private fun cacheKey(id: String, type: MediaType, season: Int, episode: Int) =
        "$id|${type.name}|$season|$episode"

    // ── Stream resolution ─────────────────────────────────────────────────────

    suspend fun resolveStream(
        id: String,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
    ): NetworkResult<StreamResult> = withContext(Dispatchers.IO) {

        val key = cacheKey(id, mediaType, season, episode)
        streamCache[key]?.let { entry ->
            if (entry.isAlive()) {
                Log.d(tag, "Stream cache HIT for $key")
                return@withContext NetworkResult.Success(entry.result, fromCache = true)
            }
            streamCache.remove(key)
        }

        val body = StreamRequestBody(
            id      = id,
            type    = if (mediaType == MediaType.MOVIE) "movie" else "tv",
            season  = season,
            episode = episode,
        )
        val result = safeApiCall(tag) { api.resolveStream(body) }

        return@withContext when (result) {
            is NetworkResult.Success -> {
                val envelope = result.data
                val payload  = envelope.data
                if (!envelope.ok || payload == null) {
                    return@withContext NetworkResult.Error(
                        message    = envelope.error ?: "No streams available for this title",
                        isNotFound = true,
                    )
                }
                if (payload.streams.isEmpty()) {
                    return@withContext NetworkResult.Error(
                        message    = "No streams available for this title",
                        isNotFound = true,
                    )
                }
                // expires_at_ms is inside data — it is content metadata (link expiry)
                val model = payload.toModel()
                streamCache[key] = StreamEntry(result = model)
                Log.d(tag, "Stream resolved: ${model.streams.size} track(s) for $key")
                NetworkResult.Success(model)
            }
            is NetworkResult.Error -> NetworkResult.Error(
                message        = result.message,
                code           = result.code,
                isNetworkError = result.isNetworkError,
                isNotFound     = result.isNotFound,
            )
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    fun invalidate(id: String, mediaType: MediaType, season: Int, episode: Int) {
        streamCache.remove(cacheKey(id, mediaType, season, episode))
    }

    // ── Download links ────────────────────────────────────────────────────────

    // ── Download links ────────────────────────────────────────────────────────
    //
    // Cached in-memory keyed by content identity. Cache lifetime is driven by
    // expires_at_ms inside the response data — same as stream links.

    private data class DownloadLinksEntry(
        val links: List<DownloadLink>,
        val expiresAtMs: Long,
    ) {
        fun isAlive() = System.currentTimeMillis() < expiresAtMs
    }

    private val downloadLinksCache = mutableMapOf<String, DownloadLinksEntry>()

    /**
     * Returns download links AND any subtitles the backend bundled with the response.
     * Subtitles are optional — the list will be empty if the backend sent none.
     * Callers should schedule silent subtitle downloads for any returned subtitles.
     */
    suspend fun getDownloadLinks(
        id: String,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
    ): NetworkResult<Pair<List<DownloadLink>, List<com.axio.reelz.data.model.Subtitle>>> = withContext(Dispatchers.IO) {
        val key = cacheKey(id, mediaType, season, episode)

        // Return cached links if still alive (expires_at_ms not passed).
        downloadLinksCache[key]?.let { entry ->
            if (entry.isAlive()) {
                Log.d(tag, "Download links cache HIT for $key")
                return@withContext NetworkResult.Success(Pair(entry.links, emptyList()), fromCache = true)
            }
            downloadLinksCache.remove(key)
        }

        val body = StreamRequestBody(
            id      = id,
            type    = if (mediaType == MediaType.MOVIE) "movie" else "tv",
            season  = season,
            episode = episode,
        )
        val result = safeApiCall(tag) { api.getDownloadLinks(body) }
        return@withContext when (result) {
            is NetworkResult.Success -> {
                val envelope = result.data
                val payload  = envelope.data
                if (!envelope.ok || payload == null) {
                    return@withContext NetworkResult.Error(envelope.error ?: "No download links available")
                }
                // Render exactly what the backend sends — no filtering, no inference.
                val links = payload.links.map { it.toModel() }
                val subtitles = payload.subtitles?.map { it.toModel() } ?: emptyList()
                // Cache using expires_at_ms from backend (same pattern as stream cache).
                if (payload.expiresAtMs > 0) {
                    downloadLinksCache[key] = DownloadLinksEntry(links, payload.expiresAtMs)
                }
                Log.d(tag, "Download links: ${links.size} link(s), ${subtitles.size} subtitle(s) for $key")
                NetworkResult.Success(Pair(links, subtitles))
            }
            is NetworkResult.Error -> NetworkResult.Error(
                message        = result.message,
                code           = result.code,
                isNetworkError = result.isNetworkError,
                isNotFound     = result.isNotFound,
            )
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    // ── Subtitles ─────────────────────────────────────────────────────────────
    //
    // Cache uses the same TTL-expiry pattern as stream/download caches.
    // Default TTL: 1 hour (3_600_000 ms), overridden by cache_ttl_ms from envelope.

    private data class SubtitleCacheEntry(
        val subtitles: List<Subtitle>,
        val expiresAtMs: Long,
    ) {
        fun isAlive() = System.currentTimeMillis() < expiresAtMs
    }

    private val subtitleCache = mutableMapOf<String, SubtitleCacheEntry>()

    suspend fun getSubtitles(
        id: String,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        /** Single ISO 639-1 language code: "en" | "es" | "fr" | "pt" | "de" | "it" | "ar" */
        language: String = "en",
        /** Duration in ms — helps backend find the exact subtitle match. */
        durationMs: Long = 0L,
    ): NetworkResult<List<Subtitle>> = withContext(Dispatchers.IO) {
        val key = "$id|${mediaType.name}|$season|$episode|$language"

        subtitleCache[key]?.let { entry ->
            if (entry.isAlive()) {
                Log.d(tag, "Subtitle cache HIT for $key")
                return@withContext NetworkResult.Success(entry.subtitles, fromCache = true)
            }
            subtitleCache.remove(key)
        }

        val body = SubtitleRequestBody(
            id         = id,
            type       = if (mediaType == MediaType.MOVIE) "movie" else "tv",
            season     = season,
            episode    = episode,
            languages  = language,
            durationMs = durationMs,
        )
        val result = safeApiCall(tag) { api.getSubtitles(body) }
        return@withContext when (result) {
            is NetworkResult.Success -> {
                val envelope = result.data
                val payload  = envelope.data
                if (!envelope.ok || payload == null) {
                    return@withContext NetworkResult.Error(envelope.error ?: "Subtitles unavailable")
                }
                val subs = payload.subtitles.map { it.toModel() }
                val ttlMs = envelope.cacheTtlMs ?: 3_600_000L
                if (subs.isNotEmpty()) {
                    subtitleCache[key] = SubtitleCacheEntry(
                        subtitles  = subs,
                        expiresAtMs = System.currentTimeMillis() + ttlMs,
                    )
                    Log.d(tag, "Subtitles cached: ${subs.size} track(s) for $key, TTL=${ttlMs}ms")
                }
                NetworkResult.Success(subs)
            }
            is NetworkResult.Error -> NetworkResult.Error(
                message        = result.message,
                code           = result.code,
                isNetworkError = result.isNetworkError,
                isNotFound     = result.isNotFound,
            )
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    fun clearSubtitleCache() = subtitleCache.clear()
}
