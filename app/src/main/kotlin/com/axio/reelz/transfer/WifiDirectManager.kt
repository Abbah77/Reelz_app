package com.axio.reelz.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ⚠️ LEGACY / UNUSED — kept only as a reference no-Play-Services fallback.
 *
 * As of the Nearby Connections rewrite (see NearbyTransferManager), this class is no
 * longer wired into TransferViewModel/TransferScreen. It's kept in the tree in case we
 * ever need a fallback path for devices without Google Play Services (some AOSP/no-GMS
 * builds), but it is NOT currently instantiated anywhere in the app.
 *
 * The core problem with this approach: `joinGroup()`'s silent-join via
 * WifiNetworkSuggestion is blocked by Android's anti-abuse policy on API 29+ — the OS
 * requires a manual tap on a system notification no matter what this code does. See
 * NearbyTransferManager's kdoc for why Play Services can do what this class cannot.
 *
 * Original doc (inaccurate — kept for history):
 * "HotspotManager — replaces Wi-Fi Direct entirely. No BUSY errors, no OS restrictions,
 * works on all Android 8+ devices. This is exactly how Xender works." — none of this
 * held up: OS restrictions apply regardless, and Xender does not use this mechanism.
 */
@Deprecated("Replaced by NearbyTransferManager — not used by TransferViewModel anymore. Kept as a no-Play-Services fallback reference only.")
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
    private val cm   = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * The actual Network object for the hotspot Wi-Fi link (receiver side).
     * Sockets must be bound to this via network.socketFactory / network.bindSocket()
     * or ALL traffic (including our TCP transfer) can silently route over mobile
     * data instead, on OEMs that don't auto-prioritize the joined Wi-Fi network.
     * This is set once the suggestion is actually connected.
     */
    @Volatile var boundNetwork: Network? = null
        private set

    // No-op — kept so call sites compile
    fun register()   {}
    fun unregister() {
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        boundNetwork = null
        publishBoundNetwork(null)
    }

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

                        // Read the sender's ACTUAL hotspot IP — do not assume 192.168.43.1.
                        // OEMs (Samsung, MIUI, HiOS/XOS on Tecno/Itel, etc.) frequently hand
                        // out a different subnet. This is read from the WifiManager's own
                        // link info once the hotspot is up.
                        val ownerIp = readLocalHotspotIp() ?: GO_IP
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

    /**
     * Reads this device's own IP address on the local-only hotspot interface
     * (the "ap" / "swlan" interface), which is what the receiver must dial.
     * Falls back to null if it can't be determined (caller uses GO_IP as last resort).
     */
    private fun readLocalHotspotIp(): String? = try {
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .filter { iface ->
                val n = iface.name.lowercase()
                iface.isUp && !iface.isLoopback &&
                    (n.contains("ap") || n.contains("softap") || n.contains("swlan") || n.contains("wlan"))
            }
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .firstOrNull { addr ->
                !addr.isLoopbackAddress && addr is java.net.Inet4Address &&
                    addr.hostAddress?.startsWith("192.168.") == true
            }
            ?.hostAddress
    } catch (_: Exception) { null }

    // ── Receiver: join sender's hotspot silently ──────────────────────────────

    @SuppressLint("NewApi")
    suspend fun joinGroup(ssid: String, passphrase: String): Result<java.net.InetAddress> =
        withContext(Dispatchers.IO) {
            _state.value = P2pState.Connecting

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: WifiNetworkSuggestion — silent background join where the OEM
                // allows it; some OEMs surface a one-tap "Networks available" notification
                // instead of a silent join. Nothing we can do about that at the API level —
                // it's an Android anti-abuse protection, not something we can suppress.
                val suggestion = WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(passphrase)
                    .setIsAppInteractionRequired(false)
                    .build()

                wifi.removeNetworkSuggestions(listOf(suggestion))
                val status = wifi.addNetworkSuggestions(listOf(suggestion))

                if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    val msg = "Couldn't suggest network (code $status). Grant Wi-Fi permission and try again."
                    _state.value = P2pState.Failed(msg)
                    return@withContext Result.failure(Exception(msg))
                }

                // CRITICAL: request the actual Network object for this suggestion via
                // ConnectivityManager, rather than assuming a fixed gateway IP or hoping
                // the OS auto-routes our traffic there. This does two things:
                //  1. Gives us the REAL gateway IP for this specific device/OEM/subnet.
                //  2. Lets us bind our sockets to this exact Network so transfer traffic
                //     can't silently leak out over mobile data on devices that don't
                //     auto-prioritize the joined Wi-Fi (a very common OEM quirk).
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                val result = suspendCancellableCoroutine<Result<java.net.InetAddress>> { netCont ->
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            boundNetwork = network
                            publishBoundNetwork(network)
                            // Bind THIS PROCESS's future sockets to this network by default —
                            // belt-and-suspenders alongside explicit per-socket binding in
                            // TransferService.
                            cm.bindProcessToNetwork(network)

                            val gatewayIp = resolveGatewayIp(network) ?: GO_IP
                            val addr = java.net.InetAddress.getByName(gatewayIp)
                            _state.value = P2pState.Connected(
                                groupOwnerAddress = addr,
                                isGroupOwner      = false,
                            )
                            if (netCont.isActive) netCont.resumeWith(kotlin.Result.success(Result.success(addr)))
                        }

                        override fun onUnavailable() {
                            // Android 10+ deliberately does not let apps silently join a
                            // suggested Wi-Fi network — it posts a "Wi-Fi networks
                            // available" system notification and requires one manual tap.
                            // This is Android anti-abuse behavior we cannot bypass at the
                            // API level. onUnavailable() fires once our timeout below
                            // expires without that tap happening, or the join failing
                            // outright (wrong password, sender's hotspot already gone, etc).
                            val msg = "Couldn't connect automatically. Pull down your " +
                                "notification shade, tap the \"Wi-Fi networks available\" " +
                                "notification, choose this network, then come back and tap Try Again."
                            _state.value = P2pState.Failed(msg)
                            if (netCont.isActive) netCont.resumeWith(kotlin.Result.success(Result.failure(Exception(msg))))
                        }

                        override fun onLost(network: Network) {
                            if (boundNetwork == network) boundNetwork = null
                        }
                    }
                    networkCallback = callback
                    // 45s, not 20s: the user has to notice the system notification in
                    // their status bar, pull it down, and tap it — that round trip
                    // regularly takes longer than 20s, especially on first use when
                    // they don't yet know to look for it.
                    cm.requestNetwork(request, callback, 45_000)
                }

                result

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

                // Wait up to a few seconds, then read the ACTUAL DHCP gateway rather
                // than assuming 192.168.43.1 — older OEM builds vary just as much.
                delay(3000)
                @Suppress("DEPRECATION")
                val dhcp = wifi.dhcpInfo
                val gatewayIp = if (dhcp != null && dhcp.gateway != 0) {
                    val g = dhcp.gateway
                    "${g and 0xff}.${g shr 8 and 0xff}.${g shr 16 and 0xff}.${g shr 24 and 0xff}"
                } else GO_IP
                val ownerIp = java.net.InetAddress.getByName(gatewayIp)
                _state.value = P2pState.Connected(groupOwnerAddress = ownerIp, isGroupOwner = false)
                Result.success(ownerIp)
            }
        }

    /** Resolves the gateway IP for a given Network via its LinkProperties — the real, OEM-correct value. */
    private fun resolveGatewayIp(network: Network): String? = try {
        val lp = cm.getLinkProperties(network)
        lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway
            ?.hostAddress
    } catch (_: Exception) { null }

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        stopHotspot()
        cm.bindProcessToNetwork(null)
        unregister()
        _state.value = P2pState.Idle
    }

    private fun stopHotspot() {
        try { hotspotReservation?.close() } catch (_: Exception) {}
        hotspotReservation = null
    }

    data class GroupInfo(val ssid: String, val passphrase: String, val ownerIp: String)

    private fun publishBoundNetwork(n: Network?) { NetworkBindingHolder.current = n }

    companion object {
        /** Sender's IP on its own hotspot — fixed by Android */
        const val GO_IP = "192.168.43.1"
    }
}
