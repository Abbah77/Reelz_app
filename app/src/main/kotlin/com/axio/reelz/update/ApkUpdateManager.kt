package com.axio.reelz.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ── Update state machine ──────────────────────────────────────────────────────

sealed class UpdateState {
    object Idle                                          : UpdateState()
    data class Downloading(val percent: Int)             : UpdateState()
    object AwaitingInstallConfirmation                   : UpdateState()
    object Installing                                    : UpdateState()
    object Cancelled                                     : UpdateState()
    data class Failed(val reason: String)                : UpdateState()
    object Success                                       : UpdateState()
}

// ── Constants ─────────────────────────────────────────────────────────────────

private const val TAG              = "ApkUpdateManager"
private const val ACTION_INSTALL_STATUS = "com.axio.reelz.INSTALL_STATUS"
private const val APK_FILENAME     = "reelz_update.apk"
private const val APK_MAX_AGE_MS   = 24L * 60 * 60 * 1000L // 24 hours

/**
 * Owns the full APK self-update flow:
 *   1. Download APK into cacheDir (not public Downloads) with live progress
 *   2. Commit via PackageInstaller.Session (gives us a result callback)
 *   3. Receive STATUS_SUCCESS / STATUS_FAILURE_ABORTED via BroadcastReceiver
 *   4. Guarantee cleanup of the temp file on success, failure, or cancel
 *
 * [UpdateScreen] should observe [state] and render whatever it receives;
 * all PackageInstaller, OkHttp, and file logic stays here.
 *
 * Call [sweepStaleCachedApks] once on app start to clean up any leftover
 * .apk files from a previous process death, missed broadcast, or install failure.
 */
@Singleton
class ApkUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ── Application-level BroadcastReceiver ──────────────────────────────────
    // Registered here (not inside a Composable) so it survives screen
    // recomposition, backgrounding, and activity recreation.

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE,
            )
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Launch the system install confirmation dialog.
                    val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirmIntent != null) {
                        _state.value = UpdateState.AwaitingInstallConfirmation
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(confirmIntent)
                    } else {
                        _state.value = UpdateState.Failed("Could not launch install dialog")
                        cleanupApk()
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    Log.d(TAG, "Install succeeded")
                    _state.value = UpdateState.Success
                    cleanupApk()  // Layer 1: explicit delete on success
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    // User dismissed the system dialog — roll back to Idle so they
                    // can retry without re-downloading (file kept intentionally).
                    Log.d(TAG, "Install aborted by user")
                    _state.value = UpdateState.Cancelled
                    // Don't delete the APK here — fast retry on next tap.
                    // The startup sweep (sweepStaleCachedApks) will clean it up
                    // if the user never retries.
                }
                else -> {
                    Log.w(TAG, "Install failed: status=$status message=$message")
                    _state.value = UpdateState.Failed("Install failed: $message")
                    cleanupApk()
                }
            }
        }
    }

    init {
        // Register the receiver at application scope so it lives as long as the process.
        val filter = IntentFilter(ACTION_INSTALL_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(installReceiver, filter)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start downloading the APK at [url].
     * If a download is already in progress this is a no-op (state stays Downloading).
     * If state is Cancelled (user dismissed the install dialog), reuses the already-
     * downloaded file and goes straight to the install step.
     */
    fun startUpdate(url: String) {
        if (_state.value is UpdateState.Downloading) return

        // If we were in Cancelled state, the APK might already be on disk.
        val cachedApk = apkFile()
        if (_state.value is UpdateState.Cancelled && cachedApk.exists() && cachedApk.length() > 0L) {
            Log.d(TAG, "Reusing cached APK for retry (${cachedApk.length()} bytes)")
            scope.launch { commitSession(cachedApk) }
            return
        }

        downloadJob = scope.launch { downloadAndInstall(url) }
    }

    /**
     * Cancel an in-progress download.
     * Cancels the OkHttp call, deletes the partial file, resets state to Idle.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        cleanupApk()  // Delete partial download immediately
        _state.value = UpdateState.Idle
    }

    /**
     * Call once from ReelzApp.onCreate().
     * Deletes any .apk files in cacheDir older than 24 hours — the backstop that
     * catches process death mid-install, missed broadcasts, or crashes.
     */
    fun sweepStaleCachedApks() {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                context.cacheDir.listFiles { f ->
                    f.name.endsWith(".apk") && (now - f.lastModified()) > APK_MAX_AGE_MS
                }?.forEach { stale ->
                    stale.delete()
                    Log.d(TAG, "Swept stale APK: ${stale.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "sweepStaleCachedApks failed: ${e.message}")
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun downloadAndInstall(url: String) = withContext(Dispatchers.IO) {
        val dest = apkFile()
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ReelzApp/2 (Android ${Build.VERSION.RELEASE})")
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _state.value = UpdateState.Failed("Server returned HTTP ${resp.code}")
                    return@withContext
                }
                val body         = resp.body ?: run {
                    _state.value = UpdateState.Failed("Empty response body")
                    return@withContext
                }
                val contentLength = body.contentLength()

                dest.outputStream().use { out ->
                    body.byteStream().use { src ->
                        val buf  = ByteArray(8192)
                        var read = 0L
                        var n: Int
                        while (src.read(buf).also { n = it } != -1) {
                            if (!isActive) {
                                // Coroutine was cancelled (user tapped cancel)
                                Log.d(TAG, "Download cancelled mid-stream")
                                return@withContext
                            }
                            out.write(buf, 0, n)
                            read += n
                            if (contentLength > 0) {
                                val pct = (read * 100L / contentLength).toInt()
                                _state.value = UpdateState.Downloading(pct)
                            }
                        }
                    }
                }
            }

            if (!isActive) return@withContext

            Log.d(TAG, "Download complete: ${dest.length()} bytes")
            commitSession(dest)

        } catch (e: Exception) {
            if (!isActive) {
                // Job was cancelled — cleanup already handled by cancelDownload()
                return@withContext
            }
            Log.e(TAG, "Download failed: ${e.message}")
            _state.value = UpdateState.Failed("Download failed: ${e.message}")
            dest.delete()
        }
    }

    private suspend fun commitSession(apk: File) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Installing
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName(context.packageName)

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("reelz_update", 0, apk.length()).use { out ->
                    apk.inputStream().use { src -> src.copyTo(out) }
                    session.fsync(out)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                session.commit(pendingIntent.intentSender)
            }
            Log.d(TAG, "PackageInstaller session committed (id=$sessionId)")
        } catch (e: Exception) {
            Log.e(TAG, "commitSession failed: ${e.message}")
            _state.value = UpdateState.Failed("Could not start install: ${e.message}")
            cleanupApk()
        }
    }

    /** Layer 1 cleanup — explicit delete. Layers 2/3 are cacheDir auto-purge and sweepStaleCachedApks. */
    private fun cleanupApk() {
        try {
            val f = apkFile()
            if (f.exists()) {
                f.delete()
                Log.d(TAG, "Deleted temp APK")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanupApk failed: ${e.message}")
        }
    }

    /** APK is stored in cacheDir — Android itself may purge this under storage pressure (Layer 2). */
    private fun apkFile(): File = File(context.cacheDir, APK_FILENAME)
}
