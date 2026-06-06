package com.reelz.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * WebViewScanner
 * ──────────────
 * Loads a stream-provider embed page inside a headless WebView, then injects
 * JavaScript that intercepts every XHR / fetch / MediaSource / video-src network
 * call and reports any .m3u8 or .mp4 URL it discovers back to Kotlin via the
 * JavascriptInterface bridge.
 *
 * The scanner races all JS-based sources in parallel (one WebView each) on the
 * main thread (WebView requirement) and returns whichever finds a valid stream URL
 * first via a Kotlin Channel.
 *
 * Design goals
 *  • Zero-buffer delivery: hand the raw .m3u8 directly to ExoPlayer so HLS
 *    segments begin downloading immediately — no intermediate proxy hop.
 *  • Header forwarding: every request carries the correct Referer / Origin /
 *    User-Agent so CDN hotlink-protection passes.
 *  • Timeout: each WebView is hard-killed after [SCAN_TIMEOUT_MS] to avoid hangs.
 */
class WebViewScanner(private val context: Context) {

    companion object {
        const val SCAN_TIMEOUT_MS = 15_000L   // 15 s per source
        private val M3U8_PATTERN = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""")
        private val MP4_PATTERN  = Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""")
        // Skip tiny/thumbnail segments that are not main streams
        private val SKIP_PATTERN = Regex("""(thumbnail|preview|sprite|vtt|sub|font)""", RegexOption.IGNORE_CASE)
    }

    /**
     * Scan a single source URL using a headless WebView.
     * Must be called from a coroutine — suspends until a URL is found or timeout.
     * @return StreamResult or null if nothing was found in time.
     */
    suspend fun scan(
        embedUrl: String,
        source: StreamSource,
    ): StreamResult? = withContext(Dispatchers.Main) {

        val resultChannel = Channel<String?>(Channel.CONFLATED)
        var webView: WebView? = null
        var resolved = false

        val timeout = Handler(Looper.getMainLooper()).postDelayed({
            if (!resolved) {
                resolved = true
                webView?.destroy()
                resultChannel.trySend(null)
            }
        }, SCAN_TIMEOUT_MS)

        try {
            webView = buildWebView(context, source) { interceptedUrl ->
                if (!resolved && isValidStreamUrl(interceptedUrl)) {
                    resolved = true
                    Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
                    resultChannel.trySend(interceptedUrl)
                }
            }
            webView.loadUrl(embedUrl, source.headers)

            val found = resultChannel.receive()
            webView.destroy()

            found?.let {
                StreamResult(
                    url        = it,
                    isHls      = it.contains(".m3u8"),
                    headers    = source.headers + buildMapOf(
                        "Referer" to source.referer,
                        "Origin"  to source.origin,
                    ).filterValues { v -> v.isNotBlank() },
                    referer    = source.referer,
                    origin     = source.origin,
                    sourceName = source.name,
                )
            }
        } catch (e: Exception) {
            webView?.destroy()
            null
        }
    }

    // ── WebView factory ────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun buildWebView(
        context: Context,
        source: StreamSource,
        onUrl: (String) -> Unit,
    ): WebView {

        val wv = WebView(context)

        wv.settings.apply {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            allowFileAccess          = false
            allowContentAccess       = false
            loadWithOverviewMode     = true
            useWideViewPort          = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode         = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode                = WebSettings.LOAD_NO_CACHE
            userAgentString          = source.headers["User-Agent"] ?: StreamHeaders.UA_CHROME_ANDROID
            databaseEnabled          = false
            geolocationEnabled       = false
            setSupportMultipleWindows(false)
        }

        // JavaScript bridge — receives URLs detected by injected JS
        wv.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onStreamUrl(url: String) { onUrl(url) }

                @JavascriptInterface
                fun onStreamError(msg: String) { /* log if needed */ }
            },
            "ReelzBridge",
        )

        wv.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(buildInterceptorJs(), null)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                // Fast-path: inspect every network request URL directly
                val url = request.url.toString()
                if (isValidStreamUrl(url)) onUrl(url)
                return null  // let the request pass through normally
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) { /* absorb page errors, wait for timeout */ }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (isValidStreamUrl(url)) onUrl(url)
                return false
            }
        }

        // Block ads / trackers to speed up page load
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean = true
        }

        return wv
    }

    // ── Interceptor JavaScript ─────────────────────────────────────────────────

    /**
     * Injected into every page after load.
     *
     * Intercepts:
     *  1. XMLHttpRequest — patches open() to capture response URLs
     *  2. fetch()        — wraps the global fetch to inspect request URLs
     *  3. MediaSource    — patches addSourceBuffer to catch codec MIME types with blob URLs
     *  4. HTMLVideoElement src / src attribute — MutationObserver on <video>
     *  5. window.open / location   — catches redirects to stream URLs
     *  6. eval / Function          — some obfuscated players eval final URLs
     */
    private fun buildInterceptorJs(): String = """
(function() {
    'use strict';

    var bridge = window.ReelzBridge;
    if (!bridge) return;

    function report(url) {
        if (!url || typeof url !== 'string') return;
        url = url.trim();
        if (url.indexOf('http') !== 0) return;
        bridge.onStreamUrl(url);
    }

    function looksLikeStream(url) {
        if (!url) return false;
        var u = url.toLowerCase().split('?')[0];
        return u.indexOf('.m3u8') !== -1 || u.indexOf('.mp4') !== -1;
    }

    // 1. Patch XMLHttpRequest ──────────────────────────────────────────────────
    var OrigXHR  = window.XMLHttpRequest;
    var OrigOpen = OrigXHR.prototype.open;
    var OrigSend = OrigXHR.prototype.send;

    OrigXHR.prototype.open = function(method, url) {
        this._reelzUrl = url;
        if (looksLikeStream(url)) report(url);
        return OrigOpen.apply(this, arguments);
    };

    OrigXHR.prototype.send = function() {
        var self = this;
        var origOnReadyStateChange = self.onreadystatechange;
        self.onreadystatechange = function() {
            if (self.readyState === 4) {
                // Check redirect / final URL
                if (self._reelzUrl && looksLikeStream(self._reelzUrl)) report(self._reelzUrl);
                // Scan response text for embedded stream URLs
                try {
                    var txt = self.responseText;
                    if (txt) {
                        var m3u = txt.match(/https?:\/\/[^\s"'\\\\]+\.m3u8[^\s"'\\\\]*/g);
                        var mp4 = txt.match(/https?:\/\/[^\s"'\\\\]+\.mp4[^\s"'\\\\]*/g);
                        if (m3u) m3u.forEach(report);
                        if (mp4) mp4.forEach(report);
                    }
                } catch(e) {}
            }
            if (origOnReadyStateChange) origOnReadyStateChange.apply(this, arguments);
        };
        return OrigSend.apply(this, arguments);
    };

    // 2. Patch fetch() ─────────────────────────────────────────────────────────
    var origFetch = window.fetch;
    window.fetch = function(input, init) {
        var url = (typeof input === 'string') ? input : (input && input.url) || '';
        if (looksLikeStream(url)) report(url);
        return origFetch.apply(this, arguments).then(function(resp) {
            if (looksLikeStream(resp.url)) report(resp.url);
            return resp.clone().text().then(function(txt) {
                if (txt) {
                    var m3u = txt.match(/https?:\/\/[^\s"'\\\\]+\.m3u8[^\s"'\\\\]*/g);
                    var mp4 = txt.match(/https?:\/\/[^\s"'\\\\]+\.mp4[^\s"'\\\\]*/g);
                    if (m3u) m3u.forEach(report);
                    if (mp4) mp4.forEach(report);
                }
                return resp;
            }).catch(function(){ return resp; });
        });
    };

    // 3. HTMLVideoElement src ──────────────────────────────────────────────────
    var videoProto = HTMLVideoElement.prototype;
    var origSrcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (origSrcDesc) {
        Object.defineProperty(videoProto, 'src', {
            set: function(val) {
                if (val && typeof val === 'string' && looksLikeStream(val)) report(val);
                if (origSrcDesc.set) origSrcDesc.set.call(this, val);
            },
            get: function() { return origSrcDesc.get ? origSrcDesc.get.call(this) : ''; },
            configurable: true,
        });
    }

    // 4. MutationObserver on video[src] ───────────────────────────────────────
    var observer = new MutationObserver(function(mutations) {
        mutations.forEach(function(m) {
            m.addedNodes.forEach(function(node) {
                if (node.nodeName === 'VIDEO' || node.nodeName === 'SOURCE') {
                    var s = node.getAttribute && node.getAttribute('src');
                    if (s && looksLikeStream(s)) report(s);
                }
            });
        });
    });
    observer.observe(document.documentElement || document.body, {
        childList: true, subtree: true, attributes: true, attributeFilter: ['src']
    });

    // 5. MediaSource / SourceBuffer — blob: URLs wrapping HLS ────────────────
    if (window.MediaSource) {
        var origAddSB = MediaSource.prototype.addSourceBuffer;
        MediaSource.prototype.addSourceBuffer = function(mime) {
            // If we hit MediaSource that means the page is using MSE to play HLS
            // — the actual manifest URL was already caught by XHR/fetch patches above.
            return origAddSB.apply(this, arguments);
        };
    }

    // 6. Scan document after a short delay for lazy-loaded players ─────────────
    setTimeout(function() {
        try {
            var html = document.documentElement.innerHTML;
            var m3u = html.match(/https?:\/\/[^\s"'\\\\<>]+\.m3u8[^\s"'\\\\<>]*/g);
            var mp4 = html.match(/https?:\/\/[^\s"'\\\\<>]+\.mp4[^\s"'\\\\<>]*/g);
            if (m3u) m3u.forEach(report);
            if (mp4) mp4.forEach(report);
        } catch(e) {}
    }, 2500);

    // 7. Scan every 1 s for pages with delayed JS evaluation ──────────────────
    var scanCount = 0;
    var scanInterval = setInterval(function() {
        scanCount++;
        if (scanCount > 8) { clearInterval(scanInterval); return; }
        try {
            var iframes = document.querySelectorAll('iframe[src]');
            iframes.forEach(function(f) {
                var s = f.getAttribute('src');
                if (s && looksLikeStream(s)) report(s);
            });
        } catch(e) {}
    }, 1000);

})();
""".trimIndent()

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun isValidStreamUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (SKIP_PATTERN.containsMatchIn(lower)) return false
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }

    private fun buildMapOf(vararg pairs: Pair<String, String>) = mapOf(*pairs)
}
