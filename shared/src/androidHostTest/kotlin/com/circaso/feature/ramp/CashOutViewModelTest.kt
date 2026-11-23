package com.circaso.feature.ramp

import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.ramp.data.remote.OffRampDto
import com.circaso.feature.ramp.domain.CancelRampOrderUseCase
import com.circaso.feature.ramp.domain.GetKycUseCase
import com.circaso.feature.ramp.domain.GetOwnMpesaNumberUseCase
import com.circaso.feature.ramp.domain.GetRampOrderUseCase
import com.circaso.feature.ramp.domain.GetRampStatusUseCase
import com.circaso.feature.ramp.domain.NoIdentityVerifier
import com.circaso.feature.ramp.domain.ObserveKycUpdatesUseCase
import com.circaso.feature.ramp.domain.ObserveRampUpdatesUseCase
import com.circaso.feature.ramp.domain.QuoteSaleUseCase
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.domain.RunIdentityCheckUseCase
import com.circaso.feature.ramp.domain.SaleWallet
import com.circaso.feature.ramp.domain.SellForMpesaUseCase
import com.circaso.feature.ramp.domain.StartKycUseCase
import com.circaso.feature.ramp.presentation.AddMoneyViewModel
import com.circaso.feature.ramp.presentation.CashOutPage
import com.circaso.feature.ramp.presentation.CashOutStep
import com.circaso.feature.ramp.presentation.CashOutViewModel
import com.circaso.feature.ramp.presentation.RampBlock
import com.circaso.feature.ramp.presentation.SaleActions
import com.circaso.feature.ramp.presentation.SaleUnit
import com.circaso.feature.ramp.presentation.SendFee
import com.circaso.feature.ramp.presentation.SendHold
import com.circaso.feature.ramp.presentation.StageMark
import com.circaso.feature.ramp.presentation.VerifyEntry
import com.circaso.feature.ramp.presentation.cashOutPageOf
import com.circaso.feature.ramp.presentation.refundWords
import com.circaso.feature.ramp.presentation.saleFigureLabels
import com.circaso.feature.ramp.presentation.saleLook
import com.circaso.feature.ramp.presentation.saleStages
import com.circaso.feature.ramp.presentation.verifyLook
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.PaymentQuote
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.QuoteProblem
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import com.circaso.feature.wallet.domain.WalletStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import com.circaso.feature.ramp.data.remote.toDomain
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

/** The wallet as a sale sees it, scripted: the balance, W17's fee and W4, every call written down. */
internal class FakeSaleWallet(balanceMinor: String = "25500000") : SaleWallet {
    val wallet = MutableStateFlow<Wallet?>(walletHolding(balanceMinor))
    var fee: AppResult<PaymentQuote?> = AppResult.Success(null)
    var send: AppResult<Transfer> = AppResult.Success(sentTransfer())
    val fees = mutableListOf<PaymentRequest>()
    val sends = mutableListOf<Pair<PaymentRequest, String>>()
    override fun wallet(network: WalletNetwork) = wallet
    override suspend fun fee(request: PaymentRequest): AppResult<PaymentQuote?> { fees += request; return fee }
    override suspend fun send(request: PaymentRequest, idempotencyKey: String): AppResult<Transfer> { sends += request to idempotencyKey; return send }

    companion object {
        fun walletHolding(minor: String) = Wallet(
            WalletStatus.Active, listOf(WalletChain("BASE-SEPOLIA", "0xme", "ACTIVE", listOf(Asset("USDC", "0x036c", 6, minor, "", null)), true)),
            emptyList(), null, null, 0L, network = WalletNetwork.TESTNET,
        )
        fun sentTransfer(id: String = "TR-1") = Transfer(id, TransferDirection.Out, TransferState.Submitted, "BASE-SEPOLIA", "0xme", null, "0x5a3c", Amount("USDC", "10000000", "10"), null, null, null, null, "M-Pesa", null, 0L, 0L)
        fun quote(feeMinor: String, problem: QuoteProblem? = null) = PaymentQuote(feeMinor, 10, false, "0", null, null, problem, null, null, null, null, "wq1", null)
    }
}

/**
 * Cash out (selling USDC for M-Pesa: R1 `offRamp`, R2 `OFF_RAMP`, R7, then W4 naming the sale) with a scripted server and
 * wallet: the happy path, every gate, the amount in both units, every R2/R7/W4 refusal, the approval cancelled, the
 * fee, the deadline, every ending's words, resuming, cancelling, and that nothing is asked offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashOutViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val online = MutableStateFlow(true)
    private val masked = MutableStateFlow<String?>(Masked)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun fixture() = RampFixture(phones = MemoryPhone(Phone)).also {
        it.remote.status = AppResult.Success(saleStatusDto())
        it.remote.quote = AppResult.Success(saleQuoteDto())
        it.remote.order = AppResult.Success(saleDto("AWAITING_TRANSFER"))
    }

    private fun TestScope.vm(f: RampFixture, w: FakeSaleWallet, orderId: String? = null) = CashOutViewModel(
        orderId, GetRampStatusUseCase(f.repo), QuoteSaleUseCase(f.repo), SellForMpesaUseCase(f.repo), GetRampOrderUseCase(f.repo), CancelRampOrderUseCase(f.repo),
        ObserveRampUpdatesUseCase(f.repo), GetOwnMpesaNumberUseCase(f.repo), StartKycUseCase(f.repo), GetKycUseCase(f.repo), ObserveKycUpdatesUseCase(f.repo),
        RunIdentityCheckUseCase(verifier = { NoIdentityVerifier }, openPage = { false }),
        saleWallet = w, ramp = f.repo, maskedPhone = masked, online = online, networks = WalletNetworks.fixed(WalletNetwork.TESTNET, known = true),
        now = { testScheduler.currentTime }, pollMillis = 4_000, estimateDelayMillis = 450,
    )

    private fun with(
        f: RampFixture = fixture(), w: FakeSaleWallet = FakeSaleWallet(), orderId: String? = null,
        block: suspend TestScope.(CashOutViewModel, RampFixture, FakeSaleWallet) -> Unit,
    ) = runTest(dispatcher) {
        val vm = vm(f, w, orderId)
        try { block(vm, f, w) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    private fun RampFixture.answer(state: String, receipt: String? = null, reason: String? = null, txHash: String? = null) { remote.order = AppResult.Success(saleDto(state, receipt = receipt, reason = reason, txHash = txHash)) }
    private suspend fun RampFixture.frame(state: String) = repo.onFrame(
        "order.updated", com.circaso.core.network.circasoJson().parseToJsonElement("""{"orderId":"s1","direction":"OFF_RAMP","state":"$state","blockchain":"BASE-SEPOLIA","network":"TESTNET"}""").jsonObject,
    )
    private fun CashOutViewModel.toReview(amount: String = "10") { onAmount(amount); onContinue() }
    private fun CashOutViewModel.look() = saleLook(state.value.order!!, state.value)

    // ---- the happy path ------------------------------------------------------------------------------------------

    @Test fun fromTheAmountToShillingsOnThePhone() = with { vm, f, w ->
        assertEquals(CashOutStep.Amount, vm.state.value.step)
        assertEquals("BASE-SEPOLIA", vm.state.value.blockchain)
        assertEquals("25.50 USDC available", vm.state.value.available)
        assertTrue(vm.state.value.testnet)

        // The live price, once the amount rests: exactly what is typed, in the sale's direction.
        vm.onAmount("10")
        assertNull(vm.state.value.liveEstimate)
        advanceTimeBy(451)
        assertEquals("KES 1,269", vm.state.value.liveEstimate?.receive?.text)
        assertEquals("OFF_RAMP", f.remote.quotes.last().first.direction)
        assertEquals("10", f.remote.quotes.last().first.crypto?.amount)

        vm.onContinue()
        val review = vm.state.value
        assertEquals(CashOutStep.Review, review.step)
        assertEquals(CashOutPage.Review, cashOutPageOf(review))
        assertEquals("KES 26", review.quote?.fee?.text)
        assertEquals("KES 5", review.quote?.providerFee?.text)
        assertEquals("KES 1,300", review.quote?.worth?.text)
        assertEquals(60, review.secondsLeft)
        assertNotEquals(f.remote.quotes[0].second, f.remote.quotes[1].second, "the live price and the review's price are separate asks")

        vm.onSell()
        assertEquals("sq1" to Phone, f.remote.sells.single().first.let { it.quoteId to it.phone })
        // W17 first (no fee), then W4 at once: exactly the deposit, naming the sale, the order id as the key.
        assertEquals(1, w.fees.size)
        val (paid, key) = w.sends.single()
        assertEquals("s1", key)
        assertEquals("s1", paid.rampOrderId)
        assertEquals("0x5a3c0000000000000000000000000000000beef", paid.toAddress)
        assertEquals("10000000", paid.amountMinor)
        assertEquals("BASE-SEPOLIA", paid.blockchain)
        assertEquals(WalletNetwork.TESTNET, paid.network)
        assertTrue(vm.state.value.sent)
        assertEquals("Sending your USDC", vm.look().title)
        assertEquals("tr-1", f.repo.saleTransfer("s1"), "this phone remembers it paid the sale")

        f.answer("FUNDED"); f.frame("FUNDED")
        assertEquals(RampOrderState.Funded, vm.state.value.order?.state)
        assertEquals("Sending your M-Pesa payment", vm.look().title)
        assertEquals(listOf(StageMark.Done, StageMark.Done, StageMark.Active, StageMark.Waiting), saleStages(vm.state.value.order!!, true, false).map { it.mark })

        f.answer("PAYING_OUT")
        advanceTimeBy(4_001)
        assertEquals(RampOrderState.PayingOut, vm.state.value.order?.state)

        f.answer("COMPLETED", receipt = "TKN3H2X1QP")
        advanceTimeBy(4_001)
        val done = vm.state.value.order!!
        assertEquals(RampOrderState.Completed, done.state)
        assertEquals("TKN3H2X1QP", done.receipt)
        assertEquals("KES 1,269 sent to your M-Pesa", vm.look().title)
        assertEquals(SaleActions.Done, vm.look().actions)
        assertEquals("You sold" to "You received", saleFigureLabels(done.state, vm.look()))
        assertEquals("TKN3H2X1QP", f.repo.sales.value().paidBy("tr-1")?.receipt)

        val reads = f.remote.reads.size
        advanceTimeBy(60_000)
        assertEquals(reads, f.remote.reads.size, "a finished sale is not polled")
        assertEquals(1, w.sends.size, "the USDC is sent once")
    }

    // ---- the gates ------------------------------------------------------------------------------------------------

    @Test fun aRampThatIsNotOpenSaysSo() = with(fixture().also { it.remote.status = refusal("ramp.unavailable", http = 403) }) { vm, _, _ ->
        assertEquals(RampBlock.Unavailable, vm.state.value.block)
    }

    @Test fun sellingSwitchedOffIsTheClosedPage() = with(fixture().also { it.remote.status = AppResult.Success(saleStatusDto(offRamp = OffRampDto(open = false))) }) { vm, _, _ ->
        assertEquals(CashOutStep.Blocked, vm.state.value.step)
        assertEquals(RampBlock.Unavailable, vm.state.value.block)
    }

    @Test fun aServerFromBeforeSellingIsTheClosedPage() = with(fixture().also { it.remote.status = AppResult.Success(saleStatusDto(offRamp = null)) }) { vm, _, _ ->
        assertEquals(RampBlock.Unavailable, vm.state.value.block)
    }

    @Test fun nobodyBuyingSaysSo() = with(fixture().also { it.remote.status = AppResult.Success(saleStatusDto(offRamp = OffRampDto(open = true, merchantOnline = false))) }) { vm, _, _ ->
        assertEquals(RampBlock.NoMerchant, vm.state.value.block)
    }

    @Test fun aBarAndAPausedAccountHaveTheirPages() {
        with(fixture().also { it.remote.status = AppResult.Success(saleStatusDto(blocked = true)) }) { vm, _, _ -> assertEquals(RampBlock.Barred, vm.state.value.block) }
        with(fixture().also { it.remote.status = AppResult.Success(saleStatusDto(active = false)) }) { vm, _, _ -> assertEquals(RampBlock.Restricted, vm.state.value.block) }
    }

    @Test fun identityIsAskedForAsAddMoneyAsksIt() = with(fixture().also { it.remote.status = AppResult.Success(saleStatusDto(kyc = "NONE", verification = checkDto("NONE"))) }) { vm, _, _ ->
        assertEquals(RampBlock.Verify, vm.state.value.block)
        assertEquals(CashOutPage.Verify, cashOutPageOf(vm.state.value))
        assertEquals("Verify once, cash out any time", verifyLook(vm.state.value.verify, entry = VerifyEntry.CashOut).title)
    }

    @Test fun anOpenSaleIsWhereTheFlowResumes() = with(fixture().also { it.remote.status = AppResult.Success(saleStatusDto(open = "s1")) }) { vm, _, w ->
        assertEquals(CashOutStep.Order, vm.state.value.step)
        assertEquals("s1", vm.state.value.order?.orderId)
        assertTrue(w.sends.isEmpty(), "reopening never brings the approval up by itself")
        assertTrue(vm.state.value.canSend)
        assertEquals("Send your USDC to finish", vm.look().title)
    }

    @Test fun anOpenPurchaseIsHandedToAddMoney() = with(fixture().also { it.remote.status = AppResult.Success(saleStatusDto(open = "o1")); it.remote.order = AppResult.Success(orderDto("PAYMENT_REQUESTED")) }) { vm, _, _ ->
        assertEquals("o1", vm.state.value.handOff)
    }

    @Test fun aSaleOpenedInAddMoneyIsHandedToCashOut() = runTest(dispatcher) {
        val f = fixture()
        val vm = AddMoneyViewModel(
            "s1", GetRampStatusUseCase(f.repo), com.circaso.feature.ramp.domain.QuoteTopUpUseCase(f.repo), com.circaso.feature.ramp.domain.BuyWithMpesaUseCase(f.repo), GetRampOrderUseCase(f.repo),
            CancelRampOrderUseCase(f.repo), ObserveRampUpdatesUseCase(f.repo), GetOwnMpesaNumberUseCase(f.repo), StartKycUseCase(f.repo), GetKycUseCase(f.repo), ObserveKycUpdatesUseCase(f.repo),
            RunIdentityCheckUseCase(verifier = { NoIdentityVerifier }, openPage = { false }), networks = WalletNetworks.fixed(WalletNetwork.TESTNET, known = true), now = { testScheduler.currentTime },
        )
        try { assertEquals("s1", vm.state.value.handOff) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    // ---- the amount -----------------------------------------------------------------------------------------------

    @Test fun moreThanTheBalanceIsSaidAndNeverPriced() = with { vm, f, _ ->
        val asked = f.remote.quotes.size
        vm.onAmount("40")
        advanceTimeBy(1_000)
        assertEquals("That's more than the 25.50 USDC in your wallet.", vm.state.value.amountError)
        assertFalse(vm.state.value.canQuote)
        assertEquals(asked, f.remote.quotes.size)
    }

    @Test fun anEmptyWalletSaysSo() = with(w = FakeSaleWallet("0")) { vm, _, _ ->
        vm.onAmount("1")
        assertEquals("You have no USDC on Base (testnet) to sell.", vm.state.value.amountError)
        assertFalse(vm.state.value.canMax)
    }

    @Test fun maxSellsEverythingInUsdc() = with { vm, _, _ ->
        vm.onUnit(SaleUnit.Kes)
        vm.onMax()
        assertEquals(SaleUnit.Usdc, vm.state.value.unit)
        assertEquals("25.5", vm.state.value.amountText)
        assertNull(vm.state.value.amountError)
    }

    @Test fun theUnitSwitchCarriesTheAmountAcrossWithThePrice() = with { vm, f, _ ->
        vm.onAmount("10"); advanceTimeBy(451)
        vm.onUnit(SaleUnit.Kes)
        assertEquals("1269", vm.state.value.amountText)
        assertEquals("KES 1,269", vm.state.value.liveEstimate?.receive?.text, "the price still holds for the same sale")
        vm.onUnit(SaleUnit.Usdc)
        assertEquals("10", vm.state.value.amountText)
        // Shillings are asked for in whole shillings.
        vm.onUnit(SaleUnit.Kes); vm.onAmount("1000"); advanceTimeBy(451)
        assertEquals("1000", f.remote.quotes.last().first.fiat?.amount)
        assertNull(f.remote.quotes.last().first.crypto)
    }

    @Test fun shillingsOutsideTheLimitsAreSaid() = with { vm, _, _ ->
        vm.onUnit(SaleUnit.Kes)
        vm.onAmount("5")
        assertEquals("The least you can cash out is KES 10.", vm.state.value.amountError)
        vm.onAmount("6000")
        assertEquals("The most you can cash out at once is KES 5,000.", vm.state.value.amountError)
    }

    @Test fun usdcTypingIsADecimalWithSixPlaces() = with { vm, _, _ ->
        vm.onAmount("1.1234567")
        assertEquals("", vm.state.value.amountText, "a seventh decimal is refused")
        vm.onAmount(".5")
        assertEquals("0.5", vm.state.value.amountText)
        vm.onAmount("1,5")
        assertEquals("1.5", vm.state.value.amountText)
    }

    @Test fun aPriceThatLeavesNothingIsSaidUnderTheAmount() = with(fixture().also { it.remote.quote = refusal("ramp.amount_invalid", http = 400) }) { vm, _, _ ->
        vm.onAmount("0.01"); advanceTimeBy(451)
        assertEquals(CashOutViewModel.AmountTooSmall, vm.state.value.amountError)
        assertFalse(vm.state.value.canQuote)
    }

    // ---- R2's refusals --------------------------------------------------------------------------------------------

    private fun refusedPrice(code: String, http: Int, check: (CashOutViewModel) -> Unit) = with(fixture()) { vm, f, _ ->
        vm.onAmount("10"); advanceTimeBy(451)
        f.remote.quote = refusal(code, http = http)
        vm.onContinue()
        check(vm)
    }

    @Test fun r2RefusalsAreWordedByCode() {
        refusedPrice("ramp.payout_fee_unavailable", 502) { assertEquals("We can't price a sale right now. Nothing left your wallet. Try again later.", it.state.value.failure?.message) }
        refusedPrice("ramp.pricing_unavailable", 502) { assertEquals("Prices aren't available right now. Nothing left your wallet. Try again in a moment.", it.state.value.failure?.message) }
        refusedPrice("ramp.no_merchant_available", 409) { assertEquals("No one can buy this amount right now. Try a smaller amount, or try again later. Nothing left your wallet.", it.state.value.failure?.message) }
        refusedPrice("ramp.limit_exceeded", 400) { assertEquals("You can cash out KES 10 to KES 5,000 at a time, and KES 20,000 a day.", it.state.value.failure?.message) }
        refusedPrice("ramp.amount_invalid", 400) { assertEquals(CashOutViewModel.AmountTooSmall, it.state.value.failure?.message) }
        refusedPrice("ramp.offramp_unavailable", 409) { assertEquals(RampBlock.Unavailable, it.state.value.block) }
        refusedPrice("ramp.wallet_required", 409) { assertEquals(RampBlock.NoWallet, it.state.value.block) }
        refusedPrice("ramp.blocked", 403) { assertEquals(RampBlock.Barred, it.state.value.block) }
        refusedPrice("ramp.account_restricted", 403) { assertEquals(RampBlock.Restricted, it.state.value.block) }
    }

    @Test fun aPriceRefusedForIdentityShowsWhereTheCheckStands() = with { vm, f, _ ->
        vm.onAmount("10")
        f.remote.quote = refusal("ramp.kyc_required", http = 403)
        f.remote.status = AppResult.Success(saleStatusDto(kyc = "NONE", verification = checkDto("NONE")))
        vm.onContinue()
        assertEquals(RampBlock.Verify, vm.state.value.block)
    }

    @Test fun aPriceWithNoAnswerKeepsItsKeyAndARefusedOneDoesNot() = with { vm, f, _ ->
        vm.onAmount("10"); advanceTimeBy(451)
        f.remote.quote = AppResult.Failure(AppError.Network)
        vm.onContinue()
        assertEquals("Cashing out needs a connection. Nothing left your wallet.", vm.state.value.failure?.message)
        vm.onContinue()
        val keys = f.remote.quotes.takeLast(2).map { it.second }
        assertEquals(keys[0], keys[1], "a retry with no answer is the same ask")
        f.remote.quote = refusal("ramp.limit_exceeded", http = 400)
        vm.onContinue(); vm.onContinue()
        val after = f.remote.quotes.takeLast(2).map { it.second }
        assertNotEquals(after[0], after[1], "after a definite no, a new ask")
    }

    // ---- R7's refusals --------------------------------------------------------------------------------------------

    @Test fun aPriceThatRanOutIsAskedAgainAndShownNeverSoldUnseen() = with { vm, f, w ->
        vm.toReview()
        f.remote.sell = refusal("ramp.quote_expired")
        vm.onSell()
        assertEquals(CashOutStep.Review, vm.state.value.step)
        assertTrue(vm.state.value.repriced)
        assertTrue(w.sends.isEmpty())
    }

    @Test fun anOrderInProgressIsOpened() = with { vm, f, _ ->
        vm.toReview()
        f.remote.sell = refusal("ramp.order_in_progress")
        f.remote.status = AppResult.Success(saleStatusDto(open = "s1"))
        vm.onSell()
        assertEquals(CashOutStep.Order, vm.state.value.step)
    }

    @Test fun theNumberIsAskedForOnceAndCheckedAgainstTheMask() = with(fixture().also { it.phones.phone = null }) { vm, f, _ ->
        vm.toReview()
        assertTrue(vm.state.value.needsPhone)
        vm.onPhone("0712 000 111")
        vm.onSell()
        assertEquals("That isn't the number your account is verified with. It ends in 678.", vm.state.value.phoneError)
        assertTrue(f.remote.sells.isEmpty())
        vm.onPhone("0712 345 678")
        vm.onSell()
        assertEquals(Phone, f.remote.sells.single().first.phone)
    }

    @Test fun aNumberTheServerRefusesIsAskedForAgain() = with { vm, f, _ ->
        vm.toReview()
        f.remote.sell = refusal("ramp.phone_not_yours", http = 400)
        vm.onSell()
        assertTrue(vm.state.value.needsPhone)
        assertNotNull(vm.state.value.phoneError)
    }

    @Test fun aSaleWithNoAnswerSaysNothingLeftAndRetriesUnderTheSameKey() = with { vm, f, w ->
        vm.toReview()
        f.remote.sell = AppResult.Failure(AppError.Network)
        vm.onSell()
        assertTrue(vm.state.value.failure!!.message.startsWith("We couldn't reach Circaso, so the sale wasn't confirmed. Nothing left your wallet"))
        f.remote.sell = AppResult.Failure(AppError.Api("service.unavailable", 503, "req-2", null, emptyMap()))
        vm.onSell()
        assertEquals(f.remote.sells[0].second, f.remote.sells[1].second)
        assertTrue(w.sends.isEmpty())
    }

    // ---- sending the USDC (W17, W4) -------------------------------------------------------------------------------

    @Test fun aCancelledApprovalLeavesTheSaleWaitingWithSendAgain() = with(w = FakeSaleWallet().also { it.send = refusal("wallet.cancelled", http = 400) }) { vm, _, w ->
        vm.toReview(); vm.onSell()
        val s = vm.state.value
        assertEquals(RampOrderState.AwaitingTransfer, s.order?.state)
        assertEquals(SendHold.Cancelled, s.sendHold)
        assertFalse(s.sent)
        assertTrue(s.canSend)
        assertTrue(s.canCancel)
        assertEquals("Send your USDC to finish", vm.look().title)
        assertTrue(vm.look().message.startsWith("You didn't approve the transfer, so nothing left your wallet."))
        w.send = AppResult.Success(FakeSaleWallet.sentTransfer())
        vm.onSend()
        assertEquals(listOf("s1", "s1"), w.sends.map { it.second }, "the same key every time: it can't be paid twice")
        assertTrue(vm.state.value.sent)
    }

    @Test fun aNetworkFeeIsShownBeforeAnythingIsApproved() = with(w = FakeSaleWallet().also { it.fee = AppResult.Success(FakeSaleWallet.quote("50000")) }) { vm, _, w ->
        vm.toReview(); vm.onSell()
        assertTrue(w.sends.isEmpty(), "a fee on top is seen before the approval comes up")
        assertEquals(SendFee.Quoted("50000", "wq1"), vm.state.value.sendFee)
        assertEquals("Sending 10.00 USDC costs a network fee of 0.05 USDC, so 10.05 USDC leaves your wallet.", vm.look().message.substringBefore(" KES"))
        vm.onSend()
        assertEquals("wq1", w.sends.single().first.quoteId)
    }

    @Test fun tooLittleForTheSaleAndItsFeeOffersCancel() = with(w = FakeSaleWallet("10000000").also { it.fee = AppResult.Success(FakeSaleWallet.quote("50000", QuoteProblem.InsufficientFunds)) }) { vm, _, w ->
        vm.toReview(); vm.onSell()
        assertEquals(SendHold.Short, vm.state.value.sendHold)
        assertFalse(vm.state.value.canSend)
        assertTrue(vm.state.value.canCancel)
        assertEquals(SaleActions.Short, vm.look().actions)
        assertTrue(vm.look().message.startsWith("Your wallet needs 10.05 USDC for this sale and its network fee and holds 10.00 USDC."))
        assertTrue(w.sends.isEmpty())
    }

    @Test fun sellLessCancelsAndOffersWhatFits() = with(w = FakeSaleWallet("10000000").also { it.fee = AppResult.Success(FakeSaleWallet.quote("50000", QuoteProblem.InsufficientFunds)) }) { vm, f, _ ->
        vm.toReview(); vm.onSell()
        assertEquals("9950000", vm.state.value.sellable)
        f.remote.cancel = AppResult.Success(saleDto("CANCELLED"))
        vm.onSellLess()
        assertEquals("s1", f.remote.cancels.single().first)
        assertEquals(CashOutStep.Amount, vm.state.value.step)
        assertEquals(SaleUnit.Usdc, vm.state.value.unit)
        assertEquals("9.95", vm.state.value.amountText)
    }

    @Test fun aTransferWithNoAnswerNeverBecomesNothingLeft() = with(fixture().also { it.remote.sell = AppResult.Success(saleDto("AWAITING_TRANSFER", expiresAt = "1970-01-01T00:01:00Z")) }, FakeSaleWallet().also { it.send = AppResult.Failure(AppError.Network) }) { vm, f, _ ->
        vm.toReview(); vm.onSell()
        assertTrue(vm.state.value.maybeSent)
        assertFalse(vm.state.value.canCancel, "it may have gone: cancelling isn't offered")
        f.answer("EXPIRED", reason = "never_funded")
        advanceTimeBy(61_000)
        assertEquals(RampOrderState.Expired, vm.state.value.order?.state)
        assertEquals(com.circaso.feature.ramp.presentation.MaybeSentWords, vm.look().message)
        assertNotEquals(SaleActions.Again, vm.look().actions, "no Try Again while the USDC may be on its way back")
    }

    @Test fun aWalletOnTheOtherNetworkOffersNoSendUntilBack() = with(w = FakeSaleWallet().also { it.send = refusal("wallet.network_changed") }) { vm, _, _ ->
        vm.toReview(); vm.onSell()
        assertEquals(SaleActions.Switch, vm.look().actions)
        assertFalse(vm.state.value.canSend)
        vm.onResumed()
        assertTrue(vm.state.value.canSend)
    }

    @Test fun aFeeThatCouldNotBeCheckedSendsNothing() = with(w = FakeSaleWallet().also { it.fee = AppResult.Failure(AppError.Network) }) { vm, _, w ->
        vm.toReview(); vm.onSell()
        assertTrue(w.sends.isEmpty())
        assertNull(vm.state.value.sendFee)
        assertEquals(CashOutViewModel.SendNeedsConnection, vm.state.value.sendFailure?.message)
        w.fee = AppResult.Success(null)
        vm.onSend()
        assertEquals(1, w.sends.size, "Send USDC asks for the fee again, then sends")
    }

    @Test fun aSaleAnotherTransferPaidIsNeverPaidAgain() = with(w = FakeSaleWallet().also { it.send = refusal("wallet.ramp_order_funded") }) { vm, f, w ->
        f.answer("FUNDED")
        vm.toReview(); vm.onSell()
        assertTrue(vm.state.value.sent)
        assertEquals(RampOrderState.Funded, vm.state.value.order?.state)
        assertFalse(vm.state.value.canSend)
        vm.onSend()
        assertEquals(1, w.sends.size)
    }

    @Test fun w4RefusalsAreWordedAndKeepTheSaleOpen() {
        with(w = FakeSaleWallet().also { it.send = refusal("wallet.insufficient_funds", http = 400) }) { vm, _, _ ->
            vm.toReview(); vm.onSell()
            assertEquals(SendHold.Short, vm.state.value.sendHold)
        }
        with(w = FakeSaleWallet().also { it.send = refusal("wallet.pin_locked", http = 423) }) { vm, _, _ ->
            vm.toReview(); vm.onSell()
            assertEquals(SendHold.Failed, vm.state.value.sendHold)
            assertEquals("Too many wrong PIN tries. Try again in 30 minutes.", vm.look().message)
        }
        with(w = FakeSaleWallet().also { it.send = refusal("wallet.network_changed") }) { vm, _, _ ->
            vm.toReview(); vm.onSell()
            assertEquals(SendHold.OtherNetwork, vm.state.value.sendHold)
            assertEquals("Switch to Testnet to finish", vm.look().title)
        }
    }

    @Test fun aChangedFeeIsShownAgainBeforeSending() = with(w = FakeSaleWallet().also { it.send = refusal("wallet.fee_changed") }) { vm, _, w ->
        vm.toReview()
        vm.onSell()
        // W4 said the fee moved: W17 again, and with a fee now the approval waits for the person.
        w.fee = AppResult.Success(FakeSaleWallet.quote("80000"))
        assertEquals(1, w.sends.size)
        vm.onSend()
        assertEquals(SendFee.Quoted("80000", "wq1"), vm.state.value.sendFee)
    }

    @Test fun aTransferWithNoAnswerIsSafeToSendAgain() = with(w = FakeSaleWallet().also { it.send = AppResult.Failure(AppError.Network) }) { vm, f, w ->
        vm.toReview(); vm.onSell()
        assertEquals(SendHold.Unknown, vm.state.value.sendHold)
        assertEquals("We couldn't confirm the transfer", vm.look().title)
        assertTrue("s1" in f.remote.reads, "the sale is read: it may have arrived")
        w.send = AppResult.Success(FakeSaleWallet.sentTransfer())
        vm.onSend()
        assertEquals(listOf("s1", "s1"), w.sends.map { it.second })
    }

    // ---- the deadline, cancelling ---------------------------------------------------------------------------------

    @Test fun theDeadlinePassingStopsSendingAndReadsTheSale() = with(fixture().also { it.remote.sell = AppResult.Success(saleDto("AWAITING_TRANSFER", expiresAt = "1970-01-01T00:01:00Z")) }, FakeSaleWallet().also { it.send = refusal("wallet.cancelled", http = 400) }) { vm, f, _ ->
        vm.toReview(); vm.onSell()
        assertTrue(vm.state.value.canSend)
        f.answer("EXPIRED", reason = "never_funded")
        advanceTimeBy(61_000)
        assertEquals(RampOrderState.Expired, vm.state.value.order?.state)
        assertFalse(vm.state.value.canSend)
        assertEquals("This sale timed out", vm.look().title)
        assertEquals("Nothing left your wallet. You can start a new sale whenever you like.", vm.look().message)
    }

    @Test fun cancellingEndsTheSaleAndSaysNothingLeft() = with(w = FakeSaleWallet().also { it.send = refusal("wallet.cancelled", http = 400) }) { vm, f, _ ->
        vm.toReview(); vm.onSell()
        f.remote.cancel = AppResult.Success(saleDto("CANCELLED"))
        vm.onCancel()
        assertEquals("s1", f.remote.cancels.single().first)
        assertEquals("Sale cancelled", vm.look().title)
        assertEquals("Nothing left your wallet.", vm.look().message)
        assertEquals(SaleActions.Again, vm.look().actions)
    }

    @Test fun aSaleTooFarAlongToCancelSaysSo() = with(w = FakeSaleWallet().also { it.send = refusal("wallet.cancelled", http = 400) }) { vm, f, _ ->
        vm.toReview(); vm.onSell()
        f.remote.cancel = refusal("ramp.order_not_cancellable")
        f.answer("FUNDED")
        vm.onCancel()
        assertTrue(vm.state.value.cancelNote!!.startsWith("Your USDC has already reached Circaso's partner"))
        assertEquals(RampOrderState.Funded, vm.state.value.order?.state)
    }

    @Test fun cancelIsNotOfferedOnceTheUsdcWasSent() = with { vm, _, _ ->
        vm.toReview(); vm.onSell()
        assertTrue(vm.state.value.sent)
        assertFalse(vm.state.value.canCancel)
    }

    // ---- every ending's words -------------------------------------------------------------------------------------

    private fun ending(state: String, reason: String? = null, sent: Boolean = true, check: (CashOutViewModel) -> Unit) =
        with(fixture().also { it.remote.status = AppResult.Success(saleStatusDto(open = "s1")); it.answer(state, reason = reason) }) { vm, f, _ ->
            if (sent) f.repo.saleSent(saleDto("AWAITING_TRANSFER").toDomain(), "tr-1")
            vm.load()
            check(vm)
        }

    @Test fun theEndingsSayWhereTheMoneyIs() {
        ending("REFUNDING", "payout_failed") {
            assertEquals("Your USDC is on its way back", it.look().title)
            assertEquals("We couldn't pay you out on M-Pesa, so no shillings were sent. 10.00 USDC is on its way back to your wallet.", it.look().message)
            assertEquals(SaleActions.Following, it.look().actions)
        }
        ending("REFUNDED", "payout_failed") {
            assertEquals("Your USDC is back in your wallet", it.look().title)
            assertEquals(SaleActions.Again, it.look().actions)
        }
        ending("EXPIRED", "never_funded", sent = true) {
            // It comes back by itself: nothing to retry, Support beside it, the reference first.
            assertEquals(SaleActions.Following, it.look().actions)
            assertTrue(it.look().needsReference)
            assertEquals("Your 10.00 USDC left your wallet but didn't reach Circaso's partner in time. It's sent back to you by itself, and this page updates when it is.", it.look().message)
        }
        ending("EXPIRED", "never_funded", sent = false) {
            assertEquals(SaleActions.Again, it.look().actions)
            assertEquals("Nothing left your wallet. You can start a new sale whenever you like.", it.look().message)
        }
        ending("REFUNDING", "payout_failed") { assertEquals("Amount" to "Would have received", saleFigureLabels(it.state.value.order!!.state, it.look())) }
        ending("REVIEW") { assertEquals("You sell" to "To receive", saleFigureLabels(it.state.value.order!!.state, it.look())) }
        ending("REVIEW") {
            assertEquals("We're checking this sale", it.look().title)
            assertEquals("Your money is safe. A person at Circaso is checking this sale, and we'll update you. Please don't send it again.", it.look().message)
            assertEquals(SaleActions.Support, it.look().actions)
            assertFalse(it.state.value.canSend, "never offered to send again")
        }
    }

    @Test fun refundReasonsAreWordedNeverShown() {
        assertEquals("Your USDC arrived after the sale had ended, so it was sent back. 10 USDC is back in your wallet on Base.", refundWords("late_transfer", "10 USDC", done = true, chain = "Base"))
        assertEquals("A different amount arrived than this sale needed, so all of it was sent back. 10 USDC is on its way back to your wallet.", refundWords("amount_mismatch", "10 USDC", done = false))
        assertFalse(refundWords("payout_rejected", "10 USDC", true).contains("payout_rejected"))
    }

    @Test fun aSaleOpenedByIdThatThisPhonePaidIsFollowedNotResent() = runTest(dispatcher) {
        val f = fixture()
        val w = FakeSaleWallet()
        f.repo.saleSent(saleDto("AWAITING_TRANSFER").toDomain(), "tr-1")
        val vm = vm(f, w, orderId = "s1")
        try {
            assertEquals(CashOutStep.Order, vm.state.value.step)
            assertTrue(vm.state.value.sent, "this phone already sent it")
            assertFalse(vm.state.value.canSend)
            assertFalse(vm.state.value.canCancel)
            assertEquals("Sending your USDC", saleLook(vm.state.value.order!!, vm.state.value).title)
            assertTrue(w.sends.isEmpty())
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun aSaleOpenedByIdNotYetPaidOffersSendButNeverSendsByItself() = with(fixture(), orderId = "s1") { vm, _, w ->
        assertEquals(CashOutStep.Order, vm.state.value.step)
        assertFalse(vm.state.value.sent)
        assertTrue(vm.state.value.canSend)
        assertTrue(w.sends.isEmpty())
        assertEquals(1, w.fees.size, "the fee is known before Send is offered")
    }

    // ---- offline --------------------------------------------------------------------------------------------------

    @Test fun offlineNothingIsAskedAndNothingIsSent() = with { vm, f, w ->
        online.value = false
        val asked = f.remote.quotes.size
        vm.onAmount("10")
        advanceTimeBy(2_000)
        assertEquals(asked, f.remote.quotes.size, "no live price offline")
        assertFalse(vm.state.value.canQuote)
        vm.onContinue()
        assertEquals(CashOutStep.Amount, vm.state.value.step)
        online.value = true
        vm.toReview()
        online.value = false
        assertFalse(vm.state.value.canConfirm)
        vm.onSell()
        assertTrue(f.remote.sells.isEmpty())
        assertTrue(w.sends.isEmpty())
        online.value = true
    }
}

/** The repository's sales, now. */
private suspend fun kotlinx.coroutines.flow.Flow<com.circaso.feature.ramp.domain.MpesaSales>.value() = first()
