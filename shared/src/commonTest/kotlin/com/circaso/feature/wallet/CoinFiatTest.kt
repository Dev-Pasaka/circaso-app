package com.circaso.feature.wallet

import com.circaso.feature.messaging.presentation.coinLine
import com.circaso.feature.messaging.presentation.heldCoins
import com.circaso.feature.wallet.data.remote.WalletResponse
import com.circaso.feature.wallet.data.repository.toDomain
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletStatus
import com.circaso.feature.wallet.domain.coinShort
import com.circaso.feature.wallet.domain.coinToFiat
import com.circaso.feature.wallet.domain.fiatToCoinMinor
import com.circaso.feature.wallet.domain.fiatToUsdcMinor
import com.circaso.feature.wallet.domain.multiplyDecimal
import com.circaso.feature.wallet.domain.unitFiatRate
import com.circaso.feature.wallet.domain.usdcToFiat
import com.circaso.feature.wallet.presentation.PayFee
import com.circaso.feature.wallet.presentation.PayUiState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The money card in the person's currency for any coin with a price (backend PR #48, 2026-11-06): "#500 @eth" is
 * KES 500 worth of ETH at the server's price, worked out on digit strings, never in floating point.
 */
class CoinFiatTest {
    private fun asset(symbol: String, minor: String, decimals: Int, usdPrice: String?) =
        Asset(symbol, "", decimals, minor, minor, null, usdPrice)

    private fun wallet(vararg assets: Asset, rate: String? = "129.25") = Wallet(
        WalletStatus.Active,
        listOf(WalletChain("BASE-SEPOLIA", "0x1", "ACTIVE", assets.toList(), true)),
        emptyList(),
        rate?.let { FiatValue("KES", "0", it, "2026-11-06T00:00:00Z", false) },
        null,
        0,
    )

    private val usdc = asset("USDC", "20000000", 6, null)
    private val eth = asset("ETH", "2000000000000000000", 18, "3500")

    @Test fun aCoinsLocalRateIsItsDollarPriceTimesTheWalletsRateExactly() {
        assertEquals("452375", multiplyDecimal("3500", "129.25"))
        assertEquals("0.0517", multiplyDecimal("0.0004", "129.25"))
        assertEquals("452375", unitFiatRate(eth, "129.25"))
        assertEquals("129.25", unitFiatRate(usdc, "129.25"), "a dollar coin is 1 even when the server sends no price")
        assertEquals("129.25", unitFiatRate(null, "129.25"), "no tag is USDC")
        assertNull(unitFiatRate(eth.copy(usdPrice = null), "129.25"), "no price, no local rate")
        assertNull(unitFiatRate(eth.copy(usdPrice = "0"), "129.25"))
        assertNull(unitFiatRate(eth.copy(usdPrice = "1e3"), "129.25"), "only plain decimals")
        assertNull(unitFiatRate(eth, null))
    }

    @Test fun fiatTurnsIntoTheCoinsMinorUnitsRoundedDownAndBackToTheSameCents() {
        assertEquals("1105277701022381", fiatToCoinMinor("500", "452375", 18), "KES 500 of ETH, 18 decimals, rounded down")
        assertEquals("500.00", coinToFiat("1105277701022381", 18, "452375"))
        assertEquals("4.52", coinToFiat("10000000000000", 18, "452375"), "0.00001 ETH, to the nearest cent")
        assertNull(fiatToCoinMinor("five", "452375", 18))
        assertNull(fiatToCoinMinor("500", "0", 18))
        // The general helpers agree with the USDC ones they extend.
        assertEquals(fiatToUsdcMinor("500", "129.25"), fiatToCoinMinor("500", "129.25", 6))
        assertEquals(usdcToFiat("3868471", "129.25"), coinToFiat("3868471", 6, "129.25"))
        assertEquals("1500.00", coinToFiat("1", 0, "1500"), "a coin with no decimals")
    }

    @Test fun hashFiveHundredAtEthStaysKesFiveHundredWithTheCoinUnderIt() {
        val s = PayUiState(wallet = wallet(usdc, eth), amountText = "500", inFiat = true, assetTag = "eth")

        assertEquals("ETH", s.asset)
        assertTrue(s.canUseFiat)
        assertEquals("1105277701022381", s.amountMinor)
        assertEquals("KES 500", s.fiatText, "as typed, in the person's currency")
        assertEquals("0.001105 ETH", coinLine(s), "the coin as the sub-line")
        assertEquals("KES 500", s.totalText)

        val fee = s.copy(fee = PayFee.Quoted("10000000000000", 50))
        assertEquals("KES 4.52", fee.feeShort, "the fee in fiat, the fee coin being priced")
        assertEquals("KES 504.52", fee.totalText)
    }

    @Test fun typingInTheCoinStillWorksAndShowsItsWorth() {
        val s = PayUiState(wallet = wallet(usdc, eth), amountText = "0.01", inFiat = false, assetTag = "eth")
        assertEquals("10000000000000000", s.amountMinor)
        assertEquals("KES 4,523.75", s.fiatText)
        assertEquals("0.01 ETH", s.usdcText)
    }

    @Test fun aCoinWithNoPriceIsTypedInItsOwnUnitsAsBefore() {
        val s = PayUiState(wallet = wallet(usdc, eth.copy(usdPrice = null)), amountText = "0.5", inFiat = true, assetTag = "eth")
        assertFalse(s.canUseFiat)
        assertEquals("500000000000000000", s.amountMinor)
        assertNull(s.fiatText)
        assertNull(coinLine(s))
        assertEquals("0.50 ETH", s.totalText)
        assertEquals("0.50 ETH", s.moneyText("500000000000000000"))
    }

    @Test fun usdcIsUnchanged() {
        val s = PayUiState(wallet = wallet(usdc, eth), amountText = "500", inFiat = true)
        assertEquals("3868471", s.amountMinor)
        assertEquals("KES 500", s.fiatText)
        assertEquals("3.87 USDC", coinLine(s))
    }

    @Test fun coinsReadShortOnTheCard() {
        assertEquals("3.87 USDC", coinShort("3868471", 6, "USDC"))
        assertEquals("0.001105 ETH", coinShort("1105277701022381", 18, "ETH"))
        assertEquals("0.001105 ETH", coinShort("1105977701022381", 18, "ETH"), "another coin rounds down: never more than is sent")
        assertEquals("0.001106 ETH", coinShort("1105577701022381", 18, "ETH", roundDown = false))
        assertEquals("1.234567 SOL", coinShort("1234567891", 9, "SOL"))
        assertEquals("2 ETH", coinShort("2000000000000000000", 18, "ETH"))
        assertEquals("0.00001 BTC", coinShort("1000", 8, "BTC"))
    }

    @Test fun aTypedAmountKeepsItsUnitWhenThePriceGoes() {
        val typed = PayUiState(wallet = wallet(usdc, eth.copy(usdPrice = null)), amountText = "500", inFiat = true, assetTag = "eth", typedInFiat = true)
        assertNull(typed.amountMinor, "KES 500 never becomes 500 ETH")
        assertTrue(typed.priceWaiting)
        assertEquals("KES 500", typed.fiatText)
        assertFalse(typed.coinPriced, "no price at all: the card says there is none right now")

        val old = PayUiState(wallet = wallet(usdc, eth), amountText = "500", inFiat = true, assetTag = "eth", pricesFresh = false)
        assertNull(old.amountMinor, "an old price isn't used to work out the coin")
        assertTrue(old.coinPriced && old.priceWaiting, "\"Getting the latest ETH price…\"")
        val usdcOld = old.copy(assetTag = null)
        assertEquals("3868471", usdcOld.amountMinor, "a dollar coin needs no market price")
    }

    @Test fun theBalanceReadsInMoneyAndCoinRoundedDown() {
        val s = PayUiState(wallet = wallet(usdc, eth.copy(amountMinor = "12345678901234567")), assetTag = "eth")
        assertEquals("KES 5,584.87 (0.01234 ETH)", s.balanceText)
        assertEquals("0.00", com.circaso.feature.wallet.presentation.formatRate("0.00"))
        assertEquals("0.001296", com.circaso.feature.wallet.presentation.formatRate("0.00129625"), "a cheap coin never reads 0.00")
        assertEquals("129.25", com.circaso.feature.wallet.presentation.formatRate("129.25"))
    }

    @Test fun heldCoinsRankPricedCoinsByWhatTheyAreWorth() {
        val btc = Asset("BTC", "", 8, "50000000", "0.5", null, "100000")
        val doge = Asset("DOGE", "", 8, "100000000000", "1000", null, null)
        val coins = heldCoins(wallet(usdc, btc, doge, eth.copy(amountMinor = "0")))
        assertEquals(listOf("BTC", "USDC", "DOGE"), coins.map { it.symbol }, "0.5 BTC outweighs 20 USDC; unpriced last; empty left out")
        assertEquals("5000000", coins[0].usdCents)
        assertNull(coins[2].usdCents)
    }

    @Test fun anOlderCachedWalletWithoutPricesStillReads() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"status":"ACTIVE","chains":[{"blockchain":"BASE","address":"0x1","state":"ACTIVE","assets":[""" +
            """{"symbol":"ETH","decimals":18,"amountMinor":"1","amount":"0.000000000000000001"}]}],""" +
            """"totals":[{"symbol":"ETH","decimals":18,"amountMinor":"1","amount":"0.000000000000000001"}]}"""
        val read = json.decodeFromString(WalletResponse.serializer(), old).toDomain(0)
        assertNull(read.chains.single().assets.single().usdPrice)

        val priced = old.replace(""""amount":"0.000000000000000001"}]}]""", """"amount":"0.000000000000000001","usdPrice":"3512.27"}]}]""")
        val asset = json.decodeFromString(WalletResponse.serializer(), priced).toDomain(0).chains.single().assets.single()
        assertEquals("3512.27", asset.usdPrice)
        assertEquals("3512.27", asset.dollarPrice)
    }
}
