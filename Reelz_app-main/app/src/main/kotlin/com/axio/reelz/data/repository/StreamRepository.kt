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
 * StreamRepository — schema v3
 *
 * Stream: returns StreamResult with streams[], expires_at_ms.
 * Download: returns list of DownloadLink (label, url, language, size_bytes, premium).
 * Subtitles: returns list of Subtitle (url, language, enabled).
 *
 * Auth is optional for all three — guests get identical service.
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
                val dto = result.data
                if (!dto.ok || dto.streams.isEmpty()) {
                    return@withContext NetworkResult.Error(
                        message    = "No streams available for this title",
                        isNotFound = true,
                    )
                }
                val model = dto.toModel()
                streamCache[key] = StreamEntry(result = model)
                Log.d(tag, "Stream resolved: ${model.streams.size} track(s) for $key")
                NetworkResult.Success<StreamResult>(model)
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

    suspend fun getDownloadLinks(
        id: String,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
    ): NetworkResult<List<DownloadLink>> = withContext(Dispatchers.IO) {
        val body = StreamRequestBody(
            id      = id,
            type    = if (mediaType == MediaType.MOVIE) "movie" else "tv",
            season  = season,
            episode = episode,
        )
        val result = safeApiCall(tag) { api.getDownloadLinks(body) }
        return@withContext when (result) {
            is NetworkResult.Success -> {
                val links = result.data.links.map { it.toModel() }
                NetworkResult.Success<List<DownloadLink>>(links)
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

    private val subtitleCache = mutableMapOf<String, List<Subtitle>>()

    suspend fun getSubtitles(
        id: String,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        languages: List<String> = listOf("en"),
    ): NetworkResult<List<Subtitle>> = withContext(Dispatchers.IO) {
        val key = "$id|${mediaType.name}|$season|$episode"
        subtitleCache[key]?.let { return@withContext NetworkResult.Success(it, fromCache = true) }

        val body = SubtitleRequestBody(
            id        = id,
            type      = if (mediaType == MediaType.MOVIE) "movie" else "tv",
            season    = season,
            episode   = episode,
            languages = languages,
        )
        val result = safeApiCall(tag) { api.getSubtitles(body) }
        return@withContext when (result) {
            is NetworkResult.Success -> {
                val subs = result.data.subtitles.map { it.toModel() }
                if (subs.isNotEmpty()) subtitleCache[key] = subs
                NetworkResult.Success<List<Subtitle>>(subs)
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
