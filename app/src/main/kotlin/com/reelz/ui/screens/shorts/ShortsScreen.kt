package com.reelz.ui.screens.shorts

// Ad imports added for native ad injection

import android.view.ViewGroup
import androidx.annotation.OptIn
import com.reelz.ads.AdEngine
import com.reelz.ads.ShortsNativeAdPage
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
import com.reelz.data.repository.MediaRepository
import com.reelz.data.model.ShortVideo
import com.reelz.remoteconfig.RemoteConfigRepository
import com.reelz.remoteconfig.ShortCategory
import com.reelz.scanner.StreamHeaders
import com.reelz.ui.components.CinematicSpinner
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Data model
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// Feed mode
// ─────────────────────────────────────────────────────────────────────────────

enum class FeedMode { FOR_YOU, DISCOVERY }

// ─────────────────────────────────────────────────────────────────────────────
// Discovery categories and For You subs are driven entirely by remote config.
// ─────────────────────────────────────────────────────────────────────────────

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
// ViewModel
// FIX 1: Base feed URL comes from remote config (shorts.feed_base_url)
// FIX 2: User-Agent matches a real Chrome browser — no "Reelz/1.0" suffix
// FIX 3: Surface errors instead of silently swallowing them
// ─────────────────────────────────────────────────────────────────────────────

// ── Shorts feed item — either a real video or an ad slot ─────────────────────
sealed class ShortsItem {
    data class Video(val video: ShortVideo) : ShortsItem()
    object AdSlot : ShortsItem()
}

// Helper: merge videos with ad slots every 5 videos
private fun buildShortsItemList(videos: List<ShortVideo>): List<ShortsItem> = buildList {
    videos.forEachIndexed { index, video ->
        add(ShortsItem.Video(video))
        if ((index + 1) % 5 == 0) add(ShortsItem.AdSlot)
    }
}

@HiltViewModel
class ShortsViewModel @Inject constructor(
    private val repo: MediaRepository,
    private val remoteConfig: RemoteConfigRepository,
) : ViewModel() {

    // Feed base URL and category config come entirely from remote config.
    val shortsConfig get() = remoteConfig.shortsConfig()
    private val feedBaseUrl  get() = shortsConfig.feedBaseUrl
    private val forYouSubs   get() = shortsConfig.forYouSubs
    private val categories   get() = shortsConfig.categories

    private val okHttp = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    // FIX 2: Match StreamHeaders.UA_CHROME_ANDROID exactly — no app suffix
                    .header("User-Agent", StreamHeaders.UA_CHROME_ANDROID)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
            )
        }
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class UiState(
        // shared
        val feedMode: FeedMode = FeedMode.FOR_YOU,
        val searchQuery: String = "",
        val isSearching: Boolean = false,
        // for you
        val forYouVideos: List<ShortVideo> = emptyList(),
        val forYouAfter: String? = null,
        val forYouLoading: Boolean = true,
        val forYouLoadingMore: Boolean = false,
        // discovery
        val discVideos: List<ShortVideo> = emptyList(),
        val discAfter: String? = null,
        val discLoading: Boolean = false,
        val discLoadingMore: Boolean = false,
        val selectedCategory: Int = 0,
        // categories driven by remote config
        val categories: List<ShortCategory> = emptyList(),
        // error
        val error: String? = null,
        // refresh
        val isRefreshing: Boolean = false,
    ) {
        val videos get() = if (feedMode == FeedMode.FOR_YOU) forYouVideos else discVideos
        val isLoading get() = if (feedMode == FeedMode.FOR_YOU) forYouLoading else discLoading
        val isLoadingMore get() = if (feedMode == FeedMode.FOR_YOU) forYouLoadingMore else discLoadingMore
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _liked = MutableStateFlow<Set<String>>(emptySet())
    private val _saved = MutableStateFlow<Set<String>>(emptySet())
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()
    val saved: StateFlow<Set<String>> = _saved.asStateFlow()

    init {
        // Seed categories from config (uses safe defaults if config not yet loaded)
        _ui.update { it.copy(categories = categories) }
        // Re-sync categories whenever the config updates (e.g. after first sync completes)
        viewModelScope.launch {
            remoteConfig.config.collect {
                _ui.update { s -> s.copy(categories = categories) }
            }
        }
        loadForYou()
    }

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
        if (mode == FeedMode.FOR_YOU && _ui.value.forYouVideos.isEmpty()) loadForYou()
        if (mode == FeedMode.DISCOVERY && _ui.value.discVideos.isEmpty()) loadDiscovery(_ui.value.selectedCategory)
    }

    private fun loadForYou(after: String? = null) {
        val sorts = listOf("hot", "top", "new", "rising")
        val sort  = sorts.random()
        val url = "$feedBaseUrl/r/$forYouSubs/$sort.json?limit=50${if (after != null) "&after=$after" else ""}"
        if (after == null) _ui.update { it.copy(forYouLoading = true, error = null) }
        else _ui.update { it.copy(forYouLoadingMore = true) }
        viewModelScope.launch {
            val result = fetchFeed(url)
            val shuffled = result.first.shuffled()
            if (after == null) {
                _ui.update { it.copy(
                    forYouVideos = shuffled,
                    forYouAfter  = result.second,
                    forYouLoading = false,
                    error = if (shuffled.isEmpty()) "No videos right now — pull to refresh" else null,
                )}
            } else {
                _ui.update { it.copy(forYouVideos = it.forYouVideos + shuffled, forYouAfter = result.second, forYouLoadingMore = false) }
            }
        }
    }

    private fun loadDiscovery(categoryIndex: Int, after: String? = null) {
        val subs = categories[categoryIndex].subs
        val url  = "$feedBaseUrl/r/$subs/hot.json?limit=25${if (after != null) "&after=$after" else ""}"
        if (after == null) _ui.update { it.copy(discLoading = true, error = null) }
        else _ui.update { it.copy(discLoadingMore = true) }
        viewModelScope.launch {
            val result = fetchFeed(url)
            if (after == null) {
                _ui.update { it.copy(
                    discVideos  = result.first,
                    discAfter   = result.second,
                    discLoading = false,
                    error = if (result.first.isEmpty()) "No videos found" else null,
                )}
            } else {
                _ui.update { it.copy(discVideos = it.discVideos + result.first, discAfter = result.second, discLoadingMore = false) }
            }
        }
    }

    fun selectCategory(index: Int) {
        if (index == _ui.value.selectedCategory && _ui.value.discVideos.isNotEmpty()) {
            _ui.update { it.copy(feedMode = FeedMode.DISCOVERY) }
            return
        }
        _ui.update { it.copy(selectedCategory = index, discVideos = emptyList(), discAfter = null, feedMode = FeedMode.DISCOVERY, searchQuery = "") }
        loadDiscovery(index)
    }

    fun loadMore() {
        val state = _ui.value
        if (state.feedMode == FeedMode.FOR_YOU) {
            if (state.forYouLoadingMore || state.forYouAfter == null) return
            loadForYou(state.forYouAfter)
        } else {
            if (state.discLoadingMore || state.discAfter == null) return
            loadDiscovery(state.selectedCategory, state.discAfter)
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _ui.update { it.copy(searchQuery = "") }
            if (_ui.value.feedMode == FeedMode.FOR_YOU) loadForYou()
            else loadDiscovery(_ui.value.selectedCategory)
            return
        }
        _ui.update { it.copy(isSearching = true, searchQuery = query, error = null) }
        val extra = if (_ui.value.feedMode == FeedMode.DISCOVERY) {
            "+subreddit:${categories[_ui.value.selectedCategory].subs.replace("+", " OR subreddit:")}"
        } else ""
        val url = "$feedBaseUrl/search.json?q=${query.trim().replace(" ", "+")}$extra&type=link&sort=relevance&limit=25"
        viewModelScope.launch {
            val result = fetchFeed(url)
            val mode   = _ui.value.feedMode
            if (mode == FeedMode.FOR_YOU) {
                _ui.update { it.copy(forYouVideos = result.first.shuffled(), forYouAfter = result.second, forYouLoading = false, isSearching = false) }
            } else {
                _ui.update { it.copy(discVideos = result.first, discAfter = result.second, discLoading = false, isSearching = false) }
            }
        }
    }

    fun toggleLike(id: String) { _liked.update { if (id in it) it - id else it + id } }
    fun toggleSave(id: String) { _saved.update { if (id in it) it - id else it + id } }

    // ─────────────────────────────────────────────────────────────────────────
    // Feed fetch
    // FIX 3: Errors are now surfaced via errorMessage instead of silently dropped
    // hls_url = video-only, DASH_audio.mp4 = audio track for the merged source
    // Both are merged in ExoPlayer via MergingMediaSource in the Screen
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun fetchFeed(url: String): Pair<List<ShortVideo>, String?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val responseBody = okHttp.newCall(request).execute().use { response ->
                // FIX 3: Surface HTTP errors so the UI can tell the user what went wrong
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        429  -> "Feed rate limit hit — wait a moment and refresh"
                        403  -> "Feed request blocked — try again later"
                        else -> "Feed returned ${response.code}"
                    }
                    _ui.update { it.copy(error = reason) }
                    return@withContext Pair(emptyList<ShortVideo>(), null)
                }
                response.body?.string() ?: return@withContext Pair(emptyList<ShortVideo>(), null)
            }
            val json     = JSONObject(responseBody)
            val data     = json.getJSONObject("data")
            val after    = data.optString("after").ifBlank { null }
            val children = data.getJSONArray("children")
            val videos   = mutableListOf<ShortVideo>()
            for (i in 0 until children.length()) {
                val post = children.getJSONObject(i).getJSONObject("data")
                if (!post.optBoolean("is_video")) continue
                if (post.optBoolean("over_18")) continue
                val media = post.optJSONObject("secure_media")
                    ?: post.optJSONObject("media") ?: continue
                val rv    = media.optJSONObject("reddit_video") ?: continue
                val hls   = rv.optString("hls_url").replace("&amp;", "&").ifBlank { null } ?: continue
                val w     = rv.optInt("width", 0)
                val h     = rv.optInt("height", 0)
                if (w > 0 && h > 0 && w > h * 1.2f) continue   // skip landscape
                val postId   = post.optString("id")
                val audioUrl = "https://v.redd.it/$postId/DASH_audio.mp4"
                videos += ShortVideo(
                    id          = postId,
                    title       = post.optString("title"),
                    author      = "u/${post.optString("author")}",
                    subreddit   = "r/${post.optString("subreddit")}",
                    hlsUrl      = hls,
                    audioUrl    = if (rv.optBoolean("has_audio", true)) audioUrl else null,
                    fallbackUrl = rv.optString("fallback_url").replace("&amp;", "&"),
                    thumbnail   = post.optString("thumbnail"),
                    ups         = post.optInt("ups", 0),
                    duration    = rv.optInt("duration", 0),
                    hasAudio    = rv.optBoolean("has_audio", true),
                    width       = w,
                    height      = h,
                )
            }
            Pair(videos, after)
        } catch (e: Exception) {
            // FIX 3: Surface network exceptions
            _ui.update { it.copy(error = "Network error: ${e.message}") }
            Pair(emptyList(), null)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
//
// PRELOAD ARCHITECTURE — Dual ExoPlayer ping-pong:
//
//   exoA and exoB alternate roles as "active" and "preload" player.
//   When the user is on page N:
//     - activePlayer plays page N (full audio+video, visible)
//     - preloadPlayer silently buffers page N+1 (volume=0, not attached to View)
//   On swipe to N+1:
//     - The two players swap roles instantly — no prepare() delay
//     - The new preloadPlayer starts buffering N+2 in the background
//
//   This eliminates the buffering spinner on every swipe, which is the single
//   biggest UX gap vs TikTok / MovieBox.
//
// FIX 4: DefaultHttpDataSource sends the Referer/Origin configured in
//   shorts.feed_referer / shorts.feed_origin (remote config) — the feed's
//   audio CDN checks this header and returns 403 without it, which was why
//   merged audio was silent even when URLs were correct.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun ShortsScreen(nav: NavController, adEngine: AdEngine, vm: ShortsViewModel = hiltViewModel()) {
    val ui    by vm.ui.collectAsState()
    val liked by vm.liked.collectAsState()
    val saved by vm.saved.collectAsState()
    val ctx   = LocalContext.current

    // Pull-to-refresh overscroll
    var pullOverscrollPx   by remember { mutableStateOf(0f) }
    val maxPullPx          = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
    val pullIndicatorScale = (pullOverscrollPx / maxPullPx).coerceIn(0f, 1f)

    // ── FIX 4: Referer/Origin headers required by the feed's audio CDN ───────
    val shortsConfig = vm.shortsConfig
    val httpFactory = remember(shortsConfig) {
        DefaultHttpDataSource.Factory()
            .setUserAgent(StreamHeaders.UA_CHROME_ANDROID)          // FIX 2: real browser UA
            .setDefaultRequestProperties(mapOf(
                "Referer" to shortsConfig.feedReferer,              // FIX 4: unlocks DASH audio
                "Origin"  to shortsConfig.feedOrigin,
            ))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
    }

    // ── Dual ExoPlayer ping-pong ──────────────────────────────────────────────
    // exoA and exoB swap roles on each page change.
    // activeIdx=0 → exoA is active, exoB is preloading. activeIdx=1 → reversed.
    val exoA = remember {
        ExoPlayer.Builder(ctx).build().apply {
            repeatMode    = Player.REPEAT_MODE_ONE
            playWhenReady = true
            volume        = 1f
        }
    }
    val exoB = remember {
        ExoPlayer.Builder(ctx).build().apply {
            repeatMode    = Player.REPEAT_MODE_ONE
            playWhenReady = false   // starts as the preload player
            volume        = 0f
        }
    }
    DisposableEffect(Unit) {
        onDispose { exoA.release(); exoB.release() }
    }

    // Which of the two players is currently showing to the user
    var activeIdx by remember { mutableIntStateOf(0) }
    val activePlayer  = if (activeIdx == 0) exoA else exoB
    val preloadPlayer = if (activeIdx == 0) exoB else exoA

    var isMuted    by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    // Build ShortsItem lists (videos + ad slots every 5 videos)
    val forYouItems by remember(ui.forYouVideos) { derivedStateOf { buildShortsItemList(ui.forYouVideos) } }
    val discItems   by remember(ui.discVideos)   { derivedStateOf { buildShortsItemList(ui.discVideos) } }

    val forYouPager = rememberPagerState { forYouItems.size.coerceAtLeast(1) }
    val discPager   = rememberPagerState { discItems.size.coerceAtLeast(1) }
    val activePager = if (ui.feedMode == FeedMode.FOR_YOU) forYouPager else discPager
    val currentPage = activePager.currentPage

    // Helper to build a MergingMediaSource for video+audio
    fun buildMediaSource(video: ShortVideo): androidx.media3.exoplayer.source.MediaSource {
        val videoSource = HlsMediaSource.Factory(httpFactory)
            .createMediaSource(MediaItem.fromUri(video.hlsUrl))
        return if (video.audioUrl != null) {
            val audioSource = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(video.audioUrl))
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }
    }

    // ── Core preload effect ───────────────────────────────────────────────────
    // Runs whenever the page or video list changes.
    // 1. Loads the current page into the active player (plays immediately)
    // 2. Loads the next page into the preload player (buffers silently)
    // For preloading, extract only real video items from the active item list
    val activeItems = if (ui.feedMode == FeedMode.FOR_YOU) forYouItems else discItems
    val activeVideosOnly = remember(activeItems) {
        activeItems.filterIsInstance<ShortsItem.Video>().map { it.video }
    }

    LaunchedEffect(currentPage, ui.videos, ui.feedMode) {
        val videos = activeVideosOnly
        if (videos.isEmpty()) return@LaunchedEffect

        // Map pager page to video index (skip ad slots in pager position)
        val currentItem = activeItems.getOrNull(currentPage)
        val current = (currentItem as? ShortsItem.Video)?.video ?: return@LaunchedEffect
        // Find next video (skip ad slots)
        val nextVideo = activeItems.drop(currentPage + 1).filterIsInstance<ShortsItem.Video>().firstOrNull()?.video
        val next = nextVideo

        // Determine if we need to swap (the preload player already has next ready)
        // On first load or after a list refresh, always set active player directly.
        activePlayer.apply {
            setMediaSource(buildMediaSource(current))
            prepare()
            volume        = if (isMuted) 0f else 1f
            playWhenReady = true
            play()
        }

        // Preload next video silently — no UI surface, zero user impact
        if (next != null) {
            preloadPlayer.apply {
                setMediaSource(buildMediaSource(next))
                prepare()
                volume        = 0f
                playWhenReady = false   // buffer only, don't play yet
            }
        }
    }

    // ── Swap players on page change ───────────────────────────────────────────
    // When the user swipes, promote the preload player to active and kick off
    // the next preload immediately. This is the zero-latency swap.
    var lastPage by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentPage) {
        if (currentPage == lastPage) return@LaunchedEffect
        val videos = ui.videos
        if (videos.isEmpty()) return@LaunchedEffect

        val isForward = currentPage > lastPage
        lastPage = currentPage

        if (isForward) {
            // Promote preload → active
            activeIdx = 1 - activeIdx   // flip

            val newActive  = if (activeIdx == 0) exoA else exoB
            val newPreload = if (activeIdx == 0) exoB else exoA

            // Unmute and play the newly active player
            newActive.volume        = if (isMuted) 0f else 1f
            newActive.playWhenReady = true
            newActive.play()

            // Silence the old active — it becomes the new preload
            newPreload.pause()
            newPreload.volume = 0f

            // Queue up N+2 in the new preload slot
            val nextVideo = videos.getOrNull(currentPage + 1)
            if (nextVideo != null) {
                newPreload.setMediaSource(buildMediaSource(nextVideo))
                newPreload.prepare()
                newPreload.playWhenReady = false
            }
        }
        // Backward swipe: just re-set the active player directly (rare case)

        // Trigger more loads when approaching end of list
        if (videos.isNotEmpty() && currentPage >= videos.size - 5) vm.loadMore()
    }

    // Reset overscroll when refresh completes
    LaunchedEffect(ui.isRefreshing) {
        if (!ui.isRefreshing) pullOverscrollPx = 0f
    }

    // Sync mute state to whichever player is active
    LaunchedEffect(isMuted) {
        activePlayer.volume = if (isMuted) 0f else 1f
    }

    // ── Pull-to-refresh nested scroll connection ──────────────────────────────
    val nestedScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                val atTop = activePager.currentPage == 0
                if (atTop && available.y > 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
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

        // ── Video pager ───────────────────────────────────────────────────────
        if (ui.isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = 52.dp) }
        } else if (!ui.error.isNullOrEmpty() || ui.videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    ui.error ?: "No videos",
                    color     = White40,
                    fontSize  = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            Box(Modifier.fillMaxSize()) {

                // For You pager — always composed so state persists on tab switch
                VerticalPager(
                    state    = forYouPager,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (ui.feedMode != FeedMode.FOR_YOU) Modifier.alpha(0f) else Modifier),
                    userScrollEnabled = ui.feedMode == FeedMode.FOR_YOU,
                ) { page ->
                    when (val item = forYouItems.getOrNull(page)) {
                        is ShortsItem.AdSlot  -> ShortsNativeAdPage(adEngine = adEngine)
                        is ShortsItem.Video   -> ShortVideoPage(
                            video         = item.video,
                            activePlayer  = activePlayer,
                            isActive      = page == forYouPager.currentPage && ui.feedMode == FeedMode.FOR_YOU,
                            isMuted       = isMuted,
                            isLiked       = item.video.id in liked,
                            isSaved       = item.video.id in saved,
                            onLike        = { vm.toggleLike(item.video.id) },
                            onSave        = { vm.toggleSave(item.video.id) },
                            onMute        = { isMuted = !isMuted },
                        )
                        null -> Unit
                    }
                }

                // Discovery pager — layered on top when active
                if (ui.feedMode == FeedMode.DISCOVERY || ui.discVideos.isNotEmpty()) {
                    VerticalPager(
                        state    = discPager,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (ui.feedMode != FeedMode.DISCOVERY) Modifier.alpha(0f) else Modifier),
                        userScrollEnabled = ui.feedMode == FeedMode.DISCOVERY,
                    ) { page ->
                        when (val item = discItems.getOrNull(page)) {
                            is ShortsItem.AdSlot -> ShortsNativeAdPage(adEngine = adEngine)
                            is ShortsItem.Video  -> ShortVideoPage(
                                video         = item.video,
                                activePlayer  = activePlayer,
                                isActive      = page == discPager.currentPage && ui.feedMode == FeedMode.DISCOVERY,
                                isMuted       = isMuted,
                                isLiked       = item.video.id in liked,
                                isSaved       = item.video.id in saved,
                                onLike        = { vm.toggleLike(item.video.id) },
                                onSave        = { vm.toggleSave(item.video.id) },
                                onMute        = { isMuted = !isMuted },
                            )
                            null -> Unit
                        }
                    }
                }
            }
        }

        // ── TOP OVERLAY ───────────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 6.dp),
        ) {

            // Search bar
            AnimatedVisibility(
                visible = showSearch,
                enter   = fadeIn(tween(180)) + slideInVertically(tween(200)) { -it },
                exit    = fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment      = Alignment.CenterVertically,
                    horizontalArrangement  = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xCC000000))
                            .border(1.dp, GlassBorderMd, RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        if (searchText.isEmpty()) {
                            Text("Search videos…", color = White40, fontSize = 14.sp)
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value         = searchText,
                            onValueChange = { searchText = it },
                            textStyle     = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = { vm.search(searchText); showSearch = false }
                            ),
                        )
                    }
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(GlassMd)
                            .clickable { showSearch = false; searchText = "" },
                        Alignment.Center,
                    ) {
                        Icon(IconClose, null, tint = White, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // For You / Discovery toggle
            AnimatedVisibility(
                visible = !showSearch,
                enter   = fadeIn(tween(160)),
                exit    = fadeOut(tween(120)),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment      = Alignment.CenterVertically,
                    horizontalArrangement  = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .padding(start = 14.dp)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0x88000000))
                            .border(1.dp, GlassBorderMd, CircleShape)
                            .clickable { showSearch = true },
                        Alignment.Center,
                    ) {
                        Icon(IconSearch, null, tint = White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    FeedToggle(feedMode = ui.feedMode, onSwitch = { vm.switchMode(it) })
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(34.dp).padding(end = 14.dp))
                }
            }

            // Discovery category chips
            AnimatedVisibility(
                visible = !showSearch && ui.feedMode == FeedMode.DISCOVERY,
                enter   = fadeIn(tween(200)) + expandVertically(tween(220)),
                exit    = fadeOut(tween(150)) + shrinkVertically(tween(170)),
            ) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier              = Modifier.fillMaxWidth().padding(top = 8.dp),
                    contentPadding        = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ui.categories.size) { i ->
                        val selected = i == ui.selectedCategory
                        val chipScale by animateFloatAsState(if (selected) 1.04f else 1f, spring(0.6f, 400f), label = "cs")
                        Box(
                            Modifier
                                .scale(chipScale)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (selected) Brush.horizontalGradient(listOf(Brand, Brand2))
                                    else          Brush.horizontalGradient(listOf(Color(0xAA000000), Color(0xAA000000)))
                                )
                                .border(1.dp, if (selected) Color.Transparent else GlassBorderMd, RoundedCornerShape(20.dp))
                                .clickable { vm.selectCategory(i) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            Alignment.Center,
                        ) {
                            Text(
                                ui.categories[i].label,
                                color      = if (selected) Color.White else White60,
                                fontSize   = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        // Pull-to-refresh indicator
        val showPullIndicator = pullOverscrollPx > 4f || ui.isRefreshing
        AnimatedVisibility(
            visible  = showPullIndicator,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
            enter    = fadeIn(tween(120)) + scaleIn(tween(150), 0.6f),
            exit     = fadeOut(tween(100)) + scaleOut(tween(120), 0.6f),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .scale(if (ui.isRefreshing) 1f else pullIndicatorScale)
                    .clip(CircleShape)
                    .background(Color(0xCC000000))
                    .border(1.dp, GlassBorderMd, CircleShape),
                Alignment.Center,
            ) {
                if (ui.isRefreshing) {
                    CinematicSpinner(size = 20.dp)
                } else {
                    Icon(
                        Icons.Default.Refresh, null, tint = White,
                        modifier = Modifier.size(18.dp).graphicsLayer {
                            rotationZ = pullIndicatorScale * 180f
                        }
                    )
                }
            }
        }

        // Loading more indicator
        if (ui.isLoadingMore) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)) {
                CinematicSpinner(size = 28.dp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TikTok-style "For You | Discovery" toggle pill (unchanged)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FeedToggle(feedMode: FeedMode, onSwitch: (FeedMode) -> Unit) {
    val forYouSelected = feedMode == FeedMode.FOR_YOU
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x88000000))
            .border(1.dp, GlassBorderMd, RoundedCornerShape(50))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedTab(label = "For You",    selected = forYouSelected,  onClick = { onSwitch(FeedMode.FOR_YOU) })
        FeedTab(label = "Discovery",  selected = !forYouSelected, onClick = { onSwitch(FeedMode.DISCOVERY) })
    }
}

@Composable
private fun FeedTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg    by animateColorAsState(if (selected) White else Color.Transparent, tween(200), label = "tabBg")
    val txt   by animateColorAsState(if (selected) Color.Black else White60, tween(200), label = "tabTxt")
    val scale by animateFloatAsState(if (selected) 1f else 0.95f, spring(0.7f, 600f), label = "tabS")

    Box(
        Modifier
            .scale(scale)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                indication        = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick           = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 7.dp),
        Alignment.Center,
    ) {
        Text(
            label,
            color      = txt,
            fontSize   = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single video page
// activePlayer is passed in — ShortVideoPage never owns the player lifecycle.
// The thumbnail is always shown underneath so there's never a black flash
// during the ~0ms swap between ping-pong players.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun ShortVideoPage(
    video: ShortVideo,
    activePlayer: ExoPlayer,
    isActive: Boolean,
    isMuted: Boolean,
    isLiked: Boolean,
    isSaved: Boolean,
    onLike: () -> Unit,
    onSave: () -> Unit,
    onMute: () -> Unit,
) {
    var isBuffering by remember { mutableStateOf(true) }

    DisposableEffect(isActive, activePlayer) {
        if (!isActive) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }
        }
        activePlayer.addListener(listener)
        // Sync immediately to current state
        isBuffering = activePlayer.playbackState == Player.STATE_BUFFERING
        onDispose { activePlayer.removeListener(listener) }
    }

    Box(Modifier.fillMaxSize()) {

        // Thumbnail always underneath — prevents black flash on player swap
        AsyncImage(
            model              = video.thumbnail,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )

        // Player surface — only attached when this page is the active one
        if (isActive) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
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

        // Buffering spinner — shown while active player is still loading
        if (isBuffering && isActive) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CinematicSpinner(size = 44.dp)
            }
        }

        // Gradient vignette
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x44000000), Color.Transparent, Color.Transparent, Color(0xBB000000))
                    )
                )
        )

        // Right action column
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            TikTokAction(
                icon    = if (isLiked) IconHeartFilled else IconHeart,
                label   = formatCount(video.ups + if (isLiked) 1 else 0),
                tint    = if (isLiked) Color(0xFFFF2D55) else Color.White,
                onClick = onLike,
            )
            TikTokAction(icon = IconComment, label = formatCount(video.ups / 10), tint = Color.White, onClick = {})
            TikTokAction(
                icon    = if (isSaved) IconBookmarkFilled else IconBookmark,
                label   = if (isSaved) "Saved" else "Save",
                tint    = if (isSaved) Brand else Color.White,
                onClick = onSave,
            )
            TikTokAction(icon = IconShare, label = "Share", tint = Color.White, onClick = {})
            TikTokAction(
                icon    = if (isMuted) IconVolumeOff else IconVolumeOn,
                label   = if (isMuted) "Muted" else "Sound",
                tint    = if (isMuted) Color(0xFFFF9A00) else Color.White,
                onClick = onMute,
            )
        }

        // Bottom-left: subreddit + author + title
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 80.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brand.copy(alpha = 0.18f))
                    .border(1.dp, Brand.copy(0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(video.subreddit, color = Brand2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(video.author, color = White80, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                video.title,
                color      = White,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
                lineHeight = 20.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TikTok-style action button (unchanged)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TikTokAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale   by animateFloatAsState(if (pressed) 1.3f else 1f, spring(0.3f, 700f), label = "s")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            icon, null, tint = tint,
            modifier = Modifier
                .size(32.dp)
                .scale(scale)
                .clickable(
                    indication        = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { pressed = true; onClick() },
        )
        Text(label, color = White.copy(.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }

    LaunchedEffect(pressed) {
        if (pressed) { kotlinx.coroutines.delay(200); pressed = false }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers (unchanged)
// ─────────────────────────────────────────────────────────────────────────────

private fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000f)}M"
    n >= 1_000     -> "${"%.1f".format(n / 1_000f)}K"
    else           -> n.toString()
}
