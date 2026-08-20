package com.axio.reelz.ui.screens.shorts

import android.content.Context
import java.io.File
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.ads.ShortsNativeAdPage
import com.axio.reelz.data.model.ShortVideo
// StreamHeaders removed — using inline UA constant below
import com.axio.reelz.ui.components.CinematicSpinner
import com.axio.reelz.ui.components.IconBookmark
import com.axio.reelz.ui.components.IconBookmarkFilled
import com.axio.reelz.ui.components.IconComment
import com.axio.reelz.ui.components.IconHeart
import com.axio.reelz.ui.components.IconHeartFilled
import com.axio.reelz.ui.components.IconLock
import com.axio.reelz.ui.components.IconSearch
import com.axio.reelz.ui.components.IconVolumeOff
import com.axio.reelz.ui.components.IconVolumeOn
import com.axio.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// ═════════════════════════════════════════════════════════════════════════════
// HOW THIS SCREEN WORKS (read this before touching playback code)
// ═════════════════════════════════════════════════════════════════════════════
//
// This mirrors how TikTok / Instagram Reels actually do it — a small fixed
// pool of player engines (ExoPlayer under the hood is what Reels uses too;
// TikTok's own engine solves the identical problem the identical way), NOT
// "ping-pong" between 2 views and NOT one player per item. A `LazyColumn`
// with thousands of live ExoPlayer/IJK instances is exactly what crashes a
// feed — you must recycle a small window of decoders across an arbitrarily
// long, paginated list.
//
//   • ONE VerticalPager drives the whole feed (per tab). No more stacking
//     two pagers and toggling alpha=0 — that was the source of player
//     hand-off bugs across tab switches (the player pool would get bound
//     to a page index from the *other* pager).
//
//   • A POOL of `PLAYER_POOL_SIZE` ExoPlayer instances is recycled by
//     index, never recreated per item. Memory stays flat no matter how
//     many pages have been scraped — 3 decoders alive at any time, period.
//
//   • LOOKAHEAD: while the user is on page N, the pool keeps page N (active,
//     playing), N+1 and N+2 warm and pre-buffered. The moment the pager
//     settles on N+1, that slot is already fully prepared → instant play,
//     zero black-frame, matches the "always one ready ahead" rule that
//     makes TikTok feel instant even on a fling.
//
//   • SCROLLING BACK is just moving the pager to a lower index — Compose's
//     `beyondViewportPageCount` keeps nearby pages (and their thumbnails)
//     composed, so the frame is already there. We don't keep a dead player
//     alive for every visited page (that's what would eventually crash);
//     we re-bind a pool slot to that video and seek, which is instant
//     because direct MP4 byte-range requests resume fast and the poster
///    frame is already on screen while it does.
//
//   • PAGINATION is cursor-style "load next batch" merged into one flat
//     list (`vm.loadMore()` fires N items before the end) — not "load
//     everything", not page-by-page navigation. This is what TikTok calls
//     its feed cursor under the hood.
//
//   • CRASH SAFETY: any pool player that throws a playback error
//     auto-skips forward instead of freezing the feed on a dead video —
//     TikTok silently skips broken/removed posts rather than stalling.
//
// ═════════════════════════════════════════════════════════════════════════════

// 4 players: 1 active + 1 backward (instant scroll-back) + 2 forward
// (buffer ahead of a fast fling). Going from 3→4 trades one extra
// ExoPlayer instance (~a few MB) for headroom that actually matches how
// the pool is used — with 3 players there was no slack for fast forward
// scrolling once a backward slot was reserved, which is what caused
// "scroll back loads again" under quick swipes.
private const val PLAYER_POOL_SIZE = 4
// Forward-only lookahead the pool keeps warm (separate from the 1
// backward slot, which the pool always reserves automatically).
private const val LOOKAHEAD_PAGES = PLAYER_POOL_SIZE - 2
private const val LOAD_MORE_THRESHOLD = 5

// ─────────────────────────────────────────────────────────────────────────────
// Disk playback cache — the actual fix for "scroll back re-downloads".
//
// Media3's SimpleCache persists downloaded byte ranges to disk keyed by URL.
// Wrapping the HTTP data source in CacheDataSource.Factory means:
//   • A video played once stays on disk (LRU-evicted at CACHE_MAX_BYTES) —
//     scrolling back to it re-reads from disk instantly, no network at all,
//     matching TikTok's own "already seen this session" behavior.
//   • A partially-buffered video that gets interrupted (fast scroll away
//     mid-download) resumes from where it left off instead of restarting,
//     because the cache stores whatever byte ranges were actually written.
//   • SimpleCache MUST be a single process-wide instance — Media3 throws
//     IllegalStateException if the same cache directory is opened twice
//     concurrently, which is exactly what would happen if this were
//     `remember`'d per-composable instead of held in a singleton.
// ─────────────────────────────────────────────────────────────────────────────

private const val SHORTS_CACHE_MAX_BYTES = 300L * 1024 * 1024 // 300MB on-disk budget

private object ShortsDiskCache {
    @Volatile private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        return instance ?: synchronized(this) {
            instance ?: SimpleCache(
                File(context.cacheDir, "shorts_media_cache"),
                LeastRecentlyUsedCacheEvictor(SHORTS_CACHE_MAX_BYTES),
                StandaloneDatabaseProvider(context),
            ).also { instance = it }
        }
    }
}



enum class FeedMode { FOR_YOU, DISCOVERY }

// ─────────────────────────────────────────────────────────────────────────────
// Icons — Heart / Bookmark / Comment / Share / Volume / Search all now come
// from CommonComponents.kt (shared, higher-fidelity Bootstrap-style vectors)
// instead of the crude hand-drawn arcs that used to live here. Only IconClose
// stays local since there's no shared equivalent yet.
// ─────────────────────────────────────────────────────────────────────────────

private val IconClose: ImageVector get() = ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(18f, 6f); lineTo(6f, 18f)
        moveTo(6f, 6f); lineTo(18f, 18f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

// ─────────────────────────────────────────────────────────────────────────────
// Feed items
// ─────────────────────────────────────────────────────────────────────────────

sealed class ShortsItem {
    data class Video(val video: ShortVideo) : ShortsItem()
    object AdSlot : ShortsItem()
}

private fun buildShortsItemList(videos: List<ShortVideo>): List<ShortsItem> = buildList {
    videos.forEachIndexed { index, video ->
        add(ShortsItem.Video(video))
        if ((index + 1) % 5 == 0) add(ShortsItem.AdSlot)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel  —  videos are resolved directly from archive.org, no backend.
//
// HOW SOURCING WORKS NOW
// ───────────────────────
// shorts.json config (ShortsConfig) lists archive.org item identifiers —
// nothing else. Each identifier is a bulk-upload item that can itself
// contain dozens/hundreds of video files. Resolving one identifier means:
//
//   GET {archive_org.metadata_base_url}/{identifier}
//     → JSON with `server`, `dir`, and a `files[]` array
//   for each file that matches shorts.video_extensions and isn't excluded:
//     direct playable URL = https://{server}{dir}/{urlEncode(file.name)}
//
// That's it — no auth, no scraping, no JS execution, just one JSON fetch
// per identifier. A pool of identifiers can yield hundreds of individual
// videos from a handful of config entries.
//
// PAGINATION + RANDOMNESS
// ────────────────────────
// Per product decision: fully random shuffle on every load, infinite
// scroll. Concretely:
//   • On (re)load, the full identifier pool for the active feed is
//     shuffled, then resolved `items_per_page` identifiers at a time.
//   • Each resolved identifier's files are also individually shuffled
//     before being appended, so even a single big bulk item doesn't
//     play its videos in upload order.
//   • loadMore() advances to the next unresolved slice of the shuffled
//     identifier pool; once the pool is exhausted it reshuffles and
//     starts again (true infinite scroll — content repeats eventually,
//     but never in the same order twice).
//   • Nothing here is hardcoded: pool size, page size, extension
//     filters, and exclusion rules all come from ShortsConfig.
// ─────────────────────────────────────────────────────────────────────────────

// ── Simple category type for Shorts discovery tabs ────────────────────────────
// Schema v3: shorts are fully backend-driven. Categories are derived from
// the "source" field on ShortVideo items returned by GET /api/v1/shorts.
data class ShortCategory(val label: String, val source: String?)

@HiltViewModel
class ShortsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: com.axio.reelz.data.repository.CatalogRepository,
    private val configRepo: com.axio.reelz.data.repository.ConfigRepository,
) : ViewModel() {

    // ── Pagination state ──────────────────────────────────────────────────────
    private var nextCursor: String? = null
    private var hasMore: Boolean = true

    data class UiState(
        val feedMode: FeedMode = FeedMode.FOR_YOU,
        val searchQuery: String = "",
        val isSearching: Boolean = false,
        val forYouVideos: List<ShortVideo> = emptyList(),
        val forYouLoading: Boolean = true,
        val forYouLoadingMore: Boolean = false,
        val discVideos: List<ShortVideo> = emptyList(),
        val discLoading: Boolean = false,
        val discLoadingMore: Boolean = false,
        val selectedCategory: Int = 0,
        val categories: List<ShortCategory> = emptyList(),
        val error: String? = null,
        val isRefreshing: Boolean = false,
    ) {
        val videos        get() = if (feedMode == FeedMode.FOR_YOU) forYouVideos else discVideos
        val isLoading     get() = if (feedMode == FeedMode.FOR_YOU) forYouLoading else discLoading
        val isLoadingMore get() = if (feedMode == FeedMode.FOR_YOU) forYouLoadingMore else discLoadingMore
    }

    private val _ui  = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _liked = MutableStateFlow<Set<String>>(emptySet())
    private val _saved = MutableStateFlow<Set<String>>(emptySet())
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()
    val saved: StateFlow<Set<String>> = _saved.asStateFlow()

    // Video ids that errored during playback this session — skipped on
    // future encounters so a single dead link can never re-stall the feed.
    private val _deadIds = MutableStateFlow<Set<String>>(emptySet())
    val deadIds: StateFlow<Set<String>> = _deadIds.asStateFlow()
    fun markDead(id: String) { _deadIds.update { it + id } }

    init {
        _ui.update { it.copy(forYouLoading = true) }
        viewModelScope.launch {
            configRepo.config
                .filterNotNull()
                .collect {
                    if (_ui.value.forYouVideos.isEmpty() && !_ui.value.forYouLoadingMore) {
                        loadForYou()
                    }
                }
        }
    }

    fun logFromUi(msg: String) { android.util.Log.d("ShortsVM", msg) }

    fun refresh() {
        _ui.update { it.copy(isRefreshing = true, error = null) }
        nextCursor = null
        hasMore = true
        viewModelScope.launch {
            if (_ui.value.feedMode == FeedMode.FOR_YOU) loadForYou()
            else loadDiscovery(_ui.value.selectedCategory)
            kotlinx.coroutines.delay(400)
            _ui.update { it.copy(isRefreshing = false) }
        }
    }

    fun switchMode(mode: FeedMode) {
        if (_ui.value.feedMode == mode) return
        _ui.update { it.copy(feedMode = mode, error = null, searchQuery = "") }
        if (mode == FeedMode.FOR_YOU   && _ui.value.forYouVideos.isEmpty()) loadForYou()
        if (mode == FeedMode.DISCOVERY && _ui.value.discVideos.isEmpty())   loadDiscovery(_ui.value.selectedCategory)
    }

    fun selectCategory(index: Int) {
        if (index == _ui.value.selectedCategory && _ui.value.discVideos.isNotEmpty()) {
            _ui.update { it.copy(feedMode = FeedMode.DISCOVERY) }
            return
        }
        _ui.update { it.copy(selectedCategory = index, discVideos = emptyList(), feedMode = FeedMode.DISCOVERY, searchQuery = "") }
        loadDiscovery(index)
    }

    fun loadMore() {
        val s = _ui.value
        if (s.feedMode == FeedMode.FOR_YOU) {
            if (s.forYouLoadingMore || !hasMore) return
            loadForYou(append = true)
        } else {
            if (s.discLoadingMore) return
            loadDiscovery(s.selectedCategory, append = true)
        }
    }

    fun search(query: String) {
        _ui.update { it.copy(searchQuery = query) }
    }

    fun toggleLike(id: String) { _liked.update { if (id in it) it - id else it + id } }
    fun toggleSave(id: String) { _saved.update { if (id in it) it - id else it + id } }

    private fun loadForYou(append: Boolean = false) {
        if (append) _ui.update { it.copy(forYouLoadingMore = true) }
        else        _ui.update { it.copy(forYouLoading = true, error = null) }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repo.getShorts(cursor = if (append) nextCursor else null, limit = 10)
            }
            when (result) {
                is com.axio.reelz.core.network.NetworkResult.Success -> {
                    val (videos, cursor, more) = result.data
                    nextCursor = cursor
                    hasMore = more
                    // Derive categories from unique sources in the full list
                    val allVideos = if (append) _ui.value.forYouVideos + videos else videos
                    val cats = buildCategories(allVideos)
                    if (append) {
                        _ui.update { it.copy(forYouVideos = allVideos, forYouLoadingMore = false, categories = cats) }
                    } else {
                        _ui.update { it.copy(
                            forYouVideos  = allVideos,
                            forYouLoading = false,
                            categories    = cats,
                            error = if (allVideos.isEmpty()) "No videos right now — pull to refresh" else null,
                        )}
                    }
                }
                else -> {
                    val msg = "Couldn't load videos — pull to refresh"
                    if (append) _ui.update { it.copy(forYouLoadingMore = false, error = msg) }
                    else        _ui.update { it.copy(forYouLoading = false, error = msg) }
                }
            }
        }
    }

    private fun loadDiscovery(categoryIndex: Int, append: Boolean = false) {
        val category = _ui.value.categories.getOrNull(categoryIndex)

        if (!append) _ui.update { it.copy(discLoading = true, error = null) }
        else         _ui.update { it.copy(discLoadingMore = true) }

        viewModelScope.launch {
            // Filter the already-loaded forYou videos by the selected category source
            val filtered = if (category == null || category.source == null) {
                _ui.value.forYouVideos
            } else {
                _ui.value.forYouVideos.filter { it.source == category.source }
            }
            val label = category?.label ?: "All"
            if (!append) {
                _ui.update { it.copy(
                    discVideos  = filtered,
                    discLoading = false,
                    error       = if (filtered.isEmpty()) "No videos in \"$label\"" else null,
                )}
            } else {
                _ui.update { it.copy(discVideos = it.discVideos + filtered, discLoadingMore = false) }
            }
        }
    }

    private fun buildCategories(videos: List<ShortVideo>): List<ShortCategory> {
        val sources = videos.mapNotNull { it.source }.distinct()
        return listOf(ShortCategory("All", null)) + sources.map { src ->
            ShortCategory(src.replaceFirstChar { it.uppercaseChar() }, src)
        }
    }

    private fun dbg(msg: String) { android.util.Log.d("ShortsVM", msg) }

}

// ─────────────────────────────────────────────────────────────────────────────
// Player pool — the single source of truth for which ExoPlayer plays what.
//
// This is intentionally its own small class instead of inline state: the
// previous version managed pool assignment with raw mutableStateMaps spread
// across the composable body, which is exactly how the old code ended up
// with two pagers fighting over one pool across tab switches. Centralizing
// it here means there is exactly one place that decides "pool slot X plays
// video Y" — and tab switches / pagination can't desync it.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
private class ShortsPlayerPool(
    private val players: List<ExoPlayer>,
    private val buildMediaSource: (ShortVideo) -> androidx.media3.exoplayer.source.MediaSource,
    private val onError: (videoId: String, poolIdx: Int, msg: String) -> Unit,
) {
    // Which page index (not pool index) each slot currently holds. -1 means
    // empty/unassigned. This is the actual fix for the "previous video
    // keeps playing" and "scroll back reloads" bugs: the old version
    // assigned slots as (activeIdx + offset) % size, which only made sense
    // when offset was measured from wherever activeIdx ALREADY was — on a
    // fast scroll that math could collide with whatever slot was still
    // mid-playback, instead of cleanly retiring it. Tracking the actual
    // page index per slot means we always know exactly what's loaded where,
    // and can mute/stop everything that isn't the target page, every time.
    private val loadedPageIndex = IntArray(players.size) { -1 }
    private val loadedVideoId = arrayOfNulls<String>(players.size)

    var activeIdx by mutableIntStateOf(0)
        private set

    val activePlayer: ExoPlayer get() = players[activeIdx]

    init {
        players.forEachIndexed { idx, player ->
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val cause = error.cause?.let { c -> "${c::class.simpleName}: ${c.message}" } ?: "no cause"
                    val vid = loadedVideoId[idx] ?: "unknown"
                    onError(vid, idx, "${error.errorCodeName} cause=$cause")
                }
            })
        }
    }

    private fun slotForPage(pageIndex: Int): Int? =
        loadedPageIndex.indexOfFirst { it == pageIndex }.takeIf { it >= 0 }

    /** Picks a slot to (re)use for [pageIndex]: reuse if already loaded, else the least-recently-relevant idle slot. */
    private fun slotToUseFor(pageIndex: Int, wantedPages: Set<Int>): Int {
        slotForPage(pageIndex)?.let { return it }
        // Prefer a slot that isn't currently holding any page we still want
        // to keep warm (active, lookahead, or lookbehind) — this is what
        // lets scroll-back stay instant: we don't evict a neighbor we'll
        // likely need again in favor of one we just walked away from.
        val freeSlot = loadedPageIndex.indexOfFirst { it !in wantedPages }
        return if (freeSlot >= 0) freeSlot else activeIdx
    }

    /** Loads [video]/[pageIndex] into [poolIdx], reusing playback if already loaded there. */
    private fun ensurePrepared(poolIdx: Int, pageIndex: Int, video: ShortVideo, playWhenReady: Boolean, muted: Boolean) {
        val player = players[poolIdx]
        if (loadedVideoId[poolIdx] == video.id && loadedPageIndex[poolIdx] == pageIndex) {
            player.playWhenReady = playWhenReady
            player.volume = if (playWhenReady && !muted) 1f else 0f
            return
        }
        player.apply {
            setMediaSource(buildMediaSource(video))
            prepare()
            this.playWhenReady = playWhenReady
            volume = if (playWhenReady && !muted) 1f else 0f
        }
        loadedVideoId[poolIdx] = video.id
        loadedPageIndex[poolIdx] = pageIndex
    }

    /**
     * Keeps [centerPageIndex], 1 page backward, and up to (PLAYER_POOL_SIZE - 2)
     * pages forward warm and pre-buffered — using every slot in the pool.
     * The backward slot is what makes "scroll down then back up" instant
     * instead of reloading. [lookahead] is accepted for call-site
     * compatibility but capped to what the pool can actually hold without
     * starving the backward slot.
     */
    fun primeWindow(items: List<ShortsItem>, centerPageIndex: Int, lookahead: Int, muted: Boolean) {
        if (items.isEmpty()) return
        val maxForward = (players.size - 2).coerceAtLeast(0)
        val effectiveLookahead = lookahead.coerceAtMost(maxForward)
        val wantedRange = (centerPageIndex - 1)..(centerPageIndex + effectiveLookahead)
        val wantedPages = wantedRange.toSet()

        for (pageIndex in wantedRange) {
            val video = (items.getOrNull(pageIndex) as? ShortsItem.Video)?.video ?: continue
            val poolIdx = slotToUseFor(pageIndex, wantedPages)
            // The active slot must NEVER be touched here — this is exactly
            // what caused the "thumbnail loads but video never plays"
            // regression: activate() would start playback, then the very
            // next primeWindow() call (which always ran right after, to
            // top up the lookahead window) re-applied playWhenReady=false
            // to every slot it iterated, including the one that was just
            // started, pausing it a frame later. Buffering/lookahead
            // management for neighboring pages should never override the
            // play state of whatever's actually on screen.
            if (poolIdx == activeIdx) continue
            ensurePrepared(poolIdx, pageIndex, video, playWhenReady = false, muted = muted)
        }
    }

    /**
     * Promotes whichever pool slot holds [pageIndex]/[video] to active and
     * plays it, while unconditionally silencing every OTHER slot. This is
     * the actual fix for "I hear the previous video's sound" — we never
     * rely on "whatever activeIdx was before" being the right thing to
     * pause; every non-target player gets stopped, every single call.
     */
    fun activate(items: List<ShortsItem>, pageIndex: Int, lookahead: Int, video: ShortVideo, muted: Boolean) {
        val effectiveLookahead = lookahead.coerceAtMost((players.size - 2).coerceAtLeast(0))
        var target = slotForPage(pageIndex)
        if (target == null) {
            // Not primed yet (e.g. a very fast fling outran lookahead) —
            // grab any slot and load it now rather than leaving the old
            // video's player as the de-facto "active" one by default.
            target = slotToUseFor(pageIndex, ((pageIndex - 1)..(pageIndex + effectiveLookahead)).toSet())
            ensurePrepared(target, pageIndex, video, playWhenReady = false, muted = muted)
        }
        activeIdx = target

        players.forEachIndexed { idx, player ->
            if (idx == target) {
                player.playWhenReady = true
                player.volume = if (muted) 0f else 1f
                player.play()
            } else {
                // Unconditional — every non-target slot is paused and
                // silenced, regardless of what it was doing before.
                player.playWhenReady = false
                player.volume = 0f
            }
        }

        // Top up the window now that activeIdx has actually moved, so the
        // next primeWindow call's "keep warm" set is anchored correctly.
        primeWindow(items, pageIndex, lookahead, muted)
    }

    fun setMuted(muted: Boolean) {
        activePlayer.volume = if (muted) 0f else 1f
    }

    fun pauseActive() {
        activePlayer.playWhenReady = false
    }

    fun playerForVideoIfActive(video: ShortVideo): ExoPlayer? =
        if (loadedVideoId[activeIdx] == video.id) activePlayer else null

    fun release() {
        players.forEach { it.release() }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun rememberShortsPlayerPool(
    httpFactory: DefaultHttpDataSource.Factory,
    onError: (videoId: String, poolIdx: Int, msg: String) -> Unit,
): ShortsPlayerPool {
    val ctx = LocalContext.current
    val players = remember {
        List(PLAYER_POOL_SIZE) {
            ExoPlayer.Builder(ctx).build().apply {
                repeatMode    = Player.REPEAT_MODE_ONE
                playWhenReady = false
                volume        = 0f
            }
        }
    }
    DisposableEffect(Unit) { onDispose { players.forEach { it.release() } } }

    // Cache-backed factory: reads/writes through disk cache first, only
    // hits the network (httpFactory) for bytes not already on disk.
    val cacheDataSourceFactory = remember(httpFactory) {
        CacheDataSource.Factory()
            .setCache(ShortsDiskCache.get(ctx))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun buildMediaSource(video: ShortVideo): androidx.media3.exoplayer.source.MediaSource {
        val primaryUrl = video.url
        val isRealHls  = primaryUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        val videoSrc = if (isRealHls) {
            // HLS segments are already chunked/cached by the HLS stack itself;
            // caching wrapper is unnecessary (and unsupported for live-style
            // manifests) so this path keeps using the plain http factory.
            HlsMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(primaryUrl))
        } else {
            ProgressiveMediaSource.Factory(cacheDataSourceFactory).createMediaSource(MediaItem.fromUri(primaryUrl))
        }
        return videoSrc
    }

    return remember(players) { ShortsPlayerPool(players, ::buildMediaSource, onError) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Feed toggle pill
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FeedToggle(feedMode: FeedMode, onSwitch: (FeedMode) -> Unit) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .clip(RoundedCornerShape(d.radiusPill))
            .background(Color(0x88000000))
            .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusPill))
            .padding(d.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedTab("For You",   feedMode == FeedMode.FOR_YOU)   { onSwitch(FeedMode.FOR_YOU) }
        FeedTab("Discovery", feedMode == FeedMode.DISCOVERY) { onSwitch(FeedMode.DISCOVERY) }
    }
}

@Composable
private fun FeedTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val d = LocalDimensions.current
    val bg    by animateColorAsState(if (selected) White else Color.Transparent, tween(200), label = "tabBg")
    val txt   by animateColorAsState(if (selected) Color.Black else White60, tween(200), label = "tabTxt")
    val scale by animateFloatAsState(if (selected) 1f else 0.95f, spring(0.7f, 600f), label = "tabS")
    Box(
        Modifier.scale(scale).clip(RoundedCornerShape(d.radiusPill)).background(bg)
            .clickable(indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onClick)
            .padding(horizontal = d.spaceLg + d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
        Alignment.Center,
    ) {
        Text(label, color = txt, fontSize = d.textMd, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen — single VerticalPager, mode switch swaps the backing item list
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun ShortsScreen(nav: NavController, adEngine: AdEngine, vm: ShortsViewModel = hiltViewModel()) {
    val d = LocalDimensions.current
    val ui      by vm.ui.collectAsState()
    val liked   by vm.liked.collectAsState()
    val saved   by vm.saved.collectAsState()
    val deadIds by vm.deadIds.collectAsState()

    var pullOverscrollPx   by remember { mutableStateOf(0f) }
    val maxPullPx          = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
    val pullIndicatorScale = (pullOverscrollPx / maxPullPx).coerceIn(0f, 1f)

    
    val httpFactory = remember {
        // archive.org direct downloads need no special Referer/Origin spoofing
        // (that was an ifunny.club anti-hotlinking workaround) — just a
        // reasonable UA and generous timeouts/redirect handling.
        DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(12_000)
    }

    val pool = rememberShortsPlayerPool(httpFactory) { videoId, poolIdx, msg ->
        vm.logFromUi("✗ EXO[$poolIdx] err=$msg")
        if (videoId != "unknown") vm.markDead(videoId)
    }

    var isMuted    by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    // ── Single flat item list for whichever mode is active ──────────────────
    // Dead (errored) videos are filtered out here so a broken link can never
    // resurface in the pager and re-trigger the same failure.
    val rawItems by remember(ui.feedMode, ui.forYouVideos, ui.discVideos, deadIds) {
        derivedStateOf {
            val source = if (ui.feedMode == FeedMode.FOR_YOU) ui.forYouVideos else ui.discVideos
            buildShortsItemList(source.filter { it.id !in deadIds })
        }
    }

    // A single PagerState drives both tabs (For You / Discovery) — no more
    // stacking two VerticalPagers and hiding one with alpha=0f, which was
    // the root cause of the old "wrong video plays after switching tabs"
    // bug (the player pool would get bound to a page index from whichever
    // pager wasn't visible). pageCount is read lazily via the lambda so
    // pagination (appending more videos) never resets scroll position.
    //
    // PagerState's constructor is internal — rememberPagerState() is the
    // only public way to obtain one, and it has no "key" parameter to
    // recreate it per tab (that incorrect assumption is what broke an
    // earlier build: "No parameter with name 'key' found"). So instead we
    // keep ONE PagerState alive for the whole screen's lifetime and
    // explicitly scroll it back to page 0 whenever feedMode changes.
    val pagerState = rememberPagerState { rawItems.size.coerceAtLeast(1) }
    LaunchedEffect(ui.feedMode) {
        if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
    }

    val currentPage = pagerState.currentPage.coerceIn(0, (rawItems.size.coerceAtLeast(1)) - 1)

    // ── Priming: start buffering ahead as soon as the user commits to a
    //    swipe (any movement, not just past a threshold) — this is what
    //    makes a fast fling land on an already-buffered page instead of a
    //    spinner, same as TikTok's own pre-fetch-on-drag behavior.
    LaunchedEffect(pagerState, ui.feedMode, rawItems) {
        snapshotFlow { Triple(pagerState.currentPage, pagerState.currentPageOffsetFraction, pagerState.isScrollInProgress) }
            .distinctUntilChanged()
            .collect { (page, offsetFraction, scrolling) ->
                if (rawItems.isEmpty()) return@collect
                if (scrolling) {
                    val target = if (offsetFraction >= 0f) page + 1 else page
                    pool.primeWindow(rawItems, target, LOOKAHEAD_PAGES, isMuted)
                }
            }
    }

    // ── Settle: promote the landed page's player to active, top up the
    //    lookahead window, and trigger pagination near the end of the list.
    var lastPage by remember(ui.feedMode) { mutableIntStateOf(0) }
    LaunchedEffect(currentPage, ui.feedMode, rawItems) {
        if (rawItems.isEmpty()) return@LaunchedEffect
        val currentVideo = (rawItems.getOrNull(currentPage) as? ShortsItem.Video)?.video
        if (currentVideo != null) {
            // activate() also tops up the priming window internally, so a
            // separate primeWindow call right after is unnecessary — and
            // was actually part of the bug: calling both let the second
            // call re-evaluate "wanted pages" against a stale activeIdx
            // for one frame, which is how a fast scroll could end up
            // priming the wrong slot.
            pool.activate(rawItems, currentPage, LOOKAHEAD_PAGES, currentVideo, isMuted)
        }

        if (currentPage != lastPage) {
            lastPage = currentPage
            val totalVideos = if (ui.feedMode == FeedMode.FOR_YOU) ui.forYouVideos.size else ui.discVideos.size
            if (totalVideos > 0 && currentPage >= totalVideos - LOAD_MORE_THRESHOLD) vm.loadMore()
        }
    }

    LaunchedEffect(ui.isRefreshing) { if (!ui.isRefreshing) pullOverscrollPx = 0f }
    LaunchedEffect(isMuted) { pool.setMuted(isMuted) }

    // Pause playback entirely while the search overlay is open, matching
    // TikTok's behavior of not playing audio under a modal search sheet.
    LaunchedEffect(showSearch) { if (showSearch) pool.pauseActive() else {
        val v = (rawItems.getOrNull(currentPage) as? ShortsItem.Video)?.video
        if (v != null) pool.activate(rawItems, currentPage, LOOKAHEAD_PAGES, v, isMuted)
    }}

    val nestedScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (pagerState.currentPage == 0 && available.y > 0f
                    && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                    pullOverscrollPx = (pullOverscrollPx + available.y * 0.4f).coerceAtMost(maxPullPx)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                if (pullOverscrollPx > maxPullPx * 0.5f) vm.refresh()
                pullOverscrollPx = 0f
                return super.onPostFling(consumed, available)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .nestedScroll(nestedScroll)
    ) {
        when {
            ui.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = d.spinnerLg) }

            !ui.error.isNullOrEmpty() -> {
                // Error state: full-screen with icon, message and retry
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    ShortsErrorState(
                        message = ui.error!!,
                        onRetry = { vm.refresh() },
                    )
                }
            }

            ui.videos.isEmpty() -> {
                // Empty state: no videos configured or available
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    ShortsEmptyState(onRetry = { vm.refresh() })
                }
            }

            else -> VerticalPager(
                state                   = pagerState,
                modifier                = Modifier.fillMaxSize(),
                userScrollEnabled       = !showSearch,
                // Keeps this many pages composed off-screen so thumbnails are
                // already loaded by the time a page becomes visible — fixes
                // any "thumbnail pops in late" flash on fast scrolling.
                beyondViewportPageCount = LOOKAHEAD_PAGES,
                key                     = { idx -> (rawItems.getOrNull(idx) as? ShortsItem.Video)?.video?.id ?: "ad_$idx" },
            ) { page ->
                when (val item = rawItems.getOrNull(page)) {
                    is ShortsItem.AdSlot -> ShortsNativeAdPage(adEngine = adEngine)
                    is ShortsItem.Video  -> ShortVideoPage(
                        video        = item.video,
                        activePlayer = pool.playerForVideoIfActive(item.video),
                        isActive     = page == pagerState.currentPage,
                        isMuted      = isMuted,
                        isLiked      = item.video.id in liked,
                        isSaved      = item.video.id in saved,
                        onLike       = { vm.toggleLike(item.video.id) },
                        onSave       = { vm.toggleSave(item.video.id) },
                        onMute       = { isMuted = !isMuted },
                    )
                    null -> Unit
                }
            }
        }

        // Top overlay
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = d.spaceSm)) {
            AnimatedVisibility(
                visible = showSearch,
                enter   = fadeIn(tween(180)) + slideInVertically(tween(200)) { -it },
                exit    = fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = d.spaceLg, vertical = d.spaceSm),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xCC000000))
                            .border(1.dp, GlassBorderMd, RoundedCornerShape(24.dp))
                            .padding(horizontal = d.screenHorizPad, vertical = d.spaceMd),
                    ) {
                        if (searchText.isEmpty()) Text("Search videos…", color = White40, fontSize = d.textMd)
                        androidx.compose.foundation.text.BasicTextField(
                            value           = searchText,
                            onValueChange   = { searchText = it },
                            textStyle       = androidx.compose.ui.text.TextStyle(color = White, fontSize = d.textMd),
                            singleLine      = true,
                            modifier        = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = { vm.search(searchText); showSearch = false }
                            ),
                        )
                    }
                    Box(
                        Modifier.size(d.avatarSm + d.spaceSm).clip(CircleShape).background(GlassMd)
                            .clickable { showSearch = false; searchText = "" },
                        Alignment.Center,
                    ) {
                        Icon(IconClose, null, tint = White, modifier = Modifier.size(d.iconMd - 2.dp))
                    }
                }
            }

            AnimatedVisibility(visible = !showSearch, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.padding(start = d.screenHorizPad).size(d.avatarSm + d.spaceXs).clip(CircleShape)
                            .background(Color(0x88000000)).border(d.borderThin, GlassBorderMd, CircleShape)
                            .clickable { showSearch = true },
                        Alignment.Center,
                    ) { Icon(IconSearch, null, tint = White, modifier = Modifier.size(d.iconMd - 4.dp)) }
                    Spacer(Modifier.weight(1f))
                    FeedToggle(feedMode = ui.feedMode, onSwitch = { vm.switchMode(it) })
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(d.iconXl).padding(end = d.screenHorizPad))
                }
            }

            AnimatedVisibility(
                visible = !showSearch && ui.feedMode == FeedMode.DISCOVERY,
                enter   = fadeIn(tween(200)) + expandVertically(tween(220)),
                exit    = fadeOut(tween(150)) + shrinkVertically(tween(170)),
            ) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier              = Modifier.fillMaxWidth().padding(top = d.spaceMd - d.spaceXxs),
                    contentPadding        = PaddingValues(horizontal = d.screenHorizPad - d.spaceXxs),
                    horizontalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
                ) {
                    items(ui.categories.size) { i ->
                        val selected  = i == ui.selectedCategory
                        val chipScale by animateFloatAsState(if (selected) 1.04f else 1f, spring(0.6f, 400f), label = "cs")
                        Box(
                            Modifier
                                .scale(chipScale)
                                .clip(RoundedCornerShape(d.radiusLg))
                                .background(
                                    if (selected) Brush.horizontalGradient(listOf(Brand, Brand2))
                                    else          Brush.horizontalGradient(listOf(Color(0xAA000000), Color(0xAA000000)))
                                )
                                .border(d.borderThin, if (selected) Color.Transparent else GlassBorderMd, RoundedCornerShape(d.radiusLg))
                                .clickable { vm.selectCategory(i) }
                                .padding(horizontal = d.spaceLg - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
                            Alignment.Center,
                        ) {
                            Text(
                                ui.categories[i].label,
                                color      = if (selected) Color.White else White60,
                                fontSize = d.textSm,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        // Pull-to-refresh indicator
        AnimatedVisibility(
            visible  = pullOverscrollPx > 4f || ui.isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = d.appBarHorizPad * 4),
            enter    = fadeIn(tween(120)) + scaleIn(tween(150), 0.6f),
            exit     = fadeOut(tween(100)) + scaleOut(tween(120), 0.6f),
        ) {
            Box(
                Modifier.size(d.buttonHeightSm - d.spaceMd)
                    .scale(if (ui.isRefreshing) 1f else pullIndicatorScale)
                    .clip(CircleShape).background(Color(0xCC000000)).border(d.borderThin, GlassBorderMd, CircleShape),
                Alignment.Center,
            ) {
                if (ui.isRefreshing) CinematicSpinner(size = d.spinnerSm)
                else Icon(
                    Icons.Default.Refresh, null, tint = White,
                    modifier = Modifier.size(d.iconMd - 2.dp).graphicsLayer { rotationZ = pullIndicatorScale * 180f },
                )
            }
        }

        if (ui.isLoadingMore) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = d.spaceXxl * 3)) {
                CinematicSpinner(size = d.spinnerMd)
            }
        }

    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single video page
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun ShortVideoPage(
    video: ShortVideo,
    activePlayer: ExoPlayer?,
    isActive: Boolean,
    isMuted: Boolean,
    isLiked: Boolean,
    isSaved: Boolean,
    onLike: () -> Unit,
    onSave: () -> Unit,
    onMute: () -> Unit,
) {
    val d = LocalDimensions.current
    var isBuffering by remember { mutableStateOf(true) }

    DisposableEffect(isActive, activePlayer) {
        if (!isActive || activePlayer == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { isBuffering = state == Player.STATE_BUFFERING }
        }
        activePlayer.addListener(listener)
        isBuffering = activePlayer.playbackState == Player.STATE_BUFFERING
        onDispose { activePlayer.removeListener(listener) }
    }

    Box(Modifier.fillMaxSize()) {
        // Thumbnail/poster frame is always composed (kept warm by the
        // pager's beyondViewportPageCount window) so it's on screen the
        // instant a page becomes visible — the player view fades in only
        // once it's actually playing, never leaving a black gap between.
        AsyncImage(
            model = video.thumbnail, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )

        if (isActive && activePlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        // ZOOM crops to fill — matches TikTok's fill-the-frame
                        // behavior for vertical video regardless of the
                        // source clip's exact aspect ratio.
                        resizeMode    = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        player        = activePlayer
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    }
                },
                update   = { it.player = activePlayer },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (isBuffering && isActive) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = d.spinnerLg) }
        }

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x44000000), Color.Transparent, Color.Transparent, Color(0xBB000000)))
            )
        )

        // Right action rail — sized and positioned entirely from
        // LocalDimensions (screen-width-derived tokens), never fixed dp, so
        // it lands in the same relative spot on a compact phone, a large
        // phone, or a tablet.
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = d.screenHorizPad, bottom = d.spaceXxl * 3),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceXl - d.spaceXxs),
        ) {
            TikTokAction(
                icon     = if (isLiked) IconHeartFilled else IconHeart,
                tint     = if (isLiked) Color(0xFFFF2D55) else Color.White,
                locked   = true,
                onClick  = onLike,
            )
            TikTokAction(
                icon    = IconComment,
                tint    = Color.White,
                locked  = true,
                onClick = {},
            )
            TikTokAction(
                icon    = if (isSaved) IconBookmarkFilled else IconBookmark,
                tint    = if (isSaved) Color(0xFF0A84FF) else Color.White,
                locked  = true,
                onClick = onSave,
            )
            TikTokAction(
                icon    = if (isMuted) IconVolumeOff else IconVolumeOn,
                tint    = if (isMuted) Color(0xFFFF9A00) else Color.White,
                locked  = false,
                onClick = onMute,
            )
        }

        // Bottom-left label — just a static "TikTok" source badge, no raw
        // id/filename, no avatar placeholder, no caption text pulled from
        // archive.org filenames (those are frequently just hashtag soup and
        // don't read as a real caption).
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = d.screenHorizPad, end = d.avatarLg + d.spaceLg, bottom = d.spaceXxl * 3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "TikTok",
                color      = White,
                fontSize   = d.textMd,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TikTok-style action button — icon + optional small lock badge to mark an
// action as unavailable (no comment/like/save backend yet) without hiding
// the button entirely, matching how TikTok itself greys out actions that
// require sign-in rather than removing them from the rail.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TikTokAction(icon: ImageVector, tint: Color, locked: Boolean, onClick: () -> Unit) {
    val d = LocalDimensions.current
    var pressed by remember { mutableStateOf(false) }
    val scale   by animateFloatAsState(if (pressed) 1.2f else 1f, spring(0.4f, 700f), label = "s")

    Box(
        modifier = Modifier.size(d.avatarSm + d.spaceMd),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint     = if (locked) tint.copy(alpha = 0.55f) else tint,
            modifier = Modifier
                .size(d.avatarSm)
                .scale(scale)
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { pressed = true; onClick() },
        )
        if (locked) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(d.iconXs + d.spaceXxs)
                    .clip(CircleShape)
                    .background(Color(0xCC1C1C1E))
                    .border(d.borderThin, Color.White.copy(alpha = 0.25f), CircleShape),
                Alignment.Center,
            ) {
                Icon(IconLock, null, tint = White.copy(alpha = 0.85f), modifier = Modifier.size(d.iconXs - 1.dp))
            }
        }
    }
    LaunchedEffect(pressed) { if (pressed) { kotlinx.coroutines.delay(200); pressed = false } }
}

// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// Shorts — rich error state with icon, message, and retry
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ShortsErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    val d = LocalDimensions.current
    val isNetworkError = message.contains("internet", true) ||
        message.contains("network", true) ||
        message.contains("connect", true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = d.spaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(d.avatarLg + d.spaceLg)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Error.copy(.18f), Color.Transparent)))
                    .border(d.borderThin, Error.copy(.4f), CircleShape)
            )
            Icon(
                if (isNetworkError) IconVolumeOff else IconSearch,
                contentDescription = null,
                tint = Error.copy(.85f),
                modifier = Modifier.size(d.iconXl),
            )
        }
        Text(
            if (isNetworkError) "No Connection" else "Couldn't load videos",
            color = White,
            fontSize = d.textXl,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            com.axio.reelz.ui.components.friendlyError(message),
            color = White40,
            fontSize = d.textMd,
            textAlign = TextAlign.Center,
            lineHeight = (d.textMd.value * 1.6f).sp,
        )
        Spacer(Modifier.height(d.spaceXs))
        com.axio.reelz.ui.components.BrandButton("Try Again", onClick = onRetry)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shorts — empty state (no videos configured / all filtered out)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ShortsEmptyState(onRetry: () -> Unit) {
    val d = LocalDimensions.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = d.spaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(d.avatarLg + d.spaceLg)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(White.copy(.06f), Color.Transparent)))
                    .border(d.borderThin, GlassBorderMd, CircleShape)
            )
            Icon(
                com.axio.reelz.ui.components.IconReel,
                contentDescription = null,
                tint = White20,
                modifier = Modifier.size(d.iconXl),
            )
        }
        Text(
            "No videos right now",
            color = White60,
            fontSize = d.textXl,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            "Pull down to refresh or check back later.",
            color = White40,
            fontSize = d.textMd,
            textAlign = TextAlign.Center,
            lineHeight = (d.textMd.value * 1.6f).sp,
        )
        Spacer(Modifier.height(d.spaceXs))
        com.axio.reelz.ui.components.GhostButton("Refresh", onClick = onRetry)
    }
}
