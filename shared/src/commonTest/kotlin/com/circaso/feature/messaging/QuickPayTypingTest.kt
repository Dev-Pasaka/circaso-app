package com.circaso.feature.messaging

import com.circaso.feature.messaging.presentation.coinQuery
import com.circaso.feature.messaging.presentation.heldCoins
import com.circaso.feature.messaging.presentation.typingAmount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The composer's money shortcuts while they are typed: "#5" shows the preview, "@us" narrows the coins. */
class QuickPayTypingTest {
    @Test fun theAmountShowsAsSoonAsADigitIsTyped() {
        assertEquals("5", typingAmount("#5"))
        assertEquals("12", typingAmount("#12."))
        assertEquals("12.5", typingAmount("#12.5"))
        assertNull(typingAmount("#"), "nothing to pay yet")
        assertNull(typingAmount("#0"), "zero isn't a payment")
        assertEquals("5", typingAmount("#5 @usdc for lunch"), "the chip turned back into text keeps its preview")
        assertNull(typingAmount("pay #5"), "only at the start of the message")
    }

    @Test fun theCoinBeingTypedIsReadAfterTheAt() {
        assertEquals("", coinQuery("@"))
        assertEquals("us", coinQuery("for lunch @us"))
        assertNull(coinQuery("for lunch"))
        assertNull(coinQuery("@usdc for lunch"), "already picked, the note goes on")
        assertNull(coinQuery("mail@x"), "not an email")
    }

    @Test fun heldCoinsComeMostValuableFirstAndEmptyOnesAreLeftOut() {
        fun a(symbol: String, minor: String, amount: String, fiat: String?) = Asset(symbol, "", 6, minor, amount, fiat)
        val wallet = Wallet(
            WalletStatus.Active,
            listOf(
                WalletChain("BASE-SEPOLIA", "0x1", "LIVE", listOf(a("USDC", "5000000", "5", "650"), a("EURC", "0", "0", null)), true),
                WalletChain("ETH-SEPOLIA", "0x2", "LIVE", listOf(a("USDC", "20000000", "20", "2600"), a("CIRBTC", "50000000", "0.5", null)), true),
            ),
            emptyList(), null, null, 0,
        )
        val coins = heldCoins(wallet)
        assertEquals(listOf("USDC", "CIRBTC"), coins.map { it.symbol })
        assertEquals("25.00", coins[0].amount, "USDC over both networks, summed exactly")
    }
}
