package com.circaso.feature.calls.presentation

import com.circaso.feature.calls.CallPerson
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.chama.domain.GroupContribution
import com.circaso.feature.chama.presentation.amountText
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * One contribution landing on a group call (design H4, "Contributions land as they happen"): "Amina +KES 1,000" rises,
 * turns into the coin that was paid, and the coin settles into the group's goal. [id] is the transfer, so the
 * moment it was sent and the moment it confirmed are the same landing.
 */
data class Celebration(
    val id: String,
    /** "Amina", or "You" on the contributor's own phone. */
    val who: String,
    /** "KES 1,000" for a dollar coin (in the person's money when it is known), "0.01 ETH" for any other coin. */
    val amount: String,
    /** The coin's symbol, for its mark. */
    val coin: String,
    val userId: String?,
    val photoUrl: String? = null,
    /** Its own words in place of "Amina +KES 1,000" ("Amina sent you KES 500", money to me on a 1:1 call). */
    val line: String? = null,
) {
    val text: String get() = line ?: "$who +$amount"

    /** What a screen reader says when it lands. */
    val spoken: String get() = line ?: if (who == You) "You added $amount" else "$who added $amount"
}

/**
 * Money to me on a 1:1 call (Pasaka, 2026-11-10): the payments in the call's chat that splash on the receiver's call
 * screen, oldest first. Only one that reached me ([Message.Payment.outgoing] false) from the person on the call
 * ([peerUserId]), that arrived during this call (not one already in the chat when the call went live, [baseline], nor
 * one dated before the call, with [ClockSlackMillis] for the two phones' clocks), and that the server says has landed
 * ([states]: confirmed on chain, as the receiver's wallet hears it in `payment.received`; never one still on its way or
 * failed). Each once ([seen]). Payments are matched on their transfer, so the end-to-end card and the server's own
 * card for the same payment are one.
 */
fun receivedOnCall(
    thread: List<Message>,
    states: Map<String, TransferState>,
    peerUserId: String?,
    since: Long?,
    baseline: Set<String>,
    seen: Set<String>,
): List<Message.Payment> {
    if (since == null || peerUserId == null) return emptyList()
    return incomingOnCall(thread, peerUserId, since, baseline)
        .filter { states[it.transferId] == TransferState.Confirmed && it.transferId !in seen }
}

/** The payments [receivedOnCall] waits on: to me, from [peerUserId], new since the call went live. */
fun incomingOnCall(thread: List<Message>, peerUserId: String?, since: Long?, baseline: Set<String>): List<Message.Payment> {
    if (since == null || peerUserId == null) return emptyList()
    return thread.filterIsInstance<Message.Payment>()
        .filter { !it.outgoing && it.senderId == peerUserId && it.transferId !in baseline && it.orderAt >= since - ClockSlackMillis }
        .distinctBy { it.transferId }
        .sortedBy { it.orderAt }
}

/** How far the sender's clock may run behind mine before a payment sent during the call reads as from before it. */
const val ClockSlackMillis = 60_000L

/**
 * "Juma sent you KES 500" for [p]: their first name as the call shows it, a dollar coin in my money when the rate is
 * known (as the call's money line says it), any other coin in its own units.
 */
fun receivedCelebration(p: Message.Payment, people: List<CallPerson>, fiat: FiatValue?): Celebration {
    val person = people.firstOrNull { it.userId == p.senderId }
    val who = (person?.name ?: p.senderName).firstName().ifBlank { "Someone" }
    val coin = p.asset.uppercase().ifBlank { "USDC" }
    val amount = if (MinorUnits.isDollar(coin) && fiat != null) amountText(p.amountMinor, fiat) else MinorUnits.coinText(p.amountMinor, coin)
    return Celebration(p.transferId, who, amount, coin, p.senderId, person?.photoUrl, line = "$who sent you $amount")
}

/**
 * "You sent Mum KES 500" on the sender's 1:1 call, the moment the payment is signed: the amount as the money card said
 * it ([fiatText], the person's money) or the coin's own units, and [to]'s first name. Null without a transfer.
 */
fun sentCelebration(transferId: String, asset: String, amountMinor: String, fiatText: String?, to: String, myUserId: String?, myPhotoUrl: String?): Celebration? {
    if (transferId.isBlank()) return null
    val coin = asset.uppercase().ifBlank { "USDC" }
    val amount = fiatText ?: MinorUnits.coinText(amountMinor, coin)
    val name = to.firstName().ifBlank { "them" }
    return Celebration(transferId, You, amount, coin, myUserId, myPhotoUrl, line = "You sent $name $amount")
}

/**
 * The words for [c] on this call. The name is the one the call's own people list shows (the first name, as on the
 * tiles); someone not on the call keeps the server's name; my own is "You". A dollar coin is said in the person's
 * money ("KES 1,000", the way the goal is), any other coin in its own units ("0.01 ETH"), as it was paid.
 */
fun celebrationOf(c: GroupContribution, people: List<CallPerson>, myUserId: String?, fiat: FiatValue?, myPhotoUrl: String? = null): Celebration {
    val person = people.firstOrNull { it.userId == c.userId }
    val who = when {
        c.userId != null && c.userId == myUserId -> You
        person != null -> person.name.firstName()
        else -> c.displayName?.firstName()?.takeIf { it.isNotBlank() } ?: "Someone"
    }
    val coin = c.coin?.uppercase() ?: "USDC"
    val amount = if (MinorUnits.isDollar(coin)) {
        val minor = c.usdMinor ?: c.coinAmount?.let { MinorUnits.fromDisplay(it, 6) } ?: "0"
        // Without a rate, in the coin to the cent ("7.75 USDC", as H5 says it), never all six decimals.
        if (fiat != null) amountText(minor, fiat) else "${MinorUnits.toDisplay(minor.dropLast(4).ifEmpty { "0" }, 2)} $coin"
    } else {
        "${coinNumber(c.coinAmount ?: "0")} $coin"
    }
    val photo = if (who == You) myPhotoUrl else person?.photoUrl
    return Celebration(c.transferId, who, amount, coin, c.userId, photo)
}

private const val You = "You"

private fun String.firstName() = trim().substringBefore(' ')

/** "1234.5000" → "1,234.5": grouped, trailing zeros gone, at most 8 decimals. */
internal fun coinNumber(text: String): String {
    val t = text.trim().replace(",", "")
    val whole = t.substringBefore('.').ifEmpty { "0" }
    val frac = t.substringAfter('.', "").take(8).trimEnd('0')
    return MinorUnits.group(whole) + if (frac.isNotEmpty()) ".$frac" else ""
}

/** A celebration in the air, in its lane (0 lowest; more stack above it when several land at once). */
data class ShownCelebration(val celebration: Celebration, val lane: Int)

/**
 * The call's contributions. Money is never shown as landed before the server says so (offline-first rule 4):
 *
 * - **Sent, not yet confirmed** (`contribution.submitted`, or my own payment just signed): [pending], a quiet "on its
 *   way" pill in the goal panel. No flight, no pulse, nothing added. One that never confirms is dropped ([expire]).
 * - **Confirmed** (`money.updated` `CONTRIBUTION`), the moment the group's balance really moves: the full landing.
 *   At most [MaxShown] in the air at once, one per lane; the rest wait their turn in order, and past [MaxWaiting] the
 *   oldest waiting ones skip the animation and go straight to [recent] (a burst never queues for half a minute).
 *
 * Each transfer flies once ([seen]), whichever phone or frame told of it first. [recent] is H4's row of the latest
 * landings ("Juma +KES 2,500 · Wanjiru +1,000"), newest first; [landings] counts coins that reached the goal, so it
 * pulses on each.
 */
data class CelebrationState(
    val pending: List<Celebration> = emptyList(),
    val shown: List<ShownCelebration> = emptyList(),
    val waiting: List<Celebration> = emptyList(),
    val seen: Set<String> = emptySet(),
    val recent: List<Celebration> = emptyList(),
    val landings: Int = 0,
) {
    /** Several are waiting: the ones in the air move faster so the queue drains. */
    val hurry: Boolean get() = waiting.size >= HurryAt

    /** Signed and on its way: shown as pending until it confirms. */
    fun sent(c: Celebration): CelebrationState {
        if (c.id in seen || pending.any { it.id == c.id }) return this
        return copy(pending = (listOf(c) + pending).take(MaxPending))
    }

    /** Confirmed on chain: the balance moved, so it lands (once). */
    fun confirmed(c: Celebration): CelebrationState {
        if (c.id in seen) return this
        // The pending pill's words win: they were said with the call's own names first.
        val it = pending.firstOrNull { p -> p.id == c.id } ?: c
        val base = copy(pending = pending.filterNot { p -> p.id == c.id }, seen = seen + c.id)
        val lane = base.freeLane()
        if (lane != null) return base.copy(shown = base.shown + ShownCelebration(it, lane))
        val queued = base.waiting + it
        if (queued.size <= MaxWaiting) return base.copy(waiting = queued)
        val skipped = queued.take(queued.size - MaxWaiting)
        return base.copy(waiting = queued.drop(skipped.size), recent = base.withRecent(skipped))
    }

    /** Confirmed while the call is out of sight (minimised): no animation to come back to, it just joins the row. */
    fun quiet(c: Celebration): CelebrationState {
        if (c.id in seen) return this
        val it = pending.firstOrNull { p -> p.id == c.id } ?: c
        return copy(pending = pending.filterNot { p -> p.id == c.id }, seen = seen + c.id, recent = withRecent(listOf(it)))
    }

    /** It never confirmed (failed, or the frame was lost; the reload has the truth): its pending pill goes. */
    fun expire(id: String): CelebrationState = if (pending.none { it.id == id }) this else copy(pending = pending.filterNot { it.id == id })

    /** [id]'s coin reached the goal: it joins the recent row and the goal pulses. */
    fun landed(id: String): CelebrationState {
        val c = shown.firstOrNull { it.celebration.id == id }?.celebration ?: return this
        if (recent.any { it.id == id }) return this
        return copy(recent = withRecent(listOf(c)), landings = landings + 1)
    }

    /** [id] is done (or was cut short): its lane goes to the next one waiting. */
    fun finished(id: String): CelebrationState {
        val gone = shown.firstOrNull { it.celebration.id == id } ?: return this
        val rest = shown - gone
        val next = waiting.firstOrNull() ?: return copy(shown = rest)
        return copy(shown = rest + ShownCelebration(next, gone.lane), waiting = waiting.drop(1))
    }

    private fun freeLane(): Int? = (0 until MaxShown).firstOrNull { l -> shown.none { it.lane == l } }

    private fun withRecent(landed: List<Celebration>): List<Celebration> =
        (landed.reversed() + recent.filter { r -> landed.none { it.id == r.id } }).take(MaxRecent)

    companion object {
        const val MaxShown = 3
        const val MaxWaiting = 6
        const val HurryAt = 2
        const val MaxRecent = 2
        const val MaxPending = 3

        /** How long a sent contribution may stay "on its way" before the call stops saying so. */
        const val PendingMillis = 120_000L
    }
}

/**
 * The [CelebrationState] of one call, held by the call layer for as long as the call lasts (minimising the call keeps
 * it: its row, what is pending and what already flew), so the screen only draws it.
 */
class CallCelebrationsModel {
    var state: CelebrationState by androidx.compose.runtime.mutableStateOf(CelebrationState())
        private set

    /** [visible]: the full call is on screen, so a confirmed one flies; otherwise it just joins the row. */
    fun receive(c: Celebration, confirmed: Boolean, visible: Boolean) {
        state = when {
            !confirmed -> state.sent(c)
            visible -> state.confirmed(c)
            else -> state.quiet(c)
        }
    }

    fun landed(id: String) { state = state.landed(id) }

    fun finished(id: String) { state = state.finished(id) }

    fun expire(id: String) { state = state.expire(id) }
}
