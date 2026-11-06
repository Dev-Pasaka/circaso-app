package com.circaso.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.chama.domain.Approval
import com.circaso.feature.chama.domain.ApprovalPolicy
import com.circaso.feature.chama.domain.ChamaGoal
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.GroupMoney
import com.circaso.feature.chama.domain.GroupWalletRef
import com.circaso.feature.chama.domain.RequestState
import com.circaso.feature.chama.presentation.GroupInfoMoney
import com.circaso.feature.chama.presentation.GroupMoneyActions
import com.circaso.feature.chama.presentation.GroupMoneyRows
import com.circaso.feature.chama.presentation.GroupWalletActions
import com.circaso.feature.chama.presentation.GroupWalletHooks
import com.circaso.feature.chama.presentation.GroupWalletSetupContent
import com.circaso.feature.chama.presentation.GroupWalletSetupState
import com.circaso.feature.chama.presentation.GroupWalletUiState
import com.circaso.feature.chama.presentation.LeaveBlockedContent
import com.circaso.feature.chama.presentation.SetupProblem
import com.circaso.feature.chama.presentation.SetupStep
import com.circaso.feature.chama.presentation.TargetContent
import com.circaso.feature.chama.presentation.TargetNeedsWalletContent
import com.circaso.feature.chama.presentation.TargetUiState
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.presentation.NewGroupContent
import com.circaso.feature.messaging.presentation.NewGroupUiState
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.AssetTotal
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletOwner
import com.circaso.feature.wallet.domain.WalletOwnerType
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.presentation.NamedWallet
import com.circaso.feature.wallet.presentation.PayContent
import com.circaso.feature.wallet.presentation.PayFailure
import com.circaso.feature.wallet.presentation.PayFee
import com.circaso.feature.wallet.presentation.PayUiState
import com.circaso.feature.wallet.presentation.PayStep
import com.circaso.feature.wallet.presentation.WalletActions
import com.circaso.feature.wallet.presentation.WalletContent
import com.circaso.feature.wallet.presentation.WalletTabContent
import com.circaso.feature.wallet.presentation.WalletUiState
import com.circaso.feature.wallet.presentation.WalletMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.circaso.feature.wallet.presentation.GroupMenuActions
import com.circaso.feature.wallet.presentation.AllGroupWalletsContent

// Debug previews for group wallets (Spaces phase 2, frames GW1–GW19): `-preview=gw-…` on iOS, `--es preview gw-…` on Android.

private fun gwMember(id: String, name: String, role: MemberRole = MemberRole.Member) = ChatMember(id, name, null, null, role, "2026-10-01T00:00:00Z")

private val kilimani = Chat(
    "g1", ChatKind.Group, "Kilimani Chama", null,
    listOf(gwMember("me", "Pasaka Mutuku", MemberRole.Owner), gwMember("2", "Otieno Ouma", MemberRole.Admin), gwMember("3", "Wanjiru Kamau", MemberRole.Admin), gwMember("4", "Achieng Odhiambo")),
    false, 3, 1, MemberRole.Owner, null,
)

private val gwFiat = FiatValue("KES", "32400", "129.20", "2026-11-03T10:00:00Z", false)

private fun gwWallet(minor: String = "250770000", walletId: String? = "gw-1") = Wallet(
    status = WalletStatus.Active,
    chains = listOf(WalletChain("BASE-SEPOLIA", "0x51c0000000000000000000000000000000c4a1", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, minor, "", null)), true)),
    totals = listOf(AssetTotal("USDC", 6, minor, "")), fiat = gwFiat, holdUntil = null, fetchedAt = nowMillis() - 120_000,
    walletId = walletId, owner = walletId?.let { WalletOwner(WalletOwnerType.Group, null, "g1", "me") },
)

private fun gwRequest(state: RequestState = RequestState.Approved) = ChamaRequest(
    "r1", "g1", "2", null, "0x1234567890abcdef1234567890abcdef12345678", "Coast Bus", "92879000", "the bus deposit", state, 2,
    listOf(Approval("2", true, "2026-11-03T08:00:00Z"), Approval("3", true, "2026-11-03T09:00:00Z")), null, "2026-11-05T08:00:00Z", "2026-11-03T08:00:00Z", "2026-11-03T09:00:00Z",
)

private fun gwMoney(wallet: Boolean = true, active: Boolean = true, goal: Boolean = true, signer: Boolean = true) = GroupMoney(
    chatId = "g1", wallet = if (wallet) GroupWalletRef("gw-1", active, if (signer) "me" else "2") else null, approvalPolicy = ApprovalPolicy.TwoAdmins,
    goal = if (goal) ChamaGoal("g1", "December trip", "386996905", "2026-12-14T00:00:00Z", "me", 1) else null,
    memberCount = 8, contributedMinor = "250770000", myContributedMinor = "30960000", contributors = emptyList(), paidOutMinor = "0",
    openRequests = listOf(gwRequest()), isAdmin = true, isOwner = true, isSigner = signer, fetchedAt = nowMillis(),
)

private val noGroupMoneyActions = GroupMoneyActions({}, {}, {}, {}, {}, {}, {}, {})

/** Group info's money rows on their own, as the frames draw them (GW6–GW8b), with an optional sheet over them. */
@Composable
private fun InfoPreview(info: GroupInfoMoney, online: Boolean = true, chat: Chat = kilimani, sheet: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null) {
    val c = CircasoTheme.colors
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar("Group Info", onBack = {}) },
        banner = { if (!online) com.circaso.designsystem.chrome.Banner("You’re offline · Showing the group as of 10:42", com.circaso.designsystem.components.CircasoIcon.Offline) },
        overlay = { if (sheet != null) CircasoSheet(true, {}, label = "Sheet", content = sheet) },
    ) { padding ->
        Column(Modifier.padding(horizontal = CircasoSpacing.s4).padding(top = CircasoSpacing.s6, bottom = padding.calculateBottomPadding())) {
            Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)) {
                GroupMoneyRows(info, chat, "me", online, busy = false, noGroupMoneyActions)
            }
        }
    }
}

private fun gwContacts() = listOf("2" to "Otieno Ouma", "3" to "Wanjiru Kamau", "4" to "Achieng Odhiambo", "5" to "Juma Kariuki").map { (id, n) -> Contact(Person(id, n, null, null, null), "phone", "") }

private fun newGroup(walletOn: Boolean, online: Boolean = true, failed: Boolean = false) = NewGroupUiState(step = com.circaso.feature.messaging.presentation.NewGroupStep.Details, loaded = true,
    name = "Kilimani Chama", contacts = gwContacts(), selected = setOf("2", "3", "4"), online = online, walletsAvailable = true, walletOn = walletOn,
    targetText = if (walletOn) "50000" else "", fiat = gwFiat, failed = failed, reference = if (failed) "req_G4mx2Q" else null,
)

private fun payState(base: PayUiState) = base.copy(
    wallet = gwWallet(walletId = null, minor = "141180000"), groupWallets = true, online = true, blockchain = "BASE-SEPOLIA",
    choices = listOf(NamedWallet.Personal, NamedWallet("gw-1", "g1", "Kilimani Chama", null, WalletOwnerType.Group, true, WalletStatus.Active, "me")),
    balances = mapOf(null to gwWallet("141180000", null), "gw-1" to gwWallet()), memberNames = kilimani.members.associate { it.userId to it.displayName },
)

/** [base] paying in ETH (priced at [price] dollars, or unpriced) with a fee quoted in ETH. */
private fun ethPay(base: PayUiState, price: String? = "3500"): PayUiState {
    val w = base.wallet ?: return base
    val eth = Asset("ETH", "", 18, "120000000000000000", "0.12", null, price)
    val chains = w.chains.mapIndexed { i, c -> if (i == 0) c.copy(assets = c.assets + eth) else c }
    return base.copy(wallet = w.copy(chains = chains, fetchedAt = nowMillis()), assetTag = "eth", fee = PayFee.Quoted("10000000000000", 50))
}

private val kilimaniWallet = NamedWallet("gw-1", "g1", "Kilimani Chama", null, WalletOwnerType.Group, true, WalletStatus.Active, "me")

@Composable
private fun PayPreview(state: PayUiState) = PayContent(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, null)

private val noActions = WalletActions({}, {}, {}, {}, {})

private fun memberState(s: GroupWalletUiState) = s.copy(money = gwMoney(signer = false).copy(isAdmin = false, isOwner = false), chat = kilimani.copy(myRole = MemberRole.Member))

/** Otieno signs for the wallet and has left the group (GW8b). */
private fun lockedState(s: GroupWalletUiState) = s.copy(money = gwMoney(signer = false), chat = kilimani.copy(members = kilimani.members.filter { it.userId != "2" }))

private fun personalWallet() = gwWallet("141180000", null).copy(
    chains = gwWallet("141180000", null).chains + WalletChain("MATIC-AMOY", "0x7a3000000000000000000000000000000000b9e2", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, "25000000", "", null)), true),
)

/** The tab's state with Personal and Kilimani Chama (and two more groups when [three]), Kilimani's wallet as [group]. */
private fun tabState(group: GroupWalletUiState, three: Boolean = false, member: Boolean = false, locked: Boolean = false): WalletUiState {
    val kili = kilimaniWallet.copy(canSign = !member, signerUserId = if (member || locked) "2" else "me")
    val more = if (three) listOf(
        NamedWallet("gw-2", "g2", "Westlands Book Club", null, WalletOwnerType.Group, false, WalletStatus.Active, "7"),
        NamedWallet("gw-3", "g3", "Mama Mboga Savings Group", null, WalletOwnerType.Group, true, WalletStatus.SettingUp, "me"),
    ) else emptyList()
    return WalletUiState(
        wallet = personalWallet(),
        recent = listOf(
            Transfer("p1", TransferDirection.In, TransferState.Confirmed, "BASE-SEPOLIA", "", null, "", Amount("USDC", "15500000", "15.50"), null, null, null, null, "Amina Wanjiku", "lunch", nowMillis() - 5_400_000, 0),
            Transfer("p2", TransferDirection.Out, TransferState.Confirmed, "BASE-SEPOLIA", "", null, "", Amount("USDC", "3870000", "3.87"), null, null, null, null, "Mum", null, nowMillis() - 90_000_000, 0),
        ),
        wallets = listOf(NamedWallet.Personal, kili) + more,
        group = group,
        balances = mapOf(null to personalWallet(), "gw-1" to gwWallet(), "gw-2" to gwWallet("38760000", "gw-2")),
        lockedWallets = if (locked) setOf("gw-1") else emptySet(),
        loading = false,
        groupsEnabled = true,
    )
}

/** Someone in many groups: more than the Groups menu lists, so it ends with "All Group Wallets…". */
private fun manyGroups(base: WalletUiState): WalletUiState {
    val names = listOf("Westlands Book Club", "Mama Mboga Savings Group", "Coast Trip 2026", "Office Lunch Kitty", "Ngong Road Runners", "Family Harambee", "Church Choir Welfare")
    val more = names.mapIndexed { i, n -> NamedWallet("gw-${i + 2}", "g${i + 2}", n, null, WalletOwnerType.Group, i % 2 == 0, WalletStatus.Active, if (i % 2 == 0) "me" else "7") }
    return base.copy(
        wallets = base.wallets.take(2) + more,
        balances = base.balances + more.mapIndexed { i, w -> w.walletId to gwWallet("${(i + 1) * 12_340_000}", w.walletId) }.toMap(),
    )
}

/** The Groups side of the Wallet tab (Pasaka, 2026-11-05): a group, its menu, many groups, the full list, none yet. */
@Composable
internal fun WalletGroupsPreview(name: String, gwState: GroupWalletUiState, gwActions: GroupWalletActions): Boolean {
    val groups = tabState(gwState, three = true).copy(mode = WalletMode.Groups, selectedWalletId = "gw-1", lastGroupWalletId = "gw-1")
    when (name) {
        "wallet-groups" -> TabPreview(groups, gwActions)
        "wallet-groups-menu" -> TabPreview(groups.copy(switching = true), gwActions)
        "wallet-groups-many" -> TabPreview(manyGroups(groups).copy(switching = true), gwActions)
        "wallet-groups-all" -> TabPreview(manyGroups(groups).copy(allGroups = true), gwActions)
        "wallet-groups-offline" -> TabPreview(groups.copy(online = false, group = gwState.copy(online = false)), gwActions)
        "wallet-groups-loading" -> TabPreview(groups.copy(group = GroupWalletUiState(chat = kilimani, money = gwMoney())), gwActions)
        "wallet-groups-empty" -> TabPreview(groups.copy(wallets = listOf(NamedWallet.Personal), selectedWalletId = null, lastGroupWalletId = null, group = null))
        else -> return false
    }
    return true
}

/** The Wallet tab in Home, as the person sees it, with the tab's state drawn without a view model. */
@Composable
private fun TabPreview(start: WalletUiState, gwActions: GroupWalletActions = GroupWalletActions({}, {}, {}, {}, {})) {
    // The menu and the full list open and close by touch on the phone, as in the app (the view model's own rules).
    var state by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(start) }
    com.circaso.feature.home.presentation.HomeScreen(
        initialTab = 1, // Wallet (tabs: Chats, Wallet, You since Spaces was removed)
        chats = {}, chatsActions = {}, chatsBanner = {},
        wallet = { pad ->
            // The tab draws the group only while one is picked, as the view model does.
            val shown = if (state.selectedGroupChat == null) state.copy(group = null) else state
            WalletTabContent(
                shown, pad, { noActions }, groupHooks = { _, _ -> GroupWalletHooks(gwActions, { _, _ -> }, {}) },
                onSwitching = { open -> state = state.copy(switching = open && state.selectedWalletId != null) },
                onSelectWallet = { w -> state = state.copy(selectedWalletId = w.walletId, switching = false, allGroups = false) },
                onAllGroups = { open -> state = state.copy(allGroups = open, switching = false) },
                onCreate = {}, onRefresh = {}, onReceive = {}, onComing = {},
                groupMenu = GroupMenuActions({}, {}),
            )
        },
        walletOverlay = {
            CircasoSheet(state.allGroups, { state = state.copy(allGroups = false) }, label = "Group Wallets") {
                AllGroupWalletsContent(state, onPick = { w -> state = state.copy(selectedWalletId = w.walletId, allGroups = false) }, onClose = { state = state.copy(allGroups = false) })
            }
        },
        walletActions = {},
        you = {},
    )
}

/** GroupWalletRoute without its view model. */
@Composable
private fun PushedWallet(state: GroupWalletUiState, gwActions: GroupWalletActions) {
    CircasoScaffold(topBar = { CircasoNavBar(state.name, onBack = {}) }) { p ->
        WalletContent(
            WalletUiState(group = state, online = state.online), p, noActions, onCreate = {}, onRefresh = {}, onReceive = {}, onComing = {},
            sheets = false, group = GroupWalletHooks(gwActions, { _, _ -> }, {}),
        )
    }
}

/** Draws a group-wallet preview; false when [name] isn't one. */
@Composable
internal fun GroupWalletPreview(name: String): Boolean {
    val setup = GroupWalletSetupState(groupName = "Kilimani Chama", biometricLabel = "Fingerprint", walletId = "gw-1")
    val gwState = GroupWalletUiState(chat = kilimani, money = gwMoney(), wallet = gwWallet(), loading = false, myUserId = "me", activity = listOf(
        Transfer("t1", TransferDirection.In, TransferState.Confirmed, "BASE-SEPOLIA", "", null, "", Amount("USDC", "7740000", "7.74"), null, "g1", null, null, "Wanjiru Kamau", null, nowMillis() - 86_400_000, 0, walletId = "gw-1"),
        Transfer("t2", TransferDirection.Out, TransferState.Confirmed, "BASE-SEPOLIA", "", null, "0x12", Amount("USDC", "92879000", "92.88"), null, "g1", null, null, "Coast Bus", null, nowMillis() - 3_600_000, 0, walletId = "gw-1"),
    ))
    val gwActions = GroupWalletActions({}, {}, {}, {}, {})
    if (WalletGroupsPreview(name, gwState, gwActions)) return true
    when (name) {
        "gw-new-group" -> NewGroupContent(newGroup(false), com.circaso.feature.messaging.presentation.NewGroupActions())
        "gw-new-group-wallet" -> NewGroupContent(newGroup(true), com.circaso.feature.messaging.presentation.NewGroupActions())
        "gw-new-group-offline" -> NewGroupContent(newGroup(true, online = false), com.circaso.feature.messaging.presentation.NewGroupActions())
        "gw-new-group-failed" -> NewGroupContent(newGroup(true, failed = true), com.circaso.feature.messaging.presentation.NewGroupActions())
        "gw-setup" -> GroupWalletSetupContent(setup.copy(step = SetupStep.Networks), {}, {}, {}, {})
        "gw-setup-bio" -> GroupWalletSetupContent(setup.copy(step = SetupStep.Biometrics), {}, {}, {}, {})
        "gw-setup-face-id" -> GroupWalletSetupContent(setup.copy(step = SetupStep.Biometrics, biometricLabel = "Face ID"), {}, {}, {}, {})
        "gw-setup-failed" -> GroupWalletSetupContent(setup.copy(step = SetupStep.Creating, problem = SetupProblem.Failed, reference = "req_P3kq9X"), {}, {}, {}, {})
        "gw-setup-offline" -> GroupWalletSetupContent(setup.copy(step = SetupStep.Creating, problem = SetupProblem.Offline, online = false), {}, {}, {}, {})
        "gw-setup-cancelled" -> GroupWalletSetupContent(setup.copy(problem = SetupProblem.Cancelled), {}, {}, {}, {})
        "gw-info-none" -> InfoPreview(GroupInfoMoney(true, gwMoney(wallet = false, goal = false)))
        "gw-info-finish" -> InfoPreview(GroupInfoMoney(true, gwMoney(active = false, goal = false)))
        "gw-info-offline" -> InfoPreview(GroupInfoMoney(true, gwMoney(wallet = false, goal = false)), online = false)
        "gw-info-loading" -> InfoPreview(GroupInfoMoney(true))
        "gw-info-error" -> InfoPreview(GroupInfoMoney(true, loadError = "Circaso is busy right now. Please try again in a moment.", loadReference = "req_K2a9"))
        "gw-info-wallet" -> InfoPreview(GroupInfoMoney(true, gwMoney(), gwWallet(), biometricsOn = true, biometricLabel = "Fingerprint"))
        "gw-info-bio-lost" -> InfoPreview(GroupInfoMoney(true, gwMoney(), gwWallet(), biometricsOn = false, biometricsLost = true, biometricLabel = "Fingerprint"))
        "gw-info-member" -> InfoPreview(GroupInfoMoney(true, gwMoney(signer = false).copy(isOwner = false, isAdmin = false), gwWallet()), chat = kilimani.copy(myRole = MemberRole.Member))
        "gw-info-locked" -> InfoPreview(GroupInfoMoney(true, gwMoney(signer = false), gwWallet()), chat = kilimani.copy(members = kilimani.members.filter { it.userId != "2" }))
        "gw-target-needs-wallet" -> InfoPreview(GroupInfoMoney(true, gwMoney(wallet = false, goal = false))) { TargetNeedsWalletContent(kilimani, {}, {}) }
        "gw-leave-blocked" -> InfoPreview(GroupInfoMoney(true, gwMoney(), gwWallet())) { LeaveBlockedContent(kilimani, "KES 32,400", {}, {}) }
        "gw-target" -> TargetContent(TargetUiState(groupName = "Kilimani Chama", name = "December trip to the coast", amountText = "50000", fiat = gwFiat, money = gwMoney(goal = false), walletJustReady = true), {}, {}, {}, {}, {}, {})
        // The pushed group wallet (group info, the goal strip): the Wallet tab's own design under a bar.
        "gw-wallet" -> PushedWallet(gwState, gwActions)
        "gw-wallet-member" -> PushedWallet(memberState(gwState), gwActions)
        "gw-wallet-loading" -> PushedWallet(GroupWalletUiState(chat = kilimani, money = gwMoney()), gwActions)
        "gw-wallet-error" -> PushedWallet(GroupWalletUiState(chat = kilimani, loading = false, error = "Circaso is busy right now. Please try again in a moment.", reference = "req_W9s"), gwActions)
        // The Wallet tab with the corner switcher (Pasaka, 2026-11-04): closed, open, three groups, a locked wallet.
        "wallet-switcher" -> TabPreview(tabState(gwState))
        "wallet-switcher-open" -> TabPreview(tabState(gwState).copy(switching = true))
        "wallet-switcher-three" -> TabPreview(tabState(gwState, three = true).copy(switching = true))
        "wallet-switcher-locked" -> TabPreview(tabState(lockedState(gwState), three = true, locked = true).copy(selectedWalletId = "gw-1", switching = true), gwActions)
        // One wallet design for every wallet: personal, a group's signer, a member, offline, locked.
        "wallet-unified-personal" -> TabPreview(tabState(gwState))
        "wallet-unified-signer" -> TabPreview(tabState(gwState).copy(selectedWalletId = "gw-1"), gwActions)
        "wallet-unified-member" -> TabPreview(tabState(memberState(gwState), member = true).copy(selectedWalletId = "gw-1"), gwActions)
        "wallet-unified-offline" -> TabPreview(tabState(gwState.copy(online = false)).copy(selectedWalletId = "gw-1", online = false), gwActions)
        "wallet-unified-locked" -> TabPreview(tabState(lockedState(gwState), locked = true).copy(selectedWalletId = "gw-1"), gwActions)
        "wallet-unified-setting-up" -> TabPreview(tabState(GroupWalletUiState(chat = kilimani, money = gwMoney(active = false, signer = false), loading = false)).copy(selectedWalletId = "gw-1"), gwActions)
        "wallet-unified-loading" -> TabPreview(tabState(GroupWalletUiState(chat = kilimani, money = gwMoney())).copy(selectedWalletId = "gw-1"), gwActions)
        "gw-contribute" -> PayPreview(payState(PayUiState(groupId = "g1", chatId = "g1", payee = Person("", "Kilimani Chama", null, null, null), amountText = "1000", note = "November", groupMoney = gwMoney())))
        "gw-pay-payout" -> PayPreview(payState(PayUiState(payee = Person("", "Coast Bus", null, null, null), chatId = "g1", amountText = "92.879", inFiat = false, requestId = "r1", goalName = "December trip", groupMoney = gwMoney())).copy(payingFrom = kilimaniWallet, wallet = gwWallet()))
        "gw-pay-signing" -> PayPreview(payState(PayUiState(payee = Person("", "Coast Bus", null, null, null), chatId = "g1", amountText = "92.879", inFiat = false, requestId = "r1", groupMoney = gwMoney(), step = PayStep.Signing)).copy(payingFrom = kilimaniWallet, wallet = gwWallet()))
        "gw-not-sent" -> PayPreview(payState(PayUiState(payee = Person("", "Coast Bus", null, null, null), chatId = "g1", amountText = "92.879", inFiat = false, requestId = "r1", groupMoney = gwMoney(), failure = PayFailure("Kilimani Chama", "Something went wrong while signing. Nothing was sent.", "req_N2sX8c"))).copy(payingFrom = kilimaniWallet, wallet = gwWallet()))
        "gw-request-payout" -> PayPreview(payState(PayUiState(payee = Person("2", "Otieno Ouma", null, null, null), chatId = "dm", amountText = "5000", note = "Venue deposit", groupMoney = gwMoney())).copy(payingFrom = kilimaniWallet, wallet = gwWallet()))
        "gw-pay-personal" -> PayPreview(payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "500", note = "for lunch tomorrow")))
        "gw-pay-from" -> PayPreview(payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "500", note = "for lunch tomorrow", choosing = true)))
        // Phase 2F on the Pay screen (Wallet tab Send): the same fee line, GW13 to GW13d, GW20.
        "gw-pay-fee" -> PayPreview(payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "500", note = "for lunch tomorrow", fee = PayFee.Quoted("19380", 50, quoteId = "q1", expiresAt = kotlin.time.Instant.fromEpochMilliseconds(nowMillis() + 900_000).toString()))))
        "gw-pay-fee-quoting" -> PayPreview(payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "500", fee = PayFee.Quoting)))
        "gw-pay-fee-error" -> PayPreview(payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "500", fee = PayFee.Failed("req_F9a2Lm"))))
        "gw-pay-fee-short" -> PayPreview(payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "18240", note = "rent", fee = PayFee.Quoted("706900", 50), block = com.circaso.feature.wallet.presentation.PayBlock.Funds("140480000"))))
        "gw-pay-offline" -> PayPreview(payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "500")).copy(online = false))
        else -> return false
    }
    return true
}

// ---- Money in the chat (Pasaka, 2026-11-04): the chat's money card is the review; Pay goes straight to the PIN.

private val mum = com.circaso.feature.messaging.domain.Chat(
    "dm", ChatKind.Direct, "Mum", null, listOf(gwMember("me", "Pasaka Mutuku"), gwMember("6", "Mum")), false, 1, 0, MemberRole.Member, "6",
)

private fun gwPayment(id: Long, transferId: String, minor: String, outgoing: Boolean, note: String?, minutesAgo: Long, chat: String = "dm", sender: String = "me") =
    com.circaso.feature.messaging.domain.Message.Payment(
        id, chat, nowMillis() - minutesAgo * 60_000, null, "m$id", sender, if (sender == "me") "Pasaka" else "Mum", outgoing, transferId, minor, "USDC", "BASE-SEPOLIA", note,
        com.circaso.feature.messaging.domain.DeliveryState.Delivered, null,
    )

private fun gwText(id: Long, text: String, outgoing: Boolean, minutesAgo: Long, chat: String = "dm") =
    com.circaso.feature.messaging.domain.Message.Text(
        id, chat, nowMillis() - minutesAgo * 60_000, null, "t$id", if (outgoing) "me" else "6", if (outgoing) "Pasaka" else "Mum", outgoing, text,
        com.circaso.feature.messaging.domain.DeliveryState.Read, null, null,
    )

private val mumThread = listOf(gwText(1, "Habari ya leo? Did you eat?", false, 9), gwText(2, "Not yet, still in a meeting", true, 8))

private fun quick(s: PayUiState) = com.circaso.feature.messaging.presentation.QuickPay(s, {}, {}, {}, {}, {}, {})

@Composable
private fun ChatPreview(
    state: com.circaso.feature.messaging.presentation.ThreadUiState,
    pay: PayUiState? = null,
    payout: PayUiState? = null,
) = com.circaso.feature.messaging.presentation.ThreadContent(
    state, {}, {}, null, {}, {}, {}, {}, {}, {}, {},
    quickPay = pay?.let { quick(it) }, payout = payout?.let { quick(it) },
)

/** A quote held 15 minutes from now, and a cap that frees up in four hours, for the fee previews. */
private val feeHeld get() = kotlin.time.Instant.fromEpochMilliseconds(nowMillis() + 15 * 60_000).toString()
private val capFits get() = kotlin.time.Instant.fromEpochMilliseconds(nowMillis() + 4 * 3_600_000).toString()

/** GW14c: an approved payout of 1,160.99 USDC that waits for the group's daily limit. */
private val waitingRequest = com.circaso.feature.chama.domain.ChamaRequest(
    "r9", "g1", "2", null, null, "Coast Bus", "1160990000", "the December trip", com.circaso.feature.chama.domain.RequestState.Approved, 2,
    listOf(com.circaso.feature.chama.domain.Approval("2", true, ""), com.circaso.feature.chama.domain.Approval("3", true, "")), null, "", "", "",
)

/** Draws a chat-money preview; false when [name] isn't one. */
@Composable
internal fun ChatMoneyPreview(name: String): Boolean {
    val mumPay = payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "500", note = "for lunch tomorrow"))
    val direct = com.circaso.feature.messaging.presentation.ThreadUiState(chat = mum, messages = mumThread, draft = "#500 for lunch tomorrow", fiat = gwFiat)
    val group = com.circaso.feature.messaging.presentation.ThreadUiState(
        chat = kilimani, messages = listOf(gwText(1, "November contributions are due Friday", false, 30, "g1")), groupMoney = gwMoney(), fiat = gwFiat, myUserId = "me",
        chama = gwMoney().asChama(), draft = "#1000 November",
    )
    val contribute = payState(PayUiState(groupId = "g1", chatId = "g1", payee = Person("", "Kilimani Chama", null, null, null), amountText = "1000", note = "November", groupMoney = gwMoney()))
    when (name) {
        // GW9a/GW9b in the chat: the whole review on the card; Personal by default with the switch.
        "chat-pay" -> ChatPreview(direct, mumPay)
        "chat-pay-from" -> ChatPreview(direct, mumPay.copy(choosing = true))
        "chat-pay-signing" -> ChatPreview(direct, mumPay.copy(step = PayStep.Signing))
        "chat-pay-failed" -> ChatPreview(direct, mumPay.copy(failure = PayFailure("your wallet", "Something went wrong while signing. Nothing was sent.", "req_N2sX8c")))
        "chat-pay-offline" -> ChatPreview(direct.copy(online = false), mumPay.copy(online = false))
        "chat-pay-personal-only" -> ChatPreview(direct, mumPay.copy(choices = listOf(NamedWallet.Personal), groupWallets = false))
        // Any priced coin in the person's money (2026-11-06): "#500 @eth" is KES 500 with the ETH under it.
        "chat-pay-eth" -> ChatPreview(direct.copy(draft = "#500 @eth for lunch tomorrow"), ethPay(mumPay))
        "chat-pay-eth-large" -> androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale = 2f),
        ) { ChatPreview(direct.copy(draft = "#500 @eth for lunch tomorrow"), ethPay(mumPay)) }
        "chat-pay-eth-quoting" -> ChatPreview(direct.copy(draft = "#500 @eth"), ethPay(mumPay).copy(fee = PayFee.Quoting))
        "chat-pay-eth-no-price" -> ChatPreview(direct.copy(draft = "#500 @eth"), ethPay(mumPay, price = null).copy(typedInFiat = true))
        "chat-pay-eth-old-price" -> ChatPreview(direct.copy(draft = "#500 @eth"), ethPay(mumPay).copy(pricesFresh = false, priceRefresh = com.circaso.feature.wallet.presentation.PriceRefresh.Loading))
        "chat-pay-eth-price-failed" -> ChatPreview(direct.copy(draft = "#500 @eth"), ethPay(mumPay).copy(pricesFresh = false, priceRefresh = com.circaso.feature.wallet.presentation.PriceRefresh.Failed("req_N2sX8c")))
        // A coin with no price at all: typed in its own units, and the card says so.
        "chat-pay-eth-coin-units" -> ChatPreview(direct.copy(draft = "#0.05 @eth"), ethPay(mumPay, price = null).copy(amountText = "0.05", typedInFiat = false))
        // GW9a2/GW9d: a contribution, with where the target will stand.
        "chat-contribute" -> ChatPreview(group, contribute)
        // GW9 from the D5 card: an approved payout paid from the chat.
        "chat-payout" -> ChatPreview(
            group.copy(draft = ""),
            payout = payState(PayUiState(payee = Person("", "Coast Bus", null, null, null), chatId = "g1", amountText = "92.879", inFiat = false, requestId = "r1", goalName = "December trip", groupMoney = gwMoney(), note = "the bus deposit"))
                .copy(payingFrom = kilimaniWallet, wallet = gwWallet()),
        )
        // Phase 2F, the fee and the limits on the card (GW13–GW13d, GW14, GW14b, GW20).
        "chat-fee" -> ChatPreview(direct, mumPay.copy(fee = PayFee.Quoted("19380", 50, quoteId = "q1", expiresAt = feeHeld)))
        "chat-fee-quoting" -> ChatPreview(direct, mumPay.copy(fee = PayFee.Quoting))
        "chat-fee-minimum" -> ChatPreview(direct, mumPay.copy(amountText = "50", note = "mandazi", fee = PayFee.Quoted("40000", 50, minimum = true, quoteId = "q1", expiresAt = feeHeld)))
        "chat-fee-changed" -> ChatPreview(direct, mumPay.copy(fee = PayFee.Quoted("25000", 50, quoteId = "q2", expiresAt = feeHeld), feeChange = com.circaso.feature.wallet.presentation.FeeChange("19380", "25000")))
        "chat-fee-error" -> ChatPreview(direct, mumPay.copy(fee = PayFee.Failed("req_F9a2Lm")))
        "chat-fee-offline" -> ChatPreview(direct.copy(online = false), mumPay.copy(online = false, fee = PayFee.Offline))
        "chat-fee-short" -> ChatPreview(direct, mumPay.copy(amountText = "18240", note = "rent", fee = PayFee.Quoted("706900", 50, quoteId = "q1", expiresAt = feeHeld), block = com.circaso.feature.wallet.presentation.PayBlock.Funds("140480000")))
        "chat-contribute-fee" -> ChatPreview(group, contribute.copy(fee = PayFee.Quoted("77400", 100, quoteId = "q1", expiresAt = feeHeld)))
        "chat-payout-cap" -> ChatPreview(
            group.copy(draft = ""),
            payout = payState(PayUiState(payee = Person("", "Coast Bus", null, null, null), chatId = "g1", amountText = "1160.99", inFiat = false, requestId = "r1", goalName = "December trip", groupMoney = gwMoney()))
                .copy(
                    payingFrom = kilimaniWallet, wallet = gwWallet(), fee = PayFee.Quoted("11609900", 100),
                    block = com.circaso.feature.wallet.presentation.PayBlock.Daily("0", "10000000000", capFits, capFits, "0"),
                ),
        )
        "chat-request-cap" -> ChatPreview(
            direct.copy(draft = "#150000 Stock for December"),
            payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "150000", note = "Stock for December", groupMoney = gwMoney()))
                .copy(payingFrom = kilimaniWallet, wallet = gwWallet(), fee = PayFee.Quoted("11627900", 100), block = com.circaso.feature.wallet.presentation.PayBlock.Daily("599000000", "10000000000", null, capFits, "594000000")),
        )
        // GW14c: the approved payout's card in the thread when it waits for the limit.
        "chat-request-waits" -> ChatPreview(
            group.copy(draft = "", groupMoney = gwMoney().let { m -> m.copy(openRequests = listOf(waitingRequest)) }, payoutWaits = mapOf("r9" to capFits)),
        )
        // After the PIN: Sending until confirmed, then Sent; Not sent with Try Again when the chain refused it.
        "chat-cards" -> ChatPreview(
            direct.copy(
                draft = "",
                messages = mumThread + listOf(
                    gwPayment(3, "tx-sent", "3870000", true, "for lunch", 6),
                    gwPayment(4, "tx-sending", "7740000", true, "fare", 2),
                    gwPayment(5, "tx-failed", "3870000", true, "for lunch tomorrow", 1),
                ),
                paymentStates = mapOf("tx-sent" to TransferState.Confirmed, "tx-sending" to TransferState.Submitted, "tx-failed" to TransferState.Failed),
            ),
            mumPay,
        )
        else -> return false
    }
    return true
}
