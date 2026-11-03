package com.circaso.feature.status.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.MediaFiles
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxHandler
import com.circaso.core.sync.nowMillis
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.status.data.local.MomentEntity
import com.circaso.feature.status.data.local.MomentPrivacyEntity
import com.circaso.feature.status.data.local.MomentsDao
import com.circaso.feature.status.data.local.MutedPosterEntity
import com.circaso.feature.status.data.local.PendingMomentEntity
import com.circaso.feature.status.data.remote.MomentDto
import com.circaso.feature.status.data.remote.MomentsRemote
import com.circaso.feature.status.data.remote.PostMomentRequest
import com.circaso.feature.status.domain.Moment
import com.circaso.feature.status.domain.MomentAudienceMode
import com.circaso.feature.status.domain.MomentDraft
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentMedia
import com.circaso.feature.status.domain.MomentMediaLoad
import com.circaso.feature.status.domain.MomentPrivacy
import com.circaso.feature.status.domain.MomentTextStyle
import com.circaso.feature.status.domain.MomentViewer
import com.circaso.feature.status.domain.MomentsRepository
import com.circaso.feature.status.domain.PendingMoment
import com.circaso.feature.status.domain.PendingState
import io.ktor.client.HttpClient
import com.circaso.core.platform.putFile
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The encrypted body of a moment (references/moments-api.md). Both phones must agree on it. */
@Serializable
internal data class MomentBody(
    val v: Int = 1,
    val kind: String,
    val text: String? = null,
    val background: String? = null,
    val style: String? = null,
    val caption: String? = null,
    val media: MomentMediaBody? = null,
    val thumb: String? = null,
    /** A voice moment's waveform, 0..100 per bar (small whole numbers keep the body light); absent on older phones. */
    val levels: List<Int>? = null,
)

@Serializable
internal data class MomentMediaBody(
    val mediaId: String, val key: String, val digest: String, val mime: String, val sizeBytes: Long,
    val durationMs: Long? = null, val width: Int? = null, val height: Int? = null,
)

/** The composer's choice for a pending moment; the plain media waits in [MediaFiles] under the pending id. */
@Serializable
internal data class PendingDraft(
    val kind: String, val text: String? = null, val background: String? = null, val style: String? = null, val caption: String? = null,
    val mime: String? = null, val thumb: String? = null, val durationMs: Long? = null, val width: Int? = null, val height: Int? = null,
    val levels: List<Int>? = null,
)

@Serializable
internal data class PrivacyJson(val mode: String = MomentAudienceMode.Contacts.name, val except: List<String> = emptyList(), val only: List<String> = emptyList())

@Serializable internal data class MomentJob(val localId: String)

/** Someone in my audience asked for keys to my moments (`status.key_requested`). */
@Serializable internal data class KeyCopyJob(val requesterId: String)

object MomentOutbox {
    const val Post = "moment.post"
    const val KeyCopies = "moment.key_copies"
}

/** Posts queued moments ([MomentsRepositoryImpl.send]); the repository is looked up only when there is one to send. */
class MomentPostHandler(private val repository: () -> MomentsRepositoryImpl) : OutboxHandler {
    override val type: String = MomentOutbox.Post
    override suspend fun send(item: OutboxEntity): AppResult<Unit> = repository().send(item)
}

/** Answers a key request with fresh copies ([MomentsRepositoryImpl.sendKeyCopies]), waiting offline like a post. */
class MomentKeyCopiesHandler(private val repository: () -> MomentsRepositoryImpl) : OutboxHandler {
    override val type: String = MomentOutbox.KeyCopies
    override suspend fun send(item: OutboxEntity): AppResult<Unit> = repository().sendKeyCopies(item)
}

class MomentsRepositoryImpl(
    private val dao: MomentsDao,
    private val remote: MomentsRemote,
    private val keys: MomentKeys,
    private val contacts: ContactsRepository,
    private val crypto: SignalCrypto,
    private val files: MediaFiles,
    /** Plain client for the pre-signed storage URLs (no app headers). */
    private val storage: HttpClient,
    private val outbox: Outbox,
    private val kickOutbox: () -> Unit,
    private val json: Json,
    private val ownUserId: suspend () -> String?,
    private val now: () -> Long = ::nowMillis,
    /** Keeps a media post going when the person leaves Circaso mid-upload. */
    private val uploads: com.circaso.core.platform.BackgroundUploads = com.circaso.core.platform.NoBackgroundUploads,
) : MomentsRepository {
    private val refreshLock = Mutex()

    /** The unreadable moments I already asked each poster about, so I ask again only for a new one (with [askedAt]). */
    private val asked = mutableMapOf<String, Set<String>>()
    private val askedAt = mutableMapOf<String, Long>()

    // Re-read every minute so a moment disappears when its 24 hours end, even with nothing else changing.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val moments: Flow<List<Moment>> = flow { while (true) { emit(now()); kotlinx.coroutines.delay(60_000) } }
        .flatMapLatest { at -> dao.observe(at) }
        .map { rows -> rows.mapNotNull { it.toModel() } }

    /** Upload progress by pending id, in memory only: it means something only while this run is sending it. */
    private val uploadProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Float>>(emptyMap())

    override val pending: Flow<List<PendingMoment>> = kotlinx.coroutines.flow.combine(dao.observePending(), uploadProgress) { rows, progress ->
        rows.map { PendingMoment(it.localId, kindOf(it.kind), stateOf(it.state), it.error, it.createdAt, progress[it.localId]) }
    }

    /** Published in whole percent steps, so a 30 MB upload is a hundred updates, not thousands. */
    private fun setProgress(localId: String, fraction: Float?) {
        uploadProgress.update { now ->
            when {
                fraction == null -> now - localId
                ((now[localId] ?: -1f) * 100).toInt() == (fraction * 100).toInt() -> now
                else -> now + (localId to fraction.coerceIn(0f, 1f))
            }
        }
    }

    override val privacy: Flow<MomentPrivacy> = dao.observePrivacy().map { it.toPrivacy() }

    override val muted: Flow<Set<String>> = dao.observeMuted().map { it.toSet() }

    // ---- reading

    override suspend fun refresh(): AppResult<Unit> = refreshLock.withLock {
        val all = mutableListOf<MomentDto>()
        var after: String? = null
        do {
            when (val page = remote.page(after)) {
                is AppResult.Failure -> return@withLock page
                is AppResult.Success -> { all += page.value.moments; after = page.value.next }
            }
        } while (after != null)
        // Key copies first (a poster's first moment may carry the key the others need), then the bodies. A copy sent
        // after a key request is that moment's body itself.
        val resent = mutableMapOf<String, String>()
        all.filter { !it.mine && it.keyCopy != null && dao.byId(it.momentId)?.body == null }.forEach { dto ->
            keys.learn(dto.posterId, dto.keyCopy!!, dto.momentId)?.let { resent[dto.momentId] = it.decodeToString() }
        }
        val rows = all.map { dto -> merge(dto, dao.byId(dto.momentId), resent[dto.momentId]) }
        dao.upsert(rows)
        askForKeys(rows)
        // Moments gone from the server (deleted, expired, no longer visible) take their decrypted media with them,
        // so "gone after 24 hours" is true on this phone too.
        val keep = rows.map { it.momentId }.toSet()
        dao.observe(0).first().filter { it.momentId !in keep || it.expiresAt <= now() }.forEach { forgetMedia(it) }
        dao.deleteAllExcept(rows.map { it.momentId })
        dao.deleteExpired(now())
        AppResult.Success(Unit)
    }

    /**
     * Someone's live moments this phone still can't open (a new phone: the copies were sealed for my old identity):
     * ask those posters for fresh ones (moments-api.md, key request). Only after a refresh that reached the server,
     * never for my own (no other phone of mine can answer), and once per unreadable moment; the server also allows one
     * per poster every 10 minutes. Best effort: after a failure the same moments are asked about again 10 minutes on.
     */
    private suspend fun askForKeys(rows: List<MomentEntity>) {
        val me = ownUserId() ?: return
        val at = now()
        val unreadable = rows.filter { it.body == null && !it.mine && it.posterId != me && it.expiresAt > at }
            .groupBy({ it.posterId }) { it.momentId }.mapValues { it.value.toSet() }
        val posters = unreadable.filter { (poster, ids) ->
            !asked[poster].orEmpty().containsAll(ids) && at - (askedAt[poster] ?: 0) >= KeyRequestEveryMillis
        }.keys.toList()
        if (posters.isEmpty()) return
        posters.chunked(KeyRequestBatch).forEach { batch ->
            val taken = remote.requestKeys(batch, newIdempotencyKey()) is AppResult.Success
            batch.forEach { if (taken) asked[it] = asked[it].orEmpty() + unreadable.getValue(it); askedAt[it] = at }
        }
    }

    /** A moment from the server, kept readable: a body already opened is never opened again (sender keys move on). */
    private suspend fun merge(dto: MomentDto, old: MomentEntity?, resent: String? = null): MomentEntity {
        val body = old?.body ?: resent ?: if (dto.mine) null else when (val opened = keys.open(dto.posterId, dto.ciphertext)) {
            is OpenedMoment.Plain -> opened.bytes.decodeToString()
            else -> null
        }
        return MomentEntity(
            momentId = dto.momentId, posterId = dto.posterId, body = body,
            ciphertext = if (body == null) dto.ciphertext else null, keyCopy = if (body == null) dto.keyCopy else null,
            postedAt = millis(dto.postedAt), expiresAt = millis(dto.expiresAt), mine = dto.mine,
            viewed = dto.viewed || old?.viewed == true, reacted = dto.reacted, viewCount = dto.viewCount ?: 0,
            reaction = dto.reaction ?: if (dto.reacted) "\u2764\uFE0F" else null, reactionCount = dto.reactionCount ?: 0,
        )
    }

    override suspend fun onFrame(type: String, momentId: String?, posterId: String?, requesterId: String?) {
        when (type) {
            "status.deleted" -> momentId?.let { id -> dao.byId(id)?.let { forgetMedia(it) }; dao.delete(id) }
            // ST5 repeats when a heart changes, so the count comes from the server, never by adding one per frame.
            "status.viewed" -> refresh()
            // Someone's new phone can't open my moments: answer through the outbox, so it waits for a connection.
            "status.key_requested" -> requesterId?.let { queueKeyCopies(it) }
            // status.posted, status.keys_ready: fetch, and open what couldn't be opened before
            else -> refresh()
        }
    }

    private suspend fun queueKeyCopies(requesterId: String) {
        if (requesterId == ownUserId()) return
        val payload = json.encodeToString(KeyCopyJob.serializer(), KeyCopyJob(requesterId))
        // One answer waiting per person is enough: it seals whatever is live when it runs.
        if (payload in outbox.waitingPayloads(MomentOutbox.KeyCopies)) return
        outbox.enqueue(MomentOutbox.KeyCopies, payload)
        kickOutbox()
    }

    /**
     * The outbox's turn for a key request: if the person is still in my audience, seal each live moment I posted from
     * this phone again for their current identity and send the copies. My moments from an earlier phone can't be
     * sealed (this phone never had their bodies) and are left out. A refusal is dropped quietly (nothing for the
     * person to retry); a network or server problem waits for the next drain.
     */
    suspend fun sendKeyCopies(item: OutboxEntity): AppResult<Unit> {
        val job = runCatching { json.decodeFromString(KeyCopyJob.serializer(), item.payload) }.getOrNull() ?: return AppResult.Success(Unit)
        val requester = job.requesterId
        if (requester == ownUserId() || requester !in audience()) return AppResult.Success(Unit)
        val live = dao.mineReadable(now()).map { it.momentId to it.body!!.encodeToByteArray() }
        if (live.isEmpty()) { keys.forgetHolder(requester); return AppResult.Success(Unit) }
        val copies = when (val r = keys.reseal(requester, live)) {
            is AppResult.Failure -> return quietUnlessRetryable(r)
            is AppResult.Success -> r.value
        }
        if (copies.isEmpty()) return AppResult.Success(Unit)
        // A fresh key each try: every try seals anew, and the server just replaces the copies.
        return when (val sent = remote.shareKeyCopies(copies, newIdempotencyKey())) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> quietUnlessRetryable(sent)
        }
    }

    private fun quietUnlessRetryable(r: AppResult.Failure): AppResult<Unit> {
        val e = r.error
        val retryable = e is AppError.Network || e is AppError.Unexpected || (e as AppError.Api).httpStatus >= 500 || e.httpStatus == 429
        return if (retryable) r else AppResult.Success(Unit)
    }

    override suspend fun markViewed(momentId: String, reacted: Boolean?): AppResult<Unit> {
        val row = dao.byId(momentId) ?: return AppResult.Success(Unit)
        if (row.mine) return AppResult.Success(Unit)
        dao.setViewed(momentId, reacted ?: row.reacted)
        return when (val r = remote.view(momentId, reacted, newIdempotencyKey())) {
            is AppResult.Success -> AppResult.Success(Unit)
            // Not taken: put the heart back as it was, so the screen never shows a reaction the poster won't see.
            is AppResult.Failure -> { if (reacted != null) dao.setViewed(momentId, row.reacted); r }
        }
    }

    override suspend fun react(momentId: String, emoji: String?): AppResult<Unit> {
        val row = dao.byId(momentId) ?: return AppResult.Success(Unit)
        if (row.mine) return AppResult.Success(Unit)
        dao.setReaction(momentId, emoji)
        val r = if (emoji == null) remote.view(momentId, false, newIdempotencyKey()) else remote.view(momentId, null, newIdempotencyKey(), reaction = emoji)
        return when (r) {
            is AppResult.Success -> AppResult.Success(Unit)
            // Not taken: back as it was, so the screen never shows a reaction the poster won't see.
            is AppResult.Failure -> { dao.setReaction(momentId, row.reaction); r }
        }
    }

    override suspend fun viewers(momentId: String): AppResult<List<MomentViewer>> = when (val r = remote.views(momentId)) {
        is AppResult.Failure -> r
        is AppResult.Success -> AppResult.Success(r.value.views.map { MomentViewer(it.userId, millis(it.viewedAt), it.reacted, it.reaction ?: if (it.reacted) "\u2764\uFE0F" else null) }.sortedByDescending { it.viewedAt })
            .also { dao.setViewCount(momentId, r.value.views.size) }
    }

    override suspend fun delete(momentId: String): AppResult<Unit> = when (val r = remote.delete(momentId, newIdempotencyKey())) {
        is AppResult.Failure -> if ((r.error as? AppError.Api)?.code == "status.moment_not_found") { dao.delete(momentId); AppResult.Success(Unit) } else r
        is AppResult.Success -> { dao.byId(momentId)?.let { forgetMedia(it) }; dao.delete(momentId); AppResult.Success(Unit) }
    }

    private suspend fun forgetMedia(row: MomentEntity) {
        val body = row.body?.let { runCatching { json.decodeFromString(MomentBody.serializer(), it) }.getOrNull() } ?: return
        body.media?.mediaId?.let { files.delete(it) }
    }

    /** This phone's copy, else ST8, checked against the digest in the body and decrypted; kept for next time. */
    override suspend fun media(moment: Moment): MomentMediaLoad {
        val media = moment.media ?: return MomentMediaLoad.Failed(null)
        files.read(media.mediaId)?.let { return MomentMediaLoad.Loaded(it, files.path(media.mediaId)) }
        val link = when (val r = remote.download(media.mediaId)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return when {
                r.error is AppError.Network -> MomentMediaLoad.NeedsConnection
                (r.error as? AppError.Api)?.code in setOf("status.media_not_found", "status.moment_not_found") -> MomentMediaLoad.Failed(null, gone = true)
                else -> MomentMediaLoad.Failed((r.error as? AppError.Api)?.requestId)
            }
        }
        val sealed = runCatching { storage.get(link.downloadUrl).takeIf { it.status.isSuccess() }?.readRawBytes() }.getOrNull()
            ?: return MomentMediaLoad.NeedsConnection
        val plain = withContext(Dispatchers.Default) {
            runCatching {
                check(crypto.sha256(sealed).contentEquals(Base64.decode(media.digest)))
                crypto.aesGcmOpen(Base64.decode(media.key), sealed)
            }.getOrNull()
        } ?: return MomentMediaLoad.Failed(null)
        files.write(media.mediaId, plain)
        return MomentMediaLoad.Loaded(plain, files.path(media.mediaId))
    }

    // ---- posting

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun post(draft: MomentDraft): AppResult<Unit> {
        val localId = Uuid.random().toString()
        val pending = when (draft) {
            is MomentDraft.Text -> PendingDraft(MomentKind.Text.name, text = draft.text, background = draft.background, style = draft.style.name)
            is MomentDraft.Media -> {
                files.write(localId, draft.bytes)
                PendingDraft(draft.kind.name, caption = draft.caption?.takeIf { it.isNotBlank() }, mime = draft.mime, thumb = draft.thumb?.let { Base64.encode(it) },
                    durationMs = draft.durationMs, width = draft.width, height = draft.height, background = draft.background,
                    levels = draft.levels?.map { (it * 100).toInt().coerceIn(0, 100) })
            }
        }
        dao.savePending(PendingMomentEntity(localId, pending.kind, json.encodeToString(PendingDraft.serializer(), pending), null, null, null, StatePosting, null, now()))
        outbox.enqueue(MomentOutbox.Post, json.encodeToString(MomentJob.serializer(), MomentJob(localId)))
        kickOutbox()
        return AppResult.Success(Unit)
    }

    override suspend fun retry(localId: String) {
        dao.pending(localId) ?: return
        dao.setPendingState(localId, StatePosting, null)
        outbox.enqueue(MomentOutbox.Post, json.encodeToString(MomentJob.serializer(), MomentJob(localId)))
        kickOutbox()
    }

    override suspend fun discard(localId: String) { dao.deletePending(localId); files.delete(localId); setProgress(localId, null) }

    /**
     * The outbox's turn for one pending moment. A photo, video or voice moment runs as background work, so it
     * finishes when the person leaves Circaso mid-upload; if it fails for good while they're away, they're told.
     */
    suspend fun send(item: OutboxEntity): AppResult<Unit> {
        val job = runCatching { json.decodeFromString(MomentJob.serializer(), item.payload) }.getOrNull() ?: return AppResult.Success(Unit)
        val row = dao.pending(job.localId) ?: return AppResult.Success(Unit)
        if (row.kind == MomentKind.Text.name) return sendNow(job)
        return uploads.keep(com.circaso.core.platform.UploadKind.Moment) { sendNow(job) }.also {
            if (dao.pending(job.localId)?.state == StateFailed) uploads.failed(com.circaso.core.platform.UploadKind.Moment)
        }
    }

    /** Upload the media once, seal for the audience, post (ST3). */
    private suspend fun sendNow(job: MomentJob): AppResult<Unit> {
        var row = dao.pending(job.localId) ?: return AppResult.Success(Unit) // discarded meanwhile
        val draft = json.decodeFromString(PendingDraft.serializer(), row.draft)
        dao.setPendingState(row.localId, StatePosting, null)

        val audience = audience()
        if (audience.isEmpty()) return failed(row, AppError.Api("status.audience_invalid", 400, null, null, emptyMap()))

        val kind = kindOf(draft.kind)
        if (kind != MomentKind.Text && row.mediaId == null) {
            (upload(row, draft) as? AppResult.Failure)?.let { return outcome(row, it.error) }
            row = dao.pending(row.localId) ?: return AppResult.Success(Unit)
        }
        val body = MomentBody(
            kind = kind.name, text = draft.text, background = draft.background, style = draft.style, caption = draft.caption, thumb = draft.thumb, levels = draft.levels,
            media = row.mediaId?.let { MomentMediaBody(it, row.mediaKey!!, row.mediaDigest!!, draft.mime ?: "application/octet-stream", files.read(it)?.size?.toLong() ?: 0, draft.durationMs, draft.width, draft.height) },
        )
        val plain = json.encodeToString(MomentBody.serializer(), body).encodeToByteArray()
        val sealed = when (val s = keys.seal(audience, plain)) {
            is AppResult.Failure -> return outcome(row, s.error)
            is AppResult.Success -> s.value
        }
        val request = PostMomentRequest(row.localId, sealed.ciphertext, audience, sealed.keyCopies, listOfNotNull(row.mediaId))
        return when (val r = remote.post(request)) {
            is AppResult.Failure -> outcome(row, r.error)
            is AppResult.Success -> {
                keys.delivered(sealed.distributionId, sealed.copiedTo)
                // My own body is kept as written: I can't decrypt my own sender-key message later.
                dao.upsert(listOf(MomentEntity(r.value.momentId, ownUserId().orEmpty(), plain.decodeToString(), null, null,
                    millis(r.value.postedAt), millis(r.value.expiresAt), mine = true, viewed = true, reacted = false, viewCount = 0)))
                dao.deletePending(row.localId)
                setProgress(row.localId, null)
                files.delete(row.localId) // the plain copy kept for the upload; the media id's copy stays for my viewer
                AppResult.Success(Unit)
            }
        }
    }

    private suspend fun upload(row: PendingMomentEntity, draft: PendingDraft): AppResult<Unit> {
        val plain = files.read(row.localId) ?: return AppResult.Failure(AppError.Api("status.media_missing", 400, null, null, emptyMap()))
        val key = crypto.randomBytes(32)
        val sealed = withContext(Dispatchers.Default) { crypto.aesGcmSeal(key, plain) }
        val kind = when (kindOf(draft.kind)) { MomentKind.Video -> "video"; MomentKind.Voice -> "voice"; else -> "image" }
        val slot = when (val r = remote.prepareMedia(sealed.size.toLong(), kind, newIdempotencyKey())) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        // The ring on "My moment" follows the bytes (M6); the last few percent wait for the post itself.
        setProgress(row.localId, 0f)
        val put = uploads.putFile(storage, slot.uploadUrl, mapOf("Content-Type" to slot.uploadHeaders.contentType), sealed) { sent, total ->
            if (total > 0) setProgress(row.localId, UploadShare * sent / total)
        }
        if (!put) { setProgress(row.localId, null); return AppResult.Failure(AppError.Network) }
        setProgress(row.localId, UploadShare)
        (remote.completeMedia(slot.mediaId, newIdempotencyKey()) as? AppResult.Failure)?.let { return it }
        // Filed under the media id, the name a viewer's copy gets, so my own viewer opens it without downloading.
        files.write(slot.mediaId, plain)
        dao.savePending(row.copy(mediaId = slot.mediaId, mediaKey = Base64.encode(key), mediaDigest = Base64.encode(withContext(Dispatchers.Default) { crypto.sha256(sealed) })))
        return AppResult.Success(Unit)
    }

    /** Who my next moment is for, decided here on the phone: my Circaso contacts, filtered by the privacy setting. */
    private suspend fun audience(): List<String> {
        val me = ownUserId()
        var stored = contacts.contacts.first()
        // A fresh sign-in (or a wiped phone) has no contacts saved yet though the server has them: fetch before
        // deciding that nobody can see this moment.
        if (stored.isEmpty() && contacts.refreshContacts() is AppResult.Success) stored = contacts.contacts.first()
        val people = stored.map { it.person.userId }.filter { it != me }.distinct()
        val p = dao.privacy().toPrivacy()
        return when (p.mode) {
            MomentAudienceMode.Contacts -> people
            MomentAudienceMode.ContactsExcept -> people.filter { it !in p.except }
            MomentAudienceMode.OnlyShareWith -> people.filter { it in p.only }
        }
    }

    /** A retryable failure leaves the moment Waiting (the queue pauses); a refusal marks it Failed (M6). */
    private suspend fun outcome(row: PendingMomentEntity, error: AppError): AppResult<Unit> {
        val retryable = error is AppError.Network || error is AppError.Unexpected ||
            (error as AppError.Api).let { it.httpStatus >= 500 || it.httpStatus == 429 || it.code == "request.in_progress" }
        if (retryable) dao.setPendingState(row.localId, StateWaiting, null) else return failed(row, error)
        return AppResult.Failure(error)
    }

    private suspend fun failed(row: PendingMomentEntity, error: AppError): AppResult<Unit> {
        dao.setPendingState(row.localId, StateFailed, (error as? AppError.Api)?.code ?: "unexpected")
        return AppResult.Failure(error)
    }

    // ---- settings

    override suspend fun setPrivacy(privacy: MomentPrivacy) {
        val before = dao.privacy().toPrivacy()
        dao.savePrivacy(MomentPrivacyEntity(json = json.encodeToString(PrivacyJson.serializer(), PrivacyJson(privacy.mode.name, privacy.except.toList(), privacy.only.toList()))))
        // Anyone who could see before but not now must not read what comes next: a new sender key.
        if (privacy != before) keys.rotate()
    }

    override suspend fun setMuted(userId: String, muted: Boolean) =
        if (muted) dao.mute(MutedPosterEntity(userId, now())) else dao.unmute(userId)

    // ---- mapping

    private fun MomentEntity.toModel(): Moment? {
        val b = body?.let { runCatching { json.decodeFromString(MomentBody.serializer(), it) }.getOrNull() } ?: return null
        return Moment(
            momentId = momentId, posterId = posterId, kind = kindOf(b.kind), text = b.text,
            background = b.background ?: "Ink",
            style = b.style?.let { runCatching { MomentTextStyle.valueOf(it) }.getOrNull() } ?: MomentTextStyle.Bold,
            caption = b.caption, thumb = b.thumb,
            media = b.media?.let { MomentMedia(it.mediaId, it.key, it.digest, it.mime, it.sizeBytes, it.durationMs, it.width, it.height) },
            postedAt = postedAt, expiresAt = expiresAt, mine = mine, viewed = viewed, reacted = reacted, viewCount = viewCount,
            reaction = reaction, reactionCount = reactionCount,
            levels = b.levels?.takeIf { it.isNotEmpty() }?.map { (it / 100f).coerceIn(0f, 1f) },
        )
    }

    private fun MomentPrivacyEntity?.toPrivacy(): MomentPrivacy {
        val stored = this?.json ?: return MomentPrivacy()
        val p = runCatching { this@MomentsRepositoryImpl.json.decodeFromString(PrivacyJson.serializer(), stored) }.getOrNull() ?: return MomentPrivacy()
        return MomentPrivacy(runCatching { MomentAudienceMode.valueOf(p.mode) }.getOrDefault(MomentAudienceMode.Contacts), p.except.toSet(), p.only.toSet())
    }

    private fun millis(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(now())

    private companion object {
        /** How much of the ring the upload fills; sealing and posting (ST3) fill the rest. */
        const val UploadShare = 0.95f
        /** The server's own limit per (requester, poster); asking sooner would only be skipped. */
        const val KeyRequestEveryMillis = 10 * 60 * 1000L
        const val KeyRequestBatch = 100
        const val StatePosting = "posting"
        const val StateWaiting = "waiting"
        const val StateFailed = "failed"
        fun kindOf(name: String) = runCatching { MomentKind.valueOf(name) }.getOrDefault(MomentKind.Text)
        fun stateOf(name: String) = when (name) { StateWaiting -> PendingState.Waiting; StateFailed -> PendingState.Failed; else -> PendingState.Posting }
    }
}
