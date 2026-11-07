package com.circaso.core.crypto

import com.circaso.core.platform.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chat files whose ciphertext would be over [LegacyMaxSealedBytes] (Pasaka, 2026-11-07: chat attachments up to
 * 100 MiB) are sealed in pieces so neither phone ever holds the whole file in memory: the plaintext is cut into
 * [ChunkBytes] chunks and chunk *i* is AES-256-GCM under one fresh file key (`nonce ‖ ciphertext ‖ tag`, the same box
 * [SignalCrypto.aesGcmSeal] makes for everything else), with associated data = *i* as 8 bytes big-endian ‖ 1 byte that
 * is 1 on the last chunk, else 0. So chunks can't be swapped, dropped, repeated or the file cut short at a chunk edge:
 * each would fail its tag. The key travels only in the end-to-end encrypted message (`ChatPayload.chunkedKey`).
 *
 * A smaller file keeps the old single-box format (`mediaKey` + `digest`) that every app, old ones too, can open.
 */
object ChunkedFileCipher {
    /** Plaintext per chunk. 1 MiB keeps the 28-byte overhead tiny and a few MB of buffers at most in memory. */
    const val ChunkBytes: Int = 1024 * 1024

    /** GCM's nonce and tag around each chunk. */
    const val ChunkOverhead: Int = 12 + 16

    /**
     * The most ciphertext the single-box format is used for: the old server limit (32 MiB), which older apps were
     * built to hold in memory. Anything larger goes chunked, which they decline to open instead of running out of memory.
     */
    const val LegacyMaxSealedBytes: Long = 32L * 1024 * 1024

    /** The single box's size for [plainBytes] (one nonce and one tag). */
    fun legacySealedSize(plainBytes: Long): Long = plainBytes + ChunkOverhead

    /** Whether a file of [plainBytes] goes in the single box every app opens. */
    fun fitsLegacy(plainBytes: Long): Boolean = legacySealedSize(plainBytes) <= LegacyMaxSealedBytes

    /** How many chunks [plainBytes] makes: at least one (an empty file is one empty, final chunk). */
    fun chunkCount(plainBytes: Long): Long = if (plainBytes <= 0) 1 else (plainBytes + ChunkBytes - 1) / ChunkBytes

    /** The chunked ciphertext's exact size for [plainBytes]. */
    fun sealedSize(plainBytes: Long): Long = plainBytes + chunkCount(plainBytes) * ChunkOverhead

    /** What a chat sends for [plainBytes]: the single box when it fits, else the chunked file. */
    fun sealedSizeFor(plainBytes: Long): Long = if (fitsLegacy(plainBytes)) legacySealedSize(plainBytes) else sealedSize(plainBytes)

    private fun associatedData(index: Long, last: Boolean): ByteArray = ByteArray(9).also { ad ->
        for (b in 0 until 8) ad[b] = (index ushr (8 * (7 - b))).toByte()
        ad[8] = if (last) 1 else 0
    }

    /**
     * Seals [plainFile] into [sealedFile] (replacing it) a chunk at a time. Returns the ciphertext's size, or null when
     * [plainFile] isn't there. [onProgress] gets (plain bytes done, plain bytes in all).
     */
    suspend fun seal(
        crypto: SignalCrypto,
        files: MediaFiles,
        key: ByteArray,
        plainFile: String,
        sealedFile: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Long? {
        val total = files.size(plainFile) ?: return null
        files.delete(sealedFile)
        val chunks = chunkCount(total)
        var written = 0L
        for (i in 0 until chunks) {
            val plain = files.readAt(plainFile, i * ChunkBytes, ChunkBytes) ?: return null
            val last = i == chunks - 1
            val box = withContext(Dispatchers.Default) { crypto.aesGcmSeal(key, plain, associatedData(i, last)) }
            files.append(sealedFile, box)
            written += box.size
            onProgress(minOf(total, (i + 1) * ChunkBytes), total)
        }
        return written
    }

    /** Why [open] stopped. */
    enum class OpenResult { Opened, Missing, Damaged }

    /**
     * Opens the chunked [sealedFile] into [plainFile] (replacing it) a chunk at a time. [Damaged] when any chunk fails
     * its tag or the file's length can't be a chunked file; [plainFile] is removed then, so nothing half-opened stays.
     */
    suspend fun open(crypto: SignalCrypto, files: MediaFiles, key: ByteArray, sealedFile: String, plainFile: String): OpenResult {
        val total = files.size(sealedFile) ?: return OpenResult.Missing
        val boxBytes = (ChunkBytes + ChunkOverhead).toLong()
        val chunks = if (total <= 0) 0 else (total + boxBytes - 1) / boxBytes
        // The last chunk carries at least its nonce and tag; a length that leaves less was cut or padded.
        if (chunks == 0L || total - (chunks - 1) * boxBytes < ChunkOverhead) return OpenResult.Damaged
        files.delete(plainFile)
        for (i in 0 until chunks) {
            val box = files.readAt(sealedFile, i * boxBytes, boxBytes.toInt()) ?: return OpenResult.Missing.also { files.delete(plainFile) }
            val plain = withContext(Dispatchers.Default) {
                runCatching { crypto.aesGcmOpen(key, box, associatedData(i, i == chunks - 1)) }.getOrNull()
            } ?: return OpenResult.Damaged.also { files.delete(plainFile) }
            files.append(plainFile, plain)
        }
        // An empty file is one empty last chunk: make sure its (empty) plaintext exists.
        if (files.size(plainFile) == null) files.append(plainFile, ByteArray(0))
        return OpenResult.Opened
    }
}
