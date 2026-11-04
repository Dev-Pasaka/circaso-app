package com.circaso.feature.assistant.domain

import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.WalletRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

/**
 * The mascot's reactions to the person's own money (asha-mascot-personality.md, "Reactions to things that happen"),
 * straight from the wallet with no model involved: a payment of theirs confirms (`payment_sent`) or fails
 * (`payment_failed`, which plays the caring "we'll fix it", never sass), and money arrives (`money_received`).
 * Only heard while Asha is on screen; nothing is replayed for what happened before she opened.
 */
class AshaMoneyReactions(private val wallet: WalletRepository) {
    val reactions: Flow<AshaReaction> = merge(
        wallet.settled.mapNotNull(::forSettled),
        arrivals(),
    )

    /** One `money_received` per incoming payment that confirms after we started listening. */
    private fun arrivals(): Flow<AshaReaction> = flow {
        var seen: Set<String>? = null
        wallet.transfers.collect { list ->
            val confirmedIn = confirmedIncoming(list)
            val before = seen
            seen = confirmedIn
            if (before != null) repeat(newArrivals(before, confirmedIn)) { emit(AshaReaction.MoneyReceived) }
        }
    }

    companion object {
        /** An outgoing payment that reached the end: confirmed or failed. Anything else plays nothing. */
        fun forSettled(t: Transfer): AshaReaction? = when {
            t.direction != TransferDirection.Out -> null
            t.state == TransferState.Confirmed -> AshaReaction.PaymentSent
            t.state == TransferState.Failed -> AshaReaction.PaymentFailed
            else -> null
        }

        fun confirmedIncoming(list: List<Transfer>): Set<String> =
            list.filter { it.direction == TransferDirection.In && it.state == TransferState.Confirmed }.mapTo(HashSet()) { it.transferId }

        /** How many incoming payments are new since the last look; capped at one so a sync burst plays a single hop. */
        fun newArrivals(before: Set<String>, now: Set<String>): Int = if ((now - before).isNotEmpty()) 1 else 0
    }
}
