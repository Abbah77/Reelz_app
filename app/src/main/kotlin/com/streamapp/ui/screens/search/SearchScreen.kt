package com.streamapp.ui.screens.search

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamapp.data.model.MediaItem
import com.streamapp.data.repository.TmdbRepository
import com.streamapp.ui.components.MediaCard
import com.streamapp.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────
sealed class SearchState {
    object Idle    : SearchState()
    object Loading : SearchState()
    data class Results(val items: List<MediaItem>) : SearchState()
    object Empty   : SearchState()
    data class Error(val msg: String) : SearchState()
}

@HiltViewModel
class SearchViewModel @Inject constructor(private val repo: TmdbRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)

    val state: StateFlow<SearchState> = _state.asStateFlow()
    val query: StateFlow<String>      = _query.asStateFlow()

    init {
        viewModelScope.launch {
            _query
                .debounce(400)
                .collectLatest { q ->
                    if (q.isBlank()) { _state.value = SearchState.Idle; return@collectLatest }
                    _state.value = SearchState.Loading
                    _state.value = try {
                        val results = repo.search(q)
                        if (results.isEmpty()) SearchState.Empty else SearchState.Results(results)
                    } catch (e: Exception) {
                        SearchState.Error(e.message ?: "Search failed")
                    }
                }
        }
    }

    fun onQuery(q: String) { _query.value = q }
    fun clear()            { _query.value = ""; _state.value = SearchState.Idle }
}

// ── Screen ────────────────────────────────────────────────────────────────────
@Composable
fun SearchScreen(
    onItemClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val query by viewModel.query.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface900)
            .statusBarsPadding(),
    ) {
        // Search bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = White)
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQuery,
                placeholder = { Text("Search movies, series…", color = White40) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor    = Primary,
                    unfocusedBorderColor  = Stroke,
                    focusedContainerColor = Surface800,
                    unfocusedContainerColor = Surface800,
                    cursorColor           = Primary,
                    focusedTextColor      = White,
                    unfocusedTextColor    = White,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                leadingIcon  = { Icon(Icons.Filled.Search, contentDescription = null, tint = White60) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = viewModel::clear) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = White60)
                        }
                    }
                },
            )
        }

        when (val s = state) {
            is SearchState.Idle -> SearchHints()

            is SearchState.Loading -> {
                Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Primary)
                }
            }

            is SearchState.Empty -> {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("🔍", fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No results for \"$query\"",
                            color = White60,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            is SearchState.Results -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp),
                ) {
                    items(s.items) { item ->
                        MediaCard(item = item, onClick = { onItemClick(item) })
                    }
                }
            }

            is SearchState.Error -> {
                Box(Modifier.fillMaxSize()) {
                    Text(s.msg, color = Error, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun SearchHints() {
    val suggestions = listOf("Action", "Comedy", "Drama", "Thriller", "Sci-Fi", "Horror", "Romance", "Documentary")
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Popular Searches", style = MaterialTheme.typography.headlineSmall, color = White)
        Spacer(Modifier.height(14.dp))
        suggestions.chunked(2).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                row.forEach { tag ->
                    Surface(
                        color = Surface700,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Stroke),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.TrendingUp,
                                contentDescription = null,
                                tint = PrimaryLight,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(tag, style = MaterialTheme.typography.labelMedium, color = White)
                        }
                    }
                }
            }
        }
    }
}
