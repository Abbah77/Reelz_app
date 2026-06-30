package com.axio.reelz.transfer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetAddress

@SuppressLint("MissingPermission")
class WifiDirectManager(private val ctx: Context) {

    sealed class P2pState {
        object Idle       : P2pState()
        object Preparing  : P2pState()
        object Connecting : P2pState()
        data class Connected(
            val groupOwnerAddress: InetAddress,
            val isGroupOwner: Boolean,
            val groupSsid: String = "",
            val groupPassphrase: String = "",
        ) : P2pState()
        data class Failed(val reason: String) : P2pState()
    }

    private val _state = MutableStateFlow<P2pState>(P2pState.Idle)
    val state: StateFlow<P2pState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val manager: WifiP2pManager =
        ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel =
        manager.initialize(ctx, Looper.getMainLooper(), null)

    // ── FIX 1: Added PEERS_CHANGED and DISCOVERY_CHANGED actions ────────────
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)       // NEW
        addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)   // NEW
    }

    // ── FIX 2: Guard against double-registration ─────────────────────────────
    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.w(TAG, "Wi-Fi P2P is disabled — cannot proceed")
                        _state.value = P2pState.Failed(
                            "Wi-Fi Direct is not available on this device. " +
                            "Please enable Wi-Fi and try again."
                        )
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info: WifiP2pInfo? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        else @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)

                    if (info?.groupFormed == true && info.groupOwnerAddress != null) {
                        val cur = _state.value
                        _state.value = P2pState.Connected(
                            groupOwnerAddress = info.groupOwnerAddress,
                            isGroupOwner      = info.isGroupOwner,
                            groupSsid         = if (cur is P2pState.Connected) cur.groupSsid else "",
                            groupPassphrase   = if (cur is P2pState.Connected) cur.groupPassphrase else "",
                        )
                    }
                }
                // Other actions (peers changed, discovery changed) can be handled
                // here if you add peer-discovery UI in the future.
            }
        }
    }

    fun register() {
        if (isRegistered) return   // FIX 2: prevent duplicate registration
        ctx.registerReceiver(receiver, intentFilter)
        isRegistered = true
    }

    fun unregister() {
        if (!isRegistered) return  // FIX 2: prevent unregister-without-register crash
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        isRegistered = false
    }

    // ── Sender: create group ──────────────────────────────────────────────────

    suspend fun startGroup(): Result<GroupInfo> {
        _state.value = P2pState.Preparing

        // Step 1: clean slate
        resetOnMain()

        // ── FIX 3: Exponential backoff — gives the Wi-Fi stack more time to reset
        repeat(3) { attempt ->
            val created = tryCreateGroup()
            if (!created) {
                val waitMs = 1500L * (attempt + 1)   // 1.5s, 3s, 4.5s
                Log.w(TAG, "createGroup busy on attempt $attempt, waiting ${waitMs}ms")
                delay(waitMs)
                resetOnMain()
                return@repeat
            }

            // Poll for group credentials (populated async by framework)
            repeat(20) {
                delay(500)
                val info = requestGroupInfoOnMain() ?: return@repeat
                if (info.networkName.isNotBlank() && info.passphrase.isNotBlank()) {
                    val result = GroupInfo(info.networkName, info.passphrase, GO_IP)
                    _state.value = P2pState.Connected(
                        groupOwnerAddress = InetAddress.getByName(GO_IP),
                        isGroupOwner      = true,
                        groupSsid         = result.ssid,
                        groupPassphrase   = result.passphrase,
                    )
                    return Result.success(result)
                }
            }
        }

        val err = "Couldn't start Wi-Fi Direct after 3 attempts. Toggle Wi-Fi off/on and try again."
        _state.value = P2pState.Failed(err)
        return Result.failure(Exception(err))
    }

    // ── Receiver: join group ──────────────────────────────────────────────────

    @SuppressLint("NewApi")
    suspend fun joinGroup(ssid: String, passphrase: String): Result<InetAddress> {
        _state.value = P2pState.Connecting
        resetOnMain()

        // ── FIX 4: Android 9 and below cannot use the SSID/passphrase API.
        //    The WpsInfo.PBC fallback with no deviceAddress silently fails.
        //    We detect this early and return a clear error instead of timing out.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val err = "Direct join via QR requires Android 10 or later. " +
                      "On Android 9 and below, use the peer-discovery (invite) flow instead."
            _state.value = P2pState.Failed(err)
            return Result.failure(Exception(err))
        }

        val config = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(passphrase)
            .build()

        val connected = connectOnMain(config)
        if (!connected) {
            val err = "Connection failed. Make sure Wi-Fi is on and you're scanning a Reelz QR code."
            _state.value = P2pState.Failed(err)
            return Result.failure(Exception(err))
        }

        // Wait for the broadcast receiver to confirm group formed
        val result = withTimeoutOrNull(15_000) {
            state.filter { it is P2pState.Connected }.first() as P2pState.Connected
        }

        return if (result != null) {
            Result.success(result.groupOwnerAddress)
        } else {
            val err = "Connection timed out. Move devices closer and try again."
            _state.value = P2pState.Failed(err)
            Result.failure(Exception(err))
        }
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    fun disconnect() {
        CoroutineScope(Dispatchers.Main).launch { resetOnMain() }
        _state.value = P2pState.Idle
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun resetOnMain() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resumeWith(kotlin.Result.success(Unit)) }
                override fun onFailure(r: Int) { if (cont.isActive) cont.resumeWith(kotlin.Result.success(Unit)) }
            })
        }
        suspendCancellableCoroutine<Unit> { cont ->
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resumeWith(kotlin.Result.success(Unit)) }
                override fun onFailure(r: Int) { if (cont.isActive) cont.resumeWith(kotlin.Result.success(Unit)) }
            })
        }
        delay(1_200)  // slightly longer than original 1000ms for safer framework teardown
    }

    private suspend fun tryCreateGroup(): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (cont.isActive) cont.resumeWith(kotlin.Result.success(true))
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "createGroup failed: reason=$reason " +
                        "(0=ERROR, 1=UNSUPPORTED, 2=BUSY)")
                    if (cont.isActive) cont.resumeWith(kotlin.Result.success(false))
                }
            })
        }
    }

    private suspend fun requestGroupInfoOnMain(): WifiP2pGroup? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            manager.requestGroupInfo(channel) { group ->
                if (cont.isActive) cont.resumeWith(kotlin.Result.success(group))
            }
        }
    }

    private suspend fun connectOnMain(config: WifiP2pConfig): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resumeWith(kotlin.Result.success(true)) }
                override fun onFailure(r: Int) {
                    Log.w(TAG, "connect failed: reason=$r")
                    if (cont.isActive) cont.resumeWith(kotlin.Result.success(false))
                }
            })
        }
    }

    data class GroupInfo(val ssid: String, val passphrase: String, val ownerIp: String)

    companion object {
        const val GO_IP = "192.168.49.1"
        private const val TAG = "WifiDirectManager"
    }
}
