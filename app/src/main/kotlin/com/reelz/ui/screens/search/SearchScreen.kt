package com.reelz.ui.screens.search

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
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
            delay(380)
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

        // ── Luxury search bar ───────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(BgCard)
                    .border(
                        1.dp,
                        if (ui.query.isNotBlank()) AmberBorder else GlassBorderMd,
                        RoundedCornerShape(16.dp)
                    ),
            ) {
                TextField(
                    value = ui.query,
                    onValueChange = vm::onQuery,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusReq),
                    placeholder = {
                        Text(
                            "Search movies, series…",
                            color = White40,
                            fontSize = 14.sp,
                        )
                    },
                    leadingIcon = {
                        Icon(IconSearch, null, tint = if (ui.query.isNotBlank()) Brand else White40, modifier = Modifier.size(20.dp))
                    },
                    trailingIcon = {
                        AnimatedVisibility(ui.query.isNotBlank(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                            IconButton(onClick = vm::clear) {
                                Box(
                                    Modifier.size(22.dp).clip(CircleShape).background(GlassMd),
                                    Alignment.Center,
                                ) {
                                    Text("✕", color = White60, fontSize = 11.sp)
                                }
                            }
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
                Text("Cancel", color = Brand, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }

        // ── Results ─────────────────────────────────────────────────────────
        when {
            ui.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = 48.dp) }

            ui.error != null -> ErrorState(ui.error!!, onRetry = { vm.onQuery(ui.query) })

            ui.results.isEmpty() && ui.hasSearched ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(80.dp).clip(CircleShape)
                                .background(Brush.radialGradient(listOf(GlassMd, Color.Transparent))))
                            Icon(IconMovieSlate, null, tint = White40, modifier = Modifier.size(34.dp))
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("No results for", color = White40, fontSize = 14.sp)
                        Text("\"${ui.query}\"", color = White60, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

            ui.results.isNotEmpty() ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp, ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement   = Arrangement.spacedBy(10.dp),
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

            else ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(88.dp).clip(CircleShape)
                                .background(Brush.radialGradient(listOf(AmberGlass, Color.Transparent)))
                                .border(1.dp, AmberBorder, CircleShape))
                            Icon(IconSearch, null, tint = Brand.copy(.7f), modifier = Modifier.size(36.dp))
                        }
                        Text("Discover anything", color = White60, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Movies, TV shows, actors…", color = White40, fontSize = 13.sp)
                    }
                }
        }
    }
}
