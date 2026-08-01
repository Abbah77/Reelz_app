package com.axio.reelz.stream

import android.util.Log
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.data.model.QualityTrack
import com.axio.reelz.data.model.StreamResult
import com.axio.reelz.data.model.Subtitle
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BackendStreamRepository — POST edition
 * ────────────────────────────────────────
 * Three independent POST calls replace the old unified SSE connection:
 *
 *   POST /api/v1/streams    → first valid stream URL (m3u8 preferred) + full
 *                             fallback ladder. Returns as soon as the backend
 *                             finds the FIRST valid source — typically 300-800 ms
 *                             vs the old SSE first-event latency of 1-3 s.
 *
 *   POST /api/v1/download   → deduplicated per-resolution download links.
 *                             m3u8 masters are expanded server-side into
 *                             individual quality variants (1080p, 720p, …).
 *                             No duplicate qualities.
 *
 *   POST /api/v1/subtitles  → OpenSubtitles results.
 *
 * All three fire in parallel (coroutineScope + async). The stream call is
 * awaited first so playback starts immediately; download and subtitle results
 * populate their UI as they arrive.
 *
 * Public surface (identical to SSE version — no ViewModel changes needed):
 *   resolveFirst(...)  → StreamResult?   (PlayerViewModel)
 *   searchSubtitles(…) → List<Subtitle>  (PlayerViewModel subtitle search)
 */
@Singleton
class BackendStreamRepository @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
) {
    // ── HTTP client ───────────────────────────────────────────────────────────
    // connectTimeout: 5 s — aggressive but fair for a home/VPS backend.
    // readTimeout: 60 s  — stream resolve can take up to 45 s on cold backend.
    // HTTP/2: one TLS handshake for all three parallel POST requests.
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // ── Sealed event types (kept identical to SSE version for compat) ─────────
    sealed class MediaEvent {
        data class StreamAvailable(
            val url:      String,
            val type:     String,
            val quality:  String,
            val name:     String,
            val language: String,
            val headers:  Map<String, String>,
            val priority: Int,
        ) : MediaEvent()

        data class DownloadAvailable(
            val url:       String,
            val type:      String,
            val quality:   String,
            val language:  String,
            val sizeBytes: Long,
        ) : MediaEvent()

        data class SubtitleAvailable(
            val url:      String,
            val language: String,
            val label:    String,
            val format:   String,
        ) : MediaEvent()

        data class Done(
            val streamsTotal:   Int,
            val downloadsTotal: Int,
            val subtitlesTotal: Int,
        ) : MediaEvent()

        data class ProviderStatus(val id: String, val state: String, val durationMs: Int) : MediaEvent()
        data class ConnectionError(val reason: String) : MediaEvent()
    }

    // ── Low-level POST helper ─────────────────────────────────────────────────

    private suspend fun post(url: String, body: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    Log.w("BackendPOST", "HTTP ${resp.code} from $url")
                    return@withContext null
                }
                val text = resp.body?.string() ?: return@withContext null
                JSONObject(text)
            } catch (e: Exception) {
                Log.e("BackendPOST", "POST $url failed: ${e.message}")
                null
            }
        }

    // ── Build the shared request body ─────────────────────────────────────────

    private fun buildBody(
        tmdbId:    Int,
        mediaType: MediaType,
        title:     String,
        season:    Int,
        episode:   Int,
        imdbId:    String?,
        year:      Int?,
    ) = JSONObject().apply {
        put("tmdb_id", tmdbId)
        put("type", if (mediaType == MediaType.MOVIE) "movie" else "tv")
        put("title", title)
        if (imdbId != null)          put("imdb_id", imdbId)
        if (year   != null)          put("year", year)
        if (season  > 0)             put("season", season)
        if (episode > 0)             put("episode", episode)
    }

    // ── Parse helpers ─────────────────────────────────────────────────────────

    private fun JSONObject.toStringMap(): Map<String, String> {
        val m = mutableMapOf<String, String>()
        keys().forEach { k -> m[k] = optString(k) }
        return m
    }

    private fun parseStreamEntry(json: JSONObject): MediaEvent.StreamAvailable? {
        val url  = json.optString("url").ifBlank { return null }
        val type = json.optString("type", "m3u8")
        if (type == "iframe") return null
        val headers = json.optJSONObject("headers")?.toStringMap() ?: emptyMap()
        return MediaEvent.StreamAvailable(
            url      = url,
            type     = type,
            quality  = json.optString("quality", "Auto").ifBlank { "Auto" },
            name     = json.optString("name", ""),
            language = json.optString("language", "English"),
            headers  = headers,
            priority = json.optInt("priority", 0),
        )
    }

    private fun parseDownloadEntry(json: JSONObject): MediaEvent.DownloadAvailable? {
        val url = json.optString("url").ifBlank { return null }
        return MediaEvent.DownloadAvailable(
            url       = url,
            type      = json.optString("type", "mp4"),
            quality   = json.optString("quality", "Auto").ifBlank { "Auto" },
            language  = json.optString("language", "English"),
            sizeBytes = json.optLong("size_bytes", 0),
        )
    }

    private fun parseSubtitleEntry(json: JSONObject): MediaEvent.SubtitleAvailable? {
        val url = json.optString("url").ifBlank { return null }
        return MediaEvent.SubtitleAvailable(
            url      = url,
            language = json.optString("language", "en"),
            label    = json.optString("label", "English"),
            format   = json.optString("format", "srt"),
        )
    }

    // ── resolveFirst — the main entry point ───────────────────────────────────
    //
    // Fires all three POST calls in parallel.
    // Returns immediately once /streams responds (playback can start).
    // Download and subtitle callbacks fire as their responses arrive.

    suspend fun resolveFirst(
        tmdbId:    Int,
        mediaType: MediaType,
        title:     String,
        season:    Int = 0,
        episode:   Int = 0,
        imdbId:    String? = null,
        year:      Int? = null,
        languages: List<String> = listOf("en"),
        onStream:   (QualityTrack) -> Unit = {},
        onDownload: (QualityTrack) -> Unit = {},
        onSubtitle: (Subtitle) -> Unit     = {},
    ): StreamResult? = coroutineScope {

        val base = streamBaseUrl() ?: run {
            Log.e("BackendPOST", "No backend URL configured")
            return@coroutineScope null
        }

        val body = buildBody(tmdbId, mediaType, title, season, episode, imdbId, year)

        // ── Fire all three in parallel ─────────────────────────────────────
        val streamDeferred = async(Dispatchers.IO) {
            post("$base/api/v1/streams", body)
        }
        val downloadDeferred = async(Dispatchers.IO) {
            post("$base/api/v1/download", body)
        }
        val subtitleDeferred = async(Dispatchers.IO) {
            val subBody = JSONObject().apply {
                put("tmdb_id", tmdbId)
                put("type", if (mediaType == MediaType.MOVIE) "movie" else "tv")
                if (imdbId != null) put("imdb_id", imdbId)
                if (season  > 0)   put("season", season)
                if (episode > 0)   put("episode", episode)
                put("languages", JSONArray(languages))
            }
            post("$base/api/v1/subtitles", subBody)
        }

        // ── Await streams first — this is the critical path ───────────────
        val streamsJson = streamDeferred.await()
        if (streamsJson == null || !streamsJson.optBoolean("ok", false)) {
            Log.w("BackendPOST", "Streams response: ok=false or null")
            // Still drain the other two so their callbacks fire
            downloadDeferred.await()?.let { handleDownloads(it, onDownload) }
            subtitleDeferred.await()?.let { handleSubtitles(it, onSubtitle) }
            return@coroutineScope null
        }

        // Parse the best (first) stream
        val bestJson = streamsJson.optJSONObject("stream")
        val best     = bestJson?.let { parseStreamEntry(it) }
        if (best == null) {
            downloadDeferred.await()?.let { handleDownloads(it, onDownload) }
            subtitleDeferred.await()?.let { handleSubtitles(it, onSubtitle) }
            return@coroutineScope null
        }

        // Build the full fallback ladder from "streams" array
        val fallbackLadder = mutableListOf<QualityTrack>()
        val streamsArr = streamsJson.optJSONArray("streams")
        if (streamsArr != null) {
            for (i in 0 until streamsArr.length()) {
                val e = parseStreamEntry(streamsArr.getJSONObject(i)) ?: continue
                val track = QualityTrack(label = e.quality, url = e.url)
                if (fallbackLadder.none { it.url == track.url }) {
                    fallbackLadder.add(track)
                    if (e.url != best.url) onStream(track)  // notify extras
                }
            }
        }
        // Ensure the winner is at index 0
        if (fallbackLadder.none { it.url == best.url }) {
            fallbackLadder.add(0, QualityTrack(label = best.quality, url = best.url))
        }

        val isHls = best.type == "m3u8" || best.url.contains(".m3u8", ignoreCase = true)

        val result = StreamResult(
            url        = best.url,
            isHls      = isHls,
            quality    = best.quality,
            headers    = best.headers,
            sourceName = best.name,
            qualities  = fallbackLadder,
        )

        // ── Drain downloads and subtitles in the background ───────────────
        // Both are already in-flight (fired above). We just await and call
        // the callbacks — these do NOT block playback from starting.
        async(Dispatchers.IO) {
            downloadDeferred.await()?.let { handleDownloads(it, onDownload) }
        }
        async(Dispatchers.IO) {
            subtitleDeferred.await()?.let { handleSubtitles(it, onSubtitle) }
        }

        result
    }

    // ── Callback handlers ─────────────────────────────────────────────────────

    private fun handleDownloads(json: JSONObject, onDownload: (QualityTrack) -> Unit) {
        val arr = json.optJSONArray("links") ?: return
        for (i in 0 until arr.length()) {
            val e = parseDownloadEntry(arr.getJSONObject(i)) ?: continue
            onDownload(QualityTrack(
                label              = "${e.quality} (${e.language})",
                url                = e.url,
                estimatedSizeBytes = e.sizeBytes,
            ))
        }
    }

    private fun handleSubtitles(json: JSONObject, onSubtitle: (Subtitle) -> Unit) {
        val arr = json.optJSONArray("subtitles") ?: return
        for (i in 0 until arr.length()) {
            val e = parseSubtitleEntry(arr.getJSONObject(i)) ?: continue
            onSubtitle(Subtitle(url = e.url, language = e.language, label = e.label))
        }
    }

    // ── resolveDownloadLinks — used by DetailScreen + DownloadService ─────────
    //
    // Calls POST /api/v1/download and returns a flat list of QualityTrack entries,
    // one per available resolution. The backend deduplicates by quality so there
    // are never two 1080p entries for the same language.

    suspend fun resolveDownloadLinks(
        tmdbId:    Int,
        mediaType: MediaType,
        title:     String,
        season:    Int = 0,
        episode:   Int = 0,
        imdbId:    String? = null,
        year:      Int? = null,
    ): List<QualityTrack> = withContext(Dispatchers.IO) {
        val base = streamBaseUrl() ?: return@withContext emptyList()
        val body = buildBody(tmdbId, mediaType, title, season, episode, imdbId, year)
        val json = post("$base/api/v1/download", body) ?: return@withContext emptyList()
        val arr  = json.optJSONArray("links") ?: return@withContext emptyList()
        val out  = mutableListOf<QualityTrack>()
        for (i in 0 until arr.length()) {
            val e = parseDownloadEntry(arr.getJSONObject(i)) ?: continue
            out.add(QualityTrack(
                label              = "${e.quality} (${e.language})",
                url                = e.url,
                estimatedSizeBytes = e.sizeBytes,
            ))
        }
        out
    }

    // ── searchSubtitles — used by PlayerViewModel subtitle search ─────────────

    suspend fun searchSubtitles(
        tmdbId:    Int,
        mediaType: MediaType,
        season:    Int = 0,
        episode:   Int = 0,
        languages: List<String> = listOf("en"),
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val base = streamBaseUrl() ?: return@withContext emptyList()
        val body = JSONObject().apply {
            put("tmdb_id", tmdbId)
            put("type", if (mediaType == MediaType.MOVIE) "movie" else "tv")
            if (season  > 0) put("season", season)
            if (episode > 0) put("episode", episode)
            put("languages", JSONArray(languages))
        }
        val json = post("$base/api/v1/subtitles", body) ?: return@withContext emptyList()
        val arr  = json.optJSONArray("subtitles") ?: return@withContext emptyList()
        val out  = mutableListOf<Subtitle>()
        for (i in 0 until arr.length()) {
            val e = parseSubtitleEntry(arr.getJSONObject(i)) ?: continue
            out.add(Subtitle(url = e.url, language = e.language, label = e.label))
        }
        out
    }

    // ── Config helper ─────────────────────────────────────────────────────────

    private fun streamBaseUrl(): String? {
        val cfg = remoteConfig.current()
        val url = cfg.backend.streamUrl.ifBlank { cfg.backend.normalizedUrl }
        return url.ifBlank { null }?.trimEnd('/')
    }
}

// ── Extension: searchSubtitles kept for call-site compat ─────────────────────

suspend fun BackendStreamRepository.searchSubtitles(
    tmdbId:    Int,
    mediaType: MediaType,
    season:    Int = 0,
    episode:   Int = 0,
    languages: List<String> = listOf("en"),
): List<Subtitle> = searchSubtitles(tmdbId, mediaType, season, episode, languages)
