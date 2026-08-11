package com.axio.reelz.core.database

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
//  ReelzDatabase v1 — Smart-cache edition
//
//  Philosophy:
//   • Cache ONLY what the UI needs to render without a network call.
//   • No TMDB IDs, no section engines, no bulk discover dumps.
//   • Smart TTL: each row stores cachedAtMs; fresh check is one comparison.
//   • Small footprint: target ~500 detail rows, ~2000 feed card rows.
//
//  Tables:
//   cached_feed      — feed sections served offline (home screen)
//   cached_detail    — detail pages served offline
//   cached_search    — recent search results (capped at 100)
//   watch_progress   — playback resume positions (purely local, never bulk)
//   watchlist        — user's saved titles (synced on auth)
//   recent_searches  — search query history (local only, max 15)
//   user_session     — auth token + premium status
//   app_config_cache — app config (feature flags, ads, etc.)
//   downloads        — offline download queue + state
//   download_subtitles — per-download subtitle files
// ─────────────────────────────────────────────────────────────────────────────

// ── Feed cache ────────────────────────────────────────────────────────────────
@Entity(tableName = "cached_feed", indices = [Index("sectionId"), Index("cachedAtMs")])
data class CachedFeedRow(
    @PrimaryKey val sectionId: String,
    val title: String,
    val itemsJson: String,        // JSON array of MediaDto (Gson serialised)
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

    // Evict all stale rows (called on pull-to-refresh or daily worker)
    @Query("DELETE FROM cached_feed WHERE (cachedAtMs + cacheTtlMs) < :now")
    suspend fun evictStale(now: Long = System.currentTimeMillis())
}

// ── Detail cache ──────────────────────────────────────────────────────────────
@Entity(tableName = "cached_detail", indices = [Index("cachedAtMs")])
data class CachedDetailRow(
    @PrimaryKey val mediaId: String,
    val detailJson: String,       // JSON of MediaDetailDto
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

    // Keep only the N most recently accessed rows
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
    val cacheTtlMs: Long = 300_000L,  // 5 min — search results change often
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

    // Keep only the 100 most recent search caches
    @Query("""
        DELETE FROM cached_search WHERE query NOT IN (
            SELECT query FROM cached_search ORDER BY cachedAtMs DESC LIMIT 100
        )
    """)
    suspend fun evictToLimit()
}

// ── Watch progress ────────────────────────────────────────────────────────────
@Entity(tableName = "watch_progress",
    primaryKeys = ["mediaId", "season", "episode"])
data class WatchProgressRow(
    val mediaId: String,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long = System.currentTimeMillis(),
) {
    val percentWatched: Float
        get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val isFinished: Boolean
        get() = percentWatched >= 0.90f
}

@Dao
interface WatchProgressDao {
    @Query("""
        SELECT * FROM watch_progress
        WHERE mediaId = :id AND season = :s AND episode = :ep LIMIT 1
    """)
    suspend fun get(id: String, s: Int, ep: Int): WatchProgressRow?

    @Query("SELECT * FROM watch_progress ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<WatchProgressRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: WatchProgressRow)

    @Query("DELETE FROM watch_progress WHERE mediaId = :id")
    suspend fun deleteForMedia(id: String)

    @Query("DELETE FROM watch_progress")
    suspend fun clear()

    // Trim to keep only the last 500 entries
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
    val isPremium: Boolean = false,
    val plan: String = "",
    val expiresAtMs: Long = 0L,
    val cachedAtMs: Long = System.currentTimeMillis(),
) {
    fun isTokenStale(): Boolean =
        System.currentTimeMillis() - cachedAtMs > 24 * 3_600_000L // 24 h
}

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
    fun isStale(): Boolean =
        System.currentTimeMillis() - cachedAtMs > 6 * 3_600_000L  // 6 h
}

@Dao
interface AppConfigCacheDao {
    @Query("SELECT * FROM app_config_cache WHERE id = 1 LIMIT 1")
    suspend fun get(): AppConfigCacheRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AppConfigCacheRow)
}

// ── Downloads ─────────────────────────────────────────────────────────────────
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
    val status: String = "QUEUED",
    val streamUrl: String = "",
    val headersJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val segmentsDone: Int = 0,
    val totalSegments: Int = 0,
    val watchProgressMs: Long = 0,
    val durationMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val localPlaylistPath: String = "",
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadRow>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun get(id: String): DownloadRow?

    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun getByStatus(status: String): List<DownloadRow>

    @Query("""
        SELECT * FROM downloads
        WHERE mediaId = :id AND season = :s AND episode = :ep AND status != 'ERROR'
        ORDER BY quality DESC
    """)
    suspend fun getForContent(id: String, s: Int, ep: Int): List<DownloadRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: DownloadRow)

    @Query("""
        UPDATE downloads SET status = :status, downloadedBytes = :bytes,
        segmentsDone = :done, totalSegments = :total, localPlaylistPath = :playlist
        WHERE id = :id
    """)
    suspend fun updateProgress(
        id: String, status: String, bytes: Long,
        done: Int = 0, total: Int = 0, playlist: String = "",
    )

    @Query("UPDATE downloads SET status = :status, filePath = :path, completedAt = :at WHERE id = :id")
    suspend fun markDone(id: String, status: String, path: String, at: Long)

    @Query("UPDATE downloads SET status = 'PAUSED' WHERE id = :id")
    suspend fun markPaused(id: String)

    @Query("UPDATE downloads SET streamUrl = :url, headersJson = :h WHERE id = :id")
    suspend fun updateStreamUrl(id: String, url: String, h: String)

    @Query("""
        UPDATE downloads SET watchProgressMs = :pos, durationMs = :dur,
        lastPlayedAt = :at WHERE mediaId = :id AND season = :s AND episode = :ep
    """)
    suspend fun updateWatchProgress(id: String, s: Int, ep: Int, pos: Long, dur: Long, at: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)
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
}

// ── Database ──────────────────────────────────────────────────────────────────
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
    ],
    version = 2,
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
}

// ─────────────────────────────────────────────────────────────────────────────
//  Supplemental types needed by ProfileScreen & TransferScreen
//  Added here to keep them co-located with the DB schema they depend on.
// ─────────────────────────────────────────────────────────────────────────────

// ── View models returned by profile DAOs ──────────────────────────────────────
// These are NOT @Entity — they are plain data classes produced by DAO queries.

data class WatchlistItem(
    val mediaId: String,
    val title: String,
    val posterUrl: String?,
    val mediaType: String,
    val addedAt: Long,
)

data class WatchHistory(
    val mediaId: String,
    val title: String,
    val posterPath: String?,
    val mediaType: String,
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

// ── WatchHistoryDao — backed by WatchProgressRow joined with WatchlistRow ─────
// Returns paginated history ordered by most-recently-watched.
// Profile screen uses watch_progress for "Continue Watching" history, and
// watchlist for the saved title metadata (title, poster, mediaType).
@Dao
interface WatchHistoryDao {
    @Query("""
        SELECT wp.mediaId, wl.title, wl.posterUrl, wl.mediaType,
               wp.positionMs, wp.durationMs, wp.watchedAt
        FROM watch_progress wp
        LEFT JOIN watchlist wl ON wl.mediaId = wp.mediaId
        WHERE wp.season = 0 AND wp.episode = 0
        ORDER BY wp.watchedAt DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPage(limit: Int = 20, offset: Int = 0): List<WatchHistory>

    @Query("SELECT COUNT(*) FROM watch_progress WHERE season = 0 AND episode = 0")
    suspend fun count(): Int

    @Query("DELETE FROM watch_progress")
    suspend fun clear()
}

// ── SavedVideoDao — alias for watchlist with a SavedVideoItem projection ──────
// "Saved" tab on Profile is the same as Watchlist — different UI label, same data.
@Dao
interface SavedVideoDao {
    @Query("""
        SELECT mediaId, title, posterUrl, mediaType, addedAt
        FROM watchlist ORDER BY addedAt DESC
    """)
    fun getAll(): Flow<List<SavedVideoItem>>
}

// ── Transfer types ─────────────────────────────────────────────────────────────

@Entity(tableName = "transfer_history")
data class TransferRecord(
    @PrimaryKey val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val direction: String,   // "SEND" | "RECEIVE"
    val peerName: String,
    val status: String,      // "DONE" | "ERROR" | "CANCELLED"
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
