package com.circaso.core.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.WireFormat
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.network.circasoJson
import com.circaso.core.realtime.RealtimeClient
import com.circaso.core.realtime.RealtimeCursors
import com.circaso.core.realtime.SocketFactory
import com.circaso.core.sync.Outbox
import com.circaso.feature.chama.domain.ChamaRepository
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
import com.circaso.feature.messaging.data.repository.MessagesRepositoryImpl
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.names.domain.PeopleNames
import com.circaso.feature.wallet.domain.WalletRepository
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a push turns into (2026-11-13): with Notification privacy off, the sender as this phone names them (a saved
 * name first), what was said and their photo; with it on, nothing that names a person, a photo, a group or an amount.
 * The real inbox pipeline with the fake crypto ("ct:" + plaintext) and in-memory Room fakes.
 */
class PushNoticesTest {
    private val json = circasoJson()
    private val me = "me"
    private val amina = "amina"
    private val otieno = "otieno"
    private val chatsDao = FakeChatsDao()
    private val messagesDao = FakeMessagesDao(chatsDao)
    private val store = SealedRecordStore(FakeSignalDao(), FakeSealer())
    private val crypto = FakeSignalCrypto()
    private val keys = KeysRepositoryImpl(store, FakeKeysRemote(), crypto, SignedKeyUpload(FakeKeys(), crypto), json)
    private val sessions = SessionCipher(store, crypto, keys)
    private val messagesRemote = FakeMessagesRemote()
    private val groupKeys = GroupKeys(store, crypto, chatsDao, sessions, messagesRemote, json, { me })
    private val saved = MutableStateFlow(mapOf(amina to "Mum"))
    private val names = PeopleNames(saved)
    private val chats = ChatsRepositoryImpl(chatsDao, messagesDao, FakeChatsRemote(), groupKeys, sessions, { me }, names = names)
    private val realtime = RealtimeClient(SocketFactory { error("no socket") }, "wss://x", { null }, NoCursors, json)
    private val inbox = InboxProcessor(messagesDao, chatsDao, messagesRemote, sessions, groupKeys, realtime, { chats.refreshChat(it) })
    private val messages = MessagesRepositoryImpl(messagesDao, chatsDao, messagesRemote, inbox, Outbox(FakeOutboxDao(), { me }), {}, realtime, json, { me })
    private val prefs = AppPreferences(MemoryPreferences())
    private val notices = PushNotices(chatsDao, messagesDao, chats, messages, inbox, stub<WalletRepository>(), stub<ChamaRepository>(), prefs, names = names)

    private object NoCursors : RealtimeCursors {
        override suspend fun lastSeq(module: String) = 0L
        override suspend fun setLastSeq(module: String, seq: Long) {}
        override suspend fun clear() {}
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }

    /** An interface these paths never call (the wallet only for its currency, which stays unknown here). */
    private inline fun <reified T> stub(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        if (Flow::class.java.isAssignableFrom(method.returnType)) flowOf(null) else null
    } as T

    private suspend fun directChat() {
        chatsDao.upsertChat(ChatEntity("c1", MessagingWire.Direct, null, null, 0, 1, false, null, false, 0, 1))
        chatsDao.insertMembers(listOf(member("c1", me, "Me"), member("c1", amina, "Amina Wanjiku", "https://cdn/x/amina.jpg?sig=1")))
    }

    private suspend fun group() {
        chatsDao.upsertChat(ChatEntity("g1", MessagingWire.Group, "Holiday Fund", null, 0, 1, false, null, false, 0, 1))
        chatsDao.insertMembers(listOf(member("g1", me, "Me"), member("g1", amina, "Amina Wanjiku"), member("g1", otieno, "Otieno")))
    }

    private fun member(chat: String, id: String, name: String, photo: String? = null) = ChatMemberEntity(chat, id, "MEMBER", name, null, photo, "t")

    private fun arrives(chatId: String, from: String, payload: ChatPayload, id: String = "m1") {
        val wire = Base64.encode(WireFormat.encode(WireFormat.Type.WhisperMessage, "ct:".encodeToByteArray() + ChatPayload.encode(payload)))
        messagesRemote.inboxPages += InboxResponse(listOf(InboxMessageDto(id, chatId, from, MessagingWire.KindMessage, wire, emptyList(), "2026-11-13T09:00:00Z")), null)
    }

    private fun push(chatId: String, id: String = "m1") = mapOf("chatId" to chatId, "messageId" to id)

    @Test fun aDirectMessageNamesTheSenderAsSavedWithTheirPhotoAndWords() = runBlocking {
        directChat()
        arrives("c1", amina, ChatPayload(type = ChatPayload.Text, text = "Are we still on for 6?"))
        val notice = assertNotNull(notices.forPush(push("c1")))
        assertEquals(NotificationContent("Mum", "Are we still on for 6?"), notice.content)
        val c = assertNotNull(notice.conversation)
        assertEquals(NoticePerson(amina, "Mum", "https://cdn/x/amina.jpg?sig=1"), c.sender)
        assertEquals("Are we still on for 6?", c.line)
        assertEquals(false, c.group)
    }

    @Test fun mediaReadsAsWhatItIsNeverItsDetails() = runBlocking {
        directChat()
        arrives("c1", amina, ChatPayload(type = ChatPayload.Voice, attachmentId = "a", mediaKey = "k", digest = "d", durationMs = 12_000))
        val notice = assertNotNull(notices.forPush(push("c1")))
        assertEquals("🎤 Voice message (0:12)", notice.content.text)
    }

    @Test fun aGroupMessageIsTitledByTheGroupAndTheLineLeavesTheSenderOut() = runBlocking {
        group()
        arrives("g1", otieno, ChatPayload(type = ChatPayload.Text, text = "Who's bringing the cake?"))
        val notice = assertNotNull(notices.forPush(push("g1")))
        assertEquals(NotificationContent("Holiday Fund", "Otieno: Who's bringing the cake?"), notice.content)
        val c = assertNotNull(notice.conversation)
        assertEquals("Holiday Fund", c.title)
        assertEquals("Otieno", c.sender.name)
        assertEquals("Who's bringing the cake?", c.line)
    }

    @Test fun moneyShowsTheSendersPhotoAndTheAmount() = runBlocking {
        directChat()
        arrives("c1", amina, ChatPayload(type = ChatPayload.Payment, transferId = "t1", amountMinor = "5000000", asset = "USDC", text = "for lunch"))
        val notice = assertNotNull(notices.forPush(push("c1")))
        assertEquals(NoticeChannel.Money, notice.channel)
        assertEquals(NotificationContent("Mum", "Sent you 5.00 USDC · for lunch"), notice.content)
        assertEquals("Mum", notice.from?.name)
        assertEquals("Sent you 5.00 USDC · for lunch", notice.fromLine)
        assertEquals(false, notice.group)
    }

    @Test fun groupMoneyIsTheGroupsTitleWithTheMembersLine() = runBlocking {
        group()
        arrives("g1", otieno, ChatPayload(type = ChatPayload.Payment, transferId = "t2", amountMinor = "1000000", asset = "USDC"))
        val notice = assertNotNull(notices.forPush(push("g1")))
        assertEquals(NotificationContent("Holiday Fund", "Otieno added 1.00 USDC"), notice.content)
        assertEquals("Otieno", notice.from?.name)
        assertEquals("Added 1.00 USDC", notice.fromLine)
        assertEquals(true, notice.group)
    }

    @Test fun withPrivacyOnNothingNamesAPersonAPhotoOrAnAmount() = runBlocking {
        directChat()
        group()
        prefs.setFlag(AppPreferences.NotificationPrivacy, true)
        arrives("c1", amina, ChatPayload(type = ChatPayload.Text, text = "secret"), id = "m1")
        val text = assertNotNull(notices.forPush(push("c1", "m1")))
        assertEquals(NotificationContent("Circaso", "New message"), text.content)
        assertNull(text.conversation)

        arrives("g1", otieno, ChatPayload(type = ChatPayload.Payment, transferId = "t3", amountMinor = "1000000", asset = "USDC"), id = "m2")
        val money = assertNotNull(notices.forPush(push("g1", "m2")))
        assertEquals(NotificationContent("Circaso", "Money moved in a group"), money.content)
        assertNull(money.from)
        assertNull(money.groupPhoto)
        assertNull(money.fromLine)

        arrives("c1", amina, ChatPayload(type = ChatPayload.Payment, transferId = "t4", amountMinor = "2000000", asset = "USDC"), id = "m3")
        val direct = assertNotNull(notices.forPush(push("c1", "m3")))
        assertEquals(NotificationContent("Circaso", "You received money"), direct.content)
        assertNull(direct.from)
    }
}
