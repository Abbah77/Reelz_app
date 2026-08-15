package com.axio.reelz.ui.screens.detail

import android.content.Intent
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.ads.DetailBannerAd
import com.axio.reelz.ads.routeAdUrl
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.axio.reelz.data.model.*
import com.axio.reelz.data.repository.DownloadRepository
import com.axio.reelz.data.repository.StreamRepository
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.screens.downloads.formatSize
import com.axio.reelz.ui.screens.player.PlayerActivity
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

// ── Local icon aliases used in DetailScreen ───────────────────────────────────
private val IconArrowLeft  get() = com.axio.reelz.ui.components.IconSearch.let {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("ArrowLeft", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = PathData { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) },
            stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White),
            strokeLineWidth = 2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent))
    }.build()
}

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

private val IconLock get() = androidx.compose.ui.graphics.vector.ImageVector.Builder("Lock", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(7f, 11f); lineTo(7f, 7f)
        arcTo(5f, 5f, 0f, false, true, 17f, 7f); lineTo(17f, 11f)
        moveTo(5f, 11f); lineTo(19f, 11f)
        arcTo(2f, 2f, 0f, false, true, 21f, 13f); lineTo(21f, 20f)
        arcTo(2f, 2f, 0f, false, true, 19f, 22f); lineTo(5f, 22f)
        arcTo(2f, 2f, 0f, false, true, 3f, 20f); lineTo(3f, 13f)
        arcTo(2f, 2f, 0f, false, true, 5f, 11f)
    }, stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White),
       strokeLineWidth = 1.7f, fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent))
}.build()

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repo: com.axio.reelz.data.repository.CatalogRepository,
    private val libraryRepo: com.axio.reelz.data.repository.LibraryRepository,
    private val downloadRepo: DownloadRepository,
    private val streamRepo: StreamRepository,
    private val adEngine: com.axio.reelz.ads.AdEngine,
    private val configRepo: com.axio.reelz.data.repository.ConfigRepository,
    private val sessionRepo: com.axio.reelz.data.repository.UserRepository,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val extrasLoading: Boolean = false,
        val error: String? = null,
        val detail: MediaDetail? = null,
        val episodes: List<Episode> = emptyList(),
        val selectedSeason: Int = 1,
        val isInWatchlist: Boolean = false,
        val isEpisodesLoading: Boolean = false,
        // Download sheet state
        val showDownloadSheet: Boolean = false,
        val downloadQualities: List<QualityTrack> = emptyList(),
        val alreadyDownloadedQualities: Set<String> = emptySet(),
        val isResolvingQualities: Boolean = false,
        val downloadEnqueued: Boolean = false,
        /**
         * The current tier's max download height in px (e.g. 480 for free, 2160
         * for premium), read once when the sheet opens. <= 0 means "no cap" —
         * Drives the lock badge on any
         * QualityTrack whose parsed height exceeds this.
         */
        /**
         * The resolution cap (px height) the BACKEND reported for this user.
         * 0 = no cap. The app NEVER sets this itself — it comes directly from
         * DownloadLinksResponseDto.maxResolution. Used only for the lock badge.
         */
        val maxDownloadResolutionHeight: Int = 0,
        /**
         * Labels we still plausibly expect to find. Drives skeleton
         * placeholder rows so the sheet shows the shape of the list that's
         * still resolving and rows fill in one at a time as sources
         * respond — no full-sheet spinner.
         *
         * IMPORTANT: this is NOT always the full 5-label ladder. Not every
         * title has all five qualities available (some sources only ever
         * expose 3 or 4), so we cap how many skeleton rows we show and drop
         * them as soon as a source's own ladder tells us they won't appear
         * — see [openDownloadSheetInternal] for how this shrinks as real
         * sources report their actual variant counts.
         */
        // For episode download context
        val pendingDownloadSeason: Int = 0,
        val pendingDownloadEpisode: Int = 0,
        val pendingDownloadTitle: String = "",
        /** True when the user tapped a quality that the backend has locked for their tier. */
        val showResolutionLockSheet: Boolean = false,
        /**
         * Set of keys ("tmdbId_season_episode" or "tmdbId_0_0" for movies)
         * that already have a non-ERROR download. Used to show IconDownloaded
         * instead of IconDownloadCloud on the episode/movie download button.
         */
        val downloadedKeys: Set<String> = emptySet(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var currentMedia: Media? = null

    /**
     * UPGRADE P1: Cached stream result from the first resolve() call in openDownloadSheet().
     * Reused in enqueueDownload() to eliminate the duplicate engine.resolve() call.
     */

    /**
     * Per-label stream mapping (label -> the StreamResult/source that actually
     * produced that quality). Needed because the live/incremental resolver can
     * find different labels from DIFFERENT sources with different headers —
     * using one shared "last found" StreamResult for every download would
     * silently pair the wrong headers with a track's URL for any label that
     * wasn't the most recently discovered one. Cleared/rebuilt each time the
     * sheet opens for a (possibly different) title.
     */

    /** Pre-resolved stream — set in background after detail loads. */
    internal var preResolvedStream: com.axio.reelz.data.model.StreamResult? = null

    /**
     * Pre-parsed quality list from the master playlist.
     * Built in the background right after preResolvedStream is resolved.
     * Makes the download sheet open instantly — zero network call on tap.
     * Key is "tmdbId_season_episode" so episodes don't collide with movies.
     */
    private val preResolvedQualities = HashMap<String, List<QualityTrack>>()

    private fun qualityKey(id: String, season: Int = 0, episode: Int = 0) =
        "${id}_${season}_${episode}"

    /** Observe all non-ERROR downloads and push their keys into UiState so the
     *  episode/movie download button can show IconDownloaded in real time. */
    fun observeDownloads() {
        viewModelScope.launch {
            downloadRepo.observeAll().collect { items ->
                val keys = items
                    .filter { it.status != DownloadStatus.ERROR }
                    .map { "${it.mediaId}_${it.season}_${it.episode}" }
                    .toSet()
                _ui.update { it.copy(downloadedKeys = keys) }
            }
        }
    }

    fun load(id: String, mediaType: MediaType) {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            // Count content opens for frequency cap gate
            adEngine.incrementContentOpen()
            try {
                // Stage 1 — cache-first detail (instant if Room hit, network otherwise)
                val inWatchlist = libraryRepo.isInWatchlist(id)
                val result = repo.getDetail(id)
                val detail = (result as? com.axio.reelz.core.network.NetworkResult.Success)?.data
                    ?: run {
                        val err = (result as? com.axio.reelz.core.network.NetworkResult.Error)?.message ?: "Failed to load"
                        _ui.update { it.copy(isLoading = false, error = err) }
                        return@launch
                    }
                currentMedia = com.axio.reelz.data.model.Media(
                    id = detail.id, title = detail.title,
                    posterUrl = detail.posterUrl, backdropUrl = detail.backdropUrl,
                    releaseYear = detail.releaseYear, rating = detail.rating,
                    mediaType = mediaType,
                )
                _ui.update { it.copy(isLoading = false, detail = detail, isInWatchlist = inWatchlist) }

                if (mediaType == MediaType.TV && detail.seasons.isNotEmpty()) {
                    loadEpisodes(id, 1)
                }

                // Background: pre-resolve stream so download sheet opens instantly
                viewModelScope.launch {
                    try {
                        val key = qualityKey(id)
                        val streamResult = streamRepo.resolveStream(
                            id = id, title = detail.title, mediaType = mediaType
                        )
                        if (streamResult is com.axio.reelz.core.network.NetworkResult.Success) {
                            preResolvedStream = streamResult.data
                            if (preResolvedQualities[key].isNullOrEmpty()) {
                                preResolvedQualities[key] = streamResult.data.qualities.ifEmpty {
                                    listOf(QualityTrack(streamResult.data.quality.ifBlank { "Auto" }, streamResult.data.url))
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = friendlyDetailError(e)) }
            }
        }
    }

    fun selectSeason(id: String, season: Int) {
        _ui.update { it.copy(selectedSeason = season) }
        loadEpisodes(id, season)
    }

    private fun loadEpisodes(id: String, season: Int) {
        viewModelScope.launch {
            _ui.update { it.copy(isEpisodesLoading = true) }
            val result = repo.getSeasonEpisodes(id, season)
            val eps = (result as? com.axio.reelz.core.network.NetworkResult.Success)?.data ?: emptyList()
            _ui.update { it.copy(episodes = eps, isEpisodesLoading = false) }
        }
    }

    fun toggleWatchlist() {
        val m = currentMedia ?: return
        viewModelScope.launch {
            val nowIn = libraryRepo.toggleWatchlist(m)
            _ui.update { it.copy(isInWatchlist = nowIn) }
        }
    }

    // ── Download flow ─────────────────────────────────────────────────────────

    /** Called when user taps the Download button on a movie or episode. */
    fun openDownloadSheet(
        id: String,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        episodeTitle: String = "",
    ) {
        val detail = _ui.value.detail ?: return
        viewModelScope.launch {
            openDownloadSheetInternal(id, mediaType, season, episode, episodeTitle, detail)
        }
    }

    fun dismissResolutionLockSheet() { _ui.update { it.copy(showResolutionLockSheet = false) } }
    fun openResolutionLockSheet() { _ui.update { it.copy(showResolutionLockSheet = true) } }

    private fun openDownloadSheetInternal(
        id: String,
        mediaType: MediaType,
        season: Int,
        episode: Int,
        episodeTitle: String,
        detail: MediaDetail,
    ) {
        _ui.update {
            it.copy(
                showDownloadSheet           = true,
                downloadQualities           = emptyList(),
                alreadyDownloadedQualities  = emptySet(),
                isResolvingQualities        = true,
                downloadEnqueued            = false,
                pendingDownloadSeason       = season,
                pendingDownloadEpisode      = episode,
                pendingDownloadTitle        = episodeTitle.ifBlank { detail.title },
                // maxDownloadResolutionHeight is set once the backend responds
                maxDownloadResolutionHeight = 0,
            )
        }

        // Load already-downloaded qualities for this content in background
        viewModelScope.launch {
            val downloaded = downloadRepo.getDownloadedItems(id, season, episode)
            val qualityLabels = downloaded.map { it.quality }.toSet()
            _ui.update { it.copy(alreadyDownloadedQualities = qualityLabels) }
        }

        val key = qualityKey(id, season, episode)

        // Fast path: use qualities resolved in background when detail loaded
        preResolvedQualities[key]?.let { cached ->
            if (cached.isNotEmpty()) {
                _ui.update { it.copy(downloadQualities = cached) }
                return
            }
        }

        // POST /api/v1/download — let the backend decide what links and caps to send.
        // The app renders whatever comes back; it never infers labels or enforces caps itself.
        viewModelScope.launch {
            val dlResult = streamRepo.getDownloadLinks(
                id = id, title = detail.title, mediaType = mediaType,
                season = season, episode = episode,
            )
            val downloadResult = (dlResult as? com.axio.reelz.core.network.NetworkResult.Success)?.data
            if (downloadResult != null && downloadResult.tracks.isNotEmpty()) {
                preResolvedQualities[key] = downloadResult.tracks
                _ui.update {
                    it.copy(
                        downloadQualities           = downloadResult.tracks,
                        // maxResolution comes from the backend — 0 means no cap
                        maxDownloadResolutionHeight = downloadResult.maxResolution,
                        isResolvingQualities        = false,
                    )
                }
            } else {
                // No download links found — show nothing (or a single fallback if stream is known)
                val fallbackUrl = preResolvedStream?.url ?: ""
                val fallback = if (fallbackUrl.isNotBlank())
                    listOf(QualityTrack("Best available", fallbackUrl))
                else emptyList()
                _ui.update {
                    it.copy(
                        downloadQualities           = fallback,
                        maxDownloadResolutionHeight = 0,
                        isResolvingQualities        = false,
                    )
                }
            }
        }
    }


    fun dismissDownloadSheet() {
        _ui.update { it.copy(showDownloadSheet = false, downloadEnqueued = false) }
    }

    fun enqueueDownload(ctx: android.content.Context, track: QualityTrack) {
        val detail = _ui.value.detail ?: return
        val state  = _ui.value

        // If the backend signalled a resolution cap and the user tapped a locked
        // quality, show the upgrade sheet instead of enqueuing. The backend also
        // enforces this server-side — this is purely a UX guard for instant feedback.
        val maxH   = _ui.value.maxDownloadResolutionHeight
        if (maxH > 0 && trackHeightPx(track.label) > maxH) {
            _ui.update { it.copy(showResolutionLockSheet = true) }
            return
        }

        viewModelScope.launch {
            val headers = preResolvedStream?.headers ?: emptyMap()
            downloadRepo.enqueue(
                ctx         = ctx,
                id          = detail.id,
                title       = state.pendingDownloadTitle,
                posterUrl   = detail.posterUrl,
                mediaType   = detail.mediaType,
                season      = state.pendingDownloadSeason,
                episode     = state.pendingDownloadEpisode,
                episodeName = if (state.pendingDownloadSeason > 0) state.pendingDownloadTitle else "",
                quality     = track.label,
                streamUrl   = track.url,
                headers     = headers,
            )
            _ui.update { it.copy(downloadEnqueued = true) }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────
@Composable
fun DetailScreen(
    id: String,
    mediaType: MediaType,
    nav: NavController,
    adEngine: AdEngine,
    vm: DetailViewModel = hiltViewModel(),
) {
    val d = LocalDimensions.current
    val ui  by vm.ui.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(id) {
        vm.load(id, mediaType)
        vm.observeDownloads()
    }

    fun launchPlayer(season: Int = 0, episode: Int = 0, epName: String = "") {
        val d = ui.detail ?: return

        // Helper so both the ad-dismissed path and the direct path share one call-site
        fun startPlayerActivity() {
            // Use pre-resolved stream if background resolve finished; otherwise
            // PlayerViewModel will call the backend on init (one POST, milliseconds).
            val readyStream = vm.preResolvedStream
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                putExtra("mediaId",    d.id)
                putExtra("mediaType",  d.mediaType.name)
                putExtra("season",     season)
                putExtra("episode",    episode)
                putExtra("title",      if (epName.isNotBlank()) epName else d.title)
                putExtra("posterUrl", d.posterUrl)
                readyStream?.let { stream ->
                    putExtra("streamUrl",     stream.url)
                    putExtra("streamIsHls",   stream.isHls)
                    // referer/origin headers are included in stream.headers map
                }
            })
        }

        adEngine.incrementPlayTap()
        if (adEngine.shouldShowInterstitial()) {
            adEngine.showInterstitial(
                activity    = ctx as android.app.Activity,
                onDismissed = { startPlayerActivity() },
                onFailed    = { startPlayerActivity() },
            )
        } else {
            startPlayerActivity()
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when {
            ui.isLoading -> DetailSkeleton()
            ui.error != null -> ErrorState(ui.error!!, onRetry = { vm.load(id, mediaType) })
            ui.detail != null -> DetailContent(
                ui             = ui,
                extrasLoading  = ui.extrasLoading,
                onBack         = { nav.popBackStack() },
                onPlayMovie    = { launchPlayer() },
                onPlayEpisode  = { s, e, name -> launchPlayer(s, e, name) },
                onSeasonSelect = { vm.selectSeason(id, it) },
                onWatchlist    = { vm.toggleWatchlist() },
                onSimilarClick = { id, type -> nav.navigate(com.axio.reelz.app.Route.Detail.go(id, type)) },
                onDownloadMovie = {
                    vm.openDownloadSheet(id, mediaType)
                },
                onDownloadEpisode = { s, e, name ->
                    vm.openDownloadSheet(id, mediaType, s, e, name)
                },
                downloadedKeys = ui.downloadedKeys,
            )
        }

        // ── Banner ad — visible at bottom of DetailScreen when configured ─
        adEngine.bannerAdUnitIdOrNull()?.let { bannerUnitId ->
            DetailBannerAd(
                adUnitId = bannerUnitId,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }

        // ── Download bottom sheet ──────────────────────────────────────
        if (ui.showDownloadSheet) {
            DownloadQualitySheet(
                title              = ui.pendingDownloadTitle,
                qualities          = ui.downloadQualities,
                isLoading          = ui.isResolvingQualities,
                enqueued           = ui.downloadEnqueued,
                maxResolutionHeight = ui.maxDownloadResolutionHeight,
                alreadyDownloadedQualities = ui.alreadyDownloadedQualities,
                onDismiss          = { vm.dismissDownloadSheet() },
                onSelectQuality    = { track -> vm.enqueueDownload(ctx, track) },
                onLockedQualityTap = { vm.openResolutionLockSheet() },
            )
        }

        // ── Resolution locked sheet (free tier tapped an above-cap quality) ──
        if (ui.showResolutionLockSheet) {
            ResolutionLockSheet(
                onDismiss    = { vm.dismissResolutionLockSheet() },
                onUpgrade    = {
                    vm.dismissResolutionLockSheet()
                    nav.navigate(com.axio.reelz.app.Route.Premium.path)
                },
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
    /**
     * Resolution cap (px height) from the backend. 0 = no cap.
     * Rows above this are shown with a Premium lock badge.
     * The app never computes or hardcodes this value.
     */
    maxResolutionHeight: Int = 0,
    onLockedQualityTap: () -> Unit = {},
    /** Quality labels already downloaded; shown with a ✓ badge. */
    alreadyDownloadedQualities: Set<String> = emptySet(),
) {
    val d = LocalDimensions.current
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
                .clip(RoundedCornerShape(topStart = d.radiusLg + d.spaceXs, topEnd = d.radiusLg + d.spaceXs))
                .background(BgCard)
                .padding(horizontal = d.spaceXl - d.spaceXs, vertical = d.spaceXl - d.spaceXs)
                .clickable(enabled = false) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Handle
            Box(Modifier.width(d.shimmerBarWidth + d.spaceXs).height(d.spaceXs).clip(RoundedCornerShape(d.spaceXxs)).background(White40))
            Spacer(Modifier.height(d.spaceXl - d.spaceXxs))

            // Header row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp),
            ) {
                Icon(IconDownloadCloud, null, tint = Brand, modifier = Modifier.size(d.iconMd - 2.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Download",
                        color = White,
                        fontWeight = FontWeight.Bold,
                        fontSize = d.textXl,
                    )
                    Text(
                        title,
                        color = White60,
                        fontSize = d.textSm,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(IconClose, null, tint = White60, modifier = Modifier.size(d.iconMd))
                }
            }
            Spacer(Modifier.height(d.spaceXl))

            val showList = qualities.isNotEmpty()
            when {
                // ── Success state ──────────────────────────────────────────────
                enqueued -> {
                    Spacer(Modifier.height(d.spaceSm + 1.dp))
                    Box(
                        Modifier.size(d.avatarMd + d.spaceLg - d.spaceXs).clip(CircleShape)
                            .background(Brand.copy(.12f))
                            .border(d.borderThin, Brand.copy(.3f), CircleShape),
                        Alignment.Center,
                    ) {
                        Icon(IconCheckCircle, null, tint = Brand, modifier = Modifier.size(d.iconLg + 4.dp))
                    }
                    Spacer(Modifier.height(d.spaceMd + d.spaceXs))
                    Text("Added to Downloads", color = White, fontWeight = FontWeight.Bold, fontSize = d.textLg)
                    Spacer(Modifier.height(d.spaceXs))
                    Text("You can watch it while it downloads.", color = White60, fontSize = d.textMd)
                    Spacer(Modifier.height(d.spaceXl))
                    BrandButton("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                }

                // ── Loading — waiting for backend response ─────────────────────
                !showList && isLoading -> {
                    Spacer(Modifier.height(d.spaceSm + 1.dp))
                    CinematicSpinner(size = d.spinnerMd + 6.dp)
                    Spacer(Modifier.height(d.spaceMd + d.spaceXs))
                    Text("Fetching available qualities…", color = White60, fontSize = d.textMd)
                    Spacer(Modifier.height(d.spaceLg))
                }

                // ── No streams ─────────────────────────────────────────────────
                !showList -> {
                    Spacer(Modifier.height(d.spaceSm + 1.dp))
                    Icon(IconError, null, tint = White40, modifier = Modifier.size(d.spinnerMd + 6.dp))
                    Spacer(Modifier.height(d.spaceMd))
                    Text("No downloadable streams found", color = White60, fontSize = d.textMd)
                    Spacer(Modifier.height(d.spaceLg))
                }

                // ── Quality list — rendered exactly as the backend sent it ─────────
                else -> {
                    Text(
                        "Choose quality",
                        color = White60,
                        fontSize = d.textSm,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(d.spaceMd + d.spaceXs))

                    qualities.forEachIndexed { index, track ->
                        // Lock badge: only shown when backend reported a cap (maxResolutionHeight > 0)
                        // and this track's height exceeds it. The app never decides the cap itself.
                        val isLocked      = maxResolutionHeight > 0 && trackHeightPx(track.label) > maxResolutionHeight
                        val isDownloaded  = alreadyDownloadedQualities.contains(track.label)

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(d.radiusLg - d.spaceXs))
                                .background(
                                    when {
                                        isDownloaded -> Success.copy(.08f)
                                        isLocked     -> GlassSm
                                        else         -> BgRaised
                                    }
                                )
                                .border(
                                    d.borderThin,
                                    if (isDownloaded) Success.copy(.25f) else GlassBorderMd,
                                    RoundedCornerShape(d.radiusLg - d.spaceXs),
                                )
                                .clickable(enabled = !isDownloaded) {
                                    if (isLocked) onLockedQualityTap() else onSelectQuality(track)
                                }
                                .padding(horizontal = d.spaceLg, vertical = d.spaceLg),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceLg),
                        ) {
                            // Exact resolution label from the backend — no rewriting
                            Text(
                                track.label,
                                color = when {
                                    isDownloaded -> Success.copy(.8f)
                                    isLocked     -> White40
                                    else         -> White
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = d.textLg,
                                modifier = Modifier.weight(1f),
                            )

                            // Right side: Downloaded badge, file size OR premium lock badge
                            when {
                                isDownloaded -> {
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(d.spaceXs))
                                            .background(Success.copy(.15f))
                                            .border(d.borderThin, Success.copy(.35f), RoundedCornerShape(d.spaceXs))
                                            .padding(horizontal = d.spaceSm, vertical = d.spaceXxs),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(d.spaceXxs),
                                    ) {
                                        Text("✓", color = Success, fontSize = d.textXxs, fontWeight = FontWeight.ExtraBold)
                                        Text(
                                            "Downloaded",
                                            color = Success,
                                            fontSize = d.textXxs,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.3.sp,
                                        )
                                    }
                                }
                                isLocked -> {
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(d.spaceXs))
                                            .background(Brand.copy(.15f))
                                            .border(d.borderThin, Brand.copy(.35f), RoundedCornerShape(d.spaceXs))
                                            .padding(horizontal = d.spaceSm, vertical = d.spaceXxs),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(d.spaceXxs),
                                    ) {
                                        Icon(IconLock, null, tint = Brand, modifier = Modifier.size(d.iconXs - 1.dp))
                                        Text(
                                            "PREMIUM",
                                            color = Brand,
                                            fontSize = d.textXxs,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 0.5.sp,
                                        )
                                    }
                                }
                                track.estimatedSizeBytes > 0 -> {
                                    Text(
                                        "~${formatSize(track.estimatedSizeBytes)}",
                                        color = White60,
                                        fontSize = d.textSm,
                                    )
                                }
                            }

                            Icon(
                                when {
                                    isDownloaded -> IconCheckCircle
                                    isLocked     -> IconLock
                                    else         -> IconDownloadCloud
                                },
                                null,
                                tint = when {
                                    isDownloaded -> Success.copy(.7f)
                                    isLocked     -> White40
                                    else         -> White60
                                },
                                modifier = Modifier.size(d.iconMd),
                            )
                        }

                        if (index < qualities.lastIndex) Spacer(Modifier.height(d.spaceMd))
                    }

                }
            }

            Spacer(Modifier.height(d.spaceMd + d.spaceXs))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}



// ── Resolution locked sheet (free tier tapped an above-cap quality) ─────────
@Composable
fun ResolutionLockSheet(
    onDismiss: () -> Unit,
    onUpgrade: () -> Unit,
) {
    val d = LocalDimensions.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(.6f))
            .clickable { onDismiss() },
    )

    Box(Modifier.fillMaxSize(), Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = d.radiusLg + d.spaceXs, topEnd = d.radiusLg + d.spaceXs))
                .background(BgCard)
                .padding(horizontal = d.spaceXl - d.spaceXs, vertical = d.spaceXl - d.spaceXs)
                .clickable(enabled = false) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.width(d.shimmerBarWidth + d.spaceXs).height(d.spaceXs).clip(RoundedCornerShape(d.spaceXxs)).background(White40))
            Spacer(Modifier.height(d.spaceXl - d.spaceXxs))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp),
            ) {
                Box(
                    Modifier.size(d.buttonHeightMd - d.spaceXxs).clip(CircleShape).background(Brand.copy(.15f)),
                    Alignment.Center,
                ) { Icon(IconLock, null, tint = Brand, modifier = Modifier.size(d.iconMd - 2.dp)) }
                Column(Modifier.weight(1f)) {
                    Text("Higher quality is Premium", color = White, fontWeight = FontWeight.Bold, fontSize = d.textXl)
                    Text("Upgrade to Premium for higher quality downloads", color = White60, fontSize = d.textSm)
                }
                IconButton(onClick = onDismiss) {
                    Icon(IconClose, null, tint = White60, modifier = Modifier.size(d.iconMd))
                }
            }
            Spacer(Modifier.height(d.spaceXl))

            Text(
                "Upgrade to Premium to download in up to 4K, with no resolution limits and no ads.",
                color      = White60,
                fontSize   = d.textMd,
                textAlign  = TextAlign.Center,
                lineHeight = (d.textMd.value * 1.45f).sp,
            )
            Spacer(Modifier.height(d.spaceXl))

            BrandButton("Upgrade to Premium", onClick = onUpgrade, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(d.spaceMd))
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = White60, fontSize = d.textMd)
            }
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
    onSimilarClick: (String, MediaType) -> Unit,
    onDownloadMovie: () -> Unit,
    onDownloadEpisode: (Int, Int, String) -> Unit,
    downloadedKeys: Set<String> = emptySet(),
) {
    val d = LocalDimensions.current
    val detail  = ui.detail!!
    val isMovie = detail.mediaType == MediaType.MOVIE
    val screenH = LocalConfiguration.current.screenHeightDp.dp

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = d.spaceXxl * 2.8f)) {

        // ── Backdrop hero ──────────────────────────────────────────────────
        item {
            Box(Modifier.fillMaxWidth().height(screenH * 0.46f)) {
                AsyncImage(
                    model = detail.backdropUrl,
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
                    modifier = Modifier.statusBarsPadding().padding(d.spaceSm)
                        .clip(CircleShape).background(Color.Black.copy(.5f))
                ) { Icon(IconArrowLeft, null, tint = White) }

                // Poster + meta
                Column(Modifier.align(Alignment.BottomStart).padding(d.heroPadding - d.spaceXs)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(d.spaceMd + d.spaceXs)) {
                        AsyncImage(
                            model = detail.posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(d.cardPosterWidth - d.spaceXs).height(d.cardPosterHeight - d.spaceXxl)
                                .clip(RoundedCornerShape(d.radiusMd)).background(BgRaised),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(d.spaceXs)) {
                            Text(detail.title, color = White, fontWeight = FontWeight.Black, fontSize = d.textXxl, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!detail.tagline.isNullOrBlank())
                                Text(detail.tagline, color = White60, fontSize = d.textSm, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm), verticalAlignment = Alignment.CenterVertically) {
                                RatingChip(detail.rating)
                                Text("•", color = White40)
                                Text(detail.releaseYear?.take(4) ?: "", color = White60, fontSize = d.textMd)
                                if (detail.runtime != null) { Text("•", color = White40); Text(formatRuntime(detail.runtime), color = White60, fontSize = d.textMd) }
                                if (!isMovie) { Text("•", color = White40); Text("${detail.seasons.size}S", color = White60, fontSize = d.textMd) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                                detail.genres.take(3).forEach { g ->
                                    Box(
                                        Modifier.clip(RoundedCornerShape(d.radiusSm)).background(BgSurface)
                                            .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusSm))
                                            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceXxs + 1.dp)
                                    ) { Text(g, color = White60, fontSize = d.textXs) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Action row ─────────────────────────────────────────────────────
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad + d.spaceXs, vertical = d.spaceMd + d.spaceXs), horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXs)) {
                if (isMovie) {
                    BrandButton(
                        text     = "Watch Now",
                        onClick  = onPlayMovie,
                        modifier = Modifier.weight(1f),
                        icon     = { Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(d.iconMd)) },
                    )
                    // ── Download button (movies only) ──────────────────────
                    val movieDownloaded = "${detail.id}_0_0" in downloadedKeys
                    OutlinedButton(
                        onClick  = if (movieDownloaded) ({}) else onDownloadMovie,
                        shape    = RoundedCornerShape(d.radiusPill),
                        border   = BorderStroke(d.borderThin, if (movieDownloaded) Color(0xFF30D158).copy(.5f) else GlassBorderMd),
                        modifier = Modifier.height(d.buttonHeightMd),
                        enabled  = !movieDownloaded,
                    ) {
                        Icon(
                            if (movieDownloaded) IconDownloaded else IconDownloadCloud,
                            contentDescription = if (movieDownloaded) "Offline" else "Download",
                            tint = if (movieDownloaded) Color(0xFF30D158) else White80,
                            modifier = Modifier.size(d.iconMd - 2.dp),
                        )
                    }
                }
                // Watchlist button — simple, no animation state inside lambda
                OutlinedButton(
                    onClick  = onWatchlist,
                    shape    = RoundedCornerShape(d.radiusPill),
                    border   = BorderStroke(
                        d.borderThin,
                        if (ui.isInWatchlist) Brand.copy(.7f) else GlassBorderMd,
                    ),
                    modifier = if (isMovie)
                        Modifier.height(d.buttonHeightMd)
                    else
                        Modifier.height(d.buttonHeightMd).weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (ui.isInWatchlist) Brand.copy(.12f) else Color.Transparent,
                    ),
                ) {
                    Icon(
                        if (ui.isInWatchlist) IconBookmarkFill else IconBookmarkOutline,
                        null,
                        tint = if (ui.isInWatchlist) Brand else White60,
                        modifier = Modifier.size(d.iconMd - 2.dp),
                    )
                    Spacer(Modifier.width(d.spaceXs + 1.dp))
                    Text(
                        if (ui.isInWatchlist) "Saved ✓" else "Save",
                        color      = if (ui.isInWatchlist) Brand else White60,
                        fontWeight = if (ui.isInWatchlist) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        // ── Overview ───────────────────────────────────────────────────────
        item {
            var expanded by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = d.screenHorizPad + d.spaceXs, vertical = d.spaceXs)) {
                Text("Overview", color = White, fontWeight = FontWeight.Bold, fontSize = d.textLg)
                Spacer(Modifier.height(d.spaceSm))
                Text(
                    detail.overview,
                    color = White60,
                    fontSize = d.textMd,
                    lineHeight = (d.textMd.value * 1.55f).sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail.overview.length > 150) {
                    Text(
                        if (expanded) "Show less" else "Show more",
                        color = Brand,
                        fontSize = d.textSm,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(top = d.spaceXs),
                    )
                }
            }
        }

        // ── Movie metadata ─────────────────────────────────────────────────
        if (isMovie && (detail.runtime != null || detail.status != null)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad + d.spaceXs, vertical = d.spaceSm + d.spaceXxs),
                    horizontalArrangement = Arrangement.spacedBy(d.spaceLg),
                ) {
                    detail.runtime?.let { MetaChip("Runtime", formatRuntime(it)) }
                    detail.status?.let   { MetaChip("Status", it) }
                    // voteCount not available in current model
                }
            }
        }

        // ── TV: Season selector + episodes ─────────────────────────────────
        if (!isMovie && detail.seasons.isNotEmpty()) {
            item {
                SectionHeader("Episodes")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = d.screenHorizPad + d.spaceXs),
                    horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp),
                ) {
                    items(detail.seasons) { s ->
                        val sel = ui.selectedSeason == s.seasonNumber
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(d.radiusPill))
                                .background(if (sel) Brand else GlassMd)
                                .border(d.borderThin, if (sel) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(d.radiusPill))
                                .clickable { onSeasonSelect(s.seasonNumber) }
                                .padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
                        ) {
                            Text(
                                "S${s.seasonNumber}",
                                color = if (sel) Color.White else White60,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                fontSize = d.textMd,
                            )
                        }
                    }
                }
            }

            if (ui.isEpisodesLoading) {
                item { Box(Modifier.fillMaxWidth().height(d.spaceXxl * 3.75f), Alignment.Center) { CinematicSpinner() } }
            } else {
                items(ui.episodes, key = { it.id }) { ep ->
                    val epKey = "${detail.id}_${ep.seasonNumber}_${ep.episodeNumber}"
                    EpisodeRow(
                        episode      = ep,
                        onClick      = { onPlayEpisode(ep.seasonNumber, ep.episodeNumber, ep.name) },
                        onDownload   = { onDownloadEpisode(ep.seasonNumber, ep.episodeNumber, ep.name) },
                        isDownloaded = epKey in downloadedKeys,
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
                    contentPadding = PaddingValues(horizontal = d.screenHorizPad + d.spaceXs),
                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd + d.spaceXs),
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
            // Psychology: "More Like This" + genre context = personal curation feel
            item {
                PersonalizedSectionHeader(
                    title       = "More Like This",
                    subtitle    = detail.genres.firstOrNull()?.let { "Based on your interest in $it" },
                    icon        = "✦",
                    accentColor = Brand,
                    action      = "See All",
                    onAction    = {},
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = d.screenHorizPad + d.spaceXs),
                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXs),
                ) {
                    items(detail.similar, key = { it.id }) { m ->
                        com.axio.reelz.ui.components.MediaRowCard(m, onClick = { onSimilarClick(m.id, m.mediaType) })
                    }
                }
            }
        }

        // ── End-of-Detail emotional connection ────────────────────────────
        // Psychology: Every detail screen ends with a warm invitation, not a dead end.
        // This small touch makes Reelz feel like it *wants* you to find something great.
        // "Reelz will find something for you" = the killer experience.
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = d.screenHorizPad + d.spaceXs)
                    .padding(top = d.spaceXxl, bottom = d.spaceLg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = GlassBorderMd,
                    modifier = Modifier.padding(bottom = d.spaceXl),
                )
                Text(
                    "🎬",
                    fontSize = (d.textXxl.value + 4f).sp,
                )
                Spacer(Modifier.height(d.spaceMd))
                Text(
                    "Not sure what to watch next?",
                    color      = White60,
                    fontSize   = d.textMd,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Reelz will find something for you.",
                    color      = Brand.copy(.85f),
                    fontSize   = d.textSm,
                )
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
    isDownloaded: Boolean = false,
) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = d.screenHorizPad + d.spaceXs, vertical = d.spaceSm + d.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd + d.spaceXs),
    ) {
        // Thumbnail — taller so episode stills are clearly visible
        Box(
            Modifier
                .width(d.avatarLg + d.spaceXxl)
                .height(d.continueCardThumbHeight)
                .clip(RoundedCornerShape(d.radiusMd))
                .background(BgRaised),
        ) {
            if (episode.stillUrl != null) {
                AsyncImage(
                    model = episode.stillUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Fallback gradient when no still is available
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(BgRaised, BgCard))
                    ),
                    Alignment.Center,
                ) {
                    Icon(IconMovieSlate, null, tint = White20, modifier = Modifier.size(d.iconLg))
                }
            }
            // Play overlay
            Box(Modifier.fillMaxSize().background(Color.Black.copy(.28f)), Alignment.Center) {
                Icon(IconPlayCircle, null, tint = White.copy(.85f), modifier = Modifier.size(d.iconLg))
            }
            // "Offline" badge when already downloaded
            if (isDownloaded) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(d.spaceXxs + 1.dp)
                        .clip(RoundedCornerShape(d.radiusSm))
                        .background(Color(0xFF30D158).copy(.9f))
                        .padding(horizontal = d.spaceXs, vertical = d.spaceXxs),
                ) {
                    Text("Offline", color = Color.White, fontSize = (d.textXxs.value - 0.5f).sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text("E${episode.episodeNumber} · ${episode.name}", color = White, fontSize = d.textMd, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(d.spaceXxs + 1.dp))
            Text(episode.overview.ifBlank { "No description." }, color = White60, fontSize = d.textXs, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (d.textXs.value * 1.45f).sp)
            episode.runtime?.let {
                Spacer(Modifier.height(d.spaceXxs + 1.dp))
                Text("${it}m", color = White40, fontSize = d.textXxs)
            }
        }
        // Download icon: shows IconDownloaded (green) if owned, otherwise normal cloud icon
        IconButton(
            onClick = if (isDownloaded) ({}) else onDownload,
            modifier = Modifier.size(d.buttonHeightSm),
            enabled = !isDownloaded,
        ) {
            Icon(
                if (isDownloaded) IconDownloaded else IconDownloadCloud,
                contentDescription = if (isDownloaded) "Offline" else "Download",
                tint = if (isDownloaded) Color(0xFF30D158) else White60,
                modifier = Modifier.size(d.iconMd - 2.dp),
            )
        }
        Icon(IconPlay, null, tint = Brand, modifier = Modifier.size(d.iconMd))
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color     = GlassBorder,
        modifier  = Modifier.padding(horizontal = d.screenHorizPad + d.spaceXs),
    )
}

// ── Cast card ─────────────────────────────────────────────────────────────────
@Composable
fun CastCard(cast: CastMember) {
    val d = LocalDimensions.current
    Column(Modifier.width(d.avatarLg + d.spaceLg - d.spaceXxs), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(d.avatarLg).clip(CircleShape).background(BgRaised)) {
            if (cast.photoUrl != null) {
                AsyncImage(
                    model = cast.photoUrl,
                    contentDescription = cast.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(IconUser, null, tint = White40, modifier = Modifier.fillMaxSize().padding(d.spaceMd))
            }
        }
        Spacer(Modifier.height(d.spaceXs + 1.dp))
        Text(cast.name, color = White80, fontSize = (d.textXxs.value + 1).sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = ((d.textXxs.value + 1) * 1.3f).sp)
        Text(cast.character, color = White40, fontSize = d.textXxs, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

// ── Meta chip ─────────────────────────────────────────────────────────────────
@Composable
fun MetaChip(label: String, value: String) {
    val d = LocalDimensions.current
    Column {
        Text(label, color = White40, fontSize = d.textXxs)
        Text(value, color = White80, fontSize = d.textMd, fontWeight = FontWeight.SemiBold)
    }
}

fun formatRuntime(minutes: Int): String {
    val h = minutes / 60; val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/**
 * Translates raw exceptions into user-facing messages for the Detail screen.
 * Never exposes internal exception class names or raw stack details.
 */
private fun friendlyDetailError(e: Exception): String {
    val msg = e.message?.lowercase() ?: ""
    return when {
        msg.contains("unable to resolve host") ||
        msg.contains("no route to host") ||
        msg.contains("network") ||
        msg.contains("timeout") ||
        msg.contains("connect") -> "No internet connection. Check your connection and try again."
        msg.contains("404") ||
        msg.contains("not found") -> "This title couldn't be found. It may have been removed."
        msg.contains("401") ||
        msg.contains("403") ||
        msg.contains("unauthorized") -> "Access denied. Please try again later."
        msg.contains("500") ||
        msg.contains("502") ||
        msg.contains("503") -> "The server is temporarily unavailable. Try again in a moment."
        else -> "Something went wrong loading this title. Pull down to retry."
    }
}

/**
 * Parses a QualityTrack.label (e.g. "1080p", "720p", "Auto", "Best available")
 * into a comparable pixel height for resolution-cap checks. "Auto" and
 * "Best available" return Int.MAX_VALUE — they represent the source's true top
 * quality, which is exactly what the free tier must NOT be allowed to silently
 * download, so they are treated as the highest tier rather than unlocked.
 */
fun trackHeightPx(label: String): Int =
    label.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

/** Human label for a tier's cap height, used in lock-sheet copy ("Free plan streams up to 480p"). */

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
    val d = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    Column(Modifier.fillMaxSize().background(Bg)) {
        // Backdrop placeholder
        ShimmerBox(
            Modifier.fillMaxWidth().height(screenH * 0.46f),
            radius = 0.dp,
        )
        Spacer(Modifier.height(d.spaceMd + d.spaceXs))
        Column(Modifier.padding(horizontal = d.screenHorizPad + d.spaceXs), verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXs)) {
            // Title
            ShimmerBox(Modifier.fillMaxWidth(0.7f).height(d.spaceXl + 4.dp), radius = d.radiusSm)
            // Subtitle line
            ShimmerBox(Modifier.fillMaxWidth(0.45f).height(d.spaceLg - d.spaceXxs), radius = d.radiusSm)
            Spacer(Modifier.height(d.spaceXs))
            // Genre pills
            Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                ShimmerBox(Modifier.width(d.avatarMd + d.spaceLg - d.spaceXs).height(d.spaceXl), radius = d.radiusMd - d.spaceXxs)
                ShimmerBox(Modifier.width(d.avatarMd + d.spaceLg - d.spaceXs).height(d.spaceXl), radius = d.radiusMd - d.spaceXxs)
                ShimmerBox(Modifier.width(d.avatarMd + d.spaceLg - d.spaceXs).height(d.spaceXl), radius = d.radiusMd - d.spaceXxs)
            }
            Spacer(Modifier.height(d.spaceXs))
            // Play button
            ShimmerBox(Modifier.fillMaxWidth().height(d.buttonHeightMd), radius = d.radiusMd - d.spaceXs)
            Spacer(Modifier.height(d.spaceXs))
            // Overview lines
            ShimmerBox(Modifier.fillMaxWidth().height(d.spaceMd), radius = d.radiusSm)
            ShimmerBox(Modifier.fillMaxWidth().height(d.spaceMd), radius = d.radiusSm)
            ShimmerBox(Modifier.fillMaxWidth(0.6f).height(d.spaceMd), radius = d.radiusSm)
        }
    }
}

/** Skeleton row for the Cast section while extras are loading. */
@Composable
fun CastRowSkeleton() {
    val d = LocalDimensions.current
    Row(
        Modifier.padding(horizontal = d.screenHorizPad + d.spaceXs),
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd + d.spaceXs),
    ) {
        repeat(5) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(d.spaceSm),
            ) {
                ShimmerBox(Modifier.size(d.avatarLg), radius = d.avatarLg / 2)
                ShimmerBox(Modifier.width(d.avatarMd + d.spaceLg - d.spaceXs - d.spaceXxs).height(d.spaceSm))
                ShimmerBox(Modifier.width(d.avatarMd - d.spaceXxs).height(d.spaceXs))
            }
        }
    }
}

/** Skeleton row for the Similar / More Like This section while extras are loading. */
@Composable
fun MediaRowSkeleton() {
    val d = LocalDimensions.current
    Row(
        Modifier.padding(horizontal = d.screenHorizPad + d.spaceXs),
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXs),
    ) {
        repeat(4) {
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                ShimmerBox(Modifier.width(d.cardRowWidth).height(d.cardRowHeight), radius = d.radiusMd - d.spaceXs)
                ShimmerBox(Modifier.width(d.cardRowWidth - d.spaceMd).height(d.spaceSm + 1.dp))
                ShimmerBox(Modifier.width(d.cardRowWidth - d.spaceXl - d.spaceXs).height(d.spaceXs))
            }
        }
    }
}
