package com.circaso.feature.wallet

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.flags.GroupWalletsFlag
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.chama.domain.ApprovalPolicy
import com.circaso.feature.chama.domain.AttachChamaPaymentUseCase
import com.circaso.feature.chama.domain.ChamaGoal
import com.circaso.feature.chama.domain.ChamaRepository
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.CreateChamaRequestUseCase
import com.circaso.feature.chama.domain.GroupMoney
import com.circaso.feature.chama.domain.GroupWalletRef
import com.circaso.feature.chama.domain.ObserveChamaUseCase
import com.circaso.feature.chama.domain.ObserveGroupMoneyUseCase
import com.circaso.feature.chama.domain.RefreshGroupMoneyUseCase
import com.circaso.feature.chama.domain.RequestState
import com.circaso.feature.chama.domain.SetApprovalPolicyUseCase
import com.circaso.feature.chama.domain.SetGoalUseCase
import com.circaso.feature.chama.presentation.GroupInfoMoney
import com.circaso.feature.chama.presentation.GroupWalletPlan
import com.circaso.feature.chama.presentation.GroupWalletSetupViewModel
import com.circaso.feature.chama.presentation.SetupProblem
import com.circaso.feature.chama.presentation.SetupStep
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.MessagesRepository
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.messaging.domain.ObserveChatsUseCase
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.messaging.domain.PaymentCard
import com.circaso.feature.messaging.domain.SendPaymentCardUseCase
import com.circaso.feature.messaging.presentation.ChatInfoUiState
import com.circaso.feature.wallet.domain.AddWalletNetworksUseCase
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.AssetTotal
import com.circaso.feature.wallet.domain.AwaitGroupWalletUseCase
import com.circaso.feature.wallet.domain.CheckRecipientUseCase
import com.circaso.feature.wallet.domain.EnableWalletBiometricsUseCase
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.GroupWallet
import com.circaso.feature.wallet.domain.ObserveWalletOfUseCase
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.ObserveWalletsUseCase
import com.circaso.feature.wallet.domain.PayUseCase
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.Recipient
import com.circaso.feature.wallet.domain.RecipientStatus
import com.circaso.feature.wallet.domain.RefreshWalletOfUseCase
import com.circaso.feature.wallet.domain.RefreshWalletsUseCase
import com.circaso.feature.wallet.domain.RemindRecipientUseCase
import com.circaso.feature.wallet.domain.SignPurpose
import com.circaso.feature.wallet.domain.SignSubject
import com.circaso.feature.wallet.domain.StartGroupWalletUseCase
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
import com.circaso.feature.wallet.presentation.PayIntent
import com.circaso.feature.wallet.presentation.PayStep
import com.circaso.feature.wallet.presentation.PayViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertNotNull
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

private class GwMemoryStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
}

private class GwMonitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

private fun walletOf(minor: String, walletId: String? = null) = Wallet(
    status = WalletStatus.Active,
    chains = listOf(WalletChain("BASE-SEPOLIA", "0xme", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, minor, "", null)), true)),
    totals = listOf(AssetTotal("USDC", 6, minor, "")),
    fiat = FiatValue("KES", "0", "129.00", "2026-10-29T10:00:00Z", false), holdUntil = null, fetchedAt = 0,
    walletId = walletId, owner = walletId?.let { WalletOwner(WalletOwnerType.Group, null, "g1", "me") },
)

private val personal = walletOf("500000000")
private val groupBalance = walletOf("100000000", "gw-1")

private val members = listOf(
    ChatMember("me", "Pasaka Mutuku", null, null, MemberRole.Owner, ""),
    ChatMember("otieno", "Otieno Ouma", null, null, MemberRole.Admin, ""),
    ChatMember("wanjiru", "Wanjiru Kamau", null, null, MemberRole.Admin, ""),
    ChatMember("achieng", "Achieng Odhiambo", null, null, MemberRole.Member, ""),
)
private val group = Chat("g1", ChatKind.Group, "Kilimani Chama", null, members, false, 1, 1, MemberRole.Owner, null)

private val listings = listOf(
    WalletListing("me", WalletOwner(WalletOwnerType.Person, "me", null, "me"), WalletStatus.Active, true, listOf("BASE-SEPOLIA")),
    WalletListing("gw-1", WalletOwner(WalletOwnerType.Group, null, "g1", "me"), WalletStatus.Active, true, listOf("BASE-SEPOLIA")),
)

private fun approved() = ChamaRequest(
    "r1", "g1", "otieno", "achieng", null, null, "12000000", "bus", RequestState.Approved, 2,
    listOf(com.circaso.feature.chama.domain.Approval("otieno", true, ""), com.circaso.feature.chama.domain.Approval("wanjiru", true, "")), null, "", "", "",
)

private fun money(policy: ApprovalPolicy = ApprovalPolicy.TwoAdmins) = GroupMoney(
    chatId = "g1", wallet = GroupWalletRef("gw-1", true, "me"), approvalPolicy = policy,
    goal = ChamaGoal("g1", "Holiday Fund", "387596899", null, "me", 1), memberCount = 4, contributedMinor = "250000000", myContributedMinor = "0",
    contributors = emptyList(), paidOutMinor = "0", openRequests = listOf(approved()), isAdmin = true, isOwner = true, isSigner = true, fetchedAt = 0,
)

private fun transfer(id: String, minor: String) =
    Transfer(id, TransferDirection.Out, TransferState.Submitted, "BASE-SEPOLIA", "0xme", null, "", Amount("USDC", minor, ""), null, "g1", null, null, null, null, 0, 0)

@OptIn(ExperimentalCoroutinesApi::class)
class GroupWalletPayViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var connection: ConnectionState
    private val payments = mutableListOf<PaymentRequest>()
    private val cards = mutableListOf<Pair<String, PaymentCard>>()
    private val asked = mutableListOf<List<Any?>>()
    private val attaches = mutableListOf<Any?>()
    private var payAnswer: (PaymentRequest) -> AppResult<Transfer> = { r -> AppResult.Success(transfer("t${payments.size}", r.amountMinor)) }

    private val walletRepo = fake<WalletRepository> { name, args ->
        when (name) {
            "getWallet" -> flowOf(personal)
            "getWallets" -> flowOf(listings)
            "walletOf" -> flowOf(if (args[0] == "gw-1") groupBalance else personal)
            "refreshWallets" -> AppResult.Success(listings)
            "refreshWallet" -> AppResult.Success(groupBalance)
            "recipient" -> AppResult.Success(Recipient(args[0] as String, RecipientStatus.CanReceive, listOf("BASE-SEPOLIA")))
            "pay" -> { val r = args[0] as PaymentRequest; payments += r; payAnswer(r) }
            "transfer" -> followed
            else -> null
        }
    }
    private val followed = MutableStateFlow<Transfer?>(null)
    private val contacts = fake<ContactsRepository> { name, _ -> if (name == "getContacts") flowOf(emptyList<Any>()) else null }
    private val messages = fake<MessagesRepository> { name, args -> if (name == "sendPayment") { cards += (args[0] as String) to (args[1] as PaymentCard); Unit } else null }
    private val chats = fake<ChatsRepository> { name, _ ->
        when (name) {
            "getChats" -> flowOf(listOf(ChatSummary(group, null, 0)))
            "chat" -> flowOf(group)
            else -> null
        }
    }
    private val chamas = fake<ChamaRepository> { name, args ->
        when (name) {
            "chama" -> flowOf(null)
            "money" -> flowOf(money())
            "refreshMoney" -> AppResult.Success(money())
            "createRequest" -> { asked.add(args); AppResult.Success(approved().copy(state = RequestState.Pending, approvals = emptyList())) }
            "attachPayment" -> { attaches.add(args); AppResult.Success(approved()) }
            else -> null
        }
    }

    private fun sources() = GroupWalletSources(
        ObserveWalletsUseCase(walletRepo), RefreshWalletsUseCase(walletRepo), ObserveWalletOfUseCase(walletRepo), RefreshWalletOfUseCase(walletRepo),
        ObserveChatsUseCase(chats), ObserveChatUseCase(chats), ObserveGroupMoneyUseCase(chamas), RefreshGroupMoneyUseCase(chamas), CreateChamaRequestUseCase(chamas),
    )

    private fun vm(intent: PayIntent) = PayViewModel(
        intent, ObserveWalletUseCase(walletRepo), CheckRecipientUseCase(walletRepo), RemindRecipientUseCase(walletRepo), PayUseCase(walletRepo),
        ObserveContactsUseCase(contacts), connection, SendPaymentCardUseCase(messages), OpenDirectChatUseCase(chats),
        AttachChamaPaymentUseCase(chamas), ObserveChamaUseCase(chamas), groupWallets = GroupWalletsFlag.on(), wallets = sources(),
        observeTransfer = com.circaso.feature.wallet.domain.ObserveTransferUseCase(walletRepo),
    )

    private fun TestScope.keep(vm: PayViewModel) { backgroundScope.launch(dispatcher) { vm.state.collect { } } }

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(GwMonitor(), NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun aContributionGoesIntoTheGroupsOwnWallet() = runTest(dispatcher) {
        val vm = vm(PayIntent(chatId = "g1", displayName = "Kilimani Chama", amount = "1000", groupId = "g1", note = "November"))
        keep(vm)
        val s = vm.state.value
        assertTrue(s.toGroup); assertTrue(s.contribution)
        assertEquals("Kilimani Chama", s.payee?.displayName)
        assertEquals(0.665f, s.progressAfter!!, 0.001f, "where the target stands after this (GW9a2)")
        assertFalse(s.canSwitch, "a contribution leaves the personal wallet; nothing to switch")
        vm.onPay()
        val p = payments.single()
        assertEquals("g1", p.toGroupId); assertNull(p.toUserId); assertNull(p.fromWalletId)
        assertEquals("Added to Holiday Fund · November", p.note)
        assertEquals("g1", cards.single().first, "the card lands in the group")
        assertEquals(PayStep.Sending, vm.state.value.step)
        followed.value = transfer("t1", "7751937").copy(state = TransferState.Confirmed)
        assertEquals(PayStep.Done, vm.state.value.step)
    }

    @Test fun anApprovedPayoutLeavesTheGroupWalletNamedOnEveryScreen() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "achieng", chatId = "g1", displayName = "Achieng", amount = "12", goalName = "Holiday Fund", requestId = "r1", fromWalletId = "gw-1"))
        keep(vm)
        val s = vm.state.value
        assertTrue(s.groupPayout)
        assertEquals("Kilimani Chama", s.walletName)
        assertEquals(listOf("otieno", "wanjiru"), s.payoutRequest?.approvedBy)
        assertFalse(s.canSwitch, "the approval fixes the wallet")
        vm.onPay()
        val p = payments.single()
        assertEquals("gw-1", p.fromWalletId); assertEquals("r1", p.payoutRequestId); assertEquals("achieng", p.toUserId)
        assertEquals(SignSubject.group("gw-1", "Kilimani Chama"), p.subject)
        assertEquals("Paid from Kilimani Chama", p.note)
        assertTrue(attaches.isEmpty(), "W4's payoutRequestId links it: no CH6")
    }

    @Test fun aPayoutSaysPaidOnlyOnceTheChainHasIt() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "achieng", chatId = "g1", amount = "12", requestId = "r1", fromWalletId = "gw-1"))
        keep(vm)
        vm.onPay()
        assertEquals(PayStep.Sending, vm.state.value.step, "GW17: signed, not settled")
        followed.value = transfer("t1", "12000000").copy(state = TransferState.Confirmed)
        assertEquals(PayStep.Done, vm.state.value.step)
        assertTrue(vm.state.value.result!!.settled)
    }

    @Test fun aPayoutThatFailsOnChainIsNotSent() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "achieng", chatId = "g1", amount = "12", requestId = "r1", fromWalletId = "gw-1"))
        keep(vm)
        vm.onPay()
        followed.value = transfer("t1", "12000000").copy(state = TransferState.Failed)
        assertEquals(PayStep.Amount, vm.state.value.step)
        assertEquals("Kilimani Chama", vm.state.value.failure?.wallet)
    }

    @Test fun aPayoutThatDidntGoSaysNothingWasTakenAndKeepsTheApproval() = runTest(dispatcher) {
        payAnswer = { AppResult.Failure(AppError.Api("wallet.payout_in_progress", 409, "req_N2sX8c", null, emptyMap())) }
        val vm = vm(PayIntent(userId = "achieng", chatId = "g1", amount = "12", requestId = "r1", fromWalletId = "gw-1"))
        keep(vm)
        vm.onPay()
        val f = assertNotNull(vm.state.value.failure)
        assertEquals("Kilimani Chama", f.wallet)
        assertEquals("req_N2sX8c", f.reference)
        assertEquals(PayStep.Amount, vm.state.value.step)
    }

    @Test fun payingFromTheGroupWalletInSomeonesChatAsksTheAdmins() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "otieno", chatId = "dm-otieno", displayName = "Otieno", amount = "5000"))
        keep(vm)
        assertTrue(vm.state.value.canSwitch, "Personal and Kilimani Chama: GW9b's chip opens GW10")
        assertEquals(2, vm.state.value.choices.size)
        vm.onPayFrom(vm.state.value.choices.first { !it.personal })
        val s = vm.state.value
        assertTrue(s.requestPayout)
        vm.onPay()
        assertTrue(payments.isEmpty(), "a request moves no money")
        val args = asked.single()
        assertEquals("g1", args[0], "the request goes to the group whose wallet pays it")
        assertEquals("otieno", args[1])
        assertEquals(PayStep.Done, vm.state.value.step)
        assertNotNull(vm.state.value.requested)
    }

    @Test fun personalStaysTheDefault() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "otieno", chatId = "dm-otieno", amount = "500"))
        keep(vm)
        assertTrue(vm.state.value.payingFrom.personal)
        vm.onPay()
        assertNull(payments.single().fromWalletId)
        assertEquals(NamedWallet.Personal.subject, payments.single().subject, "with several wallets even Personal is named (GW11b)")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GroupWalletSetupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var connection: ConnectionState
    private val calls = mutableListOf<String>()
    private var startAnswer: AppResult<GroupWallet> = AppResult.Success(GroupWallet("g1", WalletStatus.SettingUp, listings[1].copy(status = WalletStatus.SettingUp)))
    private var policyAnswer: AppResult<ApprovalPolicy> = AppResult.Success(ApprovalPolicy.AnyAdmin)
    private val subjects = mutableListOf<SignSubject>()

    private val walletRepo = fake<WalletRepository> { name, args ->
        calls += name
        when (name) {
            "startGroupWallet" -> startAnswer
            "awaitGroupWallet" -> AppResult.Success(GroupWallet("g1", WalletStatus.Active, listings[1]))
            "enableBiometricApproval" -> { subjects += args[0] as SignSubject; AppResult.Success(Unit) }
            "addMissingChains" -> { subjects += args[0] as SignSubject; AppResult.Success(Unit) }
            else -> null
        }
    }
    private val chats = fake<ChatsRepository> { name, _ -> if (name == "chat") flowOf(group) else null }
    private val chamas = fake<ChamaRepository> { name, args ->
        calls += name
        when (name) {
            "setApprovalPolicy" -> policyAnswer
            "setGoal" -> AppResult.Success(ChamaGoal("g1", args[1] as String, args[2] as String, null, "me", 1))
            "refreshMoney" -> AppResult.Success(money())
            else -> null
        }
    }
    private lateinit var prefs: AppPreferences

    private fun vm(plan: GroupWalletPlan, bio: String? = "Fingerprint") = GroupWalletSetupViewModel(
        "g1", plan, ObserveChatUseCase(chats), StartGroupWalletUseCase(walletRepo), AwaitGroupWalletUseCase(walletRepo), EnableWalletBiometricsUseCase(walletRepo),
        AddWalletNetworksUseCase(walletRepo), SetApprovalPolicyUseCase(chamas), SetGoalUseCase(chamas), RefreshGroupMoneyUseCase(chamas), prefs, connection, bio,
    )

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); prefs = AppPreferences(GwMemoryStore()); connection = ConnectionState(GwMonitor(), NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun pinThenWalletThenBiometricsThenNetworksThenTheRules() = runTest(dispatcher) {
        val vm = vm(GroupWalletPlan(ApprovalPolicy.AnyAdmin, targetMinor = "387596899", targetName = "Kilimani Chama"))
        vm.onStart()
        assertEquals(SetupStep.Biometrics, vm.state.value.step, "GW4 waits for the owner")
        assertEquals(listOf("startGroupWallet", "awaitGroupWallet"), calls)
        vm.onTurnOnBiometrics()
        assertEquals(SetupStep.Done, vm.state.value.step)
        assertEquals(SignSubject.group("gw-1", "Kilimani Chama"), subjects.first(), "biometrics for this wallet only")
        assertTrue("setApprovalPolicy" in calls && "setGoal" in calls)
        assertTrue(prefs.walletBiometrics("gw-1").first())
    }

    @Test fun twoAdminsIsTheDefaultAndIsntSent() = runTest(dispatcher) {
        val vm = vm(GroupWalletPlan(ApprovalPolicy.TwoAdmins), bio = null)
        vm.onStart()
        assertEquals(SetupStep.Done, vm.state.value.step, "no biometrics on the phone: no GW4")
        assertFalse("setApprovalPolicy" in calls)
        assertFalse("setGoal" in calls)
    }

    @Test fun aPinTheOwnerBacksOutOfIsntAnError() = runTest(dispatcher) {
        startAnswer = AppResult.Failure(AppError.Api("wallet.cancelled", 400, null, null, emptyMap()))
        val vm = vm(GroupWalletPlan())
        vm.onStart()
        assertEquals(SetupProblem.Cancelled, vm.state.value.problem)
        assertNull(vm.state.value.message)
    }

    @Test fun aLostConnectionSaysSoAndAServerFailureShowsItsReference() = runTest(dispatcher) {
        startAnswer = AppResult.Failure(AppError.Network)
        val offline = vm(GroupWalletPlan())
        offline.onStart()
        assertEquals(SetupProblem.Offline, offline.state.value.problem)

        startAnswer = AppResult.Failure(AppError.Api("wallet.custody_unavailable", 503, "req_P3kq9X", null, emptyMap()))
        val failed = vm(GroupWalletPlan())
        failed.onStart()
        assertEquals(SetupProblem.Failed, failed.state.value.problem)
        assertEquals("req_P3kq9X", failed.state.value.reference)
    }

    @Test fun settingsThatDidntSaveAreRetriedWithoutAnotherPin() = runTest(dispatcher) {
        policyAnswer = AppResult.Failure(AppError.Api("service.unavailable", 503, "req_1", null, emptyMap()))
        val vm = vm(GroupWalletPlan(ApprovalPolicy.AnyAdmin), bio = null)
        vm.onStart()
        assertTrue(vm.state.value.rulesFailed)
        policyAnswer = AppResult.Success(ApprovalPolicy.AnyAdmin)
        calls.clear()
        vm.onTryAgain()
        assertEquals(SetupStep.Done, vm.state.value.step)
        assertFalse("startGroupWallet" in calls, "the PIN isn't asked again")
    }
}

class GroupInfoRulesTest {
    private fun info(balance: String, signer: Boolean) = ChatInfoUiState(
        chat = group,
        money = GroupInfoMoney(enabled = true, money = money().copy(isSigner = signer), balance = walletOf(balance, "gw-1")),
    )

    @Test fun aSignerCantLeaveWhileTheWalletHoldsMoney() {
        assertTrue(info("100000000", signer = true).leavingBlocked)
        assertFalse(info("0", signer = true).leavingBlocked, "an empty wallet can be left")
        assertFalse(info("100000000", signer = false).leavingBlocked, "only the signer locks it by leaving")
    }

    @Test fun aSignerWhoLeftLocksTheWallet() {
        val gone = group.copy(members = members.filter { it.userId != "me" })
        assertTrue(com.circaso.feature.chama.presentation.signerLeft(gone, money()))
        assertFalse(com.circaso.feature.chama.presentation.signerLeft(group, money()))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TargetViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val saved = mutableListOf<List<Any?>>()
    private val walletRepo = fake<WalletRepository> { name, _ -> if (name == "getWallet") flowOf(personal) else null }
    private val chats = fake<ChatsRepository> { name, _ -> if (name == "chat") flowOf(group) else null }
    private val chamas = fake<ChamaRepository> { name, args ->
        when (name) {
            "money" -> flowOf(money().copy(goal = money().goal!!.copy(targetMinor = "387596899", deadline = "2026-12-14T00:00:00Z")))
            "refreshMoney" -> AppResult.Success(money())
            "setGoal" -> { saved.add(args); AppResult.Success(money().goal!!) }
            else -> null
        }
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun changingATargetKeepsItsAmountInTheShownCurrencyAndItsDate() = runTest(dispatcher) {
        val vm = com.circaso.feature.chama.presentation.TargetViewModel(
            "g1", false, ObserveChatUseCase(chats), ObserveGroupMoneyUseCase(chamas), SetGoalUseCase(chamas), com.circaso.feature.chama.domain.RemoveGoalUseCase(chamas),
            ObserveWalletUseCase(walletRepo), ConnectionState(GwMonitor(), NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0), RefreshGroupMoneyUseCase(chamas),
        )
        assertEquals("50000", vm.state.value.amountText, "387.596899 USDC at 129 shown in KES (rounded), not as USDC")
        vm.onSave()
        assertEquals("2026-12-14T00:00:00Z", saved.single()[3], "the saved date stays unless another is picked")
    }
}
