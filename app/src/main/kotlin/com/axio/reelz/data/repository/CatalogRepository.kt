package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.core.network.NetworkResult
import com.axio.reelz.core.network.map
import com.axio.reelz.core.network.safeApiCall
import com.axio.reelz.core.database.*
import com.axio.reelz.data.model.*
import com.axio.reelz.data.remote.api.ReelzApi
import com.axio.reelz.data.dto.MediaDetailDto
import com.axio.reelz.data.dto.MediaDto
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CatalogRepository — owns feed, detail, discover, genres.
 * Renamed from MediaRepository; search/library concerns live in their own repos.
 *
 * Dependency direction: CatalogRepository → (Room | Retrofit). Never touches UI.
 */
@Singleton
class CatalogRepository @Inject constructor(
    private val api: ReelzApi,
    private val feedDao: FeedCacheDao,
    private val detailDao: DetailCacheDao,
    private val gson: Gson,
) {
    private val tag = "CatalogRepository"

    // ── Feed — cache-first, background refresh ────────────────────────────────

    /**
     * Cache-first feed load.
     *
     * 1. Return cached rows immediately if not stale → instant render.
     * 2. Refresh from backend in background if stale (or forceRefresh).
     * 3. Return network result so caller can decide to merge or replace.
     *
     * The caller (ViewModel) calls this once and merges both emissions.
     */
    suspend fun getFeed(forceRefresh: Boolean = false): NetworkResult<List<FeedSection>> =
        withContext(Dispatchers.IO) {
            val cached = feedDao.getAll()
            val hasCache = cached.isNotEmpty()
            val isStale  = forceRefresh || cached.any { it.isStale() }

            // Return cache if fresh
            if (hasCache && !isStale) {
                Log.d(tag, "Feed: serving ${cached.size} sections from cache")
                return@withContext NetworkResult.Success(
                    data = cached.map { it.toModel() },
                    fromCache = true,
                    cacheAgeMs = System.currentTimeMillis() - (cached.minOfOrNull { it.cachedAtMs } ?: 0L),
                )
            }

            // Fetch from backend
            val result = safeApiCall(tag) { api.getFeed(refresh = if (forceRefresh) 1 else 0) }
            when (result) {
                is NetworkResult.Success -> {
                    val dto = result.data
                    val rows = dto.sections.map { sectionDto ->
                        CachedFeedRow(
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
                        Log.d(tag, "Feed: cached ${rows.size} sections from network")
                    }
                    NetworkResult.Success(data = dto.sections.map { it.toModel() })
                }
                is NetworkResult.Error -> {
                    // Network failed — serve stale cache rather than an error screen
                    if (hasCache) {
                        Log.w(tag, "Feed: network failed, serving stale cache (${result.message})")
                        NetworkResult.Success(
                            data = cached.map { it.toModel() },
                            fromCache = true,
                        )
                    } else {
                        result
                    }
                }
                NetworkResult.Loading -> result
            }
        }

    // ── Section pagination ────────────────────────────────────────────────────

    suspend fun getFeedSection(
        sectionId: String,
        cursor: String? = null,
        limit: Int = 20,
    ): NetworkResult<Pair<List<Media>, String?>> = withContext(Dispatchers.IO) {
        val result = safeApiCall(tag) { api.getFeedSection(sectionId, cursor, limit) }
        when (result) {
            is NetworkResult.Success -> {
                val items = result.data.items.map { it.toModel() }
                NetworkResult.Success(items to result.data.nextCursor)
            }
            else -> result.map { emptyList<Media>() to null }
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
        when (result) {
            is NetworkResult.Success -> {
                val items = result.data.items.map { it.toModel() }
                NetworkResult.Success(items to result.data.nextCursor)
            }
            else -> result.map { emptyList<Media>() to null }
        }
    }

    // ── Genres — backed by a short-lived cache ─────────────────────────────────

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
            when (result) {
                is NetworkResult.Success -> {
                    val genres = result.data.map { it.toModel() }
                    genreCache[mediaType] = genres to System.currentTimeMillis()
                    NetworkResult.Success(genres)
                }
                else -> result.map { emptyList() }
            }
        }

    // ── Detail — in-memory + Room cache ──────────────────────────────────────

    // Tiny in-memory cache: last 12 items, session-only
    private val detailMemCache = object : LinkedHashMap<String, Pair<MediaDetail, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Pair<MediaDetail, Long>>) = size > 12
    }
    private val detailMemTtlMs = 30 * 60_000L  // 30 min

    suspend fun getDetail(id: String): NetworkResult<MediaDetail> = withContext(Dispatchers.IO) {
        // 1. Memory cache (fastest)
        detailMemCache[id]?.let { (detail, ts) ->
            if (System.currentTimeMillis() - ts < detailMemTtlMs) {
                return@withContext NetworkResult.Success(detail, fromCache = true)
            }
        }

        // 2. Room cache
        val roomRow = detailDao.get(id)
        if (roomRow != null && !roomRow.isStale()) {
            detailDao.touch(id)
            val dto = gson.fromJson(roomRow.detailJson, MediaDetailDto::class.java)
            val model = dto.toModel()
            detailMemCache[id] = model to System.currentTimeMillis()
            return@withContext NetworkResult.Success(model, fromCache = true)
        }

        // 3. Network
        val result = safeApiCall(tag) { api.getDetail(id) }
        when (result) {
            is NetworkResult.Success -> {
                val dto = result.data
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
                NetworkResult.Success(model)
            }
            is NetworkResult.Error -> {
                // Serve stale Room cache rather than error
                if (roomRow != null) {
                    val dto = gson.fromJson(roomRow.detailJson, MediaDetailDto::class.java)
                    NetworkResult.Success(dto.toModel(), fromCache = true)
                } else result
            }
            else -> result
        }
    }

    // ── Season episodes ───────────────────────────────────────────────────────

    // Simple in-memory cache for seasons — they rarely change
    private val seasonMemCache = mutableMapOf<String, Pair<List<Episode>, Long>>()
    private val seasonMemTtlMs = 60 * 60_000L  // 1 h

    suspend fun getSeasonEpisodes(id: String, season: Int): NetworkResult<List<Episode>> =
        withContext(Dispatchers.IO) {
            val cacheKey = "$id/$season"
            seasonMemCache[cacheKey]?.let { (eps, ts) ->
                if (System.currentTimeMillis() - ts < seasonMemTtlMs) {
                    return@withContext NetworkResult.Success(eps, fromCache = true)
                }
            }
            val result = safeApiCall(tag) { api.getSeasonEpisodes(id, season) }
            when (result) {
                is NetworkResult.Success -> {
                    val eps = result.data.episodes.map { it.toModel() }
                    seasonMemCache[cacheKey] = eps to System.currentTimeMillis()
                    NetworkResult.Success(eps)
                }
                else -> result.map { emptyList() }
            }
        }

    // ── Cache maintenance ─────────────────────────────────────────────────────

    suspend fun evictStaleCache() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        feedDao.evictStale(now)
        detailDao.evictStale(now)
    }

    // Helper to convert CachedFeedRow back to FeedSection
    private fun CachedFeedRow.toModel(): FeedSection {
        val type = object : TypeToken<List<MediaDto>>() {}.type
        val items: List<MediaDto> = try {
            gson.fromJson(itemsJson, type) ?: emptyList()
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
