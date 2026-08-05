package com.axio.reelz.remoteconfig

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.axio.reelz.data.local.RemoteConfigCache
import com.axio.reelz.data.local.RemoteConfigCacheDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only schema version this build understands.
 * If the server sends schema_version > SUPPORTED_SCHEMA_VERSION, skip parsing
 * and keep the last good cache — there is no way to understand a newer schema
 * without shipping a new app build.
 */
private const val SUPPORTED_SCHEMA_VERSION = 1

private val CDN_URLS = listOf(
    "https://raw.githubusercontent.com/Abbah77/reelz-config/main/reelz_config.json",
    "https://cdn.jsdelivr.net/gh/Abbah777/reelz-config@main/reelz_config.json",
    "https://falling-credit-954c.yakubuyakson777.workers.dev/",
)

/** Three-state readiness so the UI never races against DB load. */
enum class ConfigReadiness {
    LOADING,   // Room read not finished yet
    NO_CONFIG, // Room read done, no cache found — first install, needs internet
    READY,     // Config is in memory and ready
}

/** Result of a single CDN attempt — used to avoid continue inside inline lambdas. */
private sealed class CdnResult {
    /** Fetched, parsed, validated — ready to commit. */
    data class Ok(val json: String, val parsed: RemoteConfig) : CdnResult()
    /** This CDN failed; try the next one. */
    data class Skip(val reason: String) : CdnResult()
    /** Schema too new for this build — stop trying all CDNs immediately. */
    data class SchemaMismatch(val server: Int, val supported: Int) : CdnResult()
}

@Singleton
class RemoteConfigRepository @Inject constructor(
    private val cacheDao: RemoteConfigCacheDao,  // Room — single source of truth
    private val gson: Gson,
) {
    private val tag = "RemoteConfig"
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    private val _config     = MutableStateFlow<RemoteConfig?>(null)
    val config: StateFlow<RemoteConfig?> = _config.asStateFlow()

    private val _readiness  = MutableStateFlow(ConfigReadiness.LOADING)
    val readiness: StateFlow<ConfigReadiness> = _readiness.asStateFlow()

    private val _syncState  = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called once from ReelzApp.onCreate().
     * Reads Room only — never touches the network.
     */
    suspend fun loadLocalConfig() {
        val found = loadFromCache()
        _readiness.value = if (found) ConfigReadiness.READY else ConfigReadiness.NO_CONFIG
    }

    fun syncInBackground() {
        repoScope.launch { sync() }
    }

    suspend fun sync() {
        if (syncMutex.isLocked) {
            Log.d(tag, "sync() skipped — another sync is already in progress")
            return
        }
        syncMutex.withLock { doSync() }
    }

    private suspend fun doSync() {
        _syncState.value = SyncState.Syncing
        Log.d(tag, "Starting config sync across ${CDN_URLS.size} CDN endpoints")

        val cdnErrors = mutableMapOf<Int, String>()

        for ((index, url) in CDN_URLS.withIndex()) {
            val result = tryCdn(index, url)

            when (result) {
                is CdnResult.Skip -> {
                    cdnErrors[index] = result.reason
                    // Try next CDN
                }
                is CdnResult.SchemaMismatch -> {
                    cdnErrors[index] = "Unsupported schema v${result.server}"
                    _syncState.value = SyncState.SchemaMismatch(result.server, result.supported)
                    return
                }
                is CdnResult.Ok -> {
                    // Version guard — don't overwrite a newer cache with a stale CDN copy
                    val cachedVersion = cacheDao.get()?.configVersion ?: 0
                    val fetchedVersion = result.parsed.meta.configVersion

                    if (fetchedVersion <= cachedVersion && _config.value != null) {
                        Log.d(tag, "Fetched v$fetchedVersion not newer than cached v$cachedVersion — no update")
                        _syncState.value = SyncState.Success
                        return
                    }

                    _config.value    = result.parsed
                    _readiness.value = ConfigReadiness.READY
                    persistToCache(result.json, result.parsed.meta.configVersion)
                    _syncState.value = SyncState.Success
                    Log.d(tag, "Config synced from CDN[$index] (v$fetchedVersion, was v$cachedVersion)")
                    return
                }
            }
        }

        val diagnostics = cdnErrors.entries.joinToString("; ") { (i, err) -> "CDN[$i]: $err" }
        Log.e(tag, "Config sync failed. $diagnostics")
        _syncState.value = SyncState.Error("Unable to reach server. Please check your connection.", diagnostics)
    }

    /**
     * Attempt to fetch and validate config from a single CDN URL.
     * Returns [CdnResult.Ok] on success, [CdnResult.Skip] on recoverable failure,
     * [CdnResult.SchemaMismatch] when the server schema is too new for this build.
     *
     * Extracted from the doSync loop to avoid using `continue` inside an inline
     * lambda — `continue` in inline lambdas is experimental in Kotlin 2.x.
     */
    private suspend fun tryCdn(index: Int, url: String): CdnResult {
        return try {
            Log.d(tag, "Trying CDN[$index]: $url")
            val (json, status) = fetchRaw(url)

            if (json == null) {
                return CdnResult.Skip("HTTP $status")
            }

            val schemaVersion = extractSchemaVersion(json)
            if (schemaVersion > SUPPORTED_SCHEMA_VERSION) {
                return CdnResult.SchemaMismatch(schemaVersion, SUPPORTED_SCHEMA_VERSION)
            }

            val parsed = parseConfig(json)
                ?: return CdnResult.Skip("Parse failed")

            val validationError = validateConfig(parsed)
            if (validationError != null) {
                return CdnResult.Skip("Validation: $validationError")
            }

            CdnResult.Ok(json, parsed)

        } catch (e: Exception) {
            Log.w(tag, "CDN[$index] exception for $url — ${e.message}")
            CdnResult.Skip("Exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Convenience accessors ─────────────────────────────────────────────────

    fun activeTmdbKey(): String? =
        _config.value?.tmdb?.keys?.filter { it.enabled }?.maxByOrNull { it.weight }?.key

    fun current(): RemoteConfig        = _config.value ?: RemoteConfig()
    fun featureFlags(): FeatureFlags   = _config.value?.featureFlags ?: FeatureFlags()
    fun meta(): MetaConfig             = _config.value?.meta ?: MetaConfig()
    fun shortsConfig(): ShortsConfig   = _config.value?.shorts ?: ShortsConfig()
    fun adsConfig(): AdsConfig         = _config.value?.ads ?: AdsConfig()
    fun tiersConfig(): TiersConfig     = _config.value?.tiers ?: TiersConfig()
    fun premiumConfig(): PremiumConfig = _config.value?.premium ?: PremiumConfig()
    fun backendConfig(): BackendConfig = _config.value?.backend ?: BackendConfig()

    fun areAdsEnabled(isPremiumUser: Boolean = false): Boolean =
        adsConfig().enabled && featureFlags().adsEnabled && !isPremiumUser

    fun activeAdNetwork(): AdNetwork? = adsConfig().networks.firstOrNull { it.enabled }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun fetchRaw(url: String): Pair<String?, Int> = withContext(Dispatchers.IO) {
        val cacheBustedUrl = url + (if (url.contains("?")) "&" else "?") + "cb=" + System.currentTimeMillis()
        val req = Request.Builder()
            .url(cacheBustedUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json, text/plain, */*")
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext Pair(null, resp.code)
            val body = resp.body?.string()
            val trimmed = body?.trimStart() ?: ""
            if (trimmed.startsWith("<!") || trimmed.startsWith("<html", ignoreCase = true)) {
                Log.w(tag, "fetchRaw $url returned HTML not JSON — skipping")
                return@withContext Pair(null, resp.code)
            }
            Pair(body, resp.code)
        }
    }

    private val safeGson = GsonBuilder().serializeNulls().setLenient().create()

    private fun extractSchemaVersion(rawJson: String): Int {
        return try {
            val root = safeGson.fromJson(rawJson, com.google.gson.JsonObject::class.java)
            root?.getAsJsonObject("meta")?.get("schema_version")?.asInt ?: 1
        } catch (_: Exception) { 1 }
    }

    private fun parseConfig(rawJson: String): RemoteConfig? {
        return try {
            val cleanJson = rawJson.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            safeGson.fromJson(cleanJson, RemoteConfig::class.java)
        } catch (e: Exception) {
            Log.e(tag, "parseConfig exception: ${e.message}"); null
        }
    }

    private fun validateConfig(cfg: RemoteConfig): String? {
        if (cfg.backend.backendUrl.isBlank() && cfg.backend.streamUrl.isBlank())
            return "backend has no urls configured"
        if (cfg.tmdb == null || cfg.tmdb.keys.filter { it.enabled }.isEmpty())
            return "tmdb has no enabled keys"
        return null
    }

    private suspend fun loadFromCache(): Boolean {
        val cached = cacheDao.get() ?: return false
        return try {
            _config.value = safeGson.fromJson(cached.configJson, RemoteConfig::class.java)
            Log.d(tag, "Loaded config from Room (version=${_config.value?.meta?.configVersion})")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse cached config: ${e.message}")
            false
        }
    }

    private suspend fun persistToCache(json: String, version: Int) {
        cacheDao.upsert(
            RemoteConfigCache(
                id            = 1,
                configJson    = json,
                fetchedAtMs   = System.currentTimeMillis(),
                configVersion = version,
            )
        )
    }
}

sealed class SyncState {
    object Idle    : SyncState()
    object Syncing : SyncState()
    object Success : SyncState()
    data class Error(val message: String, val diagnostics: String = "") : SyncState()
    data class SchemaMismatch(val serverSchema: Int, val supportedSchema: Int) : SyncState()
}
