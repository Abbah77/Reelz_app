package com.axio.reelz.ads

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.axio.reelz.ui.theme.Bg
import com.axio.reelz.ui.theme.BgSurface
import com.axio.reelz.ui.theme.White60

// ─────────────────────────────────────────────────────────────────────────────
// URL routing — the single chokepoint for every ad click in the app.
//
// Routing rules (in priority order):
//   1. Play Store URLs / market:// → open Play Store app directly
//   2. intent:// deep-links        → parse and fire the target intent
//   3. Everything else             → open in ReelzBrowserSheet (in-app WebView)
//
// All exceptions are caught; if the intended handler isn't available we fall
// back gracefully to openBrowserSheet so the user always lands somewhere.
// ─────────────────────────────────────────────────────────────────────────────

fun routeAdUrl(
    context: Context,
    url: String,
    openBrowserSheet: (String) -> Unit,
) {
    when {
        // Play Store deep-links — open the Play Store app, not a browser
        url.contains("play.google.com/store/apps") || url.startsWith("market://") -> {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.android.vending")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                // Play Store not installed (e.g. Huawei device) — fall back to browser
                openBrowserSheet(url)
            }
        }

        // Intent deep-links — try to open the target app; fall back to browser.
        // NOTE: we intentionally do NOT call openBrowserSheet(url) here after
        // startActivity succeeds; doing both would open the app AND show the
        // browser sheet simultaneously.
        url.startsWith("intent://") -> {
            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                // Success — do nothing else; app is now in foreground
            } catch (_: Exception) {
                // Intent target not installed / malformed URI — show browser instead
                openBrowserSheet(url)
            }
        }

        // All other URLs → in-app browser sheet
        else -> openBrowserSheet(url)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ReelzBrowserSheet — clean ModalBottomSheet wrapping a sandboxed WebView.
//
// Design: minimal chrome (back/forward/reload/open-in-browser/close),
// a 2 dp progress bar while loading, and the page title / domain as the
// header — enough context to feel like a real browser without the clutter.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReelzBrowserSheet(
    url: String,
    onDismiss: () -> Unit,
) {
    val context      = LocalContext.current
    var currentUrl   by remember { mutableStateOf(url) }
    var pageTitle    by remember { mutableStateOf(extractDomain(url)) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isLoading    by remember { mutableStateOf(true) }
    var canGoBack    by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webViewRef   by remember { mutableStateOf<WebView?>(null) }

    // Destroy the WebView when the sheet is dismissed so it doesn't leak
    // media resources (video, audio) or keep JS timers running.
    DisposableEffect(url) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = BgSurface,
        dragHandle       = null,
        modifier         = Modifier.fillMaxHeight(0.92f),
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Bg)
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick  = { webViewRef?.goBack() },
                    enabled  = canGoBack,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint               = if (canGoBack) Color.White else White60,
                        modifier           = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick  = { webViewRef?.goForward() },
                    enabled  = canGoForward,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint               = if (canGoForward) Color.White else White60,
                        modifier           = Modifier.size(20.dp),
                    )
                }

                // Domain/title pill — centered in remaining space
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text     = pageTitle,
                        color    = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick  = { webViewRef?.reload() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Default.Refresh,
                        contentDescription = "Reload",
                        tint               = White60,
                        modifier           = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Exception) { /* no browser installed — ignore */ }
                        onDismiss()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Default.OpenInBrowser,
                        contentDescription = "Open in browser",
                        tint               = White60,
                        modifier           = Modifier.size(20.dp),
                    )
                }

                IconButton(
                    onClick  = onDismiss,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Default.Close,
                        contentDescription = "Close",
                        tint               = Color.White,
                        modifier           = Modifier.size(20.dp),
                    )
                }
            }

            // ── Loading progress bar ───────────────────────────────────────
            AnimatedVisibility(
                visible = isLoading,
                enter   = fadeIn(),
                exit    = fadeOut(),
            ) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color    = MaterialTheme.colorScheme.primary,
                )
            }

            // ── WebView ────────────────────────────────────────────────────
            AdWebView(
                url               = url,
                context           = context,
                onPageStarted     = { u ->
                    currentUrl = u
                    pageTitle  = extractDomain(u)
                    isLoading  = true
                },
                onPageFinished    = { u, wv ->
                    currentUrl   = u
                    isLoading    = false
                    canGoBack    = wv.canGoBack()
                    canGoForward = wv.canGoForward()
                },
                onProgressChanged = { p -> loadProgress = p },
                onTitleReceived   = { t -> if (!t.isNullOrBlank()) pageTitle = t },
                onWebViewCreated  = { wv -> webViewRef = wv },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sandboxed AdWebView — no app bridge, no local file access.
// URL interception is handled entirely inside shouldOverrideUrlLoading so
// deep-links and Play Store links work from inside the WebView too.
// ─────────────────────────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AdWebView(
    url: String,
    context: Context,
    onPageStarted: (String) -> Unit,
    onPageFinished: (String, WebView) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onTitleReceived: (String?) -> Unit,
    onWebViewCreated: (WebView) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory  = {
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled     = true
                    domStorageEnabled     = true
                    setSupportMultipleWindows(false)
                    allowContentAccess    = true
                    allowFileAccess       = false   // security: no local file access
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        onPageStarted(url)
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        onPageFinished(url, view)
                    }
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val urlStr = request.url.toString()
                        return when {
                            // Play Store — open the app, consume the navigation
                            urlStr.startsWith("market://") ||
                            urlStr.contains("play.google.com/store/apps") -> {
                                routeAdUrl(context, urlStr) {}
                                true   // consumed; WebView does NOT try to load it
                            }

                            // intent:// deep-links — open target app, consume navigation.
                            // If the app isn't installed, routeAdUrl falls back to
                            // openBrowserSheet which is a no-op here (we're already in
                            // the browser sheet), so we load the fallback URL instead.
                            urlStr.startsWith("intent://") -> {
                                try {
                                    val intent = Intent.parseUri(urlStr, Intent.URI_INTENT_SCHEME).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                    true   // consumed
                                } catch (_: Exception) {
                                    // App not installed — extract fallback URL from intent:// if present
                                    val fallback = try {
                                        Intent.parseUri(urlStr, Intent.URI_INTENT_SCHEME)
                                            .getStringExtra("browser_fallback_url")
                                    } catch (_: Exception) { null }
                                    if (!fallback.isNullOrBlank()) {
                                        view.loadUrl(fallback)
                                        true
                                    } else {
                                        false  // let WebView try
                                    }
                                }
                            }

                            // Standard http(s) — let WebView handle it normally
                            else -> false
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        onProgressChanged(newProgress)
                    }
                    override fun onReceivedTitle(view: WebView, title: String?) {
                        onTitleReceived(title)
                    }
                }

                onWebViewCreated(this)
                loadUrl(url)
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun extractDomain(url: String): String = try {
    Uri.parse(url).host?.removePrefix("www.") ?: url
} catch (_: Exception) { url }
