package com.axio.reelz.scanner

import android.media.MediaMetadataRetriever
import com.axio.reelz.data.model.QualityTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Shared HLS/MP4 quality-list helpers used by both:
 *  - StreamEngine.resolveAllQualitiesForDownload() (download sheet, multi-source)
 *  - DetailViewModel (single-source pre-resolve, used for the "Play" path)
 *
 * DATA-SAVER SIZE STRATEGY (as of the data-saver upgrade):
 *  - MP4 direct links: one HEAD request → real Content-Length. Exact, instant,
 *    and cheap (headers only, no video bytes).
 *  - HLS variants: bandwidth × runtime estimate, computed entirely from the
 *    manifest text already fetched to build the list. Zero extra network
 *    calls, zero video bytes. Marked isSizeExact = false so the UI shows "~".
 *  - The OLD behavior (downloading real HLS segments automatically to
 *    measure exact size) burned meaningful mobile data just to POPULATE the
 *    download sheet, before the user picked anything. That auto-download is
 *    gone. The same real-segment-sampling logic still exists but only runs
 *    opt-in, per-track, via [probeExactQualityOnDemand] — wire it to an
 *    explicit "verify exact size" user action if you want it, it is never
 *    called automatically.
 */
object QualityListParsing {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Fetches and parses a master playlist into quality variants (no sizes yet).
     * If the playlist has NO variant ladder (just one media playlist — common
     * for scraped embed sources that pre-resolve server-side to one quality),
     * this returns an empty list; callers should fall back to
     * [probeSingleQuality] rather than a synthetic "Best available" label —
     * "Best available" was being treated as Int.MAX_VALUE height, which
     * ALWAYS exceeded the free-tier cap and locked it behind Premium even
     * when the real (only) resolution was something the free tier allows,
     * like 480p.
     */
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
     * For a stream with NO variant ladder (single quality only — either a
     * plain MP4 or an HLS media playlist with no #EXT-X-STREAM-INF entries),
     * estimates the resolution from a bandwidth hint (same tiering used
     * everywhere else) instead of opening the real video with
     * MediaMetadataRetriever. Free, instant, zero video bytes downloaded.
     *
     * PremiumGate only ever reads the resulting LABEL (see trackHeightPx in
     * DetailScreen.kt), never a probed pixel height directly, so gating
     * behavior is unaffected by this change.
     */
    suspend fun probeSingleQuality(
        url: String,
        headers: Map<String, String>,
        bandwidthHint: Long = 0L,
        runtimeMinutes: Int? = null,
        /**
         * Optional label already known from the source itself (e.g.
         * StreamResult.quality, when a scanner already reports "720p" from
         * the page/API rather than a guess). Free — zero network cost — and
         * checked BEFORE falling back to a bandwidth tier or "Best available".
         */
        knownLabelHint: String = "",
    ): QualityTrack = withContext(Dispatchers.IO) {
        // Cheap HEAD only — a few hundred bytes of headers, never the video
        // body. Works for direct MP4; returns null (no-op) for HLS.
        val headBytes = try {
            if (url.substringBefore("?").endsWith(".mp4", ignoreCase = true))
                headSizeMp4(url, headers)
            else null
        } catch (_: Exception) { null }

        val looksLikeRealLabel = Regex("""^\d{3,4}p$|^4K$""").matches(knownLabelHint)

        val label = when {
            looksLikeRealLabel -> knownLabelHint
            bandwidthHint >= 4_000_000 -> "1080p"
            bandwidthHint >= 2_000_000 -> "720p"
            bandwidthHint >= 1_000_000 -> "480p"
            bandwidthHint >= 400_000   -> "360p"
            bandwidthHint >  0         -> "240p"
            else -> "Best available" // no signal at all — last resort, same as before
        }

        val estimatedSize = headBytes ?: estimateFromBandwidth(bandwidthHint, runtimeMinutes)

        QualityTrack(
            label = label,
            url = url,
            bandwidth = bandwidthHint,
            estimatedSizeBytes = estimatedSize,
            // HEAD Content-Length for MP4 is exact AND free (no video bytes
            // downloaded) — keep isSizeExact true only for that case.
            isSizeExact = headBytes != null,
        )
    }

    /**
     * OPT-IN ONLY — real measurement (MediaMetadataRetriever + real segment/
     * HEAD probing) for exactly one track. Never called automatically while
     * building the quality list; wire this to an explicit user action (e.g.
     * a "verify exact size" tap) if you want it available in the UI.
     */
    suspend fun probeExactQualityOnDemand(
        url: String,
        headers: Map<String, String>,
    ): Pair<Int, Long?> = withContext(Dispatchers.IO) {
        val heightPx = try {
            withTimeoutOrNull(6_000L) { probeRealHeight(url, headers) }
        } catch (_: Exception) { null } ?: 0
        // Opt-in path keeps the full real-measurement behavior: exact HEAD
        // for MP4, real segment sampling for HLS (only ever spends data when
        // the user explicitly asks for this one track).
        val isMp4 = url.substringBefore("?").endsWith(".mp4", ignoreCase = true)
        val realSize = try {
            if (isMp4) headSizeMp4(url, headers) else sampleHlsVariantSize(url, headers)
        } catch (_: Exception) { null }
        heightPx to realSize
    }

    /** Reads the real video height via Android's MediaMetadataRetriever — an
     *  actual measurement of the stream, not an assumption. Only called from
     *  [probeExactQualityOnDemand], never automatically. */
    private fun probeRealHeight(url: String, headers: Map<String, String>): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, headers)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
        } finally {
            try { retriever.release() } catch (_: Exception) {}
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
     * Returns a REAL measured byte size, or null if it couldn't be measured.
     *
     * DATA-SAVER NOTE: this used to also sample real HLS segments
     * (sampleHlsVariantSize) to measure exact size, which downloaded actual
     * video bytes automatically for every quality of every source just to
     * build the list. That auto-triggered segment download has been removed
     * — HLS variants now always use the free bandwidth-based estimate (shown
     * with "~" in the UI, which the UI already does). MP4 direct links still
     * get an exact size via a HEAD request (headers only, no video bytes).
     *
     * The old real-segment-sampling behavior is preserved, opt-in only, in
     * [probeExactQualityOnDemand] for a single user-selected track.
     */
    private fun measureRealSize(url: String, headers: Map<String, String>): Long? {
        val isMp4 = url.substringBefore("?").endsWith(".mp4", ignoreCase = true)
        return if (isMp4) headSizeMp4(url, headers) else null
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
