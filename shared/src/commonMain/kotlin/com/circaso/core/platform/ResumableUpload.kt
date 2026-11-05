package com.circaso.core.platform

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.SignalCrypto
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * Where one encrypted upload has got to, saved with the job it belongs to (a chat message's row, a pending moment),
 * so a job cut off by the process dying picks up where it stopped instead of starting over:
 * sealed (the ciphertext is on disk, key and digest fixed) → slot (a pre-signed URL) → put (the bytes are up) →
 * completed (the caller writes the final id and drops this state), then the caller's own commit (the message, the post).
 */
@Serializable
data class UploadState(
    /** [MediaFiles] name of the ciphertext, kept until the upload is completed. */
    val sealedFile: String,
    /** Base64 AES-256-GCM key and SHA-256 of the ciphertext: fixed once sealed, so a retry uploads the same bytes. */
    val key: String,
    val digest: String,
    val size: Long,
    val slotId: String? = null,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Epoch millis after which [url] takes no more PUTs (the slot's `expiresAt`). */
    val urlUntil: Long? = null,
    /** The PUT returned 2xx (or the platform's uploader finished it while Circaso was closed). */
    val put: Boolean = false,
)

/** A pre-signed upload slot (moments ST1, chats AT1). */
class UploadSlot(val id: String, val url: String, val headers: Map<String, String>, val expiresAt: Long?)

/** A completed upload: the id the commit refers to, with the key and digest the encrypted message carries. */
class UploadedFile(val id: String, val key: ByteArray, val digest: ByteArray, val size: Long)

/**
 * Runs an encrypted upload step by step, saving [UploadState] after each one (see the state's doc). Every step is
 * safe to repeat: sealing happens once; a new slot is asked for only when there is none or its URL has expired before
 * the bytes went up; a PUT is repeated whole (storage takes single PUTs only, no multipart: the backend gives one
 * pre-signed URL per file, up to 32 MiB); completing twice answers the same (AT2, ST2).
 */
class ResumableUpload(
    private val crypto: SignalCrypto,
    private val files: MediaFiles,
    private val uploads: BackgroundUploads,
    private val storage: HttpClient,
    private val now: () -> Long,
) {
    suspend fun run(
        plainFile: String,
        sealedFile: String,
        state: UploadState?,
        save: suspend (UploadState) -> Unit,
        create: suspend (sizeBytes: Long) -> AppResult<UploadSlot>,
        complete: suspend (slotId: String) -> AppResult<Unit>,
        /** The slot is no good any more (expired, never uploaded, another size): ask for a new one, once per run. */
        startOver: (AppError) -> Boolean,
        missing: AppError,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): AppResult<UploadedFile> {
        var sealed = state?.let { files.read(it.sealedFile) }
        var s: UploadState = if (state != null && sealed != null) state else {
            val plain = files.read(plainFile) ?: return AppResult.Failure(missing)
            val key = crypto.randomBytes(32)
            val bytes = withContext(Dispatchers.Default) { crypto.aesGcmSeal(key, plain) }
            val digest = withContext(Dispatchers.Default) { crypto.sha256(bytes) }
            files.write(sealedFile, bytes)
            sealed = bytes
            UploadState(sealedFile, Base64.encode(key), Base64.encode(digest), bytes.size.toLong()).also { save(it) }
        }
        val body = sealed!!
        for (attempt in 0..1) {
            if (s.slotId == null || s.url == null || (!s.put && expired(s))) {
                val slot = when (val r = create(s.size)) {
                    is AppResult.Failure -> return r
                    is AppResult.Success -> r.value
                }
                s = s.copy(slotId = slot.id, url = slot.url, headers = slot.headers, urlUntil = slot.expiresAt, put = false).also { save(it) }
            }
            if (!s.put) {
                if (!uploads.finished(s.url!!)) {
                    if (!uploads.putFile(storage, s.url!!, s.headers, body, onProgress)) return AppResult.Failure(AppError.Network)
                }
                s = s.copy(put = true).also { save(it) }
            }
            when (val r = complete(s.slotId!!)) {
                is AppResult.Success -> {
                    // The ciphertext is not needed again: the next step (the message, the post) refers to the slot id.
                    files.delete(s.sealedFile)
                    return AppResult.Success(UploadedFile(s.slotId!!, Base64.decode(s.key), Base64.decode(s.digest), s.size))
                }
                is AppResult.Failure -> {
                    if (attempt > 0 || !startOver(r.error)) return r
                    s = s.copy(slotId = null, url = null, headers = emptyMap(), urlUntil = null, put = false).also { save(it) }
                }
            }
        }
        return AppResult.Failure(AppError.Network)
    }

    private fun expired(s: UploadState) = s.urlUntil != null && now() > s.urlUntil - ExpiryMarginMillis

    private companion object {
        /** A PUT started this close to the URL's end might not finish in time: ask for a fresh URL instead. */
        const val ExpiryMarginMillis = 60_000L
    }
}
