package com.axio.reelz.ui.screens.shorts

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.ads.ShortsNativeAdPage
import com.axio.reelz.data.model.ShortVideo
import com.axio.reelz.ui.components.CinematicSpinner
import com.axio.reelz.ui.components.IconVolumeOff
import com.axio.reelz.ui.components.IconVolumeOn
import com.axio.reelz.ui.components.IconSearch
import com.axio.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

// 4 players: 1 active + 1 backward + 2 forward lookahead
private const val PLAYER_POOL_SIZE    = 4
private const val LOOKAHEAD_PAGES     = PLAYER_POOL_SIZE - 2   // = 2
private const val LOAD_MORE_THRESHOLD = 5

// How long to wait before showing the buffering spinner. Any video that
// starts in < this time will NEVER show a spinner — eliminates the "buffering
// forever" feel for fast connections.
private const val BUFFER_SPINNER_DELAY_MS = 800L

// 300 MB disk cache — scrolling back to a seen video is instant, zero network.
private const val SHORTS_CACHE_MAX_BYTES = 150L * 1024 * 1024  // 150 MB — large enough for smooth scrolling, small enough to avoid forever-stale content

// ─────────────────────────────────────────────────────────────────────────────
// Disk cache singleton (must be process-wide — Media3 throws if opened twice)
// ─────────────────────────────────────────────────────────────────────────────

private object ShortsDiskCache {
    @Volatile private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: build(context).also { instance = it }
    }

    private fun build(context: Context): SimpleCache {
        val dir = File(context.cacheDir, "shorts_media_cache")
        return SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(SHORTS_CACHE_MAX_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }

    /** Call on app foreground to release stale instance if cache dir was cleared. */
    fun releaseIfStale() {
        synchronized(this) {
            // SimpleCache doesn't expose isClosed; just release and let get() rebuild if needed.
            try { instance?.release() } catch (_: Exception) {}
            instance = null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Feed model
// ─────────────────────────────────────────────────────────────────────────────

enum class FeedMode { FOR_YOU, DISCOVERY }

data class ShortCategory(val label: String, val source: String?)

sealed class ShortsItem {
    data class Video(val video: ShortVideo) : ShortsItem()
    object AdSlot : ShortsItem()
}

private fun buildShortsItemList(videos: List<ShortVideo>): List<ShortsItem> = buildList {
    // Spec: ad every 10 videos. Pattern:
    // V V V V V V V V V V AD V V V V V V V V V V AD …
    videos.forEachIndexed { i, v ->
        add(ShortsItem.Video(v))
        // After every 10th video (1-indexed) inject an ad slot
        if ((i + 1) % 10 == 0) add(ShortsItem.AdSlot)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel  (identical logic to original, just cleaned up)
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class ShortsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: com.axio.reelz.data.repository.CatalogRepository,
    private val configRepo: com.axio.reelz.data.repository.ConfigRepository,
) : ViewModel() {

    private var nextCursor: String? = null
    private var hasMore: Boolean    = true

    data class UiState(
        val feedMode: FeedMode         = FeedMode.FOR_YOU,
        val searchQuery: String        = "",
        val isSearching: Boolean       = false,
        val forYouVideos: List<ShortVideo> = emptyList(),
        val forYouLoading: Boolean     = true,
        val forYouLoadingMore: Boolean = false,
        val discVideos: List<ShortVideo>   = emptyList(),
        val discLoading: Boolean       = false,
        val discLoadingMore: Boolean   = false,
        val selectedCategory: Int      = 0,
        val categories: List<ShortCategory> = emptyList(),
        val error: String?             = null,
        val isRefreshing: Boolean      = false,
    ) {
        val videos        get() = if (feedMode == FeedMode.FOR_YOU) forYouVideos else discVideos
        val isLoading     get() = if (feedMode == FeedMode.FOR_YOU) forYouLoading else discLoading
        val isLoadingMore get() = if (feedMode == FeedMode.FOR_YOU) forYouLoadingMore else discLoadingMore
    }

    private val _ui   = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _liked  = MutableStateFlow<Set<String>>(emptySet())
    private val _saved  = MutableStateFlow<Set<String>>(emptySet())
    private val _deadIds = MutableStateFlow<Set<String>>(emptySet())

    val liked: StateFlow<Set<String>>   = _liked.asStateFlow()
    val saved: StateFlow<Set<String>>   = _saved.asStateFlow()
    val deadIds: StateFlow<Set<String>> = _deadIds.asStateFlow()

    fun markDead(id: String) { _deadIds.update { it + id } }
    fun logFromUi(msg: String) { android.util.Log.d("ShortsVM", msg) }

    init {
        _ui.update { it.copy(forYouLoading = true) }
        viewModelScope.launch {
            configRepo.config.filterNotNull().collect {
                if (_ui.value.forYouVideos.isEmpty() && !_ui.value.forYouLoadingMore) loadForYou()
            }
        }
    }

    fun refresh() {
        _ui.update { it.copy(isRefreshing = true, error = null) }
        nextCursor = null; hasMore = true
        viewModelScope.launch {
            if (_ui.value.feedMode == FeedMode.FOR_YOU) loadForYou() else loadDiscovery(_ui.value.selectedCategory)
            delay(400)
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
            _ui.update { it.copy(feedMode = FeedMode.DISCOVERY) }; return
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

    fun search(query: String) { _ui.update { it.copy(searchQuery = query) } }
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
                    nextCursor = cursor; hasMore = more
                    val all  = if (append) _ui.value.forYouVideos + videos else videos
                    val cats = buildCategories(all)
                    if (append) _ui.update { it.copy(forYouVideos = all, forYouLoadingMore = false, categories = cats) }
                    else        _ui.update { it.copy(
                        forYouVideos  = all,
                        forYouLoading = false,
                        categories    = cats,
                        error         = if (all.isEmpty()) "No videos right now — pull to refresh" else null,
                    )}
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
            // If forYou hasn't loaded yet, kick it off and wait
            val baseVideos = if (_ui.value.forYouVideos.isEmpty()) {
                // Trigger forYou fetch if not already in flight
                if (!_ui.value.forYouLoading) loadForYou()
                // Wait until forYou is done (up to 10s)
                val deadline = System.currentTimeMillis() + 10_000L
                while (_ui.value.forYouLoading && System.currentTimeMillis() < deadline) {
                    delay(100)
                }
                _ui.value.forYouVideos
            } else {
                _ui.value.forYouVideos
            }

            val filtered = if (category?.source == null) baseVideos
                           else baseVideos.filter { it.source == category.source }

            if (!append) _ui.update { it.copy(
                discVideos  = filtered,
                discLoading = false,
                error       = if (filtered.isEmpty() && baseVideos.isNotEmpty())
                                  "No videos in \"${category?.label ?: "All"}\""
                              else null,
            )}
            else _ui.update { it.copy(discVideos = it.discVideos + filtered, discLoadingMore = false) }
        }
    }

    private fun buildCategories(videos: List<ShortVideo>): List<ShortCategory> {
        val sources = videos.mapNotNull { it.source }.distinct()
        return listOf(ShortCategory("All", null)) + sources.map { src ->
            ShortCategory(src.replaceFirstChar { it.uppercaseChar() }, src)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Player pool — identical logic, tightened implementation
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
private class ShortsPlayerPool(
    private val players: List<ExoPlayer>,
    private val buildMediaSource: (ShortVideo) -> MediaSource,
    private val onError: (videoId: String, poolIdx: Int, msg: String) -> Unit,
) {
    private val loadedPageIndex = IntArray(players.size) { -1 }
    private val loadedVideoId   = arrayOfNulls<String>(players.size)

    var activeIdx by mutableIntStateOf(0)
        private set

    val activePlayer: ExoPlayer get() = players[activeIdx]

    init {
        players.forEachIndexed { idx, player ->
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    // Auto-loop: seek to 0 and continue when video ends
                    if (state == Player.STATE_ENDED && idx == activeIdx) {
                        player.seekTo(0)
                        player.play()
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val vid = loadedVideoId[idx] ?: "unknown"
                    onError(vid, idx, error.errorCodeName)
                }
            })
        }
    }

    private fun slotForPage(pageIndex: Int): Int? =
        loadedPageIndex.indexOfFirst { it == pageIndex }.takeIf { it >= 0 }

    private fun slotToUseFor(pageIndex: Int, wantedPages: Set<Int>): Int {
        slotForPage(pageIndex)?.let { return it }
        val free = loadedPageIndex.indexOfFirst { it !in wantedPages }
        return if (free >= 0) free else activeIdx
    }

    private fun ensurePrepared(poolIdx: Int, pageIndex: Int, video: ShortVideo, playWhenReady: Boolean, muted: Boolean) {
        val player = players[poolIdx]
        // Skip re-prepare if this slot already has this exact video+page loaded
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

    fun primeWindow(items: List<ShortsItem>, centerPageIndex: Int, lookahead: Int, muted: Boolean) {
        if (items.isEmpty()) return
        val maxForward      = (players.size - 2).coerceAtLeast(0)
        val effectiveLook   = lookahead.coerceAtMost(maxForward)
        val wantedRange     = (centerPageIndex - 1)..(centerPageIndex + effectiveLook)
        val wantedPages     = wantedRange.toSet()
        for (pageIndex in wantedRange) {
            val video   = (items.getOrNull(pageIndex) as? ShortsItem.Video)?.video ?: continue
            val poolIdx = slotToUseFor(pageIndex, wantedPages)
            if (poolIdx == activeIdx) continue          // never override the active player
            ensurePrepared(poolIdx, pageIndex, video, playWhenReady = false, muted = muted)
        }
    }

    fun activate(items: List<ShortsItem>, pageIndex: Int, lookahead: Int, video: ShortVideo, muted: Boolean) {
        val effectiveLook = lookahead.coerceAtMost((players.size - 2).coerceAtLeast(0))
        var target = slotForPage(pageIndex)
        if (target == null) {
            target = slotToUseFor(pageIndex, ((pageIndex - 1)..(pageIndex + effectiveLook)).toSet())
            ensurePrepared(target, pageIndex, video, playWhenReady = false, muted = muted)
        }
        activeIdx = target
        players.forEachIndexed { idx, player ->
            if (idx == target) {
                player.playWhenReady = true
                player.volume = if (muted) 0f else 1f
                player.play()
            } else {
                player.playWhenReady = false
                player.volume = 0f
            }
        }
        primeWindow(items, pageIndex, lookahead, muted)
    }

    fun setMuted(muted: Boolean) { activePlayer.volume = if (muted) 0f else 1f }
    fun pauseActive()  { activePlayer.playWhenReady = false }
    fun resumeActive() { activePlayer.playWhenReady = true; activePlayer.play() }

    fun playerForVideoIfActive(video: ShortVideo): ExoPlayer? =
        if (loadedVideoId[activeIdx] == video.id) activePlayer else null

    fun release() { players.forEach { it.release() } }
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
                repeatMode    = Player.REPEAT_MODE_OFF  // we handle looping manually via STATE_ENDED
                playWhenReady = false
                volume        = 0f
            }
        }
    }
    DisposableEffect(Unit) { onDispose { players.forEach { it.release() } } }

    val cacheDataSourceFactory = remember(httpFactory) {
        CacheDataSource.Factory()
            .setCache(ShortsDiskCache.get(ctx))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun buildMediaSource(video: ShortVideo): MediaSource {
        val isHls = video.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        return if (isHls)
            HlsMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(video.url))
        else
            ProgressiveMediaSource.Factory(cacheDataSourceFactory).createMediaSource(MediaItem.fromUri(video.url))
    }

    return remember(players) { ShortsPlayerPool(players, ::buildMediaSource, onError) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Local icons
// ─────────────────────────────────────────────────────────────────────────────

private val IconClose: ImageVector get() = ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(
        pathData = PathData { moveTo(18f, 6f); lineTo(6f, 18f); moveTo(6f, 6f); lineTo(18f, 18f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent),
    )
}.build()

private val IconPause: ImageVector get() = ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(6f, 4f); lineTo(6f, 20f); lineTo(10f, 20f); lineTo(10f, 4f); close()
        moveTo(14f, 4f); lineTo(14f, 20f); lineTo(18f, 20f); lineTo(18f, 4f); close()
    }
}.build()

// Filled heart — used only for the double-tap burst animation (TikTok signature gesture)
private val IconHeartFilled: ImageVector get() = ImageVector.Builder("HeartFilled", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color(0xFFFF2D55))) {
        moveTo(12f, 21.593f)
        curveToRelative(-5.63f, -5.539f, -11f, -9.288f, -11f, -13.093f)
        arcToRelative(5f, 5f, 0f, false, true, 9.542f, -2.062f)
        arcToRelative(0.879f, 0.879f, 0f, false, false, 1.516f, 0f)
        arcTo(5f, 5f, 0f, false, true, 23f, 8.5f)
        curveToRelative(0f, 3.805f, -5.37f, 7.554f, -11f, 13.093f)
        close()
    }
}.build()

// ── "Unavailable" action icons from composables.com ──────────────────────────
// Used to show that Like, Comment, and Bookmark are not yet available.
// They are displayed as static pictures — no click logic, no badge.

private var _IconHeartOff: ImageVector? = null
private val IconHeartOff: ImageVector get() {
    if (_IconHeartOff != null) return _IconHeartOff!!
    _IconHeartOff = ImageVector.Builder("heart-off", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.White),
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(10.5f, 4.893f)
            arcToRelative(5.5f, 5.5f, 0f, false, true, 1.091f, 0.931f)
            arcToRelative(0.56f, 0.56f, 0f, false, false, 0.818f, 0f)
            arcTo(5.49f, 5.49f, 0f, false, true, 22f, 9.5f)
            curveToRelative(0f, 1.872f, -1.002f, 3.356f, -2.187f, 4.655f)
        }
        path(fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.White),
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(16.967f, 16.967f)
            lineToRelative(-3.459f, 3.346f)
            arcToRelative(2f, 2f, 0f, false, true, -3f, 0.019f)
            lineTo(5f, 15f)
            curveToRelative(-1.5f, -1.5f, -3f, -3.2f, -3f, -5.5f)
            arcToRelative(5.5f, 5.5f, 0f, false, true, 2.747f, -4.761f)
        }
        path(fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.White),
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(2f, 2f); lineToRelative(20f, 20f)
        }
    }.build()
    return _IconHeartOff!!
}

private var _IconCommentOff: ImageVector? = null
private val IconCommentOff: ImageVector get() {
    if (_IconCommentOff != null) return _IconCommentOff!!
    _IconCommentOff = ImageVector.Builder("message-square-off", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.White),
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(19f, 19f)
            horizontalLineTo(6.828f)
            arcToRelative(2f, 2f, 0f, false, false, -1.414f, 0.586f)
            lineToRelative(-2.202f, 2.202f)
            arcTo(0.7f, 0.7f, 0f, false, true, 2f, 21.286f)
            verticalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, 1.184f, -1.826f)
        }
        path(fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.White),
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(2f, 2f); lineToRelative(20f, 20f)
        }
        path(fill = SolidColor(Color.Transparent), stroke = SolidColor(Color.White),
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8.656f, 3f)
            horizontalLineTo(20f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineToRelative(11.344f)
        }
    }.build()
    return _IconCommentOff!!
}

private var _IconBookmarkOff: ImageVector? = null
private val IconBookmarkOff: ImageVector get() {
    if (_IconBookmarkOff != null) return _IconBookmarkOff!!
    _IconBookmarkOff = ImageVector.Builder("bookmark-off", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(3.28034f, 2.21968f)
            curveTo(2.98745f, 1.92678f, 2.51257f, 1.92677f, 2.21968f, 2.21966f)
            curveTo(1.92678f, 2.51255f, 1.92677f, 2.98743f, 2.21966f, 3.28032f)
            lineTo(5.00752f, 6.06823f)
            curveTo(5.00423f, 6.12813f, 5.00256f, 6.18846f, 5.00256f, 6.24918f)
            verticalLineTo(21.2451f)
            curveTo(5.00256f, 21.8563f, 5.69444f, 22.2109f, 6.19058f, 21.8539f)
            lineTo(12.0018f, 17.673f)
            lineTo(17.8129f, 21.8539f)
            curveTo(18.3091f, 22.2109f, 19.001f, 21.8563f, 19.001f, 21.2451f)
            verticalLineTo(20.062f)
            lineTo(20.7194f, 21.7805f)
            curveTo(21.0123f, 22.0734f, 21.4872f, 22.0734f, 21.7801f, 21.7805f)
            curveTo(22.073f, 21.4876f, 22.073f, 21.0127f, 21.7801f, 20.7198f)
            lineTo(3.28034f, 2.21968f)
            close()
            moveTo(17.501f, 18.5619f)
            verticalLineTo(19.7816f)
            lineTo(12.4398f, 16.1402f)
            curveTo(12.1781f, 15.952f, 11.8254f, 15.952f, 11.5637f, 16.1402f)
            lineTo(6.50256f, 19.7816f)
            verticalLineTo(7.56331f)
            lineTo(17.501f, 18.5619f)
            close()
            moveTo(17.501f, 6.24918f)
            verticalLineTo(14.3192f)
            lineTo(19.001f, 15.8192f)
            verticalLineTo(6.24918f)
            curveTo(19.001f, 4.45426f, 17.5459f, 2.99918f, 15.751f, 2.99918f)
            horizontalLineTo(8.25256f)
            curveTo(7.65756f, 2.99918f, 7.0999f, 3.15908f, 6.62021f, 3.43824f)
            lineTo(7.75336f, 4.57142f)
            curveTo(7.91155f, 4.52442f, 8.07911f, 4.49918f, 8.25256f, 4.49918f)
            horizontalLineTo(15.751f)
            curveTo(16.7175f, 4.49918f, 17.501f, 5.28269f, 17.501f, 6.24918f)
            close()
        }
    }.build()
    return _IconBookmarkOff!!
}

// ─────────────────────────────────────────────────────────────────────────────
// Feed toggle  (TikTok "Following / For You" style)
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
    val d     = LocalDimensions.current
    val bg    by animateColorAsState(if (selected) White else Color.Transparent, tween(200), label = "tabBg")
    val txt   by animateColorAsState(if (selected) Color.Black else White60, tween(200), label = "tabTxt")
    val scale by animateFloatAsState(if (selected) 1f else 0.95f, spring(0.7f, 600f), label = "tabS")
    Box(
        Modifier
            .scale(scale)
            .clip(RoundedCornerShape(d.radiusPill))
            .background(bg)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = d.spaceLg + d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
        Alignment.Center,
    ) {
        Text(label, color = txt, fontSize = d.textMd, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun ShortsScreen(nav: NavController, adEngine: AdEngine, vm: ShortsViewModel = hiltViewModel()) {
    val d       = LocalDimensions.current
    val ui      by vm.ui.collectAsState()
    val liked   by vm.liked.collectAsState()
    val saved   by vm.saved.collectAsState()
    val deadIds by vm.deadIds.collectAsState()

    val httpFactory = remember {
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

    // ── Build flat item list, filtering dead videos ──────────────────────────
    val rawItems by remember(ui.feedMode, ui.forYouVideos, ui.discVideos, deadIds) {
        derivedStateOf {
            val source = if (ui.feedMode == FeedMode.FOR_YOU) ui.forYouVideos else ui.discVideos
            buildShortsItemList(source.filter { it.id !in deadIds })
        }
    }

    // ── Single pager state for the whole screen ──────────────────────────────
    val pagerState = rememberPagerState { rawItems.size.coerceAtLeast(1) }
    LaunchedEffect(ui.feedMode) {
        if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
    }

    val currentPage = pagerState.currentPage.coerceIn(0, (rawItems.size.coerceAtLeast(1)) - 1)

    // ── Prime on drag (pre-buffer before fling lands) ────────────────────────
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

    // ── Activate on settle + paginate near end ───────────────────────────────
    var lastPage by remember(ui.feedMode) { mutableIntStateOf(0) }
    LaunchedEffect(currentPage, ui.feedMode, rawItems) {
        if (rawItems.isEmpty()) return@LaunchedEffect
        val currentVideo = (rawItems.getOrNull(currentPage) as? ShortsItem.Video)?.video
        if (currentVideo != null) pool.activate(rawItems, currentPage, LOOKAHEAD_PAGES, currentVideo, isMuted)
        if (currentPage != lastPage) {
            lastPage = currentPage
            val total = if (ui.feedMode == FeedMode.FOR_YOU) ui.forYouVideos.size else ui.discVideos.size
            if (total > 0 && currentPage >= total - LOAD_MORE_THRESHOLD) vm.loadMore()
        }
    }

    LaunchedEffect(isMuted)      { pool.setMuted(isMuted) }


    Box(Modifier.fillMaxSize().background(Color.Black)) {

        when {
            ui.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = d.spinnerLg) }

            !ui.error.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                ShortsErrorState(message = ui.error!!, onRetry = { vm.refresh() })
            }

            ui.videos.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                ShortsEmptyState(onRetry = { vm.refresh() })
            }

            else -> VerticalPager(
                state                   = pagerState,
                modifier                = Modifier.fillMaxSize(),
                userScrollEnabled       = true,
                beyondViewportPageCount = LOOKAHEAD_PAGES,
                key = { idx -> (rawItems.getOrNull(idx) as? ShortsItem.Video)?.video?.id ?: "ad_$idx" },
                // TikTok-grade fling physics:
                // • Low velocity threshold so ANY intentional swipe commits to next video
                // • High snap positional threshold so mid-scroll always snaps cleanly
                flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
            ) { page ->
                when (val item = rawItems.getOrNull(page)) {
                    is ShortsItem.AdSlot -> ShortsNativeAdPage(adEngine = adEngine)
                    is ShortsItem.Video  -> ShortVideoPage(
                        video        = item.video,
                        activePlayer = pool.playerForVideoIfActive(item.video),
                        isActive     = page == pagerState.currentPage,
                        isMuted      = isMuted,
                        isLiked      = item.video.id in liked,
                        onLike       = { vm.toggleLike(item.video.id) },
                        onMute       = { isMuted = !isMuted },
                        onPauseToggle = {
                            if (pool.activePlayer.isPlaying) pool.pauseActive()
                            else pool.resumeActive()
                        },
                    )
                    null -> Unit
                }
            }
        }

        // ── Top overlay: feed toggle (TikTok-style centered) + discovery chips ─
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = d.spaceSm)) {

            // FeedToggle — centered exactly as TikTok, no search button
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FeedToggle(feedMode = ui.feedMode, onSwitch = { vm.switchMode(it) })
            }

            AnimatedVisibility(
                visible = ui.feedMode == FeedMode.DISCOVERY,
                enter   = fadeIn(tween(200)) + expandVertically(tween(220)),
                exit    = fadeOut(tween(150)) + shrinkVertically(tween(170)),
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = d.spaceMd - d.spaceXxs),
                    contentPadding = PaddingValues(horizontal = d.screenHorizPad - d.spaceXxs),
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
                                color = if (selected) Color.White else White60,
                                fontSize = d.textSm,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        // ── Refresh indicator ────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = ui.isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = d.appBarHorizPad * 4),
            enter    = fadeIn(tween(120)) + scaleIn(tween(150), 0.6f),
            exit     = fadeOut(tween(100)) + scaleOut(tween(120), 0.6f),
        ) {
            Box(
                Modifier.size(d.buttonHeightSm - d.spaceMd).clip(CircleShape)
                    .background(Color(0xCC000000)).border(d.borderThin, GlassBorderMd, CircleShape),
                Alignment.Center,
            ) { CinematicSpinner(size = d.spinnerSm) }
        }

        // ── Load-more indicator ──────────────────────────────────────────────
        if (ui.isLoadingMore) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = d.spaceXxl * 3)) {
                CinematicSpinner(size = d.spinnerMd)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single video page — the TikTok feel lives here
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun ShortVideoPage(
    video: ShortVideo,
    activePlayer: ExoPlayer?,
    isActive: Boolean,
    isMuted: Boolean,
    isLiked: Boolean,
    onLike: () -> Unit,
    onMute: () -> Unit,
    onPauseToggle: () -> Unit,
) {
    val d       = LocalDimensions.current
    val haptic  = LocalHapticFeedback.current

    // ── Buffering state — delayed spinner to avoid flashing on fast loads ────
    var isActuallyBuffering by remember { mutableStateOf(true) }
    var showBufferingSpinner by remember { mutableStateOf(false) }

    DisposableEffect(isActive, activePlayer) {
        if (!isActive || activePlayer == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isActuallyBuffering = state == Player.STATE_BUFFERING
            }
        }
        activePlayer.addListener(listener)
        isActuallyBuffering = activePlayer.playbackState == Player.STATE_BUFFERING
        onDispose { activePlayer.removeListener(listener) }
    }

    // Only show spinner after BUFFER_SPINNER_DELAY_MS of continuous buffering
    LaunchedEffect(isActuallyBuffering, isActive) {
        if (isActuallyBuffering && isActive) {
            delay(BUFFER_SPINNER_DELAY_MS)
            showBufferingSpinner = true
        } else {
            showBufferingSpinner = false
        }
    }

    // ── Player alpha — fade in only once playing, never shows black ──────────
    val playerAlpha by animateFloatAsState(
        targetValue = if (isActive && activePlayer != null && !isActuallyBuffering) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "playerAlpha",
    )

    // ── Pause/play visual feedback ───────────────────────────────────────────
    var showPauseIcon by remember { mutableStateOf(false) }
    val pauseIconAlpha by animateFloatAsState(
        targetValue = if (showPauseIcon) 1f else 0f,
        animationSpec = tween(200),
        label = "pauseAlpha",
    )
    val pauseIconScale by animateFloatAsState(
        targetValue = if (showPauseIcon) 1f else 0.7f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "pauseScale",
    )

    // ── Double-tap heart animation (TikTok signature) ────────────────────────
    var showDoubleTapHeart by remember { mutableStateOf(false) }
    var doubleTapPos by remember { mutableStateOf(Pair(0f, 0f)) }
    val heartScale by animateFloatAsState(
        targetValue = if (showDoubleTapHeart) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 500f),
        label = "heartScale",
    )
    val heartAlpha by animateFloatAsState(
        targetValue = if (showDoubleTapHeart) 1f else 0f,
        animationSpec = tween(300),
        label = "heartAlpha",
    )
    LaunchedEffect(showDoubleTapHeart) {
        if (showDoubleTapHeart) { delay(700); showDoubleTapHeart = false }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // Single tap: pause/play with icon flash
                        onPauseToggle()
                        showPauseIcon = true
                    },
                    onDoubleTap = { offset ->
                        // Double tap: like + heart burst
                        if (!isLiked) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLike()
                        }
                        doubleTapPos = Pair(offset.x, offset.y)
                        showDoubleTapHeart = true
                    },
                )
            },
    ) {
        // ── Thumbnail: always visible, never removed ─────────────────────────
        AsyncImage(
            model = video.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // ── Player view: fades in only when actually playing ─────────────────
        if (isActive && activePlayer != null) {
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
                modifier = Modifier.fillMaxSize().alpha(playerAlpha),
            )
        }

        // ── Buffering spinner (delayed — only shows if buffering takes > 800ms) ──
        if (showBufferingSpinner && isActive) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CinematicSpinner(size = d.spinnerLg)
            }
        }

        // ── Gradient scrim (top + bottom) ────────────────────────────────────
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x55000000),
                        0.3f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1f to Color(0xCC000000),
                    )
                )
        )

        // ── Pause icon flash (single tap) ────────────────────────────────────
        Box(
            Modifier
                .fillMaxSize()
                .alpha(pauseIconAlpha),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .scale(pauseIconScale)
                    .clip(CircleShape)
                    .background(Color(0x88000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(IconPause, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        // Auto-hide pause icon after 600ms
        LaunchedEffect(showPauseIcon) {
            if (showPauseIcon) { delay(600); showPauseIcon = false }
        }

        // ── Double-tap heart burst ───────────────────────────────────────────
        if (showDoubleTapHeart || heartAlpha > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(heartAlpha),
            ) {
                val density = LocalDensity.current
                val heartOffX = with(density) { doubleTapPos.first.toDp() - 40.dp }
                val heartOffY = with(density) { doubleTapPos.second.toDp() - 40.dp }
                Icon(
                    IconHeartFilled,
                    contentDescription = null,
                    tint = Color(0xFFFF2D55),
                    modifier = Modifier
                        .offset(heartOffX, heartOffY)
                        .size(80.dp)
                        .scale(heartScale),
                )
            }
        }

        // ── Right action rail ────────────────────────────────────────────────
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = d.screenHorizPad, bottom = d.spaceXxl + d.spaceLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceXl - d.spaceXxs),
        ) {
            // Like — unavailable, shown as static "heart-off" picture
            Icon(
                IconHeartOff,
                contentDescription = null,
                tint     = Color.White.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(d.avatarSm)
                    .padding(d.spaceXxs),
            )
            // Comment — unavailable, shown as static "message-square-off" picture
            Icon(
                IconCommentOff,
                contentDescription = null,
                tint     = Color.White.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(d.avatarSm)
                    .padding(d.spaceXxs),
            )
            // Bookmark — unavailable, shown as static "bookmark-off" picture
            Icon(
                IconBookmarkOff,
                contentDescription = null,
                tint     = Color.White.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(d.avatarSm)
                    .padding(d.spaceXxs),
            )
            // Mute/unmute — fully functional, unchanged
            TikTokAction(icon = if (isMuted) IconVolumeOff else IconVolumeOn, tint = if (isMuted) Color(0xFFFF9A00) else Color.White, locked = false, onClick = onMute)
        }

        // ── Bottom-left metadata: title + source ─────────────────────────────
        // Scaffold already pads the content area by the nav bar height, so no
        // extra inset padding needed here — just sit above the 2dp progress bar.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start  = d.screenHorizPad,
                    end    = d.avatarLg + d.spaceXl + d.screenHorizPad,
                    bottom = d.spaceXxl + d.spaceLg + 2.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(d.spaceXxs),
        ) {
            if (!video.source.isNullOrBlank()) {
                Text(
                    "@${video.source}",
                    color      = White,
                    fontSize   = d.textMd,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
            }
            if (video.title.isNotBlank()) {
                Text(
                    video.title,
                    color      = White.copy(alpha = 0.85f),
                    fontSize   = d.textSm,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    lineHeight = (d.textSm.value * 1.4f).sp,
                )
            }
        }

        // ── Thin progress bar — TikTok-style: sits directly on top of the
        //    bottom navigation bar, no extra padding above it. ──────────────
        if (isActive && activePlayer != null) {
            VideoProgressBar(
                player   = activePlayer,
                // align to BottomCenter with NO navigationBarsPadding so it
                // touches the top edge of the navigation bar exactly like TikTok.
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Thin progress bar (2dp, white, animates with playback position)
// Sits flush with the system navigation bar — no additional bottom offset.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VideoProgressBar(player: ExoPlayer, modifier: Modifier = Modifier) {
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration.takeIf { it > 0 } ?: 1L
            progress = (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            delay(250)
        }
    }

    // TikTok places the bar at the very bottom edge of the video — the bar's
    // bottom edge touches the top line of the tab bar, zero gap.
    // The parent Box is edge-to-edge (no inset padding), so aligning to
    // BottomCenter with no extra modifier here places the bar exactly there.
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Color.White.copy(alpha = 0.25f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(Color.White)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Action button — used only for mute/unmute
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TikTokAction(icon: ImageVector, tint: Color, locked: Boolean, onClick: () -> Unit) {
    val d       = LocalDimensions.current
    var pressed by remember { mutableStateOf(false) }
    val scale   by animateFloatAsState(if (pressed) 1.25f else 1f, spring(0.4f, 700f), label = "s")

    Icon(
        icon, null,
        tint = tint,
        modifier = Modifier
            .size(d.avatarSm)
            .scale(scale)
            .padding(d.spaceXxs)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                pressed = true; onClick()
            },
    )
    LaunchedEffect(pressed) { if (pressed) { delay(200); pressed = false } }
}

// ─────────────────────────────────────────────────────────────────────────────
// Error + empty states
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShortsErrorState(message: String, onRetry: () -> Unit) {
    val d = LocalDimensions.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = d.spaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(d.avatarLg + d.spaceLg).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Error.copy(.18f), Color.Transparent)))
                    .border(d.borderThin, Error.copy(.4f), CircleShape)
            )
            Icon(IconSearch, null, tint = Error.copy(.85f), modifier = Modifier.size(d.iconXl))
        }
        Text("Couldn't load videos", color = White, fontSize = d.textXl, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            com.axio.reelz.ui.components.friendlyError(message),
            color = White40, fontSize = d.textMd, textAlign = TextAlign.Center,
            lineHeight = (d.textMd.value * 1.6f).sp,
        )
        Spacer(Modifier.height(d.spaceXs))
        com.axio.reelz.ui.components.BrandButton("Try Again", onClick = onRetry)
    }
}

@Composable
private fun ShortsEmptyState(onRetry: () -> Unit) {
    val d = LocalDimensions.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = d.spaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(d.avatarLg + d.spaceLg).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(White.copy(.06f), Color.Transparent)))
                    .border(d.borderThin, GlassBorderMd, CircleShape)
            )
            Icon(com.axio.reelz.ui.components.IconReel, null, tint = White20, modifier = Modifier.size(d.iconXl))
        }
        Text("No videos right now", color = White60, fontSize = d.textXl, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            "Pull down to refresh or check back later.",
            color = White40, fontSize = d.textMd, textAlign = TextAlign.Center,
            lineHeight = (d.textMd.value * 1.6f).sp,
        )
        Spacer(Modifier.height(d.spaceXs))
        com.axio.reelz.ui.components.GhostButton("Refresh", onClick = onRetry)
    }
}
