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
import io.ktor.client.statement.readRawBytes
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
) {
    val isLive get() = liveId != null

    /** Still moving: started, not stopped, not past its end. */
    fun liveNow(now: Long) = liveId != null && liveEnded != true && (liveUntil ?: 0) > now

    companion object {
        const val Image = "image"
        const val Voice = "voice"
        const val Video = "video"
        const val Location = "location"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fun read(text: String?): MediaDetails? = text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
        fun write(d: MediaDetails): String = json.encodeToString(serializer(), d)
    }
}

internal fun isMediaKind(kind: String) = kind == MessageKind.Image || kind == MessageKind.Voice || kind == MessageKind.Video || kind == MessageKind.Location

/** The payload a media row sends (the phone-only `file` is left out). */
internal fun MediaDetails.toPayload(sentAt: String): ChatPayload = ChatPayload(
    type = when {
        kind == MediaDetails.Image -> ChatPayload.Image
        kind == MediaDetails.Voice -> ChatPayload.Voice
        kind == MediaDetails.Video -> ChatPayload.Video
        liveId != null -> ChatPayload.LiveLocation
        else -> ChatPayload.Location
    },
    text = caption, clientSentAt = sentAt,
    attachmentId = attachmentId, mediaKey = key, digest = digest, size = size, mime = mime, width = width, height = height, durationMs = durationMs,
    latitude = latitude, longitude = longitude, accuracy = accuracy, label = label,
    liveId = liveId, liveUntil = liveUntil, liveAt = liveAt, thumb = thumb,
)

/** The details an incoming media payload carries; null when it isn't complete enough to show. */
internal fun ChatPayload.toMediaDetails(): MediaDetails? = when (type) {
    ChatPayload.Location -> if (latitude != null && longitude != null) MediaDetails(MediaDetails.Location, latitude = latitude, longitude = longitude, accuracy = accuracy, label = label) else null
    ChatPayload.LiveLocation -> if (latitude != null && longitude != null && liveId != null && liveUntil != null) MediaDetails(
        MediaDetails.Location, latitude = latitude, longitude = longitude, accuracy = accuracy, liveId = liveId, liveUntil = liveUntil, liveAt = liveAt,
    ) else null
    ChatPayload.Image, ChatPayload.Voice, ChatPayload.Video -> if (attachmentId != null && mediaKey != null && digest != null) MediaDetails(
        when (type) { ChatPayload.Image -> MediaDetails.Image; ChatPayload.Video -> MediaDetails.Video; else -> MediaDetails.Voice },
        attachmentId = attachmentId, key = mediaKey, digest = digest, size = size, mime = mime, width = width, height = height, durationMs = durationMs, caption = text,
        thumb = thumb?.takeIf { type == ChatPayload.Video },
    ) else null
    else -> null
}

internal fun mediaRowKind(details: MediaDetails) = when (details.kind) { MediaDetails.Image -> MessageKind.Image; MediaDetails.Voice -> MessageKind.Voice; MediaDetails.Video -> MessageKind.Video; else -> MessageKind.Location }

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
) {
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

    suspend fun sendImage(chatId: String, bytes: ByteArray, mime: String, width: Int?, height: Int?, caption: String?): AppResult<Unit> =
        sendFile(chatId, bytes, MediaDetails(MediaDetails.Image, size = bytes.size.toLong(), mime = mime, width = width, height = height, caption = caption?.takeIf { it.isNotBlank() }))

    suspend fun sendVideo(chatId: String, bytes: ByteArray, mime: String, width: Int?, height: Int?, durationMs: Long?, thumb: String?, caption: String?): AppResult<Unit> =
        sendFile(chatId, bytes, MediaDetails(MediaDetails.Video, size = bytes.size.toLong(), mime = mime, width = width, height = height, durationMs = durationMs, thumb = thumb, caption = caption?.takeIf { it.isNotBlank() }))

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
        val details = MediaDetails.read(row.text) ?: return AppResult.Failure(AppError.Unexpected(null))
        if (details.kind == MediaDetails.Location || details.attachmentId != null) return AppResult.Success(Unit)
        val plain = details.file?.let { files.read(it) } ?: return AppResult.Failure(AppError.Api("media.file_missing", 400, null, null, emptyMap()))
        val key = crypto.randomBytes(32)
        val sealed = crypto.aesGcmSeal(key, plain)
        val digest = crypto.sha256(sealed)
        val slot = when (val r = attachments.create(row.chatId, sealed.size.toLong(), details.kind)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        val put = runCatching {
            storage.put(slot.uploadUrl) {
                slot.uploadHeaders.forEach { (k, v) -> header(k, v) }
                if (slot.uploadHeaders.keys.none { it.equals("Content-Type", true) }) header("Content-Type", "application/octet-stream")
                setBody(sealed)
            }
        }.getOrNull()
        if (put == null || !put.status.isSuccess()) return AppResult.Failure(AppError.Network)
        when (val r = attachments.complete(slot.attachmentId)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return r
        }
        // The decrypted copy is filed under the attachment id, the same name the receiver's copy gets.
        files.write(slot.attachmentId, plain)
        val ready = details.copy(attachmentId = slot.attachmentId, key = Base64.encode(key), digest = Base64.encode(digest), size = sealed.size.toLong(), file = slot.attachmentId)
        dao.setText(localId, MediaDetails.write(ready))
        return AppResult.Success(Unit)
    }

    /**
     * The decrypted bytes of a photo or voice note: this phone's copy, else downloaded (AT3), checked against the
     * digest the sender put in the message, and decrypted. Null when it can't be fetched or doesn't check out.
     */
    suspend fun bytes(details: MediaDetails): ByteArray? = (load(details) as? MediaLoad.Loaded)?.bytes

    /** The decrypted file on disk (downloading it first if needed), for a video player; or why it can't be had. */
    suspend fun file(details: MediaDetails): Pair<String?, MediaLoad> {
        val loaded = load(details)
        if (loaded !is MediaLoad.Loaded) return null to loaded
        val path = details.file?.let { files.path(it) } ?: details.attachmentId?.let { files.path(it) }
        return path to loaded
    }

    /**
     * The file, from this phone or downloaded and decrypted (AT3), with why not when it can't be had: offline, gone
     * from the server (past 30 days, or this person left the chat), or damaged (the blob doesn't match the digest the
     * sender signed into the message, or won't decrypt). Only offline and a passing failure are worth retrying.
     */
    suspend fun load(details: MediaDetails): MediaLoad {
        details.file?.let { name -> files.read(name)?.let { return MediaLoad.Loaded(it) } }
        val id = details.attachmentId ?: return MediaLoad.Failed
        files.read(id)?.let { return MediaLoad.Loaded(it) }
        val key = details.key?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return MediaLoad.Damaged
        val digest = details.digest?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return MediaLoad.Damaged
        val link = when (val r = attachments.download(id)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return when {
                r.error is AppError.Network -> MediaLoad.Offline
                (r.error as? AppError.Api)?.code == "messaging.attachment_not_found" -> MediaLoad.Gone
                else -> MediaLoad.Failed
            }
        }
        val response = runCatching { storage.get(link.downloadUrl) }.getOrNull() ?: return MediaLoad.Offline
        if (response.status.value == 404) return MediaLoad.Gone
        val sealed = runCatching { response.takeIf { it.status.isSuccess() }?.readRawBytes() }.getOrNull() ?: return MediaLoad.Failed
        if (!crypto.sha256(sealed).contentEquals(digest)) return MediaLoad.Damaged
        val plain = runCatching { crypto.aesGcmOpen(key, sealed) }.getOrNull() ?: return MediaLoad.Damaged
        files.write(id, plain)
        return MediaLoad.Loaded(plain)
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

}
