package com.axio.reelz.data.local

import android.util.LruCache
import com.axio.reelz.data.model.*
import com.axio.reelz.data.remote.api.TmdbApi
import com.axio.reelz.data.repository.toMedia
import com.axio.reelz.data.repository.toCached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * InfiniteScrollEngine — the bulletproof local-first infinite catalog.
 *
 * Design contract:
 *
 *   1. LOCAL FIRST: Always serve from Room cache first. Network is a refill
 *      mechanism, not the primary data source.
 *
 *   2. SEAMLESS UX: The UI receives a consistent CatalogPage regardless of
 *      whether items came from Room or TMDB. It never asks "is this cache?".
 *
 *   3. RESUMABLE PAGINATION: CatalogPageCursor persists the next TMDB page
 *      across cold restarts so the scroll never repeats content.
 *
 *   4. SOFT LIMITS: ~10K metadata target (allows ±500 headroom).
 *      ~350MB image target managed entirely by Coil.
 *
 *   5. INDEPENDENT PIPELINES: Metadata pagination continues regardless of
 *      image load state. Coil's prefetch window handles image preloading.
 *
 *   6. MUTUAL EXCLUSION: Only one TMDB fetch in flight at a time per media
 *      type — prevents duplicate pages from parallel calls.
 *
 * Page size = 12: Coil-friendly. Small enough that images for the previous
 * page finish loading before the next page appears.
 *
 * Prefetch window: 24 items ahead (2 pages). Coil preloads these while the
 * user looks at items 1–12, so items 13–36 are already cached when scrolled.
 */
@Singleton
class InfiniteScrollEngine @Inject constructor(
    private val cachedMediaDao: CachedMediaDao,
    private val cursorDao: CatalogPageCursorDao,
    private val api: TmdbApi,
) {
    companion object {
        const val PAGE_SIZE = 12          // items per page (Coil-friendly)
        const val CACHE_TARGET = 10_000   // soft metadata row target
        const val CACHE_SOFT_LIMIT = 10_500 // trigger eviction above this
        const val SEARCH_CACHE_CAP = 200  // max search-sourced items
    }

    // Prevent parallel TMDB fetches for the same media type
    private val fetchMutexes = mapOf(
        "movie" to Mutex(),
        "tv"    to Mutex(),
    )

    // In-memory dedup: tmdbIds already served this session (cleared on app restart)
    private val servedIds = HashSet<Int>(1024)

    // Session-scoped offset cursor: how many items already served from Room
    // (separate from TMDB page cursor which is persisted)
    private var roomOffset = 0

    /**
     * Returns the next page of items for the infinite feed.
     *
     * Strategy:
     *   Tier 1 → Room cache (offline-first, 0ms)
     *   Tier 2 → TMDB (only when cache is exhausted or thin)
     *   Tier 3 → Exhausted (nothing left anywhere)
     *
     * The caller must pass the set of already-displayed tmdbIds so dedup
     * is applied correctly even across multiple ViewModel sessions.
     */
    suspend fun nextPage(
        mediaType: String = "movie",
        excludeIds: Set<Int> = emptySet(),
    ): CatalogPage = withContext(Dispatchers.IO) {

        val allExcluded = excludeIds + servedIds

        // ── Tier 1: Serve from Room cache ─────────────────────────────────────
        val cacheItems = fetchFromCache(mediaType, allExcluded)
        if (cacheItems.size >= PAGE_SIZE) {
            cacheItems.forEach { servedIds.add(it.tmdbId) }
            return@withContext CatalogPage.FromCache(
                items = cacheItems.take(PAGE_SIZE),
                pageIndex = roomOffset / PAGE_SIZE,
            )
        }

        // ── Tier 2: Cache thin — refill from TMDB ────────────────────────────
        val mutex = fetchMutexes[mediaType] ?: Mutex()
        val networkItems = mutex.withLock {
            fetchFromTmdb(mediaType, allExcluded)
        }

        if (networkItems.isNotEmpty()) {
            networkItems.forEach { servedIds.add(it.tmdbId) }
            return@withContext CatalogPage.FromNetwork(
                items = networkItems.take(PAGE_SIZE),
                pageIndex = roomOffset / PAGE_SIZE + 1,
            )
        }

        // ── Tier 3: Truly exhausted ───────────────────────────────────────────
        CatalogPage.Exhausted
    }

    /**
     * Pull items from Room, excluding already-displayed IDs.
     * Scans in batches of 3× page size then filters in Kotlin — avoids the
     * NOT IN(?) performance cliff for large exclusion sets.
     */
    private suspend fun fetchFromCache(
        mediaType: String,
        excludeIds: Set<Int>,
    ): List<Media> {
        val result = mutableListOf<CachedMedia>()
        var offset = roomOffset
        val batchSize = PAGE_SIZE * 3

        while (result.size < PAGE_SIZE) {
            val batch = cachedMediaDao.getPopularPage(limit = batchSize, offset = offset)
            if (batch.isEmpty()) break

            val filtered = batch.filter { item ->
                item.tmdbId !in excludeIds && item.source == "catalog"
            }
            result.addAll(filtered)
            offset += batchSize

            // If the raw batch was smaller than batchSize, cache is exhausted
            if (batch.size < batchSize) break
        }

        if (result.isNotEmpty()) {
            roomOffset += result.size
        }

        return result.take(PAGE_SIZE).map { it.toMedia() }
    }

    /**
     * Fetch one page from TMDB, write to Room, and advance the cursor.
     * Alternates movie/TV content for variety (even page → movies, odd → TV
     * when mediaType is "mixed"). For explicit types, uses that type.
     *
     * Includes exponential-backoff retry for TMDB rate limits (40 req/10s).
     */
    private suspend fun fetchFromTmdb(
        mediaType: String,
        excludeIds: Set<Int>,
    ): List<Media> {
        val cursor = cursorDao.get(mediaType) ?: CatalogPageCursor(mediaType = mediaType, nextPage = 1)
        val page = cursor.nextPage

        val retryDelaysMs = longArrayOf(0L, 500L, 1500L, 3000L)

        for (delayMs in retryDelaysMs) {
            if (delayMs > 0) delay(delayMs)
            try {
                val rawItems: List<Media> = when {
                    mediaType == "tv" ->
                        api.getPopularTv(page).results.map { it.toMedia(MediaType.TV) }
                    page % 2 == 0 ->
                        api.getTrendingMovies(page).results.map { it.toMedia(MediaType.MOVIE) }
                    else ->
                        api.getPopularMovies(page).results.map { it.toMedia(MediaType.MOVIE) }
                }

                if (rawItems.isEmpty()) {
                    // TMDB has no more pages — reset cursor to page 1 so it cycles
                    cursorDao.upsert(CatalogPageCursor(mediaType = mediaType, nextPage = 1))
                    return emptyList()
                }

                // Advance cursor BEFORE writing (atomic in case of crash)
                cursorDao.upsert(CatalogPageCursor(mediaType = mediaType, nextPage = page + 1))

                // Write to Room (upsert — safe to call even if rows exist)
                val now = System.currentTimeMillis()
                val toCache = rawItems.map { media ->
                    media.toCached().copy(
                        source        = "catalog",
                        catalogPage   = page,
                        lastAccessedAt = now,
                        cachedAt      = now,
                    )
                }
                cachedMediaDao.insertAll(toCache)

                // Soft eviction if we've grown past the limit
                enforceSoftLimit()

                // Return only the items not already displayed
                val fresh = rawItems.filter { it.tmdbId !in excludeIds }
                if (fresh.isNotEmpty()) return fresh

                // All items were dupes — try next page immediately
                return fetchFromTmdb(mediaType, excludeIds)

            } catch (e: Exception) {
                if (delayMs == retryDelaysMs.last()) {
                    // All retries exhausted — return empty; caller handles gracefully
                    return emptyList()
                }
            }
        }
        return emptyList()
    }

    /**
     * Soft limit enforcement: if total rows exceed CACHE_SOFT_LIMIT, evict
     * the oldest-by-lastAccessedAt down to CACHE_TARGET. This is intentionally
     * a soft ceiling — we don't refuse inserts, we clean up after.
     */
    private suspend fun enforceSoftLimit() {
        val total = cachedMediaDao.count()
        if (total > CACHE_SOFT_LIMIT) {
            val toEvict = total - CACHE_TARGET
            cachedMediaDao.evictOldest(toEvict)
        }
    }

    /**
     * Reset the session cursor (call when the user refreshes the feed).
     * Does NOT reset the TMDB page cursor — TMDB pagination continues from
     * where it left off across sessions.
     */
    fun resetSessionCursor() {
        roomOffset = 0
        servedIds.clear()
    }

    /**
     * Prefetch hint: tell the engine to preload items for upcoming pages.
     * Called by the UI when the user is near the bottom of the current page.
     * Returns immediately — actual work happens asynchronously.
     */
    suspend fun prefetchAhead(
        mediaType: String,
        excludeIds: Set<Int>,
        prefetchCount: Int = PAGE_SIZE * 2, // 2 pages ahead
    ) {
        // Fire-and-forget: just warm the cache. If it fails silently, no harm done.
        try {
            val total = cachedMediaDao.countBySource("catalog")
            if (total < CACHE_TARGET) {
                // There's room — fire a TMDB call to fill ahead
                val mutex = fetchMutexes[mediaType] ?: return
                if (!mutex.isLocked) {
                    mutex.withLock {
                        fetchFromTmdb(mediaType, excludeIds)
                    }
                }
            }
        } catch (_: Exception) { /* prefetch is best-effort */ }
    }
}
