package com.reelz.ui.screens.downloads

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
import com.reelz.BuildConfigimport com.reelz.data.local.DownloadDao
import com.reelz.data.model.*
import com.reelz.data.repository.DownloadRepository
import com.reelz.service.DownloadService
import com.reelz.ui.components.*
import com.reelz.ui.screens.player.PlayerActivity
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val dao: DownloadDao,
    private val repo: DownloadRepository,
) : ViewModel() {
    val downloads: StateFlow<List<DownloadItem>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun delete(item: DownloadItem, ctx: Context) { viewModelScope.launch { repo.delete(ctx, item) } }
    fun resume(ctx: Context, item: DownloadItem)  { viewModelScope.launch { repo.resume(ctx, item) } }
    fun pause(ctx: Context, item: DownloadItem)   { viewModelScope.launch { repo.pause(ctx, item) } }
}

@Composable
fun DownloadsScreen(nav: NavController, vm: DownloadsViewModel = hiltViewModel()) {
    val ctx       = LocalContext.current
    val downloads by vm.downloads.collectAsState()
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {

        // ── Header ─────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp
                    )
                )
                val readyCount = downloads.count { it.status == DownloadStatus.DONE.name }
                if (readyCount > 0) {
                    Text(
                        "$readyCount file${if (readyCount > 1) "s" else ""} ready to watch",
                        color = Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Icon(IconDownloadCloud, null, tint = Brand.copy(.6f), modifier = Modifier.size(28.dp))
        }

        // ── Tab filter ─────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("All", "Movies", "TV Shows").forEachIndexed { i, label ->
                GenrePill(label, tab == i) { tab = i }
            }
        }
        Spacer(Modifier.height(10.dp))

        val filtered = when (tab) {
            1    -> downloads.filter { it.mediaType == "MOVIE" }
            2    -> downloads.filter { it.mediaType == "TV" }
            else -> downloads
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(90.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(AmberGlass, Color.Transparent)))
                            .border(1.dp, AmberBorder, CircleShape))
                        Icon(IconDownloadCloud, null, tint = Brand.copy(.7f), modifier = Modifier.size(38.dp))
                    }
                    Text("No downloads yet", color = White60, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Save movies & shows to watch offline", color = White40, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(filtered, key = { it.id }) { dl ->
                    DownloadCard(
                        item     = dl,
                        onPlay   = { playDownload(ctx, dl) },
                        onDelete = { vm.delete(dl, ctx) },
                        onResume = { vm.resume(ctx, dl) },
                        onPause  = { vm.pause(ctx, dl) },
                    )
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
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

@Composable
fun DownloadCard(
    item: DownloadItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isDownloading  = item.status == DownloadStatus.DOWNLOADING.name
    val isDone         = item.status == DownloadStatus.DONE.name
    val isPaused       = item.status == DownloadStatus.PAUSED.name
    val isQueued       = item.status == DownloadStatus.QUEUED.name
    val isError        = item.status == DownloadStatus.ERROR.name
    val canPlayPartial = item.localPlaylistPath.isNotBlank()
    val canPlay        = isDone || canPlayPartial

    val pct = when {
        item.totalSegments > 0 -> item.segmentsDone.toFloat() / item.totalSegments
        item.sizeBytes > 0     -> (item.downloadedBytes.toFloat() / item.sizeBytes).coerceIn(0f, 1f)
        else                   -> 0f
    }
    val pctInt = (pct * 100).toInt()

    val animPct by animateFloatAsState(pct, tween(600), label = "prog")

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BgCard)
            .border(
                1.dp,
                if (isDownloading) Brand.copy(.3f) else GlassBorder,
                RoundedCornerShape(18.dp)
            )
    ) {
        // Active download shimmer top edge
        if (isDownloading) {
            val shimmer = rememberInfiniteTransition(label = "shimmer")
            val sx by shimmer.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), "sx")
            Box(
                Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter)
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            sx  to Brand2,
                            1f  to Color.Transparent,
                        )
                    )
            )
        }

        Column(Modifier.padding(14.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // ── Poster ─────────────────────────────────────────────────
                Box(
                    Modifier.width(66.dp).height(94.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, GlassBorderMd, RoundedCornerShape(10.dp))
                        .background(BgRaised)
                ) {
                    AsyncImage(
                        model = BuildConfig.TMDB_IMG_W342 + item.posterPath,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (canPlay) {
                        Box(
                            Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onPlay),
                            Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape)
                                    .background(Color(0x99000000))
                                    .border(1.dp, Brand.copy(.6f), CircleShape),
                                Alignment.Center,
                            ) {
                                Icon(IconPlay, null, tint = White, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }

                // ── Info column ───────────────────────────────────────────
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title, color = White, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (item.season > 0) {
                        Text(
                            "S${item.season} · E${item.episode}  ${item.episodeName}",
                            color = White60, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(item.status)
                        QualityBadge(item.quality)
                        if (item.sizeBytes > 0) {
                            Text(formatSize(item.sizeBytes), color = White40, fontSize = 10.sp)
                        }
                    }

                    // ── Active download progress ──────────────────────────
                    if (isDownloading || isQueued) {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                                .background(GlassMd)
                        ) {
                            Box(
                                Modifier.fillMaxWidth(animPct).fillMaxHeight()
                                    .background(Brush.horizontalGradient(listOf(Brand, Brand2)))
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            val progressLabel = when {
                                item.totalSegments > 0 -> "${item.segmentsDone}/${item.totalSegments} · $pctInt%"
                                item.sizeBytes > 0     -> "${formatSize(item.downloadedBytes)} / ${formatSize(item.sizeBytes)}"
                                else                   -> "$pctInt%"
                            }
                            Text(progressLabel, color = White40, fontSize = 10.sp)
                            if (item.networkSpeedBps > 0) {
                                Text(formatSpeed(item.networkSpeedBps), color = Brand, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Pause button
                            if (isDownloading) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(GlassMd)
                                        .border(1.dp, GlassBorderMd, RoundedCornerShape(8.dp))
                                        .clickable(onClick = onPause)
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                ) {
                                    Text("⏸ Pause", color = White60, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            // Watch partial button (5%+ downloaded)
                            if (canPlayPartial && pct >= 0.05f) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AmberGlass)
                                        .border(1.dp, AmberBorder, RoundedCornerShape(8.dp))
                                        .clickable(onClick = onPlay)
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    Text("▶ Watch $pctInt%", color = Brand, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // ── Paused / Error state ──────────────────────────────
                    if (isPaused || isError) {
                        Spacer(Modifier.height(8.dp))
                        if (pct > 0f) {
                            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(GlassMd)) {
                                Box(Modifier.fillMaxWidth(animPct).fillMaxHeight().background(White40))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${pctInt}% downloaded",
                                color = White40, fontSize = 10.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isPaused) "Paused" else "Download failed",
                                color = if (isPaused) White40 else Error, fontSize = 10.sp,
                            )
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AmberGlass)
                                    .border(1.dp, AmberBorder, RoundedCornerShape(8.dp))
                                    .clickable(onClick = onResume)
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    "▶ Resume",
                                    color = Brand,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                // ── Delete button ─────────────────────────────────────────
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(GlassMd)
                        .border(1.dp, GlassBorderMd, CircleShape)
                        .clickable { showDeleteDialog = true },
                    Alignment.Center,
                ) {
                    Text("✕", color = White40, fontSize = 13.sp)
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = BgCard,
            shape = RoundedCornerShape(20.dp),
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
    val (color, label) = when (status) {
        DownloadStatus.DONE.name        -> Success to "Ready"
        DownloadStatus.DOWNLOADING.name -> Brand to "Downloading"
        DownloadStatus.QUEUED.name      -> White60 to "Queued"
        DownloadStatus.PAUSED.name      -> White40 to "Paused"
        DownloadStatus.ERROR.name       -> Error to "Failed"
        else                            -> White40 to status
    }
    Row(
        Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(.13f))
            .border(1.dp, color.copy(.35f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (status == DownloadStatus.DONE.name) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        }
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun QualityBadge(quality: String) {
    if (quality.isBlank()) return
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).background(GlassSm)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) { Text(quality, color = White60, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
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
