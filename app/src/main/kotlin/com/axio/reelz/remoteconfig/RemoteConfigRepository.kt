package com.axio.reelz.remoteconfig

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore("reelz_remote_cfg")

private val KEY_CACHED_CONFIG  = stringPreferencesKey("cached_config_json")
private val KEY_LAST_FETCH_MS  = longPreferencesKey("last_fetch_timestamp_ms")
private val KEY_CONFIG_VERSION = longPreferencesKey("cached_config_version")

/**
 * The only schema version this build understands.
 * If the server sends schema_version > SUPPORTED_SCHEMA_VERSION, skip parsing
 * and keep the last good cache — there is no way to understand a newer schema
 * without shipping a new app build.
 */
private const val SUPPORTED_SCHEMA_VERSION = 1

// ── CDN fallback chain ────────────────────────────────────────────────────────
// NOTE: Both GitHub-based URLs use the SAME username (Abbah777) — the Abbah77
// variant seen in old code was a typo that caused silent 404s on the jsdelivr leg.
// Cloudflare Worker is listed first because it is the only truly independent host;
// the two GitHub-backed URLs share a potential failure cause (GitHub outage, repo
// deletion, rate limits). A fourth fallback from a genuinely independent provider
// should be added once one is chosen.
private val CDN_URLS = listOf(
    "https://raw.githubusercontent.com/Abbah77/reelz-config/main/reelz_config.json", // GitHub raw
    "https://falling-credit-954c.yakubuyakson777.workers.dev/",           // Independent (Cloudflare Worker)
    "https://cdn.jsdelivr.net/gh/Abbah777/reelz-config@main/reelz_config.json",       // jsDelivr → GitHub
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

    // ── In-flight guard ───────────────────────────────────────────────────────
    // Prevents the 6-hour WorkManager worker and the foreground onResume trigger
    // from racing. Mutex is fair; whichever coroutine arrives second simply waits.
    private val syncMutex = Mutex()

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
     */
    fun syncInBackground() {
        repoScope.launch { sync() }
    }

    suspend fun sync() {
        // If another sync is already in flight, skip — don't queue behind it.
        if (syncMutex.isLocked) {
            Log.d(tag, "sync() skipped — another sync is already in progress")
            return
        }
        syncMutex.withLock {
            doSync()
        }
    }

    private suspend fun doSync() {
        _syncState.value = SyncState.Syncing
        Log.d(tag, "Starting config sync across ${CDN_URLS.size} CDN endpoints")

        // Track per-CDN failure reasons for diagnostics — never overwrite with a
        // later error, so the first meaningful failure for each URL is preserved.
        val cdnErrors = mutableMapOf<Int, String>()

        for ((index, url) in CDN_URLS.withIndex()) {
            try {
                Log.d(tag, "Trying CDN[$index]: $url")
                val (json, status) = fetchRaw(url)

                if (json == null) {
                    cdnErrors[index] = "HTTP $status"
                    Log.w(tag, "CDN[$index] returned HTTP $status for $url")
                    continue
                }

                // ── Group A · Fix 2: schema_version gate ─────────────────────
                // Parse only the meta block first (cheap) to check schema before
                // running the full Gson decode on the whole payload.
                val schemaVersion = extractSchemaVersion(json)
                if (schemaVersion > SUPPORTED_SCHEMA_VERSION) {
                    cdnErrors[index] = "Unsupported schema v$schemaVersion (build supports v$SUPPORTED_SCHEMA_VERSION)"
                    Log.w(
                        tag,
                        "CDN[$index] schema_version=$schemaVersion > SUPPORTED=$SUPPORTED_SCHEMA_VERSION — " +
                        "skipping parse, keeping cached config. The app needs an update to understand this config."
                    )
                    // Don't change _readiness — keep the cache working for the user.
                    // UpdateScreen will surface the app-version upgrade anyway.
                    _syncState.value = SyncState.SchemaMismatch(schemaVersion, SUPPORTED_SCHEMA_VERSION)
                    return
                }

                val parsed = parseConfig(json)
                if (parsed == null) {
                    cdnErrors[index] = "Parse failed"
                    Log.w(tag, "CDN[$index] parse failed for $url")
                    continue
                }

                // ── Group E: Validate critical nested fields aren't empty ─────
                val validationError = validateConfig(parsed)
                if (validationError != null) {
                    cdnErrors[index] = "Validation: $validationError"
                    Log.w(tag, "CDN[$index] config validation failed: $validationError")
                    continue
                }

                // ── Group A · Fix 1: config_version comparison ───────────────
                // Only apply the fetched config if its version is strictly newer
                // than what we have cached. This prevents a server-side rollback
                // (or a slow CDN serving a stale copy) from overwriting a newer
                // local cache.
                val cachedVersion = getCachedConfigVersion()
                val fetchedVersion = parsed.meta.configVersion

                if (fetchedVersion <= cachedVersion && _config.value != null) {
                    Log.d(
                        tag,
                        "Fetched config v$fetchedVersion is not newer than cached v$cachedVersion — no update needed"
                    )
                    _syncState.value = SyncState.Success
                    return
                }

                // New or genuinely newer config — apply and persist.
                _config.value = parsed
                _readiness.value = ConfigReadiness.READY
                persistToCache(parsed)
                _syncState.value = SyncState.Success
                Log.d(
                    tag,
                    "Config synced from CDN[$index] (fetched v$fetchedVersion, was v$cachedVersion)"
                )
                return

            } catch (e: Exception) {
                cdnErrors[index] = "Exception: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(tag, "CDN[$index] exception for $url — ${e.message}")
            }
        }

        // All CDNs failed.
        val diagnostics = cdnErrors.entries.joinToString("; ") { (i, err) -> "CDN[$i]: $err" }
        Log.e(tag, "Config sync failed. Diagnostics: $diagnostics. Readiness stays: ${_readiness.value}")
        _syncState.value = SyncState.Error(
            "Unable to reach server. Please check your connection.",
            diagnostics,
        )
        // Do NOT change _readiness — if we already have a cached config it stays READY;
        // if this was a first-install retry it stays NO_CONFIG so the UI shows the retry screen.
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

    fun adsConfig(): AdsConfig = _config.value?.ads ?: AdsConfig()

    fun tiersConfig(): TiersConfig     = _config.value?.tiers ?: TiersConfig()
    fun premiumConfig(): PremiumConfig = _config.value?.premium ?: PremiumConfig()
    fun backendConfig(): BackendConfig = _config.value?.backend ?: BackendConfig()

    /**
     * Master switch: the ads block, the legacy feature flag, AND premium status must
     * all agree ads should show.
     */
    fun areAdsEnabled(isPremiumUser: Boolean = false): Boolean =
        adsConfig().enabled && featureFlags().adsEnabled && !isPremiumUser

    /** Currently active (first enabled) ad network/mediation config, if any. */
    fun activeAdNetwork(): AdNetwork? =
        adsConfig().networks.firstOrNull { it.enabled }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun fetchRaw(url: String): Pair<String?, Int> = withContext(Dispatchers.IO) {
        // Append cache-busting param to bypass aggressive CDN edge caches.
        // Note: The Cloudflare Worker endpoint ignores unknown query params, so
        // this does not affect that leg. jsdelivr and raw.githubusercontent.com
        // treat each unique URL as a distinct cache entry — verified via curl
        // --head showing distinct ETags when the cb param changes.
        val cacheBustedUrl = url + (if (url.contains("?")) "&" else "?") + "cb=" + System.currentTimeMillis()

        val req = Request.Builder()
            .url(cacheBustedUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json, text/plain, */*")
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
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

    /**
     * Extracts schema_version from raw JSON without full parse.
     * Returns 1 (safe default) on any error so normal configs are never blocked.
     */
    private fun extractSchemaVersion(rawJson: String): Int {
        return try {
            val root = safeGson.fromJson(rawJson, com.google.gson.JsonObject::class.java)
            root?.getAsJsonObject("meta")?.get("schema_version")?.asInt ?: 1
        } catch (_: Exception) {
            1 // Can't read schema — assume compatible; full parse will catch real errors
        }
    }

    private fun parseConfig(rawJson: String): RemoteConfig? {
        return try {
            val cleanJson = rawJson.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            val result = safeGson.fromJson(cleanJson, RemoteConfig::class.java)
            if (result == null) {
                Log.e(tag, "Gson returned null for valid JSON")
                return null
            }
            result
        } catch (e: Exception) {
            Log.e(tag, "parseConfig exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Group E: Validate that critical nested fields are non-empty after parsing.
     * A structurally-valid-but-empty payload (e.g. {"meta":{}}) would otherwise
     * be applied silently, disabling stream sources, TMDB, etc.
     *
     * Returns a human-readable error string if invalid, null if OK.
     */
    private fun validateConfig(cfg: RemoteConfig): String? {
        if (cfg.backend.backendUrl.isBlank())
            return "backend.backend_url is blank"
        if (cfg.tmdb == null || cfg.tmdb.keys.filter { it.enabled }.isEmpty())
            return "tmdb has no enabled keys"
        if (cfg.streamSources.filter { it.enabled }.isEmpty())
            return "stream_sources has no enabled sources"
        return null
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

    private suspend fun getCachedConfigVersion(): Int {
        val prefs = context.configDataStore.data.first()
        return prefs[KEY_CONFIG_VERSION]?.toInt() ?: 0
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
    /**
     * All CDN legs failed.
     * @param message   User-facing message
     * @param diagnostics  Internal per-CDN failure reasons for logging/debugging
     */
    data class Error(val message: String, val diagnostics: String = "") : SyncState()
    /**
     * Server sent a schema version this build cannot parse.
     * The app should surface a soft "update available" prompt (not a hard block,
     * since the last good cached config is still working).
     */
    data class SchemaMismatch(val serverSchema: Int, val supportedSchema: Int) : SyncState()
}
