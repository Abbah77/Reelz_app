package com.axio.reelz.ui.screens.transfer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.axio.reelz.data.local.DownloadDao
import com.axio.reelz.data.local.TransferDao
import com.axio.reelz.data.model.DownloadItem
import com.axio.reelz.data.model.DownloadStatus
import com.axio.reelz.data.model.TransferRecord
import com.axio.reelz.transfer.TransferService
import com.axio.reelz.transfer.NearbyTransferManager
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.screens.downloads.formatSize
import com.axio.reelz.ui.screens.downloads.formatSpeed
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import javax.inject.Inject

// ── Local icons ───────────────────────────────────────────────────────────────

private val IconBack: ImageVector get() = ImageVector.Builder("TrBack", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(19f,12f); lineTo(5f,12f); moveTo(12f,19f); lineTo(5f,12f); lineTo(12f,5f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
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

private val IconCheck: ImageVector get() = ImageVector.Builder("TrOk", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f,2f); arcTo(10f,10f,0f,false,false,12f,22f)
        arcTo(10f,10f,0f,false,false,12f,2f); close()
        moveTo(8f,12f); lineTo(11f,15f); lineTo(16f,9f)
    }, stroke = SolidColor(Color(0xFF2DD36F)), strokeLineWidth = 1.7f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconCamera: ImageVector get() = ImageVector.Builder("TrCam", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(23f,19f); arcTo(2f,2f,0f,false,true,21f,21f); lineTo(3f,21f)
        arcTo(2f,2f,0f,false,true,1f,19f); lineTo(1f,8f)
        arcTo(2f,2f,0f,false,true,3f,6f); lineTo(7f,6f); lineTo(9f,3f); lineTo(15f,3f)
        lineTo(17f,6f); lineTo(21f,6f); arcTo(2f,2f,0f,false,true,23f,8f); close()
        moveTo(12f,10f); arcTo(4f,4f,0f,false,false,12f,18f)
        arcTo(4f,4f,0f,false,false,12f,10f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f,
       fill = SolidColor(Color.Transparent))
}.build()

private val IconWifi: ImageVector get() = ImageVector.Builder("TrWifi", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(5f, 12.55f); arcTo(11f, 11f, 0f, false, true, 19f, 12.55f)
        moveTo(1.42f, 9f); arcTo(16f, 16f, 0f, false, true, 22.58f, 9f)
        moveTo(8.53f, 16.11f); arcTo(6f, 6f, 0f, false, true, 15.47f, 16.11f)
        moveTo(12f, 20f); lineTo(12f, 20.01f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
       fill = SolidColor(Color.Transparent))
}.build()

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val dao: TransferDao,
    private val downloadDao: DownloadDao,
) : ViewModel() {

    val history: StateFlow<List<TransferRecord>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val progress = TransferService.progressFlow.asStateFlow()

    val completedDownloads: StateFlow<List<DownloadItem>> = downloadDao.getAll()
        .map { list -> list.filter { it.status == DownloadStatus.DONE.name && it.filePath.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── P2P state ─────────────────────────────────────────────────────────────
    // Backed by NearbyTransferManager (Google Play Services Nearby Connections)
    // instead of hand-rolled WifiP2pManager/LocalOnlyHotspot. See NearbyTransferManager
    // kdoc for why this is what lets us skip any manual Settings/Wi-Fi navigation.

    sealed class P2pUiState {
        object Idle        : P2pUiState()
        object Preparing   : P2pUiState()
        object Connecting  : P2pUiState()
        /** ssid/passphrase fields are gone — Nearby needs no Wi-Fi credentials at all.
         *  qr now encodes a short pairing code purely for a nice "scan to find me instantly"
         *  UX layered on top of Nearby's own radio-level discovery (BLE + Wi-Fi scan). */
        data class SenderReady(val pairingCode: String, val qr: Bitmap?) : P2pUiState()
        /** peerId is the Nearby endpointId (opaque per-session identifier — replaces the old peerIp). */
        data class Connected(val peerId: String, val isHost: Boolean) : P2pUiState()
        data class Error(val msg: String) : P2pUiState()
    }

    private val _p2p = MutableStateFlow<P2pUiState>(P2pUiState.Idle)
    val p2p: StateFlow<P2pUiState> = _p2p.asStateFlow()

    private var nearby: NearbyTransferManager? = null
    private var eventsJob: kotlinx.coroutines.Job? = null

    /** Human-readable name we advertise/connect as — shown on the other device. */
    private fun deviceDisplayName(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".take(32)

    // Sender/host: start advertising, show a QR/code so the other device can find us fast.
    fun initAsSender(ctx: Context) {
        if (_p2p.value is P2pUiState.SenderReady || _p2p.value is P2pUiState.Connected) return
        _p2p.value = P2pUiState.Preparing

        val manager = TransferService.ensureManager(ctx).also { nearby = it }
        val name = deviceDisplayName()
        val pairingCode = (100000..999999).random().toString()

        eventsJob = viewModelScope.launch {
            manager.startAdvertising(name).collect { event -> handleEvent(event, isHost = true) }
        }

        // QR still gives an instant, unambiguous "scan to connect" path for two phones
        // sitting next to each other, but it no longer carries Wi-Fi credentials —
        // it's just a hint the receiver's camera flow uses to skip the peer list and
        // auto-select the right endpoint once Nearby discovery finds it.
        val payload = "reelzp2p://$pairingCode"
        val qr = generateQr(payload, 700)
        _p2p.value = P2pUiState.SenderReady(pairingCode, qr)
    }

    // Receiver: start discovery, auto-connect to the first/matching endpoint found.
    fun connectFromQr(ctx: Context, raw: String) {
        // We don't strictly need the code's value (Nearby discovery finds any advertising
        // Reelz endpoint by SERVICE_ID alone) — parsing it just validates it's actually
        // our QR format before we spin up discovery.
        if (!raw.startsWith("reelzp2p://")) {
            _p2p.value = P2pUiState.Error("Not a Reelz QR code.")
            return
        }
        startDiscoveryAndConnect(ctx)
    }

    /** Also usable without a QR at all — e.g. a plain "Find nearby devices" button. */
    fun startDiscoveryAndConnect(ctx: Context) {
        _p2p.value = P2pUiState.Connecting
        val manager = TransferService.ensureManager(ctx).also { nearby = it }
        val name = deviceDisplayName()

        eventsJob = viewModelScope.launch {
            manager.startDiscovery().collect { event ->
                if (event is NearbyTransferManager.NearbyEvent.EndpointFound) {
                    // Connect to the first Reelz endpoint we see — matches the simple
                    // one-to-one pairing flow of the old QR/hotspot approach. (P2P_STAR
                    // strategy still allows the host to accept more than one of these.)
                    manager.requestConnection(name, event.endpointId)
                }
                handleEvent(event, isHost = false)
            }
        }
    }

    private fun handleEvent(event: NearbyTransferManager.NearbyEvent, isHost: Boolean) {
        when (event) {
            is NearbyTransferManager.NearbyEvent.Connected ->
                _p2p.value = P2pUiState.Connected(peerId = event.endpointId, isHost = isHost)
            is NearbyTransferManager.NearbyEvent.ConnectionFailed ->
                _p2p.value = P2pUiState.Error(event.reason)
            is NearbyTransferManager.NearbyEvent.Disconnected ->
                if (_p2p.value is P2pUiState.Connected) _p2p.value = P2pUiState.Idle
            else -> Unit
        }
    }

    fun sendFile(ctx: Context, filePath: String, peerId: String) {
        ctx.startForegroundService(
            Intent(ctx, TransferService::class.java)
                .setAction(TransferService.ACTION_SEND)
                .putExtra(TransferService.EXTRA_FILE, filePath)
                .putExtra(TransferService.EXTRA_ENDPOINT_ID, peerId),
        )
    }

    fun ensureServiceRunning(ctx: Context) {
        ctx.startForegroundService(Intent(ctx, TransferService::class.java))
    }

    fun reset() {
        eventsJob?.cancel()
        eventsJob = null
        nearby?.stopAll()
        nearby = null
        _p2p.value = P2pUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        eventsJob?.cancel()
        nearby?.stopAll()
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun TransferScreen(nav: NavController? = null, vm: TransferViewModel = hiltViewModel()) {
    val d = LocalDimensions.current
    val ctx       = LocalContext.current
    val history  by vm.history.collectAsState()
    val progress by vm.progress.collectAsState()
    val downloads by vm.completedDownloads.collectAsState()
    val p2p      by vm.p2p.collectAsState()

    // Tab: 0 = Send, 1 = Receive
    var tab by remember { mutableStateOf(0) }
    val screenH = LocalConfiguration.current.screenHeightDp.dp

    LaunchedEffect(Unit) { vm.ensureServiceRunning(ctx) }

    // Reset P2P when switching tabs so state doesn't bleed across
    LaunchedEffect(tab) { vm.reset() }

    // CRITICAL: WifiManager only allows ONE active LocalOnlyHotspot reservation per
    // process at a time. TransferViewModel is Hilt-scoped and survives navigation
    // away from this screen, so without this, leaving the screen while a hotspot
    // is active (or mid-Preparing) leaves the reservation open. Coming back and
    // tapping "Generate QR" again then fails with:
    // "Caller already has an active LocalOnlyHotspot request".
    // Closing it here, keyed on Unit with onDispose, guarantees cleanup whenever
    // this composable leaves the tree — back navigation, process the tab is on,
    // or otherwise — not just on explicit tab switches.
    DisposableEffect(Unit) {
        onDispose { vm.reset() }
    }

    // Prevent accidental disconnect: while a session is live, intercept the
    // system back button and ask for confirmation instead of silently tearing
    // down the hotspot / leaving the screen mid-transfer.
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    BackHandler(enabled = p2p is TransferViewModel.P2pUiState.Connected) {
        showDisconnectConfirm = true
    }
    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Disconnect?") },
            text = { Text("Leaving this screen will end the connection to the other device.") },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    vm.reset()
                    nav?.popBackStack()
                }) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("Stay") }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {

        // ── Header ────────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.heroPadding - d.spaceSm, vertical = d.spaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nav != null) {
                Box(
                    Modifier.size(d.buttonHeightSm - d.spaceMd).clip(CircleShape)
                        .background(GlassMd).border(1.dp, GlassBorderMd, CircleShape)
                        .clickable {
                            if (p2p is TransferViewModel.P2pUiState.Connected) {
                                showDisconnectConfirm = true
                            } else {
                                nav.popBackStack()
                            }
                        },
                    Alignment.Center,
                ) { Icon(IconBack, null, tint = Color.White, modifier = Modifier.size(d.iconMd - 2.dp)) }
                Spacer(Modifier.width(d.spaceMd - d.spaceXxs))
            }
            Column {
                Text("Transfer",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = Color.White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp))
                Text("Share downloads wirelessly", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Icon(IconSwap, null, tint = Brand.copy(.6f), modifier = Modifier.size(d.iconLg))
        }

        // ── Send / Receive toggle ─────────────────────────────────────────────
        // Hidden once connected — see ConnectedSessionTab below, which replaces
        // both tabs with one shared bidirectional screen.
        if (p2p !is TransferViewModel.P2pUiState.Connected) {
        Box(
            Modifier.padding(horizontal = d.screenHorizPad)
                .clip(RoundedCornerShape(d.radiusMd))
                .background(BgCard)
                .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
                .padding(d.spaceXs)
        ) {
            Row {
                listOf("Send" to IconQr, "Receive" to IconScan).forEachIndexed { i, (label, icon) ->
                    val sel = tab == i
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(d.spaceMd + 1.dp))
                            .background(
                                if (sel) Brush.horizontalGradient(listOf(BrandDeep, Brand.copy(.9f)))
                                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                            )
                            .clickable { tab = i }
                            .padding(vertical = d.spaceMd + 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 1.dp),
                        ) {
                            Icon(icon, null,
                                tint = if (sel) Color(0xFF1A0F00) else White60,
                                modifier = Modifier.size(d.iconMd - 4.dp))
                            Text(label,
                                color = if (sel) Color(0xFF1A0F00) else White60,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                fontSize = d.textMd)
                        }
                    }
                }
            }
        }
        }

        Spacer(Modifier.height(d.spaceXs))

        // ── Live progress bar ─────────────────────────────────────────────────
        progress?.let { p ->
            AnimatedVisibility(
                visible = !p.done && p.error == null,
                enter = slideInVertically() + fadeIn(),
                exit  = slideOutVertically() + fadeOut(),
            ) {
                val pct by animateFloatAsState(
                    (p.transferredBytes.toFloat() / p.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f),
                    tween(300), label = "tp",
                )
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad, vertical = d.sectionVertPad)
                        .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
                        .background(BgCard)
                        .border(1.dp, Brand.copy(.3f), RoundedCornerShape(d.radiusLg - d.spaceXxs))
                ) {
                    Box(Modifier.fillMaxWidth(pct).height(d.avatarLg + d.spaceXs)
                        .background(Brush.horizontalGradient(listOf(BrandDim, BrandDeep))))
                    Row(Modifier.padding(horizontal = d.screenHorizPad - d.spaceXxs, vertical = d.spaceMd - d.spaceXxs),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (p.direction == "SEND") IconUpload else IconDownloadCloud,
                            null, tint = Brand, modifier = Modifier.size(d.iconMd),
                        )
                        Spacer(Modifier.width(d.spaceMd))
                        Column(Modifier.weight(1f)) {
                            Text(p.fileName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = d.textMd, maxLines = 1)
                            Text("${if (p.direction == "SEND") "↑" else "↓"} ${p.peerName}", color = White60, fontSize = d.textXs)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatSize(p.transferredBytes), color = Brand, fontSize = d.textSm, fontWeight = FontWeight.Bold)
                            if (p.speedBps > 0) Text(formatSpeed(p.speedBps), color = White40, fontSize = d.textXs)
                        }
                    }
                }
                Spacer(Modifier.height(d.spaceXs))
            }
        }

        // ── Tab content ───────────────────────────────────────────────────────
        // Once connected, show ONE shared session screen regardless of which tab
        // started it — both sides can send files to each other from here, same as
        // Xender/ShareIt. This also removes the tab switcher so a user can't
        // accidentally tear down the live connection by tapping the other tab.
        //
        // `p2p` comes from `by collectAsState()`, so it's a delegated property —
        // Kotlin cannot smart-cast a delegated property from `is Connected` to
        // the `Connected` type directly. Capturing it in a local `val` first
        // gives the compiler a plain variable it CAN smart-cast.
        val p2pNow = p2p
        if (p2pNow is TransferViewModel.P2pUiState.Connected) {
            ConnectedSessionTab(p2p = p2pNow, downloads = downloads, history = history, ctx = ctx, vm = vm)
        } else if (tab == 0) {
            SendTab(p2p = p2pNow, downloads = downloads, ctx = ctx, vm = vm)
        } else {
            ReceiveTab(p2p = p2pNow, history = history, ctx = ctx, vm = vm)
        }
    }
}

// ── Connected session (bidirectional, both sides can send) ─────────────────────

@Composable
private fun ConnectedSessionTab(
    p2p: TransferViewModel.P2pUiState.Connected,
    downloads: List<DownloadItem>,
    history: List<TransferRecord>,
    ctx: Context,
    vm: TransferViewModel,
) {
    val d = LocalDimensions.current
    var selectedFile by remember { mutableStateOf<DownloadItem?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
    ) {
        Spacer(Modifier.height(d.spaceXs))

        ConnectedBadge(peerIp = p2p.peerId, isHost = p2p.isHost)

        Text(
            "Pick a file to send. Files the other device sends will appear automatically.",
            color = White60, fontSize = d.textSm,
        )

        SectionHeader("Send a file")
        FilePickerList(
            downloads    = downloads,
            selectedFile = selectedFile,
            onSelect     = { selectedFile = it },
        )

        BrandButton(
            text    = if (selectedFile == null) "Select a file above" else "Send \"${selectedFile!!.title}\"",
            enabled = selectedFile != null,
            onClick = {
                val file = selectedFile ?: return@BrandButton
                vm.sendFile(ctx, file.filePath, p2p.peerId)
                selectedFile = null
            },
            modifier = Modifier.fillMaxWidth(),
            icon    = { Icon(IconUpload, null, tint = Color(0xFF1A0F00), modifier = Modifier.size(d.iconMd - 4.dp)) },
        )

        // Transfer history — shows both directions live, so both sides can see
        // sends and receives from this same session without switching screens.
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(d.spaceSm + d.spaceXxs))
            SectionHeader("This session")
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
                modifier = Modifier.heightIn(max = d.spaceXxl * 12.5f),
            ) {
                items(history.take(30), key = { it.id }) { TransferHistoryRow(it) }
            }
        }

        GhostButton("Disconnect", onClick = { showDisconnectConfirm = true }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(d.spaceXxl * 3.1f))
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("Disconnect?") },
            text = { Text("This will end the connection to the other device.") },
            confirmButton = {
                TextButton(onClick = { showDisconnectConfirm = false; vm.reset() }) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("Stay") }
            },
        )
    }
}

// ── Send tab ──────────────────────────────────────────────────────────────────

@Composable
private fun SendTab(
    p2p: TransferViewModel.P2pUiState,
    downloads: List<DownloadItem>,
    ctx: Context,
    vm: TransferViewModel,
) {
    val d = LocalDimensions.current
    var selectedFile by remember { mutableStateOf<DownloadItem?>(null) }

    // Nearby Connections advertising needs Bluetooth (used for discovery + the initial
    // handshake before Play Services silently upgrades the link to Wi-Fi Direct/Aware)
    // plus NEARBY_WIFI_DEVICES on API 33+ for the Wi-Fi medium itself. These are separate
    // runtime permissions from each other and from location — without requesting all of
    // them explicitly here, advertising can silently fail with no way to grant them
    // except via App Settings, which is exactly the bug this whole rewrite fixes.
    val p2pPermissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun allP2pPermsGranted(): Boolean = p2pPermissions.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    var hasP2pPerm by remember { mutableStateOf(allP2pPermsGranted()) }

    val p2pPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        hasP2pPerm = granted
        if (granted) vm.initAsSender(ctx)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
    ) {
        Spacer(Modifier.height(d.spaceXs))

        when (p2p) {

            // ── Idle: show "Start" button ─────────────────────────────────────
            is TransferViewModel.P2pUiState.Idle -> {
                if (!hasP2pPerm) {
                    // Permission card — same style as camera permission
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(d.radiusLg))
                            .background(BgCard)
                            .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg))
                            .padding(d.spaceXl),
                    ) {
                        Column(Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs)) {
                            Icon(IconWifi, null, tint = Brand, modifier = Modifier.size(d.buttonHeightSm - d.spaceMd))
                            Text("Nearby access needed", color = Color.White,
                                fontWeight = FontWeight.Bold, fontSize = d.textLg)
                            Text("Bluetooth & Wi-Fi permissions let nearby devices find you instantly.",
                                color = White60, fontSize = d.textMd, textAlign = TextAlign.Center)
                            BrandButton(
                                text = "Allow & Generate QR",
                                onClick = { p2pPermLauncher.launch(p2pPermissions) },
                                modifier = Modifier.fillMaxWidth(),
                                icon = { Icon(IconQr, null, tint = Color(0xFF1A0F00), modifier = Modifier.size(d.iconMd - 4.dp)) },
                            )
                        }
                    }
                } else {
                    BrandButton(
                        text    = "Generate QR",
                        onClick = { vm.initAsSender(ctx) },
                        modifier = Modifier.fillMaxWidth(),
                        icon    = { Icon(IconQr, null, tint = Color(0xFF1A0F00), modifier = Modifier.size(d.iconMd - 4.dp)) },
                    )
                }
            }

            // ── Preparing: spinner ────────────────────────────────────────────
            is TransferViewModel.P2pUiState.Preparing -> {
                Box(Modifier.fillMaxWidth().padding(vertical = d.spaceXxl), Alignment.Center) {
                    CinematicSpinner(size = d.spinnerMd + d.spaceXl)
                }
            }

            // ── QR ready: show it + file picker ──────────────────────────────
            is TransferViewModel.P2pUiState.SenderReady -> {
                // QR card
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(d.radiusLg))
                        .background(BgCard)
                        .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg))
                        .padding(d.spaceXl - d.spaceXs),
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs)) {
                        // Pulsing dot to show it's live
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                            val inf = rememberInfiniteTransition(label = "dot")
                            val alpha by inf.animateFloat(0.3f, 1f,
                                infiniteRepeatable(tween(800), RepeatMode.Reverse), "da")
                            Box(Modifier.size(d.spaceSm + 1.dp).background(Success.copy(alpha), CircleShape))
                            Text("Ready to connect", color = White60, fontSize = d.textSm)
                        }

                        // QR
                        Box(
                            Modifier.size(d.cardPosterWidth + d.spaceXxl * 2.5f)
                                .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
                                .background(Color.White)
                                .padding(d.spaceMd - d.spaceXxs)
                        ) {
                            if (p2p.qr != null) {
                                Image(p2p.qr.asImageBitmap(), "QR", modifier = Modifier.fillMaxSize())
                            } else {
                                Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = d.spinnerMd + d.spaceXs) }
                            }
                        }

                        GhostButton("Reset", onClick = { vm.reset() },
                            modifier = Modifier.fillMaxWidth())
                    }
                }

                // File picker
                FilePickerList(
                    downloads    = downloads,
                    selectedFile = selectedFile,
                    onSelect     = { selectedFile = it },
                )

                BrandButton(
                    text    = if (selectedFile == null) "Select a file above" else "Send \"${selectedFile!!.title}\"",
                    enabled = false, // waiting — receiver not yet connected
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    icon    = { Icon(IconUpload, null, tint = Color(0xFF1A0F00), modifier = Modifier.size(d.iconMd - 4.dp)) },
                )
            }

            // ── Error ─────────────────────────────────────────────────────────
            is TransferViewModel.P2pUiState.Error -> {
                ErrorCard(msg = p2p.msg, onRetry = { vm.reset() })
            }

            else -> {}
        }

        Spacer(Modifier.height(d.spaceXxl * 3.1f))
    }
}

// ── Receive tab ───────────────────────────────────────────────────────────────

@Composable
private fun ReceiveTab(
    p2p: TransferViewModel.P2pUiState,
    history: List<TransferRecord>,
    ctx: Context,
    vm: TransferViewModel,
) {
    val d = LocalDimensions.current
    var hasCam by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    var selectedFile by remember { mutableStateOf<DownloadItem?>(null) }

    // Nearby Connections needs Bluetooth (for discovery + the initial handshake before
    // it upgrades to Wi-Fi) plus NEARBY_WIFI_DEVICES on API 33+ for the Wi-Fi medium.
    // Without asking for these here, discovery can silently find nothing with no way
    // to grant them except through App Settings.
    val joinPermissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun hasJoinPerms(): Boolean = joinPermissions.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
    // Holds the QR payload we tried to connect to while waiting on the permission dialog.
    var pendingQrPayload by remember { mutableStateOf<String?>(null) }
    val joinPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        val payload = pendingQrPayload
        pendingQrPayload = null
        if (granted && payload != null) {
            vm.connectFromQr(ctx, payload)
        }
    }
    fun onQrScanned(payload: String) {
        if (hasJoinPerms()) {
            vm.connectFromQr(ctx, payload)
        } else {
            pendingQrPayload = payload
            joinPermLauncher.launch(joinPermissions)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
    ) {
        Spacer(Modifier.height(d.spaceXs))

        when (p2p) {

            // ── Idle / scanning ───────────────────────────────────────────────
            is TransferViewModel.P2pUiState.Idle -> {
                if (!hasCam) {
                    CameraPermCard { permLauncher.launch(Manifest.permission.CAMERA) }
                } else {
                    ScannerCard(onScanned = { onQrScanned(it) })
                }

                // Nearby Connections finds nearby senders over Bluetooth/Wi-Fi radio
                // scanning regardless of QR — this is a fallback for when camera access
                // isn't available, or the two devices are just already side by side.
                Spacer(Modifier.height(d.spaceXs))
                GhostButton(
                    "Find nearby devices instead",
                    onClick = {
                        if (hasJoinPerms()) {
                            vm.startDiscoveryAndConnect(ctx)
                        } else {
                            pendingQrPayload = "reelzp2p://direct"
                            joinPermLauncher.launch(joinPermissions)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── Connecting ────────────────────────────────────────────────────
            is TransferViewModel.P2pUiState.Connecting -> {
                Box(Modifier.fillMaxWidth().padding(vertical = d.spaceXxl), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs)) {
                        CinematicSpinner(size = d.spinnerMd + d.spaceXl)
                        Text("Connecting…", color = White60, fontSize = d.textMd)
                    }
                }
            }

            // ── Error ─────────────────────────────────────────────────────────
            is TransferViewModel.P2pUiState.Error -> {
                ErrorCard(msg = p2p.msg, onRetry = { vm.reset() })
                // Show scanner again so they can retry immediately
                if (hasCam) ScannerCard(onScanned = { onQrScanned(it) })
            }

            else -> {}
        }

        // Transfer history
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(d.spaceSm + d.spaceXxs))
            SectionHeader("History")
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
                modifier = Modifier.heightIn(max = d.spaceXxl * 12.5f),
            ) {
                items(history.take(30), key = { it.id }) { TransferHistoryRow(it) }
            }
        }

        Spacer(Modifier.height(d.spaceXxl * 3.1f))
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun ScannerCard(onScanned: (String) -> Unit) {
    val d = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    Box(
        Modifier.fillMaxWidth().height(screenH * 0.40f)
            .clip(RoundedCornerShape(d.radiusLg))
            .background(Color.Black)
            .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg))
    ) {
        CameraScanner(onScanned = onScanned)

        // Animated scan line
        val inf = rememberInfiniteTransition(label = "sl")
        val scanY by inf.animateFloat(0f, 1f,
            infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), "sy")
        Box(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .padding(horizontal = d.spaceXxl - d.spaceXs)
                .offset(y = (scanY * 270).dp)
                .height(2.dp)
                .background(Brush.horizontalGradient(listOf(
                    Color.Transparent, Brand2, Brand, Brand2, Color.Transparent)))
        )

        // Corner brackets
        val bs = d.spaceXxl - d.spaceXs; val bw = d.spaceXxs + 1.dp
        listOf(Alignment.TopStart, Alignment.TopEnd,
               Alignment.BottomStart, Alignment.BottomEnd).forEach { a ->
            Box(Modifier.padding(d.heroPadding).size(bs).align(a)) {
                val top   = a == Alignment.TopStart   || a == Alignment.TopEnd
                val start = a == Alignment.TopStart   || a == Alignment.BottomStart
                Box(Modifier.align(if (top) Alignment.TopStart else Alignment.BottomStart)
                    .width(bs).height(bw).background(Brand))
                Box(Modifier.align(if (start) Alignment.TopStart else Alignment.TopEnd)
                    .width(bw).height(bs).background(Brand))
            }
        }
    }
}

@Composable
private fun CameraPermCard(onRequest: () -> Unit) {
    val d = LocalDimensions.current
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg))
            .background(BgCard)
            .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg))
            .padding(d.spaceXxl - d.spaceXs),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs)) {
            Icon(IconCamera, null, tint = Brand, modifier = Modifier.size(d.buttonHeightSm - d.spaceMd))
            Text("Camera access needed", color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textLg)
            BrandButton("Allow Camera", onClick = onRequest, modifier = Modifier.fillMaxWidth(),
                icon = { Icon(IconCamera, null, tint = Color(0xFF1A0F00), modifier = Modifier.size(d.iconSm + 3.dp)) })
        }
    }
}

@Composable
private fun ConnectedBadge(peerIp: String, isHost: Boolean) {
    val d = LocalDimensions.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(Brush.linearGradient(listOf(Color(0xFF0D200D), Success.copy(.5f))))
            .border(1.dp, Success.copy(.4f), RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .padding(d.screenHorizPad - d.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
    ) {
        Box(
            Modifier.size(d.buttonHeightSm - d.spaceXxs).clip(CircleShape)
                .background(Success.copy(.15f)).border(1.dp, Success.copy(.4f), CircleShape),
            Alignment.Center,
        ) { Icon(IconCheck, null, tint = Success, modifier = Modifier.size(d.iconMd)) }
        Column {
            Text("Connected!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textLg)
            Text(peerIp, color = White60, fontSize = d.textSm)
        }
    }
}

@Composable
private fun ErrorCard(msg: String, onRetry: () -> Unit) {
    val d = LocalDimensions.current
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(BgCard)
            .border(1.dp, Error.copy(.4f), RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .padding(d.heroPadding),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
            Text("✕", color = Error, fontSize = d.textXxl, fontWeight = FontWeight.Bold)
            Text(msg, color = White60, fontSize = d.textMd, textAlign = TextAlign.Center)
            BrandButton("Try Again", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FilePickerList(
    downloads: List<DownloadItem>,
    selectedFile: DownloadItem?,
    onSelect: (DownloadItem) -> Unit,
) {
    val d = LocalDimensions.current
    if (downloads.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusMd))
                .background(BgCard).border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
                .padding(d.spaceXl - d.spaceXs),
            Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                Icon(IconDownloadCloud, null, tint = White40, modifier = Modifier.size(d.iconLg))
                Text("No completed downloads", color = White60, fontSize = d.textMd)
            }
        }
        return
    }

    downloads.forEach { dl ->
        val sel = selectedFile?.id == dl.id
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(d.radiusMd))
                .background(if (sel) AmberGlass else BgCard)
                .border(1.dp, if (sel) AmberBorder else GlassBorderMd, RoundedCornerShape(d.radiusMd))
                .clickable { onSelect(dl) }
                .padding(d.screenHorizPad - d.spaceXxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
        ) {
            Box(
                Modifier.size(d.avatarSm).clip(CircleShape)
                    .background(if (sel) AmberGlass else GlassMd)
                    .border(1.dp, if (sel) AmberBorder else GlassBorderMd, CircleShape),
                Alignment.Center,
            ) {
                Icon(if (sel) IconCheck else IconMovieSlate, null,
                    tint = if (sel) Brand else White60, modifier = Modifier.size(d.iconMd - 4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(dl.title, color = Color.White, fontSize = d.textMd,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${dl.quality} · ${formatSize(dl.sizeBytes)}", color = White60, fontSize = d.textXs)
            }
        }
    }
}

// ── Camera ────────────────────────────────────────────────────────────────────

@Composable
fun CameraScanner(onScanned: (String) -> Unit) {
    val ctx            = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor       = remember { Executors.newSingleThreadExecutor() }
    val d = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    var hasScanned     by remember { mutableStateOf(false) }

    AndroidView(
        factory = { context ->
            val pv = PreviewView(context)
            ProcessCameraProvider.getInstance(context).addListener({
                val provider = ProcessCameraProvider.getInstance(context).get()
                val preview  = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    if (!hasScanned) {
                        val result = decodeQr(proxy)
                        if (result != null) { hasScanned = true; onScanned(result) }
                    }
                    proxy.close()
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(context))
            pv
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun decodeQr(proxy: ImageProxy): String? = try {
    val buf   = proxy.planes[0].buffer
    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
    val src   = PlanarYUVLuminanceSource(bytes, proxy.width, proxy.height,
                    0, 0, proxy.width, proxy.height, false)
    MultiFormatReader()
        .also { it.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
        .decode(BinaryBitmap(HybridBinarizer(src))).text
} catch (_: Exception) { null }

fun generateQr(content: String, sizePx: Int): Bitmap? = try {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val mat = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) for (y in 0 until sizePx)
        bmp.setPixel(x, y, if (mat[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    bmp
} catch (_: Exception) { null }

@Composable
fun TransferHistoryRow(record: TransferRecord) {
    val d = LocalDimensions.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .padding(d.screenHorizPad - d.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
    ) {
        Box(
            Modifier.size(d.buttonHeightSm - d.spaceMd).clip(CircleShape)
                .background(AmberGlass).border(1.dp, AmberBorder, CircleShape),
            Alignment.Center,
        ) {
            Icon(if (record.direction == "SEND") IconUpload else IconDownloadCloud,
                null, tint = Brand, modifier = Modifier.size(d.iconMd - 4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(record.fileName, color = Color.White, fontSize = d.textMd,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${if (record.direction == "SEND") "↑" else "↓"} ${record.peerName}",
                color = White60, fontSize = d.textXs)
        }
        Text(formatSize(record.sizeBytes), color = White40, fontSize = d.textXs)
    }
}
