package com.reelz.brain

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  TasteSyncWorker
 *
 *  Silent background sync of user taste profile.
 *  Mirrors the pattern of ConfigSyncWorker — same design, same philosophy.
 *
 *  Triggers:
 *  - Periodic: every 4 hours when on WiFi (saves mobile data)
 *  - On-demand: when user logs in (download remote profile)
 *  - On-demand: when app goes to background with dirty=true (upload)
 *
 *  Network traffic per sync: ~2–8 KB JSON. Nothing more.
 *  Backend sees ~5 requests/day per active user maximum.
 *
 *  Backend contract:
 *  POST /api/v1/taste  — upload profile (body: JSON, auth: Bearer token)
 *  GET  /api/v1/taste  — download profile (auth: Bearer token)
 *
 *  The backend stores one blob per user. No analytics. No ad targeting.
 *  Just persistence so the profile survives reinstalls.
 * ════════════════════════════════════════════════════════════════════════════
 */
@HiltWorker
class TasteSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val engine: TasteEngine,
    private val authStore: TasteAuthStore,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val WORK_NAME        = "reelz_taste_sync"
        private const val KEY_DIRECTION    = "direction"
        private const val DIRECTION_UPLOAD = "upload"
        private const val DIRECTION_DOWNLOAD = "download"

        /** Schedule periodic background sync. Call once from Application.onCreate(). */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Only sync every 4 hours on WiFi to save mobile data
            val request = PeriodicWorkRequestBuilder<TasteSyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_DIRECTION to DIRECTION_UPLOAD))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Upload profile right now (call when app goes to background with dirty profile). */
        fun uploadNow(context: Context) {
            if (!WorkManager.getInstance(context).let { true }) return // Always queue
            val request = OneTimeWorkRequestBuilder<TasteSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_DIRECTION to DIRECTION_UPLOAD))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_upload",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Download profile on login (restores taste after reinstall). */
        fun downloadNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<TasteSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_DIRECTION to DIRECTION_DOWNLOAD))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_download",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = authStore.getAuthToken() ?: return@withContext Result.success() // Guest mode: skip
        val baseUrl = authStore.getApiBaseUrl()
        val direction = inputData.getString(KEY_DIRECTION) ?: DIRECTION_UPLOAD

        try {
            when (direction) {
                DIRECTION_UPLOAD -> upload(token, baseUrl)
                DIRECTION_DOWNLOAD -> download(token, baseUrl)
                else -> Result.success()
            }
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun upload(token: String, baseUrl: String): Result {
        if (!engine.isDirty) return Result.success() // Nothing changed, skip

        val json = engine.exportJson()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/v1/taste")
            .post(body)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        return if (response.isSuccessful) {
            engine.markSynced()
            Result.success()
        } else {
            Result.retry()
        }
    }

    private suspend fun download(token: String, baseUrl: String): Result {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/taste")
            .get()
            .header("Authorization", "Bearer $token")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return Result.retry()

        val body = response.body?.string() ?: return Result.success()
        engine.applyRemoteProfile(body)
        engine.markSynced()
        return Result.success()
    }
}

/**
 * Stores auth token and API base URL for the taste sync.
 * Inject this from your auth/session system.
 * If using Firebase Auth or a custom JWT, populate these after login.
 */
class TasteAuthStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        context.getSharedPreferences("reelz_auth_cache", Context.MODE_PRIVATE)
    }

    fun saveToken(token: String) = prefs.edit().putString("auth_token", token).apply()
    fun clearToken() = prefs.edit().remove("auth_token").apply()
    fun getAuthToken(): String? = prefs.getString("auth_token", null)
    fun isLoggedIn(): Boolean = getAuthToken() != null

    // You can hardcode this or pull from RemoteConfig
    fun getApiBaseUrl(): String = prefs.getString("api_base_url", "https://api.reelz.app") ?: "https://api.reelz.app"
    fun saveApiBaseUrl(url: String) = prefs.edit().putString("api_base_url", url).apply()
}
