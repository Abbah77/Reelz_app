package com.axio.reelz.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.axio.reelz.data.local.CachedMediaDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily background eviction of Room's media cache.
 *
 * Runs once per day via WorkManager. Enforces:
 *   - Search-sourced items capped at 50 (evict oldest by lastAccessedAt)
 *   - Total row cap of 10,000 (evict globally oldest by lastAccessedAt)
 *
 * Scheduled in ReelzApp.onCreate() with KEEP policy — only one instance
 * ever queues, preventing duplicate daily runs after reinstalls.
 */
@HiltWorker
class EvictionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cachedMediaDao: CachedMediaDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Cap search-opened items at 50
            cachedMediaDao.evictOldestSearch(keepCount = 50)

            // Enforce 10,000 total row cap
            val total = cachedMediaDao.count()
            if (total > 10_000) {
                cachedMediaDao.evictOldest(total - 10_000)
            }

            Result.success()
        } catch (e: Exception) {
            // Retry tomorrow — don't crash silently
            Result.retry()
        }
    }
}
