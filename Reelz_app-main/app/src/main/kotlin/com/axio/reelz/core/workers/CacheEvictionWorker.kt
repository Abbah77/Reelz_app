package com.axio.reelz.core.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.axio.reelz.core.database.DetailCacheDao
import com.axio.reelz.core.database.FeedCacheDao
import com.axio.reelz.core.database.SearchCacheDao
import com.axio.reelz.core.database.WatchProgressDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * CacheEvictionWorker — daily cleanup of the smart cache.
 *
 * Does only what's needed:
 *  1. Evict stale feed sections (past their TTL)
 *  2. Evict stale detail rows
 *  3. Trim detail cache to 500 rows max
 *  4. Trim search cache to 100 queries max
 *  5. Trim watch progress to 500 entries
 *
 * Runs once per day via WorkManager (KEEP policy — never duplicates).
 */
@HiltWorker
class CacheEvictionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val feedDao: FeedCacheDao,
    private val detailDao: DetailCacheDao,
    private val searchDao: SearchCacheDao,
    private val watchProgressDao: WatchProgressDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()
            feedDao.evictStale(now)
            detailDao.evictStale(now)
            detailDao.evictToLimit(500)
            searchDao.evictToLimit()
            watchProgressDao.trimToLimit()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "reelz_cache_eviction"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CacheEvictionWorker>(1, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build()
            )
        }
    }
}
