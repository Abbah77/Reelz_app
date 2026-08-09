package com.axio.reelz.data.local

import android.database.Cursor
import com.axio.reelz.data.model.CachedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LocalSearchHelper — executes FTS5 queries via raw SupportSQLiteDatabase.
 *
 * WHY NOT @Query IN THE DAO:
 *   Room's KSP annotation processor validates every @Query against the compile-time
 *   schema. FTS5 virtual tables (fts_cached_media) do not exist in that schema —
 *   they are created at runtime by MIGRATION_13_14. KSP therefore always rejects
 *   any @Query that references them, with:
 *     "no such table: fts_cached_media"
 *
 *   The correct solution is to execute FTS5 queries through the raw
 *   SupportSQLiteDatabase, which talks directly to SQLite at runtime when the
 *   virtual table already exists.
 *
 * QUERY STRATEGY:
 *   Each search word gets a prefix wildcard (* suffix) so "bat" matches "batman",
 *   "battery", etc. The FTS5 unicode61 tokenizer folds diacritics so "café"
 *   matches "cafe". Results are joined back to cached_media for the full row.
 *
 * FALLBACK:
 *   If FTS5 is unavailable (e.g. the device somehow has an old DB that hasn't
 *   migrated yet), we fall back to the DAO's LIKE query automatically.
 */
@Singleton
class LocalSearchHelper @Inject constructor(
    private val db: ReelzDatabase,
    private val dao: CachedMediaDao,
) {
    /**
     * Search the local catalog using FTS5 for maximum speed.
     * Returns up to [limit] results ordered by popularity.
     * Falls back to LIKE search if FTS5 is unavailable.
     */
    suspend fun search(query: String, limit: Int = 40): List<CachedMedia> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()

            // Build FTS5-safe MATCH string: "bat man" → "bat* man*"
            val matchQuery = query.trim()
                .split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                // Escape FTS5 special chars (", -, *, ., :) then add prefix wildcard
                .joinToString(" ") { word ->
                    val safe = word.replace("\"", "").replace("-", " ").trim()
                    if (safe.isEmpty()) "" else "\"$safe\"*"
                }
                .trim()

            if (matchQuery.isEmpty()) return@withContext emptyList()

            try {
                searchFts5(matchQuery, limit)
            } catch (_: Exception) {
                // FTS5 unavailable or not yet migrated — fall back to LIKE
                try { dao.searchLike(query.trim(), limit) }
                catch (_: Exception) { emptyList() }
            }
        }

    /**
     * Executes the FTS5 MATCH query via raw SQLite cursor.
     *
     * SQL explanation:
     *   SELECT cm.* FROM cached_media cm
     *   INNER JOIN fts_cached_media fts ON cm.tmdbId = fts.rowid
     *   WHERE fts_cached_media MATCH ?
     *   ORDER BY cm.popularity DESC
     *   LIMIT ?
     *
     * The JOIN links the FTS rowid (= tmdbId per our content_rowid setting)
     * back to the real cached_media row so we get all columns.
     */
    private fun searchFts5(matchQuery: String, limit: Int): List<CachedMedia> {
        val sql = """
            SELECT cm.tmdbId, cm.title, cm.overview, cm.posterPath, cm.backdropPath,
                   cm.releaseDate, cm.voteAverage, cm.popularity, cm.genreIds,
                   cm.mediaType, cm.originalLanguage, cm.voteCount, cm.section,
                   cm.sectionCachedAt, cm.source, cm.lastAccessedAt, cm.cachedAt,
                   cm.catalogPage
            FROM cached_media cm
            INNER JOIN fts_cached_media fts ON cm.tmdbId = fts.rowid
            WHERE fts_cached_media MATCH ?
            ORDER BY cm.popularity DESC
            LIMIT ?
        """.trimIndent()

        val cursor: Cursor = db.openHelper.readableDatabase.query(sql, arrayOf(matchQuery, limit))
        return cursor.use { c ->
            val results = mutableListOf<CachedMedia>()
            if (!c.moveToFirst()) return@use results

            // Column indices — must match the SELECT order above exactly
            val iId          = c.getColumnIndex("tmdbId")
            val iTitle       = c.getColumnIndex("title")
            val iOverview    = c.getColumnIndex("overview")
            val iPoster      = c.getColumnIndex("posterPath")
            val iBackdrop    = c.getColumnIndex("backdropPath")
            val iRelease     = c.getColumnIndex("releaseDate")
            val iVoteAvg     = c.getColumnIndex("voteAverage")
            val iPopularity  = c.getColumnIndex("popularity")
            val iGenreIds    = c.getColumnIndex("genreIds")
            val iMediaType   = c.getColumnIndex("mediaType")
            val iOrigLang    = c.getColumnIndex("originalLanguage")
            val iVoteCount   = c.getColumnIndex("voteCount")
            val iSection     = c.getColumnIndex("section")
            val iSectionAt   = c.getColumnIndex("sectionCachedAt")
            val iSource      = c.getColumnIndex("source")
            val iLastAccess  = c.getColumnIndex("lastAccessedAt")
            val iCachedAt    = c.getColumnIndex("cachedAt")
            val iCatalogPage = c.getColumnIndex("catalogPage")

            do {
                results.add(CachedMedia(
                    tmdbId          = c.getInt(iId),
                    title           = c.getString(iTitle) ?: "",
                    overview        = c.getString(iOverview) ?: "",
                    posterPath      = if (c.isNull(iPoster)) null else c.getString(iPoster),
                    backdropPath    = if (c.isNull(iBackdrop)) null else c.getString(iBackdrop),
                    releaseDate     = if (c.isNull(iRelease)) null else c.getString(iRelease),
                    voteAverage     = c.getDouble(iVoteAvg),
                    popularity      = c.getDouble(iPopularity),
                    genreIds        = c.getString(iGenreIds) ?: "[]",
                    mediaType       = c.getString(iMediaType) ?: "MOVIE",
                    originalLanguage= c.getString(iOrigLang) ?: "en",
                    voteCount       = if (iVoteCount >= 0) c.getInt(iVoteCount) else 0,
                    section         = if (iSection >= 0) c.getString(iSection) ?: "trending" else "trending",
                    sectionCachedAt = if (iSectionAt >= 0) c.getLong(iSectionAt) else 0L,
                    source          = if (iSource >= 0) c.getString(iSource) ?: "catalog" else "catalog",
                    lastAccessedAt  = if (iLastAccess >= 0) c.getLong(iLastAccess) else 0L,
                    cachedAt        = if (iCachedAt >= 0) c.getLong(iCachedAt) else 0L,
                    catalogPage     = if (iCatalogPage >= 0) c.getInt(iCatalogPage) else 0,
                ))
            } while (c.moveToNext())
            results
        }
    }
}
