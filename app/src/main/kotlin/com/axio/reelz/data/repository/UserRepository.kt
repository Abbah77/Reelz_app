package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.core.database.UserSessionDao
import com.axio.reelz.core.database.UserSessionRow
import com.axio.reelz.data.model.UserSession
import com.axio.reelz.data.remote.api.GoogleAuthBody
import com.axio.reelz.data.remote.api.ReelzApi
import com.axio.reelz.core.network.NetworkResult
import com.axio.reelz.core.network.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UserRepository — Schema v4
 *
 * ENVELOPE RULE: all auth endpoints return ApiResponse<T>.
 *   /auth/google  → ApiResponse<AuthData>
 *   /auth/refresh → ApiResponse<RefreshData>
 *
 * name, email, photo_url come from Google SDK — NOT from backend.
 * /auth/refresh uses access_token in Bearer header.
 * Watchlist and history are 100% local (Room DB) — no server sync.
 */
@Singleton
class UserRepository @Inject constructor(
    private val api: ReelzApi,
    private val dao: UserSessionDao,
) {
    private val tag = "UserRepository"

    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session.asStateFlow()

    val isPremium: Boolean get() = _session.value?.isPremium == true
    val accessToken: String
        get() = _session.value?.accessToken?.takeIf { it.isNotBlank() }
            ?.let { "Bearer $it" } ?: ""

    // ── Init ──────────────────────────────────────────────────────────────────

    suspend fun init() = withContext(Dispatchers.IO) {
        val row = dao.get()
        if (row != null) {
            _session.value = row.toModel()
            Log.d(tag, "Session loaded: uid=${row.uid.take(8)} premium=${row.isPremium}")
            val fiveMin = 5 * 60 * 1000L
            if (row.expiresAtMs > 0 && row.expiresAtMs - System.currentTimeMillis() < fiveMin) {
                refreshAccessToken()
            }
        }
    }

    // ── Google sign-in ────────────────────────────────────────────────────────

    suspend fun signInWithGoogle(
        idToken: String,
        // Profile from Google SDK — stored locally, not from backend
        name: String,
        email: String,
        photoUrl: String?,
    ): NetworkResult<UserSession> = withContext(Dispatchers.IO) {
        val result = safeApiCall(tag) { api.authWithGoogle(GoogleAuthBody(idToken)) }
        return@withContext when (result) {
            is NetworkResult.Success -> {
                // Unwrap envelope
                val envelope = result.data
                val payload  = envelope.data
                if (!envelope.ok || payload == null) {
                    return@withContext NetworkResult.Error(envelope.error ?: "Auth failed")
                }
                if (payload.userId.isBlank()) {
                    return@withContext NetworkResult.Error("Auth failed: no user ID returned")
                }
                val row = UserSessionRow(
                    uid                = payload.userId,
                    name               = name,
                    email              = email,
                    photoUrl           = photoUrl,
                    accessToken        = payload.accessToken,
                    refreshToken       = payload.refreshToken,
                    isPremium          = payload.premium,
                    premiumExpiresAtMs = payload.premiumExpiresAtMs,
                    expiresAtMs        = payload.expiresAtMs,
                )
                dao.clear()
                dao.upsert(row)
                _session.value = row.toModel()
                Log.i(tag, "Signed in: uid=${payload.userId.take(8)} premium=${payload.premium}")
                NetworkResult.Success(row.toModel())
            }
            is NetworkResult.Error -> NetworkResult.Error(
                message        = result.message,
                code           = result.code,
                isNetworkError = result.isNetworkError,
            )
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    // ── Refresh access token using refresh_token ───────────────────────────────

    suspend fun refreshAccessToken() = withContext(Dispatchers.IO) {
        val refreshToken = dao.get()?.refreshToken?.takeIf { it.isNotBlank() } ?: return@withContext
        val result = safeApiCall(tag) { api.refreshToken("Bearer $refreshToken") }
        if (result is NetworkResult.Success) {
            val envelope = result.data
            val payload  = envelope.data
            if (envelope.ok && payload != null && payload.accessToken.isNotBlank()) {
                val current = dao.get() ?: return@withContext
                val updated = current.copy(
                    accessToken = payload.accessToken,
                    expiresAtMs = payload.expiresAtMs,
                    cachedAtMs  = System.currentTimeMillis(),
                )
                dao.upsert(updated)
                _session.value = updated.toModel()
                Log.d(tag, "Access token refreshed")
            }
        }
    }

    // ── Sign out ──────────────────────────────────────────────────────────────

    suspend fun signOut() = withContext(Dispatchers.IO) {
        dao.clear()
        _session.value = null
        Log.i(tag, "Signed out")
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private fun UserSessionRow.toModel() = UserSession(
        uid                = uid,
        isPremium          = isPremium,
        premiumExpiresAtMs = premiumExpiresAtMs,
        expiresAtMs        = expiresAtMs,
        cachedAtMs         = cachedAtMs,
        accessToken        = accessToken,
        refreshToken       = refreshToken,
        name               = name,
        email              = email,
        photoUrl           = photoUrl,
    )
}
