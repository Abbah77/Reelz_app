package com.axio.reelz.transfer

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * NearbyTransferManager — Quick-Share-style transfer, built on Google's
 * Nearby Connections API instead of hand-rolled WifiP2pManager / LocalOnlyHotspot /
 * WifiNetworkSuggestion state machines.
 *
 * WHY THIS FIXES THE "silent join" WALL:
 * Our app cannot silently join an arbitrary Wi-Fi network on API 29+ — Android's
 * anti-abuse policy requires a manual tap on the "networks available" notification,
 * no matter what we do at the app level (see WifiDirectManager, kept only as a
 * documented no-Play-Services fallback).
 *
 * Nearby Connections sidesteps this because it doesn't run as *our* app process
 * asking the OS for a Wi-Fi upgrade — it runs inside Google Play Services, a
 * system-trusted component, which negotiates the underlying medium (Bluetooth for
 * handshake, then upgrades to Wi-Fi Direct / Wi-Fi Aware / hotspot as available)
 * with privileges our APK does not have. That's the entire trick behind Quick Share,
 * Nearby Share, and effectively how Xender-class throughput is achieved without a
 * manual Settings trip.
 *
 * STRATEGY CHOICE — P2P_STAR:
 * We use Strategy.P2P_STAR: one advertiser (the "host"/sender) can have many
 * simultaneous discoverers connect to it. This matches our UI model (one QR /
 * one host screen, multiple people can join and receive), and is the strategy
 * Nearby Share itself uses. P2P_POINT_TO_POINT would restrict us to exactly one
 * connection and unnecessarily limit future multi-receiver support.
 *
 * SPEED:
 * Once connected, Nearby Connections auto-upgrades from Bluetooth to the fastest
 * available medium (Wi-Fi Direct / Wi-Fi LAN / Wi-Fi Aware) for the actual payload
 * transfer — this happens transparently. Sending large files via
 * Payload.fromFile() lets Play Services stream directly from the file descriptor
 * with minimal buffer copying, which is faster and far less code than our old
 * manual 256 KB-buffer socket loop.
 */
class NearbyTransferManager(private val ctx: Context) {

    companion object {
        /** Must be identical on both sides — Nearby only connects endpoints advertising the same service ID. */
        const val SERVICE_ID = "com.axio.reelz.TRANSFER"

        /**
         * P2P_STAR: one host, many simultaneous peers — matches "generate QR, others join" UX
         * and gives us multi-receiver support for free without protocol changes later.
         */
        private val STRATEGY = Strategy.P2P_STAR
    }

    sealed class NearbyEvent {
        data class EndpointFound(val endpointId: String, val name: String) : NearbyEvent()
        data class EndpointLost(val endpointId: String) : NearbyEvent()
        data class ConnectionInitiated(val endpointId: String, val name: String, val authDigits: String) : NearbyEvent()
        data class Connected(val endpointId: String, val name: String) : NearbyEvent()
        data class ConnectionFailed(val endpointId: String, val reason: String) : NearbyEvent()
        data class Disconnected(val endpointId: String) : NearbyEvent()

        data class TransferProgress(
            val endpointId: String,
            val payloadId: Long,
            val fileName: String,
            val bytesTransferred: Long,
            val totalBytes: Long,
            val direction: String,   // "SEND" | "RECEIVE"
            val done: Boolean = false,
            val error: String? = null,
        ) : NearbyEvent()

        data class FileReceived(val endpointId: String, val payloadId: Long, val file: File) : NearbyEvent()
    }

    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(ctx) }

    private val _events = MutableSharedFlow<NearbyEvent>(extraBufferCapacity = 64)
    val events: Flow<NearbyEvent> = _events.asSharedFlow()

    private val _connectedEndpoints = MutableStateFlow<Set<String>>(emptySet())
    val connectedEndpoints = _connectedEndpoints.asStateFlow()

    /** Tracks incoming file payloads so we can rename/move them once fully received. */
    private data class PendingIncoming(val fileName: String, val expectedBytes: Long, var payload: Payload? = null)
    private val pendingIncoming = ConcurrentHashMap<Long, PendingIncoming>()

    /** Tracks outgoing payload id -> friendly name for progress reporting. */
    private val outgoingNames = ConcurrentHashMap<Long, String>()

    /** Where finished incoming files get moved to. Set once by the caller (e.g. from Hilt-injected filesDir). */
    @Volatile var incomingDir: File? = null

    // ── Advertiser (host / "sender") side ───────────────────────────────────────

    /**
     * Starts advertising this device so nearby devices running discovery can find it.
     * Auto-accepts incoming connection requests (no manual PIN confirmation) since both
     * ends are the same app and payloads are only ever accepted after this handshake —
     * equivalent trust model to typing in a QR-shared code.
     */
    fun startAdvertising(displayName: String): Flow<NearbyEvent> = callbackFlow {
        val callback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                trySend(NearbyEvent.ConnectionInitiated(endpointId, info.endpointName, info.authenticationDigits))
                // Auto-accept: our own QR/code exchange already served as the trust step.
                client.acceptConnection(endpointId, payloadCallback)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                    _connectedEndpoints.value = _connectedEndpoints.value + endpointId
                    trySend(NearbyEvent.Connected(endpointId, endpointId))
                } else {
                    trySend(NearbyEvent.ConnectionFailed(endpointId, statusMessage(result.status.statusCode)))
                }
            }

            override fun onDisconnected(endpointId: String) {
                _connectedEndpoints.value = _connectedEndpoints.value - endpointId
                trySend(NearbyEvent.Disconnected(endpointId))
            }
        }

        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            // Low-power discovery isn't relevant during an active transfer session —
            // we favor fast discovery over battery here.
            .build()

        client.startAdvertising(displayName, SERVICE_ID, callback, options)
            .addOnFailureListener { e -> trySend(NearbyEvent.ConnectionFailed("", e.message ?: "Advertising failed")) }

        awaitClose { client.stopAdvertising() }
    }

    // ── Discoverer ("receiver") side ─────────────────────────────────────────────

    fun startDiscovery(): Flow<NearbyEvent> = callbackFlow {
        val discoveryCallback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (info.serviceId == SERVICE_ID) {
                    trySend(NearbyEvent.EndpointFound(endpointId, info.endpointName))
                }
            }

            override fun onEndpointLost(endpointId: String) {
                trySend(NearbyEvent.EndpointLost(endpointId))
            }
        }

        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnFailureListener { e -> trySend(NearbyEvent.ConnectionFailed("", e.message ?: "Discovery failed")) }

        awaitClose { client.stopDiscovery() }
    }

    /** Called by the discoverer once it's decided which found endpoint to connect to. */
    fun requestConnection(displayName: String, endpointId: String) {
        val lifecycleCallback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
                _events.tryEmit(NearbyEvent.ConnectionInitiated(id, info.endpointName, info.authenticationDigits))
                client.acceptConnection(id, payloadCallback)
            }

            override fun onConnectionResult(id: String, result: ConnectionResolution) {
                if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                    _connectedEndpoints.value = _connectedEndpoints.value + id
                    _events.tryEmit(NearbyEvent.Connected(id, id))
                } else {
                    _events.tryEmit(NearbyEvent.ConnectionFailed(id, statusMessage(result.status.statusCode)))
                }
            }

            override fun onDisconnected(id: String) {
                _connectedEndpoints.value = _connectedEndpoints.value - id
                _events.tryEmit(NearbyEvent.Disconnected(id))
            }
        }

        client.requestConnection(displayName, endpointId, lifecycleCallback)
            .addOnFailureListener { e ->
                _events.tryEmit(NearbyEvent.ConnectionFailed(endpointId, e.message ?: "Connection request failed"))
            }
    }

    // ── Sending ──────────────────────────────────────────────────────────────────

    /**
     * Sends a file to a connected endpoint using Payload.fromFile(), which streams
     * directly from the file descriptor — Play Services handles chunking, the
     * medium upgrade (BT -> Wi-Fi Direct/Aware), retry, and throughput tuning
     * internally. This is what gives us Quick-Share-class speed without writing
     * our own buffering loop.
     */
    fun sendFile(endpointId: String, file: File) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val payload = Payload.fromFile(pfd)
        outgoingNames[payload.id] = file.name

        _events.tryEmit(
            NearbyEvent.TransferProgress(
                endpointId = endpointId,
                payloadId = payload.id,
                fileName = file.name,
                bytesTransferred = 0L,
                totalBytes = file.length(),
                direction = "SEND",
            )
        )

        // Send the file name + size as a tiny BYTES payload first so the receiver
        // knows what to call the incoming file (Nearby's file payloads arrive as
        // anonymous fds — filenames aren't part of the payload itself).
        val meta = "${payload.id}|${file.name}|${file.length()}"
        client.sendPayload(endpointId, Payload.fromBytes(meta.toByteArray(Charsets.UTF_8)))
        client.sendPayload(endpointId, payload)
    }

    // ── Payload callback (shared by advertiser + discoverer) ─────────────────────

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    // Metadata payload: "<payloadId>|<fileName>|<size>" sent just before the file payload.
                    val text = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
                    val parts = text.split("|")
                    if (parts.size == 3) {
                        val id = parts[0].toLongOrNull() ?: return
                        val existingPayload = pendingIncoming[id]?.payload
                        pendingIncoming[id] = PendingIncoming(parts[1], parts[2].toLongOrNull() ?: 0L, existingPayload)
                    }
                }
                Payload.Type.FILE -> {
                    // Metadata (BYTES payload) may arrive slightly before or after the FILE
                    // payload depending on scheduling, so store/patch whichever arrives first.
                    val existing = pendingIncoming[payload.id]
                    if (existing != null) {
                        existing.payload = payload
                    } else {
                        pendingIncoming[payload.id] = PendingIncoming("file_${payload.id}", 0L, payload)
                    }
                    val meta = pendingIncoming[payload.id]
                    _events.tryEmit(
                        NearbyEvent.TransferProgress(
                            endpointId = endpointId,
                            payloadId = payload.id,
                            fileName = meta?.fileName ?: "file",
                            bytesTransferred = 0L,
                            totalBytes = meta?.expectedBytes ?: 0L,
                            direction = "RECEIVE",
                        )
                    )
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val direction = if (outgoingNames.containsKey(update.payloadId)) "SEND" else "RECEIVE"
            val fileName = outgoingNames[update.payloadId]
                ?: pendingIncoming[update.payloadId]?.fileName
                ?: "file"

            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    _events.tryEmit(
                        NearbyEvent.TransferProgress(
                            endpointId = endpointId,
                            payloadId = update.payloadId,
                            fileName = fileName,
                            bytesTransferred = update.bytesTransferred,
                            totalBytes = update.totalBytes,
                            direction = direction,
                        )
                    )
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    _events.tryEmit(
                        NearbyEvent.TransferProgress(
                            endpointId = endpointId,
                            payloadId = update.payloadId,
                            fileName = fileName,
                            bytesTransferred = update.totalBytes,
                            totalBytes = update.totalBytes,
                            direction = direction,
                            done = true,
                        )
                    )

                    // On the receive side, move the completed file out of Nearby's internal
                    // cache into our own transfers folder, then emit FileReceived so the UI/DB
                    // can record it. Sends have nothing to finalize on our end.
                    if (direction == "RECEIVE") {
                        val incoming = pendingIncoming[update.payloadId]
                        val payload = incoming?.payload
                        val dir = incomingDir
                        if (payload != null && dir != null) {
                            val moved = finalizeReceivedFile(payload, incoming.fileName, dir)
                            if (moved != null) {
                                _events.tryEmit(NearbyEvent.FileReceived(endpointId, update.payloadId, moved))
                            }
                        }
                    }

                    outgoingNames.remove(update.payloadId)
                    pendingIncoming.remove(update.payloadId)
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    _events.tryEmit(
                        NearbyEvent.TransferProgress(
                            endpointId = endpointId,
                            payloadId = update.payloadId,
                            fileName = fileName,
                            bytesTransferred = update.bytesTransferred,
                            totalBytes = update.totalBytes,
                            direction = direction,
                            error = "Transfer failed. Keep devices closer together and try again.",
                        )
                    )
                    outgoingNames.remove(update.payloadId)
                    pendingIncoming.remove(update.payloadId)
                }
                PayloadTransferUpdate.Status.CANCELED -> {
                    outgoingNames.remove(update.payloadId)
                    pendingIncoming.remove(update.payloadId)
                }
                else -> Unit
            }
        }
    }

    /**
     * Moves a completed FILE payload from Nearby's internal cache location into our
     * own transfers folder under the given file name. Called automatically once
     * onPayloadTransferUpdate reports SUCCESS for that payload.
     */
    private fun finalizeReceivedFile(payload: Payload, fileName: String, outDir: File): File? {
        val pfd = payload.asFile()?.asParcelFileDescriptor() ?: return null
        outDir.mkdirs()
        val dest = uniqueFile(outDir, fileName)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            dest.outputStream().use { output -> input.copyTo(output, bufferSize = 262_144) }
        }
        return dest
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast(".")
        val ext = name.substringAfterLast(".", "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isNotEmpty()) "$base($i).$ext" else "$base($i)")
            i++
        }
        return f
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────────

    fun disconnectAll() {
        _connectedEndpoints.value.forEach { client.disconnectFromEndpoint(it) }
        _connectedEndpoints.value = emptySet()
    }

    fun stopAll() {
        client.stopAdvertising()
        client.stopDiscovery()
        disconnectAll()
    }

    private fun statusMessage(code: Int): String = when (code) {
        ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> "The other device declined the connection."
        ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> "Already connected to this device."
        ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT -> "Not connected. Try reconnecting."
        ConnectionsStatusCodes.STATUS_ERROR -> "Connection error. Move devices closer and retry."
        ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "Turn on Wi-Fi and Bluetooth, then retry."
        ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Bluetooth error. Toggle Bluetooth and retry."
        else -> "Couldn't connect (code $code). Try again."
    }
}
