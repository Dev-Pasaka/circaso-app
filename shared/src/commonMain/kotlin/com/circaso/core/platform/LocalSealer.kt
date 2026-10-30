package com.circaso.core.platform

/**
 * Seals small blobs that must never be readable from a database file alone: Signal sessions, prekeys, sender keys,
 * group metadata keys. The wrapping key never leaves the phone's secure store (Android Keystore; a random key in the
 * iOS Keychain, device-only, not backed up). [label] is bound into the ciphertext (`table:id`), so a sealed row cannot
 * be moved to another row and opened there. Output is `nonce(12) || ciphertext || tag(16)`.
 */
interface LocalSealer {
    suspend fun seal(plain: ByteArray, label: String): ByteArray
    suspend fun open(sealed: ByteArray, label: String): ByteArray
}

class LocalSealerException(message: String, cause: Throwable? = null) : Exception(message, cause)
