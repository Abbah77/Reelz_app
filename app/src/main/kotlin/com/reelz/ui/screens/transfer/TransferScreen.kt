package com.reelz.ui.screens.transfer

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.wifi.WifiManager
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelz.data.local.TransferDao
import com.reelz.data.model.TransferRecord
import com.reelz.transfer.TransferService
import com.reelz.ui.components.*
import com.reelz.ui.screens.downloads.formatSize
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val dao: TransferDao,
) : ViewModel() {
    val history: StateFlow<List<TransferRecord>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val progress = TransferService.progressFlow.asStateFlow()

    fun startReceive(ctx: Context) {
        ctx.startForegroundService(Intent(ctx, TransferService::class.java).setAction(TransferService.ACTION_RECEIVE))
    }

    fun startSend(ctx: Context, filePath: String, ip: String, port: Int) {
        ctx.startForegroundService(
            Intent(ctx, TransferService::class.java)
                .setAction(TransferService.ACTION_SEND)
                .putExtra(TransferService.EXTRA_FILE, filePath)
                .putExtra(TransferService.EXTRA_IP, ip)
                .putExtra(TransferService.EXTRA_PORT, port)
        )
    }
}

@Composable
fun TransferScreen(vm: TransferViewModel = hiltViewModel()) {
    val ctx      = LocalContext.current
    val history  by vm.history.collectAsState()
    val progress by vm.progress.collectAsState()
    var tab      by remember { mutableStateOf(0) }  // 0=Send, 1=Receive
    var peerIp   by remember { mutableStateOf("") }
    var myIp     by remember { mutableStateOf(getDeviceIp(ctx)) }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        // ── Header ─────────────────────────────────────────────────────
        Text(
            "Transfer",
            style = MaterialTheme.typography.headlineMedium.copy(color = White, fontWeight = FontWeight.Black),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )

        // ── Tab ────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Send", "Receive").forEachIndexed { i, label ->
                GenrePill(label, tab == i) { tab = i }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Active progress ────────────────────────────────────────────
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
                                Text("${if (p.direction == "SEND") "Sending to" else "Receiving from"} ${p.peerName}", color = White60, fontSize = 12.sp)
                            }
                            Text(formatSize(p.sentBytes), color = Brand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        if (p.totalBytes > 0) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { (p.sentBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = Brand, trackColor = GlassMd,
                            )
                        }
                    }
                }
            }
        }

        // ── Send tab ───────────────────────────────────────────────────
        if (tab == 0) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                // My IP + QR
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Wifi, null, tint = Brand, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Your IP Address", color = White60, fontSize = 12.sp)
                        Text(myIp, color = White, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Share this IP with the receiver, or show them the QR code.", color = White40, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        // QR code (text representation)
                        QrBox(myIp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Enter receiver IP
                Text("Send a File", color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = peerIp,
                    onValueChange = { peerIp = it },
                    label = { Text("Receiver IP address") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Brand, unfocusedBorderColor = GlassBorderMd,
                        focusedTextColor = White, unfocusedTextColor = White,
                        focusedLabelColor = Brand, unfocusedLabelColor = White40,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                BrandButton(
                    text = "Choose File & Send",
                    onClick = {
                        // In a real app, launch file picker here
                        // For downloaded files, list them from internal storage
                        if (peerIp.isNotBlank()) {
                            val file = ctx.filesDir.resolve("downloads").listFiles()?.firstOrNull()
                            if (file != null) vm.startSend(ctx, file.absolutePath, peerIp, TransferService.TRANSFER_PORT)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Default.FolderOpen, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                )
            }
        }

        // ── Receive tab ────────────────────────────────────────────────
        if (tab == 1) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Download, null, tint = Brand, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Ready to Receive", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Make sure both devices are on the same Wi-Fi network. Tap below to start listening.", color = White60, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 19.sp)
                        Spacer(Modifier.height(16.dp))
                        BrandButton(
                            text = "Start Receiving",
                            onClick = { vm.startReceive(ctx) },
                            icon = { Icon(Icons.Default.Antenna, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                if (history.isNotEmpty()) {
                    Text("Transfer History", color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(history.take(20), key = { it.id }) { r ->
                            TransferHistoryRow(r)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TransferHistoryRow(record: TransferRecord) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(BgCard)
            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (record.direction == "SEND") Icons.Default.Upload else Icons.Default.Download,
            null, tint = Brand, modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(record.fileName, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${if (record.direction == "SEND") "→" else "←"} ${record.peerName}", color = White60, fontSize = 11.sp)
        }
        Text(formatSize(record.sizeBytes), color = White40, fontSize = 11.sp)
    }
}

// Simple QR visual placeholder — in production swap with ZXing library
@Composable
fun QrBox(content: String) {
    Box(
        Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
        Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Simplified QR-like visual
            repeat(4) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(4) {
                        Box(Modifier.size(12.dp).background(Color.Black.copy(if ((it + it) % 2 == 0) 1f else 0.3f)))
                    }
                }
                Spacer(Modifier.height(3.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(content, color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

fun getDeviceIp(ctx: Context): String {
    return try {
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifi.connectionInfo.ipAddress
        InetAddress.getByAddress(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array()).hostAddress ?: "0.0.0.0"
    } catch (_: Exception) { "0.0.0.0" }
}
