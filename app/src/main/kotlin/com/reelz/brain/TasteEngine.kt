package com.reelz.brain

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.reelz.data.model.Media
import com.reelz.data.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  TasteEngine
 *
 *  The single source of truth for user taste. Injected everywhere that
 *  needs to either READ taste (for ranking) or WRITE taste (for tracking).
 *
 *  Responsibilities:
 *  1. Store/load profile from encrypted SharedPreferences (local-first)
 *  2. Process every user interaction and update the relevant dimensions
 *  3. Apply weekly interest decay
 *  4. Expose a reactive StateFlow so UI can observe taste changes
 *  5. Signal when a sync to backend is needed (dirty flag)
 *
 *  What it does NOT do:
 *  - Hit the network (SyncWorker handles that)
 *  - Block the UI thread (all writes are async in the engine's own scope)
 *  - Store watch history (that stays in Room / MediaRepository)
 * ════════════════════════════════════════════════════════════════════════════
 */
@Singleton
class TasteEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Local encrypted storage ───────────────────────────────────────────────
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "reelz_taste_profile",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            // Fallback to regular prefs if encryption fails (unlikely on modern Android)
            context.getSharedPreferences("reelz_taste_profile_fallback", Context.MODE_PRIVATE)
        }
    }

    private val PREF_PROFILE   = "taste_profile_v1"
    private val PREF_DIRTY     = "taste_dirty"
    private val PREF_LAST_SYNC = "taste_last_sync"

    // ── Reactive profile state ────────────────────────────────────────────────
    private val _profile = MutableStateFlow(loadProfile())
    val profile: StateFlow<UserTasteProfile> = _profile.asStateFlow()

    // Tracks whether local profile has unsync'd changes
    var isDirty: Boolean
        get() = prefs.getBoolean(PREF_DIRTY, false)
        private set(v) = prefs.edit().putBoolean(PREF_DIRTY, v).apply()

    // ── Load / Save ───────────────────────────────────────────────────────────
    private fun loadProfile(): UserTasteProfile {
        val json = prefs.getString(PREF_PROFILE, null)
        val profile = if (json != null) UserTasteProfile.fromJson(json) else null
        val loaded = profile ?: UserTasteProfile.blank()
        // Apply decay if needed on load
        return maybeDecay(loaded)
    }

    private fun save(profile: UserTasteProfile) {
        prefs.edit()
            .putString(PREF_PROFILE, profile.toJson())
            .apply()
        _profile.value = profile
        isDirty = true
    }

    // ── Apply loaded profile (used after sync download from backend) ───────────
    fun applyRemoteProfile(json: String) {
        val remote = UserTasteProfile.fromJson(json) ?: return
        // Merge strategy: take higher effective scores (user may have acted on both devices)
        val local = _profile.value
        val merged = mergeProfiles(local, remote)
        save(merged)
        isDirty = false // Just synced, so clean
    }

    fun markSynced() {
        isDirty = false
        prefs.edit().putLong(PREF_LAST_SYNC, System.currentTimeMillis()).apply()
    }

    fun exportJson(): String = _profile.value.toJson()

    // ── Onboarding — cold start ───────────────────────────────────────────────
    /**
     * Called after user completes the 5-second genre picker on first launch.
     * Seeds the profile with medium confidence so recommendations don't feel cold.
     */
    fun applyOnboardingPicks(genreKeys: List<String>) {
        engineScope.launch {
            val p = _profile.value.copy(isOnboarded = true)
            for (key in genreKeys) {
                // Give a head start: equivalent of watching ~5 things in this genre
                val boosted = p.genres.getOrDefault(key, TasteDimension())
                    .boosted(InteractionScore.WATCH_50_PCT)
                    .boosted(InteractionScore.WATCH_50_PCT)
                    .boosted(InteractionScore.WATCH_50_PCT)
                p.genres[key] = boosted
            }
            save(p.copy(lastUpdatedAt = System.currentTimeMillis()))
        }
    }

    // ── The main interaction processor ────────────────────────────────────────
    /**
     * Call this for EVERY user action. It's cheap — just float arithmetic and
     * a SharedPreferences write (async). The UI never waits for it.
     *
     * @param media     The content the user interacted with
     * @param action    What they did
     * @param watchPct  0f–1f — what fraction of the content they watched (for watch events)
     */
    fun track(media: Media, action: UserAction, watchPct: Float = 0f) {
        engineScope.launch {
            val p = _profile.value
            val score = scoreForAction(action, watchPct)
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timeWindow = TimeWindow.fromHour(currentHour)

            // Determine genre keys
            val genreMap = if (media.mediaType == MediaType.TV) TmdbGenreMap.tvGenres else TmdbGenreMap.movieGenres
            val genreKeys = media.genreIds.mapNotNull { genreMap[it] }.toMutableList()
            val isAnime = TmdbGenreMap.isAnime(media.originalLanguage, media.genreIds)
            if (isAnime) genreKeys += "anime"

            // Language key
            val langKey = LanguageMap.toKey(media.originalLanguage)

            // ── Update genre dimensions ────────────────────────────────────────
            for (key in genreKeys) {
                val current = p.genres.getOrDefault(key, TasteDimension())
                p.genres[key] = current.boosted(score)

                // Update time-of-day pattern (only for positive signals)
                if (score > 0) {
                    val timeKey = "${timeWindow}_${key}"
                    val current = p.timePatterns.getOrDefault(timeKey, 0f)
                    // Time patterns use a simpler EMA (exponential moving average)
                    p.timePatterns[timeKey] = (current * 0.85f + score * 0.15f).coerceIn(0f, 100f)
                }

                // Track completion rate per genre
                if (action == UserAction.WATCH_PROGRESS && watchPct >= 0.9f) {
                    val prev = p.completionRates.getOrDefault(key, 0f)
                    p.completionRates[key] = (prev * 0.9f + 1f * 0.1f).coerceIn(0f, 1f)
                }
            }

            // ── Update language dimension ──────────────────────────────────────
            val currentLang = p.languages.getOrDefault(langKey, TasteDimension())
            p.languages[langKey] = currentLang.boosted(score * 0.7f) // Language has less weight than genre

            // ── Interaction count ──────────────────────────────────────────────
            val updated = p.copy(
                totalInteractions = p.totalInteractions + 1,
                lastUpdatedAt = System.currentTimeMillis(),
            )
            save(updated)
        }
    }

    // ── Score calculation ─────────────────────────────────────────────────────
    private fun scoreForAction(action: UserAction, watchPct: Float): Float = when (action) {
        UserAction.VIEW_DETAIL     -> InteractionScore.VIEW_DETAIL
        UserAction.LIKE            -> InteractionScore.LIKE
        UserAction.SAVE_WATCHLIST  -> InteractionScore.SAVE_WATCHLIST
        UserAction.DOWNLOAD        -> InteractionScore.DOWNLOAD
        UserAction.REMOVE_WATCHLIST -> InteractionScore.REMOVE_WATCHLIST
        UserAction.QUIT_EARLY      -> InteractionScore.QUIT_AFTER_10S
        UserAction.WATCH_PROGRESS  -> when {
            watchPct >= 0.90f -> InteractionScore.WATCH_90_PCT + InteractionScore.COMPLETION_BONUS
            watchPct >= 0.50f -> InteractionScore.WATCH_50_PCT
            watchPct >= 0.10f -> InteractionScore.WATCH_30_SEC
            else              -> 0f
        }
    }

    // ── Weekly interest decay ─────────────────────────────────────────────────
    /**
     * Decays all scores by 2% every 7 days.
     * Effect: watching anime 6 months ago won't dominate over what you watch today.
     * Called on app load — cheap, runs in <1ms.
     */
    private fun maybeDecay(profile: UserTasteProfile): UserTasteProfile {
        val now = System.currentTimeMillis()
        val daysSinceDecay = (now - profile.lastDecayAt) / (1000L * 60 * 60 * 24)
        if (daysSinceDecay < 7) return profile

        val weeksElapsed = (daysSinceDecay / 7).toInt().coerceAtMost(52)
        val decayFactor = 0.98f.pow(weeksElapsed)

        profile.genres.replaceAll { _, v -> v.decayed(decayFactor) }
        profile.languages.replaceAll { _, v -> v.decayed(decayFactor) }
        profile.timePatterns.replaceAll { _, v -> (v * decayFactor).coerceIn(0f, 100f) }

        return profile.copy(
            lastDecayAt = now,
            lastUpdatedAt = now,
        )
    }

    // ── Profile merge (local + remote) ────────────────────────────────────────
    // Called when user logs in and downloads their synced profile.
    // Strategy: take max effective score per dimension.
    // This handles the case where they used the app on another device.
    private fun mergeProfiles(local: UserTasteProfile, remote: UserTasteProfile): UserTasteProfile {
        val mergedGenres = mutableMapOf<String, TasteDimension>()
        val allGenreKeys = local.genres.keys + remote.genres.keys
        for (key in allGenreKeys) {
            val l = local.genres[key]
            val r = remote.genres[key]
            mergedGenres[key] = when {
                l == null -> r!!
                r == null -> l
                l.effectiveScore >= r.effectiveScore -> l
                else -> r
            }
        }

        val mergedLangs = mutableMapOf<String, TasteDimension>()
        val allLangKeys = local.languages.keys + remote.languages.keys
        for (key in allLangKeys) {
            val l = local.languages[key]
            val r = remote.languages[key]
            mergedLangs[key] = when {
                l == null -> r!!
                r == null -> l
                l.effectiveScore >= r.effectiveScore -> l
                else -> r
            }
        }

        val mergedTime = mutableMapOf<String, Float>()
        val allTimeKeys = local.timePatterns.keys + remote.timePatterns.keys
        for (key in allTimeKeys) {
            mergedTime[key] = maxOf(
                local.timePatterns.getOrDefault(key, 0f),
                remote.timePatterns.getOrDefault(key, 0f),
            )
        }

        return local.copy(
            genres = mergedGenres,
            languages = mergedLangs,
            timePatterns = mergedTime,
            totalInteractions = maxOf(local.totalInteractions, remote.totalInteractions),
            isOnboarded = local.isOnboarded || remote.isOnboarded,
        )
    }

    // ── Convenience: rank a list of media items ───────────────────────────────
    fun rankMedia(items: List<Media>): List<Media> {
        val p = _profile.value
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (p.totalInteractions < 5) return items // No data yet, don't reorder

        return items.sortedByDescending { media ->
            p.scoreMedia(
                genreIds = media.genreIds,
                originalLanguage = media.originalLanguage,
                mediaType = media.mediaType.name,
                isAnime = TmdbGenreMap.isAnime(media.originalLanguage, media.genreIds),
                currentHour = hour,
                voteAverage = media.voteAverage,
                popularity = media.popularity,
            )
        }
    }

    // ── Taste card data for Profile screen ────────────────────────────────────
    fun getTasteCard(): TasteCard {
        val p = _profile.value
        return TasteCard(
            topGenres = p.topGenres(5),
            topLanguages = p.topLanguages(3),
            totalWatched = p.totalInteractions,
            isOnboarded = p.isOnboarded,
            dominantMood = inferMood(p),
        )
    }

    /**
     * Infer a human-readable dominant mood from the profile.
     * Used for the "Your Vibe Tonight" section in the feed.
     */
    private fun inferMood(p: UserTasteProfile): String? {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val window = TimeWindow.fromHour(hour)

        // Find best time-pattern match for current time
        val currentWindowEntries = p.timePatterns.entries
            .filter { it.key.startsWith(window) && it.value > 10f }
            .sortedByDescending { it.value }
            .take(1)

        val topTimeGenre = currentWindowEntries.firstOrNull()?.key?.substringAfter("_")

        return topTimeGenre?.let { genreToMood(it) }
    }

    private fun genreToMood(genreKey: String) = when (genreKey) {
        "horror"    -> "😱 Scary tonight"
        "comedy"    -> "😂 Need a laugh"
        "action"    -> "😎 Feeling badass"
        "romance"   -> "🥰 Romantic mood"
        "thriller"  -> "😬 Edge of your seat"
        "scifi"     -> "🧠 Mind-bending"
        "drama"     -> "😭 Emotional"
        "anime"     -> "⚔️ Anime night"
        "crime"     -> "🕵️ Crime time"
        "documentary" -> "🌍 Learning something"
        else        -> null
    }
}

// ── Private extension ─────────────────────────────────────────────────────────
private fun Float.pow(n: Int): Float {
    var result = 1f
    repeat(n) { result *= this }
    return result
}

// ── User actions ──────────────────────────────────────────────────────────────
enum class UserAction {
    VIEW_DETAIL,
    WATCH_PROGRESS,  // Use watchPct param to specify 0–1
    LIKE,
    SAVE_WATCHLIST,
    DOWNLOAD,
    REMOVE_WATCHLIST,
    QUIT_EARLY,
}

// ── Taste card (for Profile screen display) ───────────────────────────────────
data class TasteCard(
    val topGenres: List<Pair<String, Float>>,
    val topLanguages: List<Pair<String, Float>>,
    val totalWatched: Int,
    val isOnboarded: Boolean,
    val dominantMood: String?,
)
