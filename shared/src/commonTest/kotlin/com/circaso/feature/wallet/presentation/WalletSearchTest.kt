package com.circaso.feature.wallet.presentation

import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.WalletChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletSearchTest {
    private val toAmina = Transfer(
        transferId = "t1", direction = TransferDirection.Out, state = TransferState.Confirmed, blockchain = "BASE-SEPOLIA",
        from = "0xmine", toUserId = "u-amina", toAddress = "0xa11ce", amount = Amount("USDC", "25500000", "25.5"), fee = null,
        chatId = null, txHash = "0xhash", failureReason = null, counterpartName = "Amina Wanjiru", note = "lunch",
        createdAt = 0L, updatedAt = 0L, counterpartHandle = "amina",
    )

    @Test
    fun `a payment is found by name, handle, note, coin, network or amount`() {
        listOf("amina", "@amina", "WANJIRU", "lunch", "usdc", "base", "25.5", "0xa11").forEach { q ->
            assertTrue(toAmina.matches(q), q)
        }
        assertTrue(toAmina.matches("  "))
        assertFalse(toAmina.matches("bob"))
    }

    @Test
    fun `assets list the most valuable first, then whatever is held over nothing`() {
        val chain = WalletChain(
            "ETH-SEPOLIA", "0xabc", "LIVE",
            listOf(
                Asset("DAI", "", 18, "0", "0", null),
                Asset("ETH", "", 18, "1000000000000000", "0.001", "4.20"),
                Asset("USDC", "", 6, "30000000", "30", "30.00"),
                Asset("LINK", "", 18, "5", "0.000000000000000005", null),
            ),
            receiveEnabled = true,
        )

        assertEquals(listOf("USDC", "ETH", "LINK", "DAI"), assetsByValue(chain).map { it.symbol })
    }
}
