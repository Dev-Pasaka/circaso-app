package com.circaso.feature.auth

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.circasoJson
import com.circaso.core.session.AuthManager
import com.circaso.core.session.DeviceIdentity
import com.circaso.core.session.SessionDto
import com.circaso.core.session.SessionStore
import com.circaso.feature.auth.domain.AuthRepository
import com.circaso.feature.auth.domain.PhoneVerification
import com.circaso.feature.auth.domain.RegisterDeviceUseCase
import com.circaso.feature.auth.domain.SignInOutcome
import com.circaso.feature.auth.domain.SignInStep
import com.circaso.feature.auth.domain.SignInWithSocialUseCase
import com.circaso.feature.auth.domain.SocialCredential
import com.circaso.feature.auth.domain.SocialProvider
import com.circaso.feature.auth.domain.SocialSignIn
import com.circaso.feature.auth.domain.SocialSignInResult
import com.circaso.feature.auth.domain.StartPhoneVerificationUseCase
import com.circaso.feature.auth.domain.VerifyPhoneCodeUseCase
import com.circaso.feature.auth.presentation.CodeViewModel
import com.circaso.feature.auth.presentation.PhoneViewModel
import com.circaso.feature.auth.presentation.RegisteringViewModel
import com.circaso.feature.auth.presentation.WelcomeViewModel
import com.circaso.testing.FakeKeys
import com.circaso.testing.MemoryStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeRepo : AuthRepository {
    var nonce: AppResult<String> = AppResult.Success("n-1")
    var social: AppResult<SignInOutcome> = AppResult.Success(SignInOutcome(SignInStep.VerifyPhone, "+254 7•• ••• 678"))
    var start: AppResult<PhoneVerification> = AppResult.Success(PhoneVerification("v-1", 6, 300, 30))
    var verify: AppResult<SignInStep> = AppResult.Success(SignInStep.RegisterDevice)
    var register: AppResult<Unit> = AppResult.Success(Unit)
    val verifyCalls = mutableListOf<Pair<String, String>>()
    var startCalls = 0
    var registerCalls = 0

    override suspend fun requestNonce() = nonce
    override suspend fun signInWithSocial(provider: SocialProvider, credential: SocialCredential, nonce: String) = social
    override suspend fun startPhoneVerification(phone: String): AppResult<PhoneVerification> { startCalls++; return start }
    override suspend fun verifyPhoneCode(verificationId: String, code: String): AppResult<SignInStep> { verifyCalls += verificationId to code; return verify }
    override suspend fun verifyPhoneWithCarrier(carrierToken: String) = verify
    override suspend fun registerDevice(pushToken: String?): AppResult<Unit> { registerCalls++; return register }
    override suspend fun signOut() = Unit
    override suspend fun forgetThisPhone() = AppResult.Success(Unit)
}

private class Social(var result: SocialSignInResult) : SocialSignIn {
    override suspend fun signIn(provider: SocialProvider, nonce: String) = result
}

private fun api(code: String, status: Int = 400, retryAfter: Int? = null) =
    AppResult.Failure(AppError.Api(code, status, "req-1", retryAfter, emptyMap()))

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeRepo()
    private val storage = MemoryStorage()
    private val store = SessionStore(storage, circasoJson())
    private val identity = DeviceIdentity(storage, FakeKeys())
    private val auth = AuthManager(store, identity, { error("no refresh") }, { 1_000L })

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ---- Welcome

    private fun welcome(social: SocialSignInResult = SocialSignInResult.Success(SocialCredential("t"))) =
        WelcomeViewModel(SignInWithSocialUseCase(repo, Social(social)), auth)

    @Test fun welcomeSuccessLeavesNoErrorAndNoSpinner() = runTest(dispatcher) {
        val vm = welcome(); vm.onContinue(SocialProvider.Google)
        assertEquals(WelcomeViewModel.UiState(), vm.state.value)
    }

    @Test fun welcomeCancelledSaysNothing() = runTest(dispatcher) {
        val vm = welcome(SocialSignInResult.Cancelled); vm.onContinue(SocialProvider.Google)
        assertNull(vm.state.value.error)
    }

    @Test fun welcomeSaysPlainlyWhenTheProviderIsNotSetUp() = runTest(dispatcher) {
        val vm = welcome(SocialSignInResult.Unavailable); vm.onContinue(SocialProvider.Apple)
        assertEquals("Signing in with Apple isn't available in this version yet.", vm.state.value.error)
    }

    @Test fun welcomeFailureNamesTheProvider() = runTest(dispatcher) {
        val vm = welcome(SocialSignInResult.Failed); vm.onContinue(SocialProvider.Google)
        assertEquals("Couldn't reach Google. Try again.", vm.state.value.error)
    }

    @Test fun welcomeOfflineUsesTheOfflineWords() = runTest(dispatcher) {
        repo.nonce = AppResult.Failure(AppError.Network)
        val vm = welcome(); vm.onContinue(SocialProvider.Google)
        assertEquals(ErrorMessages.Offline, vm.state.value.error)
    }

    @Test fun welcomeShowsTheSignedOutNoticeWhenTheServerEndedTheSession() = runTest(dispatcher) {
        auth.startSession(SessionDto("a", "r", 900, "app"), null)
        auth.signOutLocally(endedByServer = true)
        assertTrue(welcome().state.value.signedOutNotice)
        auth.signOutLocally(endedByServer = false)
        assertFalse(welcome().state.value.signedOutNotice)
    }

    // ---- Phone

    private fun phone() = PhoneViewModel(StartPhoneVerificationUseCase(repo), auth)

    @Test fun theFieldHoldsOnlyTheNationalDigits() = runTest(dispatcher) {
        val vm = phone()
        vm.onInput("0712345678"); assertEquals("712345678", vm.state.value.input)
        vm.onInput("+254 712 345 678"); assertEquals("712345678", vm.state.value.input)
        vm.onInput("712-345-6789999"); assertEquals("712345678", vm.state.value.input) // never more than nine
    }

    @Test fun phoneTooShortShowsTheDesignsMessageAndSendsNothing() = runTest(dispatcher) {
        val vm = phone(); vm.onInput("7123"); vm.onSendCode()
        assertEquals("That number looks too short. Kenyan numbers have 9 digits after +254.", vm.state.value.error)
        assertEquals(0, repo.startCalls)
    }

    /** Types [text] one key at a time, as the keyboard does. */
    private fun PhoneViewModel.type(text: String) = text.forEach { ch -> onInput(state.value.input + ch) }

    @Test fun phoneAnotherCountryShowsItsOwnLength() = runTest(dispatcher) {
        val vm = phone(); vm.onCountry(com.circaso.feature.auth.domain.Countries.byIso("GB")!!); vm.type("77009"); vm.onSendCode()
        assertEquals("That number looks too short. Numbers with +44 have 10 digits after the code.", vm.state.value.error)
        assertEquals(0, repo.startCalls)
    }

    @Test fun phoneATypedPlusCodePicksItsCountryKeyByKey() = runTest(dispatcher) {
        val vm = phone(); vm.type("+447700900123")
        assertEquals("GB", vm.state.value.country.iso)
        assertEquals("7700900123", vm.state.value.input)
        vm.onSendCode()
        assertEquals("+447700900123", (vm.eventFlow.first() as PhoneViewModel.Event.CodeSent).phone)
    }

    @Test fun phoneAPlusOneWaitsUntilTheCodeIsCertain() = runTest(dispatcher) {
        val vm = phone(); vm.type("+1")
        assertEquals("KE", vm.state.value.country.iso, "+1 could still be +1268")
        assertEquals("+1", vm.state.value.input)
        vm.type("202")
        assertEquals("US", vm.state.value.country.iso)
        assertEquals("202", vm.state.value.input)
    }

    @Test fun phoneTypingNeverStripsWhatLooksLikeTheCallingCode() = runTest(dispatcher) {
        val vm = phone(); vm.onCountry(com.circaso.feature.auth.domain.Countries.byIso("DE")!!); vm.type("04921123456")
        assertEquals("4921123456", vm.state.value.input, "the 0 goes, the 49 stays")
    }

    @Test fun phoneAPastedNumberDropsItsCallingCode() = runTest(dispatcher) {
        val vm = phone(); vm.onCountry(com.circaso.feature.auth.domain.Countries.byIso("GB")!!); vm.onInput("44 7700 900123")
        assertEquals("7700900123", vm.state.value.input)
    }

    @Test fun phonePickingACountryKeepsTheDigitsAndClosesThePicker() = runTest(dispatcher) {
        val vm = phone(); vm.type("712"); vm.onPickCountry(true); vm.onQuery("uga")
        assertEquals("UG", vm.state.value.countries.first().iso)
        vm.onCountry(vm.state.value.countries.first())
        assertEquals("712", vm.state.value.input)
        assertFalse(vm.state.value.picking)
        assertEquals("", vm.state.value.query)
    }

    @Test fun phoneThePickerListsKenyaThenTheChosenCountry() = runTest(dispatcher) {
        val vm = phone(); vm.onCountry(com.circaso.feature.auth.domain.Countries.byIso("GB")!!)
        assertEquals(listOf("KE", "GB"), vm.state.value.countries.take(2).map { it.iso })
    }

    @Test fun phoneValidNumberEmitsTheCodeSentEvent() = runTest(dispatcher) {
        val vm = phone(); vm.onInput("712345678"); vm.onSendCode()
        val event = vm.eventFlow.first() as PhoneViewModel.Event.CodeSent
        assertEquals("+254712345678", event.phone)
        assertEquals("v-1", event.verification.verificationId)
        assertFalse(vm.state.value.busy)
    }

    @Test fun phoneAlreadyRegisteredShowsTheTakenScreen() = runTest(dispatcher) {
        repo.start = api("identity.phone_taken", 409)
        val vm = phone(); vm.onInput("712345678"); vm.onSendCode()
        assertTrue(vm.state.value.numberTaken)
        vm.onUseDifferentNumber()
        assertFalse(vm.state.value.numberTaken)
        assertEquals("", vm.state.value.input)
    }

    @Test fun phoneRateLimitSaysHowLongToWait() = runTest(dispatcher) {
        repo.start = api("request.rate_limited", 429, retryAfter = 600)
        val vm = phone(); vm.onInput("712345678"); vm.onSendCode()
        assertEquals("Too many codes sent. Try again in 10 minutes.", vm.state.value.error)
    }

    @Test fun phoneEditingClearsTheError() = runTest(dispatcher) {
        val vm = phone(); vm.onInput("71"); vm.onSendCode(); vm.onInput("712")
        assertNull(vm.state.value.error)
    }

    // ---- Code

    private fun code() = CodeViewModel(
        phone = "+254712345678", verificationId = "v-1", codeLength = 6, resendAfterSeconds = 30,
        verify = VerifyPhoneCodeUseCase(repo), start = StartPhoneVerificationUseCase(repo),
    )

    @Test fun codeMasksTheNumber() = assertEquals("+254 7•• ••• 678", CodeViewModel.mask("+254712345678"))

    @Test fun codeSubmitsByItselfAtSixDigitsAndOnlyDigitsCount() = runTest(dispatcher) {
        val vm = code(); vm.onCode("41a79")
        assertEquals(0, repo.verifyCalls.size)
        vm.onCode("417902")
        assertEquals(listOf("v-1" to "417902"), repo.verifyCalls)
    }

    @Test fun aWrongCodeShowsAnErrorClearsTheBoxesAndAllowsAnotherTry() = runTest(dispatcher) {
        repo.verify = api("identity.code_invalid")
        val vm = code(); vm.onCode("111111")
        assertEquals("", vm.state.value.code)
        assertEquals("That code isn't right. Check it and try again.", vm.state.value.error)
        assertFalse(vm.state.value.locked)
        repo.verify = AppResult.Success(SignInStep.RegisterDevice)
        vm.onCode("222222")
        assertEquals(2, repo.verifyCalls.size)
    }

    @Test fun tooManyWrongCodesLocksTheInput() = runTest(dispatcher) {
        repo.verify = api("identity.code_locked", 429)
        val vm = code(); vm.onCode("111111")
        assertTrue(vm.state.value.locked)
        vm.onCode("222222")
        assertEquals(1, repo.verifyCalls.size) // typing does nothing while locked
    }

    @Test fun resendWaitsForTheCountdownThenSendsANewCodeWithTheNewId() = runTest(dispatcher) {
        val vm = code()
        assertFalse(vm.state.value.canResend)
        advanceTimeBy(31_000)
        assertTrue(vm.state.value.canResend)
        repo.start = AppResult.Success(PhoneVerification("v-2", 6, 300, 45))
        vm.onResend()
        assertEquals(45, vm.state.value.resendInSeconds)
        repo.verify = AppResult.Success(SignInStep.RegisterDevice)
        vm.onCode("123456")
        assertEquals("v-2", repo.verifyCalls.single().first)
    }

    @Test fun anExpiredCodeLetsThePersonResendAtOnce() = runTest(dispatcher) {
        repo.verify = api("identity.code_expired")
        val vm = code(); vm.onCode("123456")
        assertTrue(vm.state.value.canResend)
    }

    // ---- Registering

    @Test fun registeringRunsOnceAndStaysQuietOnSuccess() = runTest(dispatcher) {
        val vm = RegisteringViewModel(RegisterDeviceUseCase(repo))
        assertEquals(1, repo.registerCalls)
        assertNull(vm.state.value.error)
    }

    @Test fun registeringFailureShowsWordsAndAReferenceAndRetryWorks() = runTest(dispatcher) {
        repo.register = api("identity.device_attestation_failed", 403)
        val vm = RegisteringViewModel(RegisterDeviceUseCase(repo))
        assertEquals("We couldn't confirm this phone is genuine.", vm.state.value.error)
        assertEquals("req-1", vm.state.value.reference)
        repo.register = AppResult.Success(Unit)
        vm.retry()
        assertEquals(2, repo.registerCalls)
        assertNull(vm.state.value.error)
    }
}
