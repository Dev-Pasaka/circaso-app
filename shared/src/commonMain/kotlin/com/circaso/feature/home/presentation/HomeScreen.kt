package com.circaso.feature.home.presentation

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.circaso.designsystem.chrome.ConfirmSheet
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoTabBar
import com.circaso.designsystem.chrome.LargeTitleBar
import com.circaso.designsystem.chrome.TabItem
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.theme.CircasoTheme

internal enum class Tab(val label: String, val icon: CircasoIcon) {
    Chats("Chats", CircasoIcon.Chat),
    Wallet("Wallet", CircasoIcon.Wallet),
    You("You", CircasoIcon.Person),
}

/**
 * The app once signed in: Chats, Wallet and You, with the floating tab bar (design C1). Spaces stopped on 2026-11-05:
 * its tab is gone and Moments live in Chats (the Moments chip). The selected tab survives rotation and process death.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun HomeScreen(
    chats: @Composable (bottomPadding: PaddingValues) -> Unit,
    chatsActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    chatsBanner: @Composable () -> Unit,
    wallet: @Composable (bottomPadding: PaddingValues) -> Unit,
    walletActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    /** Each tab's sheets: drawn in the scaffold's overlay so they cover the floating tab bar, as `cx-sheet` does. */
    chatsOverlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    walletOverlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    /** The tab shown first (previews); the person's choice is remembered from then on. */
    initialTab: Int = Tab.Chats.ordinal,
    /**
     * The tab, when the navigation owns it (each tab keeps its own screens in the detail pane: core/navigation/TabStacks).
     * Null: kept here (previews).
     */
    selectedTab: Int? = null,
    onSelectTab: ((Int) -> Unit)? = null,
    /** Back's jump to Chats: a step back, so it never brings back a screen Chats had open. Null: like a tab tap. */
    onBackToTab: ((Int) -> Unit)? = null,
    you: @Composable (bottomPadding: PaddingValues) -> Unit,
) {
    var own by rememberSaveable { mutableStateOf(initialTab) }
    val selected = selectedTab ?: own
    val select: (Int) -> Unit = { value -> if (onSelectTab != null) onSelectTab(value) else own = value }
    // A tab index saved by an older build (four tabs, Spaces second) can't point past the end.
    val tab = Tab.entries.getOrElse(selected) { Tab.Chats }
    // A tapped notification can ask for a tab (a "set up your wallet" one opens Wallet); taken once.
    val wantedTab by com.circaso.core.notifications.HomeTabRequests.wanted.collectAsState()
    androidx.compose.runtime.LaunchedEffect(wantedTab) {
        val wanted = wantedTab ?: return@LaunchedEffect
        select(Tab.valueOf(wanted.name).ordinal)
        com.circaso.core.notifications.HomeTabRequests.wanted.value = null
    }
    // Back from Wallet or You goes to Chats first; Back from Chats asks before leaving (the user, 2026-10-31), so a
    // stray swipe never closes the app. Only Android has a Back that leaves; iOS apps don't close themselves.
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    // iOS sends its edge swipe here too: there it would only jump tabs and offer a Leave that can't leave, so Android only.
    val android = com.circaso.designsystem.platform.LocalPlatformStyle.current == com.circaso.designsystem.platform.PlatformStyle.Android && com.circaso.core.platform.canExitApp
    val covered = com.circaso.core.navigation.LocalListHasDetail.current
    androidx.compose.ui.backhandler.BackHandler(enabled = android && !confirmExit && !covered) {
        if (tab != Tab.Chats) (onBackToTab ?: select)(Tab.Chats.ordinal) else confirmExit = true
    }
    val layout = com.circaso.designsystem.adaptive.LocalWindowLayout.current
    // A rail on a phone on its side, a wide window, and in a tablet's list pane: one place for the tabs on a tablet,
    // whichever tab is showing.
    val rail = layout.short || layout.widthClass != com.circaso.designsystem.adaptive.WidthClass.Compact || com.circaso.designsystem.adaptive.LocalTabsAsRail.current
    CircasoScaffold(
        grouped = tab == Tab.You,
        // On its side, Wallet and You are laid out in two columns: a wider frame for them; Chats stays one column.
        maxContentWidth = if (layout.short && tab != Tab.Chats) com.circaso.designsystem.adaptive.WindowLayout.WideContentWidth else com.circaso.designsystem.adaptive.WindowLayout.ReadableWidth,
        // No title bar on the tabs (Pasaka, 2026-10-30): the tab bar already says where you are, and each screen's
        // actions live in it (the Chats button, the eye on the wallet card).
        banner = { when (tab) { Tab.Chats -> chatsBanner(); Tab.Wallet -> ConnectionBanner(); Tab.You -> Unit } },
        // On its side, with room to spare, or in a tablet's list pane, the tabs move to the left edge (Material's
        // rail, iPad's sidebar), in the same place on every tab; a phone upright keeps them at the bottom.
        bottomBar = if (rail) null else {
            {
                CircasoTabBar(
                    items = Tab.entries.map { TabItem(it.label, it.icon) },
                    selectedIndex = tab.ordinal,
                    onSelect = { select(it) },
                )
            }
        },
        sideBar = if (!rail) null else {
            {
                CircasoTabBar(
                    items = Tab.entries.map { TabItem(it.label, it.icon) },
                    selectedIndex = tab.ordinal,
                    onSelect = { select(it) },
                    vertical = true,
                )
            }
        },
        overlay = {
            when (tab) { Tab.Chats -> chatsOverlay(); Tab.Wallet -> walletOverlay(); Tab.You -> Unit }
            ConfirmSheet(
                visible = confirmExit,
                title = "Leave Circaso?",
                message = "Messages and money keep arriving while you're away; you'll get a notification.",
                confirmLabel = "Leave",
                onConfirm = { confirmExit = false; com.circaso.core.platform.exitApp() },
                onDismiss = { confirmExit = false },
                cancelLabel = "Stay",
            )
        },
    ) { padding ->
        // Each tab, once visited, stays composed and is only revealed (Pasaka, 2026-11-07: switching felt slow because
        // every tap tore the tab down and built it again; Wallet is heavy). The others sit underneath, invisible,
        // untouchable and silent to screen readers; a short crossfade hands one to the next.
        val visited = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(setOf(tab.ordinal)) }
        if (tab.ordinal !in visited.value) visited.value = visited.value + tab.ordinal
        // Bumped each time a tab comes to the front, so a tab's own arrival motion can play again.
        val arrivals = androidx.compose.runtime.remember { mutableStateOf(0) }
        androidx.compose.runtime.LaunchedEffect(tab) { arrivals.value++ }
        val still = CircasoTheme.reduceMotion
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            Tab.entries.filter { it.ordinal in visited.value }.forEach { t ->
                val front = t == tab
                androidx.compose.runtime.key(t) {
                val alpha by androidx.compose.animation.core.animateFloatAsState(
                    if (front) 1f else 0f, androidx.compose.animation.core.tween(if (still) 0 else TabFadeMs), label = "tab",
                )
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxSize()
                            .then(if (front) Modifier.zIndex(1f) else Modifier.zIndex(0f))
                            .graphicsLayer { this.alpha = alpha }
                            .then(if (front) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() } }
                            }),
                    ) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalTabArrival provides if (front) arrivals.value else -1, com.circaso.designsystem.chrome.LocalInFront provides front) {
                            when (t) {
                                Tab.You -> you(padding)
                                Tab.Chats -> chats(padding)
                                Tab.Wallet -> wallet(padding)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A quick handover between tabs: long enough to read as motion, short enough to feel instant. */
private const val TabFadeMs = 120

/**
 * How many times the tab this is read in has come to the front (-1 while it is hidden behind another): a tab keyed on
 * it replays its arrival motion, since tabs now stay alive between visits.
 */
val LocalTabArrival = androidx.compose.runtime.compositionLocalOf { 0 }

@Composable
private fun ComingSoon(title: String, message: String, padding: PaddingValues) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(title, Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(message, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
}
