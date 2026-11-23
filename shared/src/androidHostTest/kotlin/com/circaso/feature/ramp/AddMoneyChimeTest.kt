package com.circaso.feature.ramp

import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.feature.ramp.data.remote.toDomain
import com.circaso.core.platform.MoneySounds
import com.circaso.core.platform.PaymentChime
import com.circaso.feature.ramp.domain.BuyWithMpesaUseCase
import com.circaso.feature.ramp.domain.CancelRampOrderUseCase
import com.circaso.feature.ramp.domain.GetOwnMpesaNumberUseCase
import com.circaso.feature.ramp.domain.GetRampOrderUseCase
import com.circaso.feature.ramp.domain.GetRampStatusUseCase
import com.circaso.feature.ramp.domain.ObserveRampUpdatesUseCase
import com.circaso.feature.ramp.domain.QuoteTopUpUseCase
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.presentation.AddMoneyViewModel
import com.circaso.feature.ramp.presentation.CardStage
import com.circaso.feature.ramp.presentation.cardStageOf
import com.circaso.feature.ramp.presentation.cardWords
import com.circaso.feature.ramp.presentation.orderLook
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The success chime of Add Money (2026-11-23): it rings when a top-up's USDC lands and its card has settled, once per
 * order on this phone, only with You → Money sound on, never for an order that ended any other way, and the coins
 * for the same USDC keep quiet around it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddMoneyChimeTest {
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

    private fun TestScope.vm(f: RampFixture, sounds: MoneySounds, orderId: String? = null) = AddMoneyViewModel(
        orderId, GetRampStatusUseCase(f.repo), QuoteTopUpUseCase(f.repo), BuyWithMpesaUseCase(f.repo), GetRampOrderUseCase(f.repo), CancelRampOrderUseCase(f.repo),
        ObserveRampUpdatesUseCase(f.repo), GetOwnMpesaNumberUseCase(f.repo),
        com.circaso.feature.ramp.domain.StartKycUseCase(f.repo), com.circaso.feature.ramp.domain.GetKycUseCase(f.repo), com.circaso.feature.ramp.domain.ObserveKycUpdatesUseCase(f.repo),
        com.circaso.feature.ramp.domain.RunIdentityCheckUseCase(verifier = { com.circaso.feature.ramp.domain.NoIdentityVerifier }, openPage = { false }),
        maskedPhone = MutableStateFlow(Masked), online = MutableStateFlow(true), networks = WalletNetworks.fixed(WalletNetwork.TESTNET, known = true),
        now = { testScheduler.currentTime }, pollMillis = 4_000, sounds = sounds,
    )

    private fun with(phone: Phone = Phone(), f: RampFixture = RampFixture(phones = MemoryPhone(com.circaso.feature.ramp.Phone)), orderId: String? = null, block: suspend TestScope.(AddMoneyViewModel, RampFixture) -> Unit) = runTest(dispatcher) {
        val vm = vm(f, phone.sounds, orderId)
        try { block(vm, f) } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    private fun RampFixture.answer(state: String, reason: String? = null) { remote.order = AppResult.Success(orderDto(state, txHash = "0xabc", reason = reason)) }
    private suspend fun RampFixture.frame(state: String, reason: String? = null) = repo.onFrame(
        "order.updated",
        com.circaso.core.network.circasoJson().parseToJsonElement("""{"orderId":"o1","direction":"ON_RAMP","state":"$state","blockchain":"BASE-SEPOLIA"${reason?.let { ""","failureReason":"$it"""" } ?: ""}}""").jsonObject,
    )

    private suspend fun AddMoneyViewModel.buy(f: RampFixture) { onAmount("2000"); onContinue(); onConfirm(); f.answer("PAID"); f.frame("PAID") }

    @Test fun watchedAsItLandsTheChimeRingsOnceWhenTheCardSettles() {
        val phone = Phone()
        with(phone) { vm, f ->
            vm.buy(f)
            assertTrue(phone.prepared > 0, "the chime is made ready while the order is followed")
            f.answer("COMPLETED")
            f.frame("COMPLETED")
            assertEquals(RampOrderState.Completed, vm.state.value.order?.state)
            assertEquals("o1", vm.state.value.celebrate, "the page is told to play the landing")
            assertEquals(0, phone.chimes, "nothing rings before the card has settled")

            vm.onLanded()
            assertEquals(1, phone.chimes)
            assertNull(vm.state.value.celebrate)
            vm.onLanded()
            f.frame("COMPLETED")
            advanceTimeBy(30_000)
            assertEquals(1, phone.chimes, "once per order")
        }
    }

    @Test fun openingTheSameOrderAgainStaysQuiet() {
        val phone = Phone()
        val done = RampFixture().also { it.answer("COMPLETED") }
        with(phone, done, orderId = "o1") { vm, _ ->
            assertEquals("o1", vm.state.value.celebrate, "first shown from a push: it lands now")
            vm.onLanded()
            assertEquals(1, phone.chimes)
        }
        with(phone, RampFixture().also { it.answer("COMPLETED") }, orderId = "o1") { vm, _ ->
            assertNull(vm.state.value.celebrate, "heard on this phone already")
            assertEquals("o1", vm.state.value.quietLanding, "the card is simply there: no settle")
            vm.onLanded()
            assertEquals(1, phone.chimes)
        }
    }

    @Test fun withTheMoneySoundOffNothingRingsAndTheOrderStillCountsAsHeard() {
        val phone = Phone(soundOn = false)
        with(phone, RampFixture().also { it.answer("COMPLETED") }, orderId = "o1") { vm, _ ->
            assertEquals("o1", vm.state.value.celebrate, "the card still settles and the haptic is still felt")
            vm.onLanded()
            assertEquals(0, phone.chimes)
        }
        phone.soundOn = true
        with(phone, RampFixture().also { it.answer("COMPLETED") }, orderId = "o1") { vm, _ ->
            assertNull(vm.state.value.celebrate, "turning the sound on later doesn't ring an old top-up")
        }
    }

    @Test fun anOrderThatDidNotGoThroughNeverRings() {
        for ((state, reason) in listOf("FAILED" to "payment_failed", "EXPIRED" to "expired", "CANCELLED" to "cancelled", "REVIEW" to "payment_mismatch", "FAILED" to "release_failed")) {
            val phone = Phone()
            with(phone) { vm, f ->
                vm.buy(f)
                f.answer(state, reason)
                f.frame(state, reason)
                assertNull(vm.state.value.celebrate, state)
                vm.onLanded()
                assertEquals(0, phone.chimes, "$state never rings")
            }
        }
    }

    @Test fun theCoinsKeepQuietForTheTopUpsOwnUsdc() {
        val phone = Phone()
        with(phone) { vm, f ->
            vm.buy(f)
            phone.sounds.moneyReceived()
            assertEquals(0, phone.coins, "the USDC arriving while the order is followed is the chime's")
            f.answer("COMPLETED")
            f.frame("COMPLETED")
            vm.onLanded()
            phone.sounds.moneyReceived()
            assertEquals(0, phone.coins, "nor just after it rang")
            phone.clock += MoneySounds.QuietMillis + 1
            phone.sounds.moneyReceived()
            assertEquals(1, phone.coins, "later money is money: the coins")
        }
    }

    @Test fun leavingAnOrderUnderWayGivesTheCoinsBack() {
        val phone = Phone()
        with(phone) { vm, f ->
            vm.buy(f)
            phone.sounds.unfollow("other")
            phone.sounds.moneyReceived()
            assertEquals(0, phone.coins, "another screen's order doesn't lift this one")
            phone.sounds.unfollow("o1")
            phone.sounds.moneyReceived()
            assertEquals(1, phone.coins)
        }
    }

    @Test fun aSettingThatCannotBeReadStillLandsTheCard() = runTest(dispatcher) {
        val broken = MoneySounds(NoChime, coins = {}, soundOn = { true }, rung = { error("storage") }, saveRung = {}, now = { 0L })
        val vm = vm(RampFixture().also { it.answer("COMPLETED") }, broken, orderId = "o1")
        try {
            assertEquals("o1", vm.state.value.celebrate, "never left waiting: it lands as new")
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancel() }
    }

    private object NoChime : PaymentChime { override fun play() = Unit }

    @Test fun theOrderIdsKeptAreCapped() = runTest(dispatcher) {
        val phone = Phone()
        repeat(MoneySounds.Remembered + 5) { assertTrue(phone.sounds.topUpLanded("order-$it")) }
        assertFalse(phone.sounds.topUpLanded("order-${MoneySounds.Remembered + 4}"))
        assertEquals(MoneySounds.Remembered, phone.kept!!.split(',').size)
        assertFalse(phone.sounds.landedBefore("order-0"), "the oldest are let go")
    }

    @Test fun theCardFollowsTheOrder() {
        assertEquals(CardStage.Waiting, cardStageOf(RampOrderState.Reserved))
        assertEquals(CardStage.Waiting, cardStageOf(RampOrderState.PaymentRequested))
        assertEquals(CardStage.Converting, cardStageOf(RampOrderState.Paid))
        assertEquals(CardStage.Converting, cardStageOf(RampOrderState.Releasing))
        assertEquals(CardStage.Landed, cardStageOf(RampOrderState.Completed))
        listOf(RampOrderState.Failed, RampOrderState.Expired, RampOrderState.Cancelled).forEach {
            assertEquals(CardStage.Ended, cardStageOf(it), "$it dims, never turns")
            assertEquals(CardStage.Held, cardStageOf(it, everPaid = true), "$it after the payment holds: the money may have moved")
        }
        assertEquals(CardStage.Held, cardStageOf(RampOrderState.Unknown, everPaid = true), "an unknown state once paid never turns back to green")
        assertEquals(CardStage.Held, cardStageOf(RampOrderState.Review))
        assertEquals(CardStage.Waiting, cardStageOf(RampOrderState.Unknown))

        val paying = orderDto("PAYMENT_REQUESTED").toDomain()
        val words = cardWords(paying, orderLook(paying, false))
        assertEquals("You pay" to "KES 2,000", words.payLabel to words.pay)
        assertEquals("You get" to "15.31 USDC", words.getLabel to words.get)
        assertTrue(words.mpesaDescription.startsWith("M-Pesa, You pay KES 2,000"), words.mpesaDescription)
        val landed = orderDto("COMPLETED").toDomain()
        val got = cardWords(landed, orderLook(landed, true))
        assertEquals("Added from M-Pesa", got.getLabel)
        assertEquals("You paid", got.payLabel)
        assertTrue(got.usdcDescription.contains("15.31 USDC"), got.usdcDescription)
    }
}
