package com.reelz.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * DirectScanner — for sources where requiresJs = false.
 * Makes a plain OkHttp GET, scans the response body for .m3u8 / .mp4 URLs.
 */
class DirectScanner @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val M3U8_RE = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""")
    private val MP4_RE  = Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""")

    suspend fun scan(embedUrl: String, source: StreamSource): StreamResult? =
        withContext(Dispatchers.IO) {
            try {
                val reqBuilder = Request.Builder().url(embedUrl)
                source.headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
                if (source.referer.isNotBlank()) reqBuilder.addHeader("Referer", source.referer)
                if (source.origin.isNotBlank())  reqBuilder.addHeader("Origin",  source.origin)

                val response = client.newCall(reqBuilder.build()).execute()
                val body = response.body?.string() ?: return@withContext null
                response.close()

                // Prefer m3u8 over mp4
                val m3u8 = M3U8_RE.find(body)?.value
                val mp4  = MP4_RE.find(body)?.value
                val url  = m3u8 ?: mp4 ?: return@withContext null

                StreamResult(
                    url        = url,
                    isHls      = url.contains(".m3u8"),
                    headers    = source.headers + buildMap {
                        if (source.referer.isNotBlank()) put("Referer", source.referer)
                        if (source.origin.isNotBlank())  put("Origin",  source.origin)
                    },
                    referer    = source.referer,
                    origin     = source.origin,
                    sourceName = source.name,
                )
            } catch (e: Exception) {
                null
            }
        }
}
