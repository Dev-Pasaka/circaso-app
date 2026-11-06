package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ToastHost
import com.circaso.designsystem.chrome.rememberToastState
import com.circaso.designsystem.components.AshaMark
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.DetailRow
import com.circaso.designsystem.components.GoalProgress
import com.circaso.feature.chama.presentation.amountNumber
import com.circaso.feature.chama.presentation.amountText
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.MoneyCard
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SegmentedControl
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.RemindOutcome
import com.circaso.feature.wallet.domain.chainName
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Paying a person: pick them (from the wallet), the amount and note (design C4), the Circle SDK's PIN (C5), then the
 * moment (C6). The card lands in the chat with them on the way out.
 */
@Composable
fun PayScreen(
    viewModel: PayViewModel, onBack: () -> Unit, onOpenTransfer: (String) -> Unit, onOpenChat: ((String) -> Unit)?,
    /** Send to a wallet address instead (design E5), from the wallet being paid from ([String] null: personal). */
    onToAddress: ((fromWalletId: String?) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    PayContent(
        state, onBack, viewModel::onQuery, viewModel::onPick, { if (!viewModel.onBackToPick()) onBack() }, viewModel::onAmount, viewModel::onToggleUnit, viewModel::onNote, viewModel::onChain, viewModel::onRemind, viewModel::onPay, viewModel::onErrorShown, onOpenTransfer, onOpenChat,
        onAttachAgain = viewModel::onAttachAgain, onScan = if (viewModel.canScan) viewModel::onScan else null, onChoosing = viewModel::onChoosing, onPayFrom = viewModel::onPayFrom,
        onRetryQuote = viewModel::onRetryQuote, onUseMax = viewModel::onUseMax, onRetryPrice = viewModel::onRetryPrice,
        onToAddress = onToAddress?.let { open -> { open(state.payingFrom.walletId) } },
    )
}

@Composable
fun PayContent(
    state: PayUiState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onPick: (com.circaso.feature.contacts.domain.Person) -> Unit,
    onBackToPick: () -> Unit,
    onAmount: (String) -> Unit,
    onToggleUnit: () -> Unit,
    onNote: (String) -> Unit,
    onChain: (String) -> Unit,
    onRemind: () -> Unit,
    onPay: () -> Unit,
    onErrorShown: () -> Unit,
    onOpenTransfer: (String) -> Unit,
    onOpenChat: ((String) -> Unit)?,
    onAttachAgain: () -> Unit = {},
    /** Scan someone's Circaso code (design E4) to pay them; null when the phone can't scan. */
    onScan: (() -> Unit)? = null,
    /** GW10: open or close "Pay from", and pick a wallet. */
    onChoosing: (Boolean) -> Unit = {},
    onPayFrom: (NamedWallet) -> Unit = {},
    /** GW13d's Try Again on the fee. */
    onRetryQuote: () -> Unit = {},
    /** GW14 / GW20's "… Instead": the most that fits with its fee. */
    onUseMax: () -> Unit = {},
    /** The picker's "A Wallet Address" row: Send to a Wallet (E5) from the same wallet. */
    onToAddress: (() -> Unit)? = null,
    /** Try Again when a fresh coin price couldn’t be had. */
    onRetryPrice: () -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val toast = rememberToastState()
    LaunchedEffect(state.error) { state.error?.let { toast.show(it); onErrorShown() } }
    val title = when {
        // Group wallets (GW9, GW9c, GW9d): the review says what kind of money this is.
        state.toGroup -> if (state.step == PayStep.Done) "Added" else "Contribute"
        state.groupPayout -> if (state.step == PayStep.Done && state.result?.settled == true) "Paid" else if (state.step == PayStep.Done) "Sent" else "Pay Payout"
        state.directPayout && state.step == PayStep.Done -> if (state.result?.settled == true) "Paid" else "Sent"
        state.directPayout && state.step == PayStep.Pick -> "Pay from ${shortWalletName(state.walletName)}"
        state.directPayout && state.toSelf -> "Pay Yourself"
        state.directPayout -> state.payee?.let { "Pay ${it.displayName.substringBefore(' ')}" } ?: "Pay"
        state.requestPayout -> if (state.step == PayStep.Done) "Requested" else "Request Payout"
        state.contribution -> if (state.step == PayStep.Done) "Added" else "Add to ${state.goalName}"
        state.payout -> when { state.step != PayStep.Done -> "Pay from the Group"; state.linked -> "Paid"; else -> "Sent" }
        state.step == PayStep.Pick -> "Send"
        state.step == PayStep.Done -> "Sent"
        else -> state.payee?.let { "Send to ${it.displayName.substringBefore(' ')}" } ?: "Send"
    }
    CircasoScaffold(
        topBar = {
            CircasoNavBar(
                title = title,
                // Back from the amount returns to the picker when the payment started there, otherwise leaves Pay.
                onBack = when (state.step) { PayStep.Signing -> null; PayStep.Amount -> onBackToPick; else -> onBack },
                trailing = { if (state.step == PayStep.Pick && onScan != null) CircasoIconButton(CircasoIcon.Scan, "Scan a Circaso code", onScan) },
            )
        },
        toast = { ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
        overlay = {
            CircasoSheet(visible = state.choosing, onDismiss = { onChoosing(false) }, label = "Pay from") {
                PayFromSheetContent(state.choices, state.payingFrom.walletId, state.balances, state.online, state.asOf, onPick = onPayFrom, onClose = { onChoosing(false) })
            }
        },
    ) { padding ->
        when (state.step) {
            PayStep.Pick -> PickPerson(state, padding, onQuery, onPick, onToAddress)
            PayStep.Amount, PayStep.Signing, PayStep.Sending -> AmountStep(state, padding, onAmount, onToggleUnit, onNote, onChain, onRemind, onPay, onChoosing, onRetryQuote, onUseMax, onRetryPrice)
            PayStep.Done -> DoneStep(state, padding, onBack, onOpenTransfer, onOpenChat, onAttachAgain)
        }
    }
}

@Composable
private fun PickPerson(state: PayUiState, padding: PaddingValues, onQuery: (String) -> Unit, onPick: (com.circaso.feature.contacts.domain.Person) -> Unit, onToAddress: (() -> Unit)? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4)) {
        item {
            CircasoTextField(state.query, onQuery, label = "To", placeholder = "Name or @handle", modifier = Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2))
        }
        // 2026-11-06: the group wallet's owner pays anyone from it: themselves, the group's members, contacts, an address.
        val me = state.me?.takeIf { m -> state.directPayout && state.query.trim().trimStart('@').let { q -> q.isEmpty() || m.displayName.contains(q, true) || m.handle?.contains(q, true) == true || "you".startsWith(q.lowercase()) } }
        if (me != null) {
            item(key = "me") {
                ListRow("You", sub = "Your personal wallet", leading = { Avatar(me.displayName, size = CircasoSizes.avatarMd, imageUrl = me.avatarUrl) }, onClick = { onPick(me) })
                RowDivider(Modifier.padding(start = 72.dp))
            }
        }
        if (onToAddress != null && state.query.isBlank()) {
            item(key = "address") {
                ListRow(
                    "A Wallet Address", sub = if (state.fromGroupWallet) "Send from ${state.walletName} to an outside wallet" else "Send to an exchange or another wallet",
                    leading = { PickIcon(CircasoIcon.Forward) }, onClick = onToAddress,
                )
                RowDivider(Modifier.padding(start = 72.dp))
            }
        }
        val members = if (state.directPayout) pickableMembers(state) else emptyList()
        if (members.isNotEmpty()) {
            item(key = "members-head") { PickHeader("In ${state.walletName}") }
            items(members, key = { "member:" + it.userId }) { person ->
                ListRow(person.displayName, sub = person.handle?.let { "@$it" }, leading = { Avatar(person.displayName, size = CircasoSizes.avatarMd, imageUrl = person.avatarUrl) }, onClick = { onPick(person) })
                RowDivider(Modifier.padding(start = 72.dp))
            }
        }
        val people = state.filteredContacts.filter { p -> members.none { it.userId == p.userId } && p.userId != state.me?.userId }
        if (members.isNotEmpty() && people.isNotEmpty()) item(key = "contacts-head") { PickHeader("Contacts") }
        if (people.isEmpty() && members.isEmpty()) item { BasicText(if (state.contacts.isEmpty()) "Connect with people from the You tab to pay them." else "Nobody matches.", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(CircasoSpacing.s4)) }
        items(people, key = { it.userId }) { person ->
            ListRow(person.displayName, sub = person.handle?.let { "@$it" }, leading = { Avatar(person.displayName, size = CircasoSizes.avatarMd, imageUrl = person.avatarUrl) }, onClick = { onPick(person) })
            RowDivider(Modifier.padding(start = 72.dp))
        }
    }
}

/** The group's members the owner can pay from its wallet (2026-11-06), contacts or not, without the owner, as typed. */
internal fun pickableMembers(state: PayUiState): List<com.circaso.feature.contacts.domain.Person> {
    val q = state.query.trim().trimStart('@')
    return state.memberNames.filterKeys { it != state.me?.userId }.map { (id, name) ->
        state.contacts.firstOrNull { it.userId == id } ?: com.circaso.feature.contacts.domain.Person(id, name, null, null, null)
    }.filter { q.isEmpty() || it.displayName.contains(q, true) || it.handle?.contains(q, true) == true }.sortedBy { it.displayName.lowercase() }
}

@Composable
private fun PickHeader(text: String) {
    BasicText(
        text, style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s4, bottom = CircasoSpacing.s1).semantics { heading() },
    )
}

/** A round glyph the size of an avatar, for picker rows that aren't a person. */
@Composable
private fun PickIcon(icon: CircasoIcon) {
    Box(Modifier.size(CircasoSizes.avatarMd).clip(CircasoShapes.circle).background(CircasoTheme.colors.fill), contentAlignment = Alignment.Center) {
        CircasoIconImage(icon, CircasoTheme.colors.ink, Modifier.size(20.dp))
    }
}

/** Design C4: the person, the amount big, a note, from/fee/receives, then the button. The SDK takes over for C5. */
@Composable
private fun AmountStep(state: PayUiState, padding: PaddingValues, onAmount: (String) -> Unit, onToggleUnit: () -> Unit, onNote: (String) -> Unit, onChain: (String) -> Unit, onRemind: () -> Unit, onPay: () -> Unit, onChoosing: (Boolean) -> Unit = {}, onRetryQuote: () -> Unit = {}, onUseMax: () -> Unit = {}, onRetryPrice: () -> Unit = {}) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val payee = state.payee
    val focus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) { if (state.step == PayStep.Amount) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = CircasoSpacing.s4).padding(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
        if (payee != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), modifier = Modifier.padding(top = CircasoSpacing.s2)) {
                if (state.toGroup) WalletAvatar(payee.displayName, payee.avatarUrl, group = true) else Avatar(payee.displayName, size = CircasoSizes.avatarLg, imageUrl = payee.avatarUrl)
                BasicText(if (state.toSelf && state.directPayout) "You" else payee.displayName, style = t.headline.copy(color = c.ink))
                when {
                    // GW9d: into the group's own wallet, not anyone's personal one.
                    state.toGroup -> BasicText("the group’s own wallet", style = t.footnote.copy(color = c.inkSecondary))
                }
                // The signer left (spaces-plan §11.8): paying in is allowed, but say nothing can leave it for now.
                val signer = state.groupMoney?.wallet?.signerUserId
                if (state.toGroup && signer != null && state.memberNames.isNotEmpty() && signer !in state.memberNames) {
                    BasicText("Nothing can be sent from this wallet until the person who signs for it is back in the group.", style = t.footnote.copy(color = c.warning, textAlign = TextAlign.Center))
                }
                when {
                    state.toGroup -> Unit
                    state.toSelf && state.directPayout -> BasicText("your personal wallet", style = t.footnote.copy(color = c.inkSecondary))
                    state.groupPayout -> BasicText("approved by the group’s admins", style = t.footnote.copy(color = c.inkSecondary))
                    // Design D3: a contribution goes to the treasurer's wallet; design D5: a payout leaves it.
                    state.contribution -> BasicText("holds the money for ${state.goalName}", style = t.footnote.copy(color = c.inkSecondary))
                    state.payout -> BasicText("approved by the group's admins", style = t.footnote.copy(color = c.inkSecondary))
                }
            }
        }
        // The amount as typed, with the other unit under it.
        val unit = if (state.typingFiat) state.currency ?: state.asset else state.asset
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(unit, style = t.title2.copy(fontWeight = FontWeight.SemiBold, color = c.inkSecondary), modifier = Modifier.padding(bottom = CircasoSpacing.s2))
            Box {
                if (state.amountText.isEmpty()) BasicText("0", style = t.amountHero.copy(color = c.inkTertiary))
                BasicTextField(
                    value = state.amountText, onValueChange = onAmount, singleLine = true, enabled = state.step == PayStep.Amount && !state.payout,
                    textStyle = t.amountHero.copy(color = c.ink),
                    cursorBrush = SolidColor(c.ink), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    // As wide as its digits at any text size, so the number stays centred with its currency.
                    modifier = Modifier.focusRequester(focus).width(androidx.compose.foundation.layout.IntrinsicSize.Min).defaultMinSize(minWidth = 32.dp),
                )
            }
        }
        val other = if (state.typingFiat) state.amountMinor?.let { com.circaso.feature.wallet.domain.coinShort(it, state.decimals, state.asset) } else state.fiatText
        if (state.payout) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.size(12.dp))
            BasicText(listOfNotNull(state.fiatText?.let { "≈ $it" }, "The amount the admins approved").joinToString(" · "), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        }
        else if (state.canUseFiat) SuggestionChip(other?.let { "≈ $it · switch" } ?: "Type in ${if (state.typingFiat) state.asset else state.currency}", onToggleUnit)
        if (state.overBalance && state.block == null) BasicText((if (state.fromGroupWallet) "${state.walletName} has" else "You have") + " ${state.balanceText} on ${chainName(state.payChain.orEmpty())}.", style = t.footnote.copy(color = c.critical))
        // G2 / GW19: the payment didn't go; nothing was taken, and the approval (if any) still stands.
        state.failure?.let { f ->
            com.circaso.designsystem.chrome.Banner(
                "Not sent. Nothing was taken from ${f.wallet}." + (if (state.payout) " The approval still stands." else "") +
                    // The code's own words only when they add something ("Nothing was sent" would repeat the line).
                    (f.message.takeIf { it != com.circaso.core.common.ErrorMessages.SomethingWrong && !it.contains("Nothing was sent") && !it.contains("Nothing was taken") }?.let { " $it" } ?: ""),
                CircasoIcon.Warning, tone = com.circaso.designsystem.chrome.BannerTone.Critical,
            )
        }

        CircasoTextField(state.note, onNote, label = "Note", placeholder = "What's it for?", enabled = state.step == PayStep.Amount)

        when (val p = state.problem) {
            PayeeProblem.NoWallet -> Problem(
                "${payee?.displayName?.substringBefore(' ') ?: "They"} hasn't set up a wallet yet.",
                when (state.reminded) { RemindOutcome.Sent -> "Reminder sent."; RemindOutcome.AlreadySent -> "You reminded them today already."; RemindOutcome.HasWallet -> "They have one now: try again."; null -> null },
                action = if (state.reminded == null) "Remind them" to onRemind else null,
            )
            is PayeeProblem.NotOnChain -> {
                val mine = state.wallet?.chainsReceiving?.map { it.blockchain }.orEmpty()
                val shared = p.theirChains.filter { it in mine }
                Problem(
                    "They can't receive on ${chainName(state.blockchain.orEmpty())}.",
                    if (shared.isEmpty()) "Add one of their networks under Wallet → Networks: ${p.theirChains.joinToString { chainName(it) }}." else "Pay them on another network:",
                    chains = shared, onChain = onChain,
                )
            }
            is PayeeProblem.Other -> Problem(p.message, null)
            null -> Unit
        }

        // GW13c / GW14 / GW14b / GW20, above the details as the frames put them.
        FeeProblems(state, onUseMax)
        // Design `.cx-details`: a raised card with hairlines between the rows.
        Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surfaceRaised).border(1.dp, c.separator, CircasoShapes.md)) {
            if (state.showPayingFrom) {
                // GW9b: the wallet the money leaves; a payout's and a contribution's are fixed (GW9, GW9d).
                Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Paying from", style = t.body.copy(color = c.inkSecondary))
                    PayingFromChip(state.payingFrom, if (state.canSwitch) ({ onChoosing(true) }) else null)
                }
                RowDivider()
                DetailRow("Network", "USDC on ${chainName(state.blockchain.orEmpty())}")
            } else {
                DetailRow("From", "USDC on ${chainName(state.blockchain.orEmpty())}")
            }
            state.payoutRequest?.takeIf { state.groupPayout }?.let { r ->
                val names = r.approvedBy.map { state.memberNames[it]?.substringBefore(' ') ?: "an admin" }
                RowDivider(); DetailRow("Request", if (names.isEmpty()) "Approved" else "Approved by " + names.joinToString(" and "))
            }
            if (state.requestPayout) {
                val policy = state.groupMoney?.approvalPolicy
                RowDivider(); DetailRow("Approvals", if (policy == com.circaso.feature.chama.domain.ApprovalPolicy.AnyAdmin) "Any admin" else "2 admins")
            }
            (state.chama?.memberCount ?: state.groupMoney?.memberCount?.takeIf { state.toGroup || state.fromGroupWallet })?.let { RowDivider(); DetailRow("Visible to", "All $it members") }
            RowDivider()
            // §7b's one fee, network cost included, worked out by W17 for exactly this payment (GW13*).
            DetailRow("Fee", Modifier.semantics(mergeDescendants = true) {}) { FeeValue(state, onRetryQuote, t.subhead) }
            totalValue(state)?.let { RowDivider(); DetailRow("Total", it) }
            RowDivider()
            DetailRow(if (state.toSelf && state.directPayout) "You receive" else "${payee?.displayName?.substringBefore(' ') ?: "They"} receive${if (payee == null) "" else "s"}", state.usdcText ?: "—")
        }
        // GW9d: the target as it will stand once this lands.
        val groupGoal = state.groupMoney?.goal?.takeIf { state.toGroup }
        val groupAfter = state.progressAfter
        if (groupGoal != null && groupAfter != null) {
            val got = MinorUnits.add(state.groupMoney!!.contributedMinor, state.amountMinor ?: "0")
            Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s1)) {
                GoalProgress(
                    raised = amountText(got, state.wallet?.fiat), of = amountNumber(groupGoal.targetMinor, state.wallet?.fiat), progress = groupAfter,
                    meta = if (state.amountMinor?.let { MinorUnits.isZero(it) } == false) "After your contribution" else "Today", label = "Target after your contribution",
                )
            }
        }
        // Design D3: the goal as it will stand once this lands.
        val chama = state.chama
        val after = state.progressAfter
        if (chama != null && after != null && !state.toGroup) {
            val got = (chama.contributedMinor.toLongOrNull() ?: 0L) + (state.amountMinor?.toLongOrNull() ?: 0L)
            Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s1)) {
                GoalProgress(
                    raised = amountText(got.toString(), state.wallet?.fiat), of = amountNumber(chama.goal.targetMinor, state.wallet?.fiat), progress = after,
                    meta = if (state.amountMinor?.let { MinorUnits.isZero(it) } == false) "After your contribution" else "Today", label = "${chama.goal.name} after this progress",
                )
            }
        }
        Spacer(Modifier.height(CircasoSpacing.s2))
        // The button carries the total (the amount plus §7b's fee); the same as the amount while there is no fee.
        // GW13 / GW13d: a bare verb until the fee for what is typed is known.
        val amount = buttonTotal(state)
        CircasoButton(
            text = blockedLabel(state)?.takeIf { state.step == PayStep.Amount && state.failure == null } ?: when {
                state.step == PayStep.Sending -> "Sending from ${if (state.fromGroupWallet) state.walletName else "your wallet"}…"
                state.failure != null -> "Try Again"
                state.step == PayStep.Signing && state.requestPayout -> "Asking the admins…"
                state.step == PayStep.Signing && state.fromGroupWallet -> "Waiting for the PIN…"
                state.step == PayStep.Signing -> "Waiting for your PIN…"
                state.toGroup && amount != null -> "Pay $amount"
                state.toGroup -> "Contribute"
                state.requestPayout && amount != null -> "Request $amount Payout"
                state.requestPayout -> "Request Payout"
                state.contribution && amount != null -> "Add $amount to ${state.goalName}"
                state.contribution -> "Add to ${state.goalName}"
                (state.payout || state.directPayout) && amount != null -> "Pay $amount"
                state.payout || state.directPayout -> "Pay"
                amount != null -> "Send $amount"
                else -> "Send"
            },
            onClick = onPay, size = ButtonSize.Large, block = true, enabled = state.canPay, busy = state.step == PayStep.Signing || state.step == PayStep.Sending,
        )
        if (state.step == PayStep.Sending) BasicText("This takes a few seconds. You can close this; the card in the chat updates.", Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        feeFootnote(state).takeIf { it.isNotEmpty() }?.let { BasicText(it, style = t.caption1.copy(color = c.inkTertiary, textAlign = TextAlign.Center, fontFeatureSettings = "tnum")) }
        // GW11: the approval names its wallet; behind Circle's PIN (and iOS's bare Face ID glyph) this stays on screen.
        if (state.step == PayStep.Signing && state.fromGroupWallet && !state.requestPayout) {
            BasicText("Approve from ${state.walletName} · ${state.walletName}’s PIN, not your personal one.", Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        }
        if (state.requestPayout) BasicText(if (state.payingFrom.canSign) "Admins approve in the group chat. Then you pay it with ${state.walletName}’s PIN." else "Admins approve in the group chat. Then ${state.signerName} pays it from ${state.walletName}’s wallet.", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        if (!state.online) BasicText("You need a connection to send money.", style = t.footnote.copy(color = c.inkSecondary))
        // Typed in their money but no usable coin price yet: why Pay waits, with Try Again (the same as the chat card).
        if (state.online && !state.onHold) PriceWait(state, onRetryPrice, textAlign = TextAlign.Center)
        if (state.onHold) BasicText("Sending is paused for a short while after your account was recovered. You can still receive money.", style = t.footnote.copy(color = c.warning, textAlign = TextAlign.Center))
    }
}

/** "1 USDC = KES 129.25", "1 ETH = KES 452,375.00 · Prices by CoinGecko" (the feed's terms ask for the credit). */
internal fun rateLine(s: PayUiState): String? = s.unitRate?.let { r ->
    val line = "1 ${s.asset} = ${s.currency} ${formatRate(r).let { v -> MinorUnits.group(v.substringBefore('.').ifEmpty { "0" }) + "." + v.substringAfter('.') }}"
    if (MinorUnits.isDollar(s.asset)) line else "$line · Prices by CoinGecko"
}

internal fun formatRate(rate: String): String {
    // Below one, four significant digits ("0.001296"), so a cheap coin never reads 0.00; else to the cent.
    if (rate.substringBefore('.').trimStart('0').isEmpty()) {
        val frac = rate.substringAfter('.', "")
        val lead = frac.indexOfFirst { it != '0' }
        if (lead >= 0) return "0." + frac.take(lead + 4)
    }
    return rate.substringBefore('.') + "." + rate.substringAfter('.', "00").take(2).padEnd(2, '0')
}


@Composable
private fun Problem(title: String, detail: String?, action: Pair<String, () -> Unit>? = null, chains: List<String> = emptyList(), onChain: (String) -> Unit = {}) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircasoIconImage(CircasoIcon.Warning, c.warning, Modifier.size(18.dp))
            BasicText(title, style = t.callout.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
        }
        detail?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary)) }
        if (chains.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) { chains.forEach { SuggestionChip(chainName(it), { onChain(it) }) } }
        action?.let { (label, run) -> CircasoButton(label, run, style = ButtonStyle.Secondary, size = ButtonSize.Small) }
    }
}

/** Design C6: the money moment, with the card as it will sit in the chat. */
@Composable
private fun DoneStep(state: PayUiState, padding: PaddingValues, onDone: () -> Unit, onOpenTransfer: (String) -> Unit, onOpenChat: ((String) -> Unit)?, onAttachAgain: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val transfer = state.result
    Column(Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s6).padding(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4, Alignment.CenterVertically)) {
        AshaMark(size = CircasoSizes.avatarLg)
        BasicText(
            when {
                state.requestPayout -> "Payout requested"
                state.toGroup -> "Added to ${state.payee?.displayName ?: "the group"}"
                state.groupPayout || state.directPayout -> if (state.result?.settled == true) "Paid from ${state.walletName}" else "On its way from ${state.walletName}"
                state.contribution -> "Added to ${state.goalName}"; state.linked -> "Paid from the group"; state.payout -> "The money went"; else -> "On its way"
            },
            style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center),
        )
        BasicText(
            when {
                state.requestPayout -> "The admins of ${state.walletName} decide in the group chat. Once it’s approved, " + if (state.payingFrom.canSign) "you pay it with ${state.walletName}’s PIN." else "${state.signerName} pays it from the group wallet."
                state.toGroup -> "It’s in the group’s own wallet, and everyone in the group sees it. It settles on chain in a moment."
                state.groupPayout -> "${state.payee?.displayName ?: "They"} will have it in a moment, and the request in the chat turns to Paid."
                state.directPayout && state.toSelf -> (if (state.result?.settled == true) "It’s in your personal wallet." else "It’s on its way to your personal wallet.") + " Everyone in ${state.walletName} sees it in the group chat."
                state.directPayout -> (if (state.result?.settled == true) "${state.payee?.displayName ?: "They"} has it." else "${state.payee?.displayName ?: "They"} will have it in a moment.") + " Everyone in ${state.walletName} sees it in the group chat."
                state.contribution -> "Everyone in the group sees it on the goal. It settles on chain in a moment."
                state.linked -> "${state.payee?.displayName ?: "They"} will have it in a moment, and the group sees the request as paid."
                state.payout && state.attaching -> "${state.payee?.displayName ?: "They"} will have it in a moment. Linking it to the request…"
                state.payout -> "${state.payee?.displayName ?: "They"} will have it in a moment."
                else -> "${state.payee?.displayName ?: "They"} will see it in your chat. It settles on chain in a moment."
            },
            style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
        if (transfer != null) {
            MoneyCard(
                head = when { (state.groupPayout || state.directPayout) && transfer.settled -> "Paid from ${state.walletName}"; state.groupPayout || state.directPayout -> "From ${state.walletName}"; state.contribution -> "Contribution"; else -> "Sent" }, amount = state.fiatText ?: state.usdcText ?: "", foot = "${MinorUnits.toDisplay(transfer.amount.amountMinor, 6)} USDC · " + if (transfer.settled) "settled" else "on its way", outgoing = true,
                note = state.note.trim().takeIf { it.isNotEmpty() }, onClick = { onOpenTransfer(transfer.transferId) },
            )
        }
        // CH6 didn't land: the money went, so say so plainly and keep the link to the request one tap away.
        if (state.attachPending) {
            BasicText("The payment went, but it isn't linked to the request yet. Circaso keeps trying; you can also link it now. Don't pay it again.", style = t.footnote.copy(color = c.warning, textAlign = TextAlign.Center))
            CircasoButton("Link to the Request", onAttachAgain, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = state.online)
        }
        Spacer(Modifier.height(CircasoSpacing.s2))
        // Request Payout from someone's chat: the card waits in the group's own chat.
        val chat = if (state.requestPayout) state.payingFrom.chatId else state.chatId
        val groupChat = state.requestPayout || (state.directPayout && chat == state.payingFrom.chatId)
        if (chat != null && onOpenChat != null) CircasoButton(if (groupChat) "Open ${state.walletName}" else "Open Chat", { onOpenChat(chat) }, size = ButtonSize.Large, block = true)
        CircasoButton("Done", onDone, style = if (chat != null && onOpenChat != null) ButtonStyle.Plain else ButtonStyle.Primary, size = ButtonSize.Large, block = true)
    }
}
