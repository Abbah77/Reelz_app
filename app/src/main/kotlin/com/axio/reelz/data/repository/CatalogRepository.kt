package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.core.network.NetworkResult
import com.axio.reelz.core.network.safeApiCall
import com.axio.reelz.core.database.*
import com.axio.reelz.data.model.*
import com.axio.reelz.data.remote.api.ReelzApi
import com.axio.reelz.data.dto.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  CatalogRepository — Schema v4
//
//  ENVELOPE RULE: Every API call returns ApiResponse<T>.
//  Pattern in every branch:
//    val envelope = result.data           // ApiResponse<T>
//    if (!envelope.ok || envelope.data == null) → Error
//    val payload  = envelope.data         // T — the actual content
//    val ttl      = envelope.cacheTtlMs   // Long? — from root, not inside payload
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class CatalogRepository @Inject constructor(
    private val api: ReelzApi,
    private val feedDao: FeedCacheDao,
    private val detailDao: DetailCacheDao,
    private val gson: Gson,
) {
    private val tag = "CatalogRepository"

    // ── Feed ──────────────────────────────────────────────────────────────────

    suspend fun getFeed(forceRefresh: Boolean = false): NetworkResult<List<FeedSection>> =
        withContext(Dispatchers.IO) {
            val cached   = feedDao.getAll()
            val hasCache = cached.isNotEmpty()
            val isStale  = forceRefresh || cached.any { it.isStale() }

            if (hasCache && !isStale) {
                Log.d(tag, "Feed: serving ${cached.size} sections from cache")
                return@withContext NetworkResult.Success(
                    data       = cached.map { it.toFeedSection() },
                    fromCache  = true,
                    cacheAgeMs = System.currentTimeMillis() - (cached.minOfOrNull { it.cachedAtMs } ?: 0L),
                )
            }

            val result = safeApiCall(tag) { api.getFeed(refresh = if (forceRefresh) 1 else 0) }

            return@withContext when (result) {
                is NetworkResult.Success -> {
                    val envelope = result.data
                    val payload  = envelope.data
                    if (!envelope.ok || payload == null) {
                        return@withContext NetworkResult.Error(envelope.error ?: "Feed unavailable")
                    }
                    val cacheTtlMs = envelope.cacheTtlMs ?: 3_600_000L

                    val rows = payload.sections.mapNotNull { sectionDto ->
                        if (sectionDto.items.isEmpty()) null
                        else CachedFeedRow(
                            sectionId  = sectionDto.id,
                            title      = sectionDto.title,
                            itemsJson  = gson.toJson(sectionDto.items),
                            hasMore    = sectionDto.hasMore,
                            nextCursor = sectionDto.nextCursor,
                            cacheTtlMs = cacheTtlMs,
                        )
                    }
                    if (rows.isNotEmpty()) {
                        feedDao.upsertAll(rows)
                        Log.d(tag, "Feed: cached ${rows.size} sections (ttl=${cacheTtlMs}ms)")
                    }
                    NetworkResult.Success(data = payload.sections.map { it.toModel() })
                }
                is NetworkResult.Error -> {
                    if (hasCache) {
                        Log.w(tag, "Feed: network failed, serving stale cache")
                        NetworkResult.Success(
                            data      = cached.map { it.toFeedSection() },
                            fromCache = true,
                        )
                    } else {
                        NetworkResult.Error(
                            message        = result.message,
                            code           = result.code,
                            isNetworkError = result.isNetworkError,
                            isNotFound     = result.isNotFound,
                        )
                    }
                }
                NetworkResult.Loading -> {
                    if (hasCache) NetworkResult.Success(
                        data = cached.map { it.toFeedSection() }, fromCache = true
                    ) else NetworkResult.Loading
                }
            }
        }

    // ── Section pagination ────────────────────────────────────────────────────

    suspend fun getFeedSection(
        sectionId: String,
        cursor: String? = null,
        limit: Int = 20,
    ): NetworkResult<Pair<List<Media>, String?>> = withContext(Dispatchers.IO) {
        val result = safeApiCall(tag) { api.getFeedSection(sectionId, cursor, limit) }
        return@withContext when (result) {
            is NetworkResult.Success -> {
                val envelope = result.data
                val payload  = envelope.data
                if (!envelope.ok || payload == null) {
                    return@withContext NetworkResult.Error(envelope.error ?: "Section unavailable")
                }
                NetworkResult.Success(payload.items.map { it.toModel() } to payload.nextCursor)
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

    // ── Discover ──────────────────────────────────────────────────────────────

    suspend fun discover(
        mediaType: String = "movie",
        genre: String? = null,
        sortBy: String = "popularity",
        cursor: String? = null,
        limit: Int = 20,
    ): NetworkResult<Pair<List<Media>, String?>> = withContext(Dispatchers.IO) {
        val result = safeApiCall(tag) {
            api.discover(mediaType, genre, sortBy, cursor, limit)
        }
        return@withContext when (result) {
            is NetworkResult.Success -> {
                val envelope = result.data
                val payload  = envelope.data
                if (!envelope.ok || payload == null) {
                    return@withContext NetworkResult.Error(envelope.error ?: "Discover unavailable")
                }
                NetworkResult.Success(payload.items.map { it.toModel() } to payload.nextCursor)
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

    // ── Genres ────────────────────────────────────────────────────────────────

    private val genreCache = mutableMapOf<String, Pair<List<Genre>, Long>>()
    private val genreTtlMs = 24 * 3_600_000L

    suspend fun getGenres(mediaType: String = "movie"): NetworkResult<List<Genre>> =
        withContext(Dispatchers.IO) {
            genreCache[mediaType]?.let { (genres, ts) ->
                if (System.currentTimeMillis() - ts < genreTtlMs) {
                    return@withContext NetworkResult.Success(genres, fromCache = true)
                }
            }
            val result = safeApiCall(tag) { api.getGenres(mediaType) }
            return@withContext when (result) {
                is NetworkResult.Success -> {
                    val envelope = result.data
                    val payload  = envelope.data
                    if (!envelope.ok || payload == null) {
                        return@withContext NetworkResult.Error(envelope.error ?: "Genres unavailable")
                    }
                    val genres = payload.genres.map { it.toModel() }
                    genreCache[mediaType] = genres to System.currentTimeMillis()
                    NetworkResult.Success(genres)
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

    // ── Detail ────────────────────────────────────────────────────────────────

    private val detailMemCache = object : LinkedHashMap<String, Pair<MediaDetail, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Pair<MediaDetail, Long>>) = size > 12
    }
    private val detailMemTtlMs = 30 * 60_000L

    suspend fun getDetail(id: String): NetworkResult<MediaDetail> = withContext(Dispatchers.IO) {
        detailMemCache[id]?.let { (detail, ts) ->
            if (System.currentTimeMillis() - ts < detailMemTtlMs) {
                return@withContext NetworkResult.Success(detail, fromCache = true)
            }
        }

        val roomRow = detailDao.get(id)
        if (roomRow != null && !roomRow.isStale()) {
            detailDao.touch(id)
            return@withContext try {
                val dto   = gson.fromJson(roomRow.detailJson, MediaDetailDto::class.java)
                val model = dto.toModel()
                detailMemCache[id] = model to System.currentTimeMillis()
                NetworkResult.Success(model, fromCache = true)
            } catch (e: Exception) {
                Log.e(tag, "Failed to deserialize cached detail for $id", e)
                fetchDetailFromNetwork(id, roomRow)
            }
        }

        return@withContext fetchDetailFromNetwork(id, roomRow)
    }

    private suspend fun fetchDetailFromNetwork(id: String, staleRow: CachedDetailRow?): NetworkResult<MediaDetail> {
        val result = safeApiCall(tag) { api.getDetail(id) }
        return when (result) {
            is NetworkResult.Success -> {
                val envelope = result.data
                val payload  = envelope.data
                if (!envelope.ok || payload == null) {
                    return NetworkResult.Error(envelope.error ?: "Detail unavailable")
                }
                val cacheTtlMs = envelope.cacheTtlMs ?: 3_600_000L
                val model = payload.toModel()
                detailDao.upsert(CachedDetailRow(
                    mediaId    = id,
                    detailJson = gson.toJson(payload),
                    cacheTtlMs = cacheTtlMs,
                ))
                detailDao.evictToLimit(500)
                detailMemCache[id] = model to System.currentTimeMillis()
                NetworkResult.Success(model)
            }
            is NetworkResult.Error -> {
                if (staleRow != null) {
                    try {
                        val dto = gson.fromJson(staleRow.detailJson, MediaDetailDto::class.java)
                        NetworkResult.Success(dto.toModel(), fromCache = true)
                    } catch (_: Exception) {
                        NetworkResult.Error(
                            message        = result.message,
                            code           = result.code,
                            isNetworkError = result.isNetworkError,
                            isNotFound     = result.isNotFound,
                        )
                    }
                } else {
                    NetworkResult.Error(
                        message        = result.message,
                        code           = result.code,
                        isNetworkError = result.isNetworkError,
                        isNotFound     = result.isNotFound,
                    )
                }
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    // ── Season episodes ───────────────────────────────────────────────────────

    private val seasonMemCache = mutableMapOf<String, Pair<List<Episode>, Long>>()
    private val seasonMemTtlMs = 60 * 60_000L

    suspend fun getSeasonEpisodes(id: String, season: Int): NetworkResult<List<Episode>> =
        withContext(Dispatchers.IO) {
            val cacheKey = "$id/$season"
            seasonMemCache[cacheKey]?.let { (eps, ts) ->
                if (System.currentTimeMillis() - ts < seasonMemTtlMs) {
                    return@withContext NetworkResult.Success(eps, fromCache = true)
                }
            }
            val result = safeApiCall(tag) { api.getSeasonEpisodes(id, season) }
            return@withContext when (result) {
                is NetworkResult.Success -> {
                    val envelope = result.data
                    val payload  = envelope.data
                    if (!envelope.ok || payload == null) {
                        return@withContext NetworkResult.Error(envelope.error ?: "Episodes unavailable")
                    }
                    val eps = payload.episodes.map { it.toModel() }
                    seasonMemCache[cacheKey] = eps to System.currentTimeMillis()
                    NetworkResult.Success(eps)
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

    // ── Shorts ────────────────────────────────────────────────────────────────

    suspend fun getShorts(
        cursor: String? = null,
        limit: Int = 10,
    ): NetworkResult<Triple<List<com.axio.reelz.data.model.ShortVideo>, String?, Boolean>> =
        withContext(Dispatchers.IO) {
            val result = safeApiCall(tag) { api.getShorts(cursor, limit) }
            when (result) {
                is NetworkResult.Success -> {
                    val envelope = result.data
                    val payload  = envelope.data
                    if (!envelope.ok || payload == null) {
                        return@withContext NetworkResult.Error(envelope.error ?: "Shorts unavailable")
                    }
                    NetworkResult.Success(Triple(
                        payload.items.map { it.toModel() },
                        payload.nextCursor,
                        payload.hasMore,
                    ))
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

    // ── Cache maintenance ─────────────────────────────────────────────────────

    suspend fun evictStaleCache() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        feedDao.evictStale(now)
        detailDao.evictStale(now)
    }

    // ── Helper: CachedFeedRow → FeedSection ──────────────────────────────────

    private fun CachedFeedRow.toFeedSection(): FeedSection {
        val type   = object : TypeToken<List<MediaDto>>() {}.type
        val items: List<MediaDto> = try {
            gson.fromJson<List<MediaDto>>(itemsJson, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        return FeedSection(
            id         = sectionId,
            title      = title,
            items      = items.map { it.toModel() },
            hasMore    = hasMore,
            nextCursor = nextCursor,
        )
    }
}
