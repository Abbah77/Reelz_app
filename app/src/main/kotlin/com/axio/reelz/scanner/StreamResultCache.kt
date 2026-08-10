package com.axio.reelz.scanner

import com.axio.reelz.data.model.StreamResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory LRU-style cache for resolved stream results.
 *
 * TTL: 8 minutes (CDN token URLs are typically valid for 5–30 min).
 * We extended from 5 → 8 min to survive longer detail-screen browsing.
 *
 * Key: "{tmdbId}_{mediaType}_{season}_{episode}"
 *
 * The companion to StreamEngine's prefetch system — if a result is in here
 * when the player calls resolve(), playback is instant (0 network calls).
 */
@Singleton
class StreamResultCache @Inject constructor() {

    private data class Entry(val result: StreamResult, val timestamp: Long)

    private val map = LinkedHashMap<String, Entry>(8, 0.75f, true)  // access-ordered LRU
    private val TTL_MS   = 8 * 60 * 1000L   // 8 minutes (was 5)
    private val MAX_SIZE = 12               // keep up to 12 entries (episode binge)

    fun get(key: String): StreamResult? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            map.remove(key)
            return null
        }
        return entry.result
    }

    fun put(key: String, result: StreamResult) {
        map[key] = Entry(result, System.currentTimeMillis())
        // Evict oldest if over capacity
        while (map.size > MAX_SIZE) {
            map.entries.iterator().let { it.next(); it.remove() }
        }
    }

    fun remove(key: String) { map.remove(key) }

    /** Poke an entry to refresh its LRU order without changing data. */
    fun touch(key: String) { get(key) }

    fun key(
        tmdbId: Int,
        mediaType: com.axio.reelz.data.model.MediaType,
        season: Int,
        episode: Int,
    ) = "${tmdbId}_${mediaType.name}_${season}_${episode}"

    /** Pre-warm next episode while current one is playing (TV shows). */
    fun hasKey(key: String) = map.containsKey(key) && get(key) != null
}
