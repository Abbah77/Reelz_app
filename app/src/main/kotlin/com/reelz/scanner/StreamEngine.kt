package com.reelz.scanner

import android.content.Context
import com.reelz.data.model.MediaType
import com.reelz.data.model.StreamResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prefetch states so the player can observe and react instantly.
 */
sealed class PrefetchState {
    object Idle    : PrefetchState()
    object Running : PrefetchState()
    data class Ready(val result: StreamResult) : PrefetchState()
    object Failed  : PrefetchState()
}

@Singleton
class StreamEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directScanner: DirectScanner,
    private val cache: StreamResultCache,
) {
    // ── Observable prefetch state ────────────────────────────────────────────
    // The player observes this. If it's Ready when the user taps Play,
    // playback starts in ~0ms. If Running, the player subscribes and starts
    // the moment the result arrives — no second resolve() call ever made.
    private val _prefetch = MutableStateFlow<PrefetchState>(PrefetchState.Idle)
    val prefetchState: StateFlow<PrefetchState> = _prefetch.asStateFlow()

    private var prefetchKey: String = ""
    private var prefetchJob: Job?   = null

    /**
     * Called from DetailViewModel as soon as detail screen loads.
     * Races all sources in parallel; updates prefetchState when first wins.
     * The player subscribes to this — zero duplication, zero delay.
     */
    fun prefetch(
        scope: CoroutineScope,
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
    ) {
        val key = cache.key(tmdbId, mediaType, season, episode)

        // Already have a valid cached result — expose as Ready immediately
        cache.get(key)?.let {
            _prefetch.value = PrefetchState.Ready(it)
            prefetchKey = key
            return
        }

        // Same key already running — don't restart
        if (prefetchKey == key && _prefetch.value is PrefetchState.Running) return

        prefetchJob?.cancel()
        prefetchKey = key
        _prefetch.value = PrefetchState.Running

        prefetchJob = scope.launch {
            val result = raceAllSources(tmdbId, mediaType, season, episode)
            if (result != null) {
                cache.put(key, result)
                _prefetch.value = PrefetchState.Ready(result)
            } else {
                _prefetch.value = PrefetchState.Failed
            }
        }
    }

    /**
     * Called by the player when it initialises.
     * - If prefetch is already Ready → returns instantly (0ms wait).
     * - If prefetch is Running → suspends and receives the result the moment
     *   it completes — no new network call, the existing race finishes.
     * - If prefetch is Idle/Failed or key mismatch → starts a fresh race.
     *
     * This is the key insight: one race, multiple consumers, zero waste.
     */
    suspend fun resolve(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
    ): StreamResult? {
        val key = cache.key(tmdbId, mediaType, season, episode)

        // 1. Cache hit — instant
        cache.get(key)?.let { return it }

        // 2. Prefetch already has the result — instant
        val current = _prefetch.value
        if (current is PrefetchState.Ready && prefetchKey == key) {
            return current.result
        }

        // 3. Prefetch is running for the same key — subscribe and wait
        //    This is the "handoff" — the player joins the already-running race.
        if (_prefetch.value is PrefetchState.Running && prefetchKey == key) {
            // Collect until we get a terminal state
            val stateFlow = prefetchState
            var collected: StreamResult? = null
            stateFlow.collect { state ->
                when (state) {
                    is PrefetchState.Ready  -> { collected = state.result; return@collect }
                    is PrefetchState.Failed -> { collected = null; return@collect }
                    else -> return@collect  // still Running — keep waiting
                }
            }
            // If we got here via the stateFlow completing a terminal state:
            if (collected != null) return collected
            // Fall through to fresh resolve if somehow still nothing
        }

        // 4. No prefetch running — start a fresh race
        val result = raceAllSources(tmdbId, mediaType, season, episode)
        if (result != null) cache.put(key, result)
        return result
    }

    /**
     * Core racing engine. All sources race in parallel.
     * First to return a valid stream wins; all others are cancelled.
     *
     * Optimizations:
     * - DirectScanner runs first (no WebView overhead, fast HTTP scan).
     * - 150ms stagger between source launches to avoid thundering-herd.
     * - Per-source 2s timeout on DirectScanner, 18s on WebView.
     * - Global 25s hard timeout.
     */
    private suspend fun raceAllSources(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int,
        episode: Int,
    ): StreamResult? = coroutineScope {
        val sources = SourceRegistry.sorted()
        if (sources.isEmpty()) return@coroutineScope null

        val resultChannel = Channel<StreamResult?>(Channel.CONFLATED)
        val jobs = mutableListOf<Job>()

        sources.forEachIndexed { index, source ->
            val job = launch {
                if (index > 0) delay(index * 150L)   // 150ms stagger (was 200ms)
                try {
                    val url = source.buildUrl(tmdbId, mediaType, season, episode)

                    // DirectScanner first — no WebView, ultra fast
                    val directResult = withTimeoutOrNull(2_000L) {
                        withContext(Dispatchers.IO) { directScanner.scan(url, source) }
                    }
                    if (directResult != null && isActive) {
                        resultChannel.trySend(directResult)
                        return@launch
                    }

                    // WebView fallback for JS-heavy sources
                    if (!source.requiresJs) return@launch

                    val result = withTimeoutOrNull(18_000L) {
                        withContext(Dispatchers.Main) {
                            WebViewScanner(context).scan(url, source)
                        }
                    }
                    if (result != null && isActive) {
                        resultChannel.trySend(result)
                    }
                } catch (_: Exception) {}
            }
            jobs.add(job)
        }

        val timeoutJob = launch {
            delay(25_000L)
            resultChannel.trySend(null)
        }

        val result = resultChannel.receive()
        jobs.forEach { it.cancel() }
        timeoutJob.cancel()
        result
    }

    /**
     * Fallback resolve: tries remaining sources after a primary failure.
     * Skips the already-failed source, tries cache first.
     */
    suspend fun resolveWithFallback(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        excludeSource: String = "",
    ): StreamResult? {
        val key = cache.key(tmdbId, mediaType, season, episode)
        cache.remove(key)  // invalidate — previous result failed

        // Reset prefetch state so it can be re-used
        if (prefetchKey == key) _prefetch.value = PrefetchState.Idle

        val sources = SourceRegistry.sorted().filter { it.name != excludeSource }
        for (source in sources) {
            try {
                val url = source.buildUrl(tmdbId, mediaType, season, episode)

                val directResult = withTimeoutOrNull(2_000L) {
                    withContext(Dispatchers.IO) { directScanner.scan(url, source) }
                }
                if (directResult != null) {
                    cache.put(key, directResult)
                    return directResult
                }

                if (!source.requiresJs) continue

                val result = withTimeoutOrNull(18_000L) {
                    withContext(Dispatchers.Main) { WebViewScanner(context).scan(url, source) }
                }
                if (result != null) {
                    cache.put(key, result)
                    return result
                }
            } catch (_: Exception) { continue }
        }
        return null
    }

    /**
     * Reset prefetch state — call when navigating away from detail screen.
     */
    fun resetPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        _prefetch.value = PrefetchState.Idle
        prefetchKey = ""
    }
}
