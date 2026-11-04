package com.circaso.feature.wallet

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.flags.GroupWalletsFlag
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.chama.domain.ApprovalPolicy
import com.circaso.feature.chama.domain.ChamaRepository
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.CreateChamaRequestUseCase
import com.circaso.feature.chama.domain.DecideChamaRequestUseCase
import com.circaso.feature.chama.domain.GroupMoney
import com.circaso.feature.chama.domain.GroupWalletRef
import com.circaso.feature.chama.domain.ObserveGroupMoneyUseCase
import com.circaso.feature.chama.domain.RefreshGroupMoneyUseCase
import com.circaso.feature.chama.domain.RequestState
import com.circaso.feature.chama.presentation.GroupWalletReader
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.messaging.domain.ObserveChatsUseCase
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.wallet.data.local.WalletDao
import com.circaso.feature.wallet.data.remote.WalletRemote
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.AssetTotal
import com.circaso.feature.wallet.domain.CreateWalletUseCase
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.ObserveTransfersUseCase
import com.circaso.feature.wallet.domain.ObserveWalletActivityUseCase
import com.circaso.feature.wallet.domain.ObserveWalletOfUseCase
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.ObserveWalletsUseCase
import com.circaso.feature.wallet.domain.RefreshWalletActivityUseCase
import com.circaso.feature.wallet.domain.RefreshWalletOfUseCase
import com.circaso.feature.wallet.domain.RefreshWalletUseCase
import com.circaso.feature.wallet.domain.RefreshWalletsUseCase
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletListing
import com.circaso.feature.wallet.domain.WalletOwner
import com.circaso.feature.wallet.domain.WalletOwnerType
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.presentation.GroupWalletSources
import com.circaso.feature.wallet.presentation.NamedWallet
import com.circaso.feature.wallet.presentation.WalletViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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

/** [answer] first; anything it leaves out is an empty flow or a failure. */
private inline fun <reified T : Any> stub(noinline answer: (String, List<Any?>) -> Any? = { _, _ -> null }): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        val list = args.orEmpty().toList().filterNot { it is kotlin.coroutines.Continuation<*> }
        when (method.name) {
            "toString" -> "stub ${T::class.simpleName}"
            "hashCode" -> 0
            "equals" -> false
            else -> answer(method.name, list) ?: when {
                Flow::class.java.isAssignableFrom(method.returnType) -> flowOf(null)
                else -> AppResult.Failure(AppError.Unexpected(null))
            }
        }
    } as T

private class SwitchMonitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

private fun aWallet(minor: String, walletId: String? = null) = Wallet(
    status = WalletStatus.Active,
    chains = listOf(WalletChain("BASE-SEPOLIA", "0xme", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, minor, "", null)), true)),
    totals = listOf(AssetTotal("USDC", 6, minor, "")),
    fiat = FiatValue("KES", "0", "129.00", "2026-10-29T10:00:00Z", false), holdUntil = null, fetchedAt = 0,
    walletId = walletId, owner = walletId?.let { WalletOwner(WalletOwnerType.Group, null, "g1", "otieno") },
)

private val everyone = listOf(
    ChatMember("me", "Pasaka Mutuku", null, null, MemberRole.Member, ""),
    ChatMember("otieno", "Otieno Ouma", null, null, MemberRole.Owner, ""),
)
private val kilimani = Chat("g1", ChatKind.Group, "Kilimani Chama", null, everyone, false, 1, 1, MemberRole.Member, null)

private val personalListing = WalletListing("me", WalletOwner(WalletOwnerType.Person, "me", null, "me"), WalletStatus.Active, true, listOf("BASE-SEPOLIA"))
private val groupListing = WalletListing("gw-1", WalletOwner(WalletOwnerType.Group, null, "g1", "otieno"), WalletStatus.Active, false, listOf("BASE-SEPOLIA"))

private fun request() = ChamaRequest("r1", "g1", "otieno", "me", null, null, "12000000", "bus", RequestState.Pending, 2, emptyList(), null, "", "", "")

private fun groupMoney() = GroupMoney(
    chatId = "g1", wallet = GroupWalletRef("gw-1", true, "otieno"), approvalPolicy = ApprovalPolicy.TwoAdmins, goal = null, memberCount = 2,
    contributedMinor = "0", myContributedMinor = "0", contributors = emptyList(), paidOutMinor = "0", openRequests = listOf(request()),
    isAdmin = false, isOwner = false, isSigner = false, fetchedAt = 0,
)

private val contribution = Transfer("c1", TransferDirection.In, TransferState.Confirmed, "BASE-SEPOLIA", "0xwanjiru", null, "", Amount("USDC", "7740000", "7.74"), null, "g1", null, null, null, null, 0, 0, walletId = "gw-1")

/** The Wallet tab's corner switcher (GW16 as redrawn 2026-11-04): what it lists, and what the tab shows once one is picked. */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletSwitchTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var connection: ConnectionState
    private val monitor = SwitchMonitor()
    private val listings = MutableStateFlow(listOf(personalListing, groupListing))
    private val chat = MutableStateFlow<Chat?>(kilimani)
    private val moneyReads = mutableListOf<String>()
    private val decisions = mutableListOf<List<Any?>>()

    private val walletRepo = stub<WalletRepository> { name, args ->
        when (name) {
            "getWallet" -> flowOf(aWallet("500000000"))
            "getTransfers" -> flowOf(emptyList<Transfer>())
            "getWallets" -> listings
            "walletOf" -> flowOf(if (args[0] == "gw-1") aWallet("100000000", "gw-1") else aWallet("500000000"))
            "activity" -> flowOf(listOf(contribution))
            "refresh" -> AppResult.Success(aWallet("500000000"))
            "refreshWallets" -> AppResult.Success(listings.value)
            "refreshWallet" -> AppResult.Success(aWallet("100000000", "gw-1"))
            "refreshActivity" -> AppResult.Success(Unit)
            else -> null
        }
    }
    private val chats = stub<ChatsRepository> { name, _ ->
        when (name) {
            "getChats" -> flowOf(listOf(ChatSummary(kilimani, null, 0)))
            "chat" -> chat
            else -> null
        }
    }
    private val chamas = stub<ChamaRepository> { name, args ->
        when (name) {
            "money" -> flowOf(groupMoney())
            "refreshMoney" -> { moneyReads += args[0] as String; AppResult.Success(groupMoney()) }
            "decide" -> { decisions += args; AppResult.Success(request()) }
            else -> null
        }
    }

    private fun vm(flag: GroupWalletsFlag = GroupWalletsFlag.on()): WalletViewModel {
        val impl = WalletRepositoryImpl(stub<WalletRemote>(), stub<WalletDao>(), signer = { error("unused") }, currency = { null }, json = Json)
        val sources = GroupWalletSources(
            ObserveWalletsUseCase(walletRepo), RefreshWalletsUseCase(walletRepo), ObserveWalletOfUseCase(walletRepo), RefreshWalletOfUseCase(walletRepo),
            ObserveChatsUseCase(chats), ObserveChatUseCase(chats), ObserveGroupMoneyUseCase(chamas), RefreshGroupMoneyUseCase(chamas), CreateChamaRequestUseCase(chamas),
        )
        val reader = GroupWalletReader(
            ObserveChatUseCase(chats), ObserveGroupMoneyUseCase(chamas), RefreshGroupMoneyUseCase(chamas), ObserveWalletOfUseCase(walletRepo), RefreshWalletOfUseCase(walletRepo),
            ObserveWalletActivityUseCase(walletRepo), RefreshWalletActivityUseCase(walletRepo), DecideChamaRequestUseCase(chamas), auth = null,
        )
        return WalletViewModel(
            ObserveWalletUseCase(walletRepo), RefreshWalletUseCase(walletRepo), CreateWalletUseCase(walletRepo), ObserveTransfersUseCase(walletRepo),
            connection, ObserveProfileUseCase(stub<ProfileRepository>()), impl, groupWallets = flag, sources = sources, groupReader = reader,
        )
    }

    private fun TestScope.keep(vm: WalletViewModel) { backgroundScope.launch(dispatcher) { vm.state.collect { } } }

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun withTheFlagOffThereIsNoSwitcherAndThePopoverNeverOpens() = runTest(dispatcher) {
        val vm = vm(GroupWalletsFlag.off())
        keep(vm)
        assertFalse(vm.state.value.showSwitcher)
        vm.onSwitching(true)
        assertFalse(vm.state.value.switching)
        assertTrue(vm.state.value.selected.personal)
        assertNull(vm.state.value.group)
    }

    @Test fun theSwitcherListsPersonalAndTheGroupsWalletWithTheirBalances() = runTest(dispatcher) {
        val vm = vm()
        keep(vm)
        val s = vm.state.value
        assertTrue(s.showSwitcher)
        assertEquals(listOf(null, "gw-1"), s.wallets.map { it.walletId })
        assertEquals("Kilimani Chama", s.wallets[1].name, "named from the chat on this phone")
        assertEquals("100000000", s.balances["gw-1"]?.usdc?.amountMinor)
        assertEquals("500000000", s.balances[null]?.usdc?.amountMinor)
        assertTrue(s.lockedWallets.isEmpty())
        vm.onSwitching(true)
        assertTrue(vm.state.value.switching)
    }

    @Test fun pickingAGroupShowsItsMoneyInTheSameStateAndReadsItFresh() = runTest(dispatcher) {
        val vm = vm()
        keep(vm)
        vm.onSwitching(true)
        vm.onSelectWallet(vm.state.value.wallets[1])
        val s = vm.state.value
        assertFalse(s.switching, "a pick closes the popover")
        assertEquals("gw-1", s.selectedWalletId)
        assertEquals("g1", s.selectedGroupChat)
        val g = assertNotNull(s.group)
        assertEquals("100000000", g.wallet?.usdc?.amountMinor, "the group's balance, not the personal one")
        assertEquals(listOf("c1"), g.activity.map { it.transferId })
        assertEquals("Contribution", g.rows.single().second)
        assertEquals("Group wallet · only Otieno can send, after approval", g.caption, "a member: the signer sends")
        assertFalse(g.loading)
        assertTrue("g1" in moneyReads, "CH7 read when the group is picked")
        assertEquals("500000000", s.wallet?.usdc?.amountMinor, "the personal wallet stays read for the rest of the app")
    }

    @Test fun backToPersonalDropsTheGroup() = runTest(dispatcher) {
        val vm = vm()
        keep(vm)
        vm.onSelectWallet(vm.state.value.wallets[1])
        vm.onSelectWallet(NamedWallet.Personal)
        assertNull(vm.state.value.group)
        assertTrue(vm.state.value.selected.personal)
    }

    @Test fun aWalletThatGoesAwayFallsBackToPersonal() = runTest(dispatcher) {
        val vm = vm()
        keep(vm)
        vm.onSelectWallet(vm.state.value.wallets[1])
        vm.onSwitching(true)
        listings.value = listOf(personalListing)
        val s = vm.state.value
        assertNull(s.selectedWalletId)
        assertFalse(s.showSwitcher)
        assertFalse(s.switching, "nothing left to switch to")
        assertNull(s.group)
    }

    @Test fun aWalletWhoseSignerLeftIsMarkedLocked() = runTest(dispatcher) {
        chat.value = kilimani.copy(members = everyone.filter { it.userId != "otieno" })
        val vm = vm()
        keep(vm)
        assertEquals(setOf("gw-1"), vm.state.value.lockedWallets)
        vm.onSelectWallet(vm.state.value.wallets[1])
        val g = assertNotNull(vm.state.value.group)
        assertTrue(g.locked)
        assertTrue(g.caption.startsWith("Group wallet · locked"))
    }

    @Test fun approvingIsOnlineOnly() = runTest(dispatcher) {
        val vm = vm()
        keep(vm)
        vm.onSelectWallet(vm.state.value.wallets[1])
        monitor.osStatus.value = ConnectionStatus.Offline
        vm.onDecide(request(), approve = true)
        assertTrue(decisions.isEmpty(), "nothing sent offline")
        monitor.osStatus.value = ConnectionStatus.Online
        vm.onDecide(request(), approve = true)
        assertEquals(listOf<Any?>("r1", true), decisions.single())
    }
}
