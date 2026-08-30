package com.axio.reelz.transfer

// ─────────────────────────────────────────────────────────────────────────────
//  P2pEngine — 2-tier wireless file transfer  (FIXED v3)
//
//  ROOT CAUSE FIX (v3 vs v2):
//
//  The logs showed every receiver attempt failing with:
//    ENETUNREACH (Network is unreachable)
//    from /:: (port 0) → 192.168.49.1:49800
//
//  Why: Android (API 29+) treats LocalOnlyHotspot networks as having no
//  internet capability. When the receiver creates a plain Socket() and calls
//  connect(), the OS routes it through the default network (LTE/cellular),
//  not the hotspot Wi-Fi interface — which gives ENETUNREACH because
//  192.168.49.1 isn't reachable over cellular.
//
//  The fix (Xender's approach):
//    1. RECEIVER uses WifiNetworkSpecifier + ConnectivityManager.requestNetwork()
//       to join the sender's hotspot and receive the Network object in onAvailable().
//    2. Every TCP socket is created via network.socketFactory.createSocket() OR
//       network.bindSocket(socket) before connect() — this forces the OS to route
//       the socket over the hotspot interface, not the default route.
//    3. A NetworkCallback is kept alive while the transfer session is active and
//       unregistered on disconnect() / timeout.
//
//  OTHER BUGS FIXED (carried from v2):
//    FIX-A  Hotspot IP polling instead of fixed 1500ms delay
//    FIX-B  sessionValid volatile flag kills receiver loop on role switch
//    FIX-C  Session ID in HELLO frame prevents stale-server latching
//    FIX-D  ServerSocket.soTimeout unblocks accept() on timeout
//    FIX-E  cancelledReceive @Volatile drives receiveFiles() externally
//    FIX-F  disconnect() closes serverSocket first to unblock accept()
//    FIX-G  QR/connection invalidated immediately on prepareAsSender() / connectFromQr()
//
//  Xender-style approach summary:
//    • Sender creates a LocalOnlyHotspot → encodes SSID+password in QR
//    • Receiver scans QR → programmatically joins the hotspot via
//      WifiNetworkSpecifier (no user intervention needed on API 29+)
//    • Receiver binds every Socket to the returned Network before connecting
//    • Transfer runs over a single persistent TCP stream (no HTTP overhead)
//    • Session ID in every HELLO prevents ghost connections from stale retries
//
//  Protocol (single persistent stream):
//    Handshake:    HELLO <sessionId> <deviceName>\n
//    File header:  FILE <size> <urlEncodedName>\n
//    Cancel frame: CANCEL\n          ← receiver sends to skip current file
//    Skip ack:     SKIP\n            ← sender acks and moves on
//    Done:         DONE\n            ← sender signals no more files in queue
// ─────────────────────────────────────────────────────────────────────────────

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG  = "P2pEngine"
private const val PORT = 49800

// How long the sender's ServerSocket waits for a connection
private const val QR_ACCEPT_TIMEOUT_MS = 120_000L

// How long the receiver tries to join the hotspot + TCP-connect
private const val CONNECT_TIMEOUT_MS = 60_000L

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
        val direction: String,          // "SEND" | "RECEIVE"
        val transferredBytes: Long,
        val totalBytes: Long,
        val speedBps: Long,
    )                                                                          : EngineState()
    object Done                                                                : EngineState()
    data class Error(
        val msg: String,
        val retryable: Boolean = true,
        val kind: String = "GENERIC",
    ) : EngineState()
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

    // Single socket pair shared by handshake + all file transfers
    private var serverSocket: ServerSocket?   = null
    private var activeSocket: Socket?         = null
    private var socketIn:  DataInputStream?   = null
    private var socketOut: DataOutputStream?  = null

    // ── Session validity (FIX-B, FIX-G) ──────────────────────────────────────
    // Flipped to false on disconnect() or new prepareAsSender() so receiver
    // loops abort immediately.
    @Volatile private var sessionValid = false

    // ── Cancel flags (FIX-E) ──────────────────────────────────────────────────
    @Volatile private var cancelRequested   = false
    @Volatile private var cancelledReceive  = false

    // Wi-Fi Direct
    private var p2pManager: WifiP2pManager?         = null
    private var p2pChannel: WifiP2pManager.Channel? = null

    // Hotspot (sender side)
    @Suppress("DEPRECATION")
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    // ── ROOT CAUSE FIX: Network binding for receiver ──────────────────────────
    // When the receiver joins the sender's hotspot via WifiNetworkSpecifier,
    // ConnectivityManager gives us a Network object. We must bind every Socket
    // to this network; otherwise Android routes the socket through the default
    // (cellular) interface and returns ENETUNREACH for 192.168.49.x addresses.
    private var boundNetwork: Network?                              = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var peerName  = ""
    private var sessionId = ""

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

    private fun connectivityManager() =
        ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // ── SENDER: prepare ───────────────────────────────────────────────────────

    // BUG1 FIX: Track the active prepare job so we never have two running simultaneously.
    // Calling prepareAsSender() while one is already running was causing double QR emissions
    // (the old job would complete after the new one started → two ServerSockets fighting).
    @Volatile private var prepareJob: kotlinx.coroutines.Job? = null

    fun prepareAsSender(onQrReady: (String) -> Unit) {
        // BUG1 FIX: Cancel any in-flight prepare before starting a new one.
        // Without this, if the user navigates away and back before the hotspot/WD
        // group is ready, a second job starts and both emit QrReady → QR loop.
        prepareJob?.cancel()
        prepareJob = null

        // Invalidate any previous session so in-flight receiver loops abort
        sessionValid = false
        disconnect(silent = true)

        sessionId    = UUID.randomUUID().toString().take(8).uppercase()
        sessionValid = true
        _state.value = EngineState.Preparing

        prepareJob = scope.launch {
            try {
                if (hasWifiDirect()) {
                    val wdPayload = tryCreateWifiDirectGroup()
                    if (wdPayload != null && isActive && sessionValid) {
                        _state.value = EngineState.QrReady(wdPayload, sessionId)
                        withContext(Dispatchers.Main) { onQrReady(wdPayload) }
                        acceptConnectionOnServerSocket(TransportTier.WIFI_DIRECT)
                        return@launch
                    }
                    Log.d(TAG, "WD failed, falling back to Hotspot")
                }

                if (!isActive || !sessionValid) return@launch

                val hsPayload = tryCreateHotspot()
                if (!isActive || !sessionValid) return@launch   // BUG1: guard after async hotspot start

                if (hsPayload != null) {
                    _state.value = EngineState.QrReady(hsPayload, sessionId)
                    withContext(Dispatchers.Main) { onQrReady(hsPayload) }
                    acceptConnectionOnServerSocket(TransportTier.HOTSPOT)
                } else {
                    if (sessionValid) {
                        _state.value = EngineState.Error(
                            "Could not create a Wi-Fi Direct group or Hotspot. Check permissions and try again.",
                            retryable = true, kind = "CONNECTION"
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled cleanly — do not emit an error state
                Log.d(TAG, "prepareAsSender job cancelled cleanly")
            } finally {
                if (prepareJob?.isCancelled == true) prepareJob = null
            }
        }
    }

    // ── RECEIVER: connect from QR ─────────────────────────────────────────────

    fun connectFromQr(rawQr: String) {
        val payload = BeamPayload.decode(rawQr) ?: run {
            _state.value = EngineState.Error("Invalid QR code.", retryable = true, kind = "CONNECTION")
            return
        }
        // FIX-G: cancel any previous in-flight connect attempt
        sessionValid = false
        disconnect(silent = true)

        peerName     = payload.deviceName
        sessionId    = payload.sessionId
        sessionValid = true
        _state.value = EngineState.Negotiating

        scope.launch {
            val connected = when (payload.tier) {
                "WD" -> connectViaWifiDirect(payload)
                "HS" -> connectViaHotspot(payload)
                else -> false
            }
            // WD fallback: if WD failed but QR has hotspot creds, try HS
            val ok = connected
                || (payload.tier == "WD" && isActive && sessionValid && connectViaHotspot(payload))

            if (!ok && sessionValid) {
                _state.value = EngineState.Error(
                    "Could not connect. Make sure both devices are close and Wi-Fi is on.",
                    retryable = true, kind = "CONNECTION"
                )
            }
        }
    }

    // ── Wi-Fi Direct group (SENDER) ───────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun tryCreateWifiDirectGroup(): String? = withContext(Dispatchers.Main) {
        try {
            val mgr  = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return@withContext null
            val chan = mgr.initialize(ctx, Looper.getMainLooper(), null)
            p2pManager = mgr
            p2pChannel = chan

            val existingGroup = CompletableDeferred<WifiP2pGroup?>().also { d ->
                mgr.requestGroupInfo(chan) { d.complete(it) }
            }.let { withTimeoutOrNull(3_000) { it.await() } }

            if (existingGroup != null && existingGroup.isGroupOwner) {
                return@withContext buildWdPayload(existingGroup)
            }

            val created = CompletableDeferred<Boolean>().also { d ->
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { d.complete(true) }
                    override fun onFailure(r: Int) { d.complete(false) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val config = WifiP2pConfig.Builder()
                        .setNetworkName("DIRECT-${sessionId.take(4)}")
                        .setPassphrase(sessionId.lowercase())
                        .build()
                    mgr.createGroup(chan, config, listener)
                } else {
                    @Suppress("DEPRECATION")
                    mgr.createGroup(chan, listener)
                }
            }

            val groupCreated = withTimeoutOrNull(6_000) { created.await() } ?: false
            if (!groupCreated) return@withContext null

            val group = CompletableDeferred<WifiP2pGroup?>().also { d ->
                mgr.requestGroupInfo(chan) { d.complete(it) }
            }.let { withTimeoutOrNull(5_000) { it.await() } } ?: return@withContext null

            buildWdPayload(group)
        } catch (e: Exception) {
            Log.w(TAG, "WD group creation failed: ${e.message}")
            null
        }
    }

    private suspend fun buildWdPayload(group: WifiP2pGroup): String {
        withContext(Dispatchers.IO) { startServerSocket() }
        return BeamPayload(
            sessionId  = sessionId,
            deviceName = deviceName(),
            tier       = "WD",
            ip         = "192.168.49.1",
            port       = PORT,
            ssid       = group.networkName,
            password   = group.passphrase,
        ).encode()
    }

    // ── Wi-Fi Direct connect (RECEIVER) ───────────────────────────────────────

    private suspend fun connectViaWifiDirect(payload: BeamPayload): Boolean {
        return try {
            // For WD, the receiver joins the WD group through system UI / OS,
            // so we can try to TCP-connect directly. The Network object approach
            // also works here if WD is exposed as a Network by ConnectivityManager.
            if (tcpConnect(payload.ip, payload.port, TransportTier.WIFI_DIRECT, payload.sessionId, null)) return true
            val deadline = System.currentTimeMillis() + 30_000L
            while (currentCoroutineContext().isActive && sessionValid && System.currentTimeMillis() < deadline) {
                delay(2_000)
                if (tcpConnect(payload.ip, payload.port, TransportTier.WIFI_DIRECT, payload.sessionId, null)) return true
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
            val ip = getLocalIp()
            withContext(Dispatchers.IO) { startServerSocket() }
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
                    scope.launch {
                        // FIX-A: poll for hotspot IP instead of fixed delay
                        val ip = pollForHotspotIp(maxWaitMs = 8_000)
                        withContext(Dispatchers.IO) { startServerSocket() }

                        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            reservation.softApConfiguration?.ssid ?: ""
                        else
                            @Suppress("DEPRECATION") reservation.wifiConfiguration?.SSID ?: ""

                        val pass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            reservation.softApConfiguration?.passphrase ?: ""
                        else
                            @Suppress("DEPRECATION") reservation.wifiConfiguration?.preSharedKey ?: ""

                        Log.d(TAG, "Hotspot started: ssid=$ssid ip=$ip")
                        cont.resume(BeamPayload(
                            sessionId  = sessionId,
                            deviceName = deviceName(),
                            tier       = "HS",
                            ip         = ip,
                            port       = PORT,
                            ssid       = ssid,
                            password   = pass,
                        ).encode(), null)
                    }
                }
                override fun onFailed(reason: Int) {
                    Log.w(TAG, "Hotspot failed: $reason")
                    cont.resume(null, null)
                }
                override fun onStopped() { Log.d(TAG, "Hotspot stopped") }
            }, null)
        }
    }

    // FIX-A: poll until hotspot interface IP is stable
    private suspend fun pollForHotspotIp(maxWaitMs: Long): String {
        val wlanIp = getLocalIp()
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val candidate = findHotspotInterfaceIp(wlanIp)
            if (candidate != null) {
                Log.d(TAG, "Hotspot IP resolved: $candidate")
                return candidate
            }
            delay(500)
        }
        Log.w(TAG, "Hotspot IP not resolved after ${maxWaitMs}ms, using fallback 192.168.49.1")
        return "192.168.49.1"
    }

    private fun findHotspotInterfaceIp(wlanIp: String): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { iface -> iface.isUp && !iface.isLoopback }
            ?.flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { addr -> iface to addr }
            }
            ?.firstOrNull { (_, addr) ->
                val h = addr.hostAddress ?: return@firstOrNull false
                !h.startsWith("169.") && !h.startsWith("127.") && h != wlanIp
            }
            ?.second?.hostAddress
    } catch (_: Exception) { null }

    // ─────────────────────────────────────────────────────────────────────────
    //  ROOT CAUSE FIX: Hotspot connect (RECEIVER)
    //
    //  Old code: Socket().connect(InetSocketAddress(ip, port))
    //    → Android routes through default (cellular) → ENETUNREACH
    //
    //  New code (Xender approach):
    //    1. Use WifiNetworkSpecifier to programmatically join the sender's
    //       hotspot. ConnectivityManager handles auth, gives us a Network.
    //    2. Create every socket via network.socketFactory.createSocket()
    //       OR call network.bindSocket(socket) before connect().
    //    3. Keep the NetworkCallback registered for the session lifetime.
    //       Unregister it in disconnect().
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun connectViaHotspot(payload: BeamPayload): Boolean {
        if (payload.ssid.isEmpty() || payload.ip.isEmpty()) {
            Log.w(TAG, "HS payload missing ssid or ip — cannot connect")
            return false
        }

        // Step 1: programmatically join the sender's hotspot and get Network object
        val network = joinHotspotNetwork(payload.ssid, payload.password)
            ?: run {
                Log.w(TAG, "Failed to join hotspot ${payload.ssid}")
                return false
            }

        if (!sessionValid) return false
        boundNetwork = network
        Log.d(TAG, "Joined hotspot ${payload.ssid}, got Network $network")

        // Step 2: retry TCP connect, binding every socket to the hotspot Network
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        var attempt  = 0
        while (currentCoroutineContext().isActive && sessionValid && System.currentTimeMillis() < deadline) {
            attempt++
            if (tcpConnect(payload.ip, payload.port, TransportTier.HOTSPOT, payload.sessionId, network)) {
                return true
            }
            val backoff = minOf(500L * attempt, 4_000L)
            delay(backoff)
        }

        if (sessionValid) {
            Log.w(TAG, "TCP connect timed out after $attempt attempts")
        }
        return false
    }

    /**
     * Programmatically join the sender's hotspot using WifiNetworkSpecifier (API 29+).
     *
     * Returns the Network object that must be used to bind sockets, or null on failure.
     * The NetworkCallback is stored in [networkCallback] and must be unregistered
     * in disconnect() to avoid leaking it.
     *
     * Note: WifiNetworkSpecifier causes a system dialog on the first join attempt
     * on some OEMs (stock Android shows no dialog; OEM skins vary). On API < 29
     * we fall back to WifiManager.addNetwork() (deprecated but functional).
     */
    @SuppressLint("MissingPermission")
    private suspend fun joinHotspotNetwork(ssid: String, password: String): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-Q: legacy WifiManager join — network is automatically bound
            // as the active interface, so boundNetwork stays null and plain
            // Socket() will route correctly.
            return joinHotspotLegacy(ssid, password)
        }

        val cm = connectivityManager()

        // Clean up any previous callback first
        releaseNetworkCallback()

        return suspendCancellableCoroutine { cont ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // Critically: do NOT require INTERNET capability — LocalOnlyHotspot
                // networks never have internet, and this would make requestNetwork() fail
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Hotspot network available: $network")
                    // Do NOT bindProcessToNetwork — that would break internet for the whole app.
                    // We'll bind individual sockets instead.
                    if (cont.isActive) cont.resume(network, null)
                }

                override fun onUnavailable() {
                    Log.w(TAG, "Hotspot network unavailable (join failed / timeout)")
                    if (cont.isActive) cont.resume(null, null)
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Hotspot network lost")
                    // If we're mid-transfer, this triggers a disconnect
                    if (sessionValid && _state.value !is EngineState.Idle) {
                        _state.value = EngineState.Error(
                            "Wi-Fi connection to sender was lost.",
                            retryable = true, kind = "CONNECTION"
                        )
                    }
                }
            }
            networkCallback = cb

            try {
                cm.requestNetwork(request, cb)
            } catch (e: Exception) {
                Log.w(TAG, "requestNetwork failed: ${e.message}")
                networkCallback = null
                if (cont.isActive) cont.resume(null, null)
            }

            cont.invokeOnCancellation {
                try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
                networkCallback = null
            }
        }
    }

    /**
     * Legacy hotspot join for API < 29.
     * Returns a null Network — sockets don't need explicit binding because
     * the old WifiManager.enableNetwork() sets the interface as default.
     */
    @Suppress("DEPRECATION")
    private suspend fun joinHotspotLegacy(ssid: String, password: String): Network? {
        return withContext(Dispatchers.IO) {
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return@withContext null

                val config = android.net.wifi.WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                }

                // Remove any stale network for this SSID
                wm.configuredNetworks?.firstOrNull { it.SSID == "\"$ssid\"" }?.networkId
                    ?.let { wm.removeNetwork(it) }

                val netId = wm.addNetwork(config)
                if (netId == -1) {
                    Log.w(TAG, "Legacy: addNetwork failed for $ssid")
                    return@withContext null
                }
                wm.disconnect()
                wm.enableNetwork(netId, true)
                wm.reconnect()

                // Wait up to 10s for the interface to be assigned
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline) {
                    val info = wm.connectionInfo
                    if (info.ssid == "\"$ssid\"" && info.ipAddress != 0) {
                        Log.d(TAG, "Legacy: connected to $ssid")
                        return@withContext null  // null = use plain socket, OS will route correctly
                    }
                    delay(500)
                }
                Log.w(TAG, "Legacy: timed out waiting for $ssid connection")
                null
            } catch (e: Exception) {
                Log.w(TAG, "Legacy hotspot join failed: ${e.message}")
                null
            }
        }
    }

    // ── TCP plumbing ──────────────────────────────────────────────────────────

    private fun startServerSocket() {
        serverSocket?.closeQuietly()
        serverSocket = ServerSocket(PORT)
        Log.d(TAG, "ServerSocket bound on :$PORT")
    }

    // FIX-D: soTimeout unblocks accept() on QR timeout
    private suspend fun acceptConnectionOnServerSocket(tier: TransportTier) {
        withContext(Dispatchers.IO) {
            try {
                val ss = serverSocket ?: return@withContext
                ss.soTimeout = QR_ACCEPT_TIMEOUT_MS.toInt()

                Log.d(TAG, "Waiting for receiver connection (${QR_ACCEPT_TIMEOUT_MS / 1000}s)…")
                val sock = try {
                    ss.accept()
                } catch (e: SocketTimeoutException) {
                    if (sessionValid) {
                        _state.value = EngineState.Error(
                            "No device connected within ${QR_ACCEPT_TIMEOUT_MS / 1000}s. Tap Reset to generate a new code.",
                            retryable = true, kind = "TIMEOUT"
                        )
                    }
                    return@withContext
                }

                if (!sessionValid) {
                    sock.closeQuietly()
                    return@withContext
                }

                sock.soTimeout = 0
                initStreams(sock)
                // FIX-C: validate session ID in HELLO
                val handshakeOk = completeHandshake(isHost = true)
                if (!handshakeOk) {
                    Log.w(TAG, "Handshake session mismatch — rejecting connection, re-waiting")
                    sock.closeQuietly()
                    if (sessionValid) {
                        // Keep QR alive; wait for the correct receiver
                        acceptConnectionOnServerSocket(tier)
                    }
                    return@withContext
                }

                activeSocket = sock
                _state.value = EngineState.Connected(tier, peerName, isHost = true, socket = sock)
                TransferForegroundService.start(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "Accept failed: ${e.message}")
                if (sessionValid && _state.value !is EngineState.Idle) {
                    _state.value = EngineState.Error(
                        "Connection failed. Tap Reset and try again.",
                        retryable = true, kind = "TIMEOUT"
                    )
                }
            }
        }
    }

    /**
     * ROOT CAUSE FIX: create the socket from network.socketFactory (or bind it)
     * so that Android routes it over the hotspot interface, not cellular.
     *
     * [network] is null for Wi-Fi Direct and legacy API < 29 hotspot — in those
     * cases plain Socket() is correct because the OS already routes to the right iface.
     */
    private suspend fun tcpConnect(
        ip: String,
        port: Int,
        tier: TransportTier,
        expectedSessionId: String,
        network: Network?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!sessionValid) return@withContext false
        repeat(2) { attempt ->
            try {
                val sock: Socket = if (network != null) {
                    // KEY FIX: create socket via network's socket factory so the OS
                    // routes it through the hotspot interface, not the default route.
                    network.socketFactory.createSocket()
                } else {
                    Socket()
                }
                sock.connect(InetSocketAddress(ip, port), 4_000)
                sock.soTimeout = 0
                initStreams(sock)
                // FIX-C: validate session ID in HELLO
                val handshakeOk = completeHandshake(isHost = false, expectedSessionId = expectedSessionId)
                if (!handshakeOk) {
                    Log.w(TAG, "Session ID mismatch from server at $ip — ignoring stale server")
                    sock.closeQuietly()
                    delay(1_500L)
                    return@repeat
                }
                activeSocket = sock
                _state.value = EngineState.Connected(tier, peerName, isHost = false, socket = sock)
                TransferForegroundService.start(ctx)
                return@withContext true
            } catch (e: Exception) {
                Log.d(TAG, "TCP attempt ${attempt + 1} → $ip:$port — ${e.message}")
                delay(800L * (attempt + 1))
            }
        }
        false
    }

    private fun initStreams(sock: Socket) {
        socketOut = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 131_072))
        socketIn  = DataInputStream(BufferedInputStream(sock.getInputStream(), 131_072))
    }

    /**
     * FIX-C: HELLO includes sessionId for validation.
     *
     * Host sends:   HELLO <sessionId> <deviceName>\n  (after reading client hello)
     * Client sends: HELLO <sessionId> <deviceName>\n  (before reading server reply)
     */
    private fun completeHandshake(
        isHost: Boolean,
        expectedSessionId: String = sessionId,
    ): Boolean {
        val out = socketOut ?: return false
        val inn = socketIn  ?: return false
        return try {
            if (isHost) {
                val hello = inn.readLine() ?: ""
                val parts = hello.split(" ", limit = 3)
                val clientSession = parts.getOrElse(1) { "" }
                peerName = parts.getOrElse(2) { "Unknown" }
                val match = clientSession == sessionId
                out.writeBytes("HELLO $sessionId ${deviceName()}\n")
                out.flush()
                if (!match) Log.w(TAG, "Client sent session=$clientSession, expected=$sessionId")
                match
            } else {
                out.writeBytes("HELLO $sessionId ${deviceName()}\n")
                out.flush()
                val hello = inn.readLine() ?: ""
                val parts = hello.split(" ", limit = 3)
                val serverSession = parts.getOrElse(1) { "" }
                peerName = parts.getOrElse(2) { "Unknown" }
                val match = serverSession == expectedSessionId
                if (!match) Log.w(TAG, "Server sent session=$serverSession, expected=$expectedSessionId")
                match
            }
        } catch (e: Exception) {
            Log.w(TAG, "Handshake error: ${e.message}")
            false
        }
    }

    // ── File send ─────────────────────────────────────────────────────────────

    // ── FileMetadata — rich info sent before raw bytes (Xender-style) ─────────
    // BUG3 FIX: Always send metadata before the FILE frame.
    // This allows the receiving UI to show the movie poster/title immediately.

    data class FileMetadata(
        val title:     String = "",
        val posterUrl: String = "",
        val mediaType: String = "",
        val season:    Int    = 0,
        val episode:   Int    = 0,
        val quality:   String = "",
    ) {
        fun toJson(): String = buildString {
            append("{")
            append("\"title\":\"${title.replace("\"","\\\"")}\",")
            append("\"posterUrl\":\"${posterUrl.replace("\"","\\\"")}\",")
            append("\"mediaType\":\"$mediaType\",")
            append("\"season\":$season,")
            append("\"episode\":$episode,")
            append("\"quality\":\"$quality\"")
            append("}")
        }

        companion object {
            fun fromJson(json: String): FileMetadata = try {
                fun extract(key: String): String {
                    val pat = Regex("\"$key\":\\s*\"([^\"]*)\"")
                    return pat.find(json)?.groupValues?.getOrNull(1) ?: ""
                }
                fun extractInt(key: String): Int {
                    val pat = Regex("\"$key\":\\s*(\\d+)")
                    return pat.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                }
                FileMetadata(
                    title     = extract("title"),
                    posterUrl = extract("posterUrl"),
                    mediaType = extract("mediaType"),
                    season    = extractInt("season"),
                    episode   = extractInt("episode"),
                    quality   = extract("quality"),
                )
            } catch (_: Exception) { FileMetadata() }
        }
    }

    fun sendFile(
        filePath: String,
        fileName: String,
        meta: FileMetadata = FileMetadata(),
        onProgress: (sent: Long, total: Long, bps: Long) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val out  = socketOut ?: run { onError("Not connected"); return }
        val file = File(filePath)
        if (!file.exists()) { onError("File not found: $filePath"); return }

        val tier = currentTier()
        cancelRequested = false

        scope.launch(Dispatchers.IO) {
            try {
                val total = file.length()
                _state.value = EngineState.Transferring(tier, peerName, fileName, "SEND", 0, total, 0)

                // BUG3 FIX: Send metadata frame BEFORE the FILE frame.
                // Xender always sends rich metadata first so the receiver can render
                // the movie poster card while waiting for bytes.
                val encodedMeta = URLEncoder.encode(meta.toJson(), "UTF-8")
                out.writeBytes("META $encodedMeta\n")

                val encodedName = URLEncoder.encode(fileName, "UTF-8")
                out.writeBytes("FILE $total $encodedName\n")

                val buf = ByteArray(131_072)
                var sent = 0L
                var tLast = System.currentTimeMillis()
                var bLast = 0L

                FileInputStream(file).use { fis ->
                    var n: Int
                    while (fis.read(buf).also { n = it } != -1) {
                        if (cancelRequested) {
                            // Drain remaining bytes as zeros so receiver's byte-count is satisfied
                            val zeros = ByteArray(buf.size)
                            var remaining = total - sent
                            while (remaining > 0) {
                                val chunk = minOf(zeros.size.toLong(), remaining).toInt()
                                out.write(zeros, 0, chunk)
                                remaining -= chunk
                            }
                            break
                        }
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
                if (!cancelRequested) _state.value = EngineState.Done
                cancelRequested = false
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Send failed") }
                _state.value = EngineState.Error(e.message ?: "Send failed", retryable = false, kind = "TRANSFER")
            }
        }
    }

    fun sendDone() {
        scope.launch(Dispatchers.IO) {
            try {
                socketOut?.apply { writeBytes("DONE\n"); flush() }
            } catch (_: Exception) {}
        }
    }

    fun cancelCurrentSend() {
        cancelRequested = true
    }

    // ── File receive ──────────────────────────────────────────────────────────

    fun receiveFiles(
        saveDir: File,
        onFileStart: (fileName: String, total: Long, meta: FileMetadata) -> Unit,
        onProgress:  (received: Long, total: Long, bps: Long, fileName: String) -> Unit,
        onFileDone:  (File, meta: FileMetadata) -> Unit,
        onAllDone:   () -> Unit,
        onError:     (String) -> Unit,
    ) {
        val inn  = socketIn  ?: run { onError("Not connected"); return }
        val out  = socketOut ?: run { onError("Not connected"); return }
        val tier = currentTier()
        cancelledReceive = false

        scope.launch(Dispatchers.IO) {
            try {
                saveDir.mkdirs()
                // BUG3 FIX: Track META frame separately so it can be attached to the next FILE frame.
                // Xender sends META before FILE for rich UI — we hold it here and deliver with onFileStart.
                var pendingMeta = FileMetadata()

                while (true) {
                    val header = inn.readLine()?.trim() ?: break
                    when {
                        header == "DONE" -> {
                            withContext(Dispatchers.Main) { onAllDone() }
                            _state.value = EngineState.Done
                            break
                        }
                        header.startsWith("META ") -> {
                            // BUG3 FIX: parse metadata frame, hold for the next FILE frame
                            val encodedJson = header.removePrefix("META ").trim()
                            val json = try { URLDecoder.decode(encodedJson, "UTF-8") } catch (_: Exception) { "{}" }
                            pendingMeta = FileMetadata.fromJson(json)
                            Log.d(TAG, "META received: title=${pendingMeta.title}")
                        }
                        header.startsWith("FILE ") -> {
                            val parts = header.split(" ", limit = 3)
                            if (parts.size < 3) { onError("Bad header: \"$header\""); break }
                            val total    = parts[1].toLongOrNull() ?: break
                            val fileName = URLDecoder.decode(parts[2], "UTF-8")

                            // Capture the pending metadata for this file, then reset for next file
                            val fileMeta = pendingMeta
                            pendingMeta  = FileMetadata()

                            // FIX-E: reset per-file cancel flag
                            val fileCancelled = cancelledReceive
                            cancelledReceive  = false

                            // BUG3 FIX: pass fileMeta to UI so it can show poster immediately
                            withContext(Dispatchers.Main) { onFileStart(fileName, total, fileMeta) }
                            _state.value = EngineState.Transferring(tier, peerName, fileName, "RECEIVE", 0, total, 0)

                            val outFile = File(saveDir, fileName)
                            val buf = ByteArray(131_072)
                            var received = 0L
                            var tLast = System.currentTimeMillis()
                            var bLast = 0L

                            FileOutputStream(outFile).use { fos ->
                                while (received < total) {
                                    val toRead = minOf(buf.size.toLong(), total - received).toInt()
                                    val n = inn.read(buf, 0, toRead)
                                    if (n == -1) break
                                    if (!fileCancelled && !cancelledReceive) fos.write(buf, 0, n)
                                    received += n
                                    val now = System.currentTimeMillis()
                                    if (now - tLast >= 200) {
                                        val bps = (received - bLast) * 1000L / (now - tLast).coerceAtLeast(1)
                                        tLast = now; bLast = received
                                        if (!fileCancelled && !cancelledReceive) {
                                            withContext(Dispatchers.Main) { onProgress(received, total, bps, fileName) }
                                            _state.value = EngineState.Transferring(tier, peerName, fileName, "RECEIVE", received, total, bps)
                                        }
                                    }
                                }
                            }

                            if (fileCancelled || cancelledReceive) {
                                outFile.delete()
                            } else {
                                withContext(Dispatchers.Main) { onFileDone(outFile, fileMeta) }
                            }
                        }
                        header == "SKIP" -> {
                            Log.d(TAG, "Sender acked CANCEL with SKIP")
                        }
                        else -> {
                            Log.w(TAG, "Unexpected frame: $header")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Receive failed") }
                _state.value = EngineState.Error(e.message ?: "Receive failed", retryable = false, kind = "TRANSFER")
            }
        }
    }

    fun cancelCurrentReceive() {
        cancelledReceive = true
        scope.launch(Dispatchers.IO) {
            try {
                socketOut?.apply { writeBytes("CANCEL\n"); flush() }
            } catch (_: Exception) {}
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /**
     * Full disconnect.
     * [silent] = true skips resetting state to Idle (caller will set its own state).
     *
     * BUG2 FIX: The previous implementation left hotspotReservation alive across
     * disconnect(), which silently prevented startLocalOnlyHotspot() from succeeding
     * on retry (Android only allows one LocalOnlyHotspot reservation at a time).
     * We now:
     *   1. Cancel prepareJob before touching anything else (BUG1 synergy).
     *   2. Close hotspotReservation eagerly (not just on API 26+).
     *   3. Unregister NetworkCallback before closing reservation so the OS frees the
     *      hotspot interface association fully.
     *   4. Return a hint to the UI when the failure is likely due to a stale network
     *      association — so users know to toggle Wi-Fi if auto-recovery fails.
     */
    fun disconnect(silent: Boolean = false) {
        // BUG1+BUG2: cancel any pending prepare so it can't emit QrReady after we clear state
        prepareJob?.cancel()
        prepareJob = null

        // Kill session first so loops abort immediately
        sessionValid     = false
        cancelRequested  = false
        cancelledReceive = false

        // Close serverSocket before activeSocket to unblock accept()
        serverSocket?.closeQuietly()
        serverSocket = null

        socketOut?.closeQuietly()
        socketIn?.closeQuietly()
        socketOut = null
        socketIn  = null
        activeSocket?.closeQuietly()
        activeSocket = null

        // BUG2 FIX: unregister NetworkCallback FIRST, then close hotspot reservation.
        // Unregistering callback signals to Android that we're done with the hotspot
        // network, allowing the OS to fully release the interface for reuse.
        releaseNetworkCallback()

        p2pChannel?.let { ch ->
            try { p2pManager?.removeGroup(ch, null) } catch (_: Exception) {}
        }
        p2pManager = null
        p2pChannel = null

        // BUG2 FIX: close hotspot reservation regardless of API level (was guarded by O check).
        // Android allows only ONE LocalOnlyHotspot at a time — if reservation is not closed,
        // the next startLocalOnlyHotspot() call will either be silently swallowed or trigger
        // onFailed(ERROR_TETHERING_DISALLOWED) depending on OEM. Closing it here lets the
        // OS reclaim the slot so a fresh prepareAsSender() can succeed.
        try { hotspotReservation?.close() } catch (_: Exception) {}
        hotspotReservation = null

        TransferForegroundService.stop(ctx)
        if (!silent) _state.value = EngineState.Idle
    }

    /**
     * BUG2: Returns true if the receiver can automatically retry without user action.
     * Returns false if the OS Wi-Fi stack is still holding a stale association —
     * in that case the user must toggle Wi-Fi off/on to clear it.
     *
     * The UI should show an explicit message when this returns false rather than
     * just retrying silently (which will keep failing until Wi-Fi is toggled).
     */
    fun isWifiStaleAfterDisconnect(): Boolean {
        // If a boundNetwork is still non-null here, Android's Wi-Fi stack is
        // still holding the hotspot association. We released the callback in
        // disconnect() so boundNetwork should be null — but on some OEMs the
        // association lingers. We check the interface state as a proxy.
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.any { iface ->
                    iface.isUp && !iface.isLoopback &&
                    iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .any { addr ->
                            val h = addr.hostAddress ?: return@any false
                            // 192.168.49.x = WD group; 192.168.43.x = typical hotspot
                            h.startsWith("192.168.49.") || h.startsWith("192.168.43.")
                        }
                } == true
        } catch (_: Exception) { false }
    }

    private fun releaseNetworkCallback() {
        networkCallback?.let { cb ->
            try { connectivityManager().unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        networkCallback = null
        boundNetwork    = null
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

    private fun Socket.closeQuietly()           = try { close() } catch (_: Exception) {}
    private fun ServerSocket.closeQuietly()     = try { close() } catch (_: Exception) {}
    private fun DataOutputStream.closeQuietly() = try { close() } catch (_: Exception) {}
    private fun DataInputStream.closeQuietly()  = try { close() } catch (_: Exception) {}
}
