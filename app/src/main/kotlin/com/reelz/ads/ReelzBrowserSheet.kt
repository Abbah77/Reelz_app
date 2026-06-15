package com.reelz.ads

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
import com.reelz.ui.theme.Bg
import com.reelz.ui.theme.BgSurface
import com.reelz.ui.theme.White60

// ─────────────────────────────────────────────────────────────────────────────
// URL routing — call this from every ad click callback
// ─────────────────────────────────────────────────────────────────────────────

fun routeAdUrl(
    context: Context,
    url: String,
    openBrowserSheet: (String) -> Unit,
) {
    when {
        // Play Store deep-links → open Play Store app directly
        url.contains("play.google.com/store/apps") || url.startsWith("market://") -> {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.android.vending")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(intent) }
            catch (_: Exception) { openBrowserSheet(url) }
        }

        // Intent / deep-links → try to open the target app
        url.startsWith("intent://") -> {
            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                openBrowserSheet(url)
            }
        }

        // Everything else → in-app browser sheet
        else -> openBrowserSheet(url)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ReelzBrowserSheet — ModalBottomSheet wrapping a clean WebView
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReelzBrowserSheet(
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var currentUrl   by remember { mutableStateOf(url) }
    var pageTitle    by remember { mutableStateOf(extractDomain(url)) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isLoading    by remember { mutableStateOf(true) }
    var canGoBack    by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webViewRef   by remember { mutableStateOf<WebView?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = BgSurface,
        dragHandle       = null,
        modifier         = Modifier.fillMaxHeight(0.92f),
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier            = Modifier
                    .fillMaxWidth()
                    .background(Bg)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment   = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Back
                IconButton(
                    onClick  = { webViewRef?.goBack() },
                    enabled  = canGoBack,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint   = if (canGoBack) Color.White else White60,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Forward
                IconButton(
                    onClick  = { webViewRef?.goForward() },
                    enabled  = canGoForward,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint   = if (canGoForward) Color.White else White60,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Domain title
                Text(
                    text     = pageTitle,
                    color    = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )

                // Reload
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

                // Open in external browser
                IconButton(
                    onClick  = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { context.startActivity(intent) } catch (_: Exception) {}
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

                // Close
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

            // ── Progress bar ──────────────────────────────────────────────────
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

            // ── WebView ───────────────────────────────────────────────────────
            AdWebView(
                url     = url,
                context = context,
                onPageStarted = { u ->
                    currentUrl = u
                    pageTitle  = extractDomain(u)
                    isLoading  = true
                },
                onPageFinished = { u, wv ->
                    currentUrl   = u
                    isLoading    = false
                    canGoBack    = wv.canGoBack()
                    canGoForward = wv.canGoForward()
                },
                onProgressChanged = { p -> loadProgress = p },
                onTitleReceived   = { t -> if (!t.isNullOrBlank()) pageTitle = t },
                onUrlIntercept    = { interceptUrl ->
                    // Re-route any navigation inside the WebView as well
                    routeAdUrl(context, interceptUrl) { /* stay in WebView for http links */ }
                    false // let WebView handle http(s) links
                },
                onWebViewCreated = { wv -> webViewRef = wv },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Isolated WebView composable — NO injected scripts, NO ReelzBridge
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
    onUrlIntercept: (String) -> Boolean,
    onWebViewCreated: (WebView) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory  = {
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled      = true
                    domStorageEnabled      = true
                    setSupportMultipleWindows(false)
                    allowContentAccess     = true
                    allowFileAccess        = false  // security: no local file access
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        onPageStarted(url)
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        onPageFinished(url, view)
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val urlStr = request.url.toString()
                        return when {
                            urlStr.startsWith("market://") || urlStr.contains("play.google.com/store/apps") -> {
                                routeAdUrl(context, urlStr) {}
                                true
                            }
                            urlStr.startsWith("intent://") -> {
                                routeAdUrl(context, urlStr) { view.loadUrl(it) }
                                true
                            }
                            else -> onUrlIntercept(urlStr)
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
