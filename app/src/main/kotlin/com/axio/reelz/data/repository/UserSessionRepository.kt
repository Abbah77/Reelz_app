package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.data.local.UserSessionDao
import com.axio.reelz.data.local.UserSessionStore
import com.axio.reelz.data.model.UserSession
import com.axio.reelz.remoteconfig.PremiumGate
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UserSessionRepo"

/**
 * Contract for resolving subscription status given a user's email.
 * Production binding: [BackendSessionSource] (talks to FastAPI backend).
 * Legacy / fallback binding: [ManualGrantSessionSource] (reads config.json).
 * Swap the binding in AppModule only — nothing else changes.
 */
interface SessionSource {
    suspend fun fetch(email: String): Grant?

    data class Grant(
        val isPremium: Boolean,
        val plan: String,
        val expiresAtMs: Long,
    )
}

/**
 * Legacy implementation: reads manual_grants from RemoteConfigRepository.
 * Kept as a safe fallback. Production uses [BackendSessionSource] instead.
 */
@Singleton
class ManualGrantSessionSource @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
) : SessionSource {
    override suspend fun fetch(email: String): SessionSource.Grant? {
        if (email.isBlank()) return null
        val grant = remoteConfig.premiumConfig().manualGrants
            .firstOrNull { it.email.trim().equals(email.trim(), ignoreCase = true) }
            ?: return null
        if (grant.expiresAtMs <= 0L) return null
        return SessionSource.Grant(
            isPremium   = true,
            plan        = grant.plan,
            expiresAtMs = grant.expiresAtMs,
        )
    }
}

/**
 * UserSessionRepository
 * ─────────────────────
 * The heart of the premium system. Local-first by design:
 *
 *   1. COLD START      → load from local DataStore/Room instantly, update UI,
 *                        then refresh in background (only if cache is stale).
 *   2. SIGN-IN         → save basic profile to show name/avatar instantly,
 *                        exchange id_token with backend (background), update session.
 *   3. SIGN-OUT        → clear local state, reset PremiumGate to GUEST.
 *   4. MANUAL REFRESH  → user taps "Refresh status" after paying.
 *
 * Rules:
 *   - ONLY this class calls out for premium status. PremiumGate is the
 *     single in-memory source of truth; every screen reads PremiumGate.
 *   - Any failure fails SAFE toward FREE, never toward premium.
 *   - Backend is hit at most: once on sign-in, once on manual refresh,
 *     once per 24 h on launch. Never per-screen.
 */
@Singleton
class UserSessionRepository @Inject constructor(
    private val store: UserSessionStore,
    private val dao: UserSessionDao,
    private val sessionSource: SessionSource,
    private val backendAuth: BackendAuthRepository,
    private val premiumGate: PremiumGate,
) {
    /**
     * The session currently held in PremiumGate's memory — read by ViewModels
     * that need name/email/photoUrl, not just the isPremium() boolean.
     * Always a fast in-memory read.
     */
    fun currentSessionOrNull(): UserSession? = premiumGate.currentSession()

    /**
     * Call once from Application.onCreate(). Reads local DataStore/Room only —
     * never touches the network. Sets PremiumGate so every screen has a state
     * before any background work starts.
     */
    suspend fun loadLocalSession() {
        val cached = store.load() ?: dao.get()
        premiumGate.update(cached)
        Log.d(TAG, "Loaded local session: premium=${cached?.isPremium} uid=${cached?.uid?.take(8)}")
    }

    /**
     * Called right after Google sign-in succeeds.
     *
     * 1. Save name/avatar/email immediately → UI updates with zero delay.
     * 2. Exchange id_token with backend (background, non-blocking for UI).
     *    - Backend returns a stable user_id (UUID) + subscription status.
     *    - We store that UUID as our uid — it survives email changes.
     * 3. If backend is unreachable, fall back to sessionSource (config grants).
     *
     * @param idToken The raw Google id_token from CredentialManager — sent to
     *                the backend for server-side verification. Not stored locally.
     */
    suspend fun onSignedIn(
        idToken: String?,
        name: String,
        email: String,
        photoUrl: String?,
    ) {
        // Step 1: show profile immediately
        val tempUid = stableUidForEmail(email)
        val basicSession = UserSession(
            uid        = tempUid,
            name       = name,
            email      = email,
            photoUrl   = photoUrl,
            cachedAtMs = System.currentTimeMillis(),
        )
        store.save(basicSession)
        premiumGate.update(basicSession)
        Log.i(TAG, "Sign-in: profile saved instantly for ${email.take(6)}...")

        // Step 2: exchange token with backend (background)
        if (!idToken.isNullOrBlank()) {
            val authResult = try {
                backendAuth.exchangeToken(idToken)
            } catch (e: Exception) {
                Log.e(TAG, "Backend auth failed: ${e.message}")
                null
            }

            if (authResult != null) {
                // Backend gave us a real UUID — use it as the canonical uid.
                // Prefix "backend:" so BackendSessionSource can recognize it.
                val backendUid = "backend:${authResult.userId}"
                val resolved = UserSession(
                    uid            = backendUid,
                    name           = name,
                    email          = email,
                    photoUrl       = photoUrl,
                    isPremium      = authResult.isPremium,
                    plan           = authResult.status,
                    expiresAtMs    = authResult.expiresAtMs,
                    subscribedAtMs = if (authResult.isPremium) System.currentTimeMillis() else 0L,
                    cachedAtMs     = System.currentTimeMillis(),
                )
                store.save(resolved)
                dao.upsert(resolved)
                premiumGate.update(resolved)
                Log.i(TAG, "Backend session resolved: premium=${authResult.isPremium}")
                return
            }
        }

        // Step 3: backend unreachable — fall back to config.json manual grants
        Log.w(TAG, "Backend unreachable at sign-in — falling back to config grants")
        refreshFromSource(tempUid, name, email, photoUrl)
    }

    /**
     * Backward-compatible overload for callers that don't have the id_token
     * (e.g. the legacy ProfileScreen flow before the CredentialManager upgrade).
     * Falls straight to the config-grant fallback.
     */
    suspend fun onSignedIn(name: String, email: String, photoUrl: String?) {
        onSignedIn(idToken = null, name = name, email = email, photoUrl = photoUrl)
    }

    /**
     * Re-checks subscription status from the source of truth.
     * Called:
     *   • In background on app launch if cache is >24 h stale (caller decides when).
     *   • When user taps "I've paid — refresh status" on PremiumScreen.
     *
     * BackendSessionSource handles its own 24 h cache check internally, so
     * callers don't need to throttle this themselves.
     */
    suspend fun refreshCurrentSession() {
        val current = store.load() ?: return
        Log.d(TAG, "Refreshing session for uid=${current.uid.take(8)}...")
        refreshFromSource(current.uid, current.name, current.email, current.photoUrl)
    }

    private suspend fun refreshFromSource(
        uid: String,
        name: String,
        email: String,
        photoUrl: String?,
    ) {
        val grant = try {
            sessionSource.fetch(email)
        } catch (e: Exception) {
            Log.e(TAG, "SessionSource.fetch failed: ${e.message}")
            null // Rule 6: fail safe toward free
        }

        val resolved = UserSession(
            uid            = uid,
            name           = name,
            email          = email,
            photoUrl       = photoUrl,
            isPremium      = grant?.isPremium ?: false,
            plan           = grant?.plan ?: "",
            expiresAtMs    = grant?.expiresAtMs ?: 0L,
            subscribedAtMs = if (grant != null) System.currentTimeMillis() else 0L,
            cachedAtMs     = System.currentTimeMillis(),
        )

        store.save(resolved)
        dao.upsert(resolved)
        premiumGate.update(resolved)
        Log.d(TAG, "Session refreshed: premium=${resolved.isPremium}")
    }

    suspend fun signOut() {
        store.clear()
        dao.clear()
        premiumGate.update(null)
        Log.i(TAG, "Signed out — session cleared")
    }

    /**
     * Derives a temporary stable uid from the email for the period between
     * sign-in and backend response. The backend call replaces this with a real
     * UUID prefixed "backend:". If the backend is never reachable, this
     * email-based uid is used as a fallback (matches legacy manual_grants logic).
     */
    private fun stableUidForEmail(email: String): String =
        if (email.isBlank()) UUID.randomUUID().toString()
        else "email:${email.trim().lowercase()}"
}
