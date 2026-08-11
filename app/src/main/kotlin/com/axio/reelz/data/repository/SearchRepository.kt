package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.core.network.NetworkResult
import com.axio.reelz.core.network.safeApiCall
import com.axio.reelz.data.local.CachedSearchRow
import com.axio.reelz.data.local.RecentSearchDao
import com.axio.reelz.data.local.RecentSearchRow
import com.axio.reelz.data.local.SearchCacheDao
import com.axio.reelz.data.model.Media
import com.axio.reelz.data.dto.MediaDto
import com.axio.reelz.data.remote.api.ReelzApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SearchRepository — search results + recent search history.
 * Split out of MediaRepository per the restructure plan.
 *
 * Dependency direction: SearchRepository → (Room | Retrofit). Never touches UI.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val api: ReelzApi,
    private val searchDao: SearchCacheDao,
    private val recentSearchDao: RecentSearchDao,
    private val gson: Gson,
) {
    private val tag = "SearchRepository"

    // ── Search — local cache-first then network ───────────────────────────────

    suspend fun search(
        query: String,
        mediaType: String? = null,
        cursor: String? = null,
    ): NetworkResult<Pair<List<Media>, String?>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext NetworkResult.Success(emptyList<Media>() to null)

        // Check local cache first (5 min TTL)
        if (cursor == null) {
            val cached = searchDao.get(query)
            if (cached != null && !cached.isStale()) {
                val type = object : TypeToken<List<MediaDto>>() {}.type
                val items: List<MediaDto> = gson.fromJson(cached.resultsJson, type)
                return@withContext NetworkResult.Success(
                    items.map { it.toModel() } to cached.nextCursor,
                    fromCache = true,
                )
            }
        }

        val result = safeApiCall(tag) { api.search(query, mediaType, cursor) }
        when (result) {
            is NetworkResult.Success -> {
                val items = result.data.items
                // Cache only first page of results
                if (cursor == null && items.isNotEmpty()) {
                    searchDao.upsert(
                        CachedSearchRow(
                            query       = query,
                            resultsJson = gson.toJson(items),
                            hasMore     = result.data.hasMore,
                            nextCursor  = result.data.nextCursor,
                            cacheTtlMs  = result.data.cacheTtlMs,
                        )
                    )
                    searchDao.evictToLimit()
                }
                NetworkResult.Success(items.map { it.toModel() } to result.data.nextCursor)
            }
            else -> result.map { emptyList<Media>() to null }
        }
    }

    // ── Recent searches ───────────────────────────────────────────────────────

    fun observeRecentSearches() = recentSearchDao.observe()

    suspend fun saveRecentSearch(query: String) = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext
        recentSearchDao.insert(RecentSearchRow(query.trim()))
        recentSearchDao.trimToLimit()
    }

    suspend fun deleteRecentSearch(query: String) = withContext(Dispatchers.IO) {
        recentSearchDao.delete(query)
    }

    suspend fun clearRecentSearches() = withContext(Dispatchers.IO) {
        recentSearchDao.clear()
    }
}
