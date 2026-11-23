package com.circaso.feature.ramp.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.circaso.core.common.maskedForDisplay
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.SheetBadge
import com.circaso.designsystem.chrome.SheetTone
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.DetailRow
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.MoneyCard
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.ramp.domain.RampAmount
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.chainName
import kotlinx.coroutines.delay

// A sale's status (guide "Selling USDC for M-Pesa", "States of a sale"): where the person's money is, in one sentence,
// at every step. Kept apart from the drawing so every state's words can be tested.

/** What the buttons under a sale are for. */
internal enum class SaleActions {
    /** The sale waits for this phone's USDC: "Send USDC", and Cancel. */
    Send,
    /** The wallet can't cover the USDC and its fee: Cancel is the way on. */
    Short,
    /** Nothing to do but wait: the server moves it on. */
    Following,
    /** Paid out. */
    Done,
    /** Money may be anywhere but where it should: Support, never "Try Again". */
    Support,
    /** Ended with nothing taken, or the USDC back: a new sale may be started. */
    Again,
}

/** What a sale's state says to the person. */
internal data class SaleLook(
    /** Null is the spinner, in the badge's place. */
    val icon: CircasoIcon?,
    val tone: SheetTone,
    val title: String,
    val message: String,
    val actions: SaleActions,
    /** The badge is a settled sign, not the waiting one. */
    val settled: Boolean,
    /** Support will ask for it: the reference comes first in the figures. */
    val needsReference: Boolean = false,
)

/** "18:15": the sale's deadline for the USDC, in the person's own time. */
internal fun depositDeadline(order: RampOrder): String? = (order.deposit?.expiresAt ?: order.expiresAt)?.let { formatLocalTime(it, LocalTimeStyle.Clock) }

internal fun saleLook(order: RampOrder, s: CashOutUiState): SaleLook {
    val usdc = order.pay.text
    val kes = order.receive.text
    val chain = chainName(order.blockchain)
    // The number never breaks across two lines in the middle of a sentence.
    val phone = order.maskedPhone.takeIf { it.isNotBlank() }?.maskedForDisplay()?.replace(' ', ' ') ?: "your M-Pesa number"
    val until = depositDeadline(order)
    val held = until?.let { " $kes is held for you until $it." } ?: ""
    return when (order.state) {
        RampOrderState.AwaitingTransfer -> when {
            s.sent -> SaleLook(null, SheetTone.Neutral, "Sending your USDC", "$usdc is on its way to Circaso's partner. This takes a few seconds.", SaleActions.Following, settled = false)
            s.sending -> SaleLook(null, SheetTone.Neutral, "Approve the transfer", "Confirm sending $usdc to finish your sale.", SaleActions.Send, settled = false)
            s.depositOver -> SaleLook(CircasoIcon.Clock, SheetTone.Warning, "Time's up for this sale", "Your USDC wasn't sent in time, so nothing left your wallet. This updates in a moment.", SaleActions.Following, settled = true)
            s.sendHold == SendHold.Short -> {
                val fee = (s.sendFee as? SendFee.Quoted)?.amountMinor
                val need = fee?.let { MinorUnits.add(order.deposit?.amountMinor ?: order.pay.amountMinor, it) } ?: (order.deposit?.amountMinor ?: order.pay.amountMinor)
                val has = s.balanceMinor?.let { " and holds ${CashOutUiState.usdcText(it)}" } ?: ""
                SaleLook(
                    CircasoIcon.Warning, SheetTone.Warning, "Not enough USDC for this sale",
                    "Your wallet needs ${CashOutUiState.usdcText(need)}${if (fee != null) " for this sale and its network fee" else " for this sale"}$has. Nothing left your wallet. Cancel this sale, then sell a little less.",
                    SaleActions.Short, settled = true,
                )
            }
            s.sendHold == SendHold.OtherNetwork -> SaleLook(
                CircasoIcon.Network, SheetTone.Warning, "Switch to ${s.network.label} to finish",
                "This sale is on ${s.network.label}, and your wallet is showing the other network. Switch on the Wallet tab, then send. Nothing left your wallet.",
                SaleActions.Send, settled = true,
            )
            s.sendHold == SendHold.Cancelled -> SaleLook(CircasoIcon.ArrowUp, SheetTone.Neutral, "Send your USDC to finish", "You didn't approve the transfer, so nothing left your wallet.$held", SaleActions.Send, settled = true)
            s.sendHold == SendHold.Unknown -> SaleLook(CircasoIcon.Warning, SheetTone.Warning, "We couldn't confirm the transfer", CashOutViewModel.SendUnknown, SaleActions.Send, settled = true, needsReference = true)
            s.sendHold == SendHold.Failed -> SaleLook(CircasoIcon.Warning, SheetTone.Warning, "Your USDC wasn't sent", s.sendFailure?.message ?: CashOutViewModel.SendFailed, SaleActions.Send, settled = true)
            s.sendFee == null && s.pricingSend -> SaleLook(null, SheetTone.Neutral, "Getting your sale ready", "Checking the network fee for sending $usdc.", SaleActions.Send, settled = false)
            s.sendFee is SendFee.Quoted -> {
                val fee = (s.sendFee as SendFee.Quoted).amountMinor
                SaleLook(
                    CircasoIcon.ArrowUp, SheetTone.Neutral, "Send your USDC to finish",
                    "Sending $usdc costs a network fee of ${CashOutUiState.usdcText(fee)}, so ${CashOutUiState.usdcText(MinorUnits.add(order.deposit?.amountMinor ?: order.pay.amountMinor, fee))} leaves your wallet.$held",
                    SaleActions.Send, settled = true,
                )
            }
            else -> SaleLook(CircasoIcon.ArrowUp, SheetTone.Neutral, "Send your USDC to finish", "Approve sending $usdc and $kes goes to your M-Pesa.${until?.let { " It's held for you until $it." } ?: ""}", SaleActions.Send, settled = true)
        }
        RampOrderState.Funded -> SaleLook(null, SheetTone.Neutral, "Sending your M-Pesa payment", "Your USDC reached Circaso's partner. They're sending $kes to $phone.", SaleActions.Following, settled = false)
        RampOrderState.PayingOut -> SaleLook(null, SheetTone.Neutral, "Sending your M-Pesa payment", "$kes is on its way to $phone. It can take a minute.", SaleActions.Following, settled = false)
        RampOrderState.Completed -> SaleLook(CircasoIcon.Check, SheetTone.Neutral, "$kes sent to your M-Pesa", "Look out for the M-Pesa message on $phone.", SaleActions.Done, settled = true)
        RampOrderState.Refunding -> SaleLook(null, SheetTone.Neutral, "Your USDC is on its way back", refundWords(order.failureReason, usdc, done = false), SaleActions.Following, settled = false)
        RampOrderState.Refunded -> SaleLook(CircasoIcon.ArrowDown, SheetTone.Neutral, "Your USDC is back in your wallet", refundWords(order.failureReason, usdc, done = true, chain = chain), SaleActions.Again, settled = true)
        // Nothing arrived by the deadline. If this phone sent it late, it comes back by itself (REFUNDING follows).
        RampOrderState.Expired -> if (s.sent) SaleLook(
            CircasoIcon.Clock, SheetTone.Warning, "This sale timed out",
            "Your USDC didn't reach Circaso's partner in time. If it arrives late, it's sent back to your wallet by itself, and this page updates when it is.",
            SaleActions.Support, settled = true, needsReference = true,
        ) else SaleLook(CircasoIcon.Clock, SheetTone.Neutral, "This sale timed out", "Nothing left your wallet. You can start a new sale whenever you like.", SaleActions.Again, settled = true)
        RampOrderState.Cancelled -> SaleLook(CircasoIcon.Close, SheetTone.Neutral, "Sale cancelled", "Nothing left your wallet.", SaleActions.Again, settled = true)
        RampOrderState.Review -> SaleLook(
            CircasoIcon.Shield, SheetTone.Warning, "We're checking this sale",
            "Your money is safe. A person at Circaso is checking this sale, and we'll update you. Please don't send it again.",
            SaleActions.Support, settled = true, needsReference = true,
        )
        // A newer server's state, or a buying state (never on a sale): still under way, never an ending.
        else -> SaleLook(null, SheetTone.Neutral, "Working on your sale", "This is taking a moment. You don't need to do anything.", SaleActions.Following, settled = false)
    }
}

/** Why the USDC is coming back, worded from `failureReason` (guide: "word it, don't show it"). */
internal fun refundWords(reason: String?, usdc: String, done: Boolean, chain: String? = null): String {
    val where = if (done) "$usdc is back in your wallet${chain?.let { " on $it" } ?: ""}." else "$usdc is on its way back to your wallet."
    return when (reason) {
        "late_transfer" -> "Your USDC arrived after the sale had ended, so it was sent back. $where"
        "amount_mismatch" -> "A different amount arrived than this sale needed, so all of it was sent back. $where"
        "resolved_refund" -> "Circaso sent your USDC back after checking this sale. $where"
        "never_funded" -> "No USDC arrived for this sale. Nothing left your wallet."
        // payout_failed, payout_missed, payout_unavailable, payout_rejected, or none: the seller couldn't pay.
        else -> "We couldn't pay you out on M-Pesa, so no shillings were sent. $where"
    }
}

/**
 * A sale's progress as four plain steps: the USDC is sent, Circaso's partner receives it, the M-Pesa payout is sent,
 * the shillings are on the phone. Each step's words change with where it stands: what is still to come is said as
 * something that will happen, never as if it had. Empty for any ending other than paid out.
 */
internal fun saleStages(order: RampOrder, sent: Boolean, sending: Boolean): List<OrderStage> {
    val at = when (order.state) {
        RampOrderState.AwaitingTransfer -> if (sent) 1 else 0
        RampOrderState.Funded -> 2
        RampOrderState.PayingOut -> 3
        RampOrderState.Completed -> 4
        else -> return emptyList()
    }
    val kes = order.receive.text
    fun mark(i: Int) = when { i < at -> StageMark.Done; i == at -> StageMark.Active; else -> StageMark.Waiting }
    return listOf(
        OrderStage(when (mark(0)) { StageMark.Done -> "USDC sent"; else -> if (sending) "Approving the transfer" else "Send your USDC" }, mark(0)),
        OrderStage(when (mark(1)) { StageMark.Done -> "Received by Circaso's partner"; StageMark.Active -> "Reaching Circaso's partner"; else -> "Circaso's partner receives it" }, mark(1)),
        OrderStage(when (mark(2)) { StageMark.Done -> "M-Pesa payout sent"; StageMark.Active -> "Sending your M-Pesa payment"; else -> "They send your M-Pesa payment" }, mark(2)),
        OrderStage(when (mark(3)) { StageMark.Done -> "$kes on your phone"; StageMark.Active -> "Waiting for M-Pesa to confirm"; else -> "$kes lands on your phone" }, mark(3)),
    )
}

/** What the two money figures are called: future while under way, past once paid out, conditional for an ending. */
internal fun saleFigureLabels(state: RampOrderState, look: SaleLook): Pair<String, String> = when {
    state == RampOrderState.Completed -> "You sold" to "You received"
    look.actions == SaleActions.Again || look.actions == SaleActions.Support -> "Amount" to "Would have received"
    else -> "You sell" to "You receive"
}

/** "KES 26"; "None" at zero. */
internal fun saleFee(amount: RampAmount?): String = when {
    amount == null -> "None"
    MinorUnits.isZero(amount.amountMinor) -> "None"
    else -> amount.text
}

/**
 * A sale from the moment it is made until the shillings are on the person's phone, or until it ends another way: the
 * badge and one sentence on where the money is, the story so far, then every figure (every fee named). Every change
 * eases in, and a payout that lands while the person watches is felt as well as seen.
 */
@Composable
internal fun SaleOrderPage(
    state: CashOutUiState, order: RampOrder, bottomInset: Dp,
    onBack: () -> Unit, onSupport: () -> Unit, onSend: () -> Unit, onCancel: () -> Unit, onAgain: () -> Unit, onCopy: (String) -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val look = saleLook(order, state)
    val still = CircasoTheme.reduceMotion
    val done = order.state == RampOrderState.Completed
    val haptics = LocalHapticFeedback.current
    var seen by remember { mutableStateOf(order.state) }
    LaunchedEffect(order.state) {
        if (done && seen != RampOrderState.Completed) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            if (!still) delay(CircasoMotion.MomentMs.toLong())
        }
        seen = order.state
    }
    val landing = done && seen != RampOrderState.Completed
    AddMoneyPageFrame(
        bottomInset,
        footer = {
            AnimatedContent(look.actions, transitionSpec = { easeOver(still) }, label = "sale actions") { kind ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (kind) {
                        SaleActions.Send -> {
                            CircasoButton(
                                if (state.sending) "Approving…" else "Send USDC", onSend, size = ButtonSize.Large, block = true,
                                enabled = state.canSend || state.sending || state.pricingSend, busy = state.sending || state.pricingSend,
                            )
                            AnimatedContent(
                                when {
                                    !state.online -> "You're offline. Sending your USDC needs a connection."
                                    state.depositLeft != null && !state.depositOver -> "Send within ${clock(state.depositLeft)} to finish this sale."
                                    else -> "You'll approve it as you do any payment."
                                },
                                // Only the minutes and seconds move; the line keeps its place.
                                contentKey = { it.substringBefore("within") }, transitionSpec = { easeOver(still) }, label = "send note", modifier = Modifier.padding(top = CircasoSpacing.s2),
                            ) { FooterNote(it, Modifier.semantics { contentDescription = it }) }
                            CircasoButton(
                                "Cancel Sale", onCancel, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true,
                                enabled = state.canCancel && state.online && !state.cancelling, busy = state.cancelling,
                            )
                        }
                        SaleActions.Short -> {
                            CircasoButton("Cancel Sale", onCancel, size = ButtonSize.Large, block = true, enabled = state.canCancel && state.online && !state.cancelling, busy = state.cancelling)
                            Reveal(!state.online) { FooterNote(CashOutOfflineNote, Modifier.padding(top = CircasoSpacing.s2)) }
                            CircasoButton("Close", onBack, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
                        }
                        SaleActions.Done -> CircasoButton("Done", onBack, size = ButtonSize.Large, block = true)
                        SaleActions.Support -> {
                            CircasoButton("Contact Support", onSupport, size = ButtonSize.Large, block = true)
                            CircasoButton("Done", onBack, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
                        }
                        SaleActions.Again -> {
                            CircasoButton("Try Again", onAgain, size = ButtonSize.Large, block = true, enabled = state.online)
                            Reveal(!state.online) { FooterNote(CashOutOfflineNote, Modifier.padding(top = CircasoSpacing.s2)) }
                            CircasoButton("Done", onBack, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
                        }
                        SaleActions.Following -> {
                            CircasoButton("Close", onBack, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
                            AnimatedContent(
                                when {
                                    !state.online -> "You're offline. Your sale carries on, and this updates when you're back."
                                    state.stale -> "Couldn't check just now. Still trying."
                                    order.state == RampOrderState.Refunding -> "You can leave this screen. We'll tell you when your USDC is back."
                                    else -> "You can leave this screen. We'll tell you when the money is on your phone."
                                },
                                transitionSpec = { easeOver(still) }, label = "sale note", modifier = Modifier.padding(top = CircasoSpacing.s2),
                            ) { FooterNote(it) }
                        }
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(
                Pair(look.icon, look.tone) to look.settled,
                transitionSpec = { fadeIn(tween(if (still) 0 else CircasoMotion.BaseMs)) togetherWith fadeOut(tween(if (still) 0 else CircasoMotion.QuickMs)) },
                contentAlignment = Alignment.Center, label = "sale badge",
            ) { (sign, settled) -> if (settled && sign.first != null) SheetBadge(sign.first!!, sign.second) else WaitingBadge(sign.first) }
            AnimatedContent(
                look.title to look.message, transitionSpec = { easeOver(still) }, label = "sale state",
                modifier = Modifier.fillMaxWidth().padding(top = CircasoSpacing.s4).semantics { liveRegion = LiveRegionMode.Polite },
            ) { (title, message) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    BasicText(title, Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
                    BasicText(message, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                }
            }
            // The app's own money moment, for shillings that have arrived: the M-Pesa code is what the person will match.
            Reveal(done && !landing) {
                Box(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s5), contentAlignment = Alignment.Center) {
                    MoneyCard(head = "Cashed out to M-Pesa", amount = order.receive.text, foot = order.receipt?.let { "M-Pesa receipt $it" } ?: "Sold ${order.pay.text}", outgoing = true, symbol = order.receive.unit)
                }
            }
        }
        Column {
            val stages = saleStages(order, state.sent, state.sending)
            val shown = lastOf(stages.takeIf { it.isNotEmpty() })
            Reveal(stages.isNotEmpty() && (!done || landing)) { if (shown != null) Stages(shown, Modifier.padding(bottom = CircasoSpacing.s5)) }
            val (selling, getting) = saleFigureLabels(order.state, look)
            ListGroup {
                if (look.needsReference) { ReferenceRow(order.orderId, onCopy); RowDivider() }
                FigureRow(selling, order.pay.text)
                RowDivider()
                FigureRow(getting, order.receive.text, strong = true)
                RowDivider()
                FigureRow("Rate", rateLine(order.rate))
                RowDivider()
                FigureRow("Circaso fee", saleFee(order.fee))
                RowDivider()
                FigureRow("M-Pesa payout fee", saleFee(order.providerFee))
                // The wallet's own fee for sending the USDC, when there is one: on top, and said before it is approved.
                (state.sendFee as? SendFee.Quoted)?.takeIf { order.state == RampOrderState.AwaitingTransfer }?.let { RowDivider(); FigureRow("Network fee", CashOutUiState.usdcText(it.amountMinor) + ", on top") }
                RowDivider()
                DetailRow("To", "M-Pesa " + order.maskedPhone.maskedForDisplay())
                order.receipt?.let { RowDivider(); ReceiptRow(it, onCopy) }
                RowDivider()
                DetailRow("Network", chainName(order.blockchain))
                if (!look.needsReference) { RowDivider(); ReferenceRow(order.orderId, onCopy) }
            }
            FailureNote(state.sendFailure?.takeIf { look.actions == SaleActions.Send && state.sendHold != SendHold.Failed && state.sendHold != SendHold.Unknown }, onCopy)
            state.sendFailure?.reference?.takeIf { state.sendHold == SendHold.Failed || state.sendHold == SendHold.Unknown }?.let { Reference(it, onCopy, Modifier.padding(top = CircasoSpacing.s3)) }
            val note = lastOf(state.cancelNote)
            Reveal(state.cancelNote != null) { if (note != null) Banner(note, CircasoIcon.Info, modifier = Modifier.padding(top = CircasoSpacing.s4), tone = BannerTone.Neutral) }
        }
    }
}

/** The M-Pesa confirmation code, which the person matches with the M-Pesa message: tap to copy. */
@Composable
private fun ReceiptRow(receipt: String, onCopy: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    DetailRow(
        "M-Pesa receipt",
        Modifier.pressable(role = Role.Button, onClickLabel = "Copy the M-Pesa receipt") { onCopy(receipt) }
            .semantics(mergeDescendants = true) { contentDescription = "M-Pesa receipt $receipt" },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
            BasicText(receipt, style = t.subhead.copy(color = c.ink, textAlign = TextAlign.End, fontFeatureSettings = "tnum"), maxLines = 1)
            CircasoIconImage(CircasoIcon.Copy, c.inkSecondary, Modifier.size(CopyGlyph))
        }
    }
}

internal const val CashOutOfflineNote = "Cashing out needs a connection."
