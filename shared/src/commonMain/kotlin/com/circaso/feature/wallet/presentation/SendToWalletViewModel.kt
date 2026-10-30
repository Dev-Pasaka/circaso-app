package com.circaso.feature.wallet.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.PayUseCase
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.ValidateAddressUseCase
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.usdcToFiat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExternalStep { Form, Review, Signing, Done }

data class SendToWalletUiState(
    /** The phone can scan a QR code (design E5's scan button). */
    val canScan: Boolean = false,
    val step: ExternalStep = ExternalStep.Form,
    val wallet: Wallet? = null,
    val online: Boolean = true,
    val blockchain: String? = null,
    val address: String = "",
    /** The address as the server will pay it (EVM lowercase), once checked. */
    val checkedAddress: String? = null,
    val amountText: String = "",
    val checking: Boolean = false,
    val error: String? = null,
    val result: Transfer? = null,
) {
    val amountMinor get() = MinorUnits.fromDisplay(amountText, 6)
    val balanceMinor get() = wallet?.chains?.firstOrNull { it.blockchain == blockchain }?.usdc?.amountMinor ?: "0"
    val overBalance get() = amountMinor?.let { MinorUnits.lessThan(balanceMinor, it) } ?: false
    val fiatHint: String? get() = wallet?.fiat?.let { f -> amountMinor?.let { usdcToFiat(it, f.rate) }?.let { "About ${f.currency} $it" } }
    val canReview get() = online && blockchain != null && address.isNotBlank() && amountMinor?.let { !MinorUnits.isZero(it) } == true && !overBalance && !checking && step == ExternalStep.Form
}

/** Design E5: USDC to an outside address on one of the wallet's chains, checked with W11, warned, then signed. */
class SendToWalletViewModel(
    observeWallet: ObserveWalletUseCase,
    private val validateAddress: ValidateAddressUseCase,
    private val pay: PayUseCase,
    connection: ConnectionState,
    private val scanner: com.circaso.core.qr.QrScanner = com.circaso.core.qr.NoQrScanner,
) : ViewModel() {
    private val local = MutableStateFlow(SendToWalletUiState(canScan = scanner.available))
    private var idempotencyKey = newIdempotencyKey()

    val state: StateFlow<SendToWalletUiState> = combine(local, observeWallet(), connection.status) { ui, w, c ->
        ui.copy(wallet = w, online = c == ConnectionStatus.Online, blockchain = ui.blockchain ?: w?.chains?.firstOrNull { it.state == "ACTIVE" }?.blockchain)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SendToWalletUiState())

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
                    else local.update { it.copy(error = "That code is for USDC on ${com.circaso.feature.wallet.domain.chainName(named)}, which your wallet isn't on. Add it under Wallet → Networks first.") }
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

    /** W11 first: a wrong address is an answer here, never a failed payment. */
    fun onReview() {
        val s = state.value
        if (!s.canReview) return
        local.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            when (val r = validateAddress(s.blockchain!!, s.address)) {
                is AppResult.Success -> {
                    val ok = r.value
                    local.update { it.copy(checking = false, checkedAddress = ok, step = if (ok != null) ExternalStep.Review else ExternalStep.Form, error = if (ok == null) "That isn't a valid address on this network." else null) }
                }
                is AppResult.Failure -> local.update { it.copy(checking = false, error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    fun onBackToForm() = local.update { it.copy(step = ExternalStep.Form) }

    fun onConfirm() {
        val s = state.value
        val to = s.checkedAddress ?: return
        val minor = s.amountMinor ?: return
        val chain = s.blockchain ?: return
        if (s.step != ExternalStep.Review) return
        local.update { it.copy(step = ExternalStep.Signing, error = null) }
        viewModelScope.launch {
            when (val r = pay(PaymentRequest(blockchain = chain, toUserId = null, toAddress = to, amountMinor = minor), idempotencyKey)) {
                is AppResult.Success -> { idempotencyKey = newIdempotencyKey(); local.update { it.copy(step = ExternalStep.Done, result = r.value) } }
                is AppResult.Failure -> {
                    val code = (r.error as? AppError.Api)?.code
                    local.update { it.copy(step = ExternalStep.Review, error = if (code == "wallet.cancelled") null else ErrorMessages.forError(r.error)) }
                }
            }
        }
    }

    fun onErrorShown() = local.update { it.copy(error = null) }
}
