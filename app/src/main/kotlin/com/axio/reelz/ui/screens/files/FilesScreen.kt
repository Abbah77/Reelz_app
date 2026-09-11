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
// Data structures — backed by files table (FileItem), not downloads table
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single movie quality entry shown as a flat row in the Movies list.
 * Avatar(480p), Avatar(720p), Avatar(1080p) each become their own card.
 */
data class MovieFileCard(
    val fileItem: FileItem,
) {
    val title: String         get() = fileItem.title
    val quality: String       get() = fileItem.quality
    val posterPath: String?   get() = fileItem.posterUrl
    val sizeBytes: Long       get() = fileItem.sizeBytes
    val watchProgressMs: Long get() = fileItem.watchProgressMs
    val durationMs: Long      get() = fileItem.durationMs
    val lastPlayedAt: Long    get() = fileItem.lastPlayedAt
    val addedAt: Long         get() = fileItem.addedAt
}

data class SeriesGroup(
    val mediaId: String,
    val title: String,
    val posterPath: String?,
    val seasons: List<SeasonGroup>,
) {
    val totalEpisodes: Int get() = seasons.sumOf { it.episodeGroups.size }
    val lastWatchedLabel: String? get() {
        val lastPlayed = seasons
            .flatMap { it.episodeGroups }
            .flatMap { it.files }
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
    val totalSize: Long get() = episodeGroups.sumOf { it.totalSize }
}

/**
 * All quality variants of one episode shown as separate rows inside the episode group.
 * episode1(360p), episode1(1080p) — each listed explicitly, no hiding/switching.
 */
data class EpisodeGroup(
    val mediaId: String,
    val season: Int,
    val episode: Int,
    val episodeName: String,
    val posterPath: String?,
    val files: List<FileItem>,         // all quality variants of this episode
) {
    val primaryFile: FileItem get() =
        files.maxByOrNull { it.lastPlayedAt }
            ?: files.maxByOrNull { it.sizeBytes }
            ?: files.first()
    val watchProgressMs: Long get() = primaryFile.watchProgressMs
    val durationMs: Long      get() = primaryFile.durationMs
    val lastPlayedAt: Long    get() = primaryFile.lastPlayedAt
    val totalSize: Long       get() = files.sumOf { it.sizeBytes }
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel — reads from files table, not downloads table
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val repo: DownloadRepository,
) : ViewModel() {

    // Active downloads (downloads table — in-progress only)
    val activeDownloads: StateFlow<List<DownloadItem>> = repo.observeAll()
        .map { list ->
            list.filter {
                it.status == DownloadStatus.DOWNLOADING
                    || it.status == DownloadStatus.QUEUED
                    || it.status == DownloadStatus.PAUSED
                    || it.status == DownloadStatus.REMUXING
                    || it.status == DownloadStatus.ERROR
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // All files from files table
    private val allFiles: StateFlow<List<FileItem>> = repo.observeFiles()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Movies: each (mediaId + quality) = one flat card at the root
    // Avatar(480p) and Avatar(720p) appear as two separate entries
    val movieCards: StateFlow<List<MovieFileCard>> = allFiles
        .map { list ->
            list.filter { it.mediaType == "MOVIE" }
                .map { MovieFileCard(it) }
                .sortedByDescending { it.addedAt }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Series: grouped by show → season → episode, qualities listed per episode
    val seriesGroups: StateFlow<List<SeriesGroup>> = allFiles
        .map { list -> buildSeriesGroups(list.filter { it.mediaType == "TV" }) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val readyCount: StateFlow<Int> = allFiles
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private fun buildSeriesGroups(items: List<FileItem>): List<SeriesGroup> =
        items.groupBy { it.mediaId }
            .map { (mediaId, eps) ->
                val seasons = eps
                    .groupBy { it.season }
                    .map { (season, seasonEps) ->
                        val episodeGroups = seasonEps
                            .groupBy { it.episode }
                            .map { (_, epItems) ->
                                EpisodeGroup(
                                    mediaId     = mediaId,
                                    season      = epItems.first().season,
                                    episode     = epItems.first().episode,
                                    episodeName = epItems.firstOrNull()?.episodeName ?: "",
                                    posterPath  = epItems.firstOrNull()?.posterUrl,
                                    files       = epItems.sortedByDescending { it.sizeBytes },
                                )
                            }
                            .sortedBy { it.episode }
                        SeasonGroup(season, episodeGroups)
                    }
                    .sortedBy { it.season }
                SeriesGroup(mediaId, eps.first().title, eps.first().posterUrl, seasons)
            }
            .filter { it.totalEpisodes > 0 }
            .sortedByDescending { g ->
                g.seasons.flatMap { it.episodeGroups }.flatMap { it.files }
                    .maxOfOrNull { it.addedAt } ?: 0L
            }

    fun deleteFile(item: FileItem, ctx: Context) {
        viewModelScope.launch { repo.deleteFile(item) }
    }

    fun deleteEpisodeGroup(eg: EpisodeGroup, ctx: Context) {
        viewModelScope.launch { eg.files.forEach { repo.deleteFile(it) } }
    }

    fun deleteSeries(group: SeriesGroup, ctx: Context) {
        viewModelScope.launch {
            group.seasons.flatMap { it.episodeGroups }.flatMap { it.files }.forEach { repo.deleteFile(it) }
        }
    }

    fun deleteSeason(season: SeasonGroup, ctx: Context) {
        viewModelScope.launch {
            season.episodeGroups.flatMap { it.files }.forEach { repo.deleteFile(it) }
        }
    }

    fun deleteMovieCard(card: MovieFileCard, ctx: Context) {
        viewModelScope.launch { repo.deleteFile(card.fileItem) }
    }

    // Active download controls
    fun resume(ctx: Context, item: DownloadItem) { viewModelScope.launch { repo.resume(ctx, item) } }
    fun pause(ctx: Context, item: DownloadItem)  { viewModelScope.launch { repo.pause(ctx, item) } }
    fun cancelDownload(item: DownloadItem, ctx: Context) { viewModelScope.launch { repo.delete(ctx, item) } }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom sheet menu
// ─────────────────────────────────────────────────────────────────────────────

data class MenuOption(
    val icon: String,
    val label: String,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit,
)

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
        dragHandle = {
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
                Text(title, color = White, fontSize = d.textMd, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                            .background(if (opt.isDestructive) Error.copy(.12f) else GlassSm),
                        Alignment.Center,
                    ) { Text(opt.icon, fontSize = d.textMd) }
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
// Screen root
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FilesScreen(nav: NavController, adEngine: com.axio.reelz.ads.AdEngine? = null, vm: DownloadsViewModel = hiltViewModel()) {
    val d               = LocalDimensions.current
    val ctx             = LocalContext.current
    val movieCards      by vm.movieCards.collectAsState()
    val seriesGroups    by vm.seriesGroups.collectAsState()
    val activeDownloads by vm.activeDownloads.collectAsState()
    val readyCount      by vm.readyCount.collectAsState()
    var tab             by remember { mutableStateOf(0) }

    val showMovies = tab == 0 || tab == 1
    val showSeries = tab == 0 || tab == 2
    val isEmpty    = movieCards.isEmpty() && seriesGroups.isEmpty()

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
                        movieCount  = movieCards.size,
                        seriesCount = seriesGroups.size,
                        onSelect    = { tab = it },
                    )
                }

                if (isEmpty && activeDownloads.isEmpty()) {
                    item { EmptyDownloadsState() }
                    adEngine?.let { engine ->
                        item { com.axio.reelz.ads.FilesScreenBanner(engine) }
                    }
                } else if (isEmpty) {
                    item { LibraryPendingState() }
                }

                // ── Movies: flat list — every (title + quality) is its own card ──
                if (showMovies && movieCards.isNotEmpty()) {
                    item {
                        SectionLabel(
                            "Movies",
                            "${movieCards.size} file${if (movieCards.size > 1) "s" else ""}",
                            modifier = Modifier.padding(horizontal = d.screenHorizPad, vertical = d.spaceSm),
                        )
                    }
                    items(movieCards, key = { "mc-${it.fileItem.id}" }) { card ->
                        MovieFileCardRow(
                            card     = card,
                            onPlay   = { playFile(ctx, card.fileItem) },
                            onDelete = { vm.deleteMovieCard(card, ctx) },
                            modifier = Modifier
                                .padding(horizontal = d.screenHorizPad)
                                .padding(bottom = d.spaceSm + d.spaceXxs),
                        )
                    }
                }

                // ── TV Shows ──────────────────────────────────────────────────
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
                                    .flatMap { it.files }
                                    .filter { it.lastPlayedAt > 0 }
                                    .maxByOrNull { it.lastPlayedAt }
                                val firstEp = group.seasons.firstOrNull()
                                    ?.episodeGroups?.firstOrNull()?.primaryFile
                                val toPlay = lastEp ?: firstEp
                                if (toPlay != null) playFile(ctx, toPlay)
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
    val listState = rememberLazyListState()
    var showSeasonMenu by remember { mutableStateOf(false) }

    LaunchedEffect(selectedSeason) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad, vertical = d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(d.iconLg + d.spaceSm).clip(CircleShape).background(GlassMd).clickable(onClick = onBack),
                Alignment.Center,
            ) { Text("←", color = White, fontSize = d.textLg, fontWeight = FontWeight.Bold) }
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
                    Modifier.size(d.iconLg + d.spaceSm).clip(CircleShape).background(GlassMd)
                        .clickable { showSeasonMenu = true },
                    Alignment.Center,
                ) { Text("⋮", color = White60, fontSize = d.textLg) }
            }
        }

        // Season tabs
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

        // Episode list — each episode shows all its quality variants as separate rows
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = d.screenHorizPad, vertical = d.spaceMd),
            verticalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            if (currentSeason != null) {
                items(currentSeason.episodeGroups, key = { "eg-${it.mediaId}-${it.season}-${it.episode}" }) { eg ->
                    EpisodeGroupCard(
                        eg              = eg,
                        onPlayFile      = { file -> playFile(ctx, file) },
                        onDeleteFile    = { file -> vm.deleteFile(file, ctx) },
                        onDeleteAll     = { vm.deleteEpisodeGroup(eg, ctx) },
                    )
                }
            }
        }
    }

    if (showSeasonMenu && currentSeason != null) {
        DownloadOptionsSheet(
            title    = "Season ${currentSeason.season}",
            subtitle = "${currentSeason.episodeGroups.size} episodes · ${formatSize(currentSeason.totalSize)}",
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
        Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad + d.spaceXxs, vertical = d.spaceLg),
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
// Active Queue Strip (shows in-progress downloads, not files)
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
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad, vertical = d.spaceXs),
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
                Box(Modifier.size(d.spaceXs + 2.dp).clip(CircleShape).background(Brand.copy(alpha = pulseAlpha)))
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
                    onCancel = { vm.cancelDownload(item, ctx) },
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
    val d           = LocalDimensions.current
    val isRemuxing  = item.status == DownloadStatus.REMUXING
    val isDownloading = item.status == DownloadStatus.DOWNLOADING || isRemuxing
    val isPaused    = item.status == DownloadStatus.PAUSED
    val isQueued    = item.status == DownloadStatus.QUEUED
    val isError     = item.status == DownloadStatus.ERROR

    val pct     = downloadProgress(item)
    val animPct by animateFloatAsState(pct.coerceIn(0f, 1f), label = "aq-pct")
    val cardW   = d.continueCardWidth + d.spaceLg

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
            AsyncImage(model = item.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }

        Column(Modifier.weight(1f)) {
            Text(item.title, color = White, fontSize = d.textXs, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.mediaType == "TV" && item.season > 0) {
                Text("S${item.season}E${item.episode}", color = White40, fontSize = (d.textXxs.value + 0.5f).sp)
            }
            if (item.quality.isNotBlank()) {
                Text(item.quality, color = Brand.copy(.8f), fontSize = (d.textXxs.value + 0.5f).sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(d.spaceXxs + 2.dp))

            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(GlassMd)) {
                Box(
                    Modifier.fillMaxWidth(animPct).fillMaxHeight().background(
                        brush = when {
                            isError   -> SolidColor(Error)
                            isPaused  -> SolidColor(White40)
                            isQueued  -> SolidColor(White20)
                            isRemuxing -> SolidColor(Color(0xFFFF9800))
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
                        isQueued   -> "Waiting…"
                        isError    -> "Failed"
                        isRemuxing -> "Converting…"
                        isPaused   -> "${(pct * 100).toInt()}% · Paused"
                        else       -> "${(pct * 100).toInt()}%"
                    },
                    color    = if (isDownloading) Success.copy(.85f) else White40,
                    fontSize = (d.textXxs.value + 0.5f).sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(d.spaceXs)) {
                    if (!isRemuxing) {
                        Box(
                            Modifier.size(d.iconMd + d.spaceXxs).clip(CircleShape).background(GlassMd)
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
                    }
                    Box(
                        Modifier.size(d.iconMd + d.spaceXxs).clip(CircleShape).background(GlassMd).clickable(onClick = onCancel),
                        Alignment.Center,
                    ) { Text("✕", color = White40, fontSize = (d.textXxs.value + 1f).sp) }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab filter bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TabFilterBar(selected: Int, movieCount: Int, seriesCount: Int, onSelect: (Int) -> Unit) {
    val d = LocalDimensions.current
    val tabs = listOf("All" to null, "Movies" to movieCount, "Shows" to seriesCount)
    Row(Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad), horizontalArrangement = Arrangement.spacedBy(d.spaceXs + 1.dp)) {
        tabs.forEachIndexed { i, (label, count) ->
            val isSelected = selected == i
            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(if (isSelected) Brush.horizontalGradient(listOf(BrandDeep.copy(.9f), Brand.copy(.8f))) else SolidColor(GlassSm))
                    .border(1.dp, if (isSelected) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(d.radiusPill))
                    .clickable { onSelect(i) }
                    .padding(horizontal = d.spaceLg - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp)) {
                    Text(label, color = if (isSelected) White else White40, fontSize = d.textSm, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                    if (count != null && count > 0) {
                        Box(
                            Modifier.clip(CircleShape).background(if (isSelected) White20 else GlassMd).padding(horizontal = d.spaceXs, vertical = 1.dp),
                            Alignment.Center,
                        ) { Text("$count", color = if (isSelected) White else White40, fontSize = (d.textXxs.value + 0.5f).sp, fontWeight = FontWeight.Bold) }
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
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
            Box(
                Modifier.width(d.sectionAccentWidth).height(d.sectionAccentHeight + d.spaceXxs)
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(Brush.verticalGradient(listOf(Brand, Brand.copy(.3f))))
            )
            Text(title, color = White, fontSize = (d.textMd.value + 0.5f).sp, fontWeight = FontWeight.Bold)
        }
        Text(subtitle, color = White40, fontSize = d.textXs, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Movie file card — one card per (title + quality), flat at root
// e.g. Avatar(480p) and Avatar(720p) are two separate rows
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MovieFileCardRow(
    card: MovieFileCard,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val watchFraction = if (card.durationMs > 0) (card.watchProgressMs.toFloat() / card.durationMs).coerceIn(0f, 1f) else 0f
    val hasProgress = card.watchProgressMs > 0 && card.durationMs > 0

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(BgCard)
            .border(1.dp, Success.copy(.2f), RoundedCornerShape(d.radiusLg - d.spaceXxs))
    ) {
        Box(
            Modifier.width(3.dp).fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Success.copy(.8f), Success.copy(.3f))))
                .clip(RoundedCornerShape(topStart = d.radiusLg, bottomStart = d.radiusLg))
        )

        Row(Modifier.fillMaxWidth().padding(d.spaceMd), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(d.avatarMd + d.spaceXxs + 2.dp)
                    .height(d.avatarLg + d.spaceXxs)
                    .clip(RoundedCornerShape(d.radiusSm + 2.dp))
                    .background(BgRaised)
                    .clickable(onClick = onPlay)
            ) {
                AsyncImage(model = card.posterPath, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(.35f)), Alignment.Center) {
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape).background(Color.Black.copy(.55f)).border(1.5.dp, White60, CircleShape),
                        Alignment.Center,
                    ) { Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(d.iconSm + 2.dp).offset(x = 1.dp)) }
                }
                if (hasProgress) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color.Black.copy(.5f)).align(Alignment.BottomCenter)) {
                        Box(Modifier.fillMaxWidth(watchFraction).fillMaxHeight().background(Brand))
                    }
                }
            }

            Spacer(Modifier.width(d.spaceMd))

            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    // Title (quality) — e.g. "Avatar (720p)"
                    Text(
                        "${card.title} (${card.quality})",
                        color      = White,
                        fontSize   = d.textMd,
                        fontWeight = FontWeight.Bold,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(d.spaceXs))
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape).background(GlassMd).clickable { showMenu = true },
                        Alignment.Center,
                    ) { Text("⋮", color = White60, fontSize = d.textMd) }
                }

                Spacer(Modifier.height(d.spaceXxs))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        if (card.sizeBytes > 0) Text(formatSize(card.sizeBytes), color = White40, fontSize = d.textXs)
                        Spacer(Modifier.height(d.spaceXxs))
                        Text(
                            when {
                                hasProgress -> {
                                    val pct = (watchFraction * 100).toInt()
                                    if (pct >= 95) "Watched" else "$pct% watched"
                                }
                                card.lastPlayedAt > 0 -> "Played recently"
                                else -> "Not opened"
                            },
                            color      = if (hasProgress && watchFraction < 0.95f) Brand.copy(.8f) else White40,
                            fontSize   = (d.textXxs.value + 1f).sp,
                            fontWeight = if (hasProgress) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(d.radiusPill))
                            .background(Brand.copy(.15f))
                            .border(1.dp, Brand.copy(.35f), RoundedCornerShape(d.radiusPill))
                            .clickable(onClick = onPlay)
                            .padding(horizontal = d.spaceMd, vertical = d.spaceXxs + 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp)) {
                            Icon(IconPlay, null, tint = Brand, modifier = Modifier.size(d.iconSm - 1.dp))
                            Text("Play", color = Brand, fontSize = d.textXs, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showMenu) {
        DownloadOptionsSheet(
            title    = "${card.title} (${card.quality})",
            subtitle = if (card.sizeBytes > 0) formatSize(card.sizeBytes) else "",
            options  = listOf(
                MenuOption("▶", "Play") { onPlay(); showMenu = false },
                MenuOption("🗑", "Delete", isDestructive = true) { showDeleteDialog = true; showMenu = false },
            ),
            onDismiss = { showMenu = false },
        )
    }

    if (showDeleteDialog) {
        ReelzDeleteDialog(
            title     = "Delete \"${card.title} (${card.quality})\"?",
            message   = "This will remove this file (${formatSize(card.sizeBytes)}) from your device.",
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
    val totalEpisodes = group.totalEpisodes

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .clickable(onClick = onTap)
    ) {
        Row(Modifier.fillMaxWidth().padding(d.spaceMd), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(d.avatarMd + d.spaceXxs + 2.dp)
                    .height(d.avatarLg + d.spaceXxs)
                    .clip(RoundedCornerShape(d.radiusSm + 2.dp))
                    .background(BgRaised)
            ) {
                AsyncImage(model = group.posterPath, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(.25f)), Alignment.Center) {
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape).background(Color.Black.copy(.5f)).border(1.5.dp, White60, CircleShape),
                        Alignment.Center,
                    ) { Icon(IconPlay, null, tint = Color.White, modifier = Modifier.size(d.iconSm + 2.dp).offset(x = 1.dp)) }
                }
            }

            Spacer(Modifier.width(d.spaceMd))

            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(group.title, color = White, fontSize = d.textMd, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(d.spaceXs))
                    Box(
                        Modifier.size(d.iconLg).clip(CircleShape).background(GlassMd).clickable { showMenu = true },
                        Alignment.Center,
                    ) { Text("⋮", color = White60, fontSize = d.textMd) }
                }

                Spacer(Modifier.height(d.spaceXxs))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            buildString {
                                append("$totalEpisodes ep · ${group.seasonCount} season${if (group.seasonCount > 1) "s" else ""}")
                                if (group.totalSize > 0) append(" · ${formatSize(group.totalSize)}")
                            },
                            color = White40, fontSize = d.textXs,
                        )
                        if (group.lastWatchedLabel != null) {
                            Spacer(Modifier.height(d.spaceXxs))
                            Text("Last: ${group.lastWatchedLabel}", color = White40, fontSize = (d.textXxs.value + 1f).sp)
                        }
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(d.radiusPill))
                            .background(Brand.copy(.15f))
                            .border(1.dp, Brand.copy(.35f), RoundedCornerShape(d.radiusPill))
                            .clickable(onClick = onPlay)
                            .padding(horizontal = d.spaceMd, vertical = d.spaceXxs + 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp)) {
                            Icon(IconPlay, null, tint = Brand, modifier = Modifier.size(d.iconSm - 1.dp))
                            Text("Play", color = Brand, fontSize = d.textXs, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showMenu) {
        DownloadOptionsSheet(
            title    = group.title,
            subtitle = "$totalEpisodes episodes · ${group.seasonCount} season${if (group.seasonCount > 1) "s" else ""}${if (group.totalSize > 0) " · ${formatSize(group.totalSize)}" else ""}",
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
// Episode group card — shows all quality variants as separate rows
// e.g. Episode 1 (360p), Episode 1 (1080p) listed individually with 3-dot menu
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun EpisodeGroupCard(
    eg: EpisodeGroup,
    onPlayFile: (FileItem) -> Unit,
    onDeleteFile: (FileItem) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    var showGroupMenu by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    val label = if (eg.episodeName.isNotBlank()) eg.episodeName else "Episode ${eg.episode}"

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(BgCard)
            .border(1.dp, Success.copy(.15f), RoundedCornerShape(d.radiusLg - d.spaceXxs))
    ) {
        // Episode header row
        Box(
            Modifier.width(3.dp).height(2.dp)
                .background(Brush.verticalGradient(listOf(Success.copy(.7f), Success.copy(.2f))))
                .clip(RoundedCornerShape(topStart = d.radiusLg, bottomStart = d.radiusLg))
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.spaceMd, vertical = d.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "E${eg.episode}${if (eg.episodeName.isNotBlank()) " · ${eg.episodeName}" else ""}",
                color      = White60,
                fontSize   = d.textXs,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.weight(1f),
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            if (eg.files.size > 1) {
                Box(
                    Modifier.size(d.iconLg).clip(CircleShape).background(GlassMd).clickable { showGroupMenu = true },
                    Alignment.Center,
                ) { Text("⋮", color = White60, fontSize = d.textMd) }
            }
        }

        HorizontalDivider(color = GlassBorder.copy(alpha = 0.5f), thickness = 0.5.dp)

        // Each quality variant as a separate row
        eg.files.forEach { file ->
            EpisodeQualityRow(
                file          = file,
                episodeLabel  = label,
                season        = eg.season,
                episode       = eg.episode,
                onPlay        = { onPlayFile(file) },
                onDelete      = { onDeleteFile(file) },
            )
            if (file != eg.files.last()) {
                HorizontalDivider(color = GlassBorder.copy(alpha = 0.3f), thickness = 0.3.dp, modifier = Modifier.padding(horizontal = d.spaceMd))
            }
        }
    }

    if (showGroupMenu) {
        DownloadOptionsSheet(
            title    = label,
            subtitle = "S${eg.season.toString().padStart(2,'0')}E${eg.episode.toString().padStart(2,'0')} · ${eg.files.size} qualities · ${formatSize(eg.totalSize)}",
            options  = listOf(
                MenuOption("🗑", "Delete All Qualities", isDestructive = true) { showDeleteAllDialog = true; showGroupMenu = false },
            ),
            onDismiss = { showGroupMenu = false },
        )
    }

    if (showDeleteAllDialog) {
        ReelzDeleteDialog(
            title     = "Delete Episode",
            message   = "Remove all ${eg.files.size} quality versions of \"$label\"?",
            onDelete  = { onDeleteAll(); showDeleteAllDialog = false },
            onDismiss = { showDeleteAllDialog = false },
        )
    }
}

// One quality row inside an episode group
@Composable
private fun EpisodeQualityRow(
    file: FileItem,
    episodeLabel: String,
    season: Int,
    episode: Int,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val d = LocalDimensions.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val watchFraction = if (file.durationMs > 0) (file.watchProgressMs.toFloat() / file.durationMs).coerceIn(0f, 1f) else 0f
    val hasProgress = file.watchProgressMs > 0 && file.durationMs > 0

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = d.spaceMd, vertical = d.spaceSm + d.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
    ) {
        // Thumbnail
        Box(
            Modifier
                .width(d.avatarSm + d.spaceMd)
                .height(d.avatarSm + d.spaceXxs)
                .clip(RoundedCornerShape(d.radiusSm))
                .background(BgRaised)
        ) {
            AsyncImage(model = file.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(.3f)), Alignment.Center) {
                Icon(IconPlay, null, tint = White60, modifier = Modifier.size(d.iconSm - 2.dp))
            }
            if (hasProgress) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color.Black.copy(.5f)).align(Alignment.BottomCenter)) {
                    Box(Modifier.fillMaxWidth(watchFraction).fillMaxHeight().background(Brand))
                }
            }
        }

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp)) {
                // Quality badge
                Box(
                    Modifier
                        .clip(RoundedCornerShape(d.radiusPill))
                        .background(Brand.copy(.15f))
                        .border(1.dp, Brand.copy(.3f), RoundedCornerShape(d.radiusPill))
                        .padding(horizontal = d.spaceSm, vertical = 2.dp)
                ) {
                    Text(file.quality, color = Brand, fontSize = (d.textXxs.value + 1f).sp, fontWeight = FontWeight.Bold)
                }
                if (file.sizeBytes > 0) {
                    Text(formatSize(file.sizeBytes), color = White40, fontSize = (d.textXxs.value + 0.5f).sp)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    hasProgress -> {
                        val pct = (watchFraction * 100).toInt()
                        if (pct >= 95) "Watched" else "$pct% watched"
                    }
                    file.lastPlayedAt > 0 -> "Played recently"
                    else -> "Not opened"
                },
                color    = if (hasProgress && watchFraction < 0.95f) Brand.copy(.7f) else White40,
                fontSize = (d.textXxs.value + 0.5f).sp,
            )
        }

        // 3-dot action menu
        Box(
            Modifier.size(d.iconLg).clip(CircleShape).background(GlassMd).clickable { showMenu = true },
            Alignment.Center,
        ) { Text("⋮", color = White60, fontSize = d.textSm) }
    }

    if (showMenu) {
        DownloadOptionsSheet(
            title    = "$episodeLabel (${file.quality})",
            subtitle = "S${season.toString().padStart(2,'0')}E${episode.toString().padStart(2,'0')}${if (file.sizeBytes > 0) " · ${formatSize(file.sizeBytes)}" else ""}",
            options  = listOf(
                MenuOption("▶", "Play (${file.quality})") { onPlay(); showMenu = false },
                MenuOption("🗑", "Delete ${file.quality}", isDestructive = true) { showDeleteDialog = true; showMenu = false },
            ),
            onDismiss = { showMenu = false },
        )
    }

    if (showDeleteDialog) {
        ReelzDeleteDialog(
            title     = "Delete ${file.quality} version?",
            message   = "Remove the ${file.quality} copy of \"$episodeLabel\"${if (file.sizeBytes > 0) " (${formatSize(file.sizeBytes)})" else ""}?",
            onDelete  = { onDelete(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
        )
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
                Box(Modifier.size(d.avatarLg + d.spaceXxl + d.spaceLg).clip(CircleShape).background(Brush.radialGradient(listOf(Brand.copy(.06f), Color.Transparent))))
                Box(Modifier.size(d.avatarLg + d.spaceXxl).clip(CircleShape).background(BlueGlass).border(1.dp, BlueBorder, CircleShape))
                Box(Modifier.size(d.avatarLg + d.spaceLg).clip(CircleShape).background(GlassSm).border(1.dp, GlassBorderMd, CircleShape))
                Icon(IconDownloadCloud, contentDescription = null, tint = Brand.copy(.8f), modifier = Modifier.size(d.avatarSm + d.spaceMd))
            }
            Spacer(Modifier.height(d.spaceXs))
            Text("Your offline library is empty", color = White, fontSize = d.textXl, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(
                "Download movies & shows to watch anywhere — even without Wi-Fi or mobile data.",
                color = White40, fontSize = d.textSm, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = (d.textSm.value * 1.6f).sp,
            )
            Spacer(Modifier.height(d.spaceXs))
            Text(
                "Look for the ↓ icon on any title to save it for offline viewing.",
                color = Brand.copy(.7f), fontSize = d.textXs, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = (d.textXs.value * 1.5f).sp,
            )
        }
    }
}

@Composable
private fun LibraryPendingState() {
    val d = LocalDimensions.current
    Box(Modifier.fillMaxWidth().padding(top = d.spaceXxl), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceSm),
            modifier = Modifier.padding(horizontal = d.spaceXxl),
        ) {
            Text("Downloading…", color = Brand, fontSize = d.textLg, fontWeight = FontWeight.Bold)
            Text(
                "Your content will appear here once it finishes downloading.",
                color = White40, fontSize = d.textSm, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = (d.textSm.value * 1.5f).sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared atoms (kept for compatibility — used in other screens)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StatusPill(status: DownloadStatus) {
    val d = LocalDimensions.current
    val (color, label) = when (status) {
        DownloadStatus.DOWNLOADING -> Brand to "Downloading"
        DownloadStatus.QUEUED      -> White60 to "Queued"
        DownloadStatus.PAUSED      -> White40 to "Paused"
        DownloadStatus.REMUXING    -> Color(0xFFFF9800) to "Converting"
        DownloadStatus.ERROR       -> Error to "Failed"
    }
    Row(
        Modifier.clip(RoundedCornerShape(d.radiusPill)).background(color.copy(.12f)).border(1.dp, color.copy(.3f), RoundedCornerShape(d.radiusPill)).padding(horizontal = d.spaceSm + 1.dp, vertical = d.spaceXxs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp),
    ) {
        Text(label, color = color, fontSize = (d.textXxs.value + 1f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun QualityChip(quality: String) {
    val d = LocalDimensions.current
    if (quality.isBlank()) return
    Box(Modifier.clip(RoundedCornerShape(d.radiusPill)).background(GlassMd).padding(horizontal = d.spaceSm, vertical = d.spaceXxs + 1.dp)) {
        Text(quality, color = White40, fontSize = (d.textXxs.value + 1f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable fun QualityBadge(quality: String) = QualityChip(quality)
@Composable fun StatusBadge(status: DownloadStatus) = StatusPill(status)

@Composable
fun MultiQualityBadges(qualities: List<String>) {
    if (qualities.isEmpty()) return
    val d = LocalDimensions.current
    Row(horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp), verticalAlignment = Alignment.CenterVertically) {
        qualities.distinct().sorted().forEach { q ->
            if (q.isNotBlank()) {
                Box(
                    Modifier.clip(RoundedCornerShape(d.radiusPill)).background(GlassMd).border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusPill)).padding(horizontal = d.spaceSm, vertical = d.spaceXxs + 1.dp)
                ) { Text(q, color = White60, fontSize = (d.textXxs.value + 1f).sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ReelzDeleteDialog(title: String, message: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val d = LocalDimensions.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgCard,
        shape            = RoundedCornerShape(d.radiusLg),
        title  = { Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = d.textLg) },
        text   = { Text(message, color = White60, fontSize = d.textMd) },
        confirmButton = {
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusPill)).background(Error.copy(.15f)).border(1.dp, Error.copy(.35f), RoundedCornerShape(d.radiusPill)).clickable(onClick = onDelete).padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Delete", color = Error, fontWeight = FontWeight.Bold, fontSize = d.textSm) }
        },
        dismissButton = {
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusPill)).background(GlassMd).clickable(onClick = onDismiss).padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Cancel", color = White60, fontSize = d.textSm) }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

fun downloadProgress(item: DownloadItem): Float = when {
    item.status == DownloadStatus.REMUXING -> 0.95f   // show near-done during remux
    item.totalSegments > 0 -> item.segmentsDone.toFloat() / item.totalSegments
    item.sizeBytes > 0     -> (item.downloadedBytes.toFloat() / item.sizeBytes).coerceIn(0f, 1f)
    else -> 0f
}

// Play a file from the files table — always .mp4, never HLS
private fun playFile(ctx: Context, file: FileItem) {
    val intent = Intent(ctx, PlayerActivity::class.java).apply {
        putExtra("mediaId",          file.mediaId)
        putExtra("mediaType",        file.mediaType)
        putExtra("season",           file.season)
        putExtra("episode",          file.episode)
        putExtra("title",            file.title)
        putExtra("posterUrl",        file.posterUrl)
        putExtra("downloadId",       file.id)
        putExtra("preferredQuality", file.quality)
        putExtra("isOffline",        true)
        putExtra("streamUrl",        "file://${file.filePath}")
        putExtra("streamIsHls",      false)   // files table = always .mp4
    }
    ctx.startActivity(intent)
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
