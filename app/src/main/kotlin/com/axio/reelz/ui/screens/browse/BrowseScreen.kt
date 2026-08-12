package com.axio.reelz.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.axio.reelz.app.Route
import com.axio.reelz.data.model.MediaType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.axio.reelz.ui.theme.Bg


// ── FeedRow ───────────────────────────────────────────────────────────────────
// (kept here so BrowseScreen composable can use it without changes)

sealed class FeedRow {
    data class Section(val section: com.axio.reelz.data.model.FeedSection) : FeedRow()
    data class InfinitePage(val items: List<com.axio.reelz.data.model.Media>, val page: Int) : FeedRow()
    object NativeAdPlacement : FeedRow()
}

@dagger.hilt.android.lifecycle.HiltViewModel
class BrowseViewModel @javax.inject.Inject constructor(
    private val repo: com.axio.reelz.data.repository.CatalogRepository,
    private val libraryRepo: com.axio.reelz.data.repository.LibraryRepository,
) : androidx.lifecycle.ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val isBackgroundRefreshing: Boolean = false,
        val error: String? = null,
        val featured: List<com.axio.reelz.data.model.Media> = emptyList(),
        val feedRows: List<FeedRow> = emptyList(),
        val genres: List<com.axio.reelz.data.model.Genre> = emptyList(),
        val selectedGenreId: String? = null,
        val genreItems: List<com.axio.reelz.data.model.Media> = emptyList(),
        val genreCursor: String? = null,
        val isGenreLoading: Boolean = false,
        val hasMoreGenrePages: Boolean = true,
        val continueWatching: List<com.axio.reelz.core.database.WatchProgressRow> = emptyList(),
        val isLoadingMore: Boolean = false,
        val isCacheLoaded: Boolean = false,
        val watchlistedIds: Set<String> = emptySet(),
    )

    private val _ui = kotlinx.coroutines.flow.MutableStateFlow(UiState())
    val ui: kotlinx.coroutines.flow.StateFlow<UiState> = _ui.asStateFlow()

    private var infiniteCursor: String? = null
    private var isInfiniteExhausted = false

    init {
        initLoad()
        // Keep continue-watching row live
        androidx.lifecycle.viewModelScope.launch {
            val history = libraryRepo.getRecentProgress(12)
            _ui.update { it.copy(continueWatching = history) }
        }
        // Keep watchlist set live for hero banner button
        androidx.lifecycle.viewModelScope.launch {
            libraryRepo.observeWatchlist().collect { list ->
                _ui.update { it.copy(watchlistedIds = list.map { w -> w.mediaId }.toSet()) }
            }
        }
    }

    /** Toggle a media item in/out of the watchlist from the hero banner. */
    fun toggleHeroWatchlist(media: com.axio.reelz.data.model.Media) {
        androidx.lifecycle.viewModelScope.launch {
            libraryRepo.toggleWatchlist(media)
        }
    }

    /**
     * Cache-first feed load.
     *
     *  STEP 1 — Serve cached feed instantly if fresh (0ms, no network).
     *  STEP 2 — If stale or empty, show skeleton and fetch from backend.
     *  STEP 3 — Backend response merges in-place; stale sections replaced.
     */
    private fun initLoad() {
        androidx.lifecycle.viewModelScope.launch {
            isInfiniteExhausted = false

            // STEP 1 — cache-first
            val cacheResult = repo.getFeed(forceRefresh = false)
            val cachedSections = (cacheResult as? com.axio.reelz.core.network.NetworkResult.Success)?.data
            val fromCache      = (cacheResult as? com.axio.reelz.core.network.NetworkResult.Success)?.fromCache == true

            if (!cachedSections.isNullOrEmpty()) {
                _ui.update {
                    it.copy(
                        isLoading              = false,
                        isCacheLoaded          = true,
                        error                  = null,
                        featured               = pickFeatured(cachedSections),
                        feedRows               = buildFeedRows(cachedSections),
                        isBackgroundRefreshing = fromCache,  // true = stale cache, refresh in bg
                    )
                }
            } else {
                _ui.update { it.copy(isLoading = true, isCacheLoaded = false, error = null) }
            }

            // Genres in parallel
            launch {
                val gResult = repo.getGenres("movie")
                val genres  = (gResult as? com.axio.reelz.core.network.NetworkResult.Success)?.data ?: emptyList()
                if (genres.isNotEmpty()) _ui.update { it.copy(genres = genres) }
            }

            // STEP 2/3 — If cache was stale or empty, fetch from backend
            if (cachedSections.isNullOrEmpty() || fromCache) {
                val freshResult = repo.getFeed(forceRefresh = fromCache)
                when (freshResult) {
                    is com.axio.reelz.core.network.NetworkResult.Success -> {
                        val fresh = freshResult.data
                        _ui.update {
                            it.copy(
                                isLoading              = false,
                                isCacheLoaded          = true,
                                isBackgroundRefreshing = false,
                                error                  = null,
                                featured               = pickFeatured(fresh),
                                feedRows               = buildFeedRows(fresh),
                            )
                        }
                    }
                    is com.axio.reelz.core.network.NetworkResult.Error -> {
                        if (cachedSections.isNullOrEmpty()) {
                            _ui.update {
                                it.copy(
                                    isLoading              = false,
                                    isCacheLoaded          = false,
                                    isBackgroundRefreshing = false,
                                    error                  = "Couldn't load content. Check your connection.",
                                )
                            }
                        } else {
                            // Stale cache on screen — network failure is silent
                            _ui.update { it.copy(isBackgroundRefreshing = false) }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun pickFeatured(sections: List<com.axio.reelz.data.model.FeedSection>): List<com.axio.reelz.data.model.Media> =
        sections.take(3).flatMap { it.items.take(2) }

    private fun buildFeedRows(sections: List<com.axio.reelz.data.model.FeedSection>): List<FeedRow> {
        val rows = sections.map { FeedRow.Section(it) }
        return buildList {
            rows.forEachIndexed { index, row ->
                add(row)
                if ((index + 1) % 3 == 0) add(FeedRow.NativeAdPlacement)
            }
        }
    }

    /** User-triggered pull-to-refresh. */
    fun load(forceRefresh: Boolean = true) {
        if (!forceRefresh) { initLoad(); return }
        androidx.lifecycle.viewModelScope.launch {
            _ui.update { it.copy(isRefreshing = true, error = null) }
            isInfiniteExhausted = false
            infiniteCursor = null

            launch {
                val gResult = repo.getGenres("movie")
                val genres  = (gResult as? com.axio.reelz.core.network.NetworkResult.Success)?.data ?: emptyList()
                if (genres.isNotEmpty()) _ui.update { it.copy(genres = genres) }
            }

            val result = repo.getFeed(forceRefresh = true)
            when (result) {
                is com.axio.reelz.core.network.NetworkResult.Success -> {
                    val sections = result.data
                    _ui.update {
                        it.copy(
                            isRefreshing  = false,
                            isCacheLoaded = true,
                            featured      = pickFeatured(sections),
                            feedRows      = buildFeedRows(sections),
                        )
                    }
                }
                is com.axio.reelz.core.network.NetworkResult.Error ->
                    _ui.update { it.copy(isRefreshing = false, error = result.message) }
                else -> _ui.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /** Infinite scroll — fetches discover pages and appends as InfinitePage rows. */
    fun loadMoreInfinite() {
        if (_ui.value.isLoadingMore || isInfiniteExhausted) return
        androidx.lifecycle.viewModelScope.launch {
            _ui.update { it.copy(isLoadingMore = true) }

            val existingIds = _ui.value.feedRows.flatMap { row ->
                when (row) {
                    is FeedRow.Section      -> row.section.items.map { it.id }
                    is FeedRow.InfinitePage -> row.items.map { it.id }
                    else                    -> emptyList()
                }
            }.toSet()

            val result = repo.discover(cursor = infiniteCursor)
            when (result) {
                is com.axio.reelz.core.network.NetworkResult.Success -> {
                    val (items, nextCursor) = result.data
                    val fresh = items.filter { it.id !in existingIds }
                    if (fresh.isEmpty()) {
                        isInfiniteExhausted = true
                        _ui.update { it.copy(isLoadingMore = false) }
                        return@launch
                    }
                    val pageIndex = _ui.value.feedRows.count { it is FeedRow.InfinitePage } + 1
                    infiniteCursor = nextCursor
                    if (nextCursor == null) isInfiniteExhausted = true
                    _ui.update { st ->
                        val alreadyPresent = st.feedRows.any { it is FeedRow.InfinitePage && (it as FeedRow.InfinitePage).page == pageIndex }
                        if (alreadyPresent) return@update st.copy(isLoadingMore = false)
                        st.copy(
                            feedRows      = st.feedRows + FeedRow.InfinitePage(fresh, pageIndex),
                            isLoadingMore = false,
                        )
                    }
                }
                else -> {
                    isInfiniteExhausted = true
                    _ui.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    /** Genre chip tap — fetches first page of genre content. */
    fun selectGenre(genreId: String?) {
        val current = _ui.value.selectedGenreId
        if (genreId == current) {
            _ui.update { it.copy(selectedGenreId = null, genreItems = emptyList(), genreCursor = null, hasMoreGenrePages = true) }
            return
        }
        _ui.update { it.copy(selectedGenreId = genreId, genreItems = emptyList(), genreCursor = null, hasMoreGenrePages = true, isGenreLoading = true) }
        androidx.lifecycle.viewModelScope.launch {
            val result = repo.discover(genre = genreId)
            when (result) {
                is com.axio.reelz.core.network.NetworkResult.Success -> {
                    _ui.update { it.copy(
                        genreItems      = result.data.first,
                        genreCursor     = result.data.second,
                        isGenreLoading  = false,
                        hasMoreGenrePages = result.data.second != null,
                    )}
                }
                else -> _ui.update { it.copy(isGenreLoading = false) }
            }
        }
    }

    fun loadMoreGenre() {
        val st = _ui.value
        if (st.isGenreLoading || !st.hasMoreGenrePages) return
        androidx.lifecycle.viewModelScope.launch {
            _ui.update { it.copy(isGenreLoading = true) }
            val result = repo.discover(genre = st.selectedGenreId, cursor = st.genreCursor)
            when (result) {
                is com.axio.reelz.core.network.NetworkResult.Success -> {
                    val (items, nextCursor) = result.data
                    _ui.update { it.copy(
                        genreItems        = (it.genreItems + items).distinctBy { m -> m.id },
                        genreCursor       = nextCursor,
                        hasMoreGenrePages = nextCursor != null,
                        isGenreLoading    = false,
                    )}
                }
                else -> _ui.update { it.copy(isGenreLoading = false) }
            }
        }
    }
}



// ── BrowseScreen composable ──────────────────────────────────────────────────

@androidx.compose.runtime.Composable
fun BrowseScreen(
    nav: androidx.navigation.NavController,
    adEngine: com.axio.reelz.ads.AdEngine,
    viewModel: BrowseViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    val ui by viewModel.ui.collectAsState()

    androidx.compose.foundation.lazy.LazyColumn(
        state    = listState,
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(com.axio.reelz.ui.theme.Bg),
    ) {
        // Loading state
        if (ui.isLoading) {
            item { com.axio.reelz.ui.components.SkeletonBannerLoader() }
            item { com.axio.reelz.ui.components.SkeletonRowLoader() }
            item { com.axio.reelz.ui.components.SkeletonRowLoader() }
            return@LazyColumn
        }

        // Error state
        ui.error?.let { err ->
            item {
                com.axio.reelz.ui.components.ErrorState(
                    message  = err,
                    onRetry  = { viewModel.load() },
                    modifier = androidx.compose.ui.Modifier.fillParentMaxSize(),
                )
            }
            return@LazyColumn
        }

        // Featured hero row
        if (ui.featured.isNotEmpty()) {
            item {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp, vertical = 8.dp,
                    ),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    items(ui.featured) { media ->
                        com.axio.reelz.ui.components.MediaPosterCard(
                            media   = media,
                            onClick = {
                                nav.navigate(
                                    com.axio.reelz.app.Route.Detail.go(
                                        media.id,
                                        media.mediaType,
                                    )
                                )
                            },
                            modifier = androidx.compose.ui.Modifier.width(160.dp),
                        )
                    }
                }
            }
        }

        // Feed rows
        items(
            count = ui.feedRows.size,
            key   = { index ->
                when (val row = ui.feedRows[index]) {
                    is FeedRow.Section      -> "section_${row.section.id}"
                    is FeedRow.InfinitePage -> "page_${row.page}"
                    FeedRow.NativeAdPlacement -> "native_$index"
                }
            },
        ) { index ->
            when (val row = ui.feedRows[index]) {
                is FeedRow.Section -> {
                    com.axio.reelz.ui.components.SectionHeader(title = row.section.title)
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 4.dp,
                        ),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        items(row.section.items) { media ->
                            com.axio.reelz.ui.components.MediaPosterCard(
                                media   = media,
                                onClick = {
                                    nav.navigate(
                                        com.axio.reelz.app.Route.Detail.go(
                                            media.id,
                                            media.mediaType,
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
                is FeedRow.InfinitePage -> {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 4.dp,
                        ),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        items(row.items) { media ->
                            com.axio.reelz.ui.components.MediaPosterCard(
                                media   = media,
                                onClick = {
                                    nav.navigate(
                                        com.axio.reelz.app.Route.Detail.go(
                                            media.id,
                                            media.mediaType,
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
                FeedRow.NativeAdPlacement -> com.axio.reelz.ads.NativeAdCard(adEngine = adEngine)
            }
        }

        // Infinite scroll trigger + loader
        item {
            if (ui.isLoadingMore) {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    com.axio.reelz.ui.components.SmallSpinner()
                }
            }
            androidx.compose.runtime.LaunchedEffect(ui.feedRows.size) {
                viewModel.loadMoreInfinite()
            }
        }
    }
}

