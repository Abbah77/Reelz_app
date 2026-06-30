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
import java.io.*
import java.net.*
import java.util.UUID
import javax.inject.Inject

/**
 * Wi-Fi Direct Transfer Service — Xender-style bidirectional, Quick Share-speed.
 *
 * Architecture:
 *  • Both devices always run a TCP server (ServerSocket) so either side can send.
 *  • Sender connects to receiver's server socket and streams the file.
 *  • Speed is limited only by the Wi-Fi Direct link (~50–200 MB/s on modern hardware).
 *  • 256 KB I/O buffers, TCP_NODELAY off (Nagle ON) for large streaming throughput.
 *  • Real-time progress + speed reporting every 300 ms.
 */
@AndroidEntryPoint
class TransferService : Service() {

    @Inject lateinit var transferDao: TransferDao

    companion object {
        const val CHANNEL_ID    = "reelz_transfer"
        const val ACTION_SEND   = "action_send"
        const val ACTION_RECEIVE = "action_receive"
        const val ACTION_STOP   = "action_stop"
        const val EXTRA_FILE    = "file_path"
        const val EXTRA_IP      = "peer_ip"
        const val EXTRA_PORT    = "peer_port"
        /** Both devices listen on this port so either can send. */
        const val TRANSFER_PORT = 49_200
        const val REELZ_MAGIC   = "REELZ\u0001"
        /** I/O buffer — 256 KB for maximum throughput */
        private const val BUF = 262_144

        /** UI subscribes to this for real-time transfer updates. */
        val progressFlow = MutableStateFlow<TransferProgress?>(null)

        /** Friendly progress model exposed to the UI. */
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
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** Our persistent receive server — always listening so peer can send to us. */
    private var receiveServer: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(2, buildNotif("Reelz Transfer", "Ready to transfer"))
        // Always start receive server so THIS device can also be a recipient
        scope.launch { runReceiveServer() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND -> {
                val filePath = intent.getStringExtra(EXTRA_FILE) ?: return START_NOT_STICKY
                val ip       = intent.getStringExtra(EXTRA_IP)   ?: return START_NOT_STICKY
                val port     = intent.getIntExtra(EXTRA_PORT, TRANSFER_PORT)
                scope.launch { sendFile(filePath, ip, port) }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        receiveServer?.close()
        super.onDestroy()
    }

    // ── Persistent receive server ─────────────────────────────────────────────

    /**
     * Runs forever while the service is alive.
     * Accepts multiple sequential incoming transfers — after one completes it
     * immediately loops back and waits for the next, just like Xender.
     */
    private suspend fun runReceiveServer() {
        try {
            receiveServer = ServerSocket(TRANSFER_PORT)
            while (scope.isActive) {
                try {
                    val socket = receiveServer!!.accept()
                    // Each accepted connection is handled in its own coroutine
                    // so simultaneous inbound transfers don't block each other.
                    scope.launch { handleIncoming(socket) }
                } catch (e: SocketException) {
                    if (receiveServer?.isClosed == true) break   // clean shutdown
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("TransferService", "Receive server error: ${e.message}")
        }
    }

    private suspend fun handleIncoming(socket: Socket) {
        val id = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            socket.use { s ->
                try {
                    val peerIp = s.inetAddress.hostAddress ?: "unknown"
                    val inp    = DataInputStream(BufferedInputStream(s.getInputStream(), BUF))

                    val magic = inp.readUTF()
                    if (!magic.startsWith("REELZ")) {
                        updateNotif("Transfer", "Unrecognised transfer request")
                        return@use
                    }

                    val fileName  = inp.readUTF()
                    val totalSize = inp.readLong()
                    val prog = Companion.TransferProgress(id, fileName, totalSize, 0L, "RECEIVE", peerIp)
                    progressFlow.emit(prog)
                    updateNotif("Receiving", fileName)

                    val outDir  = File(filesDir, "transfers").also { it.mkdirs() }
                    val outFile = uniqueFile(outDir, fileName)

                    var received = 0L
                    var windowBytes = 0L
                    var windowStart = System.currentTimeMillis()

                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(BUF)
                        var n: Int
                        while (received < totalSize) {
                            val toRead = minOf(buf.size.toLong(), totalSize - received).toInt()
                            n = inp.read(buf, 0, toRead)
                            if (n == -1) break
                            fos.write(buf, 0, n)
                            received    += n
                            windowBytes += n

                            val now     = System.currentTimeMillis()
                            val elapsed = now - windowStart
                            if (elapsed >= 300) {
                                val bps = windowBytes * 1000 / elapsed
                                windowBytes = 0; windowStart = now
                                progressFlow.emit(prog.copy(transferredBytes = received, speedBps = bps))
                                updateNotif(
                                    "Receiving ${fileName}",
                                    "${fmt(received)} / ${fmt(totalSize)} · ${fmtSpeed(bps)}"
                                )
                            }
                        }
                    }

                    progressFlow.emit(prog.copy(transferredBytes = totalSize, done = true))
                    recordTransfer(id, fileName, outFile.absolutePath, totalSize, "RECEIVE", peerIp)
                    updateNotif("Received ✓", "$fileName · ${fmt(totalSize)}")

                } catch (e: Exception) {
                    android.util.Log.w("TransferService", "Receive error: ${e.message}")
                    progressFlow.emit(
                        Companion.TransferProgress(id, "", 0, 0, "RECEIVE", "",
                            error = "Receiving failed. Make sure both devices are connected.")
                    )
                    updateNotif("Transfer failed", "Could not receive file")
                }
            }
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    private suspend fun sendFile(filePath: String, ip: String, port: Int) {
        val file = File(filePath)
        if (!file.exists()) return
        val id   = UUID.randomUUID().toString()
        val prog = Companion.TransferProgress(id, file.name, file.length(), 0L, "SEND", ip)
        progressFlow.emit(prog)
        updateNotif("Sending", file.name)

        try {
            Socket().use { socket ->
                // Connect with a reasonable timeout — Wi-Fi Direct links are fast
                socket.connect(InetSocketAddress(ip, port), 8_000)
                socket.sendBufferSize = BUF
                // Keep Nagle on (default) for large sequential writes — best throughput

                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUF))
                out.writeUTF(REELZ_MAGIC)
                out.writeUTF(file.name)
                out.writeLong(file.length())
                out.flush()

                var sent = 0L
                var windowBytes = 0L
                var windowStart = System.currentTimeMillis()

                FileInputStream(file).use { fis ->
                    val buf = ByteArray(BUF)
                    var n: Int
                    while (fis.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        sent        += n
                        windowBytes += n

                        val now     = System.currentTimeMillis()
                        val elapsed = now - windowStart
                        if (elapsed >= 300) {
                            val bps = windowBytes * 1000 / elapsed
                            windowBytes = 0; windowStart = now
                            progressFlow.emit(prog.copy(transferredBytes = sent, speedBps = bps))
                            updateNotif(
                                "Sending ${file.name}",
                                "${fmt(sent)} / ${fmt(file.length())} · ${fmtSpeed(bps)}"
                            )
                        }
                    }
                }
                out.flush()

                progressFlow.emit(prog.copy(transferredBytes = file.length(), done = true))
                recordTransfer(id, file.name, filePath, file.length(), "SEND", ip)
                updateNotif("Sent ✓", "${file.name} · ${fmt(file.length())}")
            }
        } catch (e: Exception) {
            android.util.Log.w("TransferService", "Send error: ${e.message}")
            val friendly = when {
                e is ConnectException ||
                e.message?.contains("refused") == true ->
                    "Couldn't reach the other device. Make sure you're both connected."
                e is SocketTimeoutException ->
                    "Connection timed out. Move devices closer together and try again."
                else -> "Send failed. Reconnect and try again."
            }
            progressFlow.emit(prog.copy(error = friendly))
            updateNotif("Send failed", "Could not send ${file.name}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun recordTransfer(
        id: String, name: String, path: String, size: Long, dir: String, peer: String,
    ) {
        transferDao.insert(TransferRecord(id, name, path, size, dir, peer, peer, "DONE"))
    }

    /** Returns a file that doesn't collide with an existing file in the directory. */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast(".")
        val ext  = name.substringAfterLast(".", "")
        var i    = 1
        while (f.exists()) {
            f = File(dir, if (ext.isNotEmpty()) "$base($i).$ext" else "$base($i)")
            i++
        }
        return f
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
}
