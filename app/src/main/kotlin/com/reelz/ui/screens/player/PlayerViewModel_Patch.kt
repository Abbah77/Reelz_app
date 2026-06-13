package com.reelz.brain

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  PlayerViewModel — TasteEngine integration patch
 *
 *  This file shows EXACTLY what to add/change in PlayerViewModel.kt.
 *  Don't replace the whole file — just apply these diffs.
 * ════════════════════════════════════════════════════════════════════════════
 */

/**
 * ── STEP 1: Add TasteEngine to PlayerViewModel constructor ──────────────────
 *
 * Change:
 *   class PlayerViewModel @Inject constructor(
 *       @ApplicationContext private val appContext: Context,
 *       private val engine: StreamEngine,
 *       private val repo: MediaRepository,
 *       ...
 *   ) : ViewModel() {
 *
 * To:
 *   class PlayerViewModel @Inject constructor(
 *       @ApplicationContext private val appContext: Context,
 *       private val engine: StreamEngine,
 *       private val repo: MediaRepository,
 *       private val tasteEngine: TasteEngine,   // ← ADD THIS
 *       ...
 *   ) : ViewModel() {
 */

/**
 * ── STEP 2: Add these fields to PlayerViewModel ──────────────────────────────
 *
 * Paste after the existing field declarations (after `private val _ui = ...`):
 */
/*
    // Taste tracking fields
    private var currentTmdbId: Int = 0
    private var currentGenreIds: List<Int> = emptyList()
    private var currentLanguage: String = "en"
    private var currentMediaType: com.reelz.data.model.MediaType = com.reelz.data.model.MediaType.MOVIE
    private var watchStartMs: Long = 0L
    private var totalDurationMs: Long = 0L
    private var hasSentEarlyQuit: Boolean = false
    private var hasSent30SecSignal: Boolean = false
    private var hasSent50PctSignal: Boolean = false
    private var hasSent90PctSignal: Boolean = false
*/

/**
 * ── STEP 3: Add this function to PlayerViewModel ─────────────────────────────
 *
 * Call this from launchStream() after you know the media details:
 * initTasteTracking(tmdbId, genreIds, originalLanguage, mediaType)
 */
/*
    fun initTasteTracking(
        tmdbId: Int,
        genreIds: List<Int>,
        originalLanguage: String,
        mediaType: com.reelz.data.model.MediaType,
    ) {
        currentTmdbId = tmdbId
        currentGenreIds = genreIds
        currentLanguage = originalLanguage
        currentMediaType = mediaType
        watchStartMs = System.currentTimeMillis()
        hasSentEarlyQuit = false
        hasSent30SecSignal = false
        hasSent50PctSignal = false
        hasSent90PctSignal = false
    }

    // Helper to create a lightweight Media stub for taste tracking
    private fun trackingMedia() = com.reelz.data.model.Media(
        id = currentTmdbId,
        tmdbId = currentTmdbId,
        title = _ui.value.title,
        overview = "",
        posterPath = null,
        backdropPath = null,
        releaseDate = null,
        voteAverage = 0.0,
        voteCount = 0,
        popularity = 0.0,
        genreIds = currentGenreIds,
        mediaType = currentMediaType,
        originalLanguage = currentLanguage,
    )
*/

/**
 * ── STEP 4: Add this function to PlayerViewModel ─────────────────────────────
 *
 * Call this from your ExoPlayer progress listener (the periodic position update).
 * You already have something like this in PlayerActivity's player listener.
 */
/*
    fun onPlaybackProgress(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || currentTmdbId == 0) return
        totalDurationMs = durationMs
        val pct = positionMs.toFloat() / durationMs

        // Early quit detection: stopped within 10 seconds
        if (!hasSentEarlyQuit && positionMs < 10_000L && durationMs > 60_000L) {
            // Only send quit signal if they didn't continue watching
            // (handled in onPlayerStopped)
        }

        // 30-second signal
        if (!hasSent30SecSignal && positionMs >= 30_000L) {
            hasSent30SecSignal = true
            tasteEngine.track(trackingMedia(), UserAction.WATCH_PROGRESS, 0.10f)
        }

        // 50% signal
        if (!hasSent50PctSignal && pct >= 0.50f) {
            hasSent50PctSignal = true
            tasteEngine.track(trackingMedia(), UserAction.WATCH_PROGRESS, 0.50f)
        }

        // 90% signal (basically finished)
        if (!hasSent90PctSignal && pct >= 0.90f) {
            hasSent90PctSignal = true
            tasteEngine.track(trackingMedia(), UserAction.WATCH_PROGRESS, 0.90f)
        }
    }

    fun onPlayerStopped(positionMs: Long, durationMs: Long) {
        if (currentTmdbId == 0) return
        val pct = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

        // Quit within first 10 seconds and less than 10% watched = bounce
        if (positionMs < 10_000L && pct < 0.10f && !hasSent30SecSignal) {
            hasSentEarlyQuit = true
            tasteEngine.track(trackingMedia(), UserAction.QUIT_EARLY)
        }
    }
*/

/**
 * ── STEP 5: Add to PlayerActivity/PlayerViewModel where download is triggered ──
 */
/*
    fun onDownloadTriggered(media: Media) {
        tasteEngine.track(media, UserAction.DOWNLOAD)
    }
*/

/**
 * ── STEP 6: Hook into ExoPlayer listener in PlayerActivity ───────────────────
 *
 * Find your ExoPlayer.addListener block. Add:
 *
 *   override fun onEvents(player: Player, events: Player.Events) {
 *       if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
 *           events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
 *           val pos = player.currentPosition
 *           val dur = player.duration.takeIf { it > 0 } ?: return
 *           viewModel.onPlaybackProgress(pos, dur)
 *       }
 *   }
 *
 *   // In onStop() or when ExoPlayer is released:
 *   viewModel.onPlayerStopped(player.currentPosition, player.duration)
 */

/**
 * ── STEP 7: In DetailScreen — track "View Detail" ────────────────────────────
 *
 * In DetailViewModel.init or wherever you load media details:
 *
 *   tasteEngine.track(media, UserAction.VIEW_DETAIL)
 *
 * When user taps Like:
 *   tasteEngine.track(media, UserAction.LIKE)
 *
 * When user adds to watchlist:
 *   tasteEngine.track(media, UserAction.SAVE_WATCHLIST)
 *
 * When user removes from watchlist:
 *   tasteEngine.track(media, UserAction.REMOVE_WATCHLIST)
 */
