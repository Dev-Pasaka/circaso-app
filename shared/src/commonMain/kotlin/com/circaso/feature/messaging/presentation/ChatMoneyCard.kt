package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.circaso.designsystem.components.GoalProgress
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.designsystem.chrome.glass
import com.circaso.feature.chama.domain.ApprovalPolicy
import com.circaso.feature.chama.presentation.amountNumber
import com.circaso.feature.chama.presentation.amountText
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.presentation.PayStep
import com.circaso.feature.wallet.presentation.PayUiState
import com.circaso.feature.wallet.presentation.PayeeProblem
import com.circaso.feature.wallet.presentation.PayingFromChip
import com.circaso.feature.wallet.presentation.WalletAvatar

/**
 * The chat's money card (Pasaka, 2026-11-04): money starts and ends in the chat. `#500 @usdc for lunch` in the composer
 * becomes this card over the thread, and it is the whole review: who (or which group, with where its target will stand),
 * the amount in the person's currency and in the coin, the network, Paying from (GW9b, a chip that opens GW10), the
 * note (the rest of the line), the one fee (§7b) and what they receive. Pay goes straight to the Circle PIN, which names
 * the wallet. There is no other screen in between. Offline, Pay is dimmed before the tap (G3/GW15): money never queues.
 *
 * After the PIN the card in the thread takes over (Sending, then Sent once confirmed); a refusal before that stays here
 * (GW19: Not sent, nothing was taken, Try Again and the reference).
 */
@Composable
internal fun ChatMoneyCard(
    s: PayUiState,
    onPay: () -> Unit,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
    /** A payout card opened from a request (D5) can be put away; a `#` card goes when the chip does. */
    onClose: (() -> Unit)? = null,
    /**
     * The room between the header and the composer. The card never grows past it: with large text on a small phone and
     * the keyboard up, the rows scroll while who, how much and Pay stay in view.
     */
    maxHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    /** GW13d's Try Again on the fee. */
    onRetryQuote: () -> Unit = {},
    /** GW14 / GW20's "… Instead": the most that fits, with its fee. */
    onUseMax: () -> Unit = {},
    /** Spaces SH2e: why this payment can't be made yet ("Only people who follow … can pay it"); no quote, no fee row. */
    blocked: String? = null,
    /** SH2e: what the button does instead of Pay ("Follow to Pay", "Join to Pay"). */
    blockedAction: String? = null,
    onBlocked: () -> Unit = {},
) {
    if (blocked != null) { SpacePayBlockedCard(s, blocked, blockedAction, onBlocked, modifier); return }
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val payee = s.payee
    val first = payee?.displayName?.substringBefore(' ') ?: "them"
    val group = payee?.displayName ?: "the group"
    val amount = s.fiatText ?: s.usdcText
    val signing = s.step == PayStep.Signing
    val title = when {
        s.requestPayout -> "Request Payout from ${s.walletName}"
        s.groupPayout || s.payout -> "Pay Payout to $first"
        s.toGroup || s.contribution -> "Add to ${if (s.toGroup) group else s.goalName ?: group}"
        s.spaceId != null -> "Pay $group"
        else -> "Send to $first"
    }
    // Slim (Pasaka, 2026-11-05): only what decides the payment. The amount in the person's currency and in the coin, the
    // one fee on the same line, Paying from, and Send. Who it goes to is the chat itself; the network is what `@` picked;
    // the note is the text they typed. Problems show only when there is one.
    val shape = CircasoShapes.md
    Column(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s3).padding(bottom = CircasoSpacing.s2)
            .then(if (maxHeight != androidx.compose.ui.unit.Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier)
            // Solid, not glass: on Android the glass has no blur, so the chat behind read through it.
            .shadow(8.dp, shape, ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.2f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.2f))
            .clip(shape).background(c.surfaceRaised)
            .then(if (c.isHighContrast) Modifier.border(1.dp, c.separator, shape) else Modifier)
            .padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {
                heading()
                contentDescription = listOfNotNull(title, amount, s.usdcText.takeIf { s.fiatText != null }, "the amount the admins approved".takeIf { s.payout }).joinToString(", ")
            }) {
                BasicText(
                    amount ?: "Type an amount after #",
                    style = (if (amount != null) t.title3 else t.callout).copy(color = if (amount != null) c.ink else c.inkSecondary, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (amount != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val coin = listOfNotNull(s.usdcText.takeIf { s.fiatText != null }, "approved".takeIf { s.payout }).joinToString(" · ")
                        if (coin.isNotEmpty()) BasicText("$coin · ", style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"), maxLines = 1)
                        BasicText("Fee ", style = t.footnote.copy(color = c.inkSecondary))
                        com.circaso.feature.wallet.presentation.FeeValue(s, onRetryQuote, t.footnote)
                    }
                }
            }
            if (s.showPayingFrom) {
                Box(Modifier.defaultMinSize(minHeight = CircasoSizes.controlMd), contentAlignment = Alignment.Center) {
                    PayingFromChip(s.payingFrom, if (s.canSwitch && !signing) onChoose else null)
                }
            }
            if (onClose != null && !signing) CircasoIconButton(CircasoIcon.Close, "Close", onClose)
        }
        // Only when something needs saying; scrolls if it ever outgrows the room above the composer.
        val (line, tone) = statusLine(s, first)
        val anything = s.failure != null || s.feeChange != null || s.block != null || line != null
        if (anything) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                // GW19 / G2: refused before it reached the chain. Nothing was taken; a payout's approval stands.
                s.failure?.let { f ->
                    com.circaso.designsystem.chrome.Banner(
                        "Not sent. Nothing was taken from ${f.wallet}." + (if (s.payout) " The approval still stands." else "") +
                            (f.message.takeIf { it != com.circaso.core.common.ErrorMessages.SomethingWrong && !it.contains("Nothing was sent") && !it.contains("Nothing was taken") }?.let { " $it" } ?: ""),
                        CircasoIcon.Warning, tone = com.circaso.designsystem.chrome.BannerTone.Critical,
                    )
                }
                // GW13c / GW14 / GW14b / GW20: why Pay waits (a changed fee, a limit, not enough money).
                com.circaso.feature.wallet.presentation.FeeProblems(s, onUseMax)
                if (line != null) {
                    BasicText(
                        line,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = t.footnote.copy(color = when (tone) { Tone.Critical -> c.critical; Tone.Warning -> c.warning; Tone.Quiet -> c.inkSecondary }),
                    )
                }
            }
        }
        CircasoButton(
            text = payLabel(s, amount, com.circaso.feature.wallet.presentation.buttonTotal(s)),
            onClick = onPay, size = ButtonSize.Medium, block = true, enabled = s.canPay, busy = signing,
        )
    }
}

/** The card's button, and the composer arrow's spoken label while a payment is in the field. */
internal fun payLabel(s: PayUiState, amount: String? = s.fiatText ?: s.usdcText, total: String? = com.circaso.feature.wallet.presentation.buttonTotal(s)): String = com.circaso.feature.wallet.presentation.blockedLabel(s)?.takeIf { s.step == PayStep.Amount && s.failure == null } ?: when {
    s.step == PayStep.Signing && s.requestPayout -> "Asking the admins…"
    s.step == PayStep.Signing && s.fromGroupWallet -> "Waiting for ${s.walletName}’s PIN…"
    s.step == PayStep.Signing -> "Waiting for your PIN…"
    s.failure != null -> "Try Again"
    s.requestPayout -> if (amount != null) "Request $amount Payout" else "Request Payout"
    s.toGroup || s.contribution || s.payout || s.spaceId != null -> if (total != null) "Pay $total" else "Pay"
    else -> if (total != null) "Send $total" else "Send"
}

private enum class Tone { Quiet, Warning, Critical }

/** The card's status line: offline and hold first (amber, they pass and explain a dimmed Pay), then what's wrong (red). */
private fun statusLine(s: PayUiState, first: String): Pair<String?, Tone> = when {
    s.step == PayStep.Signing && s.fromGroupWallet && !s.requestPayout -> "Approve from ${s.walletName} · ${s.walletName}’s PIN, not your personal one." to Tone.Quiet
    s.step == PayStep.Signing -> null to Tone.Quiet
    !s.online -> "You’re offline. Money sends only while you’re connected." to Tone.Warning
    s.onHold -> "Sending is paused for a short while after your account was recovered. You can still receive money." to Tone.Warning
    // The banner above says what happened; nothing more to add here.
    s.failure != null -> null to Tone.Quiet
    s.checking -> "Checking $first’s wallet…" to Tone.Quiet
    s.problem is PayeeProblem.NoWallet -> "$first doesn’t have a Circaso wallet yet." to Tone.Critical
    s.problem is PayeeProblem.NotOnChain -> "$first can’t receive on this network yet." to Tone.Critical
    s.problem is PayeeProblem.Other -> s.problem.message to Tone.Critical
    s.assetMissing -> "You don’t have ${s.assetTag?.uppercase()} in this wallet." to Tone.Critical
    s.block != null && !s.requestPayout -> null to Tone.Quiet
    s.overBalance -> ((if (s.fromGroupWallet) "${s.walletName} has" else "You have") + " ${MinorUnits.toDisplay(s.balanceMinor, s.tagged?.second?.decimals ?: 6)} ${s.asset}.") to Tone.Critical
    s.requestPayout -> (if (s.payingFrom.canSign) "Admins approve in the group chat. Then you pay it with ${s.walletName}’s PIN." else "Admins approve in the group chat. Then ${s.signerName} pays it from ${s.walletName}’s wallet.") to Tone.Quiet
    else -> null to Tone.Quiet
}

/** One row of the card: the label quiet on the left, the value on the right (`.cx-details`, compact). */
@Composable
private fun CardRow(label: String, value: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top,
    ) {
        BasicText(label, style = t.footnote.copy(color = c.inkSecondary))
        BasicText(value, Modifier.weight(1f), style = t.footnote.copy(color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFeatureSettings = "tnum"))
    }
}

/** A row whose value is drawn (the fee: a spinner while it is worked out, Try Again when it couldn't be). */
@Composable
private fun CardRowContent(label: String, value: @Composable () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top,
    ) {
        // As CardRow: the label keeps its width, the value takes the rest and wraps at large text.
        BasicText(label, style = t.footnote.copy(color = c.inkSecondary))
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) { value() }
    }
}

/** Under a card the chain didn't take (its head says Not sent): nothing was taken, and Try Again. */
@Composable
internal fun NotSentLine(outgoing: Boolean, onRetry: (() -> Unit)?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(Modifier.padding(top = CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoIconImage(CircasoIcon.Warning, c.critical, Modifier.size(14.dp))
        BasicText(if (outgoing) "Nothing was taken." else "Nothing reached you.", style = t.footnote.copy(color = c.critical))
    }
    if (onRetry != null) com.circaso.designsystem.components.SuggestionChip("Try Again", onRetry, Modifier.padding(top = CircasoSpacing.s1))
}

