package com.axio.reelz.ui.screens.transfer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.screens.downloads.formatSize
import com.axio.reelz.ui.screens.downloads.formatSpeed
import com.axio.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import javax.inject.Inject

private const val QR_SCHEME = "reelz://"

// Custom icons
private val IconBack: ImageVector get() = ImageVector.Builder("TransferBack", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(19f, 12f); lineTo(5f, 12f); moveTo(12f, 19f); lineTo(5f, 12f); lineTo(12f, 5f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconUpload: ImageVector get() = ImageVector.Builder("Upload", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 3f); lineTo(12f, 15f)
        moveTo(8f, 7f); lineTo(12f, 3f); lineTo(16f, 7f)
        moveTo(20f, 17f); lineTo(20f, 21f); lineTo(4f, 21f); lineTo(4f, 17f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconQr: ImageVector get() = ImageVector.Builder("QR", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(3f, 3f); lineTo(9f, 3f); lineTo(9f, 9f); lineTo(3f, 9f); close()
        moveTo(5f, 5f); lineTo(7f, 5f); lineTo(7f, 7f); lineTo(5f, 7f); close()
        moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f); lineTo(15f, 9f); close()
        moveTo(17f, 5f); lineTo(19f, 5f); lineTo(19f, 7f); lineTo(17f, 7f); close()
        moveTo(3f, 15f); lineTo(9f, 15f); lineTo(9f, 21f); lineTo(3f, 21f); close()
        moveTo(5f, 17f); lineTo(7f, 17f); lineTo(7f, 19f); lineTo(5f, 19f); close()
        moveTo(15f, 15f); lineTo(17f, 15f); lineTo(17f, 17f); lineTo(15f, 17f); close()
        moveTo(19f, 15f); lineTo(21f, 15f); lineTo(21f, 17f); lineTo(19f, 17f); close()
        moveTo(17f, 19f); lineTo(21f, 19f); lineTo(21f, 21f); lineTo(17f, 21f); close()
    }, fill = SolidColor(Color.White))
}.build()

private val IconScan: ImageVector get() = ImageVector.Builder("Scan", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(3f, 7f); lineTo(3f, 3f); lineTo(7f, 3f)
        moveTo(17f, 3f); lineTo(21f, 3f); lineTo(21f, 7f)
        moveTo(21f, 17f); lineTo(21f, 21f); lineTo(17f, 21f)
        moveTo(7f, 21f); lineTo(3f, 21f); lineTo(3f, 17f)
        moveTo(3f, 12f); lineTo(21f, 12f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconCheckCircle: ImageVector get() = ImageVector.Builder("Check", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
        moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
    }, stroke = SolidColor(Color(0xFF2DD36F)), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconCamera: ImageVector get() = ImageVector.Builder("Camera", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(23f, 19f); arcTo(2f, 2f, 0f, false, true, 21f, 21f); lineTo(3f, 21f)
        arcTo(2f, 2f, 0f, false, true, 1f, 19f); lineTo(1f, 8f)
        arcTo(2f, 2f, 0f, false, true, 3f, 6f); lineTo(7f, 6f); lineTo(9f, 3f); lineTo(15f, 3f)
        lineTo(17f, 6f); lineTo(21f, 6f); arcTo(2f, 2f, 0f, false, true, 23f, 8f); close()
        moveTo(12f, 10f); arcTo(4f, 4f, 0f, false, false, 12f, 18f); arcTo(4f, 4f, 0f, false, false, 12f, 10f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, fill = SolidColor(Color.Transparent))
}.build()

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

    fun startReceive(ctx: Context) {
        ctx.startForegroundService(Intent(ctx, TransferService::class.java).setAction(TransferService.ACTION_RECEIVE))
    }
    fun startSend(ctx: Context, filePath: String, ip: String, port: Int) {
        ctx.startForegroundService(
            Intent(ctx, TransferService::class.java)
                .setAction(TransferService.ACTION_SEND)
                .putExtra(TransferService.EXTRA_FILE, filePath)
                .putExtra(TransferService.EXTRA_IP, ip)
                .putExtra(TransferService.EXTRA_PORT, port),
        )
    }
}

@Composable
fun TransferScreen(nav: NavController? = null, vm: TransferViewModel = hiltViewModel()) {
    val ctx                = LocalContext.current
    val history           by vm.history.collectAsState()
    val progress          by vm.progress.collectAsState()
    val completedDownloads by vm.completedDownloads.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val myIp   = remember { getDeviceIp(ctx) }
    val myQr   = "$QR_SCHEME$myIp:${TransferService.TRANSFER_PORT}"

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {

        // ── Header ─────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nav != null) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(GlassMd)
                        .border(1.dp, GlassBorderMd, CircleShape)
                        .clickable { nav.popBackStack() },
                    Alignment.Center,
                ) { Icon(IconBack, null, tint = White, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text("Transfer", style = MaterialTheme.typography.headlineMedium.copy(
                    color = White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp
                ))
                Text("Share downloads wirelessly", color = Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Icon(IconSwap, null, tint = Brand.copy(.6f), modifier = Modifier.size(26.dp))
        }

        // ── Tab toggle ─────────────────────────────────────────────────────
        Box(
            Modifier.padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BgCard)
                .border(1.dp, GlassBorderMd, RoundedCornerShape(14.dp))
                .padding(4.dp)
        ) {
            Row {
                listOf("Send" to IconQr, "Receive" to IconScan).forEachIndexed { i, (label, icon) ->
                    val sel = tab == i
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (sel) Brush.horizontalGradient(listOf(BrandDeep, Brand.copy(.9f)))
                                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                            )
                            .clickable { tab = i }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(icon, null, tint = if (sel) Color(0xFF1A0F00) else White60, modifier = Modifier.size(16.dp))
                            Text(label, color = if (sel) Color(0xFF1A0F00) else White60,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Active transfer progress bar ────────────────────────────────────
        progress?.let { p ->
            AnimatedVisibility(visible = !p.done && p.error == null, enter = slideInVertically() + fadeIn(), exit = slideOutVertically() + fadeOut()) {
                val animPct by animateFloatAsState((p.sentBytes.toFloat() / p.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f), tween(400), label = "tp")
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgCard)
                        .border(1.dp, Brand.copy(.3f), RoundedCornerShape(16.dp))
                ) {
                    // Animated progress fill behind content
                    Box(Modifier.fillMaxWidth(animPct).height(72.dp)
                        .background(Brush.horizontalGradient(listOf(BrandDim, BrandDeep))))
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (p.direction == "SEND") IconUpload else IconDownloadCloud,
                            null, tint = Brand, modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.fileName, color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "${if (p.direction == "SEND") "→" else "←"} ${p.peerName}",
                                color = White60, fontSize = 11.sp,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatSize(p.sentBytes), color = Brand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            if (p.speedBps > 0) Text(formatSpeed(p.speedBps), color = White40, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (tab == 0) {
            SendPanel(myIp = myIp, qrPayload = myQr, ctx = ctx, vm = vm, downloads = completedDownloads)
        } else {
            ReceivePanel(ctx = ctx, vm = vm, history = history)
        }
    }
}

@Composable
private fun SendPanel(myIp: String, qrPayload: String, ctx: Context, vm: TransferViewModel, downloads: List<DownloadItem>) {
    val qrBitmap = remember(qrPayload) { generateQr(qrPayload, 600) }
    var selectedFile  by remember { mutableStateOf<DownloadItem?>(null) }
    var connectedIp   by remember { mutableStateOf<String?>(null) }
    var connectedPort by remember { mutableStateOf(TransferService.TRANSFER_PORT) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // QR Card
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(BgCard)
                .border(1.dp, AmberBorder, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Show this QR to the receiver", color = White60, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                // QR with branded border
                Box(
                    Modifier.size(210.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.radialGradient(listOf(Brand.copy(.06f), Color.Transparent)))
                        .border(2.dp, AmberBorder, RoundedCornerShape(16.dp))
                        .padding(3.dp)
                ) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(12.dp)
                    ) {
                        if (qrBitmap != null) {
                            Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR", modifier = Modifier.fillMaxSize())
                        } else {
                            Box(Modifier.fillMaxSize(), Alignment.Center) { CinematicSpinner(size = 36.dp) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(myIp, color = White, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 2.sp)
                Text("Port ${TransferService.TRANSFER_PORT}", color = White40, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Receiver scans this QR · both on same Wi-Fi",
                    color = White40, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 16.sp,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // File picker
        if (downloads.isNotEmpty()) {
            Text("Select a file to send", color = White60, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            downloads.forEach { dl ->
                val isSelected = selectedFile?.id == dl.id
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) AmberGlass else BgCard)
                        .border(1.dp, if (isSelected) AmberBorder else GlassBorderMd, RoundedCornerShape(14.dp))
                        .clickable { selectedFile = dl }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .background(if (isSelected) AmberGlass else GlassMd)
                            .border(1.dp, if (isSelected) AmberBorder else GlassBorderMd, CircleShape),
                        Alignment.Center,
                    ) {
                        Icon(if (isSelected) IconCheckCircle else IconMovieSlate, null,
                            tint = if (isSelected) Brand else White60, modifier = Modifier.size(16.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(dl.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${dl.quality} · ${formatSize(dl.sizeBytes)}", color = White60, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        } else {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BgCard)
                    .border(1.dp, GlassBorderMd, RoundedCornerShape(16.dp)).padding(24.dp),
                Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(IconDownloadCloud, null, tint = White40, modifier = Modifier.size(32.dp))
                    Text("No completed downloads to send", color = White60, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        BrandButton(
            text     = if (connectedIp == null) "Waiting for receiver…"
                       else if (selectedFile == null) "Select a file above"
                       else "Send \"${selectedFile!!.title}\"",
            onClick  = {
                val ip   = connectedIp ?: return@BrandButton
                val file = selectedFile ?: return@BrandButton
                vm.startSend(ctx, file.filePath, ip, connectedPort)
            },
            modifier = Modifier.fillMaxWidth(),
            icon     = { Icon(IconUpload, null, tint = Color(0xFF1A0F00), modifier = Modifier.size(16.dp)) },
        )
        Spacer(Modifier.height(10.dp))
        GhostButton("Also Start Receiving", onClick = { vm.startReceive(ctx) },
            modifier = Modifier.fillMaxWidth(),
            icon     = { Icon(IconDownloadCloud, null, tint = Brand, modifier = Modifier.size(16.dp)) })
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun ReceivePanel(ctx: Context, vm: TransferViewModel, history: List<TransferRecord>) {
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }
    var scannedResult by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        when {
            scannedResult != null -> {
                val parsed = parseQrPayload(scannedResult!!)
                if (parsed != null) {
                    val (ip, port) = parsed
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(Brush.verticalGradient(listOf(BgCard, BgRaised)))
                            .border(1.dp, Success.copy(.4f), RoundedCornerShape(20.dp))
                    ) {
                        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(contentAlignment = Alignment.Center) {
                                Box(Modifier.size(70.dp).clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(Success.copy(.15f), Color.Transparent)))
                                    .border(1.dp, Success.copy(.35f), CircleShape))
                                Icon(IconCheckCircle, null, tint = Success, modifier = Modifier.size(30.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("Connected!", color = White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(ip, color = Brand, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.height(18.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GhostButton("Scan Again", onClick = { scannedResult = null }, modifier = Modifier.weight(1f))
                                BrandButton("Start Receiving", onClick = { vm.startReceive(ctx) }, modifier = Modifier.weight(1f),
                                    icon = { Icon(IconDownloadCloud, null, tint = Color(0xFF1A0F00), modifier = Modifier.size(15.dp)) })
                            }
                        }
                    }
                } else {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(BgCard)
                            .border(1.dp, Error.copy(.4f), RoundedCornerShape(20.dp)).padding(22.dp),
                        Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("✕", color = Error, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                            Text("Invalid QR code", color = White, fontWeight = FontWeight.Bold)
                            Text("Only Reelz QR codes are supported.", color = White60, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            BrandButton("Scan Again", onClick = { scannedResult = null }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            !hasCameraPermission -> {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(BgCard)
                        .border(1.dp, AmberBorder, RoundedCornerShape(20.dp)).padding(24.dp),
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(70.dp).clip(CircleShape)
                                .background(Brush.radialGradient(listOf(AmberGlass, Color.Transparent)))
                                .border(1.dp, AmberBorder, CircleShape))
                            Icon(IconCamera, null, tint = Brand, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Camera access needed", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("To scan the sender's QR code, Reelz needs camera access.", color = White60, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
                        Spacer(Modifier.height(18.dp))
                        BrandButton("Grant Camera Access", onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = { Icon(IconCamera, null, tint = Color(0xFF1A0F00), modifier = Modifier.size(15.dp)) })
                    }
                }
            }

            else -> {
                Text("Point camera at sender's QR code", color = White60, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                // Scanner with corner brackets
                Box(
                    Modifier.fillMaxWidth().height(310.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black)
                        .border(1.dp, AmberBorder, RoundedCornerShape(20.dp)),
                ) {
                    CameraScanner(onScanned = { scannedResult = it })
                    // Corner brackets overlay
                    val bracketSize = 28.dp
                    val bracketW = 3.dp
                    listOf(
                        Alignment.TopStart, Alignment.TopEnd,
                        Alignment.BottomStart, Alignment.BottomEnd,
                    ).forEach { alignment ->
                        Box(Modifier.padding(20.dp).size(bracketSize).align(alignment)) {
                            val isTop   = alignment == Alignment.TopStart || alignment == Alignment.TopEnd
                            val isStart = alignment == Alignment.TopStart || alignment == Alignment.BottomStart
                            Box(Modifier.align(if (isTop) Alignment.TopStart else Alignment.BottomStart)
                                .width(bracketSize).height(bracketW).background(Brand))
                            Box(Modifier.align(if (isStart) Alignment.TopStart else Alignment.TopEnd)
                                .width(bracketW).height(bracketSize).background(Brand))
                        }
                    }
                    // Scanning line animation
                    val inf = rememberInfiniteTransition(label = "scan")
                    val scanY by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), "sy")
                    Box(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter)
                            .padding(horizontal = 32.dp)
                            .offset(y = (scanY * 270).dp)
                            .height(2.dp)
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, Brand2, Brand, Brand2, Color.Transparent)))
                    )
                }
            }
        }

        // Transfer history
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionHeader("Transfer History")
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 400.dp),
            ) {
                items(history.take(20), key = { it.id }) { r -> TransferHistoryRow(r) }
            }
        }
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
fun CameraScanner(onScanned: (String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var hasScanned by remember { mutableStateOf(false) }

    AndroidView(
        factory = { context ->
            val previewView = PreviewView(context)
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    if (!hasScanned) {
                        val result = decodeQr(proxy)
                        if (result != null) { hasScanned = true; onScanned(result) }
                    }
                    proxy.close()
                }
                try { provider.unbindAll(); provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(context))
            previewView
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun decodeQr(imageProxy: ImageProxy): String? {
    return try {
        val buffer: ByteBuffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
        val source = PlanarYUVLuminanceSource(bytes, imageProxy.width, imageProxy.height, 0, 0, imageProxy.width, imageProxy.height, false)
        MultiFormatReader().also { it.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
            .decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) { null }
}

fun generateQr(content: String, sizePx: Int): Bitmap? {
    return try {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) for (y in 0 until sizePx)
            bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        bmp
    } catch (_: Exception) { null }
}

fun parseQrPayload(raw: String): Pair<String, Int>? {
    return try {
        if (!raw.startsWith(QR_SCHEME)) return null
        val hostPort = raw.removePrefix(QR_SCHEME)
        hostPort.substringBeforeLast(":") to hostPort.substringAfterLast(":").toInt()
    } catch (_: Exception) { null }
}

@Composable
fun TransferHistoryRow(record: TransferRecord) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(AmberGlass).border(1.dp, AmberBorder, CircleShape),
            Alignment.Center,
        ) {
            Icon(if (record.direction == "SEND") IconUpload else IconDownloadCloud, null, tint = Brand, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(record.fileName, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${if (record.direction == "SEND") "→" else "←"} ${record.peerName}", color = White60, fontSize = 11.sp)
        }
        Text(formatSize(record.sizeBytes), color = White40, fontSize = 11.sp)
    }
}

fun getDeviceIp(ctx: Context): String {
    return try {
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifi.connectionInfo.ipAddress
        InetAddress.getByAddress(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array()).hostAddress ?: "0.0.0.0"
    } catch (_: Exception) { "0.0.0.0" }
}
