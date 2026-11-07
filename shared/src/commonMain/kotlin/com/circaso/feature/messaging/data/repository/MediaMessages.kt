package com.circaso.feature.messaging.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.GeoPoint
import com.circaso.core.platform.MediaFiles
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxType
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.MessageEntity
import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.MessageStatus
import com.circaso.feature.messaging.data.local.MessagesDao
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * What a media message holds (photo, voice note, shared location), kept as JSON in the message row's `text` and sent
 * inside the end-to-end encrypted payload. A photo or voice note is a Signal-style attachment: the file is encrypted
 * on the phone with a fresh AES-256-GCM [key], uploaded as an opaque blob (guide AT1–AT2), and only the message
 * carries the key and the [digest] of the encrypted blob; the server never sees the content.
 */
@Serializable
data class MediaDetails(
    val kind: String,
    val attachmentId: String? = null,
    val key: String? = null,
    val digest: String? = null,
    val size: Long? = null,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val caption: String? = null,
    /** This phone's decrypted copy (MediaFiles name); never sent. */
    val file: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    /** A place's name for a location; a document's own file name ("Lease.pdf") for a file. */
    val label: String? = null,
    /** A live location: the share's id, when it ends, when the pin last moved (epoch millis), and whether it was stopped early. */
    val liveId: String? = null,
    val liveUntil: Long? = null,
    val liveAt: Long? = null,
    val liveEnded: Boolean? = null,
    /** This phone's own share only: false while the others haven't been told it ended (offline at Stop). Never sent. */
    val liveStopSent: Boolean? = null,
    /** A video's first frame, a small base64 JPEG carried in the message so the bubble shows before the file loads. */
    val thumb: String? = null,
    /** Sent together with other photos and videos: their shared album id (ChatPayload.albumId). */
    val album: String? = null,
    /** How far this phone's upload of the file got, so a send cut off by the process dying resumes it. Never sent. */
    val upload: com.circaso.core.platform.UploadState? = null,
    /**
     * Sealed in 1 MiB chunks ([com.circaso.core.crypto.ChunkedFileCipher]): [key] opens each chunk and there is no
     * whole-file [digest]. Sent as `chunkedKey` (2026-11-07, chat files over 32 MiB).
     */
    val chunked: Boolean? = null,
) {
    val isLive get() = liveId != null

    /** Still moving: started, not stopped, not past its end. */
    fun liveNow(now: Long) = liveId != null && liveEnded != true && (liveUntil ?: 0) > now

    companion object {
        const val Image = "image"
        const val Voice = "voice"
        const val Video = "video"
        const val Location = "location"
        /** A document (any file), 2026-11-07. */
        const val File = "file"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fun read(text: String?): MediaDetails? = text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
        fun write(d: MediaDetails): String = json.encodeToString(serializer(), d)
    }
}

internal fun isMediaKind(kind: String) = kind == MessageKind.Image || kind == MessageKind.Voice || kind == MessageKind.Video || kind == MessageKind.Location || kind == MessageKind.File

/** The payload a media row sends (the phone-only `file` is left out). */
internal fun MediaDetails.toPayload(sentAt: String): ChatPayload = ChatPayload(
    type = when {
        kind == MediaDetails.Image -> ChatPayload.Image
        kind == MediaDetails.Voice -> ChatPayload.Voice
        kind == MediaDetails.Video -> ChatPayload.Video
        kind == MediaDetails.File -> ChatPayload.File
        liveId != null -> ChatPayload.LiveLocation
        else -> ChatPayload.Location
    },
    text = caption, clientSentAt = sentAt,
    attachmentId = attachmentId, mediaKey = key.takeIf { chunked != true }, digest = digest.takeIf { chunked != true },
    chunkedKey = key.takeIf { chunked == true }, size = size, mime = mime, width = width, height = height, durationMs = durationMs,
    latitude = latitude, longitude = longitude, accuracy = accuracy, label = label,
    liveId = liveId, liveUntil = liveUntil, liveAt = liveAt, thumb = thumb,
    albumId = album?.takeIf { kind == MediaDetails.Image || kind == MediaDetails.Video },
)

/** The details an incoming media payload carries; null when it isn't complete enough to show. */
internal fun ChatPayload.toMediaDetails(): MediaDetails? = when (type) {
    ChatPayload.Location -> if (latitude != null && longitude != null) MediaDetails(MediaDetails.Location, latitude = latitude, longitude = longitude, accuracy = accuracy, label = label) else null
    ChatPayload.LiveLocation -> if (latitude != null && longitude != null && liveId != null && liveUntil != null) MediaDetails(
        MediaDetails.Location, latitude = latitude, longitude = longitude, accuracy = accuracy, liveId = liveId, liveUntil = liveUntil, liveAt = liveAt,
    ) else null
    ChatPayload.Image, ChatPayload.Voice, ChatPayload.Video, ChatPayload.File -> if (attachmentId != null && (chunkedKey != null || (mediaKey != null && digest != null))) MediaDetails(
        when (type) { ChatPayload.Image -> MediaDetails.Image; ChatPayload.Video -> MediaDetails.Video; ChatPayload.File -> MediaDetails.File; else -> MediaDetails.Voice },
        attachmentId = attachmentId, key = chunkedKey ?: mediaKey, digest = digest.takeIf { chunkedKey == null }, chunked = true.takeIf { chunkedKey != null }, size = size, mime = mime, width = width, height = height, durationMs = durationMs, caption = text,
        thumb = thumb?.takeIf { type == ChatPayload.Video },
        label = label?.takeIf { type == ChatPayload.File }?.let(::safeFileName),
        album = albumId?.takeIf { (type == ChatPayload.Image || type == ChatPayload.Video) && it.length in 1..64 },
    ) else null
    else -> null
}

internal fun mediaRowKind(details: MediaDetails) = when (details.kind) {
    MediaDetails.Image -> MessageKind.Image; MediaDetails.Voice -> MessageKind.Voice; MediaDetails.Video -> MessageKind.Video; MediaDetails.File -> MessageKind.File
    else -> MessageKind.Location
}

/**
 * A file name as it came in a message, made safe to show and to save under: no folders, no control characters, at
 * most 120 characters (the end kept, so the extension stays). A sender can put anything there.
 */
internal fun safeFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }.trim().trimStart('.')
    val cut = if (base.length > 120) base.takeLast(120) else base
    return cut.ifBlank { "File" }
}

/** What loading a photo or voice note came to; see [MediaMessages.load]. */
sealed interface MediaLoad {
    class Loaded(val bytes: ByteArray) : MediaLoad
    data object Offline : MediaLoad
    data object Gone : MediaLoad
    data object Damaged : MediaLoad
    data object Failed : MediaLoad
}

// ---- guide AT1–AT3

@Serializable data class NewAttachmentRequest(val sizeBytes: Long, val kind: String)
@Serializable data class NewAttachmentResponse(val attachmentId: String, val uploadUrl: String, val uploadHeaders: Map<String, String> = emptyMap(), val expiresAt: String? = null)
@Serializable data class AttachmentReadyResponse(val attachmentId: String, val sizeBytes: Long? = null)
@Serializable data class AttachmentDownloadResponse(val downloadUrl: String, val expiresAt: String? = null, val sizeBytes: Long? = null)

interface AttachmentsRemote {
    suspend fun create(chatId: String, sizeBytes: Long, kind: String): AppResult<NewAttachmentResponse>
    suspend fun complete(attachmentId: String): AppResult<AttachmentReadyResponse>
    suspend fun download(attachmentId: String): AppResult<AttachmentDownloadResponse>
}

class KtorAttachmentsRemote(private val api: CircasoApi) : AttachmentsRemote {
    override suspend fun create(chatId: String, sizeBytes: Long, kind: String) = api.call<NewAttachmentResponse> {
        method = HttpMethod.Post; url("/v1/chats/$chatId/attachments"); idempotencyKey(newIdempotencyKey())
        contentType(ContentType.Application.Json); setBody(NewAttachmentRequest(sizeBytes, kind))
    }
    override suspend fun complete(attachmentId: String) = api.call<AttachmentReadyResponse> {
        method = HttpMethod.Post; url("/v1/attachments/$attachmentId/complete"); idempotencyKey(newIdempotencyKey())
    }
    override suspend fun download(attachmentId: String) = api.call<AttachmentDownloadResponse> { method = HttpMethod.Get; url("/v1/attachments/$attachmentId") }
}

/**
 * Photos, voice notes and shared locations in chats (WhatsApp-style). A location is sent like a text. A photo or voice
 * note is saved and shown at once, then the outbox encrypts, uploads and sends it in order (offline it waits, like
 * text); a refused upload marks the row Failed and "Send again" puts it back in the queue.
 */
@OptIn(ExperimentalEncodingApi::class)
class MediaMessages(
    private val dao: MessagesDao,
    private val chats: ChatsDao,
    private val outbox: Outbox,
    private val kickOutbox: () -> Unit,
    private val crypto: SignalCrypto,
    private val files: MediaFiles,
    private val attachments: AttachmentsRemote,
    private val storage: HttpClient,
    private val json: Json,
    private val ownUserId: suspend () -> String?,
    private val now: () -> Long,
    /** The PUT goes through the platform's background uploader where there is one (iOS), so it outlives the app. */
    private val uploads: com.circaso.core.platform.BackgroundUploads = com.circaso.core.platform.NoBackgroundUploads,
    /** The app's scope: downloads run there, so one keeps going when its bubble scrolls away (AttachmentDownloads). */
    downloadScope: kotlinx.coroutines.CoroutineScope? = null,
) {
    private val resumable = com.circaso.core.platform.ResumableUpload(crypto, files, uploads, storage, now)
    private val downloads = AttachmentDownloads(crypto, files, attachments, storage, downloadScope)

    /** How far each incoming file's download has got (0..1), by attachment id: the ring on its bubble or album tile. */
    val downloading: kotlinx.coroutines.flow.StateFlow<Map<String, Float>> get() = downloads.progress

    /**
     * How far each outgoing file's upload has got (0..1) by row, in memory only: it means something only while this
     * run is sending it. The thread draws it as the ring on the thumbnail (the moment ring's M6 language).
     */
    private val _progress = kotlinx.coroutines.flow.MutableStateFlow<Map<Long, Float>>(emptyMap())
    val progress: kotlinx.coroutines.flow.StateFlow<Map<Long, Float>> = _progress

    /** Published in whole percent steps, so a 30 MB upload is a hundred updates, not thousands. */
    private fun setProgress(localId: Long, fraction: Float?) {
        _progress.value = _progress.value.let { now ->
            when {
                fraction == null -> now - localId
                ((now[localId] ?: -1f) * 100).toInt() == (fraction * 100).toInt() -> now
                else -> now + (localId to fraction.coerceIn(0f, 1f))
            }
        }
    }

    suspend fun sendLocation(chatId: String, point: GeoPoint, label: String?) {
        val details = MediaDetails(MediaDetails.Location, latitude = point.latitude, longitude = point.longitude, accuracy = point.accuracyMeters, label = label)
        val localId = insert(chatId, details) ?: return
        enqueue(localId)
    }

    /** Starts a live location share: one message whose pin later moves (LiveLocations sends the moves). */
    suspend fun sendLive(chatId: String, point: GeoPoint, liveId: String, until: Long): Long? {
        val details = MediaDetails(
            MediaDetails.Location, latitude = point.latitude, longitude = point.longitude, accuracy = point.accuracyMeters,
            liveId = liveId, liveUntil = until, liveAt = now(),
        )
        val localId = insert(chatId, details) ?: return null
        enqueue(localId)
        return localId
    }

    suspend fun sendImage(chatId: String, bytes: ByteArray, mime: String, width: Int?, height: Int?, caption: String?, album: String? = null): AppResult<Unit> =
        sendFile(chatId, bytes, MediaDetails(MediaDetails.Image, size = bytes.size.toLong(), mime = mime, width = width, height = height, caption = caption?.takeIf { it.isNotBlank() }, album = album))

    suspend fun sendVideo(chatId: String, bytes: ByteArray, mime: String, width: Int?, height: Int?, durationMs: Long?, thumb: String?, caption: String?, album: String? = null): AppResult<Unit> =
        sendFile(chatId, bytes, MediaDetails(MediaDetails.Video, size = bytes.size.toLong(), mime = mime, width = width, height = height, durationMs = durationMs, thumb = thumb, caption = caption?.takeIf { it.isNotBlank() }, album = album))

    /**
     * A video from its file as it is (no trim, no re-encode; Pasaka, 2026-11-07), up to [com.circaso.core.platform.ChatAttachmentMaxBytes] once
     * encrypted. Copied into Circaso's files a piece at a time, never read into memory.
     */
    suspend fun sendVideoFile(chatId: String, path: String, mime: String, width: Int?, height: Int?, durationMs: Long?, thumb: String?, caption: String?, album: String? = null): AppResult<Unit> =
        sendStored(chatId, path, MediaDetails(MediaDetails.Video, mime = mime, width = width, height = height, durationMs = durationMs, thumb = thumb, caption = caption?.takeIf { it.isNotBlank() }, album = album))

    /** A document from its file under its own [name], like [sendVideoFile]. */
    suspend fun sendDocumentFile(chatId: String, path: String, mime: String, name: String, caption: String?): AppResult<Unit> =
        sendStored(chatId, path, MediaDetails(MediaDetails.File, mime = mime, label = safeFileName(name), caption = caption?.takeIf { it.isNotBlank() }))

    /** A document (any file) under its own [name], with an optional caption: the same queue, encryption and upload as a photo. */
    suspend fun sendDocument(chatId: String, bytes: ByteArray, mime: String, name: String, caption: String?): AppResult<Unit> =
        sendFile(chatId, bytes, MediaDetails(MediaDetails.File, size = bytes.size.toLong(), mime = mime, label = safeFileName(name), caption = caption?.takeIf { it.isNotBlank() }))

    suspend fun sendVoice(chatId: String, bytes: ByteArray, mime: String, durationMs: Long): AppResult<Unit> =
        sendFile(chatId, bytes, MediaDetails(MediaDetails.Voice, size = bytes.size.toLong(), mime = mime, durationMs = durationMs))

    /** Saved and shown at once; the outbox uploads then sends it, in order, waiting for the network like text does. */
    private suspend fun sendFile(chatId: String, bytes: ByteArray, base: MediaDetails): AppResult<Unit> {
        val file = "out-" + newClientMessageId()
        files.write(file, bytes)
        val localId = insert(chatId, base.copy(file = file)) ?: return AppResult.Failure(AppError.Unexpected(null))
        enqueue(localId)
        return AppResult.Success(Unit)
    }

    private suspend fun sendStored(chatId: String, path: String, base: MediaDetails): AppResult<Unit> {
        val file = "out-" + newClientMessageId()
        if (!files.copyIn(path, file)) return AppResult.Failure(AppError.Api("media.file_missing", 400, null, null, emptyMap()))
        val size = files.size(file) ?: 0L
        if (com.circaso.core.crypto.ChunkedFileCipher.sealedSizeFor(size) > com.circaso.core.platform.ChatAttachmentMaxBytes) {
            files.delete(file)
            return AppResult.Failure(AppError.Api("messaging.attachment_too_large", 400, null, null, emptyMap()))
        }
        val localId = insert(chatId, base.copy(file = file, size = size)) ?: return AppResult.Failure(AppError.Unexpected(null)).also { files.delete(file) }
        enqueue(localId)
        return AppResult.Success(Unit)
    }

    /** "Send again" on a photo, voice note or location that failed: back in the queue (the upload resumes if needed). */
    suspend fun retry(localId: Long): AppResult<Unit> {
        dao.byLocalId(localId) ?: return AppResult.Failure(AppError.Unexpected(null))
        dao.setStatus(localId, MessageStatus.Waiting, null)
        enqueue(localId)
        return AppResult.Success(Unit)
    }

    /**
     * Called by the outbox before a photo or voice note is sent: encrypts this phone's copy under a fresh key, uploads
     * it (AT1, PUT, AT2) and writes the attachment id, key and digest into the row. Status is left to the caller, so a
     * network failure keeps it Waiting and a refusal marks it Failed, the same rules as a text message.
     */
    suspend fun uploadPending(localId: Long): AppResult<Unit> {
        val row = dao.byLocalId(localId) ?: return AppResult.Failure(AppError.Unexpected(null))
        var details = MediaDetails.read(row.text) ?: return AppResult.Failure(AppError.Unexpected(null))
        if (details.kind == MediaDetails.Location || details.attachmentId != null) return AppResult.Success(Unit)
        val plainFile = details.file ?: return AppResult.Failure(AppError.Api("media.file_missing", 400, null, null, emptyMap()))
        // Over 32 MiB sealed (the old limit older apps hold in memory): sealed and uploaded in chunks, streamed.
        val chunked = details.upload?.chunked ?: files.size(plainFile)?.let { !com.circaso.core.crypto.ChunkedFileCipher.fitsLegacy(it) } ?: false
        // Each step is saved in the row (MediaDetails.upload), so a send cut off by the process dying resumes it.
        val done = when (val r = resumable.run(
            plainFile = plainFile, sealedFile = "sealed-$localId", state = details.upload,
            save = { state -> details = details.copy(upload = state); dao.setText(localId, MediaDetails.write(details)) },
            create = { size ->
                when (val slot = attachments.create(row.chatId, size, details.kind)) {
                    is AppResult.Failure -> slot
                    is AppResult.Success -> {
                        val headers = slot.value.uploadHeaders.toMutableMap()
                        if (headers.keys.none { it.equals("Content-Type", true) }) headers["Content-Type"] = "application/octet-stream"
                        AppResult.Success(com.circaso.core.platform.UploadSlot(slot.value.attachmentId, slot.value.uploadUrl, headers,
                            slot.value.expiresAt?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }))
                    }
                }
            },
            complete = { id -> when (val c = attachments.complete(id)) { is AppResult.Failure -> c; is AppResult.Success -> AppResult.Success(Unit) } },
            startOver = { e -> (e as? AppError.Api)?.code in setOf("messaging.attachment_incomplete", "messaging.attachment_not_found") },
            missing = AppError.Api("media.file_missing", 400, null, null, emptyMap()),
            onProgress = { sent, total -> if (total > 0) setProgress(localId, sent.toFloat() / total) },
            chunked = chunked,
        )) {
            is AppResult.Failure -> { setProgress(localId, null); return r }
            is AppResult.Success -> r.value
        }
        setProgress(localId, null)
        // The decrypted copy is filed under the attachment id, the same name the receiver's copy gets (a rename, not a copy).
        if (plainFile != done.id) files.move(plainFile, done.id)
        val ready = details.copy(
            attachmentId = done.id, key = Base64.encode(done.key), digest = Base64.encode(done.digest).takeIf { !done.chunked }, size = done.size,
            file = done.id, upload = null, chunked = true.takeIf { done.chunked },
        )
        dao.setText(localId, MediaDetails.write(ready))
        return AppResult.Success(Unit)
    }

    /**
     * The decrypted bytes of a photo or voice note: this phone's copy, else downloaded (AT3), checked against the
     * digest the sender put in the message, and decrypted. Null when it can't be fetched or doesn't check out.
     */
    suspend fun bytes(details: MediaDetails): ByteArray? = (load(details) as? MediaLoad.Loaded)?.bytes

    /**
     * The decrypted file on disk (downloading and decrypting it first if needed, streamed), for a video player or to
     * open a document; or why it can't be had (null when it can). Never read into memory, whatever its size.
     */
    suspend fun file(details: MediaDetails): Pair<String?, MediaLoad?> {
        downloads.fetch(details)?.let { return null to it }
        val path = details.file?.let { files.path(it) } ?: details.attachmentId?.let { files.path(it) }
        return path to (if (path == null) MediaLoad.Failed else null)
    }

    /**
     * The file's bytes, from this phone or downloaded and decrypted (AT3), with why not when it can't be had: offline,
     * gone from the server (past 30 days, or this person left the chat), or damaged (the blob doesn't match what the
     * sender signed into the message, or won't decrypt). Only offline and a passing failure are worth retrying. For a
     * photo or voice note: anything over [InMemoryMaxBytes] is refused rather than read (videos and documents use [file]).
     */
    suspend fun load(details: MediaDetails): MediaLoad {
        downloads.fetch(details)?.let { return it }
        val name = details.file?.takeIf { files.size(it) != null } ?: details.attachmentId ?: return MediaLoad.Failed
        val size = files.size(name) ?: return MediaLoad.Failed
        if (size > InMemoryMaxBytes) return MediaLoad.Failed
        return files.read(name)?.let { MediaLoad.Loaded(it) } ?: MediaLoad.Failed
    }

    private suspend fun insert(chatId: String, details: MediaDetails): Long? {
        val me = ownUserId() ?: return null
        val at = now()
        val localId = dao.insert(
            MessageEntity(
                clientMessageId = newClientMessageId(), messageId = null, chatId = chatId, senderId = me, outgoing = true,
                kind = mediaRowKind(details), text = MediaDetails.write(details), replyTo = null, status = MessageStatus.Waiting, errorCode = null,
                cachedRequest = null, sentAt = null, orderAt = at, createdAt = at,
            ),
        )
        chats.touch(chatId, at)
        return localId
    }

    private suspend fun enqueue(localId: Long) {
        outbox.enqueue(OutboxType.ChatMessage, json.encodeToString(ChatMessageJob.serializer(), ChatMessageJob(localId)))
        kickOutbox()
    }

    private companion object {
        /** A photo or voice note read into memory to show or play; anything larger is never read whole. */
        const val InMemoryMaxBytes = com.circaso.core.crypto.ChunkedFileCipher.LegacyMaxSealedBytes
    }

}
