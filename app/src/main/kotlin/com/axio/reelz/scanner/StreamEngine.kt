package com.axio.reelz.scanner

import android.content.Context
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.data.model.StreamResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val sourceRegistry: SourceRegistry,
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

        // 3. Prefetch is running for the same key — subscribe and wait.
        //    Use first{} so the coroutine actually suspends and resumes on the
        //    first terminal emission.  collect{} on a StateFlow never completes
        //    on its own, so return@collect only skips the current item and the
        //    coroutine hangs forever — that was the original deadlock.
        if (_prefetch.value is PrefetchState.Running && prefetchKey == key) {
            val terminal = prefetchState.first { it is PrefetchState.Ready || it is PrefetchState.Failed }
            if (terminal is PrefetchState.Ready) return terminal.result
            // Failed — fall through to fresh resolve
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
        val sources = sourceRegistry.sorted()
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

        val sources = sourceRegistry.sorted().filter { it.name != excludeSource }
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

    /**
     * Aggregates stream results from MULTIPLE sources for the download sheet.
     *
     * Why this exists: a single source's master playlist may only expose one
     * or two renditions (e.g. some embeds serve 360p-only, others 1080p-only).
     * Racing for the *first* successful source (as resolve()/prefetch() do for
     * playback) is fine for watching, but it silently caps download quality to
     * whatever that one lucky/fast source happened to have.
     *
     * Instead, this fires several sources CONCURRENTLY (not staggered — every
     * quality tier is user-facing and worth waiting a bounded amount for),
     * collects every result that comes back within the window, and returns
     * them all so the caller can merge every quality tier into one list.
     *
     * - Runs the first [maxSources] sources from the registry in parallel.
     * - Each source gets its own short timeout so a slow/dead source can't
     *   block the others.
     * - Returns as soon as [minResults] have arrived AND at least [settleMs]
     *   has passed since the first hit, OR when [hardTimeoutMs] elapses —
     *   whichever is first. This keeps things fast while still giving slower-
     *   but-higher-quality sources a chance to report in.
     */
    suspend fun resolveMultiSource(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        maxSources: Int = 6,
        minResults: Int = 2,
        settleMs: Long = 1_200L,
        hardTimeoutMs: Long = 9_000L,
    ): List<StreamResult> = coroutineScope {
        val sources = sourceRegistry.sorted().take(maxSources)
        if (sources.isEmpty()) return@coroutineScope emptyList()

        val results = java.util.Collections.synchronizedList(mutableListOf<StreamResult>())
        var firstHitAt = -1L

        val jobs = sources.map { source ->
            async {
                try {
                    val url = source.buildUrl(tmdbId, mediaType, season, episode)

                    // DirectScanner first — cheap, no WebView.
                    val direct = withTimeoutOrNull(2_500L) {
                        withContext(Dispatchers.IO) { directScanner.scan(url, source) }
                    }
                    if (direct != null) {
                        results.add(direct)
                        if (firstHitAt < 0) firstHitAt = System.currentTimeMillis()
                        return@async
                    }

                    if (!source.requiresJs) return@async

                    // WebView fallback — more expensive, still bounded.
                    val viaWebView = withTimeoutOrNull(7_000L) {
                        withContext(Dispatchers.Main) { WebViewScanner(context).scan(url, source) }
                    }
                    if (viaWebView != null) {
                        results.add(viaWebView)
                        if (firstHitAt < 0) firstHitAt = System.currentTimeMillis()
                    }
                } catch (_: Exception) {}
            }
        }

        // Poll until we've settled: enough results in hand and a short grace
        // window elapsed since the first one arrived, or we hit the hard cap.
        val start = System.currentTimeMillis()
        while (isActive) {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= hardTimeoutMs) break
            if (results.size >= minResults && firstHitAt >= 0 &&
                System.currentTimeMillis() - firstHitAt >= settleMs) break
            if (jobs.all { it.isCompleted }) break
            delay(100L)
        }
        jobs.forEach { it.cancel() }

        results.toList()
    }
}
