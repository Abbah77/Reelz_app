package com.axio.reelz.ui.screens.transfer

// ─────────────────────────────────────────────────────────────────────────────
//  TransferScreen — Reelz Beam
//
//  Architecture:
//   TransferScreen (Compose UI)
//     └── TransferViewModel (Hilt)
//           └── TransferManager (Singleton)
//                 └── P2pEngine (Singleton)
//                       ├── Tier 1: Wi-Fi Direct (WifiP2pManager)
//                       ├── Tier 2: Local Wi-Fi (NSD + TCP)
//                       └── Tier 3: Hotspot (WifiManager.LocalOnlyHotspot)
//
//  QR flow:
//   Sender: "Allow & Share" → permissions → QR generated instantly
//   Receiver: "Scan QR" → camera opens → scan → auto-negotiate → connected
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.axio.reelz.core.database.DownloadDao
import com.axio.reelz.core.database.DownloadRow
import com.axio.reelz.core.database.TransferDao
import com.axio.reelz.core.database.TransferRecord
import com.axio.reelz.data.model.DownloadItem
import com.axio.reelz.data.model.DownloadStatus
import com.axio.reelz.transfer.*
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.screens.downloads.formatSize
import com.axio.reelz.ui.screens.downloads.formatSpeed
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

// ─── Local icon vectors ────────────────────────────────────────────────────────

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

// ─── Permissions matrix ────────────────────────────────────────────────────────

private fun buildTransferPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    else -> arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
}

private fun Context.allTransferPermsGranted(): Boolean {
    // Only the Wi-Fi / Nearby-devices / Location permissions gate the feature.
    // Bluetooth perms are optional discovery enhancements; don't block on them.
    val criticalPerms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    return criticalPerms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

// ─── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val transferManager: TransferManager,
    private val downloadDao: DownloadDao,
    private val transferDao: TransferDao,
) : ViewModel() {

    // ── History ───────────────────────────────────────────────────────────────
    val history: StateFlow<List<TransferRecord>> = transferDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Engine state passthrough ───────────────────────────────────────────────
    val uiState: StateFlow<TransferUiState> = transferManager.uiState

    // ── Completed downloads available to send ─────────────────────────────────
    val completedDownloads: StateFlow<List<DownloadItem>> = downloadDao.observeAll()
        .map { list ->
            list.filter { it.status == DownloadStatus.DONE.name && it.filePath.isNotBlank() }
                .map { row -> row.toDownloadItem() }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Commands ──────────────────────────────────────────────────────────────

    fun startAsSender() = transferManager.startAsSender()

    fun connectFromQr(rawQr: String) = transferManager.connectFromQr(rawQr)

    fun sendFile(ctx: Context, item: DownloadItem) {
        val peerName = (uiState.value as? TransferUiState.Connected)?.peerName ?: "Peer"
        transferManager.sendFile(
            filePath = item.filePath,
            fileName = item.title.ifBlank { item.filePath.substringAfterLast('/') },
            peerName = peerName,
        )
    }

    fun startReceiving(ctx: Context) {
        val peerName = (uiState.value as? TransferUiState.Connected)?.peerName ?: "Peer"
        val saveDir  = File(
            ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ReelzBeam"
        )
        transferManager.startReceiving(saveDir, peerName)
    }

    fun disconnect() = transferManager.disconnect()

    fun reset() = transferManager.disconnect()

    override fun onCleared() {
        super.onCleared()
        transferManager.release()
    }
}

private fun DownloadRow.toDownloadItem() = DownloadItem(
    id = id, mediaId = mediaId, title = title, posterUrl = posterUrl,
    mediaType = mediaType, season = season, episode = episode, episodeName = episodeName,
    quality = quality, filePath = filePath, sizeBytes = sizeBytes, downloadedBytes = downloadedBytes,
    status = DownloadStatus.DONE, streamUrl = streamUrl, createdAt = createdAt, completedAt = completedAt,
)

// ─── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun TransferScreen(
    nav: NavController? = null,
    vm: TransferViewModel = hiltViewModel(),
) {
    val d         = LocalDimensions.current
    val ctx       = LocalContext.current
    val uiState   by vm.uiState.collectAsState()
    val history   by vm.history.collectAsState()
    val downloads by vm.completedDownloads.collectAsState()

    // Tab: 0 = Send, 1 = Receive
    var tab by remember { mutableStateOf(0) }

    // Reset when leaving screen
    DisposableEffect(Unit) { onDispose { vm.reset() } }

    // Reset on tab switch (unless connected — then tab is hidden)
    LaunchedEffect(tab) {
        if (uiState !is TransferUiState.Connected && uiState !is TransferUiState.Transferring) {
            vm.reset()
        }
    }

    // Back-press protection while connected
    var showDisconnectDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = uiState is TransferUiState.Connected || uiState is TransferUiState.Transferring) {
        showDisconnectDialog = true
    }

    if (showDisconnectDialog) {
        DisconnectDialog(
            onConfirm = { showDisconnectDialog = false; vm.disconnect(); nav?.popBackStack() },
            onDismiss = { showDisconnectDialog = false },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding(),
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        BeamHeader(
            nav = nav,
            isConnected = uiState is TransferUiState.Connected || uiState is TransferUiState.Transferring,
            onBackRequest = { if (uiState is TransferUiState.Connected) showDisconnectDialog = true else nav?.popBackStack() },
        )

        // ── Tab bar (hidden when connected) ───────────────────────────────────
        val connected = uiState is TransferUiState.Connected || uiState is TransferUiState.Transferring
        AnimatedVisibility(!connected, enter = fadeIn(), exit = fadeOut()) {
            BeamTabBar(selected = tab, onSelect = { tab = it })
        }

        Spacer(Modifier.height(d.spaceXs))

        // ── Transfer progress bar (always visible when transferring) ──────────
        TransferProgressBanner(uiState = uiState)

        // ── Content ───────────────────────────────────────────────────────────
        val state = uiState
        when {
            state is TransferUiState.Connected || state is TransferUiState.Transferring -> {
                ConnectedSession(
                    uiState   = state,
                    downloads = downloads,
                    history   = history,
                    ctx       = ctx,
                    vm        = vm,
                )
            }
            tab == 0 -> SendTab(uiState = state, ctx = ctx, vm = vm)
            else     -> ReceiveTab(uiState = state, history = history, ctx = ctx, vm = vm)
        }
    }
}

// ─── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun BeamHeader(
    nav: NavController?,
    isConnected: Boolean,
    onBackRequest: () -> Unit,
) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.heroPadding - d.spaceSm, vertical = d.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nav != null) {
            Box(
                Modifier
                    .size(d.buttonHeightSm - d.spaceMd)
                    .clip(CircleShape)
                    .background(GlassMd)
                    .border(1.dp, GlassBorderMd, CircleShape)
                    .clickable(onClick = onBackRequest),
                Alignment.Center,
            ) { Icon(IconBack, null, tint = Color.White, modifier = Modifier.size(d.iconMd - 2.dp)) }
            Spacer(Modifier.width(d.spaceMd - d.spaceXxs))
        }
        Column {
            Text(
                "Reelz Beam",
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = Color.White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp,
                ),
            )
            Text(
                if (isConnected) "Session active" else "Instant wireless transfer",
                color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.weight(1f))
        // Animated beam icon
        val inf = rememberInfiniteTransition(label = "hdr")
        val pulse by inf.animateFloat(0.4f, 1f,
            infiniteRepeatable(tween(1200), RepeatMode.Reverse), "hp")
        Icon(
            IconBeam, null,
            tint = Brand.copy(if (isConnected) 1f else pulse),
            modifier = Modifier.size(d.iconLg),
        )
    }
}

// ─── Tab bar ───────────────────────────────────────────────────────────────────

@Composable
private fun BeamTabBar(selected: Int, onSelect: (Int) -> Unit) {
    val d = LocalDimensions.current
    Box(
        Modifier
            .padding(horizontal = d.screenHorizPad)
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .padding(d.spaceXs),
    ) {
        Row {
            listOf("Share" to IconQr, "Receive" to IconScan).forEachIndexed { i, (label, icon) ->
                val sel = selected == i
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(d.spaceMd))
                        .background(
                            if (sel) Brush.horizontalGradient(listOf(BrandDeep, Brand.copy(.9f)))
                            else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        )
                        .clickable { onSelect(i) }
                        .padding(vertical = d.spaceMd),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                    ) {
                        Icon(icon, null,
                            tint = if (sel) Color.White else White60,
                            modifier = Modifier.size(d.iconMd - 4.dp))
                        Text(label,
                            color = if (sel) Color.White else White60,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            fontSize = d.textMd)
                    }
                }
            }
        }
    }
}

// ─── Transfer progress banner ──────────────────────────────────────────────────

@Composable
private fun TransferProgressBanner(uiState: TransferUiState) {
    val d = LocalDimensions.current
    AnimatedVisibility(
        uiState is TransferUiState.Transferring,
        enter = slideInVertically { -it } + fadeIn(),
        exit  = slideOutVertically { -it } + fadeOut(),
    ) {
        val t = uiState as? TransferUiState.Transferring ?: return@AnimatedVisibility
        val pct by animateFloatAsState(
            (t.transferredBytes.toFloat() / t.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f),
            tween(300), label = "tp",
        )
        val tierLabel = when (t.tier) {
            TransportTier.WIFI_DIRECT -> "Wi-Fi Direct"
            TransportTier.LOCAL_WIFI  -> "Local Wi-Fi"
            TransportTier.HOTSPOT     -> "Hotspot"
            null                      -> ""
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = d.screenHorizPad, vertical = d.spaceSm)
                .clip(RoundedCornerShape(d.radiusLg))
                .background(BgCard)
                .border(1.dp, Brand.copy(.35f), RoundedCornerShape(d.radiusLg))
        ) {
            // Progress fill
            Box(
                Modifier
                    .fillMaxWidth(pct)
                    .height(60.dp)
                    .background(Brush.horizontalGradient(listOf(BrandDim, BrandDeep.copy(.8f))))
            )
            Row(
                Modifier.padding(horizontal = d.spaceMd, vertical = d.spaceMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (t.direction == "SEND") IconUpload else IconDownloadCloud,
                    null, tint = Brand, modifier = Modifier.size(d.iconMd),
                )
                Spacer(Modifier.width(d.spaceMd))
                Column(Modifier.weight(1f)) {
                    Text(t.fileName, color = Color.White, fontWeight = FontWeight.SemiBold,
                        fontSize = d.textMd, maxLines = 1)
                    Text(
                        "${if (t.direction == "SEND") "↑" else "↓"} ${t.peerName}" +
                        if (tierLabel.isNotEmpty()) " · $tierLabel" else "",
                        color = White60, fontSize = d.textXs,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatSize(t.transferredBytes), color = Brand,
                        fontSize = d.textSm, fontWeight = FontWeight.Bold)
                    if (t.speedBps > 0) Text(formatSpeed(t.speedBps), color = White40, fontSize = d.textXs)
                }
            }
        }
    }
}

// ─── Send tab ──────────────────────────────────────────────────────────────────

@Composable
private fun SendTab(
    uiState: TransferUiState,
    ctx: Context,
    vm: TransferViewModel,
) {
    val d = LocalDimensions.current
    val transferPerms = buildTransferPermissions()
    var hasPerms by remember { mutableStateOf(ctx.allTransferPermsGranted()) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Mirror the same critical-only logic used in ReceiveTab.
        // Bluetooth perms are optional; Wi-Fi / location perms are required.
        val criticalGranted = results.entries
            .filter { (perm, _) ->
                perm == Manifest.permission.ACCESS_FINE_LOCATION ||
                perm == Manifest.permission.ACCESS_COARSE_LOCATION ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    perm == Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            .all { (_, granted) -> granted }
        hasPerms = criticalGranted
        if (hasPerms) vm.startAsSender()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Spacer(Modifier.height(d.spaceXs))

        when (uiState) {

            // ── Idle ─────────────────────────────────────────────────────────
            is TransferUiState.Idle -> {
                if (!hasPerms) {
                    PermissionCard(
                        icon = IconWifi,
                        title = "Wireless access needed",
                        subtitle = "Grant Wi-Fi & Bluetooth so nearby devices can discover you instantly.",
                        buttonLabel = "Allow & Share",
                        buttonIcon = { Icon(IconQr, null, tint = Color(0xFF001428), modifier = Modifier.size(d.iconMd - 4.dp)) },
                        onRequest = { permLauncher.launch(transferPerms) },
                    )
                } else {
                    BeamInitCard(
                        title = "Ready to share",
                        subtitle = "Tap to generate your QR code. The receiver scans it to connect.",
                        buttonLabel = "Generate QR",
                        icon = IconQr,
                        onClick = { vm.startAsSender() },
                    )
                }
            }

            // ── Preparing ────────────────────────────────────────────────────
            is TransferUiState.Preparing -> {
                Box(Modifier.fillMaxWidth().padding(vertical = d.spaceXxl), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                        CinematicSpinner(size = d.spinnerMd + d.spaceXl)
                        Text("Preparing…", color = White60, fontSize = d.textMd)
                    }
                }
            }

            // ── QR ready ─────────────────────────────────────────────────────
            is TransferUiState.QrReady -> {
                QrCard(
                    qr        = uiState.qr,
                    sessionId = uiState.sessionId,
                    onReset   = { vm.reset() },
                )
                WaitingPeerCard()
            }

            // ── Error ─────────────────────────────────────────────────────────
            is TransferUiState.Error -> {
                ErrorCard(msg = uiState.msg, retryable = uiState.retryable, onRetry = { vm.reset() })
            }

            else -> {}
        }

        Spacer(Modifier.height(d.spaceXxl * 3f))
    }
}

// ─── Receive tab ───────────────────────────────────────────────────────────────

@Composable
private fun ReceiveTab(
    uiState: TransferUiState,
    history: List<TransferRecord>,
    ctx: Context,
    vm: TransferViewModel,
) {
    val d = LocalDimensions.current
    var hasCam by remember { mutableStateOf(ctx.hasCameraPermission()) }
    var hasTransferPerms by remember { mutableStateOf(ctx.allTransferPermsGranted()) }
    val transferPerms = buildTransferPermissions()
    var pendingQr by remember { mutableStateOf<String?>(null) }

    val camLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasCam = it }

    val transferPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Only the Wi-Fi / Nearby-devices permissions are strictly required for
        // the connection to work.  Bluetooth perms enhance discovery but are
        // not fatal if denied — don't block the whole flow on them.
        val criticalGranted = results.entries
            .filter { (perm, _) ->
                perm == Manifest.permission.ACCESS_FINE_LOCATION ||
                perm == Manifest.permission.ACCESS_COARSE_LOCATION ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    perm == Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            .all { (_, granted) -> granted }
        hasTransferPerms = criticalGranted
        val qr = pendingQr
        pendingQr = null
        if (hasTransferPerms && qr != null) vm.connectFromQr(qr)
    }

    fun onQrScanned(raw: String) {
        if (!raw.startsWith("reelzbeam://")) return // ignore non-Reelz QRs silently
        if (hasTransferPerms) {
            vm.connectFromQr(raw)
        } else {
            pendingQr = raw
            transferPermLauncher.launch(transferPerms)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Spacer(Modifier.height(d.spaceXs))

        when (uiState) {

            // ── Idle — show scanner ───────────────────────────────────────────
            is TransferUiState.Idle -> {
                if (!hasCam) {
                    PermissionCard(
                        icon = IconCamera,
                        title = "Camera access needed",
                        subtitle = "Point your camera at the sender's QR code to connect instantly.",
                        buttonLabel = "Allow Camera",
                        buttonIcon = { Icon(IconCamera, null, tint = Color(0xFF001428), modifier = Modifier.size(d.iconMd - 4.dp)) },
                        onRequest = { camLauncher.launch(Manifest.permission.CAMERA) },
                    )
                } else {
                    ScannerCard(onScanned = ::onQrScanned)
                }
            }

            // ── Connecting ────────────────────────────────────────────────────
            is TransferUiState.Connecting -> {
                NegotiatingCard()
            }

            // ── Error ─────────────────────────────────────────────────────────
            is TransferUiState.Error -> {
                ErrorCard(msg = uiState.msg, retryable = uiState.retryable, onRetry = { vm.reset() })
                if (hasCam) {
                    Spacer(Modifier.height(d.spaceSm))
                    ScannerCard(onScanned = ::onQrScanned)
                }
            }

            else -> {}
        }

        // History
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(d.spaceSm))
            SectionHeader("History")
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(d.spaceSm),
                modifier = Modifier.heightIn(max = 400.dp),
            ) {
                items(history.take(30), key = { it.id }) { TransferHistoryRow(it) }
            }
        }

        Spacer(Modifier.height(d.spaceXxl * 3f))
    }
}

// ─── Connected session (bidirectional) ─────────────────────────────────────────

@Composable
private fun ConnectedSession(
    uiState: TransferUiState,
    downloads: List<DownloadItem>,
    history: List<TransferRecord>,
    ctx: Context,
    vm: TransferViewModel,
) {
    val d = LocalDimensions.current
    var selectedFile by remember { mutableStateOf<DownloadItem?>(null) }
    var showDisconnect by remember { mutableStateOf(false) }

    val connected = uiState as? TransferUiState.Connected
    val peerName  = connected?.peerName ?: (uiState as? TransferUiState.Transferring)?.peerName ?: "Peer"
    val tier      = connected?.tier ?: (uiState as? TransferUiState.Transferring)?.tier

    // Start receiving automatically when connected as non-host
    LaunchedEffect(connected) {
        if (connected != null && !connected.isHost) {
            vm.startReceiving(ctx)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.screenHorizPad)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Spacer(Modifier.height(d.spaceXs))

        // Connection status badge
        ConnectedBadge(peerName = peerName, tier = tier, isHost = connected?.isHost == true)

        Text(
            "Both devices can send files. Select a movie to share.",
            color = White60, fontSize = d.textSm,
        )

        SectionHeader("Your downloads")

        if (downloads.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(d.radiusMd))
                    .background(BgCard)
                    .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
                    .padding(d.spaceXl),
                Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                    Icon(IconDownloadCloud, null, tint = White40, modifier = Modifier.size(d.iconLg))
                    Text("No completed downloads to send", color = White60, fontSize = d.textMd)
                }
            }
        } else {
            downloads.forEach { dl ->
                val sel = selectedFile?.id == dl.id
                FileRow(dl = dl, selected = sel, onSelect = { selectedFile = if (sel) null else dl })
            }
        }

        BrandButton(
            text    = if (selectedFile == null) "Select a file above" else "Send \"${selectedFile!!.title}\"",
            enabled = selectedFile != null && uiState is TransferUiState.Connected,
            onClick = {
                val file = selectedFile ?: return@BrandButton
                vm.sendFile(ctx, file)
                selectedFile = null
            },
            modifier = Modifier.fillMaxWidth(),
            icon = { Icon(IconUpload, null, tint = Color(0xFF001428), modifier = Modifier.size(d.iconMd - 4.dp)) },
        )

        // Session history
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(d.spaceSm))
            SectionHeader("This session")
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(d.spaceSm),
                modifier = Modifier.heightIn(max = 400.dp),
            ) {
                items(history.take(30), key = { it.id }) { TransferHistoryRow(it) }
            }
        }

        GhostButton("Disconnect", onClick = { showDisconnect = true }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(d.spaceXxl * 3f))
    }

    if (showDisconnect) {
        DisconnectDialog(
            onConfirm = { showDisconnect = false; vm.disconnect() },
            onDismiss = { showDisconnect = false },
        )
    }
}

// ─── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun BeamInitCard(
    title: String,
    subtitle: String,
    buttonLabel: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val d = LocalDimensions.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg))
            .background(Brush.verticalGradient(listOf(BgCard, BgRaised)))
            .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg))
            .padding(d.spaceXxl - d.spaceSm),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            // Animated beam rings
            BeamRings()
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textXl)
            Text(subtitle, color = White60, fontSize = d.textMd, textAlign = TextAlign.Center)
            BrandButton(
                text    = buttonLabel,
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                icon    = { Icon(icon, null, tint = Color(0xFF001428), modifier = Modifier.size(d.iconMd - 4.dp)) },
            )
        }
    }
}

@Composable
private fun BeamRings() {
    val d = LocalDimensions.current
    val inf = rememberInfiniteTransition(label = "rings")
    val ring1 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), "r1")
    val ring2 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, 667, LinearEasing), RepeatMode.Restart), "r2")
    val ring3 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, 1334, LinearEasing), RepeatMode.Restart), "r3")

    Canvas(Modifier.size(80.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        for ((progress, delay) in listOf(ring1 to 0f, ring2 to 0f, ring3 to 0f)) {
            val radius = 20.dp.toPx() + progress * 20.dp.toPx()
            drawCircle(
                color  = Brand.copy(alpha = (1f - progress).coerceAtLeast(0f) * 0.5f),
                radius = radius, center = center,
                style  = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
        drawCircle(color = Brand, radius = 20.dp.toPx(), center = center)
        drawCircle(color = BrandDeep, radius = 12.dp.toPx(), center = center)
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    buttonLabel: String,
    buttonIcon: @Composable () -> Unit,
    onRequest: () -> Unit,
) {
    val d = LocalDimensions.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg))
            .background(BgCard)
            .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg))
            .padding(d.spaceXxl - d.spaceSm),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            Icon(icon, null, tint = Brand, modifier = Modifier.size(44.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = d.textLg,
                textAlign = TextAlign.Center)
            Text(subtitle, color = White60, fontSize = d.textMd, textAlign = TextAlign.Center)
            BrandButton(
                text = buttonLabel, onClick = onRequest, modifier = Modifier.fillMaxWidth(),
                icon = buttonIcon,
            )
        }
    }
}

@Composable
private fun QrCard(qr: android.graphics.Bitmap?, sessionId: String, onReset: () -> Unit) {
    val d = LocalDimensions.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg))
            .background(Brush.verticalGradient(listOf(BgCard, BgRaised)))
            .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg))
            .padding(d.spaceXl - d.spaceXs),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            // Live indicator
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                val inf = rememberInfiniteTransition(label = "dot")
                val a by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), "da")
                Box(Modifier.size(8.dp).background(Success.copy(a), CircleShape))
                Text("Ready to scan", color = White60, fontSize = d.textSm)
                Spacer(Modifier.weight(1f))
                Text("ID: $sessionId", color = White40, fontSize = d.textXs)
            }

            // QR code
            Box(
                Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(d.radiusMd))
                    .background(Color.White)
                    .padding(8.dp)
            ) {
                if (qr != null) {
                    Image(qr.asImageBitmap(), "QR", modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CinematicSpinner(size = d.spinnerMd)
                    }
                }
            }

            Text(
                "Scan with the other device's Reelz app",
                color = White60, fontSize = d.textSm, textAlign = TextAlign.Center,
            )

            GhostButton("Reset", onClick = onReset, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WaitingPeerCard() {
    val d = LocalDimensions.current
    val inf = rememberInfiniteTransition(label = "wp")
    val dots by inf.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), "wd")
    val dotStr = ".".repeat((dots.toInt() % 3) + 1)

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd))
            .background(GlassSm)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .padding(d.spaceMd),
        Alignment.Center,
    ) {
        Text("Waiting for receiver$dotStr", color = White60, fontSize = d.textMd)
    }
}

@Composable
private fun NegotiatingCard() {
    val d = LocalDimensions.current
    val tiers = listOf("Wi-Fi Direct", "Local Wi-Fi", "Hotspot")
    var activeTier by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        for (i in tiers.indices) {
            activeTier = i
            kotlinx.coroutines.delay(1800)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg))
            .background(BgCard)
            .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg))
            .padding(d.spaceXl),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            CinematicSpinner(size = d.spinnerMd + d.spaceXl)
            Text("Negotiating connection…", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = d.textLg)
            tiers.forEachIndexed { i, name ->
                val state = when {
                    i < activeTier  -> "done"
                    i == activeTier -> "active"
                    else            -> "pending"
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
                ) {
                    val (color, text) = when (state) {
                        "done"    -> Success to "✓"
                        "active"  -> Brand to "…"
                        else      -> White40 to "${i + 1}"
                    }
                    Box(
                        Modifier.size(24.dp).clip(CircleShape)
                            .background(color.copy(.15f)).border(1.dp, color.copy(.4f), CircleShape),
                        Alignment.Center,
                    ) { Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    Text(name, color = if (state == "pending") White40 else Color.White, fontSize = d.textMd)
                    if (state == "active") {
                        val inf = rememberInfiniteTransition(label = "nt$i")
                        val a by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), "na$i")
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(6.dp).background(Brand.copy(a), CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedBadge(peerName: String, tier: TransportTier?, isHost: Boolean) {
    val d = LocalDimensions.current
    val tierLabel = when (tier) {
        TransportTier.WIFI_DIRECT -> "Wi-Fi Direct · fastest"
        TransportTier.LOCAL_WIFI  -> "Local Wi-Fi"
        TransportTier.HOTSPOT     -> "Hotspot"
        null                      -> "Connected"
    }
    val tierColor = when (tier) {
        TransportTier.WIFI_DIRECT -> Brand
        TransportTier.LOCAL_WIFI  -> Teal
        TransportTier.HOTSPOT     -> Warning
        null                      -> Success
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg))
            .background(Brush.linearGradient(listOf(Color(0xFF0D1F0D), Success.copy(.4f))))
            .border(1.dp, Success.copy(.35f), RoundedCornerShape(d.radiusLg))
            .padding(d.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Box(
            Modifier
                .size(40.dp).clip(CircleShape)
                .background(Success.copy(.15f)).border(1.dp, Success.copy(.4f), CircleShape),
            Alignment.Center,
        ) { Icon(IconCheck, null, tint = Success, modifier = Modifier.size(d.iconMd)) }
        Column(Modifier.weight(1f)) {
            Text("Connected to $peerName", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = d.textMd)
            Text(tierLabel, color = tierColor, fontSize = d.textXs, fontWeight = FontWeight.SemiBold)
        }
        Text(if (isHost) "HOST" else "CLIENT", color = White40, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun FileRow(dl: DownloadItem, selected: Boolean, onSelect: () -> Unit) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd))
            .background(if (selected) AmberGlass else BgCard)
            .border(1.dp, if (selected) AmberBorder else GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .clickable(onClick = onSelect)
            .padding(d.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Box(
            Modifier
                .size(36.dp).clip(CircleShape)
                .background(if (selected) AmberGlass else GlassMd)
                .border(1.dp, if (selected) AmberBorder else GlassBorderMd, CircleShape),
            Alignment.Center,
        ) {
            Icon(
                if (selected) IconCheck else IconMovieSlate,
                null,
                tint = if (selected) Brand else White60,
                modifier = Modifier.size(d.iconMd - 4.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(dl.title, color = Color.White, fontSize = d.textMd, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${dl.quality} · ${formatSize(dl.sizeBytes)}", color = White60, fontSize = d.textXs)
        }
        if (selected) {
            Text("Selected", color = Brand, fontSize = d.textXs, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ErrorCard(msg: String, retryable: Boolean, onRetry: () -> Unit) {
    val d = LocalDimensions.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg))
            .background(BgCard)
            .border(1.dp, Error.copy(.4f), RoundedCornerShape(d.radiusLg))
            .padding(d.spaceXl),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            Text("✕", color = Error, fontSize = d.textXxl, fontWeight = FontWeight.Bold)
            Text(msg, color = White60, fontSize = d.textMd, textAlign = TextAlign.Center)
            if (retryable) BrandButton("Try Again", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DisconnectDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgCard,
        title = { Text("Disconnect?", color = Color.White) },
        text  = { Text("This will end the session on both devices.", color = White60) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Disconnect", color = Error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay", color = Brand) }
        },
    )
}

// ─── QR Scanner ────────────────────────────────────────────────────────────────

@Composable
private fun ScannerCard(onScanned: (String) -> Unit) {
    val d = LocalDimensions.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    Box(
        Modifier
            .fillMaxWidth()
            .height(screenH * 0.42f)
            .clip(RoundedCornerShape(d.radiusLg))
            .background(Color.Black)
            .border(1.dp, AmberBorder, RoundedCornerShape(d.radiusLg))
    ) {
        CameraScanner(onScanned = onScanned)

        // Scan line
        val inf = rememberInfiniteTransition(label = "sl")
        val scanY by inf.animateFloat(0f, 1f,
            infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), "sy")
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 48.dp)
                .offset(y = (scanY * 250).dp)
                .height(2.dp)
                .background(Brush.horizontalGradient(listOf(
                    Color.Transparent, Brand2, Brand, Brand2, Color.Transparent,
                )))
        )

        // Corner brackets
        val bs = 32.dp; val bw = 3.dp
        listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd).forEach { a ->
            Box(Modifier.padding(24.dp).size(bs).align(a)) {
                val top   = a == Alignment.TopStart || a == Alignment.TopEnd
                val start = a == Alignment.TopStart || a == Alignment.BottomStart
                Box(Modifier.align(if (top) Alignment.TopStart else Alignment.BottomStart)
                    .width(bs).height(bw).background(Brand))
                Box(Modifier.align(if (start) Alignment.TopStart else Alignment.TopEnd)
                    .width(bw).height(bs).background(Brand))
            }
        }

        // Label
        Text(
            "Point at the sender's QR code",
            color = White60, fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = d.spaceLg)
                .background(Color.Black.copy(.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun CameraScanner(onScanned: (String) -> Unit) {
    val ctx            = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor       = remember { Executors.newSingleThreadExecutor() }
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
                        if (result != null) {
                            hasScanned = true
                            onScanned(result)
                        }
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

// ─── Transfer history row ──────────────────────────────────────────────────────

@Composable
fun TransferHistoryRow(record: TransferRecord) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .padding(d.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(AmberGlass).border(1.dp, AmberBorder, CircleShape),
            Alignment.Center,
        ) {
            Icon(
                if (record.direction == "SEND") IconUpload else IconDownloadCloud,
                null, tint = Brand, modifier = Modifier.size(d.iconMd - 4.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(record.fileName, color = Color.White, fontSize = d.textMd,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                "${if (record.direction == "SEND") "↑" else "↓"} ${record.peerName} · ${record.status}",
                color = White60, fontSize = d.textXs,
            )
        }
        Text(formatSize(record.sizeBytes), color = White40, fontSize = d.textXs)
    }
}

// ─── QR decoder helper ─────────────────────────────────────────────────────────

private fun decodeQr(proxy: ImageProxy): String? = try {
    val buf   = proxy.planes[0].buffer
    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
    val src   = PlanarYUVLuminanceSource(bytes, proxy.width, proxy.height,
                    0, 0, proxy.width, proxy.height, false)
    MultiFormatReader()
        .also { it.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
        .decode(BinaryBitmap(HybridBinarizer(src))).text
} catch (_: Exception) { null }

@Composable
private fun SectionHeader(text: String) {
    val d = LocalDimensions.current
    Text(text, color = White60, fontSize = d.textSm, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp)
}
