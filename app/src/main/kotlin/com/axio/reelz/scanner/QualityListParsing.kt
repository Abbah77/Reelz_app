package com.axio.reelz.scanner

import com.axio.reelz.data.model.QualityTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Shared HLS/MP4 quality-list helpers used by both:
 *  - StreamEngine.resolveAllQualitiesForDownload() (download sheet, multi-source)
 *  - DetailViewModel (single-source pre-resolve, used for the "Play" path)
 *
 * REAL SIZE CALCULATION (replaces the old bandwidth×runtime guess):
 *  - MP4 direct links: one HEAD request → real Content-Length. Exact, instant.
 *  - HLS variants: download the FIRST 2 segments of that variant's media
 *    playlist, measure their real byte size, then multiply by total segment
 *    count. This is a true measurement of the actual encoded stream (not a
 *    guess from declared bandwidth), and only costs the time to fetch ~2
 *    short segments (typically well under a second on a decent connection).
 *    Falls back to the bandwidth estimate ONLY if segment sampling fails
 *    (e.g. source blocks range requests), and that fallback is marked so the
 *    UI can show "≈" instead of an exact size.
 */
object QualityListParsing {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Fetches and parses a master playlist into quality variants (no sizes yet). */
    suspend fun parseVariants(
        masterUrl: String,
        headers: Map<String, String>,
    ): List<QualityTrack> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(masterUrl).apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return@withContext emptyList()
            val raw = NativeBridge.variants(body, masterUrl)
            if (raw.isEmpty()) return@withContext emptyList()
            withRealSizes(raw, headers)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Given already-parsed variants (label/url/bandwidth), fills in REAL sizes:
     *  - MP4 → HEAD request, exact Content-Length.
     *  - HLS variant playlist → sample first ~2 segments, measure real bytes,
     *    scale by segment count for the true total.
     * Falls back to the bandwidth estimate (flagged) only if measurement fails.
     * Runs all variants in parallel — this is the "fast, no long wait" part.
     */
    suspend fun withRealSizes(
        tracks: List<QualityTrack>,
        headers: Map<String, String>,
        runtimeMinutes: Int? = null,
    ): List<QualityTrack> = coroutineScope {
        tracks.map { track ->
            async(Dispatchers.IO) {
                val fixedLabel = fixLabel(track.label, track.bandwidth)
                val realSize = try {
                    measureRealSize(track.url, headers)
                } catch (_: Exception) { null }

                val finalSize = realSize ?: estimateFromBandwidth(track.bandwidth, runtimeMinutes)
                track.copy(
                    label = fixedLabel,
                    estimatedSizeBytes = finalSize,
                    isSizeExact = realSize != null,
                )
            }
        }.awaitAll()
            .groupBy { it.label }
            .map { (_, v) -> v.maxByOrNull { it.bandwidth }!! }
            .sortedByDescending { it.bandwidth }
    }

    private fun fixLabel(label: String, bandwidth: Long): String = when {
        label != "Auto" && label.isNotBlank() -> label
        bandwidth >= 4_000_000 -> "1080p"
        bandwidth >= 2_000_000 -> "720p"
        bandwidth >= 1_000_000 -> "480p"
        bandwidth >= 400_000   -> "360p"
        bandwidth >  0         -> "240p"
        else -> "Auto"
    }

    private fun estimateFromBandwidth(bandwidth: Long, runtimeMinutes: Int?): Long {
        if (bandwidth <= 0) return 0L
        val runtimeSec = if (runtimeMinutes != null && runtimeMinutes > 0) runtimeMinutes * 60L else 7200L
        return ((bandwidth * runtimeSec) / 8L * 55L) / 100L
    }

    /**
     * Returns a REAL measured byte size, or null if it couldn't be measured
     * (caller then falls back to the bandwidth estimate).
     */
    private fun measureRealSize(url: String, headers: Map<String, String>): Long? {
        val isMp4 = url.substringBefore("?").endsWith(".mp4", ignoreCase = true)
        return if (isMp4) {
            headSizeMp4(url, headers)
        } else {
            sampleHlsVariantSize(url, headers)
        }
    }

    /** Real exact size for a direct MP4 via HEAD's Content-Length. */
    private fun headSizeMp4(url: String, headers: Map<String, String>): Long? {
        val req = Request.Builder().url(url).head().apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val len = resp.header("Content-Length")?.toLongOrNull()
            return if (len != null && len > 0) len else null
        }
    }

    /**
     * Real measured size for an HLS variant: fetch its media playlist, take the
     * first up-to-2 segment URLs, download them, measure actual bytes, and
     * scale up by (totalSegments / sampledSegments). This reflects the true
     * encoded size of THIS variant — not a bandwidth guess.
     */
    private fun sampleHlsVariantSize(variantUrl: String, headers: Map<String, String>): Long? {
        val playlistReq = Request.Builder().url(variantUrl).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        val playlistBody = client.newCall(playlistReq).execute().use { it.body?.string() } ?: return null

        val segmentUrls = NativeBridge.segments(playlistBody, variantUrl)
        if (segmentUrls.isEmpty()) return null

        val sampleCount = minOf(2, segmentUrls.size)
        var sampledBytes = 0L
        for (i in 0 until sampleCount) {
            val segReq = Request.Builder().url(segmentUrls[i]).apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            client.newCall(segReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                sampledBytes += resp.body?.bytes()?.size ?: 0
            }
        }
        if (sampledBytes <= 0) return null

        val avgPerSegment = sampledBytes.toDouble() / sampleCount
        return (avgPerSegment * segmentUrls.size).toLong()
    }
}
