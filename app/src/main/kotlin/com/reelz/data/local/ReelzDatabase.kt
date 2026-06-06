package com.reelz.data.local

import androidx.room.*
import com.reelz.data.model.WatchlistItem
import com.reelz.data.model.WatchHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun getAll(): Flow<List<WatchlistItem>>

    @Query("SELECT * FROM watchlist WHERE tmdbId = :tmdbId LIMIT 1")
    suspend fun get(tmdbId: Int): WatchlistItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WatchlistItem)

    @Delete
    suspend fun delete(item: WatchlistItem)

    @Query("DELETE FROM watchlist WHERE tmdbId = :tmdbId")
    suspend fun deleteById(tmdbId: Int)
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT 50")
    fun getRecent(): Flow<List<WatchHistory>>

    @Query("SELECT * FROM watch_history WHERE key = :key LIMIT 1")
    suspend fun get(key: String): WatchHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WatchHistory)

    @Query("DELETE FROM watch_history WHERE key = :key")
    suspend fun deleteByKey(key: String)
}

@Database(
    entities = [WatchlistItem::class, WatchHistory::class],
    version  = 1,
    exportSchema = false,
)
abstract class ReelzDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun watchHistoryDao(): WatchHistoryDao
}
