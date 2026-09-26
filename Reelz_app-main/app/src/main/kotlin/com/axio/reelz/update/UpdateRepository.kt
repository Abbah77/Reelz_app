package com.axio.reelz.update

import com.axio.reelz.data.repository.ConfigRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UpdateRepository — thin: reads update info from ConfigRepository.
 *
 * ConfigRepository is the authority for what version the backend considers
 * current. This repo exposes a simple check so UpdateManager can ask "is
 * there an update?" without knowing anything about the config schema.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val configRepo: ConfigRepository,
) {
    /** Returns the latest APK URL if an update is available, null otherwise. */
    fun getLatestApkUrl(): String? = configRepo.latestApkUrl()

    /** Returns the latest version name as reported by the backend. */
    fun getLatestVersionName(): String? = configRepo.latestVersionName()

    /** True if the backend reports a version newer than what's installed. */
    fun isUpdateAvailable(installedVersionCode: Int): Boolean =
        configRepo.latestVersionCode() > installedVersionCode

    /** True if the backend has put the app into maintenance mode. */
    fun isUnderMaintenance(): Boolean = configRepo.isUnderMaintenance()
}
