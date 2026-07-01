package com.axio.reelz.ui.screens.explore

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.axio.reelz.data.model.*
import com.axio.reelz.data.repository.MediaRepository
import com.axio.reelz.ui.Route
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import java.util.Calendar

// ─────────────────────────────────────────────────────────────────────────────
// Explore is the discovery engine — distinct from Home (editorial rows) and
// Search (name lookup). It's for "I don't know what I want yet" moments:
// filter-first browsing across TMDB's full discover surface, plus one-tap
// mood presets that pre-fill filter combinations for people who don't want
// to fiddle with sliders.
// ─────────────────────────────────────────────────────────────────────────────

private const val CURRENT_YEAR = 2026 // fallback; real value read from Calendar at runtime

data class ExploreFilters(
    val mediaType: String = "MOVIE",          // "MOVIE" or "TV" — Explore is single-type per query (clearer mental model)
    val genreIds: Set<Int> = emptySet(),       // multi-select, AND-matched
    val sortBy: String = "popularity.desc",
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val ratingFrom: Float? = null,
    val runtimeFrom: Int? = null,              // movies only
    val runtimeTo: Int? = null,                // movies only
) {
    val isDefault: Boolean get() =
        genreIds.isEmpty() && sortBy == "popularity.desc" && yearFrom == null &&
        yearTo == null && ratingFrom == null && runtimeFrom == null && runtimeTo == null
}

/** A one-tap mood preset — pre-fills filters so users can dive in without configuring anything. */
data class MoodPreset(
    val label: String,
    val emoji: String,
    val apply: (ExploreFilters) -> ExploreFilters,
)

val moodPresets = listOf(
    MoodPreset("Hidden Gems", "💎") { it.copy(sortBy = "vote_average.desc", ratingFrom = 7.5f) },
    MoodPreset("This Decade", "🆕") { it.copy(yearFrom = 2020, sortBy = "popularity.desc") },
    MoodPreset("All-Time Greats", "🏆") { it.copy(sortBy = "vote_average.desc", ratingFrom = 8f) },
    MoodPreset("Quick Watch", "⚡") { it.copy(runtimeTo = 100, sortBy = "popularity.desc") },
    MoodPreset("Trending", "🔥") { it.copy(sortBy = "popularity.desc") },
    MoodPreset("Most Talked About", "💬") { it.copy(sortBy = "vote_count.desc") },
)

val sortOptions = listOf(
    "Most Popular"   to "popularity.desc",
    "Top Rated"      to "vote_average.desc",
    "Newest First"   to "primary_release_date.desc",
    "Oldest First"   to "primary_release_date.asc",
    "Most Discussed" to "vote_count.desc",
)

/** Explicit type instead of nested nullable Pairs — avoids Kotlin type-inference
 *  ambiguity when mixing literal `null` with `Int` values in a single list literal. */
data class RuntimeOption(val label: String, val from: Int?, val to: Int?)

val runtimeOptions = listOf(
    RuntimeOption("Any",        null, null),
    RuntimeOption("Under 90m",  null, 90),
    RuntimeOption("90–120m",    90,   120),
    RuntimeOption("2h+",        120,  null),
)

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val repo: MediaRepository,
) : ViewModel() {

    data class UiState(
        val filters: ExploreFilters = ExploreFilters(),
        val genres: List<Genre> = emptyList(),
        val results: List<Media> = emptyList(),
        val page: Int = 1,
        val isLoading: Boolean = true,
        val isLoadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val error: String? = null,
        val activeMood: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val genres = repo.getMovieGenres()
                _ui.update { it.copy(genres = genres) }
            } catch (_: Exception) {}
        }
        runQuery()
    }

    private fun runQuery(resetPage: Boolean = true) {
        viewModelScope.launch {
            val f = _ui.value.filters
            _ui.update { it.copy(isLoading = resetPage, error = null, page = if (resetPage) 1 else it.page) }
            try {
                val page = if (resetPage) 1 else _ui.value.page
                val items = if (f.mediaType == "MOVIE") {
                    repo.discoverMoviesAdvanced(
                        genreIds = f.genreIds.toList(), sortBy = f.sortBy, page = page,
                        yearFrom = f.yearFrom, yearTo = f.yearTo, ratingFrom = f.ratingFrom,
                        minVotes = if (f.sortBy == "vote_average.desc") 50 else null,
                        runtimeFrom = f.runtimeFrom, runtimeTo = f.runtimeTo,
                    )
                } else {
                    repo.discoverTvAdvanced(
                        genreIds = f.genreIds.toList(), sortBy = f.sortBy, page = page,
                        yearFrom = f.yearFrom, yearTo = f.yearTo, ratingFrom = f.ratingFrom,
                        minVotes = if (f.sortBy == "vote_average.desc") 50 else null,
                    )
                }
                _ui.update {
                    it.copy(
                        results       = if (resetPage) items else it.results + items,
                        page          = page,
                        isLoading     = false,
                        isLoadingMore = false,
                        hasMore       = items.isNotEmpty(),
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, isLoadingMore = false, error = friendlyExploreError(e)) }
            }
        }
    }

    fun loadMore() {
        val st = _ui.value
        if (st.isLoading || st.isLoadingMore || !st.hasMore) return
        _ui.update { it.copy(isLoadingMore = true, page = it.page + 1) }
        runQuery(resetPage = false)
    }

    fun setMediaType(type: String) {
        _ui.update { it.copy(filters = it.filters.copy(mediaType = type), activeMood = null) }
        runQuery()
    }

    fun toggleGenre(id: Int) {
        val current = _ui.value.filters.genreIds
        val updated = if (id in current) current - id else current + id
        _ui.update { it.copy(filters = it.filters.copy(genreIds = updated), activeMood = null) }
        runQuery()
    }

    fun setSort(sort: String) {
        _ui.update { it.copy(filters = it.filters.copy(sortBy = sort), activeMood = null) }
        runQuery()
    }

    fun setYearRange(from: Int?, to: Int?) {
        _ui.update { it.copy(filters = it.filters.copy(yearFrom = from, yearTo = to), activeMood = null) }
        runQuery()
    }

    fun setMinRating(rating: Float?) {
        _ui.update { it.copy(filters = it.filters.copy(ratingFrom = rating), activeMood = null) }
        runQuery()
    }

    fun setRuntime(from: Int?, to: Int?) {
        _ui.update { it.copy(filters = it.filters.copy(runtimeFrom = from, runtimeTo = to), activeMood = null) }
        runQuery()
    }

    fun applyMood(preset: MoodPreset) {
        _ui.update { it.copy(filters = preset.apply(ExploreFilters(mediaType = it.filters.mediaType)), activeMood = preset.label) }
        runQuery()
    }

    fun clearFilters() {
        _ui.update { it.copy(filters = ExploreFilters(mediaType = it.filters.mediaType), activeMood = null) }
        runQuery()
    }
}

@Composable
fun ExploreScreen(nav: NavController, vm: ExploreViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }
    val d = LocalDimensions.current
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }

    val activeFilterCount = with(ui.filters) {
        genreIds.size + listOf(yearFrom, yearTo, ratingFrom, runtimeFrom, runtimeTo).count { it != null } +
            (if (sortBy != "popularity.desc") 1 else 0)
    }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {

        // ── Header — same blue brand identity, compass mark for Explore ──────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.heroPadding - d.spaceSm, vertical = d.spaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Explore",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp
                    )
                )
                Text(
                    "Find your next watch",
                    color = Brand.copy(.85f), fontSize = d.textSm, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(IconCompass, null, tint = Brand.copy(.8f), modifier = Modifier.size(d.iconLg))
        }

        // ── Movie / TV switch — primary axis, large and obvious ─────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
        ) {
            TypeSwitchPill("Movies", ui.filters.mediaType == "MOVIE", Modifier.weight(1f)) { vm.setMediaType("MOVIE") }
            TypeSwitchPill("TV Shows", ui.filters.mediaType == "TV", Modifier.weight(1f)) { vm.setMediaType("TV") }
        }

        Spacer(Modifier.height(d.spaceLg - d.spaceXxs))

        // ── Mood presets — one-tap discovery, lowers the barrier to explore ──
        Text(
            "Quick picks",
            color = White40, fontSize = d.textXs, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = d.heroPadding - d.spaceXs),
        )
        Spacer(Modifier.height(d.spaceSm + d.spaceXxs))
        LazyRow(
            contentPadding = PaddingValues(horizontal = d.screenHorizPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
        ) {
            items(moodPresets) { mood ->
                MoodChip(mood, ui.activeMood == mood.label) { vm.applyMood(mood) }
            }
        }

        Spacer(Modifier.height(d.spaceMd - d.spaceXxs))

        // ── Filter bar — genre chips (scrollable) + filter button ───────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp),
            ) {
                items(ui.genres) { g ->
                    SmallFilterChip(g.name, g.id in ui.filters.genreIds) { vm.toggleGenre(g.id) }
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusMd - d.spaceSm))
                    .background(if (activeFilterCount > 0) Brand.copy(.18f) else GlassMd)
                    .border(1.dp, if (activeFilterCount > 0) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceSm))
                    .clickable { showFilterSheet = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceXs + 1.dp)) {
                    Icon(IconFilter, null, tint = if (activeFilterCount > 0) Brand else White60, modifier = Modifier.size(d.iconSm + 3.dp))
                    if (activeFilterCount > 0) {
                        Box(Modifier.size(d.iconMd - 4.dp).clip(CircleShape).background(Brand), Alignment.Center) {
                            Text("$activeFilterCount", color = Color.White, fontSize = d.textXxs, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(d.spaceMd))

        // ── Results grid ───────────────────────────────────────────────────
        when {
            ui.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = d.spinnerLg, color = Brand) }
            ui.error != null -> ErrorState(ui.error!!, onRetry = { vm.applyMood(moodPresets[4]) })
            ui.results.isEmpty() -> ExploreEmptyState(onClear = vm::clearFilters)
            else -> {
                val gridState = rememberLazyGridState()
                LaunchedEffect(gridState, ui.results.size) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .collect { lastVisible ->
                            if (lastVisible >= ui.results.size - 6) vm.loadMore()
                        }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = d.spaceMd - d.spaceXxs, vertical = d.sectionVertPad),
                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
                    verticalArrangement   = Arrangement.spacedBy(d.spaceMd),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(ui.results, key = { it.tmdbId }) { m ->
                        MediaPosterCard(
                            media   = m,
                            onClick = { nav.navigate(Route.Detail.go(m.tmdbId, m.mediaType)) },
                            modifier = Modifier.aspectRatio(0.65f),
                        )
                    }
                    if (ui.isLoadingMore) {
                        item(span = { GridItemSpan(3) }) {
                            Box(Modifier.fillMaxWidth().padding(vertical = d.spaceLg - d.spaceXxs), Alignment.Center) {
                                CinematicSpinner(size = d.spinnerMd, color = Brand)
                            }
                        }
                    }
                    item(span = { GridItemSpan(3) }) { Spacer(Modifier.height(d.avatarLg + d.spaceLg)) }
                }
            }
        }
    }

    if (showFilterSheet) {
        ExploreFilterSheet(
            filters      = ui.filters,
            currentYear  = currentYear,
            onYearRange  = vm::setYearRange,
            onRating     = vm::setMinRating,
            onRuntime    = vm::setRuntime,
            onSort       = vm::setSort,
            onClear      = vm::clearFilters,
            onDismiss    = { showFilterSheet = false },
        )
    }
}

@Composable
private fun ExploreEmptyState(onClear: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs)) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(d.avatarLg + d.spaceXl - d.spaceXs).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Brand.copy(.15f), Color.Transparent)))
                    .border(1.dp, Brand.copy(.3f), CircleShape))
                Icon(IconCompass, null, tint = Brand.copy(.8f), modifier = Modifier.size(d.buttonHeightSm - d.spaceMd))
            }
            Text("Nothing matches yet", color = White60, fontSize = d.textXl - 1.sp, fontWeight = FontWeight.SemiBold)
            Text("Try widening your filters", color = White40, fontSize = d.textMd)
            Spacer(Modifier.height(d.spaceXs))
            TextButton(onClick = onClear) { Text("Clear filters", color = Brand, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ── Type switch (Movies / TV) — large pill, primary navigation axis ─────────
@Composable
fun TypeSwitchPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
            .background(
                if (selected) Brush.horizontalGradient(listOf(Brand.copy(.85f), Brand2.copy(.85f)))
                else Brush.horizontalGradient(listOf(BgRaised, BgSurface))
            )
            .border(1.dp, if (selected) Brand.copy(.6f) else GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXxs))
            .clickable(onClick = onClick)
            .padding(vertical = d.spaceMd + 1.dp),
        contentAlignment = Alignment.Center,
    ) {
    val d = LocalDimensions.current
        Text(label, color = if (selected) Color.White else White60, fontSize = d.textMd,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

// ── Mood preset chip ──────────────────────────────────────────────────────────
@Composable
fun MoodChip(mood: MoodPreset, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(d.radiusPill))
            .background(
                if (selected) Brush.horizontalGradient(listOf(Brand.copy(.3f), Brand2.copy(.3f)))
                else Brush.horizontalGradient(listOf(BgRaised, BgSurface))
            )
            .border(1.dp, if (selected) Brand.copy(.6f) else GlassBorderMd, RoundedCornerShape(d.radiusPill))
            .clickable(onClick = onClick)
            .padding(horizontal = d.screenHorizPad - d.spaceXxs, vertical = d.spaceMd - d.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
    ) {
    val d = LocalDimensions.current
        Text(mood.emoji, fontSize = d.textMd)
        Text(mood.label, color = if (selected) White else White60, fontSize = d.textSm,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

// ── Filter bottom sheet — year range, rating floor, runtime, sort ───────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreFilterSheet(
    filters: ExploreFilters,
    currentYear: Int,
    onYearRange: (Int?, Int?) -> Unit,
    onRating: (Float?) -> Unit,
    onRuntime: (Int?, Int?) -> Unit,
    onSort: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val d = LocalDimensions.current
    var yearRange by remember(filters.yearFrom, filters.yearTo) {
        mutableStateOf((filters.yearFrom ?: 1970).toFloat()..(filters.yearTo ?: currentYear).toFloat())
    }
    var ratingFloor by remember(filters.ratingFrom) { mutableStateOf(filters.ratingFrom ?: 0f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        dragHandle = { Box(Modifier.padding(top = d.spaceMd).width(d.shimmerBarWidth).height(d.shimmerBarHeight).clip(RoundedCornerShape(2.dp)).background(GlassBorderHv)) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = d.heroPadding - d.spaceSm, vertical = d.spaceMd - d.spaceXxs).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(d.spaceXl - d.spaceXs),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(IconFilter, null, tint = Brand, modifier = Modifier.size(d.iconMd - 2.dp))
                Spacer(Modifier.width(d.spaceSm + d.spaceXxs))
                Text("Refine results", color = White, fontWeight = FontWeight.Bold, fontSize = d.textXl - 1.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Reset", color = White60, fontSize = d.textMd) }
            }

            // Sort
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
                Text("Sort by", color = White60, fontSize = d.textXs, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp)) {
                    items(sortOptions) { (label, value) ->
                        SmallFilterChip(label, filters.sortBy == value, accent = Brand) { onSort(value) }
                    }
                }
            }

            // Year range
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Release year", color = White60, fontSize = d.textXs, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    Text("${yearRange.start.toInt()} – ${yearRange.endInclusive.toInt()}", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.Bold)
                }
                RangeSlider(
                    value = yearRange,
                    onValueChange = { yearRange = it },
                    onValueChangeFinished = { onYearRange(yearRange.start.toInt(), yearRange.endInclusive.toInt()) },
                    valueRange = 1950f..currentYear.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Brand, activeTrackColor = Brand, inactiveTrackColor = GlassMd,
                    ),
                )
            }

            // Rating floor
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Minimum rating", color = White60, fontSize = d.textXs, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp)) {
                        Icon(IconStar, null, tint = Gold, modifier = Modifier.size(d.iconSm))
                        Text(if (ratingFloor > 0f) "%.1f+".format(ratingFloor) else "Any", color = Gold, fontSize = d.textSm, fontWeight = FontWeight.Bold)
                    }
                }
                Slider(
                    value = ratingFloor,
                    onValueChange = { ratingFloor = it },
                    onValueChangeFinished = { onRating(if (ratingFloor > 0f) ratingFloor else null) },
                    valueRange = 0f..9f,
                    steps = 17,
                    colors = SliderDefaults.colors(
                        thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = GlassMd,
                    ),
                )
            }

            // Runtime (movies only — visually de-emphasized for TV but harmless if shown)
            if (filters.mediaType == "MOVIE") {
                Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
                    Text("Runtime", color = White60, fontSize = d.textXs, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp)) {
                        items(runtimeOptions) { option ->
                            val isSelected = filters.runtimeFrom == option.from && filters.runtimeTo == option.to
                            SmallFilterChip(option.label, isSelected, accent = Brand) { onRuntime(option.from, option.to) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(d.spaceSm + d.spaceXxs))
        }
    }
}

// ── Local filter chip — accent color defaults to Brand, kept overridable ───
@Composable
fun SmallFilterChip(label: String, selected: Boolean, accent: Color = Brand, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(d.radiusSm + d.spaceXxs))
            .background(if (selected) Brush.horizontalGradient(listOf(accent.copy(.5f), accent.copy(.8f)))
                        else Brush.horizontalGradient(listOf(BgSurface, BgOverlay)))
            .border(1.dp, if (selected) accent.copy(.6f) else GlassBorder, RoundedCornerShape(d.radiusSm + d.spaceXxs))
            .clickable(onClick = onClick)
            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + 1.dp),
    ) {
    val d = LocalDimensions.current
        Text(label, color = if (selected) Color.White else White60, fontSize = d.textSm,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun friendlyExploreError(e: Exception): String {
    val msg = e.message?.lowercase() ?: ""
    return when {
        msg.contains("unable to resolve host") ||
        msg.contains("network") ||
        msg.contains("timeout") ||
        msg.contains("connect") -> "No internet connection. Check your connection and try again."
        else -> "Couldn't load content. Pull down to try again."
    }
}
