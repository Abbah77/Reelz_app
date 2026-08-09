package com.axio.reelz.data.repository

import android.util.LruCache
import com.axio.reelz.data.local.*
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
    private val infiniteScrollEngine: InfiniteScrollEngine,
    private val localSearchHelper: LocalSearchHelper,
) {

    // ── Detail memory cache (session-only, 12h TTL, 50 entries) ──────────────
    private val detailCache = LruCache<Int, Pair<MediaDetail, Long>>(50)

    private fun getCachedDetail(tmdbId: Int): MediaDetail? {
        val entry = detailCache[tmdbId] ?: return null
        if (System.currentTimeMillis() - entry.second > 12 * 3_600_000L) {
            detailCache.remove(tmdbId); return null
        }
        return entry.first
    }
    private fun putDetailCache(detail: MediaDetail) =
        detailCache.put(detail.tmdbId, detail to System.currentTimeMillis())

    // ── Cache freshness ───────────────────────────────────────────────────────

    suspend fun cacheAgeMs(): Long {
        val newest = cachedMediaDao.getNewestSectionTimestamp()
        return if (newest == 0L) Long.MAX_VALUE else System.currentTimeMillis() - newest
    }

    suspend fun hasCachedData(): Boolean {
        if (cachedMediaDao.count() == 0) return false
        val probe = Section.DEFAULT_ORDER.take(3)
        return probe.any { cachedMediaDao.getBySection(it.id, 1).isNotEmpty() }
    }

    // ── Home sections ─────────────────────────────────────────────────────────

    suspend fun getHomeSectionsFromCacheOnly(): List<HomeSection> = buildSectionsFromCache()

    suspend fun getHomeSectionsFromNetwork(): List<HomeSection> {
        val all = mutableListOf<HomeSection>()
        streamHomeSections { batch -> all.addAll(batch) }
        return all
    }

    suspend fun getHomeSections(forceRefresh: Boolean = false): List<HomeSection> {
        val hasUsable = hasCachedData()
        if (!forceRefresh && hasUsable) return buildSectionsFromCache()
        return try { getHomeSectionsFromNetwork() } catch (e: Exception) {
            if (hasUsable) buildSectionsFromCache() else throw e
        }
    }

    suspend fun streamHomeSections(
        sectionFilter: Set<String>? = null,
        onBatch: suspend (List<HomeSection>) -> Unit,
    ) {
        val batches: List<List<Pair<Section, suspend () -> List<Media>>>> = listOf(
            listOf(
                Section.TRENDING       to { api.getTrendingMovies().results.map  { it.toMedia(MediaType.MOVIE) } },
                Section.NEW_RELEASES   to { api.getNowPlayingMovies().results.map { it.toMedia(MediaType.MOVIE) } },
                Section.POPULAR_MOVIES to { api.getPopularMovies().results.map   { it.toMedia(MediaType.MOVIE) } },
                Section.HOT_TV         to { api.getPopularTv().results.map       { it.toMedia(MediaType.TV)    } },
                Section.TOP_RATED      to { api.getTopRatedMovies().results.map  { it.toMedia(MediaType.MOVIE) } },
                Section.KDRAMA         to { api.getKDrama().results.map          { it.toMedia(MediaType.TV)    } },
                Section.ANIME          to { api.getAnime().results.map           { it.toMedia(MediaType.TV)    } },
            ),
            listOf(
                Section.INDIAN         to { api.getIndianMovies().results.map    { it.toMedia(MediaType.MOVIE) } },
                Section.NOLLYWOOD      to { api.getNollywood().results.map       { it.toMedia(MediaType.MOVIE) } },
                Section.CDRAMA         to { api.getCDrama().results.map          { it.toMedia(MediaType.TV)    } },
                Section.ACTION         to { api.discoverMovies(28).results.map   { it.toMedia(MediaType.MOVIE) } },
                Section.COMEDY         to { api.discoverMovies(35).results.map   { it.toMedia(MediaType.MOVIE) } },
                Section.ROMANCE        to { api.discoverMovies(10749).results.map{ it.toMedia(MediaType.MOVIE) } },
                Section.HORROR         to { api.discoverMovies(27).results.map   { it.toMedia(MediaType.MOVIE) } },
            ),
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
            val filteredEntries = if (sectionFilter == null) entries
                                  else entries.filter { (section, _) -> section.id in sectionFilter }
            if (filteredEntries.isEmpty()) return@forEachIndexed

            if (batchIndex > 0) delay(300)

            val batchSections = coroutineScope {
                filteredEntries.map { (section, fetch) ->
                    async {
                        val items = runCatching { fetch() }.getOrDefault(emptyList())
                        if (items.isEmpty()) null else HomeSection(section.label, items)
                    }
                }.mapNotNull { it.await() }
            }

            if (batchSections.isNotEmpty()) {
                allSections.addAll(batchSections)
                onBatch(batchSections)
                cacheHomeSections(batchSections)
            }
        }
    }

    private suspend fun buildSectionsFromCache(): List<HomeSection> {
        val weights = sectionWeightDao.getAll().associateBy { it.sectionId }

        fun scoreSection(sectionId: String): Float {
            val w = weights[sectionId] ?: return 0f
            val recency = if (w.lastTappedAt == 0L) 0f
                else 1f / (1f + (System.currentTimeMillis() - w.lastTappedAt) / 86_400_000f)
            return (w.taps * 0.7f) + (recency * 30f)
        }

        val orderedSections = if (weights.isEmpty()) Section.DEFAULT_ORDER
                              else Section.DEFAULT_ORDER.sortedByDescending { scoreSection(it.id) }

        return orderedSections.mapNotNull { section ->
            val items = cachedMediaDao.getBySection(section.id, 20)
            if (items.isEmpty()) null
            else HomeSection(section.label, items.map { it.toMedia() })
        }
    }

    suspend fun staleSectionIds(): Set<String> = coroutineScope {
        Section.DEFAULT_ORDER.map { section ->
            async {
                val cachedAt = cachedMediaDao.getSectionTimestamp(section.id)
                if (section.isSectionStale(cachedAt)) section.id else null
            }
        }.mapNotNull { it.await() }.toSet()
    }

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
            cachedMediaDao.deleteOldSectionRows(sEnum.id, keepNewerThan = now - 1000L)
        }

        // Soft eviction after home cache write
        val total = cachedMediaDao.count()
        if (total > InfiniteScrollEngine.CACHE_SOFT_LIMIT) {
            cachedMediaDao.evictOldest(total - InfiniteScrollEngine.CACHE_TARGET)
        }
    }

    // ── Infinite scroll — unified local-first engine ──────────────────────────

    /**
     * Returns the next page of the infinite feed.
     *
     * The UI never asks "was this cache or network?" — it receives a
     * CatalogPage and renders items uniformly with skeleton transitions.
     *
     * The engine:
     *   1. Checks Room (0ms, offline-safe)
     *   2. Falls back to TMDB only when Room is thin
     *   3. Writes all TMDB results to Room before returning
     *   4. Advances the persisted TMDB page cursor
     */
    suspend fun getNextInfinitePage(
        mediaType: String = "movie",
        excludeIds: Set<Int> = emptySet(),
    ): CatalogPage = infiniteScrollEngine.nextPage(mediaType, excludeIds)

    /**
     * Prefetch trigger — call when user is within 2 pages of the scroll end.
     * Warms the cache silently so the next nextPage() call serves from Room.
     */
    suspend fun prefetchAhead(mediaType: String, excludeIds: Set<Int>) =
        infiniteScrollEngine.prefetchAhead(mediaType, excludeIds)

    /** Reset session cursor (call on pull-to-refresh) */
    fun resetInfiniteCursor() = infiniteScrollEngine.resetSessionCursor()

    // ── Legacy getCachePageExcluding — kept for compatibility ─────────────────
    // BrowseViewModel still calls this in its loadMoreInfinite() path.
    // New code should use getNextInfinitePage() instead.
    suspend fun getCachePageExcluding(excludeIds: Set<Int>, limit: Int = 20): List<Media> {
        var offset = 0
        val result = mutableListOf<CachedMedia>()
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

        if (genreIds.isNotEmpty()) {
            rows = rows.filter { cached ->
                val ids: List<Int> = try {
                    Gson().fromJson(cached.genreIds, object : TypeToken<List<Int>>() {}.type) ?: emptyList()
                } catch (_: Exception) { emptyList() }
                ids.any { it in genreIds }
            }
        }

        if (language != null) rows = rows.filter { it.originalLanguage == language }
        if (ratingFrom != null) rows = rows.filter { it.voteAverage >= ratingFrom }

        if (yearFrom != null) {
            rows = rows.filter { it.releaseDate?.take(4)?.toIntOrNull() ?: 0 >= yearFrom }
        }
        if (yearTo != null) {
            rows = rows.filter { it.releaseDate?.take(4)?.toIntOrNull() ?: 9999 <= yearTo }
        }

        rows = when (sortBy) {
            "popularity.desc"   -> rows.sortedByDescending { it.popularity }
            "vote_average.desc" -> rows.filter { it.voteCount >= 100 }.sortedByDescending { it.voteAverage }
            "primary_release_date.desc" -> rows.sortedByDescending { it.releaseDate ?: "" }
            "revenue.desc"      -> rows.sortedByDescending { it.popularity }
            else                -> rows.sortedByDescending { it.popularity }
        }

        return rows.take(40).map { it.toMedia() }
    }

    // ── Search — local-first, FTS5-powered ───────────────────────────────────

    /**
     * Local-first search via FTS5 (executed through LocalSearchHelper to bypass
     * Room KSP compile-time validation of virtual table queries).
     *
     * Returns results in < 5ms against the full 10K cache. Works offline.
     * Falls back to LIKE search automatically if FTS5 isn't ready yet.
     */
    suspend fun searchLocal(query: String, limit: Int = 40): List<Media> {
        if (query.isBlank()) return emptyList()
        return localSearchHelper.search(query, limit).map { it.toMedia() }
    }

    /**
     * Network search — calls TMDB, caches results to Room, returns combined list.
     * Called as a background refresh after searchLocal() returns local results.
     */
    suspend fun searchNetwork(query: String, page: Int = 1): List<Media> {
        val movies = api.searchMovies(query, page).results.map { it.toMedia(MediaType.MOVIE) }
        val tv     = api.searchTv(query, page).results.map { it.toMedia(MediaType.TV) }
        val combined = (movies + tv).sortedByDescending { it.popularity }

        // Cache search results to Room — expands the local catalog naturally
        if (combined.isNotEmpty()) {
            val count = cachedMediaDao.countBySource("search")
            if (count >= InfiniteScrollEngine.SEARCH_CACHE_CAP) {
                cachedMediaDao.evictOldestSearch(InfiniteScrollEngine.SEARCH_CACHE_CAP - combined.size)
            }
            val now = System.currentTimeMillis()
            cachedMediaDao.insertAll(combined.map { media ->
                media.toCached().copy(
                    source = "search",
                    lastAccessedAt = now,
                    cachedAt = now,
                )
            })
        }
        return combined
    }

    // Keep old search() method for backward compatibility
    suspend fun search(query: String, page: Int = 1): List<Media> = searchNetwork(query, page)

    /**
     * Save a search-opened item to the catalog feed.
     * Next time it appears in the home feed, it's already in Room.
     */
    suspend fun saveSearchOpenToCatalog(media: Media) {
        val count = cachedMediaDao.countBySource("search")
        if (count >= InfiniteScrollEngine.SEARCH_CACHE_CAP) {
            cachedMediaDao.evictOldestSearch(InfiniteScrollEngine.SEARCH_CACHE_CAP - 1)
        }
        cachedMediaDao.insertAll(listOf(
            media.toCached().copy(
                source = "search",
                lastAccessedAt = System.currentTimeMillis(),
            )
        ))
        // Touch LRU so this item survives eviction
        cachedMediaDao.touchItem(media.tmdbId)
    }

    // ── Section personalization ───────────────────────────────────────────────

    suspend fun recordSectionTap(sectionId: String) {
        val existing = sectionWeightDao.getAll().find { it.sectionId == sectionId }
        if (existing == null) {
            sectionWeightDao.upsert(SectionWeight(sectionId = sectionId, taps = 1,
                lastTappedAt = System.currentTimeMillis()))
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

    // ── Genres — cache-first, 7-day TTL ──────────────────────────────────────

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val GENRE_TTL_MS = 7 * 24 * 3_600_000L

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
            CachedGenre(id = it.id, name = it.name, mediaType = "movie", cachedAtMs = now)
        })
        return genres
    }

    private suspend fun refreshTvGenres(): List<Genre> {
        val now = System.currentTimeMillis()
        val genres = api.getTvGenres().genres.map { Genre(it.id, it.name) }
        cachedGenreDao.insertAll(genres.map {
            CachedGenre(id = it.id, name = it.name, mediaType = "tv", cachedAtMs = now)
        })
        return genres
    }

    // ── Advanced Discover (Explore screen) ────────────────────────────────────

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
        runtimeFrom   = runtimeFrom,
        runtimeTo     = runtimeTo,
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

    suspend fun getDetailFast(tmdbId: Int, type: MediaType): MediaDetail {
        getCachedDetail(tmdbId)?.let { return it }
        // Mark item as recently accessed in LRU
        repoScope.launch { runCatching { cachedMediaDao.touchItem(tmdbId) } }
        val detail = if (type == MediaType.MOVIE) api.getMovieDetail(tmdbId).toDetail()
                     else api.getTvDetail(tmdbId).toDetail()
        putDetailCache(detail)
        return detail
    }

    suspend fun getDetailExtras(tmdbId: Int, type: MediaType): MediaDetail {
        val detail = if (type == MediaType.MOVIE) api.getMovieExtras(tmdbId).toDetail()
                     else api.getTvExtras(tmdbId).toDetail()
        putDetailCache(detail)
        return detail
    }

    suspend fun getMovieDetail(tmdbId: Int): MediaDetail = api.getMovieExtras(tmdbId).toDetail()
    suspend fun getTvDetail(tmdbId: Int): MediaDetail    = api.getTvExtras(tmdbId).toDetail()
    suspend fun getDetail(tmdbId: Int, type: MediaType): MediaDetail =
        if (type == MediaType.MOVIE) getMovieDetail(tmdbId) else getTvDetail(tmdbId)

    suspend fun getSeasonEpisodes(tmdbId: Int, season: Int): List<Episode> =
        api.getSeasonDetail(tmdbId, season).episodes.map { it.toEpisode() }

    // ── Watchlist ─────────────────────────────────────────────────────────────
    fun getWatchlist(): Flow<List<WatchlistItem>> = watchlistDao.getAll()
    suspend fun isInWatchlist(id: Int): Boolean = watchlistDao.get(id) != null
    suspend fun addToWatchlist(media: Media) = watchlistDao.insert(
        WatchlistItem(media.tmdbId, media.title, media.posterPath, media.mediaType.name)
    )
    suspend fun removeFromWatchlist(tmdbId: Int) = watchlistDao.delete(tmdbId)
    suspend fun toggleWatchlist(media: Media): Boolean {
        return if (isInWatchlist(media.tmdbId)) { removeFromWatchlist(media.tmdbId); false }
        else { addToWatchlist(media); true }
    }

    // ── Liked ─────────────────────────────────────────────────────────────────
    fun getLiked(): Flow<List<LikedItem>> = likedDao.getAll()
    suspend fun isLiked(id: Int): Boolean = likedDao.get(id) != null
    suspend fun toggleLike(media: Media): Boolean {
        return if (isLiked(media.tmdbId)) { likedDao.delete(media.tmdbId); false }
        else {
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
