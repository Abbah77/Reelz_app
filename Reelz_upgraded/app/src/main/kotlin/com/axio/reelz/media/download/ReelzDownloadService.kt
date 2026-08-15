package com.axio.reelz.media.download

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.axio.reelz.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * ReelzDownloadService — thin wrapper around Media3 DownloadService.
 *
 * Replaces the custom 931-line OkHttp HLS segment downloader (old DownloadService.kt).
 * Media3's DownloadManager handles:
 *  - Parallel segment fetching
 *  - M3U8/DASH/progressive download
 *  - Resume after crash or process death
 *  - Exponential backoff on segment failures
 *  - Determinate progress tracking
 *
 * All download orchestration logic now lives in DownloadManager.kt (coordinator)
 * and DownloadRepository.kt (data). This service is just the Android foreground
 * service glue that keeps the process alive during a download.
 *
 * Dependency direction: ReelzDownloadService → Media3 DownloadManager.
 * Never makes network calls directly.
 */
@UnstableApi
@AndroidEntryPoint
class ReelzDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description,
) {

    override fun getDownloadManager(): DownloadManager =
        DownloadManagerCoordinator.getDownloadManager(this)

    override fun getScheduler(): Scheduler =
        PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification =
        DownloadManagerCoordinator.buildNotification(this, downloads)

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "reelz_downloads"
        private const val JOB_ID = 1
    }
}
