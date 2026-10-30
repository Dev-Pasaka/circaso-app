package com.circaso.core.platform

import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.crypto.SignalException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64

/**
 * AES-256-GCM (CryptoKit, through the crypto bridge) under a random key that lives in the Keychain, device-only and
 * readable after first unlock (see [IosSecureStorage]), so sealed rows can be opened by a background push wake.
 * The key is made on first use and never leaves the Keychain except to seal and open in memory.
 */
class IosLocalSealer(private val storage: SecureStorage, private val crypto: SignalCrypto) : LocalSealer {
    private val lock = Mutex()
    private var cached: ByteArray? = null

    private suspend fun key(): ByteArray = cached ?: lock.withLock {
        cached ?: run {
            val existing = storage.get(StorageKey)?.let { Base64.decode(it) }
            val key = existing ?: crypto.randomBytes(KeySize).also { storage.put(StorageKey, Base64.encode(it)) }
            if (key.size != KeySize) throw LocalSealerException("wrapping key has the wrong size")
            key.also { cached = it }
        }
    }

    override suspend fun seal(plain: ByteArray, label: String): ByteArray =
        crypto.aesGcmSeal(key(), plain, label.encodeToByteArray())

    override suspend fun open(sealed: ByteArray, label: String): ByteArray = try {
        crypto.aesGcmOpen(key(), sealed, label.encodeToByteArray())
    } catch (e: SignalException) {
        throw LocalSealerException("sealed blob does not open", e)
    }

    private companion object {
        const val StorageKey = "records.wrapping_key"
        const val KeySize = 32
    }
}
