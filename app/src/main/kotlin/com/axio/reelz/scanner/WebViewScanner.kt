package com.axio.reelz.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.axio.reelz.data.model.StreamResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/**
 * Per-scan WebView — creates a fresh instance per scan.
 *
 * KEY FIX vs original:
 *  - nukeWebViewState() is NO LONGER called at the START of scan().
 *    The original pre-scan nuke wiped the global CookieManager, destroying
 *    cookies of other WebViews running in parallel — causing random failures.
 *  - nukeWebViewState() is now called ONLY after result is obtained or on timeout.
 *  - YouTube itag regex removed from SKIP_PATTERN — it matched nothing useful
 *    for our embed sources and added regex overhead on every URL.
 */
class WebViewScanner(private val context: Context) {

    companion object {
        private val SKIP_PATTERN = Regex(
            """(thumbnail|preview|sprite|\.vtt|/sub|/font|/ads|/track)""",
            RegexOption.IGNORE_CASE
        )

        const val SCAN_TIMEOUT_MS = 20_000L

        /**
         * Nuke WebView state AFTER result is retrieved or on timeout.
         * NOT called before scan starts to avoid corrupting parallel scans.
         */
        fun nukeWebViewState(context: Context) {
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            } catch (_: Exception) {}
        }

        val INTERCEPTOR_JS = """
(function() {
    'use strict';
    if (window.__reelz_hooked) return;
    window.__reelz_hooked = true;

    var b = window.ReelzBridge;
    function report(url) {
        if (!url || typeof url !== 'string') return;
        url = url.split('#')[0].trim();
        if (!url.startsWith('http')) return;
        var l = url.toLowerCase().split('?')[0];
        if (l.indexOf('.m3u8') !== -1 || l.indexOf('.mp4') !== -1) {
            try { b.onStreamUrl(url); } catch(e) {}
        }
    }

    var XHR = window.XMLHttpRequest;
    var _open = XHR.prototype.open;
    XHR.prototype.open = function(m, url) {
        this.__url = url; report(url);
        return _open.apply(this, arguments);
    };
    var _send = XHR.prototype.send;
    XHR.prototype.send = function() {
        var self = this;
        var _orsc = self.onreadystatechange;
        self.onreadystatechange = function() {
            if (self.readyState === 4 && self.status === 200) {
                try {
                    var t = self.responseText || '';
                    var m = t.match(/https?:\/\/[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*/g);
                    var p = t.match(/https?:\/\/[^\s"'\\<>]+\.mp4[^\s"'\\<>]*/g);
                    if (m) m.forEach(report);
                    if (p) p.forEach(report);
                } catch(e) {}
            }
            if (_orsc) _orsc.apply(this, arguments);
        };
        return _send.apply(this, arguments);
    };

    var _fetch = window.fetch;
    window.fetch = function(input, init) {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        report(url);
        return _fetch.apply(this, arguments).then(function(resp) {
            report(resp.url);
            return resp.clone().text().then(function(txt) {
                var m = txt.match(/https?:\/\/[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*/g);
                var p = txt.match(/https?:\/\/[^\s"'\\<>]+\.mp4[^\s"'\\<>]*/g);
                if (m) m.forEach(report);
                if (p) p.forEach(report);
                return resp;
            }).catch(function() { return resp; });
        });
    };

    var srcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (srcDesc) {
        Object.defineProperty(HTMLVideoElement.prototype, 'src', {
            set: function(v) { report(v); if (srcDesc.set) srcDesc.set.call(this, v); },
            get: function() { return srcDesc.get ? srcDesc.get.call(this) : ''; },
            configurable: true,
        });
    }

    new MutationObserver(function(muts) {
        muts.forEach(function(m) {
            m.addedNodes.forEach(function(n) {
                var s = n.nodeName === 'VIDEO' || n.nodeName === 'SOURCE'
                    ? (n.getAttribute && n.getAttribute('src')) : null;
                if (s) report(s);
            });
        });
    }).observe(document.documentElement || document.body,
        { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] }
    );

    try {
        var h = document.documentElement.innerHTML;
        var m2 = h.match(/https?:\/\/[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*/g);
        var p2 = h.match(/https?:\/\/[^\s"'\\<>]+\.mp4[^\s"'\\<>]*/g);
        if (m2) m2.forEach(report);
        if (p2) p2.forEach(report);
    } catch(e) {}
})();
""".trimIndent()
    }

    /**
     * BUG FIX (stuck download sheet / stuck skeleton rows): this used to be a
     * fixed 20s regardless of what the caller asked for. The download sheet's
     * resolveAllQualitiesForDownloadFlow calls scan() wanting an effective 10s
     * budget (webViewTimeoutMs), but the WebView's own internal timeout kept
     * running for a full 20s. When the *outer* flow's 8s hardCapMs fired first
     * and cancelled the coroutine, the WebView itself — created and driven on
     * Dispatchers.Main — could still be mid-load with an unresolved Handler
     * callback still queued. If that source's embed page has a slow/hung
     * script (ad redirect chains, popup loops — common on unlicensed embed
     * sources), it can keep the Main looper busy long enough that even the
     * *cancellation* teardown gets delayed, since the callback removal and
     * destroy() calls also queue on Main. Net effect: the coroutine says
     * "cancelled" but the WebView keeps running, ties up a WebView instance,
     * and — because sourceLadderLabels/foundLabels never receive anything
     * from that source — the caller's skeleton rows for whatever labels this
     * source was expected to supply just sit there indefinitely with no
     * further signal to clear them.
     *
     * Kept as a class constant default for anything calling scan() without a
     * caller-supplied budget, but callers should always pass their own
     * timeoutMs so the WebView's internal deadline can never outlive the
     * flow that's waiting on it.
     */
    suspend fun scan(
        embedUrl: String,
        source: StreamSource,
        timeoutMs: Long = SCAN_TIMEOUT_MS,
    ): StreamResult? =
        withContext(Dispatchers.Main) {
            val resultCh = Channel<String?>(Channel.CONFLATED)
            var resolved = false
            var webView: WebView? = null
            val handler = Handler(Looper.getMainLooper())

            // NOTE: nukeWebViewState() deliberately NOT called here.
            // Calling it at the start corrupts parallel WebView sessions.

            val timeout = Runnable {
                if (!resolved) {
                    resolved = true
                    nukeWebViewState(context)  // nuke AFTER timeout
                    destroy(webView); webView = null
                    resultCh.trySend(null)
                }
            }

            // BUG FIX: guarantee teardown even if the coroutine is cancelled
            // (e.g. the outer flow's hardCapMs fires) while we're still
            // suspended on resultCh.receive(). Without this, a cancellation
            // that races ahead of the try/catch's own cleanup could leave the
            // WebView alive and its Handler callback still pending — the
            // exact "stuck, no error, nothing ever clears it" failure mode.
            // This runs synchronously on Main the instant cancellation is
            // requested, rather than waiting for the suspended call to
            // unwind through the catch block.
            coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException && !resolved) {
                    resolved = true
                    handler.removeCallbacks(timeout)
                    nukeWebViewState(context)
                    destroy(webView); webView = null
                }
            }

            try {
                webView = buildWebView(source) { url ->
                    if (!resolved && isValidStream(url)) {
                        resolved = true
                        handler.removeCallbacks(timeout)
                        nukeWebViewState(context)  // nuke AFTER result obtained
                        resultCh.trySend(url)
                    }
                }

                handler.postDelayed(timeout, timeoutMs)
                webView!!.loadUrl(embedUrl, source.headers)

                val found = resultCh.receive()
                destroy(webView); webView = null

                found?.let {
                    StreamResult(
                        url        = it,
                        isHls      = it.contains(".m3u8", true),
                        headers    = source.headers + buildMap {
                            if (source.referer.isNotBlank()) put("Referer", source.referer)
                            if (source.origin.isNotBlank())  put("Origin",  source.origin)
                        },
                        referer    = source.referer,
                        origin     = source.origin,
                        sourceName = source.name,
                    )
                }
            } catch (e: Exception) {
                handler.removeCallbacks(timeout)
                nukeWebViewState(context)
                destroy(webView)
                null
            }
        }

    private fun destroy(wv: WebView?) {
        try {
            wv?.stopLoading()
            wv?.loadUrl("about:blank")
            wv?.clearCache(true)
            wv?.clearHistory()
            wv?.clearFormData()
            wv?.destroy()
        } catch (_: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(source: StreamSource, onUrl: (String) -> Unit): WebView {
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = false
            allowContentAccess               = false
            loadWithOverviewMode             = true
            useWideViewPort                  = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode                        = WebSettings.LOAD_NO_CACHE
            userAgentString                  = source.headers["User-Agent"] ?: StreamHeaders.UA_CHROME_ANDROID
            databaseEnabled                  = false
            setSupportMultipleWindows(false)
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(false)
        cm.setAcceptThirdPartyCookies(wv, false)

        wv.addJavascriptInterface(object {
            @JavascriptInterface fun onStreamUrl(url: String) { onUrl(url) }
        }, "ReelzBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(INTERCEPTOR_JS, null)
            }

            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url.toString()
                if (isValidStream(url)) onUrl(url)
                return null
            }

            @Deprecated("Deprecated")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (isValidStream(url)) onUrl(url)
                return false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {}
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage) = true
        }

        return wv
    }

    private fun isValidStream(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (SKIP_PATTERN.containsMatchIn(lower)) return false
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }
}
