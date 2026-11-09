package com.circaso.feature.messaging

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.crypto.WireFormat
import com.circaso.core.network.circasoJson
import com.circaso.core.platform.GroupPhotoImage
import com.circaso.core.platform.MediaFiles
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload


import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.remote.ChatDto
import com.circaso.feature.messaging.data.remote.ChatMemberDto
import com.circaso.feature.messaging.data.remote.ChatProfileDto
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.remote.SendChatMessageRequest

import com.circaso.feature.messaging.data.repository.AttachmentDownloadResponse
import com.circaso.feature.messaging.data.repository.AttachmentReadyResponse
import com.circaso.feature.messaging.data.repository.AttachmentsRemote
import com.circaso.feature.messaging.data.repository.ChatsRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.data.repository.GroupPhotos
import com.circaso.feature.messaging.data.repository.NewAttachmentResponse
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.GroupKeyErrors
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A group whose metadata key this phone doesn't have (the owner's phone, 2026-11-05: a group it created with no key
 * row at all). The key is now on disk before the create request leaves; a phone without it asks the members, who
 * answer 1:1; and once no one has, an admin's rename or photo change gives the group a new key.
 */
class GroupMetaKeyRecoveryTest {
    private val json = circasoJson()
    private val me = "me"
    private val amina = "amina"
    /** Two days after the group was made (2026-11-04 06:32 EAT). */
    private var clock = com.circaso.feature.messaging.data.repository.isoToMillis("2026-11-06T09:00:00Z")!!

    /** One phone: its own Room, Signal store and repositories, sharing the fake server. */
    private inner class Phone(val userId: String) {
        val chatsDao = FakeChatsDao()
        val store = SealedRecordStore(FakeSignalDao(), FakeSealer())
        val keys = KeysRepositoryImpl(store, FakeKeysRemote(), crypto, SignedKeyUpload(FakeKeys(), crypto), json)
        val sessions = SessionCipher(store, crypto, keys)
        val messagesRemote = FakeMessagesRemote()
        val groupKeys = GroupKeys(store, crypto, chatsDao, sessions, messagesRemote, json, { userId }, now = { clock })
        val photos = GroupPhotos(attachments, HttpClient(MockEngine { respond("", HttpStatusCode.OK) }), crypto, KeyTestFiles(), CoroutineScope(Dispatchers.Unconfined))
        val chats = ChatsRepositoryImpl(chatsDao, FakeMessagesDao(), remote, groupKeys, sessions, { userId }, now = { clock }, photos = photos)

        /** What this phone sent 1:1 in [chatId], opened (the fake session's ciphertext is "ct:" + the payload). */
        fun sentPayloads(): List<Pair<SendChatMessageRequest, List<Pair<String, ChatPayload>>>> = messagesRemote.sent.map { (_, request, _) ->
            request to request.recipients.orEmpty().map { r ->
                val frame = WireFormat.decode(Base64.decode(r.ciphertext))!!
                r.userId to ChatPayload.decode(frame.body.copyOfRange(3, frame.body.size))!!
            }
        }
    }

    private val crypto = KeyedCrypto()
    private val remote = FakeChatsRemote()
    private val attachments = FakeGroupAttachments()
    private val phone = Phone(me)

    private var version = 1
    private var metaVersion = 1
    private var myRole = MessagingWire.Owner
    private fun group(sealed: String?) = ChatDto(
        "g1", MessagingWire.Group, sealed, metaVersion,
        listOf(ChatMemberDto(ChatProfileDto(me, "Me"), myRole, "2026-11-04T03:32:00Z"), ChatMemberDto(ChatProfileDto(amina, "Amina"), MessagingWire.Member, "2026-11-04T03:32:00Z")),
        version = version,
    )

    /** The fake server: a PATCH raises both versions and keeps the new seal. */
    private fun serverAcceptsPatches() {
        remote.updateGroupResult = { _, req ->
            version++
            if (req.sealedMeta != null) metaVersion++
            val g = group(req.sealedMeta ?: remote.chats.first().sealedMeta)
            remote.chats = listOf(g)
            AppResult.Success(g)
        }
    }

    /** The key [aGroupWithoutItsKey] sealed under; somewhere else (a member's phone), never on this one. */
    private var lostKey = ByteArray(0)

    /**
     * A group sealed under a key this phone never kept (what the owner's phone had for `efee1e59-…`), on the server
     * and in this phone's Room.
     */
    private suspend fun aGroupWithoutItsKey(role: String = MessagingWire.Owner): String {
        myRole = role
        lostKey = crypto.randomBytes(32)
        val sealed = phone.groupKeys.sealMeta(lostKey, "Holiday Fund")
        remote.chats = listOf(group(sealed))
        serverAcceptsPatches()
        phone.chats.store(remote.chats.single())
        return sealed
    }

    private fun asks(p: Phone) = p.sentPayloads().filter { (_, to) -> to.any { it.second.type == ChatPayload.MetaKeyRequest } }

    private fun code(r: AppResult<*>) = ((r as AppResult.Failure).error as AppError.Api).code

    // ---- the root cause: the creator's key lived only in memory until the create answer arrived

    @Test fun aCreateWhoseAnswerIsLostStillOpensWhenTheGroupTurnsUp() = runSuspending {
        var sealed: String? = null
        // The server makes the group, but the answer never reaches the phone (a 20 s timeout, a dropped connection).
        remote.createGroupResult = { req, _ -> sealed = req.sealedMeta; AppResult.Failure(AppError.Network) }
        assertIs<AppResult.Failure>(phone.chats.createGroup(GroupDraft("Holiday Fund", listOf(amina)), "create-1"))
        assertEquals(1, phone.chatsDao.pendingMetaKeys().size, "the key was on disk before the request left")

        remote.chats = listOf(group(sealed))
        assertIs<AppResult.Success<*>>(phone.chats.refresh())
        assertEquals("Holiday Fund", phone.chats.chat("g1").first()!!.title)
        assertNotNull(phone.chatsDao.latestMetaKey("g1"), "the attempt's key is now the group's own")
        assertTrue(phone.chatsDao.pendingMetaKeys().isEmpty())
        assertTrue(asks(phone).isEmpty(), "nothing to ask for")
    }

    @Test fun aRetryWithTheSameKeySealsUnderTheSameKeyEvenAfterARestart() = runSuspending {
        val sent = mutableListOf<String>()
        remote.createGroupResult = { req, _ -> sent += req.sealedMeta!!; AppResult.Failure(AppError.Network) }
        phone.chats.createGroup(GroupDraft("Holiday Fund", listOf(amina)), "create-1")
        // The app is restarted (new repositories, nothing in memory, the same Room): Try Again reuses the key from disk.
        val keysAfterRestart = GroupKeys(phone.store, crypto, phone.chatsDao, phone.sessions, phone.messagesRemote, json, { me }, now = { clock })
        val again = ChatsRepositoryImpl(phone.chatsDao, FakeMessagesDao(), remote, keysAfterRestart, phone.sessions, { me }, now = { clock })
        remote.createGroupResult = { req, _ -> sent += req.sealedMeta!!; AppResult.Success(group(sent.first())) }
        assertIs<AppResult.Success<*>>(again.createGroup(GroupDraft("Holiday Fund", listOf(amina)), "create-1"))
        assertEquals(sent[0], sent[1], "the same key and the same seal on the retry")
        assertEquals("Holiday Fund", again.chat("g1").first()!!.title)
    }

    @Test fun anAttemptsKeyNeverOpensSomeoneElsesGroup() = runSuspending {
        remote.createGroupResult = { _, _ -> AppResult.Failure(AppError.Network) }
        phone.chats.createGroup(GroupDraft("Mine", listOf(amina)), "create-1")
        aGroupWithoutItsKey()
        phone.chats.refresh()
        assertNull(phone.chatsDao.latestMetaKey("g1"))
        assertEquals("You and Amina", phone.chats.chat("g1").first()!!.title, "titled by who is in it, never a made-up name")
        assertEquals(false, phone.chats.chat("g1").first()!!.nameKnown, "the title is a placeholder: the name sheet must not offer it")
        assertEquals(1, phone.chatsDao.pendingMetaKeys().size, "kept for the group it did make")
    }

    // ---- (a) ask the members

    @Test fun aPhoneWithoutTheKeyAsksTheMembersOnceOverTheirSessions() = runSuspending {
        aGroupWithoutItsKey()
        phone.chats.refresh()
        phone.chats.refresh()
        val sent = asks(phone)
        assertEquals(1, sent.size, "once, not on every refresh")
        val (request, to) = sent.single()
        assertEquals(MessagingWire.KindSenderKey, request.kind, "a 1:1 copy per member, like a sender key")
        assertEquals(false, request.pushable)
        assertEquals(listOf(amina), to.map { it.first })
        assertEquals(1, to.single().second.metaVersion)
    }

    @Test fun someoneWhoJustJoinedWaitsForTheKeyInsteadOfAsking() = runSuspending {
        clock = com.circaso.feature.messaging.data.repository.isoToMillis("2026-11-04T03:35:00Z")!!
        aGroupWithoutItsKey()
        phone.chats.refresh()
        assertTrue(phone.messagesRemote.sent.isEmpty())
    }

    @Test fun aMemberWhoHoldsTheKeyAnswersAndTheNameOpens() = runSuspending {
        val sealed = aGroupWithoutItsKey()
        phone.chats.refresh()
        // Amina's phone holds the key and gets my ask.
        val aminas = Phone(amina)
        aminas.chats.store(group(sealed))
        aminas.groupKeys.rememberMetaKey("g1", 1, lostKey)
        aminas.groupKeys.answerMetaKeyRequest("g1", me)
        aminas.groupKeys.answerMetaKeyRequest("g1", me)
        val answers = aminas.sentPayloads()
        assertEquals(1, answers.size, "answered once a minute at most")
        val (to, payload) = answers.single().second.single()
        assertEquals(me, to)
        assertEquals(ChatPayload.MetaKey, payload.type)

        // It arrives here as any key over the 1:1 session does.
        phone.groupKeys.processDistribution("g1", amina, payload)
        phone.chats.refreshChat("g1")
        assertEquals("Holiday Fund", phone.chats.chat("g1").first()!!.title)
        assertTrue(phone.chats.chat("g1").first()!!.nameKnown)
    }

    @Test fun noOneOutsideTheGroupIsAnswered() = runSuspending {
        val sealed = aGroupWithoutItsKey()
        val aminas = Phone(amina)
        aminas.chats.store(group(sealed))
        aminas.groupKeys.rememberMetaKey("g1", 1, lostKey)
        aminas.groupKeys.answerMetaKeyRequest("g1", "stranger")
        assertTrue(aminas.messagesRemote.sent.isEmpty())
    }

    @Test fun aKeyThatDoesNotOpenTheGroupIsNotKept() = runSuspending {
        aGroupWithoutItsKey()
        phone.chats.refresh()
        val wrong = ChatPayload(type = ChatPayload.MetaKey, metaKey = Base64.encode(ByteArray(32) { 9 }), metaVersion = 1)
        phone.groupKeys.processDistribution("g1", amina, wrong)
        assertNull(phone.chatsDao.latestMetaKey("g1"))
    }

    // ---- (b) no answer: an admin re-keys

    @Test fun renameAsksFirstAndSaysSoInPlainWords() = runSuspending {
        aGroupWithoutItsKey()
        val r = phone.chats.rename("g1", "Holiday Fund 2027")
        assertEquals(GroupKeyErrors.Asked, code(r))
        assertEquals(
            "This phone lost the group's key. We've asked the members for it. If no one sends it, try again in a minute to give the group a new key.",
            ErrorMessages.forError((r as AppResult.Failure).error),
        )
        assertNull(ErrorMessages.reference(r.error), "a known code: no reference to quote")
        assertTrue(remote.updates.isEmpty(), "nothing went to the server")
    }

    @Test fun aMemberIsToldTheMembersAreBeingAsked() = runSuspending {
        aGroupWithoutItsKey(role = MessagingWire.Member)
        val r = phone.chats.rename("g1", "x")
        assertEquals(GroupKeyErrors.Missing, code(r))
        assertEquals("This phone lost the group's key. We've asked the members for it, so try again soon.", ErrorMessages.forError((r as AppResult.Failure).error))
        clock += 5 * 60_000
        assertEquals(GroupKeyErrors.Missing, code(phone.chats.rename("g1", "x")), "only an admin gives the group a new key")
        assertTrue(remote.updates.isEmpty())
    }

    @Test fun offlineTheAskIsNotCountedAndSaysSo() = runSuspending {
        aGroupWithoutItsKey()
        phone.messagesRemote.sendResult = { AppResult.Failure(AppError.Network) }
        assertEquals(AppError.Network, (phone.chats.rename("g1", "x") as AppResult.Failure).error)
        assertNull(phone.groupKeys.askedSince("g1"))
    }

    @Test fun afterAMinuteWithoutAnAnswerAnAdminRenameGivesTheGroupANewKey() = runSuspending {
        aGroupWithoutItsKey()
        phone.chats.refresh() // the ask goes out
        clock += ChatsRepositoryImpl.RekeyAfterMillis
        val r = phone.chats.rename("g1", "Holiday Fund 2027")
        assertIs<AppResult.Success<*>>(r)
        val patch = remote.updates.single().second
        assertEquals(JsonNull, patch.avatarMediaId, "the old photo can't be read here: cleared, not left pointing at the old seal")
        assertEquals(2, phone.chatsDao.latestMetaKey("g1")!!.metaVersion, "kept as the next meta version")
        assertEquals("Holiday Fund 2027", phone.chats.chat("g1").first()!!.title)
        // Every member gets the new key, whether or not they hold my sender key.
        val handed = phone.sentPayloads().last().second.single()
        assertEquals(amina, handed.first)
        assertEquals(ChatPayload.MetaKey, handed.second.type)
        assertEquals(2, handed.second.metaVersion)
        // And it opens what went to the server: Amina's phone reads the new name.
        val aminas = Phone(amina)
        aminas.chats.store(group(patch.sealedMeta))
        aminas.groupKeys.processDistribution("g1", me, handed.second)
        aminas.chats.store(group(patch.sealedMeta))
        assertEquals("Holiday Fund 2027", aminas.chats.chat("g1").first()!!.title)
    }

    @Test fun aPhotoChangeAsksForTheNameWhenItCantReadItThenReKeys() = runSuspending {
        aGroupWithoutItsKey()
        phone.chats.refresh()
        clock += ChatsRepositoryImpl.RekeyAfterMillis
        val image = GroupPhotoImage(ByteArray(2_000) { 3 }, 512, 512, ByteArray(300) { 4 })
        assertEquals(GroupKeyErrors.NameNeeded, code(phone.chats.setGroupPhoto("g1", image)))
        assertTrue(attachments.created.isEmpty(), "nothing uploaded before the name is in")
        assertTrue(remote.updates.isEmpty())

        assertIs<AppResult.Success<*>>(phone.chats.setGroupPhoto("g1", image, "Holiday Fund"))
        val patch = remote.updates.single().second
        assertEquals(kotlinx.serialization.json.JsonPrimitive("att-1"), patch.avatarMediaId)
        val chat = phone.chats.chat("g1").first()!!
        assertEquals("Holiday Fund", chat.title)
        assertNotNull(chat.avatarUrl, "the new photo shows at once")
    }

    @Test fun removingAPhotoWithoutTheKeyAlsoNeedsTheName() = runSuspending {
        aGroupWithoutItsKey()
        phone.chats.refresh()
        clock += ChatsRepositoryImpl.RekeyAfterMillis
        assertEquals(GroupKeyErrors.NameNeeded, code(phone.chats.setGroupPhoto("g1", null)))
        assertIs<AppResult.Success<*>>(phone.chats.setGroupPhoto("g1", null, "Holiday Fund"))
        assertEquals(JsonNull, remote.updates.single().second.avatarMediaId)
        assertEquals("Holiday Fund", phone.chats.chat("g1").first()!!.title)
    }

    @Test fun anAnswerThatArrivesInTimeMeansNoNewKey() = runSuspending {
        aGroupWithoutItsKey()
        phone.chats.refresh()
        phone.groupKeys.processDistribution("g1", amina, ChatPayload(type = ChatPayload.MetaKey, metaKey = Base64.encode(lostKey), metaVersion = 1))
        clock += ChatsRepositoryImpl.RekeyAfterMillis
        assertIs<AppResult.Success<*>>(phone.chats.rename("g1", "Holiday Fund 2027"))
        // The existing key seals the new name: no new key goes to the members.
        assertEquals(1, phone.chatsDao.metaKeys.values.count { it.chatId == "g1" && it.metaVersion == 2 })
        assertTrue(phone.sentPayloads().none { (_, to) -> to.any { it.second.type == ChatPayload.MetaKey } })
    }

    @Test fun removingSomeoneWithoutTheKeyNeverSealsThePlaceholderName() = runSuspending {
        aGroupWithoutItsKey()
        phone.chats.refresh()
        phone.chats.removeMember("g1", amina)
        assertTrue(remote.updates.none { it.second.sealedMeta != null }, "\"Group\" is not sealed as the group's name")
    }
}

/**
 * The fake crypto, with AES-GCM that only opens under the key it was sealed with and keys that differ each time
 * (the shared fake ignores the key and always makes the same one, which can't show a wrong key being refused).
 */
internal class KeyedCrypto(private val inner: FakeSignalCrypto = FakeSignalCrypto()) : SignalCrypto by inner {
    private var next = 0
    override fun randomBytes(count: Int) = ByteArray(count) { next.toByte() }.also { next++ }
    override fun aesGcmSeal(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray) = key.copyOf(4) + plaintext.reversedArray()
    override fun aesGcmOpen(key: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray {
        require(sealed.size >= 4 && sealed.copyOf(4).contentEquals(key.copyOf(4))) { "wrong key" }
        return sealed.copyOfRange(4, sealed.size).reversedArray()
    }
}

internal class FakeGroupAttachments : AttachmentsRemote {
    val created = mutableListOf<String>()
    override suspend fun create(chatId: String, sizeBytes: Long, kind: String): AppResult<NewAttachmentResponse> {
        created += chatId
        return AppResult.Success(NewAttachmentResponse("att-${created.size}", "https://r2/upload"))
    }
    override suspend fun complete(attachmentId: String) = AppResult.Success(AttachmentReadyResponse(attachmentId))
    override suspend fun download(attachmentId: String) = AppResult.Success(AttachmentDownloadResponse("https://r2/x", sizeBytes = null))
}

internal class KeyTestFiles : MediaFiles {
    private val map = mutableMapOf<String, ByteArray>()
    override suspend fun read(name: String) = map[name]
    override suspend fun write(name: String, bytes: ByteArray) { map[name] = bytes }
    override suspend fun clear() = map.clear()
    override suspend fun path(name: String) = if (name in map) "/files/$name" else null
}
