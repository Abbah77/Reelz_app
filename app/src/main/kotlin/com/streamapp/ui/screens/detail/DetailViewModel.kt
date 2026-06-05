package com.streamapp.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamapp.data.model.*
import com.streamapp.data.repository.StreamRepository
import com.streamapp.data.repository.TmdbRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val loading: Boolean = true,
    val movie: Movie? = null,
    val show: TvShow? = null,
    val seasons: List<Season> = emptyList(),
    val currentSeason: Season? = null,
    val recommendations: List<MediaItem> = emptyList(),
    val streamResult: StreamResult = StreamResult.Loading,
    val error: String? = null,
    val mediaType: String = "movie",
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val tmdb: TmdbRepository,
    private val streams: StreamRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val tmdbId: Int = savedState.get<Int>("id") ?: 0
    private val mediaType: String = savedState.get<String>("type") ?: "movie"

    private val _state = MutableStateFlow(DetailUiState(mediaType = mediaType))
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                if (mediaType == "movie") loadMovie()
                else loadTv()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    private suspend fun loadMovie() {
        val movie = tmdb.getMovieDetail(tmdbId)
        val recs = tmdb.getMovieRecommendations(tmdbId)
        _state.update { it.copy(loading = false, movie = movie, recommendations = recs) }
        // Kick off stream scan in background
        scanStream(tmdbId.toString(), "movie", 1, 1)
    }

    private suspend fun loadTv() {
        val show = tmdb.getTvDetail(tmdbId)
        val recs = tmdb.getTvRecommendations(tmdbId)
        _state.update { it.copy(loading = false, show = show, recommendations = recs) }
        // Load first season
        if (show.numberOfSeasons > 0) loadSeason(1)
    }

    fun loadSeason(number: Int) {
        viewModelScope.launch {
            try {
                val season = tmdb.getSeasonDetail(tmdbId, number)
                val allSeasons = _state.value.seasons.toMutableList()
                val idx = allSeasons.indexOfFirst { it.seasonNumber == number }
                if (idx >= 0) allSeasons[idx] = season else allSeasons.add(season)
                _state.update { it.copy(seasons = allSeasons, currentSeason = season) }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun playEpisode(episode: Episode) {
        viewModelScope.launch {
            _state.update { it.copy(streamResult = StreamResult.Loading) }
            scanStream(tmdbId.toString(), "tv", episode.seasonNumber, episode.episodeNumber)
        }
    }

    private fun scanStream(id: String, type: String, season: Int, episode: Int) {
        viewModelScope.launch {
            _state.update { it.copy(streamResult = StreamResult.Loading) }
            val result = streams.resolveStream(id.toInt(), type, season, episode)
            _state.update { it.copy(streamResult = result) }
        }
    }
}
