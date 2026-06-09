package com.reelz.ui.screens.detail

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.reelz.BuildConfig
import com.reelz.data.model.*
import com.reelz.data.repository.DownloadRepository
import com.reelz.data.repository.MediaRepository
import com.reelz.scanner.NativeBridge
import com.reelz.scanner.StreamEngine
import com.reelz.ui.components.*
import com.reelz.ui.screens.downloads.formatSize
import com.reelz.ui.screens.player.PlayerActivity
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

// ── Local icon aliases used in DetailScreen ───────────────────────────────────
private val IconArrowLeft  get() = com.reelz.ui.components.IconSearch.let {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("ArrowLeft", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = PathData { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) },
            stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White),
            strokeLineWidth = 2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent))
    }.build()
}

private val IconHeartFill get() = androidx.compose.ui.graphics.vector.ImageVector.Builder("HeartFill", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(20.84f, 4.61f); arcTo(5.5f, 5.5f, 0f, false, false, 12f, 8.5f)
        arcTo(5.5f, 5.5f, 0f, false, false, 3.16f, 4.61f)
        arcTo(5.5f, 5.5f, 0f, false, false, 12f, 20f); arcTo(5.5f, 5.5f, 0f, false, false, 20.84f, 4.61f); close()
    }, fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color(0xFFFF3D6E)))
}.build()

private val IconHeartOutline get() = androidx.compose.ui.graphics.vector.ImageVector.Builder("HeartOut", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(20.84f, 4.61f); arcTo(5.5f, 5.5f, 0f, false, false, 12f, 8.5f)
        arcTo(5.5f, 5.5f, 0f, false, false, 3.16f, 4.61f)
        arcTo(5.5f, 5.5f, 0f, false, false, 12f, 20f); arcTo(5.5f, 5.5f, 0f, false, false, 20.84f, 4.61f); close()
    }, stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White),
       strokeLineWidth = 1.7f, fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent))
}.build()

private val IconBookmarkFill get() = androidx.compose.ui.graphics.vector.ImageVector.Builder("BookFill", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(12f, 16f); lineTo(5f, 21f); close() },
        fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color(0xFFE8A020)))
}.build()

private val IconBookmarkOutline get() = androidx.compose.ui.graphics.vector.ImageVector.Builder("BookOut", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(12f, 16f); lineTo(5f, 21f); close() },
        stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White),
        strokeLineWidth = 1.7f, fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent))
}.build()

private val IconCheckCircle get() = androidx.compose.ui.graphics.vector.ImageVector.Builder("Check", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
        moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
    }, stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color(0xFF2DD36F)),
       strokeLineWidth = 1.7f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
       strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
       fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent))
}.build()

private val IconError get() = androidx.compose.ui.graphics.vector.ImageVector.Builder("Err", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
        moveTo(12f, 8f); lineTo(12f, 12f); moveTo(12f, 16f); lineTo(12f, 16.01f)
    }, stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color(0xFFFF3B30)),
       strokeLineWidth = 1.7f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
       fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent))
}.build()

private val IconClose get() = androidx.compose.ui.graphics.vector.ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(18f, 6f); lineTo(6f, 18f); moveTo(6f, 6f); lineTo(18f, 18f) },
        stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White),
        strokeLineWidth = 1.8f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent))
}.build()

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repo: MediaRepository,
    private val downloadRepo: DownloadRepository,
    private val engine: StreamEngine,
    @javax.inject.Named("download") private val httpClient: okhttp3.OkHttpClient,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val extrasLoading: Boolean = false,
        val error: String? = null,
        val detail: MediaDetail? = null,
        val episodes: List<Episode> = emptyList(),
        val selectedSeason: Int = 1,
        val isInWatchlist: Boolean = false,
        val isLiked: Boolean = false,
        val isEpisodesLoading: Boolean = false,
        // Download sheet state
        val showDownloadSheet: Boolean = false,
        val downloadQualities: List<QualityTrack> = emptyList(),
        val isResolvingQualities: Boolean = false,
        val downloadEnqueued: Boolean = false,
        // For episode download context
        val pendingDownloadSeason: Int = 0,
        val pendingDownloadEpisode: Int = 0,
        val pendingDownloadTitle: String = "",
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var currentMedia: Media? = null

    /**
     * UPGRADE P1: Cached stream result from the first resolve() call in openDownloadSheet().
     * Reused in enqueueDownload() to eliminate the duplicate engine.resolve() call.
     */
    private var cachedStreamResult: com.reelz.data.model.StreamResult? = null

    /**
     * UPGRADE P9: Pre-resolved stream started in background after detail loads.
     * Used if available when user taps Play or Download.
     */
    internal var preResolvedStream: com.reelz.data.model.StreamResult? = null
    /** Live prefetch state from the engine — exposed so the composable can read it on tap. */
    val enginePrefetchState get() = engine.prefetchState

    /**
     * Pre-parsed quality list from the master playlist.
     * Built in the background right after preResolvedStream is resolved.
     * Makes the download sheet open instantly — zero network call on tap.
     * Key is "tmdbId_season_episode" so episodes don't collide with movies.
     */
    private val preResolvedQualities = HashMap<String, List<QualityTrack>>()

    private fun qualityKey(tmdbId: Int, season: Int = 0, episode: Int = 0) =
        "${tmdbId}_${season}_${episode}"

    fun load(tmdbId: Int, mediaType: MediaType) {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            try {
                // Stage 1 — fast fetch (no append_to_response). Screen appears immediately.
                val inWatchlist = repo.isInWatchlist(tmdbId)
                val liked       = repo.isLiked(tmdbId)
                val detail      = repo.getDetailFast(tmdbId, mediaType)
                currentMedia = Media(
                    id = detail.tmdbId, tmdbId = detail.tmdbId, title = detail.title,
                    overview = detail.overview, posterPath = detail.posterPath,
                    backdropPath = detail.backdropPath, releaseDate = detail.releaseDate,
                    voteAverage = detail.voteAverage, voteCount = detail.voteCount,
                    popularity = 0.0, mediaType = mediaType,
                )
                // Screen is now visible — isLoading = false, extrasLoading = true
                _ui.update { it.copy(
                    isLoading     = false,
                    extrasLoading = true,
                    detail        = detail,
                    isInWatchlist = inWatchlist,
                    isLiked       = liked,
                ) }

                if (mediaType == MediaType.TV && detail.seasons.isNotEmpty()) {
                    loadEpisodes(tmdbId, 1)
                }

                // Stage 2 — heavy extras (credits, videos, similar) in background
                viewModelScope.launch {
                    try {
                        val extras = repo.getDetailExtras(tmdbId, mediaType)
                        _ui.update { it.copy(detail = extras, extrasLoading = false) }
                    } catch (_: Exception) {
                        _ui.update { it.copy(extrasLoading = false) }
                    }
                }

                // Stage 3 — kick off prefetch via engine (fires the racing resolver in background).
                // The engine exposes prefetchState: StateFlow so the player subscribes to it
                // and starts playing the moment the result arrives — no duplicate network call.
                engine.prefetch(viewModelScope, tmdbId, mediaType)

                // Also subscribe here so we can populate preResolvedStream / preResolvedQualities
                // for the download sheet (same result, zero extra work).
                viewModelScope.launch {
                    engine.prefetchState
                        .filter { it is com.reelz.scanner.PrefetchState.Ready }
                        .take(1)
                        .collect { state ->
                            val stream = (state as com.reelz.scanner.PrefetchState.Ready).result
                            preResolvedStream = stream
                            try {
                                val qualities = when {
                                    stream.qualities.isNotEmpty() -> stream.qualities
                                    stream.isHls -> parseMasterPlaylist(
                                        stream.url, stream.headers,
                                        _ui.value.detail?.runtime,
                                    )
                                    else -> listOf(QualityTrack("Best available", stream.url))
                                }
                                preResolvedQualities[qualityKey(tmdbId)] = qualities
                            } catch (_: Exception) {}
                        }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun selectSeason(tmdbId: Int, season: Int) {
        _ui.update { it.copy(selectedSeason = season) }
        loadEpisodes(tmdbId, season)
    }

    private fun loadEpisodes(tmdbId: Int, season: Int) {
        viewModelScope.launch {
            _ui.update { it.copy(isEpisodesLoading = true) }
            try {
                val eps = repo.getSeasonEpisodes(tmdbId, season)
                _ui.update { it.copy(episodes = eps, isEpisodesLoading = false) }
            } catch (_: Exception) {
                _ui.update { it.copy(isEpisodesLoading = false) }
            }
        }
    }

    fun toggleWatchlist() {
        val m = currentMedia ?: return
        viewModelScope.launch {
            val now = repo.toggleWatchlist(m)
            _ui.update { it.copy(isInWatchlist = now) }
        }
    }

    fun toggleLike() {
        val m = currentMedia ?: return
        viewModelScope.launch {
            val now = repo.toggleLike(m)
            _ui.update { it.copy(isLiked = now) }
        }
    }

    // ── Download flow ─────────────────────────────────────────────────────────

    /** Called when user taps the Download button on a movie or episode. */
    fun openDownloadSheet(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        episodeTitle: String = "",
    ) {
        val detail = _ui.value.detail ?: return
        _ui.update {
            it.copy(
                showDownloadSheet       = true,
                downloadQualities       = emptyList(),
                isResolvingQualities    = true,
                downloadEnqueued        = false,
                pendingDownloadSeason   = season,
                pendingDownloadEpisode  = episode,
                pendingDownloadTitle    = episodeTitle.ifBlank { detail.title },
            )
        }
        viewModelScope.launch {
            try {
                val key = qualityKey(tmdbId, season, episode)

                // Fast path — qualities already parsed in background, show instantly
                val cached = preResolvedQualities[key]
                if (cached != null) {
                    cachedStreamResult = preResolvedStream
                    _ui.update { it.copy(
                        downloadQualities    = cached,
                        isResolvingQualities = false,
                    )}
                    return@launch
                }

                // Slow path — not yet pre-resolved (user tapped very fast, or episode)
                val stream = preResolvedStream?.let { s ->
                    // Only reuse movie pre-resolve for movie (season==0)
                    if (season == 0) s else null
                } ?: engine.resolve(tmdbId, mediaType, season, episode)

                cachedStreamResult = stream

                val qualities = when {
                    stream == null -> emptyList()
                    stream.qualities.isNotEmpty() -> stream.qualities
                    stream.isHls -> parseMasterPlaylist(stream.url, stream.headers, detail.runtime)
                    else -> listOf(QualityTrack("Best available", stream.url))
                }

                // Store so next tap is instant
                if (qualities.isNotEmpty()) preResolvedQualities[key] = qualities

                _ui.update { it.copy(downloadQualities = qualities, isResolvingQualities = false) }
            } catch (e: Exception) {
                _ui.update { it.copy(isResolvingQualities = false, showDownloadSheet = false) }
            }
        }
    }

    fun dismissDownloadSheet() {
        _ui.update { it.copy(showDownloadSheet = false, downloadEnqueued = false) }
    }

    fun enqueueDownload(ctx: android.content.Context, track: QualityTrack) {
        val detail = _ui.value.detail ?: return
        val state  = _ui.value
        viewModelScope.launch {
            // UPGRADE P1: Use CACHED stream result — no second engine.resolve() call.
            // The cachedStreamResult was stored when openDownloadSheet() ran.
            // This eliminates the 10–20 second wait after quality selection.
            val cachedHeaders = cachedStreamResult?.headers ?: emptyMap()
            val qualityTracks = _ui.value.downloadQualities

            downloadRepo.enqueue(
                ctx             = ctx,
                tmdbId          = detail.tmdbId,
                title           = state.pendingDownloadTitle,
                posterPath      = detail.posterPath,
                mediaType       = detail.mediaType,
                season          = state.pendingDownloadSeason,
                episode         = state.pendingDownloadEpisode,
                episodeName     = if (state.pendingDownloadSeason > 0) state.pendingDownloadTitle else "",
                quality         = track.label,
                streamUrl       = track.url,
                headers         = cachedHeaders,
                qualityTracks   = qualityTracks,
            )
            _ui.update { it.copy(downloadEnqueued = true) }
        }
    }

    /**
     * UPGRADE P2 + P3 + P14: Fast M3U8 master playlist parser.
     *
     * Uses NativeBridge (C++) for single-pass parsing — 10–50x faster than Kotlin loop.
     * Uses bandwidth × runtime for size estimation — ZERO extra network calls.
     * Uses the injected shared OkHttpClient — warm connections, DNS cache, HTTP/2.
     *
     * Result: quality list appears in < 200ms after master playlist is fetched.
     */
    private suspend fun parseMasterPlaylist(
        masterUrl: String,
        headers: Map<String, String>,
        runtimeMinutes: Int? = null,
    ): List<QualityTrack> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            // UPGRADE P13: Use injected shared client (warm connections, no cold-start)
            val req = okhttp3.Request.Builder().url(masterUrl).apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            val body = httpClient.newCall(req).execute().use { it.body?.string() } ?: return@withContext emptyList()

            // UPGRADE P3: NativeBridge C++ parsing — single linear pass, no allocations per line
            val rawVariants = com.reelz.scanner.NativeBridge.variants(body, masterUrl)
            if (rawVariants.isEmpty()) return@withContext emptyList()

            // UPGRADE P2: Bandwidth-based size estimation — ZERO extra network calls
            // estimatedBytes = (bandwidth_bps * runtime_seconds) / 8
            val runtimeSec = (runtimeMinutes ?: 0) * 60L

            rawVariants.map { variant ->
                val estimatedSize = if (runtimeSec > 0 && variant.bandwidth > 0) {
                    // Apply 0.55 correction factor — declared HLS bandwidth is always
                    // peak/theoretical. Real encoded streams average ~55% of declared.
                    // This brings the shown size much closer to actual download size.
                    ((variant.bandwidth * runtimeSec) / 8L * 55L) / 100L
                } else 0L
                variant.copy(estimatedSizeBytes = estimatedSize)
            }
            .groupBy { it.label }
            .map { (_, v) -> v.maxByOrNull { it.bandwidth }!! }
            .sortedByDescending { it.bandwidth }
        } catch (_: Exception) { emptyList() }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────
@Composable
fun DetailScreen(
    tmdbId: Int,
    mediaType: MediaType,
    nav: NavController,
    vm: DetailViewModel = hiltViewModel(),
) {
    val ui  by vm.ui.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(tmdbId) { vm.load(tmdbId, mediaType) }

    fun launchPlayer(season: Int = 0, episode: Int = 0, epName: String = "") {
        val d = ui.detail ?: return
        // Check engine's live prefetchState first — handles the race where the
        // subscriber coroutine has not updated preResolvedStream yet but the engine
        // already finished. Either path avoids a second resolve() in the player.
        val readyStream = vm.preResolvedStream
            ?: (vm.enginePrefetchState.value as? com.reelz.scanner.PrefetchState.Ready)?.result
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
            putExtra("tmdbId",     d.tmdbId)
            putExtra("mediaType",  d.mediaType.name)
            putExtra("season",     season)
            putExtra("episode",    episode)
            putExtra("title",      if (epName.isNotBlank()) epName else d.title)
            putExtra("posterPath", d.posterPath)
            readyStream?.let { stream ->
                putExtra("streamUrl",     stream.url)
                putExtra("streamIsHls",   stream.isHls)
                putExtra("streamReferer", stream.referer)
                putExtra("streamOrigin",  stream.origin)
            }
        })
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when {
            ui.isLoading -> DetailSkeleton()
            ui.error != null -> ErrorState(ui.error!!, onRetry = { vm.load(tmdbId, mediaType) })
            ui.detail != null -> DetailContent(
                ui             = ui,
                extrasLoading  = ui.extrasLoading,
                onBack         = { nav.popBackStack() },
                onPlayMovie    = { launchPlayer() },
                onPlayEpisode  = { s, e, name -> launchPlayer(s, e, name) },
                onSeasonSelect = { vm.selectSeason(tmdbId, it) },
                onWatchlist    = { vm.toggleWatchlist() },
                onLike         = { vm.toggleLike() },
                onSimilarClick = { id, type -> nav.navigate(com.reelz.ui.Route.Detail.go(id, type)) },
                onDownloadMovie = {
                    vm.openDownloadSheet(tmdbId, mediaType)
                },
                onDownloadEpisode = { s, e, name ->
                    vm.openDownloadSheet(tmdbId, mediaType, s, e, name)
                },
            )
        }

        // ── Download bottom sheet ──────────────────────────────────────
        if (ui.showDownloadSheet) {
            DownloadQualitySheet(
                title              = ui.pendingDownloadTitle,
                qualities          = ui.downloadQualities,
                isLoading          = ui.isResolvingQualities,
                enqueued           = ui.downloadEnqueued,
                onDismiss          = { vm.dismissDownloadSheet() },
                onSelectQuality    = { track -> vm.enqueueDownload(ctx, track) },
            )
        }
    }
}

// ── Download quality bottom sheet ────────────────────────────────────────────
@Composable
fun DownloadQualitySheet(
    title: String,
    qualities: List<QualityTrack>,
    isLoading: Boolean,
    enqueued: Boolean,
    onDismiss: () -> Unit,
    onSelectQuality: (QualityTrack) -> Unit,
) {
    // Scrim
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(.6f))
            .clickable { onDismiss() },
    )

    // Sheet
    Box(Modifier.fillMaxSize(), Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(BgCard)
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .clickable(enabled = false) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Handle
            Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(White40))
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(IconDownloadCloud, null, tint = Brand, modifier = Modifier.size(22.dp))
                Text(
                    "Download",
                    color = White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(IconClose, null, tint = White60, modifier = Modifier.size(20.dp))
                }
            }
            Text(title, color = White60, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(20.dp))

            when {
                enqueued -> {
                    Icon(IconCheckCircle, null, tint = Brand, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Added to downloads!", color = White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("You can watch it once enough has downloaded.", color = White60, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    BrandButton("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                }

                isLoading -> {
                    CinematicSpinner(size = 32.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Fetching available qualities…", color = White60, fontSize = 13.sp)
                }

                qualities.isEmpty() -> {
                    Icon(IconError, null, tint = White40, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No downloadable streams found", color = White60, fontSize = 13.sp)
                }

                else -> {
                    Text("Select quality", color = White60, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    qualities.forEach { track ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(BgRaised)
                                .border(1.dp, GlassBorderMd, RoundedCornerShape(12.dp))
                                .clickable { onSelectQuality(track) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Quality badge
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Brand.copy(.15f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(track.label, color = Brand, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            Column(Modifier.weight(1f)) {
                                val typeLabel = if (track.url.contains(".m3u8", true)) "HLS" else "MP4"
                                Text(typeLabel, color = White60, fontSize = 11.sp)

                                // Show estimated size if available
                                if (track.estimatedSizeBytes > 0) {
                                    Text(
                                        "~${formatSize(track.estimatedSizeBytes)}",
                                        color = White40,
                                        fontSize = 10.sp,
                                    )
                                } else if (track.bandwidth > 0) {
                                    Text(
                                        "~${track.bandwidth / 1_000_000}Mbps",
                                        color = White40,
                                        fontSize = 10.sp,
                                    )
                                }
                            }

                            Icon(IconDownloadCloud, null, tint = White60, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

// ── Detail content ────────────────────────────────────────────────────────────
@Composable
private fun DetailContent(
    ui: DetailViewModel.UiState,
    extrasLoading: Boolean = false,
    onBack: () -> Unit,
    onPlayMovie: () -> Unit,
    onPlayEpisode: (Int, Int, String) -> Unit,
    onSeasonSelect: (Int) -> Unit,
    onWatchlist: () -> Unit,
    onLike: () -> Unit,
    onSimilarClick: (Int, MediaType) -> Unit,
    onDownloadMovie: () -> Unit,
    onDownloadEpisode: (Int, Int, String) -> Unit,
) {
    val detail  = ui.detail!!
    val isMovie = detail.mediaType == MediaType.MOVIE

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {

        // ── Backdrop hero ──────────────────────────────────────────────────
        item {
            Box(Modifier.fillMaxWidth().height(420.dp)) {
                AsyncImage(
                    model = BuildConfig.TMDB_IMG_ORIGINAL + detail.backdropPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(.45f), Bg))
                ))
                // Back button
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.statusBarsPadding().padding(8.dp)
                        .clip(CircleShape).background(Color.Black.copy(.5f))
                ) { Icon(IconArrowLeft, null, tint = White) }

                // Poster + meta
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AsyncImage(
                            model = BuildConfig.TMDB_IMG_W342 + detail.posterPath,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(90.dp).height(134.dp)
                                .clip(RoundedCornerShape(12.dp)).background(BgRaised),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(detail.title, color = White, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!detail.tagline.isNullOrBlank())
                                Text(detail.tagline, color = White60, fontSize = 12.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RatingChip(detail.voteAverage)
                                Text("•", color = White40)
                                Text(detail.releaseDate?.take(4) ?: "", color = White60, fontSize = 13.sp)
                                if (detail.runtime != null) { Text("•", color = White40); Text(formatRuntime(detail.runtime), color = White60, fontSize = 13.sp) }
                                if (!isMovie) { Text("•", color = White40); Text("${detail.numberOfSeasons}S", color = White60, fontSize = 13.sp) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                detail.genres.take(3).forEach { g ->
                                    Box(
                                        Modifier.clip(RoundedCornerShape(5.dp)).background(BgSurface)
                                            .border(1.dp, GlassBorderMd, RoundedCornerShape(5.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) { Text(g.name, color = White60, fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Action row ─────────────────────────────────────────────────────
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isMovie) {
                    BrandButton(
                        text     = "Watch Now",
                        onClick  = onPlayMovie,
                        modifier = Modifier.weight(1f),
                        icon     = { Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(20.dp)) },
                    )
                    // ── Download button (movies only, like MovieBox) ────────
                    OutlinedButton(
                        onClick  = onDownloadMovie,
                        shape    = RoundedCornerShape(100.dp),
                        border   = BorderStroke(1.dp, GlassBorderMd),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(IconDownloadCloud, null, tint = White80, modifier = Modifier.size(18.dp))
                    }
                }
                // Watchlist button
                OutlinedButton(
                    onClick  = onWatchlist,
                    shape    = RoundedCornerShape(100.dp),
                    border   = BorderStroke(1.dp, if (ui.isInWatchlist) Brand else GlassBorderMd),
                    modifier = Modifier.height(48.dp).let { if (isMovie) it else it.weight(1f) },
                ) {
                    Icon(
                        if (ui.isInWatchlist) IconBookmarkFill else IconBookmarkOutline,
                        null,
                        tint = if (ui.isInWatchlist) Brand else White60,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (ui.isInWatchlist) "Saved" else "Save", color = if (ui.isInWatchlist) Brand else White60)
                }
                // Like button
                OutlinedButton(
                    onClick = onLike,
                    shape   = RoundedCornerShape(100.dp),
                    border  = BorderStroke(1.dp, if (ui.isLiked) Like else GlassBorderMd),
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(
                        if (ui.isLiked) IconHeartFill else IconHeartOutline,
                        null,
                        tint = if (ui.isLiked) Like else White60,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // ── Overview ───────────────────────────────────────────────────────
        item {
            var expanded by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Overview", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    detail.overview,
                    color = White60,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail.overview.length > 150) {
                    Text(
                        if (expanded) "Show less" else "Show more",
                        color = Brand,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(top = 4.dp),
                    )
                }
            }
        }

        // ── Movie metadata ─────────────────────────────────────────────────
        if (isMovie && (detail.runtime != null || detail.status != null)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    detail.runtime?.let { MetaChip("Runtime", formatRuntime(it)) }
                    detail.status?.let   { MetaChip("Status", it) }
                    if (detail.voteCount > 0) MetaChip("Votes", "${detail.voteCount}")
                }
            }
        }

        // ── TV: Season selector + episodes ─────────────────────────────────
        if (!isMovie && detail.seasons.isNotEmpty()) {
            item {
                SectionHeader("Episodes")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(detail.seasons) { s ->
                        val sel = ui.selectedSeason == s.seasonNumber
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .background(if (sel) Brand else GlassMd)
                                .border(1.dp, if (sel) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(100.dp))
                                .clickable { onSeasonSelect(s.seasonNumber) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "S${s.seasonNumber}",
                                color = if (sel) Color.White else White60,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            if (ui.isEpisodesLoading) {
                item { Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CinematicSpinner() } }
            } else {
                items(ui.episodes, key = { it.id }) { ep ->
                    EpisodeRow(
                        episode   = ep,
                        onClick   = { onPlayEpisode(ep.seasonNumber, ep.episodeNumber, ep.name) },
                        onDownload = { onDownloadEpisode(ep.seasonNumber, ep.episodeNumber, ep.name) },
                    )
                }
            }
        }

        // ── Cast ───────────────────────────────────────────────────────────
        if (extrasLoading) {
            item { SectionHeader("Cast") }
            item { CastRowSkeleton() }
        } else if (detail.cast.isNotEmpty()) {
            item { SectionHeader("Cast") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(detail.cast, key = { it.id }) { c ->
                        CastCard(c)
                    }
                }
            }
        }

        // ── Similar ────────────────────────────────────────────────────────
        if (extrasLoading) {
            item { SectionHeader("More Like This") }
            item { MediaRowSkeleton() }
        } else if (detail.similar.isNotEmpty()) {
            item { SectionHeader("More Like This") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(detail.similar, key = { it.tmdbId }) { m ->
                        com.reelz.ui.components.MediaRowCard(m, onClick = { onSimilarClick(m.tmdbId, m.mediaType) })
                    }
                }
            }
        }
    }
}

// ── Episode row ───────────────────────────────────────────────────────────────
@Composable
fun EpisodeRow(
    episode: Episode,
    onClick: () -> Unit,
    onDownload: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.width(110.dp).height(64.dp).clip(RoundedCornerShape(12.dp)).background(BgRaised),
        ) {
            if (episode.stillPath != null) {
                AsyncImage(
                    model = BuildConfig.TMDB_IMG_W342 + episode.stillPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(.25f)), Alignment.Center) {
                Icon(IconPlayCircle, null, tint = White.copy(.8f), modifier = Modifier.size(26.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text("E${episode.episodeNumber} · ${episode.name}", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(episode.overview.ifBlank { "No description." }, color = White60, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            episode.runtime?.let {
                Spacer(Modifier.height(3.dp))
                Text("${it}m", color = White40, fontSize = 10.sp)
            }
        }
        // Download icon for each episode
        IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
            Icon(IconDownloadCloud, null, tint = White60, modifier = Modifier.size(18.dp))
        }
        Icon(IconPlay, null, tint = Brand, modifier = Modifier.size(20.dp))
    }
    Divider(color = GlassBorder, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
}

// ── Cast card ─────────────────────────────────────────────────────────────────
@Composable
fun CastCard(cast: CastMember) {
    Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(BgRaised)) {
            if (cast.profilePath != null) {
                AsyncImage(
                    model = BuildConfig.TMDB_IMG_W342 + cast.profilePath,
                    contentDescription = cast.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(IconUser, null, tint = White40, modifier = Modifier.fillMaxSize().padding(12.dp))
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(cast.name, color = White80, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 13.sp)
        Text(cast.character, color = White40, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ── Meta chip ─────────────────────────────────────────────────────────────────
@Composable
fun MetaChip(label: String, value: String) {
    Column {
        Text(label, color = White40, fontSize = 10.sp)
        Text(value, color = White80, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

fun formatRuntime(minutes: Int): String {
    val h = minutes / 60; val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

// ── Skeleton shimmer ──────────────────────────────────────────────────────────

@Composable
private fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        androidx.compose.ui.graphics.Color(0xFF1A1A24),
        androidx.compose.ui.graphics.Color(0xFF2A2A38),
        androidx.compose.ui.graphics.Color(0xFF1A1A24),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue  = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start  = androidx.compose.ui.geometry.Offset(translateAnim - 300f, 0f),
        end    = androidx.compose.ui.geometry.Offset(translateAnim, 0f),
    )
}

@Composable
private fun ShimmerBox(modifier: Modifier, radius: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(shimmerBrush())
    )
}

/** Full-screen skeleton shown while Stage 1 (fast detail) is loading. */
@Composable
fun DetailSkeleton() {
    Column(Modifier.fillMaxSize().background(Bg)) {
        // Backdrop placeholder
        ShimmerBox(
            Modifier.fillMaxWidth().height(420.dp),
            radius = 0.dp,
        )
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Title
            ShimmerBox(Modifier.fillMaxWidth(0.7f).height(26.dp))
            // Subtitle line
            ShimmerBox(Modifier.fillMaxWidth(0.45f).height(14.dp))
            Spacer(Modifier.height(4.dp))
            // Genre pills
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShimmerBox(Modifier.width(64.dp).height(24.dp), radius = 12.dp)
                ShimmerBox(Modifier.width(64.dp).height(24.dp), radius = 12.dp)
                ShimmerBox(Modifier.width(64.dp).height(24.dp), radius = 12.dp)
            }
            Spacer(Modifier.height(4.dp))
            // Play button
            ShimmerBox(Modifier.fillMaxWidth().height(48.dp), radius = 14.dp)
            Spacer(Modifier.height(4.dp))
            // Overview lines
            ShimmerBox(Modifier.fillMaxWidth().height(13.dp))
            ShimmerBox(Modifier.fillMaxWidth().height(13.dp))
            ShimmerBox(Modifier.fillMaxWidth(0.6f).height(13.dp))
        }
    }
}

/** Skeleton row for the Cast section while extras are loading. */
@Composable
fun CastRowSkeleton() {
    Row(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(5) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ShimmerBox(Modifier.size(64.dp), radius = 32.dp)
                ShimmerBox(Modifier.width(56.dp).height(10.dp))
                ShimmerBox(Modifier.width(44.dp).height(9.dp))
            }
        }
    }
}

/** Skeleton row for the Similar / More Like This section while extras are loading. */
@Composable
fun MediaRowSkeleton() {
    Row(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(4) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ShimmerBox(Modifier.width(120.dp).height(170.dp), radius = 10.dp)
                ShimmerBox(Modifier.width(100.dp).height(11.dp))
                ShimmerBox(Modifier.width(72.dp).height(10.dp))
            }
        }
    }
}
