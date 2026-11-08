package com.circaso.feature.status

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.CiphertextKind
import com.circaso.core.crypto.WireFormat
import com.circaso.core.network.circasoJson
import com.circaso.core.platform.MediaFiles
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
import com.circaso.feature.messaging.data.local.SenderKeyDeliveryEntity
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.status.data.local.MomentEntity
import com.circaso.feature.status.data.local.MomentPrivacyEntity
import com.circaso.feature.status.data.local.MomentsDao
import com.circaso.feature.status.data.local.MutedPosterEntity
import com.circaso.feature.status.data.local.PendingMomentEntity
import com.circaso.feature.status.data.remote.KeyCopiesSentDto
import com.circaso.feature.status.data.remote.KeyRequestedDto
import com.circaso.feature.status.data.remote.MomentDeletedDto
import com.circaso.feature.status.data.remote.MomentDto
import com.circaso.feature.status.data.remote.MomentKeyCopyDto
import com.circaso.feature.status.data.remote.MomentMediaCompletedDto
import com.circaso.feature.status.data.remote.MomentMediaDownloadDto
import com.circaso.feature.status.data.remote.MomentMediaUploadDto
import com.circaso.feature.status.data.remote.MomentViewsDto
import com.circaso.feature.status.data.remote.MomentsPageDto
import com.circaso.feature.status.data.remote.MomentsRemote
import com.circaso.feature.status.data.remote.PostMomentRequest
import com.circaso.feature.status.data.remote.PostedMomentDto
import com.circaso.feature.status.data.remote.ViewedDto
import com.circaso.feature.status.data.repository.MomentKeyCopiesHandler
import com.circaso.feature.status.data.repository.MomentKeys
import com.circaso.feature.status.data.repository.MomentKeyRequestPush
import com.circaso.feature.status.data.repository.MomentsRepositoryImpl
import com.circaso.feature.status.domain.LockState
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
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The moments key request (a new phone can't open moments sealed for its old identity), with the fake crypto
 * (ciphertext = "ct:" + plaintext), the real MomentKeys, SessionCipher and outbox, and in-memory fakes.
 */
class MomentKeyRequestTest {
    private val json = circasoJson()
    private val me = "me"
    private val amina = "amina"
    private val baraka = "baraka"
    private var clock = 1_000_000L

    private val chatsDao = FakeChatsDao()
    private val signalDao = FakeSignalDao()
    private val store = SealedRecordStore(signalDao, FakeSealer())
    private val crypto = FakeSignalCrypto()
    private val keysRemote = FakeKeysRemote()
    private val keyDirectory = KeysRepositoryImpl(store, keysRemote, crypto, SignedKeyUpload(FakeKeys(), crypto), json)
    private val sessions = SessionCipher(store, crypto, keyDirectory, identityChanged = { chatsDao.deleteDelivery(MomentKeys.Scope, it) })
    private val keys = MomentKeys(store, crypto, chatsDao, sessions, { me })
    private val dao = KeyRequestDao()
    private val remote = KeyRequestRemote()
    private val contacts = KeyRequestContacts(listOf(amina, baraka))
    private val outbox = Outbox(FakeOutboxDao(), { me })
    /** Whether this phone's own keys are on the server yet (a fresh sign-in publishes them first). */
    private var keysPublished = true
    private val repo = MomentsRepositoryImpl(
        dao, remote, keys, contacts, crypto, KeyRequestFiles, HttpClient(MockEngine { respondOk() }), outbox, {}, json, { me }, now = { clock },
        keysPublished = { keysPublished },
    )
    private val processor = OutboxProcessor(outbox, listOf(MomentKeyCopiesHandler { repo }), nowMillis = { clock })

    private val day = 24 * 60 * 60 * 1000L

    /** A moment under a sender key this phone has never been given. */
    private fun unreadable(id: String, poster: String, keyCopy: String? = null) = MomentDto(
        momentId = id, posterId = poster, keyCopy = keyCopy,
        ciphertext = Base64.encode(WireFormat.encode(WireFormat.Type.SenderKeyMessage, "gct:{}".encodeToByteArray())),
        postedAt = "2026-11-04T08:00:00Z", expiresAt = "2099-01-01T00:00:00Z",
    )

    private fun sealedFor(payload: ChatPayload) =
        Base64.encode(WireFormat.encode(WireFormat.Type.WhisperMessage, "ct:".encodeToByteArray() + ChatPayload.encode(payload)))

    private fun opened(copy: MomentKeyCopyDto): ChatPayload {
        val frame = WireFormat.decode(Base64.decode(copy.ciphertext))!!
        return ChatPayload.decode(frame.body.copyOfRange(3, frame.body.size))!!
    }

    private suspend fun myMoment(id: String, body: String) =
        dao.upsert(listOf(MomentEntity(id, me, body, null, null, clock - 1_000, clock + day, mine = true, viewed = true, reacted = false, viewCount = 0)))

    // ---- viewer side

    @Test fun posterWithUnreadableMomentsIsAskedOnceAndAgainOnlyForANewMomentAfterTenMinutes() = runSuspending {
        remote.moments = listOf(unreadable("m1", amina), unreadable("m2", amina))
        repo.refresh()
        assertEquals(listOf(listOf(amina)), remote.keyRequests)

        repo.refresh()
        assertEquals(1, remote.keyRequests.size, "the same unreadable moments are not asked about twice")

        remote.moments = remote.moments + unreadable("m3", amina)
        clock += 9 * 60 * 1000L
        repo.refresh()
        assertEquals(1, remote.keyRequests.size, "a new one within 10 minutes waits: the server would skip it")

        clock += 60 * 1000L
        repo.refresh()
        assertEquals(listOf(listOf(amina), listOf(amina)), remote.keyRequests)
    }

    @Test fun myOwnUnreadableMomentsAndReadableOnesAreNeverAskedAbout() = runSuspending {
        remote.moments = listOf(unreadable("old", me).copy(mine = true), unreadable("m1", baraka))
        repo.refresh()
        assertEquals(listOf(listOf(baraka)), remote.keyRequests, "only the other poster; my earlier phone's moments can't be recovered")
        assertTrue(repo.moments.first().isEmpty(), "unreadable rows stay hidden, and nothing breaks")
    }

    @Test fun aFailedRequestIsTriedAgainTenMinutesLater() = runSuspending {
        remote.moments = listOf(unreadable("m1", amina))
        remote.requestResult = AppResult.Failure(AppError.Api("request.not_found", 404, null, null, emptyMap()))
        repo.refresh()
        repo.refresh()
        assertEquals(1, remote.keyRequests.size, "an old server (404) isn't asked on every refresh")
        clock += 10 * 60 * 1000L
        remote.requestResult = AppResult.Success(KeyRequestedDto(listOf(amina)))
        repo.refresh()
        assertEquals(2, remote.keyRequests.size)
        clock += 10 * 60 * 1000L
        repo.refresh()
        assertEquals(2, remote.keyRequests.size, "once taken, the same moments aren't asked about again")
    }

    @Test fun keysReadyOpensTheMomentFromItsResealedBody() = runSuspending {
        remote.moments = listOf(unreadable("m1", amina))
        repo.refresh()
        assertTrue(repo.moments.first().isEmpty())

        val body = """{"v":1,"kind":"Text","text":"Habari","background":"Ink","style":"Bold"}"""
        val wrongMoment = sealedFor(ChatPayload(type = ChatPayload.MomentBody, momentId = "m9", text = body))
        remote.moments = listOf(unreadable("m1", amina, keyCopy = wrongMoment))
        repo.onFrame("status.keys_ready", null, amina)
        assertTrue(repo.moments.first().isEmpty(), "a body naming another moment is never shown as this one")

        remote.moments = listOf(unreadable("m1", amina, keyCopy = sealedFor(ChatPayload(type = ChatPayload.MomentBody, momentId = "m1", text = body))))
        repo.onFrame("status.keys_ready", null, amina)
        assertEquals("Habari", repo.moments.first().single().text)
    }

    @Test fun aNewPhoneShowsWhoseMomentsAreOnTheirWayInsteadOfNothing() = runSuspending {
        remote.moments = listOf(unreadable("m1", amina), unreadable("m2", amina))
        repo.refresh()

        assertTrue(repo.moments.first().isEmpty())
        val locked = repo.locked.first()
        assertEquals(listOf("m1", "m2"), locked.map { it.momentId })
        assertTrue(locked.all { it.posterId == amina && it.state == LockState.Waiting })
    }

    @Test fun anAnswerWithoutAMomentMakesItUnavailableAndItIsNotAskedAboutAgain() = runSuspending {
        remote.requestResult = AppResult.Success(KeyRequestedDto(listOf(amina)))
        remote.moments = listOf(unreadable("old", amina), unreadable("m2", amina))
        repo.refresh()
        assertEquals(1, remote.keyRequests.size)

        // Her phone answered with a copy for m2 only: "old" was shared from her earlier phone.
        val body = """{"v":1,"kind":"Text","text":"Habari","background":"Ink","style":"Bold"}"""
        remote.moments = listOf(
            unreadable("old", amina),
            unreadable("m2", amina, keyCopy = sealedFor(ChatPayload(type = ChatPayload.MomentBody, momentId = "m2", text = body))),
        )
        repo.onFrame("status.keys_ready", null, amina)

        assertEquals(listOf("m2"), repo.moments.first().map { it.momentId })
        assertEquals(listOf(LockState.Unavailable), repo.locked.first().map { it.state })
        clock += 60 * 60 * 1000L
        repo.refresh()
        assertEquals(1, remote.keyRequests.size, "her phone already said it has nothing for it")
    }

    @Test fun aKeysReadyThisRunNeverAskedForLeavesTheMomentWaiting() = runSuspending {
        remote.moments = listOf(unreadable("m1", baraka))
        repo.onFrame("status.keys_ready", null, amina) // replayed from before; this phone never asked Amina
        assertEquals(listOf(LockState.Waiting), repo.locked.first().map { it.state })
    }

    @Test fun stillWaitingAfterHalfAnHourAsksAgainAndASkippedRequestAfterTenMinutes() = runSuspending {
        remote.requestResult = AppResult.Success(KeyRequestedDto(listOf(amina)))
        remote.moments = listOf(unreadable("m1", amina))
        repo.refresh()
        clock += 20 * 60 * 1000L
        repo.refresh()
        assertEquals(1, remote.keyRequests.size)
        clock += 10 * 60 * 1000L
        repo.refresh()
        assertEquals(2, remote.keyRequests.size, "her phone may have missed it: asked again after 30 minutes")

        // The server skipped Baraka (asked within its 10 minutes, e.g. before a restart): asked again once that passes.
        remote.requestResult = AppResult.Success(KeyRequestedDto(emptyList()))
        remote.moments = listOf(unreadable("b1", baraka))
        repo.refresh()
        clock += 10 * 60 * 1000L
        repo.refresh()
        assertEquals(listOf(listOf(amina), listOf(amina), listOf(baraka), listOf(baraka)), remote.keyRequests)
    }

    // ---- cold launch and fresh sign-in (Pasaka, 2026-11-08: contacts' moments visible at once)

    @Test fun aColdLaunchShowsStoredRingsBeforeAnyNetwork() = runSuspending {
        // Last run left one readable moment and one still waiting for keys.
        dao.upsert(listOf(
            MomentEntity("r1", amina, """{"v":1,"kind":"Text","text":"hi"}""", null, null, clock - 1_000, clock + day, mine = false, viewed = false, reacted = false, viewCount = 0),
            MomentEntity("w1", baraka, null, "x", null, clock - 1_000, clock + day, mine = false, viewed = false, reacted = false, viewCount = 0),
        ))
        assertEquals(listOf("r1"), repo.moments.first().map { it.momentId })
        assertEquals(listOf("w1" to LockState.Waiting), repo.locked.first().map { it.momentId to it.state })
        assertTrue(remote.keyRequests.isEmpty(), "nothing asked of the server yet")
    }

    @Test fun aFreshSignInAsksForKeysInTheSameRefreshThatListsTheMoments() = runSuspending {
        remote.moments = listOf(unreadable("m1", amina), unreadable("m2", baraka))
        repo.refresh()
        assertEquals(listOf(amina, baraka), remote.keyRequests.single().sorted(), "one request for every poster, straight away")
        assertEquals(2, repo.locked.first().size, "both rings show, waiting")
    }

    @Test fun aFreshSignInWaitsForItsOwnKeysBeforeAsking() = runSuspending {
        keysPublished = false
        remote.moments = listOf(unreadable("m1", amina))
        repo.refresh()
        assertTrue(remote.keyRequests.isEmpty(), "Amina's phone would seal for my old phone's keys")
        assertEquals(1, repo.locked.first().size, "the ring shows anyway")

        keysPublished = true
        repo.refresh()
        assertEquals(listOf(listOf(amina)), remote.keyRequests)
    }

    // ---- poster side

    @Test fun aKeyRequestRefreshesTheSessionClearsTheDeliveryAndSendsAFreshCopyPerMoment() = runSuspending {
        myMoment("mine-1", """{"v":1,"kind":"Text","text":"one"}""")
        myMoment("mine-2", """{"v":1,"kind":"Text","text":"two"}""")
        // Amina's old phone: a session and identity this phone trusted, and she counted as holding my key.
        store.rememberPeerIdentity(amina, "old-identity".encodeToByteArray())
        store.saveSession(amina, "old-session".encodeToByteArray())
        chatsDao.insertDeliveries(listOf(SenderKeyDeliveryEntity(MomentKeys.Scope, amina, "d1", 0), SenderKeyDeliveryEntity(MomentKeys.Scope, baraka, "d1", 0)))

        repo.onFrame("status.key_requested", null, null, requesterId = amina)
        repo.onFrame("status.key_requested", null, null, requesterId = amina)
        assertEquals(1, outbox.waiting(com.circaso.feature.status.data.repository.MomentOutbox.KeyCopies), "one answer waiting per person")
        processor.drain()

        assertContentEquals("session:1".encodeToByteArray(), store.session(amina), "the old session was dropped for one with her new identity")
        assertContentEquals(Base64.decode("aWQ="), store.peerIdentity(amina)!!.identityKey)
        assertEquals(listOf(baraka), chatsDao.deliveredTo(MomentKeys.Scope, "d1"), "she no longer counts as holding my key; Baraka still does")
        val copies = remote.sharedCopies.single()
        assertEquals(listOf("mine-1", "mine-2"), copies.map { it.momentId })
        assertTrue(copies.all { it.userId == amina })
        val first = opened(copies.first())
        assertEquals(ChatPayload.MomentBody, first.type)
        assertEquals("mine-1", first.momentId)
        assertEquals("""{"v":1,"kind":"Text","text":"one"}""", first.text)
    }

    @Test fun aKeyRequestOfflineWaitsInTheOutboxAndIsSentOnceOnline() = runSuspending {
        myMoment("mine-1", """{"v":1,"kind":"Text","text":"one"}""")
        remote.shareResult = AppResult.Failure(AppError.Network)
        repo.onFrame("status.key_requested", null, null, requesterId = amina)
        processor.drain()
        assertEquals(1, outbox.waiting(com.circaso.feature.status.data.repository.MomentOutbox.KeyCopies))

        remote.shareResult = AppResult.Success(KeyCopiesSentDto(1))
        processor.drain()
        assertEquals(0, outbox.waiting(com.circaso.feature.status.data.repository.MomentOutbox.KeyCopies))
        assertEquals(2, remote.sharedCopies.size)
    }

    @Test fun someoneNoLongerInMyAudienceGetsNothing() = runSuspending {
        myMoment("mine-1", """{"v":1,"kind":"Text","text":"one"}""")
        chatsDao.insertDeliveries(listOf(SenderKeyDeliveryEntity(MomentKeys.Scope, "stranger", "d1", 0)))
        repo.onFrame("status.key_requested", null, null, requesterId = "stranger")
        processor.drain()
        assertTrue(remote.sharedCopies.isEmpty())
        assertEquals(listOf("stranger"), chatsDao.deliveredTo(MomentKeys.Scope, "d1"))
    }

    @Test fun withNoMomentsPostedFromThisPhoneTheAnswerHasNoCopiesButNamesHerAndMyNextMomentCopiesTheKey() = runSuspending {
        dao.upsert(listOf(MomentEntity("from-old-phone", me, null, "x", null, clock, clock + day, mine = true, viewed = true, reacted = false, viewCount = 0)))
        chatsDao.insertDeliveries(listOf(SenderKeyDeliveryEntity(MomentKeys.Scope, amina, "d1", 0)))
        repo.onFrame("status.key_requested", null, null, requesterId = amina)
        processor.drain()
        // Before 2026-11-08 nothing was sent, so her new phone waited in silence for moments that could never come.
        assertEquals(listOf(emptyList<MomentKeyCopyDto>()), remote.sharedCopies, "no copies: this phone never had those bodies")
        assertEquals<List<String>>(listOf(amina), remote.answered, "but she is told, so her phone stops waiting")
        assertTrue(chatsDao.deliveredTo(MomentKeys.Scope, "d1").isEmpty())
    }

    @Test fun anAnswerWithCopiesNamesTheRequesterToo() = runSuspending {
        myMoment("mine-1", """{"v":1,"kind":"Text","text":"one"}""")
        repo.onFrame("status.key_requested", null, null, requesterId = amina)
        processor.drain()
        assertEquals(listOf("mine-1"), remote.sharedCopies.single().map { it.momentId })
        assertEquals<List<String>>(listOf(amina), remote.answered)
    }

    @Test fun theSilentPushAnswersAtOnceWithCircasoClosed() = runSuspending {
        myMoment("mine-1", """{"v":1,"kind":"Text","text":"one"}""")
        val handled = MomentKeyRequestPush.handle(
            mapOf("kind" to "moment.keys_requested", "userId" to amina), repository = { repo }, drain = { processor.drain() }, windowMillis = 5_000,
        )
        assertTrue(handled)
        assertEquals(listOf("mine-1"), remote.sharedCopies.single().map { it.momentId }, "sent within the push, not on the next open")
        assertEquals(0, outbox.waiting(com.circaso.feature.status.data.repository.MomentOutbox.KeyCopies))

        assertFalse(MomentKeyRequestPush.handle(mapOf("kind" to "moment.loved"), repository = { repo }, drain = { processor.drain() }, windowMillis = 5_000))
        assertEquals(1, remote.sharedCopies.size, "any other push is left to the usual handling")
    }

    // ---- identity changes seen in chats

    @Test fun aChatMessageFromANewIdentityClearsTheirMomentDelivery() = runSuspending {
        store.rememberPeerIdentity(amina, "old-identity".encodeToByteArray())
        chatsDao.insertDeliveries(listOf(SenderKeyDeliveryEntity(MomentKeys.Scope, amina, "d1", 0), SenderKeyDeliveryEntity(MomentKeys.Scope, baraka, "d1", 0), SenderKeyDeliveryEntity("chat-1", amina, "g1", 0)))

        val opened = sessions.open(amina, CiphertextKind.Whisper, "ct:".encodeToByteArray() + ChatPayload.encode(ChatPayload(type = ChatPayload.Text, text = "hi")))

        assertTrue((opened as com.circaso.feature.messaging.data.repository.Opened.Plain).identityChanged)
        assertEquals(listOf(baraka), chatsDao.deliveredTo(MomentKeys.Scope, "d1"))
        assertEquals(listOf(amina), chatsDao.deliveredTo("chat-1", "g1"), "group chats keep their own deliveries")
    }

    @Test fun sendingToANewIdentityAndKeysChangedClearTheirMomentDeliveryToo() = runSuspending {
        store.rememberPeerIdentity(amina, "old-identity".encodeToByteArray())
        chatsDao.insertDeliveries(listOf(SenderKeyDeliveryEntity(MomentKeys.Scope, amina, "d1", 0), SenderKeyDeliveryEntity(MomentKeys.Scope, baraka, "d1", 0)))
        val sealed = sessions.seal(amina, "hi".encodeToByteArray())
        assertTrue((sealed as AppResult.Success).value.identityChanged)
        assertEquals(listOf(baraka), chatsDao.deliveredTo(MomentKeys.Scope, "d1"))

        sessions.reset(baraka) // keys.changed
        assertTrue(chatsDao.deliveredTo(MomentKeys.Scope, "d1").isEmpty())
        assertNotNull(store.session(amina))
    }
}

private object KeyRequestFiles : MediaFiles {
    override suspend fun read(name: String): ByteArray? = null
    override suspend fun write(name: String, bytes: ByteArray) {}
    override suspend fun clear() {}
}

private class KeyRequestContacts(ids: List<String>) : ContactsRepository {
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

private class KeyRequestRemote : MomentsRemote {
    var moments: List<MomentDto> = emptyList()
    val keyRequests = mutableListOf<List<String>>()
    var requestResult: AppResult<KeyRequestedDto> = AppResult.Success(KeyRequestedDto())
    val sharedCopies = mutableListOf<List<MomentKeyCopyDto>>()
    val answered = mutableListOf<String>()
    var shareResult: AppResult<KeyCopiesSentDto> = AppResult.Success(KeyCopiesSentDto(1))

    override suspend fun page(after: String?, limit: Int) = AppResult.Success(MomentsPageDto(moments, null))
    override suspend fun requestKeys(posterIds: List<String>, idempotencyKey: String): AppResult<KeyRequestedDto> { keyRequests += posterIds; return requestResult }
    override suspend fun shareKeyCopies(copies: List<MomentKeyCopyDto>, answered: List<String>, idempotencyKey: String): AppResult<KeyCopiesSentDto> {
        sharedCopies += copies; this.answered.addAll(answered); return shareResult
    }
    override suspend fun prepareMedia(sizeBytes: Long, kind: String, idempotencyKey: String): AppResult<MomentMediaUploadDto> = AppResult.Failure(AppError.Network)
    override suspend fun completeMedia(mediaId: String, idempotencyKey: String): AppResult<MomentMediaCompletedDto> = AppResult.Failure(AppError.Network)
    override suspend fun post(request: PostMomentRequest): AppResult<PostedMomentDto> = AppResult.Failure(AppError.Network)
    override suspend fun view(momentId: String, reacted: Boolean?, idempotencyKey: String, reaction: String?): AppResult<ViewedDto> = AppResult.Success(ViewedDto(true))
    override suspend fun views(momentId: String) = AppResult.Success(MomentViewsDto())
    override suspend fun delete(momentId: String, idempotencyKey: String) = AppResult.Success(MomentDeletedDto(true))
    override suspend fun download(mediaId: String): AppResult<MomentMediaDownloadDto> = AppResult.Failure(AppError.Network)
}

private class KeyRequestDao : MomentsDao {
    val rows = MutableStateFlow<Map<String, MomentEntity>>(emptyMap())
    private val pendingRows = MutableStateFlow<Map<String, PendingMomentEntity>>(emptyMap())
    private val mutedRows = MutableStateFlow<Map<String, MutedPosterEntity>>(emptyMap())
    private val privacyRow = MutableStateFlow<MomentPrivacyEntity?>(null)

    override fun observe(now: Long) = rows.map { r -> r.values.filter { it.expiresAt > now }.sortedBy { it.postedAt } }
    override suspend fun byId(id: String) = rows.value[id]
    override suspend fun unreadableFrom(posterId: String) = rows.value.values.filter { it.body == null && it.posterId == posterId }
    override suspend fun mineReadable(now: Long) = rows.value.values.filter { it.mine && it.body != null && it.expiresAt > now }.sortedBy { it.postedAt }
    override suspend fun upsert(rows: List<MomentEntity>) { this.rows.value = this.rows.value + rows.associateBy { it.momentId } }
    override suspend fun setViewed(id: String, reacted: Boolean) { rows.value[id]?.let { upsert(listOf(it.copy(viewed = true, reacted = reacted))) } }
    override suspend fun setReaction(id: String, emoji: String?) { rows.value[id]?.let { upsert(listOf(it.copy(reaction = emoji, reacted = emoji != null))) } }
    override suspend fun setViewCount(id: String, count: Int) { rows.value[id]?.let { upsert(listOf(it.copy(viewCount = count))) } }
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
