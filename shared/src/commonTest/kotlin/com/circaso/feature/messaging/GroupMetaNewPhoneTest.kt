package com.circaso.feature.messaging

import com.circaso.core.common.AppResult
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.WireFormat
import com.circaso.core.network.circasoJson
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.remote.ChatDto
import com.circaso.feature.messaging.data.remote.ChatMemberDto
import com.circaso.feature.messaging.data.remote.ChatProfileDto
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.remote.SendChatMessageRequest
import com.circaso.feature.messaging.data.repository.ChatsRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupKeyRequestPush
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.data.repository.GroupPhotos
import com.circaso.feature.messaging.data.repository.MessagingStarter
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.messaging.data.repository.isoToMillis
import com.circaso.feature.messaging.data.repository.unnamedGroupTitle
import com.circaso.feature.messaging.domain.CallOutcome
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.GroupNameState
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.domain.MessagesRepository
import com.circaso.feature.messaging.domain.MessagingSettings
import com.circaso.feature.messaging.domain.PaymentCard
import com.circaso.feature.messaging.presentation.groupNameLine
import com.circaso.testing.FakeChatsDao
import com.circaso.testing.FakeChatsRemote
import com.circaso.testing.FakeKeys
import com.circaso.testing.FakeKeysRemote
import com.circaso.testing.FakeMessagesDao
import com.circaso.testing.FakeMessagesRemote
import com.circaso.testing.FakeSealer
import com.circaso.testing.FakeSignalDao
import com.circaso.testing.runSuspending
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A new phone and the groups its person is already in (Pasaka, 2026-11-09: "When I sign in on a new device, I don't see
 * the groups I'm already a member of. I don't see their icons, and their names change to the default 'Group'"). The
 * name and photo are sealed under the group's metadata key, which a new phone doesn't have: it asks the members, as
 * Moments' new phone asks posters (MomentKeyRequestTest). On the dev server his new phone asked ~20 times and nothing
 * answered: the only other member's phone was offline, a key request never woke anyone, and the first ask left 0.8 s
 * before his phone's own keys reached the server.
 */
class GroupMetaNewPhoneTest {
    private val json = circasoJson()
    private val me = "me"
    private val amina = "amina"
    private var clock = isoToMillis("2026-11-09T05:00:00Z")!!
    private val crypto = KeyedCrypto()
    private val remote = FakeChatsRemote()

    private inner class Phone(val userId: String) {
        /** Whether this phone's own keys are on the server yet (a fresh sign-in publishes them in the background). */
        var published = true
        val chatsDao = FakeChatsDao()
        val store = SealedRecordStore(FakeSignalDao(), FakeSealer())
        val keys = KeysRepositoryImpl(store, FakeKeysRemote(), crypto, SignedKeyUpload(FakeKeys(), crypto), json)
        val sessions = SessionCipher(store, crypto, keys)
        val messagesRemote = FakeMessagesRemote()
        val groupKeys = GroupKeys(store, crypto, chatsDao, sessions, messagesRemote, json, { userId }, now = { clock }, keysPublished = { published })
        val photos = GroupPhotos(FakeGroupAttachments(), HttpClient(MockEngine { respond("", HttpStatusCode.OK) }), crypto, KeyTestFiles(), CoroutineScope(Dispatchers.Unconfined))
        val chats = ChatsRepositoryImpl(chatsDao, FakeMessagesDao(), remote, groupKeys, sessions, { userId }, now = { clock }, photos = photos)

        fun sentPayloads(): List<Pair<SendChatMessageRequest, List<Pair<String, ChatPayload>>>> = messagesRemote.sent.map { (_, request, _) ->
            request to request.recipients.orEmpty().map { r ->
                val frame = WireFormat.decode(Base64.decode(r.ciphertext))!!
                r.userId to ChatPayload.decode(frame.body.copyOfRange(3, frame.body.size))!!
            }
        }

        fun asks() = sentPayloads().filter { (_, to) -> to.any { it.second.type == ChatPayload.MetaKeyRequest } }
    }

    private val phone = Phone(me)
    private val groupKey = crypto.randomBytes(32)
    private var metaVersion = 2

    /** The group as `efee1e59-…` is on the server: Pasaka the owner, one other member, made long before this phone. */
    private fun group(sealed: String) = ChatDto(
        "g1", MessagingWire.Group, sealed, metaVersion,
        listOf(
            ChatMemberDto(ChatProfileDto(me, "Pasaka"), MessagingWire.Owner, "2026-10-30T20:26:04Z"),
            ChatMemberDto(ChatProfileDto(amina, "Amina Odhiambo"), MessagingWire.Member, "2026-10-30T20:26:04Z"),
        ),
        version = 3,
    )

    /** The server knows the group; this (new) phone has never held its key. */
    private fun onTheServer(): String {
        val sealed = phone.groupKeys.sealMeta(groupKey, "Holiday Fund")
        remote.chats = listOf(group(sealed))
        return sealed
    }

    /** Amina's phone, holding the group's key (or not, a new phone itself). */
    private suspend fun aminasPhone(sealed: String, holdsKey: Boolean): Phone = Phone(amina).also {
        it.chats.store(group(sealed))
        if (holdsKey) it.groupKeys.rememberMetaKey("g1", metaVersion, groupKey)
    }

    // ---- the group always shows

    @Test fun aNewPhoneListsItsGroupsAtOnceNamedByWhoIsInThemAndWaiting() = runSuspending {
        onTheServer()
        phone.chats.refresh()

        val row = phone.chats.chats.first().single().chat
        assertEquals(ChatKind.Group, row.kind)
        assertFalse(row.nameKnown)
        assertEquals("You and Amina", row.title, "never the made-up name \"Group\"")
        assertEquals(GroupNameState.Waiting, row.nameState)
        assertEquals("Amina", row.waitingFor)
        assertNull(row.avatarUrl, "no photo until the key comes")
        assertEquals("Waiting for Amina’s phone", groupNameLine(row, online = true))
        assertEquals("Waiting for a connection", groupNameLine(row, online = false))
    }

    @Test fun aNamedGroupHasNoWaitingLine() = runSuspending {
        onTheServer()
        phone.groupKeys.rememberMetaKey("g1", metaVersion, groupKey)
        phone.chats.refresh()
        val row = phone.chats.chats.first().single().chat
        assertEquals("Holiday Fund", row.title)
        assertEquals(GroupNameState.Known, row.nameState)
        assertNull(groupNameLine(row, online = true))
    }

    @Test fun unnamedTitlesSayWhoIsInTheGroup() {
        assertEquals("Group", unnamedGroupTitle(emptyList()))
        assertEquals("You and Amina", unnamedGroupTitle(listOf("Amina Odhiambo")))
        assertEquals("Amina and Brian", unnamedGroupTitle(listOf("Amina", "Brian Kip")))
        assertEquals("Amina, Brian and 2 more", unnamedGroupTitle(listOf("Amina", "Brian", "Chidi", "Dan")))
    }

    // ---- asking: after this phone's own keys, marked so offline members are woken

    @Test fun theFirstAskWaitsForThisPhonesOwnKeysOnTheServer() = runSuspending {
        onTheServer()
        phone.published = false
        phone.chats.refresh()
        assertTrue(phone.asks().isEmpty(), "a member would seal the answer for the old phone's keys")

        phone.published = true
        phone.chats.askForMissingGroupKeys()
        assertEquals(1, phone.asks().size, "asked the next round, without waiting ten minutes")
    }

    @Test fun theAskIsMarkedAsAKeyRequestSoTheServerWakesOfflineMembers() = runSuspending {
        onTheServer()
        phone.chats.refresh()
        val (request, to) = phone.asks().single()
        assertTrue(request.keyRequest, "the server can't read the copy: the phone says it is a key request")
        assertFalse(request.pushable, "never a notification")
        assertEquals(listOf(amina), to.map { it.first })
    }

    @Test fun theAskIsRepeatedEveryTenMinutesWhileTheNameIsUnknownAndNotBefore() = runSuspending {
        onTheServer()
        phone.chats.refresh()
        phone.chats.askForMissingGroupKeys()
        assertEquals(1, phone.asks().size)
        clock += GroupKeys.AskEveryMillis
        phone.chats.askForMissingGroupKeys()
        assertEquals(2, phone.asks().size)
    }

    // ---- answers: late ones are taken in and the name and photo swap in live

    @Test fun aLateAnswerNamesTheGroupWhileTheListIsOpen() = runSuspending {
        val sealed = onTheServer()
        phone.chats.refresh()
        assertEquals("You and Amina", phone.chats.chats.first().single().chat.title)

        // Amina's phone was in her pocket; the silent push wakes it, and it answers from the inbox.
        val aminas = aminasPhone(sealed, holdsKey = true)
        aminas.groupKeys.answerMetaKeyRequest("g1", me)
        val (to, payload) = aminas.sentPayloads().single().second.single()
        assertEquals(me, to)
        assertEquals(ChatPayload.MetaKey, payload.type)

        // As the inbox does it: keep the key, then reload the chat.
        phone.groupKeys.processDistribution("g1", amina, payload)
        phone.chats.refreshChat("g1")
        val row = phone.chats.chats.first().single().chat
        assertEquals("Holiday Fund", row.title)
        assertEquals(GroupNameState.Known, row.nameState)
        clock += GroupKeys.AskEveryMillis
        phone.chats.askForMissingGroupKeys()
        assertEquals(1, phone.asks().size, "no more asks once it is named")
    }

    @Test fun aMemberWithNoKeySaysSoAndTheGroupStopsWaiting() = runSuspending {
        val sealed = onTheServer()
        phone.chats.refresh()

        // Amina is on a new phone too: she has nothing to send, and says so instead of staying silent.
        val aminas = aminasPhone(sealed, holdsKey = false)
        aminas.groupKeys.answerMetaKeyRequest("g1", me)
        val (to, payload) = aminas.sentPayloads().single().second.single()
        assertEquals(me, to)
        assertEquals(ChatPayload.MetaKeyNone, payload.type)

        phone.groupKeys.onNoKey("g1", amina)
        val row = phone.chats.chats.first().single().chat
        assertEquals(GroupNameState.Unavailable, row.nameState)
        assertEquals("Name and photo can’t be shown on this phone", groupNameLine(row, online = true))
    }

    @Test fun anOwnerCanNameTheGroupAgainAsSoonAsNoMemberHasTheKey() = runSuspending {
        onTheServer()
        remote.updateGroupResult = { _, req ->
            metaVersion++
            val g = group(req.sealedMeta!!).copy(version = 4)
            remote.chats = listOf(g)
            AppResult.Success(g)
        }
        phone.chats.refresh()
        phone.groupKeys.onNoKey("g1", amina)

        // No minute's wait: everyone asked has answered that they have nothing.
        val renamed = phone.chats.rename("g1", "Holiday Fund")
        assertTrue(renamed is AppResult.Success)
        val row = phone.chats.chats.first().single().chat
        assertEquals("Holiday Fund", row.title)
        assertEquals(GroupNameState.Known, row.nameState)
    }

    @Test fun aNoKeyAnswerToAnAskNeverMadeIsIgnored() = runSuspending {
        onTheServer()
        phone.published = false
        phone.chats.refresh() // nothing asked yet
        phone.groupKeys.onNoKey("g1", amina)
        assertEquals(GroupNameState.Waiting, phone.chats.chats.first().single().chat.nameState)
    }

    @Test fun aKeyAfterAllEndsTheUnavailableState() = runSuspending {
        val sealed = onTheServer()
        phone.chats.refresh()
        phone.groupKeys.onNoKey("g1", amina)
        assertEquals(GroupNameState.Unavailable, phone.chats.chats.first().single().chat.nameState)

        val aminas = aminasPhone(sealed, holdsKey = true)
        aminas.groupKeys.answerMetaKeyRequest("g1", me)
        phone.groupKeys.processDistribution("g1", amina, aminas.sentPayloads().single().second.single().second)
        phone.chats.refreshChat("g1")
        assertEquals(GroupNameState.Known, phone.chats.chats.first().single().chat.nameState)
        assertTrue(phone.groupKeys.unavailable.value.isEmpty())
    }

    // ---- the member's side: woken by a silent push

    @Test fun theSilentPushPullsTheInboxWhichAnswersTheAsk() = runSuspending {
        var synced = 0
        assertTrue(GroupKeyRequestPush.handle(mapOf("kind" to "chat.meta_key_requested", "chatId" to "g1", "userId" to me), sync = { synced++ }, windowMillis = 5_000))
        assertEquals(1, synced)
        assertFalse(GroupKeyRequestPush.handle(mapOf("kind" to "moment.keys_requested"), sync = { synced++ }, windowMillis = 5_000))
        assertEquals(1, synced)
    }

    // ---- launch, sign-in and reconnect

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun eachConnectionReloadsAndWhileConnectedTheAskIsTriedAgain() = runTest(StandardTestDispatcher()) {
        val connected = MutableStateFlow(false)
        val chats = CountingChats()
        val messages = CountingMessages()
        MessagingStarter(connected, chats, messages, backgroundScope, askAgainMillis = 1_000).start()
        runCurrent()
        assertEquals(0, chats.refreshes, "signed out or offline: nothing")

        connected.value = true // launch or a fresh sign-in: the socket connects
        runCurrent()
        assertEquals(1, chats.refreshes)
        assertEquals(1, messages.syncs)
        advanceTimeBy(2_500)
        assertEquals(2, chats.asks, "asked again while waiting, as often as a group may be asked")

        connected.value = false
        advanceTimeBy(5_000)
        assertEquals(2, chats.asks, "not while the socket is down")
        connected.value = true
        runCurrent()
        assertEquals(2, chats.refreshes, "a reconnect reloads (and asks) again")
    }

    private class CountingChats : com.circaso.feature.messaging.domain.ChatsRepository by NoChats {
        var refreshes = 0
        var asks = 0
        override suspend fun refresh(): AppResult<Unit> { refreshes++; return AppResult.Success(Unit) }
        override suspend fun askForMissingGroupKeys() { asks++ }
    }

    private object NoChats : com.circaso.feature.messaging.domain.ChatsRepository {
        override val chats: Flow<List<com.circaso.feature.messaging.domain.ChatSummary>> = flowOf(emptyList())
        override fun chat(chatId: String): Flow<com.circaso.feature.messaging.domain.Chat?> = flowOf(null)
        override suspend fun refresh() = AppResult.Success(Unit)
        override suspend fun refreshChat(chatId: String) = error("unused")
        override suspend fun openDirect(userId: String) = error("unused")
        override suspend fun createGroup(draft: com.circaso.feature.messaging.domain.GroupDraft, idempotencyKey: String) = error("unused")
        override suspend fun rename(chatId: String, name: String) = error("unused")
        override suspend fun setMembersCanAdd(chatId: String, allowed: Boolean) = error("unused")
        override suspend fun addMembers(chatId: String, userIds: List<String>) = error("unused")
        override suspend fun removeMember(chatId: String, userId: String) = error("unused")
        override suspend fun leave(chatId: String) = error("unused")
        override suspend fun setRole(chatId: String, userId: String, role: com.circaso.feature.messaging.domain.MemberRole) = error("unused")
        override suspend fun securityCode(userId: String) = null
    }

    private class CountingMessages : MessagesRepository {
        var syncs = 0
        override suspend fun syncInbox(): AppResult<Unit> { syncs++; return AppResult.Success(Unit) }
        override fun thread(chatId: String): Flow<List<Message>> = flowOf(emptyList())
        override fun thread(chatId: String, limit: Int): Flow<List<Message>> = flowOf(emptyList())
        override suspend fun send(chatId: String, text: String, replyTo: String?) = Unit
        override suspend fun sendMomentReply(chatId: String, text: String, quote: com.circaso.core.crypto.MomentQuote) = Unit
        override suspend fun sendPayment(chatId: String, card: PaymentCard) = Unit
        override suspend fun logCall(chatId: String, callId: String, outgoing: Boolean, video: Boolean, outcome: CallOutcome, durationSeconds: Long, callerId: String, at: Long) = Unit
        override suspend fun retry(localId: Long) = Unit
        override suspend fun discard(localId: Long) = Unit
        override suspend fun deleteForEveryone(localId: Long) = AppResult.Success(Unit)
        override suspend fun markRead(chatId: String) = Unit
        override suspend fun setTyping(chatId: String, typing: Boolean) = Unit
        override fun typing(chatId: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun settings() = AppResult.Success(MessagingSettings(true, true))
        override suspend fun updateSettings(readReceipts: Boolean?, typing: Boolean?) = AppResult.Success(MessagingSettings(true, true))
    }
}
