package com.circaso.feature.wallet.presentation

import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.WalletChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A card's value is the sum of its priced assets, exact to the cent. */
class WalletCardsTest {
    private val kes = FiatValue("KES", "0", "129.25", "2026-10-30T00:00:00Z", partial = false)
    private fun chain(vararg assets: Asset) = WalletChain("BASE-SEPOLIA", "0xabc", "ACTIVE", assets.toList(), receiveEnabled = true)

    @Test fun centsAreExact() {
        assertEquals(123450L, centsOf("1234.5"))
        assertEquals(123456L, centsOf("1,234.56"))
        assertEquals(1L, centsOf("0.01"))
        assertNull(centsOf("abc"))
    }

    @Test fun aCardAddsItsPricedAssetsAndLeavesOutUnpricedOnes() {
        val c = chain(Asset("USDC", "0x1", 6, "20000000", "20", "2585.00"), Asset("ETH", "", 18, "1", "0.000000000000000001", null), Asset("EURC", "0x2", 6, "1000000", "1", "140.10"))
        assertEquals(com.circaso.feature.wallet.domain.formatFiat("KES", "2725.10"), chainFiat(c, kes))
    }

    private fun on(name: String, vararg assets: Asset) = WalletChain(name, "0x$name", "ACTIVE", assets.toList(), receiveEnabled = true)

    @Test fun theRichestNetworkComesFirst() {
        val base = on("BASE", Asset("USDC", "0x1", 6, "1000000", "1", "129.25"))
        val polygon = on("MATIC", Asset("USDC", "0x2", 6, "5000000", "5", "646.25"))
        val solana = on("SOL")
        assertEquals(listOf("MATIC", "BASE", "SOL"), byValue(listOf(base, solana, polygon), kes).map { it.blockchain })
    }

    @Test fun withoutARateOnlyUsdcIsCompared() {
        // 3 ETH is not "3" of anything comparable to USDC: with no prices, USDC decides.
        val eth = on("ETH", Asset("ETH", "", 18, "3000000000000000000", "3", null))
        val base = on("BASE", Asset("USDC", "0x1", 6, "2000000", "2", null))
        assertEquals(listOf("BASE", "ETH"), byValue(listOf(eth, base), null).map { it.blockchain })
    }

    @Test fun nothingPricedMeansNoCurrencyValue() {
        assertNull(chainFiat(chain(Asset("ETH", "", 18, "1", "0", null)), kes))
        assertNull(chainFiat(chain(Asset("USDC", "0x1", 6, "1", "0", "1.00")), null))
    }
}
