package com.axio.reelz.ui.screens.search

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.*
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
import com.axio.reelz.data.model.*
import com.axio.reelz.data.repository.MediaRepository
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

// ── Filter state ─────────────────────────────────────────────────────────────
data class SearchFilters(
    val mediaType: String? = null,    // "MOVIE", "TV", or null for both
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minRating: Float? = null,
    val sortBy: String = "popularity",
)

@HiltViewModel
class SearchViewModel @Inject constructor(private val repo: MediaRepository) : ViewModel() {
    data class UiState(
        val query: String = "",
        val results: List<Media> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val hasSearched: Boolean = false,
        val filters: SearchFilters = SearchFilters(),
        val genres: List<Genre> = emptyList(),
        val selectedGenreId: Int? = null,
        val showFilters: Boolean = false,
    )
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val genres = repo.getMovieGenres()
                _ui.update { it.copy(genres = genres) }
            } catch (_: Exception) {}
        }
    }

    fun onQuery(q: String) {
        _ui.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.isBlank()) { _ui.update { it.copy(results = emptyList(), hasSearched = false) }; return }
        searchJob = viewModelScope.launch {
            delay(320)
            _ui.update { it.copy(isLoading = true, error = null) }
            try {
                val results = repo.search(q)
                _ui.update { it.copy(results = applyFilters(results), isLoading = false, hasSearched = true) }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message, hasSearched = true) }
            }
        }
    }

    fun toggleFilters() = _ui.update { it.copy(showFilters = !it.showFilters) }

    fun setMediaType(type: String?) {
        _ui.update { it.copy(filters = it.filters.copy(mediaType = type)) }
        reFilter()
    }

    fun setGenre(id: Int?) {
        _ui.update { it.copy(selectedGenreId = id) }
        if (id == null) reFilter() else reFilter()
    }

    fun setMinRating(rating: Float?) {
        _ui.update { it.copy(filters = it.filters.copy(minRating = rating)) }
        reFilter()
    }

    fun setSortBy(sort: String) {
        _ui.update { it.copy(filters = it.filters.copy(sortBy = sort)) }
        reFilter()
    }

    fun clearFilters() {
        _ui.update { it.copy(filters = SearchFilters(), selectedGenreId = null) }
        reFilter()
    }

    private fun reFilter() {
        val q = _ui.value.query
        if (q.isNotBlank()) onQuery(q)
    }

    fun clear() { searchJob?.cancel(); _ui.update { UiState() } }

    private fun applyFilters(raw: List<Media>): List<Media> {
        val f = _ui.value.filters
        val gId = _ui.value.selectedGenreId
        var list = raw
        if (f.mediaType != null) {
            list = list.filter { it.mediaType.name == f.mediaType }
        }
        if (f.minRating != null) {
            list = list.filter { it.voteAverage >= f.minRating }
        }
        // Sort
        list = when (f.sortBy) {
            "rating"   -> list.sortedByDescending { it.voteAverage }
            "newest"   -> list.sortedByDescending { it.releaseDate }
            "title"    -> list.sortedBy { it.title }
            else       -> list // popularity (API order)
        }
        return list
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavController, vm: SearchViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val focusReq = remember { FocusRequester() }
    val hasActiveFilters = ui.filters.mediaType != null || ui.selectedGenreId != null ||
                           ui.filters.minRating != null || ui.filters.sortBy != "popularity"

    LaunchedEffect(Unit) { focusReq.requestFocus() }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {

        // ── Search bar row ──────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Horizontal search box
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0x18FFFFFF), Color(0x0AFFFFFF)))
                    )
                    .border(
                        1.dp,
                        if (ui.query.isNotBlank()) Brand.copy(.6f) else GlassBorderMd,
                        RoundedCornerShape(14.dp)
                    ),
            ) {
                TextField(
                    value = ui.query,
                    onValueChange = vm::onQuery,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusReq),
                    placeholder = {
                        Text("Search movies, series…", color = White40, fontSize = 14.sp)
                    },
                    leadingIcon = {
                        Icon(IconSearch, null, tint = if (ui.query.isNotBlank()) Brand else White40, modifier = Modifier.size(20.dp))
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Filter button inside search bar
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (hasActiveFilters) BlueGlass else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (hasActiveFilters) BlueBorder else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { vm.toggleFilters() }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(IconFilter, null, tint = if (hasActiveFilters) Brand else White40, modifier = Modifier.size(16.dp))
                                    if (hasActiveFilters) {
                                        Box(
                                            Modifier.size(6.dp).clip(CircleShape).background(Brand)
                                        )
                                    }
                                }
                            }
                            // Clear button
                            AnimatedVisibility(ui.query.isNotBlank(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                                IconButton(onClick = vm::clear) {
                                    Box(Modifier.size(22.dp).clip(CircleShape).background(GlassMd), Alignment.Center) {
                                        Text("✕", color = White60, fontSize = 11.sp)
                                    }
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

        // ── Filter panel — slides in ─────────────────────────────────────────
        AnimatedVisibility(
            visible = ui.showFilters,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit  = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            FilterPanel(
                filters        = ui.filters,
                genres         = ui.genres,
                selectedGenre  = ui.selectedGenreId,
                hasActive      = hasActiveFilters,
                onMediaType    = vm::setMediaType,
                onGenre        = vm::setGenre,
                onRating       = vm::setMinRating,
                onSort         = vm::setSortBy,
                onClear        = vm::clearFilters,
            )
        }

        // ── Results ─────────────────────────────────────────────────────────
        when {
            ui.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CinematicSpinner(size = 48.dp)
            }

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
                        if (hasActiveFilters) {
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = vm::clearFilters) {
                                Text("Clear filters", color = Brand, fontSize = 13.sp)
                            }
                        }
                    }
                }

            ui.results.isNotEmpty() ->
                Column {
                    // Results count
                    Text(
                        "${ui.results.size} results",
                        color = White40,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement   = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(ui.results, key = { it.tmdbId }) { m ->
                            MediaPosterCard(
                                media   = m,
                                onClick = { nav.navigate(com.axio.reelz.ui.Route.Detail.go(m.tmdbId, m.mediaType)) },
                                modifier = Modifier.aspectRatio(0.65f),
                            )
                        }
                    }
                }

            else ->
                // Empty state — discovery prompt
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(24.dp))
                    // Quick genre chips for discovery
                    if (ui.genres.isNotEmpty()) {
                        Text("Browse by Genre", color = White40, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(ui.genres.take(10)) { g ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(BgSurface)
                                        .border(1.dp, GlassBorderMd, RoundedCornerShape(10.dp))
                                        .clickable { vm.onQuery(g.name) }
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                ) {
                                    Text(g.name, color = White60, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(88.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(BlueGlass, Color.Transparent)))
                            .border(1.dp, BlueBorder, CircleShape))
                        Icon(IconSearch, null, tint = Brand.copy(.7f), modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Discover anything", color = White60, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Movies, TV shows, actors…", color = White40, fontSize = 13.sp)
                }
        }
    }
}

// ── Filter Panel ──────────────────────────────────────────────────────────────
@Composable
fun FilterPanel(
    filters: SearchFilters,
    genres: List<Genre>,
    selectedGenre: Int?,
    hasActive: Boolean,
    onMediaType: (String?) -> Unit,
    onGenre: (Int?) -> Unit,
    onRating: (Float?) -> Unit,
    onSort: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(BgCard, BgRaised))
            )
            .border(BorderStroke(1.dp, GlassBorderMd))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(IconFilter, null, tint = Brand, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Filters", color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (hasActive) {
                TextButton(onClick = onClear) {
                    Text("Clear all", color = Brand, fontSize = 12.sp)
                }
            }
        }

        // Type filter
        FilterRow("Type") {
            FilterChipRow(
                options = listOf("All" to null, "Movies" to "MOVIE", "TV Shows" to "TV"),
                selected = filters.mediaType,
                onSelect = onMediaType,
            )
        }

        // Sort by
        FilterRow("Sort by") {
            FilterChipRow(
                options = listOf("Popular" to "popularity", "Top Rated" to "rating", "Newest" to "newest", "A-Z" to "title"),
                selected = filters.sortBy,
                onSelect = { onSort(it ?: "popularity") },
            )
        }

        // Min rating
        FilterRow("Min. Rating") {
            FilterChipRow(
                options = listOf("Any" to null, "6+" to 6f, "7+" to 7f, "8+" to 8f, "9+" to 9f),
                selected = filters.minRating,
                onSelect = onRating,
            )
        }

        // Genre (scrollable)
        if (genres.isNotEmpty()) {
            FilterRow("Genre") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    item {
                        SmallFilterChip("All", selectedGenre == null) { onGenre(null) }
                    }
                    items(genres) { g ->
                        SmallFilterChip(g.name, selectedGenre == g.id) { onGenre(g.id) }
                    }
                }
            }
        }
    }
}

@Composable
fun FilterRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = White60, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        content()
    }
}

@Composable
fun <T> FilterChipRow(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(options) { (label, value) ->
            SmallFilterChip(label, selected == value) { onSelect(value) }
        }
    }
}

@Composable
fun SmallFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Brush.horizontalGradient(listOf(BrandDeep, Brand.copy(.8f)))
                        else Brush.horizontalGradient(listOf(BgSurface, BgOverlay)))
            .border(1.dp, if (selected) Brand.copy(.5f) else GlassBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) Color.White else White60, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
