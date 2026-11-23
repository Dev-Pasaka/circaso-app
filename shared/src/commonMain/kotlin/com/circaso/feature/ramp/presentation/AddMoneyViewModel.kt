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
import com.circaso.feature.ramp.domain.GetKycUseCase
import com.circaso.feature.ramp.domain.GetOwnMpesaNumberUseCase
import com.circaso.feature.ramp.domain.GetRampOrderUseCase
import com.circaso.feature.ramp.domain.GetRampStatusUseCase
import com.circaso.feature.ramp.domain.KycSession
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.ObserveKycUpdatesUseCase
import com.circaso.feature.ramp.domain.ObserveRampUpdatesUseCase
import com.circaso.feature.ramp.domain.QuoteTopUpUseCase
import com.circaso.feature.ramp.domain.RampAmount
import com.circaso.feature.ramp.domain.RampCodes
import com.circaso.feature.ramp.domain.RampMoney
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.domain.RampOrderUpdate
import com.circaso.feature.ramp.domain.RampQuote
import com.circaso.feature.ramp.domain.RampStatus
import com.circaso.feature.ramp.domain.RampVerification
import com.circaso.feature.ramp.domain.RunIdentityCheckUseCase
import com.circaso.feature.ramp.domain.StartKycUseCase
import com.circaso.feature.ramp.domain.VerifierOutcome
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.PinnedNetwork
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
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
import kotlinx.coroutines.flow.map
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
    /** Identity verification stands in the way (design E3 and its states): what to show is in [VerifyUi]. */
    Verify,
    /** A bar on the account or its identity (R1's `blocked`, `ramp.blocked`): Support is the only way on. */
    Barred,
    /** The account can't trade. */
    Restricted,
    /** No partner is online to sell right now (or, cashing out, to buy). */
    NoMerchant,
    /** Cashing out: the wallet isn't on the sale's chain (`ramp.wallet_required`). */
    NoWallet,
}

/** Something that went wrong and stays on screen until dealt with: the words, and what to quote to support. */
data class AddMoneyFailure(
    val message: String, val reference: String?,
    /** Nothing failed for the person: a note about how to carry on, said without alarm. */
    val calm: Boolean = false,
)

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
    /** The last price had in this visit, kept after going back to the amount: what [estimate] is worked out from. */
    val lastQuote: RampQuote? = null,
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
    /** What is loading is an order (a push, or the open one), so the first paint is an order's shape, not the amount's. */
    val loadingOrder: Boolean = false,
    /** The load that failed was of an order that exists (a push, or the open order): the words never say nothing was sent. */
    val orderUnread: Boolean = false,
    /** Identity verification: only shown while [block] is [RampBlock.Verify]. */
    val verify: VerifyUi = VerifyUi(),
    /**
     * The order this flow opened on is a sale (a `ramp.order` push, or R1's open order): Cash Out shows it, and this
     * screen hands over to it at once.
     */
    val handOff: String? = null,
    /**
     * This order's USDC is in the wallet and its success hasn't been heard on this phone yet (watched as it landed, or
     * opened later from a push or the open order): the page plays the card settling with the `Confirm` haptic and calls
     * [AddMoneyViewModel.onLanded] on that frame, which rings the chime. Null once rung, and for every other order.
     */
    val celebrate: String? = null,
    /** This completed order's landing was heard on this phone before: its card is simply there, no settle, no chime. */
    val quietLanding: String? = null,
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
    /**
     * Roughly what the typed amount buys, from the last price had here ([lastQuote]); null until there has been one,
     * because R1 carries no rate. The fee is a share of what is paid, so the amount scales in proportion. Only ever
     * shown with "About": the exact figure is the review's.
     */
    val estimate: RampAmount?
        get() {
            val last = lastQuote?.takeIf { it.blockchain == blockchain } ?: return null
            val typed = amountMinor?.takeIf { amountError == null }?.toLongOrNull() ?: return null
            val paid = last.pay.amountMinor.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val got = last.receive.amountMinor.toLongOrNull()?.takeIf { it > 0 } ?: return null
            if (got > Long.MAX_VALUE / typed) return null
            return RampAmount(last.receive.unit, (got * typed / paid).toString(), "")
        }
    /** The identity check may be started, continued or tried again right now (R0). **Online-only.** */
    val canVerify get() = online && step == AddMoneyStep.Blocked && block == RampBlock.Verify && verify.startable
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
 * Design E2, on the ramp's routes (R0–R6): the gates, identity verification once where the server asks for it (E3),
 * the amount in whole shillings, a price held 60 seconds, the M-Pesa prompt on the person's own verified number, then
 * the order followed live until the USDC is in the wallet.
 *
 * **Money: never queued, never optimistic.** Nothing is asked of the server while offline (the buttons say it needs a
 * connection), and the screen only ever shows what the server last answered. [orderId] opens straight on an order
 * (a push, or R1's open order).
 *
 * **Identity verification is online-only** and the server's alone to decide: R1's `verification` says what to show,
 * R0 starts or continues the check, Didit's SDK runs it ([runCheck]), and whatever the SDK reports the result is read
 * from `GET /v1/ramp/kyc` (and heard on `kyc.updated`). Nothing about it is kept on the phone, and neither the
 * session's token nor its link is ever logged or put in the state.
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
    private val startKyc: StartKycUseCase,
    private val getKyc: GetKycUseCase,
    observeKyc: ObserveKycUpdatesUseCase,
    private val runCheck: RunIdentityCheckUseCase,
    /** The account's verified number, masked (the security overview). */
    maskedPhone: Flow<String?> = flowOf(null),
    online: Flow<Boolean> = flowOf(true),
    private val networks: WalletNetworks = WalletNetworks.Single,
    private val now: () -> Long = ::nowMillis,
    private val pollMillis: Long = PollMillis,
    /** The network a `ramp.kyc` push was about: the flow opens on it whatever the Wallet tab shows. */
    network: WalletNetwork? = null,
    /** How long "You're verified" stays before the flow goes on to the amount. */
    private val verifiedHoldMillis: Long = VerifiedHoldMillis,
    /** The money sounds: the chime when a top-up lands, once per order, if You → Money sound is on. */
    private val sounds: com.circaso.core.platform.MoneySounds? = null,
) : ViewModel() {
    /** The wallet network the whole flow is for, fixed when it opens: the quote, the prompt and the order are all its. */
    private var pinned: WalletNetwork = network ?: networks.current()
    private suspend fun <T> onPin(block: suspend () -> T): T = withContext(PinnedNetwork(pinned)) { block() }

    private val local = MutableStateFlow(AddMoneyUiState())
    private val pin = MutableStateFlow(pinned)

    /** One key per ask, the same on a retry that got no answer; a new one once the server has answered. */
    private var quoteKey = newIdempotencyKey()
    private var buyKey = newIdempotencyKey()
    private var cancelKey = newIdempotencyKey()

    /**
     * Identity verification itself ([IdentityCheck], shared with the profile's Verification screen). This flow is its
     * host: the check's state lives in [AddMoneyUiState.verify], and what happens when it ends is decided here.
     */
    private val check = IdentityCheck(
        viewModelScope, startKyc, getKyc, runCheck, network = { pinned }, now = now,
        host = object : IdentityCheck.Host {
            override val verify get() = local.value.verify
            override fun update(change: (VerifyUi) -> VerifyUi) = local.update { it.copy(verify = change(it.verify)) }
            override val showing get() = onVerifyPage()
            override val online get() = state.value.online
            override val canVerify get() = state.value.canVerify
            // Staff switched the requirement off while the person was here: straight on, with nothing to celebrate.
            override fun onNotRequired(now: RampVerification): Boolean { load(); return true }
            override fun onVerified(now: RampVerification) = this@AddMoneyViewModel.onVerified(now)
            override fun onBlocked(block: RampBlock) = local.update { if (it.step == AddMoneyStep.Blocked && it.block == RampBlock.Verify) it.copy(block = block, verify = VerifyUi()) else it }
        },
    )

    /** The full number when this phone holds it; never put in the state, never logged. */
    private var savedPhone: String? = null
    private var quoteDeadline = 0L
    private var countdown: Job? = null
    /** The price being asked for; dropped when the person goes back, so its answer can't carry them forward again. */
    private var pricing: Job? = null
    private var polling: Job? = null
    /** The order this screen was opened on has been read once; "Try Again" after it starts a new top-up. */
    private var opened = false
    /** The completed orders whose chime has been decided on (to ring or not): asked once each. */
    private val landingsDecided = mutableSetOf<String>()

    val state: StateFlow<AddMoneyUiState> = combine(local, online, maskedPhone, combine(pin, networks.state) { on, all -> all.known && on == WalletNetwork.TESTNET }) { ui, isOnline, masked, testnet ->
        ui.copy(online = isOnline, maskedPhone = ui.order?.maskedPhone?.takeIf { it.isNotBlank() } ?: masked, testnet = testnet)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AddMoneyUiState())

    init {
        // Frames for the order on screen, live and replayed; kept for the life of the screen, because an order that
        // timed out can still move (a payment that landed late).
        viewModelScope.launch {
            observeUpdates().collect { update ->
                // The socket lost its place (`resync`): a check being waited on is read again too.
                if (update.orderId == RampOrderUpdate.ReloadAll) check.onResync()
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
        // The identity check moved (`kyc.updated`, live or replayed): only this flow's network is taken.
        viewModelScope.launch { observeKyc().collect { moved -> if (moved.network == null || moved.network == pinned) check.show(moved.verification) } }
        viewModelScope.launch { savedPhone = ownNumber(); local.update { it.copy(needsPhone = savedPhone == null) } }
        // The chime is loaded while the person is still choosing an amount: a landing never waits on it.
        sounds?.prepare()
        // The connection is back: a check that was waiting on it is read at once, not at the next tick.
        viewModelScope.launch { state.map { it.online }.distinctUntilChanged().drop(1).collect { back -> if (back) check.readIfItMayHaveMoved() } }
        load()
    }

    /** R1 (or the order, when opened on one). Also "Try Again" on the load-failed and blocked states. */
    fun load() = load(refusedForIdentity = null)

    /**
     * [refusedForIdentity] is a `ramp.kyc_required` from R2 or R3: R1 is read to show where the check stands (never a
     * dead end). Should R1 not agree that anything is needed, the refusal's own words are said on the amount.
     */
    private fun load(refusedForIdentity: AppError?) {
        val openingOrder = orderId != null && !opened
        check.stop()
        local.update { it.copy(step = AddMoneyStep.Loading, block = null, failure = null, orderUnread = false, loadingOrder = openingOrder, verify = VerifyUi()) }
        viewModelScope.launch {
            if (orderId != null && !opened) { opened = true; open(orderId); return@launch }
            when (val result = onPin { getStatus() }) {
                is AppResult.Success -> {
                    onStatus(result.value)
                    if (refusedForIdentity != null) local.update { if (it.step == AddMoneyStep.Amount) it.copy(failure = failureOf(refusedForIdentity, NoPrice)) else it }
                }
                is AppResult.Failure -> blockFor(result.error)?.let { b -> local.update { it.copy(step = AddMoneyStep.Blocked, block = b) } }
                    ?: local.update { it.copy(step = AddMoneyStep.LoadFailed, failure = failureOf(result.error, CouldNotOpen)) }
            }
        }
    }

    private suspend fun onStatus(status: RampStatus) {
        val open = status.openOrderId
        when {
            // One order at a time: an open one is where the flow resumes.
            open != null -> { local.update { it.copy(status = status, loadingOrder = true) }; open(open) }
            // A bar wins over everything else: verifying is never offered behind it.
            status.blocked -> local.update { it.copy(status = status, step = AddMoneyStep.Blocked, block = RampBlock.Barred) }
            !status.accountActive -> local.update { it.copy(status = status, step = AddMoneyStep.Blocked, block = RampBlock.Restricted) }
            // Asked once, here, and only where the server says it is required.
            !status.verification.cleared -> onNeedsVerifying(status)
            !status.merchantOnline || status.blockchains.isEmpty() -> local.update { it.copy(status = status, step = AddMoneyStep.Blocked, block = RampBlock.NoMerchant) }
            else -> local.update {
                it.copy(status = status, step = AddMoneyStep.Amount, block = null, order = null, everPaid = false, quote = null, stale = false, cancelNote = null,
                    blockchain = it.blockchain?.takeIf { b -> b in status.blockchains } ?: status.blockchains.first(), verify = VerifyUi())
            }
        }
    }

    // ---- identity verification (R0, design E3) ----------------------------------------------------------------------

    private fun onVerifyPage(): Boolean = local.value.let { it.step == AddMoneyStep.Blocked && it.block == RampBlock.Verify }

    /**
     * R1 says identity stands in the way. A check that was open or in review when the person last left may have moved
     * since: R1 only repeats what the server last heard, the read asks Didit, so it is read before anything is shown
     * and the page opens on the right state (coming back after finishing lands on the result, not on "Continue").
     */
    private suspend fun onNeedsVerifying(status: RampStatus) {
        val heard = status.verification
        val unsettled = heard.status == KycStatus.Pending || heard.status == KycStatus.InReview
        val now = if (unsettled) (onPin { getKyc() } as? AppResult.Success)?.value ?: heard else heard
        local.update { it.copy(status = status, step = AddMoneyStep.Blocked, block = RampBlock.Verify, verify = VerifyUi(verification = heard)) }
        check.show(now)
    }

    /**
     * Verified in front of the person: "You're verified" for a moment, while R1 is read behind it, then straight on
     * to the amount they came for. Nothing to tap.
     */
    private fun onVerified(now: RampVerification) {
        viewModelScope.launch {
            val status = async { onPin { getStatus() } }
            delay(verifiedHoldMillis)
            when (val result = status.await()) {
                // R1 may be a beat behind the read that said "verified": the read is the newer word.
                is AppResult.Success -> onStatus(if (result.value.verification.cleared) result.value else result.value.copy(verification = now))
                is AppResult.Failure -> blockFor(result.error)?.let { b -> local.update { it.copy(step = AddMoneyStep.Blocked, block = b, verify = VerifyUi()) } }
                    ?: local.update { it.copy(step = AddMoneyStep.LoadFailed, failure = failureOf(result.error, CouldNotOpen), verify = VerifyUi()) }
            }
        }
    }

    /** The verify pages' one button: "Start Verification", "Continue", "Try Again", "Verify Again" ([IdentityCheck.onVerify]). */
    fun onVerify() = check.onVerify()

    /** "Open Settings" on the camera note: the person is on their way to turn the camera on. */
    fun onSettingsOpened() = check.onSettingsOpened()

    /**
     * The app is in front again (back from Settings, from the hosted page, from anywhere): a check that may have moved
     * while it was away is read again, so the page is never left showing yesterday's state.
     */
    fun onResumed() = check.onResumed()

    /** The app left the front: a check being waited on isn't read behind the person's back. */
    fun onPaused() = check.onPaused()

    private suspend fun open(id: String) {
        when (val result = onPin { getOrder(id) }) {
            is AppResult.Success -> show(result.value)
            is AppResult.Failure -> local.update { it.copy(step = AddMoneyStep.LoadFailed, failure = orderUnread(result.error), orderUnread = true) }
        }
    }

    private fun show(order: RampOrder) {
        // A sale is Cash Out's to show, with its own words: handed over, never drawn as a top-up.
        if (order.sale) { polling?.cancel(); local.update { it.copy(handOff = order.orderId, loadingOrder = true, step = AddMoneyStep.Loading) }; return }
        // An order says which network it is on: everything after is for that one.
        order.network?.let { pinned = it; pin.value = it }
        local.update {
            it.copy(step = AddMoneyStep.Order, order = order, everPaid = it.everPaid || order.state.paid, placing = false, failure = null, stale = false,
                celebrate = it.celebrate?.takeIf { id -> id == order.orderId && order.state == RampOrderState.Completed },
                quietLanding = it.quietLanding?.takeIf { id -> id == order.orderId })
        }
        // Followed until it settles; an order that had ended and moved again (paid after its time ran out) is followed anew.
        if (!order.state.settled && polling?.isActive != true) follow()
        listenFor(order)
    }

    /**
     * The sounds for the order on screen: while it is under way its USDC is the chime's, not the coins'; once it is
     * completed, whether its landing is still to be heard on this phone ([AddMoneyUiState.celebrate]). An order that
     * ended any other way never rings.
     */
    private fun listenFor(order: RampOrder) {
        val id = order.orderId
        when {
            order.state == RampOrderState.Completed -> if (landingsDecided.add(id)) viewModelScope.launch {
                // Decided (and the sound readied) now, so the chime can ring on the very frame the card settles.
                val fresh = sounds?.armLanding(id) ?: true
                if (!fresh) sounds?.unfollow(id)
                local.update {
                    if (it.order?.orderId != id || it.order.state != RampOrderState.Completed) it
                    else if (fresh) it.copy(celebrate = id) else it.copy(quietLanding = id)
                }
            }
            // Only paid money is on its way: before that, incoming money is someone else's and plays the coins.
            order.state.paid && !order.state.settled -> sounds?.follow(id)
            else -> sounds?.unfollow(id)
        }
    }

    /**
     * The page's card has settled on the USDC that landed ([AddMoneyUiState.celebrate]): the chime, in time with it and
     * the haptic, once per order, if the money sound is on. Anything else (a failure, an order already heard) is quiet.
     */
    fun onLanded() {
        val s = local.value
        val order = s.order ?: return
        if (order.state != RampOrderState.Completed || s.celebrate != order.orderId) return
        local.update { it.copy(celebrate = null) }
        val sounds = sounds ?: return
        // The sound first, on this frame; then it is written down as heard.
        sounds.ringLanding(order.orderId)
        viewModelScope.launch {
            sounds.markHeard(order.orderId)
            sounds.unfollow(order.orderId)
        }
    }

    override fun onCleared() {
        local.value.order?.let { sounds?.unfollow(it.orderId) }
        super.onCleared()
    }

    fun onAmount(text: String) {
        // A price is being asked for this amount: it stays as it is until the answer is in (a quick amount can't overtake it).
        if (local.value.quoting) return
        // Never more digits than the largest amount allowed has, so the number always fits its line.
        val most = local.value.limits?.maxOrder?.amountMinor?.trimStart('0')?.length?.minus(RampMoney.FiatDecimals)?.coerceIn(1, MaxDigits) ?: MaxDigits
        val digits = text.filter { it.isDigit() }.trimStart('0').take(most)
        if (digits != local.value.amountText) quoteKey = newIdempotencyKey()
        local.update { it.copy(amountText = digits, wholeOnlyHint = text.any { c -> !c.isDigit() && c != ',' && c != ' ' }, failure = null) }
    }

    fun onChain(blockchain: String) {
        if (local.value.quoting) return
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
        pricing?.cancel()
        pricing = viewModelScope.launch {
            when (val result = onPin { quoteTopUp(chain, minor, quoteKey) }) {
                is AppResult.Success -> {
                    quoteKey = newIdempotencyKey()
                    buyKey = newIdempotencyKey() // a new quote is a new purchase
                    hold(result.value)
                    local.update { it.copy(step = AddMoneyStep.Review, quoting = false, quote = result.value, lastQuote = result.value, secondsLeft = secondsLeft(), repriced = repriced, phoneError = null) }
                }
                is AppResult.Failure -> {
                    if (refused(result.error)) quoteKey = newIdempotencyKey()
                    // Not verified on this network: R1 says where the check stands, and that is what shows.
                    if ((result.error as? AppError.Api)?.code == RampCodes.KycRequired) { local.update { it.copy(quoting = false, quote = null) }; load(result.error); return@launch }
                    val block = blockFor(result.error)
                    local.update {
                        if (block != null) it.copy(step = AddMoneyStep.Blocked, block = block, quoting = false, quote = null)
                        // Back on the amount with the reason: a price that couldn't be had is never left looking live.
                        else it.copy(step = AddMoneyStep.Amount, quoting = false, quote = null, failure = failureOf(result.error, NoPrice))
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
        // A new price still on its way is let go (its key is kept: the same ask, if it is made again).
        pricing?.cancel()
        local.update { it.copy(step = AddMoneyStep.Amount, quote = null, quoting = false, failure = null, repriced = false) }
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
            // Not verified on this network: R1 says where the check stands, and that is what shows.
            code == RampCodes.KycRequired -> { countdown?.cancel(); local.update { it.copy(placing = false, quote = null) }; load(error) }
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
                    if (error == AppError.Network) "We couldn't reach Circaso, so no M-Pesa request was confirmed. Nothing is taken unless you approve a request on your phone. Try again: it never sends a second request for this price."
                    // The server (or the seller behind it) couldn't take it just now. It isn't known for certain that
                    // nothing went out, so the words stop short of "nothing was taken"; never "something went wrong on our side".
                    else "We couldn't start this payment right now. Nothing is taken unless you approve an M-Pesa request on your phone. Please try again in a moment.",
                    ErrorMessages.reference(error),
                ))
            }
            // A definite no, `ramp.merchant_unavailable` among them: its own words, and nothing was asked of M-Pesa.
            else -> local.update { it.copy(placing = false, failure = failureOf(error, CouldNotStart)) }
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
        local.value.order?.let { sounds?.unfollow(it.orderId) }
        local.update { it.copy(order = null, everPaid = false, quote = null, cancelNote = null, stale = false, celebrate = null, quietLanding = null) }
        load()
    }

    fun onErrorShown() = local.update { it.copy(error = null) }

    private fun blockFor(error: AppError): RampBlock? = IdentityCheck.blockFor(error)

    /** A definite no from the server: that attempt is over, and the next one is a new action with a new key ([IdentityCheck.refused]). */
    private fun refused(error: AppError): Boolean = IdentityCheck.refused(error)

    /** An order that couldn't be read: it exists and may be paid, so nothing here says otherwise. */
    private fun orderUnread(error: AppError) = AddMoneyFailure(
        if ((error as? AppError.Api)?.code == RampCodes.OrderNotFound) ErrorMessages.forError(error)
        else "We couldn't load this top-up just now. It isn't affected: if you approved the M-Pesa request, your USDC still arrives.",
        ErrorMessages.reference(error),
    )

    /**
     * The words for a failure. A code with no words of its own (an upstream error, a newer server's) gets [unknown],
     * which says what didn't happen and what to do next: this flow never says "something went wrong on our side".
     */
    private fun failureOf(error: AppError, unknown: String) = AddMoneyFailure(
        when {
            error == AppError.Network -> "Adding money needs a connection. No M-Pesa request was sent."
            (error as? AppError.Api)?.code == RampCodes.LimitExceeded -> local.value.limits?.let { "That would take you over your limit of ${it.daily.text} a day." } ?: ErrorMessages.forError(error)
            else -> ErrorMessages.forError(error).takeIf { it != ErrorMessages.SomethingWrong } ?: unknown
        },
        ErrorMessages.reference(error),
    )

    companion object {
        const val PollMillis = 4_000L
        private const val MaxDigits = 9

        // What didn't happen, and what to do next, when the server's answer has no words of its own.
        internal const val CouldNotOpen = "We couldn't open Add Money right now. No M-Pesa request was sent. Please try again in a moment."
        internal const val NoPrice = "We couldn't get a price right now. No M-Pesa request was sent. Please try again in a moment."
        internal const val CouldNotStart = "We couldn't start this payment right now. Nothing was taken. Please try again in a moment."

        // Identity verification's own paces and words live with the check ([IdentityCheck]); named here as they always were.
        const val CheckMillis = IdentityCheck.CheckMillis
        const val ReviewMillis = IdentityCheck.ReviewMillis
        const val HostedGraceMillis = IdentityCheck.HostedGraceMillis
        const val HostedLingerMillis = IdentityCheck.HostedLingerMillis
        internal val WakeBackoffMillis get() = IdentityCheck.WakeBackoffMillis
        /** "You're verified" stays this long: time to read it and feel it, never a wait. */
        const val VerifiedHoldMillis = 1_800L
        internal const val VerifyNeedsConnection = IdentityCheck.VerifyNeedsConnection
        internal const val CouldNotStartCheck = IdentityCheck.CouldNotStartCheck
        internal const val SdkStopped = IdentityCheck.SdkStopped
        internal const val CouldNotShowCheck = IdentityCheck.CouldNotShowCheck

        internal fun waitText(seconds: Int): String = if (seconds < 90) "a minute" else "${(seconds + 59) / 60} minutes"
    }
}
