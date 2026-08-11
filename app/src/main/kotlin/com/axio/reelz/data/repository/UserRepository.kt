package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.data.local.UserSessionDao
import com.axio.reelz.data.local.UserSessionRow
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

@Singleton
class UserRepository @Inject constructor(
    private val api: ReelzApi,
    private val dao: UserSessionDao,
) {
    private val tag = "UserRepository"

    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session.asStateFlow()

    val isPremium: Boolean get() = _session.value?.isPremium == true
    val accessToken: String get() = _session.value?.let { "Bearer ${it.uid}" } ?: ""

    // ── Init — load from Room on app start ────────────────────────────────────

    suspend fun init() = withContext(Dispatchers.IO) {
        val row = dao.get()
        if (row != null) {
            _session.value = row.toModel()
            Log.d(tag, "Session loaded: uid=${row.uid.take(8)} premium=${row.isPremium}")
            // Background refresh if token is stale (> 24h)
            if (row.isTokenStale()) {
                refreshSession()
            }
        }
    }

    // ── Google sign-in ────────────────────────────────────────────────────────

    suspend fun signInWithGoogle(
        idToken: String,
        name: String,
        email: String,
        photoUrl: String?,
    ): NetworkResult<UserSession> = withContext(Dispatchers.IO) {
        // Save profile immediately so UI updates without waiting for backend
        val tempRow = UserSessionRow(
            uid      = "temp:${email.lowercase()}",
            name     = name,
            email    = email,
            photoUrl = photoUrl,
        )
        dao.upsert(tempRow)
        _session.value = tempRow.toModel()

        // Exchange token with backend
        val result = safeApiCall(tag) { api.authWithGoogle(GoogleAuthBody(idToken)) }
        when (result) {
            is NetworkResult.Success -> {
                val dto = result.data
                if (!dto.ok || dto.userId.isBlank()) {
                    return@withContext NetworkResult.Error("Auth failed: no user ID returned")
                }
                val row = UserSessionRow(
                    uid          = dto.userId,
                    name         = dto.name.ifBlank { name },
                    email        = dto.email.ifBlank { email },
                    photoUrl     = dto.photoUrl ?: photoUrl,
                    accessToken  = dto.accessToken,
                    isPremium    = dto.premium,
                    plan         = dto.status,
                    expiresAtMs  = dto.expiresAtMs,
                )
                dao.clear()
                dao.upsert(row)
                _session.value = row.toModel()
                Log.i(tag, "Signed in: uid=${dto.userId.take(8)} premium=${dto.premium}")
                NetworkResult.Success(row.toModel())
            }
            is NetworkResult.Error -> {
                Log.w(tag, "Backend auth failed: ${result.message} — keeping temp session")
                // Keep the temp session so the user isn't stuck
                NetworkResult.Success(_session.value!!, fromCache = true)
            }
            else -> result.map { _session.value!! }
        }
    }

    // ── Refresh session (background, after payment) ───────────────────────────

    suspend fun refreshSession() = withContext(Dispatchers.IO) {
        val token = dao.get()?.accessToken?.takeIf { it.isNotBlank() } ?: return@withContext
        val result = safeApiCall(tag) { api.refreshSession("Bearer $token") }
        if (result is NetworkResult.Success) {
            val dto = result.data
            if (dto.ok && dto.userId.isNotBlank()) {
                val current = dao.get() ?: return@withContext
                val updated = current.copy(
                    isPremium   = dto.premium,
                    plan        = dto.status,
                    expiresAtMs = dto.expiresAtMs,
                    cachedAtMs  = System.currentTimeMillis(),
                )
                dao.upsert(updated)
                _session.value = updated.toModel()
                Log.d(tag, "Session refreshed: premium=${dto.premium}")
            }
        }
    }

    // ── Sign out ──────────────────────────────────────────────────────────────

    suspend fun signOut() = withContext(Dispatchers.IO) {
        dao.clear()
        _session.value = null
        Log.i(tag, "Signed out")
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private fun UserSessionRow.toModel() = UserSession(
        uid         = uid,
        name        = name,
        email       = email,
        photoUrl    = photoUrl,
        isPremium   = isPremium,
        plan        = plan,
        expiresAtMs = expiresAtMs,
        cachedAtMs  = cachedAtMs,
    )
}
