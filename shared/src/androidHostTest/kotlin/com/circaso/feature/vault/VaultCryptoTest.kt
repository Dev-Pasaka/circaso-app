package com.circaso.feature.vault

import com.circaso.core.crypto.AndroidSignalCrypto
import com.circaso.feature.vault.data.Hkdf
import com.circaso.feature.vault.data.VaultCrypto
import com.circaso.feature.vault.domain.SecurityAnswer
import com.circaso.feature.vault.domain.VaultContents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The PIN Vault's sealing: HKDF matches RFC 5869, and a vault opens only with the same passkey secret and person. */
class VaultCryptoTest {
    private val crypto = AndroidSignalCrypto()
    private val vault = VaultCrypto(crypto)

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun `HKDF-SHA256 matches RFC 5869 test case 1`() {
        val okm = Hkdf.sha256(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
            sha256 = crypto::sha256,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    @Test
    fun `HMAC-SHA256 matches RFC 4231 test case 2`() {
        val mac = Hkdf.hmac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray(), crypto::sha256)
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.hex())
    }

    @Test
    fun `a vault opens with the same secret and person, and only then`() {
        val secret = crypto.randomBytes(32)
        val contents = VaultContents("482915", listOf(SecurityAnswer("First school?", "Moi Avenue")))
        val sealed = vault.seal(secret, "user-1", contents)

        assertEquals(contents, vault.open(secret, "user-1", sealed))
        assertNull(vault.open(crypto.randomBytes(32), "user-1", sealed), "another passkey")
        assertNull(vault.open(secret, "user-2", sealed), "another person's vault")
        assertNull(vault.open(secret, "user-1", sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }), "damaged")
    }
}
