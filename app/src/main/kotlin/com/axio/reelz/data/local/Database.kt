package com.axio.reelz.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.axio.reelz.data.model.*
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
    /** Paginated load — load a page of N items starting at offset for lazy scroll. */
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int = 20, offset: Int = 0): List<WatchHistory>

    /** Total count — used to know if there are more pages to load. */
    @Query("SELECT COUNT(*) FROM watch_history")
    suspend fun count(): Int

    /** Flow of most recent 20 for the initial display. */
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT 20")
    fun getRecent(): Flow<List<WatchHistory>>

    @Query("SELECT * FROM watch_history WHERE key = :key LIMIT 1")
    suspend fun get(key: String): WatchHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(i: WatchHistory)

    @Query("DELETE FROM watch_history WHERE key = :key") suspend fun delete(key: String)

    @Query("DELETE FROM watch_history") suspend fun clear()

    /** Trim oldest entries beyond the cap so the table never grows unbounded. */
    @Query("""
        DELETE FROM watch_history WHERE key IN (
            SELECT key FROM watch_history ORDER BY watchedAt DESC LIMIT -1 OFFSET :keepCount
        )
    """)
    suspend fun trimToLimit(keepCount: Int = 500)
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

// ── Saved videos (bookmarked from hero / detail, visible in Profile) ──────────
@Dao
interface SavedVideoDao {
    @Query("SELECT * FROM saved_videos ORDER BY savedAt DESC")
    fun getAll(): Flow<List<SavedVideoItem>>
    @Query("SELECT * FROM saved_videos WHERE tmdbId = :id LIMIT 1")
    suspend fun get(id: Int): SavedVideoItem?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(i: SavedVideoItem)
    @Query("DELETE FROM saved_videos WHERE tmdbId = :id") suspend fun delete(id: Int)
}

// ── Metadata cache ────────────────────────────────────────────────────────────
@Dao
interface CachedMediaDao {
    @Query("SELECT * FROM cached_media WHERE mediaType = :type ORDER BY popularity DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 100): List<CachedMedia>

    @Query("SELECT * FROM cached_media WHERE section = :section ORDER BY popularity DESC LIMIT :limit")
    suspend fun getBySection(section: String, limit: Int = 20): List<CachedMedia>

    @Query("SELECT * FROM cached_media WHERE tmdbId = :id LIMIT 1")
    suspend fun get(id: Int): CachedMedia?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<CachedMedia>)

    @Query("DELETE FROM cached_media WHERE cachedAt < :before") suspend fun evict(before: Long)

    @Query("SELECT COUNT(*) FROM cached_media") suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM cached_media WHERE source = :source")
    suspend fun countBySource(source: String): Int

    /** Delete stale rows for a section — keeps rows newer than keepNewerThan ms timestamp. */
    @Query("DELETE FROM cached_media WHERE section = :section AND sectionCachedAt < :keepNewerThan AND source = 'catalog'")
    suspend fun deleteOldSectionRows(section: String, keepNewerThan: Long)

    /** Evict the oldest N rows globally (by lastAccessedAt) to enforce a total row cap. */
    @Query("DELETE FROM cached_media WHERE tmdbId IN (SELECT tmdbId FROM cached_media ORDER BY lastAccessedAt ASC LIMIT :count)")
    suspend fun evictOldest(count: Int)

    /** Evict search-opened items beyond the keepCount most-recently-accessed. */
    @Query("""DELETE FROM cached_media WHERE source = 'search'
        AND tmdbId NOT IN (SELECT tmdbId FROM cached_media
        WHERE source = 'search' ORDER BY lastAccessedAt DESC LIMIT :keepCount)""")
    suspend fun evictOldestSearch(keepCount: Int)
}

// ── Section personalization weights ───────────────────────────────────────────
@Dao
interface SectionWeightDao {
    @Query("SELECT * FROM section_weights")
    suspend fun getAll(): List<com.axio.reelz.data.model.SectionWeight>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(weight: com.axio.reelz.data.model.SectionWeight)

    @Query("UPDATE section_weights SET taps = taps + 1, lastTappedAt = :now WHERE sectionId = :id")
    suspend fun recordTap(id: String, now: Long = System.currentTimeMillis())
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

    /**
     * Used for the premium download cap. Excludes ERROR rows — a failed
     * download shouldn't permanently eat into a free user's quota.
     */
    @Query("SELECT COUNT(*) FROM downloads WHERE status != 'ERROR'")
    suspend fun countActive(): Int

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

    /**
     * Returns the first non-ERROR download for this content WITH the exact quality.
     * Used for per-quality duplicate guard — allows different resolutions of same movie.
     */
    @Query("""
        SELECT * FROM downloads
        WHERE tmdbId  = :tmdbId
          AND season  = :season
          AND episode = :episode
          AND quality = :quality
          AND status  != 'ERROR'
        LIMIT 1
    """)
    suspend fun findExisting(tmdbId: Int, season: Int, episode: Int, quality: String = ""): DownloadItem?

    /**
     * All non-ERROR downloads for this content (any quality).
     * Used to show all downloaded resolutions of the same movie/episode.
     */
    @Query("""
        SELECT * FROM downloads
        WHERE tmdbId  = :tmdbId
          AND season  = :season
          AND episode = :episode
          AND status  != 'ERROR'
        ORDER BY quality DESC
    """)
    fun getAllForContent(tmdbId: Int, season: Int, episode: Int): Flow<List<DownloadItem>>

    /**
     * All non-ERROR downloads for this content (suspend version for one-shot reads).
     */
    @Query("""
        SELECT * FROM downloads
        WHERE tmdbId  = :tmdbId
          AND season  = :season
          AND episode = :episode
          AND status  != 'ERROR'
        ORDER BY quality DESC
    """)
    suspend fun getAllForContentOnce(tmdbId: Int, season: Int, episode: Int): List<DownloadItem>

    /** Update watch progress when user exits the player. */
    @Query("""
        UPDATE downloads
        SET watchProgressMs = :progressMs,
            durationMs      = :durationMs,
            lastPlayedAt    = :lastPlayedAt,
            lastSelectedQuality = :lastSelectedQuality
        WHERE tmdbId  = :tmdbId
          AND season  = :season
          AND episode = :episode
    """)
    suspend fun updateWatchProgress(
        tmdbId: Int,
        season: Int,
        episode: Int,
        progressMs: Long,
        durationMs: Long,
        lastPlayedAt: Long = System.currentTimeMillis(),
        lastSelectedQuality: String = "",
    )
}

// ── Download Subtitles ────────────────────────────────────────────────────────
/**
 * Manages PERSISTENT subtitles for downloaded videos.
 * These are NEVER used for stream sessions — only for offline playback.
 *
 * Design contract:
 * - INSERT when user downloads a subtitle for a downloaded video.
 * - QUERY by downloadId to load available subs on offline playback.
 * - TOGGLE isEnabled without deleting (user can switch off/on anytime).
 * - DELETE only when the parent DownloadItem is deleted.
 */
@Dao
interface DownloadSubtitleDao {
    /** Get all subtitles for a specific download. */
    @Query("SELECT * FROM download_subtitles WHERE downloadId = :downloadId ORDER BY addedAt ASC")
    fun getForDownload(downloadId: String): Flow<List<DownloadSubtitle>>

    /** Get subtitles by tmdbId + episode key (for playback lookup). */
    @Query("""
        SELECT * FROM download_subtitles
        WHERE tmdbId = :tmdbId AND season = :season AND episode = :episode
        ORDER BY addedAt ASC
    """)
    suspend fun getForContent(tmdbId: Int, season: Int, episode: Int): List<DownloadSubtitle>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sub: DownloadSubtitle)

    /** Toggle the enabled state without deleting — UX: user wants a quick off, not a delete. */
    @Query("UPDATE download_subtitles SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** Delete all subtitles when the parent download is removed. */
    @Query("DELETE FROM download_subtitles WHERE downloadId = :downloadId")
    suspend fun deleteForDownload(downloadId: String)

    @Query("DELETE FROM download_subtitles WHERE id = :id")
    suspend fun delete(id: Long)
}

// ── Transfer history ──────────────────────────────────────────────────────────
@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_history ORDER BY createdAt DESC LIMIT 100")
    fun getAll(): Flow<List<TransferRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(r: TransferRecord)
    @Query("DELETE FROM transfer_history WHERE id = :id") suspend fun delete(id: String)
}

// ── Recent searches ──────────────────────────────────────────────────────────
@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC LIMIT :limit")
    fun getRecent(limit: Int = 15): Flow<List<RecentSearch>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: RecentSearch)

    @Query("DELETE FROM recent_searches WHERE query = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clear()

    /** Trim oldest entries beyond the cap so the table never grows unbounded. */
    @Query("""
        DELETE FROM recent_searches WHERE query IN (
            SELECT query FROM recent_searches ORDER BY searchedAt DESC LIMIT -1 OFFSET :keepCount
        )
    """)
    suspend fun trimToLimit(keepCount: Int = 15)
}

// ── User session (premium) ────────────────────────────────────────────────────
@Dao
interface UserSessionDao {
    /** Always returns the single most recently cached session, if any. */
    @Query("SELECT * FROM user_session ORDER BY cachedAtMs DESC LIMIT 1")
    suspend fun get(): UserSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: UserSession)

    @Query("DELETE FROM user_session")
    suspend fun clear()
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

// ── Migration v2 → v3 ─────────────────────────────────────────────────────────
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN qualityTracksJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE downloads ADD COLUMN resolveRequired INTEGER NOT NULL DEFAULT 1")
    }
}

// ── Migration v3 → v4: persistent download subtitles ─────────────────────────
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS download_subtitles (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                downloadId TEXT NOT NULL,
                tmdbId INTEGER NOT NULL,
                season INTEGER NOT NULL DEFAULT 0,
                episode INTEGER NOT NULL DEFAULT 0,
                language TEXT NOT NULL,
                label TEXT NOT NULL,
                localFilePath TEXT NOT NULL,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                addedAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dlsub_downloadId ON download_subtitles(downloadId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dlsub_content ON download_subtitles(tmdbId, season, episode)")
    }
}

// ── Migration v4 → v5: premium user session ───────────────────────────────────
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_session (
                uid TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                email TEXT NOT NULL DEFAULT '',
                photoUrl TEXT,
                isPremium INTEGER NOT NULL DEFAULT 0,
                plan TEXT NOT NULL DEFAULT '',
                expiresAtMs INTEGER NOT NULL DEFAULT 0,
                subscribedAtMs INTEGER NOT NULL DEFAULT 0,
                cachedAtMs INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

// ── Migration v5 → v6: saved videos ──────────────────────────────────────────
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS saved_videos (
                tmdbId INTEGER PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                posterPath TEXT,
                mediaType TEXT NOT NULL,
                savedAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

// ── Migration v6 → v7: recent searches ────────────────────────────────────────
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS recent_searches (
                query TEXT PRIMARY KEY NOT NULL,
                searchedAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

// ── Migration v7 → v8: multi-resolution + watch progress fields ───────────────
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN watchProgressMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN lastSelectedQuality TEXT NOT NULL DEFAULT ''")
    }
}

// ── Migration v8 → v9: remote config cache moved from DataStore → Room ────────
// Removes dependency on the reelz_remote_cfg DataStore file.
// Config is now stored as a single-row table with proper migration support.
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS remote_config_cache (
                id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                config_json TEXT NOT NULL DEFAULT '{}',
                fetched_at_ms INTEGER NOT NULL DEFAULT 0,
                config_version INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}


// ── Migration v9 → v10: production indices for high-frequency query paths ────
// Adding indices here (not in entity annotations) so they ship as a proper
// migration and don't cause a destructive rebuild on existing installs.
//
// Indices added:
//   • downloads(tmdbId, season, episode) — findExisting(), getAllForContent()
//   • watch_history(watchedAt)           — getPage(), getRecent()
//   • cached_media(mediaType, popularity)— getByType()
//   • watchlist(addedAt)                 — getAll() reactive flow
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Downloads: composite index for per-content queries (duplicate check,
        // getAllForContent). Without this, every duplicate guard does a full
        // table scan — gets painful with thousands of downloads.
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_downloads_content
            ON downloads(tmdbId, season, episode)
        """.trimIndent())

        // Watch history: ordering index so the paginated history and recent
        // history queries don't sort the full table on every load.
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_watch_history_at
            ON watch_history(watchedAt DESC)
        """.trimIndent())

        // Cached media: composite index for the getByType() query which filters
        // on mediaType and orders by popularity. Covers both clauses in one scan.
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_cached_media_type_pop
            ON cached_media(mediaType, popularity DESC)
        """.trimIndent())

        // Watchlist: ordering index for the reactive getAll() flow.
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_watchlist_added
            ON watchlist(addedAt DESC)
        """.trimIndent())

        // Liked media: same pattern.
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_liked_media_at
            ON liked_media(likedAt DESC)
        """.trimIndent())

        // Saved videos: same pattern.
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_saved_videos_at
            ON saved_videos(savedAt DESC)
        """.trimIndent())
    }
}

// ── Migration 10 → 11: new CachedMedia columns + indexes ─────────────────────
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cached_media ADD COLUMN originalLanguage TEXT NOT NULL DEFAULT 'en'")
        db.execSQL("ALTER TABLE cached_media ADD COLUMN voteCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cached_media ADD COLUMN section TEXT NOT NULL DEFAULT 'trending'")
        db.execSQL("ALTER TABLE cached_media ADD COLUMN sectionCachedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cached_media ADD COLUMN source TEXT NOT NULL DEFAULT 'catalog'")
        db.execSQL("ALTER TABLE cached_media ADD COLUMN lastAccessedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cached_media_section ON cached_media(section, sectionCachedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cached_media_source ON cached_media(source, lastAccessedAt)")
    }
}

// ── Migration 11 → 12: section_weights table for personalized feed order ─────
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS section_weights (
                sectionId TEXT PRIMARY KEY NOT NULL,
                taps INTEGER NOT NULL DEFAULT 0,
                lastTappedAt INTEGER NOT NULL DEFAULT 0,
                manualOrder INTEGER NOT NULL DEFAULT 999
            )
        """.trimIndent())
    }
}

// ── Database ──────────────────────────────────────────────────────────────────
@Database(
    entities = [
        WatchlistItem::class,
        WatchHistory::class,
        LikedItem::class,
        SavedVideoItem::class,
        CachedMedia::class,
        DownloadItem::class,
        TransferRecord::class,
        DownloadSubtitle::class,
        UserSession::class,
        RecentSearch::class,
        RemoteConfigCache::class,  // v9: replaces DataStore config cache
        com.axio.reelz.data.model.SectionWeight::class, // v12: feed personalization
    ],
    version = 12,
    exportSchema = false,
)
@TypeConverters(com.axio.reelz.data.model.MediaConverters::class)
abstract class ReelzDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun likedDao(): LikedDao
    abstract fun savedVideoDao(): SavedVideoDao
    abstract fun cachedMediaDao(): CachedMediaDao
    abstract fun downloadDao(): DownloadDao
    abstract fun downloadSubtitleDao(): DownloadSubtitleDao
    abstract fun transferDao(): TransferDao
    abstract fun userSessionDao(): UserSessionDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun remoteConfigCacheDao(): RemoteConfigCacheDao  // v9
    abstract fun sectionWeightDao(): SectionWeightDao          // v12
}
