package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.core.database.CachedSearchRow
import com.axio.reelz.core.database.RecentSearchDao
import com.axio.reelz.core.database.RecentSearchRow
import com.axio.reelz.core.database.SearchCacheDao
import com.axio.reelz.core.network.NetworkResult
import com.axio.reelz.core.network.safeApiCall
import com.axio.reelz.data.dto.MediaDto
import com.axio.reelz.data.model.Media
import com.axio.reelz.data.remote.api.ReelzApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val api: ReelzApi,
    private val searchDao: SearchCacheDao,
    private val recentSearchDao: RecentSearchDao,
    private val gson: Gson,
) {
    private val tag = "SearchRepository"

    suspend fun search(
        query: String,
        mediaType: String? = null,
        cursor: String? = null,
    ): NetworkResult<Pair<List<Media>, String?>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext NetworkResult.Success<Pair<List<Media>, String?>>(emptyList<Media>() to null)
        }

        if (cursor == null) {
            val cached = searchDao.get(query)
            if (cached != null && !cached.isStale()) {
                val type  = object : TypeToken<List<MediaDto>>() {}.type
                val items = try {
                    gson.fromJson<List<MediaDto>>(cached.resultsJson, type)?.map { it.toModel() } ?: emptyList()
                } catch (_: Exception) { emptyList() }
                Log.d(tag, "Search '$query': serving ${items.size} from cache")
                return@withContext NetworkResult.Success<Pair<List<Media>, String?>>(
                    items to cached.nextCursor, fromCache = true
                )
            }
        }

        val result = safeApiCall(tag) { api.search(query, mediaType, cursor) }
        return@withContext when (result) {
            is NetworkResult.Success -> {
                val dto   = result.data
                val items = dto.items.map { it.toModel() }

                if (cursor == null && items.isNotEmpty()) {
                    searchDao.upsert(CachedSearchRow(
                        query       = query,
                        resultsJson = gson.toJson(dto.items),
                        hasMore     = dto.hasMore,
                        nextCursor  = dto.nextCursor,
                        cacheTtlMs  = dto.cacheTtlMs,
                    ))
                    searchDao.evictToLimit()
                }
                Log.d(tag, "Search '$query': got ${items.size} from network")
                NetworkResult.Success<Pair<List<Media>, String?>>(items to dto.nextCursor)
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
