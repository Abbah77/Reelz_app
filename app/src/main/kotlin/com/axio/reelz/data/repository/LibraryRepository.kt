package com.axio.reelz.data.repository

import com.axio.reelz.core.database.WatchHistoryDao
import com.axio.reelz.core.database.WatchProgressDao
import com.axio.reelz.core.database.WatchProgressRow
import com.axio.reelz.core.database.WatchlistDao
import com.axio.reelz.core.database.WatchlistRow
import com.axio.reelz.data.model.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LibraryRepository — watchlist, watch progress, watch history.
 *
 * Data limits (oldest-first eviction, new insertions always work):
 *   • watch_progress / history : 500 unique items (trimmed in saveProgress)
 *   • watchlist                : 500 items (trimmed on addToWatchlist)
 *
 * UI can call clearWatchlist(), clearHistory(), clearSearchHistory() to
 * let the user wipe individual data categories from Settings.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
    private val watchlistDao: WatchlistDao,
    private val watchHistoryDao: WatchHistoryDao,
) {

    // ── Watch progress (local only) ───────────────────────────────────────────

    suspend fun getRecentProgress(limit: Int): List<WatchProgressRow> =
        withContext(Dispatchers.IO) { watchProgressDao.getRecent(limit) }

    fun observeRecentProgress(limit: Int = 10) = watchProgressDao.observeRecent(limit)

    suspend fun getProgress(id: String, season: Int, episode: Int): WatchProgressRow? =
        withContext(Dispatchers.IO) { watchProgressDao.get(id, season, episode) }

    suspend fun saveProgress(
        id: String,
        season: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        title: String = "",
        posterUrl: String? = null,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val row = WatchProgressRow(
            mediaId    = id,
            season     = season,
            episode    = episode,
            positionMs = positionMs,
            durationMs = durationMs,
            title      = title,
            posterUrl  = posterUrl,
            watchedAt  = now,
        )
        watchProgressDao.insertIfNew(row)
        watchProgressDao.updateProgress(
            mediaId    = id,
            season     = season,
            episode    = episode,
            positionMs = positionMs,
            durationMs = durationMs,
            title      = title,
            posterUrl  = posterUrl,
            watchedAt  = now,
        )
        // Trim to 500 most recent items — oldest entries are evicted, new ones always land.
        watchProgressDao.trimToLimit()
    }

    suspend fun getRecentlyWatched(limit: Int = 20) =
        withContext(Dispatchers.IO) { watchProgressDao.getRecent(limit) }

    // ── Watchlist ─────────────────────────────────────────────────────────────

    fun observeWatchlist() = watchlistDao.observeAll()

    suspend fun isInWatchlist(id: String) = watchlistDao.get(id) != null

    suspend fun addToWatchlist(media: Media) = withContext(Dispatchers.IO) {
        watchlistDao.insert(
            WatchlistRow(
                mediaId   = media.id,
                title     = media.title,
                posterUrl = media.posterUrl,
                mediaType = media.mediaType.name,
            )
        )
        // Trim to 500 most recent — oldest items evicted, new insertions always work.
        watchlistDao.trimToLimit()
    }

    suspend fun removeFromWatchlist(id: String) = withContext(Dispatchers.IO) {
        watchlistDao.delete(id)
    }

    suspend fun toggleWatchlist(media: Media): Boolean = withContext(Dispatchers.IO) {
        if (isInWatchlist(media.id)) {
            removeFromWatchlist(media.id); false
        } else {
            addToWatchlist(media); true
        }
    }

    /** Clear all watchlist entries (user action from Settings). */
    suspend fun clearWatchlist() = withContext(Dispatchers.IO) {
        watchlistDao.clear()
    }

    // ── Watch history ─────────────────────────────────────────────────────────

    fun observeHistory() = watchHistoryDao.observeAll()

    /** Clear all watch history / continue-watching data (user action from Settings). */
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        watchHistoryDao.clear()
    }
}
