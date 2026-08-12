package com.axio.reelz.data.repository

import com.axio.reelz.core.network.NetworkResult
import com.axio.reelz.core.network.safeApiCall
import com.axio.reelz.data.remote.api.ReelzApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PaymentRepository — initiates Paystack checkout sessions via the Reelz backend.
 * The backend creates a transaction and returns an authorization URL.
 * The webhook (not the app) verifies payment completion.
 */
@Singleton
class PaymentRepository @Inject constructor(
    private val api: ReelzApi,
) {
    sealed class InitResult {
        data class Success(val authorizationUrl: String) : InitResult()
        object FallbackToStaticLink : InitResult()
        data class Error(val message: String) : InitResult()
    }

    suspend fun initPayment(plan: String): InitResult {
        return try {
            val dto = api.initPayment(plan)
            val url = dto.authorizationUrl
            if (url.isNotBlank()) InitResult.Success(url)
            else InitResult.FallbackToStaticLink
        } catch (e: Exception) {
            InitResult.FallbackToStaticLink
        }
    }
}
