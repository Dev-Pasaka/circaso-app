package com.circaso.feature.wallet

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
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.CheckRecipientUseCase
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.PayUseCase
import com.circaso.feature.wallet.domain.Recipient
import com.circaso.feature.wallet.domain.RecipientStatus
import com.circaso.feature.wallet.domain.RemindRecipientUseCase
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.presentation.PayIntent
import com.circaso.feature.wallet.presentation.PayViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

private inline fun <reified T : Any> stub(noinline answer: (String, List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        val list = args.orEmpty().toList().filterNot { it is kotlin.coroutines.Continuation<*> }
        when (method.name) {
            "toString" -> "stub ${T::class.simpleName}"
            "hashCode" -> 0
            "equals" -> false
            else -> answer(method.name, list)
        }
    } as T

private class Online : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

/**
 * Typing money into a coin other than USDC (2026-11-06): the unit a number is typed in is fixed when it is typed, so
 * a refresh that loses the price, or an old price, never changes what a typed amount means.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoinTypingViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val now = 1_000_000_000L
    private val refreshes = mutableListOf<Long>()

    private fun wallet(ethPrice: String?, fetchedAt: Long = now) = Wallet(
        WalletStatus.Active,
        listOf(
            WalletChain(
                "BASE-SEPOLIA", "0xme", "ACTIVE",
                listOf(
                    Asset("USDC", "0xusdc", 6, "500000000", "500", "64625"),
                    Asset("ETH", "", 18, "2000000000000000000", "2", null, ethPrice),
                ),
                true,
            ),
        ),
        emptyList(), FiatValue("KES", "0", "129.25", "2026-11-06T00:00:00Z", false), null, fetchedAt,
    )

    private val wallets = MutableStateFlow(wallet("3500"))

    private val repo = stub<WalletRepository> { name, args ->
        when (name) {
            "getWallet" -> wallets
            "recipient" -> AppResult.Success(Recipient(args[0] as String, RecipientStatus.CanReceive, listOf("BASE-SEPOLIA")))
            else -> null
        }
    }

    private fun vm(): PayViewModel {
        val contacts = stub<ContactsRepository> { name, _ -> if (name == "getContacts") flowOf(emptyList<Any>()) else null }
        val chamas = stub<ChamaRepository> { _, _ -> null }
        return PayViewModel(
            PayIntent(userId = "otieno", chatId = "c1", displayName = "Otieno", inChat = true),
            ObserveWalletUseCase(repo), CheckRecipientUseCase(repo), RemindRecipientUseCase(repo), PayUseCase(repo),
            ObserveContactsUseCase(contacts), ConnectionState(Online(), NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0),
            SendPaymentCardUseCase(stub<MessagesRepository> { _, _ -> null }), OpenDirectChatUseCase(stub<ChatsRepository> { _, _ -> null }),
            AttachChamaPaymentUseCase(chamas), ObserveChamaUseCase(chamas),
            now = { now }, refreshWallet = { refreshes += now },
        )
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun hashFiveHundredAtEthIsKesFiveHundredAndAskesForAFreshPrice() = runTest(dispatcher) {
        val vm = vm()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.onAmount("500")
        vm.onAssetTag("eth")

        val s = vm.state.value
        assertEquals("1105277701022381", s.amountMinor)
        assertEquals("KES 500", s.fiatText)
        assertEquals(1, refreshes.size, "a coin worked out at its price asks for a fresh one")
    }

    @Test fun aRefreshThatLosesThePriceNeverTurnsKesIntoEth() = runTest(dispatcher) {
        val vm = vm()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.onAmount("500")
        vm.onAssetTag("eth")

        wallets.value = wallet(null)

        val s = vm.state.value
        assertNull(s.amountMinor, "not 500 ETH")
        assertTrue(s.priceWaiting)
        assertFalse(s.canPay)
        assertEquals("KES 500", s.fiatText, "still what was typed")

        wallets.value = wallet("3600")
        assertEquals("KES 500", vm.state.value.fiatText)
        assertEquals("1074575542660649", vm.state.value.amountMinor, "worked out again at the new price")
    }

    @Test fun anOldPriceWaitsForAFreshOne() = runTest(dispatcher) {
        wallets.value = wallet("3500", fetchedAt = now - 10 * 60 * 1000)
        val vm = vm()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.onAmount("500")
        vm.onAssetTag("eth")

        assertTrue(vm.state.value.priceWaiting)
        assertTrue(vm.state.value.coinPriced, "it has a price, just not a fresh one: \"Getting the latest ETH price…\"")
        assertNull(vm.state.value.amountMinor)

        wallets.value = wallet("3500")
        assertEquals("1105277701022381", vm.state.value.amountMinor)
    }

    @Test fun aCoinWithNoPriceAtAllIsTypedInItsOwnUnitsAsBefore() = runTest(dispatcher) {
        wallets.value = wallet(null)
        val vm = vm()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.onAmount("0.5")
        vm.onAssetTag("eth")
        assertEquals("500000000000000000", vm.state.value.amountMinor)
        assertFalse(vm.state.value.priceWaiting)
    }

    @Test fun switchingUnitsKeepsTheAmountWithinACent() = runTest(dispatcher) {
        val vm = vm()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.onAssetTag("eth")
        vm.onAmount("500")
        val minor = vm.state.value.amountMinor

        vm.onToggleUnit()
        assertEquals("0.001105277701022381", vm.state.value.amountText, "now in ETH, exactly")
        assertEquals(minor, vm.state.value.amountMinor)

        vm.onToggleUnit()
        assertEquals("500.00", vm.state.value.amountText)
        vm.onAmount("500.001")
        assertEquals("500.00", vm.state.value.amountText, "two decimals while typing money")
    }
}
