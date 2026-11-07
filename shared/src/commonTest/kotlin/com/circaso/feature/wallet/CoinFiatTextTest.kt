package com.circaso.feature.wallet

import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.domain.coinFiatText
import com.circaso.feature.wallet.domain.coinUnitRates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A card or preview in any coin reads in the person's money, formatted as USDC's (Pasaka, 2026-11-07). */
class CoinFiatTextTest {
    private val kes = FiatValue("KES", "0", "129.25", "2026-11-07", false)

    @Test fun eurcReadsInTheirMoneyLikeUsdc() {
        val wallet = Wallet(
            WalletStatus.Active,
            listOf(WalletChain("BASE-SEPOLIA", "0x1", "LIVE", listOf(Asset("EURC", "0xe", 6, "20000000", "20", null, usdPrice = "1.12")), true)),
            totals = emptyList(), fiat = kes, holdUntil = null, fetchedAt = 0L,
        )
        val rates = coinUnitRates(wallet)
        // 8.29 EURC × 1.12 × 129.25 = 1,200.04 → "KES 1,200", as a USDC card rounds it.
        assertEquals("KES 1,200", coinFiatText("8290000", "EURC", kes, rates))
        assertEquals("KES 1,000", coinFiatText("7736944", "USDC", kes, rates), "USDC unchanged")
    }

    @Test fun aCoinWithNoPriceHasNoFiatText() {
        assertNull(coinFiatText("1000000", "XYZ", kes, emptyMap()))
        assertNull(coinFiatText("1000000", "USDC", null, emptyMap()), "no rate yet")
    }
}
