package com.circaso.feature.ramp.presentation

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.newIdempotencyKey
import com.circaso.feature.ramp.domain.GetKycUseCase
import com.circaso.feature.ramp.domain.KycSession
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.RampCodes
import com.circaso.feature.ramp.domain.RampVerification
import com.circaso.feature.ramp.domain.RunIdentityCheckUseCase
import com.circaso.feature.ramp.domain.StartKycUseCase
import com.circaso.feature.ramp.domain.VerifierOutcome
import com.circaso.feature.wallet.domain.PinnedNetwork
import com.circaso.feature.wallet.domain.WalletNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * Identity verification as a screen has it (guide "Identity verification (KYC)"). [verification] is the server's
 * word and the only source of the state shown; the rest is what this phone is doing about it right now.
 */
data class VerifyUi(
    val verification: RampVerification? = null,
    /** R0 is being asked, or Didit's screens are up: the button is busy and can't be tapped twice. */
    val starting: Boolean = false,
    /**
     * Everything was sent and the result isn't in yet: the waiting state, which resolves by itself. True when the
     * server says so (`submitted`), and from the moment Didit's last screen closes until the server has caught up.
     */
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
    /** Just verified, in front of the person: the confirmation shows, and is felt once. */
    val verified: Boolean = false,
) {
    /**
     * R0 may be asked now, as far as the check itself goes (the screen adds "online" and "on this page"). `submitted`
     * is read before `canStart`: a check the person has finished is never started again.
     */
    val startable: Boolean get() = verification?.startable == true && !starting && !verified
}

/**
 * The identity check itself, shared by every place it can be done from (Add Money before a first top-up, and the
 * profile's Verification screen): R0, Didit's screens or its hosted page, the read after them, the wait for a result,
 * the frame, the daily cap and coming back to the app. One behaviour, so the two entries can't drift apart.
 *
 * It owns no state of its own to show: the screen's state holds the [VerifyUi] and hands it over through [Host], so a
 * screen can still change its page and the check together in one step. What differs between the entries is what the
 * [Host] does when the check ends (verified, not asked for, barred).
 *
 * **Online-only**, and the server's alone to decide: R0 starts or continues the check, Didit's SDK runs it, and
 * whatever the SDK reports the result is read from `GET /v1/ramp/kyc` (and heard on `kyc.updated`). `submitted` is
 * read before `canStart` everywhere: while the server says the person has finished, nothing is offered, R0 is never
 * asked and the SDK is never reopened. Neither the session's token nor its link is ever logged or put in the state.
 */
class IdentityCheck(
    private val scope: CoroutineScope,
    private val startKyc: StartKycUseCase,
    private val getKyc: GetKycUseCase,
    private val runCheck: RunIdentityCheckUseCase,
    /** The wallet network the check is for: every call is pinned to it. */
    private val network: () -> WalletNetwork,
    private val now: () -> Long,
    private val host: Host,
    /** The server answered one of the check's own reads (before it is shown): for a screen that marks what it shows as last known. */
    private val onAnswered: () -> Unit = {},
) {
    /** The screen the check is shown on. */
    interface Host {
        /** The check as the screen has it now. */
        val verify: VerifyUi
        fun update(change: (VerifyUi) -> VerifyUi)
        /** The check is what the screen is showing: nothing here moves a screen that has gone on to something else. */
        val showing: Boolean
        val online: Boolean
        /** The button may be tapped now (startable, online, on this page). */
        val canVerify: Boolean
        /**
         * The server no longer asks for verification here. True when the screen has dealt with it (Add Money goes
         * straight on); false to show the check's state like any other.
         */
        fun onNotRequired(now: RampVerification): Boolean
        /** Verified in front of the person; [verify] already says so. */
        fun onVerified(now: RampVerification)
        /** Not open to this person, barred, or the account paused: the screen's own page for it. */
        fun onBlocked(block: RampBlock)
    }

    private suspend fun <T> onPin(block: suspend () -> T): T = withContext(PinnedNetwork(network())) { block() }

    /** One key per ask, the same on a retry that got no answer; a new one once the server has answered. */
    private var kycKey = newIdempotencyKey()

    /** R0, Didit's screens and the read after them: one at a time. */
    private var verifying: Job? = null
    /** The reads while a result is awaited (just sent, or in review). */
    private var kycWatch: Job? = null
    /** Wakes the screen when today's tries are back. */
    private var retryTimer: Job? = null
    /** Didit's screens are up: what arrives meanwhile is kept ([held]), and shown once they have closed. */
    var sdkUp = false
        private set
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
    /** The app is in front: nothing is read behind the person's back while it isn't (coming back reads at once). */
    private var inFront = true

    /** Leaving the check (a reload, a gate): nothing of it keeps running on this phone. */
    fun stop() {
        if (!sdkUp) verifying?.cancel()
        kycWatch?.cancel()
        retryTimer?.cancel()
    }

    /**
     * Where the check stands, from R0, the read or a frame: the one place the check's state is moved from. While
     * Didit's screens are up it is only kept. Verified is final: a replayed older frame never walks it back.
     */
    fun show(now: RampVerification, keepTrouble: Boolean = false) {
        if (!host.showing || host.verify.verified) return
        // Nothing moves under Didit's screens: the page the person comes back to is decided once they have closed.
        if (sdkUp) { held = now; return }
        when {
            // Staff switched the requirement off while the person was here: the screen says what that means for it.
            !now.required && host.onNotRequired(now) -> Unit
            now.status == KycStatus.Verified -> {
                kycWatch?.cancel()
                retryTimer?.cancel()
                host.update { VerifyUi(verification = now, verified = true) }
                host.onVerified(now)
            }
            else -> {
                host.update { before ->
                    val moved = before.verification != now
                    // Still unfinished at the server: a wait for a result goes on.
                    val unfinished = now.status == KycStatus.Pending && now.reason == null
                    // The server's own word that the person has finished and the decision is awaited: checked first.
                    val sent = now.status == KycStatus.Pending && now.submitted
                    val keep = !sent && (keepTrouble || !moved)
                    before.copy(
                        verification = now,
                        checking = sent || (before.checking && unfinished),
                        slow = before.slow && before.checking && (unfinished || sent),
                        returned = before.returned && unfinished && !sent,
                        stale = false,
                        trouble = before.trouble.takeIf { keep },
                        retryAt = before.retryAt.takeIf { keep },
                        reference = before.reference.takeIf { keep },
                        failure = before.failure.takeIf { keep },
                    )
                }
                watch()
                wakeWhenTriesAreBack()
            }
        }
    }

    /**
     * The check's one button: "Start Verification", "Continue", "Try Again", "Verify Again". R0 starts the check or
     * returns the one still open, then Didit's screens run it. The same key on a retry that got no definite answer; a
     * new one once the server has answered (it keeps an answer for a day, and a later tap must get today's session,
     * not yesterday's).
     */
    fun onVerify() {
        if (!host.canVerify || !host.verify.startable || verifying?.isActive == true) return
        kycWatch?.cancel()
        // What is on the page stays as it is while the button is busy: only the answer changes it.
        host.update { it.copy(starting = true, stale = false) }
        verifying = scope.launch {
            when (val result = onPin { startKyc(kycKey) }) {
                is AppResult.Success -> {
                    kycKey = newIdempotencyKey()
                    val session = result.value.session
                    if (session == null || result.value.verification.awaitingDecision) {
                        // Nothing to do now: verified already, submitted and being decided, in review, or a decline
                        // another try can't fix. A submitted check never opens Didit's screens again.
                        host.update { it.copy(starting = false, checking = false, trouble = null, retryAt = null, reference = null, failure = null) }
                        show(result.value.verification)
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
        if (!host.showing || host.verify.verified) { host.update { it.copy(starting = false) }; return }
        // Decided while Didit's last screen was still up: there is nothing left to wait for.
        if (heard != null && heard.cleared) { host.update { it.copy(starting = false) }; show(heard); return }
        // What R0 (or a frame under the SDK) said: shown if the read after gets no answer, so nothing is ever lost.
        fun showHeard() { if (heard != null && host.verify.verification?.let { it.status == heard.status && it.reason == heard.reason } != true) show(heard) }
        when (outcome) {
            // Everything was sent: the waiting state, at once, while the result is read.
            VerifierOutcome.Finished -> {
                checkingUntil = null
                host.update { it.copy(verification = heard ?: it.verification, starting = false, checking = true, slow = false, returned = false, trouble = null, retryAt = null, reference = null, failure = null) }
                readKyc()
                watch()
            }
            // Back from the hosted page: whether they finished isn't known, so nothing is claimed. The result is read
            // for a short while; with none, the check is simply still open.
            VerifierOutcome.Left -> {
                checkingUntil = now() + HostedGraceMillis
                host.update { it.copy(verification = heard ?: it.verification, starting = false, checking = false, returned = true, trouble = null, retryAt = null, reference = null, failure = null) }
                readKyc()
                watch()
            }
            VerifierOutcome.CameraDenied -> host.update {
                it.copy(verification = heard ?: it.verification, starting = false, checking = false, returned = false, trouble = VerifyTrouble.CameraDenied, retryAt = null, reference = null, failure = null)
            }
            // Closed before the end, or cut short. R0 already said a check is open, so the page to continue from is
            // there the instant Didit's screens are gone; the server's own word follows behind it and replaces it
            // only if it differs.
            VerifierOutcome.Cancelled, VerifierOutcome.Offline -> {
                host.update { it.copy(starting = false, checking = false, returned = false, trouble = null, retryAt = null, reference = null, failure = null) }
                showHeard()
                scope.launch { readKyc() }
            }
            // The SDK ended it itself (the session ran out, its tries are used up): "continue" is almost never what
            // the server will say, so nothing is offered until it has answered; the page says it is finding out.
            VerifierOutcome.Ended -> {
                host.update { it.copy(verification = heard ?: it.verification, starting = false, checking = false, returned = true, trouble = null, retryAt = null, reference = null, failure = null) }
                readKyc()
                host.update { it.copy(returned = false, stale = false) }
            }
            is VerifierOutcome.Failed -> {
                useHostedPage = true
                host.update { it.copy(starting = false, checking = false, returned = false, trouble = null, retryAt = null, reference = null, failure = null) }
                showHeard()
                // Only said while the check is there to continue: the next tap opens it in the browser.
                host.update { v -> if (v.verification?.startable == true) v.copy(failure = AddMoneyFailure(SdkStopped, null, calm = true)) else v }
                scope.launch { readKyc() }
            }
            VerifierOutcome.Unavailable -> {
                host.update { it.copy(starting = false, checking = false, returned = false) }
                showHeard()
                host.update { it.copy(failure = AddMoneyFailure(CouldNotShowCheck, null)) }
            }
        }
    }

    /** `GET /v1/ramp/kyc`. True when the server answered. No answer leaves what is shown, marked as not just checked. */
    suspend fun readKyc(): Boolean {
        // Reads can overlap (the app coming to the front, the connection returning, the tick): only the answer to the
        // newest one is shown, so a slower, older answer never walks a newer one back.
        val mine = ++readsAsked
        val result = onPin { getKyc() }
        if (mine != readsAsked) return true
        return shown(result)
    }

    private fun shown(result: AppResult<RampVerification>): Boolean = when (result) {
        is AppResult.Success -> { onAnswered(); show(result.value, keepTrouble = host.verify.trouble == VerifyTrouble.CameraDenied); true }
        is AppResult.Failure -> {
            val block = blockFor(result.error)
            if (host.showing) {
                if (block != null) host.onBlocked(block)
                else host.update { it.copy(stale = it.checking || it.returned || it.verification?.status == KycStatus.InReview) }
            }
            false
        }
    }

    /**
     * While a result is awaited the read is repeated; the frame and the push arrive beside it. A check the server says
     * was submitted is read every five seconds for as long as the page is open (the pace the server asks Didit at);
     * one this phone only believes was sent, at that pace for a minute and then slower, like a check in review. It
     * stops by itself once there is nothing to wait for. Nothing is asked offline, or while the app isn't in front.
     */
    fun watch() {
        if (kycWatch?.isActive == true) return
        // After the hosted page, the open check is still read for a few minutes: someone who did finish in the browser
        // isn't left on "Continue" until a frame happens to arrive.
        fun lingering() = now() < lingerUntil && host.verify.verification.let { it?.status == KycStatus.Pending && it.reason == null }
        fun waiting() = host.showing && host.verify.let { !it.verified && !it.starting && (it.checking || it.returned || it.verification?.awaitingDecision == true || it.verification?.status == KycStatus.InReview || lingering()) }
        if (!waiting()) return
        kycWatch = scope.launch {
            var waited = 0L
            while (true) {
                val v = host.verify
                val soon = v.checking || v.returned
                val submitted = v.verification?.let { it.submitted && it.status == KycStatus.Pending } == true
                val pace = if (soon && (waited < FastWatchMillis || submitted)) CheckMillis else ReviewMillis
                delay(pace)
                waited += pace
                if (!waiting() || sdkUp) break
                // Back from the hosted page with nothing to show for it after a fair wait: the check is simply still open.
                val until = checkingUntil
                if (until != null && host.verify.returned && now() >= until) {
                    checkingUntil = null
                    lingerUntil = now() + HostedLingerMillis
                    host.update { it.copy(returned = false, stale = false) }
                    continue
                }
                // "Less than a minute" has passed: the words stop promising it, and the reads slow down.
                if (waited >= FastWatchMillis && host.verify.checking && !host.verify.slow) host.update { it.copy(slow = true) }
                if (host.online && inFront) readKyc()
            }
        }
    }

    /** Today's tries come back at a known moment: the page reads its state again then, so the button returns by itself. */
    fun wakeWhenTriesAreBack() {
        val wait = (triesBackAt() ?: return) - now()
        // A time already past is the running wake-up's to deal with (it tries a few times): it is left to it.
        if (wait <= 0 || wait > LongestWakeMillis) return
        retryTimer?.cancel()
        retryTimer = scope.launch {
            delay(wait + 1_000)
            readIfItMayHaveMoved()
            // The server may not agree that the time has come (this phone's clock is ahead), or the read may get no
            // answer: a few more tries, further apart, so the page is never left on "in a moment" with only Close.
            for (pause in WakeBackoffMillis) {
                delay(pause)
                val at = triesBackAt()
                if (!host.showing || at == null || at > now()) break
                readIfItMayHaveMoved()
            }
        }
    }

    /** When the tries on screen are due back (epoch ms): the 429's time, or the server's `retryAt` while it can't start. */
    private fun triesBackAt(): Long? = host.verify.let { v ->
        if (v.trouble == VerifyTrouble.TriesUsedUp) v.retryAt else v.verification?.takeIf { !it.canStart }?.retryAt
    }

    /**
     * Reads the check again if it may have moved without this phone hearing: a result being waited on, an open check,
     * or tries whose time to come back has passed. Called when the app comes to the front, when the connection
     * returns, and when the wake-up above fires; so nobody is left looking at "try again later" with no button once
     * later has come. Nothing is asked offline (the connection returning calls this again).
     */
    fun readIfItMayHaveMoved() {
        val v = host.verify
        if (!host.showing || sdkUp || v.starting || v.verified || !host.online) return
        val check = v.verification
        val capped = v.trouble == VerifyTrouble.TriesUsedUp
        // A cap with no time given is left as it is (lifting it would only offer a button that is refused again).
        val triesDue = if (capped) v.retryAt.let { it != null && it <= now() } else check?.canStart == false && check.retryAt.let { it == null || it <= now() }
        val unsettled = v.checking || v.returned || check?.status == KycStatus.Pending || check?.status == KycStatus.InReview
        // A decline another try can't fix has nothing to come back: it is left alone.
        val final = check?.status == KycStatus.Declined && check.canStart.not() && check.retryAt == null && !capped
        if (final || !(triesDue || unsettled)) return
        // The cap has lifted: the note goes, and the server says what is offered now.
        if (capped && triesDue) host.update { it.copy(trouble = null, retryAt = null) }
        scope.launch { readKyc(); watch() }
    }

    private fun onStartRefused(error: AppError) {
        // The server answered: this attempt is over, and the next one is a new action.
        if (refused(error)) kycKey = newIdempotencyKey()
        val code = (error as? AppError.Api)?.code
        val block = blockFor(error)
        val reference = ErrorMessages.reference(error)
        // Barred, not open to this person, or the account paused: their own pages.
        if (block != null) { host.onBlocked(block); return }
        host.update {
            val v = it.copy(starting = false, checking = false, stale = false, failure = null)
            when {
                code == RampCodes.KycAttemptsExceeded -> v.copy(
                    trouble = VerifyTrouble.TriesUsedUp, reference = null,
                    retryAt = (error as? AppError.Api)?.retryAfterSeconds?.takeIf { s -> s > 0 }?.let { s -> now() + s * 1000L } ?: v.verification?.retryAt,
                )
                code == RampCodes.KycUnavailable -> v.copy(trouble = VerifyTrouble.ProviderDown, reference = reference, retryAt = null)
                code == RampCodes.KycNotConfigured -> v.copy(trouble = VerifyTrouble.NotConfigured, reference = reference, retryAt = null)
                error == AppError.Network -> v.copy(trouble = null, failure = AddMoneyFailure(VerifyNeedsConnection, null))
                else -> v.copy(trouble = null, failure = AddMoneyFailure(ErrorMessages.forError(error).takeIf { m -> m != ErrorMessages.SomethingWrong } ?: CouldNotStartCheck, reference))
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
        inFront = true
        val v = host.verify
        if (!host.showing || sdkUp || v.starting || v.verified) return
        if (sentToSettings) {
            sentToSettings = false
            // Whether the camera is on now is the SDK's to find out: the page goes back to its button.
            host.update { it.copy(trouble = it.trouble.takeIf { t -> t != VerifyTrouble.CameraDenied }) }
        }
        readIfItMayHaveMoved()
    }

    /**
     * The app left the front. Didit's own screens are over the app, not away from it, so they never count: the wait
     * under them carries on. Otherwise the reads rest until [onResumed].
     */
    fun onPaused() { if (!sdkUp) inFront = false }

    /** The socket lost its place (`resync`): a check being waited on is read again. */
    fun onResync() {
        if (!host.showing || sdkUp) return
        scope.launch { readKyc(); watch() }
    }

    companion object {
        // A check is read every 5 s at first (the pace the server asks Didit at), then slower.
        const val CheckMillis = 5_000L
        const val ReviewMillis = 20_000L
        const val FastWatchMillis = 60_000L
        /** Back from the hosted page, how long a result is waited for before the check is taken as still open. */
        const val HostedGraceMillis = 20_000L
        /** After that, how long the open check is still read now and then while the page is up. */
        const val HostedLingerMillis = 3 * 60_000L
        /** After the tries' time has come, the pauses between further reads if the server doesn't yet agree. */
        internal val WakeBackoffMillis = listOf(15_000L, 30_000L, 60_000L)
        /** A wake-up further off than this isn't kept (the page is read again whenever it is opened or resumed). */
        private const val LongestWakeMillis = 36L * 60 * 60 * 1000
        internal const val VerifyNeedsConnection = "Verifying your identity needs a connection. Nothing was started."
        internal const val CouldNotStartCheck = "We couldn't start your verification right now. Nothing is lost. Please try again in a moment."
        internal const val SdkStopped = "The verification closed before it could finish. Continue, and it opens in your browser instead."
        internal const val CouldNotShowCheck = "We couldn't open the verification on this phone. Nothing is lost. Please try again in a moment."

        /** The refusals that are a page of their own rather than a state of the check. */
        fun blockFor(error: AppError): RampBlock? = when ((error as? AppError.Api)?.code) {
            RampCodes.Unavailable -> RampBlock.Unavailable
            RampCodes.Blocked -> RampBlock.Barred
            RampCodes.AccountRestricted -> RampBlock.Restricted
            else -> null
        }

        /**
         * The server gave a definite no (a 4xx that isn't "the first attempt is still running"): that attempt is over
         * and the next one is a new action with a new key. No answer, a 5xx (`service.unavailable`) and
         * `request.in_progress` keep the key, so a retry can never make a second request.
         */
        fun refused(error: AppError): Boolean = (error as? AppError.Api)?.let { it.httpStatus in 400..499 && it.code != "request.in_progress" } == true
    }
}
