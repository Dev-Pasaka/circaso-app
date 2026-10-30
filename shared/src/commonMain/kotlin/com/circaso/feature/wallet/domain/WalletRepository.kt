package com.circaso.feature.wallet.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Reads come from the phone's copy (the last W1 and every transfer this phone knows); everything that moves money is
 * online-only and signed by the person in the Circle SDK. Nothing about money is ever queued (offline-first.md).
 */
interface WalletRepository {
    /** The last wallet read, null before the first; `status` None until a wallet exists. */
    val wallet: Flow<Wallet?>

    /** Transfers this phone knows, newest first. */
    val transfers: Flow<List<Transfer>>

    fun transfer(transferId: String): Flow<Transfer?>

    /** W1 with the person's currency. */
    suspend fun refresh(): AppResult<Wallet>

    /** W2 then the SDK (PIN and security questions), then W1 until it is active or [wallet.ready] arrives. */
    suspend fun create(): AppResult<WalletStatus>

    suspend fun chains(): AppResult<Chains>

    /** W12 then the SDK when a challenge comes back. */
    suspend fun addChain(blockchain: String): AppResult<ChainStatus>

    /** Turns on approving payments with fingerprint / Face ID (one PIN entry to set it up). */
    suspend fun enableBiometricApproval(): AppResult<Unit>

    /** W13: puts the wallet on every supported network it isn't on (one PIN per group of networks). */
    suspend fun addMissingChains(): AppResult<Unit>

    suspend fun recipient(userId: String, blockchain: String?, chatId: String?): AppResult<Recipient>
    suspend fun remind(userId: String): AppResult<RemindOutcome>
    suspend fun validateAddress(blockchain: String, address: String): AppResult<String?>

    /**
     * W4 then the SDK. The transfer row exists from the moment the server answers (awaiting signature) so the screen
     * can follow it; the same [idempotencyKey] on a retry returns the same transfer and challenge.
     */
    suspend fun pay(request: PaymentRequest, idempotencyKey: String): AppResult<Transfer>

    /** W5, saved to the phone's copy. */
    suspend fun loadTransfer(transferId: String): AppResult<Transfer>

    /** A `wallet` realtime frame. */
    suspend fun onFrame(type: String, data: kotlinx.serialization.json.JsonObject?)
}

data class PaymentRequest(
    val blockchain: String,
    val toUserId: String?,
    val toAddress: String?,
    val amountMinor: String,
    val asset: String = Wallet.Usdc,
    val chatId: String? = null,
    /** Shown on the card and kept with the phone's copy; never sent to the wallet API. */
    val note: String? = null,
    val counterpartName: String? = null,
)
