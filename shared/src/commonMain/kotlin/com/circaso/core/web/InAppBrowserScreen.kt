package com.circaso.core.web

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.openInSystem
import com.circaso.core.platform.shareText
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.motion.CircasoSpring
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.platform.PlatformStyle
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/*
 * The in-app browser (Pasaka, 2026-11-06: external pages open inside Circaso, never by sending the person out to the
 * browser). The design canvas has no frame for it, so these are our calls, written down in the skill's
 * design-system.md ("In-app browser"):
 * - It rises over everything as a full-screen modal (the sheet's spring on iOS, a tween on Android, a fade with
 *   Reduce Motion), like Safari's and Chrome's in-app views, so it reads as "a page on top of Circaso", not a new place.
 * - Top bar: Close, then Back once there is page history, the page's title over its domain (with a lock for https),
 *   and More. The domain is the real host (user info stripped), because in a money app the address is what you trust.
 * - A 2 dp progress line in the action colour under the bar; it fades once the page has loaded.
 * - More opens a sheet (the app's menu pattern, as Asha's): the full address, then Forward (when there is one),
 *   Open in Browser, Copy Link, Share.
 * - System back (Android back, iOS back gesture) goes back in the page's history first, then closes.
 * - Loading, offline and failed states are our own, on the page background, in the app's voice; offline retries by
 *   itself when the connection is back (the G1 wording: "You're offline").
 */

/** Draws the in-app browser over the whole app whenever [InAppBrowser] has a page. Put it last in the app's root. */
@Composable
fun BoxScope.InAppBrowserHost() {
    val page by InAppBrowser.page.collectAsState()
    // The page stays while the browser slides away.
    val last = remember { mutableStateOf<OpenPage?>(null) }
    SideEffect { page?.let { last.value = it } }
    val ios = CircasoTheme.platform == PlatformStyle.Ios
    val still = CircasoTheme.reduceMotion
    AnimatedVisibility(
        page != null,
        Modifier.matchParentSize(),
        enter = if (still) fadeIn(tween(CircasoMotion.QuickMs)) else slideInVertically(if (ios) CircasoSpring.sheet() else tween(CircasoMotion.BaseMs)) { it },
        exit = if (still) fadeOut(tween(CircasoMotion.QuickMs)) else slideOutVertically(tween(CircasoMotion.BaseMs)) { it },
    ) {
        // The first web view in a process loads the browser engine on the main thread: build it once the slide has
        // settled, so the open animation never stutters. The opening state covers the page until then.
        var settled by remember { mutableStateOf(false) }
        LaunchedEffect(transition) {
            snapshotFlow { transition.currentState == transition.targetState }.first { it }
            settled = true
        }
        Box(
            Modifier.fillMaxSize()
                // Every touch stops here: nothing reaches the screen underneath (a money action) through the browser.
                // Being hit is enough; nothing is consumed, so the page still scrolls and the bar's buttons still tap.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .semantics { isTraversalGroup = true },
        ) {
            (page ?: last.value)?.let { shown ->
                key(shown.id) { InAppBrowserScreen(shown.url, onClose = InAppBrowser::close, webViewReady = settled) }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InAppBrowserScreen(url: String, onClose: () -> Unit, webViewReady: Boolean = true) {
    val state = remember(url) { WebPageState(url) }
    val toast = rememberToastState()
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val connectivity: ConnectivityMonitor = koinInject()
    val os by connectivity.osStatus.collectAsState()
    // Offline, the page loads by itself as soon as the connection is back.
    LaunchedEffect(os) { if (os == ConnectionStatus.Online && state.error == WebLoadError.Offline) state.reload() }

    BackHandler(enabled = !menu) {
        if (state.canGoBack && state.error == null) state.goBack() else onClose()
    }

    val domain = displayHost(state.url) ?: displayHost(url) ?: ""
    CircasoScaffold(
        readable = false,
        topBar = { BrowserBar(state, domain, onClose, onMore = { menu = true }) },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp)) },
        overlay = {
            CircasoSheet(menu, { menu = false }, label = "Page options") {
                BrowserMenu(
                    state,
                    onForward = { menu = false; state.goForward() },
                    onOpenInBrowser = { menu = false; openInSystem(state.url) },
                    onCopy = {
                        menu = false
                        clipboard.setText(AnnotatedString(state.url))
                        scope.launch { toast.show("Link copied") }
                    },
                    onShare = { menu = false; shareText(state.url, state.title) },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (webViewReady) key(state.generation) { PlatformWebView(state, Modifier.fillMaxSize()) }
            ProgressLine(state, Modifier.align(Alignment.TopStart))
            val waiting = !state.painted && state.error == null
            AnimatedVisibility(waiting, Modifier.matchParentSize(), enter = fadeIn(tween(0)), exit = fadeOut(tween(CircasoMotion.BaseMs))) {
                Opening(domain)
            }
            state.error?.let { error ->
                LoadProblem(error, domain, onRetry = state::reload, onOpenInBrowser = { openInSystem(state.url) })
            }
        }
    }
}

@Composable
private fun BrowserBar(state: WebPageState, domain: String, onClose: () -> Unit, onMore: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val centered = CircasoTheme.platform == PlatformStyle.Ios
    val secure = linkScheme(state.url) == "https"
    val title = state.title?.takeIf { it.isNotBlank() && it != state.url }
    val heading: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier.semantics(mergeDescendants = true) { heading(); contentDescription = listOfNotNull(title, domain, if (secure) "secure" else "not secure").joinToString(", ") },
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            // Always two lines (title, then lock and domain), so the bar never changes height as the page loads.
            BasicText(title ?: domain, style = t.headline.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            run {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                    if (secure) CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.size(11.dp))
                    else CircasoIconImage(CircasoIcon.Warning, c.warning, Modifier.size(11.dp))
                    BasicText(if (secure) domain else "$domain · Not secure", style = t.caption1.copy(color = if (secure) c.inkSecondary else c.warning), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (com.circaso.designsystem.adaptive.LocalTopBarOnSide.current) {
        // A phone on its side: the bar runs down the left, as the app's other bars do.
        Column(Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s2, top = CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                CircasoIconButton(CircasoIcon.Close, "Close", onClose)
                if (state.canGoBack) CircasoIconButton(CircasoIcon.Back, "Back", { state.goBack() })
                CircasoIconButton(CircasoIcon.More, "More", onMore)
            }
            heading(Modifier)
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlMd).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1),
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircasoIconButton(CircasoIcon.Close, "Close", onClose)
        // iOS: Back keeps its place whether or not there is history, so the centred title doesn't jump. Android leaves
        // it out (as Chrome's custom tabs do): system back already goes back in the page, and an empty slot is a hole.
        if (centered) {
        val backAlpha by animateFloatAsState(if (state.canGoBack) 1f else 0f, if (CircasoTheme.reduceMotion) snap() else tween(CircasoMotion.QuickMs), label = "back")
        CircasoIconButton(
            CircasoIcon.Back, "Back", { state.goBack() },
            Modifier.graphicsLayer { alpha = backAlpha }.then(if (state.canGoBack) Modifier else Modifier.clearAndSetSemantics {}),
            enabled = state.canGoBack,
        )
        }
        heading(Modifier.weight(1f))
        // Balances Close and Back so an iOS title sits in the middle.
        if (centered) Box(Modifier.size(CircasoSizes.iconButton))
        CircasoIconButton(CircasoIcon.More, "More", onMore)
    }
}

/** The thin line under the bar: how far the page has loaded. It fades once the page is in. */
@Composable
private fun ProgressLine(state: WebPageState, modifier: Modifier) {
    val c = CircasoTheme.colors
    val still = CircasoTheme.reduceMotion
    val shown = state.loading && state.error == null
    // Never starts empty (a sliver shows at once, so the tap clearly did something) and only ever moves forward: each
    // new load starts again from the sliver instead of shrinking back from the last page's full line.
    val fraction = remember { Animatable(0.08f) }
    LaunchedEffect(shown) { if (shown) fraction.snapTo(0.08f) }
    LaunchedEffect(shown, state.progress) {
        val target = if (shown) state.progress.coerceIn(0.08f, 1f) else 1f
        if (target > fraction.value) { if (still) fraction.snapTo(target) else fraction.animateTo(target, tween(CircasoMotion.BaseMs)) }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, if (still) snap() else tween(CircasoMotion.BaseMs, delayMillis = if (shown) 0 else CircasoMotion.QuickMs), label = "progress-alpha")
    Box(
        modifier.fillMaxWidth().height(2.dp).graphicsLayer { this.alpha = alpha }
            .semantics { if (shown) progressBarRangeInfo = ProgressBarRangeInfo(state.progress, 0f..1f) },
    ) {
        // Drawn, not laid out: the line moves every frame without measuring anything.
        Box(
            Modifier.fillMaxWidth().height(2.dp).drawBehind {
                drawRoundRect(c.action, size = size.copy(width = size.width * fraction.value), cornerRadius = CornerRadius(size.height / 2))
            },
        )
    }
}

/** Before the page draws anything: the domain it's opening, on the page background (never a blank white flash). */
@Composable
private fun Opening(domain: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxSize().background(c.bg).padding(horizontal = CircasoSpacing.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically),
    ) {
        Spinner(c.inkSecondary)
        BasicText("Opening $domain", style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LoadProblem(error: WebLoadError, domain: String, onRetry: () -> Unit, onOpenInBrowser: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val offline = error == WebLoadError.Offline
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).padding(horizontal = CircasoSpacing.s8),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            CircasoIconImage(if (offline) CircasoIcon.Offline else CircasoIcon.Globe, c.inkTertiary, Modifier.size(CircasoSpacing.s10))
            BasicText(
                if (offline) "You’re offline" else "This page didn’t load",
                Modifier.semantics { heading() },
                style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center),
            )
            BasicText(
                if (offline) "The page opens by itself when you’re back online."
                else "$domain isn’t answering right now. Try again, or open it in your browser.",
                style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
            )
            Column(Modifier.padding(top = CircasoSpacing.s3), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                CircasoButton("Try Again", onRetry, style = if (offline) ButtonStyle.Secondary else ButtonStyle.Primary, size = ButtonSize.Medium, block = true)
                if (!offline) CircasoButton("Open in Browser", onOpenInBrowser, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
            }
        }
    }
}

@Composable
private fun BrowserMenu(state: WebPageState, onForward: () -> Unit, onOpenInBrowser: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // The full address first: in a money app, where a link really goes is worth one glance.
    Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s1), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(
            state.title?.takeIf { it.isNotBlank() } ?: (displayHost(state.url) ?: ""),
            Modifier.semantics { heading() },
            style = t.headline.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        BasicText(state.url, style = t.footnote.copy(color = c.inkSecondary), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(if (c.isDark) c.fill else c.bgGrouped)) {
        if (state.canGoForward) {
            MenuRow("Forward", CircasoIcon.ChevronRight, onForward)
            MenuDivider()
        }
        MenuRow("Open in Browser", CircasoIcon.Globe, onOpenInBrowser)
        MenuDivider()
        MenuRow("Copy Link", CircasoIcon.Link, onCopy)
        MenuDivider()
        MenuRow("Share", CircasoIcon.Share, onShare)
    }
}

@Composable
private fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(CircasoTheme.colors.separator))
}

/** A row in the menu group: a 28 dp icon tile and the action (the app's sheet-menu row, as in Asha's menu). */
@Composable
private fun MenuRow(title: String, icon: CircasoIcon, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().pressable(role = Role.Button, onClick = onClick).defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = CircasoSpacing.s3, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Box(Modifier.size(CircasoSizes.avatarSm).clip(CircasoShapes.sm).background(c.fillStrong), contentAlignment = Alignment.Center) {
            CircasoIconImage(icon, c.ink, Modifier.size(16.dp))
        }
        BasicText(title, Modifier.weight(1f), style = t.body.copy(color = c.ink, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
