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
import com.axio.reelz.BuildConfig
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
// Grouped view models — built client-side from the flat DownloadItem list.
// No DB schema changes needed; getAll() already returns everything.
// ─────────────────────────────────────────────────────────────────────────────

/** All downloaded/queued episodes belonging to one TV series (grouped by tmdbId). */
data class SeriesGroup(
    val tmdbId: Int,
    val title: String,
    val posterPath: String?,
    val seasons: List<SeasonGroup>,
) {
    val totalEpisodes: Int get() = seasons.sumOf { it.episodes.size }
    val doneEpisodes: Int get() = seasons.sumOf { season -> season.episodes.count { it.status == DownloadStatus.DONE.name } }
    val isFullyDownloaded: Boolean get() = totalEpisodes > 0 && doneEpisodes == totalEpisodes
    val isAnyActive: Boolean get() = seasons.any { s -> s.episodes.any { it.status == DownloadStatus.DOWNLOADING.name || it.status == DownloadStatus.QUEUED.name } }
}

data class SeasonGroup(
    val season: Int,
    val episodes: List<DownloadItem>,
) {
    val doneCount: Int get() = episodes.count { it.status == DownloadStatus.DONE.name }
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val dao: DownloadDao,
    private val repo: DownloadRepository,
) : ViewModel() {

    private val allDownloads: StateFlow<List<DownloadItem>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val movies: StateFlow<List<DownloadItem>> = allDownloads
        .map { list -> list.filter { it.mediaType == "MOVIE" } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val seriesGroups: StateFlow<List<SeriesGroup>> = allDownloads
        .map { list -> buildSeriesGroups(list.filter { it.mediaType == "TV" }) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val totalCount: StateFlow<Int> = allDownloads
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val readyCount: StateFlow<Int> = allDownloads
        .map { list -> list.count { it.status == DownloadStatus.DONE.name } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private fun buildSeriesGroups(items: List<DownloadItem>): List<SeriesGroup> =
        items.groupBy { it.tmdbId }
            .map { (tmdbId, eps) ->
                val seasons = eps.groupBy { it.season }
                    .map { (season, seasonEps) ->
                        SeasonGroup(season, seasonEps.sortedBy { it.episode })
                    }
                    .sortedBy { it.season }
                SeriesGroup(
                    tmdbId     = tmdbId,
                    title      = eps.first().title,
                    posterPath = eps.first().posterPath,
                    seasons    = seasons,
                )
            }
            .sortedByDescending { group -> group.seasons.flatMap { it.episodes }.maxOf { it.createdAt } }

    fun delete(item: DownloadItem, ctx: Context) { viewModelScope.launch { repo.delete(ctx, item) } }
    fun resume(ctx: Context, item: DownloadItem)  { viewModelScope.launch { repo.resume(ctx, item) } }
    fun pause(ctx: Context, item: DownloadItem)   { viewModelScope.launch { repo.pause(ctx, item) } }

    fun deleteSeries(group: SeriesGroup, ctx: Context) {
        viewModelScope.launch {
            group.seasons.flatMap { it.episodes }.forEach { repo.delete(ctx, it) }
        }
    }

    fun deleteSeason(season: SeasonGroup, ctx: Context) {
        viewModelScope.launch {
            season.episodes.forEach { repo.delete(ctx, it) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DownloadsScreen(nav: NavController, vm: DownloadsViewModel = hiltViewModel()) {
    val ctx          = LocalContext.current
    val movies        by vm.movies.collectAsState()
    val seriesGroups   by vm.seriesGroups.collectAsState()
    val readyCount    by vm.readyCount.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val d = LocalDimensions.current

    // Which series / season keys are expanded. Collapsed by default — progressive disclosure.
    val expandedSeries = remember { mutableStateOf(setOf<Int>()) }
    val expandedSeasons = remember { mutableStateOf(setOf<String>()) } // key = "tmdbId:season"

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {

        // ── Header ─────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.heroPadding - d.spaceSm, vertical = d.spaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp
                    )
                )
                if (readyCount > 0) {
                    Text(
                        "$readyCount file${if (readyCount > 1) "s" else ""} ready to watch",
                        color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // Transfer is a utility, not a bottom-nav pillar — its entry point
            // lives here since Downloads and Transfer both deal with getting
            // files onto the device.
            Row(
                Modifier
                    .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                    .background(Brush.horizontalGradient(listOf(BrandDeep, Brand.copy(.85f))))
                    .border(1.dp, Brand.copy(.5f), RoundedCornerShape(d.radiusMd - d.spaceXxs))
                    .clickable { nav.navigate(Route.Transfer.path) }
                    .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceMd - d.spaceXxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
            ) {
                Icon(IconSwap, null, tint = Color.White, modifier = Modifier.size(d.iconSm + 3.dp))
                Text("Transfer", color = Color.White, fontSize = d.textSm, fontWeight = FontWeight.Bold)
            }
        }

        // ── Tab filter ─────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad),
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
        ) {
            listOf("All", "Movies", "TV Shows").forEachIndexed { i, label ->
                GenrePill(label, tab == i) { tab = i }
            }
        }
        Spacer(Modifier.height(d.spaceMd))

        val showMovies = tab == 0 || tab == 1
        val showSeries = tab == 0 || tab == 2
        val isEmpty = (!showMovies || movies.isEmpty()) && (!showSeries || seriesGroups.isEmpty())

        if (isEmpty) {
            EmptyDownloadsState()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = d.screenHorizPad, vertical = d.sectionVertPad),
                verticalArrangement = Arrangement.spacedBy(d.spaceMd),
            ) {
                if (showSeries) {
                    items(seriesGroups, key = { "series-${it.tmdbId}" }) { group ->
                        SeriesCard(
                            group           = group,
                            expanded        = group.tmdbId in expandedSeries.value,
                            expandedSeasons = expandedSeasons.value,
                            onToggle        = {
                                expandedSeries.value = toggle(expandedSeries.value, group.tmdbId)
                            },
                            onToggleSeason  = { seasonKey ->
                                expandedSeasons.value = toggle(expandedSeasons.value, seasonKey)
                            },
                            onPlay          = { playDownload(ctx, it) },
                            onDelete        = { vm.delete(it, ctx) },
                            onResume        = { vm.resume(ctx, it) },
                            onPause         = { vm.pause(ctx, it) },
                            onDeleteSeries  = { vm.deleteSeries(group, ctx) },
                            onDeleteSeason  = { vm.deleteSeason(it, ctx) },
                        )
                    }
                }
                if (showMovies) {
                    items(movies, key = { "movie-${it.id}" }) { dl ->
                        MovieRow(
                            item     = dl,
                            onPlay   = { playDownload(ctx, dl) },
                            onDelete = { vm.delete(dl, ctx) },
                            onResume = { vm.resume(ctx, dl) },
                            onPause  = { vm.pause(ctx, dl) },
                        )
                    }
                }
                item { Spacer(Modifier.height(d.spaceXxl * 2.8f)) }
            }
        }
    }
}

private fun toggle(set: Set<Int>, key: Int): Set<Int> =
    if (key in set) set - key else set + key

private fun toggle(set: Set<String>, key: String): Set<String> =
    if (key in set) set - key else set + key

@Composable
private fun EmptyDownloadsState() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs)) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(d.avatarLg + d.spaceXl - d.spaceXs).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(AmberGlass, Color.Transparent)))
                    .border(1.dp, AmberBorder, CircleShape))
                Icon(IconDownloadCloud, null, tint = Brand.copy(.7f), modifier = Modifier.size(d.avatarSm + d.spaceSm))
            }
            Text("No downloads yet", color = White60, fontSize = d.textXl, fontWeight = FontWeight.SemiBold)
            Text("Save movies & shows to watch offline", color = White40, fontSize = d.textMd)
        }
    }
}

private fun playDownload(ctx: Context, dl: DownloadItem) {
    val base = Intent(ctx, PlayerActivity::class.java).apply {
        putExtra("tmdbId",      dl.tmdbId)
        putExtra("mediaType",   dl.mediaType)
        putExtra("season",      dl.season)
        putExtra("episode",     dl.episode)
        putExtra("title",       dl.title)
        putExtra("posterPath",  dl.posterPath)
        putExtra("downloadId",  dl.id)   // tells player this is offline mode
    }
    when {
        // Fully downloaded MP4 — play directly as progressive
        dl.status == DownloadStatus.DONE.name && dl.filePath.isNotBlank() -> {
            base.putExtra("streamUrl",   "file://${dl.filePath}")
            base.putExtra("streamIsHls", false)
            ctx.startActivity(base)
        }
        // Partial download with a local HLS playlist — play in-progress content
        dl.localPlaylistPath.isNotBlank() -> {
            base.putExtra("streamUrl",   "file://${dl.localPlaylistPath}")
            base.putExtra("streamIsHls", true)
            ctx.startActivity(base)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LEVEL 1 — Series card (collapsed by default)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SeriesCard(
    group: SeriesGroup,
    expanded: Boolean,
    expandedSeasons: Set<String>,
    onToggle: () -> Unit,
    onToggleSeason: (String) -> Unit,
    onPlay: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
    onResume: (DownloadItem) -> Unit,
    onPause: (DownloadItem) -> Unit,
    onDeleteSeries: () -> Unit,
    onDeleteSeason: (SeasonGroup) -> Unit,
) {
    val d = LocalDimensions.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(BgCard)
            .border(1.dp, if (expanded) Brand.copy(.35f) else GlassBorderMd, RoundedCornerShape(d.radiusLg - d.spaceXxs))
    ) {
        // ── Series header row — tap to expand/collapse ───────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = group.posterPath?.let { "${BuildConfig.TMDB_IMG_W342}$it" },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = d.avatarMd + d.spaceXxs, height = d.avatarLg).clip(RoundedCornerShape(d.radiusSm + d.spaceXxs)).background(BgRaised),
            )
            Spacer(Modifier.width(d.spaceMd - d.spaceXxs))

            Column(Modifier.weight(1f)) {
                Text(
                    group.title,
                    color = White, fontSize = d.textMd, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(d.spaceXs))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                    SeriesStatusDot(group)
                    Text(
                        "${group.seasons.size} season${if (group.seasons.size > 1) "s" else ""} · ${group.doneEpisodes}/${group.totalEpisodes} episodes",
                        color = White40, fontSize = d.textXs,
                    )
                }
                Spacer(Modifier.height(d.spaceSm))
                // Slim aggregate progress bar — Zeigarnik nudge for incomplete sets
                val pct = if (group.totalEpisodes > 0) group.doneEpisodes.toFloat() / group.totalEpisodes else 0f
                Box(Modifier.fillMaxWidth(0.85f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(GlassMd)) {
                    if (group.isFullyDownloaded) {
                        Box(Modifier.fillMaxWidth(pct).fillMaxHeight().background(Success))
                    } else {
                        Box(Modifier.fillMaxWidth(pct).fillMaxHeight().background(Brush.horizontalGradient(listOf(Brand, Brand2))))
                    }
                }
            }

            Spacer(Modifier.width(d.spaceSm + d.spaceXxs))
            Icon(
                IconChevronDown, null, tint = White40,
                modifier = Modifier.size(d.iconMd).rotate(chevronRotation),
            )
            Spacer(Modifier.width(d.spaceXs))
            Box(
                Modifier.size(d.iconLg).clip(CircleShape).background(GlassMd)
                    .clickable { showDeleteDialog = true },
                Alignment.Center,
            ) { Text("✕", color = White40, fontSize = d.textSm) }
        }

        // ── Seasons (collapsible) ─────────────────────────────────────────
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = d.screenHorizPad - d.spaceXxs, end = d.spaceMd, bottom = d.spaceSm + d.spaceXxs)) {
                group.seasons.forEach { season ->
                    val seasonKey = "${group.tmdbId}:${season.season}"
                    SeasonRow(
                        season         = season,
                        expanded       = seasonKey in expandedSeasons,
                        onToggle       = { onToggleSeason(seasonKey) },
                        onPlay         = onPlay,
                        onDelete       = onDelete,
                        onResume       = onResume,
                        onPause        = onPause,
                        onDeleteSeason = { onDeleteSeason(season) },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = BgCard,
            shape = RoundedCornerShape(d.radiusLg),
            title = { Text("Delete Series", color = White, fontWeight = FontWeight.Bold) },
            text  = { Text("Remove all downloaded episodes of \"${group.title}\"?", color = White60) },
            confirmButton = {
                TextButton(onClick = { onDeleteSeries(); showDeleteDialog = false }) { Text("Delete All", color = Error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = White60) }
            },
        )
    }
}

@Composable
private fun SeriesStatusDot(group: SeriesGroup) {
    val color = when {
        group.isFullyDownloaded -> Success
        group.isAnyActive       -> Brand
        else                    -> White40
    }
    Box(Modifier.size(d.spaceXs + d.spaceXxs).clip(CircleShape).background(color))
}

// ─────────────────────────────────────────────────────────────────────────────
// LEVEL 2 — Season row (collapsible inside series)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SeasonRow(
    season: SeasonGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPlay: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
    onResume: (DownloadItem) -> Unit,
    onPause: (DownloadItem) -> Unit,
    onDeleteSeason: () -> Unit,
) {
    val d = LocalDimensions.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "season-chevron")
    val allDone = season.doneCount == season.episodes.size

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = d.spaceXxs + 1.dp)
            .clip(RoundedCornerShape(d.radiusMd - d.spaceSm))
            .background(BgRaised)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Season ${season.season}",
                color = White80, fontSize = d.textMd, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${season.doneCount}/${season.episodes.size}",
                color = if (allDone) Success else White40, fontSize = d.textXs, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(d.spaceSm + d.spaceXxs))
            Icon(
                IconChevronDown, null, tint = White40,
                modifier = Modifier.size(d.iconMd - 4.dp).rotate(chevronRotation),
            )
            Spacer(Modifier.width(d.spaceXs))
            Box(
                Modifier.size(d.iconMd).clip(CircleShape).background(GlassSm)
                    .clickable { showDeleteDialog = true },
                Alignment.Center,
            ) { Text("✕", color = White40, fontSize = d.textXs) }
        }

        // ── Episodes (collapsible inside season) ─────────────────────────
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(bottom = d.spaceSm)) {
                season.episodes.forEach { ep ->
                    EpisodeRow(
                        item     = ep,
                        onPlay   = { onPlay(ep) },
                        onDelete = { onDelete(ep) },
                        onResume = { onResume(ep) },
                        onPause  = { onPause(ep) },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = BgCard,
            shape = RoundedCornerShape(d.radiusLg),
            title = { Text("Delete Season ${season.season}", color = White, fontWeight = FontWeight.Bold) },
            text  = { Text("Remove all ${season.episodes.size} downloaded episodes in this season?", color = White60) },
            confirmButton = {
                TextButton(onClick = { onDeleteSeason(); showDeleteDialog = false }) { Text("Delete", color = Error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = White60) }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LEVEL 3 — Episode row (compact single-line, inside an expanded season)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun EpisodeRow(
    item: DownloadItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
) {
    val d = LocalDimensions.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isDownloading = item.status == DownloadStatus.DOWNLOADING.name
    val isDone        = item.status == DownloadStatus.DONE.name
    val isPaused      = item.status == DownloadStatus.PAUSED.name
    val isQueued      = item.status == DownloadStatus.QUEUED.name
    val isError       = item.status == DownloadStatus.ERROR.name

    val pct = if (item.totalSegments > 0) item.segmentsDone.toFloat() / item.totalSegments
              else if (item.sizeBytes > 0) item.downloadedBytes.toFloat() / item.sizeBytes
              else 0f
    val animPct by animateFloatAsState(pct.coerceIn(0f, 1f), label = "ep-progress")

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Episode number badge — quick scan target
            Box(
                Modifier.size(d.iconMd).clip(RoundedCornerShape(d.radiusSm))
                    .background(if (isDone) Success.copy(.15f) else GlassMd),
                Alignment.Center,
            ) {
                Text("${item.episode}", color = if (isDone) Success else White40, fontSize = d.textXs, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(d.spaceMd))

            Column(Modifier.weight(1f)) {
                Text(
                    item.episodeName.ifBlank { "Episode ${item.episode}" },
                    color = White80, fontSize = d.textSm, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (isDownloading || isQueued || isPaused || isError) {
                    Spacer(Modifier.height(d.spaceXxs + 1.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceXs + 1.dp)) {
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(GlassMd)) {
                            Box(Modifier.fillMaxWidth(animPct).fillMaxHeight().background(
                                if (isError) Error else Brand
                            ))
                        }
                        Text(
                            "${(pct * 100).toInt()}%",
                            color = White40, fontSize = d.textXxs,
                        )
                    }
                } else {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${item.quality} · ${formatSize(item.sizeBytes)}",
                        color = White40, fontSize = d.textXs,
                    )
                }
            }

            Spacer(Modifier.width(d.spaceSm))

            // ── Compact action button — one primary action per state ─────
            when {
                isDone -> CompactIconAction(IconPlay, Brand) { onPlay() }
                isDownloading -> CompactTextAction("⏸", White60) { onPause() }
                isPaused || isError -> CompactTextAction("▶", Brand) { onResume() }
                else -> Spacer(Modifier.width(d.spaceXxl - d.spaceXs))
            }

            Spacer(Modifier.width(d.spaceXs))
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .clickable { showDeleteDialog = true },
                Alignment.Center,
            ) { Text("✕", color = White40.copy(.7f), fontSize = d.textXs) }
        }
        Divider(color = GlassBorder, thickness = 0.5.dp, modifier = Modifier.padding(start = d.spinnerLg))
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = BgCard,
            shape = RoundedCornerShape(d.radiusLg),
            title = { Text("Delete Episode", color = White, fontWeight = FontWeight.Bold) },
            text  = { Text("Remove \"${item.episodeName.ifBlank { "Episode ${item.episode}" }}\" from downloads?", color = White60) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("Delete", color = Error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = White60) }
            },
        )
    }
}

@Composable
private fun CompactIconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(d.iconLg).clip(CircleShape).background(tint.copy(.15f))
            .clickable(onClick = onClick),
        Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(d.iconSm + 2.dp)) }
}

@Composable
private fun CompactTextAction(symbol: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(d.iconLg).clip(CircleShape).background(color.copy(alpha = .15f))
            .clickable(onClick = onClick),
        Alignment.Center,
    ) { Text(symbol, color = color, fontSize = d.textSm, fontWeight = FontWeight.Bold) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Movies — flat, compact cards (no series wrapper needed)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MovieRow(
    item: DownloadItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
) {
    val d = LocalDimensions.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isDownloading = item.status == DownloadStatus.DOWNLOADING.name
    val isDone        = item.status == DownloadStatus.DONE.name
    val isPaused      = item.status == DownloadStatus.PAUSED.name
    val isQueued      = item.status == DownloadStatus.QUEUED.name
    val isError       = item.status == DownloadStatus.ERROR.name
    val canPlayPartial = item.localPlaylistPath.isNotBlank()

    val pct = if (item.totalSegments > 0) item.segmentsDone.toFloat() / item.totalSegments
              else if (item.sizeBytes > 0) item.downloadedBytes.toFloat() / item.sizeBytes
              else 0f
    val pctInt = (pct * 100).toInt()
    val animPct by animateFloatAsState(pct.coerceIn(0f, 1f), label = "movie-progress")

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .padding(d.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.posterPath?.let { "${BuildConfig.TMDB_IMG_W342}$it" },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = d.avatarMd + d.spaceXxs, height = d.avatarLg).clip(RoundedCornerShape(d.radiusSm + d.spaceXxs)).background(BgRaised),
        )
        Spacer(Modifier.width(d.spaceMd - d.spaceXxs))

        Column(Modifier.weight(1f)) {
            Text(
                item.title, color = White, fontSize = d.textMd, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(d.spaceXs))
            Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(item.status)
                QualityBadge(item.quality)
                if (item.sizeBytes > 0) Text(formatSize(item.sizeBytes), color = White40, fontSize = d.textXs)
            }

            if (isDownloading || isQueued) {
                Spacer(Modifier.height(d.spaceSm + 1.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(GlassMd)) {
                    Box(Modifier.fillMaxWidth(animPct).fillMaxHeight().background(Brush.horizontalGradient(listOf(Brand, Brand2))))
                }
                Spacer(Modifier.height(d.spaceXs))
                Text(
                    if (item.totalSegments > 0) "${item.segmentsDone}/${item.totalSegments} · $pctInt%" else "$pctInt%",
                    color = White40, fontSize = d.textXs,
                )
            }
            if (isPaused || isError) {
                Spacer(Modifier.height(d.spaceXs))
                Text("$pctInt% downloaded", color = White40, fontSize = d.textXs)
            }
        }

        Spacer(Modifier.width(d.spaceSm + d.spaceXxs))

        when {
            isDone -> CompactIconAction(IconPlay, Brand) { onPlay() }
            isDownloading -> CompactTextAction("⏸", White60) { onPause() }
            isPaused || isError -> CompactTextAction("▶", Brand) { onResume() }
            canPlayPartial && pct >= 0.05f -> CompactTextAction("▶", Brand) { onPlay() }
        }

        Spacer(Modifier.width(d.spaceSm))
        Box(
            Modifier.size(d.iconLg).clip(CircleShape).background(GlassMd)
                .border(1.dp, GlassBorderMd, CircleShape)
                .clickable { showDeleteDialog = true },
            Alignment.Center,
        ) { Text("✕", color = White40, fontSize = d.textSm) }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = BgCard,
            shape = RoundedCornerShape(d.radiusLg),
            title = { Text("Delete Download", color = White, fontWeight = FontWeight.Bold) },
            text  = { Text("Remove \"${item.title}\" from your downloads?", color = White60) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("Delete", color = Error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = White60) }
            },
        )
    }
}

@Composable
fun StatusBadge(status: String) {
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
        Modifier.clip(RoundedCornerShape(d.spaceXs + 1.dp)).background(color.copy(.13f))
            .border(1.dp, color.copy(.35f), RoundedCornerShape(d.spaceXs + 1.dp))
            .padding(horizontal = d.spaceSm + 1.dp, vertical = d.spaceXxs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
    ) {
        if (status == DownloadStatus.DONE.name) {
            Box(Modifier.size(d.spaceXs + 1.dp).clip(CircleShape).background(color))
        }
        Text(label, color = color, fontSize = d.textXs, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun QualityBadge(quality: String) {
    if (quality.isBlank()) return
    Box(
        Modifier.clip(RoundedCornerShape(d.spaceXs + 1.dp)).background(GlassSm)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.spaceXs + 1.dp))
            .padding(horizontal = d.spaceSm, vertical = d.spaceXxs + 1.dp)
    ) { Text(quality, color = White60, fontSize = d.textXs, fontWeight = FontWeight.Bold) }
    val d = LocalDimensions.current
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
