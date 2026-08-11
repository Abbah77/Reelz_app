package com.axio.reelz.network

import android.util.Log
import retrofit2.Response
import java.io.IOException

// ─────────────────────────────────────────────────────────────────────────────
//  ApiCallHandler — centralises all Retrofit call error handling.
//
//  Usage:
//    val result = safeApiCall { api.getFeed() }
//
//  Handles:
//   • HTTP success  → NetworkResult.Success
//   • HTTP 404      → NetworkResult.Error(isNotFound = true)
//   • HTTP 4xx/5xx  → NetworkResult.Error with message
//   • IOException   → NetworkResult.Error(isNetworkError = true)
//   • Any exception → NetworkResult.Error
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "ApiCallHandler"

suspend fun <T> safeApiCall(
    tag: String = TAG,
    call: suspend () -> Response<T>,
): NetworkResult<T> {
    return try {
        val response = call()
        when {
            response.isSuccessful -> {
                val body = response.body()
                if (body != null) {
                    NetworkResult.Success(body)
                } else {
                    Log.w(tag, "HTTP ${response.code()} but empty body")
                    NetworkResult.Error("Empty response from server", response.code())
                }
            }
            response.code() == 404 -> {
                Log.w(tag, "404 Not Found")
                NetworkResult.Error("Content not found", 404, isNotFound = true)
            }
            response.code() == 401 || response.code() == 403 -> {
                Log.w(tag, "Auth error ${response.code()}")
                NetworkResult.Error("Authentication error", response.code())
            }
            response.code() in 500..599 -> {
                Log.w(tag, "Server error ${response.code()}")
                NetworkResult.Error("Server error — try again shortly", response.code())
            }
            else -> {
                Log.w(tag, "HTTP error ${response.code()}")
                NetworkResult.Error("Request failed (${response.code()})", response.code())
            }
        }
    } catch (e: IOException) {
        Log.e(tag, "Network error: ${e.message}")
        NetworkResult.Error(
            message = "No internet connection",
            isNetworkError = true,
        )
    } catch (e: Exception) {
        Log.e(tag, "Unexpected error: ${e.message}")
        NetworkResult.Error("Unexpected error: ${e.message}")
    }
}
