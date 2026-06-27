package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.data.local.UserSessionStore
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
 * backend on Render to verify subscription status.
 *
 * Design constraints (local-first):
 *   • Called at most ONCE per launch (only if >24h since last successful check).
 *   • Called once after the user pays (user taps "Refresh status").
 *   • NEVER called per-screen.
 *   • Any network failure returns null → PremiumGate fails SAFE toward free.
 *   • The backend URL comes from config.json — never hardcoded here.
 *
 * Wire-up: swap [ManualGrantSessionSource] for this in AppModule.kt.
 */
@Singleton
class BackendSessionSource @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
    private val sessionStore: UserSessionStore,
) : SessionSource {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** How long we consider a cached status fresh — 24 hours. */
    private val cacheTtlMs = 24L * 60 * 60 * 1000

    /**
     * Fetch the subscription status for [userId] from the backend.
     *
     * Flow:
     *  1. If the local cache is fresh (< 24 h old) AND user is premium → use cache, skip network.
     *  2. Otherwise call GET /subscription/status?user_id=<id> on the backend.
     *  3. Parse the response → return a [SessionSource.Grant] or null.
     *  4. Any exception → return null (fail safe).
     *
     * NOTE: `email` is not used here — the backend identifies users by their
     * UUID (user_id), which was assigned by the backend at sign-in and stored
     * locally. Email is just a display string; google_sub is the real identity.
     */
    override suspend fun fetch(email: String): SessionSource.Grant? = withContext(Dispatchers.IO) {
        // ── 1. Try local cache first ──────────────────────────────────────
        val session = sessionStore.load()
        val now = System.currentTimeMillis()

        if (session != null && session.isPremium) {
            val age = now - session.cachedAtMs
            if (age < cacheTtlMs && session.expiresAtMs > now) {
                Log.d(TAG, "Cache hit — premium valid for ${(session.expiresAtMs - now) / 3600_000}h more")
                return@withContext SessionSource.Grant(
                    isPremium   = true,
                    plan        = session.plan,
                    expiresAtMs = session.expiresAtMs,
                )
            }
        }

        // ── 2. Resolve the userId stored locally (set at sign-in) ─────────
        val userId = session?.uid?.removePrefix("backend:")
            ?: run {
                Log.w(TAG, "No local session — skipping backend check")
                return@withContext null
            }

        // If uid is still the old "email:..." format from ManualGrantSessionSource,
        // skip the backend call (user hasn't signed in against the new backend yet).
        if (userId.startsWith("email:") || userId.isBlank()) {
            Log.w(TAG, "Legacy uid format — user must sign in again to activate backend session")
            return@withContext null
        }

        // ── 3. Call backend ───────────────────────────────────────────────
        // normalizedUrl auto-adds "https://" if config.json's backend_url
        // is ever saved without a scheme (see BackendConfig.normalizedUrl).
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

            // Parse ISO-8601 expires_at into a millisecond timestamp
            val expiresAtMs: Long = expiresAt
                ?.let { parseIso8601ToMs(it) }
                ?: run {
                    Log.w(TAG, "Backend returned premium=true but no expires_at — failing safe")
                    return@withContext null
                }

            Log.i(TAG, "Backend confirmed premium until ${java.util.Date(expiresAtMs)}")
            SessionSource.Grant(
                isPremium   = true,
                plan        = status,    // backend returns the plan in status for simplicity
                expiresAtMs = expiresAtMs,
            )

        } catch (e: Exception) {
            Log.e(TAG, "Network error checking subscription: ${e.message}")
            null   // Rule 6: fail safe toward free
        }
    }

    /** Parse an ISO-8601 string like "2025-07-18T10:30:00+00:00" to epoch millis. */
    private fun parseIso8601ToMs(iso: String): Long? = try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        fmt.parse(iso)?.time
    } catch (_: Exception) {
        try {
            // Fallback: try without timezone suffix
            val fmt2 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt2.parse(iso)?.time
        } catch (_: Exception) { null }
    }
}
