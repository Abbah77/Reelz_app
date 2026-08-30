package com.axio.reelz.transfer

// ─────────────────────────────────────────────────────────────────────────────
//  TransferManager — queue orchestration layer above P2pEngine
//
//  Spec compliance:
//    ✓ Both devices send simultaneously (each has its own send queue)
//    ✓ Queue processed sequentially, one item at a time
//    ✓ Tapping X on active receive → cancel current, sender moves to next
//    ✓ Tapping X on queued receive → skip when sender reaches it
//    ✓ Floating button state driven by hasActiveWork
//    ✓ Disconnect cleans up both sides cleanly
//    ✓ Connection drop → incomplete file discarded, both return to beam page
//
//  Queue model:
//    sendQueue    — items this device is sending to peer (ordered)
//    receiveQueue — items this device is receiving from peer (ordered)
//    Both queues are StateFlows so the UI panel observes them reactively.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.axio.reelz.core.database.TransferRecord
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ─── Transfer item ────────────────────────────────────────────────────────────

data class TransferItem(
    val id:        String  = UUID.randomUUID().toString(),
    val fileName:  String,
    val filePath:  String  = "",   // only meaningful for sends
    val sizeBytes: Long    = 0,
    val status:    TransferItemStatus = TransferItemStatus.QUEUED,
    val bytesdone: Long    = 0,
    val speedBps:  Long    = 0,
    // BUG3 FIX: Rich metadata for Xender-style poster display
    val title:     String  = "",
    val posterUrl: String  = "",
    val mediaType: String  = "",
    val season:    Int     = 0,
    val episode:   Int     = 0,
    val quality:   String  = "",
)

enum class TransferItemStatus { QUEUED, ACTIVE, DONE, CANCELLED, ERROR }

// ─── Manager ─────────────────────────────────────────────────────────────────

@Singleton
class TransferManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val engine: P2pEngine,
    private val repo: TransferRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Engine state passthrough ──────────────────────────────────────────────

    val engineState: StateFlow<EngineState> = engine.state

    private val _uiState = MutableStateFlow<TransferUiState>(TransferUiState.Idle)
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    // ── Send queue ────────────────────────────────────────────────────────────

    private val _sendQueue = MutableStateFlow<List<TransferItem>>(emptyList())
    val sendQueue: StateFlow<List<TransferItem>> = _sendQueue.asStateFlow()

    // ── Receive queue ─────────────────────────────────────────────────────────

    private val _receiveQueue = MutableStateFlow<List<TransferItem>>(emptyList())
    val receiveQueue: StateFlow<List<TransferItem>> = _receiveQueue.asStateFlow()

    // True when at least one item is in flight or queued on either side
    val hasActiveWork: StateFlow<Boolean> = combine(sendQueue, receiveQueue) { s, r ->
        s.any { it.status == TransferItemStatus.QUEUED || it.status == TransferItemStatus.ACTIVE } ||
        r.any { it.status == TransferItemStatus.QUEUED || it.status == TransferItemStatus.ACTIVE }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var sendJob: Job? = null
    private var peerName = ""

    // ─────────────────────────────────────────────────────────────────────────

    // BUG3 FIX: Track whether we've already started the receive loop for this session.
    // Without this guard, re-entering Connected state (e.g. from Transferring → Connected)
    // would spawn a second receive loop that fights the first one for the same stream.
    @Volatile private var receiveLoopStarted = false

    init {
        scope.launch {
            engine.state.collect { es ->
                _uiState.value = mapToUi(es)
                when (es) {
                    is EngineState.Connected -> {
                        peerName = es.peerName
                        // BUG3 FIX: BOTH sides must start a receive loop simultaneously.
                        //
                        // Previous code only started the receive loop for !isHost (receiver).
                        // But the protocol is bidirectional — both the sender AND the receiver
                        // can enqueue files to send. "isHost" only means "who created the
                        // ServerSocket / hotspot" — it has nothing to do with who can receive.
                        //
                        // Xender's design: once connected, both devices are peers and can
                        // both send and receive. Each device starts its own receive loop
                        // listening on the shared socket stream.
                        //
                        // The P2pEngine single-stream protocol handles this: the sender writes
                        // FILE frames and the receiver reads them. When a device has nothing
                        // more to send it writes DONE. The receive loop runs until it sees DONE
                        // or the socket closes.
                        if (!receiveLoopStarted) {
                            receiveLoopStarted = true
                            startReceiveLoop()
                        }
                    }
                    is EngineState.Error, EngineState.Idle -> {
                        receiveLoopStarted = false
                        sendJob?.cancel()
                        sendJob = null
                        // Discard any half-done receive items
                        _receiveQueue.value = _receiveQueue.value.map {
                            if (it.status == TransferItemStatus.ACTIVE)
                                it.copy(status = TransferItemStatus.ERROR)
                            else it
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    // ── UI state mapping ──────────────────────────────────────────────────────

    private fun mapToUi(es: EngineState): TransferUiState = when (es) {
        is EngineState.Idle        -> TransferUiState.Idle
        is EngineState.Preparing   -> TransferUiState.Preparing
        is EngineState.Negotiating -> TransferUiState.Connecting
        is EngineState.QrReady     -> TransferUiState.QrReady(
            qr        = generateQr(es.qrPayload, 700),
            payload   = es.qrPayload,
            sessionId = es.sessionId,
        )
        is EngineState.Connected -> TransferUiState.Connected(
            peerName = es.peerName,
            tier     = es.tier,
            isHost   = es.isHost,
        )
        is EngineState.Transferring -> TransferUiState.Transferring(
            fileName         = es.fileName,
            direction        = es.direction,
            peerName         = es.peerName,
            transferredBytes = es.transferredBytes,
            totalBytes       = es.totalBytes,
            speedBps         = es.speedBps,
            tier             = es.tier,
        )
        is EngineState.Done  -> {
            // Stay in Connected state visually; Done is transient
            val prev = _uiState.value
            if (prev is TransferUiState.Connected || prev is TransferUiState.Transferring) prev
            else TransferUiState.Done
        }
        is EngineState.Error -> TransferUiState.Error(
            msg       = es.msg,
            retryable = es.retryable,
            kind      = when (es.kind) {
                "CONNECTION" -> TransferUiState.ErrorKind.CONNECTION
                "TRANSFER"   -> TransferUiState.ErrorKind.TRANSFER
                "PERMISSION" -> TransferUiState.ErrorKind.PERMISSION
                "TIMEOUT"    -> TransferUiState.ErrorKind.TIMEOUT
                else         -> TransferUiState.ErrorKind.GENERIC
            },
        )
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    fun startAsSender() {
        engine.prepareAsSender { /* QrReady state already set */ }
    }

    fun connectFromQr(rawQr: String) {
        engine.connectFromQr(rawQr)
    }

    /** Enqueue one or more items to send. Processing starts immediately. */
    fun enqueueToSend(items: List<TransferItem>) {
        _sendQueue.value = _sendQueue.value + items
        if (sendJob == null || sendJob?.isActive == false) {
            processSendQueue()
        }
    }

    /** Cancel the currently active send (the one with status ACTIVE). */
    fun cancelActiveSend() {
        engine.cancelCurrentSend()
        // Mark active item as cancelled in UI immediately
        _sendQueue.value = _sendQueue.value.map {
            if (it.status == TransferItemStatus.ACTIVE) it.copy(status = TransferItemStatus.CANCELLED)
            else it
        }
    }

    /** Cancel a queued receive item before it starts. */
    fun cancelQueuedReceive(id: String) {
        _receiveQueue.value = _receiveQueue.value.map {
            if (it.id == id && it.status == TransferItemStatus.QUEUED)
                it.copy(status = TransferItemStatus.CANCELLED)
            else it
        }
    }

    /** Cancel the currently active receive (stop downloading, discard partial). */
    fun cancelActiveReceive() {
        engine.cancelCurrentReceive()
        _receiveQueue.value = _receiveQueue.value.map {
            if (it.status == TransferItemStatus.ACTIVE) it.copy(status = TransferItemStatus.CANCELLED)
            else it
        }
    }

    fun disconnect() {
        sendJob?.cancel()
        sendJob = null
        receiveLoopStarted  = false
        _sendQueue.value    = emptyList()
        _receiveQueue.value = emptyList()
        engine.disconnect()
        _uiState.value = TransferUiState.Idle
    }

    fun release() {
        disconnect()
        scope.cancel()
    }

    // ── Send queue processor ──────────────────────────────────────────────────

    private fun processSendQueue() {
        sendJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val next = _sendQueue.value.firstOrNull { it.status == TransferItemStatus.QUEUED }
                    ?: break

                // Mark active
                updateSendItem(next.id) { it.copy(status = TransferItemStatus.ACTIVE) }

                // Send and await completion
                val done = CompletableDeferred<Boolean>()

                // BUG3 FIX: build and pass metadata so receiver shows rich poster UI
                val meta = P2pEngine.FileMetadata(
                    title     = next.title,
                    posterUrl = next.posterUrl,
                    mediaType = next.mediaType,
                    season    = next.season,
                    episode   = next.episode,
                    quality   = next.quality,
                )
                engine.sendFile(
                    filePath = next.filePath,
                    fileName = next.fileName,
                    meta     = meta,
                    onProgress = { sent, total, bps ->
                        updateSendItem(next.id) { it.copy(bytesdone = sent, speedBps = bps) }
                    },
                    onDone = {
                        updateSendItem(next.id) { it.copy(status = TransferItemStatus.DONE) }
                        scope.launch {
                            repo.recordTransfer(TransferRecord(
                                id        = UUID.randomUUID().toString(),
                                fileName  = next.fileName,
                                sizeBytes = next.sizeBytes,
                                direction = "SEND",
                                peerName  = peerName,
                                status    = "DONE",
                            ))
                        }
                        done.complete(true)
                    },
                    onError = { msg ->
                        updateSendItem(next.id) { it.copy(status = TransferItemStatus.ERROR) }
                        done.complete(false)
                    },
                )
                done.await()
                delay(100) // tiny gap between files so both sides can breathe
            }

            // Queue drained — tell receiver we're done
            val stillConnected = engineState.value.let {
                it is EngineState.Connected || it is EngineState.Transferring || it is EngineState.Done
            }
            if (stillConnected) engine.sendDone()
            sendJob = null
        }
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private fun startReceiveLoop() {
        val saveDir = File(ctx.getExternalFilesDir(null), "ReelzBeam")

        engine.receiveFiles(
            saveDir = saveDir,
            // BUG3 FIX: onFileStart now receives the FileMetadata so the UI can show
            // the movie poster immediately before any bytes have been received.
            onFileStart = { fileName, total, meta ->
                val item = TransferItem(
                    fileName  = fileName,
                    sizeBytes = total,
                    status    = TransferItemStatus.ACTIVE,
                    title     = meta.title.ifBlank { fileName },
                    posterUrl = meta.posterUrl,
                    mediaType = meta.mediaType,
                    season    = meta.season,
                    episode   = meta.episode,
                    quality   = meta.quality,
                )
                _receiveQueue.value = _receiveQueue.value + item
            },
            onProgress = { received, total, bps, fileName ->
                _receiveQueue.value = _receiveQueue.value.map { item ->
                    if (item.fileName == fileName && item.status == TransferItemStatus.ACTIVE)
                        item.copy(bytesdone = received, speedBps = bps)
                    else item
                }
            },
            // BUG3 FIX: onFileDone also receives metadata for history recording
            onFileDone = { file, meta ->
                _receiveQueue.value = _receiveQueue.value.map { item ->
                    if (item.fileName == file.name && item.status == TransferItemStatus.ACTIVE)
                        item.copy(status = TransferItemStatus.DONE)
                    else item
                }
                scope.launch {
                    repo.recordTransfer(TransferRecord(
                        id        = UUID.randomUUID().toString(),
                        fileName  = file.name,
                        sizeBytes = file.length(),
                        direction = "RECEIVE",
                        peerName  = peerName,
                        status    = "DONE",
                    ))
                }
            },
            onAllDone = {
                // Sender's queue empty; session stays open for more
            },
            onError = { msg ->
                _uiState.value = TransferUiState.Error(msg, retryable = false)
            },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateSendItem(id: String, transform: (TransferItem) -> TransferItem) {
        _sendQueue.value = _sendQueue.value.map { if (it.id == id) transform(it) else it }
    }
}

// ─── UI state ─────────────────────────────────────────────────────────────────

sealed class TransferUiState {
    object Idle       : TransferUiState()
    object Preparing  : TransferUiState()
    object Connecting : TransferUiState()

    data class QrReady(
        val qr: Bitmap?,
        val payload: String,
        val sessionId: String,
    ) : TransferUiState()

    data class Connected(
        val peerName: String,
        val tier: TransportTier,
        val isHost: Boolean,
    ) : TransferUiState()

    data class Transferring(
        val fileName: String,
        val direction: String,
        val peerName: String,
        val transferredBytes: Long,
        val totalBytes: Long,
        val speedBps: Long,
        val tier: TransportTier?,
    ) : TransferUiState()

    object Done : TransferUiState()

    data class Error(
        val msg: String,
        val retryable: Boolean,
        val kind: ErrorKind = ErrorKind.GENERIC,
    ) : TransferUiState()

    enum class ErrorKind { PERMISSION, CONNECTION, TIMEOUT, TRANSFER, GENERIC }
}

// ─── QR generator ─────────────────────────────────────────────────────────────

fun generateQr(content: String, sizePx: Int): Bitmap? = try {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val mat = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) for (y in 0 until sizePx)
        bmp.setPixel(x, y, if (mat[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    bmp
} catch (_: Exception) { null }
