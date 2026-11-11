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
import com.circaso.feature.wallet.presentation.cardNoteTyped
import com.circaso.feature.wallet.presentation.isGroupPayoutNote
import com.circaso.feature.wallet.presentation.payoutNote
import com.circaso.feature.wallet.presentation.payoutHeadAndRest
import com.circaso.feature.messaging.presentation.groupPayoutCard
import com.circaso.feature.wallet.presentation.pickableMembers
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import androidx.lifecycle.viewModelScope
import com.circaso.feature.wallet.presentation.PayUiState
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
    private val payKeys = mutableListOf<String>()
    private val asked = mutableListOf<List<Any?>>()
    private val attaches = mutableListOf<Any?>()
    private var memberOnly = false
    private var payAnswer: (PaymentRequest) -> AppResult<Transfer> = { r -> AppResult.Success(transfer("t${payments.size}", r.amountMinor)) }

    private val walletRepo = fake<WalletRepository> { name, args ->
        when (name) {
            "getWallet" -> flowOf(personal)
            "getWallets" -> flowOf(if (memberOnly) listings.map { it.copy(canSign = it.personal) } else listings)
            "walletOf" -> flowOf(if (args[0] == "gw-1") groupBalance else personal)
            "refreshWallets" -> AppResult.Success(listings)
            "validateAddress" -> AppResult.Success((args[1] as String).lowercase())
            "refreshWallet" -> AppResult.Success(groupBalance)
            "recipient" -> AppResult.Success(Recipient(args[0] as String, RecipientStatus.CanReceive, listOf("BASE-SEPOLIA")))
            "pay" -> { val r = args[0] as PaymentRequest; payments += r; payKeys += args[1] as String; payAnswer(r) }
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

    private val me = com.circaso.feature.contacts.domain.Person("me", "Pasaka Mutuku", "pasaka", null, null)
    private val opened = mutableListOf<String>()

    private fun vm(intent: PayIntent, approvals: Boolean = false) = PayViewModel(
        intent, ObserveWalletUseCase(walletRepo), CheckRecipientUseCase(walletRepo), RemindRecipientUseCase(walletRepo), PayUseCase(walletRepo),
        ObserveContactsUseCase(contacts), connection, SendPaymentCardUseCase(messages), OpenDirectChatUseCase(chats),
        AttachChamaPaymentUseCase(chamas), ObserveChamaUseCase(chamas), groupWallets = GroupWalletsFlag.on(), wallets = sources(),
        observeTransfer = com.circaso.feature.wallet.domain.ObserveTransferUseCase(walletRepo),
        observeMe = flowOf(me), approvals = approvals,
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

    @Test fun withApprovalsOnPayingFromTheGroupWalletInSomeonesChatAsksTheAdmins() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "otieno", chatId = "dm-otieno", displayName = "Otieno", amount = "5000"), approvals = true)
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

    // ---- Money in the chat (Pasaka, 2026-11-04): the chat's money card is the review; Pay goes straight to the PIN.

    @Test fun inTheChatAContributionIsDoneOnceSignedAndTheCardFollowsIt() = runTest(dispatcher) {
        val vm = vm(PayIntent(chatId = "g1", displayName = "Kilimani Chama", groupId = "g1", inChat = true))
        keep(vm)
        vm.onAmount("1000"); vm.onNote("November")
        val s = vm.state.value
        assertEquals("No fee", s.feeText, "§7b's one fee: none until 2F quotes it")
        assertEquals(s.fiatText, s.totalText, "with no fee the total is the amount")
        assertTrue(s.canPay)
        vm.onPay()
        assertEquals("Added to Holiday Fund · November", payments.single().note)
        assertEquals("g1", cards.single().first, "the card lands in the group, where it shows Sending until confirmed")
        assertEquals(PayStep.Done, vm.state.value.step, "no GW17 wait in the chat: the card carries it")
        vm.onQuickReset()
        assertEquals(PayStep.Amount, vm.state.value.step)
        assertEquals("", vm.state.value.amountText, "ready for the next #")
    }

    @Test fun aRefusalBeforeTheChainStaysOnTheChatCardWithItsReferenceAndTryAgainSigns() = runTest(dispatcher) {
        payAnswer = { AppResult.Failure(AppError.Api("wallet.sdk_failed", 400, "req_N2sX8c", null, emptyMap())) }
        val vm = vm(PayIntent(userId = "otieno", chatId = "dm-otieno", displayName = "Otieno", inChat = true))
        keep(vm)
        vm.onAmount("500")
        vm.onPay()
        val f = assertNotNull(vm.state.value.failure)
        assertEquals("your wallet", f.wallet)
        assertEquals("req_N2sX8c", f.reference)
        assertTrue(cards.isEmpty(), "nothing went, so no card")
        assertEquals(PayStep.Amount, vm.state.value.step)
        assertTrue(vm.state.value.canPay, "Try Again is the same Pay")
        payAnswer = { r -> AppResult.Success(transfer("t${payments.size}", r.amountMinor)) }
        vm.onPay()
        assertEquals(2, payments.size)
        assertEquals(PayStep.Done, vm.state.value.step)
        assertNull(vm.state.value.failure)
        assertEquals("dm-otieno", cards.single().first)
    }

    @Test fun offlineTheChatCardCantPayBeforeTheTap() = runTest(dispatcher) {
        val monitor = GwMonitor().also { it.osStatus.value = ConnectionStatus.Offline }
        connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
        val vm = vm(PayIntent(userId = "otieno", chatId = "dm-otieno", inChat = true))
        keep(vm)
        vm.onAmount("500")
        assertFalse(vm.state.value.online)
        assertFalse(vm.state.value.canPay)
        vm.onPay()
        assertTrue(payments.isEmpty(), "money never queues")
    }

    @Test fun payingFromInTheChatGoesBackToPersonalForTheNextPayment() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "otieno", chatId = "dm-otieno", inChat = true))
        keep(vm)
        vm.onAmount("500")
        vm.onChoosing(true)
        assertTrue(vm.state.value.choosing, "GW10 opens over the chat")
        vm.onPayFrom(vm.state.value.choices.first { !it.personal })
        assertTrue(vm.state.value.directPayout)
        vm.onQuickReset()
        assertTrue(vm.state.value.payingFrom.personal)
    }

    @Test fun aPayoutCardFromTheChatKeepsTheApprovedAmountAfterAReset() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "achieng", chatId = "g1", amount = "12", requestId = "r1", fromWalletId = "gw-1", inChat = true))
        keep(vm)
        vm.onPay()
        assertEquals(PayStep.Done, vm.state.value.step, "signed: the card in the chat follows it")
        assertEquals("Paid from Kilimani Chama", cards.single().second.note)
        vm.onQuickReset()
        assertEquals("12000000", vm.state.value.amountMinor, "the approval's amount, not an empty card")
        assertEquals("gw-1", vm.state.value.payingFrom.walletId)
    }

    @Test fun aQuotedFeeIsAddedOnTopAndPayWaitsWhileItsWorkedOut() {
        val base = PayUiState(
            payee = com.circaso.feature.contacts.domain.Person("6", "Mum", null, null, null), wallet = personal, blockchain = "BASE-SEPOLIA", amountText = "500",
        )
        assertEquals("KES 500", base.fiatText, "as typed, not 499.99 back from USDC")
        assertEquals("KES 500", base.totalText)
        val quoted = base.copy(fee = com.circaso.feature.wallet.presentation.PayFee.Quoted("19380", 50))
        assertEquals(com.circaso.feature.wallet.domain.MinorUnits.add(base.amountMinor!!, "19380"), quoted.totalMinor, "the sender pays the fee on top")
        assertTrue(quoted.feeText.endsWith("· network + 0.5%"))
        assertTrue(base.canPay)
        assertFalse(base.copy(fee = com.circaso.feature.wallet.presentation.PayFee.Quoting).canPay, "GW13: Send waits for the quote")
        assertEquals("Working it out", base.copy(fee = com.circaso.feature.wallet.presentation.PayFee.Quoting).feeText)
    }

    @Test fun tryAgainOnACardKeepsOnlyWhatThePersonTyped() {
        assertEquals("November", com.circaso.feature.wallet.presentation.cardNoteTyped("Added to Holiday Fund · November"))
        assertEquals("", com.circaso.feature.wallet.presentation.cardNoteTyped("Added to Holiday Fund"))
        assertEquals("for lunch", com.circaso.feature.wallet.presentation.cardNoteTyped("for lunch"))
        assertEquals("", com.circaso.feature.wallet.presentation.cardNoteTyped(null))
    }

    // ---- 2026-11-06: the group wallet's owner pays directly, and every payment out shows in the group's chat.

    @Test fun theOwnerPaysAMemberStraightFromTheGroupWalletAndTheGroupSeesTheCard() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "achieng", displayName = "Achieng Odhiambo", amount = "12", fromWalletId = "gw-1"))
        keep(vm)
        vm.onToggleUnit()
        vm.onAmount("12"); vm.onNote("bus fare")
        val s = vm.state.value
        assertTrue(s.directPayout); assertFalse(s.requestPayout); assertFalse(s.groupPayout)
        assertTrue(s.canPay)
        vm.onPay()
        val p = payments.single()
        assertEquals("gw-1", p.fromWalletId); assertNull(p.payoutRequestId); assertEquals("achieng", p.toUserId)
        assertEquals("g1", p.chatId, "a group wallet's payment belongs to its chat")
        assertEquals(SignSubject.group("gw-1", "Kilimani Chama"), p.subject)
        assertEquals("Paid from Kilimani Chama to Achieng Odhiambo · bus fare", p.note)
        assertEquals(listOf("g1"), cards.map { it.first }, "a member sees it in the group; no second card")
        assertTrue(asked.isEmpty(), "no request, no approvals")
        assertEquals(PayStep.Sending, vm.state.value.step, "GW17: Paid only once the chain has it")
        followed.value = transfer("t1", "12000000").copy(state = TransferState.Confirmed)
        assertEquals(PayStep.Done, vm.state.value.step)
    }

    @Test fun theOwnerPaysThemselvesFromTheGroupWallet() = runTest(dispatcher) {
        val vm = vm(PayIntent(fromWalletId = "gw-1"))
        keep(vm)
        assertEquals(PayStep.Pick, vm.state.value.step)
        assertEquals("me", vm.state.value.me?.userId, "You is offered first")
        assertTrue(pickableMembers(vm.state.value).none { it.userId == "me" })
        assertEquals(listOf("Achieng Odhiambo", "Otieno Ouma", "Wanjiru Kamau"), pickableMembers(vm.state.value).map { it.displayName })
        vm.onPick(vm.state.value.me!!)
        assertTrue(vm.state.value.toSelf)
        assertNull(vm.state.value.problem, "no W9 check for your own wallet")
        vm.onToggleUnit(); vm.onAmount("20")
        vm.onPay()
        val p = payments.single()
        assertEquals("me", p.toUserId); assertEquals("gw-1", p.fromWalletId)
        assertEquals("Paid from Kilimani Chama to Pasaka Mutuku", p.note)
        assertEquals(listOf("g1"), cards.map { it.first })
    }

    @Test fun payingSomeoneOutsideTheGroupPutsTheCardInTheGroupAndInTheirChat() = runTest(dispatcher) {
        val vm = vm(PayIntent(userId = "mum", chatId = "dm-mum", displayName = "Mum", amount = "500"))
        keep(vm)
        vm.onPayFrom(vm.state.value.choices.first { !it.personal })
        assertTrue(vm.state.value.directPayout)
        vm.onPay()
        assertEquals("g1", payments.single().chatId)
        assertEquals(listOf("g1", "dm-mum"), cards.map { it.first }, "the group sees where its money went; Mum sees it with the owner")
        assertEquals("Paid from Kilimani Chama to Mum", cards[0].second.note)
        assertEquals("From Kilimani Chama", cards[1].second.note, "Mum's own chat says where it came from")
    }

    private fun sendVm() = com.circaso.feature.wallet.presentation.SendToWalletViewModel(
        ObserveWalletUseCase(walletRepo), com.circaso.feature.wallet.domain.ValidateAddressUseCase(walletRepo), PayUseCase(walletRepo), connection,
        fromWalletId = "gw-1", wallets = sources(), sendCard = SendPaymentCardUseCase(messages),
    )

    @Test fun theOwnerWithdrawsFromTheGroupWalletToAnOutsideAddressAndTheGroupSeesIt() = runTest(dispatcher) {
        val vm = sendVm()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        val s0 = vm.state.value
        assertTrue(s0.fromGroup); assertFalse(s0.notOwner); assertEquals("Kilimani Chama", s0.walletName)
        vm.onAddress("0xAB12000000000000000000000000000000004AB4"); vm.onAmount("15"); vm.onNote("to Binance")
        vm.onReview()
        assertEquals(com.circaso.feature.wallet.presentation.ExternalStep.Review, vm.state.value.step)
        vm.onConfirm()
        val p = payments.single()
        assertEquals("gw-1", p.fromWalletId); assertNull(p.toUserId); assertNull(p.payoutRequestId)
        assertEquals("0xab12000000000000000000000000000000004ab4", p.toAddress)
        assertEquals("g1", p.chatId)
        assertEquals(SignSubject.group("gw-1", "Kilimani Chama"), p.subject)
        val (chat, card) = cards.single()
        assertEquals("g1", chat)
        assertTrue(card.note!!.startsWith("Sent from Kilimani Chama to 0x"), card.note)
        assertTrue(card.note!!.endsWith(" · to Binance"), card.note)
        assertEquals(com.circaso.feature.wallet.presentation.ExternalStep.Done, vm.state.value.step)
    }

    @Test fun aWithdrawalThatFailedOnTheServerRetriesWithTheSameKey() = runTest(dispatcher) {
        val keys = mutableListOf<String>()
        var first = true
        payAnswer = { r -> if (first) { first = false; AppResult.Failure(AppError.Api("service.unavailable", 503, "req_N2sX8c", null, emptyMap())) } else AppResult.Success(transfer("t1", r.amountMinor)) }
        val vm = sendVm()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.onAddress("0xab12000000000000000000000000000000004ab4"); vm.onAmount("15")
        vm.onReview(); vm.onConfirm()
        assertNotNull(vm.state.value.failure, "Not sent, with what went wrong")
        vm.onConfirm()
        assertEquals(com.circaso.feature.wallet.presentation.ExternalStep.Done, vm.state.value.step)
        assertEquals(2, payments.size)
        assertEquals(1, payKeys.distinct().size, "a 503 may have gone through: Try Again must not send it twice")
    }

    @Test fun aMemberCannotSendFromTheGroupWallet() = runTest(dispatcher) {
        memberOnly = true
        val vm = sendVm()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        assertTrue(vm.state.value.notOwner)
        vm.onAddress("0xab12000000000000000000000000000000004ab4"); vm.onAmount("15")
        assertFalse(vm.state.value.canReview)
        vm.onReview(); vm.onConfirm()
        assertTrue(payments.isEmpty()); assertTrue(cards.isEmpty())
    }

    @Test fun aGroupPayoutCardNoteSaysWhereTheMoneyWent() {
        assertEquals("Sent from Diani vacation to 0x1234…34cdef · rent", payoutNote("Diani vacation", null, "0x1234567890abcdef1234567890abcdef1234cdef", "rent"))
        assertEquals("Paid from Diani vacation to Pasaka", payoutNote("Diani vacation", "Pasaka", null, null))
        assertEquals("rent", cardNoteTyped("Sent from Diani vacation to 0x1234…cdef · rent"))
        assertTrue(isGroupPayoutNote("Sent from Diani vacation to 0x1234…cdef"))
        assertTrue(isGroupPayoutNote("Paid from Diani vacation to Pasaka"))
        assertFalse(isGroupPayoutNote("Added to Diani vacation"))
    }

    @Test fun onlyTheSignersCardReadsAsMoneyOutOfTheGroupWallet() {
        fun card(sender: String, note: String) = com.circaso.feature.messaging.domain.Message.Payment(
            1, "g1", 0, null, "m1", sender, sender, false, "t1", "1000000", "USDC", "BASE-SEPOLIA", note,
            com.circaso.feature.messaging.domain.DeliveryState.Delivered, null,
        )
        assertTrue(groupPayoutCard(card("me", "Paid from Holiday Fund to Otieno"), money()), "the signer's card")
        assertFalse(groupPayoutCard(card("otieno", "Paid from Holiday Fund to Pasaka"), money()), "a member typing the words gets an ordinary card")
        assertFalse(groupPayoutCard(card("me", "Paid from Holiday Fund to Otieno"), null), "no group wallet, no payout card")
        assertFalse(groupPayoutCard(card("me", "lunch"), money()))
    }

    @Test fun gw18TheChatCardSaysFromWhichWalletOverTheAmountAndToWhomUnderIt() {
        val split = ::payoutHeadAndRest
        assertEquals("Paid from Diani vacation" to "to Pasaka · Bus fare", split("Paid from Diani vacation to Pasaka · Bus fare", "Diani vacation"))
        assertEquals("Sent from Diani vacation" to "to 0x1234…cdef", split("Sent from Diani vacation to 0x1234…cdef", "Diani vacation"))
        assertEquals("Paid from Road to Lamu" to "to Mum", split("Paid from Road to Lamu to Mum", "Road to Lamu"), "a group name with \" to \" in it")
        assertEquals("Paid from Kilimani Chama" to "bus", split("Paid from Kilimani Chama · bus", "Kilimani Chama"), "an approved request's older note")
        assertEquals("Paid from Kilimani Chama" to null, split("Paid from Kilimani Chama", "Kilimani Chama"))
        assertNull(split("Added to Diani vacation · fare", "Diani vacation"))
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

/** A group's money moves live (Pasaka, 2026-11-04): frames and my own confirmed payments reload the copies screens read. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMoneyLiveTest {
    private val calls = mutableListOf<String>()
    private val settled = kotlinx.coroutines.flow.MutableSharedFlow<Transfer>(extraBufferCapacity = 4)
    private val walletRepo = fake<WalletRepository> { name, args ->
        when (name) {
            "getSettled" -> settled
            "refreshWallet" -> { calls += "W1 ${args[0]}"; AppResult.Success(groupBalance) }
            "refreshActivity" -> { calls += "W6 ${args[0]}"; AppResult.Success(Unit) }
            else -> null
        }
    }
    private val chamas = fake<ChamaRepository> { name, args ->
        when (name) {
            "money" -> flowOf(money())
            "refreshMoney" -> { calls += "CH7 ${args[0]}"; AppResult.Success(money()) }
            else -> null
        }
    }

    private fun frame(vararg pairs: Pair<String, String>) = kotlinx.serialization.json.JsonObject(pairs.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) })

    @Test fun someoneElsesContributionReloadsTheGroupWallet() = runTest {
        val live = com.circaso.feature.chama.data.repository.GroupMoneyLive(walletRepo, chamas, backgroundScope, groupWallets = { true })
        live.onChamaFrame("money.updated", frame("chatId" to "g1", "kind" to "CONTRIBUTION"))
        assertEquals(listOf("W1 gw-1", "W6 gw-1"), calls, "balance and history behind CH7 (the repository reloads CH7 itself)")
        calls.clear()
        live.onChamaFrame("request.updated", frame("chatId" to "g1", "state" to "APPROVED"))
        assertTrue(calls.isEmpty(), "an approval moves no money")
        live.onChamaFrame("request.updated", frame("chatId" to "g1", "state" to "PAID"))
        assertEquals(listOf("W1 gw-1", "W6 gw-1"), calls, "a paid payout left the wallet")
    }

    @Test fun myOwnContributionReloadsTheGroupAtOnceAndAgainAMomentLater() = runTest {
        val live = com.circaso.feature.chama.data.repository.GroupMoneyLive(walletRepo, chamas, backgroundScope, groupWallets = { true }, againAfterMillis = 2_000)
        live.start()
        runCurrent()
        settled.emit(transfer("t1", "7751937").copy(state = TransferState.Confirmed, toGroupId = "g1"))
        runCurrent()
        assertEquals(listOf("CH7 g1", "W1 gw-1", "W6 gw-1"), calls, "no waiting for the group's frame")
        advanceTimeBy(2_100)
        assertEquals(6, calls.size, "once more, after the server has recorded it")
        calls.clear()
        settled.emit(transfer("t2", "1").copy(state = TransferState.Confirmed))
        runCurrent()
        assertTrue(calls.isEmpty(), "a payment to a person touches no group")
    }

    @Test fun nothingWithoutGroupWallets() = runTest {
        val live = com.circaso.feature.chama.data.repository.GroupMoneyLive(walletRepo, chamas, backgroundScope, groupWallets = { false })
        live.onChamaFrame("money.updated", frame("chatId" to "g1"))
        live.onSettled(transfer("t1", "1").copy(state = TransferState.Confirmed, toGroupId = "g1"))
        assertTrue(calls.isEmpty())
    }
}

/** The thread's money cards (Pasaka, 2026-11-04): Sending until confirmed, then Sent; a card on its way is checked with W5. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadPaymentStatesTest {
    private val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    private val states = MutableStateFlow(mapOf("t1" to TransferState.Submitted))
    private val checked = mutableListOf<String>()
    private val asked = mutableListOf<Collection<*>>()
    private val walletRepo = fake<WalletRepository> { name, args ->
        when (name) {
            "transfers" -> { asked += args[0] as Collection<*>; states.map { m -> m.map { (id, st) -> transfer(id, "1").copy(state = st) } } }
            "loadTransfer" -> { checked += args[0] as String; loadAnswer(args[0] as String) }
            else -> null
        }
    }
    private val card = com.circaso.feature.messaging.domain.Message.Payment(
        1, "dm", com.circaso.core.sync.nowMillis(), null, "m1", "me", "Me", true, "t1", "3870000", "USDC", "BASE-SEPOLIA", "lunch",
        com.circaso.feature.messaging.domain.DeliveryState.Delivered, null,
    )
    private val messagesRepo = fake<MessagesRepository> { name, _ ->
        when (name) {
            "thread" -> flowOf(listOf(card))
            "typing" -> flowOf(emptyList<String>())
            else -> Unit
        }
    }
    private val chats = fake<ChatsRepository> { name, _ -> if (name == "chat") flowOf(null) else null }

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private var loadAnswer: (String) -> AppResult<Transfer> = { AppResult.Success(transfer(it, "1")) }

    private fun TestScope.thread(): com.circaso.feature.messaging.presentation.ThreadViewModel {
        val connection = ConnectionState(GwMonitor(), NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
        val vm = com.circaso.feature.messaging.presentation.ThreadViewModel(
            "dm", com.circaso.feature.messaging.domain.ObserveChatUseCase(chats), com.circaso.feature.messaging.domain.ObserveThreadUseCase(messagesRepo),
            com.circaso.feature.messaging.domain.ObserveTypingUseCase(messagesRepo),
            com.circaso.feature.messaging.domain.SendMessageUseCase(messagesRepo), com.circaso.feature.messaging.domain.RetryMessageUseCase(messagesRepo),
            com.circaso.feature.messaging.domain.DiscardMessageUseCase(messagesRepo), com.circaso.feature.messaging.domain.DeleteForEveryoneUseCase(messagesRepo),
            com.circaso.feature.messaging.domain.MarkReadUseCase(messagesRepo), com.circaso.feature.messaging.domain.SetTypingUseCase(messagesRepo),
            connection, com.circaso.core.platform.NoChatNotifications,
            payments = com.circaso.feature.messaging.presentation.ThreadPayments(walletRepo), checkEveryMillis = 1_000,
        )
        // On screen: the chat is being looked at (the W5 checks pause when nothing collects it).
        backgroundScope.launch { vm.state.collect { } }
        return vm
    }

    @Test fun aCardSaysSendingUntilTheChainConfirmsAndIsCheckedWhileOnItsWay() = runTest(dispatcher) {
        val vm = thread()
        // The VM checks W5 on a timer: always stop it, or a failed assertion leaves runTest skipping time forever.
        try {
            runCurrent()
            assertEquals(TransferState.Submitted, vm.state.value.paymentStates["t1"], "Sending: signed, not yet on chain")
            assertTrue(vm.state.value.paymentPending(card))
            assertEquals(setOf("t1"), asked.last().toSet())
            assertEquals(listOf("t1"), checked, "read at once, in case a frame was missed")
            advanceTimeBy(1_100)
            assertEquals(listOf("t1", "t1"), checked, "and again while it is on its way")
            states.value = mapOf("t1" to TransferState.Confirmed)
            runCurrent()
            assertEquals(TransferState.Confirmed, vm.state.value.paymentStates["t1"], "Sent only now")
            assertFalse(vm.state.value.paymentPending(card))
            advanceTimeBy(3_000)
            assertEquals(2, checked.size, "a confirmed card isn't checked again")
        } finally { vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun swipingAMessageRepliesToItAndTheBarCanBeCancelled() = runTest(dispatcher) {
        val vm = thread()
        try {
            vm.onReply(card)
            val target = vm.state.value.replyingTo
            assertEquals("m1", target?.messageId)
            assertEquals("You", target?.senderName, "my own message")
            assertEquals("Payment · lunch", target?.preview)
            vm.onCancelReply()
            assertEquals(null, vm.state.value.replyingTo)
            // A message the server hasn't got yet can't be quoted.
            vm.onReply(card.copy(messageId = null))
            assertEquals(null, vm.state.value.replyingTo)
        } finally { vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun aNewCardThisPhoneHasntReadIsNeverShownAsArrived() = runTest(dispatcher) {
        states.value = emptyMap()
        loadAnswer = { AppResult.Failure(AppError.Api("wallet.transfer_not_found", 404, null, null, emptyMap())) }
        val vm = thread()
        // The VM checks W5 on a timer: always stop it, or a failed assertion leaves runTest skipping time forever.
        try {
            assertTrue(com.circaso.feature.messaging.presentation.ThreadUiState(paymentStates = emptyMap()).paymentPending(card), "no state yet: on its way, not Received")
            assertFalse(com.circaso.feature.messaging.presentation.ThreadUiState().paymentPending(card.copy(orderAt = 0)), "an old card with no state reads as it always did")
            runCurrent()
            assertEquals(listOf("t1"), checked)
            assertTrue("t1" in vm.state.value.paymentsUnreadable, "the server won't show it: stop asking")
            assertFalse(vm.state.value.paymentPending(card), "and don't leave it on its way")
            advanceTimeBy(3_000)
            assertEquals(1, checked.size)
        } finally { vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
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
