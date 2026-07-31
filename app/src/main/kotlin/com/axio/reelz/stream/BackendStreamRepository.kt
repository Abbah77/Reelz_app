package com.axio.reelz.stream

import com.axio.reelz.data.model.MediaType
import com.axio.reelz.data.model.QualityTrack
import com.axio.reelz.data.model.StreamResult
import com.axio.reelz.data.model.Subtitle
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BackendStreamRepository
 * ───────────────────────
 * Replaces the entire scanner package (StreamEngine, DirectScanner,
 * WebViewScanner, NativeBridge, all C++). Stream/download/subtitle
 * resolution is 100% server-side now.
 *
 * Two endpoints used (both on config.json → backend.stream_url):
 *   POST /api/v1/streams   → stream URL + qualities + subtitles
 *   POST /api/v1/download  → download links per quality
 *   POST /api/v1/subtitles → subtitle search (manual)
 *
 * The app just POSTs tmdb_id + type + season/episode and plays
 * whatever the backend returns. No WebView. No C++. No regex.
 * No scan loop. Just one HTTP round-trip.
 */
@Singleton
class BackendStreamRepository @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MT = "application/json; charset=utf-8".toMediaType()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Resolve a stream URL from the backend.
     * Returns the best [StreamResult] (with qualities + embedded subtitles),
     * or null if the backend found nothing.
     *
     * Caller: PlayerViewModel (replaces StreamEngine.resolve / prefetch).
     */
    suspend fun resolve(
        tmdbId: Int,
        mediaType: MediaType,
        title: String,
        season: Int = 0,
        episode: Int = 0,
        imdbId: String? = null,
        year: Int? = null,
    ): StreamResult? = withContext(Dispatchers.IO) {
        val baseUrl = streamBaseUrl() ?: return@withContext null
        val body = buildStreamBody(tmdbId, mediaType, title, season, episode, imdbId, year)

        val req = Request.Builder()
            .url("$baseUrl/api/v1/streams")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()

        try {
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val json = JSONObject(resp.body?.string() ?: return@withContext null)
            if (!json.optBoolean("ok", false)) return@withContext null
            parseStreamResponse(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetch all download quality links from the backend.
     * Replaces StreamEngine.resolveAllQualitiesForDownload.
     *
     * Caller: DownloadRepository / download sheet ViewModel.
     */
    suspend fun resolveDownloadLinks(
        tmdbId: Int,
        mediaType: MediaType,
        title: String,
        season: Int = 0,
        episode: Int = 0,
        imdbId: String? = null,
        year: Int? = null,
    ): List<QualityTrack> = withContext(Dispatchers.IO) {
        val baseUrl = streamBaseUrl() ?: return@withContext emptyList()
        val body = buildDownloadBody(tmdbId, mediaType, title, season, episode, imdbId, year)

        val req = Request.Builder()
            .url("$baseUrl/api/v1/download")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()

        try {
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string() ?: return@withContext emptyList())
            if (!json.optBoolean("ok", false)) return@withContext emptyList()
            parseDownloadLinks(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Search subtitles from the backend (manual search).
     * Replaces OpenSubtitlesRepository.
     *
     * Caller: PlayerViewModel subtitle search.
     */
    suspend fun searchSubtitles(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        languages: List<String> = listOf("en"),
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val baseUrl = streamBaseUrl() ?: return@withContext emptyList()
        val body = JSONObject().apply {
            put("tmdb_id", tmdbId)
            put("type", if (mediaType == MediaType.MOVIE) "movie" else "tv")
            put("season", season)
            put("episode", episode)
            put("languages", JSONArray(languages))
        }

        val req = Request.Builder()
            .url("$baseUrl/api/v1/subtitles")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()

        try {
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string() ?: return@withContext emptyList())
            if (!json.optBoolean("ok", false)) return@withContext emptyList()
            parseSubtitleResponse(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Reads stream_url from config. Falls back to backend_url if stream_url absent. */
    private fun streamBaseUrl(): String? {
        val cfg = remoteConfig.current()
        // stream_url added by you later; backend_url is the auth/session url
        val url = cfg.backend.streamUrl.ifBlank { cfg.backend.normalizedUrl }
        return url.ifBlank { null }
    }

    private fun buildStreamBody(
        tmdbId: Int, mediaType: MediaType, title: String,
        season: Int, episode: Int, imdbId: String?, year: Int?,
    ) = JSONObject().apply {
        put("tmdb_id", tmdbId)
        put("type", if (mediaType == MediaType.MOVIE) "movie" else "tv")
        put("title", title)
        if (season > 0)  put("season", season)
        if (episode > 0) put("episode", episode)
        imdbId?.let { put("imdb_id", it) }
        year?.let   { put("year", it) }
    }

    private fun buildDownloadBody(
        tmdbId: Int, mediaType: MediaType, title: String,
        season: Int, episode: Int, imdbId: String?, year: Int?,
    ) = buildStreamBody(tmdbId, mediaType, title, season, episode, imdbId, year)

    /** Parse POST /streams response → StreamResult
     *
     *  Upgraded to:
     *  1. Skip non-playable streams (type == "iframe") — the backend marks
     *     these with playable=false; trying to send an embed URL to ExoPlayer
     *     fails silently. We pick the first stream where playable=true.
     *  2. Use the backend's `type` field ("m3u8" / "mp4") to set isHls
     *     correctly instead of relying solely on URL sniffing. This is needed
     *     for torrent stream URLs (/api/v1/torrent/stream?magnet=…) and
     *     debrid links (/api/v1/torrent/http?url=…), which are always MP4
     *     progressive but don't contain ".m3u8" in the URL.
     *  3. Collect ALL playable streams as a quality ladder so the player
     *     can fall back to the next source if the first one fails.
     */
    private fun parseStreamResponse(json: JSONObject): StreamResult? {
        val streams = json.optJSONArray("streams") ?: return null
        if (streams.length() == 0) return null

        // Pick the first playable stream (backend sorted best-first)
        var first: JSONObject? = null
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            // Skip iframe embeds — ExoPlayer can't play them
            val streamType = s.optString("type", "m3u8")
            val playable   = s.optBoolean("playable", streamType != "iframe")
            if (playable && streamType != "iframe") {
                first = s
                break
            }
        }
        first ?: return null  // all streams were iframes — nothing to play

        val url = first.optString("url").ifBlank { return null }

        // Use the backend's explicit type field; fall back to URL sniffing
        val streamType = first.optString("type", "")
        val isHls = when {
            streamType == "m3u8" -> true
            streamType == "mp4"  -> false
            // Torrent stream endpoints always return progressive MP4
            url.contains("/api/v1/torrent/") -> false
            // Classic URL sniff for all other cases
            else -> url.contains(".m3u8", ignoreCase = true)
        }

        // Headers
        val headers = mutableMapOf<String, String>()
        first.optJSONObject("headers")?.keys()?.forEach { k ->
            headers[k] = first.optJSONObject("headers")!!.optString(k)
        }
        val referer = first.optString("referer", "")
        val origin  = first.optString("origin",  "")

        // Quality ladder — include all other playable streams as fallback sources.
        // The player can cycle through these if the primary URL fails.
        val qualities = mutableListOf<QualityTrack>()
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            val sType    = s.optString("type", "m3u8")
            val sPlayable = s.optBoolean("playable", sType != "iframe")
            if (!sPlayable || sType == "iframe") continue
            val sUrl = s.optString("url").ifBlank { continue }
            qualities.add(QualityTrack(
                label     = s.optString("quality", s.optString("name", "Auto")).ifBlank { "Auto" },
                url       = sUrl,
                bandwidth = 0L,
            ))
        }
        // Also accept an explicit quality array embedded in the first stream
        first.optJSONArray("qualities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val q = arr.getJSONObject(i)
                val qUrl = q.optString("url", url).ifBlank { continue }
                val label = q.optString("label", "Auto")
                if (qualities.none { it.url == qUrl }) {
                    qualities.add(QualityTrack(label = label, url = qUrl,
                        bandwidth = q.optLong("bandwidth", 0)))
                }
            }
        }

        // Subtitles embedded in stream response
        val subtitles = parseSubtitlesArray(json.optJSONArray("subtitles"))

        return StreamResult(
            url        = url,
            isHls      = isHls,
            quality    = first.optString("quality", "Auto"),
            headers    = headers,
            referer    = referer,
            origin     = origin,
            sourceName = first.optString("provider", "backend"),
            subtitles  = subtitles,
            qualities  = qualities,
        )
    }

    /** Parse POST /download response → List<QualityTrack> */
    private fun parseDownloadLinks(json: JSONObject): List<QualityTrack> {
        val links = json.optJSONArray("links") ?: return emptyList()
        return (0 until links.length()).map { i ->
            val item = links.getJSONObject(i)
            QualityTrack(
                label     = item.optString("quality", "Auto"),
                url       = item.optString("url", ""),
                bandwidth = item.optLong("bandwidth", 0),
                estimatedSizeBytes = item.optLong("size_bytes", 0),
                isSizeExact = item.optBoolean("size_exact", false),
            )
        }.filter { it.url.isNotBlank() }
    }

    /** Parse POST /subtitles response → List<Subtitle> */
    private fun parseSubtitleResponse(json: JSONObject): List<Subtitle> =
        parseSubtitlesArray(json.optJSONArray("subtitles"))

    private fun parseSubtitlesArray(arr: JSONArray?): List<Subtitle> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.getJSONObject(i)
            val url = s.optString("url").ifBlank { return@mapNotNull null }
            Subtitle(
                url      = url,
                language = s.optString("language", "en"),
                label    = s.optString("label", "English"),
            )
        }
    }
}
