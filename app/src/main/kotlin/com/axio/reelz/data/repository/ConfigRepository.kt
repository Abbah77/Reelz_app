package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.BuildConfig
import com.axio.reelz.core.database.AppConfigCacheDao
import com.axio.reelz.core.database.AppConfigCacheRow
import com.axio.reelz.data.remote.api.ReelzApi
import com.axio.reelz.data.dto.AppConfigDto
import com.axio.reelz.core.network.NetworkResult
import com.axio.reelz.core.network.safeApiCall
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  ConfigRepository — Schema v4
//
//  ENVELOPE RULE: GET /config returns ApiResponse<AppConfigDto>.
//  Unwrap envelope.data to get AppConfigDto.
//  cache_ttl_ms is at envelope root — used to know how long to honour the cache.
// ─────────────────────────────────────────────────────────────────────────────

enum class ConfigState { LOADING, READY, ERROR }

@Singleton
class ConfigRepository @Inject constructor(
    private val api: ReelzApi,
    private val cacheDao: AppConfigCacheDao,
    private val gson: Gson,
) {
    private val tag   = "ConfigRepository"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _config = MutableStateFlow<AppConfigDto?>(null)
    val config: StateFlow<AppConfigDto?> = _config.asStateFlow()

    private val _state = MutableStateFlow(ConfigState.LOADING)
    val state: StateFlow<ConfigState> = _state.asStateFlow()

    @Volatile
    private var liveBackendUrl: String = BuildConfig.BACKEND_URL.trimEnd('/')

    fun backendUrl(): String = liveBackendUrl

    // ── Convenience accessors ─────────────────────────────────────────────────

    fun current(): AppConfigDto = _config.value ?: AppConfigDto()
    fun isMaintenanceMode(): Boolean = current().forceMaintenance
    fun isUnderMaintenance(): Boolean = isMaintenanceMode()

    fun areDownloadsEnabled(): Boolean = current().downloadsEnabled
    fun areShortsEnabled(): Boolean = current().shortsEnabled
    fun isGuestStreamingEnabled(): Boolean = current().guestStreamingEnabled
    fun searchMinChars(): Int = current().searchMinChars

    fun minAppVersion(): Int = current().minAppVersion
    fun latestAppVersion(): Int = current().latestAppVersion
    fun latestApkUrl(): String = current().latestApkUrl
    fun latestVersionCode(): Int = current().latestAppVersion
    fun latestVersionName(): String? = current().latestAppVersion.takeIf { it > 0 }?.toString()

    fun areAdsEnabled(isPremiumUser: Boolean = false): Boolean =
        current().ads.enabled && !isPremiumUser
    fun adsConfig() = current().ads

    fun isPremiumEnabled(): Boolean = current().premium.enabled
    fun premiumMonthlyPrice(): Long = current().premium.monthlyPrice
    fun paystackMonthlyUrl(): String = current().premium.paystackMonthlyUrl
    fun paystackYearlyUrl(): String = current().premium.paystackYearlyUrl

    // ── Init ──────────────────────────────────────────────────────────────────

    suspend fun init() = withContext(Dispatchers.IO) {
        val cached = cacheDao.get()
        if (cached != null) {
            applyConfig(gson.fromJson(cached.configJson, AppConfigDto::class.java))
            _state.value = ConfigState.READY
            Log.d(tag, "Config loaded from Room v${_config.value?.version}")
            if (cached.isStale()) {
                scope.launch { fetchFromBackend() }
            }
        } else {
            fetchFromBackend()
        }
    }

    suspend fun refresh() = fetchFromBackend()

    private suspend fun fetchFromBackend() = withContext(Dispatchers.IO) {
        val result = safeApiCall(tag) { api.getAppConfig() }
        when (result) {
            is NetworkResult.Success -> {
                // Unwrap the standard envelope
                val envelope = result.data
                val payload  = envelope.data
                if (!envelope.ok || payload == null) {
                    Log.e(tag, "Config envelope error: ${envelope.error}")
                    if (_config.value == null) _state.value = ConfigState.ERROR
                    return@withContext
                }
                applyConfig(payload)
                _state.value = ConfigState.READY
                cacheDao.upsert(AppConfigCacheRow(
                    configJson    = gson.toJson(payload),
                    version       = payload.version,
                    // honour the server-supplied TTL when caching in Room
                    cacheTtlMs    = envelope.cacheTtlMs ?: 3_600_000L,
                ))
                Log.d(tag, "Config fetched from backend v${payload.version}")
            }
            is NetworkResult.Error -> {
                Log.e(tag, "Config fetch failed: ${result.message}")
                if (_config.value == null) _state.value = ConfigState.ERROR
            }
            else -> {}
        }
    }

    private fun applyConfig(dto: AppConfigDto) {
        _config.value = dto
    }
}
