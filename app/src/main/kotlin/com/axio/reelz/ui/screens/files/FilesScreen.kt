package com.axio.reelz.ui.screens.files

import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
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
import com.axio.reelz.core.database.DownloadDao
import com.axio.reelz.data.model.*
import com.axio.reelz.data.repository.DownloadRepository
import com.axio.reelz.app.Route
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

data class MovieGroup(
    val mediaId: String,
    val title: String,
    val posterPath: String?,
    val downloads: List<DownloadItem>,
) {
    val doneDownloads: List<DownloadItem> get() = downloads.filter { it.status == DownloadStatus.DONE }
    val totalSize: Long get() = doneDownloads.sumOf { it.sizeBytes }
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
    val lastWatchedLabel: String? get() {
        val lastPlayed = seasons
            .flatMap { it.episodeGroups }
            .flatMap { it.downloads }
            .filter { it.lastPlayedAt > 0 }
            .maxByOrNull { it.lastPlayedAt }
        return lastPlayed?.let { "S%02dE%02d".format(it.season, it.episode) }
    }
    val seasonCount: Int get() = seasons.size
    val totalSize: Long get() = seasons.sumOf { it.totalSize }
}

data class SeasonGroup(
    val season: Int,
    val episodeGroups: List<EpisodeGroup>,
) {
    val doneCount: Int get() = episodeGroups.count { it.doneDownloads.isNotEmpty() }
    val totalSize: Long get() = episodeGroups.sumOf { eg -> eg.doneDownloads.sumOf { it.sizeBytes } }
}

data class EpisodeGroup(
    val mediaId: String,
    val season: Int,
    val episode: Int,
    val episodeName: String,
    val posterPath: String?,
    val downloads: List<DownloadItem>,
) {
    val doneDownloads: List<DownloadItem> get() = downloads.filter { it.status == DownloadStatus.DONE }
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

    private val allDownloads: StateFlow<List<DownloadItem>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Movies: only DONE items appear in the library grid ───────────────────
    val movieGroups: StateFlow<List<MovieGroup>> = allDownloads
        .map { list ->
            list.filter { it.mediaType == "MOVIE" && it.status == DownloadStatus.DONE }
                .groupBy { it.mediaId }
                .map { (mediaId, items) ->
                    MovieGroup(
                        mediaId    = mediaId,
                        title      = items.first().title,
                        posterPath = items.first().posterUrl,
                        downloads  = items.sortedByDescending { it.sizeBytes },
                    )
                }
                .sortedByDescending { it.completedAt }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Series: only series that have AT LEAST ONE fully done episode appear ─
    //
    // BUG FIX: Previously, ALL TV items were grouped (including DOWNLOADING,
    // QUEUED, PAUSED) so a series appeared in the library immediately after
    // download started.  Now we only include episodes whose status == DONE,
    // and we only surface a SeriesGroup when it has at least one done episode.
    //
    val seriesGroups: StateFlow<List<SeriesGroup>> = allDownloads
        .map { list ->
            buildSeriesGroups(
                // Only DONE episodes belong in the library list
                list.filter { it.mediaType == "TV" && it.status == DownloadStatus.DONE }
            )
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Active: any non-DONE, non-cancelled state ─────────────────────────────
    val activeDownloads: StateFlow<List<DownloadItem>> = allDownloads
        .map { list ->
            list.filter {
                it.status == DownloadStatus.DOWNLOADING
                    || it.status == DownloadStatus.QUEUED
                    || it.status == DownloadStatus.PAUSED
                    || it.status == DownloadStatus.ERROR
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val readyCount: StateFlow<Int> = allDownloads
        .map { list -> list.count { it.status == DownloadStatus.DONE } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private fun buildSeriesGroups(items: List<DownloadItem>): List<SeriesGroup> =
        items.groupBy { it.mediaId }
            .map { (groupMediaId, eps) ->
                val seasons = eps
                    .groupBy { it.season }
                    .map { (season, seasonEps) ->
                        val episodeGroups = seasonEps
                            .groupBy { it.episode }
                            .map { (_, epItems) ->
                                EpisodeGroup(
                                    mediaId     = groupMediaId,
                                    season      = epItems.first().season,
                                    episode     = epItems.first().episode,
                                    episodeName = epItems.firstOrNull()?.episodeName ?: "",
                                    posterPath  = epItems.firstOrNull()?.posterUrl,
                                    downloads   = epItems.sortedByDescending { it.sizeBytes },
                                )
                            }
                            .sortedBy { it.episode }
                        SeasonGroup(season, episodeGroups)
                    }
                    .sortedBy { it.season }
                SeriesGroup(groupMediaId, eps.first().title, eps.first().posterUrl, seasons)
            }
            // Only include series that have at least 1 done episode
            .filter { g -> g.doneEpisodes > 0 }
            .sortedByDescending { g ->
                g.seasons.flatMap { it.episodeGroups }.flatMap { it.downloads }
                    .maxOfOrNull { it.completedAt } ?: 0L
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
// Bottom sheet menu data
// ─────────────────────────────────────────────────────────────────────────────

data class MenuOption(
    val icon: String,
    val label: String,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit,
)

// ─────────────────────────────────────────────────────────────────────────────
// Reusable bottom sheet menu (Netflix/YouTube style)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadOptionsSheet(
    title: String,
    subtitle: String = "",
    options: List<MenuOption>,
    onDismiss: () -> Unit,
) {
    val d = LocalDimensions.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = BgCard,
        dragHandle       = {
            Box(
                Modifier
                    .padding(top = d.spaceMd)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(White20),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = d.spaceLg),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = d.screenHorizPad, vertical = d.spaceMd),
            ) {
                Text(
                    title,
                    color      = White,
                    fontSize   = d.textMd,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(d.spaceXxs))
                    Text(subtitle, color = White40, fontSize = d.textXs)
                }
            }

            HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)
            Spacer(Modifier.height(d.spaceXs))

            options.forEach { opt ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { opt.onClick(); onDismiss() }
                        .padding(horizontal = d.screenHorizPad, vertical = d.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
                ) {
                    Box(
                        Modifier
                            .size(d.iconLg + d.spaceXs)
                            .clip(CircleShape)
                            .background(
                                if (opt.isDestructive) Error.copy(.12f) else GlassSm
                            ),
                        Alignment.Center,
                    ) {
                        Text(opt.icon, fontSize = d.textMd)
                    }
                    Text(
                        opt.label,
                        color      = if (opt.isDestructive) Error else White,
                        fontSize   = d.textMd,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FilesScreen(nav: NavController, vm: DownloadsViewModel = hiltViewModel()) {
    val d               = LocalDimensions.current
    val ctx             = LocalContext.current
    val movieGroups     by vm.movieGroups.collectAsState()
    val seriesGroups    by vm.seriesGroups.collectAsState()
    val activeDownloads by vm.activeDownloads.collectAsState()
    val readyCount      by vm.readyCount.collectAsState()
    var tab             by remember { mutableStateOf(0) }

    val showMovies = tab == 0 || tab == 1
    val showSeries = tab == 0 || tab == 2
    // Empty = no finished content (active downloads live in the strip, not the library)
    val isEmpty    = movieGroups.isEmpty() && seriesGroups.isEmpty()

    var seriesDetailGroup by remember { mutableStateOf<SeriesGroup?>(null) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (seriesDetailGroup != null) {
            SeriesDetailPage(
                group  = seriesDetailGroup!!,
                vm     = vm,
                ctx    = ctx,
                onBack = { seriesDetailGroup = null },
            )
        } else {
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

                if (activeDownloads.isNotEmpty()) {
                    item {
                        ActiveQueueStrip(
                            items     = activeDownloads,
                            ctx       = ctx,
                            vm        = vm,
                            onViewAll = { nav.navigate("downloads_active") },
                        )
                    }
                }

                item {
                    TabFilterBar(
                        selected    = tab,
                        movieCount  = movieGroups.size,
                        seriesCount = seriesGroups.size,
                        onSelect    = { tab = it },
                    )
                }

                if (isEmpty && activeDownloads.isEmpty()) { item { EmptyDownloadsState() } }
                else if (isEmpty) { item { LibraryPendingState() } }

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
                            group           = group,
                            onPlay          = { item -> playDownload(ctx, item) },
                            onDelete        = { vm.deleteMovieGroup(group, ctx) },
                            onDeleteQuality = { item -> vm.delete(item, ctx) },
                            modifier        = Modifier
                                .padding(horizontal = d.screenHorizPad)
                                .padding(bottom = d.spaceSm + d.spaceXxs),
                        )
                    }
                }

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
                                val lastEp = group.seasons
                                    .flatMap { it.episodeGroups }
                                    .flatMap { it.downloads }
                                    .filter { it.status == DownloadStatus.DONE && it.lastPlayedAt > 0 }
                                    .maxByOrNull { it.lastPlayedAt }
                                val firstEp = group.seasons
                                    .firstOrNull()?.episodeGroups?.firstOrNull()
                                    ?.doneDownloads?.firstOrNull()
                                val toPlay = lastEp ?: firstEp
                                if (toPlay != null) playDownload(ctx, toPlay)
                            },
                            onDelete = { vm.deleteSeries(group, ctx) },
                            modifier = Modifier
                                .padding(horizontal = d.screenHorizPad)
                                .padding(bottom = d.spaceSm + d.spaceXxs),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Series Detail Page
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
    var showSeasonMenu by remember { mutableStateOf(false) }

    LaunchedEffect(selectedSeason) { episodeListState.scrollToItem(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = d.screenHorizPad, vertical = d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                color      = White,
                fontSize   = (d.textXl.value + 1f).sp,
                fontWeight = FontWeight.Black,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f),
            )
            if (currentSeason != null) {
                Box(
                    Modifier
                        .size(d.iconLg + d.spaceSm)
                        .clip(CircleShape)
                        .background(GlassMd)
                        .clickable { showSeasonMenu = true },
                    Alignment.Center,
                ) {
                    Text("⋮", color = White60, fontSize = d.textLg)
                }
            }
        }

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
                        color      = if (isSelected) White else White40,
                        fontSize   = d.textSm,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)

        LazyColumn(
            state = episodeListState,
            contentPadding = PaddingValues(horizontal = d.screenHorizPad, vertical = d.spaceMd),
            verticalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            if (currentSeason != null) {
                items(currentSeason.episodeGroups, key = { "eg-${it.mediaId}-${it.season}-${it.episode}" }) { eg ->
                    EpisodeGroupCard(
                        eg              = eg,
                        onPlay          = { item -> playDownload(ctx, item) },
                        onDelete        = { vm.deleteEpisodeGroup(eg, ctx) },
                        onDeleteQuality = { item -> vm.delete(item, ctx) },
                    )
                }
            }
        }
    }

    if (showSeasonMenu && currentSeason != null) {
        val seasonSizeStr = formatSize(currentSeason.totalSize)
        DownloadOptionsSheet(
            title    = "Season ${currentSeason.season}",
            subtitle = "${currentSeason.episodeGroups.size} episodes · $seasonSizeStr",
            options  = listOf(
                MenuOption("🗑", "Delete Season ${currentSeason.season}", isDestructive = true) {
                    vm.deleteSeason(currentSeason, ctx)
                },
            ),
            onDismiss = { showSeasonMenu = false },
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
                color         = White,
                fontSize      = (d.textXxl.value + 3f).sp,
                fontWeight    = FontWeight.Black,
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
                    color      = if (activeCount > 0) Brand else if (readyCount > 0) Success else White40,
                    fontSize   = d.textSm,
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
// Active Queue Strip
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
                    color         = White40,
                    fontSize      = (d.textXxs.value + 1f).sp,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
            }
            Text(
                "View All",
                color      = Brand,
                fontSize   = d.textXs,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier
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
    val isDownloading = item.status == DownloadStatus.DOWNLOADING
    val isPaused      = item.status == DownloadStatus.PAUSED
    val isQueued      = item.status == DownloadStatus.QUEUED
    val isError       = item.status == DownloadStatus.ERROR

    // Compute progress — prefer segment-based, fall back to byte-based
    val pct = downloadProgress(item)
    val animPct by animateFloatAsState(pct.coerceIn(0f, 1f), label = "aq-pct")
    val cardW = d.continueCardWidth + d.spaceLg

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
                color      = White,
                fontSize   = d.textXs,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            if (item.mediaType == "TV" && item.season > 0) {
                Text("S${item.season}E${item.episode}", color = White40, fontSize = (d.textXxs.value + 0.5f).sp)
            }
            if (item.quality.isNotBlank()) {
                Text(item.quality, color = Brand.copy(.8f), fontSize = (d.textXxs.value + 0.5f).sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(d.spaceXxs + 2.dp))
            Box(
                Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(GlassMd)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animPct)
                        .fillMaxHeight()
                        .background(
                            brush = when {
                                isError  -> SolidColor(Error)
                                isPaused -> SolidColor(White40)
                                isQueued -> SolidColor(White20)
                                else     -> Brush.horizontalGradient(listOf(Brand, Brand2))
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
                        isQueued -> "Waiting…"
                        isError  -> "Failed"
                        isPaused -> "${(pct * 100).toInt()}% · Paused"
                        else     -> "${(pct * 100).toInt()}%"
                    },
                    color    = if (isDownloading) Success.copy(.85f) else White40,
                    fontSize = (d.textXxs.value + 0.5f).sp,
                )
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
                            tint     = if (isPaused || isError) Brand else White60,
                            modifier = Modifier.size(d.iconSm - 4.dp),
                        )
                    }
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
                        color      = if (isSelected) White else White40,
                        fontSize   = d.textSm,
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
                                color      = if (isSelected) White else White40,
                                fontSize   = (d.textXxs.value + 0.5f).sp,
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
// Movie Group Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MovieGroupCard(
    group: MovieGroup,
    onPlay: (DownloadItem) -> Unit,
    onDelete: () -> Unit,
    onDeleteQuality: (DownloadItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    var showMenu by remember { mutableStateOf(false) }
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
        Box(
            Modifier
                .width(3.dp).fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Success.copy(.8f), Success.copy(.3f))))
                .clip(RoundedCornerShape(topStart = d.radiusLg, bottomStart = d.radiusLg))
        )

        Row(Modifier.fillMaxWidth().padding(d.spaceMd), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .width(d.avatarMd + d.spaceXxs + 2.dp)
                    .aspectRatio(2f / 3f)
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
                Box(Modifier.fillMaxSize().background(Color.Black.copy(.35f)), Alignment.Center) {
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape)
                            .background(Color.Black.copy(.55f))
                            .border(1.5.dp, White60, CircleShape),
                        Alignment.Center,
                    ) {
                        Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(d.iconSm + 2.dp).offset(x = 1.dp))
                    }
                }
                if (hasProgress) {
                    Box(
                        Modifier.fillMaxWidth().height(3.dp)
                            .background(Color.Black.copy(.5f))
                            .align(Alignment.BottomCenter)
                    ) {
                        Box(Modifier.fillMaxWidth(watchFraction).fillMaxHeight().background(Brand))
                    }
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
                        primary.title,
                        color      = White,
                        fontSize   = d.textMd,
                        fontWeight = FontWeight.Bold,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(d.spaceXs))
                    Box(
                        Modifier
                            .size(d.iconLg)
                            .clip(CircleShape)
                            .background(GlassMd)
                            .clickable { showMenu = true },
                        Alignment.Center,
                    ) { Text("⋮", color = White60, fontSize = d.textMd) }
                }

                Spacer(Modifier.height(d.spaceXs))
                if (group.totalSize > 0) {
                    Text(formatSize(group.totalSize), color = White40, fontSize = d.textXs)
                }
                Spacer(Modifier.height(d.spaceXs))
                MultiQualityBadges(doneDownloads.map { it.quality })
                Spacer(Modifier.height(d.spaceSm))

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
                    color      = if (hasProgress && watchFraction < 0.95f) Brand.copy(.8f) else White40,
                    fontSize   = (d.textXxs.value + 1f).sp,
                    fontWeight = if (hasProgress) FontWeight.SemiBold else FontWeight.Normal,
                )

                Spacer(Modifier.height(d.spaceMd))
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

    if (showMenu) {
        val qualityOptions = if (doneDownloads.size > 1)
            doneDownloads.map { item ->
                MenuOption("🗑", "Delete ${item.quality} (${formatSize(item.sizeBytes)})", isDestructive = true) {
                    onDeleteQuality(item)
                    showMenu = false
                }
            }
        else emptyList()

        DownloadOptionsSheet(
            title    = primary.title,
            subtitle = formatSize(group.totalSize),
            options  = buildList {
                add(MenuOption("▶", "Play") { onPlay(primary); showMenu = false })
                addAll(qualityOptions)
                add(MenuOption("🗑", "Delete All", isDestructive = true) { showDeleteDialog = true; showMenu = false })
            },
            onDismiss = { showMenu = false },
        )
    }

    if (showDeleteDialog) {
        ReelzDeleteDialog(
            title     = "Delete \"${primary.title}\"?",
            message   = "This will remove all ${doneDownloads.size} version${if (doneDownloads.size > 1) "s" else ""} (${formatSize(group.totalSize)}) from your device.",
            onDelete  = { onDelete(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Series root card
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
    var showMenu by remember { mutableStateOf(false) }
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
            Box(
                Modifier
                    .width(d.avatarMd + d.spaceXxs + 2.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(d.radiusSm + 2.dp))
                    .background(BgRaised)
            ) {
                AsyncImage(
                    model = group.posterPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (group.isFullyDownloaded) {
                    Box(
                        Modifier.fillMaxSize().background(Success.copy(.25f)),
                        Alignment.Center,
                    ) { Text("✓", color = Success, fontSize = d.textLg, fontWeight = FontWeight.Black) }
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(.25f)), Alignment.Center) {
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape)
                            .background(Color.Black.copy(.5f))
                            .border(1.5.dp, White60, CircleShape),
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
                        color      = White,
                        fontSize   = d.textMd,
                        fontWeight = FontWeight.Bold,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(d.spaceXs))
                    Box(
                        Modifier
                            .size(d.iconLg)
                            .clip(CircleShape)
                            .background(GlassMd)
                            .clickable(onClick = { showMenu = true }),
                        Alignment.Center,
                    ) { Text("⋮", color = White60, fontSize = d.textMd) }
                }

                Spacer(Modifier.height(d.spaceXs))
                Text("${group.doneEpisodes} Episodes ready", color = White40, fontSize = d.textXs)
                Spacer(Modifier.height(d.spaceXxs))
                Text("${group.seasonCount} Season${if (group.seasonCount > 1) "s" else ""}", color = White40, fontSize = d.textXs)
                Spacer(Modifier.height(d.spaceXs))

                if (group.lastWatchedLabel != null) {
                    Text("Last watched ${group.lastWatchedLabel}", color = White40, fontSize = (d.textXxs.value + 1f).sp)
                    Spacer(Modifier.height(d.spaceXs))
                }

                if (group.totalSize > 0) {
                    Text(formatSize(group.totalSize), color = White40, fontSize = d.textXs)
                    Spacer(Modifier.height(d.spaceXs))
                }

                Spacer(Modifier.height(d.spaceMd))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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

    if (showMenu) {
        DownloadOptionsSheet(
            title    = group.title,
            subtitle = "${group.doneEpisodes} episodes · ${group.seasonCount} season${if (group.seasonCount > 1) "s" else ""}${if (group.totalSize > 0) " · ${formatSize(group.totalSize)}" else ""}",
            options  = listOf(
                MenuOption("▶", "Resume Watching") { onPlay(); showMenu = false },
                MenuOption("📂", "Browse Episodes") { onTap(); showMenu = false },
                MenuOption("🗑", "Delete All Episodes", isDestructive = true) { showDeleteDialog = true; showMenu = false },
            ),
            onDismiss = { showMenu = false },
        )
    }

    if (showDeleteDialog) {
        ReelzDeleteDialog(
            title     = "Delete Series",
            message   = "Remove all downloaded episodes of \"${group.title}\"?",
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
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val primary = eg.primaryDownload
    val doneDownloads = eg.doneDownloads
    val label = if (eg.episodeName.isNotBlank()) eg.episodeName else "Episode ${eg.episode}"

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
            Box(
                Modifier
                    .width(d.avatarMd + d.spaceXxs + 2.dp)
                    .aspectRatio(16f / 9f)
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
                Box(Modifier.fillMaxSize().background(Color.Black.copy(.35f)), Alignment.Center) {
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape)
                            .background(Color.Black.copy(.55f))
                            .border(1.5.dp, White60, CircleShape),
                        Alignment.Center,
                    ) { Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(d.iconSm + 2.dp).offset(x = 1.dp)) }
                }
                if (hasProgress) {
                    Box(
                        Modifier.fillMaxWidth().height(3.dp)
                            .background(Color.Black.copy(.5f))
                            .align(Alignment.BottomCenter)
                    ) {
                        Box(Modifier.fillMaxWidth(watchFraction).fillMaxHeight().background(Brand))
                    }
                }
            }

            Spacer(Modifier.width(d.spaceMd))

            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Episode ${eg.episode}${if (eg.episodeName.isNotBlank()) " · ${eg.episodeName}" else ""}",
                            color      = White,
                            fontSize   = d.textSm,
                            fontWeight = FontWeight.Bold,
                            maxLines   = 2,
                            overflow   = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(d.spaceXs))
                    Box(
                        Modifier
                            .size(d.iconLg)
                            .clip(CircleShape)
                            .background(GlassMd)
                            .clickable { showMenu = true },
                        Alignment.Center,
                    ) { Text("⋮", color = White60, fontSize = d.textMd) }
                }

                Spacer(Modifier.height(d.spaceXs))
                if (eg.totalSize > 0) {
                    Text(formatSize(eg.totalSize), color = White40, fontSize = d.textXs)
                    Spacer(Modifier.height(d.spaceXxs))
                }
                MultiQualityBadges(doneDownloads.map { it.quality })
                Spacer(Modifier.height(d.spaceSm))

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
                    color      = if (hasProgress && watchFraction < 0.95f) Brand.copy(.8f) else White40,
                    fontSize   = (d.textXxs.value + 1f).sp,
                    fontWeight = if (hasProgress) FontWeight.SemiBold else FontWeight.Normal,
                )

                Spacer(Modifier.height(d.spaceMd))
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

    if (showMenu) {
        val qualityOptions = if (doneDownloads.size > 1)
            doneDownloads.map { item ->
                MenuOption("🗑", "Delete ${item.quality} (${formatSize(item.sizeBytes)})", isDestructive = true) {
                    onDeleteQuality(item)
                    showMenu = false
                }
            }
        else emptyList()

        DownloadOptionsSheet(
            title    = label,
            subtitle = "S${eg.season.toString().padStart(2,'0')}E${eg.episode.toString().padStart(2,'0')}${if (eg.totalSize > 0) " · ${formatSize(eg.totalSize)}" else ""}",
            options  = buildList {
                add(MenuOption("▶", "Play") { onPlay(primary); showMenu = false })
                addAll(qualityOptions)
                add(MenuOption("🗑", "Delete Episode", isDestructive = true) { showDeleteDialog = true; showMenu = false })
            },
            onDismiss = { showMenu = false },
        )
    }

    if (showDeleteDialog) {
        ReelzDeleteDialog(
            title     = "Delete Episode",
            message   = "Remove \"$label\"?",
            onDelete  = { onDelete(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Multi-quality badge row
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
// Empty states
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyDownloadsState() {
    val d = LocalDimensions.current
    Box(Modifier.fillMaxWidth().padding(top = d.spaceXxl * 2), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
            modifier = Modifier.padding(horizontal = d.spaceXxl),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(d.avatarLg + d.spaceXxl + d.spaceLg)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Brand.copy(.06f), Color.Transparent)))
                )
                Box(
                    Modifier.size(d.avatarLg + d.spaceXxl)
                        .clip(CircleShape)
                        .background(BlueGlass)
                        .border(1.dp, BlueBorder, CircleShape)
                )
                Box(
                    Modifier.size(d.avatarLg + d.spaceLg)
                        .clip(CircleShape)
                        .background(GlassSm)
                        .border(1.dp, GlassBorderMd, CircleShape)
                )
                Icon(
                    IconDownloadCloud,
                    contentDescription = null,
                    tint = Brand.copy(.8f),
                    modifier = Modifier.size(d.avatarSm + d.spaceMd),
                )
            }
            Spacer(Modifier.height(d.spaceXs))
            Text(
                "Your offline library is empty",
                color      = White,
                fontSize   = d.textXl,
                fontWeight = FontWeight.Bold,
                textAlign  = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                "Download movies & shows to watch anywhere — even without Wi-Fi or mobile data.",
                color     = White40,
                fontSize  = d.textSm,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = (d.textSm.value * 1.6f).sp,
            )
            Spacer(Modifier.height(d.spaceXs))
            Text(
                "Look for the ↓ icon on any title to save it for offline viewing.",
                color     = Brand.copy(.7f),
                fontSize  = d.textXs,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = (d.textXs.value * 1.5f).sp,
            )
        }
    }
}

/** Shown when active downloads exist but nothing is DONE yet. */
@Composable
private fun LibraryPendingState() {
    val d = LocalDimensions.current
    Box(Modifier.fillMaxWidth().padding(top = d.spaceXxl), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceSm),
            modifier = Modifier.padding(horizontal = d.spaceXxl),
        ) {
            Text(
                "Downloading…",
                color      = Brand,
                fontSize   = d.textLg,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Your content will appear here once it finishes downloading.",
                color     = White40,
                fontSize  = d.textSm,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = (d.textSm.value * 1.5f).sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared atoms
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StatusPill(status: DownloadStatus) {
    val d = LocalDimensions.current
    val (color, label) = when (status) {
        DownloadStatus.DONE        -> Success to "Ready"
        DownloadStatus.DOWNLOADING -> Brand to "Downloading"
        DownloadStatus.QUEUED      -> White60 to "Queued"
        DownloadStatus.PAUSED      -> White40 to "Paused"
        DownloadStatus.ERROR       -> Error to "Failed"
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
        if (status == DownloadStatus.DONE) {
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
@Composable fun StatusBadge(status: DownloadStatus) = StatusPill(status)

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
 * Unified progress fraction [0..1] for a DownloadItem.
 * Prefers segment-based progress for HLS (most accurate), falls back to
 * byte-based for MP4, then 0.
 */
fun downloadProgress(item: DownloadItem): Float = when {
    item.totalSegments > 0 ->
        item.segmentsDone.toFloat() / item.totalSegments
    item.sizeBytes > 0 ->
        (item.downloadedBytes.toFloat() / item.sizeBytes).coerceIn(0f, 1f)
    else -> 0f
}

private fun playDownload(ctx: Context, dl: DownloadItem) {
    val base = Intent(ctx, PlayerActivity::class.java).apply {
        putExtra("mediaId",          dl.mediaId)
        putExtra("mediaType",        dl.mediaType)
        putExtra("season",           dl.season)
        putExtra("episode",          dl.episode)
        putExtra("title",            dl.title)
        putExtra("posterUrl",        dl.posterUrl)
        putExtra("downloadId",       dl.id)
        putExtra("preferredQuality", dl.quality)
        putExtra("isOffline",        true)
    }
    when {
        dl.status == DownloadStatus.DONE && dl.filePath.isNotBlank() -> {
            val isHls = dl.filePath.endsWith(".m3u8", ignoreCase = true)
            base.putExtra("streamUrl",   "file://${dl.filePath}")
            base.putExtra("streamIsHls", isHls)
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
