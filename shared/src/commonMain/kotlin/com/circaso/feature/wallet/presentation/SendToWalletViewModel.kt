package com.circaso.feature.wallet.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.messaging.domain.PaymentCard
import com.circaso.feature.messaging.domain.SendPaymentCardUseCase
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.PayUseCase
import com.circaso.feature.wallet.domain.PaymentQuote
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.QuotePaymentUseCase
import com.circaso.feature.wallet.domain.QuoteProblem
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.ValidateAddressUseCase
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.chainName
import com.circaso.feature.wallet.domain.usdcToFiat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExternalStep { Form, Review, Signing, Done }

data class SendToWalletUiState(
    /** The phone can scan a QR code (design E5's scan button). */
    val canScan: Boolean = false,
    /** Sending from a group wallet: known from the first frame, before its name and signer have loaded. */
    val group: Boolean = false,
    val step: ExternalStep = ExternalStep.Form,
    val wallet: Wallet? = null,
    val online: Boolean = true,
    val blockchain: String? = null,
    val address: String = "",
    /** The address as the server will pay it (EVM lowercase), once checked. */
    val checkedAddress: String? = null,
    val amountText: String = "",
    /** What it's for: kept with the payment, and on the group chat's card for a group wallet. */
    val note: String = "",
    val checking: Boolean = false,
    val error: String? = null,
    /** The payment didn't go (G2): stays on Review with what went wrong and its reference, until tried again. */
    val failure: PayFailure? = null,
    val result: Transfer? = null,
    /**
     * The group wallet it leaves (2026-11-06: its owner withdraws to an outside address); null is the personal wallet.
     * Named from the group's chat.
     */
    val from: NamedWallet? = null,
    /** The one Circaso fee for exactly this payment (W17), shown on Review and held for W4 by its quote. */
    val fee: PayFee = PayFee.None,
) {
    val fromGroup get() = group || (from != null && !from.personal)
    /** The group wallet's name and signer haven't loaded yet: nothing is reviewed or signed until they have. */
    val fromLoading get() = group && from == null
    /** Only a group wallet's owner sends from it; anyone else sees why and nothing to press. */
    val notOwner get() = fromGroup && from?.canSign == false
    val walletName get() = from?.name ?: if (group) "the group wallet" else "your wallet"
    val amountMinor get() = MinorUnits.fromDisplay(amountText, 6)
    val balanceMinor get() = wallet?.chains?.firstOrNull { it.blockchain == blockchain }?.usdc?.amountMinor ?: "0"
    val overBalance get() = amountMinor?.let { MinorUnits.lessThan(balanceMinor, it) } ?: false
    val fiatHint: String? get() = wallet?.fiat?.let { f -> amountMinor?.let { usdcToFiat(it, f.rate) }?.let { "About ${f.currency} $it" } }
    val canReview get() = online && !notOwner && !fromLoading && blockchain != null && address.isNotBlank() && amountMinor?.let { !MinorUnits.isZero(it) } == true && !overBalance && !checking && step == ExternalStep.Form
    val feeMinor: String? get() = (fee as? PayFee.Quoted)?.amountMinor
    /** "No fee", "0.25 USDC · 1%", "0.50 USDC · minimum": the same one fee as every payment, network cost included. */
    val feeText: String
        get() = when (val f = fee) {
            is PayFee.Quoted -> if (MinorUnits.isZero(f.amountMinor)) "No fee" else listOfNotNull(MinorUnits.toDisplay(f.amountMinor, 6) + " USDC", if (f.minimum) "minimum" else f.rateBps?.let(::rateText)).joinToString(" · ")
            else -> "No fee"
        }
    /** What leaves the wallet: the amount plus the fee (the address receives exactly the amount). */
    val totalMinor: String? get() = amountMinor?.let { a -> feeMinor?.let { MinorUnits.add(a, it) } ?: a }
}

/**
 * Design E5: USDC to an outside address on one of the wallet's chains, checked with W11, priced with W17 (the one fee,
 * network cost included), warned, then signed. From a group wallet (2026-11-06) only its owner can do this: the PIN is
 * the group wallet's, and the payment shows in the group's chat as a card ("Sent from Diani to 0x12…ab4").
 */
class SendToWalletViewModel(
    observeWallet: ObserveWalletUseCase,
    private val validateAddress: ValidateAddressUseCase,
    private val pay: PayUseCase,
    connection: ConnectionState,
    private val scanner: com.circaso.core.qr.QrScanner = com.circaso.core.qr.NoQrScanner,
    /** The group wallet to send from (its owner only); null is the personal wallet. */
    private val fromWalletId: String? = null,
    /** Group wallets' names, balances and refresh; absent in older tests and previews. */
    private val wallets: GroupWalletSources? = null,
    /** W17; absent in older tests and previews, which show no fee. */
    private val quotePayment: QuotePaymentUseCase? = null,
    /** The card into the group's chat after a group wallet's payment. */
    private val sendCard: SendPaymentCardUseCase? = null,
) : ViewModel() {
    private val local = MutableStateFlow(SendToWalletUiState(canScan = scanner.available, group = fromWalletId != null))
    private var idempotencyKey = newIdempotencyKey()

    private val source: Flow<Wallet?> = if (fromWalletId != null && wallets != null) wallets.walletOf(fromWalletId) else observeWallet()
    private val from: Flow<NamedWallet?> = if (fromWalletId == null || wallets == null) flowOf(null)
    else wallets.named.map { list -> list.firstOrNull { it.walletId == fromWalletId } }

    val state: StateFlow<SendToWalletUiState> = combine(local, source, connection.status, from) { ui, w, c, f ->
        ui.copy(wallet = w, online = c == ConnectionStatus.Online, from = f, blockchain = ui.blockchain ?: w?.chains?.firstOrNull { it.state == "ACTIVE" }?.blockchain)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        if (fromWalletId != null) wallets?.let { w -> viewModelScope.launch { w.refresh(fromWalletId, null) } }
    }

    fun onAddress(text: String) = local.update { it.copy(address = text.trim(), checkedAddress = null, error = null) }

    /** Design E5's scan button: a wallet's QR (a bare address or a wallet URI) fills the address. */
    fun onScan() {
        viewModelScope.launch {
            val raw = scanner.scan() ?: return@launch
            when (val r = com.circaso.core.qr.QrPayload.read(raw)) {
                is com.circaso.core.qr.QrPayload.Scanned.Address -> {
                    onAddress(r.address)
                    // A code that names its network decides the network: never send on another chain than it was made for.
                    val named = r.blockchain ?: return@launch
                    val mine = state.value.wallet?.chains?.map { it.blockchain }.orEmpty()
                    val match = mine.firstOrNull { it == named } ?: if (named == "SOL") mine.firstOrNull { it.startsWith("SOL") } else null
                    if (match != null) onChain(match)
                    else local.update { it.copy(error = "That code is for USDC on ${chainName(named)}, which ${if (state.value.fromGroup) "${state.value.walletName}’s wallet" else "your wallet"} isn't on. Add it under Wallet → Networks first.") }
                }
                is com.circaso.core.qr.QrPayload.Scanned.Handle -> local.update { it.copy(error = "That's a Circaso code for @${r.handle}. Pay them from Send instead, no address needed.") }
                com.circaso.core.qr.QrPayload.Scanned.Unknown -> local.update { it.copy(error = "That code isn't a wallet address.") }
            }
        }
    }
    fun onChain(blockchain: String) = local.update { it.copy(blockchain = blockchain, checkedAddress = null) }
    fun onAmount(text: String) {
        val clean = text.filter { it.isDigit() || it == '.' }
        if (clean.count { it == '.' } > 1 || clean.substringAfter('.', "").length > 6) return
        local.update { it.copy(amountText = clean, error = null) }
    }
    fun onNote(text: String) = local.update { it.copy(note = text.take(140)) }

    private fun requestOf(s: SendToWalletUiState, to: String, minor: String, quoteId: String? = null) = PaymentRequest(
        blockchain = s.blockchain!!, toUserId = null, toAddress = to, amountMinor = minor,
        // A group wallet's payment belongs to its chat (the server and this phone's history file it there).
        chatId = s.from?.chatId.takeIf { s.fromGroup },
        note = s.note.trim().takeIf { it.isNotEmpty() }, counterpartName = com.circaso.feature.wallet.domain.shortAddress(to),
                // Never the personal subject for a group wallet: onConfirm waits until its name has loaded.
        fromWalletId = fromWalletId, subject = if (fromWalletId != null) s.from!!.subject else com.circaso.feature.wallet.domain.SignSubject.Personal,
        quoteId = quoteId,
    )

    /** W11 first: a wrong address is an answer here, never a failed payment. Then W17's fee for exactly this. */
    fun onReview() {
        val s = state.value
        if (!s.canReview) return
        local.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            when (val r = validateAddress(s.blockchain!!, s.address)) {
                is AppResult.Success -> {
                    val ok = r.value
                    if (ok == null) { local.update { it.copy(checking = false, checkedAddress = null, error = "That isn't a valid address on this network.") }; return@launch }
                    val fee = quote(s, ok) ?: return@launch
                    local.update { it.copy(checking = false, checkedAddress = ok, fee = fee, step = ExternalStep.Review) }
                }
                is AppResult.Failure -> local.update { it.copy(checking = false, error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    /** The fee for this payment, or null after saying why it can't go as it is (and leaving the form as it was). */
    private suspend fun quote(s: SendToWalletUiState, to: String): PayFee? {
        val q = quotePayment ?: return PayFee.None
        val minor = s.amountMinor ?: return null
        return when (val r = q(requestOf(s, to, minor))) {
            is AppResult.Success -> {
                val answer = r.value ?: return PayFee.None
                answer.problem?.let { p -> local.update { it.copy(checking = false, error = problemText(p, answer, s)) }; return null }
                PayFee.Quoted(answer.feeMinor, answer.rateBps, answer.minimum, answer.quoteId, answer.expiresAt)
            }
            is AppResult.Failure -> { local.update { it.copy(checking = false, error = ErrorMessages.forError(r.error)) }; null }
        }
    }

    private fun problemText(p: QuoteProblem, q: PaymentQuote, s: SendToWalletUiState): String {
        val max = q.maxAmountMinor?.takeIf { !MinorUnits.isZero(it) }?.let { MinorUnits.toDisplay(it, 6) + " USDC" }
        val who = if (s.fromGroup) s.walletName else "You"
        return when (p) {
            QuoteProblem.InsufficientFunds -> "$who ${if (s.fromGroup) "doesn’t" else "don’t"} have enough for this and its fee." + (max?.let { " The most that can go is $it." } ?: "")
            QuoteProblem.PerPaymentLimit -> "That’s over the limit for one payment." + (max?.let { " The most is $it." } ?: "")
            QuoteProblem.DailyLimit -> "That’s over today’s limit." + (max?.let { " $it can still go today." } ?: "")
        }
    }

    fun onBackToForm() = local.update { it.copy(step = ExternalStep.Form, failure = null) }

    fun onConfirm() {
        val s = state.value
        val to = s.checkedAddress ?: return
        val minor = s.amountMinor ?: return
        if (s.blockchain == null || s.step != ExternalStep.Review || s.notOwner || s.fromLoading) return
        local.update { it.copy(step = ExternalStep.Signing, error = null, failure = null) }
        viewModelScope.launch {
            val request = requestOf(s, to, minor, (s.fee as? PayFee.Quoted)?.quoteId)
            when (val r = pay(request, idempotencyKey)) {
                is AppResult.Success -> {
                    idempotencyKey = newIdempotencyKey()
                    val transfer = r.value
                    // Every payment out of a group wallet shows in the group's chat (Pasaka, 2026-11-06).
                    val chat = s.from?.chatId?.takeIf { s.fromGroup }
                    if (chat != null) sendCard?.invoke(chat, PaymentCard(transfer.transferId, transfer.amount.amountMinor, transfer.amount.asset, transfer.blockchain, payoutNote(s.walletName, null, to, request.note)))
                    local.update { it.copy(step = ExternalStep.Done, result = transfer) }
                }
                is AppResult.Failure -> {
                    val code = (r.error as? AppError.Api)?.code
                    // The fee rose since it was shown (the quote ran out): show the new one before anything is signed.
                    if (code == "wallet.fee_changed") {
                        idempotencyKey = newIdempotencyKey()
                        val fee = quote(s, to)
                        if (fee == null) { local.update { it.copy(step = ExternalStep.Form) }; return@launch }
                        local.update { it.copy(step = ExternalStep.Review, fee = fee, error = "The fee changed to ${state.value.copy(fee = fee).feeText}. Check it and confirm again.") }
                        return@launch
                    }
                    // PayViewModel's rule: a new key only when nothing was prepared or the SDK failed; a 5xx or
                    // request.in_progress keeps it, so Try Again can never send to the address twice.
                    if (code == "wallet.sdk_failed" || code == "wallet.payout_in_progress" || code in Refused) idempotencyKey = newIdempotencyKey()
                    local.update {
                        it.copy(step = ExternalStep.Review, failure = if (code == "wallet.cancelled") null else PayFailure(s.walletName, ErrorMessages.forError(r.error), ErrorMessages.reference(r.error)))
                    }
                }
            }
        }
    }

    fun onErrorShown() = local.update { it.copy(error = null) }

    private companion object {
        /** W4 refusals that prepared nothing (the same as Pay's): the same key would replay the refusal. */
        val Refused = setOf("wallet.fee_changed", "wallet.limit_exceeded", "wallet.insufficient_funds")
    }
}
