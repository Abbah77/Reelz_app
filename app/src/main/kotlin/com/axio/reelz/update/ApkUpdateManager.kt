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
    object Idle                                       : UpdateState()
    data class Downloading(val percent: Int)          : UpdateState()
    object AwaitingInstallConfirmation                : UpdateState()
    object Installing                                 : UpdateState()
    object Cancelled                                  : UpdateState()
    data class Failed(val reason: String)             : UpdateState()
    object Success                                    : UpdateState()
}

// ── Constants ─────────────────────────────────────────────────────────────────

private const val TAG                   = "ApkUpdateManager"
private const val ACTION_INSTALL_STATUS = "com.axio.reelz.INSTALL_STATUS"
private const val APK_FILENAME          = "reelz_update.apk"
private const val APK_MAX_AGE_MS        = 24L * 60 * 60 * 1000L  // 24 hours
private const val MIN_APK_SIZE_BYTES    = 500_000L                // sanity: real APK > 500 KB

/**
 * Owns the full APK self-update flow:
 *   1. Download APK into cacheDir (not public Downloads) with live progress
 *   2. Validate the downloaded file is actually an APK (ZIP magic + AndroidManifest.xml entry)
 *   3. Commit via PackageInstaller.Session (gives us a result callback)
 *   4. Receive STATUS_SUCCESS / STATUS_FAILURE_ABORTED via BroadcastReceiver
 *   5. Guarantee cleanup of the temp file on success, failure, or cancel
 *
 * [UpdateScreen] should observe [state] and render whatever it receives.
 * All PackageInstaller, OkHttp, and file logic lives here — zero of it in the Composable.
 *
 * Call [sweepStaleCachedApks] once on app start to clean up leftover .apk files
 * from a previous process death, missed broadcast, or install failure.
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
        .readTimeout(120, TimeUnit.SECONDS)  // Large APK — give it 2 min
        .build()

    // ── Application-level BroadcastReceiver ──────────────────────────────────
    // Registered here (not inside a Composable) so it survives screen
    // recomposition, backgrounding, and activity recreation.

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status  = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }

            Log.d(TAG, "Install broadcast: status=$status message=$message")

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
                        Log.e(TAG, "STATUS_PENDING_USER_ACTION but no EXTRA_INTENT")
                        _state.value = UpdateState.Failed("Could not launch install dialog. Please try again.")
                        cleanupApk()
                    }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    Log.d(TAG, "Install succeeded")
                    _state.value = UpdateState.Success
                    cleanupApk()
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    // User dismissed the system install dialog — roll back to Cancelled
                    // so the UI shows "Try Again". Keep the APK file for fast retry.
                    Log.d(TAG, "Install aborted by user — keeping APK for retry")
                    _state.value = UpdateState.Cancelled
                }

                PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                    _state.value = UpdateState.Failed(
                        "Install blocked by Android. Please allow installs from this source in your device Settings → Install unknown apps."
                    )
                    cleanupApk()
                }

                PackageInstaller.STATUS_FAILURE_INVALID -> {
                    _state.value = UpdateState.Failed(
                        "The downloaded file appears to be corrupt. Please try again."
                    )
                    cleanupApk()
                }

                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                    _state.value = UpdateState.Failed(
                        "This update is not compatible with your device. Contact support."
                    )
                    cleanupApk()
                }

                PackageInstaller.STATUS_FAILURE_STORAGE -> {
                    _state.value = UpdateState.Failed(
                        "Not enough storage to install the update. Please free up space and try again."
                    )
                    cleanupApk()
                }

                PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                    _state.value = UpdateState.Failed(
                        "Install conflict — another version may already be installing. Try again."
                    )
                    cleanupApk()
                }

                else -> {
                    val reason = message ?: "Unknown error (code $status)"
                    Log.w(TAG, "Install failed: $reason")
                    _state.value = UpdateState.Failed("Install failed: $reason")
                    cleanupApk()
                }
            }
        }
    }

    init {
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
     * Guards against blank/placeholder URLs before touching the network.
     * If state is Cancelled (user dismissed install dialog), reuses the cached APK.
     */
    fun startUpdate(url: String) {
        // Guard: blank or placeholder URL — fail fast with a clear message.
        if (url.isBlank() || url.contains("yourusername") || url.contains("REPLACE")) {
            _state.value = UpdateState.Failed("Update URL is not configured. Contact the developer.")
            return
        }

        if (_state.value is UpdateState.Downloading) return

        // If already cancelled (user dismissed install dialog), reuse the file.
        val cached = apkFile()
        if (_state.value is UpdateState.Cancelled && cached.exists() && cached.length() > MIN_APK_SIZE_BYTES) {
            Log.d(TAG, "Reusing cached APK for retry (${cached.length()} bytes)")
            _state.value = UpdateState.Idle
            scope.launch { commitSession(cached) }
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
        cleanupApk()
        _state.value = UpdateState.Idle
    }

    /**
     * Call once from ReelzApp.onCreate() — backstop sweep for stale APK files
     * left by process death, missed broadcasts, or install crashes.
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
                Log.w(TAG, "sweepStaleCachedApks error: ${e.message}")
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun downloadAndInstall(url: String) = withContext(Dispatchers.IO) {
        val dest = apkFile()
        dest.delete() // Remove any leftover partial file

        try {
            _state.value = UpdateState.Downloading(0)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ReelzApp/2 (Android ${Build.VERSION.RELEASE})")
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _state.value = UpdateState.Failed(
                        "Server returned HTTP ${resp.code}. Check your connection and try again."
                    )
                    return@withContext
                }

                val contentType = resp.header("Content-Type") ?: ""
                // If the server sent HTML (e.g. a 404 page that returned 200), bail early.
                if (contentType.contains("text/html", ignoreCase = true)) {
                    _state.value = UpdateState.Failed(
                        "The update URL returned an error page, not an APK. Contact the developer."
                    )
                    return@withContext
                }

                val body = resp.body ?: run {
                    _state.value = UpdateState.Failed("Empty response from server. Please try again.")
                    return@withContext
                }
                val contentLength = body.contentLength()

                dest.outputStream().buffered().use { out ->
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
                                val pct = (read * 100L / contentLength).toInt().coerceIn(0, 99)
                                _state.value = UpdateState.Downloading(pct)
                            }
                        }
                    }
                }
            }

            if (!isActive) return@withContext

            // ── Validate the file before handing to PackageInstaller ──────────
            // This catches HTML 404 pages, truncated downloads, or corrupted files
            // before PackageInstaller sees them (which causes the silent crash).
            val validationError = validateApkFile(dest)
            if (validationError != null) {
                Log.e(TAG, "APK validation failed: $validationError")
                _state.value = UpdateState.Failed(validationError)
                dest.delete()
                return@withContext
            }

            _state.value = UpdateState.Downloading(100)
            Log.d(TAG, "Download + validation complete: ${dest.length()} bytes")
            commitSession(dest)

        } catch (e: Exception) {
            if (!isActive) {
                // Job was cancelled — cancelDownload() already cleaned up.
                return@withContext
            }
            Log.e(TAG, "Download failed: ${e.message}")
            _state.value = UpdateState.Failed(
                "Download failed: ${e.message ?: "Unknown network error"}. Check your connection."
            )
            dest.delete()
        }
    }

    /**
     * Validates the downloaded file is actually an APK.
     * An APK is a ZIP file that must contain "AndroidManifest.xml" at its root.
     * This catches HTML error pages saved as .apk and truncated/corrupt downloads.
     *
     * Returns a user-friendly error string, or null if the file looks valid.
     */
    private fun validateApkFile(file: File): String? {
        if (!file.exists() || file.length() == 0L) {
            return "Downloaded file is empty. Please try again."
        }
        if (file.length() < MIN_APK_SIZE_BYTES) {
            return "Downloaded file is too small to be a valid APK (${file.length()} bytes). " +
                   "The update URL may be misconfigured — contact the developer."
        }
        return try {
            ZipFile(file).use { zip ->
                val hasManifest = zip.getEntry("AndroidManifest.xml") != null
                if (!hasManifest) {
                    "Downloaded file is not a valid APK (missing AndroidManifest.xml). " +
                    "The update URL may be pointing to the wrong file."
                } else {
                    null // Valid
                }
            }
        } catch (e: Exception) {
            // ZipFile throws if the file isn't a ZIP at all (e.g. it's an HTML page)
            "Downloaded file is not a valid APK. The update URL may be misconfigured. " +
            "Contact the developer."
        }
    }

    private suspend fun commitSession(apk: File) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Installing
        try {
            val installer = context.packageManager.packageInstaller
            val params    = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("reelz_update", 0, apk.length()).use { out ->
                    apk.inputStream().use { src -> src.copyTo(out) }
                    session.fsync(out)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                session.commit(pi.intentSender)
            }
            Log.d(TAG, "PackageInstaller session committed (id=$sessionId)")
        } catch (e: Exception) {
            Log.e(TAG, "commitSession failed: ${e.message}")
            _state.value = UpdateState.Failed(
                "Could not start install: ${e.message ?: "Unknown error"}. Please try again."
            )
            cleanupApk()
        }
    }

    private fun cleanupApk() {
        try {
            val f = apkFile()
            if (f.exists()) { f.delete(); Log.d(TAG, "Deleted temp APK") }
        } catch (e: Exception) {
            Log.w(TAG, "cleanupApk error: ${e.message}")
        }
    }

    /** APK stored in cacheDir — Android may auto-purge under storage pressure. */
    private fun apkFile(): File = File(context.cacheDir, APK_FILENAME)
}
