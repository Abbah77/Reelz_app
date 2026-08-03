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
import androidx.compose.ui.draw.blur
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
// View model — unchanged business logic, same data shapes
// ─────────────────────────────────────────────────────────────────────────────

data class SeriesGroup(
    val tmdbId: Int,
    val title: String,
    val posterPath: String?,
    val seasons: List<SeasonGroup>,
) {
    val totalEpisodes: Int get() = seasons.sumOf { it.episodes.size }
    val doneEpisodes: Int get() = seasons.sumOf { s -> s.episodes.count { it.status == DownloadStatus.DONE.name } }
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

    val activeDownloads: StateFlow<List<DownloadItem>> = allDownloads
        .map { list -> list.filter { it.status == DownloadStatus.DOWNLOADING.name || it.status == DownloadStatus.QUEUED.name || it.status == DownloadStatus.PAUSED.name } }
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
                    .map { (season, seasonEps) -> SeasonGroup(season, seasonEps.sortedBy { it.episode }) }
                    .sortedBy { it.season }
                SeriesGroup(tmdbId, eps.first().title, eps.first().posterPath, seasons)
            }
            .sortedByDescending { g -> g.seasons.flatMap { it.episodes }.maxOf { it.createdAt } }

    fun delete(item: DownloadItem, ctx: Context) { viewModelScope.launch { repo.delete(ctx, item) } }
    fun resume(ctx: Context, item: DownloadItem) { viewModelScope.launch { repo.resume(ctx, item) } }
    fun pause(ctx: Context, item: DownloadItem)  { viewModelScope.launch { repo.pause(ctx, item) } }

    fun deleteSeries(group: SeriesGroup, ctx: Context) {
        viewModelScope.launch { group.seasons.flatMap { it.episodes }.forEach { repo.delete(ctx, it) } }
    }
    fun deleteSeason(season: SeasonGroup, ctx: Context) {
        viewModelScope.launch { season.episodes.forEach { repo.delete(ctx, it) } }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DownloadsScreen(nav: NavController, vm: DownloadsViewModel = hiltViewModel()) {
    val d              = LocalDimensions.current
    val ctx            = LocalContext.current
    val movies         by vm.movies.collectAsState()
    val seriesGroups   by vm.seriesGroups.collectAsState()
    val activeDownloads by vm.activeDownloads.collectAsState()
    val readyCount     by vm.readyCount.collectAsState()
    var tab            by remember { mutableStateOf(0) }
    val expandedSeries  = remember { mutableStateOf(setOf<Int>()) }
    val expandedSeasons = remember { mutableStateOf(setOf<String>()) }

    val showMovies  = tab == 0 || tab == 1
    val showSeries  = tab == 0 || tab == 2
    val showActive  = tab == 0 || tab == 3
    val isEmpty = movies.isEmpty() && seriesGroups.isEmpty() && activeDownloads.isEmpty()

    Box(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = d.spaceXxl * 3),
        ) {
            // ── HEADER ───────────────────────────────────────────────────
            item {
                DownloadsHeader(
                    readyCount  = readyCount,
                    activeCount = activeDownloads.size,
                    onTransfer  = { nav.navigate(Route.Transfer.path) },
                )
            }

            // ── ACTIVE QUEUE STRIP (always visible if active downloads exist)
            if (activeDownloads.isNotEmpty()) {
                item { ActiveQueueStrip(activeDownloads, ctx, vm) }
            }

            // ── TAB FILTER BAR ───────────────────────────────────────────
            item {
                TabFilterBar(
                    selected     = tab,
                    movieCount   = movies.size,
                    seriesCount  = seriesGroups.size,
                    activeCount  = activeDownloads.size,
                    onSelect     = { tab = it },
                )
            }

            // ── EMPTY STATE ───────────────────────────────────────────────
            if (isEmpty) {
                item { EmptyDownloadsState() }
            }

            // ── SERIES ────────────────────────────────────────────────────
            if (showSeries && seriesGroups.isNotEmpty()) {
                item {
                    SectionLabel(
                        "TV Shows",
                        "${seriesGroups.size} series",
                        modifier = Modifier.padding(horizontal = d.screenHorizPad, vertical = d.spaceSm),
                    )
                }
                items(seriesGroups, key = { "s-${it.tmdbId}" }) { group ->
                    SeriesCard(
                        group           = group,
                        expanded        = group.tmdbId in expandedSeries.value,
                        expandedSeasons = expandedSeasons.value,
                        onToggle        = { expandedSeries.value = toggle(expandedSeries.value, group.tmdbId) },
                        onToggleSeason  = { expandedSeasons.value = toggle(expandedSeasons.value, it) },
                        onPlay          = { playDownload(ctx, it) },
                        onDelete        = { vm.delete(it, ctx) },
                        onResume        = { vm.resume(ctx, it) },
                        onPause         = { vm.pause(ctx, it) },
                        onDeleteSeries  = { vm.deleteSeries(group, ctx) },
                        onDeleteSeason  = { vm.deleteSeason(it, ctx) },
                        modifier        = Modifier.padding(horizontal = d.screenHorizPad).padding(bottom = d.spaceSm + d.spaceXxs),
                    )
                }
            }

            // ── MOVIES ────────────────────────────────────────────────────
            if (showMovies && movies.isNotEmpty()) {
                item {
                    SectionLabel(
                        "Movies",
                        "${movies.size} title${if (movies.size > 1) "s" else ""}",
                        modifier = Modifier.padding(horizontal = d.screenHorizPad, vertical = d.spaceSm),
                    )
                }
                items(movies, key = { "m-${it.id}" }) { dl ->
                    MovieCard(
                        item     = dl,
                        onPlay   = { playDownload(ctx, dl) },
                        onDelete = { vm.delete(dl, ctx) },
                        onResume = { vm.resume(ctx, dl) },
                        onPause  = { vm.pause(ctx, dl) },
                        modifier = Modifier.padding(horizontal = d.screenHorizPad).padding(bottom = d.spaceSm + d.spaceXxs),
                    )
                }
            }
        }
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

        // Transfer button — elevated pill design
        Row(
            Modifier
                .clip(RoundedCornerShape(d.radiusPill))
                .background(
                    Brush.horizontalGradient(listOf(Color(0xFF003F8F), Color(0xFF0A5FCC)))
                )
                .border(1.dp, Brand.copy(.3f), RoundedCornerShape(d.radiusPill))
                .clickable(onClick = onTransfer)
                .padding(horizontal = d.spaceLg - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceXs + 1.dp),
        ) {
            Icon(
                IconSwap, null, tint = Color.White,
                modifier = Modifier.size(d.iconSm + 4.dp),
            )
            Text(
                "Transfer",
                color = Color.White,
                fontSize = d.textSm,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Active Queue Strip — horizontal live download cards
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActiveQueueStrip(
    items: List<DownloadItem>,
    ctx: Context,
    vm: DownloadsViewModel,
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
            // Pulsing dot + label
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
                    Modifier
                        .size(d.spaceXs + 2.dp)
                        .clip(CircleShape)
                        .background(Brand.copy(alpha = pulseAlpha))
                )
                Text(
                    "ACTIVE",
                    color = White40,
                    fontSize = (d.textXxs.value + 1f).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
            }
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
) {
    val d = LocalDimensions.current
    val isDownloading = item.status == DownloadStatus.DOWNLOADING.name
    val isPaused      = item.status == DownloadStatus.PAUSED.name
    val isQueued      = item.status == DownloadStatus.QUEUED.name

    val pct = if (item.totalSegments > 0) item.segmentsDone.toFloat() / item.totalSegments
              else if (item.sizeBytes > 0) item.downloadedBytes.toFloat() / item.sizeBytes
              else 0f
    val animPct by animateFloatAsState(pct.coerceIn(0f, 1f), label = "aq-pct")

    // Card width slightly more than a movie poster
    val cardW = d.continueCardWidth + d.spaceLg

    Box(
        Modifier
            .width(cardW)
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, if (isDownloading) Brand.copy(.25f) else GlassBorderMd, RoundedCornerShape(d.radiusMd))
    ) {
        // Poster fills top portion
        Box(Modifier.fillMaxWidth().height(cardW * 0.56f)) {
            AsyncImage(
                model = item.posterPath?.let { "${BuildConfig.TMDB_IMG_W342}$it" },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Gradient overlay from bottom
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, BgCard),
                        startY = Float.MAX_VALUE * 0.3f,
                    )
                )
            )
            // Quality badge top-left
            if (item.quality.isNotBlank()) {
                Box(
                    Modifier
                        .padding(d.spaceXs + 1.dp)
                        .clip(RoundedCornerShape(d.radiusSm - 2.dp))
                        .background(Color.Black.copy(.65f))
                        .padding(horizontal = d.spaceSm, vertical = d.spaceXxs + 1.dp)
                        .align(Alignment.TopStart)
                ) {
                    Text(item.quality, color = White80, fontSize = (d.textXxs.value + 0.5f).sp, fontWeight = FontWeight.Bold)
                }
            }
            // Pause/Resume control — top-right
            Box(
                Modifier
                    .padding(d.spaceXs + 1.dp)
                    .size(d.iconLg + d.spaceXxs)
                    .clip(CircleShape)
                    .background(Color.Black.copy(.6f))
                    .border(1.dp, White20, CircleShape)
                    .clickable(onClick = if (isDownloading) onPause else onResume)
                    .align(Alignment.TopEnd),
                Alignment.Center,
            ) {
                Text(
                    if (isDownloading) "⏸" else "▶",
                    color = White,
                    fontSize = (d.textSm.value - 1f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Info below image
        Column(Modifier.padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + 1.dp)) {
            Text(
                item.title,
                color = White,
                fontSize = d.textSm,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.mediaType == "TV" && item.season > 0) {
                Text(
                    "S${item.season}E${item.episode}",
                    color = White40,
                    fontSize = (d.textXxs.value + 0.5f).sp,
                )
            }
            Spacer(Modifier.height(d.spaceSm - 1.dp))

            // Progress bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GlassMd)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animPct)
                        .fillMaxHeight()
                        .background(
                            brush = if (isPaused) SolidColor(White40)
                            else if (isQueued) SolidColor(White20)
                            else Brush.horizontalGradient(listOf(Brand, Brand2))
                        )
                )
            }
            Spacer(Modifier.height(d.spaceXxs + 1.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    when {
                        isQueued      -> "Waiting…"
                        isPaused      -> "${(pct * 100).toInt()}% paused"
                        item.networkSpeedBps > 0 -> formatSpeed(item.networkSpeedBps)
                        else          -> "${(pct * 100).toInt()}%"
                    },
                    color = if (isDownloading && item.networkSpeedBps > 0) Success.copy(.9f) else White40,
                    fontSize = (d.textXxs.value + 0.5f).sp,
                    fontWeight = if (isDownloading) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    "${(pct * 100).toInt()}%",
                    color = White40,
                    fontSize = (d.textXxs.value + 0.5f).sp,
                )
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
    activeCount: Int,
    onSelect: (Int) -> Unit,
) {
    val d = LocalDimensions.current
    val tabs = listOf(
        "All" to null,
        "Movies" to movieCount,
        "Shows" to seriesCount,
        "Active" to if (activeCount > 0) activeCount else null,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad),
        horizontalArrangement = Arrangement.spacedBy(d.spaceXs + 1.dp),
    ) {
        tabs.forEachIndexed { i, (label, count) ->
            val isSelected = selected == i
            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(
                        if (isSelected)
                            Brush.horizontalGradient(listOf(BrandDeep.copy(.9f), Brand.copy(.8f)))
                        else
                            SolidColor(GlassSm)
                    )
                    .border(
                        1.dp,
                        if (isSelected) Brand.copy(.5f) else GlassBorderMd,
                        RoundedCornerShape(d.radiusPill),
                    )
                    .clickable { onSelect(i) }
                    .padding(
                        horizontal = d.spaceLg - d.spaceXxs,
                        vertical = d.spaceSm + d.spaceXxs,
                    ),
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
                    if (count != null) {
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
            // Accent line
            Box(
                Modifier
                    .width(d.sectionAccentWidth)
                    .height(d.sectionAccentHeight + d.spaceXxs)
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(
                        Brush.verticalGradient(listOf(Brand, Brand.copy(.3f)))
                    )
            )
            Text(
                title,
                color = White,
                fontSize = (d.textMd.value + 0.5f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(subtitle, color = White40, fontSize = d.textXs, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Movie card — horizontal with backdrop-tinted glow
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MovieCard(
    item: DownloadItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
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
    val animPct by animateFloatAsState(pct.coerceIn(0f, 1f), label = "movie-pct")
    val pctInt = (pct * 100).toInt()

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(BgCard)
            .border(
                1.dp,
                if (isDone) Success.copy(.2f) else if (isDownloading) Brand.copy(.2f) else GlassBorderMd,
                RoundedCornerShape(d.radiusLg - d.spaceXxs),
            )
    ) {
        // Done state: subtle green glow at left edge
        if (isDone) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            listOf(Success.copy(.8f), Success.copy(.3f))
                        )
                    )
                    .clip(RoundedCornerShape(topStart = d.radiusLg, bottomStart = d.radiusLg))
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Poster
            Box(Modifier.size(width = d.avatarMd + d.spaceXxs + 2.dp, height = d.avatarLg + d.spaceXxs)) {
                AsyncImage(
                    model = item.posterPath?.let { "${BuildConfig.TMDB_IMG_W342}$it" },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(d.radiusSm + 2.dp))
                        .background(BgRaised),
                )
                // Play overlay for done/partial
                if (isDone || (canPlayPartial && pct >= 0.05f)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(d.radiusSm + 2.dp))
                            .background(Color.Black.copy(.35f))
                            .clickable(onClick = onPlay),
                        Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(d.iconLg)
                                .clip(CircleShape)
                                .background(Color.Black.copy(.55f))
                                .border(1.5.dp, White60, CircleShape),
                            Alignment.Center,
                        ) {
                            Icon(
                                IconPlay, null,
                                tint = Color.White,
                                modifier = Modifier.size(d.iconSm + 2.dp).offset(x = 1.dp),
                            )
                        }
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
                            item.title,
                            color = White,
                            fontSize = d.textMd,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(d.spaceXxs + 1.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(d.spaceSm - 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusPill(item.status)
                            if (item.quality.isNotBlank()) {
                                QualityChip(item.quality)
                            }
                        }
                    }

                    // Delete button — minimal X
                    Box(
                        Modifier
                            .size(d.iconLg)
                            .clip(CircleShape)
                            .background(GlassMd)
                            .clickable { showDeleteDialog = true },
                        Alignment.Center,
                    ) {
                        Text("✕", color = White40, fontSize = (d.textSm.value - 1f).sp)
                    }
                }

                Spacer(Modifier.height(d.spaceMd - d.spaceXxs))

                if (isDownloading || isQueued || isPaused || isError) {
                    // Progress section
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(GlassMd)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(animPct)
                                .fillMaxHeight()
                                .background(
                                    brush = if (isError) SolidColor(Error)
                                    else if (isPaused) SolidColor(White40)
                                    else Brush.horizontalGradient(listOf(Brand, Brand2))
                                )
                        )
                    }
                    Spacer(Modifier.height(d.spaceSm - 1.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isDownloading && item.networkSpeedBps > 0) {
                                Text(
                                    "↓ ${formatSpeed(item.networkSpeedBps)}",
                                    color = Success.copy(.85f),
                                    fontSize = (d.textXxs.value + 1f).sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (item.sizeBytes > 0) {
                                Text(
                                    "${formatSize(item.downloadedBytes)} / ${formatSize(item.sizeBytes)}",
                                    color = White40,
                                    fontSize = (d.textXxs.value + 1f).sp,
                                )
                            }
                        }

                        // Pause/Resume action
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(d.radiusPill))
                                .background(GlassMd)
                                .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusPill))
                                .clickable(onClick = if (isDownloading) onPause else onResume)
                                .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceXxs + 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp),
                        ) {
                            Text(
                                if (isDownloading) "⏸" else if (isPaused || isError) "▶" else "…",
                                color = if (isPaused || isError) Brand else White60,
                                fontSize = d.textXs,
                            )
                            Text(
                                when {
                                    isDownloading -> "Pause"
                                    isPaused      -> "Resume"
                                    isError       -> "Retry"
                                    else          -> "Queue"
                                },
                                color = if (isPaused || isError) Brand else White60,
                                fontSize = d.textXs,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else if (isDone) {
                    // Done — show size and date
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                    ) {
                        if (item.sizeBytes > 0) {
                            Text(formatSize(item.sizeBytes), color = White40, fontSize = d.textXs)
                        }
                        if (item.completedAt > 0) {
                            val daysAgo = ((System.currentTimeMillis() - item.completedAt) / 86_400_000L).toInt()
                            val label = when (daysAgo) {
                                0    -> "Today"
                                1    -> "Yesterday"
                                else -> "$daysAgo days ago"
                            }
                            Text(label, color = White20, fontSize = d.textXs)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        ReelzDeleteDialog(
            title    = "Remove Download",
            message  = "Remove \"${item.title}\" from your library?",
            onDelete = { onDelete(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Series card
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
    modifier: Modifier = Modifier,
) {
    val d = LocalDimensions.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "series-chevron")

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(BgCard)
            .border(
                1.dp,
                if (expanded) Brand.copy(.25f) else GlassBorderMd,
                RoundedCornerShape(d.radiusLg - d.spaceXxs),
            )
    ) {
        // ── Header row ────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Poster with completion overlay
            Box(Modifier.size(width = d.avatarMd + 2.dp, height = d.avatarLg + 2.dp)) {
                AsyncImage(
                    model = group.posterPath?.let { "${BuildConfig.TMDB_IMG_W342}$it" },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(d.radiusSm + 2.dp))
                        .background(BgRaised),
                )
                if (group.isFullyDownloaded) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(d.radiusSm + 2.dp))
                            .background(Success.copy(.25f)),
                        Alignment.Center,
                    ) {
                        Text("✓", color = Success, fontSize = d.textLg, fontWeight = FontWeight.Black)
                    }
                }
            }
            Spacer(Modifier.width(d.spaceMd - d.spaceXxs))

            Column(Modifier.weight(1f)) {
                Text(
                    group.title,
                    color = White,
                    fontSize = d.textMd,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(d.spaceXs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                ) {
                    // Status dot
                    Box(
                        Modifier.size(d.spaceXs + 1.dp).clip(CircleShape).background(
                            when {
                                group.isFullyDownloaded -> Success
                                group.isAnyActive       -> Brand
                                else                    -> White40
                            }
                        )
                    )
                    Text(
                        "${group.seasons.size} season${if (group.seasons.size > 1) "s" else ""} · ${group.doneEpisodes}/${group.totalEpisodes} ep",
                        color = White40,
                        fontSize = d.textXs,
                    )
                }
                Spacer(Modifier.height(d.spaceSm + 1.dp))

                // Aggregate progress bar
                val pct = if (group.totalEpisodes > 0) group.doneEpisodes.toFloat() / group.totalEpisodes else 0f
                Box(
                    Modifier
                        .fillMaxWidth(0.9f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(GlassMd)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(pct)
                            .fillMaxHeight()
                            .background(
                                if (group.isFullyDownloaded) SolidColor(Success)
                                else Brush.horizontalGradient(listOf(Brand, Brand2))
                            )
                    )
                }
            }

            Spacer(Modifier.width(d.spaceSm))
            Icon(
                IconChevronDown, null, tint = White40,
                modifier = Modifier.size(d.iconMd).rotate(chevronRotation),
            )
            Spacer(Modifier.width(d.spaceXs))
            Box(
                Modifier
                    .size(d.iconLg)
                    .clip(CircleShape)
                    .background(GlassMd)
                    .clickable { showDeleteDialog = true },
                Alignment.Center,
            ) { Text("✕", color = White40, fontSize = (d.textSm.value - 1f).sp) }
        }

        // ── Seasons ───────────────────────────────────────────────────────────
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(
                    start = d.spaceMd,
                    end = d.spaceMd,
                    bottom = d.spaceMd,
                )
            ) {
                HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)
                Spacer(Modifier.height(d.spaceXs + 1.dp))
                group.seasons.forEach { season ->
                    val seasonKey = "${group.tmdbId}:${season.season}"
                    SeasonRow(
                        season        = season,
                        expanded      = seasonKey in expandedSeasons,
                        onToggle      = { onToggleSeason(seasonKey) },
                        onPlay        = onPlay,
                        onDelete      = onDelete,
                        onResume      = onResume,
                        onPause       = onPause,
                        onDeleteSeason = { onDeleteSeason(season) },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        ReelzDeleteDialog(
            title     = "Remove Series",
            message   = "Remove all downloaded episodes of \"${group.title}\"?",
            onDelete  = { onDeleteSeries(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Season row
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
            .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
            .background(BgRaised)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceMd - d.spaceXxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Season ${season.season}",
                color = White80,
                fontSize = (d.textSm.value + 0.5f).sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // Completion badge
            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(if (allDone) Success.copy(.15f) else GlassMd)
                    .padding(horizontal = d.spaceSm + 1.dp, vertical = d.spaceXxs + 1.dp)
            ) {
                Text(
                    "${season.doneCount}/${season.episodes.size}",
                    color = if (allDone) Success else White40,
                    fontSize = (d.textXxs.value + 1f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(d.spaceSm))
            Icon(
                IconChevronDown, null, tint = White40,
                modifier = Modifier.size(d.iconMd - 4.dp).rotate(chevronRotation),
            )
            Spacer(Modifier.width(d.spaceXs))
            Box(
                Modifier
                    .size(d.iconMd + d.spaceXxs)
                    .clip(CircleShape)
                    .background(GlassSm)
                    .clickable { showDeleteDialog = true },
                Alignment.Center,
            ) { Text("✕", color = White40, fontSize = (d.textXxs.value + 1f).sp) }
        }

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
        ReelzDeleteDialog(
            title     = "Remove Season ${season.season}",
            message   = "Remove all ${season.episodes.size} downloaded episodes?",
            onDelete  = { onDeleteSeason(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Episode row
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
    val animPct by animateFloatAsState(pct.coerceIn(0f, 1f), label = "ep-pct")

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Episode number circle — doubles as status indicator
            Box(
                Modifier
                    .size(d.iconLg - d.spaceXxs)
                    .clip(CircleShape)
                    .background(
                        when {
                            isDone -> Success.copy(.18f)
                            isError -> Error.copy(.15f)
                            else -> GlassMd
                        }
                    ),
                Alignment.Center,
            ) {
                Text(
                    "${item.episode}",
                    color = when {
                        isDone  -> Success
                        isError -> Error
                        else    -> White40
                    },
                    fontSize = d.textXs,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(d.spaceMd - d.spaceXxs))

            Column(Modifier.weight(1f)) {
                Text(
                    item.episodeName.ifBlank { "Episode ${item.episode}" },
                    color = White80,
                    fontSize = d.textSm,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isDownloading || isQueued || isPaused || isError) {
                    Spacer(Modifier.height(d.spaceXxs + 2.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(GlassMd)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(animPct)
                                .fillMaxHeight()
                                .background(brush = if (isError) SolidColor(Error) else if (isPaused) SolidColor(White40) else SolidColor(Brand))
                        )
                    }
                } else {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            if (item.quality.isNotBlank()) { append(item.quality); append(" · ") }
                            if (item.sizeBytes > 0) append(formatSize(item.sizeBytes))
                        },
                        color = White40,
                        fontSize = (d.textXxs.value + 1f).sp,
                    )
                }
            }

            Spacer(Modifier.width(d.spaceSm))
            // Primary action
            when {
                isDone        -> EpActionButton(symbol = "▶", tint = Brand, onClick = onPlay)
                isDownloading -> EpActionButton(symbol = "⏸", tint = White60, onClick = onPause)
                isPaused || isError -> EpActionButton(symbol = "▶", tint = Brand, onClick = onResume)
                isQueued      -> Spacer(Modifier.width(d.iconLg))
                else          -> Spacer(Modifier.width(d.iconLg))
            }

            Spacer(Modifier.width(d.spaceXs))
            Box(
                Modifier.size(22.dp).clip(CircleShape).clickable { showDeleteDialog = true },
                Alignment.Center,
            ) { Text("✕", color = White20, fontSize = (d.textXxs.value + 1f).sp) }
        }
        HorizontalDivider(
            color = GlassBorder,
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = d.iconLg + d.spaceMd),
        )
    }

    if (showDeleteDialog) {
        ReelzDeleteDialog(
            title     = "Remove Episode",
            message   = "Remove \"${item.episodeName.ifBlank { "Episode ${item.episode}" }}\"?",
            onDelete  = { onDelete(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun EpActionButton(symbol: String, tint: Color, onClick: () -> Unit) {
    val d = LocalDimensions.current
    Box(
        Modifier
            .size(d.iconLg)
            .clip(CircleShape)
            .background(tint.copy(.15f))
            .clickable(onClick = onClick),
        Alignment.Center,
    ) { Text(symbol, color = tint, fontSize = (d.textSm.value - 1f).sp, fontWeight = FontWeight.Bold) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyDownloadsState() {
    val d = LocalDimensions.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = d.spaceXxl * 2),
        Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
        ) {
            // Concentric rings
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(d.avatarLg + d.spaceXxl + d.spaceSm)
                        .clip(CircleShape)
                        .background(BlueGlass)
                        .border(1.dp, BlueBorder, CircleShape)
                )
                Box(
                    Modifier
                        .size(d.avatarLg + d.spaceLg)
                        .clip(CircleShape)
                        .background(GlassSm)
                        .border(1.dp, GlassBorderMd, CircleShape)
                )
                Icon(
                    IconDownloadCloud, null,
                    tint = Brand.copy(.75f),
                    modifier = Modifier.size(d.avatarSm + d.spaceMd),
                )
            }
            Spacer(Modifier.height(d.spaceXxs))
            Text(
                "Nothing here yet",
                color = White60,
                fontSize = d.textXl,
                fontWeight = FontWeight.Bold,
            )
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
// Shared UI atoms
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

// Re-export for backward compat (used externally as QualityBadge / StatusBadge)
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
        title = {
            Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = d.textLg)
        },
        text = {
            Text(message, color = White60, fontSize = d.textMd)
        },
        confirmButton = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(Error.copy(.15f))
                    .border(1.dp, Error.copy(.35f), RoundedCornerShape(d.radiusPill))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Remove", color = Error, fontWeight = FontWeight.Bold, fontSize = d.textSm) }
        },
        dismissButton = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(GlassMd)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Cancel", color = White60, fontSize = d.textSm) }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun toggle(set: Set<Int>, key: Int): Set<Int> =
    if (key in set) set - key else set + key

private fun toggle(set: Set<String>, key: String): Set<String> =
    if (key in set) set - key else set + key

private fun playDownload(ctx: Context, dl: DownloadItem) {
    val base = Intent(ctx, PlayerActivity::class.java).apply {
        putExtra("tmdbId",     dl.tmdbId)
        putExtra("mediaType",  dl.mediaType)
        putExtra("season",     dl.season)
        putExtra("episode",    dl.episode)
        putExtra("title",      dl.title)
        putExtra("posterPath", dl.posterPath)
        putExtra("downloadId", dl.id)
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
