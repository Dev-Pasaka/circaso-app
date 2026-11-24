package com.circaso.feature.wallet

import com.circaso.core.qr.QrPayload
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.ScanFill
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.resolveScan
import com.circaso.feature.wallet.domain.unitsToText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanResolveTest {
    private val to = "0x7a3f0000000000000000000000000000001e4b00"
    private val sol = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val usdcBase = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
    private val usdcBaseSepolia = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
    private val usdcAmoy = "0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582"
    private val usdcSol = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val usdcSolDevnet = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"

    private fun chain(name: String, token: String, state: String = "ACTIVE") =
        WalletChain(name, if (name.startsWith("SOL")) sol else to, state, listOf(Asset("USDC", token, 6, "0", "0", null)), true)

    private val testnet = listOf(chain("BASE-SEPOLIA", usdcBaseSepolia), chain("MATIC-AMOY", usdcAmoy), chain("SOL-DEVNET", usdcSolDevnet))
    private val mainnet = listOf(chain("BASE", usdcBase), chain("SOL", usdcSol))

    private fun resolve(text: String, chains: List<WalletChain> = testnet, network: WalletNetwork = WalletNetwork.TESTNET, whose: String = "your wallet") =
        resolveScan(QrPayload.read(text), chains, network, whose)

    private fun refusal(text: String, chains: List<WalletChain> = testnet, network: WalletNetwork = WalletNetwork.TESTNET, whose: String = "your wallet"): String =
        assertIs<ScanFill.Refuse>(resolve(text, chains, network, whose)).message

    @Test fun aUsdcRequestPreselectsEverything() {
        assertEquals(ScanFill.Fill(to, "BASE-SEPOLIA", "12.5"), resolve("ethereum:$usdcBaseSepolia@84532/transfer?address=$to&uint256=12500000"))
        assertEquals(ScanFill.Fill(to, "BASE", "0.000001"), resolve("ethereum:$usdcBase@8453/transfer?address=$to&uint256=1", mainnet, WalletNetwork.MAINNET))
        assertEquals(ScanFill.Fill(to, "MATIC-AMOY", null), resolve("ethereum:$usdcAmoy@80002/transfer?address=$to"))
    }

    @Test fun theTokenNamesTheChainWhenTheCodeDoesnt() {
        assertEquals(ScanFill.Fill(to, "MATIC-AMOY", "3"), resolve("ethereum:$usdcAmoy/transfer?address=$to&uint256=3e6"))
        // Lower-case contract, as some wallets write it.
        assertEquals(ScanFill.Fill(to, "BASE-SEPOLIA", null), resolve("ethereum:${usdcBaseSepolia.lowercase()}/transfer?address=$to"))
    }

    @Test fun aNativeTransferPreselectsTheChainAndTheAddressOnly() {
        assertEquals(ScanFill.Fill(to, "BASE-SEPOLIA"), resolve("ethereum:$to@84532"))
        val asked = assertIs<ScanFill.Fill>(resolve("ethereum:$to@84532?value=1e18"))
        assertEquals("BASE-SEPOLIA", asked.blockchain); assertNull(asked.amountText)
        assertTrue(asked.notice!!.contains("ETH, not USDC"))
        assertTrue(assertIs<ScanFill.Fill>(resolve("ethereum:$to@80002?value=5")).notice!!.contains("POL"))
    }

    @Test fun aPlainAddressLeavesTheChainToThePerson() {
        assertEquals(ScanFill.Fill(to, null), resolve(to))
        assertEquals(ScanFill.Fill(to, null), resolve("ethereum:$to"))
    }

    @Test fun solanaAddressesAndSolanaPay() {
        assertEquals(ScanFill.Fill(sol, "SOL-DEVNET"), resolve(sol))
        assertEquals(ScanFill.Fill(sol, "SOL", "2.25"), resolve("solana:$sol?amount=2.25&spl-token=$usdcSol", mainnet, WalletNetwork.MAINNET))
        assertEquals(ScanFill.Fill(sol, "SOL-DEVNET"), resolve("solana:$sol?spl-token=$usdcSolDevnet"))
        val native = assertIs<ScanFill.Fill>(resolve("solana:$sol?amount=1"))
        assertEquals("SOL-DEVNET", native.blockchain); assertNull(native.amountText); assertTrue(native.notice!!.contains("SOL, not USDC"))
        assertTrue(refusal(sol, chains = testnet.filter { !it.blockchain.startsWith("SOL") }).contains("isn't on Solana"))
    }

    @Test fun theOtherNetworksChainIsRefusedWithWhatToDo() {
        val m = refusal("ethereum:$usdcBaseSepolia@84532/transfer?address=$to&uint256=1", mainnet, WalletNetwork.MAINNET)
        assertEquals("That code is for USDC on Base Testnet. This is your wallet on Mainnet: switch to Testnet on the Wallet tab to pay it.", m)
        assertTrue(refusal("ethereum:$to@8453").contains("switch to Mainnet"))
        assertTrue(refusal("solana:$sol?spl-token=$usdcSol").contains("switch to Mainnet"))
    }

    @Test fun aChainTheWalletIsntOnIsRefused() {
        assertEquals(
            "That code is for USDC on Ethereum (testnet), which Kilimani Chama’s wallet isn't on. Add Ethereum under Wallet → Networks first.",
            refusal("ethereum:0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238@11155111/transfer?address=$to", whose = "Kilimani Chama’s wallet"),
        )
        // A chain still being added isn't one to pay from yet.
        assertTrue(refusal("ethereum:$to@80002", chains = listOf(chain("BASE-SEPOLIA", usdcBaseSepolia), chain("MATIC-AMOY", usdcAmoy, state = "PENDING"))).contains("isn't on"))
    }

    @Test fun chainsCircasoDoesntOfferAreSaidByName() {
        assertTrue(refusal("ethereum:$to@42161", mainnet, WalletNetwork.MAINNET).contains("Arbitrum, which Circaso doesn't support"))
        assertTrue(refusal("ethereum:$to@999999").contains("chain 999999"))
    }

    @Test fun aTokenOtherThanUsdcIsRefused() {
        val usdt = "0xdAC17F958D2ee523a2206206994597C13D831ec7"
        assertTrue(refusal("ethereum:$usdt@84532/transfer?address=$to&uint256=1").contains("other than USDC"))
        assertTrue(refusal("ethereum:$usdt/transfer?address=$to").contains("other than USDC"))
        // Base's USDC named with Polygon's chain id: the contract isn't USDC there.
        assertTrue(refusal("ethereum:$usdcBaseSepolia@80002/transfer?address=$to").contains("other than USDC"))
        assertTrue(refusal("solana:$sol?spl-token=Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB&amount=1").contains("other than USDC"))
    }

    @Test fun anUnreadableAmountFillsTheRestAndSaysSo() {
        val f = assertIs<ScanFill.Fill>(resolve("ethereum:$usdcBaseSepolia@84532/transfer?address=$to&uint256=1.5"))
        assertEquals("BASE-SEPOLIA", f.blockchain); assertNull(f.amountText); assertTrue(f.notice!!.contains("couldn't be read"))
        val p = assertIs<ScanFill.Fill>(resolve("solana:$sol?amount=0.0000001&spl-token=$usdcSolDevnet"))
        assertNull(p.amountText); assertTrue(p.notice!!.contains("couldn't be read"))
    }

    @Test fun codesThatArentWalletsSayWhat() {
        assertTrue(refusal("circaso://pay/@amina").contains("@amina"))
        assertEquals("That code isn't a wallet address.", refusal("https://example.com"))
        assertTrue(refusal("ethereum:$usdcBase@8453/approve?address=$to&uint256=1").contains("other than pay"))
        assertTrue(refusal("ethereum:vitalik.eth").contains("wallet name"))
        assertTrue(refusal("solana:https://shop.example/pay").contains("Solana Pay"))
    }

    @Test fun anEvmAddressForASolanaOnlyWallet() {
        assertTrue(refusal(to, chains = listOf(chain("SOL-DEVNET", usdcSolDevnet))).contains("only on Solana"))
    }

    @Test fun unitsAsTyped() {
        assertEquals("0", unitsToText("0", 6))
        assertEquals("1", unitsToText("1000000", 6))
        assertEquals("1234.56", unitsToText("1234560000", 6))
        assertEquals("0.000001", unitsToText("000001", 6))
        assertEquals("42", unitsToText("42", 0))
    }
}
