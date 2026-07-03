package com.axio.reelz.ui.screens.shorts

import android.content.Context
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
import androidx.media3.datasource.DefaultHttpDataSource
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
import com.axio.reelz.data.repository.MediaRepository
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import com.axio.reelz.remoteconfig.ShortCategory
import com.axio.reelz.scanner.StreamHeaders
import com.axio.reelz.ui.components.CinematicSpinner
import com.axio.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
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
// Feed mode
// ─────────────────────────────────────────────────────────────────────────────

enum class FeedMode { FOR_YOU, DISCOVERY }

// ─────────────────────────────────────────────────────────────────────────────
// Icons (unchanged)
// ─────────────────────────────────────────────────────────────────────────────

private val IconHeart: ImageVector get() = ImageVector.Builder("Heart", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(20.84f, 4.61f)
        arcTo(5.5f, 5.5f, 0f, false, false, 12f, 8.5f)
        arcTo(5.5f, 5.5f, 0f, false, false, 3.16f, 4.61f)
        arcTo(5.5f, 5.5f, 0f, false, false, 12f, 20f)
        arcTo(5.5f, 5.5f, 0f, false, false, 20.84f, 4.61f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, fill = SolidColor(Color.Transparent))
}.build()

private val IconHeartFilled: ImageVector get() = ImageVector.Builder("HeartFilled", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(20.84f, 4.61f)
        arcTo(5.5f, 5.5f, 0f, false, false, 12f, 8.5f)
        arcTo(5.5f, 5.5f, 0f, false, false, 3.16f, 4.61f)
        arcTo(5.5f, 5.5f, 0f, false, false, 12f, 20f)
        arcTo(5.5f, 5.5f, 0f, false, false, 20.84f, 4.61f); close()
    }, fill = SolidColor(Color(0xFFFF2D55)))
}.build()

private val IconBookmark: ImageVector get() = ImageVector.Builder("Bookmark", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(12f, 16f); lineTo(5f, 21f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, fill = SolidColor(Color.Transparent))
}.build()

private val IconBookmarkFilled: ImageVector get() = ImageVector.Builder("BookmarkFilled", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(12f, 16f); lineTo(5f, 21f); close()
    }, fill = SolidColor(Color(0xFF0A84FF)))
}.build()

private val IconShare: ImageVector get() = ImageVector.Builder("Share", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(13f, 5f); lineTo(20f, 12f); lineTo(13f, 19f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconComment: ImageVector get() = ImageVector.Builder("Comment", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(21f, 15f)
        arcTo(2f, 2f, 0f, false, true, 19f, 17f); lineTo(7f, 17f)
        lineTo(3f, 21f); lineTo(3f, 5f)
        arcTo(2f, 2f, 0f, false, true, 5f, 3f); lineTo(19f, 3f)
        arcTo(2f, 2f, 0f, false, true, 21f, 5f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconVolumeOff: ImageVector get() = ImageVector.Builder("VolOff", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(11f, 5f); lineTo(6f, 9f); lineTo(2f, 9f)
        lineTo(2f, 15f); lineTo(6f, 15f); lineTo(11f, 19f); close()
        moveTo(23f, 9f); lineTo(17f, 15f)
        moveTo(17f, 9f); lineTo(23f, 15f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
       strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconVolumeOn: ImageVector get() = ImageVector.Builder("VolOn", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(11f, 5f); lineTo(6f, 9f); lineTo(2f, 9f)
        lineTo(2f, 15f); lineTo(6f, 15f); lineTo(11f, 19f); close()
        moveTo(15.54f, 8.46f); arcTo(5f, 5f, 0f, false, true, 15.54f, 15.54f)
        moveTo(19.07f, 4.93f); arcTo(10f, 10f, 0f, false, true, 19.07f, 19.07f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
       strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconSearch: ImageVector get() = ImageVector.Builder("Search", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(11f, 11f); arcTo(7f, 7f, 0f, false, true, 4f, 11f); arcTo(7f, 7f, 0f, false, true, 11f, 4f)
        arcTo(7f, 7f, 0f, false, true, 18f, 11f); arcTo(7f, 7f, 0f, false, true, 11f, 11f)
        moveTo(16f, 16f); lineTo(20f, 20f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

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
// ViewModel  —  ALL scraping happens server-side via GET /shorts/feed
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class ShortsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: MediaRepository,
    private val remoteConfig: RemoteConfigRepository,
) : ViewModel() {

    val shortsConfig       get() = remoteConfig.shortsConfig()
    private val categories get() = shortsConfig.categories
    // normalizedUrl auto-adds "https://" if config.json's backend_url is
    // ever saved without a scheme — see BackendConfig.normalizedUrl for why.
    private val backendUrl get() = remoteConfig.backendConfig().normalizedUrl
    private val forYouSubs get() = shortsConfig.forYouSubs.trim()

    private val okHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

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
            remoteConfig.config
                .filterNotNull()
                .collect {
                    _ui.update { s -> s.copy(categories = categories) }
                    if (_ui.value.forYouVideos.isEmpty() && !_ui.value.forYouLoadingMore) {
                        loadForYou()
                    }
                }
        }
    }

    fun logFromUi(msg: String) { android.util.Log.d("ShortsVM", msg) }

    fun refresh() {
        _ui.update { it.copy(isRefreshing = true, error = null) }
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
            if (s.forYouLoadingMore) return
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
            val videos = withContext(Dispatchers.IO) { fetchFromBackend(subs = forYouSubs) }
            dbg("forYou total=${videos.size}")
            if (append) {
                _ui.update { it.copy(forYouVideos = it.forYouVideos + videos, forYouLoadingMore = false) }
            } else {
                _ui.update { it.copy(
                    forYouVideos  = videos,
                    forYouLoading = false,
                    error = if (videos.isEmpty()) "No videos right now — pull to refresh" else null,
                )}
            }
        }
    }

    private fun loadDiscovery(categoryIndex: Int, append: Boolean = false) {
        val slugString = categories.getOrNull(categoryIndex)?.subs ?: return

        if (!append) _ui.update { it.copy(discLoading = true, error = null) }
        else         _ui.update { it.copy(discLoadingMore = true) }

        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) { fetchFromBackend(subs = slugString) }
            dbg("✓ discovery cat=$categoryIndex total=${videos.size}")
            if (!append) {
                _ui.update { it.copy(
                    discVideos  = videos,
                    discLoading = false,
                    error       = if (videos.isEmpty()) "No videos found" else null,
                )}
            } else {
                _ui.update { it.copy(discVideos = it.discVideos + videos, discLoadingMore = false) }
            }
        }
    }

    private fun fetchFromBackend(subs: String): List<ShortVideo> {
        if (backendUrl.isBlank() || subs.isBlank()) {
            dbg("✗ backend URL or subs blank — skipping")
            return emptyList()
        }

        val encodedSubs = subs.trim()
            .split("+")
            .joinToString("+") { URLEncoder.encode(it.trim(), "UTF-8") }

        val feedUrl = "$backendUrl/shorts/feed?subs=$encodedSubs" +
                      "&base_url=${URLEncoder.encode(shortsConfig.feedBaseUrl.ifBlank { "https://ifunny.club" }, "UTF-8")}"

        dbg("→ backend $feedUrl")

        return try {
            val req = Request.Builder().url(feedUrl).get().build()
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    dbg("✗ backend returned ${resp.code}")
                    return emptyList()
                }
                val body = resp.body?.string() ?: return emptyList()
                parseShortVideos(body)
            }
        } catch (e: Exception) {
            dbg("✗ backend error: ${e.message}")
            emptyList()
        }
    }

    private fun JSONObject.optUrlString(key: String): String {
        val raw = optString(key).trim()
        return if (raw.isBlank() || raw.equals("null", ignoreCase = true)) "" else raw
    }

    private fun parseShortVideos(json: String): List<ShortVideo> {
        return try {
            val root   = JSONObject(json)
            val arr    = root.getJSONArray("videos")
            val result = mutableListOf<ShortVideo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val hlsUrl      = o.optUrlString("hlsUrl")
                val fallbackUrl = o.optUrlString("fallbackUrl")
                if (hlsUrl.isBlank() && fallbackUrl.isBlank()) continue
                result += ShortVideo(
                    id          = o.optString("id").ifBlank { "v_$i" },
                    title       = o.optString("title"),
                    author      = o.optString("author"),
                    community   = o.optString("community"),
                    hlsUrl      = hlsUrl.ifBlank { fallbackUrl },
                    audioUrl    = o.optUrlString("audioUrl").ifBlank { null }.takeIf { !it.isNullOrBlank() },
                    fallbackUrl = fallbackUrl.ifBlank { hlsUrl },
                    thumbnail   = o.optUrlString("thumbnail"),
                    ups         = o.optInt("ups", 0),
                    duration    = o.optInt("duration", 0),
                    hasAudio    = o.optBoolean("hasAudio", true),
                    width       = o.optInt("width", 0),
                    height      = o.optInt("height", 0),
                )
            }
            dbg("✓ parsed ${result.size} videos from backend")
            result
        } catch (e: Exception) {
            dbg("✗ parse error: ${e.message}")
            emptyList()
        }
    }

    private fun dbg(msg: String) {
        android.util.Log.d("ShortsVM", msg)
    }
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

    fun buildMediaSource(video: ShortVideo): androidx.media3.exoplayer.source.MediaSource {
        val primaryUrl = video.hlsUrl.ifBlank { video.fallbackUrl }
        val isRealHls  = primaryUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        val videoSrc = if (isRealHls) {
            HlsMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(primaryUrl))
        } else {
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(primaryUrl))
        }
        return if (video.audioUrl != null) {
            val audioSrc = ProgressiveMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(video.audioUrl))
            MergingMediaSource(videoSrc, audioSrc)
        } else videoSrc
    }

    return remember(players) { ShortsPlayerPool(players, ::buildMediaSource, onError) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Feed toggle pill
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FeedToggle(feedMode: FeedMode, onSwitch: (FeedMode) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x88000000))
            .border(1.dp, GlassBorderMd, RoundedCornerShape(50))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val d = LocalDimensions.current
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
        Modifier.scale(scale).clip(RoundedCornerShape(50)).background(bg)
            .clickable(indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
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

    val shortsConfig = vm.shortsConfig
    val httpFactory = remember(shortsConfig) {
        DefaultHttpDataSource.Factory()
            .setUserAgent(StreamHeaders.UA_CHROME_ANDROID)
            .setDefaultRequestProperties(mapOf(
                "Referer" to shortsConfig.feedReferer.ifBlank { "https://ifunny.club/" },
                "Origin"  to shortsConfig.feedOrigin.ifBlank  { "https://ifunny.club" },
            ))
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
            ui.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = 52.dp) }

            !ui.error.isNullOrEmpty() || ui.videos.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        ui.error ?: "No videos",
                        color     = White40,
                        fontSize = d.textLg,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.padding(horizontal = 32.dp),
                    )
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
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp)) {
            AnimatedVisibility(
                visible = showSearch,
                enter   = fadeIn(tween(180)) + slideInVertically(tween(200)) { -it },
                exit    = fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
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
                        Modifier.padding(start = 14.dp).size(34.dp).clip(CircleShape)
                            .background(Color(0x88000000)).border(1.dp, GlassBorderMd, CircleShape)
                            .clickable { showSearch = true },
                        Alignment.Center,
                    ) { Icon(IconSearch, null, tint = White, modifier = Modifier.size(d.iconMd - 4.dp)) }
                    Spacer(Modifier.weight(1f))
                    FeedToggle(feedMode = ui.feedMode, onSwitch = { vm.switchMode(it) })
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(d.iconXl).padding(end = 14.dp))
                }
            }

            AnimatedVisibility(
                visible = !showSearch && ui.feedMode == FeedMode.DISCOVERY,
                enter   = fadeIn(tween(200)) + expandVertically(tween(220)),
                exit    = fadeOut(tween(150)) + shrinkVertically(tween(170)),
            ) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier              = Modifier.fillMaxWidth().padding(top = 8.dp),
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
                                .border(1.dp, if (selected) Color.Transparent else GlassBorderMd, RoundedCornerShape(d.radiusLg))
                                .clickable { vm.selectCategory(i) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
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
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
            enter    = fadeIn(tween(120)) + scaleIn(tween(150), 0.6f),
            exit     = fadeOut(tween(100)) + scaleOut(tween(120), 0.6f),
        ) {
            Box(
                Modifier.size(d.buttonHeightSm - d.spaceMd)
                    .scale(if (ui.isRefreshing) 1f else pullIndicatorScale)
                    .clip(CircleShape).background(Color(0xCC000000)).border(1.dp, GlassBorderMd, CircleShape),
                Alignment.Center,
            ) {
                if (ui.isRefreshing) CinematicSpinner(size = 20.dp)
                else Icon(
                    Icons.Default.Refresh, null, tint = White,
                    modifier = Modifier.size(d.iconMd - 2.dp).graphicsLayer { rotationZ = pullIndicatorScale * 180f },
                )
            }
        }

        if (ui.isLoadingMore) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)) {
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

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            TikTokAction(
                icon  = if (isLiked) IconHeartFilled else IconHeart,
                label = formatCount(video.ups + if (isLiked) 1 else 0),
                tint  = if (isLiked) Color(0xFFFF2D55) else Color.White,
                onClick = onLike,
            )
            TikTokAction(IconComment, formatCount(video.ups / 10), Color.White) {}
            TikTokAction(
                icon  = if (isSaved) IconBookmarkFilled else IconBookmark,
                label = if (isSaved) "Saved" else "Save",
                tint  = if (isSaved) Brand else Color.White,
                onClick = onSave,
            )
            TikTokAction(IconShare, "Share", Color.White) {}
            TikTokAction(
                icon  = if (isMuted) IconVolumeOff else IconVolumeOn,
                label = if (isMuted) "Muted" else "Sound",
                tint  = if (isMuted) Color(0xFFFF9A00) else Color.White,
                onClick = onMute,
            )
        }

        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 80.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                    .background(Brand.copy(alpha = 0.18f))
                    .border(1.dp, Brand.copy(0.35f), RoundedCornerShape(d.radiusMd - d.spaceXxs))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) { Text(video.community, color = Brand2, fontSize = d.textXs, fontWeight = FontWeight.SemiBold) }
            Text(video.author, color = White80, fontSize = d.textMd, fontWeight = FontWeight.SemiBold)
            Text(video.title, color = White, fontSize = d.textMd, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (d.textMd.value * 1.5f).sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TikTok action button
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TikTokAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val d = LocalDimensions.current
    var pressed by remember { mutableStateOf(false) }
    val scale   by animateFloatAsState(if (pressed) 1.3f else 1f, spring(0.3f, 700f), label = "s")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceXs)) {
        Icon(
            icon, null, tint = tint,
            modifier = Modifier.size(d.avatarSm).scale(scale).clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) { pressed = true; onClick() },
        )
        Text(label, color = White.copy(.85f), fontSize = d.textXs, fontWeight = FontWeight.Medium)
    }
    LaunchedEffect(pressed) { if (pressed) { kotlinx.coroutines.delay(200); pressed = false } }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000f)}M"
    n >= 1_000     -> "${"%.1f".format(n / 1_000f)}K"
    else           -> n.toString()
}
