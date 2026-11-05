package com.circaso.feature.messaging.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.platform.BackgroundUploads
import com.circaso.core.platform.GroupPhotoImage
import com.circaso.core.platform.MediaFiles
import com.circaso.core.platform.NoBackgroundUploads
import com.circaso.core.platform.putFile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * Where a group's photo is, as it travels inside the group's sealed metadata (guide M5/M8; the server never reads it).
 * The full photo is a chat attachment (AT1–AT3) encrypted on the phone under its own fresh AES-256-GCM [key]; [digest]
 * is the SHA-256 of that ciphertext, checked before decrypting. [thumb] is a tiny base64 JPEG so lists draw the photo
 * at once and keep drawing it if the full one can't be fetched. Older phones ignore this field (unknown keys).
 */
@Serializable
data class GroupPhotoRef(
    val id: String,
    val key: String,
    val digest: String,
    val size: Long,
    val mime: String = "image/jpeg",
    val width: Int? = null,
    val height: Int? = null,
    val thumb: String? = null,
)

/**
 * Group photos: encrypt and upload a new one into the group's own attachments, and on every phone in the group fetch,
 * check and decrypt it lazily, keeping the decrypted copy on disk. Lists ask [localPath] and get the full photo when it
 * is here, else the thumbnail (and a fetch starts behind it); [changes] ticks when a full photo lands so lists redraw.
 *
 * Durability: the photo change names the attachment as the group's `avatarMediaId` (M8, backend PR #47), and the server
 * keeps that one past the usual 30 days. A group whose photo was set before that (the server says null) shows its
 * thumbnail, never initials, once the old attachment is gone, until an admin sets the photo again.
 */
class GroupPhotos(
    private val attachments: AttachmentsRemote,
    private val storage: HttpClient,
    private val crypto: SignalCrypto,
    private val files: MediaFiles,
    private val scope: CoroutineScope,
    private val uploads: BackgroundUploads = NoBackgroundUploads,
    private val now: () -> Long = { com.circaso.core.sync.nowMillis() },
) {
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val lock = Mutex()
    private val fetching = mutableSetOf<String>()
    /** Gone from the server (past 30 days, or damaged): the thumbnail stays, no more fetches this session. */
    private val unavailable = mutableSetOf<String>()
    /** When a fetch last failed for a passing reason (offline), so a busy chat list doesn't retry on every redraw. */
    private val lastFailure = mutableMapOf<String, Long>()

    /** Encrypts [image] under a fresh key and uploads it to [chatId]'s attachments (AT1, PUT, AT2). */
    suspend fun upload(chatId: String, image: GroupPhotoImage): AppResult<GroupPhotoRef> {
        val key = crypto.randomBytes(32)
        val sealed = crypto.aesGcmSeal(key, image.full)
        // The server takes a group photo of at most 2 MiB of ciphertext; the encoder stays far under it.
        if (sealed.size > MaxCiphertextBytes) return AppResult.Failure(AppError.Api(AvatarInvalid, 400, null, null, emptyMap()))
        val digest = crypto.sha256(sealed)
        val slot = when (val r = attachments.create(chatId, sealed.size.toLong(), "image")) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        val headers = slot.uploadHeaders.toMutableMap()
        if (headers.keys.none { it.equals("Content-Type", true) }) headers["Content-Type"] = "application/octet-stream"
        if (!uploads.putFile(storage, slot.uploadUrl, headers, sealed)) return AppResult.Failure(AppError.Network)
        when (val r = attachments.complete(slot.attachmentId)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return r
        }
        // This phone has both already: no download later, and the new photo shows at once.
        files.write(fullName(slot.attachmentId), image.full)
        files.write(thumbName(slot.attachmentId), image.thumb)
        return AppResult.Success(
            GroupPhotoRef(
                id = slot.attachmentId, key = Base64.encode(key), digest = Base64.encode(digest), size = sealed.size.toLong(),
                width = image.width, height = image.height, thumb = Base64.encode(image.thumb),
            ),
        )
    }

    /**
     * The best picture on this phone for [ref]: the full photo when it is here, otherwise the thumbnail (written from
     * the metadata the first time), with a fetch of the full one started behind it. Null when there is neither.
     * [serverId] is the group's `avatarMediaId`: when it names another attachment than [ref] it is tried first (still
     * opened with [ref]'s key and checked against its digest), then [ref]'s own.
     */
    suspend fun localPath(ref: GroupPhotoRef, serverId: String? = null): String? {
        files.path(fullName(ref.id))?.let { return it }
        startFetch(ref, serverId?.takeIf { it != ref.id })
        files.path(thumbName(ref.id))?.let { return it }
        val thumb = ref.thumb?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
        files.write(thumbName(ref.id), thumb)
        return files.path(thumbName(ref.id))
    }

    /** Somebody changed what lists should draw (a new photo sealed): redraw them. */
    fun notifyChanged() = _changes.update { it + 1 }

    private suspend fun startFetch(ref: GroupPhotoRef, serverId: String?) {
        val go = lock.withLock {
            val failedAt = lastFailure[ref.id]
            if (ref.id in unavailable || ref.id in fetching || (failedAt != null && now() - failedAt < RetryAfterMillis)) false
            else { fetching += ref.id; true }
        }
        if (!go) return
        scope.launch {
            val fromServer = serverId?.let { fetch(ref, it) }
            val result = if (fromServer is MediaLoad.Loaded) fromServer else fetch(ref)
            lock.withLock {
                fetching -= ref.id
                when (result) {
                    MediaLoad.Gone, MediaLoad.Damaged -> unavailable += ref.id
                    is MediaLoad.Loaded -> lastFailure -= ref.id
                    else -> lastFailure[ref.id] = now()
                }
            }
            if (result is MediaLoad.Loaded) notifyChanged()
        }
    }

    /**
     * AT3 for [id] (the group's members only), then the digest from the sealed metadata, then decrypt; the plain photo
     * is kept on disk under [ref]'s id. A size other than the sealed one is another photo: not downloaded.
     */
    internal suspend fun fetch(ref: GroupPhotoRef, id: String = ref.id): MediaLoad {
        val key = runCatching { Base64.decode(ref.key) }.getOrNull() ?: return MediaLoad.Damaged
        val digest = runCatching { Base64.decode(ref.digest) }.getOrNull() ?: return MediaLoad.Damaged
        val link = when (val r = attachments.download(id)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return when {
                r.error is AppError.Network -> MediaLoad.Offline
                (r.error as? AppError.Api)?.code == "messaging.attachment_not_found" -> MediaLoad.Gone
                else -> MediaLoad.Failed
            }
        }
        if (id != ref.id && link.sizeBytes != null && link.sizeBytes != ref.size) return MediaLoad.Damaged
        val response = runCatching { storage.get(link.downloadUrl) }.getOrNull() ?: return MediaLoad.Offline
        if (response.status.value == 404) return MediaLoad.Gone
        val sealed = runCatching { response.takeIf { it.status.isSuccess() }?.readRawBytes() }.getOrNull() ?: return MediaLoad.Failed
        if (!crypto.sha256(sealed).contentEquals(digest)) return MediaLoad.Damaged
        val plain = runCatching { crypto.aesGcmOpen(key, sealed) }.getOrNull() ?: return MediaLoad.Damaged
        files.write(fullName(ref.id), plain)
        return MediaLoad.Loaded(plain)
    }

    private companion object {
        const val RetryAfterMillis = 60_000L
        const val MaxCiphertextBytes = 2 * 1024 * 1024
        const val AvatarInvalid = "messaging.avatar_invalid"
        fun fullName(id: String) = "gphoto-$id"
        fun thumbName(id: String) = "gthumb-$id"
    }
}
