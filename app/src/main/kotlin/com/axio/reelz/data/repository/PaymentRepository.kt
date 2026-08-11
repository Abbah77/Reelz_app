package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.data.local.UserSessionDao
import com.axio.reelz.data.remote.api.ReelzApi
import com.axio.reelz.data.repository.ConfigRepository
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
 * PaymentRepository — server-side Paystack transaction init.
 *
 * The backend initialises the transaction (embeds user_id in metadata),
 * returns a one-time authorization_url, and handles the webhook that
 * activates the subscription. The client just opens the URL in the browser.
 *
 * Falls back to the static Paystack link from config if the backend is down.
 */
@Singleton
class PaymentRepository @Inject constructor(
    private val configRepo: ConfigRepository,
    private val sessionDao: UserSessionDao,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    sealed class InitResult {
        data class Success(val authorizationUrl: String, val reference: String) : InitResult()
        data class FallbackToStaticLink(val reason: String) : InitResult()
        data class Error(val message: String) : InitResult()
    }

    suspend fun initPayment(plan: String): InitResult = withContext(Dispatchers.IO) {
        val session = sessionDao.get()
            ?: return@withContext InitResult.Error("Sign in before subscribing.")

        val uid = session.uid.removePrefix("backend:").removePrefix("temp:")
            .removePrefix("email:")
        if (uid.isBlank() || uid.contains("@")) {
            return@withContext InitResult.FallbackToStaticLink(
                "Sign out and sign back in to link your account."
            )
        }

        val backendUrl = configRepo.backendUrl().trimEnd('/')
        if (backendUrl.isBlank() || backendUrl == "https://your-vps.example.com") {
            return@withContext InitResult.FallbackToStaticLink("Backend not configured.")
        }

        val body = JSONObject().apply {
            put("user_id", uid)
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
                return@withContext InitResult.FallbackToStaticLink("Server error ${response.code}.")
            }
            val json = JSONObject(response.body?.string() ?: "")
            val url  = json.optString("authorization_url", "")
            val ref  = json.optString("reference", "")
            if (url.isBlank()) {
                return@withContext InitResult.FallbackToStaticLink("No checkout URL returned.")
            }
            Log.i(TAG, "payments/init OK: ref=$ref")
            InitResult.Success(url, ref)
        } catch (e: Exception) {
            Log.e(TAG, "payments/init error: ${e.message}")
            InitResult.FallbackToStaticLink("Network error.")
        }
    }

    // Convenience: static fallback URLs from config
    fun staticMonthlyUrl(): String = configRepo.current().premium.paystackMonthlyUrl
    fun staticYearlyUrl(): String  = configRepo.current().premium.paystackYearlyUrl
}
