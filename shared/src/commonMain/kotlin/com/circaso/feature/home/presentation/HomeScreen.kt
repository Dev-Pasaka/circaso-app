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
    Wallet("Wallet", CircasoIcon.Wallet),
    You("You", CircasoIcon.Person),
}

/**
 * The app once signed in: Chats, Wallet and You, with the floating tab bar (design C1). Chats and Wallet have no
 * backend yet, so they say so plainly instead of showing fake data. The selected tab survives rotation and process death.
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
    you: @Composable (bottomPadding: PaddingValues) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(Tab.Chats.ordinal) }
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
    CircasoScaffold(
        grouped = tab == Tab.You,
        // No title bar on the tabs (Pasaka, 2026-10-30): the tab bar already says where you are, and each screen's
        // actions live in it (the Chats button, the eye on the wallet card).
        topBar = {},
        banner = { when (tab) { Tab.Chats -> chatsBanner(); Tab.Wallet -> ConnectionBanner(); Tab.You -> Unit } },
        bottomBar = {
            CircasoTabBar(
                items = Tab.entries.map { TabItem(it.label, it.icon) },
                selectedIndex = selected,
                onSelect = { selected = it },
            )
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
        when (tab) {
            Tab.You -> you(padding)
            Tab.Chats -> chats(padding)
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
