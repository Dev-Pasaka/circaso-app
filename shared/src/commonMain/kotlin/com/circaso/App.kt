package com.circaso

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.circaso.core.datastore.AppPreferences
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import com.circaso.core.navigation.AppNavigation
import com.circaso.core.web.InAppBrowser
import com.circaso.core.web.InAppBrowserHost
import com.circaso.core.platform.openUrl
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.circaso.core.network.NetworkEvents
import com.circaso.designsystem.platform.rememberIsHighContrast
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.gallery.ThemeGallery
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import org.koin.compose.koinInject
import com.circaso.core.platform.AppUpdateBar

@Composable
@Preview
fun App() {
    // Pictures come from pre-signed storage links, so they are fetched without our bearer token (a plain Ktor client).
    // Coin marks the app doesn't bundle come from the API's public icon route as SVG, by symbol, and are disk-cached.
    val api: com.circaso.core.network.ApiConfig = koinInject()
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context).components {
            add(com.circaso.designsystem.components.CoinIconMapper(api.baseUrl))
            add(KtorNetworkFetcherFactory())
            add(coil3.svg.SvgDecoder.Factory())
        }.build()
    }
    val events: NetworkEvents = koinInject()
    val prefs: AppPreferences = koinInject()
    val outdated by events.clientOutdated.collectAsState()
    val overrides by prefs.themeOverrides.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    val saved = overrides
    val systemDark = isSystemInDarkTheme()
    val systemHigh = rememberIsHighContrast()
    // Wait for the saved choice (a few milliseconds) so the first frame is never the wrong theme.
    if (saved == null) {
        Box(Modifier.fillMaxSize().background(if (systemDark) Color.Black else Color.White))
        return
    }
    val isDark = saved.dark ?: systemDark
    val isHigh = saved.highContrast ?: systemHigh
    CircasoTheme(darkTheme = isDark, highContrast = isHigh) {
        // In front or not, for the "Online" / "last seen" others see (away 15 s after leaving the app).
        val presence: com.circaso.core.realtime.PresenceReporter = koinInject()
        // And for notifying a message that arrives over the socket while Circaso is away (2026-11-13).
        val liveNotices: com.circaso.core.notifications.LiveMessageNotices = koinInject()
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_START -> { liveNotices.onForeground(); presence.onForeground() }
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> { liveNotices.onBackground(); presence.onBackground() }
                    else -> Unit
                }
            }
            lifecycle.lifecycle.addObserver(observer)
            onDispose { lifecycle.lifecycle.removeObserver(observer) }
        }
        // Every screen knows the space it has (rotation, split screen, tablets): see designsystem/adaptive.
        // Debug only: `-frame=WxH` lays the app out as a window of that size (gallery/DebugFrame.kt).
        com.circaso.gallery.DebugFrame { com.circaso.designsystem.adaptive.ProvideWindowLayout {
            // Links open the Circaso way (core/web): web pages in the in-app browser drawn over everything here, other
            // apps' links (mail, phone, stores) in the system. Text links (Asha's answers) go through LocalUriHandler.
            CompositionLocalProvider(LocalUriHandler provides CircasoUriHandler) {
                val browsing by InAppBrowser.page.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    // While a page is open, screen readers stay in the browser, not in the app underneath it.
                    Box(Modifier.fillMaxSize().then(if (browsing != null) Modifier.clearAndSetSemantics {} else Modifier)) {
                        AppNavigation(forceUpdate = outdated) {
                            ThemeGallery(
                                darkTheme = isDark,
                                highContrast = isHigh,
                                onToggleDark = { scope.launch { prefs.setDark(!isDark) } },
                                onToggleContrast = { scope.launch { prefs.setHighContrast(!isHigh) } },
                            )
                        }
                    }
                    InAppBrowserHost()
                    // A newer Circaso downloaded in the background (Play's flexible update): restart into it.
                    AppUpdateBar()
                }
            }
        } }
    }
}

private object CircasoUriHandler : UriHandler {
    override fun openUri(uri: String) = openUrl(uri)
}
