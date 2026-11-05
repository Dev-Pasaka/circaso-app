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

    /** The phone's copies of these transfers (any wallet), for the money cards in a chat. */
    fun transfers(ids: Collection<String>): Flow<List<Transfer>>

    /**
     * An outgoing payment this phone knows just reached the end: confirmed on chain, or failed. Heard once per change,
     * from a frame or a W5 read, so whatever shows that money (the group's balance and target) can reload at once.
     */
    val settled: Flow<Transfer>

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

    /** W17 for the same payment, online only; null when the server takes no fees (an older server). */
    suspend fun quote(request: PaymentRequest): AppResult<PaymentQuote?> = AppResult.Success(null)

    /** W5, saved to the phone's copy. */
    suspend fun loadTransfer(transferId: String): AppResult<Transfer>

    /** A `wallet` realtime frame. */
    suspend fun onFrame(type: String, data: kotlinx.serialization.json.JsonObject?)

    // ---- Group wallets (phase 2, guide "Group wallets", W14–W16 and `?walletId=`). All online-only; reads from Room.

    /** W16 from the phone's copy: the person's own first, then their groups' (the ones they sign for first). */
    val wallets: Flow<List<WalletListing>>

    suspend fun refreshWallets(): AppResult<List<WalletListing>>

    /** One wallet from the phone's copy; [walletId] null is the person's own. */
    fun walletOf(walletId: String?): Flow<Wallet?>

    /** W1 for one wallet (`?walletId=`); null is the person's own. */
    suspend fun refreshWallet(walletId: String?): AppResult<Wallet>

    /** A group wallet's payments, newest first: W6 `?walletId=` plus what this phone signed from it. */
    fun activity(walletId: String): Flow<List<Transfer>>

    suspend fun refreshActivity(walletId: String): AppResult<Unit>

    /** W15: a group's wallet as the server's rows say (members). */
    suspend fun groupWallet(chatId: String): AppResult<GroupWallet>

    /**
     * W14 then the Circle SDK with **the group wallet's own session**: the owner sets the group wallet's PIN on
     * Circle's screens, which name the group ([name]; the server never knows it). Calling again while it is still
     * setting up gives a fresh challenge (Finish Wallet Setup); null challenge means the PIN is already set.
     */
    suspend fun startGroupWallet(chatId: String, name: String): AppResult<GroupWallet>

    /** Polls W15 (and listens for `wallet.ready`) until the group's wallet is active, or a minute passes. */
    suspend fun awaitGroupWallet(chatId: String): AppResult<GroupWallet>

    /** Fingerprint / Face ID for one wallet: each wallet is its own Circle user, so each has its own setting. */
    suspend fun enableBiometricApproval(subject: SignSubject): AppResult<Unit>

    /** W13 for one wallet: puts it on every supported network (its own PIN, named on the screen). */
    suspend fun addMissingChains(subject: SignSubject): AppResult<Unit>

    // ---- Space wallets (phase 3, W18–W19). Defaults keep the older fakes building; WalletRepositoryImpl has them.

    /** W19: a space's wallet (its owner and admins). */
    suspend fun spaceWallet(spaceId: String): AppResult<SpaceWallet> = AppResult.Failure(com.circaso.core.common.AppError.Unexpected(null))

    /**
     * W18 then the Circle SDK with **the space wallet's own session** (SN4): Circle's PIN screen names the space ([name]).
     * Calling again while it is setting up gives a fresh challenge (Finish Wallet Setup, SN5b); null challenge: PIN set.
     */
    suspend fun startSpaceWallet(spaceId: String, name: String): AppResult<SpaceWallet> = AppResult.Failure(com.circaso.core.common.AppError.Unexpected(null))

    /** Polls W19 (and listens for `wallet.ready`) until the space's wallet is active, or a minute passes (SN5). */
    suspend fun awaitSpaceWallet(spaceId: String): AppResult<SpaceWallet> = AppResult.Failure(com.circaso.core.common.AppError.Unexpected(null))
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
    /** Pay into this group's wallet (`to: { groupId }`): a contribution when [chatId] is the same group. */
    val toGroupId: String? = null,
    /** Pay from this group wallet (its signer only); null is the person's own wallet. */
    val fromWalletId: String? = null,
    /** From a group wallet: the approved payout request this pays (same payee, same amount). */
    val payoutRequestId: String? = null,
    /** Which wallet signs, as the PIN screen names it. */
    val subject: SignSubject = SignSubject.Personal,
    /** W17's hold on the fee the person saw (phase 2F): W4 charges exactly it while it holds. */
    val quoteId: String? = null,
    /** Pay into this space's wallet (`to: { spaceId }`, phase 3): members only, or with [spacePaymentId]. */
    val toSpaceId: String? = null,
    /** SP13's membership quote: personal wallet only, exactly its amount (SM1). */
    val spacePaymentId: String? = null,
)

/** What stops a quoted payment as it is (W17 reports it; W4 would refuse it). */
enum class QuoteProblem { InsufficientFunds, PerPaymentLimit, DailyLimit }

/**
 * W17's answer (phase 2F, spaces-plan §7b): the one Circaso fee for exactly this payment, network cost included, in
 * the payment's coin (USDC minor units). [minimum] when the floor that covers the network cost made it more than the
 * rate. [maxAmountMinor] is the most that can go now with its fee; [leftMinor] what the wallet may still send in its
 * rolling 24 hours; [fitsAt] when this payment fits that window (ISO), if it doesn't now. [quoteId] only for the signer.
 */
data class PaymentQuote(
    val feeMinor: String,
    /** Null when the server didn't say (a repeat); the row then shows the fee alone. */
    val rateBps: Int?,
    val minimum: Boolean,
    val totalMinor: String,
    val balanceMinor: String?,
    val maxAmountMinor: String?,
    val problem: QuoteProblem?,
    val fitsAt: String?,
    val leftMinor: String?,
    val dailyMaxMinor: String?,
    val moreAt: String?,
    val quoteId: String?,
    val expiresAt: String?,
)
