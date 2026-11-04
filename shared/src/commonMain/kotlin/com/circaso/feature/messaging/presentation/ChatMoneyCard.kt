package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
) {
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
        else -> "Send to $first"
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s3).padding(bottom = CircasoSpacing.s2)
            .glass(CircasoShapes.lg, floating = true, edge = false)
            .padding(start = CircasoSpacing.s4, end = CircasoSpacing.s3, top = CircasoSpacing.s3, bottom = CircasoSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        // Who and how much: the amount large in the person's currency, the coin under it (C4's head, in the chat).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            if (s.toGroup) WalletAvatar(group, payee?.avatarUrl, group = true, size = CircasoSizes.avatarMd)
            else Avatar(payee?.displayName ?: "", size = CircasoSizes.avatarMd, imageUrl = payee?.avatarUrl)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {
                heading()
                contentDescription = listOfNotNull(title, amount, s.usdcText.takeIf { s.fiatText != null }).joinToString(", ")
            }) {
                BasicText(title, style = t.subhead.copy(color = c.inkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(amount ?: "Type an amount after #", style = (if (amount != null) t.title2 else t.callout).copy(color = if (amount != null) c.ink else c.inkSecondary, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
                if (s.fiatText != null) BasicText(s.usdcText.orEmpty(), style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"))
            }
            if (onClose != null && !signing) CircasoIconButton(CircasoIcon.Close, "Close", onClose)
        }
        // The review's rows, compact (GW9b / GW9d / GW9): nothing here is typed, it all came from the chat.
        Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s1)) {
            if (s.showPayingFrom) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    BasicText("Paying from", Modifier.weight(1f), style = t.footnote.copy(color = c.inkSecondary))
                    PayingFromChip(s.payingFrom, if (s.canSwitch && !signing) onChoose else null)
                }
            }
            s.payChain?.let { CardRow("Network", "${s.asset} on ${chainName(it)}") }
            s.note.trim().takeIf { it.isNotEmpty() }?.let { CardRow("For", it) }
            s.payoutRequest?.takeIf { s.groupPayout }?.let { r ->
                val names = r.approvedBy.map { s.memberNames[it]?.substringBefore(' ') ?: "an admin" }
                CardRow("Request", if (names.isEmpty()) "Approved" else "Approved by " + names.joinToString(" and "))
            }
            if (s.requestPayout) CardRow("Approvals", if (s.groupMoney?.approvalPolicy == ApprovalPolicy.AnyAdmin) "Any admin" else "2 admins")
            // GW9a2/GW9d: where the group's target will stand once this lands.
            val goal = s.groupMoney?.goal?.takeIf { s.toGroup }
            val after = s.progressAfter
            if (s.toGroup && goal != null && after != null) {
                val got = MinorUnits.add(s.groupMoney!!.contributedMinor, s.amountMinor ?: "0")
                CardRow("Target", "${amountText(got, s.wallet?.fiat)} of ${amountNumber(goal.targetMinor, s.wallet?.fiat)} · ${(after * 100).toInt()}% after this")
            } else if (!s.toGroup && s.contribution && s.chama != null && after != null) {
                CardRow(s.goalName ?: "Goal", "${(after * 100).toInt()}% after this")
            }
            if (!s.requestPayout) {
                CardRow("Fee", s.feeText)
                CardRow(if (s.toGroup) "$group receives" else "$first receives", amount ?: "—")
            }
        }
        // Why it can't go yet, or what's happening: one line, announced when it changes.
        val (line, tone) = statusLine(s, first)
        if (line != null) {
            BasicText(
                line,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = t.footnote.copy(color = when (tone) { Tone.Critical -> c.critical; Tone.Warning -> c.warning; Tone.Quiet -> c.inkSecondary }),
            )
        }
        val total = s.totalText ?: amount
        CircasoButton(
            text = when {
                signing && s.requestPayout -> "Asking the admins…"
                signing && s.fromGroupWallet -> "Waiting for ${s.walletName}’s PIN…"
                signing -> "Waiting for your PIN…"
                s.failure != null -> "Try Again"
                s.requestPayout -> if (amount != null) "Request $amount Payout" else "Request Payout"
                s.toGroup || s.contribution || s.payout -> if (total != null) "Pay $total" else "Pay"
                else -> if (total != null) "Send $total" else "Send"
            },
            onClick = onPay, size = ButtonSize.Large, block = true, enabled = s.canPay, busy = signing,
        )
        s.failure?.reference?.let { BasicText("Ref $it", style = t.caption1.copy(color = c.inkTertiary, fontFeatureSettings = "tnum")) }
    }
}

private enum class Tone { Quiet, Warning, Critical }

/** The card's one status line: offline and hold first (amber, they pass), then what's wrong (red), then guidance. */
private fun statusLine(s: PayUiState, first: String): Pair<String?, Tone> = when {
    s.step == PayStep.Signing && s.fromGroupWallet && !s.requestPayout -> "Approve from ${s.walletName} · ${s.walletName}’s PIN, not your personal one." to Tone.Quiet
    s.step == PayStep.Signing -> null to Tone.Quiet
    // GW19 / G2: refused before it reached the chain. Nothing was taken; a payout's approval stands.
    s.failure != null -> ("Not sent. Nothing was taken from ${s.failure.wallet}." + (if (s.payout) " The approval still stands." else "") +
        (s.failure.message.takeIf { it != com.circaso.core.common.ErrorMessages.SomethingWrong && !it.contains("Nothing was sent") && !it.contains("Nothing was taken") }?.let { " $it" } ?: "")) to Tone.Critical
    !s.online -> "You’re offline. Money sends only while you’re connected." to Tone.Warning
    s.onHold -> "Sending is paused for a short while after your account was recovered. You can still receive money." to Tone.Warning
    s.checking -> "Checking $first’s wallet…" to Tone.Quiet
    s.problem is PayeeProblem.NoWallet -> "$first doesn’t have a Circaso wallet yet." to Tone.Critical
    s.problem is PayeeProblem.NotOnChain -> "$first can’t receive on this network yet." to Tone.Critical
    s.problem is PayeeProblem.Other -> s.problem.message to Tone.Critical
    s.assetMissing -> "You don’t have ${s.assetTag?.uppercase()} in this wallet." to Tone.Critical
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
        Modifier.fillMaxWidth().padding(vertical = 2.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top,
    ) {
        BasicText(label, style = t.footnote.copy(color = c.inkSecondary))
        BasicText(value, Modifier.weight(1f), style = t.footnote.copy(color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFeatureSettings = "tnum"))
    }
}

/** The money line for a card the chain didn't take, drawn under it: "Not sent · Nothing was taken" and Try Again. */
@Composable
internal fun NotSentLine(outgoing: Boolean, onRetry: (() -> Unit)?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(Modifier.padding(top = CircasoSpacing.s1), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoIconImage(CircasoIcon.Warning, c.critical, Modifier.size(14.dp))
        BasicText(if (outgoing) "Not sent. Nothing was taken." else "This payment didn’t go through.", style = t.footnote.copy(color = c.critical))
    }
    if (onRetry != null) com.circaso.designsystem.components.SuggestionChip("Try Again", onRetry, Modifier.padding(top = CircasoSpacing.s1))
}

