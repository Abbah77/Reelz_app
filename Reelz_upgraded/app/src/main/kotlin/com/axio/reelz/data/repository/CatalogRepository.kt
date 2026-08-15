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

/**
 * CatalogRepository — owns feed, detail, discover, genres.
 *
 * FIX: ClassCastException was caused by result.map { } being called on
 *      NetworkResult<FeedResponseDto> and trying to coerce it to a different type.
 *      All map() calls replaced with explicit when() branches.
 *
 * FIX: Double-fetch on init — cache check and network refresh now done in one
 *      pass instead of calling getFeed() twice with different parameters.
 */
@Singleton
class CatalogRepository @Inject constructor(
    private val api: ReelzApi,
    private val feedDao: FeedCacheDao,
    private val detailDao: DetailCacheDao,
    private val gson: Gson,
) {
    private val tag = "CatalogRepository"

    // ── Feed ──────────────────────────────────────────────────────────────────

    /**
     * Cache-first feed load.
     *
     * Returns cached data if fresh, fetches from network if stale/empty.
     * On network failure, returns stale cache rather than an error screen.
     */
    suspend fun getFeed(forceRefresh: Boolean = false): NetworkResult<List<FeedSection>> =
        withContext(Dispatchers.IO) {
            val cached = feedDao.getAll()
            val hasCache = cached.isNotEmpty()
            val isStale  = forceRefresh || cached.any { it.isStale() }

            // Serve cache if fresh
            if (hasCache && !isStale) {
                Log.d(tag, "Feed: serving ${cached.size} sections from cache")
                return@withContext NetworkResult.Success(
                    data       = cached.map { it.toFeedSection() },
                    fromCache  = true,
                    cacheAgeMs = System.currentTimeMillis() - (cached.minOfOrNull { it.cachedAtMs } ?: 0L),
                )
            }

            // Fetch from backend
            val result = safeApiCall(tag) { api.getFeed(refresh = if (forceRefresh) 1 else 0) }

            return@withContext when (result) {
                is NetworkResult.Success -> {
                    val dto  = result.data
                    val rows = dto.sections.mapNotNull { sectionDto ->
                        if (sectionDto.items.isEmpty()) null
                        else CachedFeedRow(
                            sectionId  = sectionDto.id,
                            title      = sectionDto.title,
                            itemsJson  = gson.toJson(sectionDto.items),
                            hasMore    = sectionDto.hasMore,
                            nextCursor = sectionDto.nextCursor,
                            cacheTtlMs = dto.cacheTtlMs,
                        )
                    }
                    if (rows.isNotEmpty()) {
                        feedDao.upsertAll(rows)
                        Log.d(tag, "Feed: cached ${rows.size} sections")
                    }
                    NetworkResult.Success<List<FeedSection>>(
                        data = dto.sections.map { it.toModel() },
                    )
                }
                is NetworkResult.Error -> {
                    // Network failed — serve stale cache rather than an error screen
                    if (hasCache) {
                        Log.w(tag, "Feed: network failed, serving stale cache")
                        NetworkResult.Success<List<FeedSection>>(
                            data      = cached.map { it.toFeedSection() },
                            fromCache = true,
                        )
                    } else {
                        NetworkResult.Error(
                            message       = result.message,
                            code          = result.code,
                            isNetworkError = result.isNetworkError,
                            isNotFound    = result.isNotFound,
                        )
                    }
                }
                NetworkResult.Loading -> {
                    if (hasCache) {
                        NetworkResult.Success<List<FeedSection>>(
                            data      = cached.map { it.toFeedSection() },
                            fromCache = true,
                        )
                    } else {
                        NetworkResult.Loading
                    }
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
                val items = result.data.items.map { it.toModel() }
                NetworkResult.Success<Pair<List<Media>, String?>>(items to result.data.nextCursor)
            }
            is NetworkResult.Error -> NetworkResult.Error(
                message       = result.message,
                code          = result.code,
                isNetworkError = result.isNetworkError,
                isNotFound    = result.isNotFound,
            )
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    // ── Discover (Explore screen) ─────────────────────────────────────────────

    suspend fun discover(
        mediaType: String = "movie",
        genre: String? = null,
        language: String? = null,
        sortBy: String = "popularity",
        yearFrom: Int? = null,
        yearTo: Int? = null,
        ratingMin: Float? = null,
        cursor: String? = null,
        limit: Int = 20,
    ): NetworkResult<Pair<List<Media>, String?>> = withContext(Dispatchers.IO) {
        val result = safeApiCall(tag) {
            api.discover(mediaType, genre, language, sortBy, yearFrom, yearTo, ratingMin, cursor, limit)
        }
        return@withContext when (result) {
            is NetworkResult.Success -> {
                val items = result.data.items.map { it.toModel() }
                NetworkResult.Success<Pair<List<Media>, String?>>(items to result.data.nextCursor)
            }
            is NetworkResult.Error -> NetworkResult.Error(
                message       = result.message,
                code          = result.code,
                isNetworkError = result.isNetworkError,
                isNotFound    = result.isNotFound,
            )
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    // ── Genres ────────────────────────────────────────────────────────────────

    private val genreCache  = mutableMapOf<String, Pair<List<Genre>, Long>>()
    private val genreTtlMs  = 24 * 3_600_000L

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
                    val genres = result.data.map { it.toModel() }
                    genreCache[mediaType] = genres to System.currentTimeMillis()
                    NetworkResult.Success<List<Genre>>(genres)
                }
                is NetworkResult.Error -> NetworkResult.Error(
                    message       = result.message,
                    code          = result.code,
                    isNetworkError = result.isNetworkError,
                    isNotFound    = result.isNotFound,
                )
                NetworkResult.Loading -> NetworkResult.Loading
            }
        }

    // ── Detail ────────────────────────────────────────────────────────────────

    private val detailMemCache  = object : LinkedHashMap<String, Pair<MediaDetail, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Pair<MediaDetail, Long>>) = size > 12
    }
    private val detailMemTtlMs = 30 * 60_000L

    suspend fun getDetail(id: String): NetworkResult<MediaDetail> = withContext(Dispatchers.IO) {
        // 1. Memory cache
        detailMemCache[id]?.let { (detail, ts) ->
            if (System.currentTimeMillis() - ts < detailMemTtlMs) {
                return@withContext NetworkResult.Success(detail, fromCache = true)
            }
        }

        // 2. Room cache
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

        // 3. Network
        return@withContext fetchDetailFromNetwork(id, roomRow)
    }

    private suspend fun fetchDetailFromNetwork(
        id: String,
        staleRow: CachedDetailRow?,
    ): NetworkResult<MediaDetail> {
        val result = safeApiCall(tag) { api.getDetail(id) }
        return when (result) {
            is NetworkResult.Success -> {
                val dto   = result.data
                val model = dto.toModel()
                detailDao.upsert(
                    CachedDetailRow(
                        mediaId    = id,
                        detailJson = gson.toJson(dto),
                        cacheTtlMs = dto.cacheTtlMs,
                    )
                )
                detailDao.evictToLimit(500)
                detailMemCache[id] = model to System.currentTimeMillis()
                NetworkResult.Success<MediaDetail>(model)
            }
            is NetworkResult.Error -> {
                // Return stale cache rather than error if available
                if (staleRow != null) {
                    try {
                        val dto = gson.fromJson(staleRow.detailJson, MediaDetailDto::class.java)
                        NetworkResult.Success<MediaDetail>(dto.toModel(), fromCache = true)
                    } catch (_: Exception) {
                        NetworkResult.Error(
                            message       = result.message,
                            code          = result.code,
                            isNetworkError = result.isNetworkError,
                            isNotFound    = result.isNotFound,
                        )
                    }
                } else {
                    NetworkResult.Error(
                        message       = result.message,
                        code          = result.code,
                        isNetworkError = result.isNetworkError,
                        isNotFound    = result.isNotFound,
                    )
                }
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    // ── Season episodes ───────────────────────────────────────────────────────

    private val seasonMemCache  = mutableMapOf<String, Pair<List<Episode>, Long>>()
    private val seasonMemTtlMs  = 60 * 60_000L

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
                    val eps = result.data.episodes.map { it.toModel() }
                    seasonMemCache[cacheKey] = eps to System.currentTimeMillis()
                    NetworkResult.Success<List<Episode>>(eps)
                }
                is NetworkResult.Error -> NetworkResult.Error(
                    message       = result.message,
                    code          = result.code,
                    isNetworkError = result.isNetworkError,
                    isNotFound    = result.isNotFound,
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
