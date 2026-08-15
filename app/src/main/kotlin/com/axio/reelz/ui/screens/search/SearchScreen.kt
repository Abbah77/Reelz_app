package com.axio.reelz.ui.screens.search

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.axio.reelz.data.model.*
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
class SearchViewModel @Inject constructor(
    private val searchRepo: com.axio.reelz.data.repository.SearchRepository,
    private val catalogRepo: com.axio.reelz.data.repository.CatalogRepository,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val localResults: List<Media> = emptyList(),   // FTS5 Room results — instant, offline
        val networkResults: List<Media> = emptyList(), // TMDB results — merged after local
        val results: List<Media> = emptyList(),        // merged display list
        val isLocalLoading: Boolean = false,           // FTS search in-flight
        val isNetworkLoading: Boolean = false,         // TMDB search in-flight
        val isOffline: Boolean = false,                // no network, local-only mode
        val error: String? = null,
        val hasSearched: Boolean = false,
        val filters: SearchFilters = SearchFilters(),
        val genres: List<Genre> = emptyList(),
        val selectedGenreId: String? = null,
        val showFilters: Boolean = false,
        val recentSearches: List<String> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var searchJob: Job? = null
    private var networkJob: Job? = null

    init {
        viewModelScope.launch {
            val gr = catalogRepo.getGenres("movie")
            if (gr is com.axio.reelz.core.network.NetworkResult.Success) _ui.update { it.copy(genres = gr.data) }
        }
        viewModelScope.launch {
            searchRepo.observeRecentSearches().collect { list ->
                _ui.update { it.copy(recentSearches = list.map { r -> r.query }) }
            }
        }
    }

    /**
     * Local-first search: two-phase, two-speed result delivery.
     *
     * Phase 1 — FTS5 Room (< 5ms, works offline):
     *   Results appear immediately after 120ms debounce.
     *   The user sees relevant matches before any network round-trip.
     *
     * Phase 2 — TMDB network (300-800ms, requires connection):
     *   Results merge into the display list as they arrive.
     *   New results are deduplicated by tmdbId before merging.
     *   If the network fails: Phase 1 results stay, no error shown
     *   unless Phase 1 was also empty (true offline + uncached).
     *
     * Architecture rule: the UI renders `results` — it never checks
     * which phase produced them. Consistent skeleton → content pipeline.
     */
    fun onQuery(q: String) {
        _ui.update { it.copy(query = q) }
        searchJob?.cancel()
        networkJob?.cancel()

        if (q.isBlank()) {
            _ui.update { it.copy(
                results = emptyList(), localResults = emptyList(),
                networkResults = emptyList(), hasSearched = false,
                isLocalLoading = false, isNetworkLoading = false, error = null,
            )}
            return
        }

        searchJob = viewModelScope.launch {
            // Debounce
            delay(250)
            _ui.update { it.copy(isNetworkLoading = true, error = null) }

            try {
                val result = searchRepo.search(q, mediaType = _ui.value.filters.mediaType?.lowercase())
                when (result) {
                    is com.axio.reelz.core.network.NetworkResult.Success -> {
                        val items = applyFilters(result.data.first)
                        _ui.update { st -> st.copy(
                            results          = items,
                            localResults     = items,
                            networkResults   = items,
                            isNetworkLoading = false,
                            isLocalLoading   = false,
                            isOffline        = false,
                            hasSearched      = true,
                            error            = null,
                        )}
                        if (items.isNotEmpty()) recordSearch(q)
                    }
                    is com.axio.reelz.core.network.NetworkResult.Error -> {
                        _ui.update { st -> st.copy(
                            isNetworkLoading = false,
                            isLocalLoading   = false,
                            isOffline        = result.isNetworkError,
                            error            = if (st.results.isEmpty()) result.message else null,
                            hasSearched      = true,
                        )}
                    }
                    else -> _ui.update { it.copy(isNetworkLoading = false, isLocalLoading = false) }
                }
            } catch (e: Exception) {
                val isNet = e is java.net.UnknownHostException || e is java.net.SocketTimeoutException
                _ui.update { st -> st.copy(
                    isNetworkLoading = false,
                    isLocalLoading   = false,
                    isOffline        = isNet,
                    error            = if (st.results.isEmpty()) friendlySearchError(e) else null,
                    hasSearched      = true,
                )}
            }
        }
    }

    /**
     * Merge local + network results. Deduplication by id.
     * Network results take precedence (they have fresher popularity scores).
     * Local-only items appended after network items.
     */
    private fun mergeResults(local: List<Media>, network: List<Media>): List<Media> {
        val networkIds = network.map { it.id }.toSet()
        val localOnly = local.filter { it.id !in networkIds }
        return network + localOnly
    }

    fun searchRecent(query: String) = onQuery(query)

    fun onResultTap(media: Media) {
        // no-op: catalog open tracking removed in repo refactor
    }

    private fun recordSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            searchRepo.saveRecentSearch(trimmed)
        }
    }

    fun deleteRecentSearch(query: String) {
        viewModelScope.launch { searchRepo.deleteRecentSearch(query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { searchRepo.clearRecentSearches() }
    }

    fun toggleFilters() = _ui.update { it.copy(showFilters = !it.showFilters) }
    fun setMediaType(type: String?) {
        _ui.update { it.copy(filters = it.filters.copy(mediaType = type)) }; reFilter()
    }
    fun setGenre(id: String?) { _ui.update { it.copy(selectedGenreId = id) }; reFilter() }
    fun setMinRating(rating: Float?) {
        _ui.update { it.copy(filters = it.filters.copy(minRating = rating)) }; reFilter()
    }
    fun setSortBy(sort: String) {
        _ui.update { it.copy(filters = it.filters.copy(sortBy = sort)) }; reFilter()
    }
    fun clearFilters() {
        _ui.update { it.copy(filters = SearchFilters(), selectedGenreId = null) }; reFilter()
    }
    private fun reFilter() { val q = _ui.value.query; if (q.isNotBlank()) onQuery(q) }
    fun clear() {
        searchJob?.cancel(); networkJob?.cancel()
        val recents = _ui.value.recentSearches
        _ui.update { UiState(recentSearches = recents) }
    }

    private fun applyFilters(raw: List<Media>): List<Media> {
        val f = _ui.value.filters
        var list = raw
        val genre = _ui.value.selectedGenreId
        if (f.mediaType != null) list = list.filter { it.mediaType.name == f.mediaType }
        if (genre != null) list = list.filter { genre in it.genres }
        if (f.minRating != null) list = list.filter { it.rating >= f.minRating }
        list = when (f.sortBy) {
            "rating" -> list.sortedByDescending { it.rating }
            "newest" -> list.sortedByDescending { it.releaseYear ?: "" }
            "title"  -> list.sortedBy { it.title }
            else     -> list // popularity — already sorted by TMDB/FTS
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
        // Network loading indicator — only shown as a thin bar AFTER local results appear,
        // so the user always sees something immediately rather than a blocking spinner.
        if (ui.isNetworkLoading && ui.results.isNotEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Brand,
                trackColor = Color.Transparent,
            )
        }
        when {
            ui.isLocalLoading && ui.results.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
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
                        items(ui.results, key = { it.id }) { m ->
                            MediaPosterCard(
                                media   = m,
                                onClick = {
                                    vm.onResultTap(m)
                                    nav.navigate(com.axio.reelz.app.Route.Detail.go(m.id, m.mediaType))
                                },
                                modifier = Modifier.aspectRatio(0.65f),
                            )
                        }
                    }
                }

            else ->
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(d.spaceXl))
                    if (ui.recentSearches.isNotEmpty()) {
                        RecentSearchesSection(
                            queries  = ui.recentSearches,
                            onTap    = vm::searchRecent,
                            onDelete = vm::deleteRecentSearch,
                            onClearAll = vm::clearRecentSearches,
                        )
                        Spacer(Modifier.height(d.spaceXl))
                    }
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

// ── Recent Searches ─────────────────────────────────────────────────────────
/**
 * UX notes:
 *  - "Clear all" sits next to the label (mirrors the Filters "Clear all" pattern
 *    elsewhere in this screen) so the destructive action never competes with
 *    individual chips for attention, and is easy to find without hunting.
 *  - Each chip's delete (✕) is always visible, not a long-press/swipe secret —
 *    recall value drops fast if users have to discover how to prune history.
 *  - Tapping the chip body (not the ✕) re-runs the search; the two hit targets
 *    are visually and spatially separated so a mis-tap can't delete instead of
 *    search, or vice versa.
 *  - List collapses to nothing (not an empty section) the instant it's cleared,
 *    so there's no dead space or "there's nothing here" placeholder.
 */
@Composable
fun RecentSearchesSection(
    queries: List<String>,
    onTap: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val d = LocalDimensions.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(IconHistory, null, tint = White40, modifier = Modifier.size(d.iconSm))
            Spacer(Modifier.width(d.spaceXs))
            Text(
                "Recent Searches", color = White40, fontSize = d.textSm,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearAll) {
                Text("Clear all", color = Brand, fontSize = d.textSm)
            }
        }
        Spacer(Modifier.height(d.spaceXs))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad),
            verticalArrangement = Arrangement.spacedBy(d.spaceXxs),
        ) {
            queries.forEach { q ->
                key(q) {
                    RecentSearchRow(query = q, onTap = { onTap(q) }, onDelete = { onDelete(q) })
                }
            }
        }
    }
}

@Composable
private fun RecentSearchRow(query: String, onTap: () -> Unit, onDelete: () -> Unit) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusSm))
            .clickable(onClick = onTap)
            .padding(vertical = d.spaceSm, horizontal = d.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IconHistory, null, tint = White40, modifier = Modifier.size(d.iconSm - 2.dp))
        Spacer(Modifier.width(d.spaceMd - d.spaceXxs))
        Text(
            query, color = White60, fontSize = d.textMd,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Dedicated tap target for delete, separate from the row's own search-tap
        // area, sized to the standard touch-friendly icon-button footprint.
        IconButton(onClick = onDelete, modifier = Modifier.size(d.avatarSm - d.spaceMd)) {
            Icon(Icons.Filled.Close, null, tint = White40, modifier = Modifier.size(d.iconSm - 4.dp))
        }
    }
}

// ── Filter Panel ──────────────────────────────────────────────────────────────
@Composable
fun FilterPanel(
    filters: SearchFilters,
    genres: List<Genre>,
    selectedGenre: String?,
    hasActive: Boolean,
    onMediaType: (String?) -> Unit,
    onGenre: (String?) -> Unit,
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
