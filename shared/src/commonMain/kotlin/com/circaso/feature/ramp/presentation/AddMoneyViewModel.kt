package com.circaso.feature.ramp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.sync.nowMillis
import com.circaso.feature.auth.domain.PhoneNumber
import com.circaso.feature.ramp.domain.BuyWithMpesaUseCase
import com.circaso.feature.ramp.domain.CancelRampOrderUseCase
import com.circaso.feature.ramp.domain.GetOwnMpesaNumberUseCase
import com.circaso.feature.ramp.domain.GetRampOrderUseCase
import com.circaso.feature.ramp.domain.GetRampStatusUseCase
import com.circaso.feature.ramp.domain.ObserveRampUpdatesUseCase
import com.circaso.feature.ramp.domain.QuoteTopUpUseCase
import com.circaso.feature.ramp.domain.RampCodes
import com.circaso.feature.ramp.domain.RampKyc
import com.circaso.feature.ramp.domain.RampMoney
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.domain.RampOrderUpdate
import com.circaso.feature.ramp.domain.RampQuote
import com.circaso.feature.ramp.domain.RampStatus
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.PinnedNetwork
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the Add money flow is. [Blocked] is a gate from R1 (or a refusal that means the same). */
enum class AddMoneyStep { Loading, LoadFailed, Blocked, Amount, Review, Order }

/** Why the person can't buy now (R1's gates). */
enum class RampBlock {
    /** `ramp.unavailable`: off, or a preview this person isn't in. */
    Unavailable,
    /** Identity not verified yet (design E3). */
    Verify,
    /** The account can't trade. */
    Restricted,
    /** No partner is online to sell right now. */
    NoMerchant,
}

/** Something that went wrong and stays on screen until dealt with: the words, and what to quote to support. */
data class AddMoneyFailure(val message: String, val reference: String?)

data class AddMoneyUiState(
    val step: AddMoneyStep = AddMoneyStep.Loading,
    val block: RampBlock? = null,
    val online: Boolean = true,
    /** The network the whole flow is for is Testnet: test USDC, real shillings. */
    val testnet: Boolean = false,
    val status: RampStatus? = null,
    /** The chain the USDC arrives on. */
    val blockchain: String? = null,
    /** Whole shillings as typed (digits only). */
    val amountText: String = "",
    /** Something other than a digit was typed: said once, under the amount. */
    val wholeOnlyHint: Boolean = false,
    val quoting: Boolean = false,
    val quote: RampQuote? = null,
    /** Seconds the price still holds; 0 means it has run out and a new one must be asked for. */
    val secondsLeft: Int = 0,
    /** The price changed under a confirm (the old one ran out): said on the review until it is confirmed. */
    val repriced: Boolean = false,
    /** The account's verified number as the server masks it. */
    val maskedPhone: String? = null,
    /** This phone doesn't hold the full number yet: the review asks for it once. */
    val needsPhone: Boolean = false,
    val phoneText: String = "",
    val phoneError: String? = null,
    val placing: Boolean = false,
    val order: RampOrder? = null,
    /** The order showed as paid at some point: an ending after that never says "nothing was taken". */
    val everPaid: Boolean = false,
    /** The last check of the order got no answer: it is still being followed, and the screen says so. */
    val stale: Boolean = false,
    val cancelling: Boolean = false,
    /** Why a cancel didn't happen, when it didn't. */
    val cancelNote: String? = null,
    /** A one-off line for the toast. */
    val error: String? = null,
    val failure: AddMoneyFailure? = null,
    /** The load that failed was of an order that exists (a push, or the open order): the words never say nothing was sent. */
    val orderUnread: Boolean = false,
) {
    val limits get() = status?.limits
    val amountMinor: String? get() = RampMoney.wholeToMinor(amountText)

    /** What is wrong with the amount as typed, in words; null while it is empty or fine. */
    val amountError: String?
        get() {
            val minor = amountMinor ?: return null
            val l = limits ?: return null
            return when {
                MinorUnits.lessThan(minor, l.minOrder.amountMinor) -> "The least you can add is ${l.minOrder.text}."
                MinorUnits.lessThan(l.maxOrder.amountMinor, minor) -> "The most you can add at once is ${l.maxOrder.text}."
                else -> null
            }
        }
    val canQuote get() = online && amountMinor != null && amountError == null && blockchain != null && !quoting && step == AddMoneyStep.Amount
    val quoteExpired get() = quote != null && secondsLeft <= 0
    val canConfirm get() = online && quote != null && !quoteExpired && !placing && !quoting && step == AddMoneyStep.Review
    /** R6 is only possible before the payment; the server still decides (a prompt open on the phone can't be cancelled). */
    val canCancel get() = order?.state == RampOrderState.Reserved || order?.state == RampOrderState.PaymentRequested
    /** The quick amounts of design E2 that this person's limits allow. */
    val chips: List<String>
        get() = QuickAmounts.filter { a ->
            val l = limits ?: return@filter true
            val minor = a + "00"
            !MinorUnits.lessThan(minor, l.minOrder.amountMinor) && !MinorUnits.lessThan(l.maxOrder.amountMinor, minor)
        }

    companion object { val QuickAmounts = listOf("500", "2000", "5000") }
}

/**
 * Design E2, on the ramp's routes (R1–R6): the gates, the amount in whole shillings, a price held 60 seconds, the
 * M-Pesa prompt on the person's own verified number, then the order followed live until the USDC is in the wallet.
 *
 * **Money: never queued, never optimistic.** Nothing is asked of the server while offline (the buttons say it needs a
 * connection), and the screen only ever shows what the server last answered. [orderId] opens straight on an order
 * (a push, or R1's open order).
 */
class AddMoneyViewModel(
    private val orderId: String?,
    private val getStatus: GetRampStatusUseCase,
    private val quoteTopUp: QuoteTopUpUseCase,
    private val buy: BuyWithMpesaUseCase,
    private val getOrder: GetRampOrderUseCase,
    private val cancelOrder: CancelRampOrderUseCase,
    observeUpdates: ObserveRampUpdatesUseCase,
    private val ownNumber: GetOwnMpesaNumberUseCase,
    /** The account's verified number, masked (the security overview). */
    maskedPhone: Flow<String?> = flowOf(null),
    online: Flow<Boolean> = flowOf(true),
    private val networks: WalletNetworks = WalletNetworks.Single,
    private val now: () -> Long = ::nowMillis,
    private val pollMillis: Long = PollMillis,
) : ViewModel() {
    /** The wallet network the whole flow is for, fixed when it opens: the quote, the prompt and the order are all its. */
    private var pinned: WalletNetwork = networks.current()
    private suspend fun <T> onPin(block: suspend () -> T): T = withContext(PinnedNetwork(pinned)) { block() }

    private val local = MutableStateFlow(AddMoneyUiState())
    private val pin = MutableStateFlow(pinned)

    /** One key per ask, the same on a retry that got no answer; a new one once the server has answered. */
    private var quoteKey = newIdempotencyKey()
    private var buyKey = newIdempotencyKey()
    private var cancelKey = newIdempotencyKey()

    /** The full number when this phone holds it; never put in the state, never logged. */
    private var savedPhone: String? = null
    private var quoteDeadline = 0L
    private var countdown: Job? = null
    private var polling: Job? = null
    /** The order this screen was opened on has been read once; "Try Again" after it starts a new top-up. */
    private var opened = false

    val state: StateFlow<AddMoneyUiState> = combine(local, online, maskedPhone, combine(pin, networks.state) { on, all -> all.known && on == WalletNetwork.TESTNET }) { ui, isOnline, masked, testnet ->
        ui.copy(online = isOnline, maskedPhone = ui.order?.maskedPhone?.takeIf { it.isNotBlank() } ?: masked, testnet = testnet)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AddMoneyUiState())

    init {
        // Frames for the order on screen, live and replayed; kept for the life of the screen, because an order that
        // timed out can still move (a payment that landed late).
        viewModelScope.launch {
            observeUpdates().collect { update ->
                val current = local.value.order ?: return@collect
                if (update.orderId != current.orderId && update.orderId != RampOrderUpdate.ReloadAll) return@collect
                // A finished top-up is final: a replayed older frame never walks it back, even for a moment.
                if (update.orderId == current.orderId && update.state != RampOrderState.Unknown && current.state != RampOrderState.Completed) {
                    show(current.copy(state = update.state, failureReason = update.failureReason ?: current.failureReason))
                }
                // The frame has no fee or hash: the order itself is the truth.
                check(current.orderId)
            }
        }
        viewModelScope.launch { savedPhone = ownNumber(); local.update { it.copy(needsPhone = savedPhone == null) } }
        load()
    }

    /** R1 (or the order, when opened on one). Also "Try Again" on the load-failed and blocked states. */
    fun load() {
        local.update { it.copy(step = AddMoneyStep.Loading, block = null, failure = null, orderUnread = false) }
        viewModelScope.launch {
            if (orderId != null && !opened) { opened = true; open(orderId); return@launch }
            when (val result = onPin { getStatus() }) {
                is AppResult.Success -> onStatus(result.value)
                is AppResult.Failure -> blockFor(result.error)?.let { b -> local.update { it.copy(step = AddMoneyStep.Blocked, block = b) } }
                    ?: local.update { it.copy(step = AddMoneyStep.LoadFailed, failure = failureOf(result.error)) }
            }
        }
    }

    private suspend fun onStatus(status: RampStatus) {
        val open = status.openOrderId
        when {
            // One order at a time: an open one is where the flow resumes.
            open != null -> { local.update { it.copy(status = status) }; open(open) }
            status.kyc == RampKyc.None -> local.update { it.copy(status = status, step = AddMoneyStep.Blocked, block = RampBlock.Verify) }
            !status.accountActive -> local.update { it.copy(status = status, step = AddMoneyStep.Blocked, block = RampBlock.Restricted) }
            !status.merchantOnline || status.blockchains.isEmpty() -> local.update { it.copy(status = status, step = AddMoneyStep.Blocked, block = RampBlock.NoMerchant) }
            else -> local.update {
                it.copy(status = status, step = AddMoneyStep.Amount, block = null, order = null, everPaid = false, quote = null, stale = false, cancelNote = null,
                    blockchain = it.blockchain?.takeIf { b -> b in status.blockchains } ?: status.blockchains.first())
            }
        }
    }

    private suspend fun open(id: String) {
        when (val result = onPin { getOrder(id) }) {
            is AppResult.Success -> show(result.value)
            is AppResult.Failure -> local.update { it.copy(step = AddMoneyStep.LoadFailed, failure = orderUnread(result.error), orderUnread = true) }
        }
    }

    private fun show(order: RampOrder) {
        // An order says which network it is on: everything after is for that one.
        order.network?.let { pinned = it; pin.value = it }
        local.update { it.copy(step = AddMoneyStep.Order, order = order, everPaid = it.everPaid || order.state.paid, placing = false, failure = null, stale = false) }
        // Followed until it settles; an order that had ended and moved again (paid after its time ran out) is followed anew.
        if (!order.state.settled && polling?.isActive != true) follow()
    }

    fun onAmount(text: String) {
        // Never more digits than the largest amount allowed has, so the number always fits its line.
        val most = local.value.limits?.maxOrder?.amountMinor?.trimStart('0')?.length?.minus(RampMoney.FiatDecimals)?.coerceIn(1, MaxDigits) ?: MaxDigits
        val digits = text.filter { it.isDigit() }.trimStart('0').take(most)
        if (digits != local.value.amountText) quoteKey = newIdempotencyKey()
        local.update { it.copy(amountText = digits, wholeOnlyHint = text.any { c -> !c.isDigit() && c != ',' && c != ' ' }, failure = null) }
    }

    fun onChain(blockchain: String) {
        if (blockchain != local.value.blockchain) quoteKey = newIdempotencyKey()
        local.update { it.copy(blockchain = blockchain, failure = null) }
    }

    /** R2 from the amount screen. */
    fun onContinue() { if (state.value.canQuote) askPrice(repriced = false) }

    /** R2 again from the review, once the price has run out. */
    fun onNewPrice() {
        val s = state.value
        if (s.step == AddMoneyStep.Review && s.online && !s.quoting && !s.placing) askPrice(repriced = false)
    }

    private fun askPrice(repriced: Boolean) {
        val s = local.value
        val minor = s.amountMinor ?: return
        val chain = s.blockchain ?: return
        local.update { it.copy(quoting = true, failure = null) }
        viewModelScope.launch {
            when (val result = onPin { quoteTopUp(chain, minor, quoteKey) }) {
                is AppResult.Success -> {
                    quoteKey = newIdempotencyKey()
                    buyKey = newIdempotencyKey() // a new quote is a new purchase
                    hold(result.value)
                    local.update { it.copy(step = AddMoneyStep.Review, quoting = false, quote = result.value, secondsLeft = secondsLeft(), repriced = repriced, phoneError = null) }
                }
                is AppResult.Failure -> {
                    if (refused(result.error)) quoteKey = newIdempotencyKey()
                    val block = blockFor(result.error)
                    local.update {
                        if (block != null) it.copy(step = AddMoneyStep.Blocked, block = block, quoting = false, quote = null)
                        // Back on the amount with the reason: a price that couldn't be had is never left looking live.
                        else it.copy(step = AddMoneyStep.Amount, quoting = false, quote = null, failure = failureOf(result.error))
                    }
                }
            }
        }
    }

    /** Starts the price's clock from the server's deadline, bounded so a wrong phone clock never shows more than a minute. */
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
        local.update { it.copy(step = AddMoneyStep.Amount, quote = null, failure = null, repriced = false) }
    }

    fun onPhone(text: String) = local.update { it.copy(phoneText = text.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20), phoneError = null) }

    /** R3: the M-Pesa prompt. The same key on a retry that got no answer, so one quote never prompts twice. */
    fun onConfirm() {
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
            when (val result = onPin { buy(quote.quoteId, phone, buyKey) }) {
                is AppResult.Success -> {
                    savedPhone = phone
                    countdown?.cancel()
                    local.update { it.copy(needsPhone = false, phoneText = "") }
                    show(result.value)
                }
                is AppResult.Failure -> onBuyFailed(result.error)
            }
        }
    }

    private suspend fun onBuyFailed(error: AppError) {
        val code = (error as? AppError.Api)?.code
        // The server answered: this attempt is over, and the next one is a new action.
        if (refused(error)) buyKey = newIdempotencyKey()
        val block = blockFor(error)
        when {
            block != null -> local.update { it.copy(step = AddMoneyStep.Blocked, block = block, placing = false) }
            // The price ran out between the review and the tap: a new one is fetched and shown, never bought unseen.
            code == RampCodes.QuoteExpired || code == RampCodes.QuoteNotFound -> { local.update { it.copy(placing = false) }; askPrice(repriced = true) }
            // An order of theirs is already under way (another phone, or an earlier try that did land): resume it.
            code == RampCodes.OrderInProgress -> { local.update { it.copy(placing = false) }; load() }
            code == RampCodes.PhoneNotYours -> {
                savedPhone = null
                local.update { it.copy(placing = false, needsPhone = true, phoneError = ErrorMessages.forError(error)) }
            }
            code == RampCodes.TooManyPrompts -> local.update {
                val wait = (error as? AppError.Api)?.retryAfterSeconds?.let { s -> " Try again in ${waitText(s)}." }.orEmpty()
                it.copy(placing = false, failure = AddMoneyFailure("Too many M-Pesa requests in a short time.$wait Nothing was taken.", ErrorMessages.reference(error)))
            }
            // No answer: it isn't known whether the request went. Said as it is; the retry can't prompt twice.
            !refused(error) -> local.update {
                it.copy(placing = false, failure = AddMoneyFailure(
                    if (error == AppError.Network || error is AppError.Api) "We couldn't reach Circaso, so no M-Pesa request was confirmed. Nothing is taken unless you approve a request on your phone. Try again: it never sends a second request for this price."
                    else ErrorMessages.SomethingWrong + " Nothing is taken unless you approve a request on your phone.",
                    ErrorMessages.reference(error),
                ))
            }
            else -> local.update { it.copy(placing = false, failure = failureOf(error)) }
        }
    }

    /** Follows the order on screen: a read every few seconds until it settles (frames arrive beside it). */
    private fun follow() {
        polling?.cancel()
        polling = viewModelScope.launch {
            while (true) {
                delay(pollMillis)
                val order = local.value.order ?: break
                // Settled while this waited (a frame said so): nothing more to ask.
                if (order.state.settled) break
                if (state.value.online) check(order.orderId)
            }
        }
    }

    private suspend fun check(id: String) {
        when (val result = onPin { getOrder(id) }) {
            is AppResult.Success -> if (local.value.order?.orderId == id) show(result.value)
            is AppResult.Failure -> local.update { if (it.order?.orderId == id && it.order?.state?.settled == false) it.copy(stale = true) else it }
        }
    }

    /** R6. Only offered before the payment; a prompt still open on the phone can't be cancelled from here. */
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
                            RampCodes.NotCancellable -> "The M-Pesa request is still open on your phone. Dismiss it there and this ends by itself."
                            else -> ErrorMessages.forError(result.error)
                        })
                    }
                    // It may have moved while the cancel was on its way.
                    if (code == RampCodes.NotCancellable) check(order.orderId)
                }
            }
        }
    }

    /** After an order that didn't go through: back to the amount (R1 first, in case something changed). */
    fun onAgain() {
        polling?.cancel()
        local.update { it.copy(order = null, everPaid = false, quote = null, cancelNote = null, stale = false) }
        load()
    }

    fun onErrorShown() = local.update { it.copy(error = null) }

    private fun blockFor(error: AppError): RampBlock? = when ((error as? AppError.Api)?.code) {
        RampCodes.Unavailable -> RampBlock.Unavailable
        RampCodes.KycRequired -> RampBlock.Verify
        RampCodes.AccountRestricted -> RampBlock.Restricted
        else -> null
    }

    /**
     * The server gave a definite no (a 4xx that isn't "the first attempt is still running"): that attempt is over and
     * the next one is a new action with a new key. No answer, a 5xx (`service.unavailable`) and `request.in_progress`
     * keep the key, so a retry can never make a second request.
     */
    private fun refused(error: AppError): Boolean = (error as? AppError.Api)?.let { it.httpStatus in 400..499 && it.code != "request.in_progress" } == true

    /** An order that couldn't be read: it exists and may be paid, so nothing here says otherwise. */
    private fun orderUnread(error: AppError) = AddMoneyFailure(
        if ((error as? AppError.Api)?.code == RampCodes.OrderNotFound) ErrorMessages.forError(error)
        else "We couldn't load this top-up just now. It isn't affected: if you approved the M-Pesa request, your USDC still arrives.",
        ErrorMessages.reference(error),
    )

    private fun failureOf(error: AppError) = AddMoneyFailure(
        when {
            error == AppError.Network -> "Adding money needs a connection. No M-Pesa request was sent."
            (error as? AppError.Api)?.code == RampCodes.LimitExceeded -> local.value.limits?.let { "That would take you over your limit of ${it.daily.text} a day." } ?: ErrorMessages.forError(error)
            else -> ErrorMessages.forError(error)
        },
        ErrorMessages.reference(error),
    )

    companion object {
        const val PollMillis = 4_000L
        private const val MaxDigits = 9

        internal fun waitText(seconds: Int): String = if (seconds < 90) "a minute" else "${(seconds + 59) / 60} minutes"
    }
}
