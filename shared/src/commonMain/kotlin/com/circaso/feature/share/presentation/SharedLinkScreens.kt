package com.circaso.feature.share.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.AmountDisplay
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.DetailRow
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.components.SkeletonBlock
import com.circaso.designsystem.components.TextBadge
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.share.domain.OpenTarget
import com.circaso.feature.share.domain.ShareKind
import com.circaso.feature.share.domain.ShareLink
import com.circaso.feature.share.domain.SharedAddress
import com.circaso.feature.share.domain.SharedLink
import com.circaso.feature.share.domain.SharedPerson
import com.circaso.feature.share.domain.SharedReceipt
import com.circaso.feature.share.domain.SharedStatement
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.domain.formatFiat
import com.circaso.feature.wallet.domain.shortAddress
import kotlin.time.Instant

/** A share link opened on this phone: what its page shows, drawn natively, and the one thing this person can do. */
@Composable
fun SharedLinkScreen(viewModel: SharedLinkViewModel, onBack: () -> Unit, go: (SharedLinkDestination) -> Unit) {
    val state by viewModel.state.collectAsState()
    SharedLinkContent(state, onBack, onRetry = viewModel::load, onOpen = { viewModel.onOpen(go) }, onMessageShown = viewModel::onMessageShown)
}

@Composable
fun SharedLinkContent(state: SharedLinkUiState, onBack: () -> Unit, onRetry: () -> Unit, onOpen: () -> Unit, onMessageShown: () -> Unit = {}) {
    val toast = rememberToastState()
    LaunchedEffect(state.message) { state.message?.let { toast.show(it); onMessageShown() } }
    val link = (state.load as? SharedLinkLoad.Loaded)?.link
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = navTitle(link), onBack = onBack) },
        // The offline body already says it; the banner is for a link that's open when the connection drops.
        banner = { if (state.load != SharedLinkLoad.Offline) ConnectionBanner(showing = "Some actions need a connection") },
        toast = { ToastHost(toast) },
        bottomBar = link?.let { l -> actionLabel(l)?.let { label -> { BottomAction(label, state.opening, enabled = state.online || !state.actionNeedsConnection, onOpen) } } },
    ) { padding ->
        when (val load = state.load) {
            SharedLinkLoad.Loading -> LoadingBody()
            SharedLinkLoad.NotFound -> MessageBody(
                icon = CircasoIcon.Link,
                title = "This link is no longer available",
                text = "Whoever shared it may have turned it off, or it ended.",
                action = null,
                onAction = {},
            )
            SharedLinkLoad.Offline -> MessageBody(
                icon = CircasoIcon.Offline,
                title = "You're offline",
                text = "Opening a shared link needs a connection. It'll be here when you're back online.",
                action = "Try Again",
                onAction = onRetry,
            )
            is SharedLinkLoad.Failed -> MessageBody(CircasoIcon.Warning, "Couldn't open this link", load.message, "Try Again", onRetry)
            is SharedLinkLoad.Loaded -> LinkBody(load.link, padding)
        }
    }
}

private fun navTitle(link: SharedLink?): String = when (link?.kind) {
    ShareKind.Receipt -> "Payment receipt"
    ShareKind.Statement -> "Group statement"
    ShareKind.Invite -> "Invite"
    ShareKind.PayMe -> "Pay on Circaso"
    ShareKind.Address -> "Wallet address"
    null -> "Shared link"
}

internal fun actionLabel(link: SharedLink): String? = when (val o = link.open) {
    is OpenTarget.Transfer -> "View Payment"
    is OpenTarget.Chama -> "Open Group Wallet"
    is OpenTarget.Invite -> "Chat with ${firstName(o.displayName ?: link.person?.displayName) ?: "Them"}"
    is OpenTarget.Pay -> "Pay ${firstName(o.displayName ?: link.person?.displayName) ?: "Them"}"
    is OpenTarget.PayAddress -> "Pay This Address"
    null -> null
}

private fun firstName(name: String?) = name?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }

@Composable
private fun BottomAction(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        if (!enabled) {
            BasicText(
                "Needs a connection",
                style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CircasoButton(label, onClick, size = ButtonSize.Large, block = true, busy = busy, enabled = enabled)
    }
}

@Composable
private fun LoadingBody() {
    Column(Modifier.fillMaxSize().padding(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), horizontalAlignment = Alignment.CenterHorizontally) {
        SkeletonBlock(Modifier.padding(top = CircasoSpacing.s6).width(120.dp).height(14.dp))
        SkeletonBlock(Modifier.width(200.dp).height(40.dp))
        SkeletonBlock(Modifier.padding(top = CircasoSpacing.s4).fillMaxWidth().height(168.dp), CircasoShapes.lg)
    }
}

@Composable
internal fun MessageBody(icon: CircasoIcon, title: String, text: String, action: String?, onAction: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxSize().padding(CircasoSpacing.s6),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircasoIconImage(icon, c.inkTertiary, Modifier.size(40.dp))
        BasicText(title, style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center), modifier = Modifier.semantics { heading() })
        BasicText(text, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        if (action != null) CircasoButton(action, onAction, style = ButtonStyle.Secondary, size = ButtonSize.Medium)
    }
}

@Composable
private fun LinkBody(link: SharedLink, padding: PaddingValues) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s4)
            .padding(bottom = padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
    ) {
        link.receipt?.let { ReceiptBody(it) }
        link.statement?.let { StatementBody(it) }
        link.person?.let { PersonBody(it, link.kind) }
        link.address?.let { AddressBody(it) }
        if (link.receipt == null && link.statement == null && link.person == null && link.address == null) {
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                BasicText(link.title, style = t.title2.copy(color = c.ink))
                if (link.summary.isNotEmpty()) BasicText(link.summary, style = t.body.copy(color = c.inkSecondary))
            }
        }
        BasicText(
            if (link.mine) "This is your link. Turn it off any time from You → Shared links." else "Shared with you through Circaso. Only what the sender chose to show is here.",
            style = t.footnote.copy(color = c.inkTertiary, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReceiptBody(r: SharedReceipt) {
    val coin = "${r.amount} ${r.asset}"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        val fiat = r.fiat?.let { formatFiat(it.currency, it.amount) }
        AmountDisplay(
            currency = fiat?.substringBefore(' ') ?: r.asset,
            number = fiat?.substringAfter(' ') ?: r.amount,
            sub = if (fiat != null) "$coin · ${if (r.sent) "Sent" else "Received"}" else if (r.sent) "Sent" else "Received",
        )
        TextBadge(receiptStatus(r.status))
    }
    val rows = listOfNotNull(
        r.fromName?.let { "From" to it },
        r.toName?.let { "To" to it },
        "Amount" to coin,
        "Network" to chainName(r.blockchain),
        "Date" to dateTime(r.at),
        r.txHash?.let { "Transaction" to shortAddress(it) },
    )
    ListGroup {
        rows.forEachIndexed { i, (key, value) ->
            if (i > 0) RowDivider()
            DetailRow(key, value)
        }
    }
}

internal fun receiptStatus(status: String) = when (status.uppercase()) {
    "CONFIRMED" -> "Completed"
    "SUBMITTED" -> "On its way"
    "FAILED" -> "Failed"
    else -> status.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun StatementBody(s: SharedStatement) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
        BasicText(s.goalName, style = t.title2.copy(color = c.ink), modifier = Modifier.semantics { heading() })
        BasicText("${s.memberCount} ${if (s.memberCount == 1) "member" else "members"} · amounts in ${s.asset}", style = t.subhead.copy(color = c.inkSecondary))
    }
    ListGroup {
        DetailRow("Raised", "${s.raised} of ${s.target}")
        RowDivider()
        DetailRow("Paid out", s.paidOut)
        RowDivider()
        DetailRow("Held", s.held)
    }
    if (s.lines.isNotEmpty()) Section("Payments") {
        s.lines.forEachIndexed { i, line ->
            if (i > 0) RowDivider()
            ListRow(
                title = line.name ?: if (line.incoming) "Contribution" else "Payout",
                sub = listOfNotNull(dateOnly(line.at), line.note).joinToString(" · "),
                meta = (if (line.incoming) "+" else "−") + line.amount,
            )
        }
    }
}

@Composable
private fun PersonBody(p: SharedPerson, kind: ShareKind?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Avatar(p.displayName, size = CircasoSizes.avatarXl)
        BasicText(p.displayName, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center), modifier = Modifier.semantics { heading() })
        p.handle?.let { BasicText("@$it", style = t.body.copy(color = c.inkSecondary)) }
        BasicText(
            if (kind == ShareKind.PayMe) "Pay ${p.displayName.substringBefore(' ')} on Circaso." else "${p.displayName.substringBefore(' ')} invited you to chat on Circaso, end to end encrypted.",
            style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun AddressBody(a: SharedAddress) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val network = chainName(a.blockchain)
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
        BasicText(a.name?.let { "$it's ${a.asset} address" } ?: "${a.asset} address", style = t.title2.copy(color = c.ink), modifier = Modifier.semantics { heading() })
        BasicText("On $network", style = t.subhead.copy(color = c.inkSecondary))
    }
    ListGroup {
        SelectionContainer {
            BasicText(a.address, style = t.callout.copy(color = c.ink), modifier = Modifier.fillMaxWidth().padding(CircasoSpacing.s4))
        }
    }
    BasicText("Only send ${a.asset} on $network to this address. Other coins or networks can't be recovered.", style = t.footnote.copy(color = c.warning))
    CircasoButton(if (copied) "Copied" else "Copy Address", { clipboard.setText(AnnotatedString(a.address)); copied = true }, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, icon = if (copied) CircasoIcon.Check else CircasoIcon.Copy)
}

private fun epochOf(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

internal fun dateOnly(iso: String): String = epochOf(iso)?.let { formatLocalTime(it, LocalTimeStyle.ShortDate) } ?: iso.take(10)

internal fun dateTime(iso: String): String = epochOf(iso)?.let { formatLocalTime(it, LocalTimeStyle.ShortDate) + ", " + formatLocalTime(it, LocalTimeStyle.Clock) } ?: iso

/** You → Shared links: every link that still works, newest first, each with Turn Off. */
@Composable
fun SharedLinksScreen(viewModel: SharedLinksViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    SharedLinksContent(state, onBack, onRetry = viewModel::refresh, onRevoke = viewModel::onRevoke, onMore = viewModel::loadMore, onMessageShown = viewModel::onMessageShown)
}

@Composable
fun SharedLinksContent(
    state: SharedLinksUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRevoke: (String) -> Unit,
    onMore: () -> Unit = {},
    onMessageShown: () -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.message) { state.message?.let { toast.show(it); onMessageShown() } }
    var confirm by remember { mutableStateOf<ShareLink?>(null) }
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "Shared links", onBack = onBack) },
        banner = { ConnectionBanner(showing = "Your links need a connection to load") },
        toast = { ToastHost(toast) },
        overlay = {
            ConfirmSheet(
                visible = confirm != null,
                title = "Turn off this link?",
                message = "Anyone who opens it will see that it's no longer available. You can share again to make a new one.",
                confirmLabel = "Turn Off",
                onConfirm = { confirm?.let { onRevoke(it.code) }; confirm = null },
                onDismiss = { confirm = null },
                destructive = true,
                icon = com.circaso.designsystem.components.CircasoIcon.Link,
            )
        },
    ) { padding ->
        when {
            state.loading && state.links.isEmpty() -> Column(Modifier.fillMaxSize().padding(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                repeat(3) { SkeletonBlock(Modifier.fillMaxWidth().height(64.dp), CircasoShapes.lg) }
            }
            state.failure == SharedLinkLoad.Offline && state.links.isEmpty() -> MessageBody(
                CircasoIcon.Offline, "You're offline", "Your shared links load from Circaso, so this needs a connection.", "Try Again", onRetry,
            )
            state.failure is SharedLinkLoad.Failed && state.links.isEmpty() -> MessageBody(CircasoIcon.Warning, "Couldn't load your links", state.failure.message, "Try Again", onRetry)
            state.links.isEmpty() -> MessageBody(
                CircasoIcon.Link,
                "No shared links",
                "When you share a receipt, a group statement, your address or an invite, its link shows here so you can turn it off.",
                null,
                {},
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s3, bottom = padding.calculateBottomPadding() + CircasoSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
            ) {
                item(key = "caption") {
                    BasicText(
                        "Anyone with one of these links can open its page. Turn a link off and it stops working everywhere.",
                        style = t.footnote.copy(color = c.inkSecondary),
                        modifier = Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1),
                    )
                }
                items(state.links, key = { it.code }) { link ->
                    ListGroup(Modifier.animateItem()) {
                        ListRow(
                            title = link.title,
                            sub = listOfNotNull(kindLabel(link.kind), dateOnly(link.createdAt), link.expiresAt?.let { "ends ${dateOnly(it)}" }).joinToString(" · "),
                            leading = { CircasoIconImage(kindIcon(link.kind), c.inkSecondary, Modifier.size(22.dp)) },
                            trailing = {
                                CircasoButton(
                                    "Turn Off", { confirm = link }, style = ButtonStyle.Secondary, size = ButtonSize.Medium,
                                    busy = state.revoking == link.code, enabled = state.revoking == null && state.online,
                                )
                            },
                        )
                    }
                }
                if (state.nextCursor != null) item(key = "more") {
                    if (!state.moreFailed) LaunchedEffect(state.links.size) { onMore() }
                    Box(Modifier.fillMaxWidth().padding(CircasoSpacing.s3), contentAlignment = Alignment.Center) {
                        if (state.moreFailed) CircasoButton("Try Again", onMore, style = ButtonStyle.Plain)
                        else com.circaso.designsystem.components.Spinner(c.inkTertiary)
                    }
                }
            }
        }
    }
}

private fun kindLabel(kind: ShareKind?) = when (kind) {
    ShareKind.Receipt -> "Receipt"
    ShareKind.Statement -> "Statement"
    ShareKind.Invite -> "Invite"
    ShareKind.PayMe -> "Pay me"
    ShareKind.Address -> "Address"
    null -> null
}

private fun kindIcon(kind: ShareKind?) = when (kind) {
    ShareKind.Receipt -> CircasoIcon.Money
    ShareKind.Statement -> CircasoIcon.People
    ShareKind.Invite -> CircasoIcon.UserPlus
    ShareKind.PayMe -> CircasoIcon.Wallet
    ShareKind.Address -> CircasoIcon.Qr
    null -> CircasoIcon.Link
}
