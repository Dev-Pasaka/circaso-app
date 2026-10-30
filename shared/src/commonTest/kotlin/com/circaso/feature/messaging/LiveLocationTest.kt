package com.circaso.feature.messaging

import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.WireFormat
import com.circaso.core.platform.GeoPoint
import com.circaso.feature.messaging.data.local.ChatEntity
import com.circaso.feature.messaging.data.local.ChatMemberEntity
import com.circaso.feature.messaging.data.local.MessageEntity
import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.MessageStatus
import com.circaso.feature.messaging.data.remote.InboxMessageDto
import com.circaso.feature.messaging.data.remote.InboxResponse
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.repository.ChatSealer
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.data.repository.InboxProcessor
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.repository.LiveLocations
import com.circaso.feature.messaging.data.repository.LiveTracker
import com.circaso.feature.messaging.data.repository.MediaDetails
import com.circaso.feature.messaging.data.repository.MessagesRepositoryImpl
import com.circaso.feature.messaging.data.repository.ChatsRepositoryImpl
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.core.sync.Outbox
import com.circaso.core.realtime.RealtimeCursors
import com.circaso.core.realtime.RealtimeClient
import com.circaso.core.realtime.SocketFactory
import com.circaso.core.network.circasoJson
import com.circaso.testing.FakeChatsDao
import com.circaso.testing.FakeChatsRemote
import com.circaso.testing.FakeKeys
import com.circaso.testing.FakeKeysRemote
import com.circaso.testing.FakeMessagesDao
import com.circaso.testing.FakeMessagesRemote
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.FakeSealer
import com.circaso.testing.FakeSignalCrypto
import com.circaso.testing.FakeSignalDao
import com.circaso.testing.runSuspending
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live location, WhatsApp-style: one message whose pin moves, updates that never become messages, and an end. */
@OptIn(ExperimentalEncodingApi::class)
class LiveLocationTest {
    private val json = circasoJson()
    private val me = "me"
    private val amina = "amina"
    private val chatsDao = FakeChatsDao()
    private val messagesDao = FakeMessagesDao(chatsDao)
    private val store = SealedRecordStore(FakeSignalDao(), FakeSealer())
    private val crypto = FakeSignalCrypto()
    private val keys = KeysRepositoryImpl(store, FakeKeysRemote(), crypto, SignedKeyUpload(FakeKeys(), crypto), json)
    private val sessions = SessionCipher(store, crypto, keys)
    private val remote = FakeMessagesRemote()
    private val groupKeys = GroupKeys(store, crypto, chatsDao, sessions, remote, json, { me })
    private val chats = ChatsRepositoryImpl(chatsDao, messagesDao, FakeChatsRemote(), groupKeys, sessions, { me })
    private val realtime = RealtimeClient(SocketFactory { error("no socket") }, "wss://x", { null }, NoCursors, json)
    private val inbox = InboxProcessor(messagesDao, chatsDao, remote, sessions, groupKeys, realtime, { chats.refreshChat(it) })
    private val messages = MessagesRepositoryImpl(messagesDao, chatsDao, remote, inbox, Outbox(FakeOutboxDao(), { me }), {}, realtime, json, { me })

    private object NoCursors : RealtimeCursors {
        override suspend fun lastSeq(module: String) = 0L
        override suspend fun setLastSeq(module: String, seq: Long) {}
        override suspend fun clear() {}
    }

    private var clock = 1_000_000L
    private val tracker = object : LiveTracker {
        var running: Int? = null
        override fun start(chats: Int) { running = chats }
        override fun stop() { running = null }
    }
    private val live = LiveLocations(
        messagesDao, chatsDao, remote, ChatSealer(messagesDao, chatsDao, sessions, groupKeys, { me }, { clock }),
        startShare = { chatId, point, liveId, until ->
            messagesDao.insert(
                MessageEntity(
                    clientMessageId = "cm-$liveId", messageId = null, chatId = chatId, senderId = me, outgoing = true, kind = MessageKind.Location,
                    text = MediaDetails.write(MediaDetails(MediaDetails.Location, latitude = point.latitude, longitude = point.longitude, liveId = liveId, liveUntil = until, liveAt = clock)),
                    replyTo = null, status = MessageStatus.Waiting, errorCode = null, cachedRequest = null, sentAt = null, orderAt = clock, createdAt = clock,
                ),
            )
        },
        tracker = tracker, now = { clock },
    )

    private suspend fun directChat() {
        chatsDao.upsertChat(ChatEntity("c1", MessagingWire.Direct, null, null, 0, 1, false, null, false, 0, 1))
        chatsDao.insertMembers(listOf(ChatMemberEntity("c1", me, "MEMBER", "Me", null, null, "t"), ChatMemberEntity("c1", amina, "MEMBER", "Amina", "amina", null, "t")))
    }

    private fun wire(p: ChatPayload) = Base64.encode(WireFormat.encode(WireFormat.Type.WhisperMessage, "ct:".encodeToByteArray() + ChatPayload.encode(p)))

    private suspend fun receive(id: String, p: ChatPayload) {
        remote.inboxPages += InboxResponse(listOf(InboxMessageDto(id, "c1", amina, MessagingWire.KindMessage, wire(p), emptyList(), "2026-10-30T10:00:00Z")), null)
        messages.syncInbox()
    }

    private fun details() = MediaDetails.read(messagesDao.rows.value.single { it.kind == MessageKind.Location }.text)!!

    /** What was sent, opened again: the fake cipher writes "ct:" before the plaintext. */
    private fun sentPayload(i: Int): ChatPayload {
        val sealed = Base64.decode(remote.sent[i].second.recipients!!.single().ciphertext)
        return ChatPayload.decode(WireFormat.decode(sealed)!!.body.drop(3).toByteArray())!!
    }

    private val start = ChatPayload(type = ChatPayload.LiveLocation, latitude = -1.29, longitude = 36.82, liveId = "L1", liveUntil = 5_000_000, liveAt = 100)

    @Test fun anIncomingShareIsOneMessageWhosePinMovesAndThenEnds() = runSuspending {
        directChat()
        receive("m1", start)
        assertEquals("L1", details().liveId)

        receive("m2", ChatPayload(type = ChatPayload.LiveUpdate, liveId = "L1", liveAt = 200, latitude = -1.30, longitude = 36.83))
        assertEquals(-1.30, details().latitude)
        assertEquals(1, messagesDao.rows.value.size, "an update is never a message of its own")

        receive("m3", ChatPayload(type = ChatPayload.LiveUpdate, liveId = "L1", liveAt = 150, latitude = 9.0, longitude = 9.0))
        assertEquals(-1.30, details().latitude, "an older update doesn't move the pin back")

        receive("m4", ChatPayload(type = ChatPayload.LiveStop, liveId = "L1", liveAt = 300))
        assertTrue(details().liveEnded == true)
        receive("m5", ChatPayload(type = ChatPayload.LiveUpdate, liveId = "L1", liveAt = 400, latitude = 9.0, longitude = 9.0))
        assertEquals(-1.30, details().latitude, "nothing moves after the end")
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), remote.acked)
    }

    @Test fun anUpdateForAShareNeverSeenIsDropped() = runSuspending {
        directChat()
        receive("m1", ChatPayload(type = ChatPayload.LiveUpdate, liveId = "nope", liveAt = 200, latitude = 1.0, longitude = 1.0))
        assertTrue(messagesDao.rows.value.isEmpty())
        assertEquals(listOf("m1"), remote.acked)
    }

    @Test fun sharingSendsThrottledUpdatesThenAStopAndTheTrackerFollows() = runSuspending {
        directChat()
        live.start("c1", GeoPoint(-1.29, 36.82), LiveLocations.Durations.first())
        assertEquals(1, tracker.running)
        val row = messagesDao.rows.value.single()

        live.onFix(GeoPoint(-1.291, 36.821))
        assertEquals(-1.291, details().latitude, "my own pin moves at once")
        assertTrue(remote.sent.isEmpty(), "nobody can place an update before the share's message has gone")

        messagesDao.markSent(row.localId, "srv-share", "2026-10-30T10:00:00Z", clock, MessageStatus.Sent)
        clock += 1_000
        live.onFix(GeoPoint(-1.292, 36.822))
        val update = sentPayload(0)
        assertEquals(ChatPayload.LiveUpdate, update.type)
        assertEquals(-1.292, update.latitude)
        assertFalse(remote.sent[0].second.pushable, "updates never ring anyone's phone")

        clock += 5_000
        live.onFix(GeoPoint(-1.300, 36.830))
        assertEquals(1, remote.sent.size, "not more often than every 15 seconds")

        clock += 20_000
        live.onFix(GeoPoint(-1.2921, 36.8221))
        assertEquals(1, remote.sent.size, "a few metres isn't a move")
        clock += LiveLocations.HeartbeatMillis
        live.onFix(GeoPoint(-1.2921, 36.8221))
        assertEquals(2, remote.sent.size, "but a standing phone still says it's there now and then")

        live.stop(row.localId)
        assertEquals(ChatPayload.LiveStop, sentPayload(2).type)
        assertTrue(details().liveEnded == true)
        assertNull(tracker.running)
    }

    @Test fun whenTheTimeRunsOutTheShareEndsForEveryone() = runSuspending {
        directChat()
        live.start("c1", GeoPoint(-1.29, 36.82), 60_000)
        val row = messagesDao.rows.value.single()
        messagesDao.markSent(row.localId, "srv-share", "2026-10-30T10:00:00Z", clock, MessageStatus.Sent)
        clock += 61_000
        live.onFix(GeoPoint(-1.29, 36.82))
        assertTrue(details().liveEnded == true)
        assertEquals(ChatPayload.LiveStop, sentPayload(remote.sent.lastIndex).type)
        assertNull(tracker.running)
    }

    @Test fun aSecondShareInTheSameChatReplacesTheFirst() = runSuspending {
        directChat()
        live.start("c1", GeoPoint(-1.29, 36.82), 60_000)
        val first = messagesDao.rows.value.single()
        messagesDao.markSent(first.localId, "srv-1", "2026-10-30T10:00:00Z", clock, MessageStatus.Sent)
        live.start("c1", GeoPoint(-1.29, 36.82), 60_000)
        val rows = messagesDao.rows.value.map { MediaDetails.read(it.text)!! }
        assertEquals(listOf(true, null), rows.map { it.liveEnded })
        assertEquals(1, tracker.running)
    }

    @Test fun stoppingAShareThatNeverWentWithdrawsIt() = runSuspending {
        directChat()
        live.start("c1", GeoPoint(-1.29, 36.82), 60_000)
        live.stop(messagesDao.rows.value.single().localId)
        assertTrue(messagesDao.rows.value.isEmpty(), "nobody saw it start, so nothing is left to end")
        assertTrue(remote.sent.isEmpty())
    }

    @Test fun aStopMadeOfflineIsSentWhenTheConnectionReturns() = runSuspending {
        directChat()
        live.start("c1", GeoPoint(-1.29, 36.82), 60_000)
        val row = messagesDao.rows.value.single()
        messagesDao.markSent(row.localId, "srv-share", "2026-10-30T10:00:00Z", clock, MessageStatus.Sent)
        remote.sendResult = { AppResult.Failure(AppError.Network) }
        live.stop(row.localId)
        assertEquals(false, details().liveStopSent, "ended here, but the others don't know yet")

        remote.sendResult = { AppResult.Success(com.circaso.feature.messaging.data.remote.SentMessageResponse("srv-x", "2026-10-30T10:01:00Z")) }
        live.flushStops()
        assertEquals(true, details().liveStopSent)
        assertEquals(ChatPayload.LiveStop, sentPayload(remote.sent.lastIndex).type)
        val before = remote.sent.size
        live.flushStops()
        assertEquals(before, remote.sent.size, "told once")
    }

}
