package com.circaso.feature.ramp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.sync.nowMillis
import com.circaso.feature.auth.domain.PhoneNumber
import com.circaso.feature.ramp.domain.CancelRampOrderUseCase
import com.circaso.feature.ramp.domain.GetKycUseCase
import com.circaso.feature.ramp.domain.GetOwnMpesaNumberUseCase
import com.circaso.feature.ramp.domain.GetRampOrderUseCase
import com.circaso.feature.ramp.domain.GetRampStatusUseCase
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.ObserveKycUpdatesUseCase
import com.circaso.feature.ramp.domain.ObserveRampUpdatesUseCase
import com.circaso.feature.ramp.domain.QuoteSaleUseCase
import com.circaso.feature.ramp.domain.RampAmount
import com.circaso.feature.ramp.domain.RampCodes
import com.circaso.feature.ramp.domain.RampLimits
import com.circaso.feature.ramp.domain.RampMoney
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.domain.RampOrderUpdate
import com.circaso.feature.ramp.domain.RampQuote
import com.circaso.feature.ramp.domain.RampRepository
import com.circaso.feature.ramp.domain.RampStatus
import com.circaso.feature.ramp.domain.RampVerification
import com.circaso.feature.ramp.domain.RunIdentityCheckUseCase
import com.circaso.feature.ramp.domain.SaleWallet
import com.circaso.feature.ramp.domain.SellForMpesaUseCase
import com.circaso.feature.ramp.domain.StartKycUseCase
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.NetworkChangedCode
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.PinnedNetwork
import com.circaso.feature.wallet.domain.QuoteProblem
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import com.circaso.feature.wallet.domain.chainName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the Cash Out flow is. [Blocked] is a gate from R1 (or a refusal that means the same). */
enum class CashOutStep { Loading, LoadFailed, Blocked, Amount, Review, Order }

/** What the amount is typed in: the USDC to sell, or the shillings to receive. */
enum class SaleUnit { Usdc, Kes }

/**
 * The network fee W17 quotes for sending the sale's USDC, on top of it (the wallet's one fee, network cost included).
 * [Free] when there is none; [Quoted] when the person must see it before approving.
 */
sealed interface SendFee {
    data object Free : SendFee
    data class Quoted(val amountMinor: String, val quoteId: String?) : SendFee
}

/** Why the USDC for an open sale hasn't been sent: what the order page says above its "Send USDC". */
enum class SendHold {
    /** The approval (PIN or biometrics) was closed: nothing left the wallet. */
    Cancelled,
    /** The wallet doesn't hold the USDC and its network fee. */
    Short,
    /** The wallet is switched to the other network: the sale's USDC is on this one. */
    OtherNetwork,
    /** Something else; [CashOutUiState.sendFailure] has its words. */
    Failed,
    /** No answer: whether it went is not known (the same key makes a retry safe). */
    Unknown,
}

data class CashOutUiState(
    val step: CashOutStep = CashOutStep.Loading,
    val block: RampBlock? = null,
    val online: Boolean = true,
    /** The network the whole flow is for is Testnet: test USDC, real shillings. */
    val testnet: Boolean = false,
    /** The network the flow is pinned to. */
    val network: WalletNetwork = WalletNetwork.TESTNET,
    val status: RampStatus? = null,
    /** The person's wallet on [network], from the phone's copy (the balance to sell from). */
    val wallet: Wallet? = null,
    /** The chain the USDC leaves from; null until R1 and the wallet have said which. */
    val blockchain: String? = null,
    val unit: SaleUnit = SaleUnit.Usdc,
    /** As typed: a decimal of USDC, or whole shillings (digits only). */
    val amountText: String = "",
    /** A live price for exactly what is typed (R2 reserves nothing): the estimate under the amount. */
    val estimate: RampQuote? = null,
    /** The typed amount, unit and chain the [estimate] is for. */
    val estimateFor: String? = null,
    val estimating: Boolean = false,
    /** What the live price said is wrong with the amount (too small to leave anything after the fees, over a limit). */
    val estimateProblem: String? = null,
    val quoting: Boolean = false,
    val quote: RampQuote? = null,
    val secondsLeft: Int = 0,
    val repriced: Boolean = false,
    val maskedPhone: String? = null,
    val needsPhone: Boolean = false,
    val phoneText: String = "",
    val phoneError: String? = null,
    /** R7 is on its way. */
    val placing: Boolean = false,
    val order: RampOrder? = null,
    /** W17 for the sale's transfer is on its way. */
    val pricingSend: Boolean = false,
    /** W17's answer for the sale's transfer; null until it is known. Nothing is sent before it is. */
    val sendFee: SendFee? = null,
    /** W4 and the approval are under way: the PIN or biometrics are up, or the server is answering. */
    val sending: Boolean = false,
    /** This phone sent the sale's USDC (W4 answered), or the server says it arrived. */
    val sent: Boolean = false,
    val sendHold: SendHold? = null,
    val sendFailure: AddMoneyFailure? = null,
    /** Seconds until the sale's deadline for the USDC ([com.circaso.feature.ramp.domain.SaleDeposit.expiresAt]). */
    val depositLeft: Int? = null,
    val stale: Boolean = false,
    val cancelling: Boolean = false,
    val cancelNote: String? = null,
    val failure: AddMoneyFailure? = null,
    val loadingOrder: Boolean = false,
    val orderUnread: Boolean = false,
    val verify: VerifyUi = VerifyUi(),
    /** The order this opened on is a purchase: Add Money shows it, and this screen hands over at once. */
    val handOff: String? = null,
) {
    val limits: RampLimits? get() = status?.offRamp?.limits

    /** The USDC this wallet holds on [blockchain]; null while it isn't known. */
    val balanceMinor: String? get() = wallet?.chains?.firstOrNull { it.blockchain == blockchain }?.usdc?.amountMinor

    /** The typed amount in minor units of its own [unit]; null while it is empty, zero or not an amount. */
    val amountMinor: String?
        get() = when (unit) {
            SaleUnit.Usdc -> MinorUnits.fromDisplay(amountText, RampMoney.CoinDecimals)?.takeIf { !MinorUnits.isZero(it) }
            SaleUnit.Kes -> RampMoney.wholeToMinor(amountText)
        }

    /** What the [estimate] must be for to count: the typed amount, its unit and the chain. */
    val askKey: String? get() = amountMinor?.let { "$unit:$it:$blockchain" }

    /** The live price for exactly what is typed now, if there is one. */
    val liveEstimate: RampQuote? get() = estimate?.takeIf { estimateFor != null && estimateFor == askKey }

    /** The USDC that would leave: typed, or what the live price says it takes to receive the typed shillings. */
    val usdcMinor: String? get() = if (unit == SaleUnit.Usdc) amountMinor else liveEstimate?.pay?.amountMinor

    /** The shillings that would arrive: typed, or what the live price says. */
    val kesMinor: String? get() = if (unit == SaleUnit.Kes) amountMinor else liveEstimate?.receive?.amountMinor

    /** What is wrong with the amount, in words; null while it is empty or fine. */
    val amountError: String?
        get() {
            val usdc = usdcMinor
            val balance = balanceMinor
            if (usdc != null && balance != null && MinorUnits.lessThan(balance, usdc)) {
                return if (MinorUnits.isZero(balance)) "You have no USDC on ${blockchain?.let { chainName(it) } ?: "this network"} to sell."
                else "That's more than the ${usdcText(balance)} in your wallet."
            }
            val kes = kesMinor ?: return estimateProblem
            val l = limits ?: return estimateProblem
            return when {
                MinorUnits.lessThan(kes, l.minOrder.amountMinor) -> "The least you can cash out is ${l.minOrder.text}."
                MinorUnits.lessThan(l.maxOrder.amountMinor, kes) -> "The most you can cash out at once is ${l.maxOrder.text}."
                else -> estimateProblem
            }
        }

    /** "12.50 USDC available": the balance line under the amount; null while the wallet isn't known. */
    val available: String? get() = balanceMinor?.let { usdcText(it) + " available" }

    val canMax: Boolean get() = balanceMinor?.let { !MinorUnits.isZero(it) } == true && !quoting && step == CashOutStep.Amount

    /** The identity check may be started, continued or tried again right now (R0). **Online-only.** */
    val canVerify get() = online && step == CashOutStep.Blocked && block == RampBlock.Verify && verify.startable
    val canQuote get() = online && amountMinor != null && amountError == null && blockchain != null && !quoting && step == CashOutStep.Amount
    val quoteExpired get() = quote != null && secondsLeft <= 0
    val canConfirm get() = online && quote != null && !quoteExpired && !placing && !quoting && step == CashOutStep.Review

    /** The sale is waiting for this phone to send its USDC. */
    val awaitingUsdc: Boolean get() = order?.state == RampOrderState.AwaitingTransfer && !sent
    /** The USDC's deadline has passed: the sale ends by itself (and late USDC is sent back). */
    val depositOver: Boolean get() = depositLeft != null && depositLeft <= 0
    /** "Send USDC" may be tapped: the sale waits, its fee is known, and nothing is under way. */
    val canSend: Boolean get() = online && awaitingUsdc && order?.deposit != null && !sending && !pricingSend && !depositOver && !cancelling && sendHold != SendHold.Short
    /** R6 for a sale: only while it waits for the USDC and before this phone sent it; the server has the last word. */
    val canCancel: Boolean get() = awaitingUsdc && !sending && !depositOver

    companion object {
        /** "12.50 USDC". */
        fun usdcText(minor: String) = MinorUnits.toDisplay(minor, RampMoney.CoinDecimals) + " " + RampMoney.Coin
    }
}

/**
 * Cash out: selling USDC for M-Pesa (guide "Selling USDC for M-Pesa", R1 `offRamp`, R2 `OFF_RAMP`, R7, then W4 naming
 * the sale; design E6's place, E2's shape). The gates and identity verification as Add Money has them, the amount in
 * USDC or shillings with a live price, a review with every fee named and the price held, the sale (R7), the USDC sent
 * with the app's usual approval (W4 with the order id as its key, so it can never be sent twice), then the sale
 * followed until the shillings are on the person's phone, or until it ends another way.
 *
 * **Money: never queued, never optimistic.** Nothing is asked of the server offline (the buttons say it needs a
 * connection), the screen only ever shows what the server last said, and nothing claims the USDC left the wallet before
 * W4 answered or the shillings arrived before the server says so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashOutViewModel(
    private val orderId: String?,
    private val getStatus: GetRampStatusUseCase,
    private val quoteSale: QuoteSaleUseCase,
    private val sell: SellForMpesaUseCase,
    private val getOrder: GetRampOrderUseCase,
    private val cancelOrder: CancelRampOrderUseCase,
    observeUpdates: ObserveRampUpdatesUseCase,
    private val ownNumber: GetOwnMpesaNumberUseCase,
    private val startKyc: StartKycUseCase,
    private val getKyc: GetKycUseCase,
    observeKyc: ObserveKycUpdatesUseCase,
    private val runCheck: RunIdentityCheckUseCase,
    private val saleWallet: SaleWallet,
    private val ramp: RampRepository,
    maskedPhone: Flow<String?> = flowOf(null),
    online: Flow<Boolean> = flowOf(true),
    private val networks: WalletNetworks = WalletNetworks.Single,
    private val now: () -> Long = ::nowMillis,
    private val pollMillis: Long = AddMoneyViewModel.PollMillis,
    network: WalletNetwork? = null,
    private val verifiedHoldMillis: Long = AddMoneyViewModel.VerifiedHoldMillis,
    /** How long the amount rests before its live price is asked. */
    private val estimateDelayMillis: Long = EstimateDelayMillis,
) : ViewModel() {
    private var pinned: WalletNetwork = network ?: networks.current()
    private suspend fun <T> onPin(block: suspend () -> T): T = withContext(PinnedNetwork(pinned)) { block() }

    private val local = MutableStateFlow(CashOutUiState(network = pinned))
    private val pin = MutableStateFlow(pinned)

    /** One key per ask, the same on a retry that got no answer; a new one once the server has answered. */
    private var quoteKey = newIdempotencyKey()
    private var sellKey = newIdempotencyKey()
    private var cancelKey = newIdempotencyKey()

    private val check = IdentityCheck(
        viewModelScope, startKyc, getKyc, runCheck, network = { pinned }, now = now,
        host = object : IdentityCheck.Host {
            override val verify get() = local.value.verify
            override fun update(change: (VerifyUi) -> VerifyUi) = local.update { it.copy(verify = change(it.verify)) }
            override val showing get() = local.value.let { it.step == CashOutStep.Blocked && it.block == RampBlock.Verify }
            override val online get() = state.value.online
            override val canVerify get() = state.value.canVerify
            override fun onNotRequired(now: RampVerification): Boolean { load(); return true }
            override fun onVerified(now: RampVerification) = this@CashOutViewModel.onVerified(now)
            override fun onBlocked(block: RampBlock) = local.update { if (it.step == CashOutStep.Blocked && it.block == RampBlock.Verify) it.copy(block = block, verify = VerifyUi()) else it }
        },
    )

    private var savedPhone: String? = null
    private var quoteDeadline = 0L
    private var countdown: Job? = null
    private var pricing: Job? = null
    private var estimating: Job? = null
    private var polling: Job? = null
    private var depositClock: Job? = null
    private var opened = false
    /** The person picked the chain: the one with the most USDC is no longer chosen for them. */
    private var chainPicked = false

    val state: StateFlow<CashOutUiState> = combine(
        local, online, maskedPhone,
        pin.flatMapLatest { on -> saleWallet.wallet(on).map { w -> w?.takeIf { it.network == null || it.network == on } } },
        combine(pin, networks.state) { on, all -> all.known && on == WalletNetwork.TESTNET },
    ) { ui, isOnline, masked, wallet, testnet ->
        val chains = ui.status?.blockchains.orEmpty()
        ui.copy(
            online = isOnline, wallet = wallet, testnet = testnet,
            maskedPhone = ui.order?.maskedPhone?.takeIf { it.isNotBlank() } ?: masked,
            // The person's pick, else the chain where they hold the most USDC (R1 lists the ones selling works on).
            blockchain = ui.blockchain?.takeIf { it in chains || chains.isEmpty() } ?: richest(chains, wallet),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CashOutUiState(network = pinned))

    init {
        viewModelScope.launch {
            observeUpdates().collect { update ->
                if (update.orderId == RampOrderUpdate.ReloadAll) check.onResync()
                val current = local.value.order ?: return@collect
                if (update.orderId != current.orderId && update.orderId != RampOrderUpdate.ReloadAll) return@collect
                // A finished sale is final: a replayed older frame never walks it back, even for a moment.
                if (update.orderId == current.orderId && update.state != RampOrderState.Unknown && !current.state.settled) {
                    show(current.copy(state = update.state, failureReason = update.failureReason ?: current.failureReason))
                }
                // The frame has no receipt: the order itself is the truth.
                read(current.orderId)
            }
        }
        viewModelScope.launch { observeKyc().collect { moved -> if (moved.network == null || moved.network == pinned) check.show(moved.verification) } }
        viewModelScope.launch { savedPhone = ownNumber(); local.update { it.copy(needsPhone = savedPhone == null) } }
        viewModelScope.launch { state.map { it.online }.distinctUntilChanged().drop(1).collect { back -> if (back) { check.readIfItMayHaveMoved(); local.value.order?.let { if (!it.state.settled) read(it.orderId) } } } }
        // The live price: asked once the amount has rested, for exactly what is typed.
        viewModelScope.launch { state.map { Triple(it.askKey, it.step, it.online) }.distinctUntilChanged().collect { (key, step, on) -> if (key != null && step == CashOutStep.Amount && on) estimate() } }
        load()
    }

    /** The chain with the most USDC on it, of those selling works on; the first when nothing is known. */
    private fun richest(chains: List<String>, wallet: Wallet?): String? {
        if (chains.isEmpty()) return null
        val held = wallet?.chains.orEmpty().associate { it.blockchain to (it.usdc?.amountMinor ?: "0") }
        return chains.maxWithOrNull { a, b ->
            val x = held[a] ?: "0"; val y = held[b] ?: "0"
            when { MinorUnits.lessThan(x, y) -> -1; MinorUnits.lessThan(y, x) -> 1; else -> 0 }
        }?.takeIf { held.isNotEmpty() } ?: chains.first()
    }

    /** R1 (or the order, when opened on one). Also "Try Again" on the load-failed and blocked states. */
    fun load() = load(refusedForIdentity = null)

    private fun load(refusedForIdentity: AppError?) {
        val openingOrder = orderId != null && !opened
        check.stop()
        local.update { it.copy(step = CashOutStep.Loading, block = null, failure = null, orderUnread = false, loadingOrder = openingOrder, verify = VerifyUi()) }
        viewModelScope.launch {
            if (orderId != null && !opened) { opened = true; open(orderId); return@launch }
            when (val result = onPin { getStatus() }) {
                is AppResult.Success -> {
                    onStatus(result.value)
                    if (refusedForIdentity != null) local.update { if (it.step == CashOutStep.Amount) it.copy(failure = failureOf(refusedForIdentity, NoPrice)) else it }
                }
                is AppResult.Failure -> blockFor(result.error)?.let { b -> local.update { it.copy(step = CashOutStep.Blocked, block = b) } }
                    ?: local.update { it.copy(step = CashOutStep.LoadFailed, failure = failureOf(result.error, CouldNotOpen)) }
            }
        }
    }

    private suspend fun onStatus(status: RampStatus) {
        val sale = status.offRamp
        val open = status.openOrderId
        when {
            // One order at a time (buying or selling): an open one is where the flow resumes.
            open != null -> { local.update { it.copy(status = status, loadingOrder = true) }; open(open) }
            // Selling isn't offered to this person (or the server is from before selling).
            sale == null || !sale.open -> local.update { it.copy(status = status, step = CashOutStep.Blocked, block = RampBlock.Unavailable) }
            status.blocked -> local.update { it.copy(status = status, step = CashOutStep.Blocked, block = RampBlock.Barred) }
            !status.accountActive -> local.update { it.copy(status = status, step = CashOutStep.Blocked, block = RampBlock.Restricted) }
            !status.verification.cleared -> onNeedsVerifying(status)
            !sale.merchantOnline || status.blockchains.isEmpty() -> local.update { it.copy(status = status, step = CashOutStep.Blocked, block = RampBlock.NoMerchant) }
            else -> local.update {
                it.copy(status = status, step = CashOutStep.Amount, block = null, order = null, quote = null, stale = false, cancelNote = null, sent = false,
                    sendFee = null, sendHold = null, sendFailure = null, depositLeft = null, verify = VerifyUi())
            }
        }
    }

    private suspend fun onNeedsVerifying(status: RampStatus) {
        val heard = status.verification
        val unsettled = heard.status == KycStatus.Pending || heard.status == KycStatus.InReview
        val now = if (unsettled) (onPin { getKyc() } as? AppResult.Success)?.value ?: heard else heard
        local.update { it.copy(status = status, step = CashOutStep.Blocked, block = RampBlock.Verify, verify = VerifyUi(verification = heard)) }
        check.show(now)
    }

    private fun onVerified(now: RampVerification) {
        viewModelScope.launch {
            val status = async { onPin { getStatus() } }
            delay(verifiedHoldMillis)
            when (val result = status.await()) {
                is AppResult.Success -> onStatus(if (result.value.verification.cleared) result.value else result.value.copy(verification = now))
                is AppResult.Failure -> blockFor(result.error)?.let { b -> local.update { it.copy(step = CashOutStep.Blocked, block = b, verify = VerifyUi()) } }
                    ?: local.update { it.copy(step = CashOutStep.LoadFailed, failure = failureOf(result.error, CouldNotOpen), verify = VerifyUi()) }
            }
        }
    }

    fun onVerify() = check.onVerify()
    fun onSettingsOpened() = check.onSettingsOpened()
    fun onResumed() { check.onResumed(); local.value.order?.let { if (!it.state.settled && state.value.online) viewModelScope.launch { read(it.orderId) } } }
    fun onPaused() = check.onPaused()

    // ---- the amount --------------------------------------------------------------------------------------------------

    fun onAmount(text: String) {
        if (local.value.quoting) return
        val s = local.value
        val clean = when (s.unit) {
            SaleUnit.Usdc -> {
                val t = text.replace(',', '.').filter { it.isDigit() || it == '.' }
                if (t.count { it == '.' } > 1 || t.substringAfter('.', "").length > RampMoney.CoinDecimals || t.substringBefore('.').length > MaxDigits) return
                // "07" reads as "7"; ".5" as "0.5".
                t.trimStart('0').let { if (it.startsWith('.') || (it.isEmpty() && t.isNotEmpty())) "0$it" else it }
            }
            SaleUnit.Kes -> {
                val most = s.limits?.maxOrder?.amountMinor?.trimStart('0')?.length?.minus(RampMoney.FiatDecimals)?.coerceIn(1, MaxDigits) ?: MaxDigits
                text.filter { it.isDigit() }.trimStart('0').take(most)
            }
        }
        if (clean != s.amountText) quoteKey = newIdempotencyKey()
        local.update { it.copy(amountText = clean, failure = null, estimateProblem = null) }
    }

    /** Switches between typing USDC and shillings, carrying the amount across with the live price when there is one. */
    fun onUnit(unit: SaleUnit) {
        val s = state.value
        if (unit == s.unit || s.quoting) return
        val live = s.liveEstimate
        val carried = when {
            live == null -> ""
            unit == SaleUnit.Kes -> live.receive.amountMinor.dropLast(RampMoney.FiatDecimals).trimStart('0')
            else -> MinorUnits.toDisplay(live.pay.amountMinor, RampMoney.CoinDecimals, minFraction = 0).replace(",", "")
        }
        quoteKey = newIdempotencyKey()
        local.update { it.copy(unit = unit, amountText = carried, failure = null, estimateProblem = null, estimate = live, estimateFor = if (live != null && carried.isNotEmpty()) keyOf(unit, carried, s.blockchain) else null) }
    }

    private fun keyOf(unit: SaleUnit, text: String, chain: String?): String? =
        s(unit, text).let { minor -> minor?.let { "$unit:$it:$chain" } }

    private fun s(unit: SaleUnit, text: String): String? = when (unit) {
        SaleUnit.Usdc -> MinorUnits.fromDisplay(text, RampMoney.CoinDecimals)?.takeIf { !MinorUnits.isZero(it) }
        SaleUnit.Kes -> RampMoney.wholeToMinor(text)
    }

    /** Max: everything this wallet holds on the chain, in USDC. */
    fun onMax() {
        val s = state.value
        if (!s.canMax) return
        val all = s.balanceMinor ?: return
        quoteKey = newIdempotencyKey()
        local.update { it.copy(unit = SaleUnit.Usdc, amountText = MinorUnits.toDisplay(all, RampMoney.CoinDecimals, minFraction = 0).replace(",", ""), failure = null, estimateProblem = null) }
    }

    fun onChain(blockchain: String) {
        if (local.value.quoting) return
        chainPicked = true
        if (blockchain != state.value.blockchain) quoteKey = newIdempotencyKey()
        local.update { it.copy(blockchain = blockchain, failure = null, estimateProblem = null) }
    }

    /** The live price under the amount: R2 reserves nothing, so it is asked as the amount rests, with a key of its own. */
    private fun estimate() {
        estimating?.cancel()
        estimating = viewModelScope.launch {
            delay(estimateDelayMillis)
            val s = state.value
            val key = s.askKey ?: return@launch
            val chain = s.blockchain ?: return@launch
            if (s.step != CashOutStep.Amount || !s.online) return@launch
            // An amount over the balance or outside the limits is already said: no price is needed for that.
            if (s.amountError != null && s.estimateProblem == null) return@launch
            local.update { it.copy(estimating = true) }
            val minor = s.amountMinor
            val result = onPin { quoteSale(chain, minor.takeIf { s.unit == SaleUnit.Usdc }, minor.takeIf { s.unit == SaleUnit.Kes }, newIdempotencyKey()) }
            if (state.value.askKey != key) { local.update { it.copy(estimating = false) }; return@launch }
            when (result) {
                is AppResult.Success -> local.update { it.copy(estimating = false, estimate = result.value, estimateFor = key, estimateProblem = null) }
                is AppResult.Failure -> local.update {
                    it.copy(estimating = false, estimate = null, estimateFor = null, estimateProblem = when ((result.error as? AppError.Api)?.code) {
                        RampCodes.AmountInvalid -> AmountTooSmall
                        RampCodes.LimitExceeded -> limitText()
                        else -> null // no price just now: the line falls back to the limits, and Continue says why
                    })
                }
            }
        }
    }

    private fun limitText(): String = local.value.limits?.let { "You can cash out ${it.minOrder.text} to ${it.maxOrder.text} at a time, and ${it.daily.text} a day." } ?: ErrorMessages.forError(AppError.Api(RampCodes.LimitExceeded, 400, null, null, emptyMap()))

    /** R2 from the amount page. */
    fun onContinue() { if (state.value.canQuote) askPrice(repriced = false) }

    /** R2 again from the review, once the price has run out. */
    fun onNewPrice() {
        val s = state.value
        if (s.step == CashOutStep.Review && s.online && !s.quoting && !s.placing) askPrice(repriced = false)
    }

    private fun askPrice(repriced: Boolean) {
        val s = state.value
        val minor = s.amountMinor ?: return
        val chain = s.blockchain ?: return
        estimating?.cancel()
        local.update { it.copy(quoting = true, failure = null, estimating = false) }
        pricing?.cancel()
        pricing = viewModelScope.launch {
            when (val result = onPin { quoteSale(chain, minor.takeIf { s.unit == SaleUnit.Usdc }, minor.takeIf { s.unit == SaleUnit.Kes }, quoteKey) }) {
                is AppResult.Success -> {
                    quoteKey = newIdempotencyKey()
                    sellKey = newIdempotencyKey() // a new price is a new sale
                    hold(result.value)
                    local.update { it.copy(step = CashOutStep.Review, quoting = false, quote = result.value, estimate = result.value, estimateFor = s.askKey, secondsLeft = secondsLeft(), repriced = repriced, phoneError = null) }
                }
                is AppResult.Failure -> {
                    if (refused(result.error)) quoteKey = newIdempotencyKey()
                    if ((result.error as? AppError.Api)?.code == RampCodes.KycRequired) { local.update { it.copy(quoting = false, quote = null) }; load(result.error); return@launch }
                    val block = blockFor(result.error)
                    local.update {
                        if (block != null) it.copy(step = CashOutStep.Blocked, block = block, quoting = false, quote = null)
                        else it.copy(step = CashOutStep.Amount, quoting = false, quote = null, failure = failureOf(result.error, NoPrice))
                    }
                }
            }
        }
    }

    private fun hold(quote: RampQuote) {
        val left = quote.expiresAt?.let { ((it - now()) / 1000).toInt() }?.takeIf { it in 1..RampQuote.QuoteSeconds } ?: RampQuote.QuoteSeconds
        quoteDeadline = now() + left * 1000L
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val seconds = secondsLeft()
                local.update { if (it.quote?.quoteId == quote.quoteId) it.copy(secondsLeft = seconds) else it }
                if (seconds <= 0 || local.value.quote?.quoteId != quote.quoteId) break
            }
        }
    }

    private fun secondsLeft(): Int = (((quoteDeadline - now()) + 999) / 1000).toInt().coerceAtLeast(0)

    fun onBackToAmount() {
        countdown?.cancel()
        pricing?.cancel()
        local.update { it.copy(step = CashOutStep.Amount, quote = null, quoting = false, failure = null, repriced = false) }
    }

    fun onPhone(text: String) = local.update { it.copy(phoneText = text.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20), phoneError = null) }

    // ---- the sale (R7) and its USDC (W4) -----------------------------------------------------------------------------

    /** "Sell": R7 with the held price, then straight on to sending the USDC with the person's approval. */
    fun onSell() {
        val s = state.value
        if (!s.canConfirm) return
        val quote = s.quote ?: return
        val phone = savedPhone ?: run {
            val typed = PhoneNumber.toE164(s.phoneText)
            val ending = s.maskedPhone?.filter { it.isDigit() }?.takeLast(3)?.takeIf { it.length == 3 }
            when {
                typed == null -> { local.update { it.copy(phoneError = "Type your full M-Pesa number, like 0712 345 678.") }; return }
                ending != null && !typed.endsWith(ending) -> { local.update { it.copy(phoneError = "That isn't the number your account is verified with. It ends in $ending.") }; return }
                else -> typed
            }
        }
        local.update { it.copy(placing = true, failure = null, repriced = false) }
        viewModelScope.launch {
            when (val result = onPin { sell(quote.quoteId, phone, sellKey) }) {
                is AppResult.Success -> {
                    savedPhone = phone
                    countdown?.cancel()
                    local.update { it.copy(needsPhone = false, phoneText = "", placing = false, sent = false, sendHold = null, sendFailure = null, sendFee = null) }
                    show(result.value)
                    // The person tapped Sell: their approval comes up at once (unless a fee must be seen first).
                    prepareSend(autoSend = true)
                }
                is AppResult.Failure -> onSellFailed(result.error)
            }
        }
    }

    private suspend fun onSellFailed(error: AppError) {
        val code = (error as? AppError.Api)?.code
        if (refused(error)) sellKey = newIdempotencyKey()
        val block = blockFor(error)
        when {
            code == RampCodes.KycRequired -> { countdown?.cancel(); local.update { it.copy(placing = false, quote = null) }; load(error) }
            block != null -> local.update { it.copy(step = CashOutStep.Blocked, block = block, placing = false) }
            code == RampCodes.QuoteExpired || code == RampCodes.QuoteNotFound -> { local.update { it.copy(placing = false) }; askPrice(repriced = true) }
            code == RampCodes.OrderInProgress -> { local.update { it.copy(placing = false) }; load() }
            code == RampCodes.PhoneNotYours -> {
                savedPhone = null
                local.update { it.copy(placing = false, needsPhone = true, phoneError = "That isn't the number your account is verified with. Type the number your account uses.") }
            }
            // No answer: it isn't known whether the sale was made. Nothing left the wallet either way: USDC only leaves with an approval.
            !refused(error) -> local.update {
                it.copy(placing = false, failure = AddMoneyFailure(
                    if (error == AppError.Network) "We couldn't reach Circaso, so the sale wasn't confirmed. Nothing left your wallet: your USDC only leaves when you approve it. Try again: it never starts a second sale for this price."
                    else "We couldn't start this sale right now. Nothing left your wallet. Please try again in a moment.",
                    ErrorMessages.reference(error),
                ))
            }
            else -> local.update { it.copy(placing = false, failure = failureOf(error, CouldNotStart)) }
        }
    }

    /**
     * W17 for the transfer that pays the sale, so its network fee (if any) is known before anything is approved. With no
     * fee and [autoSend], the approval comes up at once; with a fee, the order page shows it and waits for "Send USDC".
     */
    private fun prepareSend(autoSend: Boolean) {
        val s = local.value
        val order = s.order ?: return
        val deposit = order.deposit ?: return
        if (order.state != RampOrderState.AwaitingTransfer || s.sent || s.pricingSend || s.sending) return
        local.update { it.copy(pricingSend = true) }
        viewModelScope.launch {
            when (val r = onPin { saleWallet.fee(requestOf(order, null)) }) {
                is AppResult.Success -> {
                    val q = r.value
                    when {
                        q?.problem == QuoteProblem.InsufficientFunds || q?.problem == QuoteProblem.InsufficientGas ->
                            local.update { it.copy(pricingSend = false, sendFee = q.let { f -> if (MinorUnits.isZero(f.feeMinor)) SendFee.Free else SendFee.Quoted(f.feeMinor, f.quoteId) }, sendHold = SendHold.Short) }
                        q == null || MinorUnits.isZero(q.feeMinor) -> {
                            local.update { it.copy(pricingSend = false, sendFee = SendFee.Free) }
                            if (autoSend && state.value.online) send()
                        }
                        // A fee on top: shown on the order page, sent only once the person has seen it and taps.
                        else -> local.update { it.copy(pricingSend = false, sendFee = SendFee.Quoted(q.feeMinor, q.quoteId)) }
                    }
                }
                is AppResult.Failure -> local.update {
                    // The fee couldn't be had: nothing is sent unseen. "Send USDC" asks again.
                    it.copy(pricingSend = false, sendFee = null, sendHold = null,
                        sendFailure = AddMoneyFailure(if (r.error == AppError.Network) SendNeedsConnection else "We couldn't check the network fee for this transfer. Nothing left your wallet. Try again in a moment.", ErrorMessages.reference(r.error), calm = r.error == AppError.Network))
                }
            }
        }
    }

    private fun requestOf(order: RampOrder, quoteId: String?): PaymentRequest {
        val d = order.deposit!!
        return PaymentRequest(
            blockchain = d.blockchain, toUserId = null, toAddress = d.address, amountMinor = d.amountMinor, asset = d.asset,
            // How the payment reads in this phone's history until the sale's label takes over.
            counterpartName = "M-Pesa", quoteId = quoteId, network = pinned, rampOrderId = order.orderId,
        )
    }

    /** "Send USDC": W4 to the sale's deposit, exactly its amount, the order id as the key; then the person's approval. */
    fun onSend() {
        val s = state.value
        if (!s.canSend) return
        if (s.sendFee == null) { prepareSend(autoSend = true); return }
        send()
    }

    private fun send() {
        val s = local.value
        val order = s.order ?: return
        if (order.deposit == null || s.sending || s.sent) return
        local.update { it.copy(sending = true, sendHold = null, sendFailure = null) }
        viewModelScope.launch {
            val quoteId = (s.sendFee as? SendFee.Quoted)?.quoteId
            // The order id is the key, every time: a retry returns the same transfer (and its challenge), never a second.
            when (val r = onPin { saleWallet.send(requestOf(order, quoteId), order.orderId) }) {
                is AppResult.Success -> {
                    onPin { ramp.saleSent(order, r.value.transferId) }
                    local.update { it.copy(sending = false, sent = true) }
                    read(order.orderId)
                }
                is AppResult.Failure -> onSendFailed(order, r.error)
            }
        }
    }

    private suspend fun onSendFailed(order: RampOrder, error: AppError) {
        val code = (error as? AppError.Api)?.code
        when {
            code == "wallet.cancelled" -> local.update { it.copy(sending = false, sendHold = SendHold.Cancelled) }
            // Another transfer already pays this sale: never send again; the order says where it stands.
            code == RampCodes.SaleFunded -> { local.update { it.copy(sending = false, sent = true) }; read(order.orderId) }
            // The fee changed since it was shown: the new one is shown, and nothing is sent until it is seen.
            code == "wallet.fee_changed" -> { local.update { it.copy(sending = false, sendFee = null, sendFailure = AddMoneyFailure(FeeChanged, null, calm = true)) }; prepareSend(autoSend = false) }
            code == "wallet.insufficient_funds" || code == com.circaso.feature.wallet.domain.InsufficientGasCode -> local.update { it.copy(sending = false, sendHold = SendHold.Short) }
            code == NetworkChangedCode -> local.update { it.copy(sending = false, sendHold = SendHold.OtherNetwork) }
            // No definite answer: it isn't known whether it went. The same key makes "Send USDC" safe; the order is read.
            !refused(error) -> { local.update { it.copy(sending = false, sendHold = SendHold.Unknown, sendFailure = AddMoneyFailure(SendUnknown, ErrorMessages.reference(error))) }; read(order.orderId) }
            else -> local.update { it.copy(sending = false, sendHold = SendHold.Failed, sendFailure = AddMoneyFailure(ErrorMessages.forError(error).takeIf { m -> m != ErrorMessages.SomethingWrong } ?: SendFailed, ErrorMessages.reference(error))) }
        }
    }

    // ---- following the sale ------------------------------------------------------------------------------------------

    private suspend fun open(id: String) {
        when (val result = onPin { getOrder(id) }) {
            is AppResult.Success -> {
                val order = result.value
                // A purchase is Add Money's to show.
                if (!order.sale) { local.update { it.copy(handOff = order.orderId) }; return }
                // Whether this phone already sent the USDC (the sale's own transfer, remembered when W4 answered).
                val sentHere = order.network?.let { n -> withContext(PinnedNetwork(n)) { ramp.saleTransfer(order.orderId) } } ?: onPin { ramp.saleTransfer(order.orderId) }
                local.update { it.copy(sent = sentHere != null) }
                show(order)
                prepareSend(autoSend = false)
            }
            is AppResult.Failure -> local.update { it.copy(step = CashOutStep.LoadFailed, failure = orderUnread(result.error), orderUnread = true) }
        }
    }

    private fun show(order: RampOrder) {
        order.network?.let { pinned = it; pin.value = it }
        local.update {
            it.copy(
                step = CashOutStep.Order, order = order, placing = false, failure = null, stale = false,
                // The server says the USDC reached the seller: there is nothing left to send. (An ending says nothing
                // about whether this phone sent it, so it isn't inferred from one.)
                sent = it.sent || order.state in Arrived,
                sendHold = it.sendHold.takeIf { order.state == RampOrderState.AwaitingTransfer },
                sendFailure = it.sendFailure.takeIf { order.state == RampOrderState.AwaitingTransfer },
            )
        }
        tickDeposit(order)
        if (!order.state.settled && polling?.isActive != true) follow()
    }

    /** Counts down to the deadline for the USDC while the sale waits for it; when it passes, the sale is read. */
    private fun tickDeposit(order: RampOrder) {
        val until = order.deposit?.expiresAt ?: order.expiresAt
        if (order.state != RampOrderState.AwaitingTransfer || until == null) { depositClock?.cancel(); local.update { it.copy(depositLeft = null) }; return }
        if (depositClock?.isActive == true) return
        depositClock = viewModelScope.launch {
            while (true) {
                val left = (((until - now()) + 999) / 1000).toInt().coerceAtLeast(0)
                local.update { if (it.order?.orderId == order.orderId) it.copy(depositLeft = left) else it }
                if (left <= 0) { read(order.orderId); break }
                if (local.value.order?.state != RampOrderState.AwaitingTransfer) break
                delay(1_000)
            }
        }
    }

    private fun follow() {
        polling?.cancel()
        polling = viewModelScope.launch {
            while (true) {
                delay(pollMillis)
                val order = local.value.order ?: break
                if (order.state.settled) break
                if (state.value.online) read(order.orderId)
            }
        }
    }

    private suspend fun read(id: String) {
        when (val result = onPin { getOrder(id) }) {
            is AppResult.Success -> if (local.value.order?.orderId == id) {
                // A finished sale is final: an older answer never walks it back.
                val before = local.value.order
                if (before != null && before.state.settled && before.state != result.value.state && !(before.state == RampOrderState.Expired && result.value.state.let { it == RampOrderState.Refunding || it == RampOrderState.Refunded })) return
                show(result.value)
            }
            is AppResult.Failure -> local.update { if (it.order?.orderId == id && it.order?.state?.settled == false) it.copy(stale = true) else it }
        }
    }

    /** R6: only while the sale waits for USDC this phone hasn't sent. */
    fun onCancel() {
        val s = state.value
        val order = s.order ?: return
        if (!s.canCancel || s.cancelling || !s.online) return
        local.update { it.copy(cancelling = true, cancelNote = null) }
        viewModelScope.launch {
            when (val result = onPin { cancelOrder(order.orderId, cancelKey) }) {
                is AppResult.Success -> { cancelKey = newIdempotencyKey(); local.update { it.copy(cancelling = false) }; show(result.value) }
                is AppResult.Failure -> {
                    val code = (result.error as? AppError.Api)?.code
                    if (refused(result.error)) cancelKey = newIdempotencyKey()
                    local.update {
                        it.copy(cancelling = false, cancelNote = when (code) {
                            RampCodes.NotCancellable -> "Your USDC has already reached Circaso's partner, so this sale can't be cancelled. It finishes by itself, or your USDC comes back."
                            else -> ErrorMessages.forError(result.error)
                        })
                    }
                    if (code == RampCodes.NotCancellable) read(order.orderId)
                }
            }
        }
    }

    /** After a sale that ended without paying out: back to the amount (R1 first). */
    fun onAgain() {
        polling?.cancel()
        depositClock?.cancel()
        local.update { it.copy(order = null, quote = null, cancelNote = null, stale = false, sent = false, sendFee = null, sendHold = null, sendFailure = null, depositLeft = null) }
        load()
    }

    private fun blockFor(error: AppError): RampBlock? = when ((error as? AppError.Api)?.code) {
        RampCodes.OffRampUnavailable -> RampBlock.Unavailable
        RampCodes.WalletRequired -> RampBlock.NoWallet
        else -> IdentityCheck.blockFor(error)
    }

    private fun refused(error: AppError): Boolean = IdentityCheck.refused(error)

    private fun orderUnread(error: AppError) = AddMoneyFailure(
        if ((error as? AppError.Api)?.code == RampCodes.OrderNotFound) "We couldn't find that sale."
        else "We couldn't load this sale just now. It isn't affected: if you sent the USDC, the sale carries on, and your money is either paid out or comes back.",
        ErrorMessages.reference(error),
    )

    /** The words for a failure before anything was sent: what didn't happen, and what to do next. */
    private fun failureOf(error: AppError, unknown: String) = AddMoneyFailure(
        when ((error as? AppError.Api)?.code) {
            null -> if (error == AppError.Network) "Cashing out needs a connection. Nothing left your wallet." else unknown
            RampCodes.LimitExceeded -> limitText()
            RampCodes.AmountInvalid -> AmountTooSmall
            RampCodes.PayoutFeeUnavailable -> "We can't price a sale right now. Nothing left your wallet. Try again later."
            RampCodes.PricingUnavailable -> "Prices aren't available right now. Nothing left your wallet. Try again in a moment."
            RampCodes.NoMerchant -> "No one can buy this amount right now. Try a smaller amount, or try again later. Nothing left your wallet."
            RampCodes.ChainUnsupported -> "Cashing out isn't offered on that network. Nothing left your wallet."
            else -> ErrorMessages.forError(error).takeIf { it != ErrorMessages.SomethingWrong } ?: unknown
        },
        ErrorMessages.reference(error),
    )

    companion object {
        const val EstimateDelayMillis = 450L
        /** States that mean the sale's USDC arrived. */
        private val Arrived = setOf(RampOrderState.Funded, RampOrderState.PayingOut, RampOrderState.Completed, RampOrderState.Refunding, RampOrderState.Refunded)
        private const val MaxDigits = 9

        internal const val CouldNotOpen = "We couldn't open Cash Out right now. Nothing left your wallet. Please try again in a moment."
        internal const val NoPrice = "We couldn't get a price right now. Nothing left your wallet. Please try again in a moment."
        internal const val CouldNotStart = "We couldn't start this sale right now. Nothing left your wallet. Please try again in a moment."
        internal const val AmountTooSmall = "Enter a larger amount: nothing would reach M-Pesa after the fees."
        internal const val SendNeedsConnection = "Sending your USDC needs a connection. Nothing left your wallet."
        internal const val SendUnknown = "We couldn't confirm the transfer. If it went, this page updates by itself. Sending again is safe: this sale can only be paid once."
        internal const val SendFailed = "Your USDC wasn't sent. Nothing left your wallet. Try again in a moment."
        internal const val FeeChanged = "The network fee changed. Check it, then send."
    }
}
