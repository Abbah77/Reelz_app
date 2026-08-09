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
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int = 20, offset: Int = 0): List<WatchHistory>

    @Query("SELECT COUNT(*) FROM watch_history")
    suspend fun count(): Int

    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT 20")
    fun getRecent(): Flow<List<WatchHistory>>

    @Query("SELECT * FROM watch_history WHERE key = :key LIMIT 1")
    suspend fun get(key: String): WatchHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(i: WatchHistory)

    @Query("DELETE FROM watch_history WHERE key = :key") suspend fun delete(key: String)

    @Query("DELETE FROM watch_history") suspend fun clear()

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

// ── Saved videos ──────────────────────────────────────────────────────────────
@Dao
interface SavedVideoDao {
    @Query("SELECT * FROM saved_videos ORDER BY savedAt DESC")
    fun getAll(): Flow<List<SavedVideoItem>>
    @Query("SELECT * FROM saved_videos WHERE tmdbId = :id LIMIT 1")
    suspend fun get(id: Int): SavedVideoItem?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(i: SavedVideoItem)
    @Query("DELETE FROM saved_videos WHERE tmdbId = :id") suspend fun delete(id: Int)
}

// ── Metadata cache — the core of the local-first catalog ─────────────────────
@Dao
interface CachedMediaDao {

    // ── Home sections ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM cached_media WHERE mediaType = :type ORDER BY popularity DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 100): List<CachedMedia>

    @Query("SELECT * FROM cached_media WHERE section = :section ORDER BY popularity DESC LIMIT :limit")
    suspend fun getBySection(section: String, limit: Int = 20): List<CachedMedia>

    @Query("SELECT * FROM cached_media WHERE tmdbId = :id LIMIT 1")
    suspend fun get(id: Int): CachedMedia?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedMedia>)

    @Query("DELETE FROM cached_media WHERE cachedAt < :before")
    suspend fun evict(before: Long)

    @Query("SELECT COUNT(*) FROM cached_media")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM cached_media WHERE source = :source")
    suspend fun countBySource(source: String): Int

    @Query("DELETE FROM cached_media WHERE section = :section AND sectionCachedAt < :keepNewerThan AND source = 'catalog'")
    suspend fun deleteOldSectionRows(section: String, keepNewerThan: Long)

    @Query("SELECT COALESCE(MAX(sectionCachedAt), 0) FROM cached_media WHERE section = :section AND source = 'catalog'")
    suspend fun getSectionTimestamp(section: String): Long

    @Query("SELECT COALESCE(MAX(sectionCachedAt), 0) FROM cached_media WHERE source = 'catalog'")
    suspend fun getNewestSectionTimestamp(): Long

    // ── Infinite scroll — pagination cursor approach ───────────────────────────
    // Returns a page of catalog items ordered by popularity, offset-based.
    // The UI calls this in batches; when empty → switch to TMDB.
    @Query("""
        SELECT * FROM cached_media
        WHERE source = 'catalog'
        ORDER BY popularity DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPopularPage(limit: Int, offset: Int): List<CachedMedia>

    // ── Search — FTS-backed fast text search ──────────────────────────────────
    // Uses the fts_cached_media virtual table for sub-millisecond text search
    // across all 10K rows with MATCH syntax.
    @Query("""
        SELECT cm.* FROM cached_media cm
        INNER JOIN fts_cached_media fts ON cm.tmdbId = fts.rowid
        WHERE fts_cached_media MATCH :query
        ORDER BY cm.popularity DESC
        LIMIT :limit
    """)
    suspend fun searchFts(query: String, limit: Int = 40): List<CachedMedia>

    // Fallback LIKE search when FTS is unavailable
    @Query("""
        SELECT * FROM cached_media
        WHERE title LIKE '%' || :query || '%'
           OR overview LIKE '%' || :query || '%'
        ORDER BY popularity DESC
        LIMIT :limit
    """)
    suspend fun searchLike(query: String, limit: Int = 40): List<CachedMedia>

    // ── Explore queries ────────────────────────────────────────────────────────
    @Query("SELECT * FROM cached_media WHERE mediaType = :mediaType ORDER BY popularity DESC")
    suspend fun getByMediaType(mediaType: String): List<CachedMedia>

    // ── Eviction ──────────────────────────────────────────────────────────────

    // Evict the N oldest rows globally (by lastAccessedAt) — used for soft limit enforcement
    @Query("DELETE FROM cached_media WHERE tmdbId IN (SELECT tmdbId FROM cached_media ORDER BY lastAccessedAt ASC LIMIT :count)")
    suspend fun evictOldest(count: Int)

    // Evict search items beyond the keepCount most-recently-accessed
    @Query("""DELETE FROM cached_media WHERE source = 'search'
        AND tmdbId NOT IN (SELECT tmdbId FROM cached_media
        WHERE source = 'search' ORDER BY lastAccessedAt DESC LIMIT :keepCount)""")
    suspend fun evictOldestSearch(keepCount: Int)

    // Update lastAccessedAt so LRU correctly tracks recently-viewed items
    @Query("UPDATE cached_media SET lastAccessedAt = :now WHERE tmdbId = :id")
    suspend fun touchItem(id: Int, now: Long = System.currentTimeMillis())
}

// ── Catalog page cursor — resumable infinite scroll ────────────────────────────
// Persists which TMDB page was last fetched so after a cold restart the scroll
// engine immediately knows where to continue instead of refetching from page 1.
@Dao
interface CatalogPageCursorDao {
    @Query("SELECT * FROM catalog_page_cursor WHERE mediaType = :type LIMIT 1")
    suspend fun get(type: String): CatalogPageCursor?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: CatalogPageCursor)

    @Query("UPDATE catalog_page_cursor SET nextPage = :page, updatedAt = :now WHERE mediaType = :type")
    suspend fun advance(type: String, page: Int, now: Long = System.currentTimeMillis())
}

// ── Section personalization weights ───────────────────────────────────────────
@Dao
interface SectionWeightDao {
    @Query("SELECT * FROM section_weights")
    suspend fun getAll(): List<SectionWeight>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(weight: SectionWeight)

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
        id: String, status: String, bytes: Long,
        speedBps: Long = 0, segsDone: Int = 0, segsTotal: Int = 0, localPlaylist: String = "",
    )

    @Query("""
        UPDATE downloads
        SET status = :status, sizeBytes = :totalBytes, segmentDir = :segDir
        WHERE id = :id
    """)
    suspend fun updateMetadata(id: String, status: String, totalBytes: Long, segDir: String = "")

    @Query("""
        UPDATE downloads
        SET status = :status, filePath = :path, completedAt = :at,
            networkSpeedBps = 0, segmentDir = '', localPlaylistPath = '', resolveRequired = 0
        WHERE id = :id
    """)
    suspend fun markDone(id: String, status: String, path: String, at: Long)

    @Query("UPDATE downloads SET status = :status, resolveRequired = 1 WHERE id = :id")
    suspend fun markPaused(id: String, status: String = DownloadStatus.PAUSED.name)

    @Query("UPDATE downloads SET streamUrl = :url, headers = :headersJson, resolveRequired = 0 WHERE id = :id")
    suspend fun updateStreamUrl(id: String, url: String, headersJson: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("""
        SELECT * FROM downloads
        WHERE tmdbId = :tmdbId AND season = :season AND episode = :episode
          AND quality = :quality AND status != 'ERROR'
        LIMIT 1
    """)
    suspend fun findExisting(tmdbId: Int, season: Int, episode: Int, quality: String = ""): DownloadItem?

    @Query("""
        SELECT * FROM downloads
        WHERE tmdbId = :tmdbId AND season = :season AND episode = :episode AND status != 'ERROR'
        ORDER BY quality DESC
    """)
    fun getAllForContent(tmdbId: Int, season: Int, episode: Int): Flow<List<DownloadItem>>

    @Query("""
        SELECT * FROM downloads
        WHERE tmdbId = :tmdbId AND season = :season AND episode = :episode AND status != 'ERROR'
        ORDER BY quality DESC
    """)
    suspend fun getAllForContentOnce(tmdbId: Int, season: Int, episode: Int): List<DownloadItem>

    @Query("""
        UPDATE downloads
        SET watchProgressMs = :progressMs, durationMs = :durationMs,
            lastPlayedAt = :lastPlayedAt, lastSelectedQuality = :lastSelectedQuality
        WHERE tmdbId = :tmdbId AND season = :season AND episode = :episode
    """)
    suspend fun updateWatchProgress(
        tmdbId: Int, season: Int, episode: Int,
        progressMs: Long, durationMs: Long,
        lastPlayedAt: Long = System.currentTimeMillis(),
        lastSelectedQuality: String = "",
    )
}

// ── Download Subtitles ────────────────────────────────────────────────────────
@Dao
interface DownloadSubtitleDao {
    @Query("SELECT * FROM download_subtitles WHERE downloadId = :downloadId ORDER BY addedAt ASC")
    fun getForDownload(downloadId: String): Flow<List<DownloadSubtitle>>

    @Query("""
        SELECT * FROM download_subtitles
        WHERE tmdbId = :tmdbId AND season = :season AND episode = :episode
        ORDER BY addedAt ASC
    """)
    suspend fun getForContent(tmdbId: Int, season: Int, episode: Int): List<DownloadSubtitle>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sub: DownloadSubtitle)

    @Query("UPDATE download_subtitles SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

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

// ── Recent searches ───────────────────────────────────────────────────────────
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

    @Query("""
        DELETE FROM recent_searches WHERE query IN (
            SELECT query FROM recent_searches ORDER BY searchedAt DESC LIMIT -1 OFFSET :keepCount
        )
    """)
    suspend fun trimToLimit(keepCount: Int = 15)
}

// ── User session ──────────────────────────────────────────────────────────────
@Dao
interface UserSessionDao {
    @Query("SELECT * FROM user_session ORDER BY cachedAtMs DESC LIMIT 1")
    suspend fun get(): UserSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: UserSession)

    @Query("DELETE FROM user_session")
    suspend fun clear()
}

// ── Cached genres ─────────────────────────────────────────────────────────────
@Dao
interface CachedGenreDao {
    @Query("SELECT * FROM cached_genres WHERE mediaType = :type ORDER BY name ASC")
    suspend fun getByType(type: String): List<CachedGenre>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(genres: List<CachedGenre>)

    @Query("SELECT COUNT(*) FROM cached_genres WHERE mediaType = :type")
    suspend fun count(type: String): Int

    @Query("SELECT COALESCE(MIN(cachedAtMs), 0) FROM cached_genres WHERE mediaType = :type")
    suspend fun oldestTimestamp(type: String): Long
}

// ── Remote config cache ────────────────────────────────────────────────────────
// (Unchanged — kept for compatibility with RemoteConfigRepository)
@Dao
interface RemoteConfigCacheDao {
    @Query("SELECT * FROM remote_config_cache WHERE id = 1 LIMIT 1")
    suspend fun get(): RemoteConfigCache?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: RemoteConfigCache)
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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN qualityTracksJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE downloads ADD COLUMN resolveRequired INTEGER NOT NULL DEFAULT 1")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS download_subtitles (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                downloadId TEXT NOT NULL, tmdbId INTEGER NOT NULL,
                season INTEGER NOT NULL DEFAULT 0, episode INTEGER NOT NULL DEFAULT 0,
                language TEXT NOT NULL, label TEXT NOT NULL, localFilePath TEXT NOT NULL,
                isEnabled INTEGER NOT NULL DEFAULT 1, addedAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dlsub_downloadId ON download_subtitles(downloadId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dlsub_content ON download_subtitles(tmdbId, season, episode)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_session (
                uid TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL DEFAULT '',
                email TEXT NOT NULL DEFAULT '', photoUrl TEXT,
                isPremium INTEGER NOT NULL DEFAULT 0, plan TEXT NOT NULL DEFAULT '',
                expiresAtMs INTEGER NOT NULL DEFAULT 0, subscribedAtMs INTEGER NOT NULL DEFAULT 0,
                cachedAtMs INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS saved_videos (
                tmdbId INTEGER PRIMARY KEY NOT NULL, title TEXT NOT NULL,
                posterPath TEXT, mediaType TEXT NOT NULL, savedAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS recent_searches (
                query TEXT PRIMARY KEY NOT NULL, searchedAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN watchProgressMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN lastSelectedQuality TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS remote_config_cache (
                id INTEGER PRIMARY KEY NOT NULL DEFAULT 1, config_json TEXT NOT NULL DEFAULT '{}',
                fetched_at_ms INTEGER NOT NULL DEFAULT 0, config_version INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_content ON downloads(tmdbId, season, episode)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_watch_history_at ON watch_history(watchedAt DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cached_media_type_pop ON cached_media(mediaType, popularity DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_watchlist_added ON watchlist(addedAt DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_liked_media_at ON liked_media(likedAt DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_saved_videos_at ON saved_videos(savedAt DESC)")
    }
}

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

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS section_weights (
                sectionId TEXT PRIMARY KEY NOT NULL, taps INTEGER NOT NULL DEFAULT 0,
                lastTappedAt INTEGER NOT NULL DEFAULT 0, manualOrder INTEGER NOT NULL DEFAULT 999
            )
        """.trimIndent())
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cached_genres (
                id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL,
                mediaType TEXT NOT NULL DEFAULT 'movie', cachedAtMs INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cached_genres_type ON cached_genres(mediaType)")
    }
}

// ── Migration 13 → 14: local-first catalog upgrades ──────────────────────────
// Adds:
//   1. catalogPage column to cached_media — enables resumable TMDB pagination
//   2. catalog_page_cursor table — persists next TMDB page per media type
//   3. FTS5 virtual table on cached_media — sub-ms full-text search across 10K rows
//   4. Additional performance indices
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── 1. Pagination cursor column ────────────────────────────────────────
        db.execSQL("ALTER TABLE cached_media ADD COLUMN catalogPage INTEGER NOT NULL DEFAULT 0")

        // ── 2. Catalog page cursor table ──────────────────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS catalog_page_cursor (
                mediaType TEXT PRIMARY KEY NOT NULL,
                nextPage INTEGER NOT NULL DEFAULT 1,
                updatedAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // ── 3. FTS5 virtual table — blazing-fast local search ─────────────────
        // Indexes title + overview for MATCH queries.
        // content='cached_media' means FTS reads from the real table (no data duplication).
        // content_rowid='tmdbId' links FTS rowid to tmdbId for JOIN in searchFts().
        // tokenize="unicode61 remove_diacritics 2" handles "café" → "cafe" matching.
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS fts_cached_media USING fts5(
                title, overview,
                content='cached_media',
                content_rowid='tmdbId',
                tokenize="unicode61 remove_diacritics 2"
            )
        """.trimIndent())

        // Populate FTS from existing rows
        db.execSQL("INSERT INTO fts_cached_media(rowid, title, overview) SELECT tmdbId, title, overview FROM cached_media")

        // Triggers to keep FTS in sync with the main table (insert/update/delete)
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS fts_cached_media_ai
            AFTER INSERT ON cached_media BEGIN
                INSERT INTO fts_cached_media(rowid, title, overview) VALUES (new.tmdbId, new.title, new.overview);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS fts_cached_media_ad
            AFTER DELETE ON cached_media BEGIN
                INSERT INTO fts_cached_media(fts_cached_media, rowid, title, overview)
                VALUES ('delete', old.tmdbId, old.title, old.overview);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS fts_cached_media_au
            AFTER UPDATE ON cached_media BEGIN
                INSERT INTO fts_cached_media(fts_cached_media, rowid, title, overview)
                VALUES ('delete', old.tmdbId, old.title, old.overview);
                INSERT INTO fts_cached_media(rowid, title, overview) VALUES (new.tmdbId, new.title, new.overview);
            END
        """.trimIndent())

        // ── 4. Additional performance indices ─────────────────────────────────
        // Infinite scroll: offset-based pagination index
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cached_media_pop_src ON cached_media(source, popularity DESC)")
        // LRU eviction: find oldest items fast
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cached_media_lru ON cached_media(lastAccessedAt ASC)")
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
        CatalogPageCursor::class,       // v14: resumable pagination
        DownloadItem::class,
        TransferRecord::class,
        DownloadSubtitle::class,
        UserSession::class,
        RecentSearch::class,
        RemoteConfigCache::class,
        SectionWeight::class,
        CachedGenre::class,
    ],
    version = 14,
    exportSchema = false,
)
@TypeConverters(MediaConverters::class)
abstract class ReelzDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun likedDao(): LikedDao
    abstract fun savedVideoDao(): SavedVideoDao
    abstract fun cachedMediaDao(): CachedMediaDao
    abstract fun catalogPageCursorDao(): CatalogPageCursorDao  // v14
    abstract fun downloadDao(): DownloadDao
    abstract fun downloadSubtitleDao(): DownloadSubtitleDao
    abstract fun transferDao(): TransferDao
    abstract fun userSessionDao(): UserSessionDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun remoteConfigCacheDao(): RemoteConfigCacheDao
    abstract fun sectionWeightDao(): SectionWeightDao
    abstract fun cachedGenreDao(): CachedGenreDao
}
