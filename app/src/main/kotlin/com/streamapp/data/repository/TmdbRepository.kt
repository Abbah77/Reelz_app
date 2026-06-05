package com.streamapp.data.repository

import com.streamapp.BuildConfig
import com.streamapp.data.model.*
import com.streamapp.data.remote.api.TmdbApi
import com.streamapp.data.remote.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbRepository @Inject constructor(private val api: TmdbApi) {

    private val key = BuildConfig.TMDB_KEY

    // ── Home screen data ──────────────────────────────────────────────────────
    suspend fun getTrending(): List<MediaItem> = withContext(Dispatchers.IO) {
        api.trending(key).results?.map { it.toMediaItem() } ?: emptyList()
    }

    suspend fun getHomeData(): HomeData = withContext(Dispatchers.IO) {
        coroutineScope {
            val trending    = async { api.trending(key).results?.map { it.toMediaItem() } ?: emptyList() }
            val popMovies   = async { api.popularMovies(key).results?.map { it.toMediaItem() } ?: emptyList() }
            val popTv       = async { api.popularTv(key).results?.map { it.toMediaItem() } ?: emptyList() }
            val topMovies   = async { api.topRatedMovies(key).results?.map { it.toMediaItem() } ?: emptyList() }
            HomeData(
                trending   = trending.await(),
                popularMovies = popMovies.await(),
                popularTv  = popTv.await(),
                topRated   = topMovies.await(),
            )
        }
    }

    // ── Detail ────────────────────────────────────────────────────────────────
    suspend fun getMovieDetail(id: Int): Movie = withContext(Dispatchers.IO) {
        api.movieDetail(id, key).toDomain()
    }

    suspend fun getTvDetail(id: Int): TvShow = withContext(Dispatchers.IO) {
        api.tvDetail(id, key).toDomain()
    }

    suspend fun getSeasonDetail(showId: Int, season: Int): Season = withContext(Dispatchers.IO) {
        val dto = api.seasonDetail(showId, season, key)
        Season(
            seasonNumber = dto.seasonNumber ?: season,
            name = dto.name ?: "Season $season",
            episodeCount = dto.episodeCount ?: 0,
            posterPath = dto.posterPath,
            airDate = dto.airDate,
            episodes = dto.episodes?.map { it.toDomain(showId) } ?: emptyList(),
        )
    }

    // ── Search ────────────────────────────────────────────────────────────────
    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        api.searchMulti(key, query).results
            ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
            ?.map { it.toMediaItem() } ?: emptyList()
    }

    // ── Recommendations ───────────────────────────────────────────────────────
    suspend fun getMovieRecommendations(id: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        api.movieRecommendations(id, key).results?.map { it.toMediaItem() } ?: emptyList()
    }

    suspend fun getTvRecommendations(id: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        api.tvRecommendations(id, key).results?.map { it.toMediaItem() } ?: emptyList()
    }
}

data class HomeData(
    val trending: List<MediaItem>,
    val popularMovies: List<MediaItem>,
    val popularTv: List<MediaItem>,
    val topRated: List<MediaItem>,
)
