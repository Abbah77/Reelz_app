package com.axio.reelz.data.repository

import com.axio.reelz.core.network.safeApiCall
import com.axio.reelz.data.remote.api.ReelzApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PaymentRepository — Schema v4
 *
 * ENVELOPE RULE: POST /payment/init returns ApiResponse<PaymentData>.
 * Unwrap envelope.data to get authorization_url and reference.
 *
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
            val result = safeApiCall("PaymentRepository") { api.initPayment(plan) }
            if (result is com.axio.reelz.core.network.NetworkResult.Success) {
                val envelope = result.data
                val payload  = envelope.data
                if (envelope.ok && payload != null && payload.authorizationUrl.isNotBlank()) {
                    InitResult.Success(
                        authorizationUrl = payload.authorizationUrl,
                        reference        = payload.reference,
                    )
                } else {
                    InitResult.FallbackToStaticLink
                }
            } else {
                InitResult.FallbackToStaticLink
            }
        } catch (e: Exception) {
            InitResult.FallbackToStaticLink
        }
    }
}
