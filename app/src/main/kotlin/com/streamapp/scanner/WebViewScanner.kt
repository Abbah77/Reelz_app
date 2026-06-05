package com.streamapp.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * WebViewScanner — renders a page in a hidden WebView, injects JavaScript to
 * intercept network requests and extract .m3u8 URLs.
 *
 * HOW TO USE:
 *   1. Add your source site URL to targetUrl below (or pass it in).
 *   2. Adjust the JS selectors if the site uses iframes or shadow DOM.
 *   3. Call scan() from a coroutine — it returns the first m3u8 found or null.
 *
 * The JavaScript bridge posts ANY intercepted .m3u8 URL back to Kotlin instantly.
 * No polling — uses a WebMessageListener for zero-latency extraction.
 */
class WebViewScanner(private val context: Context) {

    private val TAG = "WebViewScanner"

    /**
     * Scans [targetUrl] for an m3u8 stream using a hidden WebView.
     * Returns the extracted URL or null if nothing found within [timeoutMs].
     *
     * @param targetUrl  — YOUR SOURCE URL goes here (placeholder shown)
     * @param timeoutMs  — max wait time before giving up
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun scan(
        targetUrl: String = "https://YOUR-SOURCE-PLACEHOLDER.com/embed/ID",
        timeoutMs: Long = 12_000L,
    ): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var settled = false

            fun settle(url: String?) {
                if (!settled) {
                    settled = true
                    if (cont.isActive) cont.resume(url)
                }
            }

            val webView = WebView(context)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            }

            // JavaScript → Kotlin bridge
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onM3u8Found(url: String) {
                    Log.d(TAG, "JS bridge: m3u8 found → $url")
                    webView.post { settle(url) }
                }
            }, "StreamBridge")

            // Intercept every network request — catch m3u8 before it loads
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (url.contains(".m3u8", ignoreCase = true)) {
                        Log.d(TAG, "Intercepted m3u8 request: $url")
                        settle(url)
                    }
                    return null // Let WebView load normally
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // Inject JS after page load — scans DOM + watches XHR/fetch
                    view.evaluateJavascript(buildScannerJs(), null)
                }
            }

            // Load the target page
            webView.loadUrl(targetUrl)
            Log.d(TAG, "WebView loading: $targetUrl")

            // Timeout
            webView.postDelayed({
                if (!settled) {
                    Log.w(TAG, "WebView scan timed out for $targetUrl")
                    settle(null)
                }
                webView.stopLoading()
                webView.destroy()
            }, timeoutMs)

            cont.invokeOnCancellation {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    /**
     * JavaScript injected into the page after load.
     *
     * Strategy 1: Scan existing DOM for <video>, <source> with .m3u8 src.
     * Strategy 2: Monkey-patch XMLHttpRequest to intercept AJAX responses.
     * Strategy 3: Monkey-patch fetch() to intercept fetch responses.
     * Strategy 4: MutationObserver to catch dynamically added video elements.
     *
     * All strategies report back via StreamBridge.onM3u8Found().
     */
    private fun buildScannerJs(): String = """
        (function() {
            var _found = false;
            function report(url) {
                if (_found || !url || !url.includes('.m3u8')) return;
                _found = true;
                try { StreamBridge.onM3u8Found(url); } catch(e) {}
            }

            // Strategy 1: existing DOM
            document.querySelectorAll('video, source').forEach(function(el) {
                report(el.src || el.currentSrc || el.getAttribute('src') || '');
            });

            // Strategy 2: XHR intercept
            var _XHR = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                report(url);
                return _XHR.apply(this, arguments);
            };

            // Strategy 3: fetch() intercept
            var _fetch = window.fetch;
            window.fetch = function(input, init) {
                var url = typeof input === 'string' ? input : (input && input.url) || '';
                report(url);
                return _fetch.apply(this, arguments);
            };

            // Strategy 4: MutationObserver for dynamic elements
            new MutationObserver(function(mutations) {
                mutations.forEach(function(m) {
                    m.addedNodes.forEach(function(node) {
                        if (node.tagName === 'VIDEO' || node.tagName === 'SOURCE') {
                            report(node.src || node.getAttribute('src') || '');
                        }
                        if (node.querySelectorAll) {
                            node.querySelectorAll('video, source').forEach(function(el) {
                                report(el.src || el.getAttribute('src') || '');
                            });
                        }
                    });
                });
            }).observe(document.documentElement, { childList: true, subtree: true });

            // Strategy 5: scan all script tag contents for m3u8 URLs
            var scriptRegex = /(https?:[^"'\s]+\.m3u8[^"'\s]*)/g;
            document.querySelectorAll('script').forEach(function(s) {
                var matches = (s.textContent || '').match(scriptRegex);
                if (matches) matches.forEach(report);
            });
        })();
    """.trimIndent()
}
