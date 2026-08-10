package com.axio.reelz.data.repository

import android.util.Log
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.data.model.Subtitle
import com.axio.reelz.data.remote.api.OsDownloadRequest
import com.axio.reelz.data.remote.api.OpenSubtitlesApi

private const val TAG = "OpenSubtitlesRepo"

// Language code → display label map (extend as needed)
private val LANG_LABELS = mapOf(
    "en" to "English",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "pt" to "Portuguese",
    "it" to "Italian",
    "ja" to "Japanese",
    "ko" to "Korean",
    "zh" to "Chinese",
    "ar" to "Arabic",
    "hi" to "Hindi",
    "ru" to "Russian",
    "nl" to "Dutch",
    "pl" to "Polish",
    "tr" to "Turkish",
    "sv" to "Swedish",
    "da" to "Danish",
    "nb" to "Norwegian",
    "fi" to "Finnish",
)

// AFTER
class OpenSubtitlesRepository(
    private val api: OpenSubtitlesApi,
    private val apiKey: String,
    private val userAgent: String,
) {

    /**
     * Search for subtitles using TMDB id.
     * Returns a list of [Subtitle] objects ready to be loaded into the player.
     *
     * For movies:  pass season=0, episode=0
     * For TV:      pass actual season and episode numbers
     *
     * Each returned subtitle has a DIRECT download URL (expires after ~24h from OpenSubtitles).
     * Only the best subtitle per language is returned (sorted by download_count desc).
     *
     * @param preferredLanguages  ISO 639-1 codes to filter by, e.g. listOf("en","fr").
     *                            Pass empty list to get all available languages.
     */
    suspend fun fetchSubtitles(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        preferredLanguages: List<String> = listOf("en"),
    ): List<Subtitle> {
        if (apiKey.isBlank()) {
            Log.w(TAG, "OpenSubtitles API key not configured — skipping subtitle fetch")
            return emptyList()
        }

        return try {
            val type = if (mediaType == MediaType.MOVIE) "movie" else "episode"
            val langParam = preferredLanguages.takeIf { it.isNotEmpty() }?.joinToString(",")

            val response = api.searchSubtitles(
                apiKey       = apiKey,
                userAgent    = userAgent,
                tmdbId       = tmdbId,
                type         = type,
                languages    = langParam,
                season       = if (mediaType == MediaType.TV) season else null,
                episode      = if (mediaType == MediaType.TV) episode else null,
            )

            Log.d(TAG, "Found ${response.total_count} subtitle(s) for tmdb=$tmdbId type=$type")

            // Group by language, keep the best (highest download_count) per language
            val bestPerLanguage = response.data
                .filter { it.attributes.files.isNotEmpty() }
                .filter { !it.attributes.machine_translated }  // skip machine-translated by default
                .groupBy { it.attributes.language }
                .mapValues { (_, items) -> items.maxByOrNull { it.attributes.download_count }!! }

            // Resolve download URLs (costs 1 credit each — only resolve what we need)
            bestPerLanguage.values.mapNotNull { item ->
                val fileId = item.attributes.files.first().file_id
                val lang   = item.attributes.language
                try {
                    val dlResponse = api.requestDownload(
                        apiKey    = apiKey,
                        userAgent = userAgent,
                        body      = OsDownloadRequest(file_id = fileId),
                    )
                    if (dlResponse.link.isNotBlank()) {
                        Subtitle(
                            url      = dlResponse.link,
                            language = lang,
                            label    = LANG_LABELS[lang] ?: lang.uppercase(),
                        )
                    } else {
                        Log.w(TAG, "Empty download link for lang=$lang fileId=$fileId")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get download URL for lang=$lang: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Subtitle search failed for tmdb=$tmdbId: ${e.message}")
            emptyList()
        }
    }
}
