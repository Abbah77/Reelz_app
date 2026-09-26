package com.axio.reelz.media.cache

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * MediaCache — Media3 SimpleCache singleton for streaming playback.
 *
 * Separate from the download cache (DownloadManagerCoordinator.getDownloadCache):
 *  - This cache: ephemeral, evicted by LRU at 100 MB. Used while streaming.
 *  - Download cache: permanent, no eviction. Used for offline playback.
 *
 * PlayerManager references this for CacheDataSource construction.
 */
@UnstableApi
object MediaCache {
    private const val STREAMING_CACHE_SIZE_BYTES = 100L * 1024 * 1024  // 100 MB

    @Volatile private var _cache: SimpleCache? = null

    fun get(context: Context): SimpleCache =
        _cache ?: synchronized(this) {
            _cache ?: SimpleCache(
                File(context.cacheDir, "reelz_video_cache"),
                LeastRecentlyUsedCacheEvictor(STREAMING_CACHE_SIZE_BYTES),
            ).also { _cache = it }
        }

    fun release() {
        _cache?.release()
        _cache = null
    }
}
