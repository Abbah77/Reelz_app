package com.reelz.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(i: DownloadItem)

    @Query("""
        UPDATE downloads
        SET status = :status,
            downloadedBytes = :bytes,
            networkSpeedBps = :speedBps,
            segmentsDone = :segsDone,
            totalSegments = :segsTotal,
            localPlaylistPath = :localPlaylist
        WHERE id = :id
    """)
    suspend fun updateProgress(
        id: String,
        status: String,
        bytes: Long,
        speedBps: Long = 0,
        segsDone: Int = 0,
        segsTotal: Int = 0,
        localPlaylist: String = "",
    )

    @Query("""
        UPDATE downloads
        SET status = :status,
            sizeBytes = :totalBytes,
            segmentDir = :segDir
        WHERE id = :id
    """)
    suspend fun updateMetadata(id: String, status: String, totalBytes: Long, segDir: String = "")

    @Query("""
        UPDATE downloads
        SET status = :status,
            filePath = :path,
            completedAt = :at,
            networkSpeedBps = 0,
            segmentDir = '',
            localPlaylistPath = '',
            resolveRequired = 0
        WHERE id = :id
    """)
    suspend fun markDone(id: String, status: String, path: String, at: Long)

    /**
     * When a download is paused (error or manual), mark resolveRequired = true
     * so the next resume re-resolves the CDN URL (CDN tokens expire).
     */
    @Query("""
        UPDATE downloads
        SET status = :status,
            resolveRequired = 1
        WHERE id = :id
    """)
    suspend fun markPaused(id: String, status: String = DownloadStatus.PAUSED.name)

    @Query("""
        UPDATE downloads
        SET streamUrl = :url,
            headers = :headersJson,
            resolveRequired = 0
        WHERE id = :id
    """)
    suspend fun updateStreamUrl(id: String, url: String, headersJson: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)
}

// ── Transfer history ──────────────────────────────────────────────────────────
@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_history ORDER BY createdAt DESC LIMIT 100")
    fun getAll(): Flow<List<TransferRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(r: TransferRecord)
    @Query("DELETE FROM transfer_history WHERE id = :id") suspend fun delete(id: String)
}

// ── Migration v1 → v2 ─────────────────────────────────────────────────────────
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN networkSpeedBps INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN segmentsDone INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN totalSegments INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN segmentDir TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE downloads ADD COLUMN localPlaylistPath TEXT NOT NULL DEFAULT ''")
    }
}

// ── Migration v2 → v3: qualityTracksJson + resolveRequired ────────────────────
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN qualityTracksJson TEXT NOT NULL DEFAULT '[]'")
        // resolveRequired = 1 (true) for all existing rows — safest default
        db.execSQL("ALTER TABLE downloads ADD COLUMN resolveRequired INTEGER NOT NULL DEFAULT 1")
    }
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
    version = 3,          // bumped from 2 → 3
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
