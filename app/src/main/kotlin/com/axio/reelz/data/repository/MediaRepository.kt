package com.axio.reelz.data.repository

import android.util.LruCache
import com.axio.reelz.data.local.*
import com.axio.reelz.data.local.CachedGenreDao
import com.axio.reelz.data.model.*
import com.axio.reelz.data.remote.api.TmdbApi
import com.axio.reelz.data.remote.dto.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    private val api: TmdbApi,
    private val cachedMediaDao: CachedMediaDao,
    private val watchlistDao: WatchlistDao,
    private val historyDao: WatchHistoryDao,
    private val likedDao: LikedDao,
    private val sectionWeightDao: SectionWeightDao,
    private val cachedGenreDao: CachedGenreDao,
) {

    // ── Detail memory cache ───────────────────────────────────────────────────
    // Session-only, 12h TTL, max 50 entries. One TMDB call per title per session.
    private val detailCache = LruCache<Int, Pair<MediaDetail, Long>>(50)

    private fun getCachedDetail(tmdbId: Int): MediaDetail? {
        val entry = detailCache[tmdbId] ?: return null
        val ageMs = System.currentTimeMillis() - entry.second
        if (ageMs > 12 * 3_600_000L) { detailCache.remove(tmdbId); return null }
        return entry.first
    }

    private fun putDetailCache(detail: MediaDetail) {
        detailCache.put(detail.tmdbId, Pair(detail, System.currentTimeMillis()))
    }

    // ── Cache freshness ───────────────────────────────────────────────────────

    /**
     * How many milliseconds ago the freshest catalog section was cached.
     * Returns Long.MAX_VALUE if no catalog rows exist (forces a refresh).
     */
    suspend fun cacheAgeMs(): Long {
        val newest = cachedMediaDao.getNewestSectionTimestamp()
        return if (newest == 0L) Long.MAX_VALUE
        else System.currentTimeMillis() - newest
    }

    // ── Home sections ─────────────────────────────────────────────────────────

    /**
     * Returns true only when the cache has USABLE data — i.e. at least one
     * section that is in DEFAULT_ORDER has rows. A plain count() > 0 can be
     * misleading: rows may all have an orphaned section id from an old schema,
     * meaning buildSectionsFromCache() returns an empty list and the UI shows
     * nothing forever (the "empty loop" bug). We probe the first 3 sections of
     * DEFAULT_ORDER to confirm real data exists.
     */
    suspend fun hasCachedData(): Boolean {
        if (cachedMediaDao.count() == 0) return false
        // Verify at least one canonical section actually has rows
        val probe = Section.DEFAULT_ORDER.take(3)
        return probe.any { cachedMediaDao.getBySection(it.id, 1).isNotEmpty() }
    }

    suspend fun getHomeSectionsFromCacheOnly(): List<HomeSection> = buildSectionsFromCache()

    suspend fun getHomeSectionsFromNetwork(): List<HomeSection> {
        val all = mutableListOf<HomeSection>()
        streamHomeSections { batch -> all.addAll(batch) }
        return all
    }

    suspend fun getHomeSections(forceRefresh: Boolean = false): List<HomeSection> {
        val hasUsable = hasCachedData()
        if (!forceRefresh && hasUsable) return buildSectionsFromCache()
        return try {
            getHomeSectionsFromNetwork()
        } catch (e: Exception) {
            if (hasUsable) buildSectionsFromCache() else throw e
        }
    }

    /**
     * Fetch all 21 home sections in 3 batches of 7, with a 300ms gap between batches.
     *
     * WHY BATCHED:
     *   TMDB rate-limits at 40 req/10s per API key. With only 3 keys, firing all 21
     *   calls simultaneously can saturate the rate-limit window and cause any request
     *   that arrives immediately after (e.g. Explore's discoverMovies call) to fail
     *   with a 429. Batching keeps the burst ≤ 7 req/key per window and leaves
     *   headroom for other screens.
     *
     * WHY STREAMING (onBatch callback):
     *   Each batch completes and emits to the UI before the next batch starts.
     *   The skeleton loading stays visible until Batch 1 resolves (~400ms), then
     *   sections populate progressively — exactly like a staggered reveal.
     *   No single large wait before anything appears.
     *
     * Batch layout (priority order — highest-value sections first):
     *   Batch 1 — Trending, New Releases, Popular Movies, Hot TV, Top Rated, K-Drama, Anime
     *   Batch 2 — Indian, Nollywood, C-Drama, Action, Comedy, Romance, Horror
     *   Batch 3 — Crime, Drama, Turkish, Documentary, Family, Sci-Fi, African
     */
    suspend fun streamHomeSections(onBatch: suspend (List<HomeSection>) -> Unit) {
        val batches: List<List<Pair<Section, suspend () -> List<Media>>>> = listOf(
            // ── Batch 1: highest priority (hero candidates live here) ─────────
            listOf(
                Section.TRENDING       to { api.getTrendingMovies().results.map  { it.toMedia(MediaType.MOVIE) } },
                Section.NEW_RELEASES   to { api.getNowPlayingMovies().results.map { it.toMedia(MediaType.MOVIE) } },
                Section.POPULAR_MOVIES to { api.getPopularMovies().results.map   { it.toMedia(MediaType.MOVIE) } },
                Section.HOT_TV         to { api.getPopularTv().results.map       { it.toMedia(MediaType.TV)    } },
                Section.TOP_RATED      to { api.getTopRatedMovies().results.map  { it.toMedia(MediaType.MOVIE) } },
                Section.KDRAMA         to { api.getKDrama().results.map          { it.toMedia(MediaType.TV)    } },
                Section.ANIME          to { api.getAnime().results.map           { it.toMedia(MediaType.TV)    } },
            ),
            // ── Batch 2: regional + genre picks ──────────────────────────────
            listOf(
                Section.INDIAN         to { api.getIndianMovies().results.map    { it.toMedia(MediaType.MOVIE) } },
                Section.NOLLYWOOD      to { api.getNollywood().results.map       { it.toMedia(MediaType.MOVIE) } },
                Section.CDRAMA         to { api.getCDrama().results.map          { it.toMedia(MediaType.TV)    } },
                Section.ACTION         to { api.discoverMovies(28).results.map   { it.toMedia(MediaType.MOVIE) } },
                Section.COMEDY         to { api.discoverMovies(35).results.map   { it.toMedia(MediaType.MOVIE) } },
                Section.ROMANCE        to { api.discoverMovies(10749).results.map{ it.toMedia(MediaType.MOVIE) } },
                Section.HORROR         to { api.discoverMovies(27).results.map   { it.toMedia(MediaType.MOVIE) } },
            ),
            // ── Batch 3: deep catalogue ───────────────────────────────────────
            listOf(
                Section.CRIME          to { api.discoverMovies(80).results.map   { it.toMedia(MediaType.MOVIE) } },
                Section.DRAMA          to { api.discoverMovies(18).results.map   { it.toMedia(MediaType.MOVIE) } },
                Section.TURKISH        to { api.getTurkishDrama().results.map    { it.toMedia(MediaType.TV)    } },
                Section.DOCUMENTARY    to { api.discoverMovies(99).results.map   { it.toMedia(MediaType.MOVIE) } },
                Section.FAMILY         to { api.discoverMovies(10751).results.map{ it.toMedia(MediaType.MOVIE) } },
                Section.SCIFI          to { api.discoverMovies(878).results.map  { it.toMedia(MediaType.MOVIE) } },
                Section.AFRICAN        to { api.getAfricanContent().results.map  { it.toMedia(MediaType.MOVIE) } },
            ),
        )

        val allSections = mutableListOf<HomeSection>()

        batches.forEachIndexed { batchIndex, entries ->
            // 300ms gap between batches — lets the rate-limit window partially reset
            // and gives Explore (and other screens) headroom to fire their own calls.
            if (batchIndex > 0) delay(300)

            val batchSections = coroutineScope {
                entries.map { (section, fetch) ->
                    async {
                        val items = runCatching { fetch() }.getOrDefault(emptyList())
                        if (items.isEmpty()) null else HomeSection(section.label, items)
                    }
                }.mapNotNull { it.await() }
            }

            if (batchSections.isNotEmpty()) {
                allSections.addAll(batchSections)
                onBatch(batchSections)
                // Cache each batch immediately so even a partial load is persisted
                cacheHomeSections(batchSections)
            }
        }
    }

    /**
     * Build sections from Room cache using the section column.
     * Applies personalized sort if the user has recorded any taps.
     * Falls back to Section.DEFAULT_ORDER for new users.
     */
    private suspend fun buildSectionsFromCache(): List<HomeSection> {
        val weights = sectionWeightDao.getAll().associateBy { it.sectionId }

        fun scoreSection(sectionId: String): Float {
            val w = weights[sectionId] ?: return 0f
            val recency = if (w.lastTappedAt == 0L) 0f
                else 1f / (1f + (System.currentTimeMillis() - w.lastTappedAt) / 86_400_000f)
            return (w.taps * 0.7f) + (recency * 30f)
        }

        // Sort sections by user score; fall back to DEFAULT_ORDER for equal scores
        val orderedSections = if (weights.isEmpty()) {
            Section.DEFAULT_ORDER
        } else {
            Section.DEFAULT_ORDER.sortedByDescending { scoreSection(it.id) }
        }

        return orderedSections.mapNotNull { section ->
            val items = cachedMediaDao.getBySection(section.id, 20)
            if (items.isEmpty()) null
            else HomeSection(section.label, items.map { it.toMedia() })
        }
    }

    /**
     * Cache each section's items tagged with their section id and a fresh timestamp.
     * Stale rows for a section are deleted before inserting the new batch.
     * Global row cap enforced at 10,000 rows.
     */
    private suspend fun cacheHomeSections(sections: List<HomeSection>) {
        val sectionByLabel = Section.values().associateBy { it.label }
        val now = System.currentTimeMillis()

        sections.forEach { hs ->
            val sEnum = sectionByLabel[hs.title] ?: Section.TRENDING
            val items = hs.items.map { media ->
                media.toCached().copy(
                    section = sEnum.id,
                    sectionCachedAt = now,
                    source = "catalog",
                    lastAccessedAt = now,
                )
            }
            cachedMediaDao.insertAll(items)
            // Remove stale rows for this section (replaced by new batch)
            cachedMediaDao.deleteOldSectionRows(sEnum.id, keepNewerThan = now - 1000L)
        }

        // Enforce global cap of 10,000 rows
        val total = cachedMediaDao.count()
        if (total > 10_000) {
            cachedMediaDao.evictOldest(total - 10_000)
        }
    }

    // ── Cache-first infinite scroll ───────────────────────────────────────────

    /**
     * Returns up to [limit] catalog-sourced cached items whose tmdbId is NOT in [excludeIds],
     * ordered by popularity. Used by BrowseViewModel.loadMoreInfinite() to serve cached
     * content before hitting TMDB. The exclusion is applied in Kotlin because Room struggles
     * with large NOT IN sets — the raw SQL page is fetched then filtered in memory.
     */
    suspend fun getCachePageExcluding(excludeIds: Set<Int>, limit: Int = 20): List<Media> {
        var offset = 0
        val result = mutableListOf<CachedMedia>()
        // Page through in batches of 100 until we collect enough or exhaust cache
        while (result.size < limit) {
            val batch = cachedMediaDao.getPopularPage(limit = 100, offset = offset)
            if (batch.isEmpty()) break
            result.addAll(batch.filter { it.tmdbId !in excludeIds })
            offset += 100
            if (result.size >= limit) break
        }
        return result.take(limit).map { it.toMedia() }
    }

    // ── Explore cache query ───────────────────────────────────────────────────

    /**
     * Answer an ExploreFilters query entirely from the local Room cache.
     *
     * Covers: mediaType, genreIds (any-match), language, ratingFrom, yearFrom, yearTo,
     *         voteCount minimum (for vote_average sort), and all sort orders.
     *
     * Does NOT cover: runtimeFrom/runtimeTo, originCountry — those fields are not
     * stored in CachedMedia, so callers that use them will see a partial result and
     * should fall through to TMDB.
     *
     * Returns empty list if the cache has no rows at all for the media type.
     */
    suspend fun queryExploreFromCache(
        mediaType: String,
        genreIds: Set<Int> = emptySet(),
        sortBy: String = "popularity.desc",
        yearFrom: Int? = null,
        yearTo: Int? = null,
        ratingFrom: Float? = null,
        language: String? = null,
    ): List<Media> {
        var rows = cachedMediaDao.getByMediaType(mediaType)
        if (rows.isEmpty()) return emptyList()

        // Genre filter — keep rows that share at least one genre with the filter set
        if (genreIds.isNotEmpty()) {
            rows = rows.filter { cached ->
                val ids: List<Int> = try {
                    com.google.gson.Gson().fromJson(
                        cached.genreIds,
                        object : com.google.gson.reflect.TypeToken<List<Int>>() {}.type,
                    ) ?: emptyList()
                } catch (_: Exception) { emptyList() }
                ids.any { it in genreIds }
            }
        }

        // Language filter
        if (language != null) {
            rows = rows.filter { it.originalLanguage == language }
        }

        // Rating floor
        if (ratingFrom != null) {
            rows = rows.filter { it.voteAverage >= ratingFrom }
        }

        // Year range — releaseDate is "YYYY-MM-DD" or "YYYY" or null
        if (yearFrom != null) {
            rows = rows.filter { cached ->
                val yr = cached.releaseDate?.take(4)?.toIntOrNull() ?: 0
                yr >= yearFrom
            }
        }
        if (yearTo != null) {
            rows = rows.filter { cached ->
                val yr = cached.releaseDate?.take(4)?.toIntOrNull() ?: 9999
                yr <= yearTo
            }
        }

        // Sort
        rows = when (sortBy) {
            "popularity.desc"           -> rows.sortedByDescending { it.popularity }
            "vote_average.desc"         -> rows.filter { it.voteCount >= 100 }
                                               .sortedByDescending { it.voteAverage }
            "primary_release_date.desc" -> rows.sortedByDescending { it.releaseDate ?: "" }
            "primary_release_date.asc"  -> rows.sortedBy { it.releaseDate ?: "" }
            "vote_count.desc"           -> rows.sortedByDescending { it.voteCount }
            else                        -> rows.sortedByDescending { it.popularity }
        }

        return rows.map { it.toMedia() }
    }

    // ── Section tap recording (feed personalization) ───────────────────────────

    /**
     * Call when a user taps a media card. Records the tap against the section
     * so the feed reorders toward their interests over time.
     */
    suspend fun recordSectionTap(sectionId: String) {
        // Upsert to ensure the row exists before incrementing
        val existing = sectionWeightDao.getAll().firstOrNull { it.sectionId == sectionId }
        if (existing == null) {
            sectionWeightDao.upsert(SectionWeight(sectionId = sectionId, taps = 1, lastTappedAt = System.currentTimeMillis()))
        } else {
            sectionWeightDao.recordTap(sectionId)
        }
    }

    // ── Discover ──────────────────────────────────────────────────────────────
    suspend fun discoverMovies(genreId: Int? = null, page: Int = 1): List<Media> =
        api.discoverMovies(genreId, page = page).results.map { it.toMedia(MediaType.MOVIE) }

    suspend fun discoverTv(genreId: Int? = null, page: Int = 1): List<Media> =
        api.discoverTv(genreId, page = page).results.map { it.toMedia(MediaType.TV) }

    suspend fun getAnime(page: Int = 1): List<Media> =
        api.getAnime(page = page).results.map { it.toMedia(MediaType.TV) }

    // ── Genre accessors — cache-first, 7-day TTL ──────────────────────────────
    //
    // WHY:
    //   BrowseViewModel.initLoad() previously called getMovieGenres() ON THE CRITICAL
    //   PATH of the cache-display phase. That's a live TMDB network call — it blocks
    //   the entire genre bar from appearing until the network round-trip completes.
    //   On second open with no network it fails silently (genres = empty) but more
    //   importantly it also ran concurrently with the OkHttp TMDB key interceptor
    //   which itself blocks waiting for RemoteConfig to load — causing the whole
    //   coroutine tree to suspend and the skeleton to loop forever.
    //
    //   Solution: Genres are nearly static (TMDB hasn't changed them in years).
    //   We persist them in Room (cached_genres table) with a 7-day TTL.
    //   - First install: no cache → fetches TMDB, saves to Room. Normal.
    //   - Second+ open: Room has genres → returns instantly with ZERO network.
    //   - Background refresh: if cache is older than 7 days, refreshes silently
    //     AFTER the UI is already visible and populated.
    //
    //   Callers never need to change — same API, massively different behaviour.

    // Singleton scope for fire-and-forget background tasks (genre TTL refresh).
    // SupervisorJob so a failed refresh never cancels other work.
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val GENRE_TTL_MS = 7 * 24 * 3_600_000L  // 7 days

    /**
     * Returns movie genres. Cache-first: returns Room data instantly on
     * second+ opens. Falls back to TMDB on first install or if cache is empty.
     * Triggers a silent background refresh if the cache is older than 7 days.
     */
    suspend fun getMovieGenres(): List<Genre> {
        val cached = cachedGenreDao.getByType("movie")
        if (cached.isNotEmpty()) {
            val oldest = cachedGenreDao.oldestTimestamp("movie")
            if (System.currentTimeMillis() - oldest > GENRE_TTL_MS) {
                repoScope.launch { runCatching { refreshMovieGenres() } }
            }
            return cached.map { Genre(it.id, it.name) }
        }
        return refreshMovieGenres()
    }

    /**
     * Returns TV genres. Same cache-first logic as getMovieGenres().
     */
    suspend fun getTvGenres(): List<Genre> {
        val cached = cachedGenreDao.getByType("tv")
        if (cached.isNotEmpty()) {
            val oldest = cachedGenreDao.oldestTimestamp("tv")
            if (System.currentTimeMillis() - oldest > GENRE_TTL_MS) {
                repoScope.launch { runCatching { refreshTvGenres() } }
            }
            return cached.map { Genre(it.id, it.name) }
        }
        return refreshTvGenres()
    }

    private suspend fun refreshMovieGenres(): List<Genre> {
        val now = System.currentTimeMillis()
        val genres = api.getMovieGenres().genres.map { Genre(it.id, it.name) }
        cachedGenreDao.insertAll(genres.map {
            com.axio.reelz.data.model.CachedGenre(id = it.id, name = it.name, mediaType = "movie", cachedAtMs = now)
        })
        return genres
    }

    private suspend fun refreshTvGenres(): List<Genre> {
        val now = System.currentTimeMillis()
        val genres = api.getTvGenres().genres.map { Genre(it.id, it.name) }
        cachedGenreDao.insertAll(genres.map {
            com.axio.reelz.data.model.CachedGenre(id = it.id, name = it.name, mediaType = "tv", cachedAtMs = now)
        })
        return genres
    }

    // ── Advanced Discover (Explore screen) ───────────────────────────────────
    suspend fun discoverMoviesAdvanced(
        genreIds: List<Int> = emptyList(),
        sortBy: String = "popularity.desc",
        page: Int = 1,
        language: String? = null,
        originCountry: String? = null,
        yearFrom: Int? = null,
        yearTo: Int? = null,
        ratingFrom: Float? = null,
        ratingTo: Float? = null,
        minVotes: Int? = null,
        runtimeFrom: Int? = null,
        runtimeTo: Int? = null,
    ): List<Media> = api.discoverMoviesAdvanced(
        genres       = genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
        sortBy       = sortBy,
        page         = page,
        language     = language,
        originCountry = originCountry,
        yearFrom     = yearFrom?.let { "$it-01-01" },
        yearTo       = yearTo?.let { "$it-12-31" },
        ratingFrom   = ratingFrom,
        ratingTo     = ratingTo,
        minVotes     = minVotes,
        runtimeFrom  = runtimeFrom,
        runtimeTo    = runtimeTo,
    ).results.map { it.toMedia(MediaType.MOVIE) }

    suspend fun discoverTvAdvanced(
        genreIds: List<Int> = emptyList(),
        sortBy: String = "popularity.desc",
        page: Int = 1,
        language: String? = null,
        originCountry: String? = null,
        yearFrom: Int? = null,
        yearTo: Int? = null,
        ratingFrom: Float? = null,
        ratingTo: Float? = null,
        minVotes: Int? = null,
    ): List<Media> = api.discoverTvAdvanced(
        genres        = genreIds.takeIf { it.isNotEmpty() }?.joinToString(","),
        sortBy        = sortBy,
        page          = page,
        language      = language,
        originCountry = originCountry,
        yearFrom      = yearFrom?.let { "$it-01-01" },
        yearTo        = yearTo?.let { "$it-12-31" },
        ratingFrom    = ratingFrom,
        ratingTo      = ratingTo,
        minVotes      = minVotes,
    ).results.map { it.toMedia(MediaType.TV) }

    // ── Detail ────────────────────────────────────────────────────────────────

    /** Fast — no append_to_response. Returns in ~300ms. Shows the screen immediately. */
    suspend fun getDetailFast(tmdbId: Int, type: MediaType): MediaDetail {
        getCachedDetail(tmdbId)?.let { return it }
        val detail = if (type == MediaType.MOVIE) api.getMovieDetail(tmdbId).toDetail()
                     else api.getTvDetail(tmdbId).toDetail()
        putDetailCache(detail)
        return detail
    }

    /** Extras — credits, videos, similar. Heavier, loads after screen is visible. */
    suspend fun getDetailExtras(tmdbId: Int, type: MediaType): MediaDetail {
        val detail = if (type == MediaType.MOVIE) api.getMovieExtras(tmdbId).toDetail()
                     else api.getTvExtras(tmdbId).toDetail()
        putDetailCache(detail)  // update cache with full extras
        return detail
    }

    suspend fun getMovieDetail(tmdbId: Int): MediaDetail = api.getMovieExtras(tmdbId).toDetail()
    suspend fun getTvDetail(tmdbId: Int): MediaDetail    = api.getTvExtras(tmdbId).toDetail()
    suspend fun getDetail(tmdbId: Int, type: MediaType): MediaDetail =
        if (type == MediaType.MOVIE) getMovieDetail(tmdbId) else getTvDetail(tmdbId)

    suspend fun getSeasonEpisodes(tmdbId: Int, season: Int): List<Episode> =
        api.getSeasonDetail(tmdbId, season).episodes.map { it.toEpisode() }

    // ── Search ────────────────────────────────────────────────────────────────
    suspend fun search(query: String, page: Int = 1): List<Media> {
        val movies = api.searchMovies(query, page).results.map { it.toMedia(MediaType.MOVIE) }
        val tv     = api.searchTv(query, page).results.map { it.toMedia(MediaType.TV) }
        return (movies + tv).sortedByDescending { it.popularity }
    }

    /**
     * Save a search-opened item to the feed cache.
     * Appears in relevant sections so the user can find their interests again.
     * Capped at 50 items; oldest evicted first.
     */
    suspend fun saveSearchOpenToCatalog(media: Media) {
        val count = cachedMediaDao.countBySource("search")
        if (count >= 50) cachedMediaDao.evictOldestSearch(keepCount = 49)
        cachedMediaDao.insertAll(listOf(
            media.toCached().copy(
                source = "search",
                lastAccessedAt = System.currentTimeMillis(),
            )
        ))
    }

    // ── Watchlist ─────────────────────────────────────────────────────────────
    fun getWatchlist(): Flow<List<WatchlistItem>> = watchlistDao.getAll()
    suspend fun isInWatchlist(id: Int): Boolean = watchlistDao.get(id) != null
    suspend fun addToWatchlist(media: Media) = watchlistDao.insert(
        WatchlistItem(media.tmdbId, media.title, media.posterPath, media.mediaType.name)
    )
    suspend fun removeFromWatchlist(tmdbId: Int) = watchlistDao.delete(tmdbId)
    suspend fun toggleWatchlist(media: Media): Boolean {
        return if (isInWatchlist(media.tmdbId)) {
            removeFromWatchlist(media.tmdbId); false
        } else {
            addToWatchlist(media); true
        }
    }

    // ── Liked ─────────────────────────────────────────────────────────────────
    fun getLiked(): Flow<List<LikedItem>> = likedDao.getAll()
    suspend fun isLiked(id: Int): Boolean = likedDao.get(id) != null
    suspend fun toggleLike(media: Media): Boolean {
        return if (isLiked(media.tmdbId)) {
            likedDao.delete(media.tmdbId); false
        } else {
            likedDao.insert(LikedItem(media.tmdbId, media.title, media.posterPath, media.mediaType.name))
            true
        }
    }

    // ── History ───────────────────────────────────────────────────────────────
    fun getHistory(): Flow<List<WatchHistory>> = historyDao.getRecent()
    suspend fun getPosition(tmdbId: Int, season: Int, episode: Int): Long {
        val key = "${tmdbId}_${season}_${episode}"
        return historyDao.get(key)?.positionMs ?: 0L
    }
    suspend fun saveProgress(
        tmdbId: Int, title: String, posterPath: String?, mediaType: MediaType,
        season: Int, episode: Int, positionMs: Long, durationMs: Long,
    ) {
        val key = "${tmdbId}_${season}_${episode}"
        historyDao.insert(WatchHistory(
            key = key, tmdbId = tmdbId, title = title,
            posterPath = posterPath, mediaType = mediaType.name,
            season = season, episode = episode,
            positionMs = positionMs, durationMs = durationMs,
        ))
        historyDao.trimToLimit(keepCount = 500)
        // Auto-remove from watchlist when user finishes ≥90% of content
        if (durationMs > 0 && positionMs.toFloat() / durationMs >= 0.90f) {
            watchlistDao.delete(tmdbId)
        }
    }
}

// ── DTO → Domain mappers ──────────────────────────────────────────────────────
fun TmdbMovieDto.toMedia(type: MediaType = MediaType.MOVIE) = Media(
    id = id, tmdbId = id, title = title, overview = overview,
    posterPath = posterPath, backdropPath = backdropPath,
    releaseDate = releaseDate, voteAverage = voteAverage, voteCount = voteCount,
    popularity = popularity, genreIds = genreIds, mediaType = type,
    adult = adult, originalLanguage = originalLanguage,
)

fun TmdbTvDto.toMedia(type: MediaType = MediaType.TV) = Media(
    id = id, tmdbId = id, title = name, overview = overview,
    posterPath = posterPath, backdropPath = backdropPath,
    releaseDate = firstAirDate, voteAverage = voteAverage, voteCount = voteCount,
    popularity = popularity, genreIds = genreIds, mediaType = type,
    originalLanguage = originalLanguage,
)

fun TmdbMovieDetailDto.toDetail(): MediaDetail {
    val trailer = videos?.results
        ?.filter { it.site == "YouTube" && it.type == "Trailer" && it.official }
        ?.maxByOrNull { if (it.official) 1 else 0 }?.key
    return MediaDetail(
        id = id, tmdbId = id, title = title, overview = overview,
        posterPath = posterPath, backdropPath = backdropPath,
        releaseDate = releaseDate, voteAverage = voteAverage, voteCount = voteCount,
        runtime = runtime, genres = genres.map { Genre(it.id, it.name) },
        mediaType = MediaType.MOVIE, status = status, tagline = tagline,
        cast = credits?.cast?.take(20)?.map {
            CastMember(it.id, it.name, it.character, it.profilePath, it.order)
        } ?: emptyList(),
        trailerKey = trailer, imdbId = imdbId, budget = budget, revenue = revenue,
        spokenLanguages = spokenLanguages.map { it.englishName },
        productionCountries = productionCountries.map { it.name },
        similar = similar?.results?.take(12)?.map { it.toMedia(MediaType.MOVIE) } ?: emptyList(),
    )
}

fun TmdbTvDetailDto.toDetail(): MediaDetail {
    val trailer = videos?.results
        ?.filter { it.site == "YouTube" && it.type == "Trailer" && it.official }
        ?.maxByOrNull { if (it.official) 1 else 0 }?.key
    return MediaDetail(
        id = id, tmdbId = id, title = name, overview = overview,
        posterPath = posterPath, backdropPath = backdropPath,
        releaseDate = firstAirDate, voteAverage = voteAverage, voteCount = voteCount,
        runtime = null, genres = genres.map { Genre(it.id, it.name) },
        mediaType = MediaType.TV, status = status, tagline = tagline,
        seasons = seasons.filter { it.seasonNumber > 0 }.map {
            Season(it.id, it.seasonNumber, it.name, it.episodeCount, it.posterPath, it.overview, it.airDate)
        },
        numberOfSeasons = numberOfSeasons, numberOfEpisodes = numberOfEpisodes,
        cast = credits?.cast?.take(20)?.map {
            CastMember(it.id, it.name, it.character, it.profilePath, it.order)
        } ?: emptyList(),
        trailerKey = trailer, imdbId = externalIds?.imdbId,
        similar = similar?.results?.take(12)?.map { it.toMedia(MediaType.TV) } ?: emptyList(),
    )
}

fun TmdbEpisodeDto.toEpisode() = Episode(
    id = id, episodeNumber = episodeNumber, seasonNumber = seasonNumber,
    name = name, overview = overview, stillPath = stillPath,
    airDate = airDate, runtime = runtime, voteAverage = voteAverage,
)

fun Media.toCached() = CachedMedia(
    tmdbId = tmdbId, title = title, overview = overview,
    posterPath = posterPath, backdropPath = backdropPath,
    releaseDate = releaseDate, voteAverage = voteAverage,
    voteCount = voteCount,
    popularity = popularity,
    genreIds = Gson().toJson(genreIds),
    mediaType = mediaType.name,
    originalLanguage = originalLanguage,
)

fun CachedMedia.toMedia() = Media(
    id = tmdbId, tmdbId = tmdbId, title = title, overview = overview,
    posterPath = posterPath, backdropPath = backdropPath,
    releaseDate = releaseDate, voteAverage = voteAverage,
    voteCount = voteCount,
    popularity = popularity,
    genreIds = Gson().fromJson(genreIds, object : TypeToken<List<Int>>() {}.type) ?: emptyList(),
    mediaType = if (mediaType == "TV") MediaType.TV else MediaType.MOVIE,
    originalLanguage = originalLanguage,
)
