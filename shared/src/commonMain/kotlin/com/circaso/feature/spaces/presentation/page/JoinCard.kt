package com.circaso.feature.spaces.presentation.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.MembershipPaymentKind
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.presentation.SpaceTile
import com.circaso.feature.spaces.presentation.clockTime
import com.circaso.feature.spaces.presentation.fiatText
import com.circaso.feature.spaces.presentation.shortDate
import com.circaso.feature.spaces.presentation.usdcText
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.presentation.PayFee
import com.circaso.feature.wallet.presentation.PayStep
import com.circaso.feature.wallet.presentation.PayUiState

/**
 * SM1–SM1g, SM3b: joining a members-only space is the chat's money card on the space page (D-M3). Everything is on the
 * card and nothing is typed: the price in the payer's currency first (USDC under it, since the owner set it in USDC),
 * Paying from fixed to Personal (a membership is the person's own), what it buys and until when, the one fee (the
 * `PayFee` seam, "No fee" until 2F) and what the space receives. Pay goes straight to the Circle PIN.
 *
 * [pay] is the `PayViewModel` state for the quote (null while there is no quote: quoting, offline, a failed quote).
 */
@Composable
fun JoinCard(
    space: Space,
    step: JoinStep,
    pay: PayUiState?,
    fiat: FiatValue?,
    now: Long,
    onPay: () -> Unit,
    onTryAgain: () -> Unit,
    onClose: () -> Unit,
    onAddMoney: () -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
    /** GW13d's Try Again on the fee (2F's W17 quote). */
    onRetryQuote: () -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val quote = (step as? JoinStep.Quoted)?.payment ?: (step as? JoinStep.Joining)?.payment
    // The 15-minute hold counts down while the card is open; an expired quote is never signed (it asks for a new one).
    val clock by androidx.compose.runtime.produceState(now, quote?.expiresAt, step is JoinStep.Joining) {
        value = com.circaso.core.sync.nowMillis()
        while (quote != null && step !is JoinStep.Joining) { kotlinx.coroutines.delay(1_000); value = com.circaso.core.sync.nowMillis() }
    }
    val expired = step is JoinStep.Quoted && quote != null && quote.expiresAt > 0 && clock >= quote.expiresAt && pay?.step != PayStep.Signing
    val price = space.summary.price
    val amountMinor = quote?.amountMinor ?: price?.amountMinor ?: "0"
    val period = quote?.period ?: price?.period ?: PricePeriod.OneTime
    val rateSource = pay?.wallet?.fiat ?: fiat
    val lead = fiatText(amountMinor, rateSource) ?: usdcText(amountMinor)
    val kind = quote?.kind ?: if (space.me.membership != null) MembershipPaymentKind.Renewal else MembershipPaymentKind.Join
    val title = when (kind) { MembershipPaymentKind.Join -> "Join ${space.name}"; MembershipPaymentKind.Renewal -> "Renew ${space.name}"; MembershipPaymentKind.Rejoin -> "Rejoin ${space.name}" }
    val per = if (period == PricePeriod.OneTime) " · one time" else " ${period.per}"
    val previous = quote?.previousAmountMinor
    val under = usdcText(amountMinor) + per + if (previous != null) " · was ${usdcText(previous)}" else " · priced in USDC"
    val joining = step is JoinStep.Joining
    /** No confirmation after a minute: the card says so and may close; the welcome still lands when it confirms. */
    val slow = (step as? JoinStep.Joining)?.slow == true
    val signing = pay?.step == PayStep.Signing
    val busy = (joining && !slow) || signing || step is JoinStep.Quoting
    val totalMinor = pay?.totalMinor ?: amountMinor
    val total = fiatText(totalMinor, rateSource) ?: usdcText(totalMinor)
    val failure = (step as? JoinStep.QuoteFailed)?.let { it.message to it.reference }
        ?: (step as? JoinStep.PaymentFailed)?.let { it.message to it.reference }
        ?: pay?.failure?.let { it.message to it.reference }
    val offline = step is JoinStep.Offline || (pay != null && !pay.online)
    val short = pay?.overBalance == true && !offline && failure == null
    Column(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s3).padding(bottom = CircasoSpacing.s2)
            .then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier)
            .shadow(12.dp, CircasoShapes.lg, ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f))
            .clip(CircasoShapes.lg).background(c.surfaceRaised)
            .then(if (c.isHighContrast) Modifier.border(1.dp, c.separator, CircasoShapes.lg) else Modifier)
            .padding(start = CircasoSpacing.s4, end = CircasoSpacing.s3, top = CircasoSpacing.s3, bottom = CircasoSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            SpaceTile(space.summary, 44.dp)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading(); contentDescription = "$title, $lead, $under" }) {
                BasicText(title, style = t.subhead.copy(color = c.inkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(lead, style = t.title2.copy(color = c.ink, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"))
                BasicText(under, style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"))
            }
            if (!busy) CircasoIconButton(CircasoIcon.Close, "Close", onClose)
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            if (failure != null) Banner("Not sent. Nothing was taken from your wallet." + failure.first.takeIf { it != com.circaso.core.common.ErrorMessages.SomethingWrong && !it.contains("Nothing was") }?.let { " $it" }.orEmpty(), CircasoIcon.Warning, tone = BannerTone.Critical)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Paying from Personal, your own wallet" }, verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Paying from", Modifier.weight(1f), style = t.footnote.copy(color = c.inkSecondary))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Avatar(pay?.payingFrom?.name ?: "Personal", size = 22.dp, imageUrl = pay?.payingFrom?.avatarUrl)
                        BasicText("Personal", style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                    }
                }
                (pay?.payChain ?: quote?.blockchains?.firstOrNull())?.let { JoinRow("Network", "USDC on ${chainName(it)}") }
                JoinRow("For", if (period == PricePeriod.OneTime) "Membership · one time" else "Membership · ${period.label.lowercase()}")
                when {
                    quote?.periodEnd != null && kind == MembershipPaymentKind.Renewal && quote.periodStart != null -> JoinRow("Period", "${shortDate(quote.periodStart)} to ${shortDate(quote.periodEnd)}")
                    quote?.periodEnd != null -> JoinRow("Member until", shortDate(quote.periodEnd))
                }
                if (quote != null) {
                    // §7b's one fee, quoted by W17 at the space rate for exactly this membership (2F's FeeParts).
                    if (pay != null) Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top) {
                        BasicText("Fee", style = t.footnote.copy(color = c.inkSecondary))
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { com.circaso.feature.wallet.presentation.FeeValue(pay, onRetryQuote, t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")) }
                    } else JoinRow("Fee", "No fee")
                    pay?.let { com.circaso.feature.wallet.presentation.totalValue(it) }?.let { JoinRow("Total", it) }
                    JoinRow("${space.name} receives", lead)
                }
            }
            val (line, tone) = when {
                slow -> "Still confirming. You’ll be told when you’re in." to 0
                joining -> "This takes a few seconds. You can leave; you’ll be told when you’re in." to 0
                expired -> "The price was held for 15 minutes and has expired." to 1
                signing -> null to 0
                offline -> "You’re offline. Money sends only while you’re connected." to 1
                step is JoinStep.Quoting -> "Getting the price for today…" to 0
                short -> pay?.let { p -> "You have ${fiatText(p.balanceMinor, rateSource)?.let { "$it (${usdcText(p.balanceMinor)})" } ?: usdcText(p.balanceMinor)}. Add money or receive some first." } to 2
                previous != null -> "${space.owner?.firstName ?: "The owner"} changed the price to ${usdcText(amountMinor)} from this period." to 0
                failure != null -> null to 0
                period != PricePeriod.OneTime -> "Never taken automatically." to 0
                else -> null to 0
            }
            if (line != null) BasicText(line, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = when (tone) { 1 -> c.warning; 2 -> c.critical; else -> c.inkSecondary }))
        }
        val label = when {
            slow -> "Close"
            joining -> "Paying ${space.name}…"
            signing -> "Waiting for your PIN…"
            failure != null -> "Try Again"
            expired -> "Get a New Price"
            else -> "Pay $total"
        }
        CircasoButton(
            label, onClick = when { slow -> onClose; failure != null || expired -> onTryAgain; else -> onPay }, size = ButtonSize.Large, block = true, busy = busy,
            style = if (slow) ButtonStyle.Secondary else ButtonStyle.Primary,
            enabled = slow || (failure != null || expired) && !offline || (pay?.canPay == true && quote != null && !joining && !offline),
        )
        if (short) Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CircasoButton("Add Money", onAddMoney, style = ButtonStyle.Secondary, size = ButtonSize.Small)
            CircasoButton("Receive", onReceive, style = ButtonStyle.Secondary, size = ButtonSize.Small)
        }
        val held = quote?.expiresAt?.let { e -> ((e - clock) / 1000).coerceAtLeast(0) }?.takeIf { !joining }
        val rate = rateSource?.let { "1 USDC = ${it.currency} ${it.rate.substringBefore('.')}.${it.rate.substringAfter('.', "00").take(2).padEnd(2, '0')}" }
        val foot = listOfNotNull(
            failure?.second?.let { "Ref $it" },
            "One Circaso fee, network cost included.".takeIf { pay?.fee is PayFee.Quoted && failure == null },
            rate?.let { r ->
                when {
                    failure != null -> null
                    offline -> rateSource?.asOf?.takeIf { it.isNotBlank() }?.let { a -> "$r as of ${asOfClock(a)}" } ?: r
                    held != null && held > 0 -> "$r · ${if (pay?.fee is PayFee.Quoted) "fee and rate" else "price"} held for ${held / 60}:${(held % 60).toString().padStart(2, '0')}"
                    else -> r
                }
            },
        ).joinToString(" · ")
        if (foot.isNotEmpty()) BasicText(foot, style = t.caption1.copy(color = c.inkTertiary, fontFeatureSettings = "tnum"))
    }
}

private fun asOfClock(iso: String): String = com.circaso.feature.spaces.data.repository.iso(iso)?.let { clockTime(it) } ?: iso

@Composable
private fun JoinRow(label: String, value: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top) {
        BasicText(label, style = t.footnote.copy(color = c.inkSecondary))
        BasicText(value, Modifier.weight(1f), style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, fontFeatureSettings = "tnum"))
    }
}
