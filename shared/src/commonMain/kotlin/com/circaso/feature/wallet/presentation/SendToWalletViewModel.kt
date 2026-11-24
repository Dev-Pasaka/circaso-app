package com.circaso.feature.wallet.presentation

import com.circaso.feature.wallet.domain.on
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExternalStep { Form, Review, Signing, Done }

/** The form's two pages: who and on which network, then how much. Both are [ExternalStep.Form]. */
enum class SendPage { Recipient, Amount }

/** An outside address this wallet paid before (this phone's history), offered on the recipient page. */
data class RecentAddress(val address: String, val blockchain: String, val at: Long)

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
    /** The wallet network this send is for (the view model's pin): the wallet, the review, W4 and the PIN are all its. */
    val network: com.circaso.feature.wallet.domain.WalletNetwork = com.circaso.feature.wallet.domain.WalletNetwork.TESTNET,
    /** That network is Testnet (and the server has said which networks there are): test money is on screen. */
    val testnet: Boolean = false,
    /** Solana, and the wallet has too little SOL for the network's fee: a note on the form until the form changes. */
    val gasShort: Boolean = false,
    /** Which page of the form is up. */
    val page: SendPage = SendPage.Recipient,
    /** Outside addresses paid from this wallet on this network, newest first. */
    val recent: List<RecentAddress> = emptyList(),
    /** A scanned or pasted code filled the form but asked for something that wasn't used: said calmly, beside it. */
    val notice: String? = null,
    /** A scanned or pasted code filled nothing in: why, under the address, until the address changes. */
    val scanProblem: String? = null,
    /** The amount came from Max: if the fee doesn't fit on top, the review lowers it to what does. */
    val maxed: Boolean = false,
    /** This phone's history has been read once (the recipient page waits for it before raising the keyboard). */
    val recentLoaded: Boolean = false,
    /** The address check or the fee couldn't be had: said on the amount page, with its reference, until it changes. */
    val problem: PayFailure? = null,
) {
    /** The address's kind, when it is a whole address of one: it decides which networks can pay it. */
    val family: com.circaso.core.qr.QrPayload.Family? get() = com.circaso.core.qr.QrPayload.familyOf(address)
    /** The wallet's chains that can pay [address] (all of them until the address says which kind it is). */
    val chains: List<com.circaso.feature.wallet.domain.WalletChain>
        get() = wallet?.chains?.filter { it.state == "ACTIVE" && compatible(it.blockchain, family) }.orEmpty()
    /** A whole address and a network for it: the recipient page can go on. */
    val canContinue get() = !notOwner && !fromLoading && family != null && blockchain != null && chains.any { it.blockchain == blockchain }
    /** The most that can go before the fee: the balance on this chain. */
    val canMax get() = wallet != null && !MinorUnits.isZero(balanceMinor)
    /** The sending wallet's address on the chosen chain (to copy, when it needs SOL). */
    val chainAddress: String? get() = wallet?.chains?.firstOrNull { it.blockchain == blockchain }?.address
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
    /** "No fee", "0.25 USDC · network + 1%", "0.50 USDC · minimum": the same one fee as every payment, network cost included. */
    val feeText: String
        get() = when (val f = fee) {
            is PayFee.Quoted -> if (MinorUnits.isZero(f.amountMinor)) noFee else listOfNotNull(MinorUnits.toDisplay(f.amountMinor, 6) + " USDC", feeWhy(f.minimum, f.rateBps)).joinToString(" · ")
            else -> noFee
        }
    /** On Solana Circaso takes no fee and the network's own is paid in SOL from this wallet: said instead of "No fee". */
    private val noFee: String get() = if (com.circaso.feature.wallet.domain.isSolanaChain(blockchain)) SolanaFeeLine else "No fee"
    /** What leaves the wallet: the amount plus the fee (the address receives exactly the amount). */
    val totalMinor: String? get() = amountMinor?.let { a -> feeMinor?.let { MinorUnits.add(a, it) } ?: a }
}

/** Whether [blockchain] can pay an address of [family] (EVM chains pay 0x addresses, Solana pays base58 ones). */
internal fun compatible(blockchain: String, family: com.circaso.core.qr.QrPayload.Family?): Boolean = when (family) {
    null -> true
    com.circaso.core.qr.QrPayload.Family.Solana -> com.circaso.feature.wallet.domain.isSolanaChain(blockchain)
    com.circaso.core.qr.QrPayload.Family.Evm -> !com.circaso.feature.wallet.domain.isSolanaChain(blockchain)
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
    /** The wallet network this send is on, fixed for the life of the screen: review, W4 and the PIN are all its. */
    private val networks: com.circaso.feature.wallet.domain.WalletNetworks = com.circaso.feature.wallet.domain.WalletNetworks.Single,
    /** Where group wallets are: a send from a group's wallet is on that wallet's network, whatever is selected. */
    private val groupNetworks: com.circaso.feature.wallet.domain.GroupNetworks? = null,
    /** This phone's payments, for the recent addresses; absent in older tests and previews. */
    private val history: Flow<List<Transfer>>? = null,
    /** One payment as this phone hears of it (frames, W5): the sent page follows it to the chain. */
    private val follow: ((String) -> Flow<Transfer?>)? = null,
    /** Cash Out's sales on this phone: their deposit addresses are never offered as recent addresses. */
    private val sales: Flow<com.circaso.feature.ramp.domain.MpesaSales>? = null,
) : ViewModel() {
    /** The address check and fee quote under way, cancelled when the person goes back or the network moves. */
    private var reviewJob: kotlinx.coroutines.Job? = null
    /** The network this send belongs on now: the group wallet's own, else the selected one. */
    private fun target(): com.circaso.feature.wallet.domain.WalletNetwork =
        if (fromWalletId != null && groupNetworks != null) groupNetworks.ofWalletNow(fromWalletId) else networks.current()
    private val targets: Flow<com.circaso.feature.wallet.domain.WalletNetwork> =
        if (fromWalletId != null && groupNetworks != null) groupNetworks.ofWallet(fromWalletId) else networks.selected
    private suspend fun <T> onPin(block: suspend () -> T): T = kotlinx.coroutines.withContext(com.circaso.feature.wallet.domain.PinnedNetwork(pinned)) { block() }

    /** The network the form on screen is for. It moves only while nothing is being signed, and the form says when it has. */
    private val pin = MutableStateFlow(target())
    private val pinned: com.circaso.feature.wallet.domain.WalletNetwork get() = pin.value
    private val local = MutableStateFlow(SendToWalletUiState(canScan = scanner.available, group = fromWalletId != null))
    private var idempotencyKey = newIdempotencyKey()

    // The pinned network's wallet, not the selected one's; a group wallet's copy that says it is another network's is dropped.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val source: Flow<Wallet?> = pin.flatMapLatest { on ->
        (if (fromWalletId != null && wallets != null) wallets.walletOf(fromWalletId, on) else observeWallet(on)).map { w -> w?.takeIf { it.network == null || it.network == on } }
    }
    private val from: Flow<NamedWallet?> = if (fromWalletId == null || wallets == null) flowOf(null)
    else pin.flatMapLatest { on -> wallets.named.on(on) }.map { list -> list.firstOrNull { it.walletId == fromWalletId } }

    /** Outside addresses this wallet paid on the pinned network, newest first, one row per address and chain. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val recent: Flow<List<RecentAddress>?> = if (history == null) flowOf(emptyList()) else pin.flatMapLatest { on ->
        combine(history, sales ?: flowOf(com.circaso.feature.ramp.domain.MpesaSales())) { list, sold ->
            // A sale's deposit address is the ramp's, not somewhere the person sends to: never offered, on any payment.
            val deposits = list.filter { sold.paidBy(it.transferId) != null || it.counterpartName == "M-Pesa" }.map { it.toAddress.lowercase() }.toSet()
            list.asSequence()
                .filter { t ->
                    t.outgoing && t.toUserId == null && t.toAddress.isNotBlank() && t.walletId == fromWalletId && t.toGroupId == null && t.counterpartGroupId == null &&
                        // Cash Out's deposits go to the ramp's address: not somewhere the person sends to.
                        t.toAddress.lowercase() !in deposits && t.state != com.circaso.feature.wallet.domain.TransferState.Failed &&
                        com.circaso.feature.wallet.domain.WalletNetwork.ofChain(t.blockchain) == on
                }
                .sortedByDescending { it.createdAt }
                .distinctBy { it.toAddress.lowercase() + "|" + it.blockchain }
                .take(RecentCount)
                .map { RecentAddress(it.toAddress, it.blockchain, it.createdAt) }
                .toList()
        }
    }

    val state: StateFlow<SendToWalletUiState> = combine(combine(local, recent) { ui, r -> ui.copy(recent = r.orEmpty(), recentLoaded = r != null) }, source, connection.status, from, combine(pin, networks.state) { on, all -> on to (all.known && on == com.circaso.feature.wallet.domain.WalletNetwork.TESTNET) }) { ui, w, c, f, net ->
        val family = ui.family
        val usable = w?.chains?.filter { it.state == "ACTIVE" && compatible(it.blockchain, family) }.orEmpty()
        ui.copy(
            wallet = w, online = c == ConnectionStatus.Online, from = f, network = net.first,
            // A sent payment's mark is its own (its chain says), not the selection's.
            testnet = ui.result?.takeIf { ui.step == ExternalStep.Done }?.let { com.circaso.feature.wallet.domain.isTestnetChain(it.blockchain) } ?: net.second,
            // A chain of the network that was left is not this wallet's, and an EVM chain can't pay a Solana address
            // (or the reverse): the first of its own that can.
            blockchain = ui.blockchain?.takeIf { b -> w == null || (w.chains.any { it.blockchain == b } && compatible(b, family)) }
                ?: (usable.firstOrNull() ?: w?.chains?.firstOrNull { it.state == "ACTIVE" })?.blockchain,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        // Another network picked while this screen is up (a wide screen with the Wallet tab beside it): once nothing
        // is being signed, the send starts again on it, from the form, and says so. What was reviewed (the address
        // check, the fee, the chain) was the other network's and is dropped; a payment being signed keeps its network
        // (the wallet refuses to sign across a switch) and this runs when it ends.
        viewModelScope.launch {
            combine(targets, local.map { it.step == ExternalStep.Signing }.distinctUntilChanged()) { on, signing -> on to signing }
                .collect { (on, signing) -> if (!signing && on != pinned) moveTo(on) }
        }
        if (fromWalletId != null) wallets?.let { w -> viewModelScope.launch { onPin { w.refresh(fromWalletId, null) } } }
    }

    private fun moveTo(on: com.circaso.feature.wallet.domain.WalletNetwork) {
        // A finished send is that payment's page: it keeps its network, its wallet and its mark whatever is selected.
        if (local.value.step == ExternalStep.Done) return
        reviewJob?.cancel()
        pin.value = on
        idempotencyKey = newIdempotencyKey()
        local.update {
            it.copy(
                step = ExternalStep.Form, page = SendPage.Recipient, blockchain = null, checkedAddress = null, fee = PayFee.None, failure = null, checking = false, gasShort = false,
                notice = null, scanProblem = null, problem = null,
                error = "You switched to ${on.label}. Nothing was sent. Check the network and the address again for your ${on.label} wallet.",
            )
        }
    }

    /** Typed or pasted. A pasted payment URI (`ethereum:…`, `solana:…`) is read as a scanned code would be. */
    fun onAddress(text: String) {
        if (com.circaso.core.qr.QrPayload.isPaymentUri(text)) {
            val read = com.circaso.core.qr.QrPayload.read(text)
            if (read !is com.circaso.core.qr.QrPayload.Scanned.Unknown) { apply(read); return }
        }
        local.update { it.copy(address = text.trim(), checkedAddress = null, error = null, scanProblem = null, notice = null, problem = null) }
    }

    /** A recent address: the address and the chain it was paid on, then on to the amount. */
    fun onRecent(recent: RecentAddress) {
        if (state.value.notOwner || state.value.fromLoading) return
        local.update { it.copy(address = recent.address, blockchain = recent.blockchain, checkedAddress = null, error = null, scanProblem = null, notice = null, page = SendPage.Amount) }
    }

    /** Design E5's scan button: a wallet's code fills the address, and the network, coin and amount it names. */
    fun onScan() {
        viewModelScope.launch {
            val raw = scanner.scan() ?: return@launch
            apply(com.circaso.core.qr.QrPayload.read(raw))
        }
    }

    /** What a code fills in (never guessed: a code this wallet can't pay fills nothing and says why). */
    private fun apply(code: com.circaso.core.qr.QrPayload.Scanned) {
        val s = state.value
        val whose = if (s.fromGroup) "${s.walletName}’s wallet" else "your wallet"
        when (val fill = com.circaso.feature.wallet.domain.resolveScan(code, s.wallet?.chains.orEmpty(), pinned, whose)) {
            is com.circaso.feature.wallet.domain.ScanFill.Refuse -> local.update { it.copy(scanProblem = fill.message, notice = null) }
            is com.circaso.feature.wallet.domain.ScanFill.Fill -> local.update {
                it.copy(
                    address = fill.address, blockchain = fill.blockchain ?: it.blockchain, checkedAddress = null, error = null, scanProblem = null,
                    notice = fill.notice, gasShort = false,
                    amountText = fill.amountText ?: it.amountText, maxed = if (fill.amountText != null) false else it.maxed,
                )
            }
        }
    }

    fun onChain(blockchain: String) = local.update { it.copy(blockchain = blockchain, checkedAddress = null, gasShort = false, maxed = false, notice = null, problem = null) }

    /** Recipient → amount, once there's a whole address and a network that can pay it. */
    fun onContinue() {
        if (!state.value.canContinue) return
        local.update { it.copy(page = SendPage.Amount, error = null) }
    }

    /** Amount → recipient (the page's back). */
    fun onBackToRecipient() {
        reviewJob?.cancel()
        local.update { it.copy(page = SendPage.Recipient, gasShort = false, checking = false, problem = null) }
    }

    fun onAmount(text: String) {
        val clean = text.filter { it.isDigit() || it == '.' }
        if (clean.count { it == '.' } > 1 || clean.substringAfter('.', "").length > 6) return
        local.update { it.copy(amountText = clean, error = null, maxed = false, notice = null, problem = null) }
    }

    /** Everything on this chain; the review takes off what the fee needs if it doesn't fit on top. */
    fun onMax() {
        val s = state.value
        if (!s.canMax) return
        local.update { it.copy(amountText = unitsText(s.balanceMinor), error = null, maxed = true, notice = null, problem = null) }
    }

    fun onNote(text: String) = local.update { it.copy(note = text.take(140)) }

    private fun requestOf(s: SendToWalletUiState, to: String, minor: String, quoteId: String? = null) = PaymentRequest(
        blockchain = s.blockchain!!, toUserId = null, toAddress = to, amountMinor = minor,
        // A group wallet's payment belongs to its chat (the server and this phone's history file it there).
        chatId = s.from?.chatId.takeIf { s.fromGroup },
        note = s.note.trim().takeIf { it.isNotEmpty() }, counterpartName = com.circaso.feature.wallet.domain.shortAddress(to),
                // Never the personal subject for a group wallet: onConfirm waits until its name has loaded.
        fromWalletId = fromWalletId, subject = if (fromWalletId != null) s.from!!.subject else com.circaso.feature.wallet.domain.SignSubject.Personal,
        quoteId = quoteId, network = pinned,
    )

    /** W11 first: a wrong address is an answer here, never a failed payment. Then W17's fee for exactly this. */
    fun onReview() {
        val s = state.value
        if (!s.canReview) return
        local.update { it.copy(checking = true, error = null, gasShort = false, problem = null) }
        reviewJob?.cancel()
        reviewJob = viewModelScope.launch {
            when (val r = onPin { validateAddress(s.blockchain!!, s.address) }) {
                is AppResult.Success -> {
                    val ok = r.value
                    // Not an address on this chain: said where it is fixed, under the address.
                    if (ok == null) {
                        local.update {
                            it.copy(
                                checking = false, checkedAddress = null, page = SendPage.Recipient,
                                scanProblem = "That isn't a valid address on ${com.circaso.feature.wallet.domain.chainBaseName(s.blockchain!!)}. Check it against the other wallet.",
                            )
                        }
                        return@launch
                    }
                    val fee = quote(s, ok) ?: return@launch
                    local.update { it.copy(checking = false, checkedAddress = ok, fee = fee, step = ExternalStep.Review) }
                }
                is AppResult.Failure -> local.update { it.copy(checking = false, problem = PayFailure(s.walletName, ErrorMessages.forError(r.error), ErrorMessages.reference(r.error))) }
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
                // Too little SOL for Solana's fee is a note that stays on the form with what to do, not a passing toast.
                if (answer.problem == QuoteProblem.InsufficientGas) { local.update { it.copy(checking = false, gasShort = true) }; return null }
                // Max and the fee doesn't fit on top: the most that can go, said once, and priced again.
                val most = answer.maxAmountMinor?.takeIf { !MinorUnits.isZero(it) }
                if (answer.problem == QuoteProblem.InsufficientFunds && s.maxed && most != null && most != s.amountMinor) {
                    local.update { it.copy(amountText = unitsText(most), maxed = false, notice = "Max leaves room for the fee: ${MinorUnits.toDisplay(most, 6)} USDC can go.") }
                    return quote(s.copy(amountText = unitsText(most), maxed = false), to)
                }
                answer.problem?.let { p -> local.update { it.copy(checking = false, problem = PayFailure(s.walletName, problemText(p, answer, s), null)) }; return null }
                PayFee.Quoted(answer.feeMinor, answer.rateBps, answer.minimum, answer.quoteId, answer.expiresAt)
            }
            is AppResult.Failure -> {
                val gas = com.circaso.feature.wallet.domain.isGasProblem((r.error as? AppError.Api)?.code)
                local.update { it.copy(checking = false, gasShort = gas, problem = if (gas) null else PayFailure(s.walletName, ErrorMessages.forError(r.error), ErrorMessages.reference(r.error))) }
                null
            }
        }
    }

    private fun problemText(p: QuoteProblem, q: PaymentQuote, s: SendToWalletUiState): String {
        val max = q.maxAmountMinor?.takeIf { !MinorUnits.isZero(it) }?.let { MinorUnits.toDisplay(it, 6) + " USDC" }
        val who = if (s.fromGroup) s.walletName else "You"
        return when (p) {
            QuoteProblem.InsufficientFunds -> "$who ${if (s.fromGroup) "doesn’t" else "don’t"} have enough for this and its fee." + (max?.let { " The most that can go is $it." } ?: "")
            QuoteProblem.PerPaymentLimit -> "That’s over the limit for one payment." + (max?.let { " The most is $it." } ?: "")
            QuoteProblem.DailyLimit -> "That’s over today’s limit." + (max?.let { " $it can still go today." } ?: "")
            // Shown as the form's own note ([SendToWalletUiState.gasShort]); these words are its fallback.
            QuoteProblem.InsufficientGas -> "$who ${if (s.fromGroup) "needs" else "need"} a little SOL in this wallet to pay Solana’s network fee."
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
                    followSent(transfer.transferId)
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
                    // The network moved under this send: nothing was sent or signed; back to the form on the new one.
                    if (code == com.circaso.feature.wallet.domain.NetworkChangedCode) { moveTo(target()); return@launch }
                    // W4 found too little SOL for Solana's fee: the form's note, with the address to add SOL to.
                    if (com.circaso.feature.wallet.domain.isGasProblem(code)) { local.update { it.copy(step = ExternalStep.Form, failure = null, gasShort = true) }; return@launch }
                    local.update {
                        it.copy(step = ExternalStep.Review, failure = if (code == "wallet.cancelled") null else PayFailure(s.walletName, ErrorMessages.forError(r.error), ErrorMessages.reference(r.error)))
                    }
                }
            }
        }
    }

    /** The sent page follows the payment to the chain: "On its way" → "Delivered", the explorer link once there's a hash. */
    private fun followSent(transferId: String) {
        val f = follow ?: return
        viewModelScope.launch {
            f(transferId).collect { t -> if (t != null && t.transferId == transferId) local.update { it.copy(result = t) } }
        }
    }

    fun onErrorShown() = local.update { it.copy(error = null) }



    private companion object {
        const val RecentCount = 5

        /** W4 refusals that prepared nothing (the same as Pay's): the same key would replay the refusal. */
        val Refused = setOf(
            "wallet.fee_changed", "wallet.limit_exceeded", "wallet.insufficient_funds", com.circaso.feature.wallet.domain.NetworkChangedCode,
            com.circaso.feature.wallet.domain.InsufficientGasCode,
        )
    }
}

/** Smallest units of USDC as the person would type them ("12500000" → "12.5"). */
private fun unitsText(minor: String): String = com.circaso.feature.wallet.domain.unitsToText(minor, 6)
