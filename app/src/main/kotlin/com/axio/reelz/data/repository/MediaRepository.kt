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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    // ── Home sections ─────────────────────────────────────────────────────────

    suspend fun hasCachedData(): Boolean = cachedMediaDao.count() > 0

    suspend fun getHomeSectionsFromCacheOnly(): List<HomeSection> = buildSectionsFromCache()

    suspend fun getHomeSectionsFromNetwork(): List<HomeSection> {
        val sections = fetchHomeSectionsFromNetwork()
        cacheHomeSections(sections)
        return sections
    }

    suspend fun getHomeSections(forceRefresh: Boolean = false): List<HomeSection> {
        val cacheCount = cachedMediaDao.count()
        if (!forceRefresh && cacheCount > 0) {
            return buildSectionsFromCache()
        }
        return try {
            val sections = fetchHomeSectionsFromNetwork()
            cacheHomeSections(sections)
            sections
        } catch (e: Exception) {
            if (cacheCount > 0) buildSectionsFromCache()
            else throw e
        }
    }

    /**
     * Fetch all home sections concurrently — each section is a separate async call.
     * Individual failures don't kill other sections; they emit emptyList() gracefully.
     * 21 concurrent calls, all keyed by the same TMDB connection pool.
     */
    private suspend fun fetchHomeSectionsFromNetwork(): List<HomeSection> = coroutineScope {
        val jobs = mapOf(
            Section.TRENDING      to async { runCatching { api.getTrendingMovies().results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.NEW_RELEASES  to async { runCatching { api.getNowPlayingMovies().results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.POPULAR_MOVIES to async { runCatching { api.getPopularMovies().results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.HOT_TV        to async { runCatching { api.getPopularTv().results.map { it.toMedia(MediaType.TV) } }.getOrDefault(emptyList()) },
            Section.TOP_RATED     to async { runCatching { api.getTopRatedMovies().results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.KDRAMA        to async { runCatching { api.getKDrama().results.map { it.toMedia(MediaType.TV) } }.getOrDefault(emptyList()) },
            Section.ANIME         to async { runCatching { api.getAnime().results.map { it.toMedia(MediaType.TV) } }.getOrDefault(emptyList()) },
            Section.INDIAN        to async { runCatching { api.getIndianMovies().results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.NOLLYWOOD     to async { runCatching { api.getNollywood().results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.CDRAMA        to async { runCatching { api.getCDrama().results.map { it.toMedia(MediaType.TV) } }.getOrDefault(emptyList()) },
            Section.ACTION        to async { runCatching { api.discoverMovies(28).results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.COMEDY        to async { runCatching { api.discoverMovies(35).results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.ROMANCE       to async { runCatching { api.discoverMovies(10749).results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.HORROR        to async { runCatching { api.discoverMovies(27).results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.CRIME         to async { runCatching { api.discoverMovies(80).results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.DRAMA         to async { runCatching { api.discoverMovies(18).results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.TURKISH       to async { runCatching { api.getTurkishDrama().results.map { it.toMedia(MediaType.TV) } }.getOrDefault(emptyList()) },
            Section.DOCUMENTARY   to async { runCatching { api.discoverMovies(99).results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.FAMILY        to async { runCatching { api.discoverMovies(10751).results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.SCIFI         to async { runCatching { api.discoverMovies(878).results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
            Section.AFRICAN       to async { runCatching { api.getAfricanContent().results.map { it.toMedia(MediaType.MOVIE) } }.getOrDefault(emptyList()) },
        )
        jobs.mapNotNull { (section, deferred) ->
            val items = deferred.await()
            if (items.isEmpty()) null else HomeSection(section.label, items)
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

    suspend fun getMovieGenres(): List<Genre> = api.getMovieGenres().genres.map { Genre(it.id, it.name) }
    suspend fun getTvGenres(): List<Genre>    = api.getTvGenres().genres.map { Genre(it.id, it.name) }

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
