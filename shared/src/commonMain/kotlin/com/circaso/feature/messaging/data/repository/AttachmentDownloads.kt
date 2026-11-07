package com.circaso.feature.messaging.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.platform.MediaFiles
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Receiving a chat file (AT3) without ever holding it whole in memory (2026-11-07, files up to 100 MiB): downloaded to
 * a partial file a piece at a time, resumed with `Range` after a dropped connection, then checked and decrypted from
 * disk to disk ([com.circaso.core.crypto.ChunkedFileCipher] for a chunked file; the old single box, at most 32 MiB,
 * in memory as every app always has).
 */
@OptIn(ExperimentalEncodingApi::class)
class AttachmentDownloads(
    private val crypto: SignalCrypto,
    private val files: MediaFiles,
    private val attachments: AttachmentsRemote,
    private val storage: HttpClient,
) {
    /**
     * Makes sure the decrypted file is on this phone (by its own name or the attachment id): null when it is, else why
     * not. The download streams to a partial file a piece at a time and picks up where it stopped (`Range`) when the
     * connection drops, now or on the next try; then it is checked and decrypted from disk to disk.
     */
    suspend fun fetch(details: MediaDetails): MediaLoad? {
        if (details.file != null && files.size(details.file) != null) return null
        val id = details.attachmentId ?: return MediaLoad.Failed
        if (files.size(id) != null) return null
        val key = details.key?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return MediaLoad.Damaged
        val digest = if (details.chunked == true) null else details.digest?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return MediaLoad.Damaged
        val sealed = "dl-$id"
        when (val got = download(id, sealed)) {
            null -> Unit
            else -> return got
        }
        if (details.chunked == true) {
            val part = "$id.part"
            return when (com.circaso.core.crypto.ChunkedFileCipher.open(crypto, files, key, sealed, part)) {
                com.circaso.core.crypto.ChunkedFileCipher.OpenResult.Opened -> { files.move(part, id); files.delete(sealed); null }
                com.circaso.core.crypto.ChunkedFileCipher.OpenResult.Missing -> MediaLoad.Failed
                com.circaso.core.crypto.ChunkedFileCipher.OpenResult.Damaged -> { files.delete(sealed); MediaLoad.Damaged }
            }
        }
        // The single box: at most the old 32 MiB, which is what every app held in memory to open it.
        val size = files.size(sealed) ?: return MediaLoad.Failed
        if (size > com.circaso.core.crypto.ChunkedFileCipher.LegacyMaxSealedBytes) { files.delete(sealed); return MediaLoad.Damaged }
        val box = files.read(sealed) ?: return MediaLoad.Failed
        files.delete(sealed)
        if (!crypto.sha256(box).contentEquals(digest)) return MediaLoad.Damaged
        val plain = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { crypto.aesGcmOpen(key, box) } }.getOrNull() ?: return MediaLoad.Damaged
        files.write(id, plain)
        return null
    }

    /**
     * Downloads attachment [id] into [into], streamed to disk; null when it is all there. A fresh link (AT3) per try;
     * a partial file from an earlier try is continued with `Range`, and a dropped connection is tried again
     * [DownloadAttempts] times before it counts as offline (the partial file stays for next time).
     */
    private suspend fun download(id: String, into: String): MediaLoad? {
        var lastError: MediaLoad = MediaLoad.Offline
        for (attempt in 1..DownloadAttempts) {
            val link = when (val r = attachments.download(id)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return when {
                    r.error is AppError.Network -> MediaLoad.Offline
                    (r.error as? AppError.Api)?.code == "messaging.attachment_not_found" -> MediaLoad.Gone
                    else -> MediaLoad.Failed
                }
            }
            val expected = link.sizeBytes
            var have = files.size(into) ?: 0L
            if (expected != null && have > expected) { files.delete(into); have = 0 }
            if (expected != null && have == expected && have > 0) return null
            val outcome: MediaLoad? = try {
                storage.prepareGet(link.downloadUrl) { if (have > 0) header("Range", "bytes=$have-") }.execute { response ->
                    when {
                        response.status.value == 404 -> MediaLoad.Gone
                        response.status.value == 416 -> { files.delete(into); MediaLoad.Failed }
                        !response.status.isSuccess() -> MediaLoad.Failed
                        else -> {
                            // 200 is the whole file again (the server ignored the range): start the partial over.
                            if (response.status.value != 206) files.delete(into)
                            val channel = response.bodyAsChannel()
                            val buffer = ByteArray(com.circaso.core.platform.StreamPieceBytes)
                            while (true) {
                                var filled = 0
                                while (filled < buffer.size) {
                                    val n = channel.readAvailable(buffer, filled, buffer.size - filled)
                                    if (n < 0) break
                                    filled += n
                                }
                                if (filled > 0) files.append(into, if (filled == buffer.size) buffer.copyOf() else buffer.copyOf(filled))
                                if (filled < buffer.size) break
                            }
                            val got = files.size(into) ?: 0L
                            if (expected != null && got != expected) MediaLoad.Offline else null
                        }
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Throwable) {
                MediaLoad.Offline // the connection dropped part-way: the next try continues from what arrived
            }
            if (outcome == null) return null
            if (outcome != MediaLoad.Offline) return outcome
            lastError = outcome
        }
        return lastError
    }

    private companion object {
        const val DownloadAttempts = 3
    }
}
