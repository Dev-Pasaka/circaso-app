package com.circaso.feature.ramp

import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.platform.MoneySounds
import com.circaso.core.platform.PaymentChime
import com.circaso.feature.ramp.data.remote.toDomain
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
import com.circaso.feature.ramp.domain.SellForMpesaUseCase
import com.circaso.feature.ramp.domain.StartKycUseCase
import com.circaso.feature.ramp.presentation.CardStage
import com.circaso.feature.ramp.presentation.CashOutUiState
import com.circaso.feature.ramp.presentation.CashOutViewModel
import com.circaso.feature.ramp.presentation.saleBadge
import com.circaso.feature.ramp.presentation.saleCardStageOf
import com.circaso.feature.ramp.presentation.saleCardWords
import com.circaso.feature.ramp.presentation.saleLook
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cash Out's payout chime and card (2026-11-23): the same chime as a top-up's, rung when the sale's silver card has
 * turned into M-Pesa's green one and settled, once per sale on this phone, only with You → Money sound on, never for a
 * sale that ended any other way; and never at the cost of the coins (the shillings land on M-Pesa, not in the wallet).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashOutChimeTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** The phone's side: what was played, the setting, and what is kept between visits. */
    private class Phone(var soundOn: Boolean = true) : PaymentChime {
        var chimes = 0
        var coins = 0
        var prepared = 0
        var kept: String? = null
        var clock = 0L
        override fun prepare() { prepared++ }
        override fun play() { chimes++ }
        val sounds = MoneySounds(this, coins = { coins++ }, soundOn = { soundOn }, rung = { kept }, saveRung = { kept = it }, now = { clock })
    }

    private fun fixture(state: String = "AWAITING_TRANSFER", receipt: String? = null) = RampFixture(phones = MemoryPhone(com.circaso.feature.ramp.Phone)).also {
        it.remote.status = AppResult.Success(saleStatusDto())
        it.remote.quote = AppResult.Success(saleQuoteDto())
        it.remote.order = AppResult.Success(saleDto(state, receipt = receipt))
    }

    private fun TestScope.vm(f: RampFixture, sounds: MoneySounds?, orderId: String? = null) = CashOutViewModel(
        orderId, GetRampStatusUseCase(f.repo), QuoteSaleUseCase(f.repo), SellForMpesaUseCase(f.repo), GetRampOrderUseCase(f.repo), CancelRampOrderUseCase(f.repo),
        ObserveRampUpdatesUseCase(f.repo), GetOwnMpesaNumberUseCase(f.repo), StartKycUseCase(f.repo), GetKycUseCase(f.repo), ObserveKycUpdatesUseCase(f.repo),
        RunIdentityCheckUseCase(verifier = { NoIdentityVerifier }, openPage = { false }),
        saleWallet = FakeSaleWallet(), ramp = f.repo, maskedPhone = MutableStateFlow(Masked), online = MutableStateFlow(true),
        networks = WalletNetworks.fixed(WalletNetwork.TESTNET, known = true), now = { testScheduler.currentTime }, pollMillis = 4_000, estimateDelayMillis = 450,
        sounds = sounds,
    )

    private fun with(phone: Phone = Phone(), f: RampFixture = fixture(), orderId: String? = null, block: suspend TestScope.(CashOutViewModel, RampFixture) -> Unit) = runTest(dispatcher) {
        val vm = vm(f, phone.sounds, orderId)
        try { block(vm, f) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    private fun RampFixture.answer(state: String, receipt: String? = null, reason: String? = null) { remote.order = AppResult.Success(saleDto(state, receipt = receipt, reason = reason)) }
    private suspend fun RampFixture.frame(state: String) = repo.onFrame(
        "order.updated", com.circaso.core.network.circasoJson().parseToJsonElement("""{"orderId":"s1","direction":"OFF_RAMP","state":"$state","blockchain":"BASE-SEPOLIA","network":"TESTNET"}""").jsonObject,
    )

    /** Sells 10 USDC, the USDC is sent at once (no fee), and the server says it arrived. */
    private suspend fun CashOutViewModel.sell(f: RampFixture) {
        onAmount("10"); onContinue(); onSell()
        f.answer("FUNDED"); f.frame("FUNDED")
    }

    @Test fun watchedAsItIsPaidOutTheChimeRingsOnceWhenTheMpesaCardSettles() {
        val phone = Phone()
        with(phone) { vm, f ->
            assertTrue(phone.prepared > 0, "the chime is made ready when Cash Out opens")
            vm.sell(f)
            assertTrue(vm.state.value.sent)
            assertNull(vm.state.value.celebrate, "nothing to celebrate while the USDC travels")
            f.answer("PAYING_OUT"); f.frame("PAYING_OUT")
            assertNull(vm.state.value.celebrate)
            f.answer("COMPLETED", receipt = "TKN3H2X1QP"); f.frame("COMPLETED")
            assertEquals(RampOrderState.Completed, vm.state.value.order?.state)
            assertEquals("s1", vm.state.value.celebrate, "the page is told to play the landing")
            assertEquals(0, phone.chimes, "nothing rings before the card has settled")

            vm.onLanded()
            assertEquals(1, phone.chimes)
            assertNull(vm.state.value.celebrate)
            vm.onLanded()
            f.frame("COMPLETED")
            advanceTimeBy(30_000)
            assertEquals(1, phone.chimes, "once per sale")
        }
    }

    @Test fun aSaleFirstSeenPaidOutLandsAtOnceAndOpeningItAgainStaysQuiet() {
        val phone = Phone()
        with(phone, fixture("COMPLETED", receipt = "TKN3H2X1QP"), orderId = "s1") { vm, _ ->
            assertEquals("s1", vm.state.value.celebrate, "first shown from a push: it lands now")
            vm.onLanded()
            assertEquals(1, phone.chimes)
        }
        with(phone, fixture("COMPLETED", receipt = "TKN3H2X1QP"), orderId = "s1") { vm, _ ->
            assertNull(vm.state.value.celebrate, "heard on this phone already")
            assertEquals("s1", vm.state.value.quietLanding, "the M-Pesa card is simply there: no settle")
            vm.onLanded()
            assertEquals(1, phone.chimes)
        }
    }

    @Test fun withTheMoneySoundOffNothingRingsAndTheSaleStillCountsAsHeard() {
        val phone = Phone(soundOn = false)
        with(phone, fixture("COMPLETED"), orderId = "s1") { vm, _ ->
            assertEquals("s1", vm.state.value.celebrate, "the card still settles and the haptic is still felt")
            vm.onLanded()
            assertEquals(0, phone.chimes)
        }
        phone.soundOn = true
        with(phone, fixture("COMPLETED"), orderId = "s1") { vm, _ ->
            assertNull(vm.state.value.celebrate, "turning the sound on later doesn't ring an old sale")
        }
    }

    @Test fun aSaleThatWasNotPaidOutNeverRings() {
        for ((state, reason) in listOf("REFUNDING" to "payout_failed", "REFUNDED" to "payout_failed", "EXPIRED" to "never_funded", "CANCELLED" to null, "REVIEW" to "payout_unconfirmed", "FAILED" to null)) {
            val phone = Phone()
            with(phone) { vm, f ->
                vm.sell(f)
                f.answer(state, reason = reason); f.frame(state)
                assertNull(vm.state.value.celebrate, state)
                vm.onLanded()
                assertEquals(0, phone.chimes, "$state never rings")
            }
        }
    }

    @Test fun thePayoutNeverSilencesTheCoins() {
        val phone = Phone()
        with(phone) { vm, f ->
            vm.sell(f)
            phone.sounds.moneyReceived()
            assertEquals(1, phone.coins, "a sale under way doesn't hold back other money's coins")
            f.answer("COMPLETED"); f.frame("COMPLETED")
            vm.onLanded()
            assertEquals(1, phone.chimes)
            assertEquals(1, phone.coins, "the payout itself plays no coins: the shillings land on M-Pesa, not in the wallet")
            phone.sounds.moneyReceived()
            assertEquals(2, phone.coins, "money arriving just after is someone else's: the coins, not swallowed by the chime")
        }
    }

    @Test fun aSettingThatCannotBeReadStillLandsTheCard() = runTest(dispatcher) {
        val broken = MoneySounds(object : PaymentChime { override fun play() = Unit }, coins = {}, soundOn = { true }, rung = { error("storage") }, saveRung = {}, now = { 0L })
        val vm = vm(fixture("COMPLETED"), broken, orderId = "s1")
        try { assertEquals("s1", vm.state.value.celebrate, "never left waiting: it lands as new") } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun withoutAnyPlayerTheCardStillLands() = runTest(dispatcher) {
        val vm = vm(fixture("COMPLETED"), sounds = null, orderId = "s1")
        try {
            assertEquals("s1", vm.state.value.celebrate, "the haptic and the settle don't wait on a sound")
            vm.onLanded()
            assertNull(vm.state.value.celebrate)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    @Test fun theCardFollowsTheSale() {
        // The silver USDC card while the USDC travels; turning green as the payout is sent; settled when it lands.
        assertEquals(CardStage.Waiting, saleCardStageOf(RampOrderState.AwaitingTransfer, sent = false))
        assertEquals(CardStage.Waiting, saleCardStageOf(RampOrderState.AwaitingTransfer, sent = true))
        assertEquals(CardStage.Waiting, saleCardStageOf(RampOrderState.Funded, sent = true))
        assertEquals(CardStage.Converting, saleCardStageOf(RampOrderState.PayingOut, sent = true))
        assertEquals(CardStage.Landed, saleCardStageOf(RampOrderState.Completed, sent = true))
        assertEquals(CardStage.Landed, saleCardStageOf(RampOrderState.Completed, sent = false), "a sale first seen paid out shows the M-Pesa card")
        // USDC coming back: the silver card, whole, never a break-up.
        assertEquals(CardStage.Returned, saleCardStageOf(RampOrderState.Refunding, sent = true))
        assertEquals(CardStage.Returned, saleCardStageOf(RampOrderState.Refunded, sent = true))
        listOf(RampOrderState.Expired, RampOrderState.Cancelled, RampOrderState.Failed).forEach {
            assertEquals(CardStage.Ended, saleCardStageOf(it, sent = false), "$it before sending: the silver card dims")
            assertEquals(CardStage.Returned, saleCardStageOf(it, sent = true), "$it after sending: the USDC comes back")
        }
        listOf(RampOrderState.Expired, RampOrderState.Cancelled, RampOrderState.Failed).forEach {
            assertEquals(CardStage.Held, saleCardStageOf(it, sent = false, maybeSent = true), "$it when the USDC may have gone: held, never promised back")
        }
        assertEquals(CardStage.Held, saleCardStageOf(RampOrderState.Review, sent = true), "a review holds, no celebration")
        assertEquals(CardStage.Held, saleCardStageOf(RampOrderState.Unknown, sent = true))
    }

    @Test fun theCardsSayTheSaleInWords() {
        val selling = saleDto("FUNDED").toDomain()
        val s = CashOutUiState(order = selling, sent = true)
        val words = saleCardWords(selling, saleLook(selling, s))
        assertEquals("You sell" to "10.00 USDC", words.usdcLabel to words.usdc)
        assertEquals("You receive" to "KES 1,269", words.mpesaLabel to words.mpesa)
        assertTrue(words.usdcDescription.startsWith("USDC on "), words.usdcDescription)
        val paid = saleDto("COMPLETED", receipt = "TKN3H2X1QP").toDomain()
        val got = saleCardWords(paid, saleLook(paid, s.copy(order = paid)))
        assertEquals("Received on M-Pesa", got.mpesaLabel)
        assertEquals("You sold", got.usdcLabel)
        assertTrue(got.mpesaDescription.startsWith("M-Pesa, Received on M-Pesa KES 1,269"), got.mpesaDescription)
    }

    @Test fun theBadgeUnderTheCardIsForWarningsAndEndingsOnly() {
        val waiting = saleDto("AWAITING_TRANSFER").toDomain()
        assertNull(saleBadge(waiting, saleLook(waiting, CashOutUiState(order = waiting))), "a sale waiting for its USDC needs no badge: the card and the button say it")
        val paid = saleDto("COMPLETED").toDomain()
        assertNull(saleBadge(paid, saleLook(paid, CashOutUiState(order = paid, sent = true))), "paid out: the M-Pesa card is the sign")
        val refunded = saleDto("REFUNDED", reason = "payout_failed").toDomain()
        assertNotNull(saleBadge(refunded, saleLook(refunded, CashOutUiState(order = refunded, sent = true))))
        val review = saleDto("REVIEW").toDomain()
        assertNotNull(saleBadge(review, saleLook(review, CashOutUiState(order = review, sent = true))))
    }

    @Test fun aFailedSaleSaysAmountNotYouSell() {
        val failed = saleDto("FAILED").toDomain()
        val words = saleCardWords(failed, saleLook(failed, CashOutUiState(order = failed)))
        assertEquals("Amount" to "Would have received", words.usdcLabel to words.mpesaLabel)
    }
}
