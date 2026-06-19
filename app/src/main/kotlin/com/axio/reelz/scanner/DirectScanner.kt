package com.axio.reelz.scanner

import com.axio.reelz.data.model.StreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class DirectScanner @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Matches .m3u8 and .mp4 with optional query strings
    private val M3U8 = Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""")
    private val MP4  = Regex("""https?://[^\s"'\\<>]+\.mp4[^\s"'\\<>]*""")

    suspend fun scan(embedUrl: String, source: StreamSource): StreamResult? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(embedUrl)
                    .apply {
                        source.headers.forEach { (k, v) -> addHeader(k, v) }
                        if (source.referer.isNotBlank()) addHeader("Referer", source.referer)
                        if (source.origin.isNotBlank())  addHeader("Origin",  source.origin)
                    }
                    .build()

                val response = client.newCall(req).execute()
                val body = response.body?.string() ?: return@withContext null
                response.close()

                val m3u8 = M3U8.find(body)?.value
                val mp4  = MP4.find(body)?.value
                val url  = m3u8 ?: mp4 ?: return@withContext null

                StreamResult(
                    url        = url,
                    isHls      = url.contains(".m3u8", true),
                    headers    = source.headers + buildMap {
                        if (source.referer.isNotBlank()) put("Referer", source.referer)
                        if (source.origin.isNotBlank())  put("Origin",  source.origin)
                    },
                    referer    = source.referer,
                    origin     = source.origin,
                    sourceName = source.name,
                )
            } catch (_: Exception) { null }
        }
}
