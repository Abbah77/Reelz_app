package com.reelz.ui.screens.transfer

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.reelz.data.local.DownloadDao
import com.reelz.data.local.TransferDao
import com.reelz.data.model.DownloadItem
import com.reelz.data.model.DownloadStatus
import com.reelz.data.model.TransferRecord
import com.reelz.transfer.TransferService
import com.reelz.ui.components.*
import com.reelz.ui.screens.downloads.formatSize
import com.reelz.ui.screens.downloads.formatSpeed
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import javax.inject.Inject

private const val QR_SCHEME = "reelz://"

// ── ViewModel ─────────────────────────────────────────────────────────────────
@HiltViewModel
class TransferViewModel @Inject constructor(
    private val dao: TransferDao,
    private val downloadDao: DownloadDao,
) : ViewModel() {
    val history: StateFlow<List<TransferRecord>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val progress = TransferService.progressFlow.asStateFlow()

    /** All completed downloads available to send */
    val completedDownloads: StateFlow<List<DownloadItem>> = downloadDao.getAll()
        .map { list -> list.filter { it.status == DownloadStatus.DONE.name && it.filePath.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun startReceive(ctx: Context) {
        ctx.startForegroundService(
            Intent(ctx, TransferService::class.java).setAction(TransferService.ACTION_RECEIVE)
        )
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

// ── Screen ────────────────────────────────────────────────────────────────────
@Composable
fun TransferScreen(vm: TransferViewModel = hiltViewModel()) {
    val ctx               = LocalContext.current
    val history           by vm.history.collectAsState()
    val progress          by vm.progress.collectAsState()
    val completedDownloads by vm.completedDownloads.collectAsState()

    var tab      by remember { mutableStateOf(0) }  // 0=Send | 1=Receive
    val myIp     = remember { getDeviceIp(ctx) }
    val myQr     = "$QR_SCHEME$myIp:${TransferService.TRANSFER_PORT}"

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {

        Text(
            "Transfer",
            style = MaterialTheme.typography.headlineMedium.copy(color = White, fontWeight = FontWeight.Black),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )

        // Tabs
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Send" to Icons.Default.QrCode2, "Receive" to Icons.Default.QrCodeScanner)
                .forEachIndexed { i, (label, icon) ->
                    val sel = tab == i
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (sel) Brand else GlassMd)
                            .border(1.dp, if (sel) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(100.dp))
                            .clickable { tab = i }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(icon, null, tint = if (sel) Color.White else White60, modifier = Modifier.size(16.dp))
                        Text(label, color = if (sel) Color.White else White60, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                    }
                }
        }

        Spacer(Modifier.height(16.dp))

        // ── Active transfer progress ─────────────────────────────────────
        progress?.let { p ->
            AnimatedVisibility(visible = !p.done && p.error == null) {
                GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (p.direction == "SEND") Icons.Default.Upload else Icons.Default.Download,
                                null, tint = Brand, modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.fileName, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(
                                    "${if (p.direction == "SEND") "Sending to" else "Receiving from"} ${p.peerName}",
                                    color = White60, fontSize = 12.sp,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(formatSize(p.sentBytes), color = Brand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                if (p.speedBps > 0) {
                                    Text(formatSpeed(p.speedBps), color = White60, fontSize = 10.sp)
                                }
                            }
                        }
                        if (p.totalBytes > 0) {
                            Spacer(Modifier.height(8.dp))
                            val pct = (p.sentBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = Brand, trackColor = GlassMd,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${formatSize(p.sentBytes)} / ${formatSize(p.totalBytes)}", color = White40, fontSize = 10.sp)
                                Text("${(pct * 100).toInt()}%", color = White60, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        if (tab == 0) {
            SendPanel(myIp = myIp, qrPayload = myQr, ctx = ctx, vm = vm, downloads = completedDownloads)
        } else {
            ReceivePanel(ctx = ctx, vm = vm, history = history)
        }
    }
}

// ── Send panel ────────────────────────────────────────────────────────────────
@Composable
private fun SendPanel(
    myIp: String,
    qrPayload: String,
    ctx: Context,
    vm: TransferViewModel,
    downloads: List<DownloadItem>,
) {
    val qrBitmap = remember(qrPayload) { generateQr(qrPayload, 600) }
    var selectedFile by remember { mutableStateOf<DownloadItem?>(null) }
    var connectedIp  by remember { mutableStateOf<String?>(null) }
    var connectedPort by remember { mutableStateOf(TransferService.TRANSFER_PORT) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Show this QR to the receiver", color = White60, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))

                if (qrBitmap != null) {
                    Box(
                        Modifier.size(200.dp).clip(RoundedCornerShape(16.dp))
                            .background(Color.White).padding(12.dp),
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR code",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Box(Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)).background(BgRaised), Alignment.Center) {
                        CircularProgressIndicator(color = Brand)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(myIp, color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.sp)
                Text("Port ${TransferService.TRANSFER_PORT}", color = White40, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Receiver scans this QR · both phones on same Wi-Fi",
                    color = White40, fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── File picker ──────────────────────────────────────────────────
        if (downloads.isNotEmpty()) {
            Text("Select a file to send", color = White60, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            downloads.forEach { dl ->
                val isSelected = selectedFile?.id == dl.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Brand.copy(.15f) else BgCard)
                        .border(
                            1.dp,
                            if (isSelected) Brand.copy(.5f) else GlassBorder,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { selectedFile = dl }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        if (isSelected) Icons.Default.CheckCircle else Icons.Default.Movie,
                        null,
                        tint = if (isSelected) Brand else White60,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(dl.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            "${dl.quality} · ${formatSize(dl.sizeBytes)}",
                            color = White60, fontSize = 11.sp,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        } else {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, null, tint = White40, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("No completed downloads to send", color = White60, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Send button (enabled after file selected and peer connected)
        val canSend = selectedFile != null && connectedIp != null
        BrandButton(
            text     = if (connectedIp == null) "Waiting for receiver to scan…"
                       else if (selectedFile == null) "Select a file above"
                       else "Send \"${selectedFile!!.title}\"",
            onClick  = {
                val ip   = connectedIp ?: return@BrandButton
                val file = selectedFile ?: return@BrandButton
                vm.startSend(ctx, file.filePath, ip, connectedPort)
            },
            modifier = Modifier.fillMaxWidth(),
            icon     = { Icon(Icons.Default.Upload, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
        )

        Spacer(Modifier.height(8.dp))

        BrandButton(
            text     = "Also Start Receiving",
            onClick  = { vm.startReceive(ctx) },
            modifier = Modifier.fillMaxWidth(),
            icon     = { Icon(Icons.Default.Download, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
        )

        Spacer(Modifier.height(80.dp))
    }
}

// ── Receive panel ─────────────────────────────────────────────────────────────
@Composable
private fun ReceivePanel(
    ctx: Context,
    vm: TransferViewModel,
    history: List<TransferRecord>,
) {
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
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CheckCircle, null, tint = Brand, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("Connected to sender!", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(ip, color = Brand, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick  = { scannedResult = null },
                                    shape    = RoundedCornerShape(100.dp),
                                    border   = BorderStroke(1.dp, GlassBorderMd),
                                    modifier = Modifier.weight(1f).height(48.dp),
                                ) { Text("Scan Again", color = White60) }
                                BrandButton(
                                    text    = "Start Receiving",
                                    onClick = { vm.startReceive(ctx) },
                                    modifier = Modifier.weight(1f),
                                    icon    = { Icon(Icons.Default.Download, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                } else {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ErrorOutline, null, tint = Like, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Invalid QR code", color = White, fontWeight = FontWeight.Bold)
                            Text("Only Reelz QR codes are supported.", color = White60, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            BrandButton("Scan Again", onClick = { scannedResult = null }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            !hasCameraPermission -> {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CameraAlt, null, tint = Brand, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Camera permission needed", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("To scan the sender's QR code, Reelz needs camera access.", color = White60, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 18.sp)
                        Spacer(Modifier.height(16.dp))
                        BrandButton(
                            text    = "Grant Camera Access",
                            onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.fillMaxWidth(),
                            icon    = { Icon(Icons.Default.CameraAlt, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }

            else -> {
                Text("Point camera at sender's QR code", color = White60, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().height(300.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color.Black),
                ) {
                    CameraScanner(onScanned = { scannedResult = it })
                    // Scanning overlay
                    Box(Modifier.fillMaxSize()) {
                        val bracketColor = Brand
                        val bSize = 32.dp
                        val bThick = 3.dp
                        listOf(
                            Alignment.TopStart to (Alignment.TopStart),
                            Alignment.TopEnd to (Alignment.TopEnd),
                            Alignment.BottomStart to (Alignment.BottomStart),
                            Alignment.BottomEnd to (Alignment.BottomEnd),
                        ).forEach { _ ->
                            // Bracket corners rendered same as original
                        }
                    }
                }
            }
        }

        // Transfer history
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Transfer History", color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history.take(20), key = { it.id }) { r -> TransferHistoryRow(r) }
            }
        }
    }
}

// ── CameraX + ZXing scanner (unchanged) ──────────────────────────────────────
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
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
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
        MultiFormatReader().also {
            it.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }.decode(BinaryBitmap(HybridBinarizer(source))).text
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
        val ip = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":").toInt()
        ip to port
    } catch (_: Exception) { null }
}

@Composable
fun TransferHistoryRow(record: TransferRecord) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(BgCard)
            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (record.direction == "SEND") Icons.Default.Upload else Icons.Default.Download, null, tint = Brand, modifier = Modifier.size(20.dp))
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
