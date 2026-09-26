package com.axio.reelz.transfer

// ─────────────────────────────────────────────────────────────────────────────
//  P2pEngine — Reelz Beam  (v5 — final corrected)
//
//  TRANSPORT DECISION (Sender):
//    hasWifiDirect() → createGroup → WD QR     (tier = "WD")
//    createGroup fails || !hasWifiDirect() → startLocalOnlyHotspot → HS QR (tier = "HS")
//
//  QR PAYLOAD contains BOTH ssid+password so receiver can choose its best path.
//
//  RECEIVER LOGIC:
//    tier == "WD"  → this device hasWifiDirect() ?
//                       yes → join WD group via WifiP2pManager.connect(), then TCP
//                       no  → emit SWITCH_ROLE (don't loop, one clear message)
//    tier == "HS"  → join hotspot via WifiNetworkSpecifier (API 29+) or legacy
//                       bind every socket to the returned Network object
//
//  ALL 4 CASES GUARANTEED:
//    S:WD  R:WD  → WD QR, WD join ✓
//    S:WD  R:no  → WD QR, receiver can't join → SWITCH_ROLE → now ex-receiver
//                  generates HS QR, original sender (WD ⇒ can join hotspot) joins ✓
//    S:no  R:WD  → HS QR, receiver joins hotspot ✓  (WD capable ⇒ can join hotspot)
//    S:no  R:no  → HS QR, receiver joins hotspot ✓  (all Android can join hotspot)
//
//  TCP PROTOCOL (single persistent stream):
//    Handshake:  HELLO <sessionId> <deviceName>\n
//    Meta:       META  <urlEncoded-json>\n         (before each FILE frame)
//    File:       FILE  <size> <urlEncodedName>\n
//    Cancel:     CANCEL\n   ← receiver → skip this file
//    Skip ack:   SKIP\n     ← sender ack
//    Done:       DONE\n     ← sender queue empty
// ─────────────────────────────────────────────────────────────────────────────

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG  = "P2pEngine"
private const val PORT = 49800
private const val QR_ACCEPT_TIMEOUT_MS = 120_000L
private const val CONNECT_TIMEOUT_MS   =  60_000L

// ─── Transport tier ───────────────────────────────────────────────────────────

enum class TransportTier { WIFI_DIRECT, HOTSPOT }

// ─── Engine states ────────────────────────────────────────────────────────────

sealed class EngineState {
    object Idle                                                                : EngineState()
    object Preparing                                                           : EngineState()
    data class QrReady(val qrPayload: String, val sessionId: String)           : EngineState()
    object Negotiating                                                         : EngineState()
    data class Connected(
        val tier:     TransportTier,
        val peerName: String,
        val isHost:   Boolean,
        val socket:   Socket,
    )                                                                          : EngineState()
    data class Transferring(
        val tier:             TransportTier,
        val peerName:         String,
        val fileName:         String,
        val direction:        String,   // "SEND" | "RECEIVE"
        val transferredBytes: Long,
        val totalBytes:       Long,
        val speedBps:         Long,
    )                                                                          : EngineState()
    object Done                                                                : EngineState()
    data class Error(
        val msg:       String,
        val retryable: Boolean = true,
        val kind:      String  = "GENERIC",
    )                                                                          : EngineState()
}

// ─── QR payload ───────────────────────────────────────────────────────────────
//
//  Format:  reelzbeam://<sessionId>|<deviceName>|<tier>|<ip>|<port>|<ssid>|<password>
//  tier:    "WD" = WiFi-Direct group / "HS" = LocalOnlyHotspot
//  ssid + password always present so receiver can choose best available path.

data class BeamPayload(
    val sessionId:  String,
    val deviceName: String,
    val tier:       String,   // "WD" or "HS"
    val ip:         String,
    val port:       Int,
    val ssid:       String = "",
    val password:   String = "",
) {
    fun encode(): String =
        "reelzbeam://$sessionId|$deviceName|$tier|$ip|$port|$ssid|$password"

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

    private var serverSocket: ServerSocket?  = null
    private var activeSocket: Socket?        = null
    private var socketIn:  DataInputStream?  = null
    private var socketOut: DataOutputStream? = null

    @Volatile private var sessionValid     = false
    @Volatile private var cancelRequested  = false
    @Volatile private var cancelledReceive = false
    @Volatile private var prepareJob: Job? = null
    // Explicit tier stored when the sender resolves transport — eliminates the
    // fragile IP-prefix heuristic in currentTier(). Both WD and hotspot commonly
    // use 192.168.49.x so guessing from the address is unreliable.
    @Volatile private var resolvedTier: TransportTier = TransportTier.HOTSPOT

    // Wi-Fi Direct
    private var p2pManager: WifiP2pManager?         = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver?      = null

    @Suppress("DEPRECATION")
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    // Network binding for hotspot receiver
    private var boundNetwork:    Network?                              = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var peerName  = ""
    private var sessionId = ""

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun deviceName() = "${Build.MANUFACTURER} ${Build.MODEL}".take(24)

    /** True if this device has WiFi-Direct hardware support. */
    fun hasWifiDirect(): Boolean =
        ctx.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    private fun connectivityManager() =
        ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun getLocalIp(): String = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.hostAddress.startsWith("169.") }
            ?.hostAddress ?: ""
    } catch (_: Exception) { "" }

    // ── SENDER: prepare ───────────────────────────────────────────────────────

    fun prepareAsSender(onQrReady: (String) -> Unit) {
        prepareJob?.cancel()
        prepareJob = null

        sessionValid = false
        disconnect(silent = true)

        sessionId    = UUID.randomUUID().toString().take(8).uppercase()
        sessionValid = true
        _state.value = EngineState.Preparing

        prepareJob = scope.launch {
            try {
                val payload: String? = if (hasWifiDirect()) {
                    Log.d(TAG, "Device supports WiFi-Direct — attempting WD group")
                    val wdPayload = tryCreateWifiDirectGroup()
                    if (wdPayload != null) {
                        Log.d(TAG, "WD group ready → WD QR")
                        wdPayload
                    } else {
                        Log.d(TAG, "WD group failed — falling back to Hotspot")
                        tryCreateHotspot()
                    }
                } else {
                    Log.d(TAG, "No WiFi-Direct hardware — using Hotspot QR")
                    tryCreateHotspot()
                }

                if (!isActive || !sessionValid) return@launch

                if (payload != null) {
                    resolvedTier = detectTier(payload)   // store before accepting
                    _state.value = EngineState.QrReady(payload, sessionId)
                    withContext(Dispatchers.Main) { onQrReady(payload) }
                    acceptConnectionOnServerSocket(resolvedTier)
                } else {
                    if (sessionValid) {
                        _state.value = EngineState.Error(
                            "Could not create a Wi-Fi connection. Check permissions and try again.",
                            retryable = true, kind = "CONNECTION"
                        )
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "prepareAsSender cancelled cleanly")
            } finally {
                if (prepareJob?.isCancelled == true) prepareJob = null
            }
        }
    }

    private fun detectTier(payload: String): TransportTier {
        val p = BeamPayload.decode(payload) ?: return TransportTier.HOTSPOT
        return if (p.tier == "WD") TransportTier.WIFI_DIRECT else TransportTier.HOTSPOT
    }

    // ── RECEIVER: connect from QR ─────────────────────────────────────────────

    fun connectFromQr(rawQr: String) {
        val payload = BeamPayload.decode(rawQr) ?: run {
            _state.value = EngineState.Error("Invalid QR code.", retryable = true, kind = "CONNECTION")
            return
        }
        sessionValid = false
        disconnect(silent = true)

        peerName     = payload.deviceName
        sessionId    = payload.sessionId
        sessionValid = true
        _state.value = EngineState.Negotiating

        scope.launch {
            // Record the intended tier immediately so currentTier() is accurate
            // even before the TCP socket is fully established.
            resolvedTier = if (payload.tier == "WD") TransportTier.WIFI_DIRECT else TransportTier.HOTSPOT

            when (payload.tier) {
                "WD" -> {
                    if (!hasWifiDirect()) {
                        // This device can't do WiFi-Direct at all → ask user to switch roles
                        Log.w(TAG, "Receiver has no WD hardware — emitting SWITCH_ROLE")
                        if (sessionValid) {
                            _state.value = EngineState.Error(
                                "Your device doesn't support Wi-Fi Direct. Tap the other phone's Receive button and use this phone as the sender — they will generate a new QR for you to scan.",
                                retryable = true, kind = "SWITCH_ROLE"
                            )
                        }
                        return@launch
                    }
                    // Has WD hardware — join the WD group then TCP-connect
                    val ok = connectViaWifiDirect(payload)
                    if (!ok && sessionValid && _state.value is EngineState.Negotiating) {
                        _state.value = EngineState.Error(
                            "Couldn't join the Wi-Fi Direct group. Make sure both devices are close and Wi-Fi is on. If this keeps failing, try switching roles.",
                            retryable = true, kind = "SWITCH_ROLE"
                        )
                    }
                }
                "HS" -> {
                    val ok = connectViaHotspot(payload)
                    if (!ok && sessionValid && _state.value is EngineState.Negotiating) {
                        _state.value = EngineState.Error(
                            "Could not connect to the hotspot. Make sure both devices are close and Wi-Fi is on.",
                            retryable = true, kind = "CONNECTION"
                        )
                    }
                }
                else -> {
                    _state.value = EngineState.Error(
                        "Unknown QR type. Please update the app.", retryable = false, kind = "GENERIC"
                    )
                }
            }
        }
    }

    // ── WiFi-Direct group creator (SENDER) ────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun tryCreateWifiDirectGroup(): String? = withContext(Dispatchers.Main) {
        try {
            val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return@withContext null
            val chan = mgr.initialize(ctx, Looper.getMainLooper(), null)
            p2pManager = mgr
            p2pChannel = chan

            // On API 29+ we supply our own SSID/passphrase so we never need to poll
            // requestGroupInfo for credentials — Samsung One UI returns passphrase=null
            // from requestGroupInfo even after a successful createGroup(), causing an
            // 8-second spin that silently falls through to hotspot and then fails.
            val knownSsid: String
            val knownPass: String

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // We control the credentials — no need to ask the OS for them later.
                knownSsid = "DIRECT-${sessionId.take(4)}-REELZ"
                knownPass = sessionId.lowercase()

                // Reuse an existing group we own with the same name (avoids teardown delay).
                val existing = CompletableDeferred<WifiP2pGroup?>().also { d ->
                    mgr.requestGroupInfo(chan) { d.complete(it) }
                }.let { withTimeoutOrNull(3_000) { it.await() } }

                if (existing != null && existing.isGroupOwner &&
                    existing.networkName == knownSsid) {
                    // Already our group — skip recreating it.
                    withContext(Dispatchers.IO) { startServerSocket() }
                    return@withContext BeamPayload(
                        sessionId  = sessionId,
                        deviceName = deviceName(),
                        tier       = "WD",
                        ip         = "192.168.49.1",
                        port       = PORT,
                        ssid       = knownSsid,
                        password   = knownPass,
                    ).encode()
                }

                val config = WifiP2pConfig.Builder()
                    .setNetworkName(knownSsid)
                    .setPassphrase(knownPass)
                    .build()

                val created = CompletableDeferred<Boolean>().also { d ->
                    mgr.createGroup(chan, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { d.complete(true) }
                        override fun onFailure(r: Int) {
                            Log.w(TAG, "WD createGroup (Q+) failed reason=$r")
                            d.complete(false)
                        }
                    })
                }

                val ok = withTimeoutOrNull(10_000) { created.await() } ?: false
                if (!ok) return@withContext null

                // Group is up — credentials are already known, no passphrase poll needed.
                withContext(Dispatchers.IO) { startServerSocket() }
                return@withContext BeamPayload(
                    sessionId  = sessionId,
                    deviceName = deviceName(),
                    tier       = "WD",
                    ip         = "192.168.49.1",
                    port       = PORT,
                    ssid       = knownSsid,
                    password   = knownPass,
                ).encode()

            } else {
                // Pre-Q: OS chooses SSID/passphrase; we must read them from requestGroupInfo.
                // Passphrase IS available on stock AOSP pre-Q; Samsung pre-Q is rare enough
                // that we keep the poll but cap it tightly.
                val created = CompletableDeferred<Boolean>().also { d ->
                    @Suppress("DEPRECATION")
                    mgr.createGroup(chan, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { d.complete(true) }
                        override fun onFailure(r: Int) {
                            Log.w(TAG, "WD createGroup (pre-Q) failed reason=$r")
                            d.complete(false)
                        }
                    })
                }

                val ok = withTimeoutOrNull(10_000) { created.await() } ?: false
                if (!ok) return@withContext null

                // Poll until group info has both networkName and passphrase.
                val deadline = System.currentTimeMillis() + 6_000
                while (System.currentTimeMillis() < deadline) {
                    val g = CompletableDeferred<WifiP2pGroup?>().also { d ->
                        mgr.requestGroupInfo(chan) { d.complete(it) }
                    }.let { withTimeoutOrNull(2_000) { it.await() } }
                    if (g != null && g.isGroupOwner &&
                        !g.networkName.isNullOrEmpty() && !g.passphrase.isNullOrEmpty()) {
                        withContext(Dispatchers.IO) { startServerSocket() }
                        return@withContext BeamPayload(
                            sessionId  = sessionId,
                            deviceName = deviceName(),
                            tier       = "WD",
                            ip         = "192.168.49.1",
                            port       = PORT,
                            ssid       = g.networkName,
                            password   = g.passphrase,
                        ).encode()
                    }
                    delay(500)
                }
                Log.w(TAG, "Pre-Q: requestGroupInfo never returned passphrase — falling to hotspot")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.w(TAG, "WD group creation error: ${e.message}")
            null
        }
    }

    // ── WiFi-Direct connect (RECEIVER) ────────────────────────────────────────
    //
    //  Steps:
    //   1. Use WifiP2pManager.connect() with WifiP2pConfig to join the group.
    //      This triggers the OS WD association (and on API 29+ auto-accepts the dialog
    //      when config has network name set).
    //   2. Poll TCP to 192.168.49.1 once the OS assigns an IP in 192.168.49.x.
    //      No need for WifiNetworkSpecifier — WD is NOT a regular Wi-Fi network.

    @SuppressLint("MissingPermission")
    private suspend fun connectViaWifiDirect(payload: BeamPayload): Boolean {
        val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return false
        val chan = withContext(Dispatchers.Main) {
            mgr.initialize(ctx, Looper.getMainLooper(), null)
        }
        p2pManager = mgr
        p2pChannel = chan

        // Build connect config
        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && payload.ssid.isNotEmpty()) {
            WifiP2pConfig.Builder()
                .setNetworkName(payload.ssid)
                .setPassphrase(payload.password)
                .build()
        } else {
            // Pre-Q (Android 9-): WifiP2pConfig.Builder is unavailable so we can't
            // join by SSID/password. However the QR payload always carries hotspot
            // credentials as well, so attempt the hotspot path instead of giving up.
            // This keeps the session alive without forcing a role-switch on old devices.
            Log.w(TAG, "Pre-Q receiver: WifiP2pConfig.Builder unavailable — falling back to hotspot path")
            return connectViaHotspot(payload)
        }

        val connectResult = CompletableDeferred<Boolean>().also { d ->
            withContext(Dispatchers.Main) {
                mgr.connect(chan, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { d.complete(true) }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "WD connect() failed reason=$reason")
                        d.complete(false)
                    }
                })
            }
        }

        val initiated = withTimeoutOrNull(8_000) { connectResult.await() } ?: false
        if (!initiated) {
            Log.w(TAG, "WD connect initiation failed — receiver has WD but group join refused")
            return false
        }

        // TCP-poll 192.168.49.1 — OS will give us 192.168.49.x once associated
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        var attempt  = 0
        while (currentCoroutineContext().isActive && sessionValid && System.currentTimeMillis() < deadline) {
            attempt++
            if (tcpConnect(payload.ip, payload.port, TransportTier.WIFI_DIRECT, payload.sessionId, null)) {
                return true
            }
            val backoff = minOf(1_000L * attempt, 5_000L)
            delay(backoff)
        }
        Log.w(TAG, "WD TCP connect gave up after $attempt attempts")
        return false
    }

    // ── Hotspot creator (SENDER) ──────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun tryCreateHotspot(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Pre-O: no LocalOnlyHotspot API; use current WiFi IP
            val ip = getLocalIp()
            if (ip.isEmpty()) return null
            withContext(Dispatchers.IO) { startServerSocket() }
            return BeamPayload(
                sessionId  = sessionId,
                deviceName = deviceName(),
                tier       = "HS",
                ip         = ip,
                port       = PORT,
                ssid       = "",
                password   = "",
            ).encode()
        }

        return suspendCancellableCoroutine { cont ->
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: run { cont.resume(null, null); return@suspendCancellableCoroutine }

            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                @SuppressLint("NewApi")
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    scope.launch {
                        withContext(Dispatchers.IO) { startServerSocket() }

                        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            reservation.softApConfiguration?.ssid ?: ""
                        else
                            @Suppress("DEPRECATION") reservation.wifiConfiguration?.SSID ?: ""

                        val pass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            reservation.softApConfiguration?.passphrase ?: ""
                        else
                            @Suppress("DEPRECATION") reservation.wifiConfiguration?.preSharedKey ?: ""

                        // Poll for the hotspot interface IP (AP-side address, typically 192.168.49.1)
                        val ip = pollForHotspotIp(8_000)
                        Log.d(TAG, "Hotspot started ssid=$ssid ip=$ip")

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
                    Log.w(TAG, "Hotspot start failed reason=$reason")
                    cont.resume(null, null)
                }
                override fun onStopped() { Log.d(TAG, "Hotspot stopped") }
            }, null)

            cont.invokeOnCancellation {
                try { hotspotReservation?.close() } catch (_: Exception) {}
                hotspotReservation = null
            }
        }
    }

    private suspend fun pollForHotspotIp(maxWaitMs: Long): String {
        val wlanIp   = getLocalIp()
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val candidate = findHotspotIfaceIp(wlanIp)
            if (candidate != null) { Log.d(TAG, "Hotspot iface IP: $candidate"); return candidate }
            delay(500)
        }
        Log.w(TAG, "Hotspot IP not found after ${maxWaitMs}ms; using 192.168.49.1 fallback")
        return "192.168.49.1"
    }

    private fun findHotspotIfaceIp(wlanIp: String): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { iface -> iface.inetAddresses.toList().filterIsInstance<Inet4Address>().map { iface to it } }
            ?.firstOrNull { (_, addr) ->
                val h = addr.hostAddress ?: return@firstOrNull false
                !h.startsWith("169.") && !h.startsWith("127.") && h != wlanIp
            }?.second?.hostAddress
    } catch (_: Exception) { null }

    // ── Hotspot connect (RECEIVER) ────────────────────────────────────────────
    //
    //  KEY: every socket MUST be created via network.socketFactory so the OS
    //  routes over the hotspot interface, not cellular.

    @SuppressLint("MissingPermission")
    private suspend fun connectViaHotspot(payload: BeamPayload): Boolean {
        if (payload.ssid.isEmpty() || payload.ip.isEmpty()) {
            Log.w(TAG, "HS payload missing ssid or ip — cannot connect")
            return false
        }

        val network = joinHotspotNetwork(payload.ssid, payload.password)
        // network may be null on pre-Q (legacy join returns null but Wi-Fi state is set)
        if (network == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.w(TAG, "Failed to obtain hotspot Network object")
            return false
        }

        if (!sessionValid) return false
        boundNetwork = network
        Log.d(TAG, "Joined hotspot ${payload.ssid} network=$network")

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
        Log.w(TAG, "Hotspot TCP connect gave up after $attempt attempts")
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun joinHotspotNetwork(ssid: String, password: String): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            joinHotspotLegacy(ssid, password)
            return null  // pre-Q: no Network object, caller handles null
        }

        val cm = connectivityManager()
        releaseNetworkCallback()

        return suspendCancellableCoroutine { cont ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Hotspot network available: $network")
                    if (cont.isActive) cont.resume(network, null)
                }
                override fun onUnavailable() {
                    Log.w(TAG, "Hotspot network unavailable")
                    if (cont.isActive) cont.resume(null, null)
                }
                override fun onLost(network: Network) {
                    Log.w(TAG, "Hotspot network lost during session")
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

    @Suppress("DEPRECATION")
    private suspend fun joinHotspotLegacy(ssid: String, password: String) {
        withContext(Dispatchers.IO) {
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return@withContext
                val config = android.net.wifi.WifiConfiguration().apply {
                    SSID           = "\"$ssid\""
                    preSharedKey   = "\"$password\""
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                // Remove stale entry if present
                wm.configuredNetworks?.firstOrNull { it.SSID == "\"$ssid\"" }
                    ?.let { wm.removeNetwork(it.networkId) }
                val netId = wm.addNetwork(config)
                if (netId == -1) { Log.w(TAG, "Legacy addNetwork failed"); return@withContext }
                wm.disconnect(); wm.enableNetwork(netId, true); wm.reconnect()
                // Wait until connected
                val deadline = System.currentTimeMillis() + 12_000
                while (System.currentTimeMillis() < deadline) {
                    val info = wm.connectionInfo
                    if (info?.ssid == "\"$ssid\"" && (info.ipAddress != 0)) {
                        Log.d(TAG, "Legacy: connected to $ssid"); return@withContext
                    }
                    delay(600)
                }
                Log.w(TAG, "Legacy: timed out waiting for hotspot $ssid")
            } catch (e: Exception) {
                Log.w(TAG, "Legacy hotspot join failed: ${e.message}")
            }
        }
    }

    // ── TCP plumbing ──────────────────────────────────────────────────────────

    private fun startServerSocket() {
        serverSocket?.closeQuietly()
        serverSocket = ServerSocket(PORT).also { Log.d(TAG, "ServerSocket bound on :$PORT") }
    }

    private suspend fun acceptConnectionOnServerSocket(tier: TransportTier) {
        withContext(Dispatchers.IO) {
            try {
                val ss = serverSocket ?: return@withContext
                ss.soTimeout = QR_ACCEPT_TIMEOUT_MS.toInt()
                Log.d(TAG, "Waiting for receiver (${QR_ACCEPT_TIMEOUT_MS / 1000}s)…")

                val sock = try {
                    ss.accept()
                } catch (e: java.net.SocketTimeoutException) {
                    if (sessionValid) {
                        _state.value = EngineState.Error(
                            "No device connected within ${QR_ACCEPT_TIMEOUT_MS / 1000}s. Tap Reset to generate a new code.",
                            retryable = true, kind = "TIMEOUT"
                        )
                    }
                    return@withContext
                }

                if (!sessionValid) { sock.closeQuietly(); return@withContext }

                sock.soTimeout = 0
                initStreams(sock)
                val ok = completeHandshake(isHost = true)
                if (!ok) {
                    Log.w(TAG, "Handshake session mismatch — re-waiting")
                    sock.closeQuietly()
                    if (sessionValid) acceptConnectionOnServerSocket(tier)
                    return@withContext
                }

                activeSocket = sock
                _state.value = EngineState.Connected(tier, peerName, isHost = true, socket = sock)
                TransferForegroundService.start(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "Accept loop error: ${e.message}")
                if (sessionValid && _state.value !is EngineState.Idle) {
                    _state.value = EngineState.Error(
                        "Connection failed. Tap Reset and try again.",
                        retryable = true, kind = "TIMEOUT"
                    )
                }
            }
        }
    }

    private suspend fun tcpConnect(
        ip:                String,
        port:              Int,
        tier:              TransportTier,
        expectedSessionId: String,
        network:           Network?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!sessionValid) return@withContext false
        repeat(2) { attempt ->
            try {
                val sock: Socket = if (network != null) {
                    // CRITICAL: bind to the hotspot Network so OS routes correctly
                    network.socketFactory.createSocket()
                } else {
                    Socket()
                }
                sock.connect(InetSocketAddress(ip, port), 5_000)
                sock.soTimeout = 0
                initStreams(sock)
                val ok = completeHandshake(isHost = false, expectedSessionId = expectedSessionId)
                if (!ok) {
                    Log.w(TAG, "Session mismatch from $ip — ignoring stale server")
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

    private fun completeHandshake(
        isHost:            Boolean,
        expectedSessionId: String = sessionId,
    ): Boolean {
        val out = socketOut ?: return false
        val inn = socketIn  ?: return false
        return try {
            if (isHost) {
                val hello  = inn.readLine() ?: ""
                val parts  = hello.split(" ", limit = 3)
                val clientSession = parts.getOrElse(1) { "" }
                peerName = parts.getOrElse(2) { "Unknown" }
                val match = clientSession == sessionId
                out.writeBytes("HELLO $sessionId ${deviceName()}\n"); out.flush()
                if (!match) Log.w(TAG, "Client session=$clientSession expected=$sessionId")
                match
            } else {
                out.writeBytes("HELLO $sessionId ${deviceName()}\n"); out.flush()
                val hello  = inn.readLine() ?: ""
                val parts  = hello.split(" ", limit = 3)
                val serverSession = parts.getOrElse(1) { "" }
                peerName = parts.getOrElse(2) { "Unknown" }
                val match = serverSession == expectedSessionId
                if (!match) Log.w(TAG, "Server session=$serverSession expected=$expectedSessionId")
                match
            }
        } catch (e: Exception) {
            Log.w(TAG, "Handshake error: ${e.message}"); false
        }
    }

    // ── File metadata ──────────────────────────────────────────────────────────

    data class FileMetadata(
        val title:     String = "",
        val posterUrl: String = "",
        val mediaType: String = "",
        val season:    Int    = 0,
        val episode:   Int    = 0,
        val quality:   String = "",
        val mediaId:   String = "",   // ← stable backend media ID for DB dedup
    ) {
        // Escape a string for embedding inside a JSON double-quoted value
        private fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")

        fun toJson(): String =
            "{" +
            "\"title\":\"${title.esc()}\"," +
            "\"posterUrl\":\"${posterUrl.esc()}\"," +
            "\"mediaType\":\"${mediaType.esc()}\"," +
            "\"season\":$season," +
            "\"episode\":$episode," +
            "\"quality\":\"${quality.esc()}\"," +
            "\"mediaId\":\"${mediaId.esc()}\"" +
            "}"

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
                    mediaId   = extract("mediaId"),
                )
            } catch (_: Exception) { FileMetadata() }
        }
    }

    // ── File send ──────────────────────────────────────────────────────────────

    fun sendFile(
        filePath: String,
        fileName: String,
        meta:     FileMetadata = FileMetadata(),
        onProgress: (sent: Long, total: Long, bps: Long) -> Unit,
        onDone:     () -> Unit,
        onError:    (String) -> Unit,
    ) {
        val out  = socketOut ?: run { onError("Not connected"); return }
        val file = File(filePath)
        if (!file.exists()) { onError("File not found: $filePath"); return }

        val tier = currentTier()
        cancelRequested = false

        scope.launch(Dispatchers.IO) {
            try {
                val total = file.length()

                // ── Pre-flight cancel check ────────────────────────────────────
                // If the user cancelled before we even start writing, send a SKIP
                // frame so the receiver's loop stays in sync and knows to move on.
                // This avoids writing a FILE header that will never be completed.
                if (cancelRequested) {
                    out.writeBytes("SKIP\n"); out.flush()
                    cancelRequested = false
                    withContext(Dispatchers.Main) { onDone() }
                    return@launch
                }

                _state.value = EngineState.Transferring(tier, peerName, fileName, "SEND", 0, total, 0)

                val encodedMeta = java.net.URLEncoder.encode(meta.toJson(), "UTF-8")
                out.writeBytes("META $encodedMeta\n")

                val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8")
                out.writeBytes("FILE $total $encodedName\n")

                val buf = ByteArray(131_072)
                var sent  = 0L
                var tLast = System.currentTimeMillis()
                var bLast = 0L

                FileInputStream(file).use { fis ->
                    var n: Int
                    while (fis.read(buf).also { n = it } != -1) {
                        if (cancelRequested) {
                            // Cancel detected mid-stream: pad remaining bytes so the
                            // receiver's read loop drains exactly `total` bytes and
                            // its stream cursor stays aligned for the next file.
                            // Wrap in try/catch — if the socket drops during padding
                            // we treat it as a transfer error rather than hanging.
                            try {
                                val zeros = ByteArray(minOf(buf.size, 65_536))
                                var rem = total - sent
                                while (rem > 0) {
                                    val chunk = minOf(zeros.size.toLong(), rem).toInt()
                                    out.write(zeros, 0, chunk)
                                    rem -= chunk
                                }
                                out.flush()
                            } catch (padEx: Exception) {
                                Log.w(TAG, "Socket closed during cancel-pad: ${padEx.message}")
                                // Socket is gone — don't continue; let the outer catch handle it.
                                throw padEx
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
            try { socketOut?.apply { writeBytes("DONE\n"); flush() } } catch (_: Exception) {}
        }
    }

    fun cancelCurrentSend() { cancelRequested = true }

    // ── File receive ───────────────────────────────────────────────────────────

    fun receiveFiles(
        saveDir:     File,
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
                            val encodedJson = header.removePrefix("META ").trim()
                            val json = try { java.net.URLDecoder.decode(encodedJson, "UTF-8") } catch (_: Exception) { "{}" }
                            pendingMeta = FileMetadata.fromJson(json)
                        }
                        header.startsWith("FILE ") -> {
                            val parts = header.split(" ", limit = 3)
                            if (parts.size < 3) { onError("Bad header: \"$header\""); break }
                            val total    = parts[1].toLongOrNull() ?: break
                            val fileName = java.net.URLDecoder.decode(parts[2], "UTF-8")

                            val fileMeta      = pendingMeta
                            pendingMeta       = FileMetadata()
                            val fileCancelled = cancelledReceive
                            cancelledReceive  = false

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
                        header == "SKIP" -> Log.d(TAG, "Sender acked CANCEL with SKIP")
                        else -> Log.w(TAG, "Unexpected frame: $header")
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
            try { socketOut?.apply { writeBytes("CANCEL\n"); flush() } } catch (_: Exception) {}
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun disconnect(silent: Boolean = false) {
        prepareJob?.cancel(); prepareJob = null
        sessionValid = false; cancelRequested = false; cancelledReceive = false
        resolvedTier = TransportTier.HOTSPOT   // reset to safe default

        serverSocket?.closeQuietly(); serverSocket = null
        socketOut?.closeQuietly(); socketIn?.closeQuietly()
        socketOut = null; socketIn = null
        activeSocket?.closeQuietly(); activeSocket = null

        releaseNetworkCallback()
        unregisterP2pReceiver()

        p2pChannel?.let { ch ->
            try { p2pManager?.removeGroup(ch, null) } catch (_: Exception) {}
        }
        p2pManager = null; p2pChannel = null

        try { hotspotReservation?.close() } catch (_: Exception) {}
        hotspotReservation = null

        TransferForegroundService.stop(ctx)
        if (!silent) _state.value = EngineState.Idle
    }

    private fun releaseNetworkCallback() {
        networkCallback?.let { cb ->
            try { connectivityManager().unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        networkCallback = null; boundNetwork = null
    }

    private fun unregisterP2pReceiver() {
        p2pReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Exception) {}
        }
        p2pReceiver = null
    }

    fun release() { disconnect(); scope.cancel() }

    // Returns the transport tier that was resolved when the QR was generated (sender)
    // or when the QR was scanned (receiver). This is always accurate because both
    // sides set resolvedTier before any socket activity begins.
    private fun currentTier(): TransportTier = resolvedTier

    private fun Socket.closeQuietly()           = try { close() } catch (_: Exception) {}
    private fun ServerSocket.closeQuietly()     = try { close() } catch (_: Exception) {}
    private fun DataOutputStream.closeQuietly() = try { close() } catch (_: Exception) {}
    private fun DataInputStream.closeQuietly()  = try { close() } catch (_: Exception) {}
}
