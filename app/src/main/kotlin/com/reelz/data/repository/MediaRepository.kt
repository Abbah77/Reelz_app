package com.reelz.data.repository

import com.reelz.data.local.WatchHistoryDao
import com.reelz.data.local.WatchlistDao
import com.reelz.data.model.*
import com.reelz.data.remote.api.TmdbApi
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    private val api: TmdbApi,
    private val watchlistDao: WatchlistDao,
    private val historyDao: WatchHistoryDao,
) {

    // ── Home sections ──────────────────────────────────────────────────────────
    suspend fun getHomeSections(): List<HomeSection> {
        return listOf(
            HomeSection("Trending Now",      api.getTrending().results.map { it.toDomain() }),
            HomeSection("Popular Movies",    api.getPopularMovies().results.map { it.toDomain(MediaType.MOVIE) }),
            HomeSection("Popular TV Shows",  api.getPopularTv().results.map { it.toDomain(MediaType.TV) }),
            HomeSection("Top Rated Movies",  api.getTopRatedMovies().results.map { it.toDomain(MediaType.MOVIE) }),
            HomeSection("Top Rated TV",      api.getTopRatedTv().results.map { it.toDomain(MediaType.TV) }),
            HomeSection("Now Playing",       api.getNowPlaying().results.map { it.toDomain(MediaType.MOVIE) }),
            HomeSection("Airing Today",      api.getOnTheAir().results.map { it.toDomain(MediaType.TV) }),
        )
    }

    suspend fun getFeatured(): Media? =
        api.getTrending().results.firstOrNull()?.toDomain()

    // ── Detail ─────────────────────────────────────────────────────────────────
    suspend fun getMovieDetail(id: Int): MediaDetail = api.getMovieDetail(id).toDomain()
    suspend fun getTvDetail(id: Int): MediaDetail    = api.getTvDetail(id).toDomain()

    suspend fun getDetail(id: Int, type: MediaType): MediaDetail =
        if (type == MediaType.MOVIE) getMovieDetail(id) else getTvDetail(id)

    suspend fun getSeasonEpisodes(showId: Int, season: Int): List<Episode> =
        api.getSeasonDetail(showId, season).episodes?.map { it.toDomain() } ?: emptyList()

    suspend fun getRecommendations(id: Int, type: MediaType): List<Media> =
        if (type == MediaType.MOVIE)
            api.getMovieRecommendations(id).results.map { it.toDomain(MediaType.MOVIE) }
        else
            api.getTvRecommendations(id).results.map { it.toDomain(MediaType.TV) }

    // ── Search ─────────────────────────────────────────────────────────────────
    suspend fun search(query: String): List<Media> =
        api.searchMulti(query).results
            .filter { it.mediaType != "person" }
            .map { it.toDomain() }

    // ── Watchlist ──────────────────────────────────────────────────────────────
    fun getWatchlist(): Flow<List<WatchlistItem>> = watchlistDao.getAll()

    suspend fun isInWatchlist(tmdbId: Int): Boolean =
        watchlistDao.get(tmdbId) != null

    suspend fun addToWatchlist(detail: MediaDetail) {
        watchlistDao.insert(
            WatchlistItem(
                tmdbId    = detail.tmdbId,
                title     = detail.title,
                posterPath= detail.posterPath,
                mediaType = detail.mediaType.name,
            )
        )
    }

    suspend fun removeFromWatchlist(tmdbId: Int) = watchlistDao.deleteById(tmdbId)

    // ── Watch history ──────────────────────────────────────────────────────────
    fun getHistory(): Flow<List<WatchHistory>> = historyDao.getRecent()

    suspend fun getPosition(tmdbId: Int, season: Int, episode: Int): Long {
        val key = "${tmdbId}_${season}_${episode}"
        return historyDao.get(key)?.positionMs ?: 0L
    }

    suspend fun saveProgress(
        tmdbId: Int,
        title: String,
        posterPath: String?,
        mediaType: MediaType,
        season: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        val key = "${tmdbId}_${season}_${episode}"
        historyDao.insert(
            WatchHistory(
                key        = key,
                tmdbId     = tmdbId,
                title      = title,
                posterPath = posterPath,
                mediaType  = mediaType.name,
                season     = season,
                episode    = episode,
                positionMs = positionMs,
                durationMs = durationMs,
            )
        )
    }
}
