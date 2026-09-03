package com.axio.reelz.ui.screens.transfer

// ─────────────────────────────────────────────────────────────────────────────
//  TransferScreen — Reelz Beam  (v6 — full rewrite with all fixes)
//
//  CHANGES IN V6:
//  1. IdlePage completely replaced with a rich poster-grid browser identical
//     to BrowsePage but usable BEFORE connection:
//       • Movie posters in 2-column grid with real PosterImage thumbnails
//       • TV series in an expandable card with episode rows + quality picker
//       • Tick/check overlay on selected items (Xender-style)
//       • "Send N files" button fixed at bottom; tapping it starts the sender
//         flow (QR shown) and the selected files are automatically sent the
//         moment the receiver connects — no second tap required.
//       • Receive button unchanged.
//
//  2. QR generation now happens on Dispatchers.Default inside TransferManager,
//     so the UI never blocks. QrCard shows a spinner until the bitmap arrives.
//
//  3. Received files are registered in DownloadDao (see TransferManager) with
//     full duplicate prevention (same mediaId+season+episode+quality = skip;
//     different quality = add as new row under same media entry).
//
//  SCREEN FLOW (unchanged):
//    1. User opens Transfer → sees downloaded files with Send / Receive
//    2. User selects items, taps Send → PermissionPage → QR shown
//       (selected items queued and sent automatically on connection)
//    3. Or: user taps Receive → PermissionPage → Scanner
//    4. Connected → BrowsePage for additional transfers
// ─────────────────────────────────────────────────────────────────────────────

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.axio.reelz.core.database.DownloadDao
import com.axio.reelz.core.database.DownloadRow
import com.axio.reelz.core.database.TransferDao
import com.axio.reelz.core.database.TransferRecord
import com.axio.reelz.data.model.DownloadItem
import com.axio.reelz.data.model.DownloadStatus
import com.axio.reelz.transfer.*
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.screens.files.formatSize
import com.axio.reelz.ui.screens.files.formatSpeed
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import javax.inject.Inject

// ─── Vector icons ─────────────────────────────────────────────────────────────

private val IconBack: ImageVector get() = ImageVector.Builder("TrBack", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(19f,12f); lineTo(5f,12f); moveTo(12f,19f); lineTo(5f,12f); lineTo(12f,5f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

private val IconQr: ImageVector get() = ImageVector.Builder("TrQr", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(3f,3f); lineTo(9f,3f); lineTo(9f,9f); lineTo(3f,9f); close()
        moveTo(5f,5f); lineTo(7f,5f); lineTo(7f,7f); lineTo(5f,7f); close()
        moveTo(15f,3f); lineTo(21f,3f); lineTo(21f,9f); lineTo(15f,9f); close()
        moveTo(17f,5f); lineTo(19f,5f); lineTo(19f,7f); lineTo(17f,7f); close()
        moveTo(3f,15f); lineTo(9f,15f); lineTo(9f,21f); lineTo(3f,21f); close()
        moveTo(5f,17f); lineTo(7f,17f); lineTo(7f,19f); lineTo(5f,19f); close()
        moveTo(15f,15f); lineTo(17f,15f); lineTo(17f,17f); lineTo(15f,17f); close()
        moveTo(19f,15f); lineTo(21f,15f); lineTo(21f,17f); lineTo(19f,17f); close()
        moveTo(17f,19f); lineTo(21f,19f); lineTo(21f,21f); lineTo(17f,21f); close()
    }, fill = SolidColor(Color.White))
}.build()

private val IconScan: ImageVector get() = ImageVector.Builder("TrScan", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(3f,7f); lineTo(3f,3f); lineTo(7f,3f)
        moveTo(17f,3f); lineTo(21f,3f); lineTo(21f,7f)
        moveTo(21f,17f); lineTo(21f,21f); lineTo(17f,21f)
        moveTo(7f,21f); lineTo(3f,21f); lineTo(3f,17f)
        moveTo(3f,12f); lineTo(21f,12f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconCamera: ImageVector get() = ImageVector.Builder("TrCam", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(23f,19f); arcTo(2f,2f,0f,false,true,21f,21f); lineTo(3f,21f)
        arcTo(2f,2f,0f,false,true,1f,19f); lineTo(1f,8f)
        arcTo(2f,2f,0f,false,true,3f,6f); lineTo(7f,6f); lineTo(9f,3f); lineTo(15f,3f)
        lineTo(17f,6f); lineTo(21f,6f); arcTo(2f,2f,0f,false,true,23f,8f); close()
        moveTo(12f,10f); arcTo(4f,4f,0f,false,false,12f,18f)
        arcTo(4f,4f,0f,false,false,12f,10f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, fill = SolidColor(Color.Transparent))
}.build()

private val IconUpload: ImageVector get() = ImageVector.Builder("TrUp", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f,3f); lineTo(12f,15f)
        moveTo(8f,7f); lineTo(12f,3f); lineTo(16f,7f)
        moveTo(20f,17f); lineTo(20f,21f); lineTo(4f,21f); lineTo(4f,17f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconDownload: ImageVector get() = ImageVector.Builder("TrDl", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f,3f); lineTo(12f,15f)
        moveTo(8f,11f); lineTo(12f,15f); lineTo(16f,11f)
        moveTo(20f,17f); lineTo(20f,21f); lineTo(4f,21f); lineTo(4f,17f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconCheck: ImageVector get() = ImageVector.Builder("TrOk", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f,2f); arcTo(10f,10f,0f,false,false,12f,22f)
        arcTo(10f,10f,0f,false,false,12f,2f); close()
        moveTo(8f,12f); lineTo(11f,15f); lineTo(16f,9f)
    }, stroke = SolidColor(Color(0xFF2DD36F)), strokeLineWidth = 1.7f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconWifi: ImageVector get() = ImageVector.Builder("TrWifi", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(5f,12.55f); arcTo(11f,11f,0f,false,true,19f,12.55f)
        moveTo(1.42f,9f); arcTo(16f,16f,0f,false,true,22.58f,9f)
        moveTo(8.53f,16.11f); arcTo(6f,6f,0f,false,true,15.47f,16.11f)
        moveTo(12f,20f); lineTo(12f,20.01f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconBell: ImageVector get() = ImageVector.Builder("TrBell", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(18f,8f); arcTo(6f,6f,0f,false,false,6f,8f)
        curveTo(6f,15f,3f,17f,3f,17f); lineTo(21f,17f)
        curveTo(21f,17f,18f,15f,18f,8f)
        moveTo(13.73f,21f); arcTo(2f,2f,0f,false,true,10.27f,21f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconBeam: ImageVector get() = ImageVector.Builder("TrBeam", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(22f,12f); lineTo(2f,12f)
        moveTo(22f,12f); lineTo(16f,6f)
        moveTo(22f,12f); lineTo(16f,18f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconClose: ImageVector get() = ImageVector.Builder("TrX", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(18f,6f); lineTo(6f,18f); moveTo(6f,6f); lineTo(18f,18f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconChevronDown: ImageVector get() = ImageVector.Builder("TrChev", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(6f,9f); lineTo(12f,15f); lineTo(18f,9f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconFilm: ImageVector get() = ImageVector.Builder("TrFilm", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(2f,3f); lineTo(22f,3f); lineTo(22f,21f); lineTo(2f,21f); close()
        moveTo(2f,8f); lineTo(22f,8f); moveTo(2f,16f); lineTo(22f,16f)
        moveTo(7f,3f); lineTo(7f,8f); moveTo(12f,3f); lineTo(12f,8f)
        moveTo(17f,3f); lineTo(17f,8f); moveTo(7f,16f); lineTo(7f,21f)
        moveTo(12f,16f); lineTo(12f,21f); moveTo(17f,16f); lineTo(17f,21f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f,
       strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconShield: ImageVector get() = ImageVector.Builder("TrShield", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f,22f); curveTo(12f,22f,3f,18f,3f,12f); lineTo(3f,5f); lineTo(12f,2f)
        lineTo(21f,5f); lineTo(21f,12f); curveTo(21f,18f,12f,22f,12f,22f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconLocation: ImageVector get() = ImageVector.Builder("TrLoc", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f,2f); arcTo(7f,7f,0f,false,false,5f,9f)
        curveTo(5f,14.25f,12f,22f,12f,22f)
        curveTo(12f,22f,19f,14.25f,19f,9f)
        arcTo(7f,7f,0f,false,false,12f,2f); close()
        moveTo(12f,11.5f); arcTo(2.5f,2.5f,0f,false,false,12f,6.5f)
        arcTo(2.5f,2.5f,0f,false,false,12f,11.5f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconSwap: ImageVector get() = ImageVector.Builder("TrSwap", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(7f,16f); lineTo(17f,16f); moveTo(13f,12f); lineTo(17f,16f); lineTo(13f,20f)
        moveTo(17f,8f); lineTo(7f,8f);  moveTo(11f,12f); lineTo(7f,8f);  lineTo(11f,4f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

// ─── Permission helpers ───────────────────────────────────────────────────────

private fun requiredRuntimePermissions(forSend: Boolean): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (!forSend) {
        add(Manifest.permission.CAMERA)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun Context.isGranted(perm: String): Boolean =
    ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

private fun Context.isWifiEnabled(): Boolean = try {
    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    wm?.isWifiEnabled == true
} catch (_: Exception) { false }

private fun Context.allTransferPermsGranted(forSend: Boolean): Boolean =
    requiredRuntimePermissions(forSend).all { isGranted(it) } && isWifiEnabled()

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val transferManager: TransferManager,
    private val downloadDao:     DownloadDao,
    private val transferDao:     TransferDao,
) : ViewModel() {

    val uiState:      StateFlow<TransferUiState>   = transferManager.uiState
    val sendQueue:    StateFlow<List<TransferItem>> = transferManager.sendQueue
    val receiveQueue: StateFlow<List<TransferItem>> = transferManager.receiveQueue
    val hasActiveWork: StateFlow<Boolean>           = transferManager.hasActiveWork

    val history: StateFlow<List<TransferRecord>> = transferDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val completedDownloads: StateFlow<List<DownloadItem>> = downloadDao.observeAll()
        .map { list ->
            list.filter { it.status == DownloadStatus.DONE.name && it.filePath.isNotBlank() }
                .map { it.toDownloadItem() }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun startAsSender()              = transferManager.startAsSender()
    fun connectFromQr(rawQr: String) = transferManager.connectFromQr(rawQr)

    fun sendSelected(items: List<DownloadItem>) {
        val queueItems = mutableListOf<TransferItem>()
        items.forEach { dl ->
            val isHls = dl.filePath.endsWith(".m3u8", ignoreCase = true)
            if (isHls) {
                // HLS: the filePath points to segments/index.m3u8.
                // We must send ALL .ts segment files + a rewritten m3u8 with
                // relative paths so the receiver can play it offline.
                val m3u8File   = java.io.File(dl.filePath)
                val segmentsDir = m3u8File.parentFile ?: return@forEach
                val tsFiles = segmentsDir.listFiles()
                    ?.filter { it.name.endsWith(".ts") && it.length() > 0 }
                    ?.sortedBy { it.name }
                    ?: emptyList()

                val baseName = buildFileName(dl)

                // --- Rewrite the m3u8 with relative-only segment URIs ---
                val rewrittenPlaylist = try {
                    val original = m3u8File.readText()
                    val sb = StringBuilder()
                    original.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            // Replace any path (absolute or relative) with just the filename
                            sb.appendLine(java.io.File(trimmed).name)
                        } else {
                            sb.appendLine(line)
                        }
                    }
                    sb.toString()
                } catch (_: Exception) { null }

                // Persist the rewritten m3u8 as a temp file next to the original
                val rewrittenFile = java.io.File(segmentsDir, "index_rel.m3u8")
                if (rewrittenPlaylist != null) {
                    rewrittenFile.writeText(rewrittenPlaylist)
                } else {
                    rewrittenFile.delete()
                    m3u8File.copyTo(rewrittenFile, overwrite = true)
                }

                // Enqueue all .ts segments first
                tsFiles.forEach { tsFile ->
                    queueItems += TransferItem(
                        fileName  = tsFile.name,
                        filePath  = tsFile.absolutePath,
                        sizeBytes = tsFile.length(),
                        title     = dl.title,
                        posterUrl = dl.posterUrl ?: "",
                        mediaType = dl.mediaType,
                        season    = dl.season,
                        episode   = dl.episode,
                        quality   = dl.quality,
                        mediaId   = dl.mediaId,
                    )
                }

                // Enqueue the rewritten playlist last (receiver uses it to assemble)
                queueItems += TransferItem(
                    fileName  = "$baseName.m3u8",
                    filePath  = rewrittenFile.absolutePath,
                    sizeBytes = rewrittenFile.length(),
                    title     = dl.title,
                    posterUrl = dl.posterUrl ?: "",
                    mediaType = dl.mediaType,
                    season    = dl.season,
                    episode   = dl.episode,
                    quality   = dl.quality,
                    mediaId   = dl.mediaId,
                )
            } else {
                // MP4: single file — straightforward
                queueItems += TransferItem(
                    fileName  = buildFileName(dl) + ".mp4",
                    filePath  = dl.filePath,
                    sizeBytes = dl.sizeBytes,
                    title     = dl.title,
                    posterUrl = dl.posterUrl ?: "",
                    mediaType = dl.mediaType,
                    season    = dl.season,
                    episode   = dl.episode,
                    quality   = dl.quality,
                    mediaId   = dl.mediaId,
                )
            }
        }
        transferManager.enqueueToSend(queueItems)
    }

    fun cancelActiveSend()               = transferManager.cancelActiveSend()
    fun cancelQueuedReceive(id: String)  = transferManager.cancelQueuedReceive(id)
    fun cancelActiveReceive()            = transferManager.cancelActiveReceive()
    fun disconnect()                     = transferManager.disconnect()
    fun reset()                          = transferManager.disconnect()

    override fun onCleared() {
        super.onCleared()
        transferManager.release()
    }

    private fun buildFileName(dl: DownloadItem): String = when {
        dl.episode > 0 -> "${dl.title} S${dl.season.toString().padStart(2,'0')}E${dl.episode.toString().padStart(2,'0')} ${dl.quality}"
        else           -> "${dl.title} ${dl.quality}"
    }
}

private fun DownloadRow.toDownloadItem() = DownloadItem(
    id = id, mediaId = mediaId, title = title, posterUrl = posterUrl,
    mediaType = mediaType, season = season, episode = episode, episodeName = episodeName,
    quality = quality,
    // For HLS downloads filePath == localPlaylistPath == segments/index.m3u8
    filePath          = if (localPlaylistPath.isNotBlank()) localPlaylistPath else filePath,
    localPlaylistPath = localPlaylistPath,
    sizeBytes = sizeBytes, downloadedBytes = downloadedBytes,
    status = DownloadStatus.DONE, streamUrl = streamUrl, createdAt = createdAt, completedAt = completedAt,
)

// ─── Transfer intent ──────────────────────────────────────────────────────────

private enum class TransferIntent { NONE, SEND, RECEIVE }

// ─── Screen root ──────────────────────────────────────────────────────────────

@Composable
fun TransferScreen(
    nav: NavController? = null,
    vm:  TransferViewModel = hiltViewModel(),
) {
    val d            = LocalDimensions.current
    val ctx          = LocalContext.current
    val uiState      by vm.uiState.collectAsState()
    val hasWork      by vm.hasActiveWork.collectAsState()
    val sendQueue    by vm.sendQueue.collectAsState()
    val receiveQueue by vm.receiveQueue.collectAsState()
    val downloads    by vm.completedDownloads.collectAsState()

    var intent               by remember { mutableStateOf(TransferIntent.NONE) }
    var showPermPage         by remember { mutableStateOf(false) }
    var showPanel            by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    // Files selected on IdlePage before connection is established
    var pendingSendIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val isConnected = uiState is TransferUiState.Connected || uiState is TransferUiState.Transferring

    // When the receiver connects, auto-send any files the user pre-selected
    LaunchedEffect(isConnected) {
        if (isConnected && pendingSendIds.isNotEmpty()) {
            val toSend = downloads.filter { it.id in pendingSendIds }
            if (toSend.isNotEmpty()) vm.sendSelected(toSend)
            pendingSendIds = emptySet()
        }
    }

    DisposableEffect(Unit) { onDispose { vm.reset() } }

    BackHandler(enabled = isConnected) { showDisconnectDialog = true }

    if (showDisconnectDialog) {
        DisconnectDialog(
            onConfirm = { showDisconnectDialog = false; vm.disconnect(); nav?.popBackStack() },
            onDismiss = { showDisconnectDialog = false },
        )
    }

    LaunchedEffect(intent) {
        if (intent == TransferIntent.NONE) return@LaunchedEffect
        showPermPage = !ctx.allTransferPermsGranted(forSend = intent == TransferIntent.SEND)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
            BeamHeader(
                nav           = nav,
                isConnected   = isConnected,
                peerName      = (uiState as? TransferUiState.Connected)?.peerName
                    ?: (uiState as? TransferUiState.Transferring)?.peerName,
                onBackRequest = { if (isConnected) showDisconnectDialog = true else nav?.popBackStack() },
            )

            when {
                // ── Connected: browse & send more files ───────────────────────
                isConnected -> {
                    showPermPage = false
                    intent = TransferIntent.NONE
                    BrowsePage(uiState = uiState, downloads = downloads, ctx = ctx, vm = vm)
                }

                // ── Permission gate ───────────────────────────────────────────
                showPermPage -> {
                    PermissionPage(
                        intent       = intent,
                        ctx          = ctx,
                        onAllGranted = {
                            showPermPage = false
                            when (intent) {
                                TransferIntent.SEND    -> vm.startAsSender()
                                TransferIntent.RECEIVE -> { /* scanner shows in ReceivePage */ }
                                else -> {}
                            }
                        },
                        onBack = { showPermPage = false; intent = TransferIntent.NONE; vm.reset() },
                    )
                }

                // ── QR / Scanner / Idle ───────────────────────────────────────
                else -> {
                    when (intent) {
                        TransferIntent.SEND    -> SendPage(
                            uiState = uiState, ctx = ctx, vm = vm,
                            onReset = { intent = TransferIntent.NONE; vm.reset() }
                        )
                        TransferIntent.RECEIVE -> ReceivePage(
                            uiState = uiState, ctx = ctx, vm = vm,
                            onReset = { intent = TransferIntent.NONE; vm.reset() }
                        )
                        TransferIntent.NONE    -> IdlePage(
                            downloads     = downloads,
                            pendingSendIds = pendingSendIds,
                            onSend        = { selectedIds ->
                                pendingSendIds = selectedIds
                                intent = TransferIntent.SEND
                            },
                            onReceive     = { intent = TransferIntent.RECEIVE },
                        )
                    }
                }
            }
        }

        // ── Floating transfer button ──────────────────────────────────────────
        AnimatedVisibility(
            visible  = hasWork || isConnected,
            enter    = scaleIn() + fadeIn(),
            exit     = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            val activeCount = remember(sendQueue, receiveQueue) {
                (sendQueue + receiveQueue).count {
                    it.status == TransferItemStatus.ACTIVE || it.status == TransferItemStatus.QUEUED
                }
            }
            FloatingTransferButton(
                activeCount = activeCount,
                onClick     = { showPanel = true },
                modifier    = Modifier.padding(end = 20.dp, bottom = 96.dp),
            )
        }

        if (showPanel) {
            TransferPanel(
                sendQueue           = sendQueue,
                receiveQueue        = receiveQueue,
                onCancelActiveSend  = { vm.cancelActiveSend() },
                onCancelReceive     = { id, active ->
                    if (active) vm.cancelActiveReceive() else vm.cancelQueuedReceive(id)
                },
                onDisconnect = { showPanel = false; showDisconnectDialog = true },
                onDismiss    = { showPanel = false },
            )
        }
    }
}

// ─── Idle page (rich poster-grid browser + Xender-style selection) ─────────────
//
//  DESIGN PHILOSOPHY:
//  • Movies → 2-column poster grid exactly like the connected BrowsePage, but
//    with a tap-to-select tick overlay. Long-press opens quality picker.
//  • TV series → expandable card listing seasons/episodes with quality rows
//    and a per-row checkbox just like the connected BrowsePage.
//  • "Send N files" at the bottom starts the sender flow (shows QR) and the
//    selected files are auto-sent the moment the peer connects.
//  • "Receive" button always visible for the opposite role.

@Composable
private fun IdlePage(
    downloads:     List<DownloadItem>,
    pendingSendIds: Set<String>,
    onSend:        (Set<String>) -> Unit,
    onReceive:     () -> Unit,
) {
    val d = LocalDimensions.current

    val movies = remember(downloads) { downloads.filter { it.episode == 0 }.groupBy { it.title } }
    val series = remember(downloads) { downloads.filter { it.episode > 0  }.groupBy { it.title } }

    var selected         by remember { mutableStateOf<Set<String>>(emptySet()) }
    var qualityPickerFor by remember { mutableStateOf<String?>(null) }

    // Restore pending selection if user backed from permission page
    LaunchedEffect(pendingSendIds) {
        if (pendingSendIds.isNotEmpty()) selected = pendingSendIds
    }

    Box(Modifier.fillMaxSize()) {
        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 120.dp), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(GlassMd).border(1.dp, GlassBorderMd, CircleShape), Alignment.Center) {
                        Icon(IconFilm, null, tint = White40, modifier = Modifier.size(36.dp))
                    }
                    Text("No downloads yet", color = White60, fontSize = d.textMd, fontWeight = FontWeight.SemiBold)
                    Text("Download movies first, then use Beam to share.", color = White40, fontSize = d.textSm, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = d.spaceXxl))
                }
            }
        } else {
            // Rich poster grid — mirrors BrowsePage exactly
            LazyColumn(
                Modifier.fillMaxSize().padding(bottom = 136.dp),
                contentPadding = PaddingValues(horizontal = d.screenHorizPad, vertical = d.spaceMd),
                verticalArrangement = Arrangement.spacedBy(d.spaceLg),
            ) {
                item {
                    // Selection status bar
                    val selCount = selected.size
                    AnimatedVisibility(selCount > 0, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(d.radiusMd))
                                .background(Brand.copy(0.12f))
                                .border(1.dp, Brand.copy(0.35f), RoundedCornerShape(d.radiusMd))
                                .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                        ) {
                            Icon(IconCheck, null, tint = Brand, modifier = Modifier.size(16.dp))
                            Text("$selCount file${if (selCount == 1) "" else "s"} selected",
                                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = d.textSm,
                                modifier = Modifier.weight(1f))
                            Box(
                                Modifier.clip(RoundedCornerShape(d.radiusSm))
                                    .clickable { selected = emptySet(); qualityPickerFor = null }
                                    .padding(horizontal = d.spaceMd, vertical = d.spaceXs),
                            ) {
                                Text("Clear", color = White60, fontSize = d.textXs)
                            }
                        }
                    }
                }

                if (movies.isNotEmpty()) {
                    item { SectionLabel("Movies  ·  ${movies.size}") }
                    val movieList = movies.entries.toList()
                    items(count = (movieList.size + 1) / 2, key = { "mgrid_$it" }) { rowIdx ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                            val left  = movieList.getOrNull(rowIdx * 2)
                            val right = movieList.getOrNull(rowIdx * 2 + 1)
                            if (left != null) {
                                val allIds      = left.value.map { it.id }.toSet()
                                val anySelected = allIds.any { it in selected }
                                val isMultiQ    = left.value.size > 1
                                MoviePosterCard(
                                    title           = left.key,
                                    qualities       = left.value,
                                    selected        = anySelected,
                                    selectedIds     = selected,
                                    expanded        = qualityPickerFor == left.key,
                                    onTap           = {
                                        if (isMultiQ) {
                                            qualityPickerFor = if (qualityPickerFor == left.key) null else left.key
                                        } else {
                                            selected = if (anySelected) selected - allIds else selected + allIds
                                            qualityPickerFor = null
                                        }
                                    },
                                    onLongPress     = { qualityPickerFor = if (qualityPickerFor == left.key) null else left.key },
                                    onQualityToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                                    modifier        = Modifier.weight(1f),
                                )
                            } else Spacer(Modifier.weight(1f))
                            if (right != null) {
                                val allIds      = right.value.map { it.id }.toSet()
                                val anySelected = allIds.any { it in selected }
                                val isMultiQ    = right.value.size > 1
                                MoviePosterCard(
                                    title           = right.key,
                                    qualities       = right.value,
                                    selected        = anySelected,
                                    selectedIds     = selected,
                                    expanded        = qualityPickerFor == right.key,
                                    onTap           = {
                                        if (isMultiQ) {
                                            qualityPickerFor = if (qualityPickerFor == right.key) null else right.key
                                        } else {
                                            selected = if (anySelected) selected - allIds else selected + allIds
                                            qualityPickerFor = null
                                        }
                                    },
                                    onLongPress     = { qualityPickerFor = if (qualityPickerFor == right.key) null else right.key },
                                    onQualityToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                                    modifier        = Modifier.weight(1f),
                                )
                            } else Spacer(Modifier.weight(1f))
                        }
                    }
                }

                if (series.isNotEmpty()) {
                    item { SectionLabel("Series  ·  ${series.size}") }
                    series.forEach { (title, episodes) ->
                        item(key = "series_$title") {
                            SeriesBrowseRow(
                                title    = title,
                                episodes = episodes,
                                selected = selected,
                                onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(20.dp)) }
            }
        }

        // Fixed bottom bar: Send (with count) + Receive
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Bg, Bg)))
                .padding(horizontal = d.screenHorizPad, vertical = d.spaceMd)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            val selCount = selected.size
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                BrandButton(
                    text    = when {
                        selCount == 0 -> "Send"
                        selCount == 1 -> "Send 1 file"
                        else          -> "Send $selCount files"
                    },
                    enabled = true,
                    onClick = { onSend(selected) },
                    modifier = Modifier.weight(1f),
                    icon = { Icon(IconUpload, null, tint = Color(0xFF001428), modifier = Modifier.size(18.dp)) }
                )
                Box(
                    Modifier.weight(1f).height(d.buttonHeightMd)
                        .clip(RoundedCornerShape(d.radiusMd))
                        .background(GlassMd)
                        .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
                        .clickable(onClick = onReceive),
                    Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                        Icon(IconDownload, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text("Receive", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = d.textMd)
                    }
                }
            }
        }
    }
}

// ─── Permission page ──────────────────────────────────────────────────────────

private data class PermItem(
    val label:          String,
    val description:    String,
    val icon:           ImageVector,
    val perm:           String? = null,
    val settingsAction: String? = null,
    val isSystemSetting: Boolean = false,
)

@Composable
private fun PermissionPage(
    intent:       TransferIntent,
    ctx:          Context,
    onAllGranted: () -> Unit,
    onBack:       () -> Unit,
) {
    val d = LocalDimensions.current
    val forSend = intent == TransferIntent.SEND

    val permItems: List<PermItem> = remember(forSend) {
        buildList {
            add(PermItem(
                label           = "Wi-Fi",
                description     = "Required for device-to-device wireless transfer",
                icon            = IconWifi,
                settingsAction  = Settings.ACTION_WIFI_SETTINGS,
                isSystemSetting = true,
            ))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermItem(
                    label       = "Nearby Devices",
                    description = "Required for Wi-Fi Direct & hotspot discovery on Android 13+",
                    icon        = IconWifi,
                    perm        = Manifest.permission.NEARBY_WIFI_DEVICES,
                ))
            } else {
                add(PermItem(
                    label       = "Location",
                    description = "Required to discover nearby Wi-Fi Direct networks",
                    icon        = IconLocation,
                    perm        = Manifest.permission.ACCESS_FINE_LOCATION,
                ))
            }
            if (!forSend) {
                add(PermItem(
                    label       = "Camera",
                    description = "Required to scan the sender's QR code",
                    icon        = IconCamera,
                    perm        = Manifest.permission.CAMERA,
                ))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermItem(
                    label       = "Notifications",
                    description = "Required to show transfer progress while app is in background",
                    icon        = IconBell,
                    perm        = Manifest.permission.POST_NOTIFICATIONS,
                ))
            }
        }
    }

    var refreshKey by remember { mutableStateOf(0) }

    val grantedMap: Map<String, Boolean> = remember(refreshKey) {
        permItems.associate { item ->
            item.label to when {
                item.isSystemSetting -> ctx.isWifiEnabled()
                item.perm != null    -> ctx.isGranted(item.perm)
                else                 -> true
            }
        }
    }
    val allGranted = grantedMap.values.all { it }

    val multiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshKey++ }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshKey++ }

    LaunchedEffect(allGranted) {
        if (allGranted) onAllGranted()
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = d.screenHorizPad)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Spacer(Modifier.height(d.spaceXs))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(BrandDeep.copy(0.3f), Color.Transparent)))
                    .border(1.dp, Brand.copy(0.4f), CircleShape),
                Alignment.Center,
            ) {
                Icon(IconShield, null, tint = Brand, modifier = Modifier.size(24.dp))
            }
            Column {
                Text("Permissions needed", color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textLg)
                Text(
                    "All permissions are required to guarantee ${if (intent == TransferIntent.SEND) "sending" else "receiving"} works.",
                    color = White60, fontSize = d.textSm,
                )
            }
        }

        permItems.forEach { item ->
            val granted = grantedMap[item.label] == true
            PermissionRow(
                item    = item,
                granted = granted,
                onAllow = {
                    if (item.perm != null) {
                        multiPermLauncher.launch(arrayOf(item.perm))
                    } else {
                        val settingsIntent = Intent(item.settingsAction ?: Settings.ACTION_WIFI_SETTINGS)
                        try { settingsLauncher.launch(settingsIntent) }
                        catch (_: Exception) {
                            settingsLauncher.launch(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", ctx.packageName, null))
                            )
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(d.spaceMd))

        BrandButton(
            text     = "Next →",
            enabled  = allGranted,
            onClick  = onAllGranted,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(Modifier.fillMaxWidth().clickable(onClick = onBack).padding(vertical = d.spaceSm), Alignment.Center) {
            Text("Cancel", color = White40, fontSize = d.textSm)
        }

        Spacer(Modifier.height(d.spaceXxl * 3f))
    }
}

@Composable
private fun PermissionRow(item: PermItem, granted: Boolean, onAllow: () -> Unit) {
    val d = LocalDimensions.current
    val borderColor by animateColorAsState(
        if (granted) Success.copy(0.4f) else AmberBorder, tween(300), label = "pb"
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, borderColor, RoundedCornerShape(d.radiusMd))
            .padding(d.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (granted) Success.copy(0.12f) else GlassMd)
                .border(1.dp, if (granted) Success.copy(0.35f) else GlassBorderMd, CircleShape),
            Alignment.Center,
        ) {
            Icon(if (granted) IconCheck else item.icon, null,
                tint = if (granted) Success else White60,
                modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = d.textMd)
            Text(item.description, color = White40, fontSize = d.textXs)
        }
        if (!granted) {
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusSm))
                    .background(Brush.horizontalGradient(listOf(BrandDeep, Brand)))
                    .clickable(onClick = onAllow)
                    .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
            ) {
                Text("Allow", color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textSm)
            }
        } else {
            Box(
                Modifier.clip(RoundedCornerShape(d.radiusSm))
                    .background(Success.copy(0.12f))
                    .border(1.dp, Success.copy(0.3f), RoundedCornerShape(d.radiusSm))
                    .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
            ) {
                Text("Allowed", color = Success, fontWeight = FontWeight.SemiBold, fontSize = d.textSm)
            }
        }
    }
}

// ─── Send page ────────────────────────────────────────────────────────────────

@Composable
private fun SendPage(
    uiState: TransferUiState,
    ctx:     Context,
    vm:      TransferViewModel,
    onReset: () -> Unit,
) {
    val d = LocalDimensions.current

    var startedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!startedOnce) {
            startedOnce = true
            vm.startAsSender()
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Spacer(Modifier.height(d.spaceXs))

        when (uiState) {
            is TransferUiState.Idle, is TransferUiState.Preparing -> SilentLoadingCard()
            is TransferUiState.QrReady -> {
                val payload = BeamPayload.decode(uiState.payload)
                QrCard(
                    qr          = uiState.qr,
                    sessionId   = uiState.sessionId,
                    hotspotSsid = if (payload?.tier == "HS") payload.ssid else null,
                    hotspotPass = if (payload?.tier == "HS") payload.password else null,
                    tier        = payload?.tier,
                    onReset     = { onReset() },
                )
            }
            is TransferUiState.Error -> ErrorCard(
                msg       = uiState.msg,
                retryable = uiState.retryable,
                kind      = uiState.kind,
                onRetry   = { onReset() },
            )
            else -> {}
        }
        Spacer(Modifier.height(d.spaceXxl * 3f))
    }
}

// ─── Receive page ─────────────────────────────────────────────────────────────

@Composable
private fun ReceivePage(
    uiState: TransferUiState,
    ctx:     Context,
    vm:      TransferViewModel,
    onReset: () -> Unit,
) {
    val d = LocalDimensions.current

    fun onQrScanned(raw: String) {
        if (!raw.startsWith("reelzbeam://")) return
        vm.connectFromQr(raw)
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Spacer(Modifier.height(d.spaceXs))

        when (uiState) {
            is TransferUiState.Idle       -> ScannerCard(onScanned = ::onQrScanned)
            is TransferUiState.Connecting -> ScannerConnectingOverlay()
            is TransferUiState.Error -> {
                ErrorCard(
                    msg       = uiState.msg,
                    retryable = uiState.retryable,
                    kind      = uiState.kind,
                    onRetry   = { onReset() },
                )
                if (uiState.kind != TransferUiState.ErrorKind.SWITCH_ROLE) {
                    Spacer(Modifier.height(d.spaceSm))
                    ScannerCard(onScanned = ::onQrScanned)
                }
            }
            else -> {}
        }
        Spacer(Modifier.height(d.spaceXxl * 3f))
    }
}

// ─── Browse page (post-connection) ────────────────────────────────────────────

@Composable
private fun BrowsePage(
    uiState:   TransferUiState,
    downloads: List<DownloadItem>,
    ctx:       Context,
    vm:        TransferViewModel,
) {
    val d = LocalDimensions.current

    val movies = remember(downloads) { downloads.filter { it.episode == 0 }.groupBy { it.title } }
    val series = remember(downloads) { downloads.filter { it.episode > 0  }.groupBy { it.title } }

    var selected         by remember { mutableStateOf<Set<String>>(emptySet()) }
    var qualityPickerFor by remember { mutableStateOf<String?>(null) }

    val peerName = (uiState as? TransferUiState.Connected)?.peerName
        ?: (uiState as? TransferUiState.Transferring)?.peerName ?: "Peer"
    val isSending = uiState is TransferUiState.Transferring

    Column(Modifier.fillMaxSize()) {
        ConnectedBadge(
            peerName = peerName,
            tier     = (uiState as? TransferUiState.Connected)?.tier
                ?: (uiState as? TransferUiState.Transferring)?.tier,
            modifier = Modifier.padding(horizontal = d.screenHorizPad),
        )
        Spacer(Modifier.height(d.spaceSm))

        if (downloads.isEmpty()) {
            Box(Modifier.weight(1f).padding(bottom = 100.dp), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(GlassMd).border(1.dp, GlassBorderMd, CircleShape), Alignment.Center) {
                        Icon(IconFilm, null, tint = White40, modifier = Modifier.size(36.dp))
                    }
                    Text("No downloads to share yet", color = White60, fontSize = d.textMd, fontWeight = FontWeight.SemiBold)
                    Text("The other device can still send to you.", color = White40, fontSize = d.textSm)
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = d.screenHorizPad, vertical = d.spaceSm),
                verticalArrangement = Arrangement.spacedBy(d.spaceLg),
            ) {
                if (movies.isNotEmpty()) {
                    item { SectionLabel("Movies  ·  ${movies.size}") }
                    val movieList = movies.entries.toList()
                    items(count = (movieList.size + 1) / 2, key = { "mgrid_$it" }) { rowIdx ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                            val left  = movieList.getOrNull(rowIdx * 2)
                            val right = movieList.getOrNull(rowIdx * 2 + 1)
                            if (left != null) {
                                val allIds      = left.value.map { it.id }.toSet()
                                val anySelected = allIds.any { it in selected }
                                val isMultiQ    = left.value.size > 1
                                MoviePosterCard(
                                    title = left.key, qualities = left.value, selected = anySelected,
                                    selectedIds = selected,
                                    expanded = qualityPickerFor == left.key,
                                    onTap = {
                                        if (isMultiQ) {
                                            // Multi-quality: tap opens quality picker
                                            qualityPickerFor = if (qualityPickerFor == left.key) null else left.key
                                        } else {
                                            // Single quality: tap toggles the file
                                            selected = if (anySelected) selected - allIds else selected + allIds
                                            qualityPickerFor = null
                                        }
                                    },
                                    onLongPress = { qualityPickerFor = if (qualityPickerFor == left.key) null else left.key },
                                    onQualityToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                                    modifier = Modifier.weight(1f),
                                )
                            } else Spacer(Modifier.weight(1f))
                            if (right != null) {
                                val allIds      = right.value.map { it.id }.toSet()
                                val anySelected = allIds.any { it in selected }
                                val isMultiQ    = right.value.size > 1
                                MoviePosterCard(
                                    title = right.key, qualities = right.value, selected = anySelected,
                                    selectedIds = selected,
                                    expanded = qualityPickerFor == right.key,
                                    onTap = {
                                        if (isMultiQ) {
                                            qualityPickerFor = if (qualityPickerFor == right.key) null else right.key
                                        } else {
                                            selected = if (anySelected) selected - allIds else selected + allIds
                                            qualityPickerFor = null
                                        }
                                    },
                                    onLongPress = { qualityPickerFor = if (qualityPickerFor == right.key) null else right.key },
                                    onQualityToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                                    modifier = Modifier.weight(1f),
                                )
                            } else Spacer(Modifier.weight(1f))
                        }
                    }
                }

                if (series.isNotEmpty()) {
                    item { SectionLabel("Series  ·  ${series.size}") }
                    series.forEach { (title, episodes) ->
                        item(key = "series_$title") {
                            SeriesBrowseRow(title = title, episodes = episodes, selected = selected,
                                onToggle = { id -> selected = if (id in selected) selected - id else selected + id })
                        }
                    }
                }

                item { Spacer(Modifier.height(120.dp)) }
            }
        }

        val selCount = selected.size
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Bg, Bg)))
                .padding(horizontal = d.screenHorizPad, vertical = d.spaceMd),
        ) {
            BrandButton(
                text    = when {
                    isSending     -> "Sending…"
                    selCount == 0 -> "Tap posters to select"
                    selCount == 1 -> "Send 1 file"
                    else          -> "Send $selCount files"
                },
                enabled = selCount > 0 && !isSending,
                onClick = {
                    val toSend = downloads.filter { it.id in selected }
                    vm.sendSelected(toSend)
                    selected = emptySet(); qualityPickerFor = null
                },
                modifier = Modifier.fillMaxWidth(),
                icon = { Icon(IconUpload, null, tint = Color(0xFF001428), modifier = Modifier.size(d.iconMd - 4.dp)) },
            )
        }
    }
}

// ─── Header ───────────────────────────────────────────────────────────────────

@Composable
private fun BeamHeader(
    nav:           NavController?,
    isConnected:   Boolean,
    peerName:      String?,
    onBackRequest: () -> Unit,
) {
    val d = LocalDimensions.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = d.heroPadding - d.spaceSm, vertical = d.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nav != null) {
            Box(
                Modifier.size(d.buttonHeightSm - d.spaceMd).clip(CircleShape)
                    .background(GlassMd).border(1.dp, GlassBorderMd, CircleShape)
                    .clickable(onClick = onBackRequest),
                Alignment.Center,
            ) { Icon(IconBack, null, tint = Color.White, modifier = Modifier.size(d.iconMd - 2.dp)) }
            Spacer(Modifier.width(d.spaceMd - d.spaceXxs))
        }
        Column {
            Text("Reelz Beam",
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = Color.White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp))
            Text(
                if (isConnected && peerName != null) "↔ $peerName" else "Instant wireless transfer",
                color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.weight(1f))
        val inf = rememberInfiniteTransition(label = "hdr")
        val pulse by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), "hp")
        Icon(IconBeam, null, tint = Brand.copy(if (isConnected) 1f else pulse), modifier = Modifier.size(d.iconLg))
    }
}

// ─── Movie poster card ─────────────────────────────────────────────────────────
//
//  DESIGN HIERARCHY (envelope model):
//   • 1 quality  → Tick/untick at root on tap. No inner step.
//   • 2+ quality → Tap root opens quality-picker step (step 2).
//                  Tick appears on root when ANY quality is selected.

@Composable
private fun MoviePosterCard(
    title:           String,
    qualities:       List<DownloadItem>,
    selected:        Boolean,
    selectedIds:     Set<String>,
    expanded:        Boolean,
    onTap:           () -> Unit,
    onLongPress:     () -> Unit,
    onQualityToggle: (String) -> Unit,
    modifier:        Modifier = Modifier,
) {
    val d = LocalDimensions.current
    val posterUrl         = qualities.firstOrNull()?.posterUrl ?: ""
    val totalSize         = qualities.sumOf { it.sizeBytes }
    val hue               = (title.hashCode().and(0x7FFFFFFF) % 360).toFloat()
    val placeholderColor  = Color.hsl(hue, 0.35f, 0.15f)
    val placeholderAccent = Color.hsl(hue, 0.6f, 0.45f)
    val hasMultiQuality   = qualities.size > 1
    val borderColor by animateColorAsState(if (selected) Brand else GlassBorderMd, tween(200), label = "mc")
    val bgOverlay   by animateColorAsState(if (selected) Brand.copy(0.18f) else Color.Transparent, tween(200), label = "mb")

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(d.spaceXs)) {

        // ── Root card (always visible) ──────────────────────────────────────
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(d.radiusMd)).background(placeholderColor)
                .border(2.dp, borderColor, RoundedCornerShape(d.radiusMd))
                .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        ) {
            if (posterUrl.isNotEmpty()) PosterImage(url = posterUrl, modifier = Modifier.fillMaxSize())
            else FilmStripPlaceholder(accentColor = placeholderAccent, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(bgOverlay))

            // Film strip decoration at top
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(0.55f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(5) {
                    Box(Modifier.size(width = 7.dp, height = 5.dp)
                        .background(Color.White.copy(0.5f), RoundedCornerShape(1.dp)))
                }
            }

            // Selection tick — top-right (Xender-style)
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                    .background(if (selected) Brand else Color.Black.copy(0.5f))
                    .border(1.5.dp,
                        if (selected) Color.White.copy(0.4f) else Color.White.copy(0.3f),
                        CircleShape),
                Alignment.Center,
            ) {
                if (selected) Icon(IconCheck, null, tint = Color.White, modifier = Modifier.size(13.dp))
            }

            // Bottom-left: size badge
            Box(
                Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(Color.Black.copy(0.72f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(formatSize(totalSize), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }

            // Bottom-right: multi-quality indicator (tap to drill)
            if (hasMultiQuality) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .background(
                            if (expanded) Brand.copy(0.85f) else Color.Black.copy(0.72f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(
                        "${qualities.size} qualities",
                        color = if (expanded) Color.White else Brand,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Title
        Text(
            title,
            color = if (selected) Color.White else White80,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )

        // ── Step 2: Quality picker (only when multi-quality AND expanded) ───
        AnimatedVisibility(
            visible = hasMultiQuality && expanded,
            enter   = expandVertically() + fadeIn(),
            exit    = shrinkVertically() + fadeOut(),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(d.radiusSm))
                    .background(BgCard)
                    .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusSm))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Choose quality",
                    color = White40, fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                qualities.forEach { dl ->
                    val qualSel = dl.id in selectedIds
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (qualSel) BlueGlass else GlassMd)
                            .border(1.dp, if (qualSel) Brand.copy(0.5f) else GlassBorderSm, RoundedCornerShape(5.dp))
                            .clickable { onQualityToggle(dl.id) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CheckCircle(checked = qualSel)
                        Text(
                            dl.quality,
                            color = if (qualSel) Color.White else White60,
                            fontWeight = if (qualSel) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 11.sp, modifier = Modifier.weight(1f),
                        )
                        Text(formatSize(dl.sizeBytes), color = White40, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

// ─── Series browse row ─────────────────────────────────────────────────────────
//
//  HIERARCHY (envelope model):
//   Step 1 — Root card  (show poster + title + episode count)
//              Checkbox at root selects the entire show.
//   Step 2 — Season list (tap root to expand)
//              Checkbox per season selects that whole season.
//   Step 3 — Episode list (tap season to expand)
//              Each episode row = one content unit. Checkbox selects it.
//   Step 4 — Quality picker (only when episode has 2+ qualities)
//              Appears inline under the episode on tap.

@Composable
private fun SeriesBrowseRow(
    title:    String,
    episodes: List<DownloadItem>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val d = LocalDimensions.current
    val seasons     = remember(episodes) { episodes.groupBy { it.season }.toSortedMap() }
    val allIds      = remember(episodes) { episodes.map { it.id }.toSet() }
    val anySelected = allIds.any { it in selected }
    val allSelected = allIds.isNotEmpty() && allIds.all { it in selected }

    // Tracks which seasons are expanded (step 2 → 3)
    var showSeasons        by remember { mutableStateOf(false) }
    var expandedSeasons    by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // Tracks which episode is showing its quality picker (step 3 → 4)
    var qualityPickerEpKey by remember { mutableStateOf<String?>(null) }  // "season_episode"

    // ── Root card ────────────────────────────────────────────────────────────
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, if (anySelected) Brand.copy(0.45f) else GlassBorderMd, RoundedCornerShape(d.radiusMd)),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // ── Step 1: Show header ──────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth()
                .clickable { showSeasons = !showSeasons }
                .padding(d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Poster thumbnail
            val posterUrl = episodes.firstOrNull()?.posterUrl ?: ""
            val hue       = (title.hashCode().and(0x7FFFFFFF) % 360).toFloat()
            Box(
                Modifier.size(width = 44.dp, height = 62.dp)
                    .clip(RoundedCornerShape(d.radiusSm))
                    .background(Color.hsl(hue, 0.35f, 0.15f)),
            ) {
                if (posterUrl.isNotEmpty()) PosterImage(url = posterUrl, modifier = Modifier.fillMaxSize())
                else FilmStripPlaceholder(accentColor = Color.hsl(hue, 0.6f, 0.45f), modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(d.spaceMd))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textMd, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val selCount = allIds.count { it in selected }
                Text(
                    buildString {
                        append("${episodes.size} ep${if (episodes.size == 1) "" else "s"}")
                        if (selCount > 0) append(" · $selCount selected")
                    },
                    color = if (selCount > 0) Brand else White40,
                    fontSize = d.textXs,
                )
                Text("${seasons.size} season${if (seasons.size == 1) "" else "s"}",
                    color = White40, fontSize = d.textXs)
            }
            // Show-level checkbox (selects/deselects the entire show)
            Box(
                Modifier.size(26.dp).clip(CircleShape)
                    .background(if (allSelected) Brand else if (anySelected) Brand.copy(0.3f) else GlassMd)
                    .border(1.5.dp, if (anySelected) Brand else GlassBorderMd, CircleShape)
                    .clickable {
                        // Toggle entire show: select all if none/partial selected, deselect all if all selected
                        if (allSelected) {
                            allIds.forEach { onToggle(it) }
                        } else {
                            allIds.filter { it !in selected }.forEach { onToggle(it) }
                        }
                    },
                Alignment.Center,
            ) {
                if (allSelected) Icon(IconCheck, null, tint = Color.White, modifier = Modifier.size(14.dp))
                else if (anySelected) Box(Modifier.size(8.dp).clip(CircleShape).background(Brand))
            }
            Spacer(Modifier.width(d.spaceSm))
            val rot by animateFloatAsState(if (showSeasons) 180f else 0f, label = "srot")
            Icon(IconChevronDown, null, tint = White60, modifier = Modifier.size(d.iconMd - 4.dp).rotate(rot))
        }

        // ── Step 2: Season list ──────────────────────────────────────────────
        AnimatedVisibility(showSeasons, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Bg.copy(0.6f))
                    .padding(start = d.spaceMd, end = d.spaceMd, bottom = d.spaceMd),
                verticalArrangement = Arrangement.spacedBy(d.spaceXs),
            ) {
                seasons.forEach { (seasonNum, eps) ->
                    val seasonIds    = eps.map { it.id }.toSet()
                    val seasonAllSel = seasonIds.isNotEmpty() && seasonIds.all { it in selected }
                    val seasonAnySel = seasonIds.any { it in selected }
                    val seasonOpen   = seasonNum in expandedSeasons

                    // ── Season header row ────────────────────────────────────
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(d.radiusSm))
                            .background(BgRaised)
                            .border(1.dp, if (seasonAnySel) Brand.copy(0.35f) else GlassBorderSm, RoundedCornerShape(d.radiusSm)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    expandedSeasons = if (seasonOpen)
                                        expandedSeasons - seasonNum else expandedSeasons + seasonNum
                                }
                                .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                        ) {
                            // Season-level checkbox
                            Box(
                                Modifier.size(22.dp).clip(CircleShape)
                                    .background(if (seasonAllSel) Brand else if (seasonAnySel) Brand.copy(0.25f) else GlassMd)
                                    .border(1.dp, if (seasonAnySel) Brand else GlassBorderSm, CircleShape)
                                    .clickable {
                                        if (seasonAllSel) {
                                            seasonIds.forEach { onToggle(it) }
                                        } else {
                                            seasonIds.filter { it !in selected }.forEach { onToggle(it) }
                                        }
                                    },
                                Alignment.Center,
                            ) {
                                if (seasonAllSel) Icon(IconCheck, null, tint = Color.White, modifier = Modifier.size(12.dp))
                                else if (seasonAnySel) Box(Modifier.size(6.dp).clip(CircleShape).background(Brand))
                            }

                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Season $seasonNum",
                                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = d.textSm,
                                )
                                val selEpCount = seasonIds.count { it in selected }
                                Text(
                                    "${eps.size} episode${if (eps.size == 1) "" else "s"}${if (selEpCount > 0) " · $selEpCount selected" else ""}",
                                    color = if (selEpCount > 0) Brand else White40, fontSize = d.textXs,
                                )
                            }

                            val sRot by animateFloatAsState(if (seasonOpen) 180f else 0f, label = "sn$seasonNum")
                            Icon(IconChevronDown, null, tint = White60, modifier = Modifier.size(16.dp).rotate(sRot))
                        }

                        // ── Step 3: Episode list ─────────────────────────────
                        AnimatedVisibility(seasonOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            val byEpisode = eps.groupBy { it.episode }.toSortedMap()
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = d.spaceSm).padding(bottom = d.spaceSm),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                byEpisode.forEach { (epNum, qualities) ->
                                    val epKey       = "${seasonNum}_$epNum"
                                    val epName      = qualities.firstOrNull()?.episodeName?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                                    val epIds       = qualities.map { it.id }.toSet()
                                    val epAllSel    = epIds.isNotEmpty() && epIds.all { it in selected }
                                    val epAnySel    = epIds.any { it in selected }
                                    val hasMultiQ   = qualities.size > 1
                                    val qualOpen    = qualityPickerEpKey == epKey

                                    // ── Episode row ──────────────────────────
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(d.radiusSm))
                                            .background(if (epAnySel) BlueGlass else GlassMd)
                                            .border(1.dp, if (epAnySel) Brand.copy(0.4f) else GlassBorderSm, RoundedCornerShape(d.radiusSm)),
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clickable {
                                                    if (hasMultiQ) {
                                                        // Tap opens quality picker
                                                        qualityPickerEpKey = if (qualOpen) null else epKey
                                                    } else {
                                                        // Single quality: toggle directly
                                                        onToggle(qualities.first().id)
                                                    }
                                                }
                                                .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                                        ) {
                                            // Episode checkbox (always visible; for multi-q selects all/none)
                                            Box(
                                                Modifier.size(20.dp).clip(CircleShape)
                                                    .background(if (epAllSel) Brand else if (epAnySel) Brand.copy(0.25f) else GlassMd)
                                                    .border(1.dp, if (epAnySel) Brand else GlassBorderSm, CircleShape)
                                                    .clickable {
                                                        if (hasMultiQ) {
                                                            if (epAllSel) epIds.forEach { onToggle(it) }
                                                            else epIds.filter { it !in selected }.forEach { onToggle(it) }
                                                        } else {
                                                            onToggle(qualities.first().id)
                                                        }
                                                    },
                                                Alignment.Center,
                                            ) {
                                                if (epAllSel) Icon(IconCheck, null, tint = Color.White, modifier = Modifier.size(11.dp))
                                                else if (epAnySel) Box(Modifier.size(5.dp).clip(CircleShape).background(Brand))
                                            }

                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    "E${epNum.toString().padStart(2, '0')} · $epName",
                                                    color = if (epAnySel) Color.White else White80,
                                                    fontWeight = if (epAnySel) FontWeight.SemiBold else FontWeight.Normal,
                                                    fontSize = d.textSm, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                )
                                                val sizeText = formatSize(qualities.sumOf { it.sizeBytes })
                                                Row(horizontalArrangement = Arrangement.spacedBy(d.spaceXs)) {
                                                    Text(sizeText, color = White40, fontSize = d.textXs)
                                                    if (hasMultiQ) Text(
                                                        "${qualities.size} qualities",
                                                        color = if (qualOpen) Brand else White40, fontSize = d.textXs,
                                                    )
                                                }
                                            }

                                            if (hasMultiQ) {
                                                val qRot by animateFloatAsState(if (qualOpen) 180f else 0f, label = "qr$epKey")
                                                Icon(IconChevronDown, null, tint = White40, modifier = Modifier.size(14.dp).rotate(qRot))
                                            }
                                        }

                                        // ── Step 4: Quality picker (only multi-quality episodes) ──
                                        AnimatedVisibility(
                                            hasMultiQ && qualOpen,
                                            enter = expandVertically() + fadeIn(),
                                            exit  = shrinkVertically() + fadeOut(),
                                        ) {
                                            Column(
                                                Modifier.fillMaxWidth()
                                                    .background(Bg.copy(0.8f))
                                                    .padding(horizontal = d.spaceMd)
                                                    .padding(bottom = d.spaceSm),
                                                verticalArrangement = Arrangement.spacedBy(3.dp),
                                            ) {
                                                Text(
                                                    "Select quality",
                                                    color = White40, fontSize = 9.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(top = d.spaceXs, bottom = 2.dp),
                                                )
                                                qualities.forEach { dl ->
                                                    val qSel = dl.id in selected
                                                    Row(
                                                        Modifier.fillMaxWidth()
                                                            .clip(RoundedCornerShape(5.dp))
                                                            .background(if (qSel) BlueGlass else GlassMd)
                                                            .border(1.dp, if (qSel) Brand.copy(0.5f) else GlassBorderSm, RoundedCornerShape(5.dp))
                                                            .clickable { onToggle(dl.id) }
                                                            .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                                                    ) {
                                                        CheckCircle(checked = qSel)
                                                        Text(
                                                            dl.quality,
                                                            color = if (qSel) Color.White else White60,
                                                            fontWeight = if (qSel) FontWeight.SemiBold else FontWeight.Normal,
                                                            fontSize = d.textSm, modifier = Modifier.weight(1f),
                                                        )
                                                        Text(formatSize(dl.sizeBytes), color = White40, fontSize = d.textXs)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Floating transfer button ─────────────────────────────────────────────────

@Composable
private fun FloatingTransferButton(activeCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val inf = rememberInfiniteTransition(label = "fab")
    val pulse by inf.animateFloat(0.85f, 1.0f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), "fp")

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume(); offsetX += dragAmount.x; offsetY += dragAmount.y
                }
            }
            .size(56.dp).scale(if (activeCount > 0) pulse else 1f)
            .clip(CircleShape).background(Brush.radialGradient(listOf(BrandDeep, Brand)))
            .border(2.dp, Brand.copy(.5f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(IconBeam, null, tint = Color.White, modifier = Modifier.size(22.dp))
        if (activeCount > 0) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)
                .size(16.dp).clip(CircleShape).background(Color.Red), Alignment.Center) {
                Text(activeCount.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

// ─── Transfer panel ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferPanel(
    sendQueue:          List<TransferItem>,
    receiveQueue:       List<TransferItem>,
    onCancelActiveSend: () -> Unit,
    onCancelReceive:    (id: String, active: Boolean) -> Unit,
    onDisconnect:       () -> Unit,
    onDismiss:          () -> Unit,
) {
    val d = LocalDimensions.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, containerColor = BgRaised,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = d.spaceMd), Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(White40))
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad).padding(bottom = d.spaceXxl),
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            Text("Transfer", color = Color.White, fontWeight = FontWeight.Black, fontSize = d.textXl, letterSpacing = (-0.3).sp)

            val sending = sendQueue.filter { it.status != TransferItemStatus.CANCELLED && it.status != TransferItemStatus.ERROR }
            if (sending.isNotEmpty()) {
                SectionLabel("Sending")
                sending.forEach { item -> PanelQueueRow(item = item, showCancel = false, onCancel = {}) }
            } else {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusMd))
                    .background(BgCard).border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd)).padding(d.spaceMd), Alignment.Center) {
                    Text("Nothing to send", color = White40, fontSize = d.textSm)
                }
            }

            Spacer(Modifier.height(d.spaceXs))

            val receiving = receiveQueue.filter { it.status != TransferItemStatus.CANCELLED && it.status != TransferItemStatus.ERROR }
            if (receiving.isNotEmpty()) {
                SectionLabel("Receiving")
                receiving.forEach { item ->
                    PanelQueueRow(item = item, showCancel = true,
                        onCancel = { onCancelReceive(item.id, item.status == TransferItemStatus.ACTIVE) })
                }
            } else {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusMd))
                    .background(BgCard).border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd)).padding(d.spaceMd), Alignment.Center) {
                    Text("Nothing incoming", color = White40, fontSize = d.textSm)
                }
            }

            Spacer(Modifier.height(d.spaceSm))
            GhostButton("Disconnect", onClick = onDisconnect, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ─── Panel queue row ──────────────────────────────────────────────────────────

@Composable
private fun PanelQueueRow(item: TransferItem, showCancel: Boolean, onCancel: () -> Unit) {
    val d = LocalDimensions.current
    val isActive = item.status == TransferItemStatus.ACTIVE
    val isDone   = item.status == TransferItemStatus.DONE
    val progress = if (item.sizeBytes > 0) item.bytesdone.toFloat() / item.sizeBytes else 0f
    val animProg by animateFloatAsState(progress.coerceIn(0f, 1f), tween(200), label = "qp")
    val hue = (item.title.hashCode().and(0x7FFFFFFF) % 360).toFloat()

    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusMd)).background(BgCard)
            .border(1.dp, when { isActive -> Brand.copy(.4f); isDone -> Success.copy(.3f); else -> GlassBorderMd }, RoundedCornerShape(d.radiusMd))
    ) {
        if (isActive && item.sizeBytes > 0) {
            Box(Modifier.fillMaxWidth(animProg).matchParentSize().clip(RoundedCornerShape(d.radiusMd))
                .background(Brush.horizontalGradient(listOf(BrandDim.copy(.2f), BrandDeep.copy(.15f)))))
        }
        Row(Modifier.padding(d.spaceSm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
            Box(
                Modifier.size(width = 44.dp, height = 60.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.hsl(hue, 0.3f, 0.14f))
                    .border(1.dp, if (isActive) Brand.copy(.3f) else GlassBorderSm, RoundedCornerShape(6.dp))
            ) {
                if (item.posterUrl.isNotEmpty()) PosterImage(url = item.posterUrl, modifier = Modifier.fillMaxSize())
                else FilmStripPlaceholder(accentColor = Color.hsl(hue, 0.55f, 0.4f), modifier = Modifier.fillMaxSize())
                if (isDone) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f)))
                    Box(Modifier.align(Alignment.Center).size(16.dp).clip(CircleShape).background(Success), Alignment.Center) {
                        Icon(IconCheck, null, tint = Color.White, modifier = Modifier.size(10.dp))
                    }
                }
                if (isActive) {
                    val inf = rememberInfiniteTransition(label = "pr")
                    val a by inf.animateFloat(0.2f, 0.7f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "pa")
                    Box(Modifier.fillMaxSize().border(2.dp, Brand.copy(a), RoundedCornerShape(6.dp)))
                }
            }

            Column(Modifier.weight(1f)) {
                val displayTitle = if (item.title.isNotBlank()) item.title else item.fileName
                Text(displayTitle, color = Color.White, fontSize = d.textSm,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val badge = buildString {
                    if (item.quality.isNotBlank()) append(item.quality)
                    if (item.season  > 0) append("  S${item.season.toString().padStart(2,'0')}")
                    if (item.episode > 0) append("E${item.episode.toString().padStart(2,'0')}")
                }
                if (badge.isNotBlank()) Text(badge, color = White40, fontSize = d.textXs)
                Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        isDone   -> Text("✓ Done", color = Success, fontSize = d.textXs, fontWeight = FontWeight.SemiBold)
                        isActive -> {
                            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(GlassMd)) {
                                Box(Modifier.fillMaxWidth(animProg).fillMaxHeight().background(Brush.horizontalGradient(listOf(BrandDeep, Brand))))
                            }
                            Text(formatSize(item.bytesdone), color = Brand, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            if (item.speedBps > 0) Text(formatSpeed(item.speedBps), color = White40, fontSize = 9.sp)
                        }
                        else -> {
                            Text("Queued", color = White40, fontSize = d.textXs)
                            Text(formatSize(item.sizeBytes), color = White40, fontSize = d.textXs)
                        }
                    }
                }
            }

            if (showCancel && !isDone) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(GlassMd).border(1.dp, GlassBorderSm, CircleShape)
                    .clickable(onClick = onCancel), Alignment.Center) {
                    Icon(IconClose, null, tint = White60, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

// ─── Connected badge ──────────────────────────────────────────────────────────

@Composable
private fun ConnectedBadge(peerName: String, tier: TransportTier?, modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    val (tierLabel, tierColor) = when (tier) {
        TransportTier.WIFI_DIRECT -> "Wi-Fi Direct · fastest" to Brand
        TransportTier.HOTSPOT     -> "Hotspot · reliable" to Warning
        null                      -> "Connected" to Success
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusLg))
            .background(Brush.linearGradient(listOf(Color(0xFF0D1F0D), Success.copy(.4f))))
            .border(1.dp, Success.copy(.35f), RoundedCornerShape(d.radiusLg)).padding(d.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Success.copy(.15f)).border(1.dp, Success.copy(.4f), CircleShape), Alignment.Center) {
            Icon(IconCheck, null, tint = Success, modifier = Modifier.size(d.iconMd))
        }
        Column(Modifier.weight(1f)) {
            Text("Connected to $peerName", color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textMd)
            Text(tierLabel, color = tierColor, fontSize = d.textXs, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─── Sub-components ───────────────────────────────────────────────────────────

@Composable
private fun CheckCircle(checked: Boolean) {
    val d = LocalDimensions.current
    Box(Modifier.size(20.dp).clip(CircleShape)
        .background(if (checked) AmberGlass else GlassMd)
        .border(1.5.dp, if (checked) AmberBorder else GlassBorderSm, CircleShape), Alignment.Center) {
        if (checked) Icon(IconCheck, null, tint = Brand, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    val d = LocalDimensions.current
    Text(text, color = White60, fontSize = d.textSm, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
}

@Composable
private fun SilentLoadingCard() {
    val d = LocalDimensions.current
    Box(Modifier.fillMaxWidth().padding(vertical = d.spaceXxl), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
            CinematicSpinner(size = d.spinnerMd + d.spaceXl)
            Text("Preparing…", color = White60, fontSize = d.textMd)
        }
    }
}

@Composable
private fun ScannerConnectingOverlay() {
    val d = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    Box(
        Modifier.fillMaxWidth().height(screenH * 0.42f).clip(RoundedCornerShape(d.radiusLg))
            .background(Color.Black.copy(0.85f)).border(1.dp, Brand.copy(.5f), RoundedCornerShape(d.radiusLg)),
        Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
            CinematicSpinner(size = d.spinnerMd + d.spaceXl)
            Text("Connecting…", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = d.textLg)
            Text("Hold still — establishing link", color = White60, fontSize = d.textSm)
        }
    }
}

@Composable
private fun QrCard(
    qr:          android.graphics.Bitmap?,
    sessionId:   String,
    hotspotSsid: String?,
    hotspotPass: String?,
    tier:        String?,
    onReset:     () -> Unit,
) {
    val d = LocalDimensions.current
    val tierLabel = when (tier) {
        "WD" -> "Wi-Fi Direct"
        "HS" -> "Hotspot"
        else -> "Wi-Fi"
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusLg))
            .background(Brush.verticalGradient(listOf(BgCard, BgRaised)))
            .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg)).padding(d.spaceXl - d.spaceXs),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                val inf = rememberInfiniteTransition(label = "dot")
                val a by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), "da")
                Box(Modifier.size(8.dp).background(Success.copy(a), CircleShape))
                Text("Ready to scan  ·  $tierLabel", color = White60, fontSize = d.textSm)
                Spacer(Modifier.weight(1f))
                Text("ID: $sessionId", color = White40, fontSize = d.textXs)
            }

            // QR code area — shows spinner while bitmap is generating on background thread
            Box(Modifier.size(240.dp).clip(RoundedCornerShape(d.radiusMd)).background(Color.White).padding(8.dp)) {
                if (qr != null) {
                    Image(qr.asImageBitmap(), "QR", modifier = Modifier.fillMaxSize())
                } else {
                    // Bitmap not ready yet (still generating on Default dispatcher)
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CinematicSpinner(size = d.spinnerMd)
                    }
                }
            }

            Text("Scan with the other device's Reelz app", color = White60, fontSize = d.textSm, textAlign = TextAlign.Center)

            if (!hotspotSsid.isNullOrEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusSm))
                        .background(Color(0xFF0D1A0D)).border(1.dp, Success.copy(.3f), RoundedCornerShape(d.radiusSm))
                        .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
                    horizontalArrangement = Arrangement.spacedBy(d.spaceLg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconWifi, null, tint = Success, modifier = Modifier.size(d.iconMd - 2.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Network", color = White40, fontSize = d.textXs)
                        Text(hotspotSsid, color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textSm, maxLines = 1)
                    }
                    Column {
                        Text("Password", color = White40, fontSize = d.textXs)
                        Text(hotspotPass ?: "", color = Brand, fontWeight = FontWeight.Bold, fontSize = d.textSm)
                    }
                }
            }

            val inf = rememberInfiniteTransition(label = "wt")
            val dots by inf.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), "wd")
            val dotStr = ".".repeat((dots.toInt() % 3) + 1)
            Text("Waiting for receiver$dotStr", color = White40, fontSize = d.textXs)

            GhostButton("Reset", onClick = onReset, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ─── Error card ───────────────────────────────────────────────────────────────

@Composable
private fun ErrorCard(
    msg:       String,
    retryable: Boolean,
    onRetry:   () -> Unit,
    kind:      TransferUiState.ErrorKind = TransferUiState.ErrorKind.GENERIC,
) {
    val d = LocalDimensions.current
    val (icon, accentColor, headline) = when (kind) {
        TransferUiState.ErrorKind.PERMISSION  -> Triple(IconShield, Color(0xFFFF9F0A), "Permission required")
        TransferUiState.ErrorKind.CONNECTION  -> Triple(IconWifi,  Error,             "Couldn't connect")
        TransferUiState.ErrorKind.TIMEOUT     -> Triple(IconWifi,  Color(0xFFFF9F0A), "Connection timed out")
        TransferUiState.ErrorKind.TRANSFER    -> Triple(IconFilm,  Error,             "Transfer failed")
        TransferUiState.ErrorKind.SWITCH_ROLE -> Triple(IconSwap,  Brand,             "Switch roles to connect")
        else                                  -> Triple(IconBeam,  Error,             "Something went wrong")
    }

    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusLg))
            .background(Brush.verticalGradient(listOf(BgCard, BgRaised)))
            .border(1.dp, accentColor.copy(.35f), RoundedCornerShape(d.radiusLg)).padding(d.spaceXl),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(72.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(accentColor.copy(.12f), Color.Transparent)))
                    .border(1.dp, accentColor.copy(.3f), CircleShape))
                Icon(icon, null, tint = accentColor.copy(.85f), modifier = Modifier.size(30.dp))
            }
            Text(headline, color = Color.White, fontSize = d.textLg, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(msg, color = White60, fontSize = d.textSm, textAlign = TextAlign.Center, lineHeight = (d.textSm.value * 1.6f).sp)

            if (retryable) BrandButton(
                if (kind == TransferUiState.ErrorKind.SWITCH_ROLE) "Start Over" else "Try Again",
                onClick = onRetry, modifier = Modifier.fillMaxWidth()
            )
            else GhostButton("Go Back", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DisconnectDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = BgCard,
        title   = { Text("Disconnect?", color = Color.White) },
        text    = { Text("This will end the session on both devices.", color = White60) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Disconnect", color = Error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay", color = Brand) } },
    )
}

// ─── QR Scanner ───────────────────────────────────────────────────────────────

@Composable
private fun ScannerCard(onScanned: (String) -> Unit) {
    val d       = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    Box(
        Modifier.fillMaxWidth().height(screenH * 0.42f)
            .clip(RoundedCornerShape(d.radiusLg)).background(Color.Black)
            .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg))
    ) {
        CameraScanner(onScanned = onScanned)

        val inf = rememberInfiniteTransition(label = "sl")
        val scanY by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), "sy")
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 48.dp)
            .offset(y = (scanY * 250).dp).height(2.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Brand2, Brand, Brand2, Color.Transparent))))

        listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd).forEach { a ->
            val bs = 32.dp; val bw = 3.dp
            Box(Modifier.padding(24.dp).size(bs).align(a)) {
                val top   = a == Alignment.TopStart || a == Alignment.TopEnd
                val start = a == Alignment.TopStart || a == Alignment.BottomStart
                Box(Modifier.align(if (top) Alignment.TopStart else Alignment.BottomStart).width(bs).height(bw).background(Brand))
                Box(Modifier.align(if (start) Alignment.TopStart else Alignment.TopEnd).width(bw).height(bs).background(Brand))
            }
        }

        Text("Point at the sender's QR code", color = White60, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = d.spaceLg)
                .background(Color.Black.copy(.5f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
fun CameraScanner(onScanned: (String) -> Unit) {
    val ctx            = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor       = remember { Executors.newSingleThreadExecutor() }
    var hasScanned     by remember { mutableStateOf(false) }

    AndroidView(factory = { context ->
        val pv = PreviewView(context)
        ProcessCameraProvider.getInstance(context).addListener({
            val provider = ProcessCameraProvider.getInstance(context).get()
            val preview  = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                if (!hasScanned) {
                    val result = decodeQr(proxy)
                    if (result != null) { hasScanned = true; onScanned(result) }
                }
                proxy.close()
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(context))
        pv
    }, modifier = Modifier.fillMaxSize())
}

private fun decodeQr(proxy: ImageProxy): String? = try {
    val buf   = proxy.planes[0].buffer
    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
    val src   = PlanarYUVLuminanceSource(bytes, proxy.width, proxy.height, 0, 0, proxy.width, proxy.height, false)
    MultiFormatReader()
        .also { it.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
        .decode(BinaryBitmap(HybridBinarizer(src))).text
} catch (_: Exception) { null }

// ─── Film strip placeholder ───────────────────────────────────────────────────

@Composable
private fun FilmStripPlaceholder(accentColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF0A0A12), Color(0xFF1A1A28))))
        drawCircle(brush = Brush.radialGradient(listOf(accentColor.copy(0.3f), Color.Transparent),
            center = center, radius = size.minDimension * 0.45f))
        val perfW = size.width * 0.08f; val perfH = size.height * 0.04f; val perfGap = perfH * 2.4f
        var y = perfGap
        while (y < size.height - perfH) {
            drawRoundRect(color = Color.White.copy(0.25f),
                topLeft = Offset(size.width * 0.01f, y), size = androidx.compose.ui.geometry.Size(perfW, perfH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f))
            drawRoundRect(color = Color.White.copy(0.25f),
                topLeft = Offset(size.width * 0.91f, y), size = androidx.compose.ui.geometry.Size(perfW, perfH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f))
            y += perfGap
        }
        val cx = center.x; val cy = center.y; val r = size.minDimension * 0.12f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx - r, cy - r * 1.2f); lineTo(cx + r * 1.4f, cy); lineTo(cx - r, cy + r * 1.2f); close()
        }
        drawPath(path, color = accentColor.copy(0.6f))
    }
}

// ─── Async poster image ───────────────────────────────────────────────────────

@Composable
private fun PosterImage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(url) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4_000; conn.readTimeout = 6_000; conn.connect()
                val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                withContext(kotlinx.coroutines.Dispatchers.Main) { bitmap = bmp }
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}
