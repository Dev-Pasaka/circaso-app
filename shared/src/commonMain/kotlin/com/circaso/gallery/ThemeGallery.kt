package com.circaso.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.circaso.core.platform.DeviceKeyStore
import com.circaso.feature.auth.domain.AuthRepository
import com.circaso.core.session.AuthManager
import com.circaso.core.session.DeviceIdentity
import com.circaso.core.sync.ConnectionBanner
import com.circaso.feature.auth.domain.SignOutUseCase
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxType
import org.koin.compose.koinInject
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.CircasoTabBar
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.chrome.LargeTitleBar
import com.circaso.designsystem.chrome.TabItem
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.platform.PlatformStyle
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** Debug screen that shows every token and component, so each theme can be checked against the design canvas. */
@Composable
fun ThemeGallery(darkTheme: Boolean, highContrast: Boolean, onToggleDark: () -> Unit, onToggleContrast: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var sheet by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val outbox: Outbox = koinInject()
    val signOut: SignOutUseCase = koinInject()
    val waiting by outbox.waitingMessages.collectAsState(0)
    val platformName = if (CircasoTheme.platform == PlatformStyle.Ios) "iOS" else "Android"
    CircasoScaffold(
        topBar = {
            LargeTitleBar("Circaso · $platformName") {
                CircasoIconButton(CircasoIcon.Clock, "Queue 2 demo messages, or clear the queue", {
                    scope.launch {
                        if (waiting == 0) repeat(2) { outbox.enqueue(OutboxType.ChatMessage, "demo") } else outbox.discardAll()
                    }
                })
                CircasoIconButton(CircasoIcon.Check, "Show the back online toast", { scope.launch { toast.show("Back online · 2 messages sent", com.circaso.designsystem.chrome.ToastKind.Done) } }, emphasised = true)
            }
        },
        banner = { ConnectionBanner(showing = "Showing the gallery", waitingCount = waiting) },
        bottomBar = {
            CircasoTabBar(
                items = listOf(TabItem("Chats", CircasoIcon.Chat, unread = true), TabItem("Wallet", CircasoIcon.Wallet), TabItem("You", CircasoIcon.Person)),
                selectedIndex = tab,
                onSelect = { tab = it },
            )
        },
        toast = { ToastHost(toast) },
        overlay = {
            ConfirmSheet(
                visible = confirm,
                title = "Sign out of Circaso?",
                message = "You can sign back in with Google or Apple. Your chats and money stay safe, and this phone stays yours.",
                confirmLabel = "Sign Out",
                icon = com.circaso.designsystem.components.CircasoIcon.SignOut,
                onConfirm = { confirm = false; scope.launch { signOut() } },
                onDismiss = { confirm = false },
            )
            CircasoSheet(visible = sheet, onDismiss = { sheet = false }, label = "Example sheet") {
                BasicText("Example sheet", style = t.title2.copy(color = c.ink))
                BasicText("Drag down, tap outside or go back to close.", style = t.body.copy(color = c.inkSecondary))
                CircasoButton("Done", { sheet = false }, size = ButtonSize.Large, block = true)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = CircasoSpacing.s5, end = CircasoSpacing.s5, top = CircasoSpacing.s4, bottom = CircasoSpacing.s4 + padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                Toggle(if (darkTheme) "Dark" else "Light", onToggleDark)
                Toggle(if (highContrast) "High contrast" else "Normal contrast", onToggleContrast)
            }
            Label("Chrome", t.title2)
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                CircasoButton("Sheet", { sheet = true }, style = ButtonStyle.Secondary)
                CircasoButton("Confirm", { confirm = true }, style = ButtonStyle.Secondary)
            }
            CircasoNavBar(title = "Mum", subtitle = "Waiting for network", onBack = {})
            Banner("You’re offline. Money sends only while you’re connected.", CircasoIcon.Offline, tone = BannerTone.Warning)
            SessionSection()
            ComponentGallery()
            Label("Colours", t.title2)
            listOf(
                "bg" to c.bg, "bgGrouped" to c.bgGrouped, "surface" to c.surface, "surfaceRaised" to c.surfaceRaised,
                "fill" to c.fill, "fillStrong" to c.fillStrong, "separator" to c.separator, "ink" to c.ink,
                "inkSecondary" to c.inkSecondary, "inkTertiary" to c.inkTertiary, "action" to c.action,
                "moneyCard" to c.moneyCard, "critical" to c.critical, "warning" to c.warning,
            ).forEach { (name, color) -> Swatch(name, color) }
            Label("Type", t.title2)
            listOf(
                "Large title" to t.largeTitle, "Title 1" to t.title1, "Title 2" to t.title2, "Title 3" to t.title3,
                "Headline" to t.headline, "Body" to t.body, "Callout" to t.callout, "Subhead" to t.subhead,
                "Footnote" to t.footnote, "Caption 1" to t.caption1, "Caption 2" to t.caption2,
                "KSh 12,450.00" to t.amountMedium,
            ).forEach { (text, style) -> Label(text, style) }
        }
    }
}

@Composable
private fun Label(text: String, style: TextStyle) =
    BasicText(text, style = style.copy(color = CircasoTheme.colors.ink))

@Composable
private fun Toggle(text: String, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    Box(
        Modifier
            .background(c.fill, CircasoShapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
    ) { BasicText(text, style = CircasoTheme.typography.subhead.copy(color = c.ink)) }
}

@Composable
private fun Swatch(name: String, color: Color) {
    val c = CircasoTheme.colors
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        Box(Modifier.size(44.dp).background(color, CircasoShapes.sm).border(1.dp, c.separator, CircasoShapes.sm))
        BasicText(name, style = CircasoTheme.typography.body.copy(color = c.ink), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ComponentGallery() {
    val t = CircasoTheme.typography
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
        Label("Buttons", t.title2)
        CircasoButton("Continue", { busy = !busy }, block = true, size = ButtonSize.Large, busy = busy)
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CircasoButton("Secondary", {}, style = ButtonStyle.Secondary)
            CircasoButton("Plain", {}, style = ButtonStyle.Plain)
            CircasoButton("Delete", {}, style = ButtonStyle.Destructive)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CircasoButton("Disabled", {}, enabled = false)
            CircasoButton("Small", {}, size = ButtonSize.Small, style = ButtonStyle.Secondary)
            CircasoIconButton(CircasoIcon.Back, "Back", {})
            CircasoIconButton(CircasoIcon.Check, "Done", {}, emphasised = true)
        }
        Label("Text field", t.title2)
        CircasoTextField(name, { name = it }, label = "Display name", placeholder = "Amina Wanjiru")
        CircasoTextField("abc", {}, label = "Code", error = "That code is not right. 2 tries left.")
        Label("List", t.title2)
        ListGroup {
            ListRow("Amina Wanjiru", sub = "@amina", meta = "Today", leading = { Avatar("Amina Wanjiru") }, onClick = {})
            RowDivider()
            ListRow("Remove this phone", destructive = true, onClick = {})
            RowDivider()
            DetailRow("Phone", "+254 7•• ••• 678")
        }
        Label("Amount", t.title2)
        AmountText("KSh 12,450.00", size = AmountSize.Large)
        Label("PIN", t.title2)
        PinDots(filled = pin.length)
        PinKeypad(onDigit = { if (pin.length < 6) pin += it }, onBackspace = { pin = pin.dropLast(1) })
    }
}

@Composable
private fun SessionSection() {
    val auth: AuthManager = koinInject()
    val authRepo: AuthRepository = koinInject()
    val identity: DeviceIdentity = koinInject()
    val keys: DeviceKeyStore = koinInject()
    val state by auth.state.collectAsState()
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<List<String>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (launchFlag("-self-check")) result = deviceKeySelfCheck(identity, keys)
        if (launchFlag("-ping")) result = pingSandbox(authRepo)
    }
    val t = CircasoTheme.typography
    val c = CircasoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Label("Session and device key", t.title2)
        BasicText("Session: ${state::class.simpleName}", style = t.body.copy(color = c.ink))
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CircasoButton("Check device key", { scope.launch { result = deviceKeySelfCheck(identity, keys) } }, style = ButtonStyle.Secondary, size = ButtonSize.Small)
            CircasoButton("Delete key", { scope.launch { keys.delete(); result = listOf("key deleted") } }, style = ButtonStyle.Destructive, size = ButtonSize.Small)
        }
        CircasoButton("Ping sandbox", { scope.launch { result = pingSandbox(authRepo) } }, style = ButtonStyle.Secondary, size = ButtonSize.Small)
        result.forEach { BasicText(it, style = t.footnote.copy(color = if (it.startsWith("FAIL")) c.critical else c.inkSecondary)) }
    }
}
