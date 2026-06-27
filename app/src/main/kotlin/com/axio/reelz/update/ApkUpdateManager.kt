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
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

// ── Update state machine ──────────────────────────────────────────────────────

sealed class UpdateState {
    object Idle                                   : UpdateState()
    data class Downloading(val percent: Int)      : UpdateState()
    object AwaitingInstallConfirmation            : UpdateState()
    object Installing                             : UpdateState()
    object Cancelled                              : UpdateState()
    data class Failed(val reason: String)         : UpdateState()
    object Success                                : UpdateState()
}

private const val TAG                   = "ApkUpdateManager"
private const val ACTION_INSTALL_STATUS = "com.axio.reelz.INSTALL_STATUS"
private const val APK_FILENAME          = "reelz_update.apk"
private const val APK_MAX_AGE_MS        = 24L * 60 * 60 * 1000L
private const val MIN_APK_SIZE_BYTES    = 100_000L   // 100 KB minimum — real APKs are always larger

@Singleton
class ApkUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)          // GitHub redirects to CDN — must follow
        .followSslRedirects(true)
        .build()

    // ── BroadcastReceiver (Application-level, NOT in a Composable) ────────────
    // Every code path inside onReceive is wrapped in try/catch — an uncaught
    // exception here kills the entire process because receivers run on the main thread.

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                if (intent == null) return

                val status  = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }

                Log.d(TAG, "Install broadcast received: status=$status message=$message")

                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // Show the system install confirmation dialog.
                        // MUST use FLAG_ACTIVITY_NEW_TASK — receiver has no Activity context.
                        val confirmIntent: Intent? = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(Intent.EXTRA_INTENT)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to unpack EXTRA_INTENT: ${e.message}")
                            null
                        }

                        if (confirmIntent != null) {
                            _state.value = UpdateState.AwaitingInstallConfirmation
                            try {
                                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(confirmIntent)
                            } catch (e: Exception) {
                                Log.e(TAG, "startActivity for install dialog failed: ${e.message}")
                                _state.value = UpdateState.Failed(
                                    "Could not open install dialog: ${e.message}. " +
                                    "Go to Settings → Apps → Special app access → Install unknown apps " +
                                    "and allow Reelz, then try again."
                                )
                                cleanupApk()
                            }
                        } else {
                            Log.e(TAG, "STATUS_PENDING_USER_ACTION but EXTRA_INTENT is null")
                            _state.value = UpdateState.Failed(
                                "Android did not provide an install dialog. " +
                                "Please enable 'Install unknown apps' for Reelz in Settings and try again."
                            )
                            cleanupApk()
                        }
                    }

                    PackageInstaller.STATUS_SUCCESS -> {
                        Log.d(TAG, "Install succeeded ✓")
                        _state.value = UpdateState.Success
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                        // User dismissed the system dialog — keep the APK so retry is instant.
                        Log.d(TAG, "User dismissed install dialog — keeping APK for retry")
                        _state.value = UpdateState.Cancelled
                    }

                    PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                        _state.value = UpdateState.Failed(
                            "Install blocked by Android.\n" +
                            "Go to Settings → Apps → Special app access → Install unknown apps → allow Reelz."
                        )
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_INVALID -> {
                        _state.value = UpdateState.Failed(
                            "Android rejected the APK as invalid.\n" +
                            "The APK may be signed with a different keystore than the installed version. " +
                            "Please uninstall the current version and install v3 fresh."
                        )
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                        _state.value = UpdateState.Failed(
                            "This APK is not compatible with your device (wrong ABI or minSdk)."
                        )
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_STORAGE -> {
                        _state.value = UpdateState.Failed(
                            "Not enough storage to install. Free up space and try again."
                        )
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                        _state.value = UpdateState.Failed(
                            "Version conflict: the APK version code is not higher than the installed version, " +
                            "OR it was signed with a different keystore.\n" +
                            "Try uninstalling first, then install fresh."
                        )
                        cleanupApk()
                    }

                    else -> {
                        val reason = message ?: "Unknown error (code $status)"
                        Log.w(TAG, "Unhandled install status $status: $reason")
                        _state.value = UpdateState.Failed("Install failed: $reason")
                        cleanupApk()
                    }
                }
            } catch (e: Exception) {
                // Last-resort catch — nothing should escape onReceive and kill the process.
                Log.e(TAG, "FATAL: uncaught exception in installReceiver.onReceive: ${e.message}", e)
                _state.value = UpdateState.Failed("Unexpected error during install: ${e.message}")
                cleanupApk()
            }
        }
    }

    init {
        try {
            val filter = IntentFilter(ACTION_INSTALL_STATUS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(installReceiver, filter)
            }
            Log.d(TAG, "installReceiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register installReceiver: ${e.message}")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startUpdate(url: String) {
        if (url.isBlank() || url.contains("yourusername") || url.contains("REPLACE")) {
            _state.value = UpdateState.Failed("Update URL is not configured. Contact the developer.")
            return
        }
        if (_state.value is UpdateState.Downloading) return

        // Reuse cached APK if the user just dismissed the install dialog
        val cached = apkFile()
        if (_state.value is UpdateState.Cancelled && cached.exists() && cached.length() > MIN_APK_SIZE_BYTES) {
            Log.d(TAG, "Reusing cached APK for retry (${cached.length()} bytes)")
            _state.value = UpdateState.Idle
            scope.launch { commitSession(cached) }
            return
        }

        downloadJob = scope.launch { downloadAndInstall(url) }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        cleanupApk()
        _state.value = UpdateState.Idle
    }

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
                Log.w(TAG, "sweepStaleCachedApks error: ${e.message}")
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun downloadAndInstall(url: String) = withContext(Dispatchers.IO) {
        val dest = apkFile()
        dest.delete()

        try {
            _state.value = UpdateState.Downloading(0)
            Log.d(TAG, "Starting download from: $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android ${Build.VERSION.RELEASE})")
                .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                .build()

            http.newCall(request).execute().use { resp ->
                Log.d(TAG, "Response: HTTP ${resp.code}, Content-Type: ${resp.header("Content-Type")}, Content-Length: ${resp.header("Content-Length")}")

                if (!resp.isSuccessful) {
                    _state.value = UpdateState.Failed(
                        "Server returned HTTP ${resp.code}. Check your internet connection and try again."
                    )
                    return@withContext
                }

                val contentType = resp.header("Content-Type") ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    _state.value = UpdateState.Failed(
                        "The download URL returned an HTML page instead of an APK.\n" +
                        "The GitHub release may not be publicly accessible or the URL is wrong."
                    )
                    return@withContext
                }

                val body = resp.body ?: run {
                    _state.value = UpdateState.Failed("Server returned empty response.")
                    return@withContext
                }

                val contentLength = body.contentLength()
                Log.d(TAG, "Downloading ${if (contentLength > 0) "${contentLength / 1024} KB" else "unknown size"}")

                dest.outputStream().buffered(16_384).use { out ->
                    body.byteStream().use { src ->
                        val buf  = ByteArray(16_384)
                        var read = 0L
                        var n: Int
                        while (src.read(buf).also { n = it } != -1) {
                            if (!isActive) {
                                Log.d(TAG, "Download cancelled mid-stream")
                                return@withContext
                            }
                            out.write(buf, 0, n)
                            read += n
                            if (contentLength > 0) {
                                _state.value = UpdateState.Downloading(
                                    (read * 100L / contentLength).toInt().coerceIn(0, 99)
                                )
                            }
                        }
                    }
                }
            }

            if (!isActive) return@withContext

            Log.d(TAG, "Download complete — file size: ${dest.length()} bytes")

            // ── Validate the file is actually an APK ──────────────────────────
            val validationError = validateApkFile(dest)
            if (validationError != null) {
                Log.e(TAG, "Validation failed: $validationError")
                _state.value = UpdateState.Failed(validationError)
                dest.delete()
                return@withContext
            }

            _state.value = UpdateState.Downloading(100)
            commitSession(dest)

        } catch (e: Exception) {
            if (!isActive) return@withContext
            Log.e(TAG, "downloadAndInstall exception: ${e.javaClass.simpleName}: ${e.message}", e)
            _state.value = UpdateState.Failed(
                "Download error: ${e.message ?: "Unknown error"}. Check your internet and try again."
            )
            dest.delete()
        }
    }

    /**
     * Validate the downloaded file is a real APK before handing to PackageInstaller.
     * Returns a user-friendly error string, or null if valid.
     */
    private fun validateApkFile(file: File): String? {
        if (!file.exists() || file.length() == 0L)
            return "Downloaded file is empty. Please try again."

        if (file.length() < MIN_APK_SIZE_BYTES)
            return "Downloaded file is too small (${file.length()} bytes) to be a valid APK.\n" +
                   "The GitHub release URL may be incorrect or the file was not uploaded."

        return try {
            ZipFile(file).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null)
                    "Downloaded file is not a valid APK (AndroidManifest.xml missing).\n" +
                    "Check that the correct file was uploaded to GitHub releases."
                else
                    null // ✓ valid
            }
        } catch (e: Exception) {
            "Downloaded file is corrupt or not an APK (${e.javaClass.simpleName}).\n" +
            "The file may have been interrupted or the URL points to the wrong asset."
        }
    }

    private suspend fun commitSession(apk: File) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Installing
        Log.d(TAG, "Committing PackageInstaller session for ${apk.length()} byte APK")

        try {
            val installer = context.packageManager.packageInstaller
            val params    = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)

            val sessionId = installer.createSession(params)
            Log.d(TAG, "PackageInstaller session created: id=$sessionId")

            installer.openSession(sessionId).use { session ->
                session.openWrite("reelz_apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { src -> src.copyTo(out, bufferSize = 16_384) }
                    session.fsync(out)
                }

                val receiverIntent = Intent(ACTION_INSTALL_STATUS).apply {
                    setPackage(context.packageName)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    receiverIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                session.commit(pi.intentSender)
                Log.d(TAG, "PackageInstaller session committed (id=$sessionId)")
            }
        } catch (e: SecurityException) {
            // Thrown when REQUEST_INSTALL_PACKAGES permission is missing
            Log.e(TAG, "SecurityException in commitSession — missing REQUEST_INSTALL_PACKAGES? ${e.message}")
            _state.value = UpdateState.Failed(
                "Install permission denied by Android.\n" +
                "Go to Settings → Apps → Special app access → Install unknown apps → allow Reelz."
            )
            cleanupApk()
        } catch (e: Exception) {
            Log.e(TAG, "commitSession exception: ${e.javaClass.simpleName}: ${e.message}", e)
            _state.value = UpdateState.Failed(
                "Could not start install session: ${e.message ?: "Unknown error"}.\nPlease try again."
            )
            cleanupApk()
        }
    }

    private fun cleanupApk() {
        try {
            apkFile().takeIf { it.exists() }?.let {
                it.delete()
                Log.d(TAG, "Temp APK deleted")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanupApk error: ${e.message}")
        }
    }

    private fun apkFile(): File = File(context.cacheDir, APK_FILENAME)
}
