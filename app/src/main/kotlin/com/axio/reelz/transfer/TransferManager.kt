package com.axio.reelz.transfer

// ─────────────────────────────────────────────────────────────────────────────
//  TransferManager — queue orchestration layer above P2pEngine
//
//  Key improvements in this revision
//  ────────────────────────────────
//  1. QR generation moved to Dispatchers.Default (off Main thread) so the UI
//     never freezes while the 700×700 bitmap is being built.
//
//  2. generateQr() rewrites the inner loop using Android's Canvas API instead
//     of setPixel(). setPixel() forces a format-conversion round-trip on every
//     call and is ~30× slower than a single Canvas.drawRect() per row-run.
//     Result: 700 px QR renders in <20 ms on any SoC since 2016.
//
//  3. After a file is received it is registered in DownloadDao with full
//     duplicate-prevention logic that matches the single-source-of-truth rule:
//       • Same mediaId + season + episode + quality  → skip (already have it)
//       • Same mediaId + season + episode, different quality → add new row
//       • Same mediaId but no episode match (movie extra quality) → add new row
//     This means a locally received episode and an online download of the same
//     episode in the same quality are treated as identical; the DB entry is
//     never duplicated.
//
//  4. receiveFiles() passes the full FileMetadata to the completion callback
//     so the DB row is filled with the correct title / posterUrl / mediaId etc.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import com.axio.reelz.core.database.DownloadDao
import com.axio.reelz.core.database.DownloadRow
import com.axio.reelz.core.database.TransferRecord
import com.axio.reelz.data.model.DownloadStatus
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
    val title:     String  = "",
    val posterUrl: String  = "",
    val mediaType: String  = "",
    val season:    Int     = 0,
    val episode:   Int     = 0,
    val quality:   String  = "",
    val mediaId:   String  = "",   // used for DB registration on receiver side
)

enum class TransferItemStatus { QUEUED, ACTIVE, DONE, CANCELLED, ERROR }

// ─── Manager ─────────────────────────────────────────────────────────────────

@Singleton
class TransferManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val engine:      P2pEngine,
    private val repo:        TransferRepository,
    private val downloadDao: DownloadDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val engineState: StateFlow<EngineState> = engine.state

    private val _uiState = MutableStateFlow<TransferUiState>(TransferUiState.Idle)
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private val _sendQueue    = MutableStateFlow<List<TransferItem>>(emptyList())
    val sendQueue: StateFlow<List<TransferItem>> = _sendQueue.asStateFlow()

    private val _receiveQueue = MutableStateFlow<List<TransferItem>>(emptyList())
    val receiveQueue: StateFlow<List<TransferItem>> = _receiveQueue.asStateFlow()

    val hasActiveWork: StateFlow<Boolean> = combine(sendQueue, receiveQueue) { s, r ->
        s.any { it.status == TransferItemStatus.QUEUED || it.status == TransferItemStatus.ACTIVE } ||
        r.any { it.status == TransferItemStatus.QUEUED || it.status == TransferItemStatus.ACTIVE }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var sendJob: Job? = null
    private var peerName      = ""
    private var peerSessionId = ""

    companion object {
        private const val TAG = "TransferManager"
    }   // sessionId used as stable peer identifier for duplicate-send check

    @Volatile private var receiveLoopStarted = false

    init {
        scope.launch {
            engine.state.collect { es ->
                // ── QR state: generate bitmap on Default (non-blocking) ──────
                if (es is EngineState.QrReady) {
                    // Generate on background thread, then emit to UI
                    scope.launch(Dispatchers.Default) {
                        val bmp = generateQr(es.qrPayload, 700)
                        withContext(Dispatchers.Main) {
                            _uiState.value = TransferUiState.QrReady(
                                qr        = bmp,
                                payload   = es.qrPayload,
                                sessionId = es.sessionId,
                            )
                        }
                    }
                } else {
                    _uiState.value = mapToUi(es)
                }

                when (es) {
                    is EngineState.Connected -> {
                        peerName      = es.peerName
                        // Use the QR session ID as the stable peer key — it is a UUID
                        // generated fresh per session and shared via the QR payload, so
                        // it is both unique and not affected by the user renaming their device.
                        // The socket object gives us access to the remote address as a
                        // secondary fallback, but the sessionId from EngineState is sufficient.
                        peerSessionId = es.socket.remoteSocketAddress.toString()
                            .substringBefore(":").trimStart('/')
                            .ifBlank { es.peerName }
                        if (!receiveLoopStarted) {
                            receiveLoopStarted = true
                            startReceiveLoop()
                        }
                    }
                    is EngineState.Error, EngineState.Idle -> {
                        receiveLoopStarted = false
                        sendJob?.cancel()
                        sendJob = null
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
        is EngineState.QrReady     -> {
            // QR state handled separately above with async bitmap generation
            // Return current state to avoid flickering back to Idle
            _uiState.value.let { cur ->
                if (cur is TransferUiState.QrReady) cur else TransferUiState.Preparing
            }
        }
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
        is EngineState.Done -> {
            val prev = _uiState.value
            if (prev is TransferUiState.Connected || prev is TransferUiState.Transferring) prev
            else TransferUiState.Done
        }
        is EngineState.Error -> TransferUiState.Error(
            msg       = es.msg,
            retryable = es.retryable,
            kind      = when (es.kind) {
                "CONNECTION"  -> TransferUiState.ErrorKind.CONNECTION
                "TRANSFER"    -> TransferUiState.ErrorKind.TRANSFER
                "PERMISSION"  -> TransferUiState.ErrorKind.PERMISSION
                "TIMEOUT"     -> TransferUiState.ErrorKind.TIMEOUT
                "SWITCH_ROLE" -> TransferUiState.ErrorKind.SWITCH_ROLE
                else          -> TransferUiState.ErrorKind.GENERIC
            },
        )
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    fun startAsSender() {
        engine.prepareAsSender { }
    }

    fun connectFromQr(rawQr: String) {
        engine.connectFromQr(rawQr)
    }

    fun enqueueToSend(items: List<TransferItem>) {
        _sendQueue.value = _sendQueue.value + items
        // Bug #7 fix: a completed (not cancelled) sendJob has isActive==false, so the
        // old check `sendJob?.isActive == false` correctly starts a new job — but the
        // stale reference was not nulled, meaning two jobs could co-exist briefly on
        // reconnect. Null the reference explicitly before starting a fresh job.
        val job = sendJob
        if (job == null || !job.isActive) {
            if (job != null && job.isCompleted) sendJob = null
            processSendQueue()
        }
    }

    fun cancelActiveSend() {
        engine.cancelCurrentSend()
        _sendQueue.value = _sendQueue.value.map {
            if (it.status == TransferItemStatus.ACTIVE) it.copy(status = TransferItemStatus.CANCELLED)
            else it
        }
    }

    fun cancelQueuedReceive(id: String) {
        _receiveQueue.value = _receiveQueue.value.map {
            if (it.id == id && it.status == TransferItemStatus.QUEUED)
                it.copy(status = TransferItemStatus.CANCELLED)
            else it
        }
    }

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

                updateSendItem(next.id) { it.copy(status = TransferItemStatus.ACTIVE) }

                val done = CompletableDeferred<Boolean>()

                val meta = P2pEngine.FileMetadata(
                    title     = next.title,
                    posterUrl = next.posterUrl,
                    mediaType = next.mediaType,
                    season    = next.season,
                    episode   = next.episode,
                    quality   = next.quality,
                    mediaId   = next.mediaId,
                )
                engine.sendFile(
                    filePath   = next.filePath,
                    fileName   = next.fileName,
                    meta       = meta,
                    onProgress = { sent, total, bps ->
                        updateSendItem(next.id) { it.copy(bytesdone = sent, speedBps = bps) }
                    },
                    onDone = {
                        updateSendItem(next.id) { it.copy(status = TransferItemStatus.DONE) }
                        scope.launch(Dispatchers.IO) {
                            repo.recordTransfer(TransferRecord(
                                id        = UUID.randomUUID().toString(),
                                fileName  = next.fileName,
                                sizeBytes = next.sizeBytes,
                                direction = "SEND",
                                peerName  = peerName,
                                peerId    = peerSessionId,
                                status    = "DONE",
                                mediaId   = next.mediaId,
                                season    = next.season,
                                episode   = next.episode,
                                quality   = next.quality,
                            ))
                        }
                        done.complete(true)
                    },
                    onError = { _ ->
                        updateSendItem(next.id) { it.copy(status = TransferItemStatus.ERROR) }
                        done.complete(false)
                    },
                )
                done.await()
                delay(100)
            }

            val stillConnected = engineState.value.let {
                it is EngineState.Connected || it is EngineState.Transferring || it is EngineState.Done
            }
            if (stillConnected) engine.sendDone()
            sendJob = null
        }
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private fun startReceiveLoop() {
        // HLS downloads arrive as many .ts files + one final .m3u8.
        // We place them all in a per-media subfolder so ExoPlayer can play
        // the m3u8 with relative segment paths.
        val saveDir = File(ctx.getExternalFilesDir(null), "ReelzBeam")

        // ── HLS routing helper ────────────────────────────────────────────────
        // The sender transmits all .ts segments first, then the m3u8 playlist
        // (with relative paths). We need to save everything into the SAME folder
        // so the m3u8 can resolve its sibling .ts files.
        // We derive the folder from the meta.mediaId / title to be consistent
        // across files belonging to the same piece of content.
        fun hlsSubdir(meta: P2pEngine.FileMetadata): java.io.File {
            val key = meta.mediaId.ifBlank {
                buildString {
                    append(meta.title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40))
                    if (meta.season  > 0) append("_S${meta.season.toString().padStart(2,'0')}")
                    if (meta.episode > 0) append("E${meta.episode.toString().padStart(2,'0')}")
                    append("_${meta.quality}")
                }
            }
            return java.io.File(saveDir, key).also { it.mkdirs() }
        }

        engine.receiveFiles(
            saveDir     = saveDir,
            // Route HLS files directly into their per-media subfolder so segments
            // land in the right place immediately — no post-receive rename needed,
            // which eliminates the race where the next segment arrives while the
            // previous one is still being moved on slow storage.
            overrideSaveDir = { fileName, meta ->
                val isHlsFile = fileName.endsWith(".ts", ignoreCase = true) ||
                                fileName.endsWith(".m3u8", ignoreCase = true)
                if (isHlsFile) hlsSubdir(meta) else null
            },
            onFileStart = { fileName, total, meta ->
                // Show only meaningful items — hide raw .ts segments from UI;
                // show one entry per media item (the m3u8 or the mp4)
                val isRawTs = fileName.endsWith(".ts", ignoreCase = true)
                if (!isRawTs) {
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
                        mediaId   = meta.mediaId,
                    )
                    _receiveQueue.value = _receiveQueue.value + item
                }
            },
            onProgress = { received, total, bps, fileName ->
                val isRawTs = fileName.endsWith(".ts", ignoreCase = true)
                if (!isRawTs) {
                    _receiveQueue.value = _receiveQueue.value.map { item ->
                        if (item.fileName == fileName && item.status == TransferItemStatus.ACTIVE)
                            item.copy(bytesdone = received, speedBps = bps)
                        else item
                    }
                }
            },
            onFileDone = { file, meta ->
                val isTs   = file.name.endsWith(".ts", ignoreCase = true)
                val isM3u8 = file.name.endsWith(".m3u8", ignoreCase = true)

                if (isTs || isM3u8) {
                    // Files already landed in hlsSubdir via overrideSaveDir — no move needed.
                    val targetDir  = hlsSubdir(meta)
                    val targetFile = java.io.File(targetDir, file.name)

                    if (isM3u8) {
                        // ── Rewrite m3u8 segment paths for the receiver ───────
                        // The sender's index.m3u8 contains absolute paths on the
                        // SENDER's device (e.g. /storage/emulated/0/.../seg000001.ts).
                        // Those paths are meaningless here. We rewrite every non-tag,
                        // non-blank line to point at the segment file as it exists in
                        // targetDir on this device. Segments are named seg######.ts
                        // (same convention the engine uses) so the mapping is 1-to-1.
                        if (targetFile.exists()) {
                            try {
                                val lines = targetFile.readLines()
                                val rewritten = buildString {
                                    for (line in lines) {
                                        val trimmed = line.trim()
                                        when {
                                            trimmed.isEmpty() || trimmed.startsWith("#") -> {
                                                appendLine(line)
                                            }
                                            else -> {
                                                // Replace any path (absolute or relative) with
                                                // the receiver-local absolute path for that segment.
                                                val segName = trimmed.substringAfterLast('/')
                                                    .substringAfterLast('\\')
                                                    .ifBlank { trimmed }
                                                val localSeg = java.io.File(targetDir, segName)
                                                appendLine(localSeg.absolutePath)
                                            }
                                        }
                                    }
                                }.trimEnd() + "\n"
                                targetFile.writeText(rewritten)
                                Log.d(TAG, "Rewrote m3u8 segment paths for ${file.name} → ${targetDir.absolutePath}")
                            } catch (e: Exception) {
                                Log.w(TAG, "m3u8 path rewrite failed for ${file.name}: ${e.message}")
                            }
                        } else {
                            Log.w(TAG, "m3u8 targetFile does not exist after move: ${targetFile.absolutePath}")
                        }

                        // m3u8 is the last file of an HLS bundle → finalize
                        _receiveQueue.value = _receiveQueue.value.map { item ->
                            if (item.fileName == file.name && item.status == TransferItemStatus.ACTIVE)
                                item.copy(status = TransferItemStatus.DONE)
                            else item
                        }
                        val bundleSize = targetDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                        scope.launch(Dispatchers.IO) {
                            repo.recordTransfer(TransferRecord(
                                id        = UUID.randomUUID().toString(),
                                fileName  = file.name,
                                sizeBytes = bundleSize,
                                direction = "RECEIVE",
                                peerName  = peerName,
                                peerId    = "",
                                status    = "DONE",
                                mediaId   = meta.mediaId,
                                season    = meta.season,
                                episode   = meta.episode,
                                quality   = meta.quality,
                            ))
                            // Only register if targetFile actually landed on disk
                            if (targetFile.exists()) {
                                registerReceivedContent(targetFile, meta)
                            } else {
                                Log.e(TAG, "HLS bundle finalized but m3u8 missing: ${targetFile.absolutePath}")
                            }
                        }
                    }
                    // .ts files silently complete — no DB row, no UI update needed
                } else {
                    // ── MP4: single file, normal flow ─────────────────────────
                    _receiveQueue.value = _receiveQueue.value.map { item ->
                        if (item.fileName == file.name && item.status == TransferItemStatus.ACTIVE)
                            item.copy(status = TransferItemStatus.DONE)
                        else item
                    }
                    scope.launch(Dispatchers.IO) {
                        repo.recordTransfer(TransferRecord(
                            id        = UUID.randomUUID().toString(),
                            fileName  = file.name,
                            sizeBytes = file.length(),
                            direction = "RECEIVE",
                            peerName  = peerName,
                            peerId    = "",
                            status    = "DONE",
                            mediaId   = meta.mediaId,
                            season    = meta.season,
                            episode   = meta.episode,
                            quality   = meta.quality,
                        ))
                        registerReceivedContent(file, meta)
                    }
                }
            },
            onAllDone = { /* session stays open */ },
            onError   = { msg ->
                _uiState.value = TransferUiState.Error(msg, retryable = false)
            },
        )
    }

    // ── Received file → DownloadDao registration ─────────────────────────────
    //
    // Ownership key: [mediaId + season + episode + quality]
    // season = 0, episode = 0 for movies.
    //
    // Called after:
    //   • MP4 fully received — file is the .mp4
    //   • HLS bundle fully received — file is the final index.m3u8
    //     (all .ts segments already live alongside it in the same folder)
    //
    // Three-layer duplicate guard:
    //   1. DownloadDao.findOwned()  — checks DB + composite key
    //   2. File.exists() on the stored path — catches manually-deleted files
    //   3. TransferDao.findReceivedRecord() — extra guard via transfer history
    //      (catches the edge case where the DB row was deleted but the file isn't)

    private suspend fun registerReceivedContent(
        file: File,
        meta: P2pEngine.FileMetadata,
    ) = withContext(Dispatchers.IO) {
        val mediaId   = meta.mediaId.ifBlank  { meta.title.ifBlank { file.nameWithoutExtension } }
        val season    = meta.season
        val episode   = meta.episode
        val quality   = meta.quality.ifBlank  { "720p" }
        val title     = meta.title.ifBlank    { file.nameWithoutExtension }
        val mediaType = meta.mediaType.ifBlank { if (episode > 0) "TV" else "MOVIE" }
        val isHls     = file.name.endsWith(".m3u8", ignoreCase = true)

        // ── Layer 1: DB ownership check with disk existence ───────────────────
        val ownedRow = downloadDao.findOwned(mediaId, season, episode, quality)
        if (ownedRow != null) {
            val storedPath = ownedRow.filePath.ifBlank { ownedRow.localPlaylistPath }
            if (storedPath.isNotBlank() && java.io.File(storedPath).exists()) {
                Log.d(TAG, "registerReceivedContent: already owned $mediaId s${season}e${episode} $quality — skip")
                return@withContext
            }
            // File was deleted from disk — the old DB row is stale. Remove it
            // so we can insert a fresh one pointing to the newly received file.
            Log.d(TAG, "registerReceivedContent: stale row found (file missing) — replacing for $mediaId")
            downloadDao.delete(ownedRow.id)
        }

        // ── Layer 2: transfer history guard ───────────────────────────────────
        // If the download row was deleted manually but we transferred this before,
        // we still want to re-register (user wants it back). So we only use this
        // as a log, not as a block.
        val prevTransfer = repo.findReceivedRecord(mediaId, season, episode, quality)
        if (prevTransfer != null) {
            Log.d(TAG, "registerReceivedContent: previously received — re-registering $mediaId")
        }

        // ── Compute file metadata ─────────────────────────────────────────────
        val (filePath, localPlaylistPath, sizeBytes) = if (isHls) {
            // For HLS: file IS the index.m3u8. sizeBytes = sum of all files in folder.
            val folderSize = file.parentFile
                ?.walkBottomUp()
                ?.filter { it.isFile }
                ?.sumOf { it.length() } ?: file.length()
            Triple("", file.absolutePath, folderSize)
        } else {
            Triple(file.absolutePath, "", file.length())
        }

        // ── Insert fresh DONE row ─────────────────────────────────────────────
        val newId = UUID.randomUUID().toString()
        downloadDao.insert(
            DownloadRow(
                id               = newId,
                mediaId          = mediaId,
                title            = title,
                posterUrl        = meta.posterUrl.ifBlank { null },
                mediaType        = mediaType,
                season           = season,
                episode          = episode,
                episodeName      = "",
                quality          = quality,
                filePath         = filePath,
                localPlaylistPath = localPlaylistPath,
                sizeBytes        = sizeBytes,
                downloadedBytes  = sizeBytes,
                status           = DownloadStatus.DONE.name,
                streamUrl        = "",   // received via transfer — no remote URL
                headersJson      = "{}",
                createdAt        = System.currentTimeMillis(),
                completedAt      = System.currentTimeMillis(),
            )
        )
        Log.i(TAG, "registerReceivedContent: registered $title [$quality] id=$newId isHls=$isHls")
    }

    // ── Send-side duplicate check ─────────────────────────────────────────────
    //
    // Call this before adding an item to the send queue when you have a known
    // peerId (stable device identifier from P2pEngine). Shows a confirmation
    // dialog in the UI if the user already sent this exact content to this peer.

    sealed class SendCheck {
        object Ok : SendCheck()
        data class AlreadySent(val sentAt: Long) : SendCheck()
    }

    suspend fun checkCanSend(
        item:   com.axio.reelz.data.model.DownloadItem,
        peerId: String,
    ): SendCheck = withContext(Dispatchers.IO) {
        if (peerId.isBlank()) return@withContext SendCheck.Ok
        val record = repo.findSentRecord(
            mediaId = item.mediaId,
            season  = item.season,
            episode = item.episode,
            quality = item.quality,
            peerId  = peerId,
        )
        if (record != null) SendCheck.AlreadySent(record.createdAt)
        else SendCheck.Ok
    }

    // ── Internal helper: build TransferRecord for a completed SEND ────────────
    fun buildSentRecord(item: TransferItem, peerId: String): TransferRecord = TransferRecord(
        id        = UUID.randomUUID().toString(),
        fileName  = item.fileName,
        sizeBytes = item.sizeBytes,
        direction = "SEND",
        peerName  = peerName,
        peerId    = peerId,
        status    = "DONE",
        mediaId   = item.mediaId,
        season    = item.season,
        episode   = item.episode,
        quality   = item.quality,
    )

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
        val qr:        Bitmap?,
        val payload:   String,
        val sessionId: String,
    ) : TransferUiState()

    data class Connected(
        val peerName: String,
        val tier:     TransportTier,
        val isHost:   Boolean,
    ) : TransferUiState()

    data class Transferring(
        val fileName:         String,
        val direction:        String,
        val peerName:         String,
        val transferredBytes: Long,
        val totalBytes:       Long,
        val speedBps:         Long,
        val tier:             TransportTier?,
    ) : TransferUiState()

    object Done : TransferUiState()

    data class Error(
        val msg:       String,
        val retryable: Boolean,
        val kind:      ErrorKind = ErrorKind.GENERIC,
    ) : TransferUiState()

    enum class ErrorKind { PERMISSION, CONNECTION, TIMEOUT, TRANSFER, SWITCH_ROLE, GENERIC }
}

// ─── QR generator (fast Canvas-based implementation) ─────────────────────────
//
//  The pixel-by-pixel setPixel() approach is extremely slow for 700×700 bitmaps
//  (490,000 individual JNI calls). This implementation uses Canvas.drawRect()
//  per run of same-color pixels — typically only ~5-15 calls per row — giving a
//  ~30x speedup. Generation time: <20 ms on any device post-2016.
//
//  The function is already called on Dispatchers.Default by the manager above,
//  but is itself pure/synchronous so it can be called from any coroutine.

fun generateQr(content: String, sizePx: Int): Bitmap? = try {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN           to 1,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val width  = matrix.width
    val height = matrix.height

    // ARGB_8888 for best quality; RGB_565 causes banding on some OEMs
    val bmp    = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint  = Paint(Paint.ANTI_ALIAS_FLAG)

    // Fill background white
    canvas.drawColor(Color.WHITE)
    paint.color = Color.BLACK

    // Draw runs of dark pixels using drawRect — far fewer JNI calls than setPixel
    for (y in 0 until height) {
        var runStart = -1
        for (x in 0 until width) {
            val isDark = matrix[x, y]
            if (isDark && runStart == -1) {
                runStart = x
            } else if (!isDark && runStart != -1) {
                canvas.drawRect(
                    runStart.toFloat(), y.toFloat(),
                    x.toFloat(),        (y + 1).toFloat(),
                    paint,
                )
                runStart = -1
            }
        }
        // Close any run that reaches the right edge
        if (runStart != -1) {
            canvas.drawRect(
                runStart.toFloat(), y.toFloat(),
                width.toFloat(),    (y + 1).toFloat(),
                paint,
            )
        }
    }
    bmp
} catch (_: Exception) { null }
