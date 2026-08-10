package com.axio.reelz.scanner

import com.axio.reelz.data.model.StreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * PERFORMANCE FIX: previously called response.body?.string(), which reads
 * the ENTIRE response body into memory before any regex match is attempted
 * — even though the stream URL is almost always found in the first few
 * hundred KB (or fails entirely, in which case reading the rest is pure
 * waste). Embed pages can be 200KB-1MB+ of bloated inline JS/HTML.
 *
 * Now reads the body as a stream via okio's BufferedSource and
 * regex-matches incrementally, stopping the instant a match is found — for
 * a fast-matching page this can turn a multi-hundred-KB full download into
 * a read of only a few KB. Also enforces a hard byte cap so a source that
 * genuinely has no matchable URL doesn't tie up the connection reading a
 * multi-MB page to no benefit — bails early and lets the WebView fallback
 * (or next source) take over instead.
 */
class DirectScanner @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Matches .m3u8 / .mp4 URLs, stopping at whitespace/quotes/angle-brackets/backslash
    private val M3U8 = Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""")
    private val MP4  = Regex("""https?://[^\s"'\\<>]+\.mp4[^\s"'\\<>]*""")

    companion object {
        // Read in ~8KB chunks — small enough to check frequently for an
        // early match, large enough to avoid excessive syscall overhead.
        private const val CHUNK_BYTES = 8L * 1024

        // Hard cap: if no match is found within this many bytes, give up
        // and let the WebView fallback (or next source) take over instead
        // of reading a huge page for no benefit. Embed pages that DO
        // expose a plain-text stream URL reveal it well within this.
        private const val MAX_BYTES = 512L * 1024

        // How much trailing text to retain across chunk boundaries so a
        // URL split across two reads still matches (URLs with query
        // strings can run a few hundred chars; 1KB of overlap is ample).
        private const val TAIL_KEEP = 1024
    }

    suspend fun scan(embedUrl: String, source: StreamSource): StreamResult? =
        withContext(Dispatchers.IO) {
            var response: okhttp3.Response? = null
            try {
                val req = Request.Builder()
                    .url(embedUrl)
                    .apply {
                        source.headers.forEach { (k, v) -> addHeader(k, v) }
                        if (source.referer.isNotBlank()) addHeader("Referer", source.referer)
                        if (source.origin.isNotBlank())  addHeader("Origin",  source.origin)
                    }
                    .build()

                response = client.newCall(req).execute()
                val body = response.body ?: return@withContext null
                val src = body.source()

                val sb = StringBuilder()
                var totalRead = 0L
                var found: String? = null

                while (found == null && totalRead < MAX_BYTES) {
                    // request() returns true if more data is available (or
                    // becomes available); false at true end-of-stream.
                    val hasMore = src.request(CHUNK_BYTES)
                    val available = minOf(src.buffer.size, CHUNK_BYTES)
                    if (available <= 0L) break

                    val chunk = src.buffer.readUtf8(available)
                    totalRead += available
                    sb.append(chunk)

                    found = M3U8.find(sb)?.value ?: MP4.find(sb)?.value

                    // Bound buffer growth while preserving enough tail
                    // context to catch a boundary-straddling match.
                    if (sb.length > TAIL_KEEP * 2) {
                        sb.delete(0, sb.length - TAIL_KEEP)
                    }

                    if (!hasMore) break
                }

                val url = found ?: return@withContext null

                StreamResult(
                    url        = url,
                    isHls      = url.contains(".m3u8", ignoreCase = true),
                    headers    = source.headers + buildMap {
                        if (source.referer.isNotBlank()) put("Referer", source.referer)
                        if (source.origin.isNotBlank())  put("Origin",  source.origin)
                    },
                    referer    = source.referer,
                    origin     = source.origin,
                    sourceName = source.name,
                )
            } catch (_: Exception) {
                null
            } finally {
                response?.close()
            }
        }
}
