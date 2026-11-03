package com.circaso.feature.home.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

private enum class Tab(val label: String, val icon: CircasoIcon) {
    Chats("Chats", CircasoIcon.Chat),
    Spaces("Spaces", CircasoIcon.Spaces),
    Wallet("Wallet", CircasoIcon.Wallet),
    You("You", CircasoIcon.Person),
}

/**
 * The app once signed in: Chats, Spaces, Wallet and You, with the floating tab bar (design C1, Spaces S1). Chats and Wallet have no
 * backend yet, so they say so plainly instead of showing fake data. The selected tab survives rotation and process death.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun HomeScreen(
    chats: @Composable (bottomPadding: PaddingValues) -> Unit,
    chatsActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    chatsBanner: @Composable () -> Unit,
    /** Spaces (phase 1: Moments). */
    spaces: @Composable (bottomPadding: PaddingValues) -> Unit = {},
    spacesOverlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    spacesBanner: @Composable () -> Unit = {},
    /** New moments from my contacts: a dot on the Spaces tab (S1). */
    spacesUnread: Boolean = false,
    wallet: @Composable (bottomPadding: PaddingValues) -> Unit,
    walletActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    /** Each tab's sheets: drawn in the scaffold's overlay so they cover the floating tab bar, as `cx-sheet` does. */
    chatsOverlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    walletOverlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    /** The tab shown first (previews); the person's choice is remembered from then on. */
    initialTab: Int = Tab.Chats.ordinal,
    you: @Composable (bottomPadding: PaddingValues) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(initialTab) }
    val tab = Tab.entries[selected]
    // Back from Wallet or You goes to Chats first; Back from Chats asks before leaving (the user, 2026-10-31), so a
    // stray swipe never closes the app. Only Android has a Back that leaves; iOS apps don't close themselves.
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    // iOS sends its edge swipe here too: there it would only jump tabs and offer a Leave that can't leave, so Android only.
    val android = com.circaso.designsystem.platform.LocalPlatformStyle.current == com.circaso.designsystem.platform.PlatformStyle.Android && com.circaso.core.platform.canExitApp
    val covered = com.circaso.core.navigation.LocalListHasDetail.current
    androidx.compose.ui.backhandler.BackHandler(enabled = android && !confirmExit && !covered) {
        if (tab != Tab.Chats) selected = Tab.Chats.ordinal else confirmExit = true
    }
    val layout = com.circaso.designsystem.adaptive.LocalWindowLayout.current
    val rail = layout.short || layout.widthClass != com.circaso.designsystem.adaptive.WidthClass.Compact
    CircasoScaffold(
        grouped = tab == Tab.You,
        // On its side, Wallet and You are laid out in two columns: a wider frame for them; Chats stays one column.
        maxContentWidth = if (layout.short && tab != Tab.Chats && tab != Tab.Spaces) com.circaso.designsystem.adaptive.WindowLayout.WideContentWidth else com.circaso.designsystem.adaptive.WindowLayout.ReadableWidth,
        // No title bar on the tabs (Pasaka, 2026-10-30): the tab bar already says where you are, and each screen's
        // actions live in it (the Chats button, the eye on the wallet card).
        banner = { when (tab) { Tab.Chats -> chatsBanner(); Tab.Spaces -> spacesBanner(); Tab.Wallet -> ConnectionBanner(); Tab.You -> Unit } },
        // On its side, or with room to spare, the tabs move to the left edge (Material's rail, iPad's sidebar);
        // in a split's narrow list pane they stay at the bottom of the pane.
        bottomBar = if (rail) null else {
            {
                CircasoTabBar(
                    items = Tab.entries.map { TabItem(it.label, it.icon, unread = it == Tab.Spaces && spacesUnread && tab != Tab.Spaces) },
                    selectedIndex = selected,
                    onSelect = { selected = it },
                )
            }
        },
        sideBar = if (!rail) null else {
            {
                CircasoTabBar(
                    items = Tab.entries.map { TabItem(it.label, it.icon, unread = it == Tab.Spaces && spacesUnread && tab != Tab.Spaces) },
                    selectedIndex = selected,
                    onSelect = { selected = it },
                    vertical = true,
                )
            }
        },
        overlay = {
            when (tab) { Tab.Chats -> chatsOverlay(); Tab.Spaces -> spacesOverlay(); Tab.Wallet -> walletOverlay(); Tab.You -> Unit }
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
        when (tab) {
            Tab.You -> you(padding)
            Tab.Chats -> chats(padding)
            Tab.Spaces -> spaces(padding)
            Tab.Wallet -> wallet(padding)
        }
    }
}

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
