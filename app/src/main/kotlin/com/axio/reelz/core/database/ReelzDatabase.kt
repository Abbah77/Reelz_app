package com.axio.reelz.core.database

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
//  ReelzDatabase v8 — two-table download architecture
//
//  Changes in v8:
//   • Added "files" table  — permanent library of completed .mp4 files
//   • "downloads" table stays as the temporary working table
//     (QUEUED → DOWNLOADING → PAUSED → REMUXING → DONE then deleted)
//   • Both MP4 direct downloads and HLS→mp4 remuxed content land in "files"
//   • File transfer received files also land directly in "files"
// ─────────────────────────────────────────────────────────────────────────────

// ── Feed cache ────────────────────────────────────────────────────────────────
@Entity(tableName = "cached_feed", indices = [Index("sectionId"), Index("cachedAtMs")])
data class CachedFeedRow(
    @PrimaryKey val sectionId: String,
    val title: String,
    val itemsJson: String,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val cacheTtlMs: Long = 3_600_000L,
    val cachedAtMs: Long = System.currentTimeMillis(),
) {
    fun isStale(): Boolean = System.currentTimeMillis() - cachedAtMs > cacheTtlMs
}

@Dao
interface FeedCacheDao {
    @Query("SELECT * FROM cached_feed ORDER BY sectionId")
    suspend fun getAll(): List<CachedFeedRow>

    @Query("SELECT * FROM cached_feed WHERE sectionId = :id LIMIT 1")
    suspend fun get(id: String): CachedFeedRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CachedFeedRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CachedFeedRow>)

    @Query("DELETE FROM cached_feed WHERE sectionId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM cached_feed")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM cached_feed")
    suspend fun count(): Int

    @Query("DELETE FROM cached_feed WHERE (cachedAtMs + cacheTtlMs) < :now")
    suspend fun evictStale(now: Long = System.currentTimeMillis())
}

// ── Detail cache ──────────────────────────────────────────────────────────────
@Entity(tableName = "cached_detail", indices = [Index("cachedAtMs")])
data class CachedDetailRow(
    @PrimaryKey val mediaId: String,
    val detailJson: String,
    val cacheTtlMs: Long = 3_600_000L,
    val cachedAtMs: Long = System.currentTimeMillis(),
    val lastAccessedMs: Long = System.currentTimeMillis(),
) {
    fun isStale(): Boolean = System.currentTimeMillis() - cachedAtMs > cacheTtlMs
}

@Dao
interface DetailCacheDao {
    @Query("SELECT * FROM cached_detail WHERE mediaId = :id LIMIT 1")
    suspend fun get(id: String): CachedDetailRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CachedDetailRow)

    @Query("UPDATE cached_detail SET lastAccessedMs = :now WHERE mediaId = :id")
    suspend fun touch(id: String, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM cached_detail")
    suspend fun count(): Int

    @Query("""
        DELETE FROM cached_detail WHERE mediaId NOT IN (
            SELECT mediaId FROM cached_detail ORDER BY lastAccessedMs DESC LIMIT :keepCount
        )
    """)
    suspend fun evictToLimit(keepCount: Int = 500)

    @Query("DELETE FROM cached_detail WHERE (cachedAtMs + cacheTtlMs) < :now")
    suspend fun evictStale(now: Long = System.currentTimeMillis())
}

// ── Search cache ──────────────────────────────────────────────────────────────
@Entity(tableName = "cached_search", indices = [Index("query"), Index("cachedAtMs")])
data class CachedSearchRow(
    @PrimaryKey val query: String,
    val resultsJson: String,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val cacheTtlMs: Long = 300_000L,
    val cachedAtMs: Long = System.currentTimeMillis(),
) {
    fun isStale(): Boolean = System.currentTimeMillis() - cachedAtMs > cacheTtlMs
}

@Dao
interface SearchCacheDao {
    @Query("SELECT * FROM cached_search WHERE query = :q LIMIT 1")
    suspend fun get(q: String): CachedSearchRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CachedSearchRow)

    @Query("SELECT COUNT(*) FROM cached_search")
    suspend fun count(): Int

    @Query("""
        DELETE FROM cached_search WHERE query NOT IN (
            SELECT query FROM cached_search ORDER BY cachedAtMs DESC LIMIT 100
        )
    """)
    suspend fun evictToLimit()
}

// ── Watch progress ────────────────────────────────────────────────────────────
@Entity(tableName = "watch_progress", primaryKeys = ["mediaId", "season", "episode"])
data class WatchProgressRow(
    val mediaId: String,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(defaultValue = "") val title: String = "",
    @androidx.room.ColumnInfo(name = "posterUrl", defaultValue = "") val posterUrl: String? = null,
) {
    val percentWatched: Float
        get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val isFinished: Boolean
        get() = percentWatched >= 0.90f
}

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress WHERE mediaId = :id AND season = :s AND episode = :ep LIMIT 1")
    suspend fun get(id: String, s: Int, ep: Int): WatchProgressRow?

    @Query("SELECT * FROM watch_progress ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<WatchProgressRow>

    @Query("SELECT * FROM watch_progress ORDER BY watchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<WatchProgressRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(row: WatchProgressRow)

    @Query("""
        UPDATE watch_progress
        SET positionMs = :positionMs,
            durationMs = :durationMs,
            title      = CASE WHEN :title != '' THEN :title ELSE title END,
            posterUrl  = CASE WHEN :posterUrl IS NOT NULL THEN :posterUrl ELSE posterUrl END,
            watchedAt  = :watchedAt
        WHERE mediaId = :mediaId AND season = :season AND episode = :episode
    """)
    suspend fun updateProgress(
        mediaId: String, season: Int, episode: Int,
        positionMs: Long, durationMs: Long,
        title: String, posterUrl: String?,
        watchedAt: Long,
    )

    @Query("DELETE FROM watch_progress WHERE mediaId = :id")
    suspend fun deleteForMedia(id: String)

    @Query("DELETE FROM watch_progress")
    suspend fun clear()

    @Query("""
        DELETE FROM watch_progress WHERE rowid NOT IN (
            SELECT rowid FROM watch_progress ORDER BY watchedAt DESC LIMIT 500
        )
    """)
    suspend fun trimToLimit()
}

// ── Watchlist ─────────────────────────────────────────────────────────────────
@Entity(tableName = "watchlist")
data class WatchlistRow(
    @PrimaryKey val mediaId: String,
    val title: String,
    val posterUrl: String?,
    val mediaType: String,
    val addedAt: Long = System.currentTimeMillis(),
)

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WatchlistRow>>

    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    suspend fun getAll(): List<WatchlistRow>

    @Query("SELECT * FROM watchlist WHERE mediaId = :id LIMIT 1")
    suspend fun get(id: String): WatchlistRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: WatchlistRow)

    @Query("DELETE FROM watchlist WHERE mediaId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM watchlist")
    suspend fun clear()

    @Query("""
        DELETE FROM watchlist WHERE mediaId NOT IN (
            SELECT mediaId FROM watchlist ORDER BY addedAt DESC LIMIT :keepCount
        )
    """)
    suspend fun trimToLimit(keepCount: Int = 500)
}

// ── Recent searches ───────────────────────────────────────────────────────────
@Entity(tableName = "recent_searches")
data class RecentSearchRow(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis(),
)

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC LIMIT 15")
    fun observe(): Flow<List<RecentSearchRow>>

    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC LIMIT 15")
    suspend fun getAll(): List<RecentSearchRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: RecentSearchRow)

    @Query("DELETE FROM recent_searches WHERE query = :q")
    suspend fun delete(q: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clear()

    @Query("""
        DELETE FROM recent_searches WHERE query NOT IN (
            SELECT query FROM recent_searches ORDER BY searchedAt DESC LIMIT 15
        )
    """)
    suspend fun trimToLimit()
}

// ── User session ──────────────────────────────────────────────────────────────
@Entity(tableName = "user_session")
data class UserSessionRow(
    @PrimaryKey val uid: String,
    val name: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val accessToken: String = "",
    val refreshToken: String = "",
    val isPremium: Boolean = false,
    val premiumExpiresAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val cachedAtMs: Long = System.currentTimeMillis(),
)

@Dao
interface UserSessionDao {
    @Query("SELECT * FROM user_session ORDER BY cachedAtMs DESC LIMIT 1")
    suspend fun get(): UserSessionRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: UserSessionRow)

    @Query("DELETE FROM user_session")
    suspend fun clear()
}

// ── App config cache ──────────────────────────────────────────────────────────
@Entity(tableName = "app_config_cache")
data class AppConfigCacheRow(
    @PrimaryKey val id: Int = 1,
    val configJson: String,
    val version: Int = 1,
    val cachedAtMs: Long = System.currentTimeMillis(),
) {
    fun isStale(): Boolean = System.currentTimeMillis() - cachedAtMs > 6 * 3_600_000L
}

@Dao
interface AppConfigCacheDao {
    @Query("SELECT * FROM app_config_cache WHERE id = 1 LIMIT 1")
    suspend fun get(): AppConfigCacheRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AppConfigCacheRow)
}

// ── Downloads (TEMPORARY working table) ──────────────────────────────────────
//
//  This table is the WORKER. It tracks active/paused/queued downloads.
//  When a download completes (and for HLS: after remux to .mp4), the row is
//  DELETED from this table and a row is INSERTED into the "files" table.
//  This table never contains DONE rows — DONE is transient (deleted immediately).
//
@Entity(tableName = "downloads")
data class DownloadRow(
    @PrimaryKey val id: String,
    val mediaId: String,
    val title: String,
    val posterUrl: String?,
    val mediaType: String,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeName: String = "",
    val quality: String = "720p",
    val filePath: String = "",
    val sizeBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: String = "QUEUED",   // QUEUED | DOWNLOADING | PAUSED | REMUXING | ERROR
    val streamUrl: String = "",
    val headersJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val segmentsDone: Int = 0,
    val totalSegments: Int = 0,
    val localPlaylistPath: String = "",  // HLS segments dir / index.m3u8 while in-progress
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadRow>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun get(id: String): DownloadRow?

    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun getByStatus(status: String): List<DownloadRow>

    /** Check for in-progress download of same content+quality (for duplicate guard). */
    @Query("""
        SELECT * FROM downloads
        WHERE mediaId = :id AND season = :s AND episode = :ep AND status != 'ERROR'
        ORDER BY quality DESC
    """)
    suspend fun getForContent(id: String, s: Int, ep: Int): List<DownloadRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: DownloadRow)

    @Query("""
        UPDATE downloads
        SET
            status            = :status,
            downloadedBytes   = :bytes,
            segmentsDone      = :done,
            totalSegments     = :total,
            localPlaylistPath = :playlist,
            sizeBytes         = CASE WHEN :sizeBytes > 0 THEN :sizeBytes ELSE sizeBytes END
        WHERE id = :id
    """)
    suspend fun updateProgress(
        id: String,
        status: String,
        bytes: Long,
        done: Int = 0,
        total: Int = 0,
        playlist: String = "",
        sizeBytes: Long = 0L,
    )

    @Query("UPDATE downloads SET status = 'PAUSED' WHERE id = :id")
    suspend fun markPaused(id: String)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE downloads SET streamUrl = :url, headersJson = :h WHERE id = :id")
    suspend fun updateStreamUrl(id: String, url: String, h: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)
}

// ── Files (PERMANENT library — only clean .mp4 files) ─────────────────────────
//
//  This table is the USER'S LIBRARY. It contains only successfully completed,
//  clean .mp4 files — whether from:
//    1. Direct MP4 download (no remux needed)
//    2. HLS download + Media3 Transformer remux → clean .mp4
//    3. File transfer received from another device
//
//  There is NO status column — every row IS a valid, playable .mp4 file.
//  The "downloads" table handles all the messy intermediate states.
//
@Entity(
    tableName = "files",
    indices = [
        Index("mediaId"),
        Index(value = ["mediaId", "season", "episode", "quality"], unique = true),
    ]
)
data class FileRow(
    @PrimaryKey val id: String,
    val mediaId: String,
    val title: String,
    val posterUrl: String?,
    val mediaType: String,          // "MOVIE" | "TV"
    val season: Int = 0,
    val episode: Int = 0,
    val episodeName: String = "",
    val quality: String = "720p",
    val filePath: String,           // absolute path to the .mp4 file
    val sizeBytes: Long = 0,
    val durationMs: Long = 0,
    val watchProgressMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
)

@Dao
interface FileDao {
    @Query("SELECT * FROM files ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FileRow>>

    @Query("SELECT * FROM files WHERE id = :id LIMIT 1")
    suspend fun get(id: String): FileRow?

    @Query("""
        SELECT * FROM files
        WHERE mediaId = :mediaId AND season = :season AND episode = :episode
        ORDER BY quality DESC
    """)
    suspend fun getForContent(mediaId: String, season: Int, episode: Int): List<FileRow>

    @Query("""
        SELECT * FROM files
        WHERE mediaId = :mediaId AND season = :season AND episode = :episode AND quality = :quality
        LIMIT 1
    """)
    suspend fun getExact(mediaId: String, season: Int, episode: Int, quality: String): FileRow?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(row: FileRow): Long   // returns -1 if already exists

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FileRow)

    @Query("""
        UPDATE files SET watchProgressMs = :pos, durationMs = :dur, lastPlayedAt = :at
        WHERE mediaId = :mediaId AND season = :season AND episode = :episode
    """)
    suspend fun updateWatchProgress(mediaId: String, season: Int, episode: Int, pos: Long, dur: Long, at: Long)

    @Query("DELETE FROM files WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM files WHERE mediaId = :mediaId AND season = :season AND episode = :episode AND quality = :quality")
    suspend fun deleteExact(mediaId: String, season: Int, episode: Int, quality: String)

    @Query("SELECT COUNT(*) FROM files")
    suspend fun count(): Int
}

// ── Download subtitles ────────────────────────────────────────────────────────
@Entity(tableName = "download_subtitles",
    indices = [Index("downloadId"), Index("mediaId", "season", "episode")])
data class DownloadSubtitleRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val downloadId: String,
    val mediaId: String,
    val season: Int = 0,
    val episode: Int = 0,
    val language: String,
    val label: String,
    val localFilePath: String,
    val isEnabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(defaultValue = "srt") val format: String = "srt",
)

@Dao
interface DownloadSubtitleDao {
    @Query("SELECT * FROM download_subtitles WHERE downloadId = :id ORDER BY addedAt")
    fun observeForDownload(id: String): Flow<List<DownloadSubtitleRow>>

    @Query("SELECT * FROM download_subtitles WHERE mediaId = :id AND season = :s AND episode = :ep")
    suspend fun getForContent(id: String, s: Int, ep: Int): List<DownloadSubtitleRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: DownloadSubtitleRow)

    @Query("UPDATE download_subtitles SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM download_subtitles WHERE downloadId = :id")
    suspend fun deleteForDownload(id: String)

    @Query("DELETE FROM download_subtitles WHERE mediaId = :mediaId AND season = :season AND episode = :episode")
    suspend fun deleteForContent(mediaId: String, season: Int, episode: Int)
}

// ── Transfer history ───────────────────────────────────────────────────────────
@Entity(tableName = "transfer_history")
data class TransferRecord(
    @PrimaryKey val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val direction: String,
    val peerName: String,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_history ORDER BY createdAt DESC")
    fun getAll(): Flow<List<TransferRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TransferRecord)

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM transfer_history")
    suspend fun clear()
}

// ── Supplemental view types ───────────────────────────────────────────────────
data class WatchlistItem(
    val mediaId: String,
    val title: String,
    val posterUrl: String?,
    val mediaType: String,
    val addedAt: Long,
)

data class WatchHistory(
    val mediaId: String,
    val title: String?,
    @androidx.room.ColumnInfo(name = "posterUrl") val posterPath: String?,
    val mediaType: String?,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,
)

data class SavedVideoItem(
    val mediaId: String,
    val title: String,
    val posterUrl: String?,
    val mediaType: String,
    val addedAt: Long,
)

@Dao
interface WatchHistoryDao {
    @Query("""
        SELECT wp.mediaId,
               COALESCE(wl.title, NULLIF(wp.title, ''), wp.mediaId) AS title,
               COALESCE(wp.posterUrl, wl.posterUrl)                  AS posterUrl,
               COALESCE(wl.mediaType, 'movie')                       AS mediaType,
               wp.positionMs, wp.durationMs, wp.watchedAt
        FROM watch_progress wp
        LEFT JOIN watchlist wl ON wl.mediaId = wp.mediaId
        INNER JOIN (
            SELECT mediaId, MAX(watchedAt) AS latestAt
            FROM watch_progress
            GROUP BY mediaId
        ) latest ON latest.mediaId = wp.mediaId AND latest.latestAt = wp.watchedAt
        ORDER BY wp.watchedAt DESC
    """)
    fun observeAll(): Flow<List<WatchHistory>>

    @Query("""
        SELECT wp.mediaId,
               COALESCE(wl.title, NULLIF(wp.title, ''), wp.mediaId) AS title,
               COALESCE(wp.posterUrl, wl.posterUrl)                  AS posterUrl,
               COALESCE(wl.mediaType, 'movie')                       AS mediaType,
               wp.positionMs, wp.durationMs, wp.watchedAt
        FROM watch_progress wp
        LEFT JOIN watchlist wl ON wl.mediaId = wp.mediaId
        INNER JOIN (
            SELECT mediaId, MAX(watchedAt) AS latestAt
            FROM watch_progress
            GROUP BY mediaId
        ) latest ON latest.mediaId = wp.mediaId AND latest.latestAt = wp.watchedAt
        ORDER BY wp.watchedAt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPage(limit: Int = 20, offset: Int = 0): List<WatchHistory>

    @Query("SELECT COUNT(*) FROM (SELECT mediaId FROM watch_progress GROUP BY mediaId)")
    suspend fun count(): Int

    @Query("DELETE FROM watch_progress")
    suspend fun clear()
}

@Dao
interface SavedVideoDao {
    @Query("SELECT mediaId, title, posterUrl, mediaType, addedAt FROM watchlist ORDER BY addedAt DESC")
    fun getAll(): Flow<List<SavedVideoItem>>
}

// ── Migrations ────────────────────────────────────────────────────────────────
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE watch_progress ADD COLUMN title TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE watch_progress ADD COLUMN posterUrl TEXT")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_session ADD COLUMN refreshToken TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE user_session ADD COLUMN premiumExpiresAtMs INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            DELETE FROM watchlist WHERE mediaId NOT IN (
                SELECT mediaId FROM watchlist ORDER BY addedAt DESC LIMIT 500
            )
        """)
        db.execSQL("""
            DELETE FROM watch_progress WHERE rowid NOT IN (
                SELECT rowid FROM watch_progress ORDER BY watchedAt DESC LIMIT 500
            )
        """)
        db.execSQL("""
            DELETE FROM recent_searches WHERE query NOT IN (
                SELECT query FROM recent_searches ORDER BY searchedAt DESC LIMIT 15
            )
        """)
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download_subtitles ADD COLUMN format TEXT NOT NULL DEFAULT 'srt'")
    }
}

// Migration 7→8: Add "files" permanent library table + slim down "downloads"
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create the new permanent files table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS files (
                id TEXT NOT NULL PRIMARY KEY,
                mediaId TEXT NOT NULL,
                title TEXT NOT NULL,
                posterUrl TEXT,
                mediaType TEXT NOT NULL,
                season INTEGER NOT NULL DEFAULT 0,
                episode INTEGER NOT NULL DEFAULT 0,
                episodeName TEXT NOT NULL DEFAULT '',
                quality TEXT NOT NULL DEFAULT '720p',
                filePath TEXT NOT NULL,
                sizeBytes INTEGER NOT NULL DEFAULT 0,
                durationMs INTEGER NOT NULL DEFAULT 0,
                watchProgressMs INTEGER NOT NULL DEFAULT 0,
                lastPlayedAt INTEGER NOT NULL DEFAULT 0,
                addedAt INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_files_mediaId ON files(mediaId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_files_unique ON files(mediaId, season, episode, quality)")

        // 2. Migrate completed downloads (DONE status) into files table
        //    Only migrate .mp4 files (filePath not ending in .m3u8)
        db.execSQL("""
            INSERT OR IGNORE INTO files
                (id, mediaId, title, posterUrl, mediaType, season, episode, episodeName,
                 quality, filePath, sizeBytes, durationMs, watchProgressMs, lastPlayedAt, addedAt)
            SELECT id, mediaId, title, posterUrl, mediaType, season, episode, episodeName,
                   quality, filePath, sizeBytes, durationMs, watchProgressMs, lastPlayedAt, completedAt
            FROM downloads
            WHERE status = 'DONE'
              AND filePath != ''
              AND filePath NOT LIKE '%.m3u8'
              AND filePath NOT LIKE '%.ts'
        """)

        // 3. Rebuild downloads table without old columns not needed anymore
        //    (completedAt, watchProgressMs, durationMs, lastPlayedAt are now in files)
        db.execSQL("""
            CREATE TABLE downloads_new (
                id TEXT NOT NULL PRIMARY KEY,
                mediaId TEXT NOT NULL,
                title TEXT NOT NULL,
                posterUrl TEXT,
                mediaType TEXT NOT NULL,
                season INTEGER NOT NULL DEFAULT 0,
                episode INTEGER NOT NULL DEFAULT 0,
                episodeName TEXT NOT NULL DEFAULT '',
                quality TEXT NOT NULL DEFAULT '720p',
                filePath TEXT NOT NULL DEFAULT '',
                sizeBytes INTEGER NOT NULL DEFAULT 0,
                downloadedBytes INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'QUEUED',
                streamUrl TEXT NOT NULL DEFAULT '',
                headersJson TEXT NOT NULL DEFAULT '{}',
                createdAt INTEGER NOT NULL DEFAULT 0,
                segmentsDone INTEGER NOT NULL DEFAULT 0,
                totalSegments INTEGER NOT NULL DEFAULT 0,
                localPlaylistPath TEXT NOT NULL DEFAULT ''
            )
        """)
        // Keep only active/paused/queued/error rows (not DONE — they moved to files)
        db.execSQL("""
            INSERT INTO downloads_new
                (id, mediaId, title, posterUrl, mediaType, season, episode, episodeName,
                 quality, filePath, sizeBytes, downloadedBytes, status, streamUrl, headersJson,
                 createdAt, segmentsDone, totalSegments, localPlaylistPath)
            SELECT id, mediaId, title, posterUrl, mediaType, season, episode, episodeName,
                   quality, filePath, sizeBytes, downloadedBytes, status, streamUrl, headersJson,
                   createdAt, segmentsDone, totalSegments, localPlaylistPath
            FROM downloads
            WHERE status != 'DONE'
        """)
        db.execSQL("DROP TABLE downloads")
        db.execSQL("ALTER TABLE downloads_new RENAME TO downloads")
    }
}

@Database(
    entities = [
        CachedFeedRow::class,
        CachedDetailRow::class,
        CachedSearchRow::class,
        WatchProgressRow::class,
        WatchlistRow::class,
        RecentSearchRow::class,
        UserSessionRow::class,
        AppConfigCacheRow::class,
        DownloadRow::class,
        DownloadSubtitleRow::class,
        TransferRecord::class,
        FileRow::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class ReelzDatabase : RoomDatabase() {
    abstract fun feedCacheDao(): FeedCacheDao
    abstract fun detailCacheDao(): DetailCacheDao
    abstract fun searchCacheDao(): SearchCacheDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun userSessionDao(): UserSessionDao
    abstract fun appConfigCacheDao(): AppConfigCacheDao
    abstract fun downloadDao(): DownloadDao
    abstract fun downloadSubtitleDao(): DownloadSubtitleDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun savedVideoDao(): SavedVideoDao
    abstract fun transferDao(): TransferDao
    abstract fun fileDao(): FileDao
}
