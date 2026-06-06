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
        // Try cache first (fast offline path)
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
        // Evict entries older than 48h
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

    // ── Detail ────────────────────────────────────────────────────────────────
    suspend fun getMovieDetail(tmdbId: Int): MediaDetail =
        api.getMovieDetail(tmdbId).toDetail()

    suspend fun getTvDetail(tmdbId: Int): MediaDetail =
        api.getTvDetail(tmdbId).toDetail()

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
