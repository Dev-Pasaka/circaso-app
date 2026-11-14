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
import com.circaso.feature.status.domain.LockState
import com.circaso.feature.status.domain.LockedMoment
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
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    /** How far the media's upload got, so a post cut off by the process dying resumes it (ResumableUpload). */
    val upload: com.circaso.core.platform.UploadState? = null,
    /**
     * While the moment is still being got ready (state `preparing`): the picked file's name in [MediaFiles], taken in
     * at Post. Null once the file to upload is there under the pending id (and for a photo or voice, written at Post).
     */
    val source: String? = null,
    /** The video was made smaller before uploading: that took the first part of the ring ([MomentsRepositoryImpl.PrepareShare]). */
    val encoded: Boolean = false,
    /** How many times the encoder has been tried and couldn't (it is tried again before the moment is failed). */
    val tries: Int = 0,
    /**
     * The size the next encode is planned for, once a pass has come out over the limit on this phone
     * ([com.circaso.core.platform.MomentVideoEncoding.smallerBudget]); kept so a restart doesn't repeat that pass.
     */
    val budget: Long? = null,
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
    /**
     * Makes sure this phone's encryption keys are on the server (publishing them if not) before posters are asked for
     * moment keys: a poster seals for whatever key bundle the server has, and right after a sign-in on a new phone
     * that may still be the old phone's, which this one could never open.
     */
    private val keysPublished: suspend () -> Boolean = { true },
    /** Keeps a media post going when the person leaves Circaso mid-upload. */
    private val uploads: com.circaso.core.platform.BackgroundUploads = com.circaso.core.platform.NoBackgroundUploads,
    /** Makes a video over the limit smaller once Post has closed the composer (the trimmer's encoder); null in tests without one. */
    private val library: com.circaso.core.platform.MediaLibrary? = null,
    /** The app's own scope: getting a moment ready outlives the screen that posted it. */
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
) : MomentsRepository {
    private val refreshLock = Mutex()
    /** One moment is got ready at a time, in the order they were posted. */
    private val preparing = Mutex()
    private val postLock = Mutex()
    private val resumable = com.circaso.core.platform.ResumableUpload(crypto, files, uploads, storage, now)

    /**
     * The unreadable moments I asked each poster about and when, so the same ones are asked about again only after
     * [KeyRequestAgainMillis] (the poster's phone may have been off; the request itself waits in the server's replay).
     * In memory: after a restart they are asked about once more, which the server limits to one per 10 minutes.
     */
    private val asked = mutableMapOf<String, Set<String>>()
    private val askedAt = mutableMapOf<String, Long>()

    /**
     * Moments whose poster's phone answered my key request (`status.keys_ready`) and which still don't open: they never
     * will here (shared from the poster's earlier phone), so they show as unavailable, not waiting, and aren't asked
     * about again. By moment id, so the phone's clock never matters.
     */
    private val unavailable = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    // Re-read every minute so a moment disappears when its 24 hours end, even with nothing else changing.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val live: Flow<List<MomentEntity>> = flow { while (true) { emit(now()); kotlinx.coroutines.delay(60_000) } }
        .flatMapLatest { at -> dao.observe(at) }

    override val moments: Flow<List<Moment>> = live.map { rows -> rows.mapNotNull { it.toModel() } }

    override val locked: Flow<List<LockedMoment>> = kotlinx.coroutines.flow.combine(live, unavailable) { rows, gone ->
        rows.filter { it.body == null && !it.mine }.map { row ->
            LockedMoment(row.momentId, row.posterId, row.postedAt, row.expiresAt, if (row.momentId in gone) LockState.Unavailable else LockState.Waiting)
        }
    }

    /** Upload progress by pending id, in memory only: it means something only while this run is sending it. */
    private val uploadProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Float>>(emptyMap())

    override val pending: Flow<List<PendingMoment>> = kotlinx.coroutines.flow.combine(dao.observePending(), uploadProgress) { rows, progress ->
        rows.map { PendingMoment(it.localId, kindOf(it.kind), stateOf(it.state), it.error, it.createdAt, progress[it.localId]) }
    }

    /** Published in whole percent steps, so a 30 MB upload (or a minute of encoding) is a hundred updates, not thousands. */
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

    override suspend fun refresh(): AppResult<Unit> = refresh(answeredBy = null)

    /** [answeredBy]: a poster whose phone just answered my key request (see [unavailable]). */
    private suspend fun refresh(answeredBy: String?): AppResult<Unit> = refreshLock.withLock {
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
        // Only an answer to a request this run made: a frame replayed from before can't speak for this phone's keys.
        answeredBy?.takeIf { it in askedAt }?.let { poster ->
            val still = rows.filter { it.posterId == poster && it.body == null && !it.mine }.map { it.momentId }
            unavailable.update { it + still }
        }
        dao.upsert(rows)
        askForKeys(rows)
        // Moments gone from the server (deleted, expired, no longer visible) take their decrypted media with them,
        // so "gone after 24 hours" is true on this phone too.
        val keep = rows.map { it.momentId }.toSet()
        unavailable.update { it intersect keep }
        dao.observe(0).first().filter { it.momentId !in keep || it.expiresAt <= now() }.forEach { forgetMedia(it) }
        dao.deleteAllExcept(rows.map { it.momentId })
        dao.deleteExpired(now())
        AppResult.Success(Unit)
    }

    /**
     * Someone's live moments this phone still can't open (a new phone: the copies were sealed for my old identity):
     * ask those posters for fresh ones (moments-api.md, key request). Only after a refresh that reached the server,
     * never for my own (no other phone of mine can answer), and once per unreadable moment, again after
     * [KeyRequestAgainMillis] while they still don't open; the server allows one per poster every 10 minutes. Best
     * effort: after a failure the same moments are asked about again 10 minutes on.
     */
    private suspend fun askForKeys(rows: List<MomentEntity>) {
        val me = ownUserId() ?: return
        val at = now()
        val gone = unavailable.value
        // Moments the poster's phone already answered without can't come from it: asking again would only wake it.
        val unreadable = rows.filter { it.body == null && !it.mine && it.posterId != me && it.expiresAt > at && it.momentId !in gone }
            .groupBy({ it.posterId }) { it.momentId }.mapValues { it.value.toSet() }
        val posters = unreadable.filter { (poster, ids) ->
            val since = at - (askedAt[poster] ?: 0)
            since >= KeyRequestEveryMillis && (!asked[poster].orEmpty().containsAll(ids) || since >= KeyRequestAgainMillis)
        }.keys.toList()
        if (posters.isEmpty()) return
        // Not yet: the next refresh (each reconnect, each frame) asks once they are.
        if (!keysPublished()) return
        posters.chunked(KeyRequestBatch).forEach { batch ->
            // Only the posters the server says it asked count as asked: one it skipped (asked within its 10 minutes,
            // perhaps by this phone before a restart) is asked again once that has passed.
            val requested = (remote.requestKeys(batch, newIdempotencyKey()) as? AppResult.Success)?.value?.requested?.toSet().orEmpty()
            batch.forEach { if (it in requested) asked[it] = asked[it].orEmpty() + unreadable.getValue(it); askedAt[it] = at }
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

    private val viewed = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val viewedFrames: Flow<String> = viewed

    override suspend fun onFrame(type: String, momentId: String?, posterId: String?, requesterId: String?) {
        when (type) {
            "status.deleted" -> momentId?.let { id -> dao.byId(id)?.let { forgetMedia(it) }; dao.delete(id) }
            // ST5 repeats when a heart changes, so the count comes from the server, never by adding one per frame.
            "status.viewed" -> { refresh(); momentId?.let { viewed.tryEmit(it) } }
            // A poster's phone answered my key request: open what came; what still won't open never will here.
            "status.keys_ready" -> refresh(answeredBy = posterId)
            // Someone's new phone can't open my moments: answer through the outbox, so it waits for a connection.
            "status.key_requested" -> requesterId?.let { queueKeyCopies(it) }
            // status.posted, resync: fetch, and open what couldn't be opened before
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
     * sealed (this phone never had their bodies) and are left out, but the answer still names the person
     * (`answered`), so their phone stops waiting for those and says they can't be opened there. A refusal is dropped
     * quietly (nothing for the person to retry); a network or server problem waits for the next drain.
     */
    suspend fun sendKeyCopies(item: OutboxEntity): AppResult<Unit> {
        val job = runCatching { json.decodeFromString(KeyCopyJob.serializer(), item.payload) }.getOrNull() ?: return AppResult.Success(Unit)
        val requester = job.requesterId
        if (requester == ownUserId() || requester !in audience()) return AppResult.Success(Unit)
        val live = dao.mineReadable(now()).map { it.momentId to it.body!!.encodeToByteArray() }
        val copies = if (live.isEmpty()) {
            keys.forgetHolder(requester)
            emptyList()
        } else when (val r = keys.reseal(requester, live)) {
            is AppResult.Failure -> return quietUnlessRetryable(r)
            is AppResult.Success -> r.value
        }
        // A fresh key each try: every try seals anew, and the server just replaces the copies.
        return when (val sent = remote.shareKeyCopies(copies, listOf(requester), newIdempotencyKey())) {
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

    /**
     * This phone's copy, else ST8, checked against the digest in the body and decrypted; kept for next time. A video
     * is handed over by its file alone (never read into memory: it is up to 64 MiB since 2026-11-14); the download
     * goes straight to a file, and where the phone can it is opened from file to file too ([SingleBoxFile]).
     */
    override suspend fun media(moment: Moment): MomentMediaLoad {
        val media = moment.media ?: return MomentMediaLoad.Failed(null)
        loaded(moment, media.mediaId)?.let { return it }
        val link = when (val r = remote.download(media.mediaId)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return when {
                r.error is AppError.Network -> MomentMediaLoad.NeedsConnection
                (r.error as? AppError.Api)?.code in setOf("status.media_not_found", "status.moment_not_found") -> MomentMediaLoad.Failed(null, gone = true)
                else -> MomentMediaLoad.Failed((r.error as? AppError.Api)?.requestId)
            }
        }
        val sealed = "dl-${media.mediaId}"
        if (!download(link.downloadUrl, sealed)) { files.delete(sealed); return MomentMediaLoad.NeedsConnection }
        val key = runCatching { Base64.decode(media.key) }.getOrNull()
        val digest = runCatching { Base64.decode(media.digest) }.getOrNull()
        val opened = if (key == null || digest == null) false else runCatching {
            com.circaso.core.crypto.SingleBoxFile.open(crypto, files, key, sealed, media.mediaId, digest) ?: withContext(Dispatchers.Default) {
                // No streaming on this phone (iPhone): the one-shot, in memory.
                val bytes = files.read(sealed) ?: return@withContext false
                check(crypto.sha256(bytes).contentEquals(digest))
                files.write(media.mediaId, crypto.aesGcmOpen(key, bytes))
                true
            }
        }.getOrDefault(false)
        files.delete(sealed)
        if (!opened) return MomentMediaLoad.Failed(null)
        return loaded(moment, media.mediaId) ?: MomentMediaLoad.Failed(null)
    }

    /** The decrypted file on this phone as the viewer takes it: a video by its path alone, a photo or voice by its bytes. */
    private suspend fun loaded(moment: Moment, name: String): MomentMediaLoad? {
        val path = files.path(name)
        if (moment.kind == MomentKind.Video && path != null) return MomentMediaLoad.Loaded(ByteArray(0), path)
        return files.read(name)?.let { MomentMediaLoad.Loaded(it, path) }
    }

    /** The ciphertext from storage into [name], a piece at a time. False when the connection or storage failed. */
    private suspend fun download(url: String, name: String): Boolean = try {
        files.delete(name)
        storage.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) return@execute false
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(com.circaso.core.platform.StreamPieceBytes)
            while (true) {
                var filled = 0
                while (filled < buffer.size) {
                    val n = channel.readAvailable(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled > 0) files.append(name, buffer.copyOf(filled))
                if (filled < buffer.size) break
            }
            // An empty body still leaves a file, so the open says it isn't a box rather than "not there".
            if (files.size(name) == null) files.write(name, ByteArray(0))
            true
        }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    // ---- posting

    /**
     * Post (2026-11-14, Pasaka: "the same experience as WhatsApp"): the moment is recorded as pending and this
     * returns, so the composer closes at once. Text goes straight to the outbox. A photo, video or voice is written
     * (or, a video on disk, moved: a rename, however large) into the app's private files and left `preparing`; the
     * worker ([prepareWaiting]) gets each ready in the order posted and hands it to the outbox, whatever screen is
     * showing, and again after a restart.
     */
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun post(draft: MomentDraft): AppResult<Unit> = postLock.withLock {
        val localId = Uuid.random().toString()
        // Strictly after everything already waiting: several posted in the same millisecond keep their order.
        val at = maxOf(now(), (dao.observePending().first().maxOfOrNull { it.createdAt } ?: 0L) + 1)
        when (draft) {
            is MomentDraft.Text -> {
                val pending = PendingDraft(MomentKind.Text.name, text = draft.text, background = draft.background, style = draft.style.name)
                dao.savePending(PendingMomentEntity(localId, pending.kind, json.encodeToString(PendingDraft.serializer(), pending), null, null, null, StatePosting, null, at))
                outbox.enqueue(MomentOutbox.Post, json.encodeToString(MomentJob.serializer(), MomentJob(localId)))
                kickOutbox()
            }
            is MomentDraft.Media -> {
                val fromDisk = draft.bytes.isEmpty() && draft.path != null
                // The extension stays: the phone's encoder tells a video by it (AVFoundation).
                val source = if (fromDisk) "source-$localId.${draft.path!!.substringAfterLast('/').substringAfterLast('.', "mp4").take(8)}" else null
                if (source != null) { if (!files.moveIn(draft.path!!, source)) return@withLock AppResult.Failure(AppError.Api(SourceMissing, 400, null, null, emptyMap())) }
                else files.write(localId, draft.bytes)
                val pending = PendingDraft(draft.kind.name, caption = draft.caption?.takeIf { it.isNotBlank() }, mime = draft.mime, thumb = draft.thumb?.let { Base64.encode(it) },
                    durationMs = draft.durationMs, width = draft.width, height = draft.height, background = draft.background,
                    levels = draft.levels?.map { (it * 100).toInt().coerceIn(0, 100) }, source = source)
                dao.savePending(PendingMomentEntity(localId, pending.kind, json.encodeToString(PendingDraft.serializer(), pending), null, null, null, StatePreparing, null, at))
                kickPreparing()
            }
        }
        AppResult.Success(Unit)
    }

    override suspend fun resume() = prepareWaiting()

    /** Retry (M6): a moment that failed while being got ready is got ready again; one that failed posting is posted again. */
    override suspend fun retry(localId: String) {
        val row = dao.pending(localId) ?: return
        val draft = runCatching { json.decodeFromString(PendingDraft.serializer(), row.draft) }.getOrNull()
        if (draft?.source != null) {
            dao.savePending(row.copy(draft = json.encodeToString(PendingDraft.serializer(), draft.copy(tries = 0, budget = null)), state = StatePreparing, error = null))
            kickPreparing()
            return
        }
        dao.setPendingState(localId, StatePosting, null)
        outbox.enqueue(MomentOutbox.Post, json.encodeToString(MomentJob.serializer(), MomentJob(localId)))
        kickOutbox()
    }

    override suspend fun discard(localId: String) {
        val source = dao.pending(localId)?.let { runCatching { json.decodeFromString(PendingDraft.serializer(), it.draft) }.getOrNull()?.source }
        dao.deletePending(localId)
        files.delete(localId); files.delete("sealed-$localId"); source?.let { files.delete(it) }
        setProgress(localId, null)
    }

    // ---- getting ready (after Post, outside any screen)

    /** In the app's scope when there is one; a test without one calls [prepareWaiting] itself. */
    private fun kickPreparing() { scope?.launch { prepareWaiting() } }

    /**
     * Gets every `preparing` moment ready, oldest first, one at a time, and hands each to the outbox as it is ready,
     * so they post in the order they were picked. Runs as background work ([BackgroundUploads.keep]: Android's
     * foreground service, iOS's background time), so leaving Circaso doesn't stop it. A process that dies part-way
     * loses nothing: the row is still `preparing` with its file in the app's private files, and the next launch
     * ([resume]) starts that one again. Each row leaves `preparing` (ready, or failed with why) before the next; an
     * encoder that couldn't this time (an iPhone stops encoding once Circaso is in the background) leaves its row
     * `preparing`, the ones behind it waiting their turn, and is tried again a little later ([PrepareAgainMillis])
     * and on the next launch or reconnect, [PrepareTries] times in all before it is failed.
     */
    internal suspend fun prepareWaiting(): Unit = preparing.withLock {
        if (nextPreparing() == null) return@withLock
        val again = uploads.keep(com.circaso.core.platform.UploadKind.Moment) {
            while (true) {
                val row = nextPreparing() ?: return@keep false
                val moved = try {
                    prepare(row)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    failPreparing(row, PrepareFailed); true
                }
                if (!moved) return@keep true
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (again) scope?.launch { kotlinx.coroutines.delay(PrepareAgainMillis); prepareWaiting() }
    }

    private suspend fun nextPreparing(): PendingMomentEntity? = dao.observePending().first().firstOrNull { it.state == StatePreparing }

    /**
     * One moment: its file under the pending id at a size the server takes, then the outbox. False when it is still
     * `preparing` afterwards (the encoder is to be tried again later); true when it is ready, failed or gone.
     */
    private suspend fun prepare(row: PendingMomentEntity): Boolean {
        val id = row.localId
        var draft = json.decodeFromString(PendingDraft.serializer(), row.draft)
        val source = draft.source
        if (files.size(id) == null) {
            // Nothing ready and nothing to make it from: the file was cleared from under the app.
            val size = source?.let { files.size(it) } ?: return failPreparing(row, SourceMissing)
            val video = kindOf(draft.kind) == MomentKind.Video
            when {
                size <= limit() -> if (!files.move(source, id)) return failPreparing(row, SourceMissing)
                !video -> return failPreparing(row, TooLarge)
                else -> {
                    // A minute shot at a high bit rate: the same clip at the moments' bit rate, as large as the limit
                    // allows. A pass that comes out over the limit (a hardware encoder running past its bit rate) is
                    // never the person's problem: it is done again, planned smaller by as much as it overshot.
                    val max = limit()
                    val path = files.path(source)
                    var budget = draft.budget?.coerceIn(1, max) ?: max
                    var made: com.circaso.core.platform.CapturedMedia? = null
                    for (pass in 0 until EncodePasses) {
                        // Each pass has its own stretch of the ring's first half, so the arc never goes back.
                        val from = encodeShare(pass)
                        val to = encodeShare(pass + 1)
                        setProgress(id, from)
                        val smaller = if (path == null || library == null) null else library.shrink(
                            path, 0, draft.durationMs ?: com.circaso.core.platform.MomentMediaMaxMs, max, budget,
                        ) { done -> setProgress(id, from + (to - from) * done.coerceIn(0f, 1f)) }
                        val out = smaller?.path
                        val outSize = out?.let { library?.size(it) } ?: 0
                        if (smaller == null || out == null || outSize <= 0) {
                            // Couldn't this time. Not yet the person's problem: it is tried again before it is failed.
                            val tries = draft.tries + 1
                            if (tries >= PrepareTries) return failPreparing(row, VideoUnreadable)
                            saveDraft(id) { it.copy(tries = tries) }
                            return false
                        }
                        if (outSize <= max) {
                            if (!files.moveIn(out, id)) return failPreparing(row, VideoUnreadable)
                            made = smaller
                            break
                        }
                        // Over: the encoder's file is taken in and deleted (so out of the cache), and the next pass is smaller.
                        files.moveIn(out, "over-$id"); files.delete("over-$id")
                        budget = com.circaso.core.platform.MomentVideoEncoding.smallerBudget(budget, outSize, max)
                        saveDraft(id) { it.copy(budget = budget) }
                    }
                    val smaller = made ?: return failPreparing(row, VideoTooLarge)
                    draft = draft.copy(
                        mime = smaller.mime, thumb = smaller.thumb?.let { Base64.encode(it) } ?: draft.thumb, durationMs = smaller.durationMs ?: draft.durationMs,
                        width = smaller.width ?: draft.width, height = smaller.height ?: draft.height, encoded = true,
                    )
                }
            }
        }
        source?.let { files.delete(it) }
        // Discarded while it was being made: nothing of it stays.
        val fresh = dao.pending(id) ?: run { files.delete(id); setProgress(id, null); return true }
        dao.savePending(fresh.copy(draft = json.encodeToString(PendingDraft.serializer(), draft.copy(source = null)), state = StatePosting, error = null))
        setProgress(id, if (draft.encoded) PrepareShare else 0f)
        outbox.enqueue(MomentOutbox.Post, json.encodeToString(MomentJob.serializer(), MomentJob(id)))
        kickOutbox()
        return true
    }

    /**
     * The most a moment's file may be for the server this phone is talking to: [MomentMediaMaxBytes] (64 MiB), or
     * what an older server takes once it has refused a larger one ([olderServer]).
     */
    private fun limit(): Long = if (olderServer) com.circaso.core.platform.MomentMediaLegacyMaxBytes else com.circaso.core.platform.MomentMediaMaxBytes

    /**
     * The server refused a video over 32 MiB at ST1 (`status.media_invalid`): it hasn't been updated to 64 MiB yet
     * (2026-11-14). Remembered while the app runs, so the next videos are made to fit straight away; a new launch
     * asks again, which is how the app finds out the server has been updated.
     */
    private var olderServer = false

    /**
     * ST1 refused this moment's video for its size, which an updated server would take: it goes back to be got ready,
     * made to fit what the older server takes, and is posted again by the worker. The video already made ready is
     * what it is made from (its picked file is gone by now), so against an older server it may be encoded twice.
     * True when it was sent back; false when this isn't that case (the failure stands).
     */
    private suspend fun fitOlderServer(row: PendingMomentEntity, error: AppError): Boolean {
        if ((error as? AppError.Api)?.code != "status.media_invalid" || kindOf(row.kind) != MomentKind.Video) return false
        val size = files.size(row.localId) ?: return false
        if (size <= com.circaso.core.platform.MomentMediaLegacyMaxBytes) return false
        olderServer = true
        val source = "source-${row.localId}.mp4"
        if (!files.move(row.localId, source)) return false
        files.delete("sealed-${row.localId}")
        val fresh = dao.pending(row.localId) ?: run { files.delete(source); return true }
        val draft = json.decodeFromString(PendingDraft.serializer(), fresh.draft).copy(source = source, upload = null, budget = null, tries = 0)
        dao.savePending(fresh.copy(draft = json.encodeToString(PendingDraft.serializer(), draft), state = StatePreparing, error = null, mediaId = null, mediaKey = null, mediaDigest = null))
        setProgress(row.localId, 0f)
        kickPreparing()
        return true
    }

    /** Couldn't be got ready: Failed with why (M6's row offers Retry and Delete), and told if the person is away. */
    private suspend fun failPreparing(row: PendingMomentEntity, code: String): Boolean {
        setProgress(row.localId, null)
        dao.setPendingState(row.localId, StateFailed, code)
        if (dao.pending(row.localId) != null) uploads.failed(com.circaso.core.platform.UploadKind.Moment)
        return true
    }

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
            // The queue has moved: anything still to be got ready (left by an earlier run) is picked up.
            kickPreparing()
        }
    }

    /**
     * Upload the media once, seal for the audience, post (ST3). The file the server holds is deleted if no moment
     * takes it within an hour of ST1 (a post that waited offline): then it is uploaded again, once, and posted.
     */
    private suspend fun sendNow(job: MomentJob, mayUploadAgain: Boolean = true): AppResult<Unit> {
        var row = dao.pending(job.localId) ?: return AppResult.Success(Unit) // discarded meanwhile
        val draft = json.decodeFromString(PendingDraft.serializer(), row.draft)
        dao.setPendingState(row.localId, StatePosting, null)

        val audience = audience()
        if (audience.isEmpty()) return failed(row, AppError.Api("status.audience_invalid", 400, null, null, emptyMap()))

        val kind = kindOf(draft.kind)
        if (kind != MomentKind.Text && row.mediaId == null) {
            (upload(row, draft) as? AppResult.Failure)?.let {
                // An older server's size limit is not the person's problem: the video is made to fit it and sent again.
                if (fitOlderServer(row, it.error)) return AppResult.Success(Unit)
                return outcome(row, it.error)
            }
            row = dao.pending(row.localId) ?: return AppResult.Success(Unit)
        }
        val body = MomentBody(
            kind = kind.name, text = draft.text, background = draft.background, style = draft.style, caption = draft.caption, thumb = draft.thumb, levels = draft.levels,
            media = row.mediaId?.let { MomentMediaBody(it, row.mediaKey!!, row.mediaDigest!!, draft.mime ?: "application/octet-stream", files.size(it) ?: 0, draft.durationMs, draft.width, draft.height) },
        )
        val plain = json.encodeToString(MomentBody.serializer(), body).encodeToByteArray()
        val sealed = when (val s = keys.seal(audience, plain)) {
            is AppResult.Failure -> return outcome(row, s.error)
            is AppResult.Success -> s.value
        }
        val request = PostMomentRequest(row.localId, sealed.ciphertext, audience, sealed.keyCopies, listOfNotNull(row.mediaId))
        return when (val r = remote.post(request)) {
            is AppResult.Failure -> if (mayUploadAgain && row.mediaId != null && (r.error as? AppError.Api)?.code == "status.media_not_found") {
                dao.savePending(row.copy(mediaId = null, mediaKey = null, mediaDigest = null))
                sendNow(job, mayUploadAgain = false)
            } else outcome(row, r.error)
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

    /**
     * Encrypt, ST1, PUT, ST2, step by step with each step saved in the draft, so a post cut off by the process dying
     * (MIUI, a swipe away) resumes where it stopped: same ciphertext, same slot while its URL lasts.
     */
    private suspend fun upload(row: PendingMomentEntity, draft: PendingDraft): AppResult<Unit> {
        val kind = when (kindOf(draft.kind)) { MomentKind.Video -> "video"; MomentKind.Voice -> "voice"; else -> "image" }
        // The ring on "My moment" follows the bytes (M6), after the part getting a video ready took; the last few
        // percent wait for the post itself.
        val base = if (draft.encoded) PrepareShare else 0f
        setProgress(row.localId, base)
        val done = when (val r = resumable.run(
            plainFile = row.localId, sealedFile = "sealed-" + row.localId, state = draft.upload,
            save = { state -> saveDraft(row.localId) { it.copy(upload = state) } },
            create = { size ->
                when (val slot = remote.prepareMedia(size, kind, newIdempotencyKey())) {
                    is AppResult.Failure -> slot
                    is AppResult.Success -> AppResult.Success(com.circaso.core.platform.UploadSlot(slot.value.mediaId, slot.value.uploadUrl,
                        mapOf("Content-Type" to slot.value.uploadHeaders.contentType), runCatching { Instant.parse(slot.value.expiresAt).toEpochMilliseconds() }.getOrNull()))
                }
            },
            complete = { id -> when (val c = remote.completeMedia(id, newIdempotencyKey())) { is AppResult.Failure -> c; is AppResult.Success -> AppResult.Success(Unit) } },
            startOver = { e -> (e as? AppError.Api)?.code in setOf("status.media_invalid", "status.media_not_found") },
            missing = AppError.Api("status.media_missing", 400, null, null, emptyMap()),
            onProgress = { sent, total -> if (total > 0) setProgress(row.localId, base + (UploadShare - base) * sent / total) },
        )) {
            // Waiting or failed: the ring holds where the encoder left it, so a second try never starts from nothing.
            is AppResult.Failure -> { setProgress(row.localId, base.takeIf { it > 0f }); return r }
            is AppResult.Success -> r.value
        }
        setProgress(row.localId, UploadShare)
        // Filed under the media id, the name a viewer's copy gets, so my own viewer opens it without downloading.
        // (a copy through the file system where the phone has one, never the whole video through memory)
        if (files.path(row.localId)?.let { files.copyIn(it, done.id) } != true) files.read(row.localId)?.let { files.write(done.id, it) }
        val fresh = dao.pending(row.localId) ?: return AppResult.Success(Unit)
        val clean = json.decodeFromString(PendingDraft.serializer(), fresh.draft).copy(upload = null)
        dao.savePending(fresh.copy(draft = json.encodeToString(PendingDraft.serializer(), clean),
            mediaId = done.id, mediaKey = Base64.encode(done.key), mediaDigest = Base64.encode(done.digest)))
        return AppResult.Success(Unit)
    }

    private suspend fun saveDraft(localId: String, change: (PendingDraft) -> PendingDraft) {
        val row = dao.pending(localId) ?: return
        val draft = json.decodeFromString(PendingDraft.serializer(), row.draft)
        dao.savePending(row.copy(draft = json.encodeToString(PendingDraft.serializer(), change(draft))))
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
        /** Where the ring is when the upload is done; sealing and posting (ST3) fill the rest. */
        const val UploadShare = 0.95f
        /**
         * Where the ring is when a video has been made smaller (only a video over the limit goes through that): the
         * encoder's own progress fills up to here where the phone reports it, and the upload fills on from here. A
         * fair share: encoding a minute of video takes about as long as sending 32 MB.
         */
        const val PrepareShare = 0.5f
        /** The server's own limit per (requester, poster); asking sooner would only be skipped. */
        const val KeyRequestEveryMillis = 10 * 60 * 1000L
        /** The same unreadable moments are asked about again after this, in case the poster's phone missed it. */
        const val KeyRequestAgainMillis = 30 * 60 * 1000L
        const val KeyRequestBatch = 100
        /**
         * A video is encoded at most this many times, each planned smaller than the last came out, before it is
         * failed as one this phone can't make small enough (which a minute of video should never reach: the third
         * pass is planned at well under half the limit, at a smaller size).
         */
        const val EncodePasses = 4
        /** Where the ring is as encode pass [pass] begins: the first takes four fifths of [PrepareShare], each later one four fifths of what is left. */
        fun encodeShare(pass: Int): Float { var left = 1f; repeat(pass) { left *= 0.2f }; return PrepareShare * (1f - left) }
        /** The encoder is tried this many times in all before a video is failed as one this phone can't get ready. */
        const val PrepareTries = 3
        /** How long after the encoder couldn't it is tried again (also at the next launch and reconnect). */
        const val PrepareAgainMillis = 20_000L
        const val StatePreparing = "preparing"
        const val StatePosting = "posting"
        const val StateWaiting = "waiting"
        const val StateFailed = "failed"
        fun kindOf(name: String) = runCatching { MomentKind.valueOf(name) }.getOrDefault(MomentKind.Text)
        fun stateOf(name: String) = when (name) { StatePreparing -> PendingState.Preparing; StateWaiting -> PendingState.Waiting; StateFailed -> PendingState.Failed; else -> PendingState.Posting }
        /** Why a moment couldn't be got ready (the pending row's error; MomentsList's `postError` has the words). */
        const val SourceMissing = "moment.source_missing"
        const val VideoUnreadable = "moment.video_unreadable"
        const val PrepareFailed = "moment.prepare_failed"
        /** Every encode pass came out over the limit. */
        const val VideoTooLarge = "moment.video_too_large"
        const val TooLarge = "status.moment_too_large"
    }
}
