package com.axio.reelz.scanner

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Tracks per-source success/failure stats so the engine can always try
 * the fastest, most reliable source first.
 *
 * Stored in SharedPreferences so stats persist across app restarts.
 * Format key: "stats_{sourceName}"
 * Format val: "{successCount},{totalCount},{totalMs}"
 *
 * Uses its OWN dedicated "reelz_source_stats" prefs file (qualified via
 * @Named("sourceStats") in AppModule) rather than the app's shared
 * "reelz_prefs" file from UtilModule — keeps this module's keys isolated
 * from unrelated app settings, and avoids a Dagger duplicate-binding
 * error from having two unqualified SharedPreferences providers.
 */
@Singleton
class SourceStatsTracker @Inject constructor(
    @Named("sourceStats") private val prefs: SharedPreferences,
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

    companion object {
        // Below this many total attempts, a source's stats aren't
        // statistically meaningful yet — never skip on a tiny sample
        // (e.g. 1 failure out of 1 attempt shouldn't blacklist a source
        // that's actually fine most of the time).
        private const val MIN_SAMPLE_SIZE = 4

        // A source is considered structurally broken (not just slow or
        // occasionally flaky) once its success rate falls at/below this
        // over a large enough sample. vidsrc.to's current tokenized
        // page-N.html segment scheme, for example, will trend toward
        // 0.0 here since it can never produce a working ExoPlayer result.
        private const val SKIP_SUCCESS_RATE = 0.15f

        // Even a skipped source is re-probed periodically in case it
        // recovers — every Nth would-be-skip is let through for real.
        private const val REPROBE_EVERY = 8
    }

    /**
     * True if this source has a large enough sample size AND a low enough
     * success rate that it should be skipped WITHOUT attempting a scan —
     * saving the network/WebView cost of re-discovering a known-dead
     * source on every single resolve. Self-heals via periodic re-probe so
     * a source that gets fixed upstream is rediscovered automatically,
     * without needing a remote config push or app update.
     */
    fun shouldSkip(sourceName: String): Boolean {
        val s = get(sourceName)
        if (s.totalCount < MIN_SAMPLE_SIZE) return false
        if (s.successRate > SKIP_SUCCESS_RATE) return false

        // Periodic re-probe: let one attempt through every REPROBE_EVERY
        // calls even while the source looks dead.
        //
        // BUGFIX: counting attempts from 0 meant `0 % REPROBE_EVERY == 0`
        // on the very first check after a source crossed the bad-streak
        // threshold, causing shouldSkip() to return false (don't skip)
        // on that first real opportunity — a newly-flagged source always
        // got one extra free attempt before skipping ever actually took
        // effect. Counting from 1 fixes this. Found by the equivalent
        // Python resolver's test suite catching the same logic mistake,
        // shipped here with the identical bug since both were written
        // from the same pattern.
        val probeKey = "probe_$sourceName"
        val attempts = prefs.getInt(probeKey, 0) + 1
        prefs.edit().putInt(probeKey, attempts).apply()
        return (attempts % REPROBE_EVERY) != 0
    }
}
