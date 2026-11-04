package com.circaso.feature.messaging.presentation

import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.WalletRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What a thread's money cards say about their payments (Pasaka, 2026-11-04): Sending until the chain confirms it, Sent
 * only then, Not sent (nothing taken) if it failed. Read from the phone's copy of each transfer, which W4, W5 and the
 * `transfer.updated` frames keep; [check] reads W5 for a card still on its way, in case a frame was missed.
 */
class ThreadPayments(private val wallet: WalletRepository) {
    fun states(transferIds: Set<String>): Flow<Map<String, TransferState>> =
        wallet.transfers(transferIds).map { list -> list.associate { it.transferId to it.state } }

    /** W5; false when the server says this phone may not read it, or it isn't there (stop asking). */
    suspend fun check(transferId: String): Boolean = when (val r = wallet.loadTransfer(transferId)) {
        is com.circaso.core.common.AppResult.Success -> true
        is com.circaso.core.common.AppResult.Failure -> (r.error as? com.circaso.core.common.AppError.Api)?.httpStatus?.let { it != 403 && it != 404 } ?: true
    }
}

/** On its way: signed (or about to be) and not yet on chain. */
internal val TransferState.inFlight get() = this == TransferState.AwaitingSignature || this == TransferState.Submitted

/** Didn't go: refused on chain, or the signature never came. Nothing was taken. */
internal val TransferState.notSent get() = this == TransferState.Failed || this == TransferState.Expired

