package com.reelz.ui.screens.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.reelz.BuildConfig
import com.reelz.data.local.DownloadDao
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
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val dao: DownloadDao,
    private val repo: DownloadRepository,
) : ViewModel() {
    val downloads: StateFlow<List<DownloadItem>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun delete(item: DownloadItem, ctx: Context) {
        viewModelScope.launch {
            repo.delete(ctx, item)
        }
    }

    /**
     * BUG 1 fix: Use repo.resume() which sets resolveRequired=true so the service
     * re-resolves the stream URL (CDN tokens expire — reusing old URL → 403 → PAUSED loop).
     */
    fun resume(ctx: Context, item: DownloadItem) {
        viewModelScope.launch {
            repo.resume(ctx, item)
        }
    }
}

@Composable
fun DownloadsScreen(nav: NavController, vm: DownloadsViewModel = hiltViewModel()) {
    val ctx       = LocalContext.current
    val downloads by vm.downloads.collectAsState()

    var tab by remember { mutableStateOf(0) }  // 0=All, 1=Movies, 2=TV

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        // ── Header ─────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Downloads",
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = White, fontWeight = FontWeight.Black
                )
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${downloads.count { it.status == DownloadStatus.DONE.name }} ready",
                color = White40, fontSize = 12.sp,
            )
        }

        // ── Tab filter ─────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("All", "Movies", "TV Shows").forEachIndexed { i, label ->
                GenrePill(label, tab == i) { tab = i }
            }
        }
        Spacer(Modifier.height(8.dp))

        val filtered = when (tab) {
            1    -> downloads.filter { it.mediaType == "MOVIE" }
            2    -> downloads.filter { it.mediaType == "TV" }
            else -> downloads
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, null, tint = White20, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No downloads yet", color = White40, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Download movies to watch offline", color = White20, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { dl ->
                    DownloadCard(
                        item     = dl,
                        onPlay   = { playDownload(ctx, dl) },
                        onDelete = { vm.delete(dl, ctx) },
                        onResume = { vm.resume(ctx, dl) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

/** Launch player for a completed or partially downloaded item. */
private fun playDownload(ctx: Context, dl: DownloadItem) {
    val intent = Intent(ctx, PlayerActivity::class.java).apply {
        putExtra("tmdbId",     dl.tmdbId)
        putExtra("mediaType",  dl.mediaType)
        putExtra("season",     dl.season)
        putExtra("episode",    dl.episode)
        putExtra("title",      dl.title)
        putExtra("posterPath", dl.posterPath)
    }
    when {
        // Fully merged MP4
        dl.status == DownloadStatus.DONE.name && dl.filePath.isNotBlank() -> {
            intent.data = Uri.fromFile(File(dl.filePath))
        }
        // Partial HLS — point at local playlist for partial offline play
        dl.localPlaylistPath.isNotBlank() -> {
            intent.data = Uri.fromFile(File(dl.localPlaylistPath))
            intent.putExtra("isLocalHls", true)
        }
        else -> return
    }
    ctx.startActivity(intent)
}

@Composable
fun DownloadCard(
    item: DownloadItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onResume: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isDownloading = item.status == DownloadStatus.DOWNLOADING.name
    val isDone        = item.status == DownloadStatus.DONE.name
    val isPaused      = item.status == DownloadStatus.PAUSED.name
    val isError       = item.status == DownloadStatus.ERROR.name
    val canPlayPartial = item.localPlaylistPath.isNotBlank()
    val canPlay       = isDone || canPlayPartial

    // Progress fraction
    val pct = when {
        item.totalSegments > 0 -> item.segmentsDone.toFloat() / item.totalSegments
        item.sizeBytes > 0     -> (item.downloadedBytes.toFloat() / item.sizeBytes).coerceIn(0f, 1f)
        else                   -> 0f
    }
    val pctInt = (pct * 100).toInt()

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
    ) {
        Column {
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Poster
                Box(Modifier.width(60.dp).height(84.dp).clip(RoundedCornerShape(8.dp)).background(BgRaised)) {
                    AsyncImage(
                        model = BuildConfig.TMDB_IMG_W342 + item.posterPath,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Play overlay
                    if (canPlay) {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(.3f)).clickable(onClick = onPlay),
                            Alignment.Center,
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = White, modifier = Modifier.size(24.dp))
                        }
                    }
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        item.title, color = White, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (item.season > 0) {
                        Text(
                            "S${item.season} E${item.episode} · ${item.episodeName}",
                            color = White60, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    // Status row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusBadge(item.status)
                        // Quality label
                        QualityBadge(item.quality)
                        // Size info
                        if (item.sizeBytes > 0) {
                            Text(formatSize(item.sizeBytes), color = White40, fontSize = 11.sp)
                        }
                    }

                    // Downloading: progress bar + speed + segment count
                    if (isDownloading) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { pct },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                            color = Brand,
                            trackColor = GlassMd,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Segments or bytes progress
                            val progressLabel = when {
                                item.totalSegments > 0 ->
                                    "${item.segmentsDone}/${item.totalSegments} segments ($pctInt%)"
                                item.sizeBytes > 0 ->
                                    "${formatSize(item.downloadedBytes)} / ${formatSize(item.sizeBytes)} ($pctInt%)"
                                else -> "$pctInt%"
                            }
                            Text(progressLabel, color = White40, fontSize = 10.sp)
                            // Network speed
                            if (item.networkSpeedBps > 0) {
                                Text(formatSpeed(item.networkSpeedBps), color = Brand, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Partial-play hint
                        if (canPlayPartial && pct >= 0.05f) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Brand.copy(.12f))
                                    .clickable(onClick = onPlay)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.Default.PlayCircle, null, tint = Brand, modifier = Modifier.size(12.dp))
                                Text("Watch ${pctInt}% now", color = Brand, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // Paused / Error → resume button
                    if (isPaused || isError) {
                        Spacer(Modifier.height(6.dp))
                        if (pct > 0f) {
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                                color = White40,
                                trackColor = GlassMd,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (isPaused) "Paused · will auto-resume" else "Failed",
                                color = if (isPaused) White40 else Error,
                                fontSize = 10.sp,
                            )
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Brand.copy(.15f))
                                    .clickable(onClick = onResume)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text("Resume", color = Brand, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Delete button
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Default.Delete, null, tint = White40, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = BgCard,
            title = { Text("Delete Download", color = White) },
            text  = { Text("Remove \"${item.title}\" from your downloads?", color = White60) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text("Delete", color = Error)
                }
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
        DownloadStatus.DONE.name        -> Brand to "Ready"
        DownloadStatus.DOWNLOADING.name -> Gold to "Downloading"
        DownloadStatus.QUEUED.name      -> White60 to "Queued"
        DownloadStatus.PAUSED.name      -> White60 to "Paused"
        DownloadStatus.ERROR.name       -> Error to "Failed"
        else                            -> White40 to status
    }
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(.15f))
            .border(1.dp, color.copy(.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) { Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun QualityBadge(quality: String) {
    if (quality.isBlank()) return
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(White.copy(.06f))
            .border(1.dp, GlassBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) { Text(quality, color = White60, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
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
