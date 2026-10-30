package com.circaso.feature.chama.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.chatListTime
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.platform.shareText
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.AmountDisplay
import com.circaso.designsystem.components.AmountSize
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.GoalProgress
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowAmount
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SegmentedControl
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.LedgerDirection
import com.circaso.feature.chama.domain.LedgerEntry
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.shortAddress
import kotlin.time.Instant

/** The group wallet (design D4), the requests it pays (D5) and the goal itself (set from here or chat info). */
@Composable
fun ChamaScreen(viewModel: ChamaViewModel, onBack: () -> Unit, onContribute: () -> Unit, onPayRequest: (ChamaRequest) -> Unit, onOpenTransfer: (String) -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    ChamaContent(
        state, onBack, onContribute, onPayRequest, onOpenTransfer = onOpenTransfer,
        onTab = viewModel::onTab, onRefresh = viewModel::refresh, onDraft = viewModel::onDraft, onDraftChange = viewModel::onDraftChange, onAsk = viewModel::onAsk,
        onDecide = viewModel::onDecide, onGoalDraft = viewModel::onGoalDraft, onGoalDraftChange = viewModel::onGoalDraftChange, onSaveGoal = viewModel::onSaveGoal, onNoticeShown = viewModel::onNoticeShown,
    )
}

@Composable
fun ChamaContent(
    state: ChamaUiState,
    onBack: () -> Unit,
    onContribute: () -> Unit,
    onPayRequest: (ChamaRequest) -> Unit,
    onTab: (LedgerTab) -> Unit,
    onRefresh: () -> Unit,
    onDraft: (Boolean) -> Unit,
    onDraftChange: ((PayoutDraft) -> PayoutDraft) -> Unit,
    onAsk: () -> Unit,
    onDecide: (ChamaRequest, Boolean) -> Unit,
    onGoalDraft: (Boolean) -> Unit,
    onGoalDraftChange: ((GoalDraft) -> GoalDraft) -> Unit,
    onSaveGoal: () -> Unit,
    onNoticeShown: () -> Unit,
    onOpenTransfer: (String) -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.notice, state.error) { (state.notice ?: state.error)?.let { toast.show(it); onNoticeShown() } }
    val chama = state.chama
    val chat = state.chat
    val amAdmin = chat?.myRole == MemberRole.Admin || chat?.myRole == MemberRole.Owner
    CircasoScaffold(
        grouped = true,
        topBar = {
            CircasoNavBar(
                title = chat?.title ?: chama?.goal?.name ?: "Group wallet",
                onBack = onBack,
                trailing = {
                    if (chama != null) CircasoIconButton(CircasoIcon.Share, "Export statement", { shareText(statement(state), "${chama.goal.name} statement") })
                },
            )
        },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
        bottomBar = if (chama != null) ({
            Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                CircasoButton("Make a Payment", { onDraft(true) }, size = ButtonSize.Large, block = true, enabled = state.online && !state.busy)
                if (!state.online) BasicText("You're offline. Requests and approvals need a connection.", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
            }
        }) else null,
        overlay = {
            CircasoSheet(state.draft != null, { onDraft(false) }, label = "Make a payment") { state.draft?.let { PayoutSheetContent(state, it, onDraftChange, onAsk, onClose = { onDraft(false) }) } }
            CircasoSheet(state.goalDraft != null, { onGoalDraft(false) }, label = if (chama == null) "Set a goal" else "Change the goal") {
                state.goalDraft?.let { GoalSheetContent(state, it, onGoalDraftChange, onSaveGoal, onClose = { onGoalDraft(false) }) }
            }
        },
    ) { padding ->
        when {
            chama == null && state.loading && state.online -> LoadingWallet(padding)
            chama == null && !state.noGoal -> Unreachable(state.online, padding, onRefresh)
            chama == null -> NoGoal(amAdmin, state.online, padding, onSet = { onGoalDraft(true) }, onRefresh = onRefresh)
            else -> Ledger(state, chama, padding, amAdmin, onTab, onContribute, onPayRequest, onDecide, onChangeGoal = { onGoalDraft(true) }, onOpenTransfer = onOpenTransfer)
        }
    }
}

/** First paint before CH1 answers: the hero card and rows in outline, so the layout doesn't jump when it lands. */
@Composable
private fun LoadingWallet(padding: PaddingValues) {
    val c = CircasoTheme.colors
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.5f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(CircasoMotion.MomentMs), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse",
    )
    Column(
        Modifier.fillMaxSize().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s3, bottom = padding.calculateBottomPadding())
            .graphicsLayer { alpha = pulse }
            .semantics { contentDescription = "Opening the group wallet" },
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
    ) {
        Box(Modifier.fillMaxWidth().height(164.dp).clip(CircasoShapes.lg).background(c.surface))
        Box(Modifier.fillMaxWidth().height(32.dp).clip(CircasoShapes.pill).background(c.fill))
        Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)) {
            repeat(3) { i ->
                Row(Modifier.fillMaxWidth().padding(CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(CircasoSizes.avatarMd).clip(CircleShape).background(c.fill))
                    Box(Modifier.weight(1f).height(14.dp).clip(CircasoShapes.pill).background(c.fill))
                    Box(Modifier.width(72.dp).height(14.dp).clip(CircasoShapes.pill).background(c.fill))
                }
                if (i < 2) RowDivider(Modifier.padding(start = 72.dp))
            }
        }
    }
}

/** Nothing on the phone and the server not reached: don't claim the group has no goal. */
@Composable
private fun Unreachable(online: Boolean, padding: PaddingValues, onRefresh: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s6).padding(bottom = padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(if (online) "Couldn't open the group wallet" else "Can't reach the group wallet yet", style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(if (online) "Something went wrong on our side. Try again in a moment." else "Connect to see the goal, the contributions and the payments.", style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        CircasoButton("Check Again", onRefresh, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = online)
    }
}

/** No goal yet: admins set one (design D1's goal part), everyone else hears who can. */
@Composable
private fun NoGoal(amAdmin: Boolean, online: Boolean, padding: PaddingValues, onSet: () -> Unit, onRefresh: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s6).padding(bottom = padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        AmountDisplay("USDC", "0", sub = "Nothing saved together yet", size = AmountSize.Medium)
        Spacer(Modifier.height(CircasoSpacing.s2))
        BasicText(if (amAdmin) "Give the group a goal" else "No goal yet", style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(
            if (amAdmin) "Name what you're saving for and how much. Members add to it from the chat; the money sits in your wallet, and paying out needs two admins to agree."
            else "An admin can set a goal for this group. Once they do, you'll see it here and at the top of the chat.",
            style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(CircasoSpacing.s2))
        if (amAdmin) CircasoButton("Set a Goal", onSet, size = ButtonSize.Large, block = true, enabled = online)
        else CircasoButton("Check Again", onRefresh, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = online)
        if (!online) BasicText("You need a connection for this.", style = t.footnote.copy(color = c.inkSecondary))
    }
}

@Composable
private fun Ledger(
    state: ChamaUiState,
    chama: Chama,
    padding: PaddingValues,
    amAdmin: Boolean,
    onTab: (LedgerTab) -> Unit,
    onContribute: () -> Unit,
    onPayRequest: (ChamaRequest) -> Unit,
    onDecide: (ChamaRequest, Boolean) -> Unit,
    onChangeGoal: () -> Unit,
    onOpenTransfer: (String) -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val fiat = state.fiat
    val now = nowMillis()
    val tabs = LedgerTab.entries
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s3, bottom = padding.calculateBottomPadding() + CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5)) {
        item {
            Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface).padding(horizontal = CircasoSpacing.s4, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
                val held = amountText(chama.heldMinor, fiat)
                AmountDisplay(
                    currency = held.substringBefore(' '), number = held.substringAfter(' '),
                    sub = (if (fiat != null) MinorUnits.toDisplay(chama.heldMinor, 6) + " USDC · " else "") + "held by ${state.memberName(chama.goal.treasurerUserId).substringBefore(' ')} for the group",
                    size = AmountSize.Large, centred = false,
                )
                GoalProgress(amountText(chama.contributedMinor, fiat), amountNumber(chama.goal.targetMinor, fiat), chama.progress, meta = goalMeta(chama) + daysLeft(chama), label = "${chama.goal.name} progress")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    if (!chama.isTreasurer) CircasoButton("Add to the Goal", onContribute, modifier = Modifier.weight(1f), size = ButtonSize.Medium, enabled = state.online)
                    if (amAdmin) CircasoButton("Change Goal", onChangeGoal, modifier = Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Medium, enabled = state.online && !state.busy)
                }
            }
        }
        item { SegmentedControl(tabs.map { it.label }, tabs.indexOf(state.tab), { onTab(tabs[it]) }) }
        item {
            AnimatedContent(state.tab, transitionSpec = { fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs)) }, label = "ledger") { tab ->
                Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    when (tab) {
                        LedgerTab.Contributions -> Rows(state.shownLedger, state, fiat, now, empty = "Nothing added yet. Contributions from the chat show here the moment they confirm.", onOpen = onOpenTransfer)
                        LedgerTab.Payments -> {
                            chama.openRequests.forEach { request ->
                                RequestCard(
                                    request, fiat, nameOf = state.memberName, isAdmin = chama.isAdmin, isTreasurer = chama.isTreasurer, myUserId = state.myUserId, busy = state.busy, online = state.online,
                                    onApprove = { onDecide(request, true) }, onDecline = { onDecide(request, false) }, onPay = { onPayRequest(request) },
                                )
                            }
                            Rows(state.shownLedger, state, fiat, now, empty = if (chama.openRequests.isEmpty()) "Nothing paid out yet. Ask for a payment and two admins approve it." else null, onOpen = onOpenTransfer)
                        }
                        LedgerTab.Members -> Members(state, chama, fiat)
                    }
                }
            }
        }
    }
}

@Composable
private fun Rows(entries: List<LedgerEntry>, state: ChamaUiState, fiat: FiatValue?, now: Long, empty: String?, onOpen: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    if (entries.isEmpty()) {
        if (empty != null) BasicText(empty, style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = CircasoSpacing.s1))
        return
    }
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)) {
        entries.forEachIndexed { i, e ->
            val incoming = e.direction == LedgerDirection.In
            val who = when {
                incoming -> e.userId?.let { if (it == state.myUserId) "You" else state.memberName(it) } ?: "Someone"
                else -> e.request?.let { r -> r.payeeUserId?.let(state.memberName) ?: r.payeeName ?: r.payeeAddress?.let(::shortAddress) } ?: "Payment"
            }
            val at = runCatching { Instant.parse(e.at).toEpochMilliseconds() }.getOrNull()
            ListRow(
                title = who,
                sub = listOfNotNull(if (!incoming) e.request?.note else null, at?.let { chatListTime(it, now) }).joinToString(" · "),
                leading = { if (incoming || e.request?.payeeUserId != null) Avatar(who, size = CircasoSizes.avatarMd) else com.circaso.designsystem.components.CoinDisc("OUT") },
                trailing = { RowAmount((if (incoming) "+" else "−") + amountText(e.amountMinor, fiat), meta = if (fiat != null) MinorUnits.toDisplay(e.amountMinor, 6) + " USDC" else null, incoming = incoming) },
                onClick = e.transferId?.let { id -> { onOpen(id) } },
            )
            if (i < entries.lastIndex) RowDivider(Modifier.padding(start = 72.dp))
        }
    }
}

/** Who has put in what, treasurer and admins marked (design D4's third tab). */
@Composable
private fun Members(state: ChamaUiState, chama: Chama, fiat: FiatValue?) {
    val c = CircasoTheme.colors
    val members = state.chat?.members.orEmpty()
    val totals = chama.contributors.associate { it.userId to it.amountMinor }
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)) {
        members.forEachIndexed { i, m ->
            val roles = listOfNotNull(
                if (m.userId == chama.goal.treasurerUserId) "Treasurer" else null,
                when (m.role) { MemberRole.Owner -> "Owner"; MemberRole.Admin -> "Admin"; MemberRole.Member -> null },
            ).joinToString(" · ").ifEmpty { "Member" }
            val total = totals[m.userId]
            ListRow(
                title = if (m.userId == state.myUserId) "You" else m.displayName,
                sub = roles,
                leading = { Avatar(m.displayName, size = CircasoSizes.avatarMd, imageUrl = m.avatarUrl) },
                trailing = { RowAmount(total?.let { amountText(it, fiat) } ?: "—", meta = if (total == null) "nothing yet" else null, incoming = total != null) },
            )
            if (i < members.lastIndex) RowDivider(Modifier.padding(start = 72.dp))
        }
    }
}

/** "Make a Payment" (D4 → D5): who, how much, what for. Two admins approve it, then the treasurer pays it. */
@Composable
private fun PayoutSheetContent(state: ChamaUiState, draft: PayoutDraft, onChange: ((PayoutDraft) -> PayoutDraft) -> Unit, onAsk: () -> Unit, onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val fiat = state.fiat
    val members = state.chat?.members.orEmpty().filter { it.userId != state.myUserId }
    val someoneElse = draft.someoneElse || members.isEmpty()
    val treasurer = state.chama?.let { state.memberName(it.goal.treasurerUserId).substringBefore(' ') } ?: "the treasurer"
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
        com.circaso.designsystem.chrome.SheetHead("Make a Payment", onClose)
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            BasicText("Who gets paid", style = t.footnote.copy(color = c.inkSecondary))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                items(members, key = { it.userId }) { m ->
                    SuggestionChip(m.displayName.substringBefore(' '), { onChange { it.copy(toUserId = m.userId, someoneElse = false) } }, selected = !someoneElse && draft.toUserId == m.userId)
                }
                item { SuggestionChip("Someone else", { onChange { it.copy(toUserId = null, someoneElse = true) } }, selected = someoneElse) }
            }
            if (someoneElse) {
                CircasoTextField(draft.toName, { v -> onChange { it.copy(toName = v.take(60)) } }, label = "Name", placeholder = "Who is this for? (Coast Bus)")
                CircasoTextField(draft.address, { v -> onChange { it.copy(address = v.trim()) } }, label = "USDC address", placeholder = "0x…")
            }
        }
        val unit = if (draft.inFiat && fiat != null) fiat.currency else "USDC"
        CircasoTextField(
            draft.amountText, { v -> onChange { it.copy(amountText = cleanAmount(v, if (draft.inFiat && fiat != null) 2 else 6)) } }, label = "Amount ($unit)", placeholder = "0",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        if (fiat != null) {
            val minor = state.draftAmountMinor
            val other = minor?.takeIf { !MinorUnits.isZero(it) }?.let { if (draft.inFiat) MinorUnits.toDisplay(it, 6) + " USDC" else amountText(it, fiat) }
            SuggestionChip(other?.let { "≈ $it · switch" } ?: "Type in ${if (draft.inFiat) "USDC" else fiat.currency}", { onChange { it.copy(inFiat = !it.inFiat, amountText = "") } })
        }
        CircasoTextField(draft.note, { v -> onChange { it.copy(note = v.take(140)) } }, label = "What for", placeholder = "bus deposit")
        val minor = state.draftAmountMinor
        CircasoButton(
            if (minor != null && !MinorUnits.isZero(minor)) "Request ${amountText(minor, fiat)}" else "Request a Payment",
            onAsk, size = ButtonSize.Large, block = true, enabled = state.canAsk, busy = state.busy,
        )
        BasicText("Two admins approve it, then $treasurer pays it from the group. Everyone in the chat sees the request.", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
    }
}

/** Set or change the goal (CH2). The treasurer is whoever saves it. */
@Composable
private fun GoalSheetContent(state: ChamaUiState, draft: GoalDraft, onChange: ((GoalDraft) -> GoalDraft) -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val fiat = state.fiat
    val unit = if (draft.inFiat && fiat != null) fiat.currency else "USDC"
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
        com.circaso.designsystem.chrome.SheetHead(if (state.chama == null) "Set a Goal" else "Change the Goal", onClose)
        CircasoTextField(draft.name, { v -> onChange { it.copy(name = v.take(60)) } }, label = "What for", placeholder = "Holiday Fund")
        CircasoTextField(
            draft.targetText, { v -> onChange { it.copy(targetText = cleanAmount(v, if (draft.inFiat && fiat != null) 2 else 6)) } }, label = "Target ($unit)", placeholder = "50,000",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        if (fiat != null) SuggestionChip("Type in ${if (draft.inFiat) "USDC" else fiat.currency}", { onChange { it.copy(inFiat = !it.inFiat, targetText = "") } })
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            BasicText("By when", style = t.footnote.copy(color = c.inkSecondary))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                items(GoalDeadline.entries) { d -> SuggestionChip(d.label, { onChange { it.copy(by = d) } }, selected = draft.by == d) }
            }
        }
        CircasoButton(if (state.chama == null) "Set Goal" else "Save Goal", onSave, size = ButtonSize.Large, block = true, enabled = state.online && !state.busy && draft.name.isNotBlank() && draft.targetText.isNotBlank(), busy = state.busy)
        BasicText(
            if (state.chama == null) "Contributions go to your wallet and you pay out what two admins approve. Everyone in the group can see the goal, every contribution and every payment."
            else "Changing the goal keeps everything already contributed. The money stays with ${state.memberName(state.chama.goal.treasurerUserId).substringBefore(' ')}.",
            style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Digits and one point, at most [decimals] after it. */
internal fun cleanAmount(text: String, decimals: Int): String {
    val clean = text.filter { it.isDigit() || it == '.' }
    if (clean.count { it == '.' } > 1) return clean.substringBeforeLast('.')
    val after = clean.substringAfter('.', "")
    return if (after.length > decimals) clean.substringBefore('.') + "." + after.take(decimals) else clean
}

private fun daysLeft(chama: Chama): String {
    val at = chama.goal.deadline?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: return ""
    val days = (at - nowMillis()) / 86_400_000
    return if (days > 0) " · $days days left" else ""
}

/** A plain-text statement of the goal, for "Export statement" (design D4's trailing action). */
internal fun statement(state: ChamaUiState): String {
    val chama = state.chama ?: return ""
    val fiat = state.fiat
    return buildString {
        appendLine("${chama.goal.name} · ${state.chat?.title.orEmpty()}")
        appendLine("Raised ${amountText(chama.contributedMinor, fiat)} of ${amountText(chama.goal.targetMinor, fiat)}")
        appendLine("Paid out ${amountText(chama.paidOutMinor, fiat)} · Held ${amountText(chama.heldMinor, fiat)}")
        appendLine()
        state.ledger.forEach { e ->
            val at = runCatching { formatLocalTime(Instant.parse(e.at).toEpochMilliseconds(), LocalTimeStyle.ShortDate) }.getOrDefault(e.at)
            val who = if (e.direction == LedgerDirection.In) e.userId?.let(state.memberName) ?: "Someone" else e.request?.let { it.payeeUserId?.let(state.memberName) ?: it.payeeName ?: it.payeeAddress } ?: "Payment"
            appendLine("$at  ${if (e.direction == LedgerDirection.In) "+" else "−"}${amountText(e.amountMinor, fiat)}  $who" + (e.request?.note?.let { " · $it" } ?: ""))
        }
        appendLine()
        append("From Circaso")
    }
}
