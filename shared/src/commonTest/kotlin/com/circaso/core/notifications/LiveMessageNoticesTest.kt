package com.circaso.core.notifications

import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.WireFormat
import com.circaso.core.network.circasoJson
import com.circaso.core.platform.ChatNotifications
import com.circaso.core.realtime.RealtimeClient
import com.circaso.core.realtime.RealtimeCursors
import com.circaso.core.realtime.SocketFactory
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.ChatEntity
import com.circaso.feature.messaging.data.local.ChatMemberEntity
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.remote.InboxMessageDto
import com.circaso.feature.messaging.data.remote.InboxResponse
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.repository.ChatsRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.data.repository.InboxProcessor
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.testing.FakeChatsDao
import com.circaso.testing.FakeChatsRemote
import com.circaso.testing.FakeKeys
import com.circaso.testing.FakeKeysRemote
import com.circaso.testing.FakeMessagesDao
import com.circaso.testing.FakeMessagesRemote
import com.circaso.testing.FakeSealer
import com.circaso.testing.FakeSignalCrypto
import com.circaso.testing.FakeSignalDao
import com.circaso.testing.runSuspending
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A message that arrives over the socket is notified at once (2026-11-13), not only when a push comes: the sender saw
 * "delivered" while the notification waited for the socket to drop. One notification per message, whichever path
 * (socket or push) gets there first.
 */
class LiveMessageNoticesTest {
    private class FakeNotifications : ChatNotifications {
        override var activeChatId: String? = null
        override suspend fun ensurePermission() {}
        override fun show(chatId: String, title: String, text: String, messageId: String?) {}
        override fun clear(chatId: String) {}
    }

    private val me = "me"
    private val notifications = FakeNotifications()
    private val posted = PostedMessages()
    private val shown = mutableListOf<Notice>()

    private fun notice(chatId: String) =
        Notice(NotificationContent("Amina", "Niaje"), NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId), chatId)

    private val live = LiveMessageNotices(
        word = { chatId, _ -> notice(chatId) },
        notifications = notifications,
        posted = posted,
        poster = { shown += it },
        ownUserId = { me },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun arrived(chatId: String = "c1", messageId: String = "m1", senderId: String = "amina") =
        LiveMessage(chatId, messageId, senderId, "2026-11-13T09:00:00Z")

    @Test fun aMessageForAChatNotOnScreenIsPostedAtOnceUnderThePushKey() = runSuspending {
        live.onForeground()
        notifications.activeChatId = "c2"
        live.onArrived(arrived("c1"))
        assertEquals(listOf("chat:c1"), shown.map { it.key })
    }

    @Test fun theChatOnScreenIsNotNotifiedWhileCircasoIsInFront() = runSuspending {
        live.onForeground()
        notifications.activeChatId = "c1"
        live.onArrived(arrived("c1"))
        assertTrue(shown.isEmpty())
    }

    @Test fun theChatThatWasOpenNotifiesOnceCircasoLeavesTheScreen() = runSuspending {
        live.onForeground()
        notifications.activeChatId = "c1"
        live.onBackground()
        live.onArrived(arrived("c1"))
        assertEquals(1, shown.size, "it isn't on screen any more")
        assertEquals("c1", notifications.activeChatId, "still the open thread for when Circaso comes back")
    }

    @Test fun myOwnMessagesAreNeverNotified() = runSuspending {
        live.onBackground()
        live.onArrived(arrived(senderId = me))
        assertTrue(shown.isEmpty())
    }

    @Test fun aMessageThePushAlreadyShowedIsNotPostedAgainAndTheSocketsOneStopsThePush() = runSuspending {
        live.onBackground()
        assertTrue(posted.claim("m1"), "the push got there first")
        live.onArrived(arrived(messageId = "m1"))
        assertTrue(shown.isEmpty(), "one buzz per message")

        live.onArrived(arrived(messageId = "m2"))
        assertEquals(1, shown.size)
        assertTrue(posted.posted("m2"), "so the push for m2 stays quiet")
        assertFalse(posted.claim("m2"))
    }

    @Test fun nothingToShowPostsNothing() = runSuspending {
        val quiet = LiveMessageNotices({ _, _ -> null }, notifications, posted, { shown += it }, { me }, CoroutineScope(Dispatchers.Unconfined))
        quiet.onBackground()
        quiet.onArrived(arrived())
        assertTrue(shown.isEmpty())
        assertFalse(posted.posted("m1"), "a control message isn't claimed")
    }

    @Test fun theLedgerForgetsTheOldestPastItsSize() = runSuspending {
        val small = PostedMessages(keep = 2)
        small.claim("a"); small.claim("b"); small.claim("c")
        assertFalse(small.posted("a"))
        assertTrue(small.posted("c"))
    }

    // ---- the inbox side: only a live frame that brought a new message asks for a notification

    private object NoCursors : RealtimeCursors {
        override suspend fun lastSeq(module: String) = 0L
        override suspend fun setLastSeq(module: String, seq: Long) {}
        override suspend fun clear() {}
    }

    private val json = circasoJson()
    private val chatsDao = FakeChatsDao()
    private val messagesDao = FakeMessagesDao(chatsDao)
    private val store = SealedRecordStore(FakeSignalDao(), FakeSealer())
    private val crypto = FakeSignalCrypto()
    private val keys = KeysRepositoryImpl(store, FakeKeysRemote(), crypto, SignedKeyUpload(FakeKeys(), crypto), json)
    private val sessions = SessionCipher(store, crypto, keys)
    private val messagesRemote = FakeMessagesRemote()
    private val groupKeys = GroupKeys(store, crypto, chatsDao, sessions, messagesRemote, json, { me })
    private val chats = ChatsRepositoryImpl(chatsDao, messagesDao, FakeChatsRemote(), groupKeys, sessions, { me })
    private val realtime = RealtimeClient(SocketFactory { error("no socket") }, "wss://x", { null }, NoCursors, json)
    private val arrivals = mutableListOf<LiveMessage>()
    private val inbox = InboxProcessor(
        messagesDao, chatsDao, messagesRemote, sessions, groupKeys, realtime, { chats.refreshChat(it) },
        onLiveMessage = { arrivals += it },
    )

    private suspend fun directChat() {
        chatsDao.upsertChat(ChatEntity("c1", MessagingWire.Direct, null, null, 0, 1, false, null, false, 0, 1))
        chatsDao.insertMembers(listOf(ChatMemberEntity("c1", me, "MEMBER", "Me", null, null, "t"), ChatMemberEntity("c1", "amina", "MEMBER", "Amina", "amina", null, "t")))
    }

    private fun wire(text: String) = Base64.encode(WireFormat.encode(WireFormat.Type.WhisperMessage, "ct:".encodeToByteArray() + ChatPayload.encode(ChatPayload(type = ChatPayload.Text, text = text))))

    private fun frame(messageId: String, kind: String = MessagingWire.KindMessage): JsonObject = buildJsonObject {
        put("messageId", messageId); put("chatId", "c1"); put("senderId", "amina"); put("kind", kind)
        put("ciphertext", wire("Niaje")); put("sentAt", "2026-11-13T09:00:00Z")
    }

    @Test fun aLiveFrameWithANewMessageAsksForItsNotificationOnceItIsSaved() = runSuspending {
        directChat()
        inbox.onMessageNew(frame("m1"))
        assertEquals(listOf("m1"), arrivals.map { it.messageId })
        assertEquals("2026-11-13T09:00:00Z", arrivals.single().sentAt)

        inbox.onMessageNew(frame("m1"))
        assertEquals(1, arrivals.size, "the same frame again is not a new message")
    }

    @Test fun aMessageAPushAlreadyPulledFromTheInboxIsLeftToThePush() = runSuspending {
        directChat()
        messagesRemote.inboxPages += InboxResponse(listOf(InboxMessageDto("m3", "c1", "amina", MessagingWire.KindMessage, wire("Niaje"), emptyList(), "2026-11-13T09:00:00Z")), null)
        inbox.sync()
        inbox.onMessageNew(frame("m3"))
        assertTrue(arrivals.isEmpty())
    }
}
