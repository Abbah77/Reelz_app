package com.axio.reelz.ui.screens.shorts

import android.view.ViewGroup
import androidx.annotation.OptIn
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.ads.ShortsNativeAdPage
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
import com.axio.reelz.data.repository.MediaRepository
import com.axio.reelz.data.model.ShortVideo
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import com.axio.reelz.remoteconfig.ShortCategory
import com.axio.reelz.scanner.StreamHeaders
import com.axio.reelz.ui.components.CinematicSpinner
import com.axio.reelz.ui.theme.*
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
// Feed mode
// ─────────────────────────────────────────────────────────────────────────────

enum class FeedMode { FOR_YOU, DISCOVERY }

// ─────────────────────────────────────────────────────────────────────────────
// Icons
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
// Feed items (video or ad slot)
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
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class ShortsViewModel @Inject constructor(
    private val repo: MediaRepository,
    private val remoteConfig: RemoteConfigRepository,
) : ViewModel() {

    val shortsConfig     get() = remoteConfig.shortsConfig()
    private val feedBaseUrl  get() = shortsConfig.feedBaseUrl
    private val forYouSubs   get() = shortsConfig.forYouSubs
    private val categories   get() = shortsConfig.categories

    // OkHttp client for the Reddit JSON feed.
    //
    // Key fixes vs the original:
    //  • Accept: */*  — Reddit's listing API rejects "application/json" alone and
    //    returns an empty result or 406. Using */* matches what a real browser sends.
    //  • Referer / Origin set to reddit.com — old.reddit.com returns an empty
    //    listing (or 429) when these headers are absent, even for public subreddits.
    private val okHttp = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    // Reddit blocks generic OkHttp/Android UAs with 403.
                    // Using the official Reddit iOS app UA passes their bot check.
                    .header("User-Agent",      "Reddit/Version 2023.30.0/Build 624071/iOS Version 16.6")
                    .header("Accept",          "application/json, text/plain, */*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Referer",         "https://www.reddit.com/")
                    .header("Origin",          "https://www.reddit.com")
                    .header("x-reddit-loid",   "0000000000000000.0.0.0")
                    .build()
            )
        }
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ── UI state ──────────────────────────────────────────────────────────────

    data class UiState(
        val feedMode: FeedMode = FeedMode.FOR_YOU,
        val searchQuery: String = "",
        val isSearching: Boolean = false,
        val forYouVideos: List<ShortVideo> = emptyList(),
        val forYouAfter: String? = null,
        val forYouLoading: Boolean = true,
        val forYouLoadingMore: Boolean = false,
        val discVideos: List<ShortVideo> = emptyList(),
        val discAfter: String? = null,
        val discLoading: Boolean = false,
        val discLoadingMore: Boolean = false,
        val selectedCategory: Int = 0,
        val categories: List<ShortCategory> = emptyList(),
        val error: String? = null,
        val isRefreshing: Boolean = false,
        val debugLog: List<String> = emptyList(),
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

    init {
        // Defer the first load until remote config is available so feedBaseUrl
        // and forYouSubs are never blank when the request fires.
        _ui.update { it.copy(forYouLoading = true) }
        viewModelScope.launch {
            remoteConfig.config
                .filterNotNull()
                .collect { cfg ->
                    _ui.update { s -> s.copy(categories = categories) }
                    if (_ui.value.forYouVideos.isEmpty()
                        && !_ui.value.forYouLoadingMore
                        && feedBaseUrl.isNotBlank()
                        && forYouSubs.isNotBlank()
                    ) {
                        loadForYou()
                    }
                }
        }
    }

    // ── Public actions ────────────────────────────────────────────────────────

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
        _ui.update { it.copy(selectedCategory = index, discVideos = emptyList(), discAfter = null, feedMode = FeedMode.DISCOVERY, searchQuery = "") }
        loadDiscovery(index)
    }

    fun loadMore() {
        val s = _ui.value
        if (s.feedMode == FeedMode.FOR_YOU) {
            if (s.forYouLoadingMore || s.forYouAfter == null) return
            loadForYou(s.forYouAfter)
        } else {
            if (s.discLoadingMore || s.discAfter == null) return
            loadDiscovery(s.selectedCategory, s.discAfter)
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
        val url = "$feedBaseUrl/search.json?q=${query.trim().replace(" ", "+")}$extra&type=link&sort=relevance&limit=25&raw_json=1"
        viewModelScope.launch {
            val (videos, after) = fetchFeed(url)
            if (_ui.value.feedMode == FeedMode.FOR_YOU)
                _ui.update { it.copy(forYouVideos = videos.shuffled(), forYouAfter = after, forYouLoading = false, isSearching = false) }
            else
                _ui.update { it.copy(discVideos = videos, discAfter = after, discLoading = false, isSearching = false) }
        }
    }

    fun toggleLike(id: String) { _liked.update { if (id in it) it - id else it + id } }
    fun toggleSave(id: String) { _saved.update { if (id in it) it - id else it + id } }

    // ── Private loaders ───────────────────────────────────────────────────────

    private fun loadForYou(after: String? = null) {
        // Only "hot" and "new" work reliably for combined multireddits.
        // "top" needs a &t= param and "rising" is unsupported — both silently
        // return empty listings which was the root cause of the empty feed bug.
        val sort = listOf("hot", "new").random()
        val url  = "$feedBaseUrl/r/$forYouSubs/$sort.json?limit=50&raw_json=1${after?.let { "&after=$it" } ?: ""}"
        if (after == null) _ui.update { it.copy(forYouLoading = true, error = null) }
        else               _ui.update { it.copy(forYouLoadingMore = true) }
        viewModelScope.launch {
            val (videos, nextAfter) = fetchFeed(url)
            val shuffled = videos.shuffled()
            if (after == null) {
                _ui.update { it.copy(
                    forYouVideos  = shuffled,
                    forYouAfter   = nextAfter,
                    forYouLoading = false,
                    error         = if (shuffled.isEmpty()) "No videos right now — pull to refresh" else null,
                )}
            } else {
                _ui.update { it.copy(forYouVideos = it.forYouVideos + shuffled, forYouAfter = nextAfter, forYouLoadingMore = false) }
            }
        }
    }

    private fun loadDiscovery(categoryIndex: Int, after: String? = null) {
        val subs = categories[categoryIndex].subs
        val url  = "$feedBaseUrl/r/$subs/hot.json?limit=25&raw_json=1${after?.let { "&after=$it" } ?: ""}"
        if (after == null) _ui.update { it.copy(discLoading = true, error = null) }
        else               _ui.update { it.copy(discLoadingMore = true) }
        viewModelScope.launch {
            val (videos, nextAfter) = fetchFeed(url)
            if (after == null) {
                _ui.update { it.copy(
                    discVideos  = videos,
                    discAfter   = nextAfter,
                    discLoading = false,
                    error       = if (videos.isEmpty()) "No videos found" else null,
                )}
            } else {
                _ui.update { it.copy(discVideos = it.discVideos + videos, discAfter = nextAfter, discLoadingMore = false) }
            }
        }
    }

    // ── Feed fetch ────────────────────────────────────────────────────────────
    //
    // Reddit video posts: hls_url carries video-only stream; DASH_audio.mp4 is
    // the separate audio track. Both are merged in ExoPlayer (MergingMediaSource).
    //
    // No orientation filter — RESIZE_MODE_ZOOM crops any aspect ratio to fill
    // the screen, exactly like TikTok/Reels. Filtering by w/h here was the main
    // reason the feed came back empty (many 720×1280 portrait videos had their
    // dimensions reported swapped by the API).

    private fun dbg(msg: String) {
        android.util.Log.d("ShortsVM", msg)
        _ui.update { it.copy(debugLog = (it.debugLog + msg).takeLast(30)) }
    }

    private suspend fun fetchFeed(url: String): Pair<List<ShortVideo>, String?> = withContext(Dispatchers.IO) {
        try {
            dbg("→ GET $url")
            val body = okHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                dbg("HTTP ${response.code} ct=${response.header("Content-Type")}")
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        429  -> "Rate limited (429) — wait a moment and refresh"
                        403  -> "Feed blocked (403) — try again later"
                        401  -> "Unauthorised (401)"
                        else -> "HTTP ${response.code}"
                    }
                    _ui.update { it.copy(error = reason) }
                    return@withContext Pair(emptyList<ShortVideo>(), null)
                }
                val bodyStr = response.body?.string()
                val preview = bodyStr?.take(120)?.replace("\n", " ") ?: "null"
                dbg("body[${bodyStr?.length}] $preview")
                bodyStr?.takeIf { it.isNotBlank() } ?: run {
                    _ui.update { it.copy(error = "Empty response from feed") }
                    return@withContext Pair(emptyList<ShortVideo>(), null)
                }
            }

            val data     = JSONObject(body).getJSONObject("data")
            val after    = data.optString("after").ifBlank { null }
            val children = data.getJSONArray("children")
            dbg("children=${children.length()} after=$after")

            val videos   = mutableListOf<ShortVideo>()
            var skippedNotVideo = 0; var skippedNsfw = 0; var skippedNoMedia = 0; var skippedNoHls = 0

            for (i in 0 until children.length()) {
                val post = children.getJSONObject(i).getJSONObject("data")
                if (!post.optBoolean("is_video")) { skippedNotVideo++; continue }
                if (post.optBoolean("over_18"))   { skippedNsfw++;     continue }
                val rv = (post.optJSONObject("secure_media") ?: post.optJSONObject("media"))
                    ?.optJSONObject("reddit_video")
                if (rv == null) { skippedNoMedia++; continue }
                val hlsRaw = rv.optString("hls_url").replace("&amp;", "&")
                if (hlsRaw.isBlank()) { skippedNoHls++; continue }
                val hls = hlsRaw
                val postId = post.optString("id")
                videos += ShortVideo(
                    id          = postId,
                    title       = post.optString("title"),
                    author      = "u/${post.optString("author")}",
                    subreddit   = "r/${post.optString("subreddit")}",
                    hlsUrl      = hls,
                    audioUrl    = if (rv.optBoolean("has_audio", true)) "https://v.redd.it/$postId/DASH_audio.mp4" else null,
                    fallbackUrl = rv.optString("fallback_url").replace("&amp;", "&"),
                    thumbnail   = post.optString("thumbnail"),
                    ups         = post.optInt("ups", 0),
                    duration    = rv.optInt("duration", 0),
                    hasAudio    = rv.optBoolean("has_audio", true),
                    width       = rv.optInt("width", 0),
                    height      = rv.optInt("height", 0),
                )
            }
            dbg("✓ videos=${videos.size} skip: notVid=$skippedNotVideo nsfw=$skippedNsfw noMedia=$skippedNoMedia noHls=$skippedNoHls")
            Pair(videos, after)
        } catch (e: Exception) {
            dbg("✗ ${e.javaClass.simpleName}: ${e.message}")
            _ui.update { it.copy(error = "${e.javaClass.simpleName}: ${e.message}") }
            Pair(emptyList(), null)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
//
// PRELOAD ARCHITECTURE — Dual ExoPlayer ping-pong:
//   exoA and exoB swap roles on each page change.
//   activePlayer  → plays current page (full volume, attached to View)
//   preloadPlayer → silently buffers next page (volume=0, not attached)
//   On swipe: players instantly swap roles — no prepare() latency.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun ShortsScreen(nav: NavController, adEngine: AdEngine, vm: ShortsViewModel = hiltViewModel()) {
    val ui    by vm.ui.collectAsState()
    val liked by vm.liked.collectAsState()
    val saved by vm.saved.collectAsState()
    val ctx   = LocalContext.current

    var pullOverscrollPx   by remember { mutableStateOf(0f) }
    val maxPullPx          = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
    val pullIndicatorScale = (pullOverscrollPx / maxPullPx).coerceIn(0f, 1f)

    // ExoPlayer HTTP factory — Referer/Origin unlock the DASH audio CDN
    val shortsConfig = vm.shortsConfig
    val httpFactory = remember(shortsConfig) {
        DefaultHttpDataSource.Factory()
            .setUserAgent(StreamHeaders.UA_CHROME_ANDROID)
            .setDefaultRequestProperties(mapOf(
                "Referer" to shortsConfig.feedReferer,
                "Origin"  to shortsConfig.feedOrigin,
            ))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
    }

    // Dual ping-pong players
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
            playWhenReady = false
            volume        = 0f
        }
    }
    DisposableEffect(Unit) { onDispose { exoA.release(); exoB.release() } }

    var activeIdx by remember { mutableIntStateOf(0) }
    val activePlayer  = if (activeIdx == 0) exoA else exoB
    val preloadPlayer = if (activeIdx == 0) exoB else exoA

    var isMuted    by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    val forYouItems by remember(ui.forYouVideos) { derivedStateOf { buildShortsItemList(ui.forYouVideos) } }
    val discItems   by remember(ui.discVideos)   { derivedStateOf { buildShortsItemList(ui.discVideos) } }

    val forYouPager = rememberPagerState { forYouItems.size.coerceAtLeast(1) }
    val discPager   = rememberPagerState { discItems.size.coerceAtLeast(1) }
    val activePager = if (ui.feedMode == FeedMode.FOR_YOU) forYouPager else discPager
    val currentPage = activePager.currentPage

    fun buildMediaSource(video: ShortVideo): androidx.media3.exoplayer.source.MediaSource {
        val videoSrc = HlsMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(video.hlsUrl))
        return if (video.audioUrl != null) {
            val audioSrc = ProgressiveMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(video.audioUrl))
            MergingMediaSource(videoSrc, audioSrc)
        } else videoSrc
    }

    val activeItems      = if (ui.feedMode == FeedMode.FOR_YOU) forYouItems else discItems
    val activeVideosOnly = remember(activeItems) { activeItems.filterIsInstance<ShortsItem.Video>().map { it.video } }

    // Load current page into active player; preload next page silently
    LaunchedEffect(currentPage, ui.videos, ui.feedMode) {
        if (activeVideosOnly.isEmpty()) return@LaunchedEffect
        val current = (activeItems.getOrNull(currentPage) as? ShortsItem.Video)?.video ?: return@LaunchedEffect
        val next    = activeItems.drop(currentPage + 1).filterIsInstance<ShortsItem.Video>().firstOrNull()?.video

        activePlayer.apply {
            setMediaSource(buildMediaSource(current))
            prepare()
            volume        = if (isMuted) 0f else 1f
            playWhenReady = true
            play()
        }
        if (next != null) {
            preloadPlayer.apply {
                setMediaSource(buildMediaSource(next))
                prepare()
                volume        = 0f
                playWhenReady = false
            }
        }
    }

    // On forward swipe: promote preload → active, kick off next preload
    var lastPage by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentPage) {
        if (currentPage == lastPage || ui.videos.isEmpty()) return@LaunchedEffect
        val isForward = currentPage > lastPage
        lastPage = currentPage

        if (isForward) {
            activeIdx = 1 - activeIdx
            val newActive  = if (activeIdx == 0) exoA else exoB
            val newPreload = if (activeIdx == 0) exoB else exoA

            newActive.volume        = if (isMuted) 0f else 1f
            newActive.playWhenReady = true
            newActive.play()

            newPreload.pause()
            newPreload.volume = 0f

            val nextVideo = ui.videos.getOrNull(currentPage + 1)
            if (nextVideo != null) {
                newPreload.setMediaSource(buildMediaSource(nextVideo))
                newPreload.prepare()
                newPreload.playWhenReady = false
            }
        }

        if (ui.videos.isNotEmpty() && currentPage >= ui.videos.size - 5) vm.loadMore()
    }

    LaunchedEffect(ui.isRefreshing) { if (!ui.isRefreshing) pullOverscrollPx = 0f }
    LaunchedEffect(isMuted)        { activePlayer.volume = if (isMuted) 0f else 1f }

    // Pull-to-refresh nested scroll
    val nestedScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (activePager.currentPage == 0 && available.y > 0f
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

        // ── Content area ──────────────────────────────────────────────────────
        when {
            ui.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = 52.dp) }

            !ui.error.isNullOrEmpty() || ui.videos.isEmpty() -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        ui.error ?: "No videos",
                        color     = White40,
                        fontSize  = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }

            else -> Box(Modifier.fillMaxSize()) {
                // For You pager — always composed so state survives tab switch
                VerticalPager(
                    state             = forYouPager,
                    modifier          = Modifier.fillMaxSize()
                        .then(if (ui.feedMode != FeedMode.FOR_YOU) Modifier.alpha(0f) else Modifier),
                    userScrollEnabled = ui.feedMode == FeedMode.FOR_YOU,
                ) { page ->
                    when (val item = forYouItems.getOrNull(page)) {
                        is ShortsItem.AdSlot -> ShortsNativeAdPage(adEngine = adEngine)
                        is ShortsItem.Video  -> ShortVideoPage(
                            video        = item.video,
                            activePlayer = activePlayer,
                            isActive     = page == forYouPager.currentPage && ui.feedMode == FeedMode.FOR_YOU,
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

                // Discovery pager — layered on top when active
                if (ui.feedMode == FeedMode.DISCOVERY || ui.discVideos.isNotEmpty()) {
                    VerticalPager(
                        state             = discPager,
                        modifier          = Modifier.fillMaxSize()
                            .then(if (ui.feedMode != FeedMode.DISCOVERY) Modifier.alpha(0f) else Modifier),
                        userScrollEnabled = ui.feedMode == FeedMode.DISCOVERY,
                    ) { page ->
                        when (val item = discItems.getOrNull(page)) {
                            is ShortsItem.AdSlot -> ShortsNativeAdPage(adEngine = adEngine)
                            is ShortsItem.Video  -> ShortVideoPage(
                                video        = item.video,
                                activePlayer = activePlayer,
                                isActive     = page == discPager.currentPage && ui.feedMode == FeedMode.DISCOVERY,
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
            }
        }

        // ── Top overlay ───────────────────────────────────────────────────────
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
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xCC000000))
                            .border(1.dp, GlassBorderMd, RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        if (searchText.isEmpty()) Text("Search videos…", color = White40, fontSize = 14.sp)
                        androidx.compose.foundation.text.BasicTextField(
                            value           = searchText,
                            onValueChange   = { searchText = it },
                            textStyle       = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
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
                        Modifier.size(38.dp).clip(CircleShape).background(GlassMd)
                            .clickable { showSearch = false; searchText = "" },
                        Alignment.Center,
                    ) {
                        Icon(IconClose, null, tint = White, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // For You / Discovery toggle
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
                    ) { Icon(IconSearch, null, tint = White, modifier = Modifier.size(16.dp)) }
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
                        val selected  = i == ui.selectedCategory
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
        AnimatedVisibility(
            visible  = pullOverscrollPx > 4f || ui.isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
            enter    = fadeIn(tween(120)) + scaleIn(tween(150), 0.6f),
            exit     = fadeOut(tween(100)) + scaleOut(tween(120), 0.6f),
        ) {
            Box(
                Modifier.size(36.dp)
                    .scale(if (ui.isRefreshing) 1f else pullIndicatorScale)
                    .clip(CircleShape).background(Color(0xCC000000)).border(1.dp, GlassBorderMd, CircleShape),
                Alignment.Center,
            ) {
                if (ui.isRefreshing) CinematicSpinner(size = 20.dp)
                else Icon(
                    Icons.Default.Refresh, null, tint = White,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = pullIndicatorScale * 180f },
                )
            }
        }

        if (ui.isLoadingMore) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)) {
                CinematicSpinner(size = 28.dp)
            }
        }

        // ── DEBUG OVERLAY — remove before release ─────────────────────────────
        if (ui.debugLog.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .padding(bottom = 90.dp)
                    .background(Color(0xDD000000))
                    .padding(8.dp),
                reverseLayout = true,
            ) {
                items(ui.debugLog.reversed().size) { i ->
                    Text(
                        text     = ui.debugLog.reversed()[i],
                        color    = if (ui.debugLog.reversed()[i].startsWith("✗")) Color(0xFFFF5555)
                                   else if (ui.debugLog.reversed()[i].startsWith("✓")) Color(0xFF55FF55)
                                   else Color(0xFFFFFFCC),
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
        // ── END DEBUG OVERLAY ─────────────────────────────────────────────────
    }
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
        FeedTab("For You",   feedMode == FeedMode.FOR_YOU)   { onSwitch(FeedMode.FOR_YOU) }
        FeedTab("Discovery", feedMode == FeedMode.DISCOVERY) { onSwitch(FeedMode.DISCOVERY) }
    }
}

@Composable
private fun FeedTab(label: String, selected: Boolean, onClick: () -> Unit) {
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
        Text(label, color = txt, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single video page
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
            override fun onPlaybackStateChanged(state: Int) { isBuffering = state == Player.STATE_BUFFERING }
        }
        activePlayer.addListener(listener)
        isBuffering = activePlayer.playbackState == Player.STATE_BUFFERING
        onDispose { activePlayer.removeListener(listener) }
    }

    Box(Modifier.fillMaxSize()) {
        // Thumbnail underneath — prevents black flash during player swap
        AsyncImage(
            model = video.thumbnail, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )

        // Player surface
        if (isActive) {
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
            Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = 44.dp) }
        }

        // Vignette
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x44000000), Color.Transparent, Color.Transparent, Color(0xBB000000)))
            )
        )

        // Right actions
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

        // Bottom-left: subreddit / author / title
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 80.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(Brand.copy(alpha = 0.18f))
                    .border(1.dp, Brand.copy(0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) { Text(video.subreddit, color = Brand2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            Text(video.author, color = White80, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(video.title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TikTok-style action button
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TikTokAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale   by animateFloatAsState(if (pressed) 1.3f else 1f, spring(0.3f, 700f), label = "s")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            icon, null, tint = tint,
            modifier = Modifier.size(32.dp).scale(scale).clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) { pressed = true; onClick() },
        )
        Text(label, color = White.copy(.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
