package com.circaso.feature.calls.presentation

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
import com.circaso.feature.wallet.presentation.PayIntent
import com.circaso.feature.wallet.presentation.PayStep
import com.circaso.feature.wallet.presentation.PayViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

private val kes = FiatValue("KES", "322500", "129.00", "2026-11-06T10:00:00Z", true)

/** 500 USDC (KES 64,500), 0.5 ETH at $3,000 (KES 193,500) and 1,200 DEGEN with no price yet, all on Base; KES at 129. */
private fun walletOf(vararg assets: Asset) = Wallet(
    status = WalletStatus.Active,
    chains = listOf(WalletChain("BASE-SEPOLIA", "0xme", "ACTIVE", assets.toList(), true)),
    totals = assets.map { AssetTotal(it.symbol, it.decimals, it.amountMinor, it.amount) },
    fiat = kes,
    holdUntil = null,
    fetchedAt = 0,
)

private val usdc = Asset("USDC", "0xusdc", 6, "500000000", "500", "64500")
private val eth = Asset("ETH", "", 18, "500000000000000000", "0.5", "193500")
private val degen = Asset("DEGEN", "0xdegen", 18, "1200000000000000000000", "1200", null)

private val Base = kotlin.time.Instant.parse("2026-11-06T10:00:00Z").toEpochMilliseconds()

private class Monitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

private fun quote(fee: String, expiresAt: String? = null) =
    PaymentQuote(fee, 50, false, "0", "500000000", null, null, null, "4900000", "15000000", null, "q1", expiresAt)

/**
 * Design H5 on the call: the coin the sheet starts on, the person's money turned into that coin, the fee gating
 * Contribute, and Contribute going straight to the PIN through the chat's PayViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallContributionTest {
    private val dispatcher = StandardTestDispatcher()
    private val monitor = Monitor()
    private lateinit var connection: ConnectionState

    private var wallet = walletOf(usdc, eth, degen)
    /** The wallet comes after the sheet opened (a cold start straight into a call). */
    private var late = false
    private val lateWallet = kotlinx.coroutines.flow.MutableSharedFlow<Wallet>(replay = 1)
    private val asked = mutableListOf<PaymentRequest>()
    private val paid = mutableListOf<PaymentRequest>()
    private var checks = 0
    private var fallback: AppResult<PaymentQuote?> = AppResult.Success(quote("20000"))

    private val walletRepo = fake<WalletRepository> { name, args ->
        when (name) {
            "getWallet" -> if (late) lateWallet else flowOf(wallet)
            "recipient" -> { checks++; AppResult.Success(Recipient(args[0] as String, RecipientStatus.CanReceive, listOf("BASE-SEPOLIA"))) }
            "quote" -> { asked += args[0] as PaymentRequest; fallback }
            "pay" -> {
                val r = args[0] as PaymentRequest; paid += r
                AppResult.Success(Transfer("t1", TransferDirection.Out, TransferState.Submitted, "BASE-SEPOLIA", "0xme", "tre", "0xtre", Amount(r.asset, r.amountMinor, ""), null, null, null, null, "Wanjiru", null, 0, 0))
            }
            else -> null
        }
    }
    private val contacts = fake<ContactsRepository> { name, _ -> if (name == "getContacts") flowOf(emptyList<Any>()) else null }
    private val messages = fake<MessagesRepository> { name, _ -> if (name == "sendPayment") Unit else null }
    private val chats = fake<ChatsRepository> { _, _ -> null }
    private val chamas = fake<ChamaRepository> { name, _ -> if (name == "chama") flowOf(null) else null }

    /** The chama model's call: the treasurer, as AppNavigation's CallLayer makes it. */
    private fun vm() = PayViewModel(
        PayIntent(userId = "tre", chatId = "g1", displayName = "Wanjiru", goalName = "Holiday Fund", inChat = true), ObserveWalletUseCase(walletRepo), CheckRecipientUseCase(walletRepo),
        RemindRecipientUseCase(walletRepo), PayUseCase(walletRepo), ObserveContactsUseCase(contacts), connection, SendPaymentCardUseCase(messages),
        OpenDirectChatUseCase(chats), AttachChamaPaymentUseCase(chamas), ObserveChamaUseCase(chamas),
        quotePayment = QuotePaymentUseCase(walletRepo), quoteDelayMillis = 400,
        now = { Base + dispatcher.scheduler.currentTime },
    )

    private fun TestScope.sheet(clock: () -> Long = { Base + dispatcher.scheduler.currentTime }): Pair<PayViewModel, CallContribution> {
        val vm = vm()
        backgroundScope.launch { vm.state.collect { } }
        advanceUntilIdle()
        val sheet = CallContribution(vm, clock)
        sheet.open()
        advanceUntilIdle()
        return vm to sheet
    }

    private fun ui(vm: PayViewModel, sheet: CallContribution) = contributeUi(sheet.choice.value, vm.state.value, Base + dispatcher.scheduler.currentTime)

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ---- the coins ----

    @Test fun coinsAreTheOnesHeldMostValuableFirstUnpricedLast() {
        assertEquals(listOf("ETH", "USDC", "DEGEN"), contributeCoins(wallet).map { it.symbol })
        assertEquals("ETH", defaultCoin(contributeCoins(wallet)), "the coin they hold most of, by value")
    }

    @Test fun withNothingHeldTheDefaultIsUsdc() {
        val coins = contributeCoins(walletOf())
        assertEquals(listOf("USDC"), coins.map { it.symbol })
        assertEquals("USDC", defaultCoin(coins))
        assertEquals("USDC", defaultCoin(contributeCoins(null)))
    }

    @Test fun dollarStablesArePricedAtOneAndOthersFromTheSeam() {
        assertEquals(1.0, usdPriceOf(usdc, kes))
        assertEquals(1.0, usdPriceOf(Asset("USDT", "0x", 6, "1", "0.000001", null), kes), "a dollar is a dollar without a server price")
        assertEquals(3000.0, usdPriceOf(eth, kes)!!, 1e-9)
        assertNull(usdPriceOf(degen, kes), "no price yet")
    }

    // ---- the amounts ----

    @Test fun presetsAreAmountsPeopleSayInTheirMoney() {
        val coin = contributeCoins(wallet).first { it.symbol == "ETH" }
        assertEquals(listOf("500", "1000", "2500"), presetsFor(coin, ContributeUnit.Fiat, kes))
        val dollar = FiatValue("USD", "0", "1.00", "", false)
        assertEquals(listOf("5", "10", "25"), presetsFor(coin, ContributeUnit.Fiat, dollar))
    }

    @Test fun anUnpricedCoinShowsItsPresetsInTheCoin() {
        val coin = contributeCoins(wallet).first { it.symbol == "DEGEN" }
        assertEquals(ContributeUnit.Coin, unitFor(coin, kes))
        assertEquals(listOf("120", "300", "600"), presetsFor(coin, ContributeUnit.Coin, kes), "a tenth, a quarter and half of what they hold")
    }

    @Test fun theirMoneyIsTurnedIntoTheCoinNeverRoundedUp() {
        val ethCoin = contributeCoins(wallet).first { it.symbol == "ETH" }
        // KES 1,000 at KES 387,000 an ETH = 0.002583979… → five figures, rounded down.
        assertEquals("2583900000000000", coinMinorFor(ContributeChoice("ETH", "1000", ContributeUnit.Fiat), ethCoin, kes))
        val usdcCoin = contributeCoins(wallet).first { it.symbol == "USDC" }
        assertEquals("7751937", coinMinorFor(ContributeChoice("USDC", "1000", ContributeUnit.Fiat), usdcCoin, kes), "as the chat's #1000")
        val degenCoin = contributeCoins(wallet).first { it.symbol == "DEGEN" }
        assertEquals("300000000000000000000", coinMinorFor(ContributeChoice("DEGEN", "300", ContributeUnit.Coin), degenCoin, kes))
        assertNull(coinMinorFor(ContributeChoice("ETH", "0", ContributeUnit.Fiat), ethCoin, kes))
    }

    // ---- the sheet on PayViewModel ----

    @Test fun theSheetOpensOnTheDefaultCoinAndTheMiddlePresetWithTheFeeQuoted() = runTest(dispatcher) {
        val (vm, sheet) = sheet()
        assertEquals(ContributeChoice("ETH", "1000", ContributeUnit.Fiat), sheet.choice.value)
        assertEquals("ETH", vm.state.value.asset, "PayViewModel pays in the chosen coin")
        assertEquals("2583900000000000", vm.state.value.amountMinor)
        val ui = ui(vm, sheet)
        assertEquals("KES", ui.heroCurrency)
        assertEquals("1,000", ui.heroNumber)
        assertEquals("Contribute KES 1,000", ui.button)
        assertTrue(ui.enabled)
        assertTrue(ui.fee is ContributeFee.Shown && (ui.fee as ContributeFee.Shown).text.startsWith("Fee "), "${ui.fee}")
    }

    @Test fun anotherCoinKeepsTheAmountInTheirMoney() = runTest(dispatcher) {
        val (vm, sheet) = sheet()
        sheet.onCoin("USDC"); advanceUntilIdle()
        assertEquals(ContributeChoice("USDC", "1000", ContributeUnit.Fiat, picked = true), sheet.choice.value)
        assertEquals("7751937", vm.state.value.amountMinor)
        assertEquals("USDC", vm.state.value.asset)
        assertEquals("7.75 USDC", ui(vm, sheet).sub)
    }

    @Test fun contributeGoesStraightToThePinIntoTheGroup() = runTest(dispatcher) {
        val (vm, sheet) = sheet()
        assertTrue(sheet.contribute())
        advanceUntilIdle()
        val r = paid.single()
        assertEquals("tre", r.toUserId, "the treasurer: the only destination")
        assertEquals("ETH", r.asset)
        assertEquals("2583900000000000", r.amountMinor)
        assertEquals("g1", r.chatId, "the card goes into the group chat")
        assertEquals("q1", r.quoteId, "the fee the sheet showed")
        assertEquals(PayStep.Done, vm.state.value.step)
    }

    @Test fun aChangedAmountCantBeSignedUntilItsFeeIsQuoted() = runTest(dispatcher) {
        val (vm, sheet) = sheet()
        sheet.onPreset("2500"); runCurrent()
        assertFalse(sheet.contribute(), "the quote on the sheet is for KES 1,000")
        assertFalse(ui(vm, sheet).enabled)
        assertEquals(ContributeFee.Working, ui(vm, sheet).fee)
        advanceUntilIdle()
        assertTrue(sheet.contribute()); advanceUntilIdle()
        assertEquals(1, paid.size)
    }

    @Test fun aQuoteWhoseHoldRanOutIsNeverSigned() = runTest(dispatcher) {
        fallback = AppResult.Success(quote("20000", expiresAt = "2026-11-06T10:15:00Z"))
        // The sheet's clock is past the hold; PayViewModel hasn't asked again yet.
        val (vm, sheet) = sheet(clock = { Base + 16 * 60_000 })
        assertTrue(vm.state.value.canPay)
        assertFalse(sheet.contribute())
        assertTrue(paid.isEmpty())
    }

    @Test fun offlineTheSheetSaysSoAndContributeWaits() = runTest(dispatcher) {
        monitor.osStatus.value = ConnectionStatus.Offline
        val (vm, sheet) = sheet()
        val ui = ui(vm, sheet)
        assertFalse(ui.enabled)
        assertEquals(ContributeTone.Warning, ui.status?.second)
        assertTrue(ui.status!!.first.startsWith("You’re offline"))
        assertFalse(sheet.contribute())
        assertTrue(asked.isEmpty(), "nothing is quoted offline")
    }

    @Test fun overTheBalanceSaysWhatTheyHave() = runTest(dispatcher) {
        val (vm, sheet) = sheet()
        sheet.onCoin("USDC"); sheet.onOther(); sheet.onCustom("100000"); advanceUntilIdle()
        val ui = ui(vm, sheet)
        assertFalse(ui.enabled)
        assertEquals(ContributeTone.Critical, ui.status?.second)
        assertTrue("You have 500.00 USDC" in ui.status!!.first, ui.status!!.first)
    }

    @Test fun closingLeavesNothingOnPayViewModel() = runTest(dispatcher) {
        val (vm, sheet) = sheet()
        sheet.close(); advanceUntilIdle()
        assertNull(sheet.choice.value)
        assertEquals("", vm.state.value.amountText)
        assertTrue(paid.isEmpty())
    }

    @Test fun presetTapsInACoinDontCheckTheTreasurerAgain() = runTest(dispatcher) {
        val (_, sheet) = sheet()
        val before = checks
        sheet.onPreset("2500"); sheet.onPreset("500"); sheet.onOther(); sheet.onCustom("7"); sheet.onCustom("75"); advanceUntilIdle()
        assertEquals(before, checks, "same coin: no new check per tap or keystroke")
        sheet.onCoin("DEGEN"); advanceUntilIdle()
        assertEquals(before + 1, checks, "a new coin is checked on its chain")
    }

    @Test fun aWalletThatArrivesAfterTheSheetOpensGivesTheirMoneyAndAChosenAmountStays() = runTest(dispatcher) {
        late = true
        val (vm, sheet) = sheet()
        assertEquals(ContributeChoice("USDC", "10", ContributeUnit.Coin), sheet.choice.value, "no rate yet: dollars in the coin")
        lateWallet.emit(wallet); advanceUntilIdle()
        sheet.onWallet(); advanceUntilIdle()
        assertEquals(ContributeChoice("ETH", "1000", ContributeUnit.Fiat), sheet.choice.value, "KES 1,000 once the rate is known")
        assertEquals("2583900000000000", vm.state.value.amountMinor)
        // They tap KES 2,500; a refresh that makes another coin the most valuable doesn't undo it.
        sheet.onPreset("2500"); advanceUntilIdle()
        lateWallet.emit(walletOf(usdc, degen)); advanceUntilIdle()
        sheet.onWallet(); advanceUntilIdle()
        assertEquals("2500", sheet.choice.value?.amount)
        assertEquals("ETH", sheet.choice.value?.coin)
    }
}
