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
    /** No partner is online to sell right now. */
    NoMerchant,
}

/** Something that went wrong and stays on screen until dealt with: the words, and what to quote to support. */
data class AddMoneyFailure(
    val message: String, val reference: String?,
    /** Nothing failed for the person: a note about how to carry on, said without alarm. */
    val calm: Boolean = false,
)

/** What stopped a check from starting or running, other than what the server's verification itself says. */
enum class VerifyTrouble {
    /** Circaso may not use the camera: the SDK had nothing to work with. Settings is the way on. */
    CameraDenied,
    /** `ramp.kyc_unavailable` (502): Didit is out of reach. On our side; nothing was counted. */
    ProviderDown,
    /** `ramp.kyc_not_configured` (503): verification isn't set up on this network yet. On our side; no retry. */
    NotConfigured,
    /** `ramp.kyc_attempts_exceeded` (429): today's tries are used up. */
    TriesUsedUp,
}

/**
 * Identity verification as the screen has it (guide "Identity verification (KYC)"). [verification] is the server's
 * word and the only source of the state shown; the rest is what this phone is doing about it right now.
 */
data class VerifyUi(
    val verification: RampVerification? = null,
    /** R0 is being asked, or Didit's screens are up: the button is busy and can't be tapped twice. */
    val starting: Boolean = false,
    /** Everything was sent and the result isn't in yet: the waiting state, which resolves by itself. */
    val checking: Boolean = false,
    /** The wait for a result has outlasted "less than a minute": the words stop promising it. */
    val slow: Boolean = false,
    /**
     * Back from Didit's hosted page, where "finished" can't be known: the result is read for a short while, and
     * nothing is said to have been sent.
     */
    val returned: Boolean = false,
    /** The last read of the result got no answer: it is still being read, and the screen says so. */
    val stale: Boolean = false,
    val trouble: VerifyTrouble? = null,
    /** When [VerifyTrouble.TriesUsedUp] lifts (epoch ms), from the refusal's `retryAfterSeconds`. */
    val retryAt: Long? = null,
    /** What to quote to Support about [trouble]. */
    val reference: String? = null,
    /** A start that got nowhere for a passing reason (no connection, an answer with no words of its own). */
    val failure: AddMoneyFailure? = null,
    /** Just verified, in front of the person: the confirmation shows for a moment, then the flow goes on by itself. */
    val verified: Boolean = false,
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
    val canVerify get() = online && step == AddMoneyStep.Blocked && block == RampBlock.Verify && verify.verification?.canStart == true && !verify.starting && !verify.verified
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
    private var kycKey = newIdempotencyKey()

    /** R0, Didit's screens and the read after them: one at a time. */
    private var verifying: Job? = null
    /** The reads while a result is awaited (just sent, or in review). */
    private var kycWatch: Job? = null
    /** Wakes the screen when today's tries are back. */
    private var retryTimer: Job? = null
    /** Didit's screens are up: what arrives meanwhile is kept ([held]), and shown once they have closed. */
    private var sdkUp = false
    /** The newest word on the check while Didit's screens cover the page: R0's answer, then any frame since. */
    private var held: RampVerification? = null
    /** How many reads of the check have been asked for: the answer to the newest is the only one shown. */
    private var readsAsked = 0
    /** After the hosted page's short wait: the open check is still read now and then until here (epoch ms). */
    private var lingerUntil = 0L
    /** The SDK stopped for a reason of its own (or isn't in this build): the hosted page is used from then on. */
    private var useHostedPage = false
    /** The person was sent to Settings for the camera: coming back, the camera note has done its job. */
    private var sentToSettings = false
    /** After a hosted page there is no saying whether they finished: the wait for a result ends here (epoch ms). */
    private var checkingUntil: Long? = null

    /** The full number when this phone holds it; never put in the state, never logged. */
    private var savedPhone: String? = null
    private var quoteDeadline = 0L
    private var countdown: Job? = null
    /** The price being asked for; dropped when the person goes back, so its answer can't carry them forward again. */
    private var pricing: Job? = null
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
                // The socket lost its place (`resync`): a check being waited on is read again too.
                if (update.orderId == RampOrderUpdate.ReloadAll && onVerifyPage() && !sdkUp) { readKyc(); watchKyc() }
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
        viewModelScope.launch { observeKyc().collect { moved -> if (moved.network == null || moved.network == pinned) onVerification(moved.verification) } }
        viewModelScope.launch { savedPhone = ownNumber(); local.update { it.copy(needsPhone = savedPhone == null) } }
        // The connection is back: a check that was waiting on it is read at once, not at the next tick.
        viewModelScope.launch { state.map { it.online }.distinctUntilChanged().drop(1).collect { back -> if (back) readIfItMayHaveMoved() } }
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
        stopVerifying()
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

    /** Leaving the verify pages (a reload, a gate): nothing of the check keeps running on this phone. */
    private fun stopVerifying() {
        if (!sdkUp) verifying?.cancel()
        kycWatch?.cancel()
        retryTimer?.cancel()
    }

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
        onVerification(now)
    }

    /**
     * Where the check stands, from R0, the read or a frame: the one place the verify pages are moved from. While Didit's
     * screens are up it is only kept. Verified is final: a replayed older frame never walks it back.
     */
    private fun onVerification(now: RampVerification, keepTrouble: Boolean = false) {
        if (!onVerifyPage() || local.value.verify.verified) return
        // Nothing moves under Didit's screens: the page the person comes back to is decided once they have closed.
        if (sdkUp) { held = now; return }
        when {
            // Staff switched the requirement off while the person was here: straight on, with nothing to celebrate.
            !now.required -> load()
            now.status == KycStatus.Verified -> onVerified(now)
            else -> {
                local.update {
                    val before = it.verify
                    val moved = before.verification != now
                    // Still unfinished at the server: a wait for a result goes on.
                    val unfinished = now.status == KycStatus.Pending && now.reason == null
                    it.copy(verify = before.copy(
                        verification = now,
                        checking = before.checking && unfinished,
                        slow = before.slow && before.checking && unfinished,
                        returned = before.returned && unfinished,
                        stale = false,
                        trouble = before.trouble.takeIf { keepTrouble || !moved },
                        retryAt = before.retryAt.takeIf { keepTrouble || !moved },
                        reference = before.reference.takeIf { keepTrouble || !moved },
                        failure = before.failure.takeIf { keepTrouble || !moved },
                    ))
                }
                watchKyc()
                wakeWhenTriesAreBack()
            }
        }
    }

    /**
     * Verified in front of the person: "You're verified" for a moment, while R1 is read behind it, then straight on
     * to the amount they came for. Nothing to tap.
     */
    private fun onVerified(now: RampVerification) {
        kycWatch?.cancel()
        retryTimer?.cancel()
        local.update { it.copy(verify = VerifyUi(verification = now, verified = true)) }
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

    /**
     * The verify pages' one button: "Start Verification", "Continue Verification", "Try Again", "Verify Again". R0
     * starts the check or returns the one still open, then Didit's screens run it. The same key on a retry that got no
     * definite answer; a new one once the server has answered (it keeps an answer for a day, and a later tap must get
     * today's session, not yesterday's).
     */
    fun onVerify() {
        if (!state.value.canVerify || verifying?.isActive == true) return
        kycWatch?.cancel()
        // What is on the page stays as it is while the button is busy: only the answer changes it.
        local.update { it.copy(verify = it.verify.copy(starting = true, stale = false)) }
        verifying = viewModelScope.launch {
            when (val result = onPin { startKyc(kycKey) }) {
                is AppResult.Success -> {
                    kycKey = newIdempotencyKey()
                    val session = result.value.session
                    if (session == null) {
                        // Nothing to do now: verified already, in review, or a decline another try can't fix.
                        local.update { it.copy(verify = it.verify.copy(starting = false, checking = false, trouble = null, retryAt = null, reference = null, failure = null)) }
                        onVerification(result.value.verification)
                    } else {
                        // The page keeps what it shows, its button busy, until Didit's screens are over it.
                        held = result.value.verification
                        runIdentityCheck(session)
                    }
                }
                is AppResult.Failure -> onStartRefused(result.error)
            }
        }
    }

    /** Didit's screens (or, where they can't run, Didit's hosted page), then the truth from the server. */
    private suspend fun runIdentityCheck(session: KycSession) {
        sdkUp = true
        var outcome = try { runCheck(session, hosted = useHostedPage) } finally { sdkUp = false }
        // No SDK in this build, or nothing to show it over: the hosted page does the same check, without a second tap.
        if (outcome == VerifierOutcome.Unavailable && !useHostedPage) {
            useHostedPage = true
            sdkUp = true
            outcome = try { runCheck(session, hosted = true) } finally { sdkUp = false }
        }
        val heard = held
        held = null
        if (!onVerifyPage() || local.value.verify.verified) { local.update { it.copy(verify = it.verify.copy(starting = false)) }; return }
        // Decided while Didit's last screen was still up: there is nothing left to wait for.
        if (heard != null && heard.cleared) { local.update { it.copy(verify = it.verify.copy(starting = false)) }; onVerification(heard); return }
        // What R0 (or a frame under the SDK) said: shown if the read after gets no answer, so nothing is ever lost.
        fun showHeard() { if (heard != null && local.value.verify.verification?.let { it.status == heard.status && it.reason == heard.reason } != true) onVerification(heard) }
        when (outcome) {
            // Everything was sent: the waiting state, at once, while the result is read.
            VerifierOutcome.Finished -> {
                checkingUntil = null
                local.update { it.copy(verify = it.verify.copy(verification = heard ?: it.verify.verification, starting = false, checking = true, slow = false, returned = false, trouble = null, retryAt = null, reference = null, failure = null)) }
                readKyc()
                watchKyc()
            }
            // Back from the hosted page: whether they finished isn't known, so nothing is claimed. The result is read
            // for a short while; with none, the check is simply still open.
            VerifierOutcome.Left -> {
                checkingUntil = now() + HostedGraceMillis
                local.update { it.copy(verify = it.verify.copy(verification = heard ?: it.verify.verification, starting = false, checking = false, returned = true, trouble = null, retryAt = null, reference = null, failure = null)) }
                readKyc()
                watchKyc()
            }
            VerifierOutcome.CameraDenied -> local.update {
                it.copy(verify = it.verify.copy(verification = heard ?: it.verify.verification, starting = false, checking = false, returned = false, trouble = VerifyTrouble.CameraDenied, retryAt = null, reference = null, failure = null))
            }
            // Closed before the end, or cut short. R0 already said a check is open, so the page to continue from is
            // there the instant Didit's screens are gone; the server's own word follows behind it and replaces it
            // only if it differs.
            VerifierOutcome.Cancelled, VerifierOutcome.Offline -> {
                local.update { it.copy(verify = it.verify.copy(starting = false, checking = false, returned = false, trouble = null, retryAt = null, reference = null, failure = null)) }
                showHeard()
                viewModelScope.launch { readKyc() }
            }
            // The SDK ended it itself (the session ran out, its tries are used up): "continue" is almost never what
            // the server will say, so nothing is offered until it has answered; the page says it is finding out.
            VerifierOutcome.Ended -> {
                local.update { it.copy(verify = it.verify.copy(verification = heard ?: it.verify.verification, starting = false, checking = false, returned = true, trouble = null, retryAt = null, reference = null, failure = null)) }
                readKyc()
                local.update { it.copy(verify = it.verify.copy(returned = false, stale = false)) }
            }
            is VerifierOutcome.Failed -> {
                useHostedPage = true
                local.update { it.copy(verify = it.verify.copy(starting = false, checking = false, returned = false, trouble = null, retryAt = null, reference = null, failure = null)) }
                showHeard()
                // Only said while the check is there to continue: the next tap opens it in the browser.
                local.update { v -> if (v.verify.verification?.canStart == true) v.copy(verify = v.verify.copy(failure = AddMoneyFailure(SdkStopped, null, calm = true))) else v }
                viewModelScope.launch { readKyc() }
            }
            VerifierOutcome.Unavailable -> {
                local.update { it.copy(verify = it.verify.copy(starting = false, checking = false, returned = false)) }
                showHeard()
                local.update { it.copy(verify = it.verify.copy(failure = AddMoneyFailure(CouldNotShowCheck, null))) }
            }
        }
    }

    /** `GET /v1/ramp/kyc`. True when the server answered. No answer leaves what is shown, marked as not just checked. */
    private suspend fun readKyc(): Boolean {
        // Reads can overlap (the app coming to the front, the connection returning, the tick): only the answer to the
        // newest one is shown, so a slower, older answer never walks a newer one back.
        val mine = ++readsAsked
        val result = onPin { getKyc() }
        if (mine != readsAsked) return true
        return shown(result)
    }

    private fun shown(result: AppResult<RampVerification>): Boolean = when (result) {
        is AppResult.Success -> { onVerification(result.value, keepTrouble = local.value.verify.trouble == VerifyTrouble.CameraDenied); true }
        is AppResult.Failure -> {
            val block = blockFor(result.error)
            local.update {
                when {
                    !(it.step == AddMoneyStep.Blocked && it.block == RampBlock.Verify) -> it
                    block != null -> it.copy(block = block, verify = VerifyUi())
                    else -> it.copy(verify = it.verify.copy(stale = it.verify.checking || it.verify.returned || it.verify.verification?.status == KycStatus.InReview))
                }
            }
            false
        }
    }

    /**
     * While a result is awaited the read is repeated (the server asks Didit at most every five seconds, so that is the
     * pace at first, then slower); the frame and the push arrive beside it. It stops by itself once there is nothing
     * to wait for. Nothing is asked offline.
     */
    private fun watchKyc() {
        if (kycWatch?.isActive == true) return
        // After the hosted page, the open check is still read for a few minutes: someone who did finish in the browser
        // isn't left on "Continue" until a frame happens to arrive.
        fun lingering() = now() < lingerUntil && local.value.verify.verification.let { it?.status == KycStatus.Pending && it.reason == null }
        fun waiting() = onVerifyPage() && local.value.verify.let { !it.verified && !it.starting && (it.checking || it.returned || it.verification?.status == KycStatus.InReview || lingering()) }
        if (!waiting()) return
        kycWatch = viewModelScope.launch {
            var waited = 0L
            while (true) {
                val soon = local.value.verify.let { it.checking || it.returned }
                val pace = if (soon && waited < FastWatchMillis) CheckMillis else ReviewMillis
                delay(pace)
                waited += pace
                if (!waiting() || sdkUp) break
                // Back from the hosted page with nothing to show for it after a fair wait: the check is simply still open.
                val until = checkingUntil
                if (until != null && local.value.verify.returned && now() >= until) {
                    checkingUntil = null
                    lingerUntil = now() + HostedLingerMillis
                    local.update { it.copy(verify = it.verify.copy(returned = false, stale = false)) }
                    continue
                }
                // "Less than a minute" has passed: the words stop promising it, and the reads slow down.
                if (waited >= FastWatchMillis && local.value.verify.checking && !local.value.verify.slow) local.update { it.copy(verify = it.verify.copy(slow = true)) }
                if (state.value.online) readKyc()
            }
        }
    }

    /** Today's tries come back at a known moment: the page reads its state again then, so the button returns by itself. */
    private fun wakeWhenTriesAreBack() {
        val wait = (triesBackAt() ?: return) - now()
        // A time already past is the running wake-up's to deal with (it tries a few times): it is left to it.
        if (wait <= 0 || wait > LongestWakeMillis) return
        retryTimer?.cancel()
        retryTimer = viewModelScope.launch {
            delay(wait + 1_000)
            readIfItMayHaveMoved()
            // The server may not agree that the time has come (this phone's clock is ahead), or the read may get no
            // answer: a few more tries, further apart, so the page is never left on "in a moment" with only Close.
            for (pause in WakeBackoffMillis) {
                delay(pause)
                val at = triesBackAt()
                if (!onVerifyPage() || at == null || at > now()) break
                readIfItMayHaveMoved()
            }
        }
    }

    /** When the tries on screen are due back (epoch ms): the 429's time, or the server's `retryAt` while it can't start. */
    private fun triesBackAt(): Long? = local.value.verify.let { v ->
        if (v.trouble == VerifyTrouble.TriesUsedUp) v.retryAt else v.verification?.takeIf { !it.canStart }?.retryAt
    }

    /**
     * Reads the check again if it may have moved without this phone hearing: a result being waited on, an open check,
     * or tries whose time to come back has passed. Called when the app comes to the front, when
     * the connection returns, and when the wake-up above fires; so nobody is left looking at "try again later" with
     * no button once later has come. Nothing is asked offline (the connection returning calls this again).
     */
    private fun readIfItMayHaveMoved() {
        val s = local.value
        if (!onVerifyPage() || sdkUp || s.verify.starting || s.verify.verified || !state.value.online) return
        val v = s.verify
        val check = v.verification
        val capped = v.trouble == VerifyTrouble.TriesUsedUp
        // A cap with no time given is left as it is (lifting it would only offer a button that is refused again).
        val triesDue = if (capped) v.retryAt.let { it != null && it <= now() } else check?.canStart == false && check.retryAt.let { it == null || it <= now() }
        val unsettled = v.checking || v.returned || check?.status == KycStatus.Pending || check?.status == KycStatus.InReview
        // A decline another try can't fix has nothing to come back: it is left alone.
        val final = check?.status == KycStatus.Declined && check.canStart.not() && check.retryAt == null && !capped
        if (final || !(triesDue || unsettled)) return
        // The cap has lifted: the note goes, and the server says what is offered now.
        if (capped && triesDue) local.update { it.copy(verify = it.verify.copy(trouble = null, retryAt = null)) }
        viewModelScope.launch { readKyc(); watchKyc() }
    }

    private fun onStartRefused(error: AppError) {
        // The server answered: this attempt is over, and the next one is a new action.
        if (refused(error)) kycKey = newIdempotencyKey()
        val code = (error as? AppError.Api)?.code
        val block = blockFor(error)
        val reference = ErrorMessages.reference(error)
        local.update {
            val v = it.verify.copy(starting = false, checking = false, stale = false, failure = null)
            when {
                // Barred, not open to this person, or the account paused: their own pages.
                block != null -> it.copy(block = block, verify = VerifyUi())
                code == RampCodes.KycAttemptsExceeded -> it.copy(verify = v.copy(
                    trouble = VerifyTrouble.TriesUsedUp, reference = null,
                    retryAt = (error as? AppError.Api)?.retryAfterSeconds?.takeIf { s -> s > 0 }?.let { s -> now() + s * 1000L } ?: v.verification?.retryAt,
                ))
                code == RampCodes.KycUnavailable -> it.copy(verify = v.copy(trouble = VerifyTrouble.ProviderDown, reference = reference, retryAt = null))
                code == RampCodes.KycNotConfigured -> it.copy(verify = v.copy(trouble = VerifyTrouble.NotConfigured, reference = reference, retryAt = null))
                error == AppError.Network -> it.copy(verify = v.copy(trouble = null, failure = AddMoneyFailure(VerifyNeedsConnection, null)))
                else -> it.copy(verify = v.copy(trouble = null, failure = AddMoneyFailure(ErrorMessages.forError(error).takeIf { m -> m != ErrorMessages.SomethingWrong } ?: CouldNotStartCheck, reference)))
            }
        }
        wakeWhenTriesAreBack()
    }

    /** "Open Settings" on the camera note: the person is on their way to turn the camera on. */
    fun onSettingsOpened() { sentToSettings = true }

    /**
     * The app is in front again (back from Settings, from the hosted page, from anywhere): a check that may have moved
     * while it was away is read again, so the page is never left showing yesterday's state.
     */
    fun onResumed() {
        val s = local.value
        if (!onVerifyPage() || sdkUp || s.verify.starting || s.verify.verified) return
        if (sentToSettings) {
            sentToSettings = false
            // Whether the camera is on now is the SDK's to find out: the page goes back to its button.
            local.update { it.copy(verify = it.verify.copy(trouble = it.verify.trouble.takeIf { t -> t != VerifyTrouble.CameraDenied })) }
        }
        readIfItMayHaveMoved()
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
        local.update { it.copy(order = null, everPaid = false, quote = null, cancelNote = null, stale = false) }
        load()
    }

    fun onErrorShown() = local.update { it.copy(error = null) }

    private fun blockFor(error: AppError): RampBlock? = when ((error as? AppError.Api)?.code) {
        RampCodes.Unavailable -> RampBlock.Unavailable
        RampCodes.Blocked -> RampBlock.Barred
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

        // Identity verification. A check is read every 5 s at first (the pace the server asks Didit at), then slower.
        const val CheckMillis = 5_000L
        const val ReviewMillis = 20_000L
        private const val FastWatchMillis = 60_000L
        /** Back from the hosted page, how long a result is waited for before the check is taken as still open. */
        const val HostedGraceMillis = 20_000L
        /** After that, how long the open check is still read now and then while the page is up. */
        const val HostedLingerMillis = 3 * 60_000L
        /** After the tries' time has come, the pauses between further reads if the server doesn't yet agree. */
        internal val WakeBackoffMillis = listOf(15_000L, 30_000L, 60_000L)
        /** "You're verified" stays this long: time to read it and feel it, never a wait. */
        const val VerifiedHoldMillis = 1_800L
        /** A wake-up further off than this isn't kept (the page is read again whenever it is opened or resumed). */
        private const val LongestWakeMillis = 36L * 60 * 60 * 1000
        internal const val VerifyNeedsConnection = "Verifying your identity needs a connection. Nothing was started."
        internal const val CouldNotStartCheck = "We couldn't start your verification right now. Nothing is lost. Please try again in a moment."
        internal const val SdkStopped = "The verification closed before it could finish. Continue, and it opens in your browser instead."
        internal const val CouldNotShowCheck = "We couldn't open the verification on this phone. Nothing is lost. Please try again in a moment."

        internal fun waitText(seconds: Int): String = if (seconds < 90) "a minute" else "${(seconds + 59) / 60} minutes"
    }
}
