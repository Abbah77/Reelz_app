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
 * Split out of MediaRepository per the restructure plan.
 *
 * All data here is local-only (Room). Never makes network calls.
 * Dependency direction: LibraryRepository → Room. Never touches UI.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
    private val watchlistDao: WatchlistDao,
    private val watchHistoryDao: WatchHistoryDao,
) {

    // ── Watch progress (local only) ───────────────────────────────────────────

    suspend fun getRecentProgress(limit: Int): List<com.axio.reelz.core.database.WatchProgressRow> = withContext(Dispatchers.IO) {
        watchProgressDao.getRecent(limit)
    }

    suspend fun getProgress(id: String, season: Int, episode: Int): WatchProgressRow? =
        withContext(Dispatchers.IO) { watchProgressDao.get(id, season, episode) }

    suspend fun saveProgress(
        id: String, season: Int, episode: Int,
        positionMs: Long, durationMs: Long,
        title: String = "",
    ) = withContext(Dispatchers.IO) {
        watchProgressDao.upsert(
            WatchProgressRow(
                mediaId    = id,
                season     = season,
                episode    = episode,
                positionMs = positionMs,
                durationMs = durationMs,
                title      = title,
            )
        )
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

    // ── Watch history ─────────────────────────────────────────────────────────

    fun observeHistory() = watchHistoryDao.observeAll()

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        watchHistoryDao.clear()
    }
}
