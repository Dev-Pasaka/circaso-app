package com.circaso.feature.messaging

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.WireFormat
import com.circaso.core.network.circasoJson
import com.circaso.core.realtime.RealtimeClient
import com.circaso.core.realtime.RealtimeCursors
import com.circaso.core.realtime.SocketFactory
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxProcessor
import com.circaso.core.sync.OutboxType
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.ChatEntity
import com.circaso.feature.messaging.data.local.ChatMemberEntity
import com.circaso.feature.messaging.data.local.MessageReceiptEntity
import com.circaso.feature.messaging.data.local.MessageStatus
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.remote.InboxMessageDto
import com.circaso.feature.messaging.data.remote.InboxResponse
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.remote.SentMessageResponse
import com.circaso.feature.messaging.data.repository.ChatSendHandler
import com.circaso.feature.messaging.data.repository.ChatsRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.data.repository.InboxProcessor
import com.circaso.feature.messaging.data.repository.MessagesRepositoryImpl
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.Message
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
import kotlinx.coroutines.flow.first
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The send and receive pipelines with the fake crypto (ciphertext = "ct:" + plaintext), the real repositories, the
 * real outbox and in-memory Room fakes. What is checked is the behaviour the guide and offline-first.md ask for.
 */
class MessagingPipelineTest {
    private val json = circasoJson()
    private val me = "me"
    private val amina = "amina"
    private val chatsDao = FakeChatsDao()
    private val messagesDao = FakeMessagesDao(chatsDao)
    private val signalDao = FakeSignalDao()
    private val store = SealedRecordStore(signalDao, FakeSealer())
    private val crypto = FakeSignalCrypto()
    private val keysRemote = FakeKeysRemote()
    private val keys = KeysRepositoryImpl(store, keysRemote, crypto, SignedKeyUpload(FakeKeys(), crypto), json)
    private val sessions = SessionCipher(store, crypto, keys)
    private val messagesRemote = FakeMessagesRemote()
    private val chatsRemote = FakeChatsRemote()
    private val groupKeys = GroupKeys(store, crypto, chatsDao, sessions, messagesRemote, json, { me })
    private val chats = ChatsRepositoryImpl(chatsDao, messagesDao, chatsRemote, groupKeys, sessions, { me })
    private val realtime = RealtimeClient(SocketFactory { error("no socket") }, "wss://x", { null }, NoCursors, json)
    private val inbox = InboxProcessor(messagesDao, chatsDao, messagesRemote, sessions, groupKeys, realtime, { chats.refreshChat(it) })
    private val outbox = Outbox(FakeOutboxDao(), { me })
    private val kicked = mutableListOf<Unit>()
    private val messages = MessagesRepositoryImpl(messagesDao, chatsDao, messagesRemote, inbox, outbox, { kicked += Unit }, realtime, json, { me })
    private val handler = ChatSendHandler(messagesDao, chatsDao, messagesRemote, sessions, groupKeys, json, { me }, { chats.refreshChat(it) })
    private val processor = OutboxProcessor(outbox, listOf(handler))

    private object NoCursors : RealtimeCursors {
        override suspend fun lastSeq(module: String) = 0L
        override suspend fun setLastSeq(module: String, seq: Long) {}
        override suspend fun clear() {}
    }

    private suspend fun directChat(id: String = "c1") {
        chatsDao.upsertChat(ChatEntity(id, MessagingWire.Direct, null, null, 0, 1, false, null, false, 0, 1))
        chatsDao.insertMembers(listOf(ChatMemberEntity(id, me, "MEMBER", "Me", null, null, "t"), ChatMemberEntity(id, amina, "MEMBER", "Amina", "amina", null, "t")))
    }

    private fun wire(text: String) = Base64.encode(WireFormat.encode(WireFormat.Type.WhisperMessage, "ct:".encodeToByteArray() + ChatPayload.encode(ChatPayload(type = ChatPayload.Text, text = text))))

    @Test fun aSentTextIsSavedAsWaitingQueuedEncryptedAndMarkedSent() = runSuspending {
        directChat()
        messages.send("c1", "Habari")
        val waiting = assertIs<Message.Text>(messages.thread("c1").first().single())
        assertEquals(DeliveryState.Waiting, waiting.state)
        assertEquals(1, kicked.size)
        assertEquals(1, outbox.waiting(OutboxType.ChatMessage))

        processor.drain()
        val (chatId, request, key) = messagesRemote.sent.single()
        assertEquals("c1", chatId)
        assertEquals(MessagingWire.KindMessage, request.kind)
        assertTrue(request.pushable)
        val recipient = request.recipients!!.single()
        assertEquals(amina, recipient.userId)
        val frame = WireFormat.decode(Base64.decode(recipient.ciphertext))!!
        assertEquals(WireFormat.Type.WhisperMessage, frame.type)
        assertEquals("Habari", ChatPayload.decode(frame.body.copyOfRange(3, frame.body.size))!!.text, "the plaintext is a padded ChatPayload inside the fake ciphertext")
        assertTrue(key.isNotEmpty())
        val sent = assertIs<Message.Text>(messages.thread("c1").first().single())
        assertEquals(DeliveryState.Sent, sent.state)
        assertEquals("srv-0", sent.messageId)
        assertNotNull(signalDao.sessions[amina], "a session with Amina was started from her bundle")
    }

    private var uploadResult: AppResult<Unit> = AppResult.Success(Unit)
    private val mediaHandler = ChatSendHandler(messagesDao, chatsDao, messagesRemote, sessions, groupKeys, json, { me }, { chats.refreshChat(it) }, uploadMedia = { id ->
        uploadResult.also { r ->
            if (r is AppResult.Success) {
                val row = messagesDao.byLocalId(id)!!
                val d = com.circaso.feature.messaging.data.repository.MediaDetails.read(row.text)!!
                messagesDao.setText(id, com.circaso.feature.messaging.data.repository.MediaDetails.write(d.copy(attachmentId = "att-1", key = "k", digest = "d")))
            }
        }
    })

    private suspend fun queuePhoto(): Long {
        val d = com.circaso.feature.messaging.data.repository.MediaDetails(com.circaso.feature.messaging.data.repository.MediaDetails.Image, mime = "image/jpeg", file = "out-1")
        val id = messagesDao.insert(
            com.circaso.feature.messaging.data.local.MessageEntity(
                clientMessageId = "cm-photo", messageId = null, chatId = "c1", senderId = me, outgoing = true,
                kind = com.circaso.feature.messaging.data.local.MessageKind.Image, text = com.circaso.feature.messaging.data.repository.MediaDetails.write(d),
                replyTo = null, status = com.circaso.feature.messaging.data.local.MessageStatus.Waiting, errorCode = null, cachedRequest = null, sentAt = null, orderAt = 1, createdAt = 1,
            ),
        )
        outbox.enqueue(OutboxType.ChatMessage, json.encodeToString(com.circaso.feature.messaging.data.repository.ChatMessageJob.serializer(), com.circaso.feature.messaging.data.repository.ChatMessageJob(id)))
        return id
    }

    @Test fun anOfflinePhotoWaitsInTheQueueThenUploadsAndSends() = runSuspending {
        directChat()
        queuePhoto()
        uploadResult = AppResult.Failure(AppError.Network)
        val media = OutboxProcessor(outbox, listOf(mediaHandler))
        media.drain()
        assertEquals(DeliveryState.Waiting, assertIs<Message.Media>(messages.thread("c1").first().single()).state, "no network: still waiting, not failed")
        assertTrue(messagesRemote.sent.isEmpty(), "nothing is sent before the file is uploaded")
        uploadResult = AppResult.Success(Unit)
        media.drain()
        val sent = assertIs<Message.Media>(messages.thread("c1").first().single())
        assertEquals(DeliveryState.Sent, sent.state)
        assertEquals("att-1", sent.details.attachmentId)
        val frame = WireFormat.decode(Base64.decode(messagesRemote.sent.single().second.recipients!!.single().ciphertext))!!
        assertEquals("att-1", ChatPayload.decode(frame.body.copyOfRange(3, frame.body.size))!!.attachmentId, "the key and id travel inside the encrypted payload")
    }

    @Test fun aRefusedUploadMarksThePhotoFailed() = runSuspending {
        directChat()
        queuePhoto()
        uploadResult = AppResult.Failure(AppError.Api("messaging.attachment_too_large", 400, null, null, emptyMap()))
        OutboxProcessor(outbox, listOf(mediaHandler)).drain()
        val failed = assertIs<Message.Media>(messages.thread("c1").first().single())
        assertEquals(DeliveryState.Failed, failed.state)
        assertEquals("messaging.attachment_too_large", failed.errorCode)
    }

    @Test fun offlineTheMessageStaysWaitingAndARetrySendsTheSameBytesWithTheSameKey() = runSuspending {
        directChat()
        messagesRemote.sendResult = { AppResult.Failure(AppError.Network) }
        messages.send("c1", "Hello?")
        processor.drain()
        assertEquals(DeliveryState.Waiting, assertIs<Message.Text>(messages.thread("c1").first().single()).state)
        val firstBody = messagesRemote.sent.single().second
        messagesRemote.sendResult = { AppResult.Success(SentMessageResponse("srv-9", "2026-10-28T10:05:00Z")) }
        processor.drain()
        assertEquals(2, messagesRemote.sent.size)
        assertEquals(firstBody, messagesRemote.sent[1].second, "identical request: same clientMessageId, same ciphertext")
        assertEquals(messagesRemote.sent[0].third, messagesRemote.sent[1].third, "same Idempotency-Key")
        assertEquals(DeliveryState.Sent, assertIs<Message.Text>(messages.thread("c1").first().single()).state)
    }

    @Test fun aRefusalMarksTheMessageFailedAndDoesNotBlockTheNextOne() = runSuspending {
        directChat()
        messages.send("c1", "x".repeat(10))
        messages.send("c1", "short")
        val tooLong = messagesDao.rows.value.first { it.text?.startsWith("x") == true }.clientMessageId
        messagesRemote.sendResult = { if (it.clientMessageId == tooLong) AppResult.Failure(AppError.Api("messaging.message_too_large", 400, null, null, emptyMap())) else AppResult.Success(SentMessageResponse("ok", "2026-10-28T10:06:00Z")) }
        processor.drain()
        // A sent message takes the server's time, so it may sort above the failed one: look them up by text.
        suspend fun byText(prefix: String) = messages.thread("c1").first().map { it as Message.Text }.first { it.text.startsWith(prefix) }
        val failed = byText("x")
        assertEquals(DeliveryState.Failed, failed.state)
        assertEquals("messaging.message_too_large", failed.errorCode)
        assertEquals(DeliveryState.Sent, byText("short").state)
        // retry re-queues it (a new client id only after the 20-hour window)
        messagesRemote.sendResult = { AppResult.Success(SentMessageResponse("ok2", "2026-10-28T10:07:00Z")) }
        messages.retry(failed.localId)
        processor.drain()
        assertEquals(DeliveryState.Sent, byText("x").state)
    }

    @Test fun someoneWithoutKeysCannotBeMessagedAndTheRowSaysSo() = runSuspending {
        directChat()
        keysRemote.bundleFor = { AppResult.Failure(AppError.Api("messaging.keys_not_found", 404, null, null, emptyMap())) }
        messages.send("c1", "hi")
        processor.drain()
        val m = assertIs<Message.Text>(messages.thread("c1").first().single())
        assertEquals(DeliveryState.Failed, m.state)
        assertEquals("messaging.keys_not_found", m.errorCode)
        assertTrue(messagesRemote.sent.isEmpty())
    }

    // ---- money sent for a moment (2026-11-14)

    private val sunset = com.circaso.core.crypto.MomentQuote("mo-1", "IMAGE", "Sunset at Diani", "dGh1bWI=", 1_800_000_000_000)

    private fun sealedPayload(payload: ChatPayload) = Base64.encode(WireFormat.encode(WireFormat.Type.WhisperMessage, "ct:".encodeToByteArray() + ChatPayload.encode(payload)))

    @Test fun moneyForAMomentCarriesTheMomentOnMyCardInThePayloadAndOnTheirCard() = runSuspending {
        directChat()
        messages.sendPayment("c1", com.circaso.feature.messaging.domain.PaymentCard("t-9", "5000000", "USDC", "BASE-SEPOLIA", "Asante", sunset))
        val mine = assertIs<Message.Payment>(messages.thread("c1").first().single())
        assertEquals(sunset, mine.momentQuote)
        assertEquals("Asante", mine.note)

        processor.drain()
        val frame = WireFormat.decode(Base64.decode(messagesRemote.sent.single().second.recipients!!.single().ciphertext))!!
        val payload = ChatPayload.decode(frame.body.copyOfRange(3, frame.body.size))!!
        assertEquals(ChatPayload.Payment, payload.type)
        assertEquals(sunset, payload.moment)
        assertEquals("t-9", payload.transferId)
        assertEquals("5000000", payload.amountMinor)
        assertEquals("Asante", payload.text)

        // The other phone: the very payload that left this one (another transfer id, so it is their card).
        messagesRemote.inboxPages += InboxResponse(listOf(InboxMessageDto("m-in", "c1", amina, MessagingWire.KindMessage, sealedPayload(payload.copy(transferId = "t-10")), emptyList(), "2026-11-14T09:00:00Z")), null)
        messages.syncInbox()
        val theirs = messages.thread("c1").first().filterIsInstance<Message.Payment>().single { !it.outgoing }
        assertEquals(sunset, theirs.momentQuote)
        assertEquals("5000000", theirs.amountMinor)
    }

    @Test fun aPaymentCardWithoutAMomentReadsAsBefore() = runSuspending {
        directChat()
        // What a phone from before this sends: no `moment` at all.
        val old = """{"v":1,"type":"payment","text":"Lunch","transferId":"t-1","amountMinor":"1000000","asset":"USDC","blockchain":"BASE-SEPOLIA"}"""
        val parsed = json.decodeFromString(ChatPayload.serializer(), old)
        assertNull(parsed.moment)
        messagesRemote.inboxPages += InboxResponse(listOf(InboxMessageDto("m-old", "c1", amina, MessagingWire.KindMessage, sealedPayload(parsed), emptyList(), "2026-11-14T09:00:00Z")), null)
        messages.syncInbox()
        val card = assertIs<Message.Payment>(messages.thread("c1").first().single())
        assertNull(card.momentQuote)
        assertEquals("Lunch", card.note)

        // And my own plain card sends no `moment` key, so nothing changes for anyone.
        messages.sendPayment("c1", com.circaso.feature.messaging.domain.PaymentCard("t-2", "1000000", "USDC", "BASE-SEPOLIA", null))
        processor.drain()
        val frame = WireFormat.decode(Base64.decode(messagesRemote.sent.single().second.recipients!!.single().ciphertext))!!
        assertNull(ChatPayload.decode(frame.body.copyOfRange(3, frame.body.size))!!.moment)
    }

    @Test fun aPhoneThatDoesntKnowTheFieldStillReadsTheCard() {
        // An older reader's view of the payload: unknown keys are skipped, the payment is all there.
        val bytes = ChatPayload.encode(ChatPayload(type = ChatPayload.Payment, text = "Asante", transferId = "t-9", amountMinor = "5000000", asset = "USDC", blockchain = "BASE-SEPOLIA", moment = sunset))
        val text = com.circaso.core.crypto.Padding.unpad(bytes).decodeToString()
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(OldPayload.serializer(), text)
        assertEquals(OldPayload(1, "payment", "Asante", "t-9", "5000000", "USDC"), old)
    }

    @Test fun theInboxIsDecryptedSavedAcknowledgedAndADuplicateIsOnlyAcknowledged() = runSuspending {
        directChat()
        messagesRemote.inboxPages += InboxResponse(listOf(InboxMessageDto("m1", "c1", amina, MessagingWire.KindMessage, wire("Niaje"), emptyList(), "2026-10-28T09:00:00Z")), null)
        messages.syncInbox()
        val m = assertIs<Message.Text>(messages.thread("c1").first().single())
        assertEquals("Niaje", m.text)
        assertEquals("Amina", m.senderName)
        assertEquals(listOf("m1"), messagesRemote.acked)
        assertEquals(1, chats.chats.first().single().unread)

        messagesRemote.inboxPages += InboxResponse(listOf(InboxMessageDto("m1", "c1", amina, MessagingWire.KindMessage, wire("Niaje"), emptyList(), "2026-10-28T09:00:00Z")), null)
        messages.syncInbox()
        assertEquals(1, messages.thread("c1").first().size, "not saved twice")
        assertEquals(listOf("m1", "m1"), messagesRemote.acked, "but acknowledged again so the server lets it go")
    }

    @Test fun anUnknownChatIsLoadedBeforeItsFirstMessageIsSaved() = runSuspending {
        chatsRemote.chats = listOf(
            com.circaso.feature.messaging.data.remote.ChatDto(
                "c2", MessagingWire.Direct, null, 0,
                listOf(member(me, "Me"), member(amina, "Amina")), com.circaso.feature.messaging.data.remote.ChatSettingsDto(), null, 1,
            ),
        )
        messagesRemote.inboxPages += InboxResponse(listOf(InboxMessageDto("m7", "c2", amina, MessagingWire.KindMessage, wire("new chat"), emptyList(), "2026-10-28T09:00:00Z")), null)
        messages.syncInbox()
        assertEquals("Amina", chats.chats.first().single().chat.title)
        assertEquals("new chat", (messages.thread("c2").first().single() as Message.Text).text)
    }

    private fun member(id: String, name: String) = com.circaso.feature.messaging.data.remote.ChatMemberDto(com.circaso.feature.messaging.data.remote.ChatProfileDto(id, name), "MEMBER", "t")

    @Test fun readingAChatMovesTheMarkerAndUnreadDropsToZero() = runSuspending {
        directChat()
        messagesRemote.inboxPages += InboxResponse(listOf(InboxMessageDto("m1", "c1", amina, MessagingWire.KindMessage, wire("a"), emptyList(), "2026-10-28T09:00:00Z")), null)
        messages.syncInbox()
        assertEquals(1, chats.chats.first().single().unread)
        messages.markRead("c1")
        assertEquals(0, chats.chats.first().single().unread)
    }

    @Test fun receiptsAdvanceMyMessageOnlyWhenEveryRecipientHasReachedThatStep() = runSuspending {
        directChat()
        messages.send("c1", "hey")
        processor.drain()
        messages.onReceipts("c1", listOf(MessageReceiptEntity("srv-0", amina, MessagingWire.Delivered, "t")))
        assertEquals(DeliveryState.Delivered, (messages.thread("c1").first().single() as Message.Text).state)
        messages.onReceipts("c1", listOf(MessageReceiptEntity("srv-0", amina, MessagingWire.Read, "t")))
        assertEquals(DeliveryState.Read, (messages.thread("c1").first().single() as Message.Text).state)
        // a later, lower receipt never moves it back
        messages.onReceipts("c1", listOf(MessageReceiptEntity("srv-0", amina, MessagingWire.Delivered, "t")))
        assertEquals(DeliveryState.Read, (messages.thread("c1").first().single() as Message.Text).state)
    }

    @Test fun deletedForEveryoneBecomesATombstoneHere() = runSuspending {
        directChat()
        messagesRemote.inboxPages += InboxResponse(listOf(InboxMessageDto("m1", "c1", amina, MessagingWire.KindMessage, wire("oops"), emptyList(), "2026-10-28T09:00:00Z")), null)
        messages.syncInbox()
        messages.onDeleted("m1")
        assertIs<Message.Deleted>(messages.thread("c1").first().single())
    }

    @Test fun aGroupMessageWaitsForTheSenderKeyAndOpensWhenItArrives() = runSuspending {
        chatsDao.upsertChat(ChatEntity("g1", MessagingWire.Group, "Fund", null, 1, 1, false, null, false, 0, 1))
        chatsDao.insertMembers(listOf(ChatMemberEntity("g1", me, "MEMBER", "Me", null, null, "t"), ChatMemberEntity("g1", amina, "OWNER", "Amina", null, null, "t")))
        val groupWire = Base64.encode(WireFormat.encode(WireFormat.Type.SenderKeyMessage, "gct:".encodeToByteArray() + ChatPayload.encode(ChatPayload(type = ChatPayload.Text, text = "to all"))))
        messagesRemote.inboxPages += InboxResponse(listOf(InboxMessageDto("gm1", "g1", amina, MessagingWire.KindMessage, groupWire, emptyList(), "2026-10-28T09:00:00Z")), null)
        messages.syncInbox()
        assertTrue(messages.thread("g1").first().isEmpty(), "nothing shown yet")
        assertTrue(messagesRemote.acked.isEmpty(), "and not acknowledged: the server keeps it")
        assertEquals(1, messagesDao.pending.size)

        // Amina's sender key arrives over the 1:1 session
        val distribution = Base64.encode(WireFormat.encode(WireFormat.Type.WhisperMessage, "ct:".encodeToByteArray() + ChatPayload.encode(ChatPayload(type = ChatPayload.SenderKey, senderKey = Base64.encode("dist".encodeToByteArray())))))
        messagesRemote.inboxPages += InboxResponse(listOf(InboxMessageDto("sk1", "g1", amina, MessagingWire.KindSenderKey, distribution, emptyList(), "2026-10-28T09:00:01Z")), null)
        messages.syncInbox()
        assertEquals("to all", (messages.thread("g1").first().single() as Message.Text).text)
        assertEquals(setOf("gm1", "sk1"), messagesRemote.acked.toSet())
        assertTrue(messagesDao.pending.isEmpty())
    }

    @Test fun aGroupSendHandsMySenderKeyToEachMemberOnceThenSendsOneCiphertext() = runSuspending {
        chatsDao.upsertChat(ChatEntity("g1", MessagingWire.Group, "Fund", null, 1, 1, false, null, false, 0, 1))
        chatsDao.insertMembers(listOf(ChatMemberEntity("g1", me, "OWNER", "Me", null, null, "t"), ChatMemberEntity("g1", amina, "MEMBER", "Amina", null, null, "t"), ChatMemberEntity("g1", "brian", "MEMBER", "Brian", null, null, "t")))
        messages.send("g1", "hello group")
        processor.drain()
        assertEquals(2, messagesRemote.sent.size)
        val (_, distribution, _) = messagesRemote.sent[0]
        assertEquals(MessagingWire.KindSenderKey, distribution.kind)
        assertEquals(setOf(amina, "brian"), distribution.recipients!!.map { it.userId }.toSet())
        val (_, text, _) = messagesRemote.sent[1]
        assertEquals(MessagingWire.KindMessage, text.kind)
        assertNotNull(text.ciphertext)
        assertNull(text.recipients)
        // the next message needs no new distribution
        messages.send("g1", "again")
        processor.drain()
        assertEquals(3, messagesRemote.sent.size)
        assertEquals(MessagingWire.KindMessage, messagesRemote.sent[2].second.kind)
    }

    @Test fun anOutboxRowWhoseMessageWasDiscardedIsSimplyDone() = runSuspending {
        directChat()
        messages.send("c1", "never mind")
        val m = messages.thread("c1").first().single()
        messages.discard(m.localId)
        val result = handler.send(OutboxEntity(id = 1, type = OutboxType.ChatMessage, payload = """{"localId":${m.localId}}""", idempotencyKey = "k", createdAtMillis = 0))
        assertIs<AppResult.Success<Unit>>(result)
        assertTrue(messagesRemote.sent.isEmpty())
    }
}

/** A payment card as a reader that has never heard of `moment` on it sees it. */
@kotlinx.serialization.Serializable
private data class OldPayload(val v: Int = 1, val type: String, val text: String? = null, val transferId: String? = null, val amountMinor: String? = null, val asset: String? = null)
