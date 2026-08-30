package com.axio.reelz.ui.screens.transfer

// ─────────────────────────────────────────────────────────────────────────────
//  TransferScreen — Reelz Beam  (FULL SPEC IMPLEMENTATION)
//
//  Screen flow per spec:
//
//  BEAM PAGE (entry)
//    Two tabs: Send (shows QR) | Receive (shows camera scanner)
//    QR generated silently, no extra tap required.
//    Scanner is the full view the moment tab opens.
//
//  BROWSE PAGE (after connection)
//    Both devices land here simultaneously after handshake.
//    Shows your own downloaded content — movies with quality options,
//    series → seasons → episodes, each with a checkbox.
//    Send button at bottom enqueues selected items.
//    Both sides send independently, no waiting.
//
//  FLOATING BUTTON
//    Appears the moment any transfer starts.
//    Stays on screen through all navigation.
//    Pulses / shows queue count.
//    Draggable.
//    Tapping opens Transfer Panel.
//
//  TRANSFER PANEL (bottom sheet)
//    Top half: Sending queue — active item with progress bar + speed,
//              queued items below. No cancel on send (you chose to send it).
//    Bottom half: Receiving queue — active item with progress + X button,
//                 queued items with X button.
//    Disconnect button at bottom.
// ─────────────────────────────────────────────────────────────────────────────

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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

// ─── Permissions ──────────────────────────────────────────────────────────────

private fun buildTransferPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
}.toTypedArray()

private fun Context.allTransferPermsGranted(): Boolean {
    val required = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    return required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val transferManager: TransferManager,
    private val downloadDao: DownloadDao,
    private val transferDao: TransferDao,
) : ViewModel() {

    val uiState:      StateFlow<TransferUiState>    = transferManager.uiState
    val sendQueue:    StateFlow<List<TransferItem>>  = transferManager.sendQueue
    val receiveQueue: StateFlow<List<TransferItem>>  = transferManager.receiveQueue
    val hasActiveWork: StateFlow<Boolean>            = transferManager.hasActiveWork

    val history: StateFlow<List<TransferRecord>> = transferDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Downloads grouped for browse screen:
    //   movies  → title → list of quality options
    //   series  → title → season → episode → quality options
    val completedDownloads: StateFlow<List<DownloadItem>> = downloadDao.observeAll()
        .map { list ->
            list.filter { it.status == DownloadStatus.DONE.name && it.filePath.isNotBlank() }
                .map { it.toDownloadItem() }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun startAsSender()              = transferManager.startAsSender()
    fun connectFromQr(rawQr: String) = transferManager.connectFromQr(rawQr)

    /** Enqueue the selected items into the send queue. */
    fun sendSelected(items: List<DownloadItem>) {
        val queueItems = items.map { dl ->
            TransferItem(
                fileName  = buildFileName(dl),
                filePath  = dl.filePath,
                sizeBytes = dl.sizeBytes,
                // BUG3 FIX: carry metadata so receiver shows poster/title card
                title     = dl.title,
                posterUrl = dl.posterUrl,
                mediaType = dl.mediaType,
                season    = dl.season,
                episode   = dl.episode,
                quality   = dl.quality,
            )
        }
        transferManager.enqueueToSend(queueItems)
    }

    fun cancelActiveSend()                  = transferManager.cancelActiveSend()
    fun cancelQueuedReceive(id: String)     = transferManager.cancelQueuedReceive(id)
    fun cancelActiveReceive()               = transferManager.cancelActiveReceive()
    fun disconnect()                        = transferManager.disconnect()
    fun reset()                             = transferManager.disconnect()

    override fun onCleared() {
        super.onCleared()
        transferManager.release()
    }

    private fun buildFileName(dl: DownloadItem): String {
        return when {
            dl.episode > 0 -> "${dl.title} S${dl.season.toString().padStart(2,'0')}E${dl.episode.toString().padStart(2,'0')} ${dl.quality}"
            else           -> "${dl.title} ${dl.quality}"
        }
    }
}

private fun DownloadRow.toDownloadItem() = DownloadItem(
    id = id, mediaId = mediaId, title = title, posterUrl = posterUrl,
    mediaType = mediaType, season = season, episode = episode, episodeName = episodeName,
    quality = quality, filePath = filePath, sizeBytes = sizeBytes, downloadedBytes = downloadedBytes,
    status = DownloadStatus.DONE, streamUrl = streamUrl, createdAt = createdAt, completedAt = completedAt,
)

// ─── Screen root ──────────────────────────────────────────────────────────────

@Composable
fun TransferScreen(
    nav: NavController? = null,
    vm: TransferViewModel = hiltViewModel(),
) {
    val d             = LocalDimensions.current
    val ctx           = LocalContext.current
    val uiState       by vm.uiState.collectAsState()
    val hasWork       by vm.hasActiveWork.collectAsState()
    val sendQueue     by vm.sendQueue.collectAsState()
    val receiveQueue  by vm.receiveQueue.collectAsState()
    val downloads     by vm.completedDownloads.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var showPanel by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    val isConnected = uiState is TransferUiState.Connected || uiState is TransferUiState.Transferring

    DisposableEffect(Unit) { onDispose { vm.reset() } }

    // FIX: switching tabs should only reset if we're idle or in an error state.
    // If sender is showing a QR (QrReady) and user taps Receive, we must
    // invalidate the QR so the old ServerSocket is released and no stale
    // receiver can connect to a session the user has abandoned.
    LaunchedEffect(tab) {
        if (!isConnected) {
            val state = uiState
            val shouldReset = state is TransferUiState.Idle
                || state is TransferUiState.Error
                || state is TransferUiState.QrReady   // user switched role while QR was showing
                || state is TransferUiState.Preparing
                || state is TransferUiState.Connecting
            if (shouldReset) vm.reset()
        }
    }

    BackHandler(enabled = isConnected) { showDisconnectDialog = true }

    if (showDisconnectDialog) {
        DisconnectDialog(
            onConfirm = { showDisconnectDialog = false; vm.disconnect(); nav?.popBackStack() },
            onDismiss = { showDisconnectDialog = false },
        )
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

            AnimatedVisibility(!isConnected, enter = fadeIn(), exit = fadeOut()) {
                BeamTabBar(selected = tab, onSelect = { tab = it })
            }

            Spacer(Modifier.height(d.spaceXs))

            when {
                isConnected ->
                    BrowsePage(
                        uiState   = uiState,
                        downloads = downloads,
                        ctx       = ctx,
                        vm        = vm,
                    )
                tab == 0 -> SendTab(uiState = uiState, ctx = ctx, vm = vm)
                else     -> ReceiveTab(uiState = uiState, ctx = ctx, vm = vm)
            }
        }

        // ── Floating transfer button ──────────────────────────────────────────
        AnimatedVisibility(
            visible = hasWork || isConnected,
            enter   = scaleIn() + fadeIn(),
            exit    = scaleOut() + fadeOut(),
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

        // ── Transfer panel bottom sheet ───────────────────────────────────────
        if (showPanel) {
            TransferPanel(
                sendQueue    = sendQueue,
                receiveQueue = receiveQueue,
                onCancelActiveSend    = { vm.cancelActiveSend() },
                onCancelReceive       = { id, active ->
                    if (active) vm.cancelActiveReceive() else vm.cancelQueuedReceive(id)
                },
                onDisconnect = { showPanel = false; showDisconnectDialog = true },
                onDismiss    = { showPanel = false },
            )
        }
    }
}

// ─── Header ───────────────────────────────────────────────────────────────────

@Composable
private fun BeamHeader(
    nav: NavController?,
    isConnected: Boolean,
    peerName: String?,
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
            Text(
                "Reelz Beam",
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = Color.White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
            )
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

// ─── Tab bar ──────────────────────────────────────────────────────────────────

@Composable
private fun BeamTabBar(selected: Int, onSelect: (Int) -> Unit) {
    val d = LocalDimensions.current
    Box(
        Modifier.padding(horizontal = d.screenHorizPad)
            .clip(RoundedCornerShape(d.radiusMd)).background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd)).padding(d.spaceXs),
    ) {
        Row {
            listOf("Share" to IconQr, "Receive" to IconScan).forEachIndexed { i, (label, icon) ->
                val sel = selected == i
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(d.spaceMd))
                        .background(
                            if (sel) Brush.horizontalGradient(listOf(BrandDeep, Brand.copy(.9f)))
                            else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        ).clickable { onSelect(i) }.padding(vertical = d.spaceMd),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                        Icon(icon, null, tint = if (sel) Color.White else White60, modifier = Modifier.size(d.iconMd - 4.dp))
                        Text(label, color = if (sel) Color.White else White60,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = d.textMd)
                    }
                }
            }
        }
    }
}

// ─── Send tab ─────────────────────────────────────────────────────────────────

@Composable
private fun SendTab(uiState: TransferUiState, ctx: Context, vm: TransferViewModel) {
    val d = LocalDimensions.current
    var hasPerms by remember { mutableStateOf(ctx.allTransferPermsGranted()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPerms = results.entries
            .filter { (p, _) -> p == Manifest.permission.ACCESS_FINE_LOCATION ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && p == Manifest.permission.NEARBY_WIFI_DEVICES) }
            .all { (_, g) -> g }
        if (hasPerms) vm.startAsSender()
    }

    // FIX: guard against double-firing. Only call startAsSender() once per tab
    // visit. If the user manually resets, they tap "Reset" which calls vm.reset()
    // and then the user re-lands on SendTab, which sets startedOnce = false.
    var startedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(hasPerms, uiState) {
        if (hasPerms && uiState is TransferUiState.Idle && !startedOnce) {
            startedOnce = true
            vm.startAsSender()
        }
        // Allow re-start after an error or explicit reset
        if (uiState is TransferUiState.Error || uiState is TransferUiState.Idle) {
            if (startedOnce && uiState is TransferUiState.Idle) startedOnce = false
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Spacer(Modifier.height(d.spaceXs))

        when (uiState) {
            is TransferUiState.Idle -> {
                if (!hasPerms) {
                    PermissionCard(
                        icon        = IconWifi,
                        title       = "Wireless access needed",
                        subtitle    = "Reelz Beam needs Wi-Fi permission to create a connection. Your location is never stored or sent.",
                        buttonLabel = "Allow & Share",
                        onRequest   = { permLauncher.launch(buildTransferPermissions()) },
                    )
                } else {
                    SilentLoadingCard()
                }
            }
            is TransferUiState.Preparing -> SilentLoadingCard()
            is TransferUiState.QrReady -> {
                val payload = BeamPayload.decode(uiState.payload)
                QrCard(
                    qr           = uiState.qr,
                    sessionId    = uiState.sessionId,
                    hotspotSsid  = if (payload?.tier == "HS") payload.ssid else null,
                    hotspotPass  = if (payload?.tier == "HS") payload.password else null,
                    onReset      = { startedOnce = false; vm.reset() },
                )
            }
            is TransferUiState.Error -> ErrorCard(
                msg       = uiState.msg,
                retryable = uiState.retryable,
                kind      = uiState.kind,
                onRetry   = { vm.reset() },
            )
            else -> {}
        }
        Spacer(Modifier.height(d.spaceXxl * 3f))
    }
}

// ─── Receive tab ──────────────────────────────────────────────────────────────

@Composable
private fun ReceiveTab(
    uiState: TransferUiState,
    ctx: Context,
    vm: TransferViewModel,
) {
    val d = LocalDimensions.current
    var hasCam           by remember { mutableStateOf(ctx.hasCameraPermission()) }
    var hasTransferPerms by remember { mutableStateOf(ctx.allTransferPermsGranted()) }
    var pendingQr        by remember { mutableStateOf<String?>(null) }

    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    val transferPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasTransferPerms = results.entries
            .filter { (p, _) -> p == Manifest.permission.ACCESS_FINE_LOCATION ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && p == Manifest.permission.NEARBY_WIFI_DEVICES) }
            .all { (_, g) -> g }
        val qr = pendingQr; pendingQr = null
        if (hasTransferPerms && qr != null) vm.connectFromQr(qr)
    }

    fun onQrScanned(raw: String) {
        if (!raw.startsWith("reelzbeam://")) return
        if (hasTransferPerms) vm.connectFromQr(raw)
        else { pendingQr = raw; transferPermLauncher.launch(buildTransferPermissions()) }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Spacer(Modifier.height(d.spaceXs))

        when (uiState) {
            is TransferUiState.Idle -> {
                if (!hasCam) {
                    PermissionCard(
                        icon        = IconCamera,
                        title       = "Camera access needed",
                        subtitle    = "Point your camera at the sender's QR code to connect instantly.",
                        buttonLabel = "Allow Camera",
                        onRequest   = { camLauncher.launch(Manifest.permission.CAMERA) },
                    )
                } else {
                    ScannerCard(onScanned = ::onQrScanned)
                }
            }
            is TransferUiState.Connecting -> ScannerConnectingOverlay()
            is TransferUiState.Error -> {
                ErrorCard(msg = uiState.msg, retryable = uiState.retryable, onRetry = { vm.reset() })
                if (hasCam) {
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
//
//  Xender-style design: poster cards in a 2-column grid for movies,
//  episode rows with thumbnail frame for series.
//  Tapping a card selects all qualities; long-press opens quality picker.

@Composable
private fun BrowsePage(
    uiState:   TransferUiState,
    downloads: List<DownloadItem>,
    ctx:       Context,
    vm:        TransferViewModel,
) {
    val d = LocalDimensions.current

    val movies  = remember(downloads) { downloads.filter { it.episode == 0 }.groupBy { it.title } }
    val series  = remember(downloads) { downloads.filter { it.episode > 0  }.groupBy { it.title } }

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var qualityPickerFor by remember { mutableStateOf<String?>(null) }  // title being expanded

    val peerName = (uiState as? TransferUiState.Connected)?.peerName
        ?: (uiState as? TransferUiState.Transferring)?.peerName
        ?: "Peer"

    val isSending = uiState is TransferUiState.Transferring

    Column(Modifier.fillMaxSize()) {
        // Connected banner
        ConnectedBadge(
            peerName = peerName,
            tier     = (uiState as? TransferUiState.Connected)?.tier
                ?: (uiState as? TransferUiState.Transferring)?.tier,
            modifier = Modifier.padding(horizontal = d.screenHorizPad),
        )

        Spacer(Modifier.height(d.spaceSm))

        if (downloads.isEmpty()) {
            Box(Modifier.weight(1f), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                    // Film reel illustration
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
                // ── Movies — Xender-style 2-column poster grid ──────────────
                if (movies.isNotEmpty()) {
                    item {
                        SectionLabel("Movies  ·  ${movies.size}")
                    }
                    // Chunk into pairs for the 2-column grid
                    val movieList = movies.entries.toList()
                    items(
                        count = (movieList.size + 1) / 2,
                        key   = { "mgrid_$it" },
                    ) { rowIdx ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
                        ) {
                            val left  = movieList.getOrNull(rowIdx * 2)
                            val right = movieList.getOrNull(rowIdx * 2 + 1)
                            if (left != null) {
                                val allIds = left.value.map { it.id }.toSet()
                                val anySelected = allIds.any { it in selected }
                                MoviePosterCard(
                                    title      = left.key,
                                    qualities  = left.value,
                                    selected   = anySelected,
                                    expanded   = qualityPickerFor == left.key,
                                    onTap      = {
                                        // Single tap: toggle all qualities at once (Xender UX)
                                        selected = if (anySelected) selected - allIds else selected + allIds
                                        qualityPickerFor = null
                                    },
                                    onLongPress = {
                                        qualityPickerFor = if (qualityPickerFor == left.key) null else left.key
                                    },
                                    onQualityToggle = { id ->
                                        selected = if (id in selected) selected - id else selected + id
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            if (right != null) {
                                val allIds = right.value.map { it.id }.toSet()
                                val anySelected = allIds.any { it in selected }
                                MoviePosterCard(
                                    title      = right.key,
                                    qualities  = right.value,
                                    selected   = anySelected,
                                    expanded   = qualityPickerFor == right.key,
                                    onTap      = {
                                        selected = if (anySelected) selected - allIds else selected + allIds
                                        qualityPickerFor = null
                                    },
                                    onLongPress = {
                                        qualityPickerFor = if (qualityPickerFor == right.key) null else right.key
                                    },
                                    onQualityToggle = { id ->
                                        selected = if (id in selected) selected - id else selected + id
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                // ── Series — episode rows with film-frame thumbnail ─────────
                if (series.isNotEmpty()) {
                    item { SectionLabel("Series  ·  ${series.size}") }
                    series.forEach { (title, episodes) ->
                        item(key = "series_$title") {
                            SeriesBrowseRow(
                                title     = title,
                                episodes  = episodes,
                                selected  = selected,
                                onToggle  = { id -> selected = if (id in selected) selected - id else selected + id },
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(120.dp)) }
            }
        }

        // ── Send button ───────────────────────────────────────────────────────
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
                    selected = emptySet()
                    qualityPickerFor = null
                },
                modifier = Modifier.fillMaxWidth(),
                icon = { Icon(IconUpload, null, tint = Color(0xFF001428), modifier = Modifier.size(d.iconMd - 4.dp)) },
            )
        }
    }
}

// ─── Movie poster card (Xender-style) ─────────────────────────────────────────
//
//  Displays the movie poster (via coil/async if posterUrl available),
//  or a generated colour swatch with film-strip overlay.
//  Single tap → selects all quality variants.
//  Long press → reveals inline quality pill row.

@Composable
private fun MoviePosterCard(
    title:           String,
    qualities:       List<DownloadItem>,
    selected:        Boolean,
    expanded:        Boolean,
    onTap:           () -> Unit,
    onLongPress:     () -> Unit,
    onQualityToggle: (String) -> Unit,
    modifier:        Modifier = Modifier,
) {
    val d = LocalDimensions.current
    val posterUrl = qualities.firstOrNull()?.posterUrl ?: ""
    val totalSize = qualities.sumOf { it.sizeBytes }

    // Deterministic colour from title for placeholder
    val hue = (title.hashCode().and(0x7FFFFFFF) % 360).toFloat()
    val placeholderColor = Color.hsl(hue, 0.35f, 0.15f)
    val placeholderAccent = Color.hsl(hue, 0.6f, 0.45f)

    val borderColor by animateColorAsState(
        if (selected) Brand else GlassBorderMd, tween(200), label = "mc"
    )
    val bgOverlay by animateColorAsState(
        if (selected) Brand.copy(0.18f) else Color.Transparent, tween(200), label = "mb"
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(d.spaceXs),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(2f / 3f)   // standard movie poster ratio
                .clip(RoundedCornerShape(d.radiusMd))
                .background(placeholderColor)
                .border(2.dp, borderColor, RoundedCornerShape(d.radiusMd))
                .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        ) {
            // Poster image — if URL present, try async load
            if (posterUrl.isNotEmpty()) {
                // Lazy image loading: use AndroidView wrapping an ImageView
                // (avoids requiring Coil as a mandatory dependency)
                PosterImage(url = posterUrl, modifier = Modifier.fillMaxSize())
            } else {
                // Colourful placeholder with film-strip art
                FilmStripPlaceholder(accentColor = placeholderAccent, modifier = Modifier.fillMaxSize())
            }

            // Selection overlay
            Box(Modifier.fillMaxSize().background(bgOverlay))

            // Film-strip top bar (Xender style)
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(0.55f)).padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(5) {
                    Box(Modifier.size(width = 7.dp, height = 5.dp).background(Color.White.copy(0.5f), RoundedCornerShape(1.dp)))
                }
            }

            // Selected check badge
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .size(22.dp).clip(CircleShape)
                        .background(Brand).border(1.5.dp, Color.White.copy(0.4f), CircleShape),
                    Alignment.Center,
                ) {
                    Icon(IconCheck, null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }

            // Size badge at bottom
            Box(
                Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(Color.Black.copy(0.72f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(formatSize(totalSize), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }

            // Quality count badge
            if (qualities.size > 1) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .background(Color.Black.copy(0.72f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text("${qualities.size}Q", color = Brand, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Title under poster
        Text(
            title,
            color = if (selected) Color.White else White80,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )

        // Expanded quality picker (long-press reveals this)
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                qualities.forEach { dl ->
                    val sel = dl.id in listOf<String>().let { selected.let { _ -> dl.id } }  // simplified
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (selected) BlueGlass else GlassMd)
                            .border(1.dp, if (selected) BlueBorder else GlassBorderSm, RoundedCornerShape(6.dp))
                            .clickable { onQualityToggle(dl.id) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CheckCircle(checked = dl.id in setOf<String>())
                        Text(dl.quality, color = White60, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Text(formatSize(dl.sizeBytes), color = White40, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

// ─── Film-strip placeholder art ───────────────────────────────────────────────

@Composable
private fun FilmStripPlaceholder(accentColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // Dark gradient base
        drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF0A0A12), Color(0xFF1A1A28))))

        // Centre glow
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accentColor.copy(0.3f), Color.Transparent),
                center = center,
                radius = size.minDimension * 0.45f,
            )
        )

        // Film perforations on left and right edges
        val perfW = size.width * 0.08f
        val perfH = size.height * 0.04f
        val perfGap = perfH * 2.4f
        var y = perfGap
        while (y < size.height - perfH) {
            // Left perfs
            drawRoundRect(
                color = Color.White.copy(0.25f),
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.01f, y),
                size = androidx.compose.ui.geometry.Size(perfW, perfH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f),
            )
            // Right perfs
            drawRoundRect(
                color = Color.White.copy(0.25f),
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.91f, y),
                size = androidx.compose.ui.geometry.Size(perfW, perfH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f),
            )
            y += perfGap
        }

        // Centre play-triangle hint
        val cx = center.x; val cy = center.y; val r = size.minDimension * 0.12f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx - r, cy - r * 1.2f)
            lineTo(cx + r * 1.4f, cy)
            lineTo(cx - r, cy + r * 1.2f)
            close()
        }
        drawPath(path, color = accentColor.copy(0.6f))
    }
}

// ─── Async poster image ───────────────────────────────────────────────────────
//
//  Simple URL-to-bitmap loader without requiring Coil.
//  Uses AndroidView → ImageView with a background coroutine load.

@Composable
private fun PosterImage(url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4_000
                conn.readTimeout    = 6_000
                conn.connect()
                val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                withContext(kotlinx.coroutines.Dispatchers.Main) { bitmap = bmp }
                conn.disconnect()
            } catch (_: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) { failed = true }
            }
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
    // If null/failed: caller's placeholder shows through (transparent)
}

// ─── Movie browse row — kept as fallback for very long quality lists ───────────

@Composable
private fun MovieBrowseRow(
    title:     String,
    qualities: List<DownloadItem>,
    selected:  Set<String>,
    onToggle:  (String) -> Unit,
) {
    val d = LocalDimensions.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard).border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .padding(d.spaceMd),
        verticalArrangement = Arrangement.spacedBy(d.spaceXs),
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textMd)
        qualities.forEach { dl ->
            val sel = dl.id in selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusSm))
                    .background(if (sel) AmberGlass else GlassMd)
                    .border(1.dp, if (sel) AmberBorder else GlassBorderSm, RoundedCornerShape(d.radiusSm))
                    .clickable { onToggle(dl.id) }
                    .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
            ) {
                CheckCircle(checked = sel)
                Text(dl.quality, color = if (sel) Color.White else White60,
                    fontSize = d.textSm, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f))
                Text(formatSize(dl.sizeBytes), color = White40, fontSize = d.textXs)
            }
        }
    }
}

// ─── Series browse row ────────────────────────────────────────────────────────

@Composable
private fun SeriesBrowseRow(
    title:    String,
    episodes: List<DownloadItem>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val d = LocalDimensions.current
    var expanded by remember { mutableStateOf(false) }

    // Group by season
    val seasons = remember(episodes) { episodes.groupBy { it.season }.toSortedMap() }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard).border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .padding(d.spaceMd),
        verticalArrangement = Arrangement.spacedBy(d.spaceXs),
    ) {
        // Parent: series title + expand toggle
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textMd,
                modifier = Modifier.weight(1f))
            Text("${episodes.size} eps", color = White40, fontSize = d.textXs)
            Spacer(Modifier.width(d.spaceSm))
            val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "ser")
            Icon(IconChevronDown, null, tint = White60, modifier = Modifier.size(d.iconMd - 4.dp).rotate(rot))
        }

        // Expanded: seasons → episodes
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceXs)) {
                seasons.forEach { (season, eps) ->
                    Text("Season $season", color = White60, fontSize = d.textXs, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = d.spaceXs))
                    // Group by episode number
                    val byEpisode = eps.groupBy { it.episode }.toSortedMap()
                    byEpisode.forEach { (epNum, qualities) ->
                        val epName = qualities.firstOrNull()?.episodeName?.takeIf { it.isNotBlank() }
                            ?: "Episode $epNum"
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("  E${epNum.toString().padStart(2,'0')} · $epName",
                                color = White60, fontSize = d.textXs,
                                modifier = Modifier.padding(horizontal = d.spaceSm))
                            qualities.forEach { dl ->
                                val sel = dl.id in selected
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusSm))
                                        .background(if (sel) AmberGlass else GlassMd)
                                        .border(1.dp, if (sel) AmberBorder else GlassBorderSm, RoundedCornerShape(d.radiusSm))
                                        .clickable { onToggle(dl.id) }
                                        .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                                ) {
                                    CheckCircle(checked = sel)
                                    Text(dl.quality, color = if (sel) Color.White else White60,
                                        fontSize = d.textSm, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f))
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

// ─── Floating transfer button ─────────────────────────────────────────────────

@Composable
private fun FloatingTransferButton(
    activeCount: Int,
    onClick:     () -> Unit,
    modifier:    Modifier = Modifier,
) {
    val density = LocalDensity.current
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val inf = rememberInfiniteTransition(label = "fab")
    val pulse by inf.animateFloat(
        0.85f, 1.0f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), "fp"
    )

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .size(56.dp)
            .scale(if (activeCount > 0) pulse else 1f)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(BrandDeep, Brand)))
            .border(2.dp, Brand.copy(.5f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(IconBeam, null, tint = Color.White, modifier = Modifier.size(22.dp))
        if (activeCount > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)
                    .size(16.dp).clip(CircleShape).background(Color.Red),
                Alignment.Center,
            ) {
                Text(activeCount.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

// ─── Transfer panel ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferPanel(
    sendQueue:         List<TransferItem>,
    receiveQueue:      List<TransferItem>,
    onCancelActiveSend: () -> Unit,
    onCancelReceive:   (id: String, active: Boolean) -> Unit,
    onDisconnect:      () -> Unit,
    onDismiss:         () -> Unit,
) {
    val d = LocalDimensions.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = BgRaised,
        dragHandle       = {
            Box(Modifier.fillMaxWidth().padding(vertical = d.spaceMd), Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(White40))
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad)
                .padding(bottom = d.spaceXxl),
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            Text("Transfer", color = Color.White, fontWeight = FontWeight.Black,
                fontSize = d.textXl, letterSpacing = (-0.3).sp)

            // ── Sending section ───────────────────────────────────────────────
            val sending = sendQueue.filter {
                it.status != TransferItemStatus.CANCELLED && it.status != TransferItemStatus.ERROR
            }
            if (sending.isNotEmpty()) {
                SectionLabel("Sending")
                sending.forEach { item ->
                    PanelQueueRow(
                        item        = item,
                        showCancel  = false, // per spec: no cancel on send
                        onCancel    = {},
                    )
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusMd))
                        .background(BgCard).border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
                        .padding(d.spaceMd),
                    Alignment.Center,
                ) { Text("Nothing to send", color = White40, fontSize = d.textSm) }
            }

            Spacer(Modifier.height(d.spaceXs))

            // ── Receiving section ─────────────────────────────────────────────
            val receiving = receiveQueue.filter {
                it.status != TransferItemStatus.CANCELLED && it.status != TransferItemStatus.ERROR
            }
            if (receiving.isNotEmpty()) {
                SectionLabel("Receiving")
                receiving.forEach { item ->
                    PanelQueueRow(
                        item       = item,
                        showCancel = true,
                        onCancel   = {
                            onCancelReceive(item.id, item.status == TransferItemStatus.ACTIVE)
                        },
                    )
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusMd))
                        .background(BgCard).border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
                        .padding(d.spaceMd),
                    Alignment.Center,
                ) { Text("Nothing incoming", color = White40, fontSize = d.textSm) }
            }

            Spacer(Modifier.height(d.spaceSm))
            GhostButton("Disconnect", onClick = onDisconnect, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ─── Panel queue row (Xender-style with poster thumbnail) ─────────────────────

@Composable
private fun PanelQueueRow(
    item:       TransferItem,
    showCancel: Boolean,
    onCancel:   () -> Unit,
) {
    val d = LocalDimensions.current
    val isActive  = item.status == TransferItemStatus.ACTIVE
    val isDone    = item.status == TransferItemStatus.DONE
    val progress  = if (item.sizeBytes > 0) item.bytesdone.toFloat() / item.sizeBytes else 0f
    val animProg by animateFloatAsState(progress.coerceIn(0f, 1f), tween(200), label = "qp")

    // Xender-style: show poster thumbnail on the left when metadata available
    val hasPoster = item.posterUrl.isNotEmpty()
    val hue = (item.title.hashCode().and(0x7FFFFFFF) % 360).toFloat()
    val placeholderColor  = Color.hsl(hue, 0.3f, 0.14f)
    val placeholderAccent = Color.hsl(hue, 0.55f, 0.4f)

    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp,
                when {
                    isActive -> Brand.copy(.4f)
                    isDone   -> Success.copy(.3f)
                    else     -> GlassBorderMd
                },
                RoundedCornerShape(d.radiusMd)
            )
    ) {
        // Progress fill
        if (isActive && item.sizeBytes > 0) {
            Box(
                Modifier.fillMaxWidth(animProg).matchParentSize()
                    .clip(RoundedCornerShape(d.radiusMd))
                    .background(Brush.horizontalGradient(listOf(BrandDim.copy(.2f), BrandDeep.copy(.15f))))
            )
        }

        Row(
            Modifier.padding(d.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            // BUG3 FIX: Xender-style poster thumbnail in the queue row
            Box(
                Modifier.size(width = 44.dp, height = 60.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(placeholderColor)
                    .border(1.dp, if (isActive) Brand.copy(.3f) else GlassBorderSm, RoundedCornerShape(6.dp))
            ) {
                if (hasPoster) {
                    PosterImage(url = item.posterUrl, modifier = Modifier.fillMaxSize())
                } else {
                    FilmStripPlaceholder(accentColor = placeholderAccent, modifier = Modifier.fillMaxSize())
                }
                // Status overlay badge
                if (isDone) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f)))
                    Box(Modifier.align(Alignment.Center).size(16.dp).clip(CircleShape).background(Success), Alignment.Center) {
                        Icon(IconCheck, null, tint = Color.White, modifier = Modifier.size(10.dp))
                    }
                }
                // Active: pulse ring
                if (isActive) {
                    val inf = rememberInfiniteTransition(label = "pr")
                    val a by inf.animateFloat(0.2f, 0.7f, infiniteRepeatable(tween(700), RepeatMode.Reverse), "pa")
                    Box(Modifier.fillMaxSize().border(2.dp, Brand.copy(a), RoundedCornerShape(6.dp)))
                }
            }

            Column(Modifier.weight(1f)) {
                // Show title from metadata if available, else filename
                val displayTitle = if (item.title.isNotBlank()) item.title else item.fileName
                Text(displayTitle, color = Color.White, fontSize = d.textSm,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)

                // Quality + season/ep badge
                val badge = buildString {
                    if (item.quality.isNotBlank()) append(item.quality)
                    if (item.season > 0) append("  S${item.season.toString().padStart(2,'0')}")
                    if (item.episode > 0) append("E${item.episode.toString().padStart(2,'0')}")
                }
                if (badge.isNotBlank()) {
                    Text(badge, color = White40, fontSize = d.textXs)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        isDone   -> Text("✓ Done", color = Success, fontSize = d.textXs, fontWeight = FontWeight.SemiBold)
                        isActive -> {
                            // Progress bar
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
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(GlassMd).border(1.dp, GlassBorderSm, CircleShape)
                        .clickable(onClick = onCancel),
                    Alignment.Center,
                ) {
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
    Box(
        Modifier.size(20.dp).clip(CircleShape)
            .background(if (checked) AmberGlass else GlassMd)
            .border(1.5.dp, if (checked) AmberBorder else GlassBorderSm, CircleShape),
        Alignment.Center,
    ) {
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
        Modifier.fillMaxWidth().height(screenH * 0.42f)
            .clip(RoundedCornerShape(d.radiusLg)).background(Color.Black.copy(0.85f))
            .border(1.dp, Brand.copy(.5f), RoundedCornerShape(d.radiusLg)),
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
private fun PermissionCard(icon: ImageVector, title: String, subtitle: String, buttonLabel: String, onRequest: () -> Unit) {
    val d = LocalDimensions.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusLg)).background(BgCard)
            .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg)).padding(d.spaceXxl - d.spaceSm),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
            Icon(icon, null, tint = Brand, modifier = Modifier.size(44.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textLg, textAlign = TextAlign.Center)
            Text(subtitle, color = White60, fontSize = d.textMd, textAlign = TextAlign.Center)
            BrandButton(text = buttonLabel, onClick = onRequest, modifier = Modifier.fillMaxWidth(),
                icon = { Icon(icon, null, tint = Color(0xFF001428), modifier = Modifier.size(d.iconMd - 4.dp)) })
        }
    }
}

@Composable
private fun QrCard(
    qr: android.graphics.Bitmap?,
    sessionId: String,
    hotspotSsid: String?,
    hotspotPass: String?,
    onReset: () -> Unit,
) {
    val d = LocalDimensions.current
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
                Text("Ready to scan", color = White60, fontSize = d.textSm)
                Spacer(Modifier.weight(1f))
                Text("ID: $sessionId", color = White40, fontSize = d.textXs)
            }

            Box(Modifier.size(240.dp).clip(RoundedCornerShape(d.radiusMd)).background(Color.White).padding(8.dp)) {
                if (qr != null) Image(qr.asImageBitmap(), "QR", modifier = Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = d.spinnerMd) }
            }

            Text("Scan with the other device's Reelz app", color = White60, fontSize = d.textSm, textAlign = TextAlign.Center)

            if (!hotspotSsid.isNullOrEmpty()) {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(d.radiusSm))
                        .background(Color(0xFF0D1A0D))
                        .border(1.dp, Success.copy(.3f), RoundedCornerShape(d.radiusSm))
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

@Composable
private fun ErrorCard(
    msg: String,
    retryable: Boolean,
    onRetry: () -> Unit,
    kind: TransferUiState.ErrorKind = TransferUiState.ErrorKind.GENERIC,
) {
    val d = LocalDimensions.current
    val (icon, accentColor, headline) = when (kind) {
        TransferUiState.ErrorKind.PERMISSION -> Triple(IconWifi, Color(0xFFFF9F0A), "Permission required")
        TransferUiState.ErrorKind.CONNECTION -> Triple(IconWifi,  Error,            "Couldn't connect")
        TransferUiState.ErrorKind.TIMEOUT    -> Triple(IconWifi,  Color(0xFFFF9F0A),"Connection timed out")
        TransferUiState.ErrorKind.TRANSFER   -> Triple(IconFilm,  Error,            "Transfer failed")
        else                                 -> Triple(IconBeam,  Error,            "Something went wrong")
    }

    // BUG2 FIX: Detect connection errors that are likely caused by a stale Wi-Fi
    // association (the OS is still holding the previous hotspot network even after
    // disconnect). Show a clear actionable tip so the user knows what to do.
    val isStaleWifi = kind == TransferUiState.ErrorKind.CONNECTION &&
        (msg.contains("unreachable", ignoreCase = true) ||
         msg.contains("ENETUNREACH", ignoreCase = true) ||
         msg.contains("timed out", ignoreCase = true) ||
         msg.contains("lost", ignoreCase = true))

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

            // BUG2 FIX: Show Wi-Fi toggle tip for stale-connection errors
            if (isStaleWifi) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusSm))
                        .background(Color(0xFF1A1200))
                        .border(1.dp, Color(0xFFFFCC00).copy(0.35f), RoundedCornerShape(d.radiusSm))
                        .padding(d.spaceMd),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(d.spaceXs)) {
                        Text("📶  Wi-Fi still connected to old hotspot?", color = Color(0xFFFFCC00),
                            fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Turn off Wi-Fi on your phone, wait 2 seconds, then turn it back on. " +
                            "Then tap Try Again — the app will reconnect automatically.",
                            color = White60, fontSize = d.textXs, lineHeight = (d.textXs.value * 1.7f).sp,
                        )
                    }
                }
            }

            if (retryable) BrandButton("Try Again", onClick = onRetry, modifier = Modifier.fillMaxWidth())
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
        Box(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 48.dp)
                .offset(y = (scanY * 250).dp).height(2.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Brand2, Brand, Brand2, Color.Transparent)))
        )

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
                .background(Color.Black.copy(.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp))
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
