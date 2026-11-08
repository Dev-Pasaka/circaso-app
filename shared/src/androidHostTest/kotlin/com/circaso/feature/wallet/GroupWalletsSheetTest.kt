package com.circaso.feature.wallet

import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.AssetTotal
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletOwnerType
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.presentation.GroupMenuActions
import com.circaso.feature.wallet.presentation.GroupSheetActionKind
import com.circaso.feature.wallet.presentation.NamedWallet
import com.circaso.feature.wallet.presentation.WalletMode
import com.circaso.feature.wallet.presentation.WalletUiState
import com.circaso.feature.wallet.presentation.groupSheetActions
import com.circaso.feature.wallet.presentation.groupSheetList
import com.circaso.feature.wallet.presentation.networkValue
import com.circaso.feature.wallet.presentation.networksOf
import com.circaso.feature.wallet.presentation.offlineNote
import com.circaso.feature.wallet.presentation.showsGroupSearch
import com.circaso.feature.wallet.presentation.walletTotal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Group Wallets sheet's rules (2026-11-08): what it lists, which actions each role gets, what the numbers say. */
class GroupWalletsSheetTest {
    private fun group(n: Int, canSign: Boolean = true, status: WalletStatus = WalletStatus.Active, nets: List<String> = listOf("BASE-SEPOLIA")) =
        NamedWallet("gw-$n", "g$n", "Group $n", null, WalletOwnerType.Group, canSign, status, if (canSign) "me" else "7", nets)

    private val menu = GroupMenuActions({}, {}, onPay = {}, onSendToWallet = {}, onNetwork = { _, _ -> })

    private fun state(selected: NamedWallet, others: List<NamedWallet> = emptyList(), locked: Set<String> = emptySet()) = WalletUiState(
        wallets = listOf(NamedWallet.Personal, selected) + others, selectedWalletId = selected.walletId, mode = WalletMode.Groups,
        groupsEnabled = true, lockedWallets = locked,
    )

    private val fiat = FiatValue("KES", "37722.52", "129.20", "2026-11-08T10:00:00Z", false)

    private fun wallet(vararg chains: WalletChain, fiat: FiatValue? = this.fiat) =
        Wallet(WalletStatus.Active, chains.toList(), listOf(AssetTotal("USDC", 6, "291970000", "")), fiat, null, 0L, "gw-1")

    private fun chain(id: String, usdcMinor: String, fiatAmount: String?) =
        WalletChain(id, "0x51c0000000000000000000000000000000c4a1", "ACTIVE", listOf(Asset("USDC", "0xusdc", 6, usdcMinor, "", fiatAmount)), true)

    @Test fun theGroupOnScreenComesFirstAndTheOthersFollowInOrder() {
        val groups = (1..4).map { group(it) }
        val list = groupSheetList(groups, "gw-3", "")
        assertEquals("gw-3", list.current?.walletId)
        assertEquals(listOf("gw-1", "gw-2", "gw-4"), list.others.map { it.walletId })
        assertFalse(list.searching)
    }

    @Test fun aSearchLooksThroughEveryGroupTheOneOnScreenIncluded() {
        val groups = listOf(group(1).copy(name = "Kilimani Chama"), group(2).copy(name = "Mama Mboga Savings Group"), group(3).copy(name = "Kilimani Runners"))
        val list = groupSheetList(groups, "gw-1", " kilimani ")
        assertNull(list.current, "while searching the group on screen is a result like any other, ticked")
        assertEquals(listOf("gw-1", "gw-3"), list.others.map { it.walletId })
        assertTrue(list.searching)
        assertTrue(groupSheetList(groups, "gw-1", "nothing like it").others.isEmpty())
    }

    @Test fun oneGroupListsNoOthers() {
        val list = groupSheetList(listOf(group(1)), "gw-1", "")
        assertEquals("gw-1", list.current?.walletId)
        assertTrue(list.others.isEmpty())
    }

    @Test fun searchShowsOnlyPastAHandfulOfGroups() {
        assertFalse(showsGroupSearch((1..5).map { group(it) }))
        assertTrue(showsGroupSearch((1..6).map { group(it) }))
    }

    @Test fun theOwnerGetsFourTilesInTwoEqualRows() {
        val actions = groupSheetActions(state(group(1)), menu)
        assertEquals(listOf(GroupSheetActionKind.Pay, GroupSheetActionKind.SendToWallet, GroupSheetActionKind.Chat, GroupSheetActionKind.Info), actions.map { it.kind })
        assertEquals(listOf(2, 2), actions.chunked(2).map { it.size }, "a 2 × 2 grid, no ragged row")
        assertTrue(actions.take(2).all { it.money })
    }

    @Test fun aMemberWhoCannotSendGetsOnlyTheChatAndInfo() {
        val actions = groupSheetActions(state(group(1, canSign = false)), menu)
        assertEquals(listOf(GroupSheetActionKind.Chat, GroupSheetActionKind.Info), actions.map { it.kind })
        assertFalse(actions.any { it.money })
    }

    @Test fun aLockedOrUnfinishedWalletOffersNoMoneyActions() {
        assertEquals(listOf(GroupSheetActionKind.Chat, GroupSheetActionKind.Info), groupSheetActions(state(group(1), locked = setOf("gw-1")), menu).map { it.kind })
        assertEquals(listOf(GroupSheetActionKind.Chat, GroupSheetActionKind.Info), groupSheetActions(state(group(1, status = WalletStatus.SettingUp)), menu).map { it.kind })
    }

    @Test fun withoutTheMenuOrOnPersonalThereAreNoTiles() {
        assertTrue(groupSheetActions(state(group(1)), null).isEmpty())
        assertTrue(groupSheetActions(state(group(1)).copy(selectedWalletId = null), menu).isEmpty())
    }

    @Test fun aGroupsNetworksComeFromItsBalanceRichestFirstElseFromTheList() {
        val unread = group(1, nets = listOf("BASE-SEPOLIA", "MATIC-AMOY"))
        assertEquals(listOf("BASE-SEPOLIA", "MATIC-AMOY"), networksOf(unread, null))
        val read = wallet(chain("BASE-SEPOLIA", "1000000", "129.20"), chain("MATIC-AMOY", "50000000", "6460.00"))
        assertEquals(listOf("MATIC-AMOY", "BASE-SEPOLIA"), networksOf(unread, read))
    }

    @Test fun theTotalIsTheWholeWalletInThePersonsMoneyAndHides() {
        assertEquals("KES 37,723", walletTotal(wallet(), hidden = false))
        assertEquals("KES 128,450,373", walletTotal(wallet(fiat = fiat.copy(amount = "128450372.90")), hidden = false), "a large balance keeps every digit, grouped")
        assertEquals("291.97 USDC", walletTotal(wallet(fiat = null), hidden = false))
        assertEquals("••••", walletTotal(wallet(), hidden = true))
        assertNull(walletTotal(null, hidden = false), "not read yet: no number at all, never a zero")
    }

    @Test fun aNetworksValueIsItsOwnShare() {
        assertEquals("KES 6,460", networkValue(chain("MATIC-AMOY", "50000000", "6459.62"), fiat, hidden = false))
        assertEquals("50.00 USDC", networkValue(chain("MATIC-AMOY", "50000000", null), null, hidden = false))
        assertEquals("••••", networkValue(chain("MATIC-AMOY", "50000000", "6460.00"), fiat, hidden = true))
    }

    @Test fun offlineTheNoteNamesTheOldestBalanceOrSaysNoneIsRead() {
        assertEquals("Offline · balances show when you’re back online.", offlineNote(listOf(null, null)))
        val now = 1_762_600_000_000L
        val note = offlineNote(listOf(wallet().copy(fetchedAt = now - 60_000), null, wallet().copy(fetchedAt = now - 3 * 86_400_000L)), now)
        assertTrue(note.startsWith("Offline · balances as of "))
        assertFalse(note.contains(":"), "a balance read days ago is dated, not given a bare time: $note")
    }

    @Test fun aNetworkHoldingOnlyUnpricedCoinsIsNeverAFalseZero() {
        val eth = WalletChain("ETH-SEPOLIA", "0x1", "ACTIVE", listOf(Asset("ETH", "", 18, "120000000000000000", "0.12", null), Asset("USDC", "0xusdc", 6, "0", "", null)), true)
        assertEquals("1 coin", networkValue(eth, fiat, hidden = false))
        assertEquals("KES 0", networkValue(eth.copy(assets = emptyList()), fiat, hidden = false))
    }
}
