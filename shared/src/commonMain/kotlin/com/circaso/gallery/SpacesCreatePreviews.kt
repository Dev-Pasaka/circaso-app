package com.circaso.gallery

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.circaso.feature.chama.presentation.GroupWalletUiState
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceMe
import com.circaso.feature.spaces.domain.SpacePerson
import com.circaso.feature.spaces.domain.SpacePrice
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceSummary
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.SpaceWalletRef
import com.circaso.feature.spaces.domain.SpaceWalletStatus
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.TeamPick
import com.circaso.feature.spaces.presentation.create.CreateProblem
import com.circaso.feature.spaces.presentation.create.HandleState
import com.circaso.feature.spaces.presentation.create.NewSpaceCallbacks
import com.circaso.feature.spaces.presentation.create.NewSpaceContent
import com.circaso.feature.spaces.presentation.create.NewSpaceUiState
import com.circaso.feature.spaces.presentation.create.SpaceEditActions
import com.circaso.feature.spaces.presentation.create.SpaceEditContent
import com.circaso.feature.spaces.presentation.create.SpaceEditMode
import com.circaso.feature.spaces.presentation.create.SpaceEditUiState
import com.circaso.feature.spaces.presentation.create.SpaceSettingsActions
import com.circaso.feature.spaces.presentation.create.SpaceSettingsContent
import com.circaso.feature.spaces.presentation.create.SpaceSettingsUiState
import com.circaso.feature.spaces.presentation.create.SpaceSetupProblem
import com.circaso.feature.spaces.presentation.create.SpaceSetupStep
import com.circaso.feature.spaces.presentation.create.SpaceWalletContent
import com.circaso.feature.spaces.presentation.create.SpaceWalletSetupContent
import com.circaso.feature.spaces.presentation.create.SpaceWalletSetupState
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
import com.circaso.feature.wallet.presentation.WalletActions
import com.circaso.feature.wallet.presentation.WalletTabContent
import com.circaso.feature.wallet.presentation.WalletUiState

private val kes = FiatValue("KES", "0", "129.20", "2026-11-04T10:42:00Z", false)

private val yogaDraft = NewSpaceUiState(
    name = "Kilimani Yoga Collective", handle = "kilimaniyoga", handleState = HandleState.Available, handleEdited = true,
    about = "Sunrise yoga in Kilimani, Tuesdays and Saturdays. All levels, mats provided.", topic = SpaceTopic.Health, fiat = kes,
)

private fun yoga(role: SpaceRole = SpaceRole.Owner, wallet: SpaceWalletStatus = SpaceWalletStatus.Active) = Space(
    summary = SpaceSummary("s9", "kilimaniyoga", "Kilimani Yoga Collective", "Sunrise yoga in Kilimani.", SpaceTopic.Health, visibility = SpaceVisibility.MembersOnly,
        price = SpacePrice("10000000", PricePeriod.Monthly), memberCount = 39),
    owner = SpacePerson("me", "Pasaka Mutuku"), wallet = SpaceWalletRef("sw-9", wallet, role == SpaceRole.Owner),
    me = SpaceMe(Relation.Member, role),
)

private fun spaceWallet(minor: String, fetched: Long = 1_762_250_520_000) = Wallet(
    status = WalletStatus.Active,
    chains = listOf(WalletChain("BASE-SEPOLIA", "0x5pace", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, minor, "", null)), true)),
    totals = listOf(AssetTotal("USDC", 6, minor, "")),
    fiat = FiatValue("KES", com.circaso.feature.wallet.domain.usdcToFiat(minor, "129.20") ?: "0", "129.20", "2026-11-04T10:42:00Z", false),
    holdUntil = null, fetchedAt = fetched, walletId = "sw-1", owner = WalletOwner(WalletOwnerType.Space, null, null, "baraka", "s1"), canSign = true,
)

private fun payment(id: String, name: String, minor: String, note: String, out: Boolean = false) = Transfer(
    transferId = id, direction = if (out) TransferDirection.Out else TransferDirection.In, state = TransferState.Confirmed, blockchain = "BASE-SEPOLIA",
    from = "0xpayer", toUserId = null, toAddress = "0x5pace", amount = com.circaso.feature.wallet.domain.Amount("USDC", minor, ""), fee = null, chatId = null,
    txHash = null, failureReason = null, counterpartName = name, note = note, createdAt = 1_762_250_000_000, updatedAt = 1_762_250_000_000,
)

private fun sneakerWallet(canSign: Boolean, minor: String = "1630030000", online: Boolean = true, empty: Boolean = false, loading: Boolean = false) = GroupWalletUiState(
    wallet = if (loading) null else spaceWallet(if (empty) "0" else minor),
    activity = if (empty || loading) emptyList() else listOf(
        payment("t1", "Pasaka", "15480000", "deposit, size 42 Jordans"),
        payment("t2", "Faith", "50310000", "Air Force 1, size 39"),
        payment("t3", "Juma", "387000000", "stock for Friday", out = true),
    ),
    online = online, loading = loading, nameHint = "Nairobi Sneaker Drop", spaceId = "s1", spaceOwnerName = "Baraka Mwita", spaceCanSign = canSign,
)

private val noWalletActions = WalletActions({}, {}, {}, {}, {})

/** The create and space wallet frames (SN1–SN8b, SW1–SW1f) with sample data. */
@Composable
internal fun SpaceCreatePreview(name: String): Boolean {
    when (name) {
        "space-new" -> NewSpaceContent(yogaDraft, NewSpaceCallbacks())
        "space-new-checking" -> NewSpaceContent(yogaDraft.copy(handleState = HandleState.Checking), NewSpaceCallbacks())
        "space-new-taken" -> NewSpaceContent(yogaDraft.copy(handleState = HandleState.Taken(listOf("kilimaniyoga.ke", "yogakilimani", "kilimaniyogahub"))), NewSpaceCallbacks())
        "space-new-join" -> NewSpaceContent(yogaDraft.copy(step = 2, visibility = SpaceVisibility.MembersOnly, priceText = "10.00"), NewSpaceCallbacks())
        "space-new-roles" -> NewSpaceContent(
            yogaDraft.copy(step = 3, team = listOf(TeamPick(SpacePerson("achieng", "Achieng Odhiambo", "achieng"), SpaceRole.Admin), TeamPick(SpacePerson("faith", "Faith Chebet", "faithc"), SpaceRole.Moderator))),
            NewSpaceCallbacks(),
        )
        "space-new-pick" -> NewSpaceContent(yogaDraft.copy(step = 3, picking = true, contacts = listOf(SpacePerson("achieng", "Achieng Odhiambo", "achieng"), SpacePerson("faith", "Faith Chebet", "faithc"), SpacePerson("kevin", "Kevin Ochieng", "kevo"))), NewSpaceCallbacks())
        "space-new-offline" -> NewSpaceContent(yogaDraft.copy(online = false), NewSpaceCallbacks())
        "space-new-failed" -> NewSpaceContent(yogaDraft.copy(step = 3, problem = CreateProblem.Failed("The space wasn’t created. Nothing was saved and no wallet was made.", "req_N8cv2E")), NewSpaceCallbacks())
        "space-new-not-yet" -> NewSpaceContent(yogaDraft.copy(problem = CreateProblem.NotYet(1_762_852_000_000)), NewSpaceCallbacks())
        "space-setting-up", "space-pin" -> SpaceWalletSetupContent(SpaceWalletSetupState("Kilimani Yoga Collective", step = if (name == "space-pin") SpaceSetupStep.Pin else SpaceSetupStep.Networks), false, {}, {}, {}, {}, {}, {})
        "space-setup-biometrics" -> SpaceWalletSetupContent(SpaceWalletSetupState("Kilimani Yoga Collective", step = SpaceSetupStep.Biometrics, biometricLabel = "Fingerprint"), false, {}, {}, {}, {}, {}, {})
        "space-setup-failed" -> SpaceWalletSetupContent(SpaceWalletSetupState("Kilimani Yoga Collective", step = SpaceSetupStep.Creating, problem = SpaceSetupProblem.Failed, reference = "req_W5sp3K"), false, {}, {}, {}, {}, {}, {})
        "space-live" -> SpaceWalletSetupContent(SpaceWalletSetupState("Kilimani Yoga Collective", handle = "kilimaniyoga", visibility = SpaceVisibility.MembersOnly, price = SpacePrice("10000000", PricePeriod.Monthly), step = SpaceSetupStep.Done), true, {}, {}, {}, {}, {}, {})
        "space-settings" -> SpaceSettingsContent(SpaceSettingsUiState(yoga(), waiting = 2, openReports = 0, admins = 1, moderators = 1, wallet = spaceWallet("380000000")), SpaceSettingsActions())
        "space-settings-admin" -> SpaceSettingsContent(SpaceSettingsUiState(yoga(SpaceRole.Admin), waiting = 2, openReports = 1, admins = 1, moderators = 1, wallet = spaceWallet("380000000")), SpaceSettingsActions())
        "space-settings-moderator" -> SpaceSettingsContent(SpaceSettingsUiState(yoga(SpaceRole.Moderator), openReports = 1), SpaceSettingsActions())
        "space-details" -> SpaceEditContent(SpaceEditUiState(SpaceEditMode.Details, yoga(), true, name = "Kilimani Yoga Collective", handle = "kilimaniyoga", about = "Sunrise yoga in Kilimani.", topic = SpaceTopic.Health), SpaceEditActions())
        "space-price-change" -> SpaceEditContent(
            SpaceEditUiState(SpaceEditMode.Join, yoga(), true, visibility = SpaceVisibility.MembersOnly, priceText = "12.00", period = PricePeriod.Monthly, fiat = kes),
            SpaceEditActions(),
        )
        "space-wallet" -> SpaceWalletContent(sneakerWallet(true), false, {}, noWalletActions, {}, {}, {}, {}, {})
        "space-wallet-admin" -> SpaceWalletContent(sneakerWallet(false), false, {}, noWalletActions, {}, {}, {}, {}, {})
        "space-wallet-offline" -> SpaceWalletContent(sneakerWallet(true, online = false), false, {}, noWalletActions, {}, {}, {}, {}, {})
        "space-wallet-loading" -> SpaceWalletContent(sneakerWallet(true, loading = true), false, {}, noWalletActions, {}, {}, {}, {}, {})
        "space-wallet-empty" -> SpaceWalletContent(sneakerWallet(true, empty = true).copy(nameHint = "Kilimani Yoga Collective"), false, {}, noWalletActions, {}, {}, {}, {}, {})
        "space-wallet-tab", "space-wallet-switcher" -> {
            val wallets = listOf(
                NamedWallet.Personal,
                NamedWallet("gw-1", "g1", "Mwita Family", null, WalletOwnerType.Group, false, WalletStatus.Active, "otieno"),
                NamedWallet("sw-1", null, "Nairobi Sneaker Drop", null, WalletOwnerType.Space, true, WalletStatus.Active, "me", spaceId = "s1"),
                NamedWallet("sw-2", null, "Karura Runners", null, WalletOwnerType.Space, true, WalletStatus.SettingUp, "me", spaceId = "s2"),
            )
            val state = WalletUiState(
                wallets = wallets, selectedWalletId = "sw-1", switching = name == "space-wallet-switcher", group = sneakerWallet(true),
                balances = mapOf(null to spaceWallet("76400000"), "gw-1" to spaceWallet("109900000"), "sw-1" to spaceWallet("1630030000")),
            )
            WalletTabContent(state, PaddingValues(), { noWalletActions }, groupHooks = { _, _ ->
                com.circaso.feature.chama.presentation.GroupWalletHooks(com.circaso.feature.chama.presentation.GroupWalletActions({}, {}, {}, {}, {}), { _, _ -> }, {})
            }, onSwitching = {}, onSelectWallet = {}, onCreate = {}, onRefresh = {}, onReceive = {}, onComing = {})
        }
        else -> return false
    }
    return true
}

