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
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BackendStreamRepository — POST edition with URL caching + bulletproof error handling
 * ──────────────────────────────────────────────────────────────────────────────────────
 *
 * WHAT CHANGED vs the original:
 *
 * 1. URL CACHING (StreamUrlCache, 4-minute TTL)
 *    resolveFirst() checks the cache FIRST. If a valid (non-expired) entry exists it
 *    returns immediately — no backend round-trip. This covers the common pattern of:
 *      • User pauses → replies on WhatsApp → comes back
 *      • User navigates to cast screen → returns
 *      • User locks screen and unlocks (free tier pauses, then resumes)
 *    The 4-minute TTL is conservative: most CDN-signed URLs live 5-30 min, so the
 *    cache window ends before the URL is at risk of expiring. The error handler
 *    (in PlayerViewModel) is the safety net for the rare case a cached URL dies early.
 *
 * 2. AGGRESSIVE HTTP-LAYER ERROR HANDLING
 *    post() now detects fatal HTTP errors immediately and classifies them:
 *      • 403 / Cloudflare block (CF-RAY header, 1xxx codes in body) → StreamError.Blocked
 *      • 404 → StreamError.NotFound  (only error shown to user as friendly message)
 *      • 5xx / timeouts / network errors → StreamError.Transient  (silent retry)
 *    resolveFirst() propagates the error type so PlayerViewModel can decide:
 *      → Transient: invisible to the user, instant re-fetch
 *      → Blocked:   invisible, instant re-fetch (backend may have a different source)
 *      → NotFound:  friendly message ("Reelz doesn't have this yet…")
 *
 * 3. CACHE INVALIDATION ON ERROR
 *    When the ViewModel exhausts the fallback ladder, it calls invalidateCache() so
 *    the next resolve hits the backend fresh rather than returning a dead cached URL.
 *
 * Public surface (identical to original — no ViewModel signature changes):
 *   resolveFirst(...)      → StreamResult?
 *   resolveDownloadLinks() → List<QualityTrack>
 *   searchSubtitles(…)    → List<Subtitle>
 *   invalidateCache(…)     → Unit   ← new, called by ViewModel on ladder exhaustion
 *   lastErrorType          → StreamError  ← read by ViewModel for error routing
 */
@Singleton
class BackendStreamRepository @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
    private val urlCache: StreamUrlCache,
) {

    // ── Error classification ───────────────────────────────────────────────────
    enum class StreamError {
        NONE,       // success
        TRANSIENT,  // timeout, 5xx, connection reset — retry silently
        BLOCKED,    // 403, Cloudflare — retry silently (backend may have alt source)
        NOT_FOUND,  // 404, backend ok=false with no streams — show friendly message
    }

    @Volatile var lastErrorType: StreamError = StreamError.NONE
        private set

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
        // Attach the shared secret on every backend request.
        // Token is read fresh per-request from remoteConfig so rotating it
        // via a config push takes effect immediately without restarting.
        .addInterceptor { chain ->
            val token = remoteConfig.backendConfig().appToken
            val req = if (token.isNotBlank()) {
                chain.request().newBuilder()
                    .header("X-Reelz-Token", token)
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(req)
        }
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // ── Sealed event types (kept identical to original for compat) ─────────────
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

    // ── POST result wrapper — carries error type ───────────────────────────────
    private sealed class PostResult {
        data class Ok(val json: JSONObject) : PostResult()
        data class Err(val error: StreamError) : PostResult()
    }

    // ── Low-level POST helper with aggressive error classification ────────────
    //
    // Returns PostResult.Ok on success, PostResult.Err with classified StreamError
    // on any failure. Never throws — all exceptions are caught and mapped.

    private suspend fun postClassified(url: String, body: JSONObject): PostResult =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()
                val resp = client.newCall(req).execute()

                when {
                    resp.isSuccessful -> {
                        val text = resp.body?.string()
                        if (text.isNullOrBlank()) {
                            Log.w("BackendPOST", "Empty body from $url")
                            return@withContext PostResult.Err(StreamError.TRANSIENT)
                        }
                        try {
                            PostResult.Ok(JSONObject(text))
                        } catch (e: Exception) {
                            Log.e("BackendPOST", "JSON parse failed from $url: ${e.message}")
                            PostResult.Err(StreamError.TRANSIENT)
                        }
                    }
                    resp.code == 404 -> {
                        Log.w("BackendPOST", "404 from $url — content not found")
                        PostResult.Err(StreamError.NOT_FOUND)
                    }
                    resp.code == 403 || isCloudflareBlock(resp) -> {
                        Log.w("BackendPOST", "403/CF block from $url (code=${resp.code})")
                        PostResult.Err(StreamError.BLOCKED)
                    }
                    resp.code in 500..599 -> {
                        Log.w("BackendPOST", "5xx ${resp.code} from $url")
                        PostResult.Err(StreamError.TRANSIENT)
                    }
                    resp.code == 429 -> {
                        Log.w("BackendPOST", "429 rate-limited from $url")
                        PostResult.Err(StreamError.TRANSIENT)
                    }
                    else -> {
                        Log.w("BackendPOST", "HTTP ${resp.code} from $url")
                        PostResult.Err(StreamError.TRANSIENT)
                    }
                }
            } catch (e: IOException) {
                // Network-level failure: timeout, connection reset, DNS failure, etc.
                Log.e("BackendPOST", "IO error for $url: ${e.message}")
                PostResult.Err(StreamError.TRANSIENT)
            } catch (e: Exception) {
                Log.e("BackendPOST", "Unexpected error for $url: ${e.message}")
                PostResult.Err(StreamError.TRANSIENT)
            }
        }

    /** Detect Cloudflare blocks: CF-RAY header present, or body contains CF error codes */
    private fun isCloudflareBlock(resp: okhttp3.Response): Boolean {
        if (resp.header("CF-RAY") != null || resp.header("cf-ray") != null) return true
        // CF returns 1xxx error codes in JSON body for some block types
        val bodyPeek = try { resp.peekBody(512).string() } catch (_: Exception) { "" }
        return bodyPeek.contains("\"code\":1") || bodyPeek.contains("Cloudflare", ignoreCase = true)
    }

    // Convenience wrapper that returns JSONObject? (for callers that don't need error type)
    private suspend fun post(url: String, body: JSONObject): JSONObject? =
        when (val r = postClassified(url, body)) {
            is PostResult.Ok  -> r.json
            is PostResult.Err -> null
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
        if (imdbId != null)  put("imdb_id", imdbId)
        if (year   != null)  put("year", year)
        if (season  > 0)     put("season", season)
        if (episode > 0)     put("episode", episode)
    }

    // ── Parse helpers ─────────────────────────────────────────────────────────

    private fun JSONObject.toStringMap(): Map<String, String> {
        val m = mutableMapOf<String, String>()
        keys().forEach { k -> m[k] = optString(k) }
        return m
    }

    /**
     * Safely reads a string field that may be JSON null (not just absent).
     * json.optString("quality", "Auto") returns the string "null" when the
     * field is present but set to JSON null — this helper returns the
     * fallback in that case too.
     */
    private fun JSONObject.safeString(key: String, fallback: String = ""): String {
        if (isNull(key)) return fallback
        val v = optString(key, fallback)
        return if (v.isBlank() || v == "null") fallback else v
    }

    private fun parseStreamEntry(json: JSONObject): MediaEvent.StreamAvailable? {
        val url  = json.optString("url").ifBlank { return null }
        val type = json.optString("type", "m3u8")
        if (type == "iframe") return null
        val headers = json.optJSONObject("headers")?.toStringMap() ?: emptyMap()
        return MediaEvent.StreamAvailable(
            url      = url,
            type     = type,
            quality  = json.safeString("quality", "Auto"),
            name     = json.safeString("name", ""),
            language = json.safeString("language", "English"),
            headers  = headers,
            priority = json.optInt("priority", 0),
        )
    }

    private fun parseDownloadEntry(json: JSONObject): MediaEvent.DownloadAvailable? {
        val url = json.optString("url").ifBlank { return null }
        return MediaEvent.DownloadAvailable(
            url       = url,
            type      = json.safeString("type", "mp4"),
            quality   = json.safeString("quality", "Auto"),
            language  = json.safeString("language", "English"),
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

    // ── Cache helpers ─────────────────────────────────────────────────────────

    private fun cacheTypeString(mediaType: MediaType) =
        if (mediaType == MediaType.MOVIE) "movie" else "tv"

    /** Called by PlayerViewModel when the fallback ladder is fully exhausted. */
    fun invalidateCache(tmdbId: Int, mediaType: MediaType, season: Int, episode: Int) {
        urlCache.invalidate(tmdbId, cacheTypeString(mediaType), season, episode)
    }

    // ── resolveFirst — the main entry point ───────────────────────────────────
    //
    // 1. Check the 4-minute URL cache — return immediately if valid.
    // 2. Fire all three POST calls in parallel.
    // 3. Cache the result before returning.
    // 4. Set lastErrorType for the ViewModel to route errors correctly.

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

        lastErrorType = StreamError.NONE

        // ── 1. Cache check — instant return if valid ───────────────────────
        val cached = urlCache.get(tmdbId, cacheTypeString(mediaType), season, episode)
        if (cached != null) {
            Log.d("BackendPOST", "Serving cached stream for tmdb=$tmdbId")
            // Still fire download/subtitle in background so the pickers populate
            val base = streamBaseUrl()
            if (base != null) {
                val body = buildBody(tmdbId, mediaType, title, season, episode, imdbId, year)
                async(Dispatchers.IO) {
                    post("$base/api/v1/download", body)?.let { handleDownloads(it, onDownload) }
                }
                async(Dispatchers.IO) {
                    val subBody = buildSubtitleBody(tmdbId, mediaType, imdbId, season, episode, languages)
                    post("$base/api/v1/subtitles", subBody)?.let { handleSubtitles(it, onSubtitle) }
                }
            }
            return@coroutineScope cached
        }

        // ── 2. No cache — fetch from backend ──────────────────────────────
        val base = streamBaseUrl() ?: run {
            Log.e("BackendPOST", "No backend URL configured")
            lastErrorType = StreamError.TRANSIENT
            return@coroutineScope null
        }

        val body = buildBody(tmdbId, mediaType, title, season, episode, imdbId, year)

        // Fire all three in parallel
        val streamDeferred = async(Dispatchers.IO) {
            postClassified("$base/api/v1/streams", body)
        }
        val downloadDeferred = async(Dispatchers.IO) {
            post("$base/api/v1/download", body)
        }
        val subtitleDeferred = async(Dispatchers.IO) {
            val subBody = buildSubtitleBody(tmdbId, mediaType, imdbId, season, episode, languages)
            post("$base/api/v1/subtitles", subBody)
        }

        // ── Await streams first — this is the critical path ───────────────
        val streamPostResult = streamDeferred.await()

        if (streamPostResult is PostResult.Err) {
            lastErrorType = streamPostResult.error
            Log.w("BackendPOST", "Stream fetch failed: ${streamPostResult.error}")
            // Drain the others so we don't leak coroutines
            downloadDeferred.await()?.let { handleDownloads(it, onDownload) }
            subtitleDeferred.await()?.let { handleSubtitles(it, onSubtitle) }
            return@coroutineScope null
        }

        val streamsJson = (streamPostResult as PostResult.Ok).json
        if (!streamsJson.optBoolean("ok", false)) {
            Log.w("BackendPOST", "Streams response: ok=false")
            lastErrorType = StreamError.NOT_FOUND
            downloadDeferred.await()?.let { handleDownloads(it, onDownload) }
            subtitleDeferred.await()?.let { handleSubtitles(it, onSubtitle) }
            return@coroutineScope null
        }

        // Parse the best (first) stream
        val bestJson = streamsJson.optJSONObject("stream")
        val best     = bestJson?.let { parseStreamEntry(it) }
        if (best == null) {
            lastErrorType = StreamError.NOT_FOUND
            downloadDeferred.await()?.let { handleDownloads(it, onDownload) }
            subtitleDeferred.await()?.let { handleSubtitles(it, onSubtitle) }
            return@coroutineScope null
        }

        // Build full fallback ladder from "streams" array
        val fallbackLadder = mutableListOf<QualityTrack>()
        val streamsArr = streamsJson.optJSONArray("streams")
        if (streamsArr != null) {
            for (i in 0 until streamsArr.length()) {
                val e = parseStreamEntry(streamsArr.getJSONObject(i)) ?: continue
                val track = QualityTrack(label = e.quality, url = e.url)
                if (fallbackLadder.none { it.url == track.url }) {
                    fallbackLadder.add(track)
                    if (e.url != best.url) onStream(track)
                }
            }
        }
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

        // ── 3. Store in cache ──────────────────────────────────────────────
        urlCache.put(tmdbId, cacheTypeString(mediaType), season, episode, result)

        // Drain downloads and subtitles in background — do NOT block playback
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
            // Use the SAME label format as resolveDownloadLinks():
            // "1080p" for English, "1080p · Hindi" for others.
            // Previously this was "${e.quality} (${e.language})" which
            // caused a label mismatch in resolveIfNeeded() → wrong URL.
            val label = if (e.language.isNotBlank()
                && !e.language.equals("English", ignoreCase = true)
            ) {
                "${e.quality} · ${e.language}"
            } else {
                e.quality
            }
            onDownload(QualityTrack(
                label              = label,
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

    private fun buildSubtitleBody(
        tmdbId:    Int,
        mediaType: MediaType,
        imdbId:    String?,
        season:    Int,
        episode:   Int,
        languages: List<String>,
    ) = JSONObject().apply {
        put("tmdb_id", tmdbId)
        put("type", if (mediaType == MediaType.MOVIE) "movie" else "tv")
        if (imdbId != null) put("imdb_id", imdbId)
        if (season  > 0)   put("season", season)
        if (episode > 0)   put("episode", episode)
        put("languages", JSONArray(languages))
    }

    // ── resolveDownloadLinks — used by DetailScreen + DownloadService ─────────

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

        data class RawEntry(val quality: String, val language: String, val url: String, val sizeBytes: Long)
        val raw = mutableListOf<RawEntry>()
        for (i in 0 until arr.length()) {
            val e = parseDownloadEntry(arr.getJSONObject(i)) ?: continue
            // parseDownloadEntry returns "Auto" when the backend sends quality=null.
            // Try to salvage a real label from the URL path before giving up.
            val quality = if (e.quality == "Auto") inferQualityFromUrl(e.url) else e.quality
            raw.add(RawEntry(quality, e.language, e.url, e.sizeBytes))
        }

        val qualityLanguageCounts = raw.groupBy { it.quality }.mapValues { (_, v) ->
            v.map { it.language }.toSet().size
        }

        val out = mutableListOf<QualityTrack>()
        val seen = mutableSetOf<String>()
        for (e in raw) {
            val key = "${e.quality}|${e.language}"
            if (key in seen) continue
            seen.add(key)
            val label = if ((qualityLanguageCounts[e.quality] ?: 1) > 1 && e.language != "English") {
                "${e.quality} · ${e.language}"
            } else {
                e.quality
            }
            out.add(QualityTrack(label = label, url = e.url, estimatedSizeBytes = e.sizeBytes))
        }

        val resOrder = listOf("2160p", "1080p", "720p", "480p", "360p", "240p", "Auto")
        out.sortWith(compareBy(
            { resOrder.indexOf(it.label.substringBefore(" ·")).takeIf { idx -> idx >= 0 } ?: 99 },
            { if (it.label.contains("·")) 1 else 0 }
        ))
        out
    }

    /**
     * Last-resort quality inference from the download URL when the backend
     * sends quality=null. Checks (in order):
     *  1. Standard resolution tokens in the URL path  (e.g. "1080p", "720p")
     *  2. Raw pixel heights  (e.g. "1920x1080", "1280x720", or standalone "1080")
     * Returns a standard label like "1080p", or "Auto" if nothing is found.
     */
    private fun inferQualityFromUrl(url: String): String {
        val lower = url.lowercase()
        // 1. Explicit token: 2160p / 4k / 1080p / 720p / 480p / 360p / 240p
        val tokenMatch = Regex("""(2160p|4k|1080p|720p|480p|360p|240p)""").find(lower)
        if (tokenMatch != null) {
            return when (tokenMatch.value) {
                "4k"    -> "2160p"
                else    -> tokenMatch.value
            }
        }
        // 2. WxH resolution string (e.g. "1920x1080") — use the height component
        val dimMatch = Regex("""\d{3,4}[x×]\d{3,4}""").find(lower)
        if (dimMatch != null) {
            val height = dimMatch.value.substringAfterLast(Regex("[x×]").find(dimMatch.value)!!.value)
                .toIntOrNull() ?: dimMatch.value.split(Regex("[x×]")).lastOrNull()?.toIntOrNull() ?: 0
            return when {
                height >= 2000 -> "2160p"
                height >= 900  -> "1080p"
                height >= 600  -> "720p"
                height >= 420  -> "480p"
                height >= 300  -> "360p"
                height >  0    -> "240p"
                else           -> "Auto"
            }
        }
        // 3. Standalone height number in path segment (e.g. ".../1080/...")
        val heightMatch = Regex("""[/_-](2160|1080|720|480|360|240)[/_.-]""").find(lower)
        if (heightMatch != null) {
            val h = heightMatch.groupValues[1].toIntOrNull() ?: 0
            return if (h > 0) "${h}p" else "Auto"
        }
        return "Auto"
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
        val body = buildSubtitleBody(tmdbId, mediaType, null, season, episode, languages)
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
