package com.circaso.feature.ramp

import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.core.notifications.NotificationDestination
import com.circaso.core.notifications.NotificationLookups
import com.circaso.core.notifications.NotificationRoutes
import com.circaso.core.navigation.AddMoneyRoute
import com.circaso.feature.ramp.domain.BuyWithMpesaUseCase
import com.circaso.feature.ramp.domain.CancelRampOrderUseCase
import com.circaso.feature.ramp.domain.GetKycUseCase
import com.circaso.feature.ramp.domain.GetOwnMpesaNumberUseCase
import com.circaso.feature.ramp.domain.GetRampOrderUseCase
import com.circaso.feature.ramp.domain.GetRampStatusUseCase
import com.circaso.feature.ramp.domain.IdentityVerifier
import com.circaso.feature.ramp.domain.KycReasons
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.ObserveKycUpdatesUseCase
import com.circaso.feature.ramp.domain.ObserveRampUpdatesUseCase
import com.circaso.feature.ramp.domain.QuoteTopUpUseCase
import com.circaso.feature.ramp.domain.RampVerification
import com.circaso.feature.ramp.domain.RunIdentityCheckUseCase
import com.circaso.feature.ramp.domain.StartKycUseCase
import com.circaso.feature.ramp.domain.VerifierOutcome
import com.circaso.feature.ramp.presentation.AddMoneyPage
import com.circaso.feature.ramp.presentation.AddMoneyStep
import com.circaso.feature.ramp.presentation.AddMoneyViewModel
import com.circaso.feature.ramp.presentation.RampBlock
import com.circaso.feature.ramp.presentation.StageMark
import com.circaso.feature.ramp.presentation.VerifyAction
import com.circaso.feature.ramp.presentation.VerifyKind
import com.circaso.feature.ramp.presentation.VerifyTrouble
import com.circaso.feature.ramp.presentation.VerifyUi
import com.circaso.feature.ramp.presentation.pageOf
import com.circaso.feature.ramp.presentation.retryWhen
import com.circaso.feature.ramp.presentation.verifyLook
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Didit's SDK as a script: what it reports when its screens close, and what happened while they were up. */
internal class FakeVerifier(var outcome: VerifierOutcome = VerifierOutcome.Finished) : IdentityVerifier {
    override val available = true
    val tokens = mutableListOf<String>()
    /** When set, the screens stay up until it completes. */
    var open: CompletableDeferred<Unit>? = null
    /** Runs while the screens are up (a frame arriving under them). */
    var whileOpen: (suspend () -> Unit)? = null
    override suspend fun verify(sessionToken: String): VerifierOutcome { tokens += sessionToken; whileOpen?.invoke(); open?.await(); return outcome }
}

/**
 * Identity verification before a first top-up (guide "Identity verification (KYC)", design E3) with a scripted server
 * and a scripted SDK: starting, resuming, every way the SDK can close against every answer the server can give, the
 * frame, the push, each error code, the daily cap, and offline. The page shown is always the server's word.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddMoneyVerifyTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val online = MutableStateFlow(true)
    private val sdk = FakeVerifier()
    /** The hosted page: every link opened, and whether the phone could open it. */
    private val pages = mutableListOf<String>()
    private var pageOpens = true

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(f: RampFixture, network: WalletNetwork? = null, networks: WalletNetworks = WalletNetworks.fixed(WalletNetwork.TESTNET, known = true)) = AddMoneyViewModel(
        null, GetRampStatusUseCase(f.repo), QuoteTopUpUseCase(f.repo), BuyWithMpesaUseCase(f.repo), GetRampOrderUseCase(f.repo), CancelRampOrderUseCase(f.repo),
        ObserveRampUpdatesUseCase(f.repo), GetOwnMpesaNumberUseCase(f.repo),
        StartKycUseCase(f.repo), GetKycUseCase(f.repo), ObserveKycUpdatesUseCase(f.repo),
        RunIdentityCheckUseCase(verifier = { sdk }, openPage = { url -> pages += url; pageOpens }),
        maskedPhone = MutableStateFlow(Masked), online = online, networks = networks,
        now = { testScheduler.currentTime }, pollMillis = 4_000, network = network, verifiedHoldMillis = Hold,
    )

    /** Someone who must verify and stands at [status]; always ends the view model's loops. */
    private fun with(
        status: String = "NONE", canStart: Boolean = status in setOf("NONE", "PENDING", "EXPIRED", "DECLINED"), reason: String? = null, retryAt: String? = null,
        f: RampFixture = RampFixture(phones = MemoryPhone(Phone)).also { it.remote.status = AppResult.Success(unverified(status, canStart, reason, retryAt)); it.remote.kyc = AppResult.Success(checkDto(status, canStart, reason, retryAt)) },
        block: suspend TestScope.(AddMoneyViewModel, RampFixture) -> Unit,
    ) = runTest(dispatcher) {
        val vm = vm(f)
        try { block(vm, f) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    private fun RampFixture.says(status: String, canStart: Boolean = status in setOf("NONE", "PENDING", "EXPIRED"), reason: String? = null, retryAt: String? = null) {
        remote.kyc = AppResult.Success(checkDto(status, canStart, reason, retryAt))
    }
    /** R1 once the person is through. */
    private fun RampFixture.cleared() { remote.status = AppResult.Success(statusDto(kyc = "VERIFIED", verification = checkDto("VERIFIED", canStart = false))) }
    private suspend fun RampFixture.frame(status: String, network: String = "TESTNET", canStart: Boolean = false, reason: String? = null) = repo.onFrame(
        "kyc.updated",
        circasoJson().parseToJsonElement("""{"network":"$network","status":"$status","required":true,"canStart":$canStart${reason?.let { ""","reason":"$it"""" } ?: ""}}""").jsonObject,
    )
    private val AddMoneyViewModel.look get() = verifyLook(state.value.verify, now = 0)
    private val AddMoneyViewModel.kind get() = look.kind
    private val AddMoneyViewModel.verify get() = state.value.verify

    // ---- whether it is asked at all ------------------------------------------------------------------------------

    @Test fun whereTheServerAsksForItTheFirstTopUpOpensOnE3() = with { vm, f ->
        assertEquals(AddMoneyStep.Blocked to RampBlock.Verify, vm.state.value.step to vm.state.value.block)
        assertEquals(VerifyKind.Intro, vm.kind)
        assertEquals(AddMoneyPage.Verify, pageOf(vm.state.value))
        assertEquals("Verify once, add money any time", vm.look.title)
        assertEquals("Start Verification", vm.look.button)
        assertTrue(vm.state.value.canVerify)
        assertTrue(f.remote.kycStarts.isEmpty(), "nothing is started until the person taps")
        assertEquals(0, f.remote.kycReads, "a check never started has nothing to read")
    }

    @Test fun whereItIsNotRequiredNothingChanges() = runTest(dispatcher) {
        val f = RampFixture().also { it.remote.status = AppResult.Success(statusDto(kyc = "NOT_REQUIRED", verification = checkDto("NONE", canStart = false, required = false))) }
        val vm = vm(f)
        try {
            assertEquals(AddMoneyStep.Amount, vm.state.value.step)
            assertEquals(0, f.remote.kycReads)
            assertTrue(f.remote.kycStarts.isEmpty())
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun someoneAlreadyVerifiedGoesStraightToTheAmount() = runTest(dispatcher) {
        val f = RampFixture().also { it.cleared() }
        val vm = vm(f)
        try {
            assertEquals(AddMoneyStep.Amount, vm.state.value.step)
            assertFalse(vm.verify.verified, "no confirmation for something that happened long ago")
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun aBarOnTheAccountWinsOverVerification() = runTest(dispatcher) {
        val f = RampFixture().also { it.remote.status = AppResult.Success(statusDto(kyc = "NONE", verification = checkDto("NONE"), blocked = true)) }
        val vm = vm(f)
        try {
            assertEquals(RampBlock.Barred, vm.state.value.block)
            assertEquals(AddMoneyPage.Gate, pageOf(vm.state.value))
            vm.onVerify()
            assertTrue(f.remote.kycStarts.isEmpty(), "verifying is never offered behind a bar")
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun anAccountThatIsPausedIsToldSoBeforeBeingAskedToVerify() = runTest(dispatcher) {
        val f = RampFixture().also { it.remote.status = AppResult.Success(statusDto(kyc = "NONE", verification = checkDto("NONE"), active = false)) }
        val vm = vm(f)
        try { assertEquals(RampBlock.Restricted, vm.state.value.block) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    // ---- starting -------------------------------------------------------------------------------------------------

    @Test fun theButtonAsksR0WithAKeyThenOpensTheSdkWithTheToken() = with { vm, f ->
        sdk.open = CompletableDeferred()
        vm.onVerify()
        assertEquals(1, f.remote.kycStarts.size)
        assertTrue(f.remote.kycStarts.single().isNotBlank())
        assertEquals(listOf(SessionToken), sdk.tokens)
        assertTrue(pages.isEmpty(), "the SDK is there: the hosted page isn't used")
        // The page keeps what it showed, its button busy, while Didit's screens are over it.
        assertTrue(vm.verify.starting)
        assertEquals(VerifyKind.Intro, vm.kind)
        assertFalse(vm.state.value.canVerify, "a second tap does nothing")
        vm.onVerify()
        assertEquals(1, f.remote.kycStarts.size)
        // Neither the token nor the link is ever in the state.
        assertFalse(vm.state.value.toString().contains(SessionToken))
        assertFalse(vm.state.value.toString().contains("verify.didit.me"))
        sdk.open?.complete(Unit)
    }

    @Test fun theButtonIsBusyWhileR0IsOnItsWay() = with { vm, f ->
        f.remote.kycGate = CompletableDeferred()
        vm.onVerify()
        assertTrue(vm.verify.starting)
        assertTrue(sdk.tokens.isEmpty())
        f.remote.kycGate?.complete(Unit)
        assertEquals(listOf(SessionToken), sdk.tokens)
    }

    // ---- the SDK closes: finished ------------------------------------------------------------------------------------

    @Test fun finishedAndVerifiedIsConfirmedThenGoesStraightToTheAmount() = with { vm, f ->
        f.says("VERIFIED", canStart = false)
        f.cleared()
        vm.onVerify()
        assertEquals(1, f.remote.kycReads, "the result is read the moment the SDK closes")
        assertTrue(vm.verify.verified)
        assertEquals(VerifyKind.Verified, vm.kind)
        assertEquals("You're verified", vm.look.title)
        assertEquals(VerifyAction.None, vm.look.action)
        assertTrue(vm.look.stages.all { it.mark == StageMark.Done })
        assertEquals(AddMoneyPage.VerifyState, pageOf(vm.state.value))
        // A moment to read it, then the page they came for, with nothing to tap.
        advanceTimeBy(Hold - 1)
        assertEquals(AddMoneyStep.Blocked, vm.state.value.step)
        advanceTimeBy(2)
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
        assertEquals("BASE-SEPOLIA", vm.state.value.blockchain)
        assertFalse(vm.verify.verified)
    }

    @Test fun verifiedCarriesOnEvenWhenR1IsABeatBehindTheRead() = with { vm, f ->
        f.says("VERIFIED", canStart = false)
        // R1 still says "none" when it is asked again.
        vm.onVerify()
        advanceTimeBy(Hold + 1)
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
    }

    @Test fun finishedButNotDecidedYetWaitsAndResolvesByItself() = with { vm, f ->
        f.says("PENDING")
        vm.onVerify()
        assertEquals(VerifyKind.Checking, vm.kind)
        assertEquals("Checking your details", vm.look.title)
        assertEquals(VerifyAction.Close, vm.look.action)
        assertTrue(vm.look.waiting)
        assertEquals(listOf(StageMark.Done, StageMark.Active, StageMark.Waiting), vm.look.stages.map { it.mark })
        assertFalse(vm.state.value.canVerify.not() && vm.verify.starting, "the button isn't left busy")
        // The read is repeated at the server's own pace until there is a result.
        val reads = f.remote.kycReads
        advanceTimeBy(AddMoneyViewModel.CheckMillis + 1)
        assertEquals(reads + 1, f.remote.kycReads)
        assertEquals(VerifyKind.Checking, vm.kind)
        f.says("VERIFIED", canStart = false)
        f.cleared()
        advanceTimeBy(AddMoneyViewModel.CheckMillis)
        assertEquals(VerifyKind.Verified, vm.kind)
        advanceTimeBy(Hold + 1)
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
        val after = f.remote.kycReads
        advanceTimeBy(120_000)
        assertEquals(after, f.remote.kycReads, "nothing is read once it is settled")
    }

    @Test fun aWaitThatDragsOnIsReadMoreSlowly() = with { vm, f ->
        f.says("PENDING")
        vm.onVerify()
        advanceTimeBy(60_001)
        val reads = f.remote.kycReads
        advanceTimeBy(AddMoneyViewModel.ReviewMillis + 1)
        assertEquals(reads + 1, f.remote.kycReads)
        assertEquals(VerifyKind.Checking, vm.kind)
    }

    @Test fun finishedAndSentToAReviewerSaysSoAndLetsThemLeave() = with { vm, f ->
        f.says("IN_REVIEW")
        vm.onVerify()
        assertEquals(VerifyKind.InReview, vm.kind)
        assertEquals("We're taking a closer look", vm.look.title)
        assertTrue(vm.look.message.contains("we'll notify you"))
        assertEquals(VerifyAction.Close to "Close", vm.look.action to vm.look.button)
        assertFalse(vm.state.value.canVerify)
        // Read now and then while the page is open; the frame and the push arrive beside it.
        val reads = f.remote.kycReads
        advanceTimeBy(AddMoneyViewModel.ReviewMillis + 1)
        assertEquals(reads + 1, f.remote.kycReads)
        f.cleared()
        f.frame("VERIFIED")
        assertEquals(VerifyKind.Verified, vm.kind)
        advanceTimeBy(Hold + 1)
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
    }

    @Test fun finishedAndDeclinedSaysWhatWentWrongByReason() = with { vm, f ->
        val words = mapOf(
            KycReasons.Document to ("We couldn't verify your ID" to "Try again with a clear photo of a valid ID, with all four corners showing."),
            KycReasons.DocumentExpired to ("Your ID has expired" to "Verify again with an ID that is still valid."),
            KycReasons.DocumentUnreadable to ("We couldn't read your ID" to "Its details didn't come through clearly. Try again with your national ID or passport."),
            KycReasons.Liveness to ("The selfie check didn't pass" to "Try again in good light, with your face uncovered."),
            KycReasons.FaceMatch to ("Your selfie didn't match your ID" to "The face in the selfie didn't match the photo on the ID. Try again with your own ID."),
            KycReasons.Declined to ("We couldn't verify you this time" to "Something in the check didn't pass. Please try again."),
            "a_reason_from_a_newer_server" to ("We couldn't verify you this time" to "Something in the check didn't pass. Please try again."),
        )
        words.forEach { (reason, expected) ->
            f.says("DECLINED", canStart = true, reason = reason)
            vm.onVerify()
            assertEquals(VerifyKind.Declined, vm.kind, reason)
            assertEquals(expected, vm.look.title to vm.look.message, reason)
            assertEquals(VerifyAction.Start to "Try Again", vm.look.action to vm.look.button, reason)
            assertTrue(vm.state.value.canVerify, reason)
        }
        assertEquals(words.size, f.remote.kycStarts.toSet().size, "every try after an answer is a new action with a new key")
    }

    @Test fun aDeclineAnotherTryCannotFixPointsToSupportWithNoRetry() = with { vm, f ->
        val words = mapOf(
            KycReasons.NotApproved to ("We couldn't verify your identity" to "Trying again won't change this. Contact Support and we'll help you from here."),
            KycReasons.IdentityInUse to ("This ID is already in use" to "It is verified on another Circaso account. Sign in to that account, or contact Support."),
            KycReasons.Blocked to ("You can't add money on Circaso" to "Contact Support if you think this is a mistake."),
            "a_final_reason_from_a_newer_server" to ("We couldn't verify your identity" to "Trying again won't change this. Contact Support and we'll help you from here."),
        )
        words.forEach { (reason, expected) ->
            val look = verifyLook(VerifyUi(RampVerification(KycStatus.Declined, required = true, canStart = false, reason = reason)), now = 0)
            assertEquals(VerifyKind.DeclinedFinal, look.kind, reason)
            assertEquals(expected, look.title to look.message, reason)
            assertEquals(VerifyAction.Support to "Contact Support", look.action to look.button, reason)
        }
        f.says("DECLINED", canStart = false, reason = KycReasons.IdentityInUse)
        vm.onVerify()
        assertEquals(VerifyKind.DeclinedFinal, vm.kind)
        assertFalse(vm.state.value.canVerify)
        vm.onVerify()
        assertEquals(1, f.remote.kycStarts.size, "no retry is possible")
    }

    @Test fun finishedButTheReadGotNoAnswerKeepsWaitingAndSaysSo() = with { vm, f ->
        f.remote.kyc = AppResult.Failure(AppError.Network)
        vm.onVerify()
        assertEquals(VerifyKind.Checking, vm.kind)
        assertTrue(vm.verify.stale)
        f.says("IN_REVIEW")
        advanceTimeBy(AddMoneyViewModel.CheckMillis + 1)
        assertEquals(VerifyKind.InReview, vm.kind)
        assertFalse(vm.verify.stale)
    }

    // ---- the SDK closes: cancelled, cut short, failed ---------------------------------------------------------------

    @Test fun closingTheSdkEarlyLandsOnContinueAndTheNextTapResumesTheSameCheck() = with { vm, f ->
        sdk.outcome = VerifierOutcome.Cancelled
        f.says("PENDING")
        vm.onVerify()
        assertEquals(1, f.remote.kycReads)
        assertEquals(VerifyKind.Continue, vm.kind)
        assertEquals("Finish verifying your identity", vm.look.title)
        assertEquals(VerifyAction.Start to "Continue Verification", vm.look.action to vm.look.button)
        assertEquals(AddMoneyPage.VerifyState, pageOf(vm.state.value))
        assertFalse(vm.verify.starting)
        assertFalse(vm.verify.checking, "nothing was sent: there is no result to wait for")
        // R0 hands back the same open check; the app never starts over.
        vm.onVerify()
        assertEquals(listOf(SessionToken, SessionToken), sdk.tokens)
        assertEquals(2, f.remote.kycStarts.toSet().size)
    }

    @Test fun closingEarlyWithNoAnswerFromTheReadStillLandsOnContinue() = with { vm, f ->
        sdk.outcome = VerifierOutcome.Cancelled
        f.remote.kyc = AppResult.Failure(AppError.Network)
        vm.onVerify()
        assertEquals(VerifyKind.Continue, vm.kind, "R0 said a check is open: that much is known")
        assertFalse(vm.verify.starting)
    }

    @Test fun aSessionThatRanOutShowsWhatTheServerSaysNext() = with { vm, f ->
        sdk.outcome = VerifierOutcome.Ended
        f.says("EXPIRED", canStart = true, reason = KycReasons.SessionExpired)
        vm.onVerify()
        assertEquals(VerifyKind.Expired, vm.kind)
        assertEquals("Your verification ran out of time", vm.look.title)
        assertEquals("It wasn't finished in time, so it closed. Start again when you're ready.", vm.look.message)
        assertEquals("Verify Again", vm.look.button)
    }

    @Test fun losingTheConnectionInsideTheSdkLeavesTheCheckOpen() = with { vm, f ->
        sdk.outcome = VerifierOutcome.Offline
        f.remote.kyc = AppResult.Failure(AppError.Network)
        vm.onVerify()
        assertEquals(VerifyKind.Continue, vm.kind)
        assertNull(vm.verify.failure)
    }

    @Test fun aRefusedCameraExplainsAndOffersSettingsThenTheButtonComesBack() = with { vm, f ->
        sdk.outcome = VerifierOutcome.CameraDenied
        vm.onVerify()
        assertEquals(VerifyTrouble.CameraDenied, vm.verify.trouble)
        assertEquals(VerifyKind.CameraDenied, vm.kind)
        assertEquals("Circaso can't use your camera", vm.look.title)
        assertEquals(VerifyAction.Settings to "Open Settings", vm.look.action to vm.look.button)
        assertFalse(vm.verify.starting)
        // Coming back without having gone to Settings changes nothing.
        f.says("PENDING")
        vm.onResumed()
        assertEquals(VerifyKind.CameraDenied, vm.kind)
        // Back from Settings: the note has done its job, and one tap carries on.
        vm.onSettingsOpened()
        vm.onResumed()
        assertEquals(VerifyKind.Continue, vm.kind)
        sdk.outcome = VerifierOutcome.Finished
        f.says("IN_REVIEW")
        vm.onVerify()
        assertEquals(VerifyKind.InReview, vm.kind)
    }

    @Test fun anSdkThatStopsForItsOwnReasonsUsesTheHostedPageNext() = with { vm, f ->
        sdk.outcome = VerifierOutcome.Failed("unknown")
        f.says("PENDING")
        vm.onVerify()
        assertEquals(VerifyKind.Continue, vm.kind)
        assertEquals(AddMoneyViewModel.SdkStopped, vm.verify.failure?.message)
        assertTrue(pages.isEmpty())
        vm.onVerify()
        assertEquals(listOf(SessionUrl), pages, "the same check, on Didit's page")
        assertEquals(1, sdk.tokens.size)
        assertNull(vm.verify.failure)
    }

    @Test fun aBuildWithoutTheSdkOpensTheHostedPageAtOnce() = with { vm, f ->
        sdk.outcome = VerifierOutcome.Unavailable
        f.says("PENDING")
        vm.onVerify()
        assertEquals(listOf(SessionUrl), pages)
        // Back from a browser there is no saying whether they finished: a fair wait for a result, then "Continue".
        assertEquals(VerifyKind.Checking, vm.kind)
        advanceTimeBy(AddMoneyViewModel.HostedGraceMillis + AddMoneyViewModel.CheckMillis)
        assertEquals(VerifyKind.Continue, vm.kind)
        val reads = f.remote.kycReads
        advanceTimeBy(120_000)
        assertEquals(reads, f.remote.kycReads, "an open check isn't polled")
    }

    @Test fun theHostedPageResolvesLikeTheSdkWhenTheResultIsIn() = with { vm, f ->
        sdk.outcome = VerifierOutcome.Unavailable
        f.says("IN_REVIEW")
        vm.onVerify()
        assertEquals(VerifyKind.InReview, vm.kind)
    }

    @Test fun aPhoneThatCanOpenNeitherSaysSoAndLosesNothing() = with { vm, f ->
        sdk.outcome = VerifierOutcome.Unavailable
        pageOpens = false
        vm.onVerify()
        assertEquals(AddMoneyViewModel.CouldNotShowCheck, vm.verify.failure?.message)
        assertEquals(VerifyKind.Continue, vm.kind, "R0 opened a check: it is still there to continue")
        assertFalse(vm.verify.starting)
        assertTrue(vm.state.value.canVerify)
    }

    // ---- R0 answers with nothing to do ------------------------------------------------------------------------------

    @Test fun r0SayingAlreadyVerifiedGoesOnWithoutTheSdk() = with { vm, f ->
        f.remote.kycStart = AppResult.Success(settledCheck("VERIFIED"))
        f.cleared()
        vm.onVerify()
        assertTrue(sdk.tokens.isEmpty())
        assertEquals(VerifyKind.Verified, vm.kind)
        advanceTimeBy(Hold + 1)
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
    }

    @Test fun r0SayingInReviewShowsThatWithoutTheSdk() = with { vm, f ->
        f.remote.kycStart = AppResult.Success(settledCheck("IN_REVIEW"))
        vm.onVerify()
        assertTrue(sdk.tokens.isEmpty())
        assertEquals(VerifyKind.InReview, vm.kind)
        assertFalse(vm.verify.starting)
    }

    // ---- R0's error codes -------------------------------------------------------------------------------------------

    @Test fun aRetryThatGotNoAnswerKeepsItsKeyAndADefiniteAnswerEndsIt() = with { vm, f ->
        f.remote.kycStart = AppResult.Failure(AppError.Network)
        vm.onVerify()
        assertEquals(AddMoneyViewModel.VerifyNeedsConnection, vm.verify.failure?.message)
        assertEquals(VerifyKind.Intro, vm.kind)
        assertTrue(vm.state.value.canVerify)
        f.remote.kycStart = refusal("ramp.kyc_unavailable", http = 502)
        vm.onVerify()
        f.remote.kycStart = refusal("service.unavailable", http = 503)
        vm.onVerify()
        assertEquals(1, f.remote.kycStarts.toSet().size, "no answer, and a 5xx, keep the key: the retry can't start a second check")
        f.remote.kycStart = refusal("request.rate_limited", http = 429)
        vm.onVerify()
        assertEquals(1, f.remote.kycStarts.toSet().size)
        f.remote.kycStart = AppResult.Success(openCheck())
        vm.onVerify()
        assertEquals(2, f.remote.kycStarts.toSet().size, "the refusal was an answer: the next try is a new action")
        assertEquals(listOf(SessionToken), sdk.tokens)
    }

    @Test fun diditOutOfReachIsOnOurSideNothingLostTryAgain() = with { vm, f ->
        f.remote.kycStart = refusal("ramp.kyc_unavailable", http = 502)
        vm.onVerify()
        assertEquals(VerifyTrouble.ProviderDown, vm.verify.trouble)
        assertEquals("We couldn't start your verification", vm.look.title)
        assertEquals("This is on our side, and nothing is lost: it didn't use one of your tries. Try again in a moment.", vm.look.message)
        assertEquals(VerifyAction.Start to "Try Again", vm.look.action to vm.look.button)
        assertEquals("req-1", vm.verify.reference, "the request reference is on the page")
        assertEquals(AddMoneyPage.VerifyState, pageOf(vm.state.value))
        assertTrue(sdk.tokens.isEmpty())
        // Trying again works from the same page, and a start that goes through clears it.
        f.remote.kycStart = AppResult.Success(openCheck())
        f.says("IN_REVIEW")
        vm.onVerify()
        assertNull(vm.verify.trouble)
        assertEquals(VerifyKind.InReview, vm.kind)
    }

    @Test fun verificationNotSetUpYetIsOnOurSideWithNothingToRetry() = with { vm, f ->
        f.remote.kycStart = refusal("ramp.kyc_not_configured", http = 503)
        vm.onVerify()
        assertEquals(VerifyKind.NotConfigured, vm.kind)
        assertEquals("Identity verification isn't available yet", vm.look.title)
        assertTrue(vm.look.message.contains("on our side") && vm.look.message.contains("nothing is lost"))
        assertEquals(VerifyAction.Close, vm.look.action)
        assertEquals("req-1", vm.verify.reference)
    }

    @Test fun todaysTriesUsedUpSaysWhenToComeBackAndWakesThen() = with { vm, f ->
        f.remote.kycStart = refusal("ramp.kyc_attempts_exceeded", retryAfter = 3_600, http = 429)
        vm.onVerify()
        assertEquals(VerifyTrouble.TriesUsedUp, vm.verify.trouble)
        assertEquals(3_600_000L, vm.verify.retryAt)
        assertEquals("You've tried a few times today", vm.look.title)
        assertTrue(vm.look.message.startsWith("You can try again "), vm.look.message)
        assertEquals(VerifyAction.Close, vm.look.action)
        assertNull(vm.verify.reference, "nothing went wrong: there is nothing to quote")
        // The hour passes with the page open: it reads its state again and the button is back.
        f.says("PENDING")
        advanceTimeBy(3_602_000)
        assertNull(vm.verify.trouble)
        assertEquals(VerifyKind.Continue, vm.kind)
    }

    @Test fun triesUsedUpWithNoTimeSaysTomorrow() {
        val look = verifyLook(VerifyUi(RampVerification(KycStatus.None, required = true, canStart = true), trouble = VerifyTrouble.TriesUsedUp), now = 0)
        assertEquals("You can try again tomorrow.", look.message)
    }

    @Test fun aBarFromR0IsItsOwnHonestPage() = with { vm, f ->
        f.remote.kycStart = refusal("ramp.blocked", http = 403)
        vm.onVerify()
        assertEquals(RampBlock.Barred, vm.state.value.block)
        assertEquals(AddMoneyPage.Gate, pageOf(vm.state.value))
        assertFalse(vm.state.value.canVerify)
    }

    @Test fun r0SayingTheRampIsClosedOrTheAccountPausedShowsThosePages() = with { vm, f ->
        f.remote.kycStart = refusal("ramp.account_restricted", http = 403)
        vm.onVerify()
        assertEquals(RampBlock.Restricted, vm.state.value.block)
    }

    @Test fun anAnswerWithNoWordsOfItsOwnSaysNothingIsLostWithItsReference() = with { vm, f ->
        f.remote.kycStart = refusal("ramp.something_new", http = 500)
        vm.onVerify()
        assertEquals(AddMoneyViewModel.CouldNotStartCheck, vm.verify.failure?.message)
        assertEquals("req-1 · ramp.something_new", vm.verify.failure?.reference)
        assertEquals(VerifyKind.Intro, vm.kind)
        assertFalse(vm.verify.starting)
    }

    // ---- R2 and R3 route into these states --------------------------------------------------------------------------

    @Test fun kycRequiredOnAPriceReadsR1AndShowsWhereTheCheckStands() = runTest(dispatcher) {
        val f = RampFixture(phones = MemoryPhone(Phone))
        val vm = vm(f)
        try {
            assertEquals(AddMoneyStep.Amount, vm.state.value.step)
            // The requirement was switched on since: R2 refuses, and R1 now says a check is in review.
            f.remote.quote = refusal("ramp.kyc_required", http = 403)
            f.remote.status = AppResult.Success(unverified("IN_REVIEW", canStart = false))
            f.says("IN_REVIEW")
            vm.onAmount("2000"); vm.onContinue()
            assertEquals(RampBlock.Verify, vm.state.value.block)
            assertEquals(VerifyKind.InReview, vm.kind)
            assertEquals("2000", vm.state.value.amountText, "the amount they came with is kept for after")
            assertFalse(vm.state.value.quoting)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun kycRequiredOnBuyingRoutesToVerifyToo() = runTest(dispatcher) {
        val f = RampFixture(phones = MemoryPhone(Phone))
        val vm = vm(f)
        try {
            vm.onAmount("2000"); vm.onContinue()
            f.remote.buy = refusal("ramp.kyc_required", http = 403)
            f.remote.status = AppResult.Success(unverified("NONE"))
            vm.onConfirm()
            assertEquals(RampBlock.Verify, vm.state.value.block)
            assertEquals(VerifyKind.Intro, vm.kind)
            assertFalse(vm.state.value.placing)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun kycRequiredThatR1DoesNotAgreeWithIsSaidOnTheAmountNotLooped() = runTest(dispatcher) {
        val f = RampFixture(phones = MemoryPhone(Phone))
        val vm = vm(f)
        try {
            f.remote.quote = refusal("ramp.kyc_required", http = 403)
            vm.onAmount("2000"); vm.onContinue()
            assertEquals(AddMoneyStep.Amount, vm.state.value.step)
            assertEquals("Verify your identity before you add money.", vm.state.value.failure?.message)
            assertEquals(1, f.remote.quotes.size)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun aBarOnAPriceOrOnBuyingIsTheSameHonestPage() = runTest(dispatcher) {
        val f = RampFixture(phones = MemoryPhone(Phone))
        val vm = vm(f)
        try {
            vm.onAmount("2000"); vm.onContinue()
            f.remote.buy = refusal("ramp.blocked", http = 403)
            vm.onConfirm()
            assertEquals(AddMoneyStep.Blocked to RampBlock.Barred, vm.state.value.step to vm.state.value.block)
            f.remote.status = AppResult.Success(statusDto())
            vm.load()
            f.remote.quote = refusal("ramp.blocked", http = 403)
            vm.onContinue()
            assertEquals(RampBlock.Barred, vm.state.value.block)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    // ---- the frame --------------------------------------------------------------------------------------------------

    @Test fun aFrameReplacesWhatIsShown() = with(status = "IN_REVIEW", canStart = false) { vm, f ->
        assertEquals(VerifyKind.InReview, vm.kind)
        f.frame("DECLINED", canStart = true, reason = KycReasons.Liveness)
        assertEquals(VerifyKind.Declined, vm.kind)
        assertEquals("The selfie check didn't pass", vm.look.title)
        f.frame("PENDING", canStart = true, reason = KycReasons.ResubmissionRequested)
        assertEquals(VerifyKind.OneMoreThing, vm.kind)
        assertEquals("We need one more thing", vm.look.title)
        assertEquals("Continue Verification", vm.look.button)
    }

    @Test fun aFrameForTheOtherNetworkIsNotThisFlows() = with(status = "IN_REVIEW", canStart = false) { vm, f ->
        f.frame("VERIFIED", network = "MAINNET")
        assertEquals(VerifyKind.InReview, vm.kind, "a Mainnet check never clears the Testnet one")
    }

    @Test fun verifiedIsNeverWalkedBackByAnOlderFrame() = with(status = "IN_REVIEW", canStart = false) { vm, f ->
        f.cleared()
        f.frame("VERIFIED")
        assertEquals(VerifyKind.Verified, vm.kind)
        f.frame("IN_REVIEW")
        assertEquals(VerifyKind.Verified, vm.kind)
        advanceTimeBy(Hold + 1)
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
        // Once on the amount, later frames change nothing on screen.
        f.frame("DECLINED", canStart = true, reason = KycReasons.Liveness)
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
    }

    @Test fun nothingMovesUnderTheSdkAndTheResultShowsOnceItCloses() = with { vm, f ->
        // The decision lands while Didit's own last screen is still up.
        sdk.whileOpen = { f.frame("VERIFIED"); assertEquals(VerifyKind.Intro, vm.kind); assertFalse(vm.verify.verified) }
        f.says("VERIFIED", canStart = false)
        f.cleared()
        vm.onVerify()
        assertEquals(VerifyKind.Verified, vm.kind)
        advanceTimeBy(Hold + 1)
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
    }

    @Test fun aResyncReadsTheCheckAgain() = with(status = "IN_REVIEW", canStart = false) { vm, f ->
        val reads = f.remote.kycReads
        f.says("DECLINED", canStart = true, reason = KycReasons.Document)
        f.repo.reload()
        assertEquals(reads + 1, f.remote.kycReads)
        assertEquals(VerifyKind.Declined, vm.kind)
    }

    @Test fun theRequirementSwitchedOffWhileWaitingLetsThemStraightIn() = with(status = "IN_REVIEW", canStart = false) { vm, f ->
        f.remote.status = AppResult.Success(statusDto())
        f.repo.onFrame("kyc.updated", circasoJson().parseToJsonElement("""{"network":"TESTNET","status":"NONE","required":false,"canStart":false}""").jsonObject)
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
        assertFalse(vm.verify.verified)
    }

    // ---- leaving and coming back ------------------------------------------------------------------------------------

    @Test fun comingBackToAnOpenCheckOffersToContinue() = with(status = "PENDING") { vm, f ->
        assertEquals(1, f.remote.kycReads, "an unfinished check is read on the way in: it may have moved while away")
        assertEquals(VerifyKind.Continue, vm.kind)
        assertEquals(AddMoneyPage.VerifyState, pageOf(vm.state.value))
        vm.onVerify()
        assertEquals(listOf(SessionToken), sdk.tokens)
    }

    @Test fun comingBackAfterFinishingLandsOnTheResultNotOnContinue() = runTest(dispatcher) {
        // R1 still repeats "pending"; the read, which asks Didit, knows it went to a reviewer.
        val f = RampFixture().also { it.remote.status = AppResult.Success(unverified("PENDING")); it.remote.kyc = AppResult.Success(checkDto("IN_REVIEW")) }
        val vm = vm(f)
        try { assertEquals(VerifyKind.InReview, verifyLook(vm.state.value.verify, 0).kind) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun comingBackOnceItWasApprovedIsConfirmedAndGoesOn() = runTest(dispatcher) {
        val f = RampFixture().also { it.remote.status = AppResult.Success(unverified("IN_REVIEW", canStart = false)); it.remote.kyc = AppResult.Success(checkDto("VERIFIED", canStart = false)) }
        val vm = vm(f)
        try {
            assertEquals(VerifyKind.Verified, verifyLook(vm.state.value.verify, 0).kind)
            advanceTimeBy(Hold + 1)
            assertEquals(AddMoneyStep.Amount, vm.state.value.step)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun comingBackWithNoAnswerFromTheReadShowsWhatR1Said() = runTest(dispatcher) {
        val f = RampFixture().also { it.remote.status = AppResult.Success(unverified("IN_REVIEW", canStart = false)); it.remote.kyc = AppResult.Failure(AppError.Network) }
        val vm = vm(f)
        try { assertEquals(VerifyKind.InReview, verifyLook(vm.state.value.verify, 0).kind) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun theAppComingToTheFrontReadsACheckThatMayHaveMoved() = with(status = "IN_REVIEW", canStart = false) { vm, f ->
        val reads = f.remote.kycReads
        f.says("DECLINED", canStart = true, reason = KycReasons.FaceMatch)
        vm.onResumed()
        assertEquals(reads + 1, f.remote.kycReads)
        assertEquals(VerifyKind.Declined, vm.kind)
        // A settled state has nothing to re-read.
        vm.onResumed()
        assertEquals(reads + 1, f.remote.kycReads)
    }

    @Test fun reloadingStopsEverythingTheCheckWasDoing() = with(status = "IN_REVIEW", canStart = false) { vm, f ->
        f.remote.status = AppResult.Success(statusDto())
        vm.load()
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
        val reads = f.remote.kycReads
        advanceTimeBy(120_000)
        assertEquals(reads, f.remote.kycReads)
    }

    // ---- states straight from R1 ------------------------------------------------------------------------------------

    @Test fun everyStateTheServerCanDescribeHasItsWordsAndOneAction() {
        fun look(status: KycStatus, canStart: Boolean, reason: String? = null, retryAt: Long? = null) = verifyLook(VerifyUi(RampVerification(status, true, canStart, reason, retryAt)), now = 0)
        val tomorrow = 30L * 60 * 60 * 1000
        // The guide's table, row by row.
        assertEquals(VerifyKind.Intro to "Start Verification", look(KycStatus.None, true).let { it.kind to it.button })
        assertEquals(VerifyKind.Continue to "Continue Verification", look(KycStatus.Pending, true).let { it.kind to it.button })
        assertEquals(VerifyKind.OneMoreThing to "Continue Verification", look(KycStatus.Pending, true, KycReasons.ResubmissionRequested).let { it.kind to it.button })
        look(KycStatus.Pending, false, retryAt = tomorrow).let {
            assertEquals(VerifyKind.Waiting to VerifyAction.Close, it.kind to it.action)
            assertEquals("Today's tries are used up", it.title)
            assertTrue(it.message.contains("You can try again"), it.message)
        }
        assertEquals(VerifyKind.InReview to VerifyAction.Close, look(KycStatus.InReview, false).let { it.kind to it.action })
        assertEquals(VerifyKind.Verified to VerifyAction.None, look(KycStatus.Verified, false).let { it.kind to it.action })
        assertEquals(VerifyKind.Declined to "Try Again", look(KycStatus.Declined, true, KycReasons.Liveness).let { it.kind to it.button })
        look(KycStatus.Declined, false, KycReasons.Liveness, tomorrow).let {
            assertEquals(VerifyKind.Waiting to VerifyAction.Close, it.kind to it.action)
            assertEquals("The selfie check didn't pass", it.title)
            assertTrue(it.message.startsWith("Next time, be in good light, with your face uncovered. Today's tries are used up. You can try again "), it.message)
        }
        assertEquals(VerifyKind.DeclinedFinal to "Contact Support", look(KycStatus.Declined, false, KycReasons.NotApproved).let { it.kind to it.button })
        assertEquals(VerifyKind.Expired to "Verify Again", look(KycStatus.Expired, true, KycReasons.SessionExpired).let { it.kind to it.button })
        look(KycStatus.Expired, true, KycReasons.DocumentExpired).let {
            assertEquals("Your ID has expired", it.title)
            assertEquals("The ID you verified with is no longer valid. Verify again with one that is.", it.message)
            assertEquals("Verify Again", it.button)
        }
        assertEquals(VerifyKind.Waiting, look(KycStatus.Expired, false, KycReasons.SessionExpired, tomorrow).kind)
        // A status from a newer server is never shown as an ending: `canStart` alone decides.
        assertEquals(VerifyKind.Continue, look(KycStatus.Unknown, true).kind)
        assertEquals(VerifyKind.InReview to VerifyAction.Close, look(KycStatus.Unknown, false).let { it.kind to it.action })
        // Nothing ships the old name, and every state has words.
        KycStatus.entries.forEach { s -> listOf(true, false).forEach { c -> look(s, c).let { assertTrue(it.title.isNotBlank() && it.message.isNotBlank()); assertFalse((it.title + it.message).contains("CoinX")) } } }
    }

    @Test fun whenToComeBackIsSaidInThePersonsOwnDay() {
        val now = 1_795_341_600_000L // 2026-11-22T10:00:00Z
        assertNull(retryWhen(null, now))
        assertNull(retryWhen(now - 1, now), "a time already past is never promised")
        assertTrue(retryWhen(now + 60_000, now)!!.startsWith("today, after "))
        assertTrue(retryWhen(now + 24 * 60 * 60 * 1000L, now)!!.startsWith("tomorrow, after "))
        assertTrue(retryWhen(now + 3 * 24 * 60 * 60 * 1000L, now)!!.startsWith("on "))
    }

    @Test fun declinedWithTodaysTriesUsedComesBackWhenTheyDo() = with(status = "DECLINED", canStart = false, reason = KycReasons.Liveness, retryAt = "1970-01-01T01:00:00Z") { vm, f ->
        assertEquals(VerifyKind.Waiting, vm.kind)
        assertFalse(vm.state.value.canVerify)
        f.says("DECLINED", canStart = true, reason = KycReasons.Liveness)
        advanceTimeBy(3_602_000)
        assertEquals(VerifyKind.Declined, vm.kind)
        assertTrue(vm.state.value.canVerify)
    }

    // ---- offline ----------------------------------------------------------------------------------------------------

    @Test fun offlineNothingIsAskedAndTheButtonWaits() = with { vm, f ->
        online.value = false
        assertFalse(vm.state.value.canVerify)
        vm.onVerify()
        assertTrue(f.remote.kycStarts.isEmpty(), "verifying needs a connection: nothing is asked without one")
        assertFalse(vm.verify.starting)
        online.value = true
        assertTrue(vm.state.value.canVerify)
    }

    @Test fun offlineAWaitIsNotReadUntilTheConnectionIsBack() = with { vm, f ->
        f.says("PENDING")
        vm.onVerify()
        assertEquals(VerifyKind.Checking, vm.kind)
        online.value = false
        val reads = f.remote.kycReads
        advanceTimeBy(30_000)
        assertEquals(reads, f.remote.kycReads)
        vm.onResumed()
        assertEquals(reads, f.remote.kycReads)
        assertEquals(VerifyKind.Checking, vm.kind, "the wait is still a wait")
        online.value = true
        f.says("IN_REVIEW")
        advanceTimeBy(AddMoneyViewModel.ReviewMillis)
        assertEquals(VerifyKind.InReview, vm.kind)
    }

    // ---- the push -----------------------------------------------------------------------------------------------------

    @Test fun thePushOpensAddMoneyOnItsNetworkUnderTheWalletTab() = runTest(dispatcher) {
        val lookups = object : NotificationLookups {
            override suspend fun messageLocalId(messageId: String): Long? = null
            override suspend fun paymentCardLocalId(chatId: String, transferId: String): Long? = null
            override suspend fun lastCallLocalId(chatId: String): Long? = null
            override suspend fun chatOfPayment(key: String): String? = null
        }
        val destination = assertNotNull(NotificationDestination.fromPush(mapOf("kind" to "ramp.kyc", "network" to "MAINNET")))
        val opening = NotificationRoutes.resolve(destination, lookups)
        assertEquals(listOf<Any>(AddMoneyRoute(network = "MAINNET")), opening.routes)
        assertEquals(com.circaso.core.notifications.HomeTabName.Wallet, opening.tab)
        // A network this version doesn't know opens on the one the wallet shows.
        assertEquals(listOf<Any>(AddMoneyRoute()), NotificationRoutes.resolve(NotificationDestination.RampKyc("MOONNET"), lookups).routes)
        assertEquals(listOf<Any>(AddMoneyRoute()), NotificationRoutes.resolve(NotificationDestination.RampKyc(), lookups).routes)
    }

    @Test fun openedFromThePushTheFlowIsForThePushsNetworkAndLandsOnTheRightState() = runTest(dispatcher) {
        // "We couldn't verify your identity yet. Open Circaso to see what to do next.", for Mainnet, while the Wallet tab shows Testnet.
        val f = RampFixture().also { it.remote.status = AppResult.Success(unverified("DECLINED", canStart = true, reason = KycReasons.Document)) }
        val vm = vm(f, network = WalletNetwork.MAINNET, networks = WalletNetworks.fixed(WalletNetwork.TESTNET, switchable = true, known = true))
        try {
            assertEquals(VerifyKind.Declined, verifyLook(vm.state.value.verify, 0).kind)
            assertFalse(vm.state.value.testnet)
            vm.onVerify()
            assertTrue(f.remote.networks.all { it == WalletNetwork.MAINNET }, "R1, R0 and the read are all for the push's network")
            // A Testnet frame is not this flow's.
            f.frame("VERIFIED", network = "TESTNET")
            assertNotEquals(VerifyKind.Verified, verifyLook(vm.state.value.verify, 0).kind)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun thePushSayingVerifiedOpensStraightOnTheAmount() = runTest(dispatcher) {
        val f = RampFixture().also { it.cleared() }
        val vm = vm(f, network = WalletNetwork.TESTNET)
        try { assertEquals(AddMoneyStep.Amount, vm.state.value.step) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    private companion object { const val Hold = 1_800L }
}
