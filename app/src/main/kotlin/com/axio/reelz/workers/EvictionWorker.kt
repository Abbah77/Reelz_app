package com.axio.reelz.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.axio.reelz.data.local.CachedMediaDao
import com.axio.reelz.data.local.InfiniteScrollEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily background eviction of Room's metadata cache.
 *
 * Runs once per day via WorkManager (KEEP policy — never duplicates).
 *
 * Enforces the soft limits defined in InfiniteScrollEngine:
 *   - Search-sourced items capped at SEARCH_CACHE_CAP (200)
 *   - Total row cap of CACHE_TARGET (10,000)
 *
 * WHY NOT HARD LIMITS:
 *   The engine uses soft limits with 500-row headroom so that:
 *   a) A batch insert of 20 items never triggers a disruptive mid-insert eviction
 *   b) The last few items in a TMDB page don't cause an off-by-one crash
 *   c) Eviction is amortized (once/day) rather than on every insert
 *
 * The daily worker is the safety net — the engine's enforceSoftLimit() handles
 * day-to-day cleanup incrementally on each TMDB page fetch.
 */
@HiltWorker
class EvictionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cachedMediaDao: CachedMediaDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Cap search-opened items at SEARCH_CACHE_CAP
            val searchCount = cachedMediaDao.countBySource("search")
            if (searchCount > InfiniteScrollEngine.SEARCH_CACHE_CAP) {
                cachedMediaDao.evictOldestSearch(InfiniteScrollEngine.SEARCH_CACHE_CAP)
            }

            // Enforce CACHE_TARGET total row cap
            val total = cachedMediaDao.count()
            if (total > InfiniteScrollEngine.CACHE_TARGET) {
                val toEvict = total - InfiniteScrollEngine.CACHE_TARGET
                cachedMediaDao.evictOldest(toEvict)
            }

            Result.success()
        } catch (e: Exception) {
            // Retry tomorrow — not a fatal failure
            Result.retry()
        }
    }
}
