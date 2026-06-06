package com.reelz.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelz.data.model.HomeSection
import com.reelz.data.model.Media
import com.reelz.data.model.WatchHistory
import com.reelz.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean        = true,
    val featured: Media?          = null,
    val sections: List<HomeSection> = emptyList(),
    val continueWatching: List<WatchHistory> = emptyList(),
    val error: String?            = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: MediaRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    init {
        load()
        observeHistory()
    }

    private fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            try {
                val featured  = repo.getFeatured()
                val sections  = repo.getHomeSections()
                _ui.update { it.copy(isLoading = false, featured = featured, sections = sections) }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message ?: "Failed to load content") }
            }
        }
    }

    private fun observeHistory() {
        viewModelScope.launch {
            repo.getHistory().collectLatest { history ->
                _ui.update { it.copy(continueWatching = history) }
            }
        }
    }

    fun refresh() = load()
}
