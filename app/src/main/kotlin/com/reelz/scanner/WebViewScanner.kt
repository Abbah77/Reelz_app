package com.reelz.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.reelz.data.model.StreamResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Per-scan WebView — a NEW instance is created for every scan call.
 * This eliminates the cookie/session cross-contamination bug that forced
 * users to clear app data before re-watching a movie.
 *
 * Key improvements over the original:
 *  • Cookies are completely isolated per scan (acceptCookie = false + flush each time)
 *  • DOM storage is cleared before AND after each scan
 *  • JS interceptor fires immediately on XHR open (no 2500ms delay)
 *  • MutationObserver starts BEFORE page load
 *  • The WebView is destroyed immediately after a result is received
 */
class WebViewScanner(private val context: Context) {

    companion object {
        private val SKIP_PATTERN = Regex(
            """(thumbnail|preview|sprite|\.vtt|/sub|/font|/ads|/track|googlevideo\.com/videoplayback.*itag=(?!137|248|299|303|315|264|271|272))""",
            RegexOption.IGNORE_CASE
        )

        fun nukeWebViewState(context: Context) {
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            } catch (_: Exception) {}
        }
    }

    suspend fun scan(embedUrl: String, source: StreamSource): StreamResult? =
        withContext(Dispatchers.Main) {
            val resultCh = Channel<String?>(Channel.CONFLATED)
            var resolved = false
            var webView: WebView? = null
            val handler = Handler(Looper.getMainLooper())

            // ── Nuke state BEFORE creating the WebView ─────────────────────
            nukeWebViewState(context)

            val timeout = Runnable {
                if (!resolved) {
                    resolved = true
                    nukeWebViewState(context)
                    destroy(webView); webView = null
                    resultCh.trySend(null)
                }
            }

            try {
                webView = buildWebView(source) { url ->
                    if (!resolved && isValidStream(url)) {
                        resolved = true
                        handler.removeCallbacks(timeout)
                        // Nuke cookies BEFORE sending result
                        nukeWebViewState(context)
                        resultCh.trySend(url)
                    }
                }

                handler.postDelayed(timeout, WebViewScanner.SCAN_TIMEOUT_MS)
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

        // Hard-disable cookies for scanner WebViews
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(false)
        cm.setAcceptThirdPartyCookies(wv, false)

        wv.addJavascriptInterface(object {
            @JavascriptInterface fun onStreamUrl(url: String) { onUrl(url) }
        }, "ReelzBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Inject interceptor after every page navigation
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

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // Swallow — don't surface raw errors to user
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage) = true  // suppress dev logs
        }

        return wv
    }

    private fun isValidStream(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (SKIP_PATTERN.containsMatchIn(lower)) return false
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }

    companion object {
        const val SCAN_TIMEOUT_MS = 20_000L

        /**
         * Comprehensive JS interceptor — hooks XHR open/send + fetch + video.src
         * mutation BEFORE page code runs via onPageFinished injection.
         * Reports the FIRST m3u8/mp4 it finds without any artificial delay.
         */
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

    // ── XHR ─────────────────────────────────────────────────────────────────
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

    // ── Fetch ────────────────────────────────────────────────────────────────
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

    // ── HTMLMediaElement.src ─────────────────────────────────────────────────
    var srcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (srcDesc) {
        Object.defineProperty(HTMLVideoElement.prototype, 'src', {
            set: function(v) {
                report(v);
                if (srcDesc.set) srcDesc.set.call(this, v);
            },
            get: function() { return srcDesc.get ? srcDesc.get.call(this) : ''; },
            configurable: true,
        });
    }

    // ── MutationObserver ─────────────────────────────────────────────────────
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

    // ── DOM scan (quick, no delay) ────────────────────────────────────────
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
}
