package com.axio.reelz.transfer

// ─────────────────────────────────────────────────────────────────────────────
//  TransferManager — bridges BeamEngine ↔ ViewModel / UI
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

@Singleton
class TransferManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val engine: P2pEngine,
    private val repo: TransferRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val engineState: StateFlow<EngineState> = engine.state

    private val _uiState = MutableStateFlow<TransferUiState>(TransferUiState.Idle)
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            engine.state.collect { es -> _uiState.value = mapToUi(es) }
        }
    }

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
        is EngineState.Done  -> TransferUiState.Done
        is EngineState.Error -> TransferUiState.Error(
            msg       = es.msg,
            retryable = es.retryable,
            kind = when (es.kind) {
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
        engine.prepareAsSender { qrPayload ->
            // QrReady state is already set by the engine
        }
    }

    fun connectFromQr(rawQr: String) {
        engine.connectFromQr(rawQr)
    }

    fun sendFile(filePath: String, fileName: String, peerName: String) {
        engine.sendFile(
            filePath   = filePath,
            fileName   = fileName,
            onProgress = { _, _, _ -> },
            onDone = {
                scope.launch {
                    repo.recordTransfer(TransferRecord(
                        id        = UUID.randomUUID().toString(),
                        fileName  = fileName,
                        sizeBytes = File(filePath).length(),
                        direction = "SEND",
                        peerName  = peerName,
                        status    = "DONE",
                    ))
                }
            },
            onError = { msg -> _uiState.value = TransferUiState.Error(msg, retryable = false) },
        )
    }

    fun startReceiving(saveDir: File, peerName: String) {
        engine.receiveFile(
            saveDir    = saveDir,
            onProgress = { _, _, _, _ -> },
            onDone = { file ->
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
            onError = { msg -> _uiState.value = TransferUiState.Error(msg, retryable = false) },
        )
    }

    fun disconnect() {
        engine.disconnect()
        _uiState.value = TransferUiState.Idle
    }

    fun release() {
        engine.release()
        scope.cancel()
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
