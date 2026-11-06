package com.circaso.designsystem

import com.circaso.designsystem.coins.CoinSymbols
import com.circaso.designsystem.components.coinIconUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Coin icons (decision 2026-11-06): how a symbol becomes the key the bundle and the server know. */
class CoinSymbolsTest {
    @Test
    fun symbolsAreUpperCasedAndTrimmed() {
        assertEquals("USDC", CoinSymbols.normalise("usdc"))
        assertEquals("USDC", CoinSymbols.normalise(" Usdc "))
        assertEquals("USDC", CoinSymbols.normalise("@usdc"))
        assertEquals("EURC", CoinSymbols.normalise("EURC"))
    }

    @Test
    fun wrappedBridgedAndRenamedCoinsWearTheirOriginalsMark() {
        assertEquals("ETH", CoinSymbols.normalise("WETH"))
        assertEquals("ETH", CoinSymbols.normalise("weth"))
        assertEquals("POL", CoinSymbols.normalise("MATIC"))
        assertEquals("POL", CoinSymbols.normalise("matic"))
        assertEquals("BTC", CoinSymbols.normalise("cbBTC"))
        assertEquals("USDC", CoinSymbols.normalise("USDC.e"))
        // WBTC has its own mark in the set.
        assertEquals("WBTC", CoinSymbols.normalise("wbtc"))
    }

    @Test
    fun anythingOutsideTheServersAllowListIsNoKey() {
        assertNull(CoinSymbols.normalise(""))
        assertNull(CoinSymbols.normalise("USD₮"))
        assertNull(CoinSymbols.normalise("../etc"))
        assertNull(CoinSymbols.normalise("US DC"))
        assertNull(CoinSymbols.normalise("A".repeat(16)))
        assertEquals("A".repeat(15), CoinSymbols.normalise("a".repeat(15)))
    }

    @Test
    fun theBundledListIsThere() {
        listOf("USDC", "USDT", "EURC", "ETH", "POL", "SOL", "BTC", "WBTC", "AVAX").forEach { assertTrue(CoinSymbols.isBundled(it), it) }
        listOf("WETH", "MATIC", "cbBTC").forEach { assertTrue(CoinSymbols.isBundled(CoinSymbols.normalise(it)!!), it) }
        assertFalse(CoinSymbols.isBundled("DOGE"))
    }

    @Test
    fun networksFollowCirclesBlockchainIds() {
        assertEquals("base", CoinSymbols.network("BASE-SEPOLIA"))
        assertEquals("base", CoinSymbols.network("base"))
        assertEquals("ethereum", CoinSymbols.network("ETH"))
        assertEquals("ethereum", CoinSymbols.network("ETH-SEPOLIA"))
        assertEquals("polygon", CoinSymbols.network("MATIC-AMOY"))
        assertEquals("solana", CoinSymbols.network("SOL-DEVNET"))
        assertEquals("avalanche", CoinSymbols.network("AVAX-FUJI"))
        assertEquals("arbitrum", CoinSymbols.network("ARB-SEPOLIA"))
        assertEquals("arc", CoinSymbols.network("ARC-TESTNET"))
        assertNull(CoinSymbols.network("NEAR"))
    }

    @Test
    fun theServerUrlIsThePublicIconRoute() {
        assertEquals("https://sandbox.circaso.com/v1/assets/icons/DOGE", coinIconUrl("https://sandbox.circaso.com/", "DOGE"))
    }
}
