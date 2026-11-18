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
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.wallet.data.local.WalletDao
import com.circaso.feature.wallet.data.remote.WalletRemote
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.AssetTotal
import com.circaso.feature.wallet.domain.CheckRecipientUseCase
import com.circaso.feature.wallet.domain.CreateWalletUseCase
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.InsufficientGasCode
import com.circaso.feature.wallet.domain.NetworkChangedCode
import com.circaso.feature.wallet.domain.NetworkNotice
import com.circaso.feature.wallet.domain.NetworkOption
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.PayUseCase
import com.circaso.feature.wallet.domain.PaymentQuote
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.QuotePaymentUseCase
import com.circaso.feature.wallet.domain.QuoteProblem
import com.circaso.feature.wallet.domain.Recipient
import com.circaso.feature.wallet.domain.RecipientStatus
import com.circaso.feature.wallet.domain.RefreshWalletUseCase
import com.circaso.feature.wallet.domain.RemindRecipientUseCase
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.ValidateAddressUseCase
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import com.circaso.feature.wallet.domain.WalletNetworksState
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.presentation.ComingFlow
import com.circaso.feature.wallet.presentation.ExternalStep
import com.circaso.feature.wallet.presentation.PayBlock
import com.circaso.feature.wallet.presentation.PayIntent
import com.circaso.feature.wallet.presentation.PayStep
import com.circaso.feature.wallet.presentation.PayViewModel
import com.circaso.feature.wallet.presentation.SendToWalletViewModel
import com.circaso.feature.wallet.presentation.SolanaFeeLine
import com.circaso.feature.wallet.presentation.SolanaFeeShort
import com.circaso.feature.wallet.presentation.WalletUiState
import com.circaso.feature.wallet.presentation.WalletViewModel
import com.circaso.feature.wallet.presentation.blockNote
import com.circaso.feature.wallet.presentation.receiptFeeText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An interface answered by name; a flow nobody answers is an empty-handed one (`flowOf(null)`), anything else null. */
private inline fun <reified T : Any> answered(noinline answer: (String, List<Any?>) -> Any? = { _, _ -> null }): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        val list = args.orEmpty().toList().filterNot { it is kotlin.coroutines.Continuation<*> }
        when (method.name) {
            "toString" -> "answered ${T::class.simpleName}"
            "hashCode" -> 0
            "equals" -> false
            else -> answer(method.name, list) ?: if (Flow::class.java.isAssignableFrom(method.returnType)) flowOf(null) else null
        }
    } as T

private fun walletOn(network: WalletNetwork, chain: String, minor: String) = Wallet(
    status = WalletStatus.Active,
    chains = listOf(WalletChain(chain, if (chain.startsWith("SOL")) "So1MyAddress0000000000000000000000000000me" else "0xme", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, minor, "", null)), true)),
    totals = listOf(AssetTotal("USDC", 6, minor, "")),
    fiat = FiatValue("KES", "0", "129.00", "2026-11-18T10:00:00Z", false),
    holdUntil = null, fetchedAt = 0, network = network,
)

/** Someone with both networks; the selection moves when asked, as the real store's does. */
private class TwoNetworks(start: WalletNetwork) : WalletNetworks {
    override val state = MutableStateFlow(
        WalletNetworksState(
            options = listOf(NetworkOption(WalletNetwork.MAINNET, WalletStatus.Active), NetworkOption(WalletNetwork.TESTNET, WalletStatus.Active)),
            selected = start, default = WalletNetwork.MAINNET, known = true, loaded = true,
        ),
    )
    /** Held shut to keep a refresh in the air. */
    var gate: CompletableDeferred<Unit>? = null
    var refreshes = 0
    override suspend fun refresh(): AppResult<Unit> { refreshes++; gate?.await(); return AppResult.Success(Unit) }
    override suspend fun select(network: WalletNetwork): Boolean { state.update { it.copy(selected = network) }; return true }
    override fun noticeShown() { state.update { it.copy(notice = null) } }
}

private class NetworkTestMonitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

private fun quote(fee: String = "20000", problem: QuoteProblem? = null) = PaymentQuote(fee, 50, false, "0", "500000000", null, problem, null, null, null, null, "q1", null)

private fun failure(code: String) = AppResult.Failure(AppError.Api(code, 400, null, null, emptyMap()))

/**
 * Wallet networks in the view models (design review of PR #74): a payment's screen shows the network it is pinned to,
 * the Wallet tab never shows one network's money under the other's name, and Solana's "no SOL for the fee" is a state
 * of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletNetworkViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var connection: ConnectionState
    private val networks = TwoNetworks(WalletNetwork.TESTNET)

    /** Each network's copy on the phone. */
    private val copies = mapOf(
        WalletNetwork.MAINNET to MutableStateFlow<Wallet?>(walletOn(WalletNetwork.MAINNET, "BASE", "84000000")),
        WalletNetwork.TESTNET to MutableStateFlow<Wallet?>(walletOn(WalletNetwork.TESTNET, "BASE-SEPOLIA", "500000000")),
    )
    private val asked = mutableListOf<PaymentRequest>()
    private val paid = mutableListOf<PaymentRequest>()
    private var quoteAnswer: AppResult<PaymentQuote?> = AppResult.Success(quote())
    private var payAnswer: (PaymentRequest) -> AppResult<Transfer> = { r ->
        AppResult.Success(Transfer("t1", TransferDirection.Out, TransferState.Submitted, r.blockchain, "0xme", "mum", "0xmum", Amount("USDC", r.amountMinor, ""), null, null, null, null, "Mum", null, 0, 0))
    }
    /** Runs inside W4 (the PIN is up), before it answers. */
    private var whilePaying: () -> Unit = {}
    private val refreshedOn = mutableListOf<WalletNetwork>()
    private var creates = 0

    private val walletRepo = answered<WalletRepository> { name, args ->
        when (name) {
            "walletOn" -> copies.getValue(args[0] as WalletNetwork)
            "getWallet" -> networks.selected.flatMapLatest { copies.getValue(it) }
            "recipient" -> AppResult.Success(Recipient(args[0] as String, RecipientStatus.CanReceive, emptyList()))
            "quote" -> { asked += args[0] as PaymentRequest; quoteAnswer }
            "pay" -> { val r = args[0] as PaymentRequest; paid += r; whilePaying(); payAnswer(r) }
            "validateAddress" -> AppResult.Success(args[1] as String)
            "refresh" -> { refreshedOn += networks.current(); copies.getValue(networks.current()).value?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.Network) }
            "create" -> { creates++; AppResult.Success(WalletStatus.Active) }
            else -> null
        }
    }
    private val contacts = answered<ContactsRepository> { name, _ -> if (name == "getContacts") flowOf(emptyList<Any>()) else null }
    private val messages = answered<MessagesRepository> { name, _ -> if (name == "sendPayment") Unit else null }
    private val chamas = answered<ChamaRepository>()

    private fun payVm() = PayViewModel(
        PayIntent(userId = "mum", chatId = "c1", displayName = "Mum", inChat = true), ObserveWalletUseCase(walletRepo), CheckRecipientUseCase(walletRepo),
        RemindRecipientUseCase(walletRepo), PayUseCase(walletRepo), ObserveContactsUseCase(contacts), connection, SendPaymentCardUseCase(messages),
        OpenDirectChatUseCase(answered<ChatsRepository>()), AttachChamaPaymentUseCase(chamas), ObserveChamaUseCase(chamas),
        quotePayment = QuotePaymentUseCase(walletRepo), quoteDelayMillis = 400, now = { dispatcher.scheduler.currentTime }, networks = networks,
    )

    private fun sendVm() = SendToWalletViewModel(
        ObserveWalletUseCase(walletRepo), ValidateAddressUseCase(walletRepo), PayUseCase(walletRepo), connection,
        quotePayment = QuotePaymentUseCase(walletRepo), networks = networks,
    )

    private fun walletVm() = WalletViewModel(
        ObserveWalletUseCase(walletRepo), RefreshWalletUseCase(walletRepo), CreateWalletUseCase(walletRepo), connection,
        ObserveProfileUseCase(answered<ProfileRepository>()),
        WalletRepositoryImpl(answered<WalletRemote>(), answered<WalletDao>(), signer = { error("unused") }, currency = { null }, json = Json),
        networks = networks,
    )

    private fun TestScope.keep(vm: PayViewModel) { backgroundScope.launch { vm.state.collect { } } }
    private fun TestScope.keep(vm: SendToWalletViewModel) { backgroundScope.launch { vm.state.collect { } } }
    private fun select(network: WalletNetwork) = runBlocking { networks.select(network) }

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(NetworkTestMonitor(), NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // ---- Pay

    @Test fun thePayCardShowsTheNetworkItIsPinnedToAndThatNetworksWallet() = runTest(dispatcher) {
        val vm = payVm(); keep(vm); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(WalletNetwork.TESTNET, s.network); assertTrue(s.testnet, "the strip is the card's own network")
        assertEquals("BASE-SEPOLIA", s.payChain); assertEquals("500000000", s.balanceMinor)
        vm.onAmount("500"); advanceUntilIdle()
        assertEquals(WalletNetwork.TESTNET, asked.last().network, "the quote is for the pinned network")
    }

    @Test fun anIdleCardMovesToTheNetworkSelectedAndStartsAgainThere() = runTest(dispatcher) {
        val vm = payVm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()
        select(WalletNetwork.MAINNET); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(WalletNetwork.MAINNET, s.network); assertFalse(s.testnet)
        assertEquals("BASE", s.payChain, "the other network's chain, never the one that was left")
        assertEquals("84000000", s.balanceMinor, "and its balance")
        assertEquals(WalletNetwork.MAINNET, asked.last().network, "the fee was asked again, for Mainnet")
        vm.onPay(); advanceUntilIdle()
        assertEquals(WalletNetwork.MAINNET, paid.single().network); assertEquals("BASE", paid.single().blockchain)
    }

    @Test fun aSwitchWhileThePinIsUpKeepsThePinThenResetsTheCard() = runTest(dispatcher) {
        val vm = payVm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()
        var during: WalletNetwork? = null
        // The person moves to Mainnet while Circle's PIN screen is up; the wallet then refuses to sign across it.
        whilePaying = { select(WalletNetwork.MAINNET); dispatcher.scheduler.runCurrent(); during = vm.state.value.network }
        payAnswer = { failure(NetworkChangedCode) }
        vm.onPay(); advanceUntilIdle()
        assertEquals(WalletNetwork.TESTNET, during, "while signing the card stays on the network it was reviewed on")
        assertEquals(WalletNetwork.TESTNET, paid.single().network)
        val s = vm.state.value
        assertEquals(PayStep.Amount, s.step)
        assertEquals(WalletNetwork.MAINNET, s.network, "then it is the selected network's card")
        assertEquals("BASE", s.payChain)
        assertNull(s.failure, "no Try Again for a payment that can't go")
        assertTrue(s.error!!.contains("Mainnet") && s.error!!.contains("Nothing was sent"), s.error)
    }

    @Test fun theNextPaymentInAChatIsForTheNetworkSelectedThen() = runTest(dispatcher) {
        val vm = payVm(); keep(vm); advanceUntilIdle()
        select(WalletNetwork.MAINNET)
        vm.onQuickReset(); advanceUntilIdle()
        assertEquals(WalletNetwork.MAINNET, vm.state.value.network)
    }

    @Test fun tooLittleSolIsItsOwnStateFromTheQuote() = runTest(dispatcher) {
        copies.getValue(WalletNetwork.TESTNET).value = walletOn(WalletNetwork.TESTNET, "SOL-DEVNET", "500000000")
        quoteAnswer = AppResult.Success(quote(fee = "0", problem = QuoteProblem.InsufficientGas))
        val vm = payVm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(PayBlock.Gas, s.block); assertFalse(s.canPay)
        val note = assertNotNull(blockNote(s))
        assertEquals("You need SOL for the network fee", note.title)
        assertEquals("Copy Solana Address", note.action); assertTrue("So1MyA" in note.text, note.text)
    }

    @Test fun tooLittleSolIsTheSameStateWhenW17RefusesAndWhenW4Does() = runTest(dispatcher) {
        copies.getValue(WalletNetwork.TESTNET).value = walletOn(WalletNetwork.TESTNET, "SOL-DEVNET", "500000000")
        quoteAnswer = failure(InsufficientGasCode)
        val vm = payVm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()
        assertEquals(PayBlock.Gas, vm.state.value.block, "W17 refused with the code")
        // W17 let it through, W4 found the wallet short.
        quoteAnswer = AppResult.Success(quote(fee = "0"))
        vm.onAmount("400"); advanceUntilIdle()
        assertNull(vm.state.value.block); assertTrue(vm.state.value.canPay)
        payAnswer = { failure(InsufficientGasCode) }
        vm.onPay(); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(PayStep.Amount, s.step); assertEquals(PayBlock.Gas, s.block); assertNull(s.failure)
    }

    @Test fun onSolanaTheFeeLineSaysWhoPaysWhat() = runTest(dispatcher) {
        copies.getValue(WalletNetwork.TESTNET).value = walletOn(WalletNetwork.TESTNET, "SOL-DEVNET", "500000000")
        quoteAnswer = AppResult.Success(quote(fee = "0"))
        val vm = payVm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.onSolana)
        assertEquals(SolanaFeeLine, s.feeText); assertEquals("No Circaso fee · network fee paid in SOL", SolanaFeeLine)
        assertEquals(SolanaFeeShort, s.feeShort, "the chat's slim card")
        // The receipt, later: never "No fee" for a Solana payment.
        assertEquals(SolanaFeeLine, receiptFeeText(null, null, "SOL"))
        assertEquals(SolanaFeeLine, receiptFeeText(Amount("USDC", "0", "0"), null, "SOL-DEVNET"))
        assertEquals("No fee", receiptFeeText(null, null, "BASE"))
        assertEquals("0.02 USDC", receiptFeeText(Amount("USDC", "20000", ""), null, "BASE"))
    }

    @Test fun offSolanaTheFeeLineIsAsBefore() = runTest(dispatcher) {
        quoteAnswer = AppResult.Success(quote(fee = "0"))
        val vm = payVm(); keep(vm); advanceUntilIdle()
        vm.onAmount("500"); advanceUntilIdle()
        assertEquals("No fee", vm.state.value.feeText); assertEquals("No fee", vm.state.value.feeShort)
    }

    // ---- Send to a wallet

    @Test fun sendShowsItsPinnedNetworkAndGoesBackToTheFormWhenTheNetworkMoves() = runTest(dispatcher) {
        val vm = sendVm(); keep(vm); advanceUntilIdle()
        assertEquals(WalletNetwork.TESTNET, vm.state.value.network); assertTrue(vm.state.value.testnet)
        assertEquals("BASE-SEPOLIA", vm.state.value.blockchain)
        vm.onAddress("0xabc"); vm.onAmount("12"); runCurrent(); vm.onReview(); advanceUntilIdle()
        assertEquals(ExternalStep.Review, vm.state.value.step)
        assertEquals(WalletNetwork.TESTNET, asked.last().network)
        select(WalletNetwork.MAINNET); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(ExternalStep.Form, s.step, "what was reviewed was the other network's")
        assertEquals(WalletNetwork.MAINNET, s.network); assertFalse(s.testnet)
        assertEquals("BASE", s.blockchain); assertEquals("84000000", s.balanceMinor)
        assertNull(s.checkedAddress)
        assertTrue(s.error!!.contains("Mainnet"), s.error)
        assertEquals("12", s.amountText, "what was typed is kept")
    }

    @Test fun aSendBeingSignedKeepsItsNetworkAndMovesOnlyOnceItEnds() = runTest(dispatcher) {
        val vm = sendVm(); keep(vm); advanceUntilIdle()
        vm.onAddress("0xabc"); vm.onAmount("12"); runCurrent(); vm.onReview(); advanceUntilIdle()
        var during: Pair<ExternalStep, WalletNetwork>? = null
        whilePaying = { select(WalletNetwork.MAINNET); dispatcher.scheduler.runCurrent(); during = vm.state.value.let { it.step to it.network } }
        payAnswer = { failure(NetworkChangedCode) }
        runCurrent(); vm.onConfirm(); advanceUntilIdle()
        assertEquals(ExternalStep.Signing to WalletNetwork.TESTNET, during)
        assertEquals(WalletNetwork.TESTNET, paid.single().network)
        assertEquals(ExternalStep.Form, vm.state.value.step); assertEquals(WalletNetwork.MAINNET, vm.state.value.network)
        assertNull(vm.state.value.failure)
    }

    @Test fun sendSaysTooLittleSolAsANoteOnTheFormNotAToast() = runTest(dispatcher) {
        copies.getValue(WalletNetwork.TESTNET).value = walletOn(WalletNetwork.TESTNET, "SOL-DEVNET", "500000000")
        quoteAnswer = AppResult.Success(quote(fee = "0", problem = QuoteProblem.InsufficientGas))
        val vm = sendVm(); keep(vm); advanceUntilIdle()
        vm.onAddress("So1Them"); vm.onAmount("12"); runCurrent(); vm.onReview(); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(ExternalStep.Form, s.step); assertTrue(s.gasShort); assertNull(s.error, "not a passing toast")
        assertEquals("So1MyAddress0000000000000000000000000000me", s.chainAddress, "the address to add SOL to")
        assertEquals(SolanaFeeLine, s.feeText)
        vm.onChain("SOL-DEVNET"); runCurrent()
        assertFalse(vm.state.value.gasShort, "gone once the form changes")
    }

    // ---- The Wallet tab

    @Test fun theTabNeverShowsOneNetworksMoneyUnderTheOthersName() = runTest(dispatcher) {
        val vm = walletVm()
        val seen = mutableListOf<WalletUiState>()
        backgroundScope.launch { vm.state.collect { seen += it } }
        advanceUntilIdle()
        // Back and forth, each step drawn as it happens (no settling in between is assumed).
        vm.onNetwork(WalletNetwork.MAINNET); advanceUntilIdle()
        vm.onNetwork(WalletNetwork.TESTNET); runCurrent()
        vm.onNetwork(WalletNetwork.MAINNET); advanceUntilIdle()
        runCurrent() // the collector itself (background work advanceUntilIdle doesn't wait for)
        assertTrue(seen.size > 3, "the switches were observed: ${seen.size}")
        seen.forEach { s -> assertTrue(s.wallet == null || s.wallet!!.network == s.network, "${s.network} label over ${s.wallet?.network} money") }
        // (The very first frame, before anything is read, has no money and no mark.)
        seen.filter { it.wallet != null }.forEach { s -> assertEquals(s.network == WalletNetwork.TESTNET, s.testnet, "the mark follows the money on screen") }
        val last = seen.last()
        assertEquals(WalletNetwork.MAINNET, last.network); assertEquals("84000000", last.wallet?.usdc?.amountMinor)
        assertTrue(seen.any { it.network == WalletNetwork.TESTNET && it.wallet?.usdc?.amountMinor == "500000000" })
        assertTrue(last.showNetworkSwitch)
    }

    @Test fun switchingClosesWhatBelongedToTheOtherNetworkAndRefreshesTheNewOne() = runTest(dispatcher) {
        val vm = walletVm(); backgroundScope.launch { vm.state.collect { } }; advanceUntilIdle()
        vm.onReceive(true); vm.onComing(ComingFlow.CashOut); runCurrent()
        assertTrue(vm.state.value.receiveSheet)
        refreshedOn.clear()
        vm.onNetwork(WalletNetwork.MAINNET); advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.receiveSheet, "Testnet's address is not left up over Mainnet"); assertNull(s.coming); assertNull(s.error)
        assertEquals(listOf(WalletNetwork.MAINNET), refreshedOn, "then the picked network is refreshed")
        assertFalse(s.loading)
    }

    @Test fun aRefreshInTheAirForTheNetworkThatWasLeftIsDropped() = runTest(dispatcher) {
        val vm = walletVm(); backgroundScope.launch { vm.state.collect { } }; advanceUntilIdle()
        refreshedOn.clear()
        val gate = CompletableDeferred<Unit>()
        networks.gate = gate
        vm.refresh(); runCurrent() // Testnet's refresh, waiting on the server
        assertTrue(vm.state.value.loading)
        networks.gate = null
        vm.onNetwork(WalletNetwork.MAINNET); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(WalletNetwork.MAINNET), refreshedOn, "the old refresh never went on to read a wallet")
        assertFalse(vm.state.value.loading)
        assertEquals(WalletNetwork.MAINNET, vm.state.value.network)
    }

    @Test fun theSwitchIsIgnoredWhileAWalletIsBeingCreated() = runTest(dispatcher) {
        val vm = walletVm(); backgroundScope.launch { vm.state.collect { } }; advanceUntilIdle()
        vm.onCreate() // Circle's PIN screen is up
        vm.onNetwork(WalletNetwork.MAINNET)
        advanceUntilIdle()
        assertEquals(1, creates)
        assertEquals(WalletNetwork.TESTNET, networks.current(), "the wallet being made is not left half way for another network")
        assertEquals(WalletNetwork.TESTNET, vm.state.value.network)
    }

    @Test fun aNetworkTakenAwayIsSaidOnceThenCleared() = runTest(dispatcher) {
        val vm = walletVm(); backgroundScope.launch { vm.state.collect { } }; advanceUntilIdle()
        networks.state.update {
            it.copy(options = listOf(NetworkOption(WalletNetwork.MAINNET, WalletStatus.Active)), selected = WalletNetwork.MAINNET, notice = NetworkNotice(WalletNetwork.TESTNET, WalletNetwork.MAINNET))
        }
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(NetworkNotice(WalletNetwork.TESTNET, WalletNetwork.MAINNET), s.networkNotice)
        assertFalse(s.showNetworkSwitch); assertFalse(s.testnet); assertEquals(WalletNetwork.MAINNET, s.network)
        vm.onNetworkNoticeShown(); advanceUntilIdle()
        assertNull(vm.state.value.networkNotice)
    }

    @Test fun aFirstVisitToANetworkWithNoWalletIsTheCreateScreenAtOnceEvenOffline() = runTest(dispatcher) {
        copies.getValue(WalletNetwork.MAINNET).value = null // never read on this phone
        networks.state.update { it.copy(options = listOf(NetworkOption(WalletNetwork.MAINNET, WalletStatus.None), NetworkOption(WalletNetwork.TESTNET, WalletStatus.Active))) }
        val vm = walletVm(); backgroundScope.launch { vm.state.collect { } }; advanceUntilIdle()
        assertFalse(vm.state.value.createAtOnce, "Testnet has its wallet")
        vm.onNetwork(WalletNetwork.MAINNET); runCurrent()
        val s = vm.state.value
        assertEquals(WalletNetwork.MAINNET, s.network); assertNull(s.wallet)
        assertTrue(s.createAtOnce, "the server's list already says there is none: no skeleton to wait behind")
        assertEquals(WalletStatus.None, s.createStatus)
    }
}
