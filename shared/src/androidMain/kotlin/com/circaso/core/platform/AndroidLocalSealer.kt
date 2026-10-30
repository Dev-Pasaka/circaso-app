package com.circaso.core.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM under a non-exportable Android Keystore key (same pattern as [AndroidSecureStorage], a separate key). */
class AndroidLocalSealer : LocalSealer {
    private val keyStore = KeyStore.getInstance(KeystoreProvider).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(Alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreProvider)
        generator.init(
            KeyGenParameterSpec.Builder(Alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override suspend fun seal(plain: ByteArray, label: String): ByteArray = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey()) // the Keystore picks a fresh random nonce
        cipher.updateAAD(label.encodeToByteArray())
        cipher.iv + cipher.doFinal(plain)
    }

    override suspend fun open(sealed: ByteArray, label: String): ByteArray = withContext(Dispatchers.IO) {
        if (sealed.size < NonceSize + TagBits / 8) throw LocalSealerException("sealed blob too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TagBits, sealed, 0, NonceSize))
        cipher.updateAAD(label.encodeToByteArray())
        try {
            cipher.doFinal(sealed, NonceSize, sealed.size - NonceSize)
        } catch (e: AEADBadTagException) {
            throw LocalSealerException("sealed blob does not open", e)
        }
    }

    private companion object {
        const val KeystoreProvider = "AndroidKeyStore"
        const val Alias = "circaso.records"
        const val NonceSize = 12
        const val TagBits = 128
    }
}
