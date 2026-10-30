package com.circaso.core.platform

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KeystoreProvider = "AndroidKeyStore"

/**
 * Values are encrypted with an AES-256-GCM key that lives in the Android Keystore (it cannot be exported) and
 * stored in a private preferences file. The file is excluded from backups, and the key would not restore anyway.
 */
class AndroidSecureStorage(context: Context) : SecureStorage {
    private val prefs = context.getSharedPreferences("circaso_secure", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KeystoreProvider).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreProvider)
        generator.init(
            KeyGenParameterSpec.Builder(KeyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key, null) ?: return@withContext null
        try {
            val bytes = DeviceKeyFormats.fromBase64(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, IvSize)))
            String(cipher.doFinal(bytes, IvSize, bytes.size - IvSize), Charsets.UTF_8)
        } catch (e: Exception) {
            // Unreadable (key changed, data damaged): treat as absent rather than crash; the person signs in again.
            prefs.edit().remove(key).commit()
            null
        }
    }

    override suspend fun put(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(key, DeviceKeyFormats.base64(sealed)).commit()) { "could not write secure storage" }
    }

    override suspend fun remove(key: String): Unit = withContext(Dispatchers.IO) { prefs.edit().remove(key).commit() }

    private companion object {
        const val KeyAlias = "circaso.secure.aes"
        const val IvSize = 12
    }
}

/** ECDSA P-256 in the Android Keystore, in StrongBox secure hardware when the phone has it. */
class AndroidDeviceKeyStore : DeviceKeyStore {
    private val keyStore = KeyStore.getInstance(KeystoreProvider).apply { load(null) }

    override suspend fun hasKey(): Boolean = withContext(Dispatchers.IO) { keyStore.containsAlias(Alias) }

    override suspend fun getOrCreatePublicKey(): String = withContext(Dispatchers.IO) {
        if (!keyStore.containsAlias(Alias)) generate()
        // The certificate's public key is already DER SubjectPublicKeyInfo, which is what the server wants.
        DeviceKeyFormats.base64(requireNotNull(keyStore.getCertificate(Alias)) { "device key missing" }.publicKey.encoded)
    }

    override suspend fun sign(payload: String): String = withContext(Dispatchers.IO) {
        val key = keyStore.getKey(Alias, null) as? PrivateKey ?: throw DeviceKeyException("no device key on this phone")
        val signature = Signature.getInstance("SHA256withECDSA") // produces DER
        signature.initSign(key)
        signature.update(payload.toByteArray(Charsets.UTF_8))
        DeviceKeyFormats.base64(signature.sign())
    }

    override suspend fun delete(): Unit = withContext(Dispatchers.IO) { if (keyStore.containsAlias(Alias)) keyStore.deleteEntry(Alias) }

    private fun generate() {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(Alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()

        fun pair(strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KeystoreProvider)
            .apply { initialize(spec(strongBox)) }
            .generateKeyPair()
        try {
            pair(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            pair(strongBox = false) // still hardware-backed on most phones (TEE)
        }
    }

    private companion object {
        const val Alias = "circaso.device.key"
    }
}

actual fun verifyEs256(publicKeySpkiBase64: String, payload: String, signatureBase64: String): Boolean {
    val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(DeviceKeyFormats.fromBase64(publicKeySpkiBase64)))
    return Signature.getInstance("SHA256withECDSA").run {
        initVerify(publicKey)
        update(payload.toByteArray(Charsets.UTF_8))
        verify(DeviceKeyFormats.fromBase64(signatureBase64))
    }
}
