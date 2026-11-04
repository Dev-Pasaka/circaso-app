package com.circaso.feature.wallet

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.chama.domain.AttachChamaPaymentUseCase
import com.circaso.feature.chama.domain.ChamaRepository
import com.circaso.feature.chama.domain.ObserveChamaUseCase
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.MessagesRepository
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.messaging.domain.SendPaymentCardUseCase
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.AssetTotal
import com.circaso.feature.wallet.domain.CheckRecipientUseCase
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.PayUseCase
import com.circaso.feature.wallet.domain.PaymentQuote
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.QuotePaymentUseCase
import com.circaso.feature.wallet.domain.QuoteProblem
import com.circaso.feature.wallet.domain.Recipient
import com.circaso.feature.wallet.domain.RecipientStatus
import com.circaso.feature.wallet.domain.RemindRecipientUseCase
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.presentation.PayBlock
import com.circaso.feature.wallet.presentation.PayFee
import com.circaso.feature.wallet.presentation.PayIntent
import com.circaso.feature.wallet.presentation.PayViewModel
import com.circaso.feature.wallet.presentation.blockNote
import com.circaso.feature.wallet.presentation.blockedLabel
import com.circaso.feature.wallet.presentation.feeChangeText
import com.circaso.feature.wallet.presentation.feeFootnote
import com.circaso.feature.wallet.presentation.totalValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private inline fun <reified T : Any> fake(noinline answer: (String, List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        val list = args.orEmpty().toList().filterNot { it is kotlin.coroutines.Continuation<*> }
        when (method.name) {
            "toString" -> "fake ${T::class.simpleName}"
            "hashCode" -> 0
            "equals" -> false
            else -> answer(method.name, list)
        }
    } as T

/** 500 USDC on Base; KES at 129. */
private val wallet = Wallet(
    status = WalletStatus.Active,
    chains = listOf(WalletChain("BASE-SEPOLIA", "0xme", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, "500000000", "500", "64500")), true)),
    totals = listOf(AssetTotal("USDC", 6, "500000000", "500")),
    fiat = FiatValue("KES", "64500", "129.00", "2026-10-29T10:00:00Z", false),
    holdUntil = null,
    fetchedAt = 0,
)

/** The tests' "now": 10:00 UTC on the day of the quotes, moved by virtual time. */
private val Base = kotlin.time.Instant.parse("2026-11-04T10:00:00Z").toEpochMilliseconds()

private class FeeTestMonitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

private fun quote(fee: String, rate: Int = 50, minimum: Boolean = false, problem: QuoteProblem? = null, max: String? = null, fitsAt: String? = null, id: String = "q1", expiresAt: String? = null) =
    PaymentQuote(fee, rate, minimum, "0", "500000000", max, problem, fitsAt, "4900000", "15000000", null, id, expiresAt)

/**
 * Phase 2F in the review (the chat's money card and the Pay screen share PayViewModel): W17's fee after a short pause,
 * only the latest answer, Pay waiting meanwhile, the quote's id sent to W4, GW13c's fee change, GW13d's Try Again,
 * GW14b's daily cap, GW20's "most you can send", and nothing asked offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayFeeTest {
    private val dispatcher = StandardTestDispatcher()
    private val monitor = FeeTestMonitor()
    private lateinit var connection: ConnectionState

    private val asked = mutableListOf<PaymentRequest>()
    private val paid = mutableListOf<PaymentRequest>()
    /** What W17 answers, by the amount asked about; anything else answers [fallback]. */
    private val answers = mutableMapOf<String, AppResult<PaymentQuote?>>()
    private var fallback: AppResult<PaymentQuote?> = AppResult.Success(quote("20000"))
    private var payAnswer: (PaymentRequest) -> AppResult<Transfer> = { r ->
        AppResult.Success(Transfer("t1", TransferDirection.Out, TransferState.Submitted, "BASE-SEPOLIA", "0xme", "mum", "0xmum", Amount("USDC", r.amountMinor, ""), null, null, null, null, "Mum", null, 0, 0))
    }

    private val walletRepo = fake<WalletRepository> { name, args ->
        when (name) {
            "getWallet" -> flowOf(wallet)
            "recipient" -> AppResult.Success(Recipient(args[0] as String, RecipientStatus.CanReceive, listOf("BASE-SEPOLIA")))
            "quote" -> { val r = args[0] as PaymentRequest; asked += r; answers[r.amountMinor] ?: fallback }
            "pay" -> { val r = args[0] as PaymentRequest; paid += r; payAnswer(r) }
            else -> null
        }
    }
    private val contacts = fake<ContactsRepository> { name, _ -> if (name == "getContacts") flowOf(emptyList<Any>()) else null }
    private val messages = fake<MessagesRepository> { name, _ -> if (name == "sendPayment") Unit else null }
    private val chats = fake<ChatsRepository> { _, _ -> null }
    private val chamas = fake<ChamaRepository> { name, _ -> if (name == "chama") flowOf(null) else null }

    private fun vm() = PayViewModel(
        PayIntent(userId = "mum", chatId = "c1", displayName = "Mum", inChat = true), ObserveWalletUseCase(walletRepo), CheckRecipientUseCase(walletRepo),
        RemindRecipientUseCase(walletRepo), PayUseCase(walletRepo), ObserveContactsUseCase(contacts), connection, SendPaymentCardUseCase(messages),
        OpenDirectChatUseCase(chats), AttachChamaPaymentUseCase(chamas), ObserveChamaUseCase(chamas),
        quotePayment = QuotePaymentUseCase(walletRepo), quoteDelayMillis = 400,
        now = { Base + dispatcher.scheduler.currentTime },
    )

    private fun TestScope.keep(vm: PayViewModel) { backgroundScope.launch { vm.state.collect { } } }

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun theFeeIsAskedForAfterAPauseAndOnlyTheLastAmountCounts() = runTest(dispatcher) {
        val vm = vm(); keep(vm); advanceUntilIdle()
        vm.onAmount("5"); advanceTimeBy(100)
        vm.onAmount("50"); advanceTimeBy(100)
        vm.onAmount("500")
        advanceTimeBy(100)
        assertEquals(PayFee.Quoting, vm.state.value.fee, "GW13: working it out")
        assertFalse(vm.state.value.canPay, "Pay waits for the fee")
        advanceUntilIdle()

        assertEquals(listOf(vm.state.value.amountMinor), asked.map { it.amountMinor }, "one question, for what the person last typed")
        val s = vm.state.value
        assertEquals(PayFee.Quoted("20000", 50, false, "q1", null), s.fee)
        assertTrue(s.canPay)
        assertEquals("KES 2.58 · 0.5%", s.feeText)
        assertEquals("KES 502.58", totalValue(s))
        assertTrue(feeFootnote(s).startsWith("One Circaso fee, network cost included."))
    }

    @Test fun payingSendsTheQuoteThePersonSaw() = runTest(dispatcher) {
        val vm = vm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()
        vm.onPay(); advanceUntilIdle()

        assertEquals("q1", paid.single().quoteId)
    }

    @Test fun aSmallPaymentSaysMinimumAndWhy() = runTest(dispatcher) {
        fallback = AppResult.Success(quote("50000", minimum = true))
        val vm = vm(); keep(vm); advanceUntilIdle()
        vm.onAmount("50"); advanceUntilIdle()

        assertEquals("KES 6.45 · minimum", vm.state.value.feeText)
        assertTrue("Small payments have a minimum fee that covers the network cost." in feeFootnote(vm.state.value))
    }

    @Test fun aFeeThatCantBeWorkedOutBlocksPayAndTryAgainAsksOnceMore() = runTest(dispatcher) {
        fallback = AppResult.Failure(AppError.Api("wallet.custody_unavailable", 502, "req_F9a2", null, emptyMap()))
        val vm = vm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()
        assertTrue(vm.state.value.fee is PayFee.Failed)
        assertFalse(vm.state.value.canPay, "GW13d: no guessed fee, ever")
        assertEquals("—", totalValue(vm.state.value))
        assertTrue("We couldn’t work out the fee. Ref req_F9a2" in feeFootnote(vm.state.value))

        fallback = AppResult.Success(quote("20000"))
        vm.onRetryQuote(); advanceUntilIdle()
        assertEquals(2, asked.size)
        assertTrue(vm.state.value.canPay)
    }

    @Test fun aServerWithoutFeesShowsNoFeeAndIsAskedOnce() = runTest(dispatcher) {
        fallback = AppResult.Success(null)
        val vm = vm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()
        vm.onAmount("400"); advanceUntilIdle()

        assertEquals(1, asked.size)
        assertEquals(PayFee.None, vm.state.value.fee)
        assertTrue(vm.state.value.canPay)
    }

    @Test fun offlineNothingIsAskedAndTheFeeSaysItNeedsAConnection() = runTest(dispatcher) {
        monitor.osStatus.value = ConnectionStatus.Offline
        val vm = vm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()

        assertTrue(asked.isEmpty())
        assertEquals("Needs a connection", vm.state.value.feeText)
        assertFalse(vm.state.value.canPay)
    }

    @Test fun aHigherFeeAtPayIsShownWithItsNewQuoteAndTheNextTapPaysIt() = runTest(dispatcher) {
        val vm = vm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()
        payAnswer = {
            AppResult.Failure(
                AppError.Api(
                    "wallet.fee_changed", 409, "req_1", null,
                    mapOf("quotedFeeMinor" to "20000", "feeMinor" to "25000", "rateBps" to "50", "minimum" to "false", "quoteId" to "q2", "expiresAt" to "2026-11-04T10:30:00Z"),
                ),
            )
        }
        vm.onPay(); advanceUntilIdle()

        val s = vm.state.value
        assertNull(s.failure, "nothing was attempted: no Not sent")
        assertEquals("q2", (s.fee as PayFee.Quoted).quoteId)
        assertEquals("The fee changed: KES 2.58 → KES 3.23. New total KES 503.23.", feeChangeText(s))
        assertTrue(s.canPay)

        payAnswer = { r -> AppResult.Success(Transfer("t2", TransferDirection.Out, TransferState.Submitted, "BASE-SEPOLIA", "0xme", "mum", "0xmum", Amount("USDC", r.amountMinor, ""), null, null, null, null, "Mum", null, 0, 0)) }
        vm.onPay(); advanceUntilIdle()
        assertEquals(listOf("q1", "q2"), paid.map { it.quoteId })
    }

    @Test fun whenTheHoldRunsOutTheFeeIsAskedAgainAndAChangeIsShownBeforePay() = runTest(dispatcher) {
        fallback = AppResult.Success(quote("20000", expiresAt = "2026-11-04T10:15:00Z"))
        val vm = vm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceTimeBy(1_000)
        assertEquals("20000", vm.state.value.feeMinor)

        fallback = AppResult.Success(quote("25000", id = "q2"))
        advanceUntilIdle()

        assertEquals(2, asked.size, "asked again once the 15 minutes were up")
        assertEquals("q2", (vm.state.value.fee as PayFee.Quoted).quoteId)
        assertEquals("The fee changed: KES 2.58 → KES 3.23. New total KES 503.23.", feeChangeText(vm.state.value), "GW13c, before Pay")
    }

    @Test fun aStaleFeeCantBeSignedForANewAmount() = runTest(dispatcher) {
        val vm = vm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()
        assertTrue(vm.state.value.canPay)
        vm.onAmount("600"); runCurrent()
        assertTrue(vm.state.value.feeStale)
        assertFalse(vm.state.value.canPay, "the old quote is for 500")
        assertEquals("Send", com.circaso.feature.messaging.presentation.payLabel(vm.state.value), "GW13: a bare verb until the fee is known")
        advanceUntilIdle()
        assertTrue(vm.state.value.canPay)
    }

    @Test fun overTheDailyLimitPayWaitsAndSaysWhen() = runTest(dispatcher) {
        fallback = AppResult.Success(quote("20000", problem = QuoteProblem.DailyLimit, max = "4800000", fitsAt = "2026-11-05T14:30:00Z"))
        val vm = vm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.block is PayBlock.Daily)
        assertFalse(s.canPay)
        assertTrue(blockedLabel(s)!!.startsWith("Send ") && "After " in blockedLabel(s)!!, blockedLabel(s))
        val note = blockNote(s)!!
        assertEquals("You can send KES 632.10 more today", note.title, "4.90 USDC left, at 129")
        assertTrue(note.action!!.startsWith("Send KES "), note.action)
    }

    @Test fun notEnoughWithTheFeeOffersTheMostThatFitsNeverACentOver() = runTest(dispatcher) {
        // The most that fits: 499.75 USDC; in KES at 129 that is 64,467.75, which stands for at most that much USDC.
        answers["500000000"] = AppResult.Success(quote("2500000", problem = QuoteProblem.InsufficientFunds, max = "499750000"))
        val vm = vm(); keep(vm); advanceUntilIdle()
        vm.onToggleUnit()
        vm.onAmount("500"); advanceUntilIdle()
        assertTrue(vm.state.value.block is PayBlock.Funds)
        assertEquals("Send 499.75 USDC Instead", blockNote(vm.state.value)!!.action, "in the unit being typed: exactly what it fills in")

        vm.onUseMax(); advanceUntilIdle()
        assertEquals("499.75", vm.state.value.amountText)
        assertNull(vm.state.value.block)
    }
}
