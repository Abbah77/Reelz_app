package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.BuildConfig
import com.axio.reelz.core.database.AppConfigCacheDao
import com.axio.reelz.core.database.AppConfigCacheRow
import com.axio.reelz.data.remote.api.ReelzApi
import com.axio.reelz.data.dto.AppConfigDto
import com.axio.reelz.data.dto.PremiumConfig
import com.axio.reelz.data.dto.TierConfig
import com.axio.reelz.data.dto.TiersConfig
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

enum class ConfigState { LOADING, READY, ERROR }

@Singleton
class ConfigRepository @Inject constructor(
    private val api: ReelzApi,
    private val cacheDao: AppConfigCacheDao,
    private val gson: Gson,
) {
    private val tag = "ConfigRepository"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _config = MutableStateFlow<AppConfigDto?>(null)
    val config: StateFlow<AppConfigDto?> = _config.asStateFlow()

    private val _state = MutableStateFlow(ConfigState.LOADING)
    val state: StateFlow<ConfigState> = _state.asStateFlow()

    // ── The live backend URL — bootstrapped from BuildConfig, updatable via config ──
    // On first install: uses BuildConfig.BACKEND_URL.
    // After first config fetch: may be overridden by the backend itself.
    @Volatile
    private var liveBackendUrl: String = BuildConfig.BACKEND_URL.trimEnd('/')

    fun backendUrl(): String = liveBackendUrl

    // ── Convenience accessors ─────────────────────────────────────────────────

    fun current(): AppConfigDto = _config.value ?: AppConfigDto()
    fun isMaintenanceMode(): Boolean = current().forceMaintenance

    fun tiersConfig(): TiersConfig = TiersConfig()  // extend when backend provides tier data

    fun premiumConfig(): PremiumConfig {
        val p = current().premium
        return PremiumConfig(
            monthlyPriceNgn   = p.monthlyPrice,
            yearlyPriceNgn    = p.monthlyPrice * 10,
            paystackMonthlyUrl = p.paystackMonthlyUrl,
            paystackYearlyUrl  = p.paystackYearlyUrl,
            paymentNote        = "",
        )
    }

    fun tiersConfig(): TiersConfig = TiersConfig()  // extend when backend provides tier data

    fun premiumConfig(): PremiumConfig {
        val p = current().premium
        return PremiumConfig(
            monthlyPriceNgn   = p.monthlyPrice,
            yearlyPriceNgn    = p.monthlyPrice * 10,
            paystackMonthlyUrl = p.paystackMonthlyUrl,
            paystackYearlyUrl  = p.paystackYearlyUrl,
            paymentNote        = "",
        )
    }
    fun areAdsEnabled(isPremiumUser: Boolean = false): Boolean =
        current().ads.enabled && !isPremiumUser
    fun adsConfig() = current().ads
    fun activeAdNetwork() = current().ads.network
    fun areDownloadsEnabled(): Boolean = current().downloadsEnabled
    fun areShortsEnabled(): Boolean = current().shortsEnabled
    fun minAppVersion(): Int = current().minAppVersion
    fun latestAppVersion(): Int = current().latestAppVersion
    fun latestApkUrl(): String = current().latestApkUrl
    fun latestVersionCode(): Int = current().latestAppVersion
    fun latestVersionName(): String? = current().latestAppVersion.takeIf { it > 0 }?.toString()

    // ── Init — called from Application.onCreate() ─────────────────────────────

    suspend fun init() = withContext(Dispatchers.IO) {
        val cached = cacheDao.get()
        if (cached != null) {
            applyConfig(gson.fromJson(cached.configJson, AppConfigDto::class.java))
            _state.value = ConfigState.READY
            Log.d(tag, "Config loaded from Room v${_config.value?.version}")
            // Refresh if stale
            if (cached.isStale()) {
                scope.launch { fetchFromBackend() }
            }
        } else {
            // First install — block until we have a config
            fetchFromBackend()
        }
    }

    suspend fun refresh() = fetchFromBackend()

    private suspend fun fetchFromBackend() = withContext(Dispatchers.IO) {
        val result = safeApiCall(tag) { api.getAppConfig() }
        when (result) {
            is NetworkResult.Success -> {
                val dto = result.data
                applyConfig(dto)
                _state.value = ConfigState.READY
                cacheDao.upsert(
                    AppConfigCacheRow(
                        configJson = gson.toJson(dto),
                        version    = dto.version,
                    )
                )
                Log.d(tag, "Config fetched from backend v${dto.version}")
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
        // Update live URL if backend provides an override
        // (AppConfigDto can carry a backend_url field for URL migration)
    }
}
