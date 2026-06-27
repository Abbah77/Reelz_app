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

sealed class UpdateState {
    object Idle                               : UpdateState()
    data class Downloading(val percent: Int)  : UpdateState()
    object AwaitingInstallConfirmation        : UpdateState()
    object Installing                         : UpdateState()
    object Cancelled                          : UpdateState()
    data class Failed(val reason: String)     : UpdateState()
    object Success                            : UpdateState()
}

private const val TAG                   = "ApkUpdateManager"
private const val ACTION_INSTALL_STATUS = "com.axio.reelz.INSTALL_STATUS"
private const val APK_FILENAME          = "reelz_update.apk"
private const val APK_MAX_AGE_MS        = 24L * 60 * 60 * 1000L
private const val MIN_APK_SIZE_BYTES    = 100_000L

@Singleton
class ApkUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Weak ref to current Activity — used ONLY to launch the install dialog
    // so we use startActivityForResult context, not application context.
    // WeakRef prevents leaking the Activity.
    private var activityRef: WeakReference<Activity>? = null

    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detachActivity() {
        activityRef = null
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ── BroadcastReceiver ─────────────────────────────────────────────────────
    // Every line wrapped in try/catch — one uncaught exception here = full crash.
    // startActivity is done via the attached Activity (not application context)
    // to avoid the Samsung/Xiaomi WindowManager crash.

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                if (intent == null) return

                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                )
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }

                Log.d(TAG, "installReceiver status=$status message=$message")

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
                                _state.value = UpdateState.Failed(
                                    "Android did not provide an install dialog.\n" +
                                    "Go to Settings → Apps → Special app access → " +
                                    "Install unknown apps → enable for Reelz, then retry."
                                )
                                cleanupApk()
                                return
                            }

                            _state.value = UpdateState.AwaitingInstallConfirmation

                            mainHandler.post {
                                try {
                                    // Prefer Activity context — avoids Samsung WindowManager crash
                                    val activity = activityRef?.get()
                                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                                        Log.d(TAG, "Launching install dialog via Activity context")
                                        activity.startActivity(confirmIntent)
                                    } else {
                                        // Fallback: application context with NEW_TASK flag
                                        Log.d(TAG, "Launching install dialog via application context (fallback)")
                                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(confirmIntent)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "startActivity for install dialog failed: ${e.message}", e)
                                    _state.value = UpdateState.Failed(
                                        "Could not open install dialog: ${e.message}\n\n" +
                                        "Go to Settings → Apps → Special app access → " +
                                        "Install unknown apps → enable for Reelz, then retry."
                                    )
                                    cleanupApk()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "PENDING_USER_ACTION handling failed: ${e.message}", e)
                            _state.value = UpdateState.Failed("Install dialog error: ${e.message}")
                            cleanupApk()
                        }
                    }

                    PackageInstaller.STATUS_SUCCESS -> {
                        Log.d(TAG, "Install SUCCESS ✓")
                        _state.value = UpdateState.Success
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                        Log.d(TAG, "User dismissed install dialog — keeping APK for retry")
                        _state.value = UpdateState.Cancelled
                    }

                    PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                        _state.value = UpdateState.Failed(
                            "Install blocked.\n" +
                            "Go to Settings → Apps → Special app access → " +
                            "Install unknown apps → enable for Reelz."
                        )
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_INVALID -> {
                        _state.value = UpdateState.Failed(
                            "Android rejected the APK.\n" +
                            "Most likely cause: V3 was signed with a DIFFERENT keystore than V2.\n\n" +
                            "Fix: Uninstall Reelz completely, then install V3 fresh."
                        )
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                        _state.value = UpdateState.Failed(
                            "Version conflict.\n" +
                            "The APK versionCode is not higher than installed, " +
                            "or signed with a different keystore.\n\n" +
                            "Fix: Uninstall Reelz completely, then install V3 fresh."
                        )
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_STORAGE -> {
                        _state.value = UpdateState.Failed(
                            "Not enough storage space. Free up space and try again."
                        )
                        cleanupApk()
                    }

                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                        _state.value = UpdateState.Failed(
                            "APK is not compatible with your device architecture."
                        )
                        cleanupApk()
                    }

                    1 -> {
                        // STATUS_FAILURE (generic code 1) on Samsung almost always means
                        // keystore mismatch or the APK was signed with debug key.
                        _state.value = UpdateState.Failed(
                            "Install failed — Samsung blocked the install.\n\n" +
                            "This is almost always a KEYSTORE MISMATCH:\n" +
                            "V2 and V3 must be signed with the exact same keystore.\n\n" +
                            "Fix: Uninstall Reelz, install V3 fresh, " +
                            "and make sure both builds use the same signing key."
                        )
                        cleanupApk()
                    }

                    else -> {
                        _state.value = UpdateState.Failed(
                            "Install failed (code $status): ${message ?: "Unknown error"}"
                        )
                        cleanupApk()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FATAL uncaught in installReceiver.onReceive: ${e.message}", e)
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
            Log.d(TAG, "installReceiver registered OK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register installReceiver: ${e.message}", e)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startUpdate(url: String) {
        if (url.isBlank() || url.contains("yourusername") || url.contains("REPLACE")) {
            _state.value = UpdateState.Failed("Update URL is not configured.")
            return
        }
        if (_state.value is UpdateState.Downloading) return

        val cached = apkFile()
        if (_state.value is UpdateState.Cancelled
            && cached.exists()
            && cached.length() > MIN_APK_SIZE_BYTES
        ) {
            Log.d(TAG, "Reusing cached APK (${cached.length()} bytes)")
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
                }?.forEach { it.delete(); Log.d(TAG, "Swept stale APK: ${it.name}") }
            } catch (e: Exception) {
                Log.w(TAG, "sweepStaleCachedApks: ${e.message}")
            }
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun downloadAndInstall(url: String) = withContext(Dispatchers.IO) {
        val dest = apkFile()
        dest.delete()
        try {
            _state.value = UpdateState.Downloading(0)
            Log.d(TAG, "Downloading: $url")

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android ${Build.VERSION.RELEASE})")
                .header("Accept", "application/vnd.android.package-archive, */*")
                .build()

            http.newCall(req).execute().use { resp ->
                Log.d(TAG, "HTTP ${resp.code} Content-Type:${resp.header("Content-Type")}")
                if (!resp.isSuccessful) {
                    _state.value = UpdateState.Failed("Server returned HTTP ${resp.code}.")
                    return@withContext
                }
                val ct = resp.header("Content-Type") ?: ""
                if (ct.contains("text/html", ignoreCase = true)) {
                    _state.value = UpdateState.Failed(
                        "URL returned an HTML page instead of an APK.\n" +
                        "The GitHub release may not be public or the URL is wrong."
                    )
                    return@withContext
                }
                val body = resp.body ?: run {
                    _state.value = UpdateState.Failed("Empty server response.")
                    return@withContext
                }
                val len = body.contentLength()
                dest.outputStream().buffered(16_384).use { out ->
                    body.byteStream().use { src ->
                        val buf = ByteArray(16_384); var read = 0L; var n: Int
                        while (src.read(buf).also { n = it } != -1) {
                            if (!isActive) return@withContext
                            out.write(buf, 0, n); read += n
                            if (len > 0)
                                _state.value = UpdateState.Downloading(
                                    (read * 100L / len).toInt().coerceIn(0, 99)
                                )
                        }
                    }
                }
            }

            if (!isActive) return@withContext
            Log.d(TAG, "Download complete: ${dest.length()} bytes")

            val err = validateApkFile(dest)
            if (err != null) {
                _state.value = UpdateState.Failed(err); dest.delete(); return@withContext
            }

            _state.value = UpdateState.Downloading(100)
            commitSession(dest)

        } catch (e: Exception) {
            if (!isActive) return@withContext
            Log.e(TAG, "downloadAndInstall: ${e.message}", e)
            _state.value = UpdateState.Failed("Download failed: ${e.message}")
            dest.delete()
        }
    }

    private fun validateApkFile(file: File): String? {
        if (!file.exists() || file.length() == 0L)
            return "Downloaded file is empty."
        if (file.length() < MIN_APK_SIZE_BYTES)
            return "File too small (${file.length()} bytes) — not a valid APK."
        return try {
            ZipFile(file).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null)
                    "Not a valid APK — AndroidManifest.xml missing."
                else null
            }
        } catch (e: Exception) {
            "File is corrupt or not an APK: ${e.message}"
        }
    }

    private suspend fun commitSession(apk: File) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Installing
        Log.d(TAG, "Committing PackageInstaller session (${apk.length()} bytes)")
        try {
            // ── Pre-flight version check ──────────────────────────────────────
            // Extract versionCode from the downloaded APK and compare against
            // the currently installed version. Fail fast with a clear message
            // instead of letting PackageInstaller return the cryptic code 1.
            val downloadedVersionCode = getApkVersionCode(apk)
            val installedVersionCode  = try {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .longVersionCode.toInt()
            } catch (e: Exception) { 0 }

            Log.d(TAG, "Version check: installed=$installedVersionCode downloaded=$downloadedVersionCode")

            if (downloadedVersionCode > 0 && downloadedVersionCode <= installedVersionCode) {
                _state.value = UpdateState.Failed(
                    "The downloaded APK (v$downloadedVersionCode) is not newer than " +
                    "the installed version (v$installedVersionCode).\n\n" +
                    "You already have the latest version installed."
                )
                cleanupApk()
                return@withContext
            }

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName(context.packageName)

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("reelz_apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 16_384) }
                    session.fsync(out)
                }
                val pi = PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                session.commit(pi.intentSender)
                Log.d(TAG, "Session committed id=$sessionId")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}", e)
            _state.value = UpdateState.Failed(
                "Permission denied — go to Settings → Apps → Special app access → " +
                "Install unknown apps → enable for Reelz."
            )
            cleanupApk()
        } catch (e: Exception) {
            Log.e(TAG, "commitSession: ${e.message}", e)
            _state.value = UpdateState.Failed("Install session failed: ${e.message}")
            cleanupApk()
        }
    }

    private fun getApkVersionCode(apk: File): Int {
        return try {
            val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            info?.longVersionCode?.toInt() ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "getApkVersionCode failed: ${e.message}")
            -1
        }
    }

    private fun cleanupApk() {
        try { apkFile().takeIf { it.exists() }?.delete() }
        catch (e: Exception) { Log.w(TAG, "cleanupApk: ${e.message}") }
    }

    private fun apkFile() = File(context.cacheDir, APK_FILENAME)
}
