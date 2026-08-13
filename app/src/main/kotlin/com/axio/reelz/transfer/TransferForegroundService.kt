package com.axio.reelz.transfer

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.axio.reelz.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
//  TransferForegroundService — keeps transfers alive when app goes background
//
//  Started when a connection is established. Stopped by TransferManager when
//  the session ends (disconnect, done, or error).
// ─────────────────────────────────────────────────────────────────────────────

private const val CHANNEL_ID   = "reelz_transfer"
private const val NOTIF_ID     = 2001
const val ACTION_DISCONNECT    = "com.axio.reelz.TRANSFER_DISCONNECT"

@AndroidEntryPoint
class TransferForegroundService : Service() {

    @Inject lateinit var manager: TransferManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Reelz Beam active"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                manager.disconnect()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TransferForegroundService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Reelz Beam")
            .setContentText(text)
            .setContentIntent(tapIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "File Transfer",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Reelz Beam file transfer status" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    companion object {
        fun start(ctx: Context) {
            val intent = Intent(ctx, TransferForegroundService::class.java)
            ctx.startForegroundService(intent)
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TransferForegroundService::class.java))
        }
    }
}
