package com.circaso.feature.wallet

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.core.network.NetworkHealth
import com.circaso.feature.chama.domain.AttachChamaPaymentUseCase
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ChamaGoal
import com.circaso.feature.chama.domain.ChamaRepository
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.ObserveChamaUseCase
import com.circaso.feature.chama.domain.RequestState
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.MessagesRepository
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.messaging.domain.PaymentCard
import com.circaso.feature.messaging.domain.SendPaymentCardUseCase
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.AssetTotal
import com.circaso.feature.wallet.domain.CheckRecipientUseCase
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.PayUseCase
import com.circaso.feature.wallet.domain.PaymentRequest
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

/**
 * Builds a fake of a big repository interface where only a few members matter: [answer] gets the member name and its
 * arguments (a suspend member's continuation dropped) and returns the value, or null for "not used here".
 */
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

private val wallet = Wallet(
    status = WalletStatus.Active,
    chains = listOf(WalletChain("BASE-SEPOLIA", "0xme", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, "500000000", "500", "64500")), true)),
    totals = listOf(AssetTotal("USDC", 6, "500000000", "500")),
    fiat = FiatValue("KES", "64500", "129.00", "2026-10-29T10:00:00Z", false),
    holdUntil = null,
    fetchedAt = 0,
)

private fun transfer(id: String, minor: String) =
    Transfer(id, TransferDirection.Out, TransferState.Submitted, "BASE-SEPOLIA", "0xme", "otieno", "0xotieno", Amount("USDC", minor, ""), null, "g1", null, null, "Otieno", null, 0, 0)

private val chama = Chama(
    goal = ChamaGoal("g1", "Holiday Fund", "387596899", null, "otieno", 1),
    memberCount = 8, contributedMinor = "250000000", paidOutMinor = "0", heldMinor = "250000000", myContributedMinor = "0",
    contributors = emptyList(), openRequests = emptyList(), isAdmin = false, isTreasurer = false, fetchedAt = 0,
)

private class Monitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

@OptIn(ExperimentalCoroutinesApi::class)
class PayViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var connection: ConnectionState

    private val payments = mutableListOf<PaymentRequest>()
    private val cards = mutableListOf<Pair<String, PaymentCard>>()
    private val attaches = mutableListOf<Triple<String, String, String>>()
    /** What each CH6 try answers, in order; the last one repeats. */
    private var attachAnswers: List<AppResult<ChamaRequest>> = listOf(AppResult.Success(request()))

    private fun request() = ChamaRequest("r1", "g1", "juma", "achieng", null, null, "12000000", "fare", RequestState.Approved, 2, emptyList(), null, "", "", "")

    private val walletRepo = fake<WalletRepository> { name, args ->
        when (name) {
            "getWallet" -> flowOf(wallet)
            "recipient" -> AppResult.Success(Recipient(args[0] as String, RecipientStatus.CanReceive, listOf("BASE-SEPOLIA")))
            "pay" -> { val r = args[0] as PaymentRequest; payments += r; AppResult.Success(transfer("t${payments.size}", r.amountMinor)) }
            else -> null
        }
    }
    private val contacts = fake<ContactsRepository> { name, _ -> if (name == "getContacts") flowOf(emptyList<Any>()) else null }
    private val messages = fake<MessagesRepository> { name, args -> if (name == "sendPayment") { cards += (args[0] as String) to (args[1] as PaymentCard); Unit } else null }
    private val chats = fake<ChatsRepository> { _, _ -> null }
    private val chamas = fake<ChamaRepository> { name, args ->
        when (name) {
            "chama" -> flowOf(chama)
            "attachPayment" -> {
                attaches += Triple(args[0] as String, args[1] as String, args[2] as String)
                attachAnswers[minOf(attaches.size - 1, attachAnswers.lastIndex)]
            }
            else -> null
        }
    }

    private fun vm(intent: PayIntent) = PayViewModel(
        intent, ObserveWalletUseCase(walletRepo), CheckRecipientUseCase(walletRepo), RemindRecipientUseCase(walletRepo), PayUseCase(walletRepo),
        ObserveContactsUseCase(contacts), connection, SendPaymentCardUseCase(messages), OpenDirectChatUseCase(chats),
        AttachChamaPaymentUseCase(chamas), ObserveChamaUseCase(chamas),
    )

    private fun TestScope.keep(vm: PayViewModel) { backgroundScope.launch(dispatcher) { vm.state.collect { } } }

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(Monitor(), NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun aContributionGoesToTheTreasurerFromTheChatAndSaysSo() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "otieno", chatId = "g1", displayName = "Otieno", goalName = "Holiday Fund"))
        keep(vm)
        vm.onAmount("1000")
        val s = vm.state.value
        assertTrue(s.contribution)
        assertTrue(s.inFiat)
        // KES 1,000 at 129 is 7.751937 USDC; the goal moves from 250 of 387.6 USDC.
        assertEquals("7751937", s.amountMinor)
        assertEquals(0.665f, s.progressAfter!!, 0.001f)
        vm.onPay()
        assertEquals(PayStep.Done, vm.state.value.step)
        assertEquals("g1", payments.single().chatId)
        assertEquals("otieno", payments.single().toUserId)
        assertEquals("Added to Holiday Fund", payments.single().note)
        assertEquals("g1", cards.single().first)
        assertTrue(attaches.isEmpty())
    }

    @Test fun aPayoutIsTheApprovedAmountInUsdcAndCantBeChanged() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "achieng", chatId = "g1", displayName = "Achieng", amount = "12", goalName = "Holiday Fund", requestId = "r1"))
        keep(vm)
        assertFalse(vm.state.value.inFiat)
        assertEquals("12000000", vm.state.value.amountMinor)
        vm.onAmount("99")
        vm.onToggleUnit()
        assertEquals("12000000", vm.state.value.amountMinor)
        assertFalse(vm.state.value.inFiat)
    }

    @Test fun aPayoutIsPaidOnlyOnceTheLinkLands() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "achieng", chatId = "g1", amount = "12", goalName = "Holiday Fund", requestId = "r1"))
        keep(vm)
        vm.onPay()
        advanceUntilIdle()
        assertEquals(listOf(Triple("g1", "r1", "t1")), attaches)
        assertTrue(vm.state.value.linked)
        assertEquals("Paid from Holiday Fund", payments.single().note)
    }

    @Test fun aLinkThatKeepsFailingIsShownAndCanBeSentAgain() = runTest(dispatcher) {
        attachAnswers = listOf(AppResult.Failure(AppError.Network))
        val vm = vm(PayIntent(userId = "achieng", chatId = "g1", amount = "12", goalName = "Holiday Fund", requestId = "r1"))
        keep(vm)
        vm.onPay()
        advanceUntilIdle()
        assertEquals(3, attaches.size)
        assertTrue(vm.state.value.attachPending)
        assertFalse(vm.state.value.linked)
        assertEquals(1, payments.size) // the money went once; nothing offers to send it again

        attachAnswers = listOf(AppResult.Success(request()))
        vm.onAttachAgain()
        advanceUntilIdle()
        assertFalse(vm.state.value.attachPending)
        assertTrue(vm.state.value.linked)
        assertEquals(1, payments.size)
    }

    @Test fun aRetryThatFindsTheLinkAlreadyThereCountsAsLinked() = runTest(dispatcher) {
        attachAnswers = listOf(AppResult.Failure(AppError.Network), AppResult.Failure(AppError.Api("chama.request_closed", 409, null, null, emptyMap())))
        val vm = vm(PayIntent(userId = "achieng", chatId = "g1", amount = "12", goalName = "Holiday Fund", requestId = "r1"))
        keep(vm)
        vm.onPay()
        advanceUntilIdle()
        assertEquals(2, attaches.size)
        assertTrue(vm.state.value.linked)
        assertNull(vm.state.value.error)
    }

    @Test fun aMismatchStopsAtOnceAndSaysSo() = runTest(dispatcher) {
        attachAnswers = listOf(AppResult.Failure(AppError.Api("chama.transfer_mismatch", 409, null, null, emptyMap())))
        val vm = vm(PayIntent(userId = "achieng", chatId = "g1", amount = "12", goalName = "Holiday Fund", requestId = "r1"))
        keep(vm)
        vm.onPay()
        advanceUntilIdle()
        assertEquals(1, attaches.size)
        assertTrue(vm.state.value.attachPending)
        assertTrue(vm.state.value.error != null)
    }

    @Test fun anOutsidePayeeIsPaidByAddress() = runTest(dispatcher) {
        val vm = vm(PayIntent(chatId = "g1", displayName = "Coast Bus", amount = "12", address = "0xcoast", goalName = "Holiday Fund", requestId = "r1"))
        keep(vm)
        assertEquals(PayStep.Amount, vm.state.value.step)
        assertEquals("Coast Bus", vm.state.value.payee?.displayName)
        vm.onPay()
        advanceUntilIdle()
        assertEquals("0xcoast", payments.single().toAddress)
        assertNull(payments.single().toUserId)
    }
}
