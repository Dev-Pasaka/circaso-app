package com.circaso.feature.wallet.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.platform.localDayIndex
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.coinToFiat
import com.circaso.feature.wallet.domain.fiatToCoinMinor
import kotlin.time.Instant

// Phase 2F's fee and limit lines (spaces-plan §7a, §7b; frames GW13, GW13b–d, GW14, GW14b, GW20), shared by the chat's
// money card and the Wallet tab's Pay screen so both say the same words.

private fun millisOf(iso: String?): Long? = runCatching { Instant.parse(iso ?: return null).toEpochMilliseconds() }.getOrNull()

/**
 * "14:30" in the person's own time zone for an ISO instant later today, "tomorrow 09:00" for one tomorrow (a rolling
 * 24-hour window often frees up the next day), or the weekday; null when it doesn't parse.
 */
internal fun clockOf(iso: String?, now: Long = nowMillis()): String? {
    val at = millisOf(iso) ?: return null
    val clock = formatLocalTime(at, LocalTimeStyle.Clock)
    return when (localDayIndex(at) - localDayIndex(now)) {
        0L -> clock
        1L -> "tomorrow $clock"
        else -> formatLocalTime(at, LocalTimeStyle.Weekday) + " " + clock
    }
}

/** Title case for a button ("Pay Tomorrow After 09:00"). */
private fun buttonTime(iso: String?): String? = clockOf(iso)?.let { t -> if (t.startsWith("tomorrow ")) "Tomorrow After ${t.removePrefix("tomorrow ")}" else "After $t" }

/**
 * The most that fits ([maxMinor]), as the person types it: in their currency rounded down a cent at a time until it
 * stands for no more than [maxMinor] (so an offer never promises a cent more than fits), else in the coin.
 */
internal fun fittedAmount(s: PayUiState, maxMinor: String): String? {
    val rate = s.unitRate
    if (!(s.inFiat && rate != null)) return MinorUnits.toDisplay(maxMinor, s.decimals).replace(",", "")
    var fiat = coinToFiat(maxMinor, s.decimals, rate) ?: return null
    while (fiatToCoinMinor(fiat, rate, s.decimals)?.let { MinorUnits.lessThan(maxMinor, it) } == true) {
        val cents = MinorUnits.fromDisplay(fiat, 2)?.toLongOrNull() ?: return null
        if (cents <= 0) return null
        fiat = MinorUnits.toDisplay((cents - 1).toString(), 2, minFraction = 2).replace(",", "")
    }
    return fiat.removeSuffix(".00")
}

/** [fittedAmount] in words: "KES 18,149", "499.75 USDC". */
private fun fittedText(s: PayUiState, maxMinor: String): String? {
    val amount = fittedAmount(s, maxMinor) ?: return null
    return if (s.inFiat && s.unitRate != null && s.currency != null) {
        "${s.currency} ${fiatDisplay(MinorUnits.fromDisplay(amount, 2) ?: "0")}"
    } else "${MinorUnits.group(amount.substringBefore('.'))}${amount.substringAfter('.', "").let { if (it.isEmpty()) "" else ".$it" }} ${s.asset}"
}

/** The verb the payment's buttons use: "Pay" into a group or for a payout, else "Send". */
private fun verb(s: PayUiState) = if (s.toGroup || s.payout || s.contribution) "Pay" else "Send"

/**
 * The fee row's value (GW13: a spinner and "Working it out" while Pay waits; GW13d: Try Again, never a guessed fee;
 * otherwise the fee and its rate or "minimum", dimmed while a newer answer is on its way). [onRetry] asks W17 again.
 */
@Composable
internal fun FeeValue(s: PayUiState, onRetry: () -> Unit, style: TextStyle, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = CircasoTheme.colors
    val fee = s.fee
    val shown = when {
        // No coin price yet: there is no amount in the coin, so no fee to say (never "No fee" under "KES 500").
        s.priceWaiting -> "none"
        fee is PayFee.Quoting -> "working"
        fee is PayFee.Failed && fee.retryable -> "retry"
        fee is PayFee.Failed -> "none"
        else -> "value"
    }
    Crossfade(shown, modifier, animationSpec = tween(if (CircasoTheme.reduceMotion) 0 else CircasoMotion.QuickMs), label = "fee") { kind ->
        when (kind) {
            "working" -> Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically) {
                Spinner(c.inkSecondary, Modifier.size(14.dp))
                BasicText("Working it out", style = style.copy(color = c.inkSecondary))
            }
            // A 44 dp target: it is the only way on while Pay waits.
            "retry" -> Box(
                Modifier.defaultMinSize(minHeight = CircasoSizes.controlMd).pressable(role = Role.Button, onClick = onRetry),
                contentAlignment = Alignment.CenterEnd,
            ) { BasicText("Try Again", style = CircasoTheme.typography.subhead.copy(color = c.action, fontWeight = FontWeight.SemiBold)) }
            "none" -> BasicText("—", style = style.copy(color = c.inkSecondary))
            else -> BasicText(
                if (compact) s.feeShort else s.feeText,
                style = style.copy(color = if (s.feeStale) c.inkSecondary else c.ink, textAlign = TextAlign.End, fontFeatureSettings = "tnum"),
            )
        }
    }
}

/** The total row's value: "—" until the fee is known for what is typed (GW13, GW13d), then what leaves the wallet. */
internal fun totalValue(s: PayUiState): String? = when {
    s.fee is PayFee.None || s.fee is PayFee.Offline -> null
    s.fee is PayFee.Quoted && !s.feeStale -> s.totalText?.let { if (s.requestPayout && !MinorUnits.isZero(s.feeMinor ?: "0")) "about $it" else it }
    else -> "—"
}

/** The total the buttons may name: only once the fee for what is typed is known (GW13 and GW13d show a bare "Send"). */
internal fun buttonTotal(s: PayUiState): String? = when {
    s.requestPayout -> s.fiatText ?: s.usdcText
    s.fee is PayFee.None || s.fee is PayFee.Offline -> s.totalText
    s.fee is PayFee.Quoted && !s.feeStale -> s.totalText
    else -> null
}

/**
 * GW13c: the fee changed since it was shown (after its 15 minutes, or a rule changed meanwhile, so the frame's "after 15
 * minutes" is left out); said with the numbers before anything can be signed.
 */
internal fun feeChangeText(s: PayUiState): String? {
    val change = s.feeChange ?: return null
    val total = buttonTotal(s) ?: return null
    return "The fee changed: ${s.moneyText(change.fromMinor)} → ${s.moneyText(change.toMinor)}. New total $total."
}

/**
 * The footnote under Pay, as the frames have it: GW13d only "We couldn't work out the fee." and the reference; a
 * blocked payment (GW14, GW14b, GW20) only a failure's reference; GW13b the minimum's reason; otherwise "One Circaso fee,
 * network cost included."; then the rate and until when the fee is held (never once that has passed).
 */
internal fun feeFootnote(s: PayUiState, now: Long = nowMillis()): String {
    val fee = s.fee
    val ref = s.failure?.reference?.let { "Ref $it" }
    if (fee is PayFee.Failed) return listOfNotNull(if (fee.retryable) "We couldn’t work out the fee." else fee.message, fee.reference?.let { "Ref $it" }).joinToString(" ")
    if (s.blocked) return ref.orEmpty()
    val charged = fee is PayFee.Quoted && !MinorUnits.isZero(fee.amountMinor)
    val minimum = charged && (fee as PayFee.Quoted).minimum
    val rate = rateLine(s)
    val held = (fee as? PayFee.Quoted)?.expiresAt
        ?.takeIf { charged && !s.requestPayout && !s.feeStale && (millisOf(it) ?: 0) > now }
        // The frame's words for a dollar coin; a coin's market price is never held, so for one only the fee is.
        ?.let { clockOf(it, now) }?.let { if (MinorUnits.isDollar(s.asset)) "fee and rate held until $it" else "fee held until $it" }
    return listOfNotNull(
        ref,
        "Small payments have a minimum fee that covers the network cost.".takeIf { minimum },
        "One Circaso fee, network cost included.".takeIf { charged && !minimum },
        listOfNotNull(rate, held).joinToString(" · ").ifEmpty { null },
    ).joinToString("\n")
}

/** What a limit or the balance with the fee says, and what it offers (GW14, GW14b, GW20). */
internal data class BlockNote(val title: String, val text: String, val action: String?, val icon: CircasoIcon, val warning: Boolean)

/** The note for [PayUiState.block], in the person's currency; null when nothing stops the payment. */
internal fun blockNote(s: PayUiState): BlockNote? {
    val who = if (s.fromGroupWallet) s.walletName else "You"
    fun offer(max: String?) = max?.takeIf { !MinorUnits.isZero(it) }?.let { fittedText(s, it) }
    return when (val b = s.block) {
        null -> null
        is PayBlock.Funds -> {
            val max = offer(b.maxAmountMinor)
            BlockNote(
                title = (if (s.fromGroupWallet) "${s.walletName} has " else "You have ") + s.moneyText(s.balanceMinor),
                text = if (max != null) "With the fee, the most you can send is $max." else "That isn’t enough for this payment and its fee.",
                action = max?.let { "${verb(s)} $it Instead" }.takeIf { !s.payout },
                icon = CircasoIcon.Info, warning = false,
            )
        }
        is PayBlock.PerPayment -> {
            val max = offer(b.maxAmountMinor)
            BlockNote(
                title = "That’s over the limit for one payment",
                text = max?.let { "$who can send up to $it in one payment." } ?: "Send a smaller amount.",
                action = max?.let { "${verb(s)} $it Instead" }.takeIf { !s.payout },
                icon = CircasoIcon.Clock, warning = true,
            )
        }
        is PayBlock.Daily -> {
            val left = b.leftMinor?.takeIf { !MinorUnits.isZero(it) }
            val max = offer(b.maxAmountMinor)
            val cap = b.dailyMaxMinor?.let { MinorUnits.toDisplay(it, 6, minFraction = 0) + " USDC" }
            val fits = clockOf(b.fitsAt)
            val more = clockOf(b.moreAt)
            val members = if (s.fromGroupWallet) " Members can still pay into the group." else ""
            when {
                // GW14: a request may ask for more than fits today; say what fits now and offer it.
                s.requestPayout && max != null -> BlockNote(
                    title = "${s.walletName} can send $max more today",
                    text = "With the fee, that’s what fits under the group limit${cap?.let { " of $it" } ?: ""} in 24 hours." + (more?.let { " More is available from $it." } ?: ""),
                    action = "Request $max Instead", icon = CircasoIcon.Clock, warning = true,
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
                    action = "${verb(s)} $max Instead", icon = CircasoIcon.Clock, warning = true,
                )
            }
        }
    }
}

/** Pay's label while the daily limit holds it (GW14b: "Pay After 14:30"); null when nothing holds it. */
internal fun blockedLabel(s: PayUiState): String? {
    val b = s.block as? PayBlock.Daily ?: return null
    if (s.requestPayout) return null
    return buttonTime(b.fitsAt)?.let { "${verb(s)} $it" }
}

/**
 * GW13c's banner and GW14 / GW14b / GW20's note, above the rows as the frames put them. They ease in and out (only
 * fade with reduced motion), dim while a newer answer is on its way, and are announced when their title changes.
 */
@Composable
internal fun FeeProblems(s: PayUiState, onUseMax: () -> Unit) {
    val reduce = CircasoTheme.reduceMotion
    val enter = if (reduce) fadeIn(tween(CircasoMotion.QuickMs)) else expandVertically(tween(CircasoMotion.BaseMs)) + fadeIn(tween(CircasoMotion.BaseMs))
    val exit = if (reduce) fadeOut(tween(CircasoMotion.QuickMs)) else shrinkVertically(tween(CircasoMotion.QuickMs)) + fadeOut(tween(CircasoMotion.QuickMs))
    val change = feeChangeText(s)
    AnimatedVisibility(change != null, enter = enter, exit = exit) {
        change?.let { com.circaso.designsystem.chrome.Banner(it, CircasoIcon.Clock, tone = com.circaso.designsystem.chrome.BannerTone.Warning) }
    }
    val note = blockNote(s)
    AnimatedVisibility(note != null, enter = enter, exit = exit) {
        note?.let { BlockNoteView(it, onUseMax, stale = s.feeStale) }
    }
}

/** GW14 / GW14b / GW20's note: the icon and title, the numbers, and the one-tap offer when there is one. */
@Composable
internal fun BlockNoteView(note: BlockNote, onAction: () -> Unit, modifier: Modifier = Modifier, stale: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2), verticalAlignment = Alignment.CenterVertically) {
            CircasoIconImage(note.icon, if (note.warning && !stale) c.warning else c.inkSecondary, Modifier.size(18.dp))
            BasicText(note.title, style = t.callout.copy(color = if (stale) c.inkSecondary else c.ink, fontWeight = FontWeight.SemiBold))
        }
        BasicText(note.text, style = t.subhead.copy(color = c.inkSecondary))
        // Medium and full width: the offer names an amount, which wraps at large text rather than clipping.
        note.action?.let { CircasoButton(it, onAction, style = ButtonStyle.Secondary, size = ButtonSize.Medium, block = true, enabled = !stale) }
    }
}

/**
 * Why Pay waits on a coin price, the same on the chat card and the Pay screen: a fresh one on its way (with the
 * spinner), or none to be had (with the request reference when there is one) and a 44 dp Try Again. Nothing when the
 * price isn't waited on.
 */
@Composable
internal fun PriceWait(s: PayUiState, onRetry: () -> Unit, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val spec = if (CircasoTheme.reduceMotion) 0 else CircasoMotion.QuickMs
    val line = priceLine(s)
    Column(modifier, horizontalAlignment = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start) {
        Crossfade(line, animationSpec = tween(spec), label = "price") { l ->
            if (l != null) Row(
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (l.second == PriceLineKind.Loading) Spinner(c.inkSecondary, Modifier.size(14.dp))
                BasicText(l.first, style = t.footnote.copy(color = if (l.second == PriceLineKind.Loading) c.inkSecondary else c.warning, textAlign = textAlign))
            }
        }
        AnimatedVisibility(
            canRetryPrice(s),
            enter = fadeIn(tween(spec)), exit = fadeOut(tween(if (CircasoTheme.reduceMotion) 0 else CircasoMotion.QuickMs / 2)),
        ) {
            Box(
                Modifier.defaultMinSize(minHeight = CircasoSizes.controlMd)
                    .semantics { contentDescription = "Try again to get the ${s.asset} price" }
                    .pressable(role = Role.Button, onClick = onRetry),
                contentAlignment = Alignment.CenterStart,
            ) { BasicText("Try Again", style = t.subhead.copy(color = c.action, fontWeight = FontWeight.SemiBold)) }
        }
    }
}

internal enum class PriceLineKind { Loading, Problem }

/** The words for [PriceWait]; null when Pay doesn't wait on a price. */
internal fun priceLine(s: PayUiState): Pair<String, PriceLineKind>? {
    if (!s.priceWaiting) return null
    val amount = s.fiatText ?: "this amount"
    return when (val r = s.priceRefresh) {
        PriceRefresh.Loading -> "Getting the latest ${s.asset} price…" to PriceLineKind.Loading
        is PriceRefresh.Failed ->
            ("Couldn’t get the latest ${s.asset} price, so $amount can’t be worked out in ${s.asset} yet." + (r.reference?.let { " Ref $it" } ?: "")) to PriceLineKind.Problem
        PriceRefresh.Idle -> "No ${s.asset} price right now, so $amount can’t be worked out in ${s.asset} yet." to PriceLineKind.Problem
    }
}

/** Try Again is offered whenever Pay waits on a price and no fresh one is on its way (never offline: that line says why). */
internal fun canRetryPrice(s: PayUiState): Boolean = s.priceWaiting && s.online && s.priceRefresh !is PriceRefresh.Loading
