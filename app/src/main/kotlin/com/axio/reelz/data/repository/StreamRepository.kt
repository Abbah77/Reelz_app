package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.data.model.QualityTrack
import com.axio.reelz.data.model.StreamResult
import com.axio.reelz.data.model.Subtitle
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
 * FIX: Removed all result.map {} calls — they cause ClassCastException at runtime
 *      when Kotlin type erasure cannot safely coerce between generic types inside
 *      a coroutine suspend function. Replaced with explicit when() branches.
 */
@Singleton
class StreamRepository @Inject constructor(
    private val api: ReelzApi,
) {
    private val tag = "StreamRepository"

    private data class StreamEntry(
        val result: StreamResult,
        val storedAt: Long = System.currentTimeMillis(),
        val ttlMs: Long = 240_000L,
    ) {
        fun isAlive() = System.currentTimeMillis() - storedAt < ttlMs
    }

    private val streamCache = mutableMapOf<String, StreamEntry>()

    private fun cacheKey(id: String, type: MediaType, season: Int, episode: Int) =
        "$id|${type.name}|$season|$episode"

    // ── Stream resolution ─────────────────────────────────────────────────────

    suspend fun resolveStream(
        id: String,
        title: String,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
    ): NetworkResult<StreamResult> = withContext(Dispatchers.IO) {

        val key = cacheKey(id, mediaType, season, episode)

        streamCache[key]?.let { entry ->
            if (entry.isAlive()) {
                Log.d(tag, "Stream cache HIT for $key")
                return@withContext NetworkResult.Success(entry.result, fromCache = true)
            } else {
                streamCache.remove(key)
                Log.d(tag, "Stream cache EXPIRED for $key")
            }
        }

        val body = StreamRequestBody(
            id      = id,
            type    = if (mediaType == MediaType.MOVIE) "movie" else "tv",
            title   = title,
            season  = season,
            episode = episode,
        )
        val result = safeApiCall(tag) { api.resolveStream(body) }

        return@withContext when (result) {
            is NetworkResult.Success -> {
                val dto = result.data
                if (!dto.ok || dto.streamUrl.isBlank()) {
                    return@withContext NetworkResult.Error(
                        message    = "Content not available yet",
                        isNotFound = true,
                    )
                }
                val model = dto.toModel()
                streamCache[key] = StreamEntry(
                    result = model,
                    ttlMs  = dto.cacheTtlMs.coerceAtLeast(60_000L),
                )
                Log.d(tag, "Stream resolved for $key (ttl=${dto.cacheTtlMs}ms)")
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
        val key = cacheKey(id, mediaType, season, episode)
        if (streamCache.remove(key) != null) {
            Log.d(tag, "Stream cache invalidated for $key")
        }
    }

    // ── Download links ────────────────────────────────────────────────────────

    suspend fun getDownloadLinks(
        id: String,
        title: String,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
    ): NetworkResult<List<QualityTrack>> = withContext(Dispatchers.IO) {
        val body = StreamRequestBody(
            id      = id,
            type    = if (mediaType == MediaType.MOVIE) "movie" else "tv",
            title   = title,
            season  = season,
            episode = episode,
        )
        val result = safeApiCall(tag) { api.getDownloadLinks(body) }
        return@withContext when (result) {
            is NetworkResult.Success -> {
                val tracks = result.data.links.map { it.toTrack() }
                NetworkResult.Success<List<QualityTrack>>(tracks)
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
