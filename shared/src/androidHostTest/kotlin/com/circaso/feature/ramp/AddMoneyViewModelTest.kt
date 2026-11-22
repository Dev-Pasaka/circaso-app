package com.circaso.feature.ramp

import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.ramp.data.remote.toDomain
import com.circaso.feature.ramp.domain.BuyWithMpesaUseCase
import com.circaso.feature.ramp.domain.CancelRampOrderUseCase
import com.circaso.feature.ramp.domain.GetOwnMpesaNumberUseCase
import com.circaso.feature.ramp.domain.GetRampOrderUseCase
import com.circaso.feature.ramp.domain.GetRampStatusUseCase
import com.circaso.feature.ramp.domain.ObserveRampUpdatesUseCase
import com.circaso.feature.ramp.domain.QuoteTopUpUseCase
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.presentation.AddMoneyStep
import com.circaso.feature.ramp.presentation.AddMoneyUiState
import com.circaso.feature.ramp.presentation.AddMoneyViewModel
import com.circaso.feature.ramp.presentation.RampBlock
import com.circaso.feature.ramp.presentation.clock
import com.circaso.feature.ramp.presentation.AddMoneyPage
import com.circaso.feature.ramp.presentation.StageMark
import com.circaso.feature.ramp.presentation.feeLine
import com.circaso.feature.ramp.presentation.feeText
import com.circaso.feature.ramp.presentation.figureLabels
import com.circaso.feature.ramp.presentation.orderStages
import com.circaso.feature.ramp.presentation.pageOf
import com.circaso.feature.ramp.presentation.shortReference
import com.circaso.feature.ramp.presentation.orderLook
import com.circaso.feature.ramp.presentation.rateLine
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
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

/**
 * Add money (design E2, routes R1–R6) with a scripted server: the happy path, each gate, the price running out, each
 * way an order ends, resuming, cancelling, and that nothing is asked of the server offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddMoneyViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val online = MutableStateFlow(true)
    private val masked = MutableStateFlow<String?>(Masked)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(f: RampFixture, orderId: String? = null) = AddMoneyViewModel(
        orderId, GetRampStatusUseCase(f.repo), QuoteTopUpUseCase(f.repo), BuyWithMpesaUseCase(f.repo), GetRampOrderUseCase(f.repo), CancelRampOrderUseCase(f.repo),
        ObserveRampUpdatesUseCase(f.repo), GetOwnMpesaNumberUseCase(f.repo),
        maskedPhone = masked, online = online, networks = WalletNetworks.fixed(WalletNetwork.TESTNET, known = true),
        now = { testScheduler.currentTime }, pollMillis = 4_000,
    )

    /** Runs [block] with a view model and always ends its loops, so a failed assertion never leaves the clock spinning. */
    private fun with(f: RampFixture = RampFixture(phones = MemoryPhone(Phone)), orderId: String? = null, block: suspend TestScope.(AddMoneyViewModel, RampFixture) -> Unit) = runTest(dispatcher) {
        val vm = vm(f, orderId)
        try { block(vm, f) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    private fun AddMoneyViewModel.toReview(amount: String = "2000") { onAmount(amount); onContinue() }
    private fun RampFixture.answer(state: String, txHash: String? = null, reason: String? = null) { remote.order = AppResult.Success(orderDto(state, txHash = txHash, reason = reason)) }
    private suspend fun RampFixture.frame(state: String, reason: String? = null) = repo.onFrame(
        "order.updated",
        com.circaso.core.network.circasoJson().parseToJsonElement("""{"orderId":"o1","direction":"ON_RAMP","state":"$state","blockchain":"BASE-SEPOLIA"${reason?.let { ""","failureReason":"$it"""" } ?: ""}}""").jsonObject,
    )

    // ---- the happy path ------------------------------------------------------------------------------------------

    @Test fun fromTheAmountToUsdcInTheWallet() = with { vm, f ->
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
        assertEquals("BASE-SEPOLIA", vm.state.value.blockchain)
        assertTrue(vm.state.value.testnet)
        assertEquals(Masked, vm.state.value.maskedPhone)

        vm.toReview()
        val review = vm.state.value
        assertEquals(AddMoneyStep.Review, review.step)
        assertEquals("KES 2,000", review.quote?.pay?.text)
        assertEquals("15.31 USDC", review.quote?.receive?.text)
        assertEquals(60, review.secondsLeft)
        assertFalse(review.needsPhone, "this phone already holds the number")
        assertTrue(review.canConfirm)

        vm.onConfirm()
        assertEquals("q1" to Phone, f.remote.buys.single().first.let { it.quoteId to it.phone })
        assertEquals(AddMoneyStep.Order, vm.state.value.step)
        assertEquals(RampOrderState.PaymentRequested, vm.state.value.order?.state)
        assertEquals("Check your phone", orderLook(vm.state.value.order!!, vm.state.value.everPaid).title)

        // The frame moves it at once, and the order is read again for the rest.
        f.answer("PAID")
        f.frame("PAID")
        assertEquals(RampOrderState.Paid, vm.state.value.order?.state)
        assertTrue(vm.state.value.everPaid)

        // No frame this time: the poll finds it.
        f.answer("RELEASING")
        advanceTimeBy(4_001)
        assertEquals(RampOrderState.Releasing, vm.state.value.order?.state)

        f.answer("COMPLETED", txHash = "0xabc")
        advanceTimeBy(4_001)
        assertEquals(RampOrderState.Completed, vm.state.value.order?.state)
        assertEquals(1, f.arrivals, "the wallet's balance is read again once the USDC is in")
        assertEquals("15.31 USDC added", orderLook(vm.state.value.order!!, true).title)

        val reads = f.remote.reads.size
        advanceTimeBy(60_000)
        assertEquals(reads, f.remote.reads.size, "a finished order is not polled")
        assertTrue(f.remote.networks.all { it == WalletNetwork.TESTNET }, "every call is for the network the flow opened on")
    }

    // ---- the gates ------------------------------------------------------------------------------------------------

    @Test fun aRampThatIsNotOpenToThePersonSaysSo() = with(RampFixture().also { it.remote.status = refusal("ramp.unavailable") }) { vm, _ ->
        assertEquals(AddMoneyStep.Blocked, vm.state.value.step)
        assertEquals(RampBlock.Unavailable, vm.state.value.block)
    }

    @Test fun anUnverifiedPersonSeesVerifyFromR1() = with(RampFixture().also { it.remote.status = AppResult.Success(statusDto(kyc = "NONE")) }) { vm, _ ->
        assertEquals(RampBlock.Verify, vm.state.value.block)
    }

    @Test fun kycRequiredFromAnyRouteIsTheSameGate() = with { vm, f ->
        f.remote.quote = refusal("ramp.kyc_required")
        vm.toReview()
        assertEquals(AddMoneyStep.Blocked, vm.state.value.step)
        assertEquals(RampBlock.Verify, vm.state.value.block)
    }

    @Test fun kycNotRequiredOrVerifiedLetsThePersonIn() = with(RampFixture().also { it.remote.status = AppResult.Success(statusDto(kyc = "VERIFIED")) }) { vm, _ ->
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
    }

    @Test fun anAccountThatCannotTradeIsToldToContactSupport() = with(RampFixture().also { it.remote.status = AppResult.Success(statusDto(active = false)) }) { vm, f ->
        assertEquals(RampBlock.Restricted, vm.state.value.block)
        f.remote.status = refusal("ramp.account_restricted")
        vm.load()
        assertEquals(RampBlock.Restricted, vm.state.value.block)
    }

    @Test fun noMerchantOnlineIsTryAgainSoonAndTryAgainAsksR1Again() = with(RampFixture().also { it.remote.status = AppResult.Success(statusDto(merchant = false)) }) { vm, f ->
        assertEquals(RampBlock.NoMerchant, vm.state.value.block)
        f.remote.status = AppResult.Success(statusDto())
        vm.load()
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
        assertEquals(2, f.remote.statusAsks)
    }

    @Test fun aFailedLoadSaysWhyWithItsReferenceAndCanBeTriedAgain() = with(RampFixture().also { it.remote.status = refusal("service.unavailable") }) { vm, f ->
        assertEquals(AddMoneyStep.LoadFailed, vm.state.value.step)
        assertTrue(vm.state.value.failure!!.reference!!.startsWith("req-1"), "the request id is on the error screen")
        f.remote.status = AppResult.Failure(AppError.Network)
        vm.load()
        assertEquals("Adding money needs a connection. No M-Pesa request was sent.", vm.state.value.failure?.message)
        f.remote.status = AppResult.Success(statusDto())
        vm.load()
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
    }

    // ---- the amount -----------------------------------------------------------------------------------------------

    @Test fun theAmountIsWholeShillingsWithinTheLimits() = with { vm, f ->
        vm.onAmount("50")
        assertEquals("The least you can add is KES 100.", vm.state.value.amountError)
        assertFalse(vm.state.value.canQuote)
        vm.onAmount("200000")
        assertEquals("The most you can add at once is KES 150,000.", vm.state.value.amountError)
        vm.onAmount("2,000.50")
        assertEquals("200050", vm.state.value.amountText, "only digits are kept")
        assertTrue(vm.state.value.wholeOnlyHint)
        vm.onAmount("")
        assertNull(vm.state.value.amountError)
        assertFalse(vm.state.value.canQuote)
        vm.onContinue()
        assertTrue(f.remote.quotes.isEmpty(), "nothing is priced until the amount is right")
        vm.onAmount("2000")
        assertTrue(vm.state.value.canQuote)
        assertEquals(listOf("500", "2000", "5000"), vm.state.value.chips)
    }

    @Test fun quickAmountsOutsideTheLimitsAreNotOffered() = with(RampFixture().also {
        it.remote.status = AppResult.Success(statusDto().copy(limits = com.circaso.feature.ramp.data.remote.LimitsDto("KES", kes("1000"), kes("3000"), kes("3000"))))
    }) { vm, _ ->
        assertEquals(listOf("2000"), vm.state.value.chips)
    }

    @Test fun aPriceThatCannotBeHadStaysOnTheAmountWithTheReason() = with { vm, f ->
        f.remote.quote = refusal("ramp.limit_exceeded")
        vm.toReview()
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
        assertEquals("That would take you over your limit of KES 300,000 a day.", vm.state.value.failure?.message)
        assertEquals("req-1", vm.state.value.failure?.reference)
        f.remote.quote = refusal("ramp.pricing_unavailable")
        vm.onContinue()
        assertEquals("We can't get a price right now. Try again in a moment.", vm.state.value.failure?.message)
    }

    // ---- the price and its minute ---------------------------------------------------------------------------------

    @Test fun thePriceCountsDownAndRunsOut() = with { vm, f ->
        vm.toReview()
        advanceTimeBy(18_001)
        assertEquals(42, vm.state.value.secondsLeft)
        advanceTimeBy(42_000)
        assertEquals(0, vm.state.value.secondsLeft)
        assertTrue(vm.state.value.quoteExpired)
        assertFalse(vm.state.value.canConfirm)
        vm.onConfirm()
        assertTrue(f.remote.buys.isEmpty(), "a price that ran out is never bought")

        f.remote.quote = AppResult.Success(quoteDto("q2"))
        vm.onNewPrice()
        assertEquals("q2", vm.state.value.quote?.quoteId)
        assertEquals(60, vm.state.value.secondsLeft)
        assertTrue(vm.state.value.canConfirm)
    }

    @Test fun theServersDeadlineIsUsedWhenItIsSoonerAndNeverMoreThanAMinute() = runTest(dispatcher) {
        // The phone's clock (the virtual one) starts at 0: a deadline 30 s on is honoured, one an hour on is read as a minute.
        val soon = RampFixture(phones = MemoryPhone(Phone)).also { it.remote.quote = AppResult.Success(quoteDto(expiresAt = "1970-01-01T00:00:30Z")) }
        val a = vm(soon)
        try { a.toReview(); assertEquals(30, a.state.value.secondsLeft) } finally { a.viewModelScope.coroutineContext[Job]?.cancel() }
        val late = RampFixture(phones = MemoryPhone(Phone)).also { it.remote.quote = AppResult.Success(quoteDto(expiresAt = "1970-01-01T01:00:00Z")) }
        val b = vm(late)
        try { b.toReview(); assertEquals(60, b.state.value.secondsLeft) } finally { b.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun aPriceThatRanOutAtTheServerIsFetchedAgainAndShownNotBought() = with { vm, f ->
        vm.toReview()
        f.remote.buy = refusal("ramp.quote_expired")
        f.remote.quote = AppResult.Success(quoteDto("q2"))
        vm.onConfirm()
        val s = vm.state.value
        assertEquals(AddMoneyStep.Review, s.step)
        assertEquals("q2", s.quote?.quoteId)
        assertTrue(s.repriced, "the person is told the price changed")
        assertEquals(1, f.remote.buys.size, "the new price waits for its own tap")
        assertNull(s.order)
    }

    @Test fun goingBackToTheAmountDropsThePrice() = with { vm, _ ->
        vm.toReview()
        vm.onBackToAmount()
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
        assertNull(vm.state.value.quote)
    }

    // ---- idempotency ----------------------------------------------------------------------------------------------

    @Test fun aRetryThatGotNoAnswerReusesItsKeyAndARefusalGetsANewOne() = with { vm, f ->
        vm.toReview()
        f.remote.buy = AppResult.Failure(AppError.Network)
        vm.onConfirm()
        assertTrue(vm.state.value.failure!!.message.contains("never sends a second request"))
        assertEquals(AddMoneyStep.Review, vm.state.value.step)
        vm.onConfirm()
        assertEquals(f.remote.buys[0].second, f.remote.buys[1].second, "no answer: the same action, the same key")

        f.remote.buy = refusal("ramp.collections_unavailable")
        vm.onConfirm()
        assertEquals(f.remote.buys[1].second, f.remote.buys[2].second)
        assertEquals("We couldn't reach M-Pesa. Nothing was taken. Try again in a moment.", vm.state.value.failure?.message)
        assertEquals("req-1", vm.state.value.failure?.reference)
        f.remote.buy = AppResult.Success(orderDto("PAYMENT_REQUESTED"))
        vm.onConfirm()
        assertNotEquals(f.remote.buys[2].second, f.remote.buys[3].second, "the server answered: the next try is a new action")
        assertEquals(AddMoneyStep.Order, vm.state.value.step)
    }

    @Test fun aServerThatIsBusyOrStillWorkingKeepsTheKeyAndNeverSaysNothingHappened() = with { vm, f ->
        vm.toReview()
        f.remote.buy = AppResult.Failure(AppError.Api("service.unavailable", 503, "req-5", null, emptyMap()))
        vm.onConfirm()
        assertTrue(vm.state.value.failure!!.message.contains("Nothing is taken unless you approve"))
        f.remote.buy = AppResult.Failure(AppError.Api("request.in_progress", 409, "req-6", null, emptyMap()))
        vm.onConfirm()
        f.remote.buy = AppResult.Failure(AppError.Unexpected(502))
        vm.onConfirm()
        vm.onConfirm()
        assertEquals(1, f.remote.buys.map { it.second }.toSet().size, "503, request.in_progress and an unreadable answer are the same action")
    }

    @Test fun aQuoteRetryKeepsItsKeyUntilTheServerAnswersOrTheAmountChanges() = with { vm, f ->
        f.remote.quote = AppResult.Failure(AppError.Network)
        vm.toReview()
        vm.onContinue()
        assertEquals(f.remote.quotes[0].second, f.remote.quotes[1].second)
        vm.onAmount("3000")
        vm.onContinue()
        assertNotEquals(f.remote.quotes[1].second, f.remote.quotes[2].second)
    }

    // ---- the number -----------------------------------------------------------------------------------------------

    @Test fun aPhoneThatDoesNotHoldTheNumberAsksOnceAndChecksItAgainstTheMask() = with(RampFixture()) { vm, f ->
        vm.toReview()
        assertTrue(vm.state.value.needsPhone)
        vm.onPhone("0712")
        vm.onConfirm()
        assertEquals("Type your full M-Pesa number, like 0712 345 678.", vm.state.value.phoneError)
        vm.onPhone("0712 345 999")
        vm.onConfirm()
        assertEquals("That isn't the number your account is verified with. It ends in 678.", vm.state.value.phoneError)
        assertTrue(f.remote.buys.isEmpty(), "no request leaves the phone for a number that can't be the account's")
        vm.onPhone("0712 345 678")
        vm.onConfirm()
        assertEquals(Phone, f.remote.buys.single().first.phone)
        assertEquals(Phone, f.phones.phone, "kept for next time once the server took it")
        assertFalse(vm.state.value.needsPhone)
    }

    @Test fun aNumberTheServerSaysIsNotTheirsIsAskedForAgain() = with { vm, f ->
        vm.toReview()
        f.remote.buy = refusal("ramp.phone_not_yours")
        vm.onConfirm()
        assertTrue(vm.state.value.needsPhone)
        assertEquals("Pay from the number your account is verified with.", vm.state.value.phoneError)
        assertNull(f.phones.phone)
        assertEquals(AddMoneyStep.Review, vm.state.value.step)
    }

    @Test fun tooManyPromptsSaysHowLongToWait() = with { vm, f ->
        vm.toReview()
        f.remote.buy = refusal("ramp.too_many_prompts", retryAfter = 3600)
        vm.onConfirm()
        assertEquals("Too many M-Pesa requests in a short time. Try again in 60 minutes. Nothing was taken.", vm.state.value.failure?.message)
    }

    @Test fun noMerchantForThisAmountStaysOnTheReviewWithTheReason() = with { vm, f ->
        vm.toReview()
        f.remote.buy = refusal("ramp.no_merchant_available")
        vm.onConfirm()
        assertEquals(AddMoneyStep.Review, vm.state.value.step)
        assertTrue(vm.state.value.failure!!.message.startsWith("Nobody can fill that amount right now."))
    }

    @Test fun aSellerWhoCannotTakeThePaymentSaysNothingWasTakenAndToTryAgain() = with { vm, f ->
        vm.toReview()
        f.remote.buy = AppResult.Failure(AppError.Api("ramp.merchant_unavailable", 409, "req-9", null, emptyMap()))
        vm.onConfirm()
        val s = vm.state.value
        assertEquals(AddMoneyStep.Review, s.step, "still on the review, ready to try again")
        assertEquals("We couldn't start this payment right now. Nothing was taken. Please try again in a moment.", s.failure?.message)
        assertEquals("req-9", s.failure?.reference)
        assertTrue(s.canConfirm)
        f.remote.buy = AppResult.Success(orderDto("PAYMENT_REQUESTED"))
        vm.onConfirm()
        assertNotEquals(f.remote.buys[0].second, f.remote.buys[1].second, "a definite no: the next try is a new action")
        assertEquals(AddMoneyStep.Order, vm.state.value.step)
    }

    @Test fun anAnswerWithNoWordsOfItsOwnNeverSaysSomethingWentWrongOnOurSide() = with(RampFixture(phones = MemoryPhone(Phone)).also { it.remote.status = AppResult.Failure(AppError.Api("upstream.error", 502, "req-1", null, emptyMap())) }) { vm, f ->
        // Opening: nothing was asked of M-Pesa.
        assertEquals("We couldn't open Add Money right now. No M-Pesa request was sent. Please try again in a moment.", vm.state.value.failure?.message)
        f.remote.status = AppResult.Success(statusDto())
        vm.load()
        // The price.
        f.remote.quote = AppResult.Failure(AppError.Api("upstream.error", 502, "req-2", null, emptyMap()))
        vm.toReview()
        assertEquals("We couldn't get a price right now. No M-Pesa request was sent. Please try again in a moment.", vm.state.value.failure?.message)
        f.remote.quote = AppResult.Success(quoteDto())
        vm.onContinue()
        // The request: today a seller who can't take it arrives as an upstream error. It isn't certain nothing went
        // out, so the words say what is true whichever it was.
        f.remote.buy = AppResult.Failure(AppError.Api("upstream.error", 502, "req-3", null, emptyMap()))
        vm.onConfirm()
        val said = vm.state.value.failure!!.message
        assertTrue(said.startsWith("We couldn't start this payment right now."), said)
        assertTrue(said.contains("Nothing is taken unless you approve"), said)
        // A refusal this version has no words for is still a definite no.
        f.remote.buy = AppResult.Failure(AppError.Api("ramp.something_new", 422, "req-4", null, emptyMap()))
        vm.onConfirm()
        assertEquals("We couldn't start this payment right now. Nothing was taken. Please try again in a moment.", vm.state.value.failure?.message)
        assertFalse(listOf(said, vm.state.value.failure!!.message).any { it.contains("on our side") })
    }

    @Test fun theLastPriceSaysRoughlyWhatAnotherAmountBuys() = with { vm, _ ->
        vm.onAmount("2000")
        assertNull(vm.state.value.estimate, "R1 has no rate: nothing is guessed before a price was had")
        vm.onContinue()
        vm.onBackToAmount()
        assertEquals("15.31 USDC", vm.state.value.estimate?.text)
        vm.onAmount("4000")
        assertEquals("30.62 USDC", vm.state.value.estimate?.text)
        vm.onAmount("50")
        assertNull(vm.state.value.estimate, "an amount that can't be bought has no estimate")
        vm.onAmount("")
        assertNull(vm.state.value.estimate)
    }

    @Test fun theFirstPaintKnowsWhetherAnOrderIsComing() = with(RampFixture().also { it.remote.order = AppResult.Failure(AppError.Network) }, orderId = "o1") { vm, _ ->
        assertTrue(vm.state.value.loadingOrder, "opened on an order: the skeleton is an order's")
        assertFalse(AddMoneyUiState().loadingOrder)
    }

    // ---- how an order ends ----------------------------------------------------------------------------------------

    private fun ended(state: String, reason: String?, title: String, nothingTaken: Boolean?) = with { vm, f ->
        vm.toReview(); vm.onConfirm()
        f.answer(state, reason = reason)
        f.frame(state, reason)
        val s = vm.state.value
        assertEquals(RampOrderState.of(state), s.order?.state)
        val look = orderLook(s.order!!, s.everPaid)
        assertEquals(title, look.title)
        assertTrue(look.ended)
        if (nothingTaken != null) assertEquals(nothingTaken, look.message.contains("othing was taken"), look.message)
        assertFalse(s.canCancel)
        val reads = f.remote.reads.size
        advanceTimeBy(30_000)
        assertEquals(reads, f.remote.reads.size, "an order that ended is not polled")
        assertEquals(0, f.arrivals)
    }

    @Test fun aFailedPaymentSaysNothingWasTaken() = ended("FAILED", "payment_failed", "The top-up didn't go through", nothingTaken = true)
    @Test fun anExpiredRequestSaysNothingWasTaken() = ended("EXPIRED", "expired", "The M-Pesa request timed out", nothingTaken = true)
    @Test fun aCancelledOrderSaysNothingWasTaken() = ended("CANCELLED", "cancelled", "Top-up cancelled", nothingTaken = true)
    @Test fun anOrderInReviewPointsToSupportAndNeverSaysNothingWasTaken() = ended("REVIEW", "payment_mismatch", "We're checking this top-up", nothingTaken = false)
    @Test fun aFailureThisVersionDoesNotKnowNeverClaimsNothingWasTaken() = ended("FAILED", "release_failed", "The top-up didn't go through", nothingTaken = false)

    @Test fun anOrderThatWasPaidAndThenFailedNeverSaysNothingWasTaken() = with { vm, f ->
        vm.toReview(); vm.onConfirm()
        f.answer("PAID"); f.frame("PAID")
        f.answer("FAILED", reason = "payment_failed"); f.frame("FAILED", "payment_failed")
        val s = vm.state.value
        assertTrue(s.everPaid)
        assertFalse(orderLook(s.order!!, s.everPaid).message.contains("othing was taken"))
        assertTrue(orderLook(s.order!!, s.everPaid).needsReference)
    }

    @Test fun aPaymentThatLandsAfterTheRequestTimedOutIsFollowedAgain() = with { vm, f ->
        vm.toReview(); vm.onConfirm()
        f.answer("EXPIRED", reason = "expired"); f.frame("EXPIRED", "expired")
        assertEquals(RampOrderState.Expired, vm.state.value.order?.state)
        f.answer("PAID"); f.frame("PAID")
        assertEquals(RampOrderState.Paid, vm.state.value.order?.state)
        f.answer("COMPLETED", txHash = "0x1")
        advanceTimeBy(4_001)
        assertEquals(RampOrderState.Completed, vm.state.value.order?.state)
    }

    @Test fun tryAgainAfterAFailedOrderStartsANewTopUpWithTheSameAmount() = with { vm, f ->
        vm.toReview(); vm.onConfirm()
        f.answer("FAILED", reason = "payment_failed"); f.frame("FAILED", "payment_failed")
        vm.onAgain()
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
        assertEquals("2000", vm.state.value.amountText)
        assertNull(vm.state.value.order)
        assertFalse(vm.state.value.everPaid)
    }

    @Test fun aCheckThatGetsNoAnswerSaysSoAndKeepsTrying() = with { vm, f ->
        vm.toReview(); vm.onConfirm()
        f.remote.order = AppResult.Failure(AppError.Network)
        advanceTimeBy(4_001)
        assertTrue(vm.state.value.stale)
        assertEquals(RampOrderState.PaymentRequested, vm.state.value.order?.state, "a failed check never changes what the order is")
        f.answer("PAID")
        advanceTimeBy(4_001)
        assertFalse(vm.state.value.stale)
        assertEquals(RampOrderState.Paid, vm.state.value.order?.state)
    }

    @Test fun aFrameForAnotherOrderIsNotThisScreens() = with { vm, f ->
        vm.toReview(); vm.onConfirm()
        val reads = f.remote.reads.size
        f.repo.onFrame("order.updated", com.circaso.core.network.circasoJson().parseToJsonElement("""{"orderId":"other","state":"FAILED"}""").jsonObject)
        assertEquals(RampOrderState.PaymentRequested, vm.state.value.order?.state)
        assertEquals(reads, f.remote.reads.size)
    }

    @Test fun aResyncReadsTheOrderOnScreenAgain() = with { vm, f ->
        vm.toReview(); vm.onConfirm()
        f.answer("RELEASING")
        f.repo.reload()
        assertEquals(RampOrderState.Releasing, vm.state.value.order?.state)
    }

    // ---- resuming -------------------------------------------------------------------------------------------------

    @Test fun anOpenOrderFromR1ResumesOnItsStatus() = with(RampFixture().also {
        it.remote.status = AppResult.Success(statusDto(open = "o1"))
        it.remote.order = AppResult.Success(orderDto("RELEASING"))
    }) { vm, f ->
        assertEquals(AddMoneyStep.Order, vm.state.value.step)
        assertEquals(RampOrderState.Releasing, vm.state.value.order?.state)
        assertEquals(listOf("o1"), f.remote.reads)
        f.answer("COMPLETED", txHash = "0x1")
        advanceTimeBy(4_001)
        assertEquals(RampOrderState.Completed, vm.state.value.order?.state, "a resumed order is followed like a new one")
    }

    @Test fun openedFromAPushItShowsThatOrderWithoutAskingR1() = with(RampFixture().also { it.remote.order = AppResult.Success(orderDto("COMPLETED", txHash = "0x1")) }, orderId = "o1") { vm, f ->
        assertEquals(AddMoneyStep.Order, vm.state.value.step)
        assertEquals(RampOrderState.Completed, vm.state.value.order?.state)
        assertEquals(0, f.remote.statusAsks)
        assertEquals(1, f.arrivals)
    }

    @Test fun anOrderThatCannotBeReadSaysSoAndTryAgainStartsFromR1() = with(RampFixture().also { it.remote.order = refusal("ramp.order_not_found") }, orderId = "gone") { vm, _ ->
        assertEquals(AddMoneyStep.LoadFailed, vm.state.value.step)
        assertEquals("We couldn't find that top-up.", vm.state.value.failure?.message)
        assertTrue(vm.state.value.orderUnread)
        vm.load()
        assertEquals(AddMoneyStep.Amount, vm.state.value.step)
    }

    @Test fun buyingWhileAnotherOrderIsOpenResumesThatOrder() = with { vm, f ->
        vm.toReview()
        f.remote.buy = refusal("ramp.order_in_progress")
        f.remote.status = AppResult.Success(statusDto(open = "o1"))
        f.remote.order = AppResult.Success(orderDto("PAYMENT_REQUESTED"))
        vm.onConfirm()
        assertEquals(AddMoneyStep.Order, vm.state.value.step)
        assertEquals("o1", vm.state.value.order?.orderId)
    }

    @Test fun anOrderThatDoesNotLoadNeverSaysNothingWasSent() = with(RampFixture().also { it.remote.order = AppResult.Failure(AppError.Network) }, orderId = "o1") { vm, _ ->
        val s = vm.state.value
        assertEquals(AddMoneyStep.LoadFailed, s.step)
        assertTrue(s.orderUnread)
        assertFalse(s.failure!!.message.contains("othing was sent") || s.failure!!.message.contains("No M-Pesa request"))
        assertTrue(s.failure!!.message.contains("your USDC still arrives"))
    }

    @Test fun anEndingWhereMoneyMayHaveGoneLeadsToSupportNotTryAgain() {
        val paidThenFailed = orderLook(orderDto("FAILED", reason = "payment_failed").toDomain(), everPaid = true)
        assertTrue(paidThenFailed.support)
        assertTrue(paidThenFailed.message.contains("Please don't pay again."))
        assertFalse(orderLook(orderDto("FAILED", reason = "payment_failed").toDomain(), everPaid = false).support)
        assertFalse(orderLook(orderDto("EXPIRED", reason = "expired").toDomain(), everPaid = false).support)
        assertTrue(orderLook(orderDto("REVIEW", reason = "payment_mismatch").toDomain(), everPaid = false).support)
        assertFalse(orderLook(orderDto("COMPLETED").toDomain(), everPaid = true).support)
    }

    @Test fun theAmountNeverHasMoreDigitsThanTheMostAllowed() = with { vm, _ ->
        vm.onAmount("123456789")
        assertEquals("123456", vm.state.value.amountText, "the most allowed, KES 150,000, has six digits")
    }

    // ---- cancelling -----------------------------------------------------------------------------------------------

    @Test fun cancelIsOnlyOfferedBeforeThePaymentAndTheServerHasTheLastWord() = with { vm, f ->
        vm.toReview(); vm.onConfirm()
        assertTrue(vm.state.value.canCancel)
        f.remote.cancel = refusal("ramp.order_not_cancellable")
        vm.onCancel()
        assertEquals("The M-Pesa request is still open on your phone. Dismiss it there and this ends by itself.", vm.state.value.cancelNote)
        assertEquals(RampOrderState.PaymentRequested, vm.state.value.order?.state)

        f.remote.cancel = AppResult.Success(orderDto("FAILED", reason = "payment_failed"))
        vm.onCancel()
        assertEquals(RampOrderState.Failed, vm.state.value.order?.state)
        assertNotEquals(f.remote.cancels[0].second, f.remote.cancels[1].second)
    }

    @Test fun aPaidOrderCannotBeCancelled() = with { vm, f ->
        vm.toReview(); vm.onConfirm()
        f.answer("PAID"); f.frame("PAID")
        assertFalse(vm.state.value.canCancel)
        vm.onCancel()
        assertTrue(f.remote.cancels.isEmpty())
    }

    // ---- offline: money is never queued ---------------------------------------------------------------------------

    @Test fun offlineNothingIsAskedOfTheServerAndNothingIsQueued() = with { vm, f ->
        online.value = false
        vm.onAmount("2000")
        assertFalse(vm.state.value.canQuote)
        vm.onContinue()
        assertTrue(f.remote.quotes.isEmpty())

        online.value = true
        vm.onContinue()
        online.value = false
        assertFalse(vm.state.value.canConfirm)
        vm.onConfirm()
        assertTrue(f.remote.buys.isEmpty(), "no M-Pesa request is made, now or later")
        online.value = true
        assertTrue(f.remote.buys.isEmpty(), "coming back online sends nothing by itself")
    }

    @Test fun offlineAnOrderIsNotPolledAndPicksUpWhenBack() = with { vm, f ->
        vm.toReview(); vm.onConfirm()
        online.value = false
        val reads = f.remote.reads.size
        advanceTimeBy(20_000)
        assertEquals(reads, f.remote.reads.size)
        vm.onCancel()
        assertTrue(f.remote.cancels.isEmpty(), "no cancel is sent offline")
        f.answer("PAID")
        online.value = true
        advanceTimeBy(4_001)
        assertEquals(RampOrderState.Paid, vm.state.value.order?.state)
    }

    // ---- words ----------------------------------------------------------------------------------------------------

    @Test fun everyStateHasItsOwnWords() {
        val titles = RampOrderState.entries.associateWith { state ->
            val order = orderDto(state.name).toDomain().copy(state = state)
            orderLook(order, everPaid = state.paid).also { look ->
                assertTrue(look.title.isNotBlank() && look.message.isNotBlank())
                assertEquals(state.settled, look.ended, "$state")
                assertFalse((look.title + look.message).contains("CoinX"))
            }.title
        }
        assertEquals(RampOrderState.entries.size, titles.values.toSet().size, "no two states share a title")
        assertNotNull(titles[RampOrderState.Unknown])
    }

    @Test fun theStoryOfAnOrderMovesOneStepAtATime() {
        fun marks(state: RampOrderState) = orderStages(state).map { it.mark }
        val (w, a, d) = Triple(StageMark.Waiting, StageMark.Active, StageMark.Done)
        assertEquals(listOf(a, w, w, w), marks(RampOrderState.Reserved))
        assertEquals(listOf(d, a, w, w), marks(RampOrderState.PaymentRequested))
        assertEquals(listOf(d, d, a, w), marks(RampOrderState.Paid))
        assertEquals(listOf(d, d, a, w), marks(RampOrderState.Releasing))
        assertEquals("Request sent to your phone", orderStages(RampOrderState.PaymentRequested).first().text)
        assertEquals("Payment confirmed by M-Pesa", orderStages(RampOrderState.Paid)[1].text)
        assertNotEquals(orderStages(RampOrderState.Paid)[2].text, orderStages(RampOrderState.Releasing)[2].text, "paid and releasing read differently")
        // What is still to come is never worded as if it had happened.
        orderStages(RampOrderState.PaymentRequested).drop(2).forEach { assertFalse(it.text.contains("Added") || it.text.contains("on its way") || it.text.contains("sent"), it.text) }
        // A top-up that landed has every step done; any other ending says how it ended instead, and a state this
        // version doesn't know can't say which step it is.
        assertEquals(listOf(d, d, d, d), marks(RampOrderState.Completed))
        assertEquals("Added to your wallet", orderStages(RampOrderState.Completed).last().text)
        RampOrderState.entries.filter { (it.settled && it != RampOrderState.Completed) || it == RampOrderState.Unknown }.forEach { assertTrue(orderStages(it).isEmpty(), "$it") }
        RampOrderState.entries.filter { !it.settled && it != RampOrderState.Unknown }.forEach { assertEquals(1, marks(it).count { m -> m == a }, "$it has one step under way") }
    }

    @Test fun theFiguresNeverClaimMoneyMovedWhenItDidNot() {
        fun labels(state: String, reason: String? = null, paid: Boolean = false) = orderDto(state, reason = reason).toDomain().let { figureLabels(it.state, orderLook(it, paid)) }
        assertEquals("You pay" to "You get", labels("PAYMENT_REQUESTED"))
        assertEquals("You paid" to "You got", labels("COMPLETED", paid = true))
        assertEquals("Amount" to "Would have added", labels("FAILED", "payment_failed"))
        assertEquals("Amount" to "Would have added", labels("CANCELLED", "cancelled"))
        assertEquals("M-Pesa request" to "USDC due", labels("FAILED", "release_failed", paid = true))
        assertEquals("M-Pesa request" to "USDC due", labels("REVIEW", "payment_mismatch"))
    }

    @Test fun aPriceBeingAskedForCannotBeOvertakenByAnotherAmount() = with { vm, f ->
        f.remote.quoteGate = kotlinx.coroutines.CompletableDeferred()
        vm.toReview("2000")
        assertTrue(vm.state.value.quoting)
        vm.onAmount("5000")
        vm.onChain("SOL-DEVNET")
        assertEquals("2000", vm.state.value.amountText, "the amount being priced stays")
        assertEquals("BASE-SEPOLIA", vm.state.value.blockchain)
        f.remote.quoteGate!!.complete(Unit)
        assertEquals(AddMoneyStep.Review, vm.state.value.step)
        assertEquals(1, f.remote.quotes.size, "one price was asked for, for the amount on screen")
    }

    @Test fun goingBackWhileANewPriceIsOnItsWayStaysOnTheAmount() = with { vm, f ->
        vm.toReview()
        advanceTimeBy(61_000)
        assertTrue(vm.state.value.quoteExpired)
        f.remote.quoteGate = kotlinx.coroutines.CompletableDeferred()
        vm.onNewPrice()
        assertTrue(vm.state.value.quoting)
        vm.onBackToAmount()
        assertFalse(vm.state.value.quoting)
        f.remote.quoteGate!!.complete(Unit)
        assertEquals(AddMoneyStep.Amount, vm.state.value.step, "the late answer doesn't carry the person forward again")
        assertNull(vm.state.value.quote)
    }

    @Test fun everyStateOfTheFlowHasItsPage() {
        val base = AddMoneyUiState()
        assertEquals(AddMoneyPage.Loading, pageOf(base))
        assertEquals(AddMoneyPage.Verify, pageOf(base.copy(step = AddMoneyStep.Blocked, block = RampBlock.Verify)))
        assertEquals(AddMoneyPage.Gate, pageOf(base.copy(step = AddMoneyStep.Blocked, block = RampBlock.NoMerchant)))
        assertEquals(AddMoneyPage.Amount, pageOf(base.copy(step = AddMoneyStep.Amount)))
        // A step with nothing to draw yet is the skeleton, never an empty page.
        assertEquals(AddMoneyPage.Loading, pageOf(base.copy(step = AddMoneyStep.Review)))
        assertEquals(AddMoneyPage.Loading, pageOf(base.copy(step = AddMoneyStep.Order)))
        assertEquals(AddMoneyPage.Order, pageOf(base.copy(step = AddMoneyStep.Order, order = orderDto("PAID").toDomain())))
        assertTrue(AddMoneyPage.Amount.ordinal < AddMoneyPage.Review.ordinal && AddMoneyPage.Review.ordinal < AddMoneyPage.Order.ordinal, "forward is to the right")
    }

    @Test fun theSmallLines() {
        assertEquals("0C9F6A52…2B11", shortReference("0c9f6a52-7a1e-4d1b-9d55-1f3a6f0f2b11"))
        assertEquals("O1", shortReference("o1"))
        assertEquals("KES 30, included", feeText("3000", "KES 30"))
        assertEquals("No fee", feeText("0", "KES 0"))
        assertEquals("1 USDC = KES 129.50", rateLine("129.5"))
        assertEquals("1 USDC = KES 1,290.00", rateLine("1290"))
        assertEquals("1.5%, inside what you pay", feeLine("1.5"))
        assertEquals("No fee", feeLine("0"))
        assertEquals("0:42", clock(42))
        assertEquals("1:00", clock(60))
    }
}
