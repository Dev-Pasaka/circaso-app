package com.circaso.feature.messaging

import com.circaso.feature.messaging.presentation.afterTypedAmount
import com.circaso.feature.messaging.presentation.amountShortcut
import com.circaso.feature.messaging.presentation.coinQuery
import com.circaso.feature.messaging.presentation.splitAssetTag
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
        assertEquals("5", typingAmount("pay #5"), "anywhere in the message, not only at the start")
        assertEquals("12.5", typingAmount("for lunch #12.5 @eth thanks"))
        assertEquals("12.5", typingAmount("a #12.5 @eth"))
        assertEquals("5", typingAmount("room#5"), "no space before it needed (2026-11-09)")
        assertEquals("500", typingAmount("lunch#500"))
        assertEquals("50", typingAmount("pay#50 for lunch"))
        assertEquals("500", typingAmount("#500"))
        assertNull(typingAmount("#5x"), "not part of a longer word")
        assertNull(typingAmount("#12.5kg"))
    }

    @Test fun aLinksAnchorIsNeverMoney() {
        assertNull(typingAmount("see https://circaso.com/help#12"))
        assertNull(typingAmount("circaso.com#12"))
        assertNull(typingAmount("www.example.org/#3 "))
        assertNull(typingAmount("docs/page#2"))
        assertNull(typingAmount("&#123;"), "an HTML entity")
        assertNull(amountShortcut("https://circaso.com/help#12 "))
        assertEquals("5", typingAmount("https://circaso.com/help#12 and #5"), "the link stays text, the amount after it counts")
    }

    @Test fun theAmountCompletesWhereverItIsTyped() {
        assertEquals("500" to "", amountShortcut("#500 "))
        assertEquals("500" to "lunch", amountShortcut("lunch #500 "), "the words before it stay as the note")
        assertEquals("500" to "lunch", amountShortcut("lunch#500 "), "straight after a word too")
        assertEquals("500" to "lunch @eurc", amountShortcut("lunch @eurc #500 "), "a coin picked before the amount stays")
        assertNull(amountShortcut("lunch #500"), "still being typed")
        assertNull(amountShortcut("#500"))
        assertNull(amountShortcut("#0 "), "zero isn't a payment")
        assertEquals("lunch @eurc thanks", afterTypedAmount("lunch #500 @eurc thanks"))
        assertEquals("lunch", afterTypedAmount("lunch#500 "))
        assertEquals("a @eth", afterTypedAmount("a #12.5 @eth"))
        assertEquals("eth" to "a", splitAssetTag(afterTypedAmount("a #12.5 @eth")))
        assertEquals("eurc" to "lunch thanks", splitAssetTag(afterTypedAmount("lunch #500 @eurc thanks")))
    }

    @Test fun theCoinBeingTypedIsReadAfterTheAt() {
        assertEquals("", coinQuery("@"))
        assertEquals("us", coinQuery("for lunch @us"))
        assertNull(coinQuery("for lunch"))
        assertNull(coinQuery("@usdc for lunch"), "already picked, the note goes on")
        assertNull(coinQuery("mail@x"), "not an email")
        assertEquals("eu", coinQuery("lunch with Amina @eu"), "in the middle of a sentence")
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
