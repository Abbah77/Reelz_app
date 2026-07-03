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

data class SearchFilters(
    val mediaType: String? = null,
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
            try { _ui.update { it.copy(genres = repo.getMovieGenres()) } } catch (_: Exception) {}
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
                _ui.update { it.copy(results = applyFilters(repo.search(q)), isLoading = false, hasSearched = true) }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = friendlySearchError(e), hasSearched = true) }
            }
        }
    }

    fun toggleFilters() = _ui.update { it.copy(showFilters = !it.showFilters) }
    fun setMediaType(type: String?) { _ui.update { it.copy(filters = it.filters.copy(mediaType = type)) }; reFilter() }
    fun setGenre(id: Int?) { _ui.update { it.copy(selectedGenreId = id) }; reFilter() }
    fun setMinRating(rating: Float?) { _ui.update { it.copy(filters = it.filters.copy(minRating = rating)) }; reFilter() }
    fun setSortBy(sort: String) { _ui.update { it.copy(filters = it.filters.copy(sortBy = sort)) }; reFilter() }
    fun clearFilters() { _ui.update { it.copy(filters = SearchFilters(), selectedGenreId = null) }; reFilter() }
    private fun reFilter() { val q = _ui.value.query; if (q.isNotBlank()) onQuery(q) }
    fun clear() { searchJob?.cancel(); _ui.update { UiState() } }

    private fun applyFilters(raw: List<Media>): List<Media> {
        val f = _ui.value.filters
        var list = raw
        if (f.mediaType != null) list = list.filter { it.mediaType.name == f.mediaType }
        if (f.minRating != null) list = list.filter { it.voteAverage >= f.minRating }
        list = when (f.sortBy) {
            "rating" -> list.sortedByDescending { it.voteAverage }
            "newest" -> list.sortedByDescending { it.releaseDate }
            "title"  -> list.sortedBy { it.title }
            else     -> list
        }
        return list
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavController, vm: SearchViewModel = hiltViewModel()) {
    val d = LocalDimensions.current
    val ui by vm.ui.collectAsState()
    val focusReq = remember { FocusRequester() }
    val hasActiveFilters = ui.filters.mediaType != null || ui.selectedGenreId != null ||
                           ui.filters.minRating != null || ui.filters.sortBy != "popularity"

    LaunchedEffect(Unit) { focusReq.requestFocus() }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {

        // ── Search bar row ──────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad, vertical = d.spaceLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
        ) {
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(d.radiusMd))
                    .background(Brush.linearGradient(listOf(Color(0x18FFFFFF), Color(0x0AFFFFFF))))
                    .border(d.borderThin, if (ui.query.isNotBlank()) Brand.copy(.6f) else GlassBorderMd, RoundedCornerShape(d.radiusMd)),
            ) {
                TextField(
                    value = ui.query,
                    onValueChange = vm::onQuery,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusReq),
                    placeholder = { Text("Search movies, series…", color = White40, fontSize = d.textMd) },
                    leadingIcon = {
                        Icon(IconSearch, null, tint = if (ui.query.isNotBlank()) Brand else White40, modifier = Modifier.size(d.iconMd))
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(d.radiusSm))
                                    .background(if (hasActiveFilters) BlueGlass else Color.Transparent)
                                    .border(d.borderThin, if (hasActiveFilters) BlueBorder else Color.Transparent, RoundedCornerShape(d.radiusSm))
                                    .clickable { vm.toggleFilters() }
                                    .padding(horizontal = d.spaceSm + d.spaceXxs, vertical = d.spaceXs),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceXs)) {
                                    Icon(IconFilter, null, tint = if (hasActiveFilters) Brand else White40, modifier = Modifier.size(d.iconMd - 4.dp))
                                    if (hasActiveFilters) {
                                        Box(Modifier.size(d.spaceXs).clip(CircleShape).background(Brand))
                                    }
                                }
                            }
                            AnimatedVisibility(ui.query.isNotBlank(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                                IconButton(onClick = vm::clear) {
                                    Box(Modifier.size(d.avatarSm - d.spaceSm).clip(CircleShape).background(GlassMd), Alignment.Center) {
                                        Text("✕", color = White60, fontSize = d.textXs)
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
                Text("Cancel", color = Brand, fontWeight = FontWeight.SemiBold, fontSize = d.textMd)
            }
        }

        // ── Filter panel ─────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = ui.showFilters,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit  = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            FilterPanel(
                filters       = ui.filters,
                genres        = ui.genres,
                selectedGenre = ui.selectedGenreId,
                hasActive     = hasActiveFilters,
                onMediaType   = vm::setMediaType,
                onGenre       = vm::setGenre,
                onRating      = vm::setMinRating,
                onSort        = vm::setSortBy,
                onClear       = vm::clearFilters,
            )
        }

        // ── Results ──────────────────────────────────────────────────────────
        when {
            ui.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CinematicSpinner(size = d.spinnerLg)
            }

            ui.error != null -> ErrorState(ui.error!!, onRetry = { vm.onQuery(ui.query) })

            ui.results.isEmpty() && ui.hasSearched ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(d.avatarLg + d.spaceXl).clip(CircleShape)
                                .background(Brush.radialGradient(listOf(GlassMd, Color.Transparent))))
                            Icon(IconMovieSlate, null, tint = White40, modifier = Modifier.size(d.iconXl - 2.dp))
                        }
                        Spacer(Modifier.height(d.spaceLg))
                        Text("No results for", color = White40, fontSize = d.textMd)
                        Text("\"${ui.query}\"", color = White60, fontSize = d.textXxl, fontWeight = FontWeight.Bold)
                        if (hasActiveFilters) {
                            Spacer(Modifier.height(d.spaceMd))
                            TextButton(onClick = vm::clearFilters) {
                                Text("Clear filters", color = Brand, fontSize = d.textMd)
                            }
                        }
                    }
                }

            ui.results.isNotEmpty() ->
                Column {
                    Text(
                        "${ui.results.size} results",
                        color = White40,
                        fontSize = d.textSm,
                        modifier = Modifier.padding(horizontal = d.screenHorizPad, vertical = d.sectionVertPad),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (d.isTablet) 4 else 3),
                        contentPadding = PaddingValues(horizontal = d.screenHorizPad - d.spaceXxs, vertical = d.sectionVertPad),
                        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                        verticalArrangement   = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
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
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(d.spaceXl))
                    if (ui.genres.isNotEmpty()) {
                        Text("Browse by Genre", color = White40, fontSize = d.textSm,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = d.screenHorizPad))
                        Spacer(Modifier.height(d.spaceMd - d.spaceXxs))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = d.screenHorizPad),
                            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp),
                        ) {
                            items(ui.genres.take(10)) { g ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                        .background(BgSurface)
                                        .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXxs))
                                        .clickable { vm.onQuery(g.name) }
                                        .padding(horizontal = d.chipHorizPad + d.spaceXs, vertical = d.chipVertPad + d.spaceXs),
                                ) {
                                    Text(g.name, color = White60, fontSize = d.textMd)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(d.spaceXxl + d.spaceXl))
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(d.avatarLg + d.spaceXxl).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(BlueGlass, Color.Transparent)))
                            .border(d.borderThin, BlueBorder, CircleShape))
                        Icon(IconSearch, null, tint = Brand.copy(.7f), modifier = Modifier.size(d.iconXl))
                    }
                    Spacer(Modifier.height(d.spaceLg))
                    Text("Discover anything", color = White60, fontSize = d.textXl, fontWeight = FontWeight.Medium)
                    Text("Movies, TV shows, actors…", color = White40, fontSize = d.textMd)
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
    val d = LocalDimensions.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(BgCard, BgRaised)))
            .border(BorderStroke(d.borderThin, GlassBorderMd))
            .padding(horizontal = d.screenHorizPad, vertical = d.spaceLg - d.spaceXxs),
        verticalArrangement = Arrangement.spacedBy(d.spaceLg - d.spaceXxs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(IconFilter, null, tint = Brand, modifier = Modifier.size(d.iconMd - 4.dp))
            Spacer(Modifier.width(d.spaceSm))
            Text("Filters", color = White, fontWeight = FontWeight.Bold, fontSize = d.textMd)
            Spacer(Modifier.weight(1f))
            if (hasActive) {
                TextButton(onClick = onClear) { Text("Clear all", color = Brand, fontSize = d.textSm) }
            }
        }
        FilterRow("Type") {
            FilterChipRow(listOf("All" to null, "Movies" to "MOVIE", "TV Shows" to "TV"), filters.mediaType, onMediaType)
        }
        FilterRow("Sort by") {
            FilterChipRow(listOf("Popular" to "popularity", "Top Rated" to "rating", "Newest" to "newest", "A-Z" to "title"), filters.sortBy) { onSort(it ?: "popularity") }
        }
        FilterRow("Min. Rating") {
            FilterChipRow(listOf("Any" to null, "6+" to 6f, "7+" to 7f, "8+" to 8f, "9+" to 9f), filters.minRating, onRating)
        }
        if (genres.isNotEmpty()) {
            FilterRow("Genre") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp)) {
                    item { SmallFilterChip("All", selectedGenre == null) { onGenre(null) } }
                    items(genres) { g -> SmallFilterChip(g.name, selectedGenre == g.id) { onGenre(g.id) } }
                }
            }
        }
    }
}

@Composable
fun FilterRow(label: String, content: @Composable () -> Unit) {
    val d = LocalDimensions.current
    Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
        Text(label, color = White60, fontSize = d.textXs, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        content()
    }
}

@Composable
fun <T> FilterChipRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    val d = LocalDimensions.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp)) {
        items(options) { (label, value) -> SmallFilterChip(label, selected == value) { onSelect(value) } }
    }
}

@Composable
fun SmallFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val d = LocalDimensions.current
    Box(
        Modifier
            .clip(RoundedCornerShape(d.radiusSm + d.spaceXxs))
            .background(if (selected) Brush.horizontalGradient(listOf(BrandDeep, Brand.copy(.8f)))
                        else Brush.horizontalGradient(listOf(BgSurface, BgOverlay)))
            .border(d.borderThin, if (selected) Brand.copy(.5f) else GlassBorder, RoundedCornerShape(d.radiusSm + d.spaceXxs))
            .clickable(onClick = onClick)
            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm),
    ) {
        Text(label, color = if (selected) Color.White else White60, fontSize = d.textSm,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun friendlySearchError(e: Exception): String {
    val msg = e.message?.lowercase() ?: ""
    return when {
        msg.contains("unable to resolve host") || msg.contains("network") ||
        msg.contains("timeout") || msg.contains("connect") ->
            "No internet connection. Check your connection and try again."
        else -> "Search failed. Please try again."
    }
}
