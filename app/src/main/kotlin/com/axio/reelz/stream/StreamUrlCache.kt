package com.axio.reelz.stream

import android.util.Log
import com.axio.reelz.data.model.StreamResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * StreamUrlCache — short-lived in-memory cache for resolved stream URLs.
 *
 * WHY short TTL:
 *   Stream URLs from proxy/scraper backends (Cloudflare-fronted CDNs, direct
 *   extractor links, etc.) typically expire aggressively — some in as little as
 *   5-10 minutes, many in 15-30 min, a few in hours. We conservatively cache for
 *   4 minutes so:
 *     - User pauses → checks WhatsApp → comes back in 2 min  → instant replay ✓
 *     - User switches to cast → comes back in 3 min           → instant replay ✓
 *     - User leaves > 4 min                                   → fresh fetch    ✓
 *     - URL expires mid-cache (rare but possible)             → handleError()
 *       instantly tries the cached fallback ladder before re-fetching backend  ✓
 *
 * THREAD SAFETY: all reads/writes are @Synchronized — safe to call from any
 * coroutine dispatcher.
 *
 * MEMORY: entries are tiny (a few URLs + maps). Even with 10 cached items this
 * is well under 1 KB total. No eviction policy needed — users rarely juggle more
 * than 2-3 streams in a session.
 */
@Singleton
class StreamUrlCache @Inject constructor() {

    // Cache TTL: 4 minutes. Aggressive enough that URLs won't expire inside the
    // window on most providers, short enough to always fetch fresh on true return.
    private val TTL_MS = 4 * 60 * 1_000L

    private data class CacheEntry(
        val result: StreamResult,
        val storedAt: Long = System.currentTimeMillis(),
    ) {
        fun isAlive(ttlMs: Long) = System.currentTimeMillis() - storedAt < ttlMs
    }

    // Key: "tmdbId|type|season|episode"  e.g. "123456|movie|0|0" or "654321|tv|2|4"
    private val store = mutableMapOf<String, CacheEntry>()

    private fun key(tmdbId: Int, mediaType: String, season: Int, episode: Int) =
        "$tmdbId|$mediaType|$season|$episode"

    /**
     * Store a resolved [StreamResult] for the given content identity.
     * Overwrites any existing entry (fresh resolve always wins).
     */
    @Synchronized
    fun put(tmdbId: Int, mediaType: String, season: Int, episode: Int, result: StreamResult) {
        val k = key(tmdbId, mediaType, season, episode)
        store[k] = CacheEntry(result)
        Log.d("StreamCache", "Cached stream for $k (${result.qualities.size} fallbacks)")
    }

    /**
     * Returns a cached [StreamResult] if one exists AND it is still within TTL.
     * Returns null if no entry, or entry has expired.
     */
    @Synchronized
    fun get(tmdbId: Int, mediaType: String, season: Int, episode: Int): StreamResult? {
        val k = key(tmdbId, mediaType, season, episode)
        val entry = store[k] ?: return null
        if (!entry.isAlive(TTL_MS)) {
            store.remove(k)
            Log.d("StreamCache", "Cache expired for $k — will re-fetch")
            return null
        }
        val ageSeconds = (System.currentTimeMillis() - entry.storedAt) / 1_000
        Log.d("StreamCache", "Cache HIT for $k (age: ${ageSeconds}s)")
        return entry.result
    }

    /**
     * Forcibly invalidate the cache for a specific content item.
     * Called when a URL is confirmed dead (error handler exhausted all fallbacks).
     */
    @Synchronized
    fun invalidate(tmdbId: Int, mediaType: String, season: Int, episode: Int) {
        val k = key(tmdbId, mediaType, season, episode)
        if (store.remove(k) != null) {
            Log.d("StreamCache", "Invalidated cache for $k")
        }
    }

    /** Clear everything — e.g. on user sign-out. */
    @Synchronized
    fun clear() { store.clear() }
}
