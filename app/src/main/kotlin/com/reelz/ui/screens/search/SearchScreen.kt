package com.reelz.ui.screens.search

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.reelz.data.model.*
import com.reelz.data.repository.MediaRepository
import com.reelz.ui.components.*
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(private val repo: MediaRepository) : ViewModel() {
    data class UiState(
        val query: String = "",
        val results: List<Media> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val hasSearched: Boolean = false,
    )
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var searchJob: Job? = null

    fun onQuery(q: String) {
        _ui.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.isBlank()) { _ui.update { it.copy(results = emptyList(), hasSearched = false) }; return }
        searchJob = viewModelScope.launch {
            delay(400)  // debounce
            _ui.update { it.copy(isLoading = true, error = null) }
            try {
                val results = repo.search(q)
                _ui.update { it.copy(results = results, isLoading = false, hasSearched = true) }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message, hasSearched = true) }
            }
        }
    }
    fun clear() { searchJob?.cancel(); _ui.update { UiState() } }
}

@Composable
fun SearchScreen(nav: NavController, vm: SearchViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val focusReq = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusReq.requestFocus() }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        // ── Search bar ─────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(BgCard).border(1.dp, GlassBorderMd, RoundedCornerShape(14.dp)),
            ) {
                TextField(
                    value = ui.query,
                    onValueChange = vm::onQuery,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusReq),
                    placeholder = { Text("Search movies, TV shows…", color = White40) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = White40) },
                    trailingIcon = {
                        if (ui.query.isNotBlank()) {
                            IconButton(onClick = vm::clear) { Icon(Icons.Default.Close, null, tint = White40) }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.onQuery(ui.query) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor        = White,
                        unfocusedTextColor      = White,
                        cursorColor             = Brand,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
            TextButton(onClick = { nav.popBackStack() }) {
                Text("Cancel", color = Brand)
            }
        }

        // ── Results ────────────────────────────────────────────────────
        when {
            ui.isLoading -> FullScreenLoader()
            ui.error != null -> ErrorState(ui.error!!, onRetry = { vm.onQuery(ui.query) })
            ui.results.isEmpty() && ui.hasSearched -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.SearchOff, null, tint = White40, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No results for \"${ui.query}\"", color = White60)
                }
            }
            ui.results.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(ui.results, key = { it.tmdbId }) { m ->
                    MediaPosterCard(
                        media   = m,
                        onClick = { nav.navigate(com.reelz.ui.Route.Detail.go(m.tmdbId, m.mediaType)) },
                        modifier = Modifier.aspectRatio(0.65f),
                    )
                }
            }
            else -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Movie, null, tint = White20, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Search for anything", color = White40, fontSize = 15.sp)
                }
            }
        }
    }
}
