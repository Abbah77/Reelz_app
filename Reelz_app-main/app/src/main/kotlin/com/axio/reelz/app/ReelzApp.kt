package com.axio.reelz.app

import com.axio.reelz.BuildConfig

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.core.database.DownloadDao
import com.axio.reelz.data.model.DownloadStatus
import com.axio.reelz.data.repository.ConfigRepository
import com.axio.reelz.data.repository.UserRepository
import com.axio.reelz.media.download.ReelzDownloadService
import com.axio.reelz.core.workers.CacheEvictionWorker
import com.axio.reelz.core.workers.ConfigSyncWorker
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class ReelzApp : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject lateinit var configRepository: ConfigRepository
    @Inject lateinit var userSessionRepository: UserRepository
    @Inject lateinit var downloadDao: DownloadDao
    @Inject lateinit var adEngine: AdEngine
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(true)
            setCustomKey("versionName", BuildConfig.VERSION_NAME)
            setCustomKey("versionCode", BuildConfig.VERSION_CODE)
        }

        appScope.launch {
            // 1. Load app config (Room first, then backend in background)
            configRepository.init()

            // 2. Load user session from Room (instant, no network)
            userSessionRepository.init()

            // 3. Init ads (needs config for SDK key)
            adEngine.initialize(this@ReelzApp)
            adEngine.loadPersistedCounters()
        }

        // Periodic workers
        ConfigSyncWorker.schedule(this)
        CacheEvictionWorker.schedule(this)

        // Recover downloads interrupted by a crash or force-quit
        recoverStuckDownloads()
    }

    private fun recoverStuckDownloads() {
        appScope.launch {
            try {
                val stuck = downloadDao.getByStatus(DownloadStatus.QUEUED.name) +
                            downloadDao.getByStatus(DownloadStatus.DOWNLOADING.name)
                stuck.forEach { row ->
                    downloadDao.markPaused(row.id)
                    ReelzDownloadService.resumeAll(this@ReelzApp)
                }
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(300L * 1024 * 1024)  // 300 MB — images are now full URLs from backend
                    .build()
            }
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
            }
            .respectCacheHeaders(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .crossfade(300)
            .build()
}
