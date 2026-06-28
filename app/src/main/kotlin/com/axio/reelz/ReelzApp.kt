package com.axio.reelz

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.axio.reelz.data.local.DownloadDao
import com.axio.reelz.data.model.DownloadStatus
import com.axio.reelz.data.repository.UserSessionRepository
import com.axio.reelz.remoteconfig.ConfigSyncWorker
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.service.DownloadService
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
    @Inject lateinit var remoteConfig: RemoteConfigRepository
    @Inject lateinit var adEngine: AdEngine
    @Inject lateinit var userSessionRepository: UserSessionRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Load cache first so ad config (sdk key, toggles, ad unit ids) is
        // available before the ad SDK initializes.
        appScope.launch {
            remoteConfig.loadLocalConfig()

            // Initialize ad engine — starts SDK + preloads all ad formats.
            // AdEngine itself checks ads.enabled and the AppLovin SDK key,
            // so this is a safe no-op until both are configured.
            adEngine.initialize(this@ReelzApp)

            // Load any previously cached premium session — instant, local only.
            // PremiumGate is ready with the correct state before any screen renders.
            userSessionRepository.loadLocalSession()

            // Re-resolve the grant in the background (config refreshes every 6h
            // via ConfigSyncWorker, so a manual_grants edit you push to GitHub
            // reaches a signed-in user's device automatically over time too).
            userSessionRepository.refreshCurrentSession()
        }

        // Periodic background refresh every 6 hours.
        ConfigSyncWorker.schedule(this)

        // ── Recover downloads stuck in QUEUED/DOWNLOADING state ──────────────
        recoverStuckDownloads()
    }

    private fun recoverStuckDownloads() {
        appScope.launch {
            try {
                val queued      = downloadDao.getByStatus(DownloadStatus.QUEUED.name)
                val downloading = downloadDao.getByStatus(DownloadStatus.DOWNLOADING.name)
                (queued + downloading).forEach { item ->
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
