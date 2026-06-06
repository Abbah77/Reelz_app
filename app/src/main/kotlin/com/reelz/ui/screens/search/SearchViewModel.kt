package com.reelz.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelz.data.model.Media
import com.reelz.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class SearchUiState(
    val query: String        = "",
    val results: List<Media> = emptyList(),
    val isLoading: Boolean   = false,
    val error: String?       = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: MediaRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(SearchUiState())
    val ui: StateFlow<SearchUiState> = _ui.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(q: String) {
        _ui.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.length < 2) {
            _ui.update { it.copy(results = emptyList(), isLoading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350) // debounce
            _ui.update { it.copy(isLoading = true, error = null) }
            try {
                val results = repo.search(q)
                _ui.update { it.copy(isLoading = false, results = results) }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun clearQuery() { _ui.update { SearchUiState() } }
}
