package com.reelz.data.local

import androidx.room.*
import com.reelz.data.model.*
import kotlinx.coroutines.flow.Flow

// ── Watchlist ─────────────────────────────────────────────────────────────────
@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun getAll(): Flow<List<WatchlistItem>>
    @Query("SELECT * FROM watchlist WHERE tmdbId = :id LIMIT 1")
    suspend fun get(id: Int): WatchlistItem?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(i: WatchlistItem)
    @Query("DELETE FROM watchlist WHERE tmdbId = :id") suspend fun delete(id: Int)
}

// ── Watch history ─────────────────────────────────────────────────────────────
@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT 50")
    fun getRecent(): Flow<List<WatchHistory>>
    @Query("SELECT * FROM watch_history WHERE key = :key LIMIT 1")
    suspend fun get(key: String): WatchHistory?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(i: WatchHistory)
    @Query("DELETE FROM watch_history WHERE key = :key") suspend fun delete(key: String)
    @Query("DELETE FROM watch_history") suspend fun clear()
}

// ── Liked media ───────────────────────────────────────────────────────────────
@Dao
interface LikedDao {
    @Query("SELECT * FROM liked_media ORDER BY likedAt DESC")
    fun getAll(): Flow<List<LikedItem>>
    @Query("SELECT * FROM liked_media WHERE tmdbId = :id LIMIT 1")
    suspend fun get(id: Int): LikedItem?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(i: LikedItem)
    @Query("DELETE FROM liked_media WHERE tmdbId = :id") suspend fun delete(id: Int)
}

// ── Metadata cache ────────────────────────────────────────────────────────────
@Dao
interface CachedMediaDao {
    @Query("SELECT * FROM cached_media WHERE mediaType = :type ORDER BY popularity DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 100): List<CachedMedia>
    @Query("SELECT * FROM cached_media WHERE tmdbId = :id LIMIT 1")
    suspend fun get(id: Int): CachedMedia?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<CachedMedia>)
    @Query("DELETE FROM cached_media WHERE cachedAt < :before") suspend fun evict(before: Long)
    @Query("SELECT COUNT(*) FROM cached_media") suspend fun count(): Int
}

// ── Downloads ─────────────────────────────────────────────────────────────────
@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAll(): Flow<List<DownloadItem>>
    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun get(id: String): DownloadItem?
    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun getByStatus(status: String): List<DownloadItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(i: DownloadItem)
    @Query("UPDATE downloads SET status = :status, downloadedBytes = :bytes WHERE id = :id")
    suspend fun updateProgress(id: String, status: String, bytes: Long)
    @Query("UPDATE downloads SET status = :status, filePath = :path, completedAt = :at WHERE id = :id")
    suspend fun markDone(id: String, status: String, path: String, at: Long)
    @Query("DELETE FROM downloads WHERE id = :id") suspend fun delete(id: String)
}

// ── Transfer history ──────────────────────────────────────────────────────────
@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_history ORDER BY createdAt DESC LIMIT 100")
    fun getAll(): Flow<List<TransferRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(r: TransferRecord)
    @Query("DELETE FROM transfer_history WHERE id = :id") suspend fun delete(id: String)
}

// ── Database ──────────────────────────────────────────────────────────────────
@Database(
    entities = [
        WatchlistItem::class,
        WatchHistory::class,
        LikedItem::class,
        CachedMedia::class,
        DownloadItem::class,
        TransferRecord::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(com.reelz.data.model.MediaConverters::class)
abstract class ReelzDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun likedDao(): LikedDao
    abstract fun cachedMediaDao(): CachedMediaDao
    abstract fun downloadDao(): DownloadDao
    abstract fun transferDao(): TransferDao
}
