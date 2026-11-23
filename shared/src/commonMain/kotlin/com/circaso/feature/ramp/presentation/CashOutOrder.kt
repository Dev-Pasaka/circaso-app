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
import com.circaso.designsystem.components.MpesaDetailRow
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
    /** The wallet can't cover the USDC and its fee: cancel and sell what fits. */
    Short,
    /** The wallet is on the other network: switch there first. */
    Switch,
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
            s.sent -> SaleLook(null, SheetTone.Neutral, "Your USDC is on its way", "$usdc is on its way to Circaso's partner. This takes a few seconds.", SaleActions.Following, settled = false)
            s.sending -> SaleLook(null, SheetTone.Neutral, "Approve the transfer", "Confirm sending $usdc to finish your sale.", SaleActions.Send, settled = false)
            s.depositOver && s.maybeSent -> SaleLook(CircasoIcon.Clock, SheetTone.Warning, "Time's up for this sale", MaybeSentWords, SaleActions.Following, settled = true, needsReference = true)
            s.depositOver -> SaleLook(CircasoIcon.Clock, SheetTone.Warning, "Time's up for this sale", "Your USDC wasn't sent in time, so nothing left your wallet. This updates in a moment.", SaleActions.Following, settled = true)
            s.sendHold == SendHold.Short -> {
                val fee = (s.sendFee as? SendFee.Quoted)?.amountMinor
                val need = fee?.let { MinorUnits.add(order.deposit?.amountMinor ?: order.pay.amountMinor, it) } ?: (order.deposit?.amountMinor ?: order.pay.amountMinor)
                val has = s.balanceMinor?.let { " and holds ${CashOutUiState.usdcText(it)}" } ?: ""
                SaleLook(
                    CircasoIcon.Warning, SheetTone.Warning, "Not enough USDC for this sale",
                    "Your wallet needs ${CashOutUiState.usdcText(need)}${if (fee != null) " for this sale and its network fee" else " for this sale"}${has.replace(" and holds", "; it holds")}. ${if (s.maybeSent) "" else "Nothing left your wallet."}".trimEnd() +
                        (s.sellable?.let { " You can sell ${CashOutUiState.usdcText(it)} instead." } ?: " Cancel this sale."),
                    SaleActions.Short, settled = true,
                )
            }
            s.sendHold == SendHold.NoGas -> SaleLook(
                CircasoIcon.Warning, SheetTone.Warning, "Add a little SOL to finish",
                "Solana's network fee is paid in SOL, and this wallet doesn't have enough. Nothing left your wallet. Add some SOL, then send, or cancel this sale.",
                SaleActions.Switch, settled = true,
            )
            s.sendHold == SendHold.OtherNetwork -> SaleLook(
                CircasoIcon.Network, SheetTone.Warning, "Switch to ${s.network.label} to finish",
                "This sale is on ${s.network.label}, and your wallet is showing the other network. Switch on the Wallet tab, then come back and send. Nothing left your wallet.",
                SaleActions.Switch, settled = true,
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
        RampOrderState.Refunding -> SaleLook(null, SheetTone.Neutral, if (order.failureReason == "never_funded") "This sale ended" else "Your USDC is on its way back", refundWords(order.failureReason, usdc, done = false), SaleActions.Following, settled = false)
        RampOrderState.Refunded -> SaleLook(CircasoIcon.ArrowDown, SheetTone.Neutral, if (order.failureReason == "never_funded") "This sale ended" else "Your USDC is back in your wallet", refundWords(order.failureReason, usdc, done = true, chain = chain), SaleActions.Again, settled = true)
        // Nothing arrived by the deadline. If this phone sent it late, it comes back by itself (REFUNDING follows).
        // Sent from here (or perhaps sent) and arrived late: it comes back by itself; nothing to retry, Support beside it.
        RampOrderState.Expired -> if (s.sent) SaleLook(
            CircasoIcon.Clock, SheetTone.Warning, "This sale timed out",
            "Your $usdc left your wallet but didn't reach Circaso's partner in time. It's sent back to you by itself, and this page updates when it is.",
            SaleActions.Following, settled = true, needsReference = true,
        ) else if (s.maybeSent) SaleLook(CircasoIcon.Clock, SheetTone.Warning, "This sale timed out", MaybeSentWords, SaleActions.Following, settled = true, needsReference = true)
        else SaleLook(CircasoIcon.Clock, SheetTone.Neutral, "This sale timed out", "Nothing left your wallet. You can start a new sale whenever you like.", SaleActions.Again, settled = true)
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

/** When it isn't known whether W4 went: never "nothing left your wallet". */
internal const val MaybeSentWords = "If your USDC went, it comes back to your wallet by itself, and this page updates when it does."

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
internal fun saleFigureLabels(state: RampOrderState, look: SaleLook): Pair<String, String> = when (state) {
    RampOrderState.Completed -> "You sold" to "You received"
    // Still being decided: what it is for, never that it failed.
    RampOrderState.Review -> "You sell" to "To receive"
    RampOrderState.Refunding, RampOrderState.Refunded, RampOrderState.Expired, RampOrderState.Cancelled, RampOrderState.Failed -> "Amount" to "Would have received"
    else -> "You sell" to "You receive"
}

/** "KES 26, taken out": a fee comes out of what the USDC is worth; "None" at zero. */
internal fun saleFee(amount: RampAmount?): String = when {
    amount == null -> "None"
    MinorUnits.isZero(amount.amountMinor) -> "None"
    else -> amount.text + ", taken out"
}

/**
 * Where a sale's card is (Pasaka, 2026-11-23: "the same animation from card to M-Pesa in reverse when cashing out"):
 * the silver USDC card while the USDC is being sent and received by Circaso's partner; turning into M-Pesa's green card
 * as the payout is sent; settled on it when it lands. USDC coming back keeps (or goes back to) the silver card; an
 * ending before anything was sent dims it; a review holds.
 */
internal fun saleCardStageOf(state: RampOrderState, sent: Boolean, maybeSent: Boolean = false): CardStage = when (state) {
    RampOrderState.AwaitingTransfer, RampOrderState.Funded -> CardStage.Waiting
    RampOrderState.PayingOut -> CardStage.Converting
    RampOrderState.Completed -> CardStage.Landed
    RampOrderState.Refunding, RampOrderState.Refunded -> CardStage.Returned
    // Sent and then ended: the USDC comes back by itself. Perhaps sent (W4 got no answer): nobody knows yet, so the card
    // holds rather than promise it is coming home. Nothing sent: nothing moved, and the card dims.
    RampOrderState.Cancelled, RampOrderState.Expired, RampOrderState.Failed -> when {
        sent -> CardStage.Returned
        maybeSent -> CardStage.Held
        else -> CardStage.Ended
    }
    // In review, or a state this version doesn't know: whatever shows holds, and nothing celebrates.
    else -> CardStage.Held
}

/** What a sale's two cards say: the USDC sold on the silver, the shillings and the number on the green. */
internal fun saleCardWords(order: RampOrder, look: SaleLook): CardWords {
    val (selling, getting) = saleFigureLabels(order.state, look)
    val phone = order.maskedPhone.takeIf { it.isNotBlank() }?.maskedForDisplay()
    val chain = chainName(order.blockchain)
    val receiveLabel = if (order.state == RampOrderState.Completed) "Received on M-Pesa" else getting
    return CardWords(
        mpesaLabel = receiveLabel, mpesa = order.receive.text, phone = phone,
        usdcLabel = selling, usdc = order.pay.text, chain = chain,
        mpesaDescription = listOfNotNull("M-Pesa", "$receiveLabel ${order.receive.text}", phone?.let { "to $it" }).joinToString(", "),
        usdcDescription = listOf("USDC on $chain", "$selling ${order.pay.text}").joinToString(", "),
    )
}

/** The badge under a sale's card: something to see to (a warning), or an ending other than the payout. */
internal fun saleBadge(order: RampOrder, look: SaleLook): Pair<CircasoIcon, SheetTone>? {
    val icon = look.icon ?: return null
    if (!look.settled || order.state == RampOrderState.Completed) return null
    // While the sale simply waits for the USDC, the card and the button say it all.
    if (order.state == RampOrderState.AwaitingTransfer && look.tone != SheetTone.Warning) return null
    return icon to look.tone
}

/**
 * A sale's hero: [CardConversion] the other way round from Add Money's, the silver USDC card coming apart into
 * M-Pesa's green card as the shillings are paid out, settling with the haptic and the chime ([onLanded]) once.
 */
@Composable
internal fun SaleCard(order: RampOrder, state: CashOutUiState, look: SaleLook, onLanded: () -> Unit, modifier: Modifier = Modifier, frozenAt: Float? = null) =
    CardConversion(
        ConversionDirection.UsdcToMpesa, saleCardStageOf(order.state, state.sent, state.maybeSent), saleCardWords(order, look), heldOnSecond = false,
        celebrate = state.celebrate == order.orderId, quiet = state.quietLanding == order.orderId, onLanded = onLanded, landingKey = order.orderId,
        modifier = modifier, frozenAt = frozenAt,
    )

/**
 * A sale from the moment it is made until the shillings are on the person's phone, or until it ends another way: the
 * money itself as the hero ([SaleCard]), one sentence on where it is, the story so far, then every figure (every fee
 * named). Every change eases in, and a payout that lands is felt and heard as well as seen (the haptic and the chime,
 * on the frame the M-Pesa card settles).
 */
@Composable
internal fun SaleOrderPage(
    state: CashOutUiState, order: RampOrder, bottomInset: Dp,
    onBack: () -> Unit, onSupport: () -> Unit, onSend: () -> Unit, onCancel: () -> Unit, onAgain: () -> Unit, onCopy: (String) -> Unit,
    onSellLess: () -> Unit = {}, onLanded: () -> Unit = {}, frozenAt: Float? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val look = saleLook(order, state)
    val still = CircasoTheme.reduceMotion
    val done = order.state == RampOrderState.Completed
    // Watched as it is paid out, the four steps stay a moment, all ticked, while the card turns and settles (the card
    // itself gives the haptic, on the frame it settles); a sale opened after it had ended shows none.
    var seen by remember { mutableStateOf(order.state) }
    LaunchedEffect(order.state) {
        // Converted while M-Pesa confirmed: only the settle's light is left to wait for.
        if (done && seen != RampOrderState.Completed && !still) delay(if (seen == RampOrderState.PayingOut) SheenMs.toLong() else (ConvertMs + SheenMs).toLong())
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
                            // While the USDC may have gone, cancelling isn't offered at all (not shown dimmed with no reason).
                            if (!state.maybeSent) CircasoButton(
                                "Cancel Sale", onCancel, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true,
                                enabled = state.canCancel && state.online && !state.cancelling, busy = state.cancelling,
                            )
                        }
                        SaleActions.Short -> {
                            val fits = state.sellable
                            CircasoButton(
                                if (fits != null) "Sell ${CashOutUiState.usdcText(fits)} Instead" else "Cancel Sale", if (fits != null) onSellLess else onCancel,
                                size = ButtonSize.Large, block = true, enabled = state.canCancel && state.online && !state.cancelling, busy = state.cancelling && (fits == null || state.sellingLess),
                            )
                            Reveal(!state.online) { FooterNote(CashOutOfflineNote, Modifier.padding(top = CircasoSpacing.s2)) }
                            if (fits != null) Reveal(state.online) { FooterNote("This sale is cancelled first. Nothing left your wallet.", Modifier.padding(top = CircasoSpacing.s2)) }
                            CircasoButton(if (fits != null) "Cancel Sale" else "Close", if (fits != null) onCancel else onBack, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true, enabled = fits == null || (state.canCancel && state.online && !state.cancelling), busy = fits != null && state.cancelling && !state.sellingLess)
                        }
                        SaleActions.Switch -> {
                            CircasoButton("Close", onBack, size = ButtonSize.Large, block = true)
                            FooterNote(
                                if (state.sendHold == SendHold.NoGas) "Send SOL to this wallet's address from Receive, then open this sale again." else "Switch networks on the Wallet tab, then open this sale again.",
                                Modifier.padding(top = CircasoSpacing.s2),
                            )
                            CircasoButton("Cancel Sale", onCancel, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true, enabled = state.canCancel && state.online && !state.cancelling, busy = state.cancelling)
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
                                    // Where the USDC is coming back (or may be), that is what is promised, never shillings.
                                    order.state == RampOrderState.Refunding || (order.state == RampOrderState.Expired && (state.sent || state.maybeSent)) ||
                                        (state.depositOver && state.maybeSent) -> "You can leave this screen. We'll tell you when your USDC is back."
                                    state.depositOver -> "This updates in a moment."
                                    else -> "You can leave this screen. We'll tell you when the money is on your phone."
                                },
                                transitionSpec = { easeOver(still) }, label = "sale note", modifier = Modifier.padding(top = CircasoSpacing.s2),
                            ) { FooterNote(it) }
                            // Where the money's whereabouts are in question, Support is there, never in the way.
                            if (look.needsReference) CircasoButton("Contact Support", onSupport, Modifier.padding(top = CircasoSpacing.s2), style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
                        }
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally) {
            // The money, as the hero: the silver USDC card while the USDC travels, turning into M-Pesa's green card as
            // the shillings are paid out. It keeps its place while everything under it changes.
            SaleCard(order, state, look, onLanded, frozenAt = frozenAt)
            // Something to see to, or an ending other than the payout, says what it is at a glance, as the sheets do.
            val badge = lastOf(saleBadge(order, look))
            Reveal(saleBadge(order, look) != null) { badge?.let { (icon, tone) -> SheetBadge(icon, tone, Modifier.padding(top = CircasoSpacing.s5)) } }
            AnimatedContent(
                look.title to look.message, transitionSpec = { easeOver(still) }, label = "sale state",
                modifier = Modifier.fillMaxWidth().padding(top = CircasoSpacing.s5).semantics { liveRegion = LiveRegionMode.Polite },
            ) { (title, message) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    BasicText(title, Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
                    BasicText(message, style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                }
            }
        }
        Column {
            // A sale held up (not enough, the other network, no SOL, a failed or unconfirmed send) shows no step spinning.
            val held = state.sendHold != null && state.sendHold != SendHold.Cancelled
            val stages = if (held) emptyList() else saleStages(order, state.sent, state.sending)
            val shown = lastOf(stages.takeIf { it.isNotEmpty() })
            // Under the card the steps are a quiet caption, as on Add Money: the card tells the story, the words keep it exact.
            Reveal(stages.isNotEmpty() && (!done || landing)) { if (shown != null) Stages(shown, Modifier.padding(bottom = CircasoSpacing.s5), compact = true) }
            val (selling, getting) = saleFigureLabels(order.state, look)
            ListGroup {
                if (look.needsReference) { ReferenceRow(order.orderId, onCopy); RowDivider() }
                FigureRow(selling, order.pay.text)
                // The wallet's own fee for sending the USDC, on top: said before it is approved and kept once paid.
                (state.sendFee as? SendFee.Quoted)?.takeIf { state.awaitingUsdc || state.sent }?.let {
                    RowDivider(); FigureRow("Network fee", CashOutUiState.usdcText(it.amountMinor) + if (state.sent) ", paid" else ", on top")
                }
                RowDivider()
                FigureRow("Rate", rateLine(order.rate))
                RowDivider()
                FigureRow("Circaso fee", saleFee(order.fee))
                RowDivider()
                FigureRow("M-Pesa payout fee", saleFee(order.providerFee))
                RowDivider()
                FigureRow(getting, order.receive.text, strong = true)
                RowDivider()
                MpesaDetailRow("To", order.maskedPhone)
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
