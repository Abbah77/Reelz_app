package com.axio.reelz.data.repository

import com.axio.reelz.core.network.safeApiCall
import com.axio.reelz.data.remote.api.ReelzApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PaymentRepository — schema v3
 *
 * POST /payment/init returns { ok, authorization_url, reference }
 * Open authorization_url in WebView or browser for Paystack checkout.
 * Store reference locally to verify payment status after redirect.
 */
@Singleton
class PaymentRepository @Inject constructor(
    private val api: ReelzApi,
) {
    sealed class InitResult {
        data class Success(val authorizationUrl: String, val reference: String) : InitResult()
        object FallbackToStaticLink : InitResult()
        data class Error(val message: String) : InitResult()
    }

    suspend fun initPayment(plan: String): InitResult {
        return try {
            val response = api.initPayment(plan)
            val dto = if (response.isSuccessful) response.body() else null
            val url = dto?.authorizationUrl.orEmpty()
            val ref = dto?.reference.orEmpty()
            if (url.isNotBlank()) InitResult.Success(authorizationUrl = url, reference = ref)
            else InitResult.FallbackToStaticLink
        } catch (e: Exception) {
            InitResult.FallbackToStaticLink
        }
    }
}
