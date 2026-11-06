@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.circaso.core.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.openInSystem
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDataNotAllowed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLRequest
import platform.UIKit.UIColor
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject

@Composable
actual fun PlatformWebView(state: WebPageState, modifier: Modifier) {
    val connectivity: ConnectivityMonitor = koinInject()
    // WKWebView holds its delegates weakly: keep them here for as long as the view lives.
    val delegate = remember(state) { NavDelegate(state) { connectivity.osStatus.value == ConnectionStatus.Offline } }
    val view = remember(state) { makeWebView(state, delegate) }
    UIKitView(
        factory = { view },
        modifier = modifier,
        // Touches go straight to the page (no cooperative delay) and VoiceOver reads it.
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative, isNativeAccessibilityEnabled = true),
        onRelease = {
            if (state.navigator === delegate.navigator) state.navigator = null
            it.stopLoading()
            it.navigationDelegate = null
            it.UIDelegate = null
        },
    )
    // WKWebView reports progress, title and history through KVO; reading them a few times a second is simpler and
    // costs nothing next to the page itself.
    LaunchedEffect(view) {
        while (true) {
            state.progress = view.estimatedProgress.toFloat()
            view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { state.title = it }
            // While a load is in flight WKWebView.URL is already the new address over the old page: the address only
            // follows it between loads (same-page history changes); a new page's address is set when it commits.
            if (!view.loading) view.URL?.absoluteString?.let { state.url = it }
            state.canGoBack = view.canGoBack
            state.canGoForward = view.canGoForward
            delay(if (view.loading) 100 else 400)
        }
    }
}

private fun makeWebView(state: WebPageState, delegate: NavDelegate): WKWebView {
    val config = WKWebViewConfiguration().apply {
        preferences.javaScriptCanOpenWindowsAutomatically = false
        defaultWebpagePreferences.allowsContentJavaScript = true // explorers are JavaScript apps
        allowsInlineMediaPlayback = true
        // No script message handlers (no JavaScript bridge). WebKit blocks third-party cookies by default.
    }
    return WKWebView(frame = CGRectZero.readValue(), configuration = config).apply {
        navigationDelegate = delegate
        UIDelegate = delegate
        // Back is the app's own (the browser's BackHandler and its Back button): WebKit's edge swipe on top of the
        // app's back gesture would go back twice.
        allowsBackForwardNavigationGestures = false
        // Pages that set no background assume white; the opening state covers the view until it draws.
        setOpaque(true)
        backgroundColor = UIColor.whiteColor
        scrollView.backgroundColor = UIColor.whiteColor
        delegate.view = this
        state.navigator = delegate.navigator
        NSURL.URLWithString(state.url)?.let { loadRequest(NSURLRequest.requestWithURL(it)) }
    }
}

private class NavDelegate(
    private val state: WebPageState,
    private val osOffline: () -> Boolean,
) : NSObject(), WKNavigationDelegateProtocol, WKUIDelegateProtocol {
    var view: WKWebView? = null

    /** Kotlin/Native can't mix a Kotlin interface into an Objective-C class: the navigator is its own object. */
    val navigator = object : WebNavigator {
        override fun goBack() { view?.goBack() }

        override fun goForward() { view?.goForward() }

        override fun reload() {
            val web = view ?: return
            if (web.URL == null) NSURL.URLWithString(state.url)?.let { web.loadRequest(NSURLRequest.requestWithURL(it)) } else web.reload()
        }
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        if (url == null) { decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel); return }
        val mainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: true
        val gesture = decidePolicyForNavigationAction.navigationType == WKNavigationTypeLinkActivated
        when (val decision = decideNavigation(url, mainFrame, gesture)) {
            NavDecision.Load -> decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            NavDecision.OpenInSystem -> { decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel); openInSystem(url) }
            is NavDecision.LoadInstead -> {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                NSURL.URLWithString(decision.url)?.let { webView.loadRequest(NSURLRequest.requestWithURL(it)) }
            }
            NavDecision.Block -> decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        state.loading = true
        state.progress = 0f
        state.error = null
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) {
        webView.URL?.absoluteString?.let { state.url = it }
        if (state.error == null) state.painted = true
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        state.loading = false
        state.progress = 1f
        if (state.error == null) state.painted = true
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) = failed(withError)

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) = failed(withError)

    private fun failed(error: NSError) {
        // A cancelled load (a new link tapped mid-load, a navigation this delegate refused) is not a failure.
        if (error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled) return
        if (error.domain == "WebKitErrorDomain" && error.code == 102L) return // frame load interrupted by policy
        val notConnected = error.domain == NSURLErrorDomain &&
            (error.code == NSURLErrorNotConnectedToInternet || error.code == NSURLErrorDataNotAllowed)
        state.onLoadFailed(notConnected, osOffline())
    }

    /** The page's process died (memory): load it again rather than show a blank view. */
    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        webView.reload()
    }

    /** A link that asks for a new window opens in place; the page never gets a second web view. */
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        val url = forNavigationAction.request.URL?.absoluteString ?: return null
        val gesture = forNavigationAction.navigationType == WKNavigationTypeLinkActivated
        when (val decision = decideNavigation(url, mainFrame = true, userGesture = gesture)) {
            NavDecision.Load -> webView.loadRequest(forNavigationAction.request)
            NavDecision.OpenInSystem -> openInSystem(url)
            is NavDecision.LoadInstead -> NSURL.URLWithString(decision.url)?.let { webView.loadRequest(NSURLRequest.requestWithURL(it)) }
            NavDecision.Block -> Unit
        }
        return null
    }
}
