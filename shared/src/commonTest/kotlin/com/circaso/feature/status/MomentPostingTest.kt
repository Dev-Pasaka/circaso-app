package com.circaso.feature.status

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.core.platform.CapturedMedia
import com.circaso.core.platform.LibraryAccess
import com.circaso.core.platform.LibraryItem
import com.circaso.core.platform.LibraryKind
import com.circaso.core.platform.LibraryRecents
import com.circaso.core.platform.MediaFiles
import com.circaso.core.platform.MediaLibrary
import com.circaso.core.platform.MomentMediaMaxBytes
import com.circaso.core.sync.DrainResult
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxProcessor
import com.circaso.feature.contacts.domain.Connection
import com.circaso.feature.contacts.domain.ConnectVia
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.Invite
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.contacts.domain.SyncSummary
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.status.data.local.MomentEntity
import com.circaso.feature.status.data.local.MomentPrivacyEntity
import com.circaso.feature.status.data.local.MomentsDao
import com.circaso.feature.status.data.local.MutedPosterEntity
import com.circaso.feature.status.data.local.PendingMomentEntity
import com.circaso.feature.status.data.remote.KeyCopiesSentDto
import com.circaso.feature.status.data.remote.KeyRequestedDto
import com.circaso.feature.status.data.remote.MomentDeletedDto
import com.circaso.feature.status.data.remote.MomentKeyCopyDto
import com.circaso.feature.status.data.remote.MomentMediaCompletedDto
import com.circaso.feature.status.data.remote.MomentMediaDownloadDto
import com.circaso.feature.status.data.remote.MomentMediaUploadDto
import com.circaso.feature.status.data.remote.MomentUploadHeaders
import com.circaso.feature.status.data.remote.MomentViewsDto
import com.circaso.feature.status.data.remote.MomentsPageDto
import com.circaso.feature.status.data.remote.MomentsRemote
import com.circaso.feature.status.data.remote.PostMomentRequest
import com.circaso.feature.status.data.remote.PostedMomentDto
import com.circaso.feature.status.data.remote.ViewedDto
import com.circaso.feature.status.data.repository.MomentKeys
import com.circaso.feature.status.data.repository.MomentPostHandler
import com.circaso.feature.status.data.repository.MomentsRepositoryImpl
import com.circaso.feature.status.domain.MomentDraft
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentTextStyle
import com.circaso.feature.status.domain.PendingState
import com.circaso.testing.FakeChatsDao
import com.circaso.testing.FakeKeys
import com.circaso.testing.FakeKeysRemote
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.FakeSealer
import com.circaso.testing.FakeSignalCrypto
import com.circaso.testing.FakeSignalDao
import com.circaso.testing.runSuspending
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Posting like WhatsApp (2026-11-14): Post records the moment and returns; a video is got ready (made smaller when it
 * is over the limit) by the repository's own worker, in order, and survives a restart. The real repository, moment
 * keys and outbox over in-memory fakes; the "phone's disk" is a map shared by the files and the encoder.
 */
class MomentPostingTest {
    private val json = circasoJson()
    private val me = "me"
    private var clock = 1_000_000L

    private val disk = Disk()
    private val files = DiskFiles(disk)
    private val library = Encoder(disk)
    private val dao = PostingDao()
    private val remote = PostingRemote()
    private val outboxDao = FakeOutboxDao()
    private val outbox = Outbox(outboxDao, { me })
    private val chatsDao = FakeChatsDao()
    private val store = SealedRecordStore(FakeSignalDao(), FakeSealer())
    private val crypto = FakeSignalCrypto()
    private val keyDirectory = KeysRepositoryImpl(store, FakeKeysRemote(), crypto, SignedKeyUpload(FakeKeys(), crypto), json)
    private val sessions = SessionCipher(store, crypto, keyDirectory, identityChanged = { chatsDao.deleteDelivery(MomentKeys.Scope, it) })
    private val keys = MomentKeys(store, crypto, chatsDao, sessions, { me })

    /** A repository as one run of the app has it; a second one over the same storage is the app started again. */
    private fun repository() = MomentsRepositoryImpl(
        dao, remote, keys, PostingContacts(listOf("amina")), crypto, files, HttpClient(MockEngine { respondOk() }), outbox, {}, json, { me },
        now = { clock }, library = library,
    )
    private val repo = repository()
    private fun processor(r: MomentsRepositoryImpl = repo) = OutboxProcessor(outbox, listOf(MomentPostHandler { r }), nowMillis = { clock })

    private val big = (MomentMediaMaxBytes + 1).toInt()

    /** A picked video as the composer hands it over: on disk, by its path. */
    private fun video(path: String, size: Int, caption: String? = null): MomentDraft.Media {
        disk.files[path] = ByteArray(size)
        return MomentDraft.Media(MomentKind.Video, ByteArray(0), "video/mp4", caption, ByteArray(1), 50_000, 1080, 1920, path = path)
    }
    private fun photo(caption: String? = null) = MomentDraft.Media(MomentKind.Image, ByteArray(9), "image/jpeg", caption, ByteArray(1), width = 10, height = 10)

    private suspend fun pending() = repo.pending.first()

    @Test fun postReturnsAtOnceWithEachMomentPendingInTheOrderPicked() = runSuspending {
        assertTrue(repo.post(video("/cache/a.mp4", big, "first")) is AppResult.Success)
        assertTrue(repo.post(photo("second")) is AppResult.Success)
        assertTrue(repo.post(video("/cache/c.mp4", 40, "third")) is AppResult.Success)

        val rows = dao.observePending().first()
        assertEquals(listOf("first", "second", "third"), rows.map { json.decodeFromString(com.circaso.feature.status.data.repository.PendingDraft.serializer(), it.draft).caption })
        assertEquals(listOf(PendingState.Preparing, PendingState.Preparing, PendingState.Preparing), pending().map { it.state })
        assertTrue(rows.zipWithNext().all { (a, b) -> a.createdAt < b.createdAt }, "the same millisecond still keeps the order")
        // Nothing was encoded, nothing queued: that is the worker's, after the composer has closed.
        assertEquals(0, library.shrinks.size)
        assertTrue(outboxDao.rows.value.isEmpty())
        // The picked files are the app's own now (moved, not copied), so nothing can clear them before they go.
        assertFalse("/cache/a.mp4" in disk.files)
        assertFalse("/cache/c.mp4" in disk.files)
        assertEquals(big, files.size("source-${rows[0].localId}.mp4")?.toInt())
    }

    @Test fun aVideoOverTheLimitIsAcceptedThenMadeSmallerByTheWorkerAndPosted() = runSuspending {
        repo.post(video("/cache/big.mp4", big, "sunset"))
        val id = pending().single().localId

        repo.resume()
        assertEquals(1, library.shrinks.size, "the whole clip, made smaller once")
        assertEquals(0L to 50_000L, library.shrinks.single().second to library.shrinks.single().third)
        assertEquals(PendingState.Posting, pending().single().state)
        assertEquals(Encoder.SmallerSize, files.size(id)?.toInt(), "the smaller copy is what waits to upload")
        assertNull(files.size("source-$id.mp4"), "the picked file is let go once the smaller one is ready")
        assertTrue(disk.files.keys.none { it.startsWith("/cache/") }, "the encoder's own output is taken in, not left in the cache")

        assertEquals(DrainResult.Drained(1), processor().drain())
        assertEquals(listOf(id), remote.posted.map { it.clientMomentId })
        assertEquals(1, remote.posted.single().mediaIds.size)
        assertTrue(pending().isEmpty())
        val mine = repo.moments.first().single()
        assertTrue(mine.mine)
        assertEquals("sunset", mine.caption)
        assertEquals(720, mine.media?.width, "the body says what was sent, not what was picked")
    }

    @Test fun aVideoUnderTheLimitGoesAsItIsWithoutTheEncoder() = runSuspending {
        repo.post(video("/cache/small.mp4", 40))
        repo.resume()
        assertTrue(library.shrinks.isEmpty())
        assertEquals(40, files.size(pending().single().localId)?.toInt())
        assertEquals(DrainResult.Drained(1), processor().drain())
        assertEquals(1, remote.posted.size)
    }

    @Test fun severalPostInTheOrderPickedThoughTheFirstTakesLongest() = runSuspending {
        repo.post(video("/cache/big.mp4", big))
        repo.post(photo())
        repo.post(video("/cache/small.mp4", 40))
        val ids = pending().map { it.localId }

        repo.resume()
        processor().drain()
        assertEquals(ids, remote.posted.map { it.clientMomentId })
    }

    @Test fun aMomentLeftPreparingIsPickedUpAfterTheAppStartsAgain() = runSuspending {
        repo.post(video("/cache/big.mp4", big))
        val id = pending().single().localId
        // The process died while the video was being made smaller: nothing of the encode is left.
        library.dieOnce = true
        runCatching { repo.resume() }
        assertEquals(PendingState.Preparing, pending().single().state)
        assertEquals(big, files.size("source-$id.mp4")?.toInt(), "the picked file is still the app's own")

        val again = repository()
        again.resume()
        assertEquals(PendingState.Posting, again.pending.first().single().state)
        assertEquals(DrainResult.Drained(1), processor(again).drain())
        assertEquals(listOf(id), remote.posted.map { it.clientMomentId })
    }

    @Test fun aVideoThatCannotBeGotReadyFailsWithWhyAndRetryGetsItReadyAgain() = runSuspending {
        repo.post(video("/cache/big.mp4", big))
        val id = pending().single().localId
        library.unreadable = true
        repo.resume()
        assertEquals(PendingState.Failed to "moment.video_unreadable", pending().single().let { it.state to it.error })
        assertTrue(outboxDao.rows.value.isEmpty(), "nothing half-made is ever queued")

        library.unreadable = false
        repo.retry(id)
        assertEquals(PendingState.Preparing, pending().single().state)
        repo.resume()
        assertEquals(PendingState.Posting, pending().single().state)
        processor().drain()
        assertEquals(listOf(id), remote.posted.map { it.clientMomentId })
    }

    @Test fun aSourceClearedBeforePreparingFailsPlainlyAndDeleteLeavesNothing() = runSuspending {
        repo.post(video("/cache/big.mp4", big))
        repo.post(photo())
        val (first, second) = pending().map { it.localId }
        disk.files.remove(files.path("source-$first.mp4"))

        repo.resume()
        assertEquals(PendingState.Failed to "moment.source_missing", pending().first().let { it.state to it.error })
        // One that can't go never holds the rest back.
        assertEquals(PendingState.Posting, pending()[1].state)
        processor().drain()
        assertEquals(listOf(second), remote.posted.map { it.clientMomentId })

        repo.discard(first)
        assertTrue(pending().isEmpty())
        assertTrue(disk.files.keys.none { first in it })
    }

    @Test fun aFilePostedThatIsAlreadyGoneIsRefusedAndNothingIsRecorded() = runSuspending {
        val draft = video("/cache/gone.mp4", 40)
        disk.files.remove("/cache/gone.mp4")
        assertTrue(repo.post(draft) is AppResult.Failure)
        assertTrue(pending().isEmpty())
    }

    @Test fun offlineAtPostItIsStillGotReadyAndGoesWhenTheConnectionIsBack() = runSuspending {
        remote.online = false
        repo.post(video("/cache/big.mp4", big))
        repo.resume()
        assertEquals(1, library.shrinks.size, "getting ready needs no connection")
        assertTrue(processor().drain() is DrainResult.Paused)
        assertEquals(PendingState.Waiting, pending().single().state)

        remote.online = true
        assertEquals(DrainResult.Drained(1), processor().drain())
        assertEquals(1, library.shrinks.size, "and is never done twice")
        assertEquals(1, remote.posted.size)
    }

    @Test fun theRingOnlyGrowsFromGettingReadyThroughTheUpload() = runSuspending {
        repo.post(video("/cache/big.mp4", big))
        val seen = mutableListOf<Float?>()
        suspend fun look() { seen += pending().singleOrNull()?.progress }
        library.steps = listOf(0.2f, 0.6f, 1f)
        library.onStep = { look() }
        remote.onStep = { look() }

        repo.resume()
        look()
        processor().drain()

        val shown = seen.filterNotNull()
        assertTrue(shown.size >= 6, "the encoder's steps, then the upload's: $seen")
        assertEquals(shown.sorted(), shown, "never backwards: $shown")
        assertEquals(0.1f, shown.first(), "the encoder's own progress fills the first half")
        assertTrue(shown.any { it == 0.5f }, "ready: half-way, before a byte is sent")
        assertEquals(0.95f, shown.last(), "sent: the rest is the post itself")
    }

    @Test fun aTextMomentStillGoesStraightToTheQueue() = runSuspending {
        repo.post(MomentDraft.Text("Habari", "Ink", MomentTextStyle.Bold))
        assertEquals(PendingState.Posting, pending().single().state)
        assertEquals(1, outboxDao.rows.value.size)
        processor().drain()
        assertEquals(1, remote.posted.size)
        assertTrue(remote.posted.single().mediaIds.isEmpty())
    }

    @Test fun aPhotoIsWrittenAtPostAndTheWorkerOnlyQueuesIt() = runSuspending {
        repo.post(photo("lunch"))
        val id = pending().single().localId
        assertEquals(9, files.size(id)?.toInt())
        repo.resume()
        assertTrue(library.shrinks.isEmpty())
        assertEquals(PendingState.Posting, pending().single().state)
        processor().drain()
        assertEquals("lunch", repo.moments.first().single().caption)
        assertNotNull(repo.moments.first().single().media)
    }
}

/** The phone's files by absolute path, for the app's private files and the encoder alike. */
private class Disk { val files = mutableMapOf<String, ByteArray>() }

private class DiskFiles(private val disk: Disk) : MediaFiles {
    private fun at(name: String) = "/files/$name"
    override suspend fun read(name: String) = disk.files[at(name)]
    override suspend fun write(name: String, bytes: ByteArray) { disk.files[at(name)] = bytes }
    override suspend fun clear() { disk.files.keys.removeAll { it.startsWith("/files/") } }
    override suspend fun path(name: String) = at(name).takeIf { it in disk.files }
    override suspend fun delete(name: String) { disk.files.remove(at(name)) }
    override suspend fun copyIn(path: String, name: String): Boolean { disk.files[at(name)] = disk.files[path] ?: return false; return true }
    override suspend fun moveIn(path: String, name: String): Boolean { disk.files[at(name)] = disk.files.remove(path) ?: return false; return true }
}

/** The phone's video encoder: writes a smaller file to the cache and says how far it got. */
private class Encoder(private val disk: Disk) : MediaLibrary {
    val shrinks = mutableListOf<Triple<String, Long, Long>>()
    var unreadable = false
    var dieOnce = false
    var steps = listOf(1f)
    var onStep: suspend () -> Unit = {}

    override suspend fun access() = LibraryAccess.Granted
    override suspend fun recents(kind: LibraryKind, limit: Int, ask: Boolean) = LibraryRecents(emptyList(), LibraryAccess.Granted)
    override suspend fun manage(kind: LibraryKind) = LibraryAccess.Granted
    override suspend fun load(item: LibraryItem): CapturedMedia? = null
    override suspend fun pick(kind: LibraryKind): CapturedMedia? = null
    override suspend fun size(path: String) = disk.files[path]?.size?.toLong()
    override suspend fun shrink(path: String, startMs: Long, endMs: Long, maxBytes: Long, onProgress: (Float) -> Unit): CapturedMedia? {
        if (dieOnce) { dieOnce = false; throw kotlin.coroutines.cancellation.CancellationException("the process died") }
        shrinks += Triple(path, startMs, endMs)
        if (unreadable || path !in disk.files) return null
        steps.forEach { onProgress(it); onStep() }
        val out = "/cache/moment-trim-${shrinks.size}.mp4"
        disk.files[out] = ByteArray(SmallerSize)
        return CapturedMedia(ByteArray(0), "video/mp4", 720, 1280, durationMs = endMs - startMs, thumb = ByteArray(2), path = out)
    }

    companion object { const val SmallerSize = 64 }
}

private class PostingRemote : MomentsRemote {
    var online = true
    val posted = mutableListOf<PostMomentRequest>()
    var onStep: suspend () -> Unit = {}
    private var nextMedia = 1
    private fun <T> offline(): AppResult<T> = AppResult.Failure(AppError.Network)

    override suspend fun page(after: String?, limit: Int) = AppResult.Success(MomentsPageDto(emptyList(), null))
    override suspend fun requestKeys(posterIds: List<String>, idempotencyKey: String) = AppResult.Success(KeyRequestedDto())
    override suspend fun shareKeyCopies(copies: List<MomentKeyCopyDto>, answered: List<String>, idempotencyKey: String) = AppResult.Success(KeyCopiesSentDto(1))
    override suspend fun prepareMedia(sizeBytes: Long, kind: String, idempotencyKey: String): AppResult<MomentMediaUploadDto> {
        onStep()
        return if (online) AppResult.Success(MomentMediaUploadDto("media-${nextMedia++}", "https://storage.test/put", MomentUploadHeaders("application/octet-stream"), "2099-01-01T00:00:00Z")) else offline()
    }
    override suspend fun completeMedia(mediaId: String, idempotencyKey: String): AppResult<MomentMediaCompletedDto> {
        onStep()
        return if (online) AppResult.Success(MomentMediaCompletedDto(mediaId, 1)) else offline()
    }
    override suspend fun post(request: PostMomentRequest): AppResult<PostedMomentDto> {
        onStep()
        if (!online) return offline()
        posted += request
        return AppResult.Success(PostedMomentDto("server-${posted.size}", "2026-11-14T08:00:00Z", "2099-01-01T00:00:00Z"))
    }
    override suspend fun view(momentId: String, reacted: Boolean?, idempotencyKey: String, reaction: String?) = AppResult.Success(ViewedDto(true))
    override suspend fun views(momentId: String) = AppResult.Success(MomentViewsDto())
    override suspend fun delete(momentId: String, idempotencyKey: String) = AppResult.Success(MomentDeletedDto(true))
    override suspend fun download(mediaId: String): AppResult<MomentMediaDownloadDto> = offline()
}

private class PostingContacts(ids: List<String>) : ContactsRepository {
    override val contacts: Flow<List<Contact>> = MutableStateFlow(ids.map { Contact(Person(it, it, null, null, null), "phone", "t") })
    override val blocks: Flow<List<Person>> = MutableStateFlow(emptyList())
    override suspend fun refreshContacts() = AppResult.Success(Unit)
    override suspend fun refreshBlocks() = AppResult.Success(Unit)
    override suspend fun syncPhoneBook(phones: List<String>): AppResult<SyncSummary> = AppResult.Failure(AppError.Network)
    override suspend fun connect(via: ConnectVia, value: String): AppResult<Connection> = AppResult.Failure(AppError.Network)
    override suspend fun createInvite(): AppResult<Invite> = AppResult.Failure(AppError.Network)
    override suspend fun revokeInvite(token: String) = AppResult.Success(Unit)
    override suspend fun removeContact(userId: String) {}
    override suspend fun block(person: Person) {}
    override suspend fun unblock(userId: String) {}
    override suspend fun canReach(userId: String) = AppResult.Success(true)
}

private class PostingDao : MomentsDao {
    val rows = MutableStateFlow<Map<String, MomentEntity>>(emptyMap())
    private val pendingRows = MutableStateFlow<Map<String, PendingMomentEntity>>(emptyMap())
    private val mutedRows = MutableStateFlow<Map<String, MutedPosterEntity>>(emptyMap())
    private val privacyRow = MutableStateFlow<MomentPrivacyEntity?>(null)

    override fun observe(now: Long) = rows.map { r -> r.values.filter { it.expiresAt > now }.sortedBy { it.postedAt } }
    override suspend fun byId(id: String) = rows.value[id]
    override suspend fun unreadableFrom(posterId: String) = rows.value.values.filter { it.body == null && it.posterId == posterId }
    override suspend fun mineReadable(now: Long) = rows.value.values.filter { it.mine && it.body != null && it.expiresAt > now }.sortedBy { it.postedAt }
    override suspend fun upsert(rows: List<MomentEntity>) { this.rows.value = this.rows.value + rows.associateBy { it.momentId } }
    override suspend fun setViewed(id: String, reacted: Boolean) {}
    override suspend fun setReaction(id: String, emoji: String?) {}
    override suspend fun setViewCount(id: String, count: Int) {}
    override suspend fun delete(id: String) { rows.value = rows.value - id }
    override suspend fun deleteAllExcept(keep: List<String>) { rows.value = rows.value.filterKeys { it in keep } }
    override suspend fun deleteExpired(now: Long) { rows.value = rows.value.filterValues { it.expiresAt > now } }
    override fun observePending() = pendingRows.map { it.values.sortedBy { p -> p.createdAt } }
    override suspend fun pending(localId: String) = pendingRows.value[localId]
    override suspend fun savePending(row: PendingMomentEntity) { pendingRows.value = pendingRows.value + (row.localId to row) }
    override suspend fun setPendingState(localId: String, state: String, error: String?) { pendingRows.value[localId]?.let { savePending(it.copy(state = state, error = error)) } }
    override suspend fun deletePending(localId: String) { pendingRows.value = pendingRows.value - localId }
    override fun observeMuted() = mutedRows.map { it.keys.toList() }
    override suspend fun mute(row: MutedPosterEntity) { mutedRows.value = mutedRows.value + (row.userId to row) }
    override suspend fun unmute(userId: String) { mutedRows.value = mutedRows.value - userId }
    override fun observePrivacy() = privacyRow
    override suspend fun privacy() = privacyRow.value
    override suspend fun savePrivacy(row: MomentPrivacyEntity) { privacyRow.value = row }
    override suspend fun clearMoments() { rows.value = emptyMap() }
    override suspend fun clearPending() { pendingRows.value = emptyMap() }
}
