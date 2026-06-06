package com.reelz.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelz.data.model.*
import com.reelz.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val isLoading: Boolean          = true,
    val detail: MediaDetail?        = null,
    val episodes: List<Episode>     = emptyList(),
    val selectedSeason: Int         = 1,
    val recommendations: List<Media> = emptyList(),
    val isInWatchlist: Boolean      = false,
    val error: String?              = null,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repo: MediaRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()

    fun load(tmdbId: Int, mediaType: MediaType) {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            try {
                val detail = repo.getDetail(tmdbId, mediaType)
                val inWl   = repo.isInWatchlist(tmdbId)
                val recs   = repo.getRecommendations(tmdbId, mediaType)
                _ui.update { it.copy(
                    isLoading       = false,
                    detail          = detail,
                    isInWatchlist   = inWl,
                    recommendations = recs,
                    selectedSeason  = if (detail.seasons.isNotEmpty()) detail.seasons.first().seasonNumber else 1,
                )}
                // Auto-load first season episodes for TV
                if (mediaType == MediaType.TV && detail.seasons.isNotEmpty()) {
                    loadEpisodes(tmdbId, detail.seasons.first().seasonNumber)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun selectSeason(showId: Int, season: Int) {
        _ui.update { it.copy(selectedSeason = season, episodes = emptyList()) }
        loadEpisodes(showId, season)
    }

    private fun loadEpisodes(showId: Int, season: Int) {
        viewModelScope.launch {
            try {
                val eps = repo.getSeasonEpisodes(showId, season)
                _ui.update { it.copy(episodes = eps) }
            } catch (_: Exception) {}
        }
    }

    fun toggleWatchlist() {
        val detail = _ui.value.detail ?: return
        viewModelScope.launch {
            if (_ui.value.isInWatchlist) {
                repo.removeFromWatchlist(detail.tmdbId)
                _ui.update { it.copy(isInWatchlist = false) }
            } else {
                repo.addToWatchlist(detail)
                _ui.update { it.copy(isInWatchlist = true) }
            }
        }
    }
}
