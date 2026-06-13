package com.reelz.brain

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  REELZ BRAIN — UserTasteProfile
 *  A lightweight, psychology-informed recommendation engine.
 *  100% local. Syncs silently when logged in.
 *  No ML model. No server inference. No heavy dependencies.
 *
 *  Design principles:
 *  - Confidence-weighted scores (100 watches ≠ 2 watches)
 *  - Time-of-day mood patterns (horror at 11 PM is different from horror at 9 AM)
 *  - Weekly interest decay (prevents taste lock-in from months ago)
 *  - Cold start solved by a 5-second onboarding taste picker
 *  - All floats, not integers — precision matters for ranking
 * ════════════════════════════════════════════════════════════════════════════
 */

// ── TMDB genre ID → readable key mapping ─────────────────────────────────────
// These are TMDB's real genre IDs. Used to map API data to our profile keys.
object TmdbGenreMap {
    val movieGenres = mapOf(
        28    to "action",
        12    to "adventure",
        16    to "animation",
        35    to "comedy",
        80    to "crime",
        99    to "documentary",
        18    to "drama",
        10751 to "family",
        14    to "fantasy",
        36    to "history",
        27    to "horror",
        10402 to "music",
        9648  to "mystery",
        10749 to "romance",
        878   to "scifi",
        10770 to "tv_movie",
        53    to "thriller",
        10752 to "war",
        37    to "western",
    )
    val tvGenres = mapOf(
        10759 to "action",
        16    to "animation",
        35    to "comedy",
        80    to "crime",
        99    to "documentary",
        18    to "drama",
        10751 to "family",
        10762 to "kids",
        9648  to "mystery",
        10763 to "news",
        10764 to "reality",
        10765 to "scifi",
        10766 to "soap",
        10767 to "talk",
        10768 to "war",
        37    to "western",
    )
    // Anime is identified by: originalLanguage == "ja" AND genre 16 (animation)
    fun isAnime(originalLanguage: String, genreIds: List<Int>) =
        originalLanguage == "ja" && genreIds.contains(16)
}

// ── Language → profile key mapping ───────────────────────────────────────────
object LanguageMap {
    fun toKey(lang: String) = when (lang) {
        "ja"   -> "lang_japanese"
        "ko"   -> "lang_korean"
        "hi"   -> "lang_hindi"
        "ta"   -> "lang_tamil"
        "te"   -> "lang_telugu"
        "zh"   -> "lang_chinese"
        "fr"   -> "lang_french"
        "es"   -> "lang_spanish"
        "de"   -> "lang_german"
        "pt"   -> "lang_portuguese"
        "tr"   -> "lang_turkish"
        "ar"   -> "lang_arabic"
        "th"   -> "lang_thai"
        "en"   -> "lang_english"
        else   -> "lang_other"
    }
}

// ── Hour of day → session label mapping ──────────────────────────────────────
// Divides the day into 4 psychological mood windows
object TimeWindow {
    fun fromHour(hour: Int) = when (hour) {
        in 5..11  -> "morning"   // 5 AM–11 AM: lighter mood, comedy/family
        in 12..17 -> "afternoon" // 12 PM–5 PM: casual browsing
        in 18..22 -> "evening"   // 6 PM–10 PM: prime time
        else       -> "night"    // 11 PM–4 AM: dark/intense content
    }
}

// ── Interaction scores (the weights you assign to each action) ─────────────────
// These values are carefully calibrated:
// - Signals with high intent (watch 90%) are worth much more than casual taps
// - Negative signals are mild — don't punish curiosity
// - Download signals intent to revisit = very high confidence
object InteractionScore {
    const val VIEW_DETAIL    =  1.0f  // Low intent — browsing
    const val WATCH_30_SEC   =  3.0f  // Decided to try
    const val WATCH_50_PCT   = 10.0f  // Genuinely interested
    const val WATCH_90_PCT   = 20.0f  // Loved it (strong signal)
    const val LIKE            = 25.0f  // Explicit appreciation
    const val SAVE_WATCHLIST  = 15.0f  // Future intent
    const val DOWNLOAD        = 18.0f  // High commitment
    const val REMOVE_WATCHLIST = -8.0f // Changed mind (mild negative)
    const val QUIT_AFTER_10S  = -4.0f  // Bounced fast (mild negative)
    // Completion bonus: watch 90% of something → extra genre confidence
    const val COMPLETION_BONUS = 5.0f
}

// ── A single taste dimension (genre, language, theme, etc.) ──────────────────
data class TasteDimension(
    val score: Float = 0f,       // Raw accumulated interest score
    val confidence: Float = 0f,  // How many interactions back this up (0–100)
    val interactionCount: Int = 0,
) {
    // Confidence-weighted effective score — what ranking actually uses
    val effectiveScore: Float get() {
        val confFactor = (confidence / 100f).coerceIn(0f, 1f)
        // Blend: 30% raw score + 70% confidence-adjusted
        // New users' scores don't dominate. Veteran users' scores are trusted more.
        return score * (0.3f + 0.7f * confFactor)
    }

    fun boosted(points: Float, maxScore: Float = 200f): TasteDimension {
        val newScore = (score + points).coerceIn(0f, maxScore)
        val newCount = interactionCount + 1
        // Confidence grows logarithmically — 1 interaction is not 1/100 of 100 interactions
        // It takes ~20 interactions to reach 80% confidence, ~50 for 95%
        val newConf = (100f * (1f - kotlin.math.exp(-newCount / 15f))).coerceIn(0f, 100f)
        return TasteDimension(newScore, newConf, newCount)
    }

    fun decayed(factor: Float = 0.98f): TasteDimension {
        // Only decay score, not confidence — confidence reflects history, not recency
        return copy(score = score * factor)
    }
}

// ── Time-of-day genre affinity (e.g., "I watch horror at night") ──────────────
// Key format: "{timeWindow}_{genreKey}" e.g., "night_horror", "morning_comedy"
typealias TimeGenreAffinities = MutableMap<String, Float>

// ── The full user taste profile ───────────────────────────────────────────────
data class UserTasteProfile(
    // Core genre interests
    val genres: MutableMap<String, TasteDimension> = mutableMapOf(),

    // Language preferences (which original languages user watches)
    val languages: MutableMap<String, TasteDimension> = mutableMapOf(),

    // Actor/director weights (key = "actor_{tmdbId}" or "director_{tmdbId}")
    // Kept lightweight — only top 30 are tracked
    val people: MutableMap<String, TasteDimension> = mutableMapOf(),

    // Time-based viewing patterns
    // Key: "{timeWindow}_{genreKey}", Value: score 0–100
    val timePatterns: TimeGenreAffinities = mutableMapOf(),

    // Completion rate per genre (how often user finishes content in this genre)
    // High completion = genuine love, not just curiosity
    val completionRates: MutableMap<String, Float> = mutableMapOf(),

    // Metadata
    val profileVersion: Int = 1,
    val totalInteractions: Int = 0,
    val lastDecayAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val isOnboarded: Boolean = false, // Whether user completed cold-start picker
) {

    // ── Top interests for UI display (Spotify-style taste card) ───────────────
    fun topGenres(n: Int = 5): List<Pair<String, Float>> =
        genres.entries
            .map { (k, v) -> k to v.effectiveScore }
            .filter { it.second > 2f }
            .sortedByDescending { it.second }
            .take(n)

    fun topLanguages(n: Int = 3): List<Pair<String, Float>> =
        languages.entries
            .map { (k, v) -> k to v.effectiveScore }
            .filter { it.second > 2f }
            .sortedByDescending { it.second }
            .take(n)

    // ── Compute a relevance score for a piece of media ────────────────────────
    // This is what powers the feed ranking. Higher = show earlier/more prominently.
    fun scoreMedia(
        genreIds: List<Int>,
        originalLanguage: String,
        mediaType: String, // "MOVIE" or "TV"
        isAnime: Boolean,
        currentHour: Int,
        voteAverage: Double,
        popularity: Double,
    ): Float {
        var total = 0f
        val timeWindow = TimeWindow.fromHour(currentHour)

        // Genre contribution (40% of total weight)
        val genreMap = if (mediaType == "TV") TmdbGenreMap.tvGenres else TmdbGenreMap.movieGenres
        val genreKeys = genreIds.mapNotNull { genreMap[it] }.toMutableList()
        if (isAnime) genreKeys += "anime"

        for (key in genreKeys) {
            val dim = genres[key]
            if (dim != null) {
                total += dim.effectiveScore * 0.40f

                // Time-of-day bonus: if user historically watches this genre at this time
                val timeKey = "${timeWindow}_${key}"
                total += (timePatterns[timeKey] ?: 0f) * 0.15f
            }
        }

        // Language contribution (25% of total weight)
        val langKey = LanguageMap.toKey(originalLanguage)
        val langDim = languages[langKey]
        if (langDim != null) total += langDim.effectiveScore * 0.25f

        // Completion rate bonus (10% weight) — genres user finishes = loves
        for (key in genreKeys) {
            total += (completionRates[key] ?: 0f) * 10f * 0.10f
        }

        // TMDB quality signals (10% weight) — don't recommend garbage even if it matches
        val qualityScore = ((voteAverage / 10.0) * 0.6 + (minOf(popularity, 1000.0) / 1000.0) * 0.4).toFloat()
        total += qualityScore * 10f * 0.10f

        // Cold start: if no profile yet, fall back purely to TMDB signals
        if (totalInteractions < 5) {
            return qualityScore * 10f
        }

        return total.coerceIn(0f, 100f)
    }

    // ── Serialization ─────────────────────────────────────────────────────────
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): UserTasteProfile? = try {
            Gson().fromJson(json, UserTasteProfile::class.java)
        } catch (_: Exception) { null }

        // A blank profile with slight general boosts from cold start picks
        fun blank() = UserTasteProfile(isOnboarded = false)
    }
}
