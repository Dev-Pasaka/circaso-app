package com.circaso.core.crypto

import com.circaso.core.platform.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single AES-256-GCM box every app version opens (`nonce(12) || ciphertext || tag(16)`, the format of a moment's
 * file and of a chat file up to 32 MiB), made and opened from a file a piece at a time (2026-11-14, a moment's file up
 * to 64 MiB). The bytes are exactly those of [SignalCrypto.aesGcmSeal]; only the memory differs: the one-shot holds
 * the plaintext and the ciphertext whole, two or three times over, which at 64 MiB is more than a phone with a small
 * heap has. Where the platform has no streaming GCM ([SignalCrypto.gcmStream] is null) the callers keep the one-shot.
 */
object SingleBoxFile {
    const val NonceBytes = 12
    const val TagBytes = 16
    /** Read, encrypted and written at a time. */
    const val PieceBytes = 1024 * 1024

    /** What [seal] made: the ciphertext's size and its SHA-256. */
    class Sealed(val size: Long, val digest: ByteArray)

    /**
     * Seals [plainFile] into [sealedFile] (replacing it). Null when this platform can't stream, the key isn't an
     * AES-256 one, or [plainFile] isn't there: the caller falls back to the one-shot, which says which.
     */
    suspend fun seal(crypto: SignalCrypto, files: MediaFiles, key: ByteArray, plainFile: String, sealedFile: String): Sealed? = withContext(Dispatchers.Default) {
        val size = files.size(plainFile) ?: return@withContext null
        val hash = crypto.sha256Stream() ?: return@withContext null
        val nonce = crypto.randomBytes(NonceBytes)
        val gcm = crypto.gcmStream(key, nonce, seal = true) ?: return@withContext null
        files.delete(sealedFile)
        files.append(sealedFile, nonce)
        hash.update(nonce)
        var at = 0L
        while (at < size) {
            val piece = files.readAt(plainFile, at, PieceBytes)?.takeIf { it.isNotEmpty() } ?: run { files.delete(sealedFile); return@withContext null }
            gcm.update(piece)
            hash.update(piece)
            files.append(sealedFile, piece)
            at += piece.size
        }
        val tag = gcm.tag()
        hash.update(tag)
        files.append(sealedFile, tag)
        Sealed(size + NonceBytes + TagBytes, hash.finish())
    }

    /**
     * Opens [sealedFile] into [plainFile], which appears only once the whole box has been checked: its SHA-256 against
     * [digest] (when given) and GCM's own tag. True when it opened; false when it is not what was sealed (nothing is
     * left behind); null when this platform can't stream (the caller uses the one-shot).
     */
    suspend fun open(crypto: SignalCrypto, files: MediaFiles, key: ByteArray, sealedFile: String, plainFile: String, digest: ByteArray? = null): Boolean? = withContext(Dispatchers.Default) {
        val size = files.size(sealedFile) ?: return@withContext false
        val hash = crypto.sha256Stream() ?: return@withContext null
        if (size < NonceBytes + TagBytes) return@withContext false
        val nonce = files.readAt(sealedFile, 0, NonceBytes)?.takeIf { it.size == NonceBytes } ?: return@withContext false
        val gcm = crypto.gcmStream(key, nonce, seal = false) ?: return@withContext null
        val part = "$plainFile.part"
        files.delete(part)
        hash.update(nonce)
        val end = size - TagBytes
        var at = NonceBytes.toLong()
        while (at < end) {
            val piece = files.readAt(sealedFile, at, minOf(PieceBytes.toLong(), end - at).toInt())?.takeIf { it.isNotEmpty() } ?: run { files.delete(part); return@withContext false }
            hash.update(piece)
            gcm.update(piece)
            files.append(part, piece)
            at += piece.size
        }
        val tag = files.readAt(sealedFile, end, TagBytes)?.takeIf { it.size == TagBytes } ?: run { files.delete(part); return@withContext false }
        hash.update(tag)
        // An empty plaintext still makes a file, so it can be moved into place.
        if (end == NonceBytes.toLong()) files.append(part, ByteArray(0))
        val whole = (digest == null || hash.finish().contentEquals(digest)) && gcm.verify(tag)
        if (!whole) { files.delete(part); return@withContext false }
        files.move(part, plainFile)
    }
}
