package com.circaso.feature.ramp

import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.feature.ramp.data.remote.KycResponse
import com.circaso.feature.ramp.domain.GetKycUseCase
import com.circaso.feature.ramp.domain.KycReasons
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.ObserveIdentityUseCase
import com.circaso.feature.ramp.domain.ObserveKycUpdatesUseCase
import com.circaso.feature.ramp.domain.ObserveRampUpdatesUseCase
import com.circaso.feature.ramp.domain.RunIdentityCheckUseCase
import com.circaso.feature.ramp.domain.StartKycUseCase
import com.circaso.feature.ramp.domain.VerifierOutcome
import com.circaso.feature.ramp.presentation.IdentityCheck
import com.circaso.feature.ramp.presentation.IdentityPage
import com.circaso.feature.ramp.presentation.IdentityRowKind
import com.circaso.feature.ramp.presentation.IdentityRowModel
import com.circaso.feature.ramp.presentation.IdentityRowUi
import com.circaso.feature.ramp.presentation.IdentityViewModel
import com.circaso.feature.ramp.presentation.RampBlock
import com.circaso.feature.ramp.presentation.VerifyAction
import com.circaso.feature.ramp.presentation.VerifyEntry
import com.circaso.feature.ramp.presentation.VerifyKind
import com.circaso.feature.ramp.presentation.VerifyTrouble
import com.circaso.feature.ramp.presentation.lastChecked
import com.circaso.feature.ramp.presentation.verifyLook
import com.circaso.feature.wallet.domain.NetworkOption
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import com.circaso.feature.wallet.domain.WalletNetworksState
import com.circaso.feature.wallet.domain.WalletStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * You › Verification with a scripted server and a scripted SDK: every status from the profile's entry, verifying
 * without a top-up, "You're verified" staying, the `submitted` rule, the phone's copy and offline, the frame, the
 * push's network, and a server that hasn't opened the routes (`ramp.unavailable`). The same check runs under Add Money
 * (`AddMoneyVerifyTest`); what is tested here is what differs and what the profile adds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val online = MutableStateFlow(true)
    private val sdk = FakeVerifier()
    private val pages = mutableListOf<String>()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val testnet = WalletNetworks.fixed(WalletNetwork.TESTNET, switchable = true, known = true)
    private val mainnet = WalletNetworks.fixed(WalletNetwork.MAINNET, switchable = true, known = true)

    private fun TestScope.vm(f: RampFixture, network: WalletNetwork? = null, networks: WalletNetworks = mainnet): IdentityViewModel {
        f.clock = { testScheduler.currentTime }
        return IdentityViewModel(
            ObserveIdentityUseCase(f.repo), StartKycUseCase(f.repo), GetKycUseCase(f.repo), ObserveKycUpdatesUseCase(f.repo), ObserveRampUpdatesUseCase(f.repo),
            RunIdentityCheckUseCase(verifier = { sdk }, openPage = { url -> pages += url; true }),
            online = online, networks = networks, now = { testScheduler.currentTime }, network = network,
        )
    }

    private fun fixture(networks: WalletNetworks = mainnet) = RampFixture(networks = networks)

    /** Someone standing at [status]; always ends the view model's loops. */
    private fun with(
        status: String = "NONE", canStart: Boolean = status in setOf("NONE", "PENDING", "EXPIRED", "DECLINED"), reason: String? = null, retryAt: String? = null,
        required: Boolean = true, submitted: Boolean = false, networks: WalletNetworks = mainnet,
        f: RampFixture = fixture(networks).also { it.remote.kyc = AppResult.Success(checkDto(status, canStart, reason, retryAt, required, submitted)) },
        block: suspend TestScope.(IdentityViewModel, RampFixture) -> Unit,
    ) = runTest(dispatcher) {
        val vm = vm(f, networks = networks)
        try { block(vm, f) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    private fun RampFixture.says(status: String, canStart: Boolean = status in setOf("NONE", "PENDING", "EXPIRED"), reason: String? = null, submitted: Boolean = false) {
        remote.kyc = AppResult.Success(checkDto(status, canStart, reason, submitted = submitted))
    }
    private suspend fun RampFixture.frame(status: String, network: String = "MAINNET", canStart: Boolean = false, submitted: Boolean = false) = repo.onFrame(
        "kyc.updated", circasoJson().parseToJsonElement("""{"network":"$network","status":"$status","required":true,"canStart":$canStart,"submitted":$submitted}""").jsonObject,
    )
    /** What the phone kept from an earlier visit. */
    private suspend fun RampFixture.kept(status: String, canStart: Boolean = false, submitted: Boolean = false, network: WalletNetwork = WalletNetwork.MAINNET) {
        val before = remote.kyc
        remote.kyc = AppResult.Success(checkDto(status, canStart, submitted = submitted))
        kotlinx.coroutines.withContext(com.circaso.feature.wallet.domain.PinnedNetwork(network)) { repo.kyc() }
        remote.kyc = before
        remote.kycReads = 0
        remote.networks.clear()
    }
    private val IdentityViewModel.look get() = verifyLook(state.value.verify, now = 0, entry = VerifyEntry.Profile)
    private val IdentityViewModel.kind get() = look.kind
    private val IdentityViewModel.page get() = state.value.page

    // ---- every status, from the profile ---------------------------------------------------------------------------

    @Test fun neverStartedOpensOnE3WithoutATopUp() = with { vm, f ->
        assertEquals(IdentityPage.Intro, vm.page)
        assertEquals(VerifyKind.Intro, vm.kind)
        assertEquals("Start Verification", vm.look.button)
        assertTrue(vm.state.value.canVerify)
        assertEquals(1, f.remote.kycReads, "the status is read from the verification's own route")
        assertEquals(0, f.remote.statusAsks, "R1 (the top-up's gates) isn't needed to verify")
        assertTrue(f.remote.kycStarts.isEmpty(), "nothing is started until the person taps")
        assertFalse(vm.state.value.verifyPage.wayOut, "Back leaves; there is no Not Now")
    }

    @Test fun verifyingFromTheProfileRunsTheSameCheckAndStaysOnVerified() = with { vm, f ->
        f.says("VERIFIED", canStart = false)
        vm.onVerify()
        assertEquals(1, f.remote.kycStarts.size)
        assertEquals(listOf(SessionToken), sdk.tokens)
        assertEquals(IdentityPage.Verified, vm.page)
        assertTrue(vm.state.value.justVerified, "it happened in front of the person")
        assertEquals("You're verified", vm.look.title)
        assertEquals(VerifyAction.None, vm.look.action)
        assertNull(vm.look.note, "nothing says it is going anywhere")
        // It stays: nothing more is asked of the top-up's routes, and nothing moves.
        advanceTimeBy(60_000)
        assertEquals(IdentityPage.Verified, vm.page)
        assertEquals(0, f.remote.statusAsks, "no jump into Add Money")
        val reads = f.remote.kycReads
        advanceTimeBy(120_000)
        assertEquals(reads, f.remote.kycReads, "nothing is read once it is settled")
    }

    @Test fun someoneAlreadyVerifiedSeesItCalmly() = with("VERIFIED") { vm, _ ->
        assertEquals(IdentityPage.Verified, vm.page)
        assertFalse(vm.state.value.justVerified, "no celebration for something that happened long ago")
        assertFalse(vm.state.value.canVerify)
        assertFalse(vm.state.value.lastKnown)
    }

    @Test fun anOpenCheckOffersContinue() = with("PENDING") { vm, f ->
        assertEquals(IdentityPage.State, vm.page)
        assertEquals(VerifyKind.Continue, vm.kind)
        assertEquals("Continue", vm.look.button)
        assertTrue(vm.state.value.canVerify)
        sdk.outcome = VerifierOutcome.Cancelled
        vm.onVerify()
        assertEquals(VerifyKind.Continue, vm.kind, "closing Didit's screens early leaves the check open")
        assertEquals(1, f.remote.kycStarts.size)
    }

    @Test fun aStepAskedAgainOffersContinue() = with("PENDING", reason = KycReasons.ResubmissionRequested) { vm, _ ->
        assertEquals(VerifyKind.OneMoreThing, vm.kind)
        assertTrue(vm.state.value.canVerify)
    }

    @Test fun aSubmittedCheckSaysWereCheckingWithNoButtonAndIsReadEveryFiveSeconds() = with("PENDING", canStart = false, submitted = true) { vm, f ->
        assertEquals(IdentityPage.State, vm.page)
        assertEquals(VerifyKind.Checking, vm.kind)
        assertEquals("We're checking your details", vm.look.title)
        assertEquals(VerifyAction.Close to "Done", vm.look.action to vm.look.button)
        assertFalse(vm.state.value.canVerify)
        vm.onVerify()
        assertTrue(f.remote.kycStarts.isEmpty(), "R0 is never asked while the check is submitted")
        assertTrue(sdk.tokens.isEmpty(), "and the SDK is never reopened")
        val reads = f.remote.kycReads
        advanceTimeBy(IdentityCheck.CheckMillis + 1)
        assertEquals(reads + 1, f.remote.kycReads)
        advanceTimeBy(90_000)
        val later = f.remote.kycReads
        advanceTimeBy(IdentityCheck.CheckMillis)
        assertEquals(later + 1, f.remote.kycReads, "every five seconds for as long as the screen is open")
        // The decision arrives while the person watches: it stays on "You're verified".
        f.says("VERIFIED", canStart = false)
        advanceTimeBy(IdentityCheck.CheckMillis)
        assertEquals(IdentityPage.Verified, vm.page)
        assertTrue(vm.state.value.justVerified)
    }

    @Test fun submittedIsReadBeforeCanStart() = with("PENDING", canStart = true, submitted = true) { vm, f ->
        assertEquals(VerifyKind.Checking, vm.kind)
        assertFalse(vm.state.value.canVerify)
        vm.onVerify()
        assertTrue(f.remote.kycStarts.isEmpty())
    }

    @Test fun r0SayingSubmittedNeverOpensTheSdk() = with("PENDING") { vm, f ->
        f.remote.kycStart = AppResult.Success(KycResponse("PENDING", required = true, canStart = false, submitted = true, session = null))
        f.says("PENDING", canStart = false, submitted = true)
        vm.onVerify()
        assertTrue(sdk.tokens.isEmpty())
        assertEquals(VerifyKind.Checking, vm.kind)
    }

    @Test fun inReviewWaitsWithNothingToTap() = with("IN_REVIEW", submitted = true) { vm, f ->
        assertEquals(VerifyKind.InReview, vm.kind)
        assertFalse(vm.state.value.canVerify)
        val reads = f.remote.kycReads
        advanceTimeBy(IdentityCheck.ReviewMillis + 1)
        assertEquals(reads + 1, f.remote.kycReads)
    }

    @Test fun aDeclineAnotherTryCanFixOffersTryAgain() = with("DECLINED", reason = KycReasons.FaceMatch) { vm, _ ->
        assertEquals(VerifyKind.Declined, vm.kind)
        assertEquals("Your selfie didn't match your ID", vm.look.title)
        assertEquals("Try Again", vm.look.button)
        assertTrue(vm.state.value.canVerify)
    }

    @Test fun aDeclineForGoodPointsToSupport() = with("DECLINED", canStart = false, reason = KycReasons.NotApproved) { vm, f ->
        assertEquals(VerifyKind.DeclinedFinal, vm.kind)
        assertEquals(VerifyAction.Support, vm.look.action)
        assertFalse(vm.state.value.canVerify)
        vm.onVerify()
        assertTrue(f.remote.kycStarts.isEmpty())
    }

    @Test fun outOfTimeOffersVerifyAgain() = with("EXPIRED", reason = KycReasons.SessionExpired) { vm, _ ->
        assertEquals(VerifyKind.Expired, vm.kind)
        assertEquals("Verify Again", vm.look.button)
    }

    @Test fun triesUsedUpWaitsForTheTime() = with("DECLINED", canStart = false, reason = KycReasons.Liveness, retryAt = "2030-01-01T09:05:00Z") { vm, _ ->
        assertEquals(VerifyKind.Waiting, vm.kind)
        assertEquals(VerifyAction.Close to "Done", vm.look.action to vm.look.button)
        assertFalse(vm.state.value.canVerify)
    }

    @Test fun whereItIsNotRequiredItCanStillBeDoneWhenTheServerOffersIt() = with("NONE", canStart = true, required = false) { vm, f ->
        assertEquals(IdentityPage.Intro, vm.page)
        assertTrue(vm.state.value.canVerify)
        // Offered, not asked for: never said to be the law.
        assertFalse(vm.look.message.contains("by law"), vm.look.message)
        assertTrue(vm.look.message.contains("doesn't need it yet"), vm.look.message)
        f.says("VERIFIED", canStart = false)
        vm.onVerify()
        assertEquals(1, f.remote.kycStarts.size)
    }

    @Test fun whereItIsNotRequiredAndCannotBeStartedItSaysNoVerificationIsNeeded() = with("NONE", canStart = false, required = false) { vm, f ->
        assertEquals(IdentityPage.NotNeeded, vm.page)
        assertFalse(vm.state.value.canVerify)
        vm.onVerify()
        assertTrue(f.remote.kycStarts.isEmpty())
    }

    @Test fun verifiedWhereItIsNotRequiredIsStillVerified() = with("VERIFIED", required = false) { vm, _ ->
        assertEquals(IdentityPage.Verified, vm.page)
    }

    // ---- errors ---------------------------------------------------------------------------------------------------------

    @Test fun aServerThatHasNotOpenedTheRoutesSaysNotAvailableYetNeverAnError() = runTest(dispatcher) {
        val f = fixture().also { it.remote.kyc = refusal("ramp.unavailable", http = 403) }
        val vm = vm(f)
        try {
            assertEquals(IdentityPage.Unavailable, vm.page)
            assertEquals(RampBlock.Unavailable, vm.state.value.gate)
            assertNull(vm.state.value.failure, "it isn't an error")
            assertFalse(vm.state.value.canVerify)
            vm.onVerify()
            assertTrue(f.remote.kycStarts.isEmpty())
            // The server opens the routes: the next read shows the check.
            f.says("NONE")
            vm.read()
            assertEquals(IdentityPage.Intro, vm.page)
            assertTrue(vm.state.value.canVerify)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun r0AnsweringNotAvailableGoesToTheSamePage() = with { vm, f ->
        f.remote.kycStart = refusal("ramp.unavailable", http = 403)
        vm.onVerify()
        assertEquals(IdentityPage.Unavailable, vm.page)
        assertTrue(sdk.tokens.isEmpty())
    }

    @Test fun aBarAndAPausedAccountAreTheirOwnPage() = with { vm, f ->
        f.remote.kycStart = refusal("ramp.blocked", http = 403)
        vm.onVerify()
        assertEquals(IdentityPage.Gate, vm.page)
        assertEquals(RampBlock.Barred, vm.state.value.gate)
        f.remote.kyc = refusal("ramp.account_restricted", http = 403)
        vm.read()
        assertEquals(RampBlock.Restricted, vm.state.value.gate)
        assertFalse(vm.state.value.canVerify)
    }

    @Test fun aFaultWithNothingToShowSaysSoWithItsReferenceAndCanBeTriedAgain() = runTest(dispatcher) {
        val f = fixture().also { it.remote.kyc = refusal("service.unavailable", http = 503) }
        val vm = vm(f)
        try {
            assertEquals(IdentityPage.LoadFailed, vm.page)
            val failure = assertNotNull(vm.state.value.failure)
            assertTrue(failure.reference!!.contains("req-1"), failure.reference)
            assertFalse(failure.message.contains("Something went wrong"), failure.message)
            f.says("NONE")
            vm.read()
            assertEquals(IdentityPage.Intro, vm.page)
            assertNull(vm.state.value.failure)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun didItBeingOutOfReachAndTheOtherStartErrorsAreTheChecksOwnStates() = with { vm, f ->
        f.remote.kycStart = refusal("ramp.kyc_unavailable", http = 502)
        vm.onVerify()
        assertEquals(VerifyKind.ProviderDown, vm.kind)
        assertEquals(VerifyTrouble.ProviderDown, vm.state.value.verify.trouble)
        assertTrue(vm.state.value.verify.reference!!.contains("req-1"))
        f.remote.kycStart = refusal("ramp.kyc_not_configured", http = 503)
        vm.onVerify()
        assertEquals(VerifyKind.NotConfigured, vm.kind)
        assertEquals("Done", vm.look.button)
        f.says("NONE")
        vm.read()
        f.remote.kycStart = refusal("ramp.kyc_attempts_exceeded", retryAfter = 3_600, http = 429)
        vm.onVerify()
        assertEquals(VerifyKind.TriesUsedUp, vm.kind)
    }

    @Test fun theCameraBeingRefusedPointsToSettings() = with { vm, _ ->
        sdk.outcome = VerifierOutcome.CameraDenied
        vm.onVerify()
        assertEquals(VerifyKind.CameraDenied, vm.kind)
        assertEquals(VerifyAction.Settings, vm.look.action)
    }

    // ---- the phone's copy, and offline ------------------------------------------------------------------------------

    @Test fun thePhonesCopyShowsAtOnceAndTheServersAnswerReplacesIt() = runTest(dispatcher) {
        val f = fixture()
        f.kept("PENDING", canStart = true)
        f.remote.kycHold = CompletableDeferred()
        f.says("VERIFIED", canStart = false)
        val vm = vm(f)
        try {
            // Before the server has answered: what the phone knew.
            assertEquals(IdentityPage.State, vm.page)
            assertEquals(VerifyKind.Continue, vm.kind)
            f.remote.kycHold!!.complete(Unit)
            assertEquals(IdentityPage.Verified, vm.page)
            assertFalse(vm.state.value.justVerified, "the copy turning out to be old is no event")
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun offlineItShowsTheLastKnownStatusMarkedAsSuchAndVerifyingWaits() = runTest(dispatcher) {
        val f = fixture()
        f.kept("PENDING", canStart = true)
        online.value = false
        f.remote.kyc = AppResult.Failure(AppError.Network)
        val vm = vm(f)
        try {
            assertEquals(VerifyKind.Continue, vm.kind)
            assertTrue(vm.state.value.lastKnown)
            assertNotNull(vm.state.value.checkedAt)
            assertFalse(vm.state.value.canVerify, "verifying needs a connection")
            vm.onVerify()
            assertTrue(f.remote.kycStarts.isEmpty())
            assertTrue(lastChecked(vm.state.value, now = 0)!!.startsWith("Showing your last known status, checked "))
            // Back online: it is read at once, and the mark goes.
            f.says("PENDING")
            online.value = true
            assertFalse(vm.state.value.lastKnown)
            assertTrue(vm.state.value.canVerify)
            assertNull(lastChecked(vm.state.value, now = 0))
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun offlineSomeoneVerifiedStillSeesVerified() = runTest(dispatcher) {
        val f = fixture()
        f.kept("VERIFIED")
        online.value = false
        f.remote.kyc = AppResult.Failure(AppError.Network)
        val vm = vm(f)
        try {
            assertEquals(IdentityPage.Verified, vm.page)
            assertTrue(vm.state.value.lastKnown)
            assertFalse(vm.state.value.justVerified)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun offlineWithNothingKeptSaysWhyAndReadsWhenTheConnectionIsBack() = runTest(dispatcher) {
        val f = fixture().also { it.remote.kyc = AppResult.Failure(AppError.Network) }
        online.value = false
        val vm = vm(f)
        try {
            assertEquals(IdentityPage.LoadFailed, vm.page)
            assertFalse(vm.state.value.canVerify)
            f.says("NONE")
            online.value = true
            assertEquals(IdentityPage.Intro, vm.page)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun aReadThatGetsNoAnswerKeepsTheCopyAndSaysItCouldNotCheck() = runTest(dispatcher) {
        val f = fixture()
        f.kept("DECLINED", canStart = true)
        f.remote.kyc = refusal("service.unavailable", http = 503)
        val vm = vm(f)
        try {
            assertEquals(VerifyKind.Declined, vm.kind)
            assertTrue(vm.state.value.lastKnown)
            assertTrue(lastChecked(vm.state.value, now = 0)!!.startsWith("Couldn't check just now."))
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun aVerifiedCopyIsNotFinalWhenTheServerSaysTheIdHasExpired() = runTest(dispatcher) {
        val f = fixture()
        f.kept("VERIFIED")
        f.remote.kyc = AppResult.Success(checkDto("EXPIRED", canStart = true, reason = KycReasons.DocumentExpired))
        val vm = vm(f)
        try {
            assertEquals(IdentityPage.State, vm.page)
            assertEquals(VerifyKind.Expired, vm.kind)
            assertEquals("Your ID has expired", vm.look.title)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun aCopyThatSaidNotAvailableGivesWayOnceTheServerOpens() = runTest(dispatcher) {
        val f = fixture()
        f.remote.kyc = refusal("ramp.unavailable", http = 403)
        f.repo.kyc()
        f.remote.kycReads = 0
        f.says("NONE")
        val vm = vm(f)
        try { assertEquals(IdentityPage.Intro, vm.page) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    // ---- frames, the push, coming back --------------------------------------------------------------------------------

    @Test fun theFrameMovesTheScreenForItsOwnNetworkOnly() = with("PENDING", canStart = false, submitted = true) { vm, f ->
        f.frame("VERIFIED", network = "TESTNET")
        assertEquals(VerifyKind.Checking, vm.kind, "a Testnet check is not this screen's")
        f.frame("VERIFIED")
        assertEquals(IdentityPage.Verified, vm.page)
        assertTrue(vm.state.value.justVerified)
        // Verified in this visit is final: a replayed older frame never walks it back.
        f.frame("PENDING", canStart = true)
        assertEquals(IdentityPage.Verified, vm.page)
    }

    @Test fun theFrameFlippingSubmittedTakesTheButtonAway() = with("PENDING") { vm, f ->
        assertTrue(vm.state.value.canVerify)
        f.frame("PENDING", submitted = true)
        assertEquals(VerifyKind.Checking, vm.kind)
        assertFalse(vm.state.value.canVerify)
    }

    @Test fun openedFromThePushItIsForThePushsNetwork() = runTest(dispatcher) {
        // "You're verified" for Mainnet, while the Wallet tab shows Testnet.
        val f = fixture(testnet).also { it.remote.kyc = AppResult.Success(checkDto("VERIFIED", canStart = false)) }
        val vm = vm(f, network = WalletNetwork.MAINNET, networks = testnet)
        try {
            assertEquals(IdentityPage.Verified, vm.page)
            assertEquals(WalletNetwork.MAINNET, vm.state.value.network)
            assertFalse(vm.state.value.testnet)
            assertTrue(f.remote.networks.all { it == WalletNetwork.MAINNET }, "every call is for the push's network")
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun onTestnetItSaysSoAndReadsTestnetsOwnCheck() = with("NONE", networks = testnet) { vm, f ->
        assertTrue(vm.state.value.testnet)
        assertEquals(WalletNetwork.TESTNET, vm.state.value.network)
        assertTrue(vm.state.value.verifyPage.testnet)
        assertTrue(f.remote.networks.all { it == WalletNetwork.TESTNET })
    }

    @Test fun comingBackToTheAppReadsASettledStatusAgain() = with("VERIFIED") { vm, f ->
        val reads = f.remote.kycReads
        vm.onPaused()
        vm.onResumed()
        assertEquals(reads + 1, f.remote.kycReads)
        // The ID has expired since: the screen never shows yesterday's status.
        f.remote.kyc = AppResult.Success(checkDto("EXPIRED", canStart = true, reason = KycReasons.DocumentExpired))
        vm.onResumed()
        assertEquals(VerifyKind.Expired, vm.kind)
    }

    @Test fun leavingTheAppRestsTheReadsOfAWait() = with("PENDING", canStart = false, submitted = true) { vm, f ->
        vm.onPaused()
        val away = f.remote.kycReads
        advanceTimeBy(30_000)
        assertEquals(away, f.remote.kycReads)
        vm.onResumed()
        assertEquals(away + 1, f.remote.kycReads)
    }

    @Test fun aSessionNeverReachesTheState() = with { vm, _ ->
        sdk.open = CompletableDeferred()
        vm.onVerify()
        val shown = vm.state.value.toString()
        assertFalse(shown.contains(SessionToken), shown)
        assertFalse(shown.contains("didit"), shown)
        sdk.open!!.complete(Unit)
    }

    // ---- the profile's row ---------------------------------------------------------------------------------------------

    private fun TestScope.row(f: RampFixture, networks: WalletNetworks = mainnet): Pair<IdentityRowModel, MutableStateFlow<IdentityRowUi?>> {
        val model = IdentityRowModel(ObserveIdentityUseCase(f.repo), GetKycUseCase(f.repo), online = online, networks = networks, now = { testScheduler.currentTime })
        val shown = MutableStateFlow<IdentityRowUi?>(null)
        model.ui.onEach { shown.value = it }.launchIn(backgroundScope)
        model.start(backgroundScope)
        return model to shown
    }
    private val MutableStateFlow<IdentityRowUi?>.look get() = assertIs<IdentityRowUi.Shown>(value).look

    @Test fun theRowAsksOnOpeningAndShowsTheAnswer() = runTest(dispatcher) {
        val f = fixture().also { it.remote.kyc = AppResult.Success(checkDto("VERIFIED", canStart = false)) }
        val (_, shown) = row(f)
        assertEquals(1, f.remote.kycReads)
        assertEquals(IdentityRowKind.Verified, shown.look.kind)
        assertFalse(shown.look.lastKnown)
    }

    @Test fun theRowIsASkeletonUntilTheFirstAnswer() = runTest(dispatcher) {
        val f = fixture().also { it.remote.kycHold = CompletableDeferred() }
        val (_, shown) = row(f)
        assertEquals(IdentityRowUi.Loading, shown.value)
        f.remote.kycHold!!.complete(Unit)
        assertEquals(IdentityRowKind.InProgress, shown.look.kind)
    }

    @Test fun theRowShowsThePhonesCopyOfflineAndCatchesUpWhenBack() = runTest(dispatcher) {
        val f = fixture()
        f.kept("NONE", canStart = true)
        online.value = false
        f.remote.kyc = AppResult.Failure(AppError.Network)
        val (_, shown) = row(f)
        assertEquals(IdentityRowKind.NotVerified, shown.look.kind)
        assertTrue(shown.look.lastKnown)
        assertEquals("Last known status. Verifying needs a connection.", shown.look.note)
        f.says("VERIFIED", canStart = false)
        online.value = true
        assertEquals(IdentityRowKind.Verified, shown.look.kind)
        assertFalse(shown.look.lastKnown)
    }

    @Test fun theRowLeavesWhereTheServerHasNoVerificationToOffer() = runTest(dispatcher) {
        val f = fixture().also { it.remote.kyc = refusal("ramp.unavailable", http = 403) }
        val (model, shown) = row(f)
        assertEquals(IdentityRowUi.Hidden, shown.value)
        // And comes back when the server opens the routes.
        f.says("NONE")
        model.refresh()
        assertEquals(IdentityRowKind.NotVerified, shown.look.kind)
    }

    @Test fun theRowFollowsTheFrameAndThePush() = runTest(dispatcher) {
        val f = fixture().also { it.remote.kyc = AppResult.Success(checkDto("PENDING", canStart = false, submitted = true)) }
        val (_, shown) = row(f)
        assertEquals(IdentityRowKind.Checking, shown.look.kind)
        f.frame("IN_REVIEW", submitted = true)
        assertEquals(IdentityRowKind.InReview, shown.look.kind)
        // The push carries no state: the check is read for its network, and the row follows the copy.
        f.says("VERIFIED", canStart = false)
        com.circaso.feature.ramp.data.repository.IdentityPush.received(mapOf("kind" to "ramp.kyc", "network" to "MAINNET"), backgroundScope) { f.repo }
        assertEquals(IdentityRowKind.Verified, shown.look.kind)
        // A Testnet frame is not this network's row.
        f.frame("DECLINED", network = "TESTNET", canStart = true)
        assertEquals(IdentityRowKind.Verified, shown.look.kind)
    }

    @Test fun theRowIsForTheNetworkInUseAndAsksAgainWhenItChanges() = runTest(dispatcher) {
        val state = MutableStateFlow(
            WalletNetworksState(options = WalletNetwork.entries.map { NetworkOption(it, WalletStatus.Active) }, selected = WalletNetwork.MAINNET, default = WalletNetwork.MAINNET, known = true, loaded = true),
        )
        val networks = object : WalletNetworks { override val state = state }
        val f = RampFixture(networks = networks).also { it.remote.kyc = AppResult.Success(checkDto("VERIFIED", canStart = false)) }
        val (_, shown) = row(f, networks)
        assertEquals("Your identity is confirmed", shown.look.sub)
        // Testnet has its own check: a Mainnet verification says nothing about it.
        f.says("NONE")
        state.value = state.value.copy(selected = WalletNetwork.TESTNET)
        assertEquals(IdentityRowKind.NotVerified, shown.look.kind)
        assertEquals("Testnet · Needed to move money in or out of Circaso", shown.look.sub)
        assertEquals(2, f.remote.kycReads)
        // Back on Mainnet its own copy shows at once.
        f.remote.kycHold = CompletableDeferred()
        state.value = state.value.copy(selected = WalletNetwork.MAINNET)
        assertEquals(IdentityRowKind.Verified, shown.look.kind)
        f.remote.kycHold!!.complete(Unit)
    }

    @Test fun aRowReadThatGetsNoAnswerMarksTheStatusAsLastKnown() = runTest(dispatcher) {
        val f = fixture()
        f.kept("VERIFIED")
        f.remote.kyc = refusal("service.unavailable", http = 503)
        val (_, shown) = row(f)
        assertEquals(IdentityRowKind.Verified, shown.look.kind)
        assertEquals("Last known status. Couldn't check just now.", shown.look.note)
    }
}
