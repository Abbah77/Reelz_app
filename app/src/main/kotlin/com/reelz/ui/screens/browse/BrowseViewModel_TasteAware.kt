package com.reelz.ui.screens.browse

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  BrowseViewModel — Taste-Aware Edition
 *
 *  Changes from original:
 *  1. Injects TasteEngine — uses it to rank all feed rows
 *  2. Mood-based "Vibe Tonight" row appears at top when profile exists
 *  3. Genre sections are reordered to match user taste
 *  4. Infinite scroll is now taste-filtered (not just alternating movies/TV)
 *  5. Hero banner picks the highest-scored items, not just "Trending"
 *  6. TasteEngine.track() called for relevant UI events
 * ════════════════════════════════════════════════════════════════════════════
 *
 * PASTE THIS into BrowseScreen.kt, replacing the original BrowseViewModel.
 * The rest of BrowseScreen.kt (all Composables) stays the same.
 */

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelz.brain.TasteEngine
import com.reelz.brain.UserAction
import com.reelz.data.model.*
import com.reelz.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

sealed class FeedRow {
    data class Section(val section: HomeSection) : FeedRow()
    data class InfinitePage(val items: List<Media>, val page: Int) : FeedRow()
    data class MoodRow(val mood: String, val items: List<Media>) : FeedRow()
    object NativeAdPlacement : FeedRow()
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: MediaRepository,
    private val tasteEngine: TasteEngine,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val featured: List<Media> = emptyList(),
        val feedRows: List<FeedRow> = emptyList(),
        val genres: List<Genre> = emptyList(),
        val selectedGenreId: Int? = null,
        val genreItems: List<Media> = emptyList(),
        val genrePage: Int = 1,
        val isGenreLoading: Boolean = false,
        val hasMoreGenrePages: Boolean = true,
        val continueWatching: List<WatchHistory> = emptyList(),
        val isLoadingMore: Boolean = false,
        val isCacheLoaded: Boolean = false,
        val moodLabel: String? = null,  // e.g., "😱 Scary tonight"
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var infinitePage = 1
    private var isInfiniteExhausted = false
    private var categorySectionsEmitted = false

    init {
        load(forceRefresh = false)

        viewModelScope.launch {
            repo.getHistory().collect { h ->
                _ui.update { it.copy(continueWatching = h) }
            }
        }

        // React to taste profile changes (e.g., after onboarding completes)
        viewModelScope.launch {
            tasteEngine.profile.drop(1).collect { // drop(1) = skip initial value
                // Re-sort existing feed if profile changes significantly
                _ui.update { state ->
                    val reranked = rerankFeed(state.feedRows)
                    state.copy(feedRows = reranked)
                }
            }
        }
    }

    fun load(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            if (forceRefresh) _ui.update { it.copy(isRefreshing = true, error = null) }
            else _ui.update { it.copy(isLoading = true, error = null) }

            infinitePage = 1
            isInfiniteExhausted = false
            categorySectionsEmitted = false

            try {
                val sections = repo.getHomeSections(forceRefresh)
                val genres   = runCatching { repo.getMovieGenres() }.getOrElse { emptyList() }

                // ── Taste-aware section reordering ────────────────────────────
                // Sort sections so the genres user loves come first.
                // "Continue Watching" is always first (handled in UI layer).
                val rankedSections = rankSections(sections)

                // ── Mood row (only when profile has enough data) ───────────────
                val tasteCard = tasteEngine.getTasteCard()
                val moodRow: FeedRow.MoodRow? = if (
                    tasteCard.isOnboarded && tasteCard.totalWatched >= 5 && tasteCard.dominantMood != null
                ) {
                    // Find items matching the mood's genre from the available sections
                    val moodGenreKey = moodKeyFromLabel(tasteCard.dominantMood)
                    val moodItems = sections.flatMap { it.items }
                        .filter { media ->
                            val genreMap = if (media.mediaType == MediaType.TV)
                                com.reelz.brain.TmdbGenreMap.tvGenres
                            else com.reelz.brain.TmdbGenreMap.movieGenres
                            val keys = media.genreIds.mapNotNull { genreMap[it] }
                            moodGenreKey in keys
                        }
                        .take(20)
                        .let { tasteEngine.rankMedia(it) }

                    if (moodItems.isNotEmpty())
                        FeedRow.MoodRow(tasteCard.dominantMood, moodItems)
                    else null
                } else null

                // ── Hero banner: taste-ranked top items ───────────────────────
                val allItems = sections.flatMap { it.items }
                val featured = tasteEngine.rankMedia(allItems).take(6)
                    .ifEmpty { sections.firstOrNull()?.items?.take(6) ?: emptyList() }

                // ── Build feed rows with taste-sorted sections ─────────────────
                val sectionRows = rankedSections.map { FeedRow.Section(it) }
                val feedRows = buildList {
                    // Mood row first (when available)
                    moodRow?.let { add(it) }

                    // Then section rows with ad injection
                    sectionRows.forEachIndexed { index, row ->
                        add(row)
                        if ((index + 1) % 3 == 0) add(FeedRow.NativeAdPlacement)
                    }
                }

                _ui.update {
                    it.copy(
                        isLoading    = false,
                        isRefreshing = false,
                        feedRows     = feedRows,
                        featured     = featured,
                        genres       = genres,
                        isCacheLoaded = true,
                        moodLabel    = tasteCard.dominantMood,
                    )
                }
                categorySectionsEmitted = true

            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, isRefreshing = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    // ── Rank sections by user taste ───────────────────────────────────────────
    private fun rankSections(sections: List<HomeSection>): List<HomeSection> {
        val profile = tasteEngine.profile.value
        if (profile.totalInteractions < 5) return sections // Not enough data yet

        // Score each section by averaging the taste scores of its items
        return sections.sortedByDescending { section ->
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            section.items.take(5).map { media ->
                profile.scoreMedia(
                    genreIds         = media.genreIds,
                    originalLanguage = media.originalLanguage,
                    mediaType        = media.mediaType.name,
                    isAnime          = com.reelz.brain.TmdbGenreMap.isAnime(media.originalLanguage, media.genreIds),
                    currentHour      = hour,
                    voteAverage      = media.voteAverage,
                    popularity       = media.popularity,
                )
            }.average()
        }.map { section ->
            // Also re-rank items within each section
            section.copy(items = tasteEngine.rankMedia(section.items))
        }
    }

    // ── Re-rank existing feed without re-fetching ─────────────────────────────
    private fun rerankFeed(rows: List<FeedRow>): List<FeedRow> {
        return rows.map { row ->
            when (row) {
                is FeedRow.Section -> row.copy(
                    section = row.section.copy(items = tasteEngine.rankMedia(row.section.items))
                )
                is FeedRow.InfinitePage -> row.copy(items = tasteEngine.rankMedia(row.items))
                is FeedRow.MoodRow -> row.copy(items = tasteEngine.rankMedia(row.items))
                else -> row
            }
        }
    }

    // ── Infinite scroll — taste-biased discovery ──────────────────────────────
    fun loadMoreInfinite() {
        if (_ui.value.isLoadingMore || isInfiniteExhausted) return
        viewModelScope.launch {
            _ui.update { it.copy(isLoadingMore = true) }
            try {
                val nextPage = infinitePage + 1
                val profile = tasteEngine.profile.value

                // Pick the media type based on what user tends to watch more
                val movieLangScore = profile.genres.values.sumOf { it.effectiveScore.toDouble() }
                val tvScore = profile.genres["anime"]?.effectiveScore?.toDouble() ?: 0.0

                val items: List<Media> = when {
                    // If user loves anime, inject anime pages
                    tvScore > 30.0 && nextPage % 3 == 0 -> repo.getAnime(nextPage)
                    nextPage % 2 == 0 -> repo.discoverMovies(genreId = null, page = nextPage)
                    else -> repo.discoverTv(genreId = null, page = nextPage)
                }

                if (items.isEmpty()) {
                    isInfiniteExhausted = true
                    _ui.update { it.copy(isLoadingMore = false) }
                    return@launch
                }

                infinitePage = nextPage
                // Rank the page before inserting
                val ranked = tasteEngine.rankMedia(items)
                val newRow = FeedRow.InfinitePage(ranked, nextPage)
                _ui.update { st -> st.copy(feedRows = st.feedRows + newRow, isLoadingMore = false) }

            } catch (_: Exception) {
                _ui.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun selectGenre(genreId: Int?) {
        val current = _ui.value.selectedGenreId
        if (genreId == current) {
            _ui.update { it.copy(selectedGenreId = null, genreItems = emptyList(), genrePage = 1, hasMoreGenrePages = true) }
            return
        }
        _ui.update { it.copy(selectedGenreId = genreId, genreItems = emptyList(), genrePage = 1, hasMoreGenrePages = true, isGenreLoading = true) }
        viewModelScope.launch {
            try {
                val items = tasteEngine.rankMedia(repo.discoverMovies(genreId, page = 1))
                _ui.update { it.copy(genreItems = items, isGenreLoading = false) }
            } catch (_: Exception) { _ui.update { it.copy(isGenreLoading = false) } }
        }
    }

    fun loadMoreGenre() {
        val st = _ui.value
        if (st.isGenreLoading || !st.hasMoreGenrePages) return
        viewModelScope.launch {
            _ui.update { it.copy(isGenreLoading = true) }
            try {
                val nextPage = st.genrePage + 1
                val items = tasteEngine.rankMedia(repo.discoverMovies(st.selectedGenreId, page = nextPage))
                _ui.update {
                    it.copy(
                        genreItems        = it.genreItems + items,
                        genrePage         = nextPage,
                        hasMoreGenrePages = items.isNotEmpty(),
                        isGenreLoading    = false,
                    )
                }
            } catch (_: Exception) { _ui.update { it.copy(isGenreLoading = false) } }
        }
    }

    // ── Track user actions from Browse screen ─────────────────────────────────
    fun onMediaTapped(media: Media) {
        tasteEngine.track(media, UserAction.VIEW_DETAIL)
    }

    fun onMediaLiked(media: Media) {
        tasteEngine.track(media, UserAction.LIKE)
    }

    fun onMediaSaved(media: Media) {
        tasteEngine.track(media, UserAction.SAVE_WATCHLIST)
    }

    fun onMediaRemovedFromWatchlist(media: Media) {
        tasteEngine.track(media, UserAction.REMOVE_WATCHLIST)
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun moodKeyFromLabel(label: String) = when {
        label.contains("horror", ignoreCase = true) || label.contains("Scary") -> "horror"
        label.contains("laugh") || label.contains("Comedy") -> "comedy"
        label.contains("badass") || label.contains("Action") -> "action"
        label.contains("Romantic") || label.contains("Romance") -> "romance"
        label.contains("edge") || label.contains("Thriller") -> "thriller"
        label.contains("Mind") || label.contains("Sci") -> "scifi"
        label.contains("Emotional") || label.contains("Drama") -> "drama"
        label.contains("Anime") -> "anime"
        label.contains("Crime") -> "crime"
        else -> "drama"
    }
}

// Extension to allow copying HomeSection (it's a data class so this is automatic)
// Just ensuring the import exists in original Models.kt:
// data class HomeSection(val title: String, val items: List<Media>)
