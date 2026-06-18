package com.reelz.data.repository

import android.util.Log
import com.reelz.remoteconfig.RemoteConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BackendAuthRepo"

/**
 * BackendAuthRepository
 * ─────────────────────
 * Handles the one-time POST /auth/google call that happens right after the
 * user signs in with Google.
 *
 * What this does:
 *   1. Sends the Google id_token to the backend (the only thing we trust).
 *   2. Backend verifies with Google, upserts the user row, returns user_id
 *      + current subscription status.
 *   3. We return an [AuthResult] that [UserSessionRepository.onSignedIn] uses
 *      to populate the local session.
 *
 * What this does NOT do:
 *   • Never sends name, avatar, or email to derive identity (backend uses google_sub).
 *   • Never stores the id_token.
 *   • Never called again until the next explicit sign-in.
 */
@Singleton
class BackendAuthRepository @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class AuthResult(
        /** UUID assigned by the backend — store this as the local uid. */
        val userId: String,
        val isPremium: Boolean,
        val status: String,
        val expiresAtMs: Long,
    )

    /**
     * Exchange a Google id_token for a backend user_id + subscription status.
     * Returns null on any failure — the caller should fall back to the
     * existing local session or treat the user as free.
     */
    suspend fun exchangeToken(idToken: String): AuthResult? = withContext(Dispatchers.IO) {
        val backendUrl = remoteConfig.backendConfig().backendUrl.trimEnd('/')
        if (backendUrl.isBlank()) {
            Log.e(TAG, "backend_url not set in config.json")
            return@withContext null
        }

        val body = JSONObject().put("id_token", idToken).toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$backendUrl/auth/google")
            .post(body)
            .build()

        return@withContext try {
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "auth/google returned ${response.code}")
                return@withContext null
            }

            val json      = JSONObject(response.body?.string() ?: return@withContext null)
            val userId    = json.optString("user_id", "")
            val premium   = json.optBoolean("premium", false)
            val status    = json.optString("status", "none")
            val expiresAt = json.optString("expires_at", null)

            if (userId.isBlank()) {
                Log.e(TAG, "Backend returned no user_id")
                return@withContext null
            }

            val expiresAtMs: Long = expiresAt
                ?.let { parseIso8601ToMs(it) }
                ?: 0L

            Log.i(TAG, "auth/google OK: uid=${userId.take(8)}... premium=$premium")
            AuthResult(
                userId      = userId,
                isPremium   = premium,
                status      = status,
                expiresAtMs = expiresAtMs,
            )
        } catch (e: Exception) {
            Log.e(TAG, "auth/google network error: ${e.message}")
            null
        }
    }

    private fun parseIso8601ToMs(iso: String): Long? = try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        fmt.parse(iso)?.time
    } catch (_: Exception) {
        try {
            val fmt2 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt2.parse(iso)?.time
        } catch (_: Exception) { null }
    }
}
