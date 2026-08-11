package com.axio.reelz.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.axio.reelz.data.repository.ConfigRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * ConfigSyncWorker — periodic background sync of app config from YOUR backend.
 * Runs every 6 hours. The ConfigRepository handles caching and staleness checks.
 */
@HiltWorker
class ConfigSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val configRepository: ConfigRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            configRepository.refresh()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "reelz_config_sync"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ConfigSyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }
}
