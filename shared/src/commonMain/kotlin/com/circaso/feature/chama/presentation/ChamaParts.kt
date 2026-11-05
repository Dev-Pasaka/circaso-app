package com.circaso.feature.chama.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.GoalProgress
import com.circaso.designsystem.components.TextBadge
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.RequestState
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.formatFiat
import com.circaso.feature.wallet.domain.shortAddress
import com.circaso.feature.wallet.domain.usdcToFiat
import com.circaso.feature.wallet.presentation.hoursUntil
import kotlin.time.Instant

/** "KES 32,400" when a rate is known, else "324.00 USDC". Fiat of 100 or more reads in whole units, as the design does. */
fun amountText(minor: String, fiat: FiatValue?): String =
    fiat?.let { usdcToFiat(minor, it.rate) }?.let { formatFiat(fiat.currency, whole(it)) } ?: (MinorUnits.toDisplay(minor, 6) + " USDC")

/** The number alone ("50,000"), for "of 50,000". */
fun amountNumber(minor: String, fiat: FiatValue?): String =
    fiat?.let { usdcToFiat(minor, it.rate) }?.let { formatFiat("", whole(it)).trim() } ?: MinorUnits.toDisplay(minor, 6)

/** "32349.84" → "32350": a converted amount shows cents only while they matter (under 100). */
internal fun whole(fiat: String): String {
    val units = fiat.substringBefore('.').toLongOrNull() ?: return fiat
    if (units < 100) return fiat
    val cents = fiat.substringAfter('.', "0").padEnd(2, '0').take(2).toIntOrNull() ?: 0
    return (if (cents >= 50) units + 1 else units).toString()
}

/** "Holiday Fund · by 14 Dec" (the goal's deadline when it has one). */
fun goalMeta(chama: Chama): String {
    val by = chama.goal.deadline?.let { runCatching { formatLocalTime(Instant.parse(it).toEpochMilliseconds(), LocalTimeStyle.ShortDate) }.getOrNull() }
    return chama.goal.name + (by?.let { " · by $it" }.orEmpty())
}

/** Design D2: the goal floats under the group's header in a glass card, with Add. Tapping the progress opens the group wallet (D4). */
@Composable
fun GoalStrip(chama: Chama, fiat: FiatValue?, showAdd: Boolean, canAdd: Boolean, onAdd: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s1, bottom = CircasoSpacing.s2)
            .glass(CircasoShapes.lg, floating = true)
            .pressable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "${chama.goal.name}: open the group wallet" }
            .padding(horizontal = CircasoSpacing.s4, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GoalProgress(
            raised = amountText(chama.contributedMinor, fiat),
            of = amountNumber(chama.goal.targetMinor, fiat),
            progress = chama.progress,
            meta = goalMeta(chama),
            label = "${chama.goal.name} progress",
            modifier = Modifier.weight(1f),
        )
        // The treasurer holds the money: they can't add to their own wallet, so the button isn't there for them.
        if (showAdd) CircasoButton("Add", onAdd, size = ButtonSize.Small, enabled = canAdd, icon = com.circaso.designsystem.components.CircasoIcon.Plus)
    }
}

/** Design D5: a payment request in the thread or the group wallet, with what the viewer may do about it. */
@Composable
fun RequestCard(
    request: ChamaRequest,
    fiat: FiatValue?,
    nameOf: (String) -> String,
    isAdmin: Boolean,
    isTreasurer: Boolean,
    myUserId: String?,
    busy: Boolean,
    online: Boolean,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onPay: () -> Unit,
    modifier: Modifier = Modifier,
    /** Group wallets: the money is in the group's own wallet and its signer pays (GW9); otherwise the treasurer model. */
    inGroupWallet: Boolean = false,
    /** GW14c: approved, but it fits the group wallet's daily limit only from this time (ISO); null when it fits now. */
    waitsUntil: String? = null,
    /** Who signs for the group wallet, as the card names them ("Pasaka can pay it then"). */
    signerName: String? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val payee = request.payeeUserId?.let(nameOf) ?: request.payeeName ?: request.payeeAddress?.let(::shortAddress) ?: "someone"
    val decided = myUserId != null && request.approvals.any { it.userId == myUserId }
    val hours = hoursUntil(request.expiresAt)
    // GW14c: only an approved, unpaid payout from the group wallet waits for the limit.
    val waits = waitsUntil?.takeIf { request.state == RequestState.Approved && request.transferId == null && inGroupWallet }
        ?.let { com.circaso.feature.wallet.presentation.clockOf(it) }
    Column(
        modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface).border(1.dp, c.separator, CircasoShapes.lg).padding(CircasoSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BasicText("Payment request", style = t.footnote.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold))
            when (request.state) {
                RequestState.Pending -> TextBadge("${request.approvedBy.size} of ${request.approvalsNeeded} approvals", warning = true, icon = com.circaso.designsystem.components.CircasoIcon.Clock)
                RequestState.Approved -> if (waits != null) TextBadge("Approved · waits for the limit", warning = true, icon = com.circaso.designsystem.components.CircasoIcon.Clock)
                    else TextBadge(if (request.transferId != null) "Paying" else "Approved")
                RequestState.Paid -> TextBadge("Paid")
                RequestState.Declined -> TextBadge("Declined", warning = true)
                RequestState.Expired -> TextBadge("Expired", warning = true)
            }
        }
        BasicText(amountText(request.amountMinor, fiat), style = t.amountMedium.copy(color = c.ink))
        BasicText(
            buildAnnotatedString {
                append("${nameOf(request.requestedBy)} wants to pay ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(payee) }
                append((request.note?.let { " for $it" } ?: "") + ".")
            },
            style = t.callout.copy(color = c.ink),
        )
        val approvers = request.approvedBy.map(nameOf)
        val line = listOfNotNull(
            if (approvers.isNotEmpty()) "Approved by ${approvers.joinToString()}" else null,
            if (request.state == RequestState.Pending && hours != null) "Expires in $hours hour${if (hours == 1L) "" else "s"}" else null,
            if (waits != null) "It fits the group wallet’s daily limit from $waits; ${if (isTreasurer) "you" else signerName ?: "its signer"} can pay it then." else null,
            if (waits == null && request.state == RequestState.Approved && isTreasurer && request.transferId == null) (if (inGroupWallet) "It’s ready for you to pay from the group wallet" else "Waiting for you to pay it") else null,
            if (waits == null && request.state == RequestState.Approved && !isTreasurer && inGroupWallet && request.transferId == null) "Paid from the group wallet once its signer sends it" else null,
        ).joinToString(if (waits != null) ". " else " · ")
        if (line.isNotEmpty()) BasicText(line, style = t.footnote.copy(color = c.inkSecondary))
        val canDecide = request.state == RequestState.Pending && isAdmin && !decided
        val canPay = request.state == RequestState.Approved && isTreasurer && request.transferId == null
        when {
            canDecide -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                CircasoButton("Approve", onApprove, modifier = Modifier.weight(1f), size = ButtonSize.Medium, enabled = !busy && online, busy = busy)
                CircasoButton("Decline", onDecline, modifier = Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Medium, enabled = !busy && online)
            }
            // GW14c: the button wakes up at the time the server gives.
            canPay && waits != null -> CircasoButton("Pay After $waits", onPay, size = ButtonSize.Medium, block = true, enabled = false)
            canPay -> CircasoButton(if (inGroupWallet) "Pay Payout" else "Pay from the Group", onPay, size = ButtonSize.Medium, block = true, enabled = !busy && online)
            else -> Unit
        }
        if ((canDecide || canPay) && !online) BasicText(if (canPay) "You're offline. Paying needs a connection." else "You're offline. Approving needs a connection.", style = t.footnote.copy(color = c.inkSecondary))
    }
}

/** Design D6: what the group is, who is in it, and the goal, the first time it opens. */
@Composable
fun GroupIntroContent(chat: Chat, chama: Chama, fiat: FiatValue?, myUserId: String?, onDone: () -> Unit, inGroupWallet: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val mine = chama.goal.treasurerUserId == myUserId
    val treasurer = chat.members.firstOrNull { it.userId == chama.goal.treasurerUserId }?.displayName?.substringBefore(' ') ?: "An admin"
    // D6 is drawn full screen with a Close; as a sheet over the thread it keeps the chat in view behind it, and the
    // grabber, the scrim and "Got It" all close it. The Close stays for anyone who looks for it.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { com.circaso.designsystem.components.CircasoIconButton(com.circaso.designsystem.components.CircasoIcon.Close, "Close", onDone) }
    Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5)) {
        Avatar(chat.title, size = CircasoSizes.avatarXl, imageUrl = chat.avatarUrl)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(chat.title, style = t.title1.copy(color = c.ink, textAlign = TextAlign.Center))
            BasicText("${chat.members.size} members · Shared wallet", style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        }
        AvatarStack(chat)
        Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.bgGrouped).padding(CircasoSpacing.s4)) {
            GoalProgress(amountText(chama.contributedMinor, fiat), amountNumber(chama.goal.targetMinor, fiat), chama.progress, meta = goalMeta(chama), label = "${chama.goal.name} progress")
        }
        CircasoButton("Got It", onDone, size = ButtonSize.Large, block = true)
        BasicText(
            when {
                // Group wallets: "held by Otieno" becomes "in the group wallet" (spaces-plan §7).
                inGroupWallet && mine -> "The money is in the group’s own wallet. Only you can send from it, and only what the admins approve. Everyone sees every payment."
                inGroupWallet -> "The money is in the group’s own wallet. Only $treasurer can send from it, and only what the admins approve. Contributing is always your choice."
                mine -> "You hold the group's money and pay out only what two admins approve. Everyone sees every contribution and payment."
                else -> "$treasurer holds the group's money and pays out only what the admins approve. Contributing is always your choice."
            },
            style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
    }
}

/** The first four members, overlapping, then "+N more" (design D6). */
@Composable
private fun AvatarStack(chat: Chat) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val shown = chat.members.take(4)
    // The ring is the sheet's own colour, so the overlap reads as a cut-out in every theme.
    Row(horizontalArrangement = Arrangement.spacedBy((-10).dp), verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { m ->
            Box(Modifier.clip(CircleShape).background(c.surface).padding(3.dp)) {
                Avatar(m.displayName, size = CircasoSizes.avatarMd, imageUrl = m.avatarUrl)
            }
        }
        val more = chat.members.size - shown.size
        if (more > 0) BasicText("+$more more", style = t.subhead.copy(color = c.inkSecondary), modifier = Modifier.padding(start = 20.dp))
    }
}
