package com.reelz.scanner

import com.reelz.data.model.StreamResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache for resolved stream results.
 * TTL: 5 minutes — CDN token URLs are typically valid for 5–30 minutes.
 *
 * Key: "{tmdbId}_{mediaType}_{season}_{episode}"
 *
 * This eliminates redundant WebView scans when the user opens the same
 * Detail screen multiple times, or when the download sheet needs the same
 * stream that the player already resolved.
 */
@Singleton
class StreamResultCache @Inject constructor() {

    private data class Entry(val result: StreamResult, val timestamp: Long)

    private val map = HashMap<String, Entry>()
    private val TTL_MS = 5 * 60 * 1000L  // 5 minutes

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
    }

    fun remove(key: String) { map.remove(key) }

    fun key(tmdbId: Int, mediaType: com.reelz.data.model.MediaType, season: Int, episode: Int) =
        "${tmdbId}_${mediaType.name}_${season}_${episode}"
}
