package com.streamapp.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamapp.data.model.MediaItem
import com.streamapp.data.repository.HomeData
import com.streamapp.data.repository.TmdbRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(val data: HomeData) : HomeUiState()
    data class Error(val msg: String) : HomeUiState()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: TmdbRepository
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = HomeUiState.Loading
            _state.value = try {
                HomeUiState.Success(repo.getHomeData())
            } catch (e: Exception) {
                HomeUiState.Error(e.message ?: "Failed to load")
            }
        }
    }
}
