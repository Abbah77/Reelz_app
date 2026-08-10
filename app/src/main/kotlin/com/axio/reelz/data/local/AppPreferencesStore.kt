package com.axio.reelz.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appPrefsDataStore: DataStore<Preferences>
    by preferencesDataStore("reelz_app_prefs")

// ── Keys ──────────────────────────────────────────────────────────────────────
private val KEY_PIP_ENABLED           = booleanPreferencesKey("pip_globally_enabled")
private val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
private val KEY_LAST_INTERSTITIAL_MS  = longPreferencesKey("last_interstitial_time_ms")
private val KEY_TOTAL_CONTENT_OPENS   = intPreferencesKey("total_content_opens")
private val KEY_TOTAL_PLAY_TAPS       = intPreferencesKey("total_play_taps")

/**
 * AppPreferencesStore — single DataStore for all scalar app preferences.
 *
 * Consolidates what was previously split across:
 *   • PipPreferenceStore (deleted — was reelz_pip_prefs) → KEY_PIP_ENABLED
 *   • SettingsScreen remember{} (lost on nav)            → KEY_NOTIFICATIONS_ENABLED
 *   • AdEngine in-memory vars (reset on start)           → ad counters
 *
 * Single DataStore file ("reelz_app_prefs") for all scalar toggles and counters.
 * Anything relational goes to Room.
 *
 * WHAT BELONGS HERE: boolean toggles, simple counters, and timestamps that
 * are scalar and don't need SQL queries. Anything relational goes to Room.
 */
@Singleton
class AppPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // ── PiP toggle ────────────────────────────────────────────────────────────

    /** Reactive stream of the PiP enabled state. Default: true (on by default). */
    val isPipEnabled: Flow<Boolean> =
        context.appPrefsDataStore.data.map { it[KEY_PIP_ENABLED] ?: true }

    suspend fun setPipEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[KEY_PIP_ENABLED] = enabled }
    }

    // ── Notifications toggle ──────────────────────────────────────────────────

    /** Reactive stream of the notifications enabled state. Default: true. */
    val isNotificationsEnabled: Flow<Boolean> =
        context.appPrefsDataStore.data.map { it[KEY_NOTIFICATIONS_ENABLED] ?: true }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[KEY_NOTIFICATIONS_ENABLED] = enabled }
    }

    /** One-shot read for ViewModels that need the current value synchronously. */
    suspend fun isNotificationsEnabledOnce(): Boolean =
        context.appPrefsDataStore.data.first()[KEY_NOTIFICATIONS_ENABLED] ?: true

    // ── Ad engine counters ────────────────────────────────────────────────────
    // These survive cold starts so frequency caps are honoured across app restarts.
    // Only lastInterstitialTimeMs and totalContentOpens need persistence;
    // interstitialShownCount resets per session intentionally.

    suspend fun getLastInterstitialTimeMs(): Long =
        context.appPrefsDataStore.data.first()[KEY_LAST_INTERSTITIAL_MS] ?: 0L

    suspend fun setLastInterstitialTimeMs(ms: Long) {
        context.appPrefsDataStore.edit { it[KEY_LAST_INTERSTITIAL_MS] = ms }
    }

    suspend fun getTotalContentOpens(): Int =
        context.appPrefsDataStore.data.first()[KEY_TOTAL_CONTENT_OPENS] ?: 0

    suspend fun incrementContentOpens() {
        context.appPrefsDataStore.edit {
            it[KEY_TOTAL_CONTENT_OPENS] = (it[KEY_TOTAL_CONTENT_OPENS] ?: 0) + 1
        }
    }

    suspend fun getTotalPlayTaps(): Int =
        context.appPrefsDataStore.data.first()[KEY_TOTAL_PLAY_TAPS] ?: 0

    suspend fun incrementPlayTaps() {
        context.appPrefsDataStore.edit {
            it[KEY_TOTAL_PLAY_TAPS] = (it[KEY_TOTAL_PLAY_TAPS] ?: 0) + 1
        }
    }
}
