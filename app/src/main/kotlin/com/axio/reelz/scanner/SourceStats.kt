package com.axio.reelz.scanner

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks per-source success/failure stats so the engine can always try
 * the fastest, most reliable source first.
 *
 * Stored in SharedPreferences so stats persist across app restarts.
 * Format key: "stats_{sourceName}"
 * Format val: "{successCount},{totalCount},{totalMs}"
 */
@Singleton
class SourceStatsTracker @Inject constructor(
    private val prefs: SharedPreferences,
) {
    data class Stats(
        val successCount: Int = 0,
        val totalCount: Int = 0,
        val totalMs: Long = 0,
    ) {
        val successRate: Float get() = if (totalCount == 0) 0.5f else successCount.toFloat() / totalCount
        val avgMs: Long        get() = if (successCount == 0) 15_000L else totalMs / successCount
        /** Score: higher is better. Combines success rate and speed. */
        val score: Float       get() = successRate * (1f / (avgMs / 1000f + 1f))
    }

    fun get(sourceName: String): Stats {
        val raw = prefs.getString("stats_$sourceName", null) ?: return Stats()
        val parts = raw.split(",")
        if (parts.size < 3) return Stats()
        return Stats(
            successCount = parts[0].toIntOrNull() ?: 0,
            totalCount   = parts[1].toIntOrNull() ?: 0,
            totalMs      = parts[2].toLongOrNull() ?: 0,
        )
    }

    fun recordSuccess(sourceName: String, elapsedMs: Long) {
        val s = get(sourceName)
        save(sourceName, s.copy(
            successCount = s.successCount + 1,
            totalCount   = s.totalCount + 1,
            totalMs      = s.totalMs + elapsedMs,
        ))
    }

    fun recordFailure(sourceName: String) {
        val s = get(sourceName)
        save(sourceName, s.copy(totalCount = s.totalCount + 1))
    }

    private fun save(sourceName: String, stats: Stats) {
        prefs.edit()
            .putString("stats_$sourceName", "${stats.successCount},${stats.totalCount},${stats.totalMs}")
            .apply()
    }
}
