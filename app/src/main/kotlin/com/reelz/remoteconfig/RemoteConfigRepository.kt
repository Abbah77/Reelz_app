package com.reelz.remoteconfig

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore("reelz_remote_cfg")

private val KEY_CACHED_CONFIG    = stringPreferencesKey("cached_config_json")
private val KEY_LAST_FETCH_MS    = longPreferencesKey("last_fetch_timestamp_ms")
private val KEY_CONFIG_VERSION   = longPreferencesKey("cached_config_version")

/**
 * Remote config CDN endpoints (ordered by priority).
 *
 * The app tries each URL in order. The first successful decrypt wins.
 * Add more GitHub raw / Cloudflare / jsDelivr mirrors here as you deploy them.
 *
 * URL format: must return `{ "v": 1, "d": "<base64-encrypted-blob>" }`
 */
private val CDN_URLS = listOf(
    // ── Primary: your GitHub Pages / Cloudflare Pages deploy ─────────────────
    "https://cdn.jsdelivr.net/gh/yourusername/reelz-config@main/reelz_config.json",

    // ── Mirror 1: Cloudflare R2 / Workers KV ─────────────────────────────────
    "https://config.yourdomain.workers.dev/reelz_config.json",

    // ── Mirror 2: Raw GitHub (rate-limited, last resort) ─────────────────────
    "https://raw.githubusercontent.com/yourusername/reelz-config/main/reelz_config.json",

    // ── Mirror 3: Cloudflare Pages ────────────────────────────────────────────
    "https://reelz-config.pages.dev/reelz_config.json",
)

/** How often we re-fetch (6 hours by default). */
private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L

@Singleton
class RemoteConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
) {
    private val tag = "RemoteConfig"

    // ── Observable state ──────────────────────────────────────────────────────
    private val _config = MutableStateFlow<RemoteConfig?>(null)
    val config: StateFlow<RemoteConfig?> = _config.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called from ReelzApp.onCreate().
     * Loads cached config instantly, then triggers a background refresh if stale.
     */
    suspend fun init() {
        loadFromCache()
        if (isCacheStale()) {
            sync()
        }
    }

    /**
     * Force a background sync regardless of cache freshness.
     * Tries each CDN in order; first success wins.
     */
    suspend fun sync() {
        _syncState.value = SyncState.Syncing
        Log.d(tag, "Starting config sync across ${CDN_URLS.size} CDN endpoints")

        var lastError: String? = null
        for ((index, url) in CDN_URLS.withIndex()) {
            try {
                Log.d(tag, "Trying CDN[$index]: $url")
                val json = fetchRaw(url) ?: continue
                val parsed = parseAndDecrypt(json) ?: continue

                // Success
                _config.value = parsed
                persistToCache(parsed)
                _syncState.value = SyncState.Success
                Log.d(tag, "Config synced from CDN[$index] (version=${parsed.meta.configVersion})")
                return
            } catch (e: Exception) {
                lastError = e.message
                Log.w(tag, "CDN[$index] failed: $lastError")
            }
        }

        // All CDNs failed — stay on cached config
        _syncState.value = SyncState.Error(lastError ?: "All CDN endpoints unreachable")
        Log.e(tag, "Config sync failed across all CDNs. Using cached config.")
    }

    // ── Convenience accessors (non-null, safe) ────────────────────────────────

    fun activeTmdbKey(): String =
        _config.value?.tmdb?.keys
            ?.filter { it.enabled }
            ?.maxByOrNull { it.weight }
            ?.key
            ?: "1eef1496d59aa06f62e201ddce2741b4" // compile-time fallback

    fun activeOsKey(): String =
        _config.value?.subtitles?.providers
            ?.firstOrNull { it.id == "opensubtitles" && it.enabled }
            ?.keys?.filter { it.enabled }?.maxByOrNull { it.weight }?.key
            ?: "C8jjWBqYDBiM4U3QA9xJfmf8BiC2ISyq"

    fun activeStreamSources(): List<StreamSourceConfig> =
        _config.value?.streamSources
            ?.filter { it.enabled }
            ?.sortedBy { it.priority }
            ?: emptyList()

    fun featureFlags(): FeatureFlags =
        _config.value?.featureFlags ?: FeatureFlags()

    fun meta(): MetaConfig =
        _config.value?.meta ?: MetaConfig()

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun fetchRaw(url: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.string()
        }
    }

    private fun parseAndDecrypt(rawJson: String): RemoteConfig? = runCatching {
        val envelope = gson.fromJson(rawJson, EncryptedConfigEnvelope::class.java)
        val plainJson = ConfigCrypto.decrypt(envelope.data) ?: return null
        gson.fromJson(plainJson, RemoteConfig::class.java)
    }.getOrElse {
        Log.e(tag, "Parse/decrypt error: ${it.message}")
        null
    }

    private suspend fun loadFromCache() {
        val prefs = context.configDataStore.data.first()
        val json  = prefs[KEY_CACHED_CONFIG] ?: return
        runCatching {
            _config.value = gson.fromJson(json, RemoteConfig::class.java)
            Log.d(tag, "Loaded config from cache (version=${_config.value?.meta?.configVersion})")
        }
    }

    private suspend fun persistToCache(cfg: RemoteConfig) {
        val json = gson.toJson(cfg)
        context.configDataStore.edit { prefs ->
            prefs[KEY_CACHED_CONFIG]  = json
            prefs[KEY_LAST_FETCH_MS]  = System.currentTimeMillis()
            prefs[KEY_CONFIG_VERSION] = cfg.meta.configVersion.toLong()
        }
    }

    private suspend fun isCacheStale(): Boolean {
        val prefs       = context.configDataStore.data.first()
        val lastFetch   = prefs[KEY_LAST_FETCH_MS] ?: 0L
        return System.currentTimeMillis() - lastFetch > REFRESH_INTERVAL_MS
    }
}

sealed class SyncState {
    object Idle     : SyncState()
    object Syncing  : SyncState()
    object Success  : SyncState()
    data class Error(val message: String) : SyncState()
}
