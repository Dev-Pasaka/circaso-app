package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.MinorUnits
import kotlin.time.Instant

// Phase 2F's fee and limit lines (spaces-plan §7a, §7b; frames GW13, GW13b–d, GW14, GW14b, GW20), shared by the chat's
// money card and the Wallet tab's Pay screen so both say the same words.

/** "14:30" in the person's own time zone, for an ISO instant; null when it doesn't parse. */
internal fun clockOf(iso: String?): String? =
    runCatching { formatLocalTime(Instant.parse(iso ?: return null).toEpochMilliseconds(), LocalTimeStyle.Clock) }.getOrNull()

/**
 * The fee row's value (GW13: a spinner and "Working it out" while Pay waits; GW13d: Try Again, never a guessed fee;
 * otherwise the fee and its rate or "minimum"). [onRetry] asks W17 again.
 */
@Composable
internal fun FeeValue(s: PayUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    when (s.fee) {
        PayFee.Quoting -> Row(modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spinner(c.inkSecondary, Modifier.size(14.dp))
            BasicText("Working it out", style = t.footnote.copy(color = c.inkSecondary))
        }
        is PayFee.Failed -> BasicText(
            "Try Again",
            modifier.pressable(role = Role.Button, onClick = onRetry),
            style = t.footnote.copy(color = c.action, fontWeight = FontWeight.SemiBold),
        )
        else -> BasicText(s.feeText, modifier, style = t.footnote.copy(color = c.ink, textAlign = TextAlign.End, fontFeatureSettings = "tnum"))
    }
}

/** The total row's value: "—" until the fee is known (GW13, GW13d), then what leaves the wallet. */
internal fun totalValue(s: PayUiState): String? = when (s.fee) {
    is PayFee.Quoted -> s.totalText?.let { if (s.requestPayout && !MinorUnits.isZero(s.feeMinor ?: "0")) "about $it" else it }
    PayFee.None, PayFee.Offline -> null
    else -> "—"
}

/** GW13c: the fee rose after its 15 minutes; said with the numbers before anything can be signed. */
internal fun feeChangeText(s: PayUiState): String? {
    val change = s.feeChange ?: return null
    val total = s.totalText ?: return null
    return "The fee changed: ${s.moneyText(change.fromMinor)} → ${s.moneyText(change.toMinor)}. New total $total."
}

/**
 * The footnote under Pay: the reference of a failure, GW13d's "couldn't work out the fee", "One Circaso fee, network
 * cost included." once there is one, the minimum's reason (GW13b), the rate, and until when the fee is held.
 */
internal fun feeFootnote(s: PayUiState): String {
    val fee = s.fee
    val charged = fee is PayFee.Quoted && !MinorUnits.isZero(fee.amountMinor)
    val rate = s.rate?.takeIf { s.tagged == null && s.currency != null }?.let { "1 USDC = ${s.currency} ${formatRate(it)}" }
    val held = (fee as? PayFee.Quoted)?.expiresAt?.takeIf { charged && !s.requestPayout }?.let(::clockOf)?.let { "fee and rate held until $it" }
    return listOfNotNull(
        s.failure?.reference?.let { "Ref $it" },
        (fee as? PayFee.Failed)?.let { f -> listOfNotNull("We couldn’t work out the fee.", f.reference?.let { "Ref $it" }).joinToString(" ") },
        "One Circaso fee, network cost included.".takeIf { charged },
        "Small payments have a minimum fee that covers the network cost.".takeIf { charged && (fee as PayFee.Quoted).minimum },
        listOfNotNull(rate, held).joinToString(" · ").ifEmpty { null },
    ).joinToString("\n")
}

/** What a limit or the balance with the fee says, and what it offers (GW14, GW14b, GW20). */
internal data class BlockNote(val title: String, val text: String, val action: String?, val icon: CircasoIcon, val warning: Boolean)

/** The note for [PayUiState.block], in the person's currency; null when nothing stops the payment. */
internal fun blockNote(s: PayUiState): BlockNote? {
    val who = if (s.fromGroupWallet) s.walletName else "You"
    return when (val b = s.block) {
        null -> null
        is PayBlock.Funds -> {
            val max = b.maxAmountMinor?.takeIf { !MinorUnits.isZero(it) }
            BlockNote(
                title = (if (s.fromGroupWallet) "${s.walletName} has " else "You have ") + s.moneyText(s.balanceMinor),
                text = if (max != null) "With the fee, the most you can send is ${s.moneyText(max)}." else "That isn’t enough for this payment and its fee.",
                action = max?.let { "${if (s.toGroup || s.payout) "Pay" else "Send"} ${s.moneyText(it)} Instead" }.takeIf { !s.payout },
                icon = CircasoIcon.Info, warning = false,
            )
        }
        is PayBlock.PerPayment -> {
            val max = b.maxAmountMinor?.takeIf { !MinorUnits.isZero(it) }
            BlockNote(
                title = "That’s over the limit for one payment",
                text = max?.let { "$who can send up to ${s.moneyText(it)} in one payment." } ?: "Send a smaller amount.",
                action = max?.let { "Send ${s.moneyText(it)} Instead" }.takeIf { !s.payout },
                icon = CircasoIcon.Clock, warning = true,
            )
        }
        is PayBlock.Daily -> {
            val left = b.leftMinor?.takeIf { !MinorUnits.isZero(it) }
            val max = b.maxAmountMinor?.takeIf { !MinorUnits.isZero(it) }
            val cap = b.dailyMaxMinor?.let { MinorUnits.toDisplay(it, 6, minFraction = 0) + " USDC" }
            val fits = clockOf(b.fitsAt)
            val more = clockOf(b.moreAt)
            val members = if (s.fromGroupWallet) " Members can still pay into the group." else ""
            when {
                // GW14: a request may ask for more than fits today; say what fits now and offer it.
                s.requestPayout && max != null -> BlockNote(
                    title = "${s.walletName} can send ${s.moneyText(max)} more today",
                    text = "With the fee, that’s what fits under the group limit${cap?.let { " of $it" } ?: ""} in 24 hours." + (more?.let { " More is available from $it." } ?: ""),
                    action = "Request ${s.moneyText(max)} Instead", icon = CircasoIcon.Clock, warning = true,
                )
                s.requestPayout -> BlockNote(
                    title = "${s.walletName} has sent its limit for today",
                    text = "You can still ask; once approved it waits until it fits the limit${more?.let { " (more from $it)" } ?: ""}.",
                    action = null, icon = CircasoIcon.Clock, warning = true,
                )
                // GW14b: paying when the limit is used: nothing to offer, just when.
                s.payout || left == null || max == null -> BlockNote(
                    title = fits?.let { "This ${if (s.payout) "payout" else "payment"} fits the daily limit from $it" } ?: "This is over the daily limit",
                    text = "$who can send up to ${cap ?: "the limit"} in 24 hours, fee included.$members",
                    action = null, icon = CircasoIcon.Clock, warning = true,
                )
                else -> BlockNote(
                    title = "$who can send ${s.moneyText(left)} more today",
                    text = "That includes the fee, within ${cap ?: "the limit"} in 24 hours." + (fits?.let { " This amount fits from $it." } ?: "") + members,
                    action = "Send ${s.moneyText(max)} Instead", icon = CircasoIcon.Clock, warning = true,
                )
            }
        }
    }
}

/** Pay's label while the daily limit holds it (GW14b: "Pay After 14:30"); null when nothing holds it. */
internal fun blockedLabel(s: PayUiState): String? {
    val b = s.block as? PayBlock.Daily ?: return null
    if (s.requestPayout) return null
    val at = clockOf(b.fitsAt) ?: return null
    return "${if (s.toGroup || s.payout) "Pay" else "Send"} After $at"
}

/** GW14 / GW14b / GW20's note: the icon and title, the numbers, and the one-tap offer when there is one. */
@Composable
internal fun BlockNoteView(note: BlockNote, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
            CircasoIconImage(note.icon, if (note.warning) c.warning else c.inkSecondary, Modifier.size(18.dp))
            BasicText(note.title, style = t.callout.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
        }
        BasicText(note.text, style = t.subhead.copy(color = c.inkSecondary))
        note.action?.let { CircasoButton(it, onAction, style = ButtonStyle.Secondary, size = ButtonSize.Small) }
    }
}
