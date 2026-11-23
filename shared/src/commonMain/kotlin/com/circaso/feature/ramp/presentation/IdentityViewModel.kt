package com.circaso.feature.ramp.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.sync.nowMillis
import com.circaso.feature.ramp.domain.GetKycUseCase
import com.circaso.feature.ramp.domain.IdentitySnapshot
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.ObserveIdentityUseCase
import com.circaso.feature.ramp.domain.ObserveKycUpdatesUseCase
import com.circaso.feature.ramp.domain.ObserveRampUpdatesUseCase
import com.circaso.feature.ramp.domain.RampOrderUpdate
import com.circaso.feature.ramp.domain.RampVerification
import com.circaso.feature.ramp.domain.RunIdentityCheckUseCase
import com.circaso.feature.ramp.domain.StartKycUseCase
import com.circaso.feature.wallet.domain.PinnedNetwork
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the profile's Verification screen is showing. */
enum class IdentityPage {
    /** Nothing is known yet (no copy on the phone, the first answer still on its way): the page's shape, breathing. */
    Loading,
    /** Nothing is known and the first read got no answer (no connection, or a fault): said, with a way to try again. */
    LoadFailed,
    /** `ramp.unavailable`: verification isn't open to this person on this server yet. */
    Unavailable,
    /** A bar on the account or its identity, or the account paused: Support is the way on. */
    Gate,
    /** The server doesn't ask for verification on this network and won't start one. */
    NotNeeded,
    /** Design E3: never started. */
    Intro,
    /** Every state of the check after E3. */
    State,
    /** Verified: it stays, and says what that means. */
    Verified,
}

data class IdentityUiState(
    /** There is something to show: a copy from the phone, or the server's answer. */
    val known: Boolean = false,
    val online: Boolean = true,
    /** The network the check is for. Each has its own: a Testnet practice run never counts on Mainnet. */
    val network: WalletNetwork = WalletNetwork.MAINNET,
    /** That network is Testnet and the server has said so (never a placeholder's guess). */
    val testnet: Boolean = false,
    val verify: VerifyUi = VerifyUi(),
    /** Not open here, barred, or paused: a page of its own instead of the check. */
    val gate: RampBlock? = null,
    /** Why nothing could be shown, when nothing could. */
    val failure: AddMoneyFailure? = null,
    /** When the server last said what is on screen (epoch ms). */
    val checkedAt: Long? = null,
    /** The last read in this visit got no answer: what shows is the last known status, and the screen says so. */
    val unconfirmed: Boolean = false,
    /** Verified while the person was here (not long ago): felt once. */
    val justVerified: Boolean = false,
) {
    /** The identity check may be started, continued or tried again right now (R0). **Online-only.** */
    val canVerify get() = online && known && gate == null && verify.startable

    /** What shows is the phone's last known copy, not something the server just said: marked as such. */
    val lastKnown get() = known && gate == null && checkedAt != null && (!online || unconfirmed)

    val page: IdentityPage
        get() {
            val v = verify.verification
            return when {
                gate == RampBlock.Unavailable -> IdentityPage.Unavailable
                gate != null -> IdentityPage.Gate
                !known -> if (failure != null) IdentityPage.LoadFailed else IdentityPage.Loading
                verify.verified || v?.status == KycStatus.Verified -> IdentityPage.Verified
                // Not asked for here, never started, and the server won't start one: nothing to do, said plainly.
                v != null && !v.required && v.status == KycStatus.None && !v.canStart && verify.trouble == null -> IdentityPage.NotNeeded
                verifyLook(verify, entry = VerifyEntry.Profile).kind == VerifyKind.Intro -> IdentityPage.Intro
                else -> IdentityPage.State
            }
        }

    internal val verifyPage get() = VerifyPageState(verify, online, testnet, canVerify, VerifyEntry.Profile)
}

/**
 * The profile's Verification screen (You › Verification): where the person's identity check stands on the wallet
 * network in use, and the check itself, without having to start a top-up first.
 *
 * **Reading the status is a Read** (offline-first.md): the last known status is kept on the phone per network
 * ([ObserveIdentityUseCase]) and shows at once, offline too, marked as last known; `GET /v1/ramp/kyc` then replaces
 * it. **Verifying is online-only**, never queued: the check is [IdentityCheck], the same one Add Money runs, so the
 * states, the words and the SDK launch can't differ between the two. What differs is the ending: verified here
 * stays on "You're verified" and goes nowhere by itself.
 *
 * The network is fixed when the screen opens ([network] from a `ramp.kyc` push, else the one the Wallet tab shows).
 * A server that hasn't opened the verification routes to everyone answers `ramp.unavailable`: said as "not available
 * yet", never as an error. Neither the session's token nor its link is ever logged or put in the state.
 */
class IdentityViewModel(
    observeIdentity: ObserveIdentityUseCase,
    startKyc: StartKycUseCase,
    private val getKyc: GetKycUseCase,
    observeKyc: ObserveKycUpdatesUseCase,
    observeUpdates: ObserveRampUpdatesUseCase,
    runCheck: RunIdentityCheckUseCase,
    online: Flow<Boolean> = flowOf(true),
    networks: WalletNetworks = WalletNetworks.Single,
    private val now: () -> Long = ::nowMillis,
    /** The network a `ramp.kyc` push was about: the screen opens on it whatever the Wallet tab shows. */
    network: WalletNetwork? = null,
) : ViewModel() {
    private val pinned: WalletNetwork = network ?: networks.current()
    private suspend fun <T> onPin(block: suspend () -> T): T = withContext(PinnedNetwork(pinned)) { block() }

    private val local = MutableStateFlow(IdentityUiState(network = pinned))

    val state: StateFlow<IdentityUiState> = combine(local, online, networks.state.map { it.known && pinned == WalletNetwork.TESTNET }) { ui, isOnline, testnet ->
        ui.copy(online = isOnline, testnet = testnet)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, IdentityUiState(network = pinned))

    /** Something other than "verified" has been on screen in this visit: verified after it happened in front of the person. */
    private var sawUnverified = false
    /** The screen's own read (opening, Try Again, coming back): one at a time. */
    private var reading: Job? = null
    /** The server has answered in this visit. */
    private var answered = false

    private val check = IdentityCheck(
        viewModelScope, startKyc, getKyc, runCheck, network = { pinned }, now = now,
        host = object : IdentityCheck.Host {
            override val verify get() = local.value.verify
            override fun update(change: (VerifyUi) -> VerifyUi) = local.update { ui ->
                val next = change(ui.verify)
                // Only once the server has spoken in this visit: the phone's copy turning out to be old is no event.
                if (answered && !next.verified && next.verification != null && next.verification.status != KycStatus.Verified) sawUnverified = true
                ui.copy(verify = next)
            }
            override val showing get() = local.value.let { it.known && it.gate == null }
            override val online get() = state.value.online
            override val canVerify get() = state.value.canVerify
            // Not asked for on this network: shown like any other state (verifying may still be offered).
            override fun onNotRequired(now: RampVerification) = false
            // It stays: nothing is opened, nothing follows. Felt only when it happened in front of the person.
            override fun onVerified(now: RampVerification) = local.update { it.copy(justVerified = sawUnverified) }
            override fun onBlocked(block: RampBlock) = local.update { it.copy(known = true, gate = block, verify = VerifyUi(), failure = null) }
        },
        onAnswered = { answered = true; local.update { it.copy(unconfirmed = false, failure = null) } },
    )

    init {
        // The phone's copy for this network: what shows first, and (as every answer is filed there) when the server
        // last said what is on screen.
        viewModelScope.launch(PinnedNetwork(pinned)) {
            var first = true
            observeIdentity().collect { copy ->
                local.update { it.copy(checkedAt = copy?.checkedAt ?: it.checkedAt) }
                if (first) { first = false; showCopy(copy); read() }
            }
        }
        // The check moved (`kyc.updated`, live or replayed): only this screen's network is taken.
        viewModelScope.launch { observeKyc().collect { moved -> if (moved.network == null || moved.network == pinned) onAnswer(moved.verification, frame = true) } }
        // The socket lost its place (`resync`): what is on screen is read again.
        viewModelScope.launch { observeUpdates().collect { if (it.orderId == RampOrderUpdate.ReloadAll) { check.onResync(); if (!check.sdkUp) read() } } }
        // The connection is back: a status that couldn't be read is read at once, and a wait picks up.
        viewModelScope.launch { state.map { it.online }.distinctUntilChanged().drop(1).collect { back -> if (back) { if (!answered || local.value.unconfirmed) read() else check.readIfItMayHaveMoved() } } }
    }

    /** The last known status, from the phone: on screen before the server has said anything, and all there is offline. */
    private fun showCopy(copy: IdentitySnapshot?) {
        if (answered || copy == null) return
        if (copy.unavailable) { local.update { it.copy(known = true, gate = RampBlock.Unavailable) }; return }
        val v = copy.verification ?: return
        local.update { it.copy(known = true) }
        check.show(v)
    }

    /**
     * `GET /v1/ramp/kyc` for the screen as a whole: on opening, on "Try Again", on coming back to the app. An answer
     * replaces whatever the phone's copy said; no answer leaves the copy on screen, marked as last known, or, with
     * nothing to show, says why.
     */
    fun read() {
        if (reading?.isActive == true || check.sdkUp || local.value.verify.starting) return
        reading = viewModelScope.launch {
            when (val result = onPin { getKyc() }) {
                is AppResult.Success -> onAnswer(result.value)
                is AppResult.Failure -> {
                    val block = IdentityCheck.blockFor(result.error)
                    local.update {
                        when {
                            block != null -> it.copy(known = true, gate = block, verify = VerifyUi(), failure = null, unconfirmed = false)
                            it.known -> it.copy(unconfirmed = true)
                            else -> it.copy(failure = failureOf(result.error))
                        }
                    }
                    if (block != null) { answered = true; check.stop() }
                }
            }
        }
    }

    /** The server's word, from the read or a frame: it replaces the copy, a gate that has lifted, and a failed load. */
    private fun onAnswer(v: RampVerification, frame: Boolean = false) {
        answered = true
        // "Verified" from the phone's copy isn't final: the server's answer is (an ID can expire). A frame never
        // walks back a "verified" the server gave in this visit (it may be an older one, replayed).
        val stale = local.value.verify.verified && v.status != KycStatus.Verified && !frame
        local.update { it.copy(known = true, gate = null, failure = null, unconfirmed = false, verify = if (stale || it.gate != null) VerifyUi() else it.verify, justVerified = if (stale) false else it.justVerified) }
        check.show(v, keepTrouble = local.value.verify.trouble == VerifyTrouble.CameraDenied)
    }

    /** The screen's one button: "Start Verification", "Continue", "Try Again", "Verify Again" ([IdentityCheck.onVerify]). */
    fun onVerify() = check.onVerify()

    /** "Open Settings" on the camera note: the person is on their way to turn the camera on. */
    fun onSettingsOpened() = check.onSettingsOpened()

    /**
     * The app is in front again (back from Settings, from the hosted page, from a notification): what is on screen is
     * read again, so it never shows yesterday's status.
     */
    fun onResumed() {
        check.onResumed()
        val ui = local.value
        // A wait or an open check is the check's own to read; anything settled (verified, declined for good, a gate,
        // a load that failed) is read here.
        val settled = ui.gate != null || !ui.known || ui.verify.verified || ui.unconfirmed || ui.verify.verification?.let { it.status != KycStatus.Pending && it.status != KycStatus.InReview } == true
        if (answered && settled && state.value.online) read()
    }

    /** The app left the front: a check being waited on isn't read behind the person's back. */
    fun onPaused() = check.onPaused()

    private fun failureOf(error: AppError) = AddMoneyFailure(
        when {
            error == AppError.Network -> CouldNotReach
            else -> ErrorMessages.forError(error).takeIf { it != ErrorMessages.SomethingWrong } ?: CouldNotLoad
        },
        ErrorMessages.reference(error),
    )

    companion object {
        internal const val CouldNotReach = "We couldn't reach Circaso to check your verification. Nothing is lost. It shows here when you're back online."
        internal const val CouldNotLoad = "We couldn't check your verification just now. Nothing is lost. Please try again in a moment."
    }
}
