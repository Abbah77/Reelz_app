package com.reelz.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.reelz.data.model.UserSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userSessionDataStore: DataStore<Preferences> by preferencesDataStore("reelz_user_session")

private val KEY_UID             = stringPreferencesKey("uid")
private val KEY_NAME             = stringPreferencesKey("name")
private val KEY_EMAIL            = stringPreferencesKey("email")
private val KEY_PHOTO_URL        = stringPreferencesKey("photo_url")
private val KEY_IS_PREMIUM       = booleanPreferencesKey("is_premium")
private val KEY_PLAN_TYPE        = stringPreferencesKey("plan_type")
private val KEY_EXPIRES_AT_MS    = longPreferencesKey("expires_at_ms")
private val KEY_SUBSCRIBED_AT_MS = longPreferencesKey("subscribed_at_ms")
private val KEY_CACHED_AT_MS     = longPreferencesKey("cached_at_ms")

/**
 * Instant synchronous-style reads for the signed-in user's session — no network,
 * no Room query, just a DataStore read. Room (UserSessionDao) is the second copy
 * for structured queries and to survive DataStore corruption edge cases; this
 * class and UserSessionRepository keep both in sync on every write.
 */
@Singleton
class UserSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun load(): UserSession? {
        val prefs = context.userSessionDataStore.data.first()
        val uid = prefs[KEY_UID] ?: return null
        return UserSession(
            uid            = uid,
            name           = prefs[KEY_NAME] ?: "",
            email          = prefs[KEY_EMAIL] ?: "",
            photoUrl       = prefs[KEY_PHOTO_URL],
            isPremium      = prefs[KEY_IS_PREMIUM] ?: false,
            plan           = prefs[KEY_PLAN_TYPE] ?: "",
            expiresAtMs    = prefs[KEY_EXPIRES_AT_MS] ?: 0L,
            subscribedAtMs = prefs[KEY_SUBSCRIBED_AT_MS] ?: 0L,
            cachedAtMs     = prefs[KEY_CACHED_AT_MS] ?: 0L,
        )
    }

    suspend fun save(session: UserSession) {
        context.userSessionDataStore.edit { prefs ->
            prefs[KEY_UID]             = session.uid
            prefs[KEY_NAME]             = session.name
            prefs[KEY_EMAIL]            = session.email
            session.photoUrl?.let { prefs[KEY_PHOTO_URL] = it }
            prefs[KEY_IS_PREMIUM]       = session.isPremium
            prefs[KEY_PLAN_TYPE]        = session.plan
            prefs[KEY_EXPIRES_AT_MS]    = session.expiresAtMs
            prefs[KEY_SUBSCRIBED_AT_MS] = session.subscribedAtMs
            prefs[KEY_CACHED_AT_MS]     = session.cachedAtMs
        }
    }

    suspend fun clear() {
        context.userSessionDataStore.edit { it.clear() }
    }
}
