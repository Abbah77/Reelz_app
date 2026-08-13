package com.axio.reelz.transfer

// ─────────────────────────────────────────────────────────────────────────────
//  P2pEngine — three-tier wireless negotiation
//
//  Priority:
//    1. Wi-Fi Direct (WifiP2pManager) — fastest, no AP needed, ~250 Mbps
//    2. Local Wi-Fi  (NSD + TCP)       — both on same LAN/AP, ~150 Mbps
//    3. Hotspot      (WifiManager)     — one device creates AP, other joins
//
//  QR payload format:
//    reelzbeam://<sessionId>|<deviceName>|<caps>|<localIp>|<port>
//
//  caps is a bitmask: bit0=wifiDirect, bit1=localWifi, bit2=hotspot
//
//  Flow:
//    Sender   → advertise all 3 tiers → generate QR
//    Receiver → scan QR → read caps → intersect with own caps
//             → attempt tier-1 first, fallback chain on failure
//    Both     → once transport established → bidirectional TCP socket
// ─────────────────────────────────────────────────────────────────────────────

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "P2pEngine"
private const val NSD_SERVICE_TYPE = "_reelzbeam._tcp."
private const val NSD_SERVICE_NAME = "ReelzBeam"
private const val DEFAULT_PORT     = 49800

// ─── Transport medium ─────────────────────────────────────────────────────────

enum class TransportTier {
    WIFI_DIRECT,   // tier-1: fastest
    LOCAL_WIFI,    // tier-2: same LAN
    HOTSPOT,       // tier-3: one device is AP
}

// ─── Engine state machine ─────────────────────────────────────────────────────

sealed class EngineState {
    object Idle                                           : EngineState()
    object Advertising                                    : EngineState()
    data class QrReady(
        val qrPayload: String,
        val sessionId: String,
        val tier: TransportTier?,     // null until receiver connects
    )                                                     : EngineState()
    object Negotiating                                    : EngineState()
    data class Connected(
        val tier: TransportTier,
        val peerName: String,
        val isHost: Boolean,
        val socket: Socket,
    )                                                     : EngineState()
    data class Transferring(
        val tier: TransportTier,
        val peerName: String,
        val fileName: String,
        val direction: String,       // "SEND"|"RECEIVE"
        val transferredBytes: Long,
        val totalBytes: Long,
        val speedBps: Long,
    )                                                     : EngineState()
    object Done                                           : EngineState()
    data class Error(val msg: String, val retryable: Boolean = true) : EngineState()
}

// ─── QR payload ───────────────────────────────────────────────────────────────

data class QrPayload(
    val sessionId: String,
    val deviceName: String,
    val caps: Int,           // bitmask: 1=wifiDirect, 2=localWifi, 4=hotspot
    val localIp: String,
    val port: Int,
) {
    fun encode() = "reelzbeam://$sessionId|$deviceName|$caps|$localIp|$port"

    companion object {
        fun decode(raw: String): QrPayload? = try {
            val stripped = raw.removePrefix("reelzbeam://")
            val parts = stripped.split("|")
            if (parts.size < 5) null
            else QrPayload(
                sessionId  = parts[0],
                deviceName = parts[1],
                caps       = parts[2].toInt(),
                localIp    = parts[3],
                port       = parts[4].toInt(),
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

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var nsdManager: NsdManager? = null
    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var wifiDirectReceiver: BroadcastReceiver? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var hotspotReservation: Any? = null  // WifiManager.LocalOnlyHotspotReservation
    private var hotspotSsid: String? = null
    private var hotspotPassword: String? = null

    private val transferEvents = Channel<TransferEvent>(Channel.UNLIMITED)
    private var currentSessionId: String = ""
    private var peerName: String = ""

    sealed class TransferEvent {
        data class Progress(val bytes: Long, val total: Long, val speed: Long) : TransferEvent()
        data class Finished(val fileName: String, val bytes: Long)             : TransferEvent()
        data class Failed(val reason: String)                                  : TransferEvent()
    }

    // ── Capability detection ──────────────────────────────────────────────────

    fun detectCaps(): Int {
        var caps = 0
        // Bit 0: Wi-Fi Direct
        if (ctx.packageManager.hasSystemFeature("android.hardware.wifi.direct")) caps = caps or 1
        // Bit 1: Local Wi-Fi (connected to an AP)
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm?.isWifiEnabled == true && getLocalIp().isNotEmpty()) caps = caps or 2
        // Bit 2: Hotspot — we can always try to create one
        caps = caps or 4
        return caps
    }

    fun getLocalIp(): String = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.hostAddress.startsWith("169.") }
            ?.hostAddress ?: ""
    } catch (_: Exception) { "" }

    // ── Sender: build QR payload + start server socket ────────────────────────

    fun prepareAsSender(deviceName: String): String {
        disconnect()
        currentSessionId = UUID.randomUUID().toString().take(8).uppercase()
        peerName = deviceName

        val caps = detectCaps()
        val localIp = getLocalIp()
        val port = startServerSocket()

        val payload = QrPayload(
            sessionId  = currentSessionId,
            deviceName = deviceName,
            caps       = caps,
            localIp    = localIp,
            port       = port,
        ).encode()

        _state.value = EngineState.QrReady(
            qrPayload = payload,
            sessionId = currentSessionId,
            tier = null,
        )

        // Advertise via NSD so receiver on same LAN can discover without QR
        scope.launch { advertiseNsd(port) }

        // Start accepting connections in background
        scope.launch { acceptLoop(caps) }

        return payload
    }

    // ── Receiver: parse QR → negotiate tier → connect ─────────────────────────

    fun connectFromQr(rawQr: String, myDeviceName: String) {
        val remote = QrPayload.decode(rawQr) ?: run {
            _state.value = EngineState.Error("Invalid QR code — not a Reelz Beam code.", retryable = true)
            return
        }
        peerName = remote.deviceName
        currentSessionId = remote.sessionId
        _state.value = EngineState.Negotiating

        val myCaps  = detectCaps()
        val shared  = myCaps and remote.caps  // bits both devices support

        scope.launch {
            val tiers = buildFallbackChain(shared)
            var connected = false
            for (tier in tiers) {
                Log.d(TAG, "Trying tier: $tier")
                connected = tryConnect(tier, remote, myDeviceName)
                if (connected) break
                delay(300) // brief gap before next tier
            }
            if (!connected) {
                _state.value = EngineState.Error(
                    "Could not connect via Wi-Fi Direct, local Wi-Fi, or hotspot.\n" +
                    "Check permissions and try again.",
                    retryable = true,
                )
            }
        }
    }

    // ── Tier fallback chain ───────────────────────────────────────────────────

    private fun buildFallbackChain(sharedCaps: Int): List<TransportTier> {
        val chain = mutableListOf<TransportTier>()
        if (sharedCaps and 1 != 0) chain += TransportTier.WIFI_DIRECT
        if (sharedCaps and 2 != 0) chain += TransportTier.LOCAL_WIFI
        if (sharedCaps and 4 != 0) chain += TransportTier.HOTSPOT
        // If nothing shared (shouldn't happen), try everything
        if (chain.isEmpty()) chain += listOf(
            TransportTier.LOCAL_WIFI,
            TransportTier.WIFI_DIRECT,
            TransportTier.HOTSPOT,
        )
        return chain
    }

    private suspend fun tryConnect(
        tier: TransportTier,
        remote: QrPayload,
        myName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext when (tier) {
            TransportTier.LOCAL_WIFI    -> tryLocalWifi(remote, myName)
            TransportTier.WIFI_DIRECT   -> tryWifiDirect(remote, myName)
            TransportTier.HOTSPOT       -> tryHotspot(remote, myName)
        }
    }

    // ── Tier 1: Wi-Fi Direct ─────────────────────────────────────────────────

    private suspend fun tryWifiDirect(remote: QrPayload, myName: String): Boolean {
        return try {
            val p2pMgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return false
            val channel = p2pMgr.initialize(ctx, Looper.getMainLooper(), null)
            wifiP2pManager = p2pMgr
            wifiP2pChannel = channel

            // Register broadcast receiver to monitor P2P state
            val peerFoundDeferred = CompletableDeferred<WifiP2pDevice?>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                            p2pMgr.requestPeers(channel) { peers ->
                                val device = peers.deviceList.firstOrNull()
                                if (device != null) peerFoundDeferred.complete(device)
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            ctx.registerReceiver(receiver, filter)
            wifiDirectReceiver = receiver

            // Discover peers
            val discoverResult = CompletableDeferred<Boolean>()
            p2pMgr.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { discoverResult.complete(true) }
                override fun onFailure(reason: Int) { discoverResult.complete(false) }
            })

            if (!discoverResult.await()) {
                ctx.unregisterReceiver(receiver)
                return false
            }

            // Wait up to 5s for a Reelz device
            val peer = withTimeoutOrNull(5_000) { peerFoundDeferred.await() }
            ctx.unregisterReceiver(receiver)
            wifiDirectReceiver = null

            if (peer == null) return false

            // Connect to peer
            val config = WifiP2pConfig().apply { deviceAddress = peer.deviceAddress }
            val connectResult = CompletableDeferred<Boolean>()
            p2pMgr.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { connectResult.complete(true) }
                override fun onFailure(reason: Int) { connectResult.complete(false) }
            })

            if (!connectResult.await()) return false

            // Get group info to determine GO IP
            val groupDeferred = CompletableDeferred<String?>()
            val connReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                        p2pMgr.requestGroupInfo(channel) { group ->
                            val ownerAddr = group?.owner?.deviceAddress
                            groupDeferred.complete(ownerAddr)
                        }
                    }
                }
            }
            val connFilter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            ctx.registerReceiver(connReceiver, connFilter)

            val groupOwnerIp = withTimeoutOrNull(5_000) { groupDeferred.await() }
            ctx.unregisterReceiver(connReceiver)

            // Try TCP to group owner; if GO is us, wait for accept; if GO is peer, connect
            val ip = remote.localIp.ifEmpty { groupOwnerIp } ?: return false
            return establishTcpConnection(ip, remote.port, myName, TransportTier.WIFI_DIRECT)
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi Direct failed: ${e.message}")
            false
        }
    }

    // ── Tier 2: Local Wi-Fi (NSD + TCP) ─────────────────────────────────────

    private suspend fun tryLocalWifi(remote: QrPayload, myName: String): Boolean {
        if (remote.localIp.isEmpty()) return false
        return try {
            establishTcpConnection(remote.localIp, remote.port, myName, TransportTier.LOCAL_WIFI)
        } catch (e: Exception) {
            Log.w(TAG, "Local Wi-Fi failed: ${e.message}")
            false
        }
    }

    // ── Tier 3: Hotspot ──────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private suspend fun tryHotspot(remote: QrPayload, myName: String): Boolean {
        // If sender already has a hotspot IP embedded in QR, try it directly
        if (remote.localIp.isNotEmpty()) {
            val direct = tryLocalWifi(remote, myName)
            if (direct) return true
        }

        // Otherwise create our own hotspot so sender can join us
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return false

            val hotspotReady = CompletableDeferred<String>() // resolves to our hotspot IP

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        hotspotReservation = reservation
                        val ssid = reservation.wifiConfiguration?.SSID
                            ?: reservation.softApConfiguration?.ssid ?: ""
                        hotspotSsid = ssid
                        hotspotPassword = reservation.wifiConfiguration?.preSharedKey
                            ?: reservation.softApConfiguration?.passphrase ?: ""
                        // Our IP on hotspot interface is typically 192.168.49.1
                        hotspotReady.complete("192.168.49.1")
                    }
                    override fun onFailed(reason: Int) { hotspotReady.completeExceptionally(Exception("Hotspot failed: $reason")) }
                }, null)
            } else {
                hotspotReady.complete(getLocalIp())
            }

            val hotspotIp = withTimeoutOrNull(8_000) {
                try { hotspotReady.await() } catch (_: Exception) { null }
            } ?: return false

            // Start server on hotspot interface and wait for peer to connect
            val port = startServerSocket(bindAddr = hotspotIp)
            delay(1_000) // let peer discover our hotspot SSID via side-channel (future: NFC/BT)

            // Block waiting for the peer connection (they'll scan and join via retry)
            val socket = withTimeoutOrNull(30_000) { serverSocket?.accept() } ?: return false
            val peerId = socket.inetAddress.hostAddress ?: ""
            onSocketConnected(socket, TransportTier.HOTSPOT, isHost = true)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Hotspot failed: ${e.message}")
            false
        }
    }

    // ── TCP helpers ───────────────────────────────────────────────────────────

    private fun startServerSocket(bindAddr: String? = null): Int {
        serverSocket?.closeQuietly()
        val ss = if (bindAddr != null) {
            ServerSocket(DEFAULT_PORT, 5, InetAddress.getByName(bindAddr))
        } else {
            ServerSocket(0)  // OS picks a free port
        }
        serverSocket = ss
        return ss.localPort
    }

    private suspend fun acceptLoop(senderCaps: Int) = withContext(Dispatchers.IO) {
        try {
            val ss = serverSocket ?: return@withContext
            val socket = ss.accept()
            val tier = guessTierFromSocket(socket, senderCaps)
            onSocketConnected(socket, tier, isHost = true)
        } catch (e: Exception) {
            if (_state.value !is EngineState.Idle) {
                Log.w(TAG, "Accept loop ended: ${e.message}")
            }
        }
    }

    private fun guessTierFromSocket(socket: Socket, caps: Int): TransportTier {
        val ip = socket.inetAddress.hostAddress ?: ""
        return when {
            ip.startsWith("192.168.49") -> TransportTier.HOTSPOT
            ip.startsWith("192.168.")   -> if (caps and 1 != 0) TransportTier.WIFI_DIRECT else TransportTier.LOCAL_WIFI
            else                        -> TransportTier.LOCAL_WIFI
        }
    }

    private suspend fun establishTcpConnection(
        ip: String, port: Int, myName: String, tier: TransportTier,
    ): Boolean = withContext(Dispatchers.IO) {
        for (attempt in 1..3) {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(ip, port), 3_000)
                sock.soTimeout = 0  // no read timeout during transfer

                // Handshake: send our name, read peer name
                val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                writer.write("HELLO $myName\n"); writer.flush()
                val hello = reader.readLine() ?: ""
                peerName = if (hello.startsWith("HELLO ")) hello.removePrefix("HELLO ") else "Unknown"

                onSocketConnected(sock, tier, isHost = false)
                return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "TCP attempt $attempt to $ip:$port failed: ${e.message}")
                delay(500L * attempt)
            }
        }
        false
    }

    private fun onSocketConnected(socket: Socket, tier: TransportTier, isHost: Boolean) {
        activeSocket = socket
        // Complete handshake on host side
        if (isHost) {
            try {
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val hello = reader.readLine() ?: ""
                peerName = if (hello.startsWith("HELLO ")) hello.removePrefix("HELLO ") else "Unknown"
                writer.write("HELLO ${deviceName()}\n"); writer.flush()
            } catch (_: Exception) {}
        }
        _state.value = EngineState.Connected(
            tier     = tier,
            peerName = peerName,
            isHost   = isHost,
            socket   = socket,
        )
    }

    // ── File transfer ─────────────────────────────────────────────────────────

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

        scope.launch(Dispatchers.IO) {
            try {
                val total = file.length()
                _state.value = EngineState.Transferring(
                    tier = tierFromSocket(sock), peerName = peerName,
                    fileName = fileName, direction = "SEND",
                    transferredBytes = 0, totalBytes = total, speedBps = 0,
                )

                val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 131_072))
                // Header: "FILE <name> <size>\n"
                val header = "FILE $fileName $total\n"
                out.write(header.toByteArray())

                val buf = ByteArray(131_072)
                var sent = 0L
                var lastTime = System.currentTimeMillis()
                var lastSent = 0L

                FileInputStream(file).use { fis ->
                    var n: Int
                    while (fis.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        sent += n
                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 200) {
                            val bps = ((sent - lastSent) * 1000L) / (now - lastTime).coerceAtLeast(1)
                            lastTime = now; lastSent = sent
                            withContext(Dispatchers.Main) { onProgress(sent, total, bps) }
                            _state.value = EngineState.Transferring(
                                tier = tierFromSocket(sock), peerName = peerName,
                                fileName = fileName, direction = "SEND",
                                transferredBytes = sent, totalBytes = total, speedBps = bps,
                            )
                        }
                    }
                }
                out.flush()
                withContext(Dispatchers.Main) { onDone() }
                _state.value = EngineState.Done
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Send failed") }
                _state.value = EngineState.Error(e.message ?: "Send failed", retryable = false)
            }
        }
    }

    fun receiveFile(
        saveDir: File,
        onProgress: (received: Long, total: Long, bps: Long, fileName: String) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        val sock = activeSocket ?: run { onError("Not connected"); return }
        scope.launch(Dispatchers.IO) {
            try {
                val din = DataInputStream(BufferedInputStream(sock.getInputStream(), 131_072))
                // Read header line
                val headerLine = StringBuilder()
                var b: Int
                while (din.read().also { b = it } != -1 && b.toChar() != '\n') {
                    headerLine.append(b.toChar())
                }
                val parts = headerLine.toString().trim().split(" ")
                if (parts.size < 3 || parts[0] != "FILE") { onError("Bad protocol"); return@launch }
                val fileName = parts[1]
                val total    = parts[2].toLongOrNull() ?: 0L

                saveDir.mkdirs()
                val outFile = File(saveDir, fileName)
                val buf = ByteArray(131_072)
                var received = 0L
                var lastTime = System.currentTimeMillis()
                var lastRecv = 0L

                FileOutputStream(outFile).use { fos ->
                    while (received < total) {
                        val toRead = minOf(buf.size.toLong(), total - received).toInt()
                        val n = din.read(buf, 0, toRead)
                        if (n == -1) break
                        fos.write(buf, 0, n)
                        received += n
                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 200) {
                            val bps = ((received - lastRecv) * 1000L) / (now - lastTime).coerceAtLeast(1)
                            lastTime = now; lastRecv = received
                            withContext(Dispatchers.Main) { onProgress(received, total, bps, fileName) }
                            _state.value = EngineState.Transferring(
                                tier = tierFromSocket(sock), peerName = peerName,
                                fileName = fileName, direction = "RECEIVE",
                                transferredBytes = received, totalBytes = total, speedBps = bps,
                            )
                        }
                    }
                }
                withContext(Dispatchers.Main) { onDone(outFile) }
                _state.value = EngineState.Done
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Receive failed") }
                _state.value = EngineState.Error(e.message ?: "Receive failed", retryable = false)
            }
        }
    }

    // ── NSD (Local Wi-Fi discovery) ───────────────────────────────────────────

    private fun advertiseNsd(port: Int) {
        try {
            val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = nsd

            val info = NsdServiceInfo().apply {
                serviceName = "$NSD_SERVICE_NAME-$currentSessionId"
                serviceType = NSD_SERVICE_TYPE
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.d(TAG, "NSD registered: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "NSD registration failed: $code")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
            nsdRegistrationListener = listener
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD advertise failed: ${e.message}")
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun tierFromSocket(sock: Socket): TransportTier {
        val ip = sock.inetAddress.hostAddress ?: ""
        return when {
            ip.startsWith("192.168.49") -> TransportTier.HOTSPOT
            ip.startsWith("192.168.")   -> TransportTier.LOCAL_WIFI
            else                        -> TransportTier.LOCAL_WIFI
        }
    }

    private fun deviceName() = "${Build.MANUFACTURER} ${Build.MODEL}".take(24)

    private fun Socket.closeQuietly() = try { close() } catch (_: Exception) {}
    private fun ServerSocket.closeQuietly() = try { close() } catch (_: Exception) {}

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun disconnect() {
        activeSocket?.closeQuietly()
        activeSocket = null
        serverSocket?.closeQuietly()
        serverSocket = null

        // NSD
        nsdRegistrationListener?.let {
            try { nsdManager?.unregisterService(it) } catch (_: Exception) {}
        }
        nsdRegistrationListener = null

        // Wi-Fi Direct
        wifiDirectReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Exception) {}
        }
        wifiDirectReceiver = null
        wifiP2pChannel?.let { ch ->
            try { wifiP2pManager?.removeGroup(ch, null) } catch (_: Exception) {}
        }
        wifiP2pManager = null
        wifiP2pChannel = null

        // Hotspot
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (hotspotReservation as? WifiManager.LocalOnlyHotspotReservation)?.close()
        }
        hotspotReservation = null
        hotspotSsid = null
        hotspotPassword = null

        _state.value = EngineState.Idle
    }

    fun release() {
        disconnect()
        scope.cancel()
    }
}
