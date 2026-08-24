package com.axio.reelz.transfer

// ─────────────────────────────────────────────────────────────────────────────
//  BeamEngine — 2-tier wireless file transfer
//
//  How Xender/QuickShare do it (and how we do it):
//
//  Tier 1 — Wi-Fi Direct (WifiP2pManager)
//    • Android OS negotiates a P2P group automatically.
//    • One device becomes Group Owner (GO), the other a client.
//    • GO runs the TCP ServerSocket; client connects to GO's p2p0 IP.
//    • No router/internet needed. Up to ~200 Mbps raw.
//
//  Tier 2 — Hotspot (LocalOnlyHotspot + TCP)
//    • SENDER creates a LocalOnlyHotspot (silent, no notification required).
//    • QR encodes: sessionId | deviceName | ssid | password | port
//    • RECEIVER sees SSID + password in the QR, connects to that Wi-Fi.
//    • Once on the same AP, standard TCP socket completes the link.
//    • Works on every Android device, even API 21+.
//    • ~40–100 Mbps depending on hardware.
//
//  LAN/NSD removed — it requires both devices on the same router and adds
//  complexity without helping when that condition is not met.
//
//  QR format:
//    reelzbeam://<sessionId>|<deviceName>|<tier>|<ip>|<port>[|<ssid>|<pass>]
//    tier: "WD" = WiFiDirect ready (receiver connects after WD handshake)
//          "HS" = Hotspot (ssid + pass follow ip|port)
// ─────────────────────────────────────────────────────────────────────────────

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG  = "BeamEngine"
private const val PORT = 49800

// ─── Transport tier ───────────────────────────────────────────────────────────

enum class TransportTier { WIFI_DIRECT, HOTSPOT }

// ─── Engine states ────────────────────────────────────────────────────────────

sealed class EngineState {
    object Idle                                                                : EngineState()
    object Preparing                                                           : EngineState()
    data class QrReady(val qrPayload: String, val sessionId: String)           : EngineState()
    object Negotiating                                                         : EngineState()
    data class Connected(
        val tier: TransportTier,
        val peerName: String,
        val isHost: Boolean,
        val socket: Socket,
    )                                                                          : EngineState()
    data class Transferring(
        val tier: TransportTier,
        val peerName: String,
        val fileName: String,
        val direction: String,
        val transferredBytes: Long,
        val totalBytes: Long,
        val speedBps: Long,
    )                                                                          : EngineState()
    object Done                                                                : EngineState()
    data class Error(val msg: String, val retryable: Boolean = true, val kind: String = "GENERIC") : EngineState()
}

// ─── QR payload ───────────────────────────────────────────────────────────────

data class BeamPayload(
    val sessionId:  String,
    val deviceName: String,
    val tier:       String,   // "WD" or "HS"
    val ip:         String,
    val port:       Int,
    val ssid:       String = "",
    val password:   String = "",
) {
    fun encode(): String {
        val base = "reelzbeam://$sessionId|$deviceName|$tier|$ip|$port"
        return if (tier == "HS") "$base|$ssid|$password" else base
    }

    companion object {
        fun decode(raw: String): BeamPayload? = try {
            val s = raw.removePrefix("reelzbeam://")
            val p = s.split("|")
            if (p.size < 5) null
            else BeamPayload(
                sessionId  = p[0],
                deviceName = p[1],
                tier       = p[2],
                ip         = p[3],
                port       = p[4].toInt(),
                ssid       = p.getOrElse(5) { "" },
                password   = p.getOrElse(6) { "" },
            )
        } catch (_: Exception) { null }
    }
}

// ─── Engine ───────────────────────────────────────────────────────────────────

@Singleton
class P2pEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    // Sockets
    private var serverSocket: ServerSocket? = null
    private var activeSocket:  Socket?      = null

    // Wi-Fi Direct
    private var p2pManager:  WifiP2pManager?         = null
    private var p2pChannel:  WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver?      = null

    // Hotspot
    @Suppress("DEPRECATION")
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private var peerName    = ""
    private var sessionId   = ""

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun deviceName() = "${Build.MANUFACTURER} ${Build.MODEL}".take(24)

    fun getLocalIp(): String = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.hostAddress.startsWith("169.") }
            ?.hostAddress ?: ""
    } catch (_: Exception) { "" }

    private fun hasWifiDirect(): Boolean =
        ctx.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    // ── SENDER: prepare (try WD first, fall back to hotspot) ─────────────────

    fun prepareAsSender(onQrReady: (String) -> Unit) {
        disconnect()
        sessionId = UUID.randomUUID().toString().take(8).uppercase()
        _state.value = EngineState.Preparing

        scope.launch {
            if (hasWifiDirect()) {
                Log.d(TAG, "Sender: trying Wi-Fi Direct group")
                val wdPayload = tryCreateWifiDirectGroup()
                if (wdPayload != null) {
                    _state.value = EngineState.QrReady(wdPayload, sessionId)
                    withContext(Dispatchers.Main) { onQrReady(wdPayload) }
                    acceptConnectionOnServerSocket(TransportTier.WIFI_DIRECT)
                    return@launch
                }
                Log.d(TAG, "Sender: WD group failed, falling back to Hotspot")
            }

            // Hotspot fallback
            val hsPayload = tryCreateHotspot()
            if (hsPayload != null) {
                _state.value = EngineState.QrReady(hsPayload, sessionId)
                withContext(Dispatchers.Main) { onQrReady(hsPayload) }
                acceptConnectionOnServerSocket(TransportTier.HOTSPOT)
            } else {
                _state.value = EngineState.Error(
                    "Could not create Wi-Fi Direct group or Hotspot. Check permissions and try again.",
                    retryable = true, kind = "CONNECTION"
                )
            }
        }
    }

    // ── RECEIVER: connect from QR ─────────────────────────────────────────────

    fun connectFromQr(rawQr: String) {
        val payload = BeamPayload.decode(rawQr) ?: run {
            _state.value = EngineState.Error("Invalid QR code.", retryable = true, kind = "CONNECTION")
            return
        }
        peerName  = payload.deviceName
        sessionId = payload.sessionId
        _state.value = EngineState.Negotiating

        scope.launch {
            val connected = when (payload.tier) {
                "WD" -> connectViaWifiDirect(payload)
                "HS" -> connectViaHotspotIp(payload)
                else -> false
            }
            if (!connected) {
                // Try the other tier as fallback
                val fallbackOk = when (payload.tier) {
                    "WD" -> connectViaHotspotIp(payload)  // WD failed, try TCP if IP is in QR
                    else -> false
                }
                if (!fallbackOk) {
                    _state.value = EngineState.Error(
                        "Could not connect. Make sure both devices are close together and Wi-Fi is on.",
                        retryable = true, kind = "CONNECTION"
                    )
                }
            }
        }
    }

    // ── Wi-Fi Direct group (SENDER) ───────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun tryCreateWifiDirectGroup(): String? = withContext(Dispatchers.Main) {
        try {
            val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return@withContext null
            val chan = mgr.initialize(ctx, Looper.getMainLooper(), null)
            p2pManager = mgr
            p2pChannel  = chan

            // Request group info — if we already own a group, use it
            val groupDeferred = CompletableDeferred<WifiP2pGroup?>()
            mgr.requestGroupInfo(chan) { group -> groupDeferred.complete(group) }
            val existingGroup = withTimeoutOrNull(3_000) { groupDeferred.await() }

            if (existingGroup != null && existingGroup.isGroupOwner) {
                Log.d(TAG, "Reusing existing WD group: ${existingGroup.networkName}")
                return@withContext buildWdPayload(mgr, chan, existingGroup)
            }

            // Create a new persistent group
            val createDeferred = CompletableDeferred<Boolean>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName("DIRECT-${sessionId.take(4)}")
                    .setPassphrase(sessionId.lowercase())
                    .build()
                mgr.createGroup(chan, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { createDeferred.complete(true) }
                    override fun onFailure(r: Int) { createDeferred.complete(false) }
                })
            } else {
                @Suppress("DEPRECATION")
                mgr.createGroup(chan, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { createDeferred.complete(true) }
                    override fun onFailure(r: Int) { createDeferred.complete(false) }
                })
            }

            if (!withTimeoutOrNull(6_000) { createDeferred.await() }!!) {
                return@withContext null
            }

            // Retrieve group info with actual SSID/password
            val newGroupDeferred = CompletableDeferred<WifiP2pGroup?>()
            mgr.requestGroupInfo(chan) { group -> newGroupDeferred.complete(group) }
            val group = withTimeoutOrNull(5_000) { newGroupDeferred.await() }
                ?: return@withContext null

            buildWdPayload(mgr, chan, group)
        } catch (e: Exception) {
            Log.w(TAG, "WD group creation failed: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildWdPayload(
        mgr: WifiP2pManager,
        chan: WifiP2pManager.Channel,
        group: WifiP2pGroup,
    ): String {
        // The GO's IP on the p2p interface is always 192.168.49.1 in AOSP
        // (WifiP2pServiceImpl hardcodes this). We start a server socket on all
        // interfaces and embed the GO ip so the client knows where to connect.
        val goIp = "192.168.49.1"
        startServerSocket()
        return BeamPayload(
            sessionId  = sessionId,
            deviceName = deviceName(),
            tier       = "WD",
            ip         = goIp,
            port       = PORT,
            ssid       = group.networkName,
            password   = group.passphrase,
        ).encode()
    }

    // ── Wi-Fi Direct connect (RECEIVER) ───────────────────────────────────────

    private suspend fun connectViaWifiDirect(payload: BeamPayload): Boolean {
        // The receiver just needs to connect via TCP to the GO IP.
        // Android handles joining the WD group automatically once the user
        // connects to the WD AP (SSID from QR). We try a direct TCP connect
        // first — if the device already joined the WD network, it works instantly.
        return try {
            val ok = tcpConnect(payload.ip, payload.port, TransportTier.WIFI_DIRECT)
            if (ok) return true

            // If direct TCP fails, it means the receiver hasn't joined the
            // WD group yet. Show a hint — user must join the Wi-Fi named
            // in the QR. Then retry for up to 30s.
            Log.d(TAG, "WD TCP failed initially; waiting for user to join WD network...")
            val deadline = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < deadline) {
                delay(2_000)
                if (tcpConnect(payload.ip, payload.port, TransportTier.WIFI_DIRECT)) return true
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "WD connect: ${e.message}")
            false
        }
    }

    // ── Hotspot (SENDER) ──────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun tryCreateHotspot(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // API < 26: use existing Wi-Fi IP (assume user creates hotspot manually)
            val ip = getLocalIp()
            startServerSocket()
            return if (ip.isNotEmpty()) BeamPayload(
                sessionId  = sessionId,
                deviceName = deviceName(),
                tier       = "HS",
                ip         = ip,
                port       = PORT,
            ).encode() else null
        }

        return suspendCancellableCoroutine { cont ->
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? WifiManager ?: run { cont.resume(null, null); return@suspendCancellableCoroutine }

            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                @SuppressLint("NewApi")
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation

                    // Wait briefly for the hotspot interface to come up
                    scope.launch {
                        delay(1_500)
                        val ip = resolveHotspotGatewayIp()
                        startServerSocket()

                        val ssid = reservation.softApConfiguration?.ssid
                            ?: reservation.wifiConfiguration?.SSID
                            ?: ""
                        val pass = reservation.softApConfiguration?.passphrase
                            ?: reservation.wifiConfiguration?.preSharedKey
                            ?: ""

                        Log.d(TAG, "Hotspot started: ssid=$ssid pass=$pass ip=$ip")
                        val payload = BeamPayload(
                            sessionId  = sessionId,
                            deviceName = deviceName(),
                            tier       = "HS",
                            ip         = ip,
                            port       = PORT,
                            ssid       = ssid,
                            password   = pass,
                        ).encode()
                        cont.resume(payload, null)
                    }
                }
                override fun onFailed(reason: Int) {
                    Log.w(TAG, "Hotspot failed: $reason")
                    cont.resume(null, null)
                }
                override fun onStopped() {
                    Log.d(TAG, "Hotspot stopped")
                }
            }, null)
        }
    }

    /** Returns the hotspot gateway IP across all OEMs.
     *  Samsung/AOSP → 192.168.49.1
     *  Pixel        → 192.168.0.1
     *  Xiaomi/MIUI  → 192.168.43.1
     *  We detect it by looking for a new non-loopback, non-169.x private IPv4. */
    private fun resolveHotspotGatewayIp(): String {
        val wlanIp = getLocalIp()
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.toList() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { addr ->
                    val h = addr.hostAddress ?: return@firstOrNull false
                    !h.startsWith("169.") && h != wlanIp
                }
                ?.hostAddress
        } catch (_: Exception) { null }
            ?: "192.168.49.1"  // safe AOSP default
    }

    // ── Hotspot connect (RECEIVER) ─────────────────────────────────────────────

    private suspend fun connectViaHotspotIp(payload: BeamPayload): Boolean {
        if (payload.ip.isEmpty()) return false
        // Receiver needs to join the hotspot Wi-Fi first (user sees SSID/pass on screen).
        // We retry for 60s giving the user time to switch Wi-Fi.
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            if (tcpConnect(payload.ip, payload.port, TransportTier.HOTSPOT)) return true
            delay(2_000)
        }
        return false
    }

    // ── TCP plumbing ──────────────────────────────────────────────────────────

    private fun startServerSocket() {
        serverSocket?.closeQuietly()
        serverSocket = ServerSocket(PORT)
        Log.d(TAG, "ServerSocket listening on port $PORT")
    }

    /** Sender side: block until a client connects, then complete the handshake. */
    private suspend fun acceptConnectionOnServerSocket(tier: TransportTier) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Waiting for incoming connection ($tier)...")
                val ss = serverSocket ?: return@withContext
                ss.soTimeout = 120_000   // 2 min max wait
                val sock = ss.accept()
                sock.soTimeout = 0
                completeHandshake(sock, isHost = true)
                _state.value = EngineState.Connected(
                    tier     = tier,
                    peerName = peerName,
                    isHost   = true,
                    socket   = sock,
                )
                activeSocket = sock
            } catch (e: Exception) {
                Log.w(TAG, "Accept failed: ${e.message}")
                if (_state.value !is EngineState.Idle) {
                    _state.value = EngineState.Error("Connection timed out. Try again.", retryable = true, kind = "TIMEOUT")
                }
            }
        }
    }

    /** Receiver side: connect to sender's IP:port with retries. */
    private suspend fun tcpConnect(ip: String, port: Int, tier: TransportTier): Boolean =
        withContext(Dispatchers.IO) {
            repeat(3) { attempt ->
                try {
                    val sock = Socket()
                    sock.connect(InetSocketAddress(ip, port), 4_000)
                    sock.soTimeout = 0
                    completeHandshake(sock, isHost = false)
                    activeSocket = sock
                    _state.value = EngineState.Connected(
                        tier     = tier,
                        peerName = peerName,
                        isHost   = false,
                        socket   = sock,
                    )
                    return@withContext true
                } catch (e: Exception) {
                    Log.d(TAG, "TCP attempt ${attempt + 1} to $ip:$port — ${e.message}")
                    delay(600L * (attempt + 1))
                }
            }
            false
        }

    /** Exchange device names so both sides know who they're talking to. */
    private fun completeHandshake(sock: Socket, isHost: Boolean) {
        val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        if (isHost) {
            val hello = reader.readLine() ?: ""
            peerName = if (hello.startsWith("HELLO ")) hello.removePrefix("HELLO ") else "Unknown"
            writer.write("HELLO ${deviceName()}\n"); writer.flush()
        } else {
            writer.write("HELLO ${deviceName()}\n"); writer.flush()
            val hello = reader.readLine() ?: ""
            peerName = if (hello.startsWith("HELLO ")) hello.removePrefix("HELLO ") else "Unknown"
        }
        Log.d(TAG, "Handshake done — peer=$peerName isHost=$isHost")
    }

    // ── File send ─────────────────────────────────────────────────────────────

    fun sendFile(
        filePath: String,
        fileName: String,
        onProgress: (sent: Long, total: Long, bps: Long) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val sock = activeSocket ?: run { onError("Not connected"); return }
        val file = File(filePath)
        if (!file.exists()) { onError("File not found: $filePath"); return }

        val tier = currentTier()
        scope.launch(Dispatchers.IO) {
            try {
                val total = file.length()
                _state.value = EngineState.Transferring(tier, peerName, fileName, "SEND", 0, total, 0)

                // 128 KB buffer — sweet spot for mobile TCP
                val out = BufferedOutputStream(sock.getOutputStream(), 131_072)
                out.write("FILE $fileName $total\n".toByteArray())

                val buf = ByteArray(131_072)
                var sent = 0L; var tLast = System.currentTimeMillis(); var bLast = 0L

                FileInputStream(file).use { fis ->
                    var n: Int
                    while (fis.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        sent += n
                        val now = System.currentTimeMillis()
                        if (now - tLast >= 200) {
                            val bps = (sent - bLast) * 1000L / (now - tLast).coerceAtLeast(1)
                            tLast = now; bLast = sent
                            withContext(Dispatchers.Main) { onProgress(sent, total, bps) }
                            _state.value = EngineState.Transferring(tier, peerName, fileName, "SEND", sent, total, bps)
                        }
                    }
                }
                out.flush()
                withContext(Dispatchers.Main) { onDone() }
                _state.value = EngineState.Done
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Send failed") }
                _state.value = EngineState.Error(e.message ?: "Send failed", retryable = false, kind = "TRANSFER")
            }
        }
    }

    // ── File receive ──────────────────────────────────────────────────────────

    fun receiveFile(
        saveDir: File,
        onProgress: (received: Long, total: Long, bps: Long, fileName: String) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        val sock = activeSocket ?: run { onError("Not connected"); return }
        val tier = currentTier()

        scope.launch(Dispatchers.IO) {
            try {
                val din = BufferedInputStream(sock.getInputStream(), 131_072)

                // Read header line: "FILE <name> <size>\n"
                val header = StringBuilder()
                var b: Int
                while (din.read().also { b = it } != -1 && b.toChar() != '\n') header.append(b.toChar())
                val parts = header.toString().trim().split(" ")
                if (parts.size < 3 || parts[0] != "FILE") { onError("Bad protocol header"); return@launch }

                val fileName = parts[1]
                val total    = parts[2].toLongOrNull() ?: 0L

                saveDir.mkdirs()
                val outFile = File(saveDir, fileName)
                val buf = ByteArray(131_072)
                var received = 0L; var tLast = System.currentTimeMillis(); var bLast = 0L

                FileOutputStream(outFile).use { fos ->
                    while (received < total) {
                        val toRead = minOf(buf.size.toLong(), total - received).toInt()
                        val n = din.read(buf, 0, toRead)
                        if (n == -1) break
                        fos.write(buf, 0, n)
                        received += n
                        val now = System.currentTimeMillis()
                        if (now - tLast >= 200) {
                            val bps = (received - bLast) * 1000L / (now - tLast).coerceAtLeast(1)
                            tLast = now; bLast = received
                            withContext(Dispatchers.Main) { onProgress(received, total, bps, fileName) }
                            _state.value = EngineState.Transferring(tier, peerName, fileName, "RECEIVE", received, total, bps)
                        }
                    }
                }
                withContext(Dispatchers.Main) { onDone(outFile) }
                _state.value = EngineState.Done
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Receive failed") }
                _state.value = EngineState.Error(e.message ?: "Receive failed", retryable = false, kind = "TRANSFER")
            }
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun disconnect() {
        activeSocket?.closeQuietly()
        activeSocket = null
        serverSocket?.closeQuietly()
        serverSocket = null

        p2pReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        p2pReceiver = null
        p2pChannel?.let { ch ->
            try { p2pManager?.removeGroup(ch, null) } catch (_: Exception) {}
        }
        p2pManager = null
        p2pChannel = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hotspotReservation?.close()
        }
        hotspotReservation = null

        _state.value = EngineState.Idle
    }

    fun release() {
        disconnect()
        scope.cancel()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun currentTier(): TransportTier {
        val ip = activeSocket?.inetAddress?.hostAddress ?: ""
        return if (ip.startsWith("192.168.49")) TransportTier.WIFI_DIRECT else TransportTier.HOTSPOT
    }

    private fun Socket.closeQuietly()       = try { close() } catch (_: Exception) {}
    private fun ServerSocket.closeQuietly() = try { close() } catch (_: Exception) {}
}
