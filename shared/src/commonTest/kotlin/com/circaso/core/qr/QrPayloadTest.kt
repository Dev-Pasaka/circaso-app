package com.circaso.core.qr

import com.circaso.core.qr.QrPayload.Family
import com.circaso.core.qr.QrPayload.Scanned
import com.circaso.core.qr.QrPayload.Why
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrPayloadTest {
    private val evm = "0x7a3f0000000000000000000000000000001e4b00"
    private val usdcBase = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
    private val usdcBaseSepolia = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
    private val sol = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    @Test fun aCircasoCodeIsTheHandle() {
        assertEquals("circaso://pay/@pasaka", QrPayload.forHandle("@pasaka"))
        assertEquals(Scanned.Handle("pasaka"), QrPayload.read("circaso://pay/@Pasaka"))
        assertEquals(Scanned.Handle("amina_k"), QrPayload.read("@amina_k"))
        assertEquals(Scanned.Unknown, QrPayload.read("circaso://pay/@no spaces"))
    }

    @Test fun plainAddresses() {
        assertEquals(Scanned.Address(evm, family = Family.Evm), QrPayload.read(evm))
        assertEquals(Scanned.Address(evm, family = Family.Evm), QrPayload.read("  $evm\n"))
        assertEquals(Scanned.Address(sol, family = Family.Solana), QrPayload.read(sol))
    }

    @Test fun eip681TokenTransferGivesRecipientChainTokenAndAmount() {
        val r = QrPayload.read("ethereum:$usdcBase@8453/transfer?address=$evm&uint256=12500000")
        assertEquals(Scanned.Address(evm, "BASE", Family.Evm, 8453, token = usdcBase, amountUnits = "12500000"), r)
    }

    @Test fun eip681ScientificAmounts() {
        assertEquals("2014000", (QrPayload.read("ethereum:$usdcBase@8453/transfer?address=$evm&uint256=2.014e6") as Scanned.Address).amountUnits)
        assertEquals("1000000", (QrPayload.read("ethereum:$usdcBase@8453/transfer?address=$evm&uint256=1E6") as Scanned.Address).amountUnits)
        assertEquals("1000000", (QrPayload.read("ethereum:$usdcBase@8453/transfer?address=$evm&uint256=1e+6") as Scanned.Address).amountUnits)
        // A fraction left over after the exponent isn't a whole number of units: unreadable, never rounded.
        val frac = QrPayload.read("ethereum:$usdcBase@8453/transfer?address=$evm&uint256=1.0000005e6") as Scanned.Address
        assertNull(frac.amountUnits); assertTrue(frac.amountUnreadable)
        val junk = QrPayload.read("ethereum:$usdcBase@8453/transfer?address=$evm&uint256=-5") as Scanned.Address
        assertNull(junk.amountUnits); assertTrue(junk.amountUnreadable)
        // Zero is no amount at all.
        val zero = QrPayload.read("ethereum:$usdcBase@8453/transfer?address=$evm&uint256=0") as Scanned.Address
        assertNull(zero.amountUnits); assertFalse(zero.amountUnreadable)
    }

    @Test fun eip681TransferWithoutAmountOrChain() {
        assertEquals(Scanned.Address(evm, null, Family.Evm, null, token = usdcBaseSepolia), QrPayload.read("ethereum:$usdcBaseSepolia/transfer?address=$evm"))
        assertEquals(Scanned.Address(evm, "BASE-SEPOLIA", Family.Evm, 84532, token = usdcBaseSepolia), QrPayload.read("ethereum:$usdcBaseSepolia@84532/transfer?address=$evm"))
    }

    @Test fun eip681PayPrefixCaseAndEncoding() {
        assertEquals(Scanned.Address(evm, "MATIC", Family.Evm, 137, token = usdcBase, amountUnits = "5"), QrPayload.read("ETHEREUM:pay-$usdcBase@137/Transfer?Address=$evm&uint256=5"))
        // Percent-encoded values are decoded before reading.
        assertEquals("BASE", (QrPayload.read("ethereum:$usdcBase@8453/transfer?address=%30x7a3f0000000000000000000000000000001e4b00") as Scanned.Address).blockchain)
    }

    @Test fun eip681NativeTransfer() {
        assertEquals(Scanned.Address(evm, "BASE-SEPOLIA", Family.Evm, 84532), QrPayload.read("ethereum:$evm@84532"))
        assertEquals(Scanned.Address(evm, null, Family.Evm, null), QrPayload.read("ethereum:$evm"))
        // An amount of the chain's own coin is said, never turned into USDC.
        assertEquals(Scanned.Address(evm, "BASE-SEPOLIA", Family.Evm, 84532, nativeCoin = true), QrPayload.read("ethereum:$evm@84532?value=1e6"))
        assertEquals(Scanned.Address(evm, "ETH", Family.Evm, 1, nativeCoin = false), QrPayload.read("ethereum:$evm@1?value=0"))
    }

    @Test fun eip681AnUnknownChainIsKeptAsAnId() {
        assertEquals(Scanned.Address(evm, null, Family.Evm, 999999), QrPayload.read("ethereum:$evm@999999"))
        assertEquals(Scanned.Unknown, QrPayload.read("ethereum:$evm@base"))
    }

    @Test fun eip681ThingsThatArentPayments() {
        assertEquals(Scanned.Unsupported(Why.NotATransfer), QrPayload.read("ethereum:$usdcBase@8453/approve?address=$evm&uint256=1"))
        assertEquals(Scanned.Unsupported(Why.NoRecipient), QrPayload.read("ethereum:$usdcBase@8453/transfer?uint256=1"))
        assertEquals(Scanned.Unsupported(Why.Name), QrPayload.read("ethereum:alice.eth@1"))
        assertEquals(Scanned.Unsupported(Why.Name), QrPayload.read("ethereum:$usdcBase@1/transfer?address=alice.eth"))
        assertEquals(Scanned.Unknown, QrPayload.read("ethereum:0x123"))
    }

    @Test fun solanaPay() {
        assertEquals(Scanned.Address(sol, family = Family.Solana, token = usdcMint, amount = "12.5"), QrPayload.read("solana:$sol?amount=12.50&spl-token=$usdcMint&label=Shop&message=Thanks"))
        assertEquals(Scanned.Address(sol, family = Family.Solana, token = usdcMint), QrPayload.read("solana:$sol?spl-token=$usdcMint"))
        assertEquals(Scanned.Address(sol, family = Family.Solana), QrPayload.read("solana:$sol"))
        // SOL itself, with an amount: said, never used as USDC.
        assertEquals(Scanned.Address(sol, family = Family.Solana, nativeCoin = true), QrPayload.read("solana:$sol?amount=1"))
        assertEquals(Scanned.Address(sol, family = Family.Solana, token = usdcMint, amount = "0.5"), QrPayload.read("solana:$sol?amount=.5&spl-token=$usdcMint"))
        val bad = QrPayload.read("solana:$sol?amount=1e3&spl-token=$usdcMint") as Scanned.Address
        assertNull(bad.amount); assertTrue(bad.amountUnreadable)
        assertEquals(Scanned.Unsupported(Why.TransactionRequest), QrPayload.read("solana:https%3A%2F%2Fshop.example%2Fpay"))
        assertEquals(Scanned.Unsupported(Why.TransactionRequest), QrPayload.read("solana:https://shop.example/pay"))
        assertEquals(Scanned.Unknown, QrPayload.read("solana:$sol?spl-token=0xnope"))
        assertEquals(Scanned.Unknown, QrPayload.read("solana:$evm"))
    }

    @Test fun anythingElseIsUnknown() {
        assertEquals(Scanned.Unknown, QrPayload.read("https://example.com"))
        assertEquals(Scanned.Unknown, QrPayload.read("0x123"))
        assertEquals(Scanned.Unknown, QrPayload.read(""))
        assertEquals(Scanned.Unknown, QrPayload.read("bitcoin:bc1qxyz"))
    }

    @Test fun theReceiveCodeIsAPayLinkWhoseOnlyAddressIsTheRecipient() {
        assertEquals("https://links.circaso.com/pay/$evm?chain=BASE&asset=USDC", QrPayload.forReceive("BASE", evm))
        assertEquals("https://links.circaso.com/pay/$sol?chain=SOL-DEVNET&asset=USDC", QrPayload.forReceive("sol-devnet", sol))
        // Never the USDC contract, so an exchange taking the first 0x… from the code gets the recipient.
        assertEquals(listOf(evm), Regex("0x[0-9a-fA-F]{40}").findAll(QrPayload.forReceive("BASE", evm)).map { it.value }.toList())
        // A chain Circaso doesn't offer, or an address that doesn't check out: the bare address, never a link.
        assertEquals(evm, QrPayload.forReceive("ARC-TESTNET", evm))
        assertEquals(sol, QrPayload.forReceive("BASE", sol))
        assertEquals(evm, QrPayload.forReceive("SOL", evm))
    }

    @Test fun theReceiveCodeReadsBackToTheSameWallet() {
        assertEquals(Scanned.Address(evm, family = Family.Evm, chainName = "MATIC-AMOY", asset = "USDC"), QrPayload.read(QrPayload.forReceive("MATIC-AMOY", evm)))
        assertEquals(Scanned.Address(sol, family = Family.Solana, chainName = "SOL", asset = "USDC"), QrPayload.read(QrPayload.forReceive("SOL", sol)))
    }

    @Test fun payLinksAreReadOnCircasosHostsAndScheme() {
        assertEquals(Scanned.Address(evm, family = Family.Evm, chainName = "BASE", asset = "USDC", amount = "12.5"), QrPayload.read("https://links.circaso.com/pay/$evm?chain=base&asset=usdc&amount=12.50"))
        assertEquals(Scanned.Address(evm, family = Family.Evm, chainName = "BASE-SEPOLIA", asset = "USDC"), QrPayload.read("circaso://pay/$evm?chain=BASE-SEPOLIA&asset=USDC"))
        assertEquals(Scanned.Address(evm, family = Family.Evm, chainName = "BASE"), QrPayload.read("HTTPS://Links.Circaso.com/pay/$evm/?chain=BASE"))
        // No chain said: an address the person picks a network for, as a plain one.
        assertEquals(Scanned.Address(evm, family = Family.Evm), QrPayload.read("https://links.circaso.com/pay/$evm"))
        // Another host's /pay/ is not Circaso's: nothing read from it.
        assertEquals(Scanned.Unknown, QrPayload.read("https://evil.example/pay/$evm?chain=BASE&asset=USDC"))
        // The handle code stays the handle code.
        assertEquals(Scanned.Handle("pasaka"), QrPayload.read("circaso://pay/@pasaka"))
        // Amounts: too precise is kept for the resolver to say; junk is unreadable; zero is none.
        val junk = QrPayload.read("https://links.circaso.com/pay/$evm?chain=BASE&amount=1e3") as Scanned.Address
        assertNull(junk.amount); assertTrue(junk.amountUnreadable)
        val zero = QrPayload.read("https://links.circaso.com/pay/$evm?chain=BASE&amount=0") as Scanned.Address
        assertNull(zero.amount); assertFalse(zero.amountUnreadable)
        assertEquals(Scanned.Unknown, QrPayload.read("https://links.circaso.com/pay/$evm?chain=%3Cb%3E"))
    }

    @Test fun payLinksWithAnAddressThatDoesntCheckOutAreRefused() {
        val typo = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"
        assertEquals(Scanned.Unsupported(Why.BadAddress), QrPayload.read("https://links.circaso.com/pay/$typo?chain=BASE&asset=USDC"))
        assertEquals(Scanned.Unsupported(Why.BadAddress), QrPayload.read("https://links.circaso.com/pay/$sol?chain=BASE&asset=USDC"))
        assertEquals(Scanned.Unsupported(Why.BadAddress), QrPayload.read("https://links.circaso.com/pay/$evm?chain=SOL&asset=USDC"))
        assertEquals(Scanned.Unknown, QrPayload.read("https://links.circaso.com/pay/0x1234?chain=BASE"))
    }

    @Test fun checksumsAndSolanaKeys() {
        listOf(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb", usdcBase, usdcBaseSepolia,
        ).forEach { assertEquals(it, Addresses.checksummed(it.substring(2).lowercase())); assertTrue(Addresses.isValidEvm(it), it) }
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", Keccak256.hash(ByteArray(0)).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
        assertFalse(Addresses.isValidEvm("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"))
        assertFalse(Addresses.isValidEvm("0x" + "0".repeat(40)))
        assertTrue(Addresses.isValidEvm(evm.uppercase().replace("0X", "0x")))
        assertTrue(Addresses.isValidSolana(sol)); assertTrue(Addresses.isValidSolana(usdcMint))
        assertFalse(Addresses.isValidSolana("1".repeat(34)))
        // Everywhere else codes are read, a mistyped address is refused too.
        assertEquals(Scanned.Unsupported(Why.BadAddress), QrPayload.read("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"))
        assertEquals(Scanned.Unsupported(Why.BadAddress), QrPayload.read("ethereum:$usdcBase@8453/transfer?address=0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"))
        assertEquals(Scanned.Unsupported(Why.BadAddress), QrPayload.read("solana:${"1".repeat(34)}"))
    }

    @Test fun paymentUrisAreRecognisedForPaste() {
        assertTrue(QrPayload.isPaymentUri("ethereum:$evm"))
        assertTrue(QrPayload.isPaymentUri(" Solana:$sol"))
        assertFalse(QrPayload.isPaymentUri(evm))
        assertTrue(QrPayload.isPaymentUri("https://links.circaso.com/pay/$evm?chain=BASE&asset=USDC"))
        assertFalse(QrPayload.isPaymentUri("https://links.circaso.com/s/abcdefgh1234"))
    }

    @Test fun numbers() {
        assertEquals("0", QrPayload.integerOf("000"))
        assertEquals("123", QrPayload.integerOf("123"))
        assertEquals("1500", QrPayload.integerOf("1.5e3"))
        assertEquals("15", QrPayload.integerOf("1.50e1"))
        assertNull(QrPayload.integerOf("1.55e1"))
        assertNull(QrPayload.integerOf("0x10"))
        assertNull(QrPayload.integerOf("1e9999"))
        assertEquals("1", QrPayload.decimalOf("1.000"))
        assertEquals("0.01", QrPayload.decimalOf("00.01"))
        assertNull(QrPayload.decimalOf("1,5"))
    }
}
