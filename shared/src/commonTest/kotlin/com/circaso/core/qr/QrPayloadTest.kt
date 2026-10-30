package com.circaso.core.qr

import com.circaso.core.qr.QrPayload.Scanned
import kotlin.test.Test
import kotlin.test.assertEquals

class QrPayloadTest {
    @Test fun aCircasoCodeIsTheHandle() {
        assertEquals("circaso://pay/@pasaka", QrPayload.forHandle("@pasaka"))
        assertEquals(Scanned.Handle("pasaka"), QrPayload.read("circaso://pay/@Pasaka"))
        assertEquals(Scanned.Handle("amina_k"), QrPayload.read("@amina_k"))
        assertEquals(Scanned.Unknown, QrPayload.read("circaso://pay/@no spaces"))
    }

    @Test fun walletCodesGiveTheAddressOnly() {
        val evm = "0x7a3f0000000000000000000000000000001e4b00"
        assertEquals(Scanned.Address(evm), QrPayload.read(evm))
        assertEquals(Scanned.Address(evm, "BASE-SEPOLIA"), QrPayload.read("ethereum:$evm@84532?value=1e6"))
        assertEquals(Scanned.Address(evm, null), QrPayload.read("ethereum:$evm@999999"))
        val sol = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        assertEquals(Scanned.Address(sol, "SOL"), QrPayload.read("solana:$sol?amount=1"))
        assertEquals(Scanned.Address(sol, null), QrPayload.read(sol))
    }

    @Test fun anythingElseIsUnknown() {
        assertEquals(Scanned.Unknown, QrPayload.read("https://example.com"))
        assertEquals(Scanned.Unknown, QrPayload.read("0x123"))
    }
}
