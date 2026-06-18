package com.reelz.data.repository

import android.util.Log
import com.reelz.data.local.UserSessionStore
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

private const val TAG = "PaymentRepository"

/**
 * PaymentRepository
 * ─────────────────
 * Calls POST /payments/init on the backend to initialise a Paystack transaction.
 *
 * Why server-side init instead of a static Paystack payment page link?
 *   - The backend embeds user_id in the transaction metadata.
 *   - The webhook uses that metadata to identify which user paid.
 *   - The amount is set server-side — can't be tampered by the client.
 *
 * The static paystack_monthly_url / paystack_yearly_url fields in config.json
 * are kept as a fallback: if [initPayment] fails (backend unreachable), the
 * caller can fall back to opening the static link. No payment is lost —
 * the user can still pay, and they tap "I've paid — refresh status" afterward.
 * The webhook still fires and activates their subscription server-side.
 */
@Singleton
class PaymentRepository @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
    private val sessionStore: UserSessionStore,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    sealed class InitResult {
        /** Backend created the transaction — open this URL in the browser sheet. */
        data class Success(val authorizationUrl: String, val reference: String) : InitResult()
        /** Backend unreachable — caller should open the static fallback URL. */
        data class FallbackToStaticLink(val reason: String) : InitResult()
        /** Permanent error (e.g. user not signed in, plan unknown). */
        data class Error(val message: String) : InitResult()
    }

    /**
     * Initialise a Paystack transaction for [plan] ("monthly" | "yearly").
     *
     * Returns [InitResult.Success] with a one-time authorization_url on success.
     * Returns [InitResult.FallbackToStaticLink] if the backend is unreachable,
     * so the caller can open the static Paystack payment page link from config
     * and the user can still pay (webhook will activate their subscription).
     */
    suspend fun initPayment(plan: String): InitResult = withContext(Dispatchers.IO) {
        // ── 1. Resolve user session ───────────────────────────────────────
        val session = sessionStore.load()
        if (session == null) {
            return@withContext InitResult.Error("Sign in before subscribing.")
        }

        // Strip the "backend:" prefix stored at sign-in to get the raw UUID
        val rawUid = session.uid.removePrefix("backend:").removePrefix("email:")
        if (rawUid.isBlank() || rawUid.contains("@")) {
            // Still a legacy email-based uid — user hasn't signed in against the
            // new backend yet. Fall back to the static link.
            Log.w(TAG, "Legacy uid — falling back to static Paystack link")
            return@withContext InitResult.FallbackToStaticLink(
                "Sign out and sign in again to link your account."
            )
        }

        // ── 2. Resolve backend URL ────────────────────────────────────────
        val backendUrl = remoteConfig.backendConfig().backendUrl.trimEnd('/')
        if (backendUrl.isBlank()) {
            Log.w(TAG, "No backend_url in config — falling back to static link")
            return@withContext InitResult.FallbackToStaticLink("Backend not configured.")
        }

        // ── 3. POST /payments/init ────────────────────────────────────────
        val body = JSONObject().apply {
            put("user_id", rawUid)
            put("plan", plan)
            put("email", session.email)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$backendUrl/payments/init")
            .post(body)
            .build()

        return@withContext try {
            val response = http.newCall(request).execute()

            if (!response.isSuccessful) {
                val code = response.code
                Log.w(TAG, "payments/init returned $code — falling back to static link")
                return@withContext InitResult.FallbackToStaticLink("Server error $code.")
            }

            val json = JSONObject(response.body?.string() ?: "")
            val url  = json.optString("authorization_url", "")
            val ref  = json.optString("reference", "")

            if (url.isBlank()) {
                Log.e(TAG, "payments/init returned no authorization_url")
                return@withContext InitResult.FallbackToStaticLink("No checkout URL returned.")
            }

            Log.i(TAG, "payments/init OK: ref=$ref")
            InitResult.Success(authorizationUrl = url, reference = ref)

        } catch (e: Exception) {
            Log.e(TAG, "payments/init network error: ${e.message}")
            InitResult.FallbackToStaticLink("Network error: ${e.message}")
        }
    }
}
