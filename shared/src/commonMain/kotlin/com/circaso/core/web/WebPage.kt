package com.circaso.core.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Why a page didn't load: no network at all, or the site itself (down, refused, a bad certificate). */
enum class WebLoadError { Offline, Failed }

/**
 * The live state of the page in the in-app browser. The platform web view writes it; the browser's chrome reads it
 * and drives the page through [navigator].
 */
@Stable
class WebPageState(val initialUrl: String) {
    /** The page now showing (it changes as the person follows links). */
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf<String?>(null)

    /** 0..1 while loading. */
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(true)

    /** The page has drawn something: until then the browser shows its own loading state, not a blank page. */
    var painted by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var error by mutableStateOf<WebLoadError?>(null)

    /** Set by the platform web view once it exists; null again if its page process died. */
    var navigator: WebNavigator? = null

    /** Bumped to make a fresh web view (after its page process died). */
    var generation by mutableIntStateOf(0)

    fun goBack() = navigator?.goBack()

    fun goForward() = navigator?.goForward()

    fun reload() {
        error = null
        painted = false
        loading = true
        progress = 0f
        val live = navigator
        if (live != null) live.reload() else generation++
    }

    /** What the platform reports when a main-page load fails; [notConnected] is the platform's own "no internet". */
    fun onLoadFailed(notConnected: Boolean, osOffline: Boolean) {
        error = loadError(notConnected, osOffline)
        loading = false
        progress = 0f
        // The retry is covered by the opening state, never by the platform's own error page.
        painted = false
    }
}

/** Offline when the platform says the request found no network or the OS reports none; otherwise the site failed. */
fun loadError(notConnected: Boolean, osOffline: Boolean): WebLoadError =
    if (notConnected || osOffline) WebLoadError.Offline else WebLoadError.Failed

interface WebNavigator {
    fun goBack()
    fun goForward()

    /** Loads the page again; after a first load that failed, loads [WebPageState.url]. */
    fun reload()
}

/**
 * The platform web view (Android `WebView`, iOS `WKWebView`) showing [state]'s page. JavaScript is on because block
 * explorers need it; there is no file access, no JavaScript bridge, no third-party cookies, and no new windows (a
 * link that asks for one opens in place). Every navigation goes through [decideNavigation].
 */
@Composable
expect fun PlatformWebView(state: WebPageState, modifier: Modifier = Modifier)
