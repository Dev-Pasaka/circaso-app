package com.circaso.feature.messaging.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.components.ComposerAmount
import com.circaso.designsystem.components.ComposerBar
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.presentation.PayStep
import com.circaso.feature.wallet.presentation.PayUiState
import com.circaso.feature.wallet.presentation.PayViewModel
import com.circaso.feature.wallet.presentation.PayFromSheetContent
import com.circaso.feature.wallet.presentation.fiatDisplay
import kotlinx.coroutines.delay

/**
 * What the screen around the composer shares with it: the chip of a card being paid again ("#3.875968 USDC", its exact
 * coin amount, set by Try Again on a Not sent card). A typed `#500` needs nothing here: it stays in the draft.
 */
@Stable
class ChatComposerMoney {
    var payChip by mutableStateOf<String?>(null)
}

/**
 * The composer on the dark stage, replying to a moment (2026-11-14, Pasaka: money in Moments works as it does in
 * chats): the field on glass in [embedded]'s layout, where the arrow sends a plain reply as well as money.
 */
class StageComposer(
    val placeholder: String,
    /** What a screen reader calls the field, and the arrow while it sends words ("Send reply to Wanjiru"). */
    val label: String,
    val sendLabel: String,
    /** The typed text can go as a reply now (the screen decides: it sends it, with the moment quoted). */
    val canSendText: Boolean,
    val onFocus: (Boolean) -> Unit = {},
    /** Beside the empty field, where a chat has its mic (the moment's heart). */
    val trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
)

/** The chat's in-line payment for [vm], as the composer drives it; null without one. */
@Composable
internal fun rememberQuickPay(vm: PayViewModel?): QuickPay? = vm?.let {
    val payState by it.state.collectAsState()
    QuickPay(payState, it::onAmount, it::onNote, it::onPay, it::onQuickReset, it::onErrorShown, it::onAssetTag, it::onChoosing, it::onPayFrom, { amountMinor, asset, note, moment -> it.onRetryExact(amountMinor, asset, note, moment) }, it::onRetryQuote, it::onUseMax, it::onRetryPrice)
}

/**
 * How the composer draws the typed amount (C3, Pasaka 2026-11-09): in the unit the money card will use, the person's
 * currency ("KES 500") when the coin has a price, else the coin ("0.5 ETH"); the pill formatted as the card writes it.
 */
internal fun composerAmount(text: String, pay: PayUiState?): ComposerAmount? {
    val token = amountToken(text) ?: return null
    val typed = token.amount.trimEnd('.')
    val currency = pay?.currency
    return if (pay != null && pay.typingFiat && currency != null) {
        val chip = MinorUnits.fromDisplay(typed, 2)?.let { "$currency ${fiatDisplay(it)}" } ?: "$currency $typed"
        ComposerAmount(token.start, token.end, "$currency ", "", chip)
    } else {
        val asset = pay?.asset ?: com.circaso.feature.wallet.domain.Wallet.Usdc
        ComposerAmount(token.start, token.end, "", " $asset", "$typed $asset")
    }
}

/**
 * The chat composer with its money (designs C2/C3/D2): text, `/asha`, `#500` drawn as money then a pill, the `@coin`
 * picker, the money card the arrow pays from, and a request's payout card. The thread uses it under the conversation;
 * the call's money sheet uses the same one ([embedded]), bound to the call's chat, so money works the same in both;
 * and so does the reply to a moment ([stage]), where `#500` is money for that moment.
 */
@Composable
internal fun ChatComposer(
    state: ThreadUiState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    quickPay: QuickPay?,
    money: ChatComposerMoney,
    focusRequester: FocusRequester,
    /** A one-off line for the screen's toast (a payment that failed, a request sent to the admins). */
    onToast: (text: String, problem: Boolean) -> Unit,
    onAsha: (() -> Unit)? = null,
    /** Where the chat can't take money in place: the finished `#500 ` opens Pay (direct) or the contribution (group). */
    onPay: ((amount: String?) -> Unit)? = null,
    onContribute: ((amount: String?) -> Unit)? = null,
    media: ThreadMediaActions? = null,
    onAttach: (() -> Unit)? = null,
    payout: QuickPay? = null,
    onPayoutClose: () -> Unit = {},
    /** The room the money card may take (the thread's height); read inside the card only, see ThreadContent. */
    cardMaxHeight: () -> Dp = { Dp.Unspecified },
    embedded: Boolean = false,
    /** The money card's payment was signed (or its request sent): called with its last state, before the card resets. */
    onPaid: (PayUiState) -> Unit = {},
    stage: StageComposer? = null,
) {
    @Suppress("NAME_SHADOWING") val embedded = embedded || stage != null
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val chipHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val payChip = money.payChip
    // Navigation hands the chat a quick pay only where `#` means money (a direct chat, a group taking contributions).
    val payable = quickPay != null && !state.isSupport
    fun clearPay() { money.payChip = null; onDraft(""); quickPay?.onReset?.invoke() }
    val typingAmount = if (payChip == null && payable) typingAmount(state.draft) else null
    LaunchedEffect(typingAmount) {
        if (typingAmount != null) quickPay?.onAmount?.invoke(typingAmount) else if (payChip == null) quickPay?.onReset?.invoke()
    }
    // The amount stays in the text ("lunch #500 @usdc thanks"): the rest of it is the coin and the note, kept current.
    LaunchedEffect(state.draft, typingAmount) {
        if (typingAmount != null) {
            val (tag, note) = splitAssetTag(afterTypedAmount(state.draft))
            quickPay?.onAssetTag?.invoke(tag)
            quickPay?.onNote?.invoke(note)
        }
    }
    val paying = (payChip != null || typingAmount != null) && payout == null
    val cardMotion = if (CircasoTheme.reduceMotion) Modifier else Modifier.animateContentSize(tween(CircasoMotion.BaseMs))
    // "@" after the amount: the coins this wallet holds, the most valuable first, narrowing as the person types.
    // Anywhere in the message, before or after the amount (Pasaka, 2026-11-07), in any chat money can go to.
    val coinQuery = if (payout == null && (payChip != null || payable)) coinQuery(state.draft) else null
    val coinChoices = coinQuery?.let { q -> heldCoins(quickPay?.state?.wallet).filter { it.symbol.startsWith(q, ignoreCase = true) }.take(4) }.orEmpty()
    // Sent (the SDK came back and the card is in the chat): the composer is ready for the next message.
    LaunchedEffect(quickPay?.state?.step) {
        val q = quickPay?.state
        if (q?.step == PayStep.Done) { onPaid(q); clearPay() }
    }
    // GW9c from a chat: nothing moved, so there is no card here; say where the request went.
    LaunchedEffect(quickPay?.state?.requested) { quickPay?.state?.requested?.let { onToast("Asked the admins of ${quickPay.state.walletName}. The request is in the group chat.", false) } }
    LaunchedEffect(quickPay?.state?.error) { quickPay?.state?.error?.let { e -> quickPay.onErrorShown(); onToast(e, true) } }
    val canAdd = (state.chama != null || state.groupMoney != null) && state.canContribute
    ComposerBar(
        value = state.draft,
        onValueChange = { text ->
            // A chat that takes money keeps the amount in the text (drawn as money, then a pill); one that can't hands a
            // finished amount to Pay.
            val amount = if (payChip == null && !payable) amountShortcut(text) else null
            when {
                payChip != null -> {
                    onDraft(text)
                    val (tag, note) = splitAssetTag(text)
                    quickPay?.onAssetTag?.invoke(tag)
                    quickPay?.onNote?.invoke(note)
                }
                amount != null && onPay != null && !state.isGroup && !state.isSupport -> { onDraft(amount.second); onPay(amount.first) }
                amount != null && onContribute != null && state.canContribute -> { onDraft(amount.second); onContribute(amount.first) }
                else -> onDraft(text)
            }
        },
        // In the call's sheet the arrow only pays: a plain message would land in a chat nobody can see from there.
        placeholder = stage?.placeholder ?: if (embedded) "#amount and a note" else if (canAdd) "Message, or #amount to add" else if (!state.isGroup && !state.isSupport && (onPay != null || payable)) "Message, or #amount to send" else "Message",
        onAttach = onAttach,
        focusRequester = focusRequester,
        sendLabel = if (paying) quickPay?.state?.let { q -> payLabel(q) + (q.payee?.displayName?.takeIf { !q.toGroup && q.failure == null && q.step != PayStep.Signing }?.let { " to $it" } ?: "") } ?: "Send" else stage?.sendLabel ?: "Send",
        showSend = paying || state.draft.isNotBlank() || (media == null && stage?.trailing == null),
        trailing = if (payChip == null && media != null && state.draft.isBlank()) ({ VoiceButton(state.recording, media.onVoiceStart, media.onVoiceEnd) })
        else stage?.trailing?.takeIf { !paying && state.draft.isBlank() },
        canSend = if (paying) quickPay?.state?.canPay == true else stage?.canSendText ?: (!embedded && state.canSend),
        // From the tap until Circle's PIN (or Face ID) is up, and while it sends: the arrow spins, so the wait reads as
        // work, not a frozen screen (Pasaka, 2026-11-08).
        sendBusy = paying && quickPay?.state?.step.let { it == PayStep.Signing || it == PayStep.Sending },
        // The composer's arrow is the card's Pay: straight to the PIN, nothing in between.
        onSend = if (paying) ({ quickPay?.onPay?.invoke(); Unit }) else if (embedded && stage == null) ({}) else onSend,
        // A retried card's chip (its exact coin amount), at the start of the field; a typed amount is drawn in place.
        chip = payChip?.removePrefix("#"),
        onChipEdit = {
            // A retried card's chip reads its coin amount ("#3.875968 USDC"); edit it in the person's currency.
            val back = payChip.orEmpty().let { chip -> if (' ' in chip) quickPay?.state?.fiatText?.substringAfter(' ')?.replace(",", "")?.removeSuffix(".00")?.let { "#$it" } ?: chip.substringBefore(' ') else chip }
            money.payChip = null; quickPay?.onReset?.invoke(); onDraft(back + if (state.draft.isNotEmpty()) " " + state.draft else "")
        },
        amount = if (payChip == null && payable) ({ text -> composerAmount(text, quickPay?.state) }) else null,
        embedded = embedded,
        onStage = stage != null,
        onFocusChange = stage?.onFocus,
        fieldLabel = stage?.label,
        floating = {
            AnimatedVisibility(
                coinChoices.isNotEmpty(),
                enter = if (CircasoTheme.reduceMotion) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.QuickMs)) + expandVertically(tween(CircasoMotion.QuickMs)),
                exit = if (CircasoTheme.reduceMotion) fadeOut(tween(CircasoMotion.QuickMs)) else fadeOut(tween(CircasoMotion.QuickMs)) + shrinkVertically(tween(CircasoMotion.QuickMs)),
            ) {
                CoinSuggestions(coinChoices, sideInset = if (stage != null) 0.dp else CircasoSpacing.s3) { coin ->
                    chipHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    val text = state.draft.replace(Regex("""@[A-Za-z]*$"""), "@${coin.symbol.lowercase()} ")
                    onDraft(text)
                    val (tag, note) = splitAssetTag(if (payChip != null) text else afterTypedAmount(text))
                    quickPay?.onAssetTag?.invoke(tag)
                    quickPay?.onNote?.invoke(note)
                }
            }
            // The chat's money card (C3 grown into the whole review): floating over the thread, Pay opens the PIN.
            AnimatedVisibility(
                paying && quickPay != null,
                enter = if (CircasoTheme.reduceMotion) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.QuickMs)) + slideInVertically(tween(CircasoMotion.QuickMs)) { it / 2 },
                exit = fadeOut(tween(CircasoMotion.QuickMs / 2)),
            ) { quickPay?.let { q -> ChatMoneyCard(q.state, q.onPay, { q.onChoosing(true) }, cardMotion, sideInset = if (stage != null) 0.dp else CircasoSpacing.s3, maxHeight = cardMaxHeight(), onRetryQuote = q.onRetryQuote, onUseMax = q.onUseMax, onRetryPrice = q.onRetryPrice) } }
            // An approved request's Pay (D5/GW9): the same card, the amount and payee locked by the approval.
            AnimatedVisibility(
                payout != null,
                enter = if (CircasoTheme.reduceMotion) fadeIn(tween(CircasoMotion.QuickMs)) else fadeIn(tween(CircasoMotion.QuickMs)) + slideInVertically(tween(CircasoMotion.QuickMs)) { it / 2 },
                exit = fadeOut(tween(CircasoMotion.QuickMs / 2)),
            ) { payout?.let { q -> ChatMoneyCard(q.state, q.onPay, { q.onChoosing(true) }, cardMotion, onClose = onPayoutClose, maxHeight = cardMaxHeight(), onRetryQuote = q.onRetryQuote, onUseMax = q.onUseMax, onRetryPrice = q.onRetryPrice) } }
        },
        above = {
            // Support never takes money (so nobody pretending to be Circaso can ask for it): say so the moment someone
            // types an amount here, instead of quietly sending it as text.
            AnimatedVisibility(
                state.isSupport && amountToken(state.draft) != null,
                enter = fadeIn(tween(CircasoMotion.QuickMs)), exit = fadeOut(tween(CircasoMotion.QuickMs)),
            ) {
                BasicText(
                    "Circaso support never asks for money, so payments are off in this chat.",
                    style = t.footnote.copy(color = c.inkSecondary),
                    modifier = Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s2),
                )
            }
            // Grows in and out rather than jumping the thread; the last start time stays through the exit.
            var stripSince by remember { mutableStateOf(0L) }
            state.recordingSince?.let { stripSince = it }
            val still = CircasoTheme.reduceMotion
            AnimatedVisibility(
                state.recording,
                enter = if (still) fadeIn(tween(CircasoMotion.BaseMs)) else fadeIn(tween(CircasoMotion.BaseMs)) + expandVertically(tween(CircasoMotion.BaseMs)),
                exit = if (still) fadeOut(tween(CircasoMotion.QuickMs)) else fadeOut(tween(CircasoMotion.QuickMs)) + shrinkVertically(tween(CircasoMotion.QuickMs)),
            ) { RecordingStrip(stripSince) }
            // On the stage the screen says it itself, on glass over the photo, and only for money (a reply waits and sends).
            if (!state.online && stage == null) {
                BasicText(
                    // Money is online only (offline-first.md): the sheet, which is only for money, says so.
                    if (embedded) "You're offline. Money needs a connection." else "You're offline. Messages send when you reconnect.",
                    style = t.footnote.copy(color = c.inkSecondary),
                    modifier = Modifier.padding(start = if (embedded) 0.dp else CircasoSpacing.s4, end = if (embedded) 0.dp else CircasoSpacing.s4, top = CircasoSpacing.s2),
                )
            }
        },
    )
}

/**
 * Money during a call (Pasaka, 2026-11-09): the call's chat composer on a sheet over the call, so `#500 for lunch`, the
 * money card, the `@coin` picker, the PIN and sending work exactly as in the chat, and the call never leaves the screen.
 * [thread] and [pay] are the call chat's own (null while they load); a payment signed here is a chat money card like
 * any other. [onPaid] gets the payment's last state; the sheet closes itself.
 */
@Composable
fun BoxScope.ChatMoneySheet(
    visible: Boolean,
    thread: ThreadViewModel,
    pay: PayViewModel?,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    onPaid: (PayUiState) -> Unit,
) {
    val state by thread.state.collectAsState()
    val quickPay = rememberQuickPay(pay)
    val money = remember { ChatComposerMoney() }
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // A line about the payment, and whether it is a problem (red) or just news (the request sent to the admins).
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(notice) { if (notice != null) { delay(NoticeMillis); notice = null } }
    // Opened to pay: `#` is in the field and the keyboard up, so the next thing typed is the amount (as the chat's Pay).
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        if (state.draft.isBlank()) onDraftStart(thread, quickPay)
        delay(CircasoMotion.BaseMs.toLong())
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    // While the PIN is up the sheet stays: closing it wouldn't stop the payment, and the person should see it through.
    val signing = quickPay?.state?.step.let { it == PayStep.Signing || it == PayStep.Sending }
    val close = {
        if (!signing) {
            keyboard?.hide()
            // A lone `#` was ours, not theirs: don't leave it in the chat's draft.
            if (state.draft.trim() == "#") thread.onDraft("")
            onClose()
        }
    }
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // A group's goal, as H5 heads its sheet: the goal, who sees it land, and how far along it is.
    val goal = state.chama ?: state.groupMoney?.asChama()
    CircasoSheet(visible, close, label = title) {
        if (goal != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                com.circaso.designsystem.components.Avatar(goal.goal.name, size = com.circaso.designsystem.theme.CircasoSizes.avatarMd)
                Column(Modifier.weight(1f)) {
                    BasicText(title, style = t.headline.copy(color = c.ink), modifier = Modifier.semantics { heading() })
                    BasicText(subtitle, style = t.footnote.copy(color = c.inkSecondary))
                }
                com.circaso.designsystem.components.CircasoIconButton(com.circaso.designsystem.components.CircasoIcon.Close, "Close", close, enabled = !signing)
            }
            val left = ((goal.goal.targetMinor.toLongOrNull() ?: 0L) - (goal.contributedMinor.toLongOrNull() ?: 0L)).coerceAtLeast(0L)
            com.circaso.designsystem.components.GoalProgress(
                com.circaso.feature.chama.presentation.amountText(goal.contributedMinor, state.fiat),
                com.circaso.feature.chama.presentation.amountNumber(goal.goal.targetMinor, state.fiat), goal.progress,
                meta = if (left > 0) "${com.circaso.feature.chama.presentation.amountText(left.toString(), state.fiat)} to go" else "Goal reached",
                label = "${goal.goal.name} progress",
            )
        } else {
            Column(Modifier.fillMaxWidth()) {
                SheetHead(title, close, closeEnabled = !signing)
                BasicText(subtitle, style = t.footnote.copy(color = c.inkSecondary))
            }
        }
        // Until the chat and its payment are ready, one honest line instead of a composer whose `#` does nothing.
        val waiting = when {
            state.chat == null -> "Opening the chat…"
            quickPay == null -> "Money can't go to this chat from here right now."
            else -> null
        }
        AnimatedVisibility(notice != null || waiting != null, enter = fadeIn(tween(CircasoMotion.QuickMs)), exit = fadeOut(tween(CircasoMotion.QuickMs))) {
            val last = remember { arrayOf<Pair<String, Boolean>>("" to false) }
            val (line, problem) = (notice ?: waiting?.let { it to false })?.also { last[0] = it } ?: last[0]
            BasicText(
                line,
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = t.footnote.copy(color = if (problem) c.critical else c.inkSecondary),
            )
        }
        ChatComposer(
            state, thread::onDraft, thread::onSend, quickPay, money, focus,
            onToast = { text, problem -> notice = text to problem },
            embedded = true,
            onPaid = { s -> keyboard?.hide(); onPaid(s); onClose() },
        )
    }

    // GW10: Paying from, opened from the money card, over the sheet.
    quickPay?.let { q ->
        CircasoSheet(visible = visible && q.state.choosing, onDismiss = { q.onChoosing(false) }, label = "Pay from") {
            PayFromSheetContent(q.state.choices, q.state.payingFrom.walletId, q.state.balances, q.state.online, q.state.asOf, onPick = q.onPayFrom, onClose = { q.onChoosing(false) })
        }
    }
}

/** A fresh `#` in the call chat's composer, its money card reset (the chat's own Pay does the same). */
private fun onDraftStart(thread: ThreadViewModel, quickPay: QuickPay?) {
    quickPay?.onReset?.invoke()
    thread.onDraft("#")
}

/** How long a line about a payment stays on the call's money sheet. */
private const val NoticeMillis = 5_000L
