package com.reelz.transfer

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.reelz.data.local.TransferDao
import com.reelz.data.model.TransferRecord
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.*
import java.util.UUID
import javax.inject.Inject

/**
 * Wi-Fi Direct / local network transfer service.
 * - Sender opens a socket server and waits for receiver.
 * - Receiver connects to sender's IP:port (encoded in QR code).
 * - Files saved to app-private filesDir — not visible in gallery/Files app.
 * - Emits real-time TransferProgress including network speed (bytes/sec).
 */
@AndroidEntryPoint
class TransferService : Service() {

    @Inject lateinit var transferDao: TransferDao

    companion object {
        const val CHANNEL_ID     = "reelz_transfer"
        const val ACTION_SEND    = "action_send"
        const val ACTION_RECEIVE = "action_receive"
        const val EXTRA_FILE     = "file_path"
        const val EXTRA_IP       = "peer_ip"
        const val EXTRA_PORT     = "peer_port"
        const val TRANSFER_PORT  = 49_200
        const val REELZ_MAGIC    = "REELZ\u0001"

        /** UI observes this flow for live progress. */
        val progressFlow = MutableStateFlow<TransferProgress?>(null)
    }

    data class TransferProgress(
        val id: String,
        val fileName: String,
        val totalBytes: Long,
        val sentBytes: Long,
        val direction: String,
        val peerName: String,
        /** Current transfer speed in bytes/sec */
        val speedBps: Long = 0,
        val done: Boolean = false,
        val error: String? = null,
    )

    private val scope        = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(2, buildNotif("Transfer", "Ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND -> {
                val filePath = intent.getStringExtra(EXTRA_FILE) ?: return START_NOT_STICKY
                val ip       = intent.getStringExtra(EXTRA_IP)   ?: return START_NOT_STICKY
                val port     = intent.getIntExtra(EXTRA_PORT, TRANSFER_PORT)
                scope.launch { sendFile(filePath, ip, port) }
            }
            ACTION_RECEIVE -> scope.launch { receiveFile() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); serverSocket?.close(); super.onDestroy() }

    // ── Send ──────────────────────────────────────────────────────────────────
    private suspend fun sendFile(filePath: String, ip: String, port: Int) {
        val file = File(filePath)
        if (!file.exists()) return
        val id   = UUID.randomUUID().toString()
        val prog = TransferProgress(id, file.name, file.length(), 0L, "SEND", ip)
        progressFlow.emit(prog)
        updateNotif("Sending", file.name)

        try {
            Socket(ip, port).use { socket ->
                socket.setPerformancePreferences(0, 0, 1) // optimise for bandwidth
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 131_072))
                // Protocol header
                out.writeUTF(REELZ_MAGIC)
                out.writeUTF(file.name)
                out.writeLong(file.length())
                out.flush()

                var sent = 0L
                var speedWindowBytes = 0L
                var speedWindowStart = System.currentTimeMillis()

                FileInputStream(file).use { fis ->
                    val buf = ByteArray(131_072)
                    var n: Int
                    while (fis.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        sent += n
                        speedWindowBytes += n

                        val now = System.currentTimeMillis()
                        val elapsed = now - speedWindowStart
                        if (elapsed >= 500) {
                            val bps = speedWindowBytes * 1000 / elapsed
                            speedWindowBytes = 0
                            speedWindowStart = now
                            progressFlow.emit(prog.copy(sentBytes = sent, speedBps = bps))
                            updateNotif(
                                "Sending ${file.name}",
                                "${formatSize(sent)} / ${formatSize(file.length())} · ${formatSpeed(bps)}"
                            )
                        }
                    }
                }
                out.flush()
                progressFlow.emit(prog.copy(sentBytes = file.length(), done = true))
                recordTransfer(id, file.name, filePath, file.length(), "SEND", ip, ip, "DONE")
                updateNotif("Sent", "${file.name} sent successfully")
            }
        } catch (e: Exception) {
            progressFlow.emit(prog.copy(error = e.message))
            updateNotif("Failed", "Could not send ${file.name}")
        }
    }

    // ── Receive ───────────────────────────────────────────────────────────────
    private suspend fun receiveFile() {
        val id = UUID.randomUUID().toString()
        updateNotif("Transfer", "Waiting for sender…")
        try {
            serverSocket = ServerSocket(TRANSFER_PORT)
            serverSocket!!.accept().use { socket ->
                val peerIp = socket.inetAddress.hostAddress ?: "unknown"
                val inp    = DataInputStream(BufferedInputStream(socket.getInputStream(), 131_072))

                val magic = inp.readUTF()
                if (!magic.startsWith("REELZ")) { updateNotif("Error", "Unknown file type"); return }

                val fileName  = inp.readUTF()
                val totalSize = inp.readLong()
                val prog = TransferProgress(id, fileName, totalSize, 0L, "RECEIVE", peerIp)
                progressFlow.emit(prog)
                updateNotif("Receiving", fileName)

                // App-private dir — NOT visible to Files app or gallery
                val outDir  = File(filesDir, "downloads").also { it.mkdirs() }
                val outFile = File(outDir, fileName)
                var received = 0L
                var speedWindowBytes = 0L
                var speedWindowStart = System.currentTimeMillis()

                FileOutputStream(outFile).use { fos ->
                    val buf = ByteArray(131_072)
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        fos.write(buf, 0, n)
                        received += n
                        speedWindowBytes += n

                        val now = System.currentTimeMillis()
                        val elapsed = now - speedWindowStart
                        if (elapsed >= 500) {
                            val bps = speedWindowBytes * 1000 / elapsed
                            speedWindowBytes = 0
                            speedWindowStart = now
                            progressFlow.emit(prog.copy(sentBytes = received, speedBps = bps))
                            updateNotif(
                                "Receiving $fileName",
                                "${formatSize(received)} / ${formatSize(totalSize)} · ${formatSpeed(bps)}"
                            )
                        }
                        if (received >= totalSize) break
                    }
                }

                progressFlow.emit(prog.copy(sentBytes = totalSize, done = true))
                recordTransfer(id, fileName, outFile.absolutePath, totalSize, "RECEIVE", peerIp, peerIp, "DONE")
                updateNotif("Received", "$fileName received")
            }
        } catch (e: Exception) {
            progressFlow.emit(TransferProgress(id, "", 0, 0, "RECEIVE", "", error = e.message))
            updateNotif("Failed", "Transfer failed")
        } finally {
            serverSocket?.close()
            serverSocket = null
        }
    }

    private suspend fun recordTransfer(id: String, name: String, path: String, size: Long, dir: String, peer: String, ip: String, status: String) {
        transferDao.insert(TransferRecord(id, name, path, size, dir, peer, ip, status))
    }

    private fun buildNotif(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true).build()

    private fun updateNotif(title: String, text: String) {
        getSystemService(NotificationManager::class.java)?.notify(2, buildNotif(title, text))
    }

    private fun createChannel() {
        NotificationChannel(CHANNEL_ID, "File Transfer", NotificationManager.IMPORTANCE_LOW)
            .let { getSystemService(NotificationManager::class.java)?.createNotificationChannel(it) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024L          -> "%.1f KB".format(bytes / 1024.0)
        else                    -> "$bytes B"
    }

    private fun formatSpeed(bps: Long): String = when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps >= 1_000     -> "%.0f KB/s".format(bps / 1_000.0)
        else             -> "$bps B/s"
    }
}
