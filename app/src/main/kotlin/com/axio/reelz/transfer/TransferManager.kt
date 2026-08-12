package com.axio.reelz.transfer

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TransferManager — Nearby Connections logic extracted from TransferScreen.
 *
 * Per the restructure plan, TransferScreen.kt was 1,134 lines with all
 * business logic embedded. This class owns:
 *  - Nearby Connections lifecycle (advertising, discovering, connecting)
 *  - QR code generation/scanning coordination
 *  - Transfer state machine
 *  - TransferRepository for persisting transfer history
 *
 * TransferScreen observes TransferViewModel which coordinates TransferManager.
 * TransferScreen never accesses TransferManager directly.
 *
 * Dependency direction: TransferManager → TransferRepository → Room.
 * Never references UI or Activity.
 */
@Singleton
class TransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transferRepository: TransferRepository,
) {
    private val tag = "TransferManager"

    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    // ── Advertising ───────────────────────────────────────────────────────────

    fun startAdvertising(deviceName: String) {
        Log.d(tag, "startAdvertising: $deviceName")
        _state.update { it.copy(phase = TransferPhase.Advertising, localDeviceName = deviceName) }
        // TODO: wire Nearby Connections Advertising API here
    }

    fun stopAdvertising() {
        Log.d(tag, "stopAdvertising")
        _state.update { it.copy(phase = TransferPhase.Idle) }
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    fun startDiscovery() {
        Log.d(tag, "startDiscovery")
        _state.update { it.copy(phase = TransferPhase.Discovering) }
        // TODO: wire Nearby Connections Discovery API here
    }

    fun stopDiscovery() {
        Log.d(tag, "stopDiscovery")
        _state.update { it.copy(phase = TransferPhase.Idle) }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun requestConnection(endpointId: String) {
        Log.d(tag, "requestConnection: $endpointId")
        _state.update { it.copy(phase = TransferPhase.Connecting, pendingEndpointId = endpointId) }
    }

    fun acceptConnection(endpointId: String) {
        Log.d(tag, "acceptConnection: $endpointId")
        _state.update { it.copy(phase = TransferPhase.Connected, connectedEndpointId = endpointId) }
    }

    fun disconnect() {
        Log.d(tag, "disconnect")
        _state.update { TransferState() }
    }

    // ── Transfer ──────────────────────────────────────────────────────────────

    fun sendFile(localPath: String) {
        Log.d(tag, "sendFile: $localPath")
        _state.update { it.copy(phase = TransferPhase.Transferring, transferFilePath = localPath) }
        // TODO: wire Nearby Connections Payload API here
    }

    fun release() {
        stopAdvertising()
        stopDiscovery()
        disconnect()
    }
}

// ── State ─────────────────────────────────────────────────────────────────────

data class TransferState(
    val phase: TransferPhase           = TransferPhase.Idle,
    val localDeviceName: String        = "",
    val pendingEndpointId: String      = "",
    val connectedEndpointId: String    = "",
    val transferFilePath: String       = "",
    val progressPercent: Int           = 0,
    val errorMessage: String?          = null,
)

enum class TransferPhase {
    Idle, Advertising, Discovering, Connecting, Connected, Transferring, Done, Error
}

// ── TransferProgress — live progress emitted during a transfer ────────────────

data class TransferProgress(
    val fileName: String = "",
    val direction: String = "SEND",   // "SEND" | "RECEIVE"
    val peerName: String = "",
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBps: Long = 0L,
    val done: Boolean = false,
    val error: String? = null,
)
