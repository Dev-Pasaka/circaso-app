package com.circaso.core.notifications

import com.circaso.core.network.circasoJson
import com.circaso.core.realtime.RealtimeClient
import com.circaso.core.realtime.RealtimeCursors
import com.circaso.core.realtime.SocketFactory
import com.circaso.core.session.AuthState
import com.circaso.core.sync.Outbox
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.ChatEntity
import com.circaso.feature.messaging.data.local.ChatMemberEntity
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.repository.ChatsRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.data.repository.InboxProcessor
import com.circaso.feature.messaging.data.repository.MessagesRepositoryImpl
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.names.domain.PeopleNames
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reply and Mark as read from a notification: one set of rules for Android and iPhone (2026-11-13). */
class NotificationRepliesTest {
    private val json = circasoJson()
    private val me = "me"
    private val amina = "amina"
    private val chatsDao = FakeChatsDao()
    private val messagesDao = FakeMessagesDao(chatsDao)
    private val store = SealedRecordStore(FakeSignalDao(), FakeSealer())
    private val crypto = FakeSignalCrypto()
    private val keys = KeysRepositoryImpl(store, FakeKeysRemote(), crypto, SignedKeyUpload(FakeKeys(), crypto), json)
    private val sessions = SessionCipher(store, crypto, keys)
    private val messagesRemote = FakeMessagesRemote()
    private val groupKeys = GroupKeys(store, crypto, chatsDao, sessions, messagesRemote, json, { me })
    private val names = PeopleNames(MutableStateFlow(mapOf(amina to "Mum")))
    private val chats = ChatsRepositoryImpl(chatsDao, messagesDao, FakeChatsRemote(), groupKeys, sessions, { me }, names = names)
    private val realtime = RealtimeClient(SocketFactory { error("no socket") }, "wss://x", { null }, NoCursors, json)
    private val inbox = InboxProcessor(messagesDao, chatsDao, messagesRemote, sessions, groupKeys, realtime, { chats.refreshChat(it) })
    private val messages = MessagesRepositoryImpl(messagesDao, chatsDao, messagesRemote, inbox, Outbox(FakeOutboxDao(), { me }), {}, realtime, json, { me })
    private val blocked = MutableStateFlow<List<Person>>(emptyList())
    private val auth = MutableStateFlow<AuthState>(AuthState.Active)
    private val replies = NotificationReplies(chatsDao, chats, messages, contacts(blocked), auth, { if (auth.value == AuthState.Active) me else null })

    private object NoCursors : RealtimeCursors {
        override suspend fun lastSeq(module: String) = 0L
        override suspend fun setLastSeq(module: String, seq: Long) {}
        override suspend fun clear() {}
    }

    /** Only [ContactsRepository.blocks] is read. */
    private fun contacts(blocks: Flow<List<Person>>): ContactsRepository =
        Proxy.newProxyInstance(ContactsRepository::class.java.classLoader, arrayOf(ContactsRepository::class.java)) { _, method, _ ->
            when {
                method.name == "getBlocks" -> blocks
                Flow::class.java.isAssignableFrom(method.returnType) -> flowOf(emptyList<Any>())
                else -> null
            }
        } as ContactsRepository

    private suspend fun directChat(left: Boolean = false) {
        chatsDao.upsertChat(ChatEntity("c1", MessagingWire.Direct, null, null, 0, 1, false, null, left, 0, 1))
        chatsDao.insertMembers(listOf(member("c1", me, "Me"), member("c1", amina, "Amina Wanjiku")))
    }

    private suspend fun group(left: Boolean = false) {
        chatsDao.upsertChat(ChatEntity("g1", MessagingWire.Group, "Holiday Fund", null, 0, 1, false, null, left, 0, 1))
        chatsDao.insertMembers(listOf(member("g1", me, "Me"), member("g1", amina, "Amina Wanjiku")))
    }

    private fun member(chat: String, id: String, name: String) = ChatMemberEntity(chat, id, "MEMBER", name, null, null, "t")

    private suspend fun mine(chatId: String) = messages.thread(chatId).first().filterIsInstance<Message.Text>().filter { it.outgoing }.map { it.text }

    @Test fun aReplyIsQueuedLikeOneTypedInTheChat() = runBlocking {
        directChat()
        assertEquals(NotificationReplies.Outcome.Queued, replies.reply("c1", "  On my way  "))
        assertEquals(listOf("On my way"), mine("c1"))
    }

    @Test fun aGroupReplyIsQueuedEvenWithAMemberBlocked() = runBlocking {
        group()
        blocked.value = listOf(Person(amina, "Amina", null, null, null))
        assertEquals(NotificationReplies.Outcome.Queued, replies.reply("g1", "Count me in"))
    }

    @Test fun nothingTypedDoesNothing() = runBlocking {
        directChat()
        assertEquals(NotificationReplies.Outcome.Empty, replies.reply("c1", "   "))
        assertTrue(mine("c1").isEmpty())
    }

    @Test fun noReplyToSomeoneIBlockedNamedAsSaved() = runBlocking {
        directChat()
        blocked.value = listOf(Person(amina, "Amina", null, null, null))
        assertEquals(NotificationReplies.Outcome.NotSent("Mum", canRetry = false), replies.reply("c1", "hi"))
        assertTrue(mine("c1").isEmpty())
    }

    @Test fun noReplyToAGroupIveLeft() = runBlocking {
        group(left = true)
        val outcome = replies.reply("g1", "hi")
        assertTrue(outcome is NotificationReplies.Outcome.NotSent && !outcome.canRetry)
        assertTrue(mine("g1").isEmpty())
    }

    @Test fun noReplyToAChatThisPhoneDoesNotKnow() = runBlocking {
        assertTrue(replies.reply("nope", "hi") is NotificationReplies.Outcome.NotSent)
    }

    @Test fun noReplyWhenSignedOut() = runBlocking {
        directChat()
        auth.value = AuthState.SignedOut
        assertTrue(replies.reply("c1", "hi") is NotificationReplies.Outcome.NotSent)
        assertTrue(mine("c1").isEmpty())
    }
}
