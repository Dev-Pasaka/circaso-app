package com.circaso.feature.wallet.data.remote

import kotlinx.serialization.Serializable

// Guide section 11, wallet (W1–W5, W9–W12). Amounts are strings: `amountMinor` exact, `amount` whole units for display.

@Serializable data class AssetDto(val symbol: String, val tokenAddress: String = "", val decimals: Int, val amountMinor: String, val amount: String, val fiatAmount: String? = null)
@Serializable data class AssetTotalDto(val symbol: String, val decimals: Int, val amountMinor: String, val amount: String)
@Serializable data class ChainDto(val blockchain: String, val address: String, val state: String, val assets: List<AssetDto> = emptyList(), val receiveEnabled: Boolean = true)
@Serializable data class FiatDto(val currency: String, val amount: String, val rate: String, val asOf: String, val partial: Boolean = false)
@Serializable data class HoldDto(val until: String)
@Serializable data class WalletResponse(
    val status: String, val chains: List<ChainDto> = emptyList(), val totals: List<AssetTotalDto> = emptyList(), val fiat: FiatDto? = null, val hold: HoldDto? = null,
    // Phase 2 (PR #28): which wallet, whose, and whether you sign for it. Absent from an older server.
    val walletId: String? = null, val owner: WalletOwnerDto? = null, val canSign: Boolean = true,
)

// Group wallets (phase 2, guide "Group wallets", W14–W16).
@Serializable data class WalletOwnerDto(val type: String, val userId: String? = null, val chatId: String? = null, val signerUserId: String)
@Serializable data class WalletListingDto(val walletId: String, val owner: WalletOwnerDto, val status: String, val canSign: Boolean = false, val blockchains: List<String> = emptyList())
@Serializable data class WalletsResponse(val wallets: List<WalletListingDto> = emptyList())
@Serializable data class GroupWalletResponse(val chatId: String, val status: String, val wallet: WalletListingDto? = null)
@Serializable data class GroupWalletSetupResponse(val walletId: String, val chatId: String, val status: String, val challengeId: String? = null, val sdk: SdkDto)

@Serializable data class SdkDto(val appId: String, val userToken: String, val encryptionKey: String, val expiresAt: String)
@Serializable data class CreateWalletResponse(val status: String, val challengeId: String? = null, val sdk: SdkDto)

@Serializable data class ChainOptionDto(val blockchain: String, val assets: List<String> = emptyList(), val status: String, val receiveEnabled: Boolean = true)
@Serializable data class ChainsResponse(val defaultChain: String, val chains: List<ChainOptionDto> = emptyList())
@Serializable data class AddChainRequest(val blockchain: String)
@Serializable data class AddMissingChainsResponse(val chains: List<AddChainResponse> = emptyList())
@Serializable data class AddChainResponse(val blockchain: String, val status: String, val challengeId: String? = null, val sdk: SdkDto? = null)

@Serializable data class AmountDto(val asset: String, val amountMinor: String, val amount: String)
@Serializable data class AmountRequest(val asset: String, val amountMinor: String)
@Serializable data class PayeeRequest(val userId: String? = null, val address: String? = null, val groupId: String? = null)
@Serializable data class PayeeDto(val userId: String? = null, val address: String)
@Serializable data class PrepareTransferRequest(
    val blockchain: String, val to: PayeeRequest, val amount: AmountRequest, val chatId: String? = null,
    /** Pay from this group wallet (its signer only); null for your own. Never sent unless the server has group wallets. */
    val walletId: String? = null,
    /** From a group wallet: the approved payout (CH4/CH5) this pays, same payee and amount. */
    val payoutRequestId: String? = null,
    /** W17's hold on the fee the person was shown (phase 2F): W4 charges exactly it while it holds. */
    val quoteId: String? = null,
)
@Serializable data class PrepareTransferResponse(
    val transferId: String, val state: String, val blockchain: String, val amount: AmountDto, val challengeId: String? = null, val sdk: SdkDto? = null,
    val fee: FeeDto? = null, val expiresAt: String, val walletId: String? = null, val total: AmountDto? = null,
)

/** The one Circaso fee (phase 2F): an amount, plus how it was priced; [rateBps] and [minimum] are absent on a repeat. */
@Serializable data class FeeDto(
    val asset: String, val amountMinor: String, val amount: String = "", val rateBps: Int? = null, val minimum: Boolean? = null,
    val ruleId: String? = null, val ruleVersion: Int? = null,
)

/** Where a wallet stands against its limits (phase 2F); a null cap is no cap. */
@Serializable data class LimitDto(
    val perPaymentMax: AmountDto? = null, val dailyMax: AmountDto? = null, val used: AmountDto? = null, val left: AmountDto? = null,
    val windowHours: Int = 24, val moreAt: String? = null,
)

/** W17 `POST /v1/wallet/transfers/quote`: the fee for exactly this payment before it is signed (GW13). */
@Serializable data class QuoteTransferResponse(
    val blockchain: String, val amount: AmountDto, val fee: FeeDto, val total: AmountDto, val balance: AmountDto? = null,
    val maxAmount: AmountDto? = null, val limit: LimitDto? = null, val problem: String? = null, val fitsAt: String? = null,
    val quoteId: String? = null, val expiresAt: String,
)
@Serializable data class TransferResponse(
    val transferId: String, val direction: String, val state: String, val blockchain: String, val from: String, val to: PayeeDto, val amount: AmountDto,
    val fee: AmountDto? = null, val chatId: String? = null, val origin: String? = null, val txHash: String? = null, val failureReason: String? = null,
    val createdAt: String? = null, val updatedAt: String? = null,
    val fromGroupId: String? = null, val toGroupId: String? = null, val signerUserId: String? = null, val payoutRequestId: String? = null,
)

/** W6: the person's money in and out, newest first; [nextCursor] null on the last page. */
@Serializable data class TransactionsResponse(val transactions: List<TransactionDto> = emptyList(), val nextCursor: String? = null)
@Serializable data class TransactionDto(
    val id: String, val transferId: String? = null, val direction: String, val state: String, val blockchain: String, val amount: AmountDto,
    val counterparty: CounterpartyDto? = null, val chatId: String? = null, val origin: String? = null, val txHash: String? = null,
    val occurredAt: String,
)
@Serializable data class CounterpartyDto(
    val userId: String? = null, val displayName: String? = null, val handle: String? = null, val avatarUrl: String? = null, val address: String? = null,
    /** The other side is a group's wallet: name it from the group on the phone. */
    val groupId: String? = null,
)

@Serializable data class RecipientResponse(val userId: String, val status: String, val canReceive: Boolean, val blockchains: List<String> = emptyList())
@Serializable data class RemindResponse(val status: String)
@Serializable data class ValidateAddressRequest(val blockchain: String, val address: String)
@Serializable data class ValidateAddressResponse(val blockchain: String, val address: String, val valid: Boolean)
