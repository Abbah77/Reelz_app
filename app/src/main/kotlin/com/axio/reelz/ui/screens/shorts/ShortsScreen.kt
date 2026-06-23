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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject

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
// ViewModel  —  ALL scraping now happens server-side via GET /shorts/feed
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class ShortsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: MediaRepository,
    private val remoteConfig: RemoteConfigRepository,
) : ViewModel() {

    val shortsConfig       get() = remoteConfig.shortsConfig()
    private val categories get() = shortsConfig.categories

    // Backend base URL comes from config (e.g. "https://tt-b577.onrender.com")
    private val backendUrl get() = remoteConfig.backendConfig().backendUrl.trimEnd('/')

    // For-You community slugs — "+" separated from config
    private val forYouSubs get() = shortsConfig.forYouSubs.trim()

    // ── OkHttp — used to call OUR backend, not ifunny directly ───────────────
    private val okHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // ── UI state ──────────────────────────────────────────────────────────────

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

    // ── Private loaders ───────────────────────────────────────────────────────

    private fun loadForYou(append: Boolean = false) {
        if (append) _ui.update { it.copy(forYouLoadingMore = true) }
        else        _ui.update { it.copy(forYouLoading = true, error = null) }

        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) {
                fetchFromBackend(subs = forYouSubs)
            }
            dbg("✓ forYou total=${videos.size}")
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
            val videos = withContext(Dispatchers.IO) {
                fetchFromBackend(subs = slugString)
            }
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

    // ── Core: call backend GET /shorts/feed ───────────────────────────────────
    //
    // The backend scrapes ifunny.club server-side and returns a JSON array
    // of ShortVideo-compatible objects. The app no longer needs WebView or
    // any direct connection to ifunny.club.
    //
    // URL: GET {backendUrl}/shorts/feed?subs=slug1+slug2&base_url=https://ifunny.club

    private fun fetchFromBackend(subs: String): List<ShortVideo> {
        if (backendUrl.isBlank() || subs.isBlank()) {
            dbg("✗ backend URL or subs blank — skipping")
            return emptyList()
        }

        // URL-encode the subs param ('+' must survive as a literal '+' separator
        // on the server side, so we encode space to %20 and keep '+' as-is)
        val encodedSubs = subs.trim()
            .split("+")
            .joinToString("+") { URLEncoder.encode(it.trim(), "UTF-8") }

        val feedUrl = "$backendUrl/shorts/feed?subs=$encodedSubs" +
                      "&base_url=${URLEncoder.encode(shortsConfig.feedBaseUrl.ifBlank { "https://ifunny.club" }, "UTF-8")}"

        dbg("→ backend $feedUrl")

        return try {
            val req = Request.Builder()
                .url(feedUrl)
                .get()
                .build()

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

    // ── JSON parser: backend response → List<ShortVideo> ─────────────────────

    private fun parseShortVideos(json: String): List<ShortVideo> {
        return try {
            val root   = JSONObject(json)
            val arr    = root.getJSONArray("videos")
            val result = mutableListOf<ShortVideo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val hlsUrl = o.optString("hlsUrl").trim()
                if (hlsUrl.isBlank()) continue
                result += ShortVideo(
                    id          = o.optString("id").ifBlank { "v_$i" },
                    title       = o.optString("title"),
                    author      = o.optString("author"),
                    community   = o.optString("community"),
                    hlsUrl      = hlsUrl,
                    audioUrl    = o.optString("audioUrl").ifBlank { null },
                    fallbackUrl = o.optString("fallbackUrl").ifBlank { hlsUrl },
                    thumbnail   = o.optString("thumbnail"),
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
        _ui.update { it.copy(debugLog = (it.debugLog + msg).takeLast(30)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen  (UI is IDENTICAL to original — zero changes below this line)
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

    val activeItems = if (ui.feedMode == FeedMode.FOR_YOU) forYouItems else discItems

    LaunchedEffect(currentPage, ui.videos, ui.feedMode) {
        if (ui.videos.isEmpty()) return@LaunchedEffect
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
    LaunchedEffect(isMuted) { activePlayer.volume = if (isMuted) 0f else 1f }

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

        // DEBUG OVERLAY — remove before release
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
        AsyncImage(
            model = video.thumbnail, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )

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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(Brand.copy(alpha = 0.18f))
                    .border(1.dp, Brand.copy(0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) { Text(video.community, color = Brand2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            Text(video.author, color = White80, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(video.title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TikTok action button
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
