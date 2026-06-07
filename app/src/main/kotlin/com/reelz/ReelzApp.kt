package com.reelz

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.reelz.data.local.DownloadDao
import com.reelz.data.model.DownloadStatus
import com.reelz.service.DownloadService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class ReelzApp : Application(), ImageLoaderFactory {

    @Inject lateinit var downloadDao: DownloadDao

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        recoverStuckDownloads()
    }

    /**
     * UPGRADE P20 / BUG 10: On app start, scan for downloads that were stuck in
     * QUEUED or DOWNLOADING state (e.g. service was killed by OS).
     * Restart them so they never get permanently stuck.
     */
    private fun recoverStuckDownloads() {
        appScope.launch {
            try {
                val queued = downloadDao.getByStatus(DownloadStatus.QUEUED.name)
                val downloading = downloadDao.getByStatus(DownloadStatus.DOWNLOADING.name)
                val stuck = (queued + downloading)
                stuck.forEach { item ->
                    // Mark as needing re-resolve (CDN token likely expired)
                    downloadDao.markPaused(item.id)
                    DownloadService.start(this@ReelzApp, item.id)
                }
            } catch (_: Exception) {}
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
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .crossfade(300)
            .build()
}
