package com.axio.reelz.update

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

// ── State machine ─────────────────────────────────────────────────────────────

sealed class UpdateState {
    object Idle                                        : UpdateState()
    data class Downloading(
        val percent       : Int,
        val bytesDownloaded: Long = 0L,
        val totalBytes    : Long  = 0L,
    ) : UpdateState()
    object AwaitingInstallConfirmation                 : UpdateState()
    object Installing                                  : UpdateState()
    object Cancelled                                   : UpdateState()
    data class Failed(val reason: String)              : UpdateState()
    object Success                                     : UpdateState()
}

private const val TAG                   = "ApkUpdateManager"
private const val ACTION_INSTALL_STATUS = "com.axio.reelz.INSTALL_STATUS"
private const val APK_FILENAME          = "reelz_update.apk"
private const val APK_MAX_AGE_MS        = 24L * 60 * 60 * 1000L
private const val MIN_APK_SIZE_BYTES    = 100_000L

/**
 * APK self-update using OkHttp (streaming, 16 KB chunks → no RAM pressure)
 * + PackageInstaller.Session (no file:// URI anywhere — we stream directly
 * from the File into the session OutputStream).
 *
 * Why no file:// URI:
 * - Android 7+ throws FileUriExposedException on file:// passed to external components
 * - Android 10+ blocks them in DownloadManager destinations too
 * - PackageInstaller.Session.openWrite() gives us an OutputStream we write into
 *   directly from our own File — no URI exposure, no FileProvider needed
 *
 * RAM usage:
 * - OkHttp streams in 16 KB chunks: read → write to disk → next chunk
 * - A 50 MB APK uses ~16 KB of heap at any moment, not 50 MB
 *
 * Resilience:
 * - Download runs on Dispatchers.IO coroutine
 * - isActive checks throughout — cancellation is clean
 * - File deleted on cancel, failure, and success
 * - Startup sweep cleans up any orphan from process death
 */
@Singleton
class ApkUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob : Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activityRef: WeakReference<Activity>? = null
    fun attachActivity(a: Activity) { activityRef = WeakReference(a) }
    fun detachActivity()            { activityRef = null }

    private val _state    = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // No read timeout — APK can be large on slow connections
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ── PackageInstaller result receiver ──────────────────────────────────────
    // Registered at Application level — survives screen recomposition.
    // Every line in onReceive is inside try/catch — one uncaught exception
    // here kills the entire process (receivers run on main thread).

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                if (intent == null) return
                val status  = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }
                log("PackageInstaller → status=$status message=$message")

                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        try {
                            @Suppress("DEPRECATION")
                            val confirmIntent: Intent? =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                else
                                    intent.getParcelableExtra(Intent.EXTRA_INTENT)

                            if (confirmIntent == null) {
                                log("ERROR: confirmIntent is null")
                                _state.value = UpdateState.Failed(
                                    "Android did not provide an install dialog.\n" +
                                    "Go to Settings → Apps → Special app access → " +
                                    "Install unknown apps → enable for Reelz."
                                )
                                cleanupApk()
                                return
                            }

                            _state.value = UpdateState.AwaitingInstallConfirmation
                            log("Launching system install dialog via Activity context")

                            // Must post to main thread.
                            // Must use Activity context (not Application) on Samsung/Xiaomi
                            // to avoid WindowManager$BadTokenException crash.
                            mainHandler.post {
                                try {
                                    val activity = activityRef?.get()
                                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                                        activity.startActivity(confirmIntent)
                                    } else {
                                        log("Activity unavailable — using Application context fallback")
                                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(confirmIntent)
                                    }
                                } catch (e: Exception) {
                                    log("ERROR startActivity: ${e.message}")
                                    _state.value = UpdateState.Failed(
                                        "Could not open install dialog: ${e.message}\n" +
                                        "Enable 'Install unknown apps' for Reelz in Settings."
                                    )
                                    cleanupApk()
                                }
                            }
                        } catch (e: Exception) {
                            log("ERROR handling PENDING_USER_ACTION: ${e.message}")
                            _state.value = UpdateState.Failed("Install dialog error: ${e.message}")
                            cleanupApk()
                        }
                    }

                    PackageInstaller.STATUS_SUCCESS -> {
                        log("Install SUCCESS ✓")
                        _state.value = UpdateState.Success
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                        log("User dismissed install dialog — keeping APK for instant retry")
                        _state.value = UpdateState.Cancelled
                        // Keep the APK — "Try Again" reuses it without re-downloading
                    }

                    PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                        log("Install BLOCKED")
                        _state.value = UpdateState.Failed(
                            "Install blocked by Android.\n" +
                            "Go to Settings → Apps → Special app access → " +
                            "Install unknown apps → enable for Reelz."
                        )
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_INVALID -> {
                        log("Install INVALID — keystore mismatch or corrupt APK")
                        _state.value = UpdateState.Failed(
                            "Android rejected the APK.\n" +
                            "This usually means V2 and V3 were signed with different keystores.\n" +
                            "Uninstall Reelz and install V3 fresh to fix."
                        )
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                        log("Install CONFLICT — same or lower versionCode")
                        _state.value = UpdateState.Failed(
                            "Version conflict — downloaded APK is not newer than installed version.\n" +
                            "Make sure V3 has a higher versionCode than V2."
                        )
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_STORAGE -> {
                        log("Install FAILED — insufficient storage")
                        _state.value = UpdateState.Failed("Not enough storage. Free up space and try again.")
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                        log("Install INCOMPATIBLE — wrong ABI or minSdk")
                        _state.value = UpdateState.Failed("APK is not compatible with your device.")
                        cleanupApk()
                    }

                    1 -> {
                        // Generic STATUS_FAILURE — on Samsung almost always means
                        // keystore mismatch or versionCode not higher
                        log("Install FAILED code=1 (Samsung generic rejection)")
                        _state.value = UpdateState.Failed(
                            "Install failed (code 1).\n" +
                            "On Samsung this usually means keystore mismatch.\n" +
                            "Both V2 and V3 must be signed with the exact same keystore."
                        )
                        cleanupApk()
                    }

                    else -> {
                        log("Install FAILED code=$status")
                        _state.value = UpdateState.Failed(
                            "Install failed (code $status): ${message ?: "Unknown error"}"
                        )
                        cleanupApk()
                    }
                }
            } catch (e: Exception) {
                // Last-resort catch — nothing must escape onReceive
                log("FATAL uncaught in installReceiver: ${e.message}")
                try {
                    _state.value = UpdateState.Failed("Unexpected install error: ${e.message}")
                    cleanupApk()
                } catch (_: Exception) {}
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
            log("installReceiver registered OK")
        } catch (e: Exception) {
            log("ERROR registering installReceiver: ${e.message}")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startUpdate(url: String) {
        if (url.isBlank() || url.contains("yourusername") || url.contains("REPLACE")) {
            log("ERROR: Update URL is not configured")
            _state.value = UpdateState.Failed("Update URL is not configured. Contact the developer.")
            return
        }
        if (_state.value is UpdateState.Downloading) {
            log("Already downloading — ignoring duplicate call")
            return
        }

        // If user previously dismissed install dialog, reuse the APK — no re-download
        val cached = apkFile()
        if (_state.value is UpdateState.Cancelled && cached.exists() && cached.length() > MIN_APK_SIZE_BYTES) {
            log("Reusing cached APK for retry (${cached.length()} bytes)")
            _state.value = UpdateState.Idle
            scope.launch { commitSession(cached) }
            return
        }

        downloadJob = scope.launch { downloadAndInstall(url) }
    }

    fun cancelDownload() {
        log("Download cancelled by user")
        downloadJob?.cancel()
        downloadJob = null
        cleanupApk()
        _state.value = UpdateState.Idle
    }

    /** Call once from ReelzApp.onCreate() to clean up orphan APKs from previous crashes. */
    fun sweepStaleCachedApks() {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                context.cacheDir.listFiles { f ->
                    f.name.endsWith(".apk") && (now - f.lastModified()) > APK_MAX_AGE_MS
                }?.forEach {
                    it.delete()
                    log("Swept stale APK: ${it.name}")
                }
            } catch (e: Exception) {
                log("sweepStaleCachedApks error: ${e.message}")
            }
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private suspend fun downloadAndInstall(url: String) = withContext(Dispatchers.IO) {
        val dest = apkFile()
        dest.delete()   // Remove any stale file before starting fresh

        try {
            _state.value = UpdateState.Downloading(0)
            log("Starting OkHttp download from: $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android ${Build.VERSION.RELEASE})")
                .header("Accept", "application/vnd.android.package-archive, */*")
                .build()

            http.newCall(request).execute().use { resp ->
                log("HTTP ${resp.code} | Content-Type: ${resp.header("Content-Type")} | Content-Length: ${resp.header("Content-Length")}")

                if (!resp.isSuccessful) {
                    val reason = when (resp.code) {
                        400  -> "HTTP 400 — Bad request. The URL may be malformed."
                        401  -> "HTTP 401 — Unauthorized. The file requires authentication."
                        403  -> "HTTP 403 — Forbidden. The file is not publicly accessible."
                        404  -> "HTTP 404 — File not found. The APK URL is wrong or the file was deleted.\nUpdate the config with a valid URL."
                        500  -> "HTTP 500 — Server error. Try again later."
                        else -> "HTTP ${resp.code} — Unexpected response from server."
                    }
                    log("ERROR: $reason")
                    _state.value = UpdateState.Failed(reason)
                    return@withContext
                }

                // Reject HTML responses (error pages that return 200)
                val contentType = resp.header("Content-Type") ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    log("ERROR: Server returned HTML page instead of APK")
                    _state.value = UpdateState.Failed(
                        "The download URL returned an HTML page instead of an APK.\n" +
                        "The file may have been deleted or the URL is wrong."
                    )
                    return@withContext
                }

                val body = resp.body ?: run {
                    log("ERROR: Empty response body")
                    _state.value = UpdateState.Failed("Server returned an empty response.")
                    return@withContext
                }

                val totalBytes = body.contentLength()
                log("Downloading ${if (totalBytes > 0) "${totalBytes / 1_048_576} MB" else "unknown size"}")

                // Stream in 16 KB chunks — APK never fully loaded into RAM
                dest.outputStream().buffered(16_384).use { out ->
                    body.byteStream().use { src ->
                        val buf  = ByteArray(16_384)
                        var read = 0L
                        var n    : Int
                        while (src.read(buf).also { n = it } != -1) {
                            if (!isActive) {
                                log("Download cancelled mid-stream — cleaning up")
                                return@withContext
                            }
                            out.write(buf, 0, n)
                            read += n
                            if (totalBytes > 0) {
                                _state.value = UpdateState.Downloading(
                                    percent        = (read * 100L / totalBytes).toInt().coerceIn(0, 99),
                                    bytesDownloaded = read,
                                    totalBytes     = totalBytes,
                                )
                            } else {
                                // Unknown size — show bytes downloaded only
                                _state.value = UpdateState.Downloading(
                                    percent        = -1,
                                    bytesDownloaded = read,
                                    totalBytes     = 0L,
                                )
                            }
                        }
                    }
                }
            }

            if (!isActive) return@withContext

            log("Download complete — ${dest.length()} bytes on disk")

            // ── Validate ──────────────────────────────────────────────────────
            val validationErr = validateApkFile(dest)
            if (validationErr != null) {
                log("Validation FAILED: $validationErr")
                _state.value = UpdateState.Failed(validationErr)
                dest.delete()
                return@withContext
            }
            log("Validation PASSED ✓")

            // ── Version pre-flight ────────────────────────────────────────────
            val downloadedVersion = getApkVersionCode(dest)
            val installedVersion  = try {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            } catch (_: Exception) { 0 }

            log("Version check: installed=$installedVersion downloaded=$downloadedVersion")

            if (downloadedVersion > 0 && downloadedVersion <= installedVersion) {
                log("Downloaded APK is NOT newer than installed — aborting")
                _state.value = UpdateState.Failed(
                    "Downloaded APK (v$downloadedVersion) is not newer than installed (v$installedVersion).\n" +
                    "You already have the latest version."
                )
                cleanupApk()
                return@withContext
            }

            _state.value = UpdateState.Downloading(100, dest.length(), dest.length())
            commitSession(dest)

        } catch (e: Exception) {
            if (!isActive) return@withContext
            log("Download exception: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = UpdateState.Failed(
                "Download failed: ${e.message ?: "Unknown error"}.\nCheck your internet connection."
            )
            dest.delete()
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private fun validateApkFile(file: File): String? {
        if (!file.exists() || file.length() == 0L)
            return "Downloaded file is empty. Please try again."
        if (file.length() < MIN_APK_SIZE_BYTES)
            return "File too small (${file.length()} bytes) — not a valid APK.\n" +
                   "The URL may point to an error page instead of the real APK."
        return try {
            ZipFile(file).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null)
                    "Not a valid APK — AndroidManifest.xml missing.\n" +
                    "Wrong file may have been uploaded to the host."
                else null
            }
        } catch (e: Exception) {
            "File is corrupt or not an APK (${e.javaClass.simpleName}).\n" +
            "The download may have been interrupted."
        }
    }

    private fun getApkVersionCode(apk: File): Int {
        return try {
            context.packageManager
                .getPackageArchiveInfo(apk.absolutePath, 0)
                ?.longVersionCode?.toInt() ?: -1
        } catch (e: Exception) {
            log("getApkVersionCode error: ${e.message}")
            -1
        }
    }

    // ── PackageInstaller session ──────────────────────────────────────────────
    // We stream the File directly into the session OutputStream.
    // No file:// URI is ever created or passed to any external component —
    // this is why we need neither FileProvider nor DownloadManager here.

    private suspend fun commitSession(apk: File) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Installing
        log("Opening PackageInstaller session (${apk.length()} bytes)")

        try {
            val installer = context.packageManager.packageInstaller
            val params    = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                // Required on Samsung One UI — without this the session is silently
                // rejected with code 1 before the install dialog ever appears.
                setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
                // Tell Android the APK size upfront — helps on low storage devices
                // and prevents Samsung's installer from pre-rejecting the session.
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // API 31+ — request user confirmation explicitly
                    // This is what forces Samsung to show the install dialog
                    // instead of silently returning code 1.
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }

            val sessionId = installer.createSession(params)
            log("Session created id=$sessionId")

            installer.openSession(sessionId).use { session ->
                // Stream file → session. No URI. No FileProvider. No file:// exposure.
                session.openWrite("reelz_apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input ->
                        input.copyTo(out, bufferSize = 65_536)
                    }
                    session.fsync(out)
                }

                val receiverIntent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
                val pi = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    receiverIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                session.commit(pi.intentSender)
                log("Session committed — waiting for system install dialog")
            }
        } catch (e: SecurityException) {
            log("SecurityException in commitSession: ${e.message}")
            _state.value = UpdateState.Failed(
                "Permission denied by Android.\n" +
                "Go to Settings → Apps → Special app access → " +
                "Install unknown apps → enable for Reelz."
            )
            cleanupApk()
        } catch (e: Exception) {
            log("ERROR in commitSession: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = UpdateState.Failed("Install session failed: ${e.message}")
            cleanupApk()
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun cleanupApk() {
        try {
            apkFile().takeIf { it.exists() }?.let {
                it.delete()
                log("Temp APK deleted from cacheDir ✓")
            }
        } catch (e: Exception) {
            log("cleanupApk error: ${e.message}")
        }
    }

    /** APK stored in cacheDir — Android may auto-purge under storage pressure (safety net). */
    private fun apkFile() = File(context.cacheDir, APK_FILENAME)

    // ── Debug ─────────────────────────────────────────────────────────────────

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}
