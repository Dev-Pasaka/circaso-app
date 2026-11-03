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
import com.circaso.feature.chama.presentation.GroupWalletContent
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
import com.circaso.feature.wallet.presentation.PayUiState
import com.circaso.feature.wallet.presentation.PayStep

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

private fun newGroup(walletOn: Boolean, online: Boolean = true, failed: Boolean = false) = NewGroupUiState(
    name = "Kilimani Chama", contacts = gwContacts(), selected = setOf("2", "3", "4"), online = online, walletsAvailable = true, walletOn = walletOn,
    targetText = if (walletOn) "50000" else "", fiat = gwFiat, failed = failed, reference = if (failed) "req_G4mx2Q" else null,
)

private fun payState(base: PayUiState) = base.copy(
    wallet = gwWallet(walletId = null, minor = "141180000"), groupWallets = true, online = true, blockchain = "BASE-SEPOLIA",
    choices = listOf(NamedWallet.Personal, NamedWallet("gw-1", "g1", "Kilimani Chama", null, WalletOwnerType.Group, true, WalletStatus.Active, "me")),
    balances = mapOf(null to gwWallet("141180000", null), "gw-1" to gwWallet()), memberNames = kilimani.members.associate { it.userId to it.displayName },
)

private val kilimaniWallet = NamedWallet("gw-1", "g1", "Kilimani Chama", null, WalletOwnerType.Group, true, WalletStatus.Active, "me")

@Composable
private fun PayPreview(state: PayUiState) = PayContent(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, null)

/** Draws a group-wallet preview; false when [name] isn't one. */
@Composable
internal fun GroupWalletPreview(name: String): Boolean {
    val setup = GroupWalletSetupState(groupName = "Kilimani Chama", biometricLabel = "Fingerprint", walletId = "gw-1")
    val gwState = GroupWalletUiState(chat = kilimani, money = gwMoney(), wallet = gwWallet(), loading = false, myUserId = "me", activity = listOf(
        Transfer("t1", TransferDirection.In, TransferState.Confirmed, "BASE-SEPOLIA", "", null, "", Amount("USDC", "7740000", "7.74"), null, "g1", null, null, "Wanjiru Kamau", null, nowMillis() - 86_400_000, 0, walletId = "gw-1"),
        Transfer("t2", TransferDirection.Out, TransferState.Confirmed, "BASE-SEPOLIA", "", null, "0x12", Amount("USDC", "92879000", "92.88"), null, "g1", null, null, "Coast Bus", null, nowMillis() - 3_600_000, 0, walletId = "gw-1"),
    ))
    val gwActions = GroupWalletActions({}, {}, {}, {}, {})
    when (name) {
        "gw-new-group" -> NewGroupContent(newGroup(false), {}, {}, {}, {}, {}, {}, {}, {}, {})
        "gw-new-group-wallet" -> NewGroupContent(newGroup(true), {}, {}, {}, {}, {}, {}, {}, {}, {})
        "gw-new-group-offline" -> NewGroupContent(newGroup(true, online = false), {}, {}, {}, {}, {}, {}, {}, {}, {})
        "gw-new-group-failed" -> NewGroupContent(newGroup(true, failed = true), {}, {}, {}, {}, {}, {}, {}, {}, {})
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
        "gw-wallet" -> CircasoScaffold(grouped = true, topBar = { CircasoNavBar("Kilimani Chama", onBack = {}) }) { p -> GroupWalletContent(gwState, PaddingValues(bottom = p.calculateBottomPadding()), gwActions, { _, _ -> }, {}, {}) }
        "gw-wallet-member" -> CircasoScaffold(grouped = true, topBar = { CircasoNavBar("Kilimani Chama", onBack = {}) }) { p -> GroupWalletContent(gwState.copy(money = gwMoney(signer = false).copy(isAdmin = false)), PaddingValues(bottom = p.calculateBottomPadding()), gwActions, { _, _ -> }, {}, {}) }
        "gw-wallet-loading" -> CircasoScaffold(grouped = true, topBar = { CircasoNavBar("Kilimani Chama", onBack = {}) }) { p -> GroupWalletContent(GroupWalletUiState(chat = kilimani), PaddingValues(bottom = p.calculateBottomPadding()), gwActions, { _, _ -> }, {}, {}) }
        "gw-wallet-error" -> CircasoScaffold(grouped = true, topBar = { CircasoNavBar("Kilimani Chama", onBack = {}) }) { p -> GroupWalletContent(GroupWalletUiState(chat = kilimani, loading = false, error = "Circaso is busy right now. Please try again in a moment.", reference = "req_W9s"), PaddingValues(bottom = p.calculateBottomPadding()), gwActions, { _, _ -> }, {}, {}) }
        "gw-contribute" -> PayPreview(payState(PayUiState(groupId = "g1", chatId = "g1", payee = Person("", "Kilimani Chama", null, null, null), amountText = "1000", note = "November", groupMoney = gwMoney())))
        "gw-pay-payout" -> PayPreview(payState(PayUiState(payee = Person("", "Coast Bus", null, null, null), chatId = "g1", amountText = "92.879", inFiat = false, requestId = "r1", goalName = "December trip", groupMoney = gwMoney())).copy(payingFrom = kilimaniWallet, wallet = gwWallet()))
        "gw-pay-signing" -> PayPreview(payState(PayUiState(payee = Person("", "Coast Bus", null, null, null), chatId = "g1", amountText = "92.879", inFiat = false, requestId = "r1", groupMoney = gwMoney(), step = PayStep.Signing)).copy(payingFrom = kilimaniWallet, wallet = gwWallet()))
        "gw-not-sent" -> PayPreview(payState(PayUiState(payee = Person("", "Coast Bus", null, null, null), chatId = "g1", amountText = "92.879", inFiat = false, requestId = "r1", groupMoney = gwMoney(), failure = PayFailure("Kilimani Chama", "Something went wrong while signing. Nothing was sent.", "req_N2sX8c"))).copy(payingFrom = kilimaniWallet, wallet = gwWallet()))
        "gw-request-payout" -> PayPreview(payState(PayUiState(payee = Person("2", "Otieno Ouma", null, null, null), chatId = "dm", amountText = "5000", note = "Venue deposit", groupMoney = gwMoney())).copy(payingFrom = kilimaniWallet, wallet = gwWallet()))
        "gw-pay-personal" -> PayPreview(payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "500", note = "for lunch tomorrow")))
        "gw-pay-from" -> PayPreview(payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "500", note = "for lunch tomorrow", choosing = true)))
        "gw-pay-offline" -> PayPreview(payState(PayUiState(payee = Person("6", "Mum", null, null, null), chatId = "dm", amountText = "500")).copy(online = false))
        else -> return false
    }
    return true
}
