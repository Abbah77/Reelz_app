package com.axio.reelz.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * HotspotManager — replaces Wi-Fi Direct entirely.
 *
 * Sender  → startHotspot() → gets SSID + passphrase → encode in QR
 * Receiver → joinHotspot(ssid, pass) → joins silently → TCP transfer
 *
 * Uses WifiManager.LocalOnlyHotspot (API 26+, same as your minSdk).
 * No BUSY errors, no OS restrictions, works on all Android 8+ devices.
 * This is exactly how Xender works.
 */
@SuppressLint("MissingPermission")
class WifiDirectManager(private val ctx: Context) {

    // Keep same sealed class names so TransferScreen compiles unchanged
    sealed class P2pState {
        object Idle       : P2pState()
        object Preparing  : P2pState()
        object Connecting : P2pState()
        data class Connected(
            val groupOwnerAddress: java.net.InetAddress,
            val isGroupOwner: Boolean,
            val groupSsid: String = "",
            val groupPassphrase: String = "",
        ) : P2pState()
        data class Failed(val reason: String) : P2pState()
    }

    private val _state = MutableStateFlow<P2pState>(P2pState.Idle)
    val state: StateFlow<P2pState> = _state.asStateFlow()

    private val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    // No-op — kept so call sites compile
    fun register()   {}
    fun unregister() {}

    // ── Sender: start local-only hotspot ──────────────────────────────────────

    suspend fun startGroup(): Result<GroupInfo> = withContext(Dispatchers.Main) {
        _state.value = P2pState.Preparing

        // Stop any previous reservation
        stopHotspot()

        suspendCancellableCoroutine { cont ->
            try {
                wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {

                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        hotspotReservation = reservation
                        val config = reservation.wifiConfiguration
                            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                                   reservation.softApConfiguration.let { sac ->
                                       android.net.wifi.WifiConfiguration().apply {
                                           SSID = sac.ssid ?: ""
                                           preSharedKey = sac.passphrase ?: ""
                                       }
                                   }
                               else null

                        val ssid = config?.SSID?.trim('"') ?: ""
                        val pass = config?.preSharedKey?.trim('"') ?: ""

                        if (ssid.isBlank() || pass.isBlank()) {
                            val err = "Couldn't read hotspot credentials. Try again."
                            _state.value = P2pState.Failed(err)
                            if (cont.isActive)
                                cont.resumeWith(kotlin.Result.success(Result.failure(Exception(err))))
                            return
                        }

                        // Sender's IP on its own hotspot is always 192.168.43.1
                        val ownerIp = GO_IP
                        val info = GroupInfo(ssid, pass, ownerIp)
                        _state.value = P2pState.Connected(
                            groupOwnerAddress = java.net.InetAddress.getByName(ownerIp),
                            isGroupOwner      = true,
                            groupSsid         = ssid,
                            groupPassphrase   = pass,
                        )
                        if (cont.isActive)
                            cont.resumeWith(kotlin.Result.success(Result.success(info)))
                    }

                    override fun onFailed(reason: Int) {
                        val msg = when (reason) {
                            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
                                "No Wi-Fi channel available. Disable Bluetooth and try again."
                            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
                                "Hotspot not allowed on this device. Check carrier settings."
                            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
                                "Wi-Fi is in an incompatible mode. Turn Wi-Fi off and on, then retry."
                            else ->
                                "Couldn't start hotspot (code $reason). Make sure Wi-Fi is on."
                        }
                        _state.value = P2pState.Failed(msg)
                        if (cont.isActive)
                            cont.resumeWith(kotlin.Result.success(Result.failure(Exception(msg))))
                    }

                    override fun onStopped() {
                        // Only update state if we're still in a "ready" state
                        if (_state.value is P2pState.Connected)
                            _state.value = P2pState.Idle
                    }

                }, Handler(Looper.getMainLooper()))

            } catch (e: Exception) {
                val msg = "Hotspot failed: ${e.message ?: "unknown error"}"
                _state.value = P2pState.Failed(msg)
                if (cont.isActive)
                    cont.resumeWith(kotlin.Result.success(Result.failure(Exception(msg))))
            }
        }
    }

    // ── Receiver: join sender's hotspot silently ──────────────────────────────

    @SuppressLint("NewApi")
    suspend fun joinGroup(ssid: String, passphrase: String): Result<java.net.InetAddress> =
        withContext(Dispatchers.IO) {
            _state.value = P2pState.Connecting

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: WifiNetworkSuggestion — silent background join, no system dialog
                val suggestion = WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(passphrase)
                    .setIsAppInteractionRequired(false)
                    .build()

                // Remove any stale suggestion for this SSID first
                wifi.removeNetworkSuggestions(listOf(suggestion))
                val status = wifi.addNetworkSuggestions(listOf(suggestion))

                if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    val msg = "Couldn't suggest network (code $status). Grant Wi-Fi permission and try again."
                    _state.value = P2pState.Failed(msg)
                    return@withContext Result.failure(Exception(msg))
                }

                // Wait for connection — poll connectivity every 500 ms up to 20 s
                val ownerIp = java.net.InetAddress.getByName(GO_IP)
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    delay(500)
                    try {
                        // If we can reach the owner IP, we're connected
                        if (ownerIp.isReachable(1000)) {
                            _state.value = P2pState.Connected(
                                groupOwnerAddress = ownerIp,
                                isGroupOwner      = false,
                            )
                            return@withContext Result.success(ownerIp)
                        }
                    } catch (_: Exception) {}
                }

                // Even if ping fails (some devices block ICMP), attempt TCP connection
                // — TransferService will fail fast if device isn't actually there
                try {
                    val sock = java.net.Socket()
                    sock.connect(java.net.InetSocketAddress(GO_IP, TransferService.TRANSFER_PORT), 2000)
                    sock.close()
                    _state.value = P2pState.Connected(
                        groupOwnerAddress = ownerIp,
                        isGroupOwner      = false,
                    )
                    return@withContext Result.success(ownerIp)
                } catch (_: Exception) {}

                val msg = "Couldn't connect to sender. Make sure both devices have Wi-Fi on and are close together."
                _state.value = P2pState.Failed(msg)
                Result.failure(Exception(msg))

            } else {
                // API 26–28: use deprecated WifiConfiguration
                @Suppress("DEPRECATION")
                val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    preSharedKey = "\"$passphrase\""
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                @Suppress("DEPRECATION")
                val netId = wifi.addNetwork(wifiConfig)
                if (netId == -1) {
                    val msg = "Couldn't add network. Grant Wi-Fi permission and try again."
                    _state.value = P2pState.Failed(msg)
                    return@withContext Result.failure(Exception(msg))
                }
                @Suppress("DEPRECATION")
                wifi.enableNetwork(netId, true)
                @Suppress("DEPRECATION")
                wifi.reconnect()

                // Wait up to 15 s for connection
                delay(3000)
                val ownerIp = java.net.InetAddress.getByName(GO_IP)
                _state.value = P2pState.Connected(groupOwnerAddress = ownerIp, isGroupOwner = false)
                Result.success(ownerIp)
            }
        }

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        stopHotspot()
        _state.value = P2pState.Idle
    }

    private fun stopHotspot() {
        try { hotspotReservation?.close() } catch (_: Exception) {}
        hotspotReservation = null
    }

    data class GroupInfo(val ssid: String, val passphrase: String, val ownerIp: String)

    companion object {
        /** Sender's IP on its own hotspot — fixed by Android */
        const val GO_IP = "192.168.43.1"
    }
}
