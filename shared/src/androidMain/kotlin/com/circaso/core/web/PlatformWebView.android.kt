package com.circaso.core.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.openInSystem
import org.koin.compose.koinInject

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun PlatformWebView(state: WebPageState, modifier: Modifier) {
    val connectivity: ConnectivityMonitor = koinInject()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // Pages that set no background assume white; the opening state covers the view until it draws.
                setBackgroundColor(android.graphics.Color.WHITE)
                settings.apply {
                    javaScriptEnabled = true // explorers are JavaScript apps
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    setGeolocationEnabled(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false) // target=_blank opens in place
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    mediaPlaybackRequiresUserGesture = true
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) safeBrowsingEnabled = true
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                // No addJavascriptInterface, no DownloadListener (downloads do nothing), no file chooser.
                webViewClient = Client(state) { connectivity.osStatus.value == ConnectionStatus.Offline }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        state.progress = newProgress / 100f
                        if (newProgress >= 100) state.loading = false
                    }

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        state.title = title?.takeIf { it.isNotBlank() && !it.startsWith("http") }
                    }

                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
                }
                val navigator = object : WebNavigator {
                    override fun goBack() { if (canGoBack()) goBack() }
                    override fun goForward() { if (canGoForward()) goForward() }
                    override fun reload() { if (url == null) loadUrl(state.url) else reload() }
                }
                tag = navigator
                state.navigator = navigator
                loadUrl(state.url)
            }
        },
        onRelease = { view ->
            if (state.navigator === view.tag) state.navigator = null
            view.stopLoading()
            view.destroy()
        },
    )
}

/** Chromium's "no network at all" errors. A name that doesn't resolve or an unreachable address is the site's problem
 *  (a dead or mistyped domain) unless the OS also reports no network. */
private val OfflineErrors = listOf("ERR_INTERNET_DISCONNECTED", "ERR_NETWORK_CHANGED")

private class Client(private val state: WebPageState, private val osOffline: () -> Boolean) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return when (val decision = decideNavigation(url, request.isForMainFrame, request.hasGesture())) {
            NavDecision.Load -> false
            NavDecision.OpenInSystem -> { openInSystem(url); true }
            is NavDecision.LoadInstead -> { view.loadUrl(decision.url); true }
            NavDecision.Block -> true
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        // The address stays the page on screen until the new one commits (doUpdateVisitedHistory): a page that starts
        // loading a bank's address and stalls must not get the bank's domain and lock over its own content.
        state.loading = true
        state.progress = 0f
        state.error = null
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        if (state.error == null) state.painted = true
    }

    override fun onPageFinished(view: WebView, url: String?) {
        state.loading = false
        if (state.error == null) state.painted = true
        sync(view)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        url?.let { state.url = it }
        sync(view)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        // Chromium's own "no connection" errors: the person's network, not the site (captive Wi-Fi, no route).
        val description = error.description?.toString().orEmpty()
        val notConnected = OfflineErrors.any { it in description }
        state.onLoadFailed(notConnected = notConnected, osOffline = osOffline())
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Never proceed past a bad certificate.
        handler.cancel()
        if (error.url == view.url || !state.painted) state.onLoadFailed(notConnected = false, osOffline = false)
    }

    /** The page's renderer died (memory): show the error instead of taking the app down with it. */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // Retrying makes a new web view (see WebPageState.reload).
        if (state.navigator === view.tag) state.navigator = null
        state.onLoadFailed(notConnected = false, osOffline = false)
        return true
    }

    private fun sync(view: WebView) {
        state.canGoBack = view.canGoBack()
        state.canGoForward = view.canGoForward()
    }
}
