package com.reelz.data.repository

import com.reelz.data.local.*
import com.reelz.data.model.*
import com.reelz.data.remote.api.TmdbApi
import com.reelz.data.remote.dto.*
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
) {

    // ── Home sections ─────────────────────────────────────────────────────────
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

    private suspend fun fetchHomeSectionsFromNetwork(): List<HomeSection> {
        val trending     = api.getTrendingMovies().results.map { it.toMedia(MediaType.MOVIE) }
        val popular      = api.getPopularMovies().results.map { it.toMedia(MediaType.MOVIE) }
        val topRated     = api.getTopRatedMovies().results.map { it.toMedia(MediaType.MOVIE) }
        val popularTv    = api.getPopularTv().results.map { it.toMedia(MediaType.TV) }
        val trendingTv   = api.getTrendingTv().results.map { it.toMedia(MediaType.TV) }
        val anime        = api.getAnime().results.map { it.toMedia(MediaType.TV) }
        return listOf(
            HomeSection("Trending Now",  trending),
            HomeSection("Popular Movies",popular),
            HomeSection("Top Rated",     topRated),
            HomeSection("Hot TV Series", popularTv),
            HomeSection("Trending TV",   trendingTv),
            HomeSection("Anime",         anime),
        )
    }

    private suspend fun buildSectionsFromCache(): List<HomeSection> {
        val movies = cachedMediaDao.getByType("MOVIE", 60).map { it.toMedia() }
        val tv     = cachedMediaDao.getByType("TV", 40).map { it.toMedia() }
        return listOf(
            HomeSection("Trending Now",  movies.take(20)),
            HomeSection("Popular Movies",movies.drop(20).take(20)),
            HomeSection("Hot TV Series", tv.take(20)),
            HomeSection("Anime",         tv.drop(20).take(20)),
        )
    }

    private suspend fun cacheHomeSections(sections: List<HomeSection>) {
        val all = sections.flatMap { it.items }.map { it.toCached() }
        cachedMediaDao.insertAll(all)
        cachedMediaDao.evict(System.currentTimeMillis() - 48 * 60 * 60 * 1000L)
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

    // ── Discovery rows ────────────────────────────────────────────────────────

    /**
     * Trending: combines trending movies + TV, sorted by popularity.
     * Social FOMO row — same for all users. Bypasses taste entirely.
     */
    suspend fun getTrending(): List<Media> {
        val movies = api.getTrendingMovies().results.map { it.toMedia(MediaType.MOVIE) }
        val tv     = api.getTrendingTv().results.map { it.toMedia(MediaType.TV) }
        return (movies + tv)
            .sortedByDescending { it.popularity }
            .take(20)
    }

    /**
     * Hidden Gems: high vote_average (≥7.5) but low vote_count (≤1000).
     * Quality + scarcity = treasure hunt psychology.
     * Quality floor ≥50 votes so we don't surface brand-new unknowns.
     */
    suspend fun getHiddenGems(): List<Media> =
        api.getHiddenGemsMovies().results
            .map { it.toMedia(MediaType.MOVIE) }
            .filter { it.voteAverage >= 7.5 && it.voteCount in 50..1000 }

    /**
     * World Cinema: movies from unexplored languages, ranked by quality.
     * Language discovery — Korean drama fans often love Turkish drama.
     *
     * @param unexploredLangKeys Internal language keys from getUnexploredGenres
     *        (e.g. ["lang_korean", "lang_turkish"]) — we map to ISO codes.
     */
    suspend fun getWorldCinema(unexploredLangKeys: List<String>): List<Media> {
        val langCodes = unexploredLangKeys
            .mapNotNull { langKeyToIsoCode(it) }
            .distinct()
            .take(3) // cap network calls
        if (langCodes.isEmpty()) return emptyList()

        val results = mutableListOf<Media>()
        for (lang in langCodes) {
            runCatching {
                val items = api.getWorldCinemaMovies(language = lang).results
                    .map { it.toMedia(MediaType.MOVIE) }
                results.addAll(items.take(6))
            }
        }
        return results.distinctBy { it.tmdbId }
    }

    /**
     * Award Winners: critically acclaimed films with substantial vote base.
     * Trusted third-party authority removes the "algorithm chose this" feeling.
     */
    suspend fun getAwardWinners(): List<Media> =
        api.getAwardWinnersMovies().results.map { it.toMedia(MediaType.MOVIE) }

    // ── Detail ────────────────────────────────────────────────────────────────

    suspend fun getDetailFast(tmdbId: Int, type: MediaType): MediaDetail =
        if (type == MediaType.MOVIE) api.getMovieDetail(tmdbId).toDetail()
        else api.getTvDetail(tmdbId).toDetail()

    suspend fun getDetailExtras(tmdbId: Int, type: MediaType): MediaDetail =
        if (type == MediaType.MOVIE) api.getMovieExtras(tmdbId).toDetail()
        else api.getTvExtras(tmdbId).toDetail()

    suspend fun getMovieDetail(tmdbId: Int): MediaDetail =
        api.getMovieExtras(tmdbId).toDetail()

    suspend fun getTvDetail(tmdbId: Int): MediaDetail =
        api.getTvExtras(tmdbId).toDetail()

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
    }

    // ── Internal helpers ──────────────────────────────────────────────────────
    private fun langKeyToIsoCode(key: String): String? = when (key) {
        "lang_japanese"    -> "ja"
        "lang_korean"      -> "ko"
        "lang_hindi"       -> "hi"
        "lang_french"      -> "fr"
        "lang_spanish"     -> "es"
        "lang_turkish"     -> "tr"
        "lang_arabic"      -> "ar"
        "lang_chinese"     -> "zh"
        "lang_portuguese"  -> "pt"
        "lang_german"      -> "de"
        "lang_thai"        -> "th"
        "lang_tamil"       -> "ta"
        "lang_telugu"      -> "te"
        else               -> null
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
        originalLanguage = originalLanguage,
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
        originalLanguage = originalLanguage,
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
    releaseDate = releaseDate, voteAverage = voteAverage, popularity = popularity,
    genreIds = com.google.gson.Gson().toJson(genreIds),
    mediaType = mediaType.name,
)

fun CachedMedia.toMedia() = Media(
    id = tmdbId, tmdbId = tmdbId, title = title, overview = overview,
    posterPath = posterPath, backdropPath = backdropPath,
    releaseDate = releaseDate, voteAverage = voteAverage, voteCount = 0,
    popularity = popularity, genreIds = emptyList(),
    mediaType = if (mediaType == "TV") MediaType.TV else MediaType.MOVIE,
)
