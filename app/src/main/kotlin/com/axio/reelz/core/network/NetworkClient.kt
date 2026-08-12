package com.axio.reelz.core.network

import com.axio.reelz.BuildConfig
import com.axio.reelz.data.repository.ConfigRepository
import com.axio.reelz.data.repository.UserRepository
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
//  NetworkClient — OkHttp client construction + interceptor stack.
//
//  Extracted from AppModule per the restructure plan. AppModule stays as the
//  Hilt binding site; the construction logic lives here.
//
//  Interceptor stack (applied in order):
//   1. DynamicBaseUrl   → rewrites base URL to live backendUrl() per-call
//   2. Auth             → injects Bearer token from UserRepository on each call
//   3. HttpLogging      → BODY in debug, NONE in release
//
//  The dynamic base URL approach means the app never needs to rebuild its
//  Retrofit instance when the backend URL changes at runtime (config-driven).
// ─────────────────────────────────────────────────────────────────────────────

const val PLACEHOLDER_BASE = "https://placeholder.reelz.app/"

fun buildOkHttpClient(
    configRepo: () -> ConfigRepository,
    userRepo: () -> UserRepository,
): OkHttpClient {

    // 1. Dynamic base URL — reads live URL from ConfigRepository on each call.
    //    On first launch before config loads: uses BuildConfig.BACKEND_URL.
    val dynamicBaseUrl = Interceptor { chain ->
        val original = chain.request()
        val liveBase = configRepo().backendUrl().trimEnd('/') + "/"

        val rewrittenUrl = original.url.toString()
            .replaceFirst(PLACEHOLDER_BASE, liveBase)

        val newReq = try {
            original.newBuilder().url(rewrittenUrl).build()
        } catch (_: Exception) { original }

        chain.proceed(newReq)
    }

    // 2. Auth — Bearer token from live session (no rebuild needed on sign-in/out)
    val auth = Interceptor { chain ->
        val token = userRepo().accessToken
        val req = if (token.isNotBlank()) {
            chain.request().newBuilder().header("Authorization", token).build()
        } else chain.request()
        chain.proceed(req)
    }

    // 3. Logging
    val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG)
            HttpLoggingInterceptor.Level.BODY
        else
            HttpLoggingInterceptor.Level.NONE
    }

    return OkHttpClient.Builder()
        .addInterceptor(dynamicBaseUrl)
        .addInterceptor(auth)
        .addInterceptor(logging)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
