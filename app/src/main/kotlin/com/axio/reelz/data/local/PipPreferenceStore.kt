package com.axio.reelz.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pipDataStore: DataStore<Preferences> by preferencesDataStore("reelz_pip_prefs")

private val KEY_PIP_ENABLED = booleanPreferencesKey("pip_globally_enabled")

/**
 * Persists the global PiP toggle (Auto Miniplayer) across sessions via DataStore.
 * Default = true (PiP on by default, consistent with YouTube/Netflix UX).
 */
@Singleton
class PipPreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Observe the current PiP enabled state reactively. */
    val isPipEnabled: Flow<Boolean> =
        context.pipDataStore.data.map { prefs -> prefs[KEY_PIP_ENABLED] ?: true }

    /** Persist the user's choice. */
    suspend fun setPipEnabled(enabled: Boolean) {
        context.pipDataStore.edit { prefs -> prefs[KEY_PIP_ENABLED] = enabled }
    }
}
