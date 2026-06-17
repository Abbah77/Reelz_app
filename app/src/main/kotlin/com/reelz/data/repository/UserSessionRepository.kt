package com.reelz.data.repository

import com.reelz.data.local.UserSessionDao
import com.reelz.data.local.UserSessionStore
import com.reelz.data.model.UserSession
import com.reelz.remoteconfig.PremiumGate
import com.reelz.remoteconfig.RemoteConfigRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where premium status for an email actually comes from. Today this reads the
 * `manual_grants` list in the remote config JSON — no backend required. Replace
 * the binding in AppModule with a Firebase-backed implementation later (read
 * users/{uid} from Firestore) and nothing else in this file, or anywhere else
 * in the app, needs to change. The contract is: given an email, return what
 * that user is entitled to, or null if they have no active grant.
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
 * V1 implementation: reads manual_grants from RemoteConfigRepository. Matches
 * case-insensitively since Google emails are case-insensitive but people type
 * them inconsistently. expires_at_ms <= 0 means the grant is disabled (lets you
 * "revoke" a row in the JSON without deleting it — keeps history visible).
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
 * The heart of the premium system (per the design doc, section 10). Orchestrates:
 *   1. Google sign-in        → save basic profile instantly, fetch grant in background
 *   2. Cold start             → load from local cache instantly, refresh in background
 *   3. Sign-out               → clear local cache, PremiumGate resets to GUEST
 *   4. Manual refresh         → e.g. user taps "Refresh" after paying you directly
 *
 * Golden Rule 2: this is the ONLY place that calls out for premium status. Every
 * other screen reads PremiumGate (memory) or UserSessionStore (DataStore) — never
 * the SessionSource directly.
 */
@Singleton
class UserSessionRepository @Inject constructor(
    private val store: UserSessionStore,
    private val dao: UserSessionDao,
    private val sessionSource: SessionSource,
    private val premiumGate: PremiumGate,
) {
    /**
     * The session currently held by PremiumGate, if any — read by ViewModels
     * that need name/email/photoUrl to restore a visible profile, not just the
     * boolean isPremium() check PremiumGate itself exposes. Always a fast
     * in-memory read (DataStore is loaded once into PremiumGate at cold start
     * via loadLocalSession(), never re-read from disk on every call here).
     */
    fun currentSessionOrNull(): UserSession? = premiumGate.currentSession()

    /** Call once from Application.onCreate(). Local only — never touches the network. */
    suspend fun loadLocalSession() {
        val cached = store.load() ?: dao.get()
        premiumGate.update(cached)
    }

    /**
     * Called right after Google sign-in succeeds. Saves the basic profile
     * immediately so the UI updates with name/photo with zero delay, then
     * resolves the grant (also local — manual_grants lives in the already-loaded
     * config, no network call) and finalizes the session.
     */
    suspend fun onSignedIn(name: String, email: String, photoUrl: String?) {
        val uid = stableUidForEmail(email)
        val basicSession = UserSession(
            uid = uid, name = name, email = email, photoUrl = photoUrl,
            cachedAtMs = System.currentTimeMillis(),
        )
        store.save(basicSession)
        premiumGate.update(basicSession)

        refreshFromSource(uid, name, email, photoUrl)
    }

    /**
     * Re-resolves the current session's grant. Safe to call on cold start
     * (background refresh) or after the user pays you and taps "I've paid /
     * Refresh status" on PremiumScreen — same call, both callers just trigger it
     * at different times.
     */
    suspend fun refreshCurrentSession() {
        val current = store.load() ?: return
        refreshFromSource(current.uid, current.name, current.email, current.photoUrl)
    }

    private suspend fun refreshFromSource(uid: String, name: String, email: String, photoUrl: String?) {
        val grant = try {
            sessionSource.fetch(email)
        } catch (_: Exception) {
            null // Rule 6: any failure here fails safe toward free, never toward premium
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
    }

    suspend fun signOut() {
        store.clear()
        dao.clear()
        premiumGate.update(null)
    }

    /**
     * Derives a stable internal uid from the email string the sign-in flow gives us.
     * NOTE: see the comment at GoogleSignInButton's onSignedIn call in ProfileScreen.kt —
     * confirm in Logcat ("ReelzAuth: signed in as ...") that this string is actually the
     * Google account email before relying on manual_grants matching it.
     */
    private fun stableUidForEmail(email: String): String =
        if (email.isBlank()) UUID.randomUUID().toString() else "email:${email.trim().lowercase()}"
}
