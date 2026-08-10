package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.data.local.UserSessionDao
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BackendSessionSource"

/**
 * BackendSessionSource
 * ────────────────────
 * Production implementation of [SessionSource]. Talks to the Reelz FastAPI
 * backend to verify subscription status.
 *
 * Local-first rules:
 *   • Cache TTL: 24 h. If cache is fresh and user is premium, skip the network.
 *   • Called at most ONCE per launch (caller enforces staleness check).
 *   • Called once after the user pays ("Refresh status" tap).
 *   • NEVER called per-screen.
 *   • Any network failure → null → PremiumGate fails SAFE toward free.
 *
 * Source of truth: UserSessionDao (Room). UserSessionStore (DataStore) has been
 * removed — Room is the single local store for session data.
 */
@Singleton
class BackendSessionSource @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
    private val sessionDao: UserSessionDao,   // Room — single source of truth
) : SessionSource {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** How long we consider a cached status fresh — 24 hours. */
    private val cacheTtlMs = 24L * 60 * 60 * 1_000

    /**
     * Fetch the subscription status for [email] from the backend.
     *
     * Flow:
     *  1. Read local Room session — instant, no network.
     *  2. If cache is fresh (< 24 h) AND user is premium AND subscription has
     *     not expired → return cached grant, skip network call entirely.
     *  3. Otherwise call GET /subscription/status?user_id=<uuid> on backend.
     *  4. Parse response → return [SessionSource.Grant] or null.
     *  5. Any exception → null (fail safe toward free).
     *
     * NOTE: [email] is accepted for interface compatibility but the backend
     * identifies users by their UUID (uid prefixed "backend:"), not email.
     */
    override suspend fun fetch(email: String): SessionSource.Grant? =
        withContext(Dispatchers.IO) {

            // ── 1. Read local Room session ────────────────────────────────
            val session = sessionDao.get()
            val now = System.currentTimeMillis()

            // ── 2. Cache-hit path: skip backend if still fresh ────────────
            if (session != null && session.isPremium) {
                val age = now - session.cachedAtMs
                if (age < cacheTtlMs && session.expiresAtMs > now) {
                    Log.d(TAG, "Cache hit — premium valid for ${(session.expiresAtMs - now) / 3_600_000}h more")
                    return@withContext SessionSource.Grant(
                        isPremium   = true,
                        plan        = session.plan,
                        expiresAtMs = session.expiresAtMs,
                    )
                }
            }

            // ── 3. Resolve userId stored in Room ──────────────────────────
            // uid is stored as "backend:<uuid>" at sign-in.
            val userId = session?.uid?.removePrefix("backend:")
                ?: run {
                    Log.w(TAG, "No local session in Room — skipping backend check")
                    return@withContext null
                }

            // Legacy "email:..." uid means user hasn't signed in against the
            // new backend yet. Skip the backend call.
            if (userId.startsWith("email:") || userId.isBlank()) {
                Log.w(TAG, "Legacy uid format — user must sign in again to activate backend session")
                return@withContext null
            }

            // ── 4. Call backend ───────────────────────────────────────────
            val backendUrl = remoteConfig.backendConfig().normalizedUrl
            if (backendUrl.isBlank()) {
                Log.e(TAG, "backend_url not set in config.json — cannot check subscription")
                return@withContext null
            }

            val url = "$backendUrl/subscription/status?user_id=$userId"
            Log.d(TAG, "Checking subscription: $url")

            return@withContext try {
                val request = Request.Builder().url(url).get().build()
                val response = http.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "Backend returned ${response.code} — failing safe toward free")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val premium   = json.optBoolean("premium", false)
                val status    = json.optString("status", "none")
                val expiresAt = json.optString("expires_at", null)

                if (!premium) {
                    Log.d(TAG, "Backend says not premium (status=$status)")
                    return@withContext null
                }

                val expiresAtMs: Long = expiresAt
                    ?.let { parseIso8601ToMs(it) }
                    ?: run {
                        Log.w(TAG, "Backend returned premium=true but no expires_at — failing safe")
                        return@withContext null
                    }

                Log.i(TAG, "Backend confirmed premium until ${java.util.Date(expiresAtMs)}")
                SessionSource.Grant(
                    isPremium   = true,
                    plan        = status,
                    expiresAtMs = expiresAtMs,
                )

            } catch (e: Exception) {
                Log.e(TAG, "Network error checking subscription: ${e.message}")
                null   // fail safe toward free
            }
        }

    /** Parse ISO-8601 string "2025-07-18T10:30:00+00:00" → epoch millis. */
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
