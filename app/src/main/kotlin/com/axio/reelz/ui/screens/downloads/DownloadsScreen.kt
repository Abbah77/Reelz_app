package com.axio.reelz.ui.screens.downloads

import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.axio.reelz.data.local.DownloadDao
import com.axio.reelz.data.model.*
import com.axio.reelz.data.repository.DownloadRepository
import com.axio.reelz.service.DownloadService
import com.axio.reelz.ui.Route
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.screens.player.PlayerActivity
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Data structures
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Groups all DownloadItems for the same movie (same mediaId) regardless of quality.
 * A movie card shows ONE entry in the list even if 1080p + 480p are both downloaded.
 */
data class MovieGroup(
    val mediaId: String,
    val title: String,
    val posterPath: String?,
    /** All downloads for this movie across every quality. */
    val downloads: List<DownloadItem>,
) {
    val doneDownloads: List<DownloadItem> get() = downloads.filter { it.status == DownloadStatus.DONE.name }
    val totalSize: Long get() = doneDownloads.sumOf { it.sizeBytes }
    /** The most recently watched/played item, or the highest quality done item. */
    val primaryDownload: DownloadItem get() =
        doneDownloads.maxByOrNull { it.lastPlayedAt }
            ?: doneDownloads.maxByOrNull { it.sizeBytes }
            ?: downloads.first()
    val watchProgressMs: Long get() = primaryDownload.watchProgressMs
    val durationMs: Long get() = primaryDownload.durationMs
    val lastPlayedAt: Long get() = primaryDownload.lastPlayedAt
    val completedAt: Long get() = doneDownloads.maxOfOrNull { it.completedAt } ?: 0L
    val hasMultipleQualities: Boolean get() = doneDownloads.size > 1
}

data class SeriesGroup(
    val mediaId: String,
    val title: String,
    val posterPath: String?,
    val seasons: List<SeasonGroup>,
) {
    val totalEpisodes: Int get() = seasons.sumOf { it.episodeGroups.size }
    val doneEpisodes: Int get() = seasons.sumOf { s -> s.episodeGroups.count { it.doneDownloads.isNotEmpty() } }
    val isFullyDownloaded: Boolean get() = totalEpisodes > 0 && doneEpisodes == totalEpisodes
    val isAnyActive: Boolean get() = seasons.any { s ->
        s.episodeGroups.any { eg ->
            eg.downloads.any {
                it.status == DownloadStatus.DOWNLOADING.name || it.status == DownloadStatus.QUEUED.name
            }
        }
    }
    val lastWatchedLabel: String? get() {
        val lastPlayed = seasons
            .flatMap { it.episodeGroups }
            .flatMap { it.downloads }
            .filter { it.lastPlayedAt > 0 }
            .maxByOrNull { it.lastPlayedAt }
        return lastPlayed?.let { "S%02dE%02d".format(it.season, it.episode) }
    }
    val seasonCount: Int get() = seasons.size
}

data class SeasonGroup(
    val season: Int,
    val episodeGroups: List<EpisodeGroup>,
) {
    val doneCount: Int get() = episodeGroups.count { it.doneDownloads.isNotEmpty() }
    val totalSize: Long get() = episodeGroups.sumOf { eg -> eg.doneDownloads.sumOf { it.sizeBytes } }
}

/**
 * Groups all DownloadItems for ONE episode across all qualities.
 * E.g. S01E01 might have both 1080p and 480p.
 */
data class EpisodeGroup(
    val mediaId: String,
    val season: Int,
    val episode: Int,
    val episodeName: String,
    val posterPath: String?,
    val downloads: List<DownloadItem>,
) {
    val doneDownloads: List<DownloadItem> get() = downloads.filter { it.status == DownloadStatus.DONE.name }
    val primaryDownload: DownloadItem get() =
        doneDownloads.maxByOrNull { it.lastPlayedAt }
            ?: doneDownloads.maxByOrNull { it.sizeBytes }
            ?: downloads.first()
    val watchProgressMs: Long get() = primaryDownload.watchProgressMs
    val durationMs: Long get() = primaryDownload.durationMs
    val lastPlayedAt: Long get() = primaryDownload.lastPlayedAt
    val hasMultipleQualities: Boolean get() = doneDownloads.size > 1
    val totalSize: Long get() = doneDownloads.sumOf { it.sizeBytes }
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val dao: DownloadDao,
    private val repo: DownloadRepository,
) : ViewModel() {

    private val allDownloads: StateFlow<List<DownloadItem>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Completed movies grouped by tmdbId so multi-resolution shows as one card. */
    val movieGroups: StateFlow<List<MovieGroup>> = allDownloads
        .map { list ->
            list.filter { it.mediaType == "MOVIE" && it.status == DownloadStatus.DONE.name }
                .groupBy { it.mediaId }
                .map { (mediaId, items) ->
                    MovieGroup(
                        mediaId     = mediaId,
                        title       = items.first().title,
                        posterUrl   = items.first().posterUrl,
                        downloads   = items.sortedByDescending { it.sizeBytes },
                    )
                }
                .sortedByDescending { it.completedAt }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val seriesGroups: StateFlow<List<SeriesGroup>> = allDownloads
        .map { list -> buildSeriesGroups(list.filter { it.mediaType == "TV" }) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Active = downloading, queued, paused, or error (not done). */
    val activeDownloads: StateFlow<List<DownloadItem>> = allDownloads
        .map { list ->
            list.filter {
                it.status == DownloadStatus.DOWNLOADING.name
                    || it.status == DownloadStatus.QUEUED.name
                    || it.status == DownloadStatus.PAUSED.name
                    || it.status == DownloadStatus.ERROR.name
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val readyCount: StateFlow<Int> = allDownloads
        .map { list -> list.count { it.status == DownloadStatus.DONE.name } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private fun buildSeriesGroups(items: List<DownloadItem>): List<SeriesGroup> =
        items.groupBy { it.mediaId }
            .map { (tmdbId, eps) ->
                val seasons = eps
                    .groupBy { it.season }
                    .map { (season, seasonEps) ->
                        val episodeGroups = seasonEps
                            .groupBy { it.episode }
                            .map { (epNum, epItems) ->
                                EpisodeGroup(
                                    mediaId     = mediaId,
                                    season      = season,
                                    episode     = epNum,
                                    episodeName = epItems.firstOrNull()?.episodeName ?: "",
                                    posterUrl   = epItems.firstOrNull()?.posterUrl,
                                    downloads   = epItems.sortedByDescending { it.sizeBytes },
                                )
                            }
                            .sortedBy { it.episode }
                        SeasonGroup(season, episodeGroups)
                    }
                    .sortedBy { it.season }
                SeriesGroup(mediaId, eps.first().title, eps.first().posterUrl, seasons)
            }
            .sortedByDescending { g ->
                g.seasons.flatMap { it.episodeGroups }.flatMap { it.downloads }.maxOf { it.createdAt }
            }

    fun delete(item: DownloadItem, ctx: Context) { viewModelScope.launch { repo.delete(ctx, item) } }
    fun deleteItems(items: List<DownloadItem>, ctx: Context) { viewModelScope.launch { items.forEach { repo.delete(ctx, it) } } }
    fun resume(ctx: Context, item: DownloadItem) { viewModelScope.launch { repo.resume(ctx, item) } }
    fun pause(ctx: Context, item: DownloadItem)  { viewModelScope.launch { repo.pause(ctx, item) } }

    fun deleteMovieGroup(group: MovieGroup, ctx: Context) {
        viewModelScope.launch { group.downloads.forEach { repo.delete(ctx, it) } }
    }
    fun deleteSeries(group: SeriesGroup, ctx: Context) {
        viewModelScope.launch {
            group.seasons.flatMap { it.episodeGroups }.flatMap { it.downloads }.forEach { repo.delete(ctx, it) }
        }
    }
    fun deleteSeason(season: SeasonGroup, ctx: Context) {
        viewModelScope.launch {
            season.episodeGroups.flatMap { it.downloads }.forEach { repo.delete(ctx, it) }
        }
    }
    fun deleteEpisodeGroup(eg: EpisodeGroup, ctx: Context) {
        viewModelScope.launch { eg.downloads.forEach { repo.delete(ctx, it) } }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DownloadsScreen(nav: NavController, vm: DownloadsViewModel = hiltViewModel()) {
    val d               = LocalDimensions.current
    val ctx             = LocalContext.current
    val movieGroups     by vm.movieGroups.collectAsState()
    val seriesGroups    by vm.seriesGroups.collectAsState()
    val activeDownloads by vm.activeDownloads.collectAsState()
    val readyCount      by vm.readyCount.collectAsState()
    var tab             by remember { mutableStateOf(0) }

    val showMovies = tab == 0 || tab == 1
    val showSeries = tab == 0 || tab == 2
    val isEmpty    = movieGroups.isEmpty() && seriesGroups.isEmpty() && activeDownloads.isEmpty()

    // Navigation state: which series expanded, which season selected
    var seriesDetailGroup by remember { mutableStateOf<SeriesGroup?>(null) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (seriesDetailGroup != null) {
            // ── Series Detail Page ─────────────────────────────────────────────
            SeriesDetailPage(
                group    = seriesDetailGroup!!,
                vm       = vm,
                ctx      = ctx,
                onBack   = { seriesDetailGroup = null },
            )
        } else {
            // ── Root Downloads Page ────────────────────────────────────────────
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(bottom = d.spaceXxl * 3),
            ) {
                item {
                    DownloadsHeader(
                        readyCount  = readyCount,
                        activeCount = activeDownloads.size,
                        onTransfer  = { nav.navigate(Route.Transfer.path) },
                    )
                }

                // ── Active Queue Strip (horizontal scroll, "View All" button)
                if (activeDownloads.isNotEmpty()) {
                    item {
                        ActiveQueueStrip(
                            items    = activeDownloads,
                            ctx      = ctx,
                            vm       = vm,
                            onViewAll = {
                                // Navigate to full active downloads page
                                nav.navigate("downloads_active")
                            },
                        )
                    }
                }

                // ── Tab filter: All / Movies / Series
                item {
                    TabFilterBar(
                        selected    = tab,
                        movieCount  = movieGroups.size,
                        seriesCount = seriesGroups.size,
                        onSelect    = { tab = it },
                    )
                }

                if (isEmpty) { item { EmptyDownloadsState() } }

                // ── Movies ──────────────────────────────────────────────────────
                if (showMovies && movieGroups.isNotEmpty()) {
                    item {
                        SectionLabel(
                            "Movies",
                            "${movieGroups.size} title${if (movieGroups.size > 1) "s" else ""}",
                            modifier = Modifier.padding(horizontal = d.screenHorizPad, vertical = d.spaceSm),
                        )
                    }
                    items(movieGroups, key = { "mg-${it.mediaId}" }) { group ->
                        MovieGroupCard(
                            group    = group,
                            onPlay   = { item -> playDownload(ctx, item) },
                            onDelete = { vm.deleteMovieGroup(group, ctx) },
                            onDeleteQuality = { item -> vm.delete(item, ctx) },
                            modifier = Modifier.padding(horizontal = d.screenHorizPad).padding(bottom = d.spaceSm + d.spaceXxs),
                        )
                    }
                }

                // ── Series ──────────────────────────────────────────────────────
                if (showSeries && seriesGroups.isNotEmpty()) {
                    item {
                        SectionLabel(
                            "TV Shows",
                            "${seriesGroups.size} series",
                            modifier = Modifier.padding(horizontal = d.screenHorizPad, vertical = d.spaceSm),
                        )
                    }
                    items(seriesGroups, key = { "sg-${it.mediaId}" }) { group ->
                        SeriesRootCard(
                            group    = group,
                            onTap    = { seriesDetailGroup = group },
                            onPlay   = {
                                // Resume last watched episode, or first episode of first season
                                val lastEp = group.seasons
                                    .flatMap { it.episodeGroups }
                                    .flatMap { it.downloads }
                                    .filter { it.status == DownloadStatus.DONE.name && it.lastPlayedAt > 0 }
                                    .maxByOrNull { it.lastPlayedAt }
                                val firstEp = group.seasons
                                    .firstOrNull()?.episodeGroups?.firstOrNull()
                                    ?.doneDownloads?.firstOrNull()
                                val toPlay = lastEp ?: firstEp
                                if (toPlay != null) playDownload(ctx, toPlay)
                            },
                            onDelete = { vm.deleteSeries(group, ctx) },
                            modifier = Modifier.padding(horizontal = d.screenHorizPad).padding(bottom = d.spaceSm + d.spaceXxs),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Series Detail Page (replaces the current screen)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SeriesDetailPage(
    group: SeriesGroup,
    vm: DownloadsViewModel,
    ctx: Context,
    onBack: () -> Unit,
) {
    val d = LocalDimensions.current
    var selectedSeason by remember { mutableStateOf(group.seasons.firstOrNull()?.season ?: 1) }
    val currentSeason = group.seasons.firstOrNull { it.season == selectedSeason } ?: group.seasons.firstOrNull()
    val episodeListState = rememberLazyListState()
    var showDeleteSeasonDialog by remember { mutableStateOf(false) }

    // When season changes, scroll episode list to top
    LaunchedEffect(selectedSeason) {
        episodeListState.scrollToItem(0)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        // ── Header row with back button, title, 3-dot menu for season delete ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = d.screenHorizPad, vertical = d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back arrow
            Box(
                Modifier
                    .size(d.iconLg + d.spaceSm)
                    .clip(CircleShape)
                    .background(GlassMd)
                    .clickable(onClick = onBack),
                Alignment.Center,
            ) {
                Text("←", color = White, fontSize = d.textLg, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(d.spaceMd))
            Text(
                group.title,
                color = White,
                fontSize = (d.textXl.value + 1f).sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Season-level 3-dot menu
            if (currentSeason != null) {
                Box(
                    Modifier
                        .size(d.iconLg + d.spaceSm)
                        .clip(CircleShape)
                        .background(GlassMd)
                        .clickable { showDeleteSeasonDialog = true },
                    Alignment.Center,
                ) {
                    Text("⋮", color = White60, fontSize = d.textLg)
                }
            }
        }

        // ── Season selector chips (sticky) ─────────────────────────────────────
        LazyRow(
            contentPadding = PaddingValues(horizontal = d.screenHorizPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
            modifier = Modifier.padding(bottom = d.spaceSm),
        ) {
            items(group.seasons) { season ->
                val isSelected = season.season == selectedSeason
                Box(
                    Modifier
                        .clip(RoundedCornerShape(d.radiusPill))
                        .background(
                            if (isSelected)
                                Brush.horizontalGradient(listOf(BrandDeep.copy(.9f), Brand.copy(.8f)))
                            else SolidColor(GlassSm)
                        )
                        .border(1.dp, if (isSelected) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(d.radiusPill))
                        .clickable { selectedSeason = season.season }
                        .padding(horizontal = d.spaceLg - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
                ) {
                    Text(
                        "Season ${season.season}",
                        color = if (isSelected) White else White40,
                        fontSize = d.textSm,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)

        // ── Episode list ────────────────────────────────────────────────────────
        LazyColumn(
            state = episodeListState,
            contentPadding = PaddingValues(
                horizontal = d.screenHorizPad,
                vertical = d.spaceMd,
            ),
            verticalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            if (currentSeason != null) {
                items(currentSeason.episodeGroups, key = { "eg-${it.mediaId}-${it.season}-${it.episode}" }) { eg ->
                    EpisodeGroupCard(
                        eg       = eg,
                        onPlay   = { item -> playDownload(ctx, item) },
                        onDelete = { vm.deleteEpisodeGroup(eg, ctx) },
                        onDeleteQuality = { item -> vm.delete(item, ctx) },
                    )
                }
            }
        }
    }

    // Season delete dialog
    if (showDeleteSeasonDialog && currentSeason != null) {
        val seasonSizeStr = formatSize(currentSeason.totalSize)
        ReelzDeleteDialog(
            title   = "Delete Season ${currentSeason.season}",
            message = "Remove all ${currentSeason.episodeGroups.size} episodes of Season ${currentSeason.season}? ($seasonSizeStr)",
            onDelete  = { vm.deleteSeason(currentSeason, ctx); showDeleteSeasonDialog = false },
            onDismiss = { showDeleteSeasonDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DownloadsHeader(readyCount: Int, activeCount: Int, onTransfer: () -> Unit) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad + d.spaceXxs, vertical = d.spaceLg),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Downloads",
                color = White,
                fontSize = (d.textXxl.value + 3f).sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.8).sp,
            )
            Spacer(Modifier.height(d.spaceXxs + 1.dp))
            AnimatedContent(
                targetState = when {
                    activeCount > 0 -> "$activeCount downloading"
                    readyCount > 0  -> "$readyCount ready to watch"
                    else            -> "Your offline library"
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "subtitle",
            ) { subtitle ->
                Text(
                    subtitle,
                    color = if (activeCount > 0) Brand else if (readyCount > 0) Success else White40,
                    fontSize = d.textSm,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Row(
            Modifier
                .clip(RoundedCornerShape(d.radiusPill))
                .background(Brush.horizontalGradient(listOf(Color(0xFF003F8F), Color(0xFF0A5FCC))))
                .border(1.dp, Brand.copy(.3f), RoundedCornerShape(d.radiusPill))
                .clickable(onClick = onTransfer)
                .padding(horizontal = d.spaceLg - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceXs + 1.dp),
        ) {
            Icon(IconSwap, null, tint = Color.White, modifier = Modifier.size(d.iconSm + 4.dp))
            Text("Transfer", color = Color.White, fontSize = d.textSm, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Active Queue Strip — horizontal scroll with "View All"
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActiveQueueStrip(
    items: List<DownloadItem>,
    ctx: Context,
    vm: DownloadsViewModel,
    onViewAll: () -> Unit,
) {
    val d = LocalDimensions.current
    Column(Modifier.padding(bottom = d.spaceLg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = d.screenHorizPad, vertical = d.spaceXs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pulseAlpha by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "alpha",
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
            ) {
                Box(
                    Modifier.size(d.spaceXs + 2.dp).clip(CircleShape).background(Brand.copy(alpha = pulseAlpha))
                )
                Text(
                    "ACTIVE",
                    color = White40,
                    fontSize = (d.textXxs.value + 1f).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
            }
            // View all button
            Text(
                "View All",
                color = Brand,
                fontSize = d.textXs,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(d.radiusPill))
                    .clickable(onClick = onViewAll)
                    .padding(horizontal = d.spaceSm, vertical = d.spaceXxs),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = d.screenHorizPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            items(items, key = { "aq-${it.id}" }) { item ->
                ActiveQueueCard(
                    item     = item,
                    onPause  = { vm.pause(ctx, item) },
                    onResume = { vm.resume(ctx, item) },
                    onCancel = { vm.delete(item, ctx) },
                )
            }
        }
    }
}

@Composable
private fun ActiveQueueCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val d = LocalDimensions.current
    val isDownloading = item.status == DownloadStatus.DOWNLOADING.name
    val isPaused      = item.status == DownloadStatus.PAUSED.name
    val isQueued      = item.status == DownloadStatus.QUEUED.name
    val isError       = item.status == DownloadStatus.ERROR.name

    val pct = if (item.totalSegments > 0) item.segmentsDone.toFloat() / item.totalSegments
              else if (item.sizeBytes > 0) item.downloadedBytes.toFloat() / item.sizeBytes
              else 0f
    val animPct by animateFloatAsState(pct.coerceIn(0f, 1f), label = "aq-pct")
    val cardW = LocalDimensions.current.continueCardWidth + d.spaceLg

    Row(
        Modifier
            .width(cardW)
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, if (isDownloading) Brand.copy(.22f) else GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .padding(d.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
    ) {
        // Poster
        Box(
            Modifier
                .size(width = d.avatarSm + d.spaceXxs, height = d.avatarSm + d.spaceMd)
                .clip(RoundedCornerShape(d.radiusSm))
                .background(BgRaised),
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = White,
                fontSize = d.textXs,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.mediaType == "TV" && item.season > 0) {
                Text("S${item.season}E${item.episode}", color = White40, fontSize = (d.textXxs.value + 0.5f).sp)
            }
            // Quality badge on active card
            if (item.quality.isNotBlank()) {
                Text(item.quality, color = Brand.copy(.8f), fontSize = (d.textXxs.value + 0.5f).sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(d.spaceXxs + 2.dp))
            // Progress bar
            Box(
                Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(GlassMd)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animPct)
                        .fillMaxHeight()
                        .background(
                            brush = when {
                                isError   -> SolidColor(Error)
                                isPaused  -> SolidColor(White40)
                                isQueued  -> SolidColor(White20)
                                else      -> Brush.horizontalGradient(listOf(Brand, Brand2))
                            }
                        )
                )
            }
            Spacer(Modifier.height(d.spaceXxs))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        isQueued                 -> "Waiting…"
                        isError                  -> "Failed"
                        isPaused                 -> "${(pct * 100).toInt()}% · Paused"
                        item.networkSpeedBps > 0 -> "${(pct * 100).toInt()}% · ${formatSpeed(item.networkSpeedBps)}"
                        else                     -> "${(pct * 100).toInt()}%"
                    },
                    color = if (isDownloading && item.networkSpeedBps > 0) Success.copy(.85f) else White40,
                    fontSize = (d.textXxs.value + 0.5f).sp,
                )
                // Pause/Resume icon
                Row(horizontalArrangement = Arrangement.spacedBy(d.spaceXs)) {
                    Box(
                        Modifier
                            .size(d.iconMd + d.spaceXxs)
                            .clip(CircleShape)
                            .background(GlassMd)
                            .clickable(onClick = if (isDownloading) onPause else onResume),
                        Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isDownloading) IconPause else IconPlay,
                            contentDescription = null,
                            tint = if (isPaused || isError) Brand else White60,
                            modifier = Modifier.size(d.iconSm - 4.dp),
                        )
                    }
                    // Cancel X
                    Box(
                        Modifier
                            .size(d.iconMd + d.spaceXxs)
                            .clip(CircleShape)
                            .background(GlassMd)
                            .clickable(onClick = onCancel),
                        Alignment.Center,
                    ) {
                        Text("✕", color = White40, fontSize = (d.textXxs.value + 1f).sp)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab Filter Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TabFilterBar(
    selected: Int,
    movieCount: Int,
    seriesCount: Int,
    onSelect: (Int) -> Unit,
) {
    val d = LocalDimensions.current
    val tabs = listOf(
        "All"    to null,
        "Movies" to movieCount,
        "Shows"  to seriesCount,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad),
        horizontalArrangement = Arrangement.spacedBy(d.spaceXs + 1.dp),
    ) {
        tabs.forEachIndexed { i, (label, count) ->
            val isSelected = selected == i
            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(
                        if (isSelected) Brush.horizontalGradient(listOf(BrandDeep.copy(.9f), Brand.copy(.8f)))
                        else SolidColor(GlassSm)
                    )
                    .border(1.dp, if (isSelected) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(d.radiusPill))
                    .clickable { onSelect(i) }
                    .padding(horizontal = d.spaceLg - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp),
                ) {
                    Text(
                        label,
                        color = if (isSelected) White else White40,
                        fontSize = d.textSm,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                    if (count != null && count > 0) {
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(if (isSelected) White20 else GlassMd)
                                .padding(horizontal = d.spaceXs, vertical = 1.dp),
                            Alignment.Center,
                        ) {
                            Text(
                                "$count",
                                color = if (isSelected) White else White40,
                                fontSize = (d.textXxs.value + 0.5f).sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(d.spaceMd + d.spaceXxs))
}

// ─────────────────────────────────────────────────────────────────────────────
// Section label
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            Box(
                Modifier
                    .width(d.sectionAccentWidth)
                    .height(d.sectionAccentHeight + d.spaceXxs)
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(Brush.verticalGradient(listOf(Brand, Brand.copy(.3f))))
            )
            Text(title, color = White, fontSize = (d.textMd.value + 0.5f).sp, fontWeight = FontWeight.Bold)
        }
        Text(subtitle, color = White40, fontSize = d.textXs, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Universal horizontal card (movie card)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Universal horizontal card — used for MovieGroupCard and EpisodeGroupCard.
 * One card per title regardless of how many qualities are downloaded.
 * Resolution badges are shown inline without scattering.
 */
@Composable
fun MovieGroupCard(
    group: MovieGroup,
    onPlay: (DownloadItem) -> Unit,
    onDelete: () -> Unit,
    onDeleteQuality: (DownloadItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    val primary = group.primaryDownload
    val doneDownloads = group.doneDownloads

    val watchFraction = if (group.durationMs > 0) (group.watchProgressMs.toFloat() / group.durationMs).coerceIn(0f, 1f) else 0f
    val hasProgress = group.watchProgressMs > 0 && group.durationMs > 0

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(BgCard)
            .border(1.dp, Success.copy(.2f), RoundedCornerShape(d.radiusLg - d.spaceXxs))
    ) {
        // Left accent line — green for done
        Box(
            Modifier
                .width(3.dp).fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Success.copy(.8f), Success.copy(.3f))))
                .clip(RoundedCornerShape(topStart = d.radiusLg, bottomStart = d.radiusLg))
        )

        Row(Modifier.fillMaxWidth().padding(d.spaceMd), verticalAlignment = Alignment.Top) {
            // Poster (35-40% width) — tappable to play
            Box(
                Modifier
                    .size(width = d.avatarMd + d.spaceXxs + 2.dp, height = d.avatarLg + d.spaceLg)
                    .clip(RoundedCornerShape(d.radiusSm + 2.dp))
                    .background(BgRaised)
                    .clickable { onPlay(primary) }
            ) {
                AsyncImage(
                    model = primary.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Play overlay
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(.35f)),
                    Alignment.Center,
                ) {
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape).background(Color.Black.copy(.55f)).border(1.5.dp, White60, CircleShape),
                        Alignment.Center,
                    ) {
                        Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(d.iconSm + 2.dp).offset(x = 1.dp))
                    }
                }
                // Watch progress bar at bottom of poster
                if (hasProgress) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(.5f))
                            .align(Alignment.BottomCenter)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(watchFraction)
                                .fillMaxHeight()
                                .background(Brand)
                        )
                    }
                }
            }

            Spacer(Modifier.width(d.spaceMd))

            Column(Modifier.weight(1f)) {
                // Title + 3-dot
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        primary.title,
                        color = White,
                        fontSize = d.textMd,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(d.spaceXs))
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape).background(GlassMd).clickable { showDeleteDialog = true },
                        Alignment.Center,
                    ) { Text("⋮", color = White60, fontSize = d.textMd) }
                }

                Spacer(Modifier.height(d.spaceXs))

                // Total size
                if (group.totalSize > 0) {
                    Text(formatSize(group.totalSize), color = White40, fontSize = d.textXs)
                }

                Spacer(Modifier.height(d.spaceXs))

                // Resolution badges — all qualities shown inline, no scatter
                MultiQualityBadges(doneDownloads.map { it.quality })

                Spacer(Modifier.height(d.spaceSm))

                // Downloaded date
                if (group.completedAt > 0) {
                    val daysAgo = ((System.currentTimeMillis() - group.completedAt) / 86_400_000L).toInt()
                    Text(
                        when (daysAgo) {
                            0    -> "Downloaded today"
                            1    -> "Downloaded yesterday"
                            else -> "Downloaded $daysAgo days ago"
                        },
                        color = White20, fontSize = (d.textXxs.value + 1f).sp,
                    )
                }

                Spacer(Modifier.height(d.spaceSm))

                // Watch progress label or "Not opened"
                Text(
                    when {
                        hasProgress -> {
                            val pct = (watchFraction * 100).toInt()
                            if (pct >= 95) "Watched" else "$pct% watched"
                        }
                        group.lastPlayedAt > 0 -> {
                            val daysAgo = ((System.currentTimeMillis() - group.lastPlayedAt) / 86_400_000L).toInt()
                            "Last played: ${when (daysAgo) { 0 -> "today"; 1 -> "yesterday"; else -> "$daysAgo days ago" }}"
                        }
                        else -> "Not opened"
                    },
                    color = if (hasProgress && watchFraction < 0.95f) Brand.copy(.8f) else White40,
                    fontSize = (d.textXxs.value + 1f).sp,
                    fontWeight = if (hasProgress) FontWeight.SemiBold else FontWeight.Normal,
                )

                Spacer(Modifier.height(d.spaceMd))

                // Play button row
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(d.radiusPill))
                            .background(Brand.copy(.15f))
                            .border(1.dp, Brand.copy(.35f), RoundedCornerShape(d.radiusPill))
                            .clickable { onPlay(primary) }
                            .padding(horizontal = d.spaceLg, vertical = d.spaceSm),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
                        ) {
                            Icon(IconPlay, null, tint = Brand, modifier = Modifier.size(d.iconSm))
                            Text("Play", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        MovieDeleteDialog(
            group     = group,
            onDeleteQuality = { item -> onDeleteQuality(item); showDeleteDialog = false },
            onDeleteAll     = { onDelete(); showDeleteDialog = false },
            onDismiss       = { showDeleteDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Series root card (on Downloads root page)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SeriesRootCard(
    group: SeriesGroup,
    onTap: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .clickable(onClick = onTap)
    ) {
        Row(Modifier.fillMaxWidth().padding(d.spaceMd), verticalAlignment = Alignment.Top) {
            // Poster — tap opens series page
            Box(
                Modifier
                    .size(width = d.avatarMd + d.spaceXxs + 2.dp, height = d.avatarLg + d.spaceLg)
                    .clip(RoundedCornerShape(d.radiusSm + 2.dp))
                    .background(BgRaised)
            ) {
                AsyncImage(
                    model = group.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Completion overlay
                if (group.isFullyDownloaded) {
                    Box(
                        Modifier.fillMaxSize().background(Success.copy(.25f)),
                        Alignment.Center,
                    ) { Text("✓", color = Success, fontSize = d.textLg, fontWeight = FontWeight.Black) }
                }
                // Play icon
                Box(Modifier.fillMaxSize().background(Color.Black.copy(.25f)), Alignment.Center) {
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape).background(Color.Black.copy(.5f)).border(1.5.dp, White60, CircleShape),
                        Alignment.Center,
                    ) { Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(d.iconSm + 2.dp).offset(x = 1.dp)) }
                }
            }

            Spacer(Modifier.width(d.spaceMd))

            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        group.title,
                        color = White,
                        fontSize = d.textMd,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(d.spaceXs))
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape).background(GlassMd).clickable { showDeleteDialog = true },
                        Alignment.Center,
                    ) { Text("⋮", color = White60, fontSize = d.textMd) }
                }

                Spacer(Modifier.height(d.spaceXs))
                Text("${group.doneEpisodes} Episodes", color = White40, fontSize = d.textXs)
                Spacer(Modifier.height(d.spaceXxs))
                Text("${group.seasonCount} Season${if (group.seasonCount > 1) "s" else ""}", color = White40, fontSize = d.textXs)
                Spacer(Modifier.height(d.spaceXs))

                if (group.lastWatchedLabel != null) {
                    Text("Last watched ${group.lastWatchedLabel}", color = White40, fontSize = (d.textXxs.value + 1f).sp)
                    Spacer(Modifier.height(d.spaceXs))
                }

                // Status dot + progress bar for series completion
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                ) {
                    Box(
                        Modifier.size(d.spaceXs + 1.dp).clip(CircleShape).background(
                            when {
                                group.isFullyDownloaded -> Success
                                group.isAnyActive       -> Brand
                                else                    -> White40
                            }
                        )
                    )
                    val pct = if (group.totalEpisodes > 0) group.doneEpisodes.toFloat() / group.totalEpisodes else 0f
                    Text(
                        "${group.doneEpisodes}/${group.totalEpisodes} downloaded",
                        color = White40,
                        fontSize = (d.textXxs.value + 1f).sp,
                    )
                }

                Spacer(Modifier.height(d.spaceSm))
                val pct = if (group.totalEpisodes > 0) group.doneEpisodes.toFloat() / group.totalEpisodes else 0f
                Box(
                    Modifier.fillMaxWidth(0.9f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(GlassMd)
                ) {
                    Box(
                        Modifier.fillMaxWidth(pct).fillMaxHeight().background(
                            if (group.isFullyDownloaded) SolidColor(Success)
                            else Brush.horizontalGradient(listOf(Brand, Brand2))
                        )
                    )
                }

                Spacer(Modifier.height(d.spaceMd))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(d.radiusPill))
                            .background(Brand.copy(.15f))
                            .border(1.dp, Brand.copy(.35f), RoundedCornerShape(d.radiusPill))
                            .clickable(onClick = onPlay)
                            .padding(horizontal = d.spaceLg, vertical = d.spaceSm),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
                        ) {
                            Icon(IconPlay, null, tint = Brand, modifier = Modifier.size(d.iconSm))
                            Text("Play", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        ReelzDeleteDialog(
            title    = "Delete Series",
            message  = "Remove all downloaded episodes of \"${group.title}\"?",
            onDelete  = { onDelete(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Episode group card (inside series detail page)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun EpisodeGroupCard(
    eg: EpisodeGroup,
    onPlay: (DownloadItem) -> Unit,
    onDelete: () -> Unit,
    onDeleteQuality: (DownloadItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    val primary = eg.primaryDownload
    val doneDownloads = eg.doneDownloads

    val watchFraction = if (eg.durationMs > 0) (eg.watchProgressMs.toFloat() / eg.durationMs).coerceIn(0f, 1f) else 0f
    val hasProgress = eg.watchProgressMs > 0 && eg.durationMs > 0

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(BgCard)
            .border(1.dp, if (doneDownloads.isNotEmpty()) Success.copy(.15f) else GlassBorderMd, RoundedCornerShape(d.radiusLg - d.spaceXxs))
    ) {
        if (doneDownloads.isNotEmpty()) {
            Box(
                Modifier.width(3.dp).fillMaxHeight()
                    .background(Brush.verticalGradient(listOf(Success.copy(.7f), Success.copy(.2f))))
                    .clip(RoundedCornerShape(topStart = d.radiusLg, bottomStart = d.radiusLg))
            )
        }

        Row(Modifier.fillMaxWidth().padding(d.spaceMd), verticalAlignment = Alignment.Top) {
            // Poster — tappable to play
            Box(
                Modifier
                    .size(width = d.avatarMd + d.spaceXxs + 2.dp, height = d.avatarLg + d.spaceLg)
                    .clip(RoundedCornerShape(d.radiusSm + 2.dp))
                    .background(BgRaised)
                    .clickable { onPlay(primary) }
            ) {
                AsyncImage(
                    model = primary.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Play overlay
                Box(Modifier.fillMaxSize().background(Color.Black.copy(.35f)), Alignment.Center) {
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape).background(Color.Black.copy(.55f)).border(1.5.dp, White60, CircleShape),
                        Alignment.Center,
                    ) { Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(d.iconSm + 2.dp).offset(x = 1.dp)) }
                }
                // Watch progress at bottom of poster
                if (hasProgress) {
                    Box(
                        Modifier.fillMaxWidth().height(3.dp).background(Color.Black.copy(.5f)).align(Alignment.BottomCenter)
                    ) {
                        Box(Modifier.fillMaxWidth(watchFraction).fillMaxHeight().background(Brand))
                    }
                }
            }

            Spacer(Modifier.width(d.spaceMd))

            Column(Modifier.weight(1f)) {
                // Episode label + 3-dot
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Episode ${eg.episode}${if (eg.episodeName.isNotBlank()) " · ${eg.episodeName}" else ""}",
                            color = White,
                            fontSize = d.textSm,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(d.spaceXs))
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape).background(GlassMd).clickable { showDeleteDialog = true },
                        Alignment.Center,
                    ) { Text("⋮", color = White60, fontSize = d.textMd) }
                }

                Spacer(Modifier.height(d.spaceXs))

                // Total size
                if (eg.totalSize > 0) {
                    Text(formatSize(eg.totalSize), color = White40, fontSize = d.textXs)
                    Spacer(Modifier.height(d.spaceXxs))
                }

                // Resolution badges
                MultiQualityBadges(doneDownloads.map { it.quality })

                Spacer(Modifier.height(d.spaceSm))

                // Downloaded date
                if (primary.completedAt > 0) {
                    val daysAgo = ((System.currentTimeMillis() - primary.completedAt) / 86_400_000L).toInt()
                    Text(
                        when (daysAgo) {
                            0    -> "Downloaded today"
                            1    -> "Downloaded yesterday"
                            else -> "Downloaded $daysAgo days ago"
                        },
                        color = White20, fontSize = (d.textXxs.value + 1f).sp,
                    )
                    Spacer(Modifier.height(d.spaceXxs))
                }

                // Watch progress or "Not opened"
                Text(
                    when {
                        hasProgress -> {
                            val pct = (watchFraction * 100).toInt()
                            if (pct >= 95) "Watched" else "$pct% watched"
                        }
                        eg.lastPlayedAt > 0 -> {
                            val daysAgo = ((System.currentTimeMillis() - eg.lastPlayedAt) / 86_400_000L).toInt()
                            "Last played: ${when (daysAgo) { 0 -> "today"; 1 -> "yesterday"; else -> "$daysAgo days ago" }}"
                        }
                        else -> "Not opened"
                    },
                    color = if (hasProgress && watchFraction < 0.95f) Brand.copy(.8f) else White40,
                    fontSize = (d.textXxs.value + 1f).sp,
                    fontWeight = if (hasProgress) FontWeight.SemiBold else FontWeight.Normal,
                )

                Spacer(Modifier.height(d.spaceMd))

                // Play button
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(d.radiusPill))
                            .background(Brand.copy(.15f))
                            .border(1.dp, Brand.copy(.35f), RoundedCornerShape(d.radiusPill))
                            .clickable { onPlay(primary) }
                            .padding(horizontal = d.spaceLg, vertical = d.spaceSm),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
                        ) {
                            Icon(IconPlay, null, tint = Brand, modifier = Modifier.size(d.iconSm))
                            Text("Play", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        EpisodeDeleteDialog(
            eg              = eg,
            onDeleteQuality = { item -> onDeleteQuality(item); showDeleteDialog = false },
            onDeleteAll     = { onDelete(); showDeleteDialog = false },
            onDismiss       = { showDeleteDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Multi-quality badge row — shows 480p, 1080p etc cleanly on one row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MultiQualityBadges(qualities: List<String>) {
    if (qualities.isEmpty()) return
    val d = LocalDimensions.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        qualities.distinct().sorted().forEach { q ->
            if (q.isNotBlank()) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(d.radiusPill))
                        .background(GlassMd)
                        .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusPill))
                        .padding(horizontal = d.spaceSm, vertical = d.spaceXxs + 1.dp)
                ) {
                    Text(q, color = White60, fontSize = (d.textXxs.value + 1f).sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Delete Dialogs
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Movie delete dialog:
 * - If multiple qualities: show "Delete this quality" options + "Delete all"
 * - If only one quality: simple "Delete Movie?" confirm
 */
@Composable
private fun MovieDeleteDialog(
    group: MovieGroup,
    onDeleteQuality: (DownloadItem) -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val d = LocalDimensions.current
    val doneDownloads = group.doneDownloads

    if (doneDownloads.size <= 1) {
        // Simple dialog
        ReelzDeleteDialog(
            title    = "Delete Movie",
            message  = "Remove \"${group.title}\" from your library?",
            onDelete  = onDeleteAll,
            onDismiss = onDismiss,
        )
        return
    }

    // Multi-quality dialog
    var selectedId by remember { mutableStateOf<String?>(null) }
    var deleteAll  by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgCard,
        shape            = RoundedCornerShape(d.radiusLg),
        title = {
            Text("Delete Movie", color = White, fontWeight = FontWeight.Bold, fontSize = d.textLg)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                doneDownloads.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusSm)).clickable { selectedId = item.id; deleteAll = false }
                            .padding(vertical = d.spaceXs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                    ) {
                        RadioButton(
                            selected = selectedId == item.id && !deleteAll,
                            onClick  = { selectedId = item.id; deleteAll = false },
                            colors   = RadioButtonDefaults.colors(selectedColor = Brand, unselectedColor = White40),
                        )
                        Column {
                            Text("Delete ${item.quality}", color = White60, fontSize = d.textSm)
                            Text(formatSize(item.sizeBytes), color = White40, fontSize = d.textXs)
                        }
                    }
                }
                HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusSm)).clickable { deleteAll = true; selectedId = null }
                        .padding(vertical = d.spaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                ) {
                    RadioButton(
                        selected = deleteAll,
                        onClick  = { deleteAll = true; selectedId = null },
                        colors   = RadioButtonDefaults.colors(selectedColor = Error, unselectedColor = White40),
                    )
                    Column {
                        Text("Delete all qualities", color = Error.copy(.85f), fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
                        Text(formatSize(group.totalSize), color = White40, fontSize = d.textXs)
                    }
                }
            }
        },
        confirmButton = {
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusPill))
                    .background(Error.copy(.15f))
                    .border(1.dp, Error.copy(.35f), RoundedCornerShape(d.radiusPill))
                    .clickable {
                        if (deleteAll) onDeleteAll()
                        else selectedId?.let { id -> doneDownloads.find { it.id == id }?.let { onDeleteQuality(it) } }
                    }
                    .padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Delete", color = Error, fontWeight = FontWeight.Bold, fontSize = d.textSm) }
        },
        dismissButton = {
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusPill)).background(GlassMd)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Cancel", color = White60, fontSize = d.textSm) }
        },
    )
}

/**
 * Episode delete dialog — same logic as MovieDeleteDialog
 */
@Composable
private fun EpisodeDeleteDialog(
    eg: EpisodeGroup,
    onDeleteQuality: (DownloadItem) -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val d = LocalDimensions.current
    val doneDownloads = eg.doneDownloads
    val label = if (eg.episodeName.isNotBlank()) eg.episodeName else "Episode ${eg.episode}"

    if (doneDownloads.size <= 1) {
        ReelzDeleteDialog(
            title    = "Delete Episode",
            message  = "Remove \"$label\"?",
            onDelete  = onDeleteAll,
            onDismiss = onDismiss,
        )
        return
    }

    var selectedId by remember { mutableStateOf<String?>(null) }
    var deleteAll  by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgCard,
        shape            = RoundedCornerShape(d.radiusLg),
        title = {
            Text("Delete Episode", color = White, fontWeight = FontWeight.Bold, fontSize = d.textLg)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                doneDownloads.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusSm)).clickable { selectedId = item.id; deleteAll = false }
                            .padding(vertical = d.spaceXs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                    ) {
                        RadioButton(
                            selected = selectedId == item.id && !deleteAll,
                            onClick  = { selectedId = item.id; deleteAll = false },
                            colors   = RadioButtonDefaults.colors(selectedColor = Brand, unselectedColor = White40),
                        )
                        Column {
                            Text("Delete ${item.quality}", color = White60, fontSize = d.textSm)
                            Text(formatSize(item.sizeBytes), color = White40, fontSize = d.textXs)
                        }
                    }
                }
                HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusSm)).clickable { deleteAll = true; selectedId = null }
                        .padding(vertical = d.spaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                ) {
                    RadioButton(
                        selected = deleteAll,
                        onClick  = { deleteAll = true; selectedId = null },
                        colors   = RadioButtonDefaults.colors(selectedColor = Error, unselectedColor = White40),
                    )
                    Column {
                        Text("Delete all qualities", color = Error.copy(.85f), fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
                        Text(formatSize(eg.totalSize), color = White40, fontSize = d.textXs)
                    }
                }
            }
        },
        confirmButton = {
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusPill))
                    .background(Error.copy(.15f))
                    .border(1.dp, Error.copy(.35f), RoundedCornerShape(d.radiusPill))
                    .clickable {
                        if (deleteAll) onDeleteAll()
                        else selectedId?.let { id -> doneDownloads.find { it.id == id }?.let { onDeleteQuality(it) } }
                    }
                    .padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Delete", color = Error, fontWeight = FontWeight.Bold, fontSize = d.textSm) }
        },
        dismissButton = {
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusPill)).background(GlassMd)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Cancel", color = White60, fontSize = d.textSm) }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyDownloadsState() {
    val d = LocalDimensions.current
    Box(Modifier.fillMaxWidth().padding(top = d.spaceXxl * 2), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(d.avatarLg + d.spaceXxl + d.spaceSm).clip(CircleShape).background(BlueGlass).border(1.dp, BlueBorder, CircleShape)
                )
                Box(
                    Modifier.size(d.avatarLg + d.spaceLg).clip(CircleShape).background(GlassSm).border(1.dp, GlassBorderMd, CircleShape)
                )
                Icon(IconDownloadCloud, null, tint = Brand.copy(.75f), modifier = Modifier.size(d.avatarSm + d.spaceMd))
            }
            Spacer(Modifier.height(d.spaceXxs))
            Text("Nothing here yet", color = White60, fontSize = d.textXl, fontWeight = FontWeight.Bold)
            Text(
                "Save movies & shows to watch anywhere,\neven without internet.",
                color = White40,
                fontSize = d.textSm,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared atoms
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StatusPill(status: String) {
    val d = LocalDimensions.current
    val (color, label) = when (status) {
        DownloadStatus.DONE.name        -> Success to "Ready"
        DownloadStatus.DOWNLOADING.name -> Brand to "Downloading"
        DownloadStatus.QUEUED.name      -> White60 to "Queued"
        DownloadStatus.PAUSED.name      -> White40 to "Paused"
        DownloadStatus.ERROR.name       -> Error to "Failed"
        else                            -> White40 to status
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(d.radiusPill))
            .background(color.copy(.12f))
            .border(1.dp, color.copy(.3f), RoundedCornerShape(d.radiusPill))
            .padding(horizontal = d.spaceSm + 1.dp, vertical = d.spaceXxs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp),
    ) {
        if (status == DownloadStatus.DONE.name) {
            Box(Modifier.size(d.spaceXs).clip(CircleShape).background(Success))
        }
        Text(label, color = color, fontSize = (d.textXxs.value + 1f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun QualityChip(quality: String) {
    val d = LocalDimensions.current
    if (quality.isBlank()) return
    Box(
        Modifier
            .clip(RoundedCornerShape(d.radiusPill))
            .background(GlassMd)
            .padding(horizontal = d.spaceSm, vertical = d.spaceXxs + 1.dp)
    ) {
        Text(quality, color = White40, fontSize = (d.textXxs.value + 1f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable fun QualityBadge(quality: String) = QualityChip(quality)
@Composable fun StatusBadge(status: String)   = StatusPill(status)

@Composable
private fun ReelzDeleteDialog(
    title: String,
    message: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val d = LocalDimensions.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgCard,
        shape            = RoundedCornerShape(d.radiusLg),
        title  = { Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = d.textLg) },
        text   = { Text(message, color = White60, fontSize = d.textMd) },
        confirmButton = {
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusPill)).background(Error.copy(.15f))
                    .border(1.dp, Error.copy(.35f), RoundedCornerShape(d.radiusPill))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Delete", color = Error, fontWeight = FontWeight.Bold, fontSize = d.textSm) }
        },
        dismissButton = {
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusPill)).background(GlassMd)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Cancel", color = White60, fontSize = d.textSm) }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Build a play intent for a DownloadItem.
 * Passes all downloaded qualities for this content (tmdbId/season/episode) so the
 * player can show a quality switcher using only locally available files.
 */
private fun playDownload(ctx: Context, dl: DownloadItem) {
    val base = Intent(ctx, PlayerActivity::class.java).apply {
        putExtra("mediaId",    dl.mediaId)
        putExtra("mediaType",  dl.mediaType)
        putExtra("season",     dl.season)
        putExtra("episode",    dl.episode)
        putExtra("title",      dl.title)
        putExtra("posterUrl", dl.posterUrl)
        putExtra("downloadId", dl.id)
        // Hint to player: start from this quality (empty = auto pick highest)
        putExtra("preferredQuality", dl.lastSelectedQuality)
        // Flag that this is an offline playback
        putExtra("isOffline", true)
    }
    when {
        dl.status == DownloadStatus.DONE.name && dl.filePath.isNotBlank() -> {
            base.putExtra("streamUrl",   "file://${dl.filePath}")
            base.putExtra("streamIsHls", false)
            ctx.startActivity(base)
        }
        dl.localPlaylistPath.isNotBlank() -> {
            base.putExtra("streamUrl",   "file://${dl.localPlaylistPath}")
            base.putExtra("streamIsHls", true)
            ctx.startActivity(base)
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L          -> "%.1f KB".format(bytes / 1024.0)
    else                    -> "$bytes B"
}

fun formatSpeed(bps: Long): String = when {
    bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
    bps >= 1_000     -> "%.0f KB/s".format(bps / 1_000.0)
    else             -> "$bps B/s"
}
