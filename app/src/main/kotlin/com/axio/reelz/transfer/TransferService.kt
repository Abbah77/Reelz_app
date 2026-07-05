package com.axio.reelz.transfer

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.axio.reelz.data.local.TransferDao
import com.axio.reelz.data.model.TransferRecord
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * TransferService — foreground-service shell around NearbyTransferManager.
 *
 * All the actual P2P negotiation, medium upgrade (Bluetooth -> Wi-Fi Direct/Aware),
 * chunking, retry, and throughput tuning is handled by Google Play Services via
 * NearbyTransferManager. This service exists purely so:
 *  1. Android doesn't kill our process mid-transfer (foreground service + notification).
 *  2. The transfer keeps running if the user backgrounds the app or the TransferScreen
 *     composable is torn down.
 *  3. We have one place to persist completed transfers to TransferDao.
 *
 * This replaces the old hand-rolled ServerSocket/Socket + 256KB buffer loop entirely —
 * see git history for that implementation if it's ever needed as a no-Play-Services
 * fallback path.
 */
@AndroidEntryPoint
class TransferService : Service() {

    @Inject lateinit var transferDao: TransferDao

    companion object {
        const val CHANNEL_ID = "reelz_transfer"
        const val ACTION_SEND = "action_send"
        const val ACTION_STOP = "action_stop"
        const val EXTRA_FILE = "file_path"
        const val EXTRA_ENDPOINT_ID = "endpoint_id"

        /** UI subscribes to this for real-time transfer updates — same shape as before. */
        val progressFlow = MutableStateFlow<TransferProgress?>(null)

        data class TransferProgress(
            val id: String,
            val fileName: String,
            val totalBytes: Long,
            val transferredBytes: Long,
            val direction: String,   // "SEND" | "RECEIVE"
            val peerName: String,
            val speedBps: Long = 0,
            val done: Boolean = false,
            val error: String? = null,
        )

        /**
         * Shared manager instance so both the Service (for lifecycle/notifications)
         * and the ViewModel (for advertise/discover/connect calls from the UI) talk
         * to the exact same NearbyTransferManager / Play Services session.
         */
        @Volatile var nearbyManager: NearbyTransferManager? = null
            private set

        fun ensureManager(ctx: Context): NearbyTransferManager =
            nearbyManager ?: NearbyTransferManager(ctx.applicationContext).also { nearbyManager = it }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val speedTracker = SpeedTracker()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(2, buildNotif("Reelz Transfer", "Ready to transfer"))

        val manager = ensureManager(this)
        manager.incomingDir = File(filesDir, "transfers")

        // Bridge NearbyTransferManager's low-level events into the same
        // TransferProgress shape the existing UI (TransferScreen) already renders,
        // and persist completed transfers to Room — same as the old implementation.
        scope.launch {
            manager.events.collect { event ->
                when (event) {
                    is NearbyTransferManager.NearbyEvent.TransferProgress -> {
                        val bps = speedTracker.update(event.payloadId, event.bytesTransferred)
                        progressFlow.emit(
                            TransferProgress(
                                id = event.payloadId.toString(),
                                fileName = event.fileName,
                                totalBytes = event.totalBytes,
                                transferredBytes = event.bytesTransferred,
                                direction = event.direction,
                                peerName = event.endpointId,
                                speedBps = bps,
                                done = event.done,
                                error = event.error,
                            )
                        )
                        updateNotif(
                            title = when {
                                event.error != null -> "Transfer failed"
                                event.done -> "${if (event.direction == "SEND") "Sent" else "Received"} \u2713"
                                event.direction == "SEND" -> "Sending"
                                else -> "Receiving"
                            },
                            text = event.error
                                ?: "${event.fileName} \u00b7 ${fmt(event.bytesTransferred)} / ${fmt(event.totalBytes)}" +
                                    if (bps > 0) " \u00b7 ${fmtSpeed(bps)}" else "",
                        )
                    }

                    is NearbyTransferManager.NearbyEvent.FileReceived -> {
                        recordTransfer(
                            id = event.payloadId.toString(),
                            name = event.file.name,
                            path = event.file.absolutePath,
                            size = event.file.length(),
                            dir = "RECEIVE",
                            peer = event.endpointId,
                        )
                    }

                    else -> Unit
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND -> {
                val filePath = intent.getStringExtra(EXTRA_FILE) ?: return START_NOT_STICKY
                val endpointId = intent.getStringExtra(EXTRA_ENDPOINT_ID) ?: return START_NOT_STICKY
                val file = File(filePath)
                if (file.exists()) {
                    ensureManager(this).sendFile(endpointId, file)
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun recordTransfer(
        id: String, name: String, path: String, size: Long, dir: String, peer: String,
    ) {
        transferDao.insert(TransferRecord(id, name, path, size, dir, peer, peer, "DONE"))
    }

    private fun buildNotif(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

    private fun updateNotif(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(2, buildNotif(title, text))
    }

    private fun createChannel() {
        NotificationChannel(CHANNEL_ID, "File Transfer", NotificationManager.IMPORTANCE_LOW)
            .let { getSystemService(NotificationManager::class.java)?.createNotificationChannel(it) }
    }

    private fun fmt(bytes: Long) = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }

    private fun fmtSpeed(bps: Long) = when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps >= 1_000     -> "%.0f KB/s".format(bps / 1_000.0)
        else             -> "$bps B/s"
    }

    /**
     * Nearby's PayloadTransferUpdate gives cumulative bytes, not instantaneous speed —
     * derive a rolling B/s figure the same way the old socket-loop implementation did,
     * so the UI's speed readout behaves identically.
     */
    private class SpeedTracker {
        private data class Window(var lastBytes: Long, var lastTimeMs: Long)
        private val windows = mutableMapOf<Long, Window>()

        fun update(payloadId: Long, bytesNow: Long): Long {
            val now = System.currentTimeMillis()
            val w = windows.getOrPut(payloadId) { Window(bytesNow, now) }
            val dt = now - w.lastTimeMs
            if (dt < 300) return 0L // not enough elapsed time for a stable reading yet
            val bps = ((bytesNow - w.lastBytes) * 1000) / dt.coerceAtLeast(1)
            w.lastBytes = bytesNow
            w.lastTimeMs = now
            return bps
        }
    }
}
