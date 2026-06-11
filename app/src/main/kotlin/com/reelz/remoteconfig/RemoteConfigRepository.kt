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
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore("reelz_remote_cfg")

private val KEY_CACHED_CONFIG  = stringPreferencesKey("cached_config_json")
private val KEY_LAST_FETCH_MS  = longPreferencesKey("last_fetch_timestamp_ms")
private val KEY_CONFIG_VERSION = longPreferencesKey("cached_config_version")

private val CDN_URLS = listOf(
    "https://raw.githubusercontent.com/Abbah77/reelz-config/main/reelz_config.json",
    "https://falling-credit-954c.yakubuyakson77.workers.dev/",
    "https://cdn.jsdelivr.net/gh/Abbah77/reelz-config@main/reelz_config.json",
)

/** Three-state readiness so the UI never races against DataStore. */
enum class ConfigReadiness {
    /** DataStore read not finished yet — show nothing, not even the offline screen. */
    LOADING,
    /** DataStore read done, no cache found — first install, needs internet. */
    NO_CONFIG,
    /** Config is in memory and ready — proceed normally. */
    READY,
}

@Singleton
class RemoteConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
) {
    private val tag = "RemoteConfig"
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _config = MutableStateFlow<RemoteConfig?>(null)
    val config: StateFlow<RemoteConfig?> = _config.asStateFlow()

    /**
     * Drives the splash/gate logic in MainActivity.
     * Starts as LOADING so the UI shows nothing while DataStore is being read.
     * Moves to NO_CONFIG or READY once the read completes.
     * Can move back to READY at any time when sync() succeeds.
     */
    private val _readiness = MutableStateFlow(ConfigReadiness.LOADING)
    val readiness: StateFlow<ConfigReadiness> = _readiness.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called once from ReelzApp.onCreate().
     * Reads local DataStore only — never touches the network.
     * Sets readiness to READY or NO_CONFIG when done.
     */
    suspend fun loadLocalConfig() {
        val found = loadFromCache()
        _readiness.value = if (found) ConfigReadiness.READY else ConfigReadiness.NO_CONFIG
    }

    /**
     * Fetches config from the CDN and saves it locally.
     * Called by [ConfigSyncWorker] for background refreshes, AND directly
     * by the UI when the user taps "Try again" on the offline screen.
     * Updates [_config] and [_readiness] on the calling coroutine — no WorkManager needed
     * for the first-install path.
     */
    fun syncInBackground() {
        repoScope.launch { sync() }
    }

    suspend fun sync() {
        _syncState.value = SyncState.Syncing
        Log.d(tag, "Starting config sync across ${CDN_URLS.size} CDN endpoints")

        var lastError: String? = null
        for ((index, url) in CDN_URLS.withIndex()) {
            try {
                Log.d(tag, "Trying CDN[$index]: $url")
                val (json, status) = fetchRaw(url)
                if (json != null) {
                    val parsed = parseConfig(json)
                    if (parsed != null) {
                        _config.value = parsed
                        _readiness.value = ConfigReadiness.READY
                        persistToCache(parsed)
                        _syncState.value = SyncState.Success
                        Log.d(tag, "Config synced from CDN[$index] (version=${parsed.meta.configVersion})")
                        return
                    } else {
                        lastError = "Unable to load configuration. Please check your connection."
                        Log.w(tag, "CDN[$index] parse failed for $url")
                    }
                } else {
                    lastError = "Unable to reach server. Please check your connection."
                    Log.w(tag, "CDN[$index] failed: HTTP $status for $url")
                }
            } catch (e: Exception) {
                lastError = "Connection error. Please check your internet and try again."
                Log.w(tag, "CDN[$index] exception: ${e.message}")
            }
        }

        _syncState.value = SyncState.Error(lastError ?: "No internet connection. Please try again.")
        Log.e(tag, "Config sync failed. Readiness stays: ${_readiness.value}")
        // Do NOT change _readiness here — if we already have a cached config it stays READY;
        // if this was a first-install retry it stays NO_CONFIG so the UI keeps showing the screen.
    }

    // ── Convenience accessors ─────────────────────────────────────────────────

    fun activeTmdbKey(): String? =
        _config.value?.tmdb?.keys
            ?.filter { it.enabled }
            ?.maxByOrNull { it.weight }
            ?.key

    fun activeOsKey(): String? =
        _config.value?.subtitles?.providers
            ?.firstOrNull { it.id == "opensubtitles" && it.enabled }
            ?.keys?.filter { it.enabled }?.maxByOrNull { it.weight }?.key

    fun activeStreamSources(): List<StreamSourceConfig> =
        _config.value?.streamSources
            ?.filter { it.enabled }
            ?.sortedBy { it.priority }
            ?: emptyList()

    fun featureFlags(): FeatureFlags  = _config.value?.featureFlags ?: FeatureFlags()
    fun meta(): MetaConfig            = _config.value?.meta ?: MetaConfig()
    fun shortsConfig(): ShortsConfig  = _config.value?.shorts ?: ShortsConfig()

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun fetchRaw(url: String): Pair<String?, Int> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json, text/plain, */*")
            .build()
        http.newCall(req).execute().use { resp ->
            Log.d(tag, "fetchRaw $url -> HTTP ${resp.code}")
            if (!resp.isSuccessful) {
                return@withContext Pair(null, resp.code)
            }
            val body = resp.body?.string()
            Log.d(tag, "fetchRaw got ${body?.length ?: 0} chars from $url")

            // Reject HTML responses (Cloudflare error pages, GitHub 404 HTML, etc.)
            val trimmed = body?.trimStart() ?: ""
            if (trimmed.startsWith("<!") || trimmed.startsWith("<html", ignoreCase = true)) {
                Log.w(tag, "fetchRaw $url returned HTML not JSON — skipping")
                return@withContext Pair(null, resp.code)
            }

            Pair(body, resp.code)
        }
    }

    private val safeGson = GsonBuilder().serializeNulls().setLenient().create()

    private var _lastParseError: String? = null

    private fun parseConfig(rawJson: String): RemoteConfig? {
        return try {
            val cleanJson = rawJson.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            val result = safeGson.fromJson(cleanJson, RemoteConfig::class.java)
            if (result == null) {
                _lastParseError = "Gson returned null for valid JSON"
                return null
            }
            _lastParseError = null
            result
        } catch (e: Exception) {
            Log.e(tag, "parseConfig exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun loadFromCache(): Boolean {
        val prefs = context.configDataStore.data.first()
        val json  = prefs[KEY_CACHED_CONFIG] ?: return false
        return try {
            _config.value = safeGson.fromJson(json, RemoteConfig::class.java)
            Log.d(tag, "Loaded config from local cache (version=${_config.value?.meta?.configVersion})")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to load from cache: ${e.message}")
            false
        }
    }

    private suspend fun persistToCache(cfg: RemoteConfig) {
        val json = safeGson.toJson(cfg)
        context.configDataStore.edit { prefs ->
            prefs[KEY_CACHED_CONFIG]  = json
            prefs[KEY_LAST_FETCH_MS]  = System.currentTimeMillis()
            prefs[KEY_CONFIG_VERSION] = cfg.meta.configVersion.toLong()
        }
    }
}

sealed class SyncState {
    object Idle    : SyncState()
    object Syncing : SyncState()
    object Success : SyncState()
    data class Error(val message: String) : SyncState()
}
