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

    @Test fun theReceiveCodeIsAUsdcRequest() {
        assertEquals("ethereum:$usdcBase@8453/transfer?address=$evm", QrPayload.forUsdcRequest("BASE", evm, usdcBase))
        assertEquals("ethereum:$usdcBaseSepolia@84532/transfer?address=$evm", QrPayload.forUsdcRequest("BASE-SEPOLIA", evm, usdcBaseSepolia))
        assertEquals("solana:$sol?spl-token=$usdcMint", QrPayload.forUsdcRequest("SOL", sol, usdcMint))
        // Without a known chain or contract: the bare address, which every wallet reads.
        assertEquals(evm, QrPayload.forUsdcRequest("BASE", evm, null))
        assertEquals(evm, QrPayload.forUsdcRequest("ARC-TESTNET", evm, usdcBase))
    }

    @Test fun theReceiveCodeReadsBackToTheSameWallet() {
        val r = QrPayload.read(QrPayload.forUsdcRequest("MATIC-AMOY", evm, "0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582")) as Scanned.Address
        assertEquals(evm, r.address); assertEquals("MATIC-AMOY", r.blockchain); assertEquals("0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582", r.token)
        val s = QrPayload.read(QrPayload.forUsdcRequest("SOL", sol, usdcMint)) as Scanned.Address
        assertEquals(sol, s.address); assertEquals(usdcMint, s.token)
    }

    @Test fun paymentUrisAreRecognisedForPaste() {
        assertTrue(QrPayload.isPaymentUri("ethereum:$evm"))
        assertTrue(QrPayload.isPaymentUri(" Solana:$sol"))
        assertFalse(QrPayload.isPaymentUri(evm))
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
