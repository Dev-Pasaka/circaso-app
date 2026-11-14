package com.circaso.feature.messaging

import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.WireFormat
import com.circaso.core.network.circasoJson
import com.circaso.core.realtime.RealtimeClient
import com.circaso.core.realtime.RealtimeCursors
import com.circaso.core.realtime.SocketFactory
import com.circaso.core.sync.Outbox
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.ChatEntity
import com.circaso.feature.messaging.data.local.ChatMemberEntity
import com.circaso.feature.messaging.data.local.MessageEntity
import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.MessageStatus
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.local.SenderKeyDeliveryEntity
import com.circaso.feature.messaging.data.remote.InboxMessageDto
import com.circaso.feature.messaging.data.remote.InboxResponse
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.repository.ChatPaymentCards
import com.circaso.feature.messaging.data.repository.ChatsRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.data.repository.InboxProcessor
import com.circaso.feature.messaging.data.repository.MessagesRepositoryImpl
import com.circaso.feature.messaging.data.repository.RetryReceipts
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.domain.PaymentCard
import com.circaso.feature.wallet.domain.ChatPaymentSeen
import com.circaso.testing.FakeChatsDao
import com.circaso.testing.FakeChatsRemote
import com.circaso.testing.FakeKeys
import com.circaso.testing.FakeKeysRemote
import com.circaso.testing.FakeMessagesDao
import com.circaso.testing.FakeMessagesRemote
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.FakeRecoveryDao
import com.circaso.testing.FakeSealer
import com.circaso.testing.FakeSignalCrypto
import com.circaso.testing.FakeSignalDao
import com.circaso.testing.runSuspending
import kotlinx.coroutines.flow.first
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 2026-11-05 incident: Pasaka signed in again on a new phone and paid Circaso user in their chat; the payment
 * confirmed on chain but the end-to-end card couldn't be opened, so no Received card showed. Part 1: the card comes
 * from the wallet's own record and is one card with the end-to-end one. Part 2: retry receipts (ask once, resend on a
 * fresh session under the same id, rate-limited), and a group sender key handed again to a new phone.
 */
class MessageRecoveryTest {
    private val json = circasoJson()
    private val me = "me"
    private val amina = "amina"
    private var clock = 1_800_000_000_000L
    private val chatsDao = FakeChatsDao()
    private val messagesDao = FakeMessagesDao(chatsDao)
    private val recoveryDao = FakeRecoveryDao(messagesDao)
    private val signalDao = FakeSignalDao()
    private val store = SealedRecordStore(signalDao, FakeSealer())
    private val crypto = FakeSignalCrypto()
    private val keysRemote = FakeKeysRemote()
    private val bundlesFetched = mutableListOf<String>()
    private val keys = KeysRepositoryImpl(store, keysRemote, crypto, SignedKeyUpload(FakeKeys(), crypto), json)
    private val sessions = SessionCipher(store, crypto, keys, now = { clock })
    private val messagesRemote = FakeMessagesRemote()
    private val chatsRemote = FakeChatsRemote()
    private val groupKeys = GroupKeys(store, crypto, chatsDao, sessions, messagesRemote, json, { me })
    private val chats = ChatsRepositoryImpl(chatsDao, messagesDao, chatsRemote, groupKeys, sessions, { me })
    private val realtime = RealtimeClient(SocketFactory { error("no socket") }, "wss://x", { null }, NoCursors, json)
    private val cards = ChatPaymentCards(messagesDao, chatsDao, recoveryDao, { me }, { chats.refreshChat(it) }, now = { clock })
    private val retries = RetryReceipts(messagesDao, chatsDao, recoveryDao, messagesRemote, sessions, groupKeys, json, { me }, now = { clock })
    private val inbox = InboxProcessor(messagesDao, chatsDao, messagesRemote, sessions, groupKeys, realtime, { chats.refreshChat(it) }, now = { clock }, retries = retries, paymentCards = cards)
    private val outbox = Outbox(FakeOutboxDao(), { me })
    private val messages = MessagesRepositoryImpl(messagesDao, chatsDao, messagesRemote, inbox, outbox, {}, realtime, json, { me }, now = { clock }, paymentCards = cards)

    init {
        val bundle = keysRemote.bundleFor
        keysRemote.bundleFor = { id -> bundlesFetched += id; bundle(id) }
    }

    private object NoCursors : RealtimeCursors {
        override suspend fun lastSeq(module: String) = 0L
        override suspend fun setLastSeq(module: String, seq: Long) {}
        override suspend fun clear() {}
    }

    private suspend fun directChat(id: String = "c1") {
        chatsDao.upsertChat(ChatEntity(id, MessagingWire.Direct, null, null, 0, 1, false, null, false, 0, 1))
        chatsDao.insertMembers(listOf(ChatMemberEntity(id, me, "MEMBER", "Me", null, null, "t"), ChatMemberEntity(id, amina, "MEMBER", "Amina", "amina", null, "t")))
    }

    private suspend fun groupChat(id: String = "g1") {
        chatsDao.upsertChat(ChatEntity(id, MessagingWire.Group, "Chama", null, 0, 1, false, null, false, 0, 1))
        chatsDao.insertMembers(listOf(ChatMemberEntity(id, me, "MEMBER", "Me", null, null, "t"), ChatMemberEntity(id, amina, "MEMBER", "Amina", "amina", null, "t"), ChatMemberEntity(id, "wanjiru", "MEMBER", "Wanjiru", null, null, "t")))
    }

    private fun sealed(payload: ChatPayload) = Base64.encode(WireFormat.encode(WireFormat.Type.WhisperMessage, "ct:".encodeToByteArray() + ChatPayload.encode(payload)))

    /** What the fake crypto opens to something that isn't a payload: this phone can't show it. */
    private val broken = Base64.encode(WireFormat.encode(WireFormat.Type.WhisperMessage, "ct:garbage".encodeToByteArray()))

    private fun inboxOf(vararg messages: InboxMessageDto) { messagesRemote.inboxPages += InboxResponse(messages.toList(), null) }

    private fun dto(id: String, ciphertext: String, kind: String = MessagingWire.KindMessage, sentAt: String = "2026-11-05T09:00:00Z", chat: String = "c1", from: String = amina) =
        InboxMessageDto(id, chat, from, kind, ciphertext, emptyList(), sentAt)

    private fun seen(outgoing: Boolean = false, transferId: String = "t-1") =
        ChatPaymentSeen("c1", transferId, outgoing, if (outgoing) amina else amina, "7699911", "USDC", "BASE-SEPOLIA", clock)

    private val payment = ChatPayload(type = ChatPayload.Payment, text = "For the shamba", transferId = "t-1", amountMinor = "7699911", asset = "USDC", blockchain = "BASE-SEPOLIA")

    private fun retryRequestsSent() = messagesRemote.sent.filter { (_, r, _) -> r.recipients?.any { WireFormat.decode(Base64.decode(it.ciphertext))?.type == WireFormat.Type.RetryRequest } == true }

    // ---- part 1: money is never hidden by decryption

    @Test fun aConfirmedPaymentShowsItsReceivedCardEvenWhenTheEndToEndCardIsUnreadable() = runSuspending {
        directChat()
        inboxOf(dto("m-pay", broken))
        inbox.sync()
        cards.fromWallet(seen())
        val thread = messages.thread("c1").first()
        assertEquals(1, thread.count { it is Message.Unreadable })
        val card = assertIs<Message.Payment>(thread.single { it is Message.Payment })
        assertFalse(card.outgoing)
        assertEquals(amina, card.senderId)
        assertEquals("t-1", card.transferId)
        assertEquals("7699911", card.amountMinor)
        assertNull(card.note, "the server never has the note")
    }

    @Test fun theWalletsCardIsAddedOnceHoweverOftenItIsHeard() = runSuspending {
        directChat()
        cards.fromWallet(seen())
        cards.fromWallet(seen())
        assertEquals(1, messages.thread("c1").first().count { it is Message.Payment })
    }

    @Test fun theEndToEndCardArrivingLaterFillsTheWalletsCardInsteadOfAddingOne() = runSuspending {
        directChat()
        cards.fromWallet(seen())
        inboxOf(dto("m-pay", sealed(payment)))
        inbox.sync()
        val card = assertIs<Message.Payment>(messages.thread("c1").first().single())
        assertEquals("For the shamba", card.note)
        assertEquals("m-pay", card.messageId)
    }

    @Test fun theWalletAddsNothingWhenTheEndToEndCardIsThere() = runSuspending {
        directChat()
        inboxOf(dto("m-pay", sealed(payment)))
        inbox.sync()
        cards.fromWallet(seen())
        assertEquals("For the shamba", assertIs<Message.Payment>(messages.thread("c1").first().single()).note)
    }

    @Test fun myOwnSentCardComesBackOnANewPhoneAndASendTakesItOver() = runSuspending {
        directChat()
        cards.fromWallet(seen(outgoing = true))
        val sent = assertIs<Message.Payment>(messages.thread("c1").first().single())
        assertTrue(sent.outgoing)
        assertEquals(me, sent.senderId)
        messages.sendPayment("c1", PaymentCard("t-1", "7699911", "USDC", "BASE-SEPOLIA", "Rent"))
        val one = assertIs<Message.Payment>(messages.thread("c1").first().single())
        assertEquals("Rent", one.note)
        assertEquals(DeliveryState.Waiting, one.state)
    }

    private val sunset = com.circaso.core.crypto.MomentQuote("mo-1", "IMAGE", "Sunset at Diani", "dGh1bWI=", 1_800_000_000_000)

    @Test fun aWalletMadeCardTakesTheMomentFromTheEndToEndCardWhenItArrives() = runSuspending {
        directChat()
        cards.fromWallet(seen())
        assertNull(assertIs<Message.Payment>(messages.thread("c1").first().single()).momentQuote, "the wallet knows nothing of moments")
        inboxOf(dto("m-pay", sealed(payment.copy(moment = sunset))))
        inbox.sync()
        val card = assertIs<Message.Payment>(messages.thread("c1").first().single())
        assertEquals(sunset, card.momentQuote)
        assertEquals("m-pay", card.messageId)
    }

    @Test fun mySendTakingOverAWalletMadeCardKeepsTheMoment() = runSuspending {
        directChat()
        cards.fromWallet(seen(outgoing = true))
        messages.sendPayment("c1", PaymentCard("t-1", "7699911", "USDC", "BASE-SEPOLIA", "Asante", sunset))
        val one = assertIs<Message.Payment>(messages.thread("c1").first().single())
        assertEquals(sunset, one.momentQuote)
        assertEquals("Asante", one.note)
    }

    @Test fun aGroupsMoneyGetsNoFallbackCard() = runSuspending {
        groupChat()
        cards.fromWallet(seen().copy(chatId = "g1"))
        assertTrue(messages.thread("g1").first().isEmpty())
    }

    @Test fun someoneElsesPaymentNeverLandsInThisChat() = runSuspending {
        directChat()
        cards.fromWallet(seen().copy(counterpartUserId = "stranger"))
        assertTrue(messages.thread("c1").first().isEmpty())
    }

    // ---- part 2: retry receipts, asking

    @Test fun anUnreadableMessageAsksItsSenderOnceToSendItAgain() = runSuspending {
        directChat()
        inboxOf(dto("m-1", broken))
        inbox.sync()
        val (chatId, request, _) = retryRequestsSent().single()
        assertEquals("c1", chatId)
        assertEquals(MessagingWire.KindControl, request.kind, "a control message: no push, no receipt, never shown")
        assertFalse(request.pushable)
        val to = request.recipients!!.single()
        assertEquals(amina, to.userId)
        val frame = WireFormat.decode(Base64.decode(to.ciphertext))!!
        assertEquals("m-1", WireFormat.readRetryRequest(frame.body))

        inbox.sync()
        clock += 10 * 60_000
        inbox.sync()
        assertEquals(1, retryRequestsSent().size, "once per message, ever")
    }

    @Test fun asksAreAFewAMinuteAndTheRestWait() = runSuspending {
        directChat()
        inboxOf(*Array(7) { dto("m-$it", broken, sentAt = "2026-11-05T09:0$it:00Z") })
        inbox.sync()
        assertEquals(RetryReceipts.AsksPerMinute, retryRequestsSent().size)
        inbox.sync()
        assertEquals(RetryReceipts.AsksPerMinute, retryRequestsSent().size, "still the same minute")
        clock += 61_000
        inbox.sync()
        assertEquals(7, retryRequestsSent().size)
    }

    @Test fun anAskThatCantGoIsTriedAtTheNextSync() = runSuspending {
        directChat()
        messagesRemote.sendResult = { com.circaso.core.common.AppResult.Failure(com.circaso.core.common.AppError.Network) }
        inboxOf(dto("m-1", broken))
        inbox.sync()
        assertTrue(recoveryDao.retries.isEmpty())
        messagesRemote.sendResult = { com.circaso.core.common.AppResult.Success(com.circaso.feature.messaging.data.remote.SentMessageResponse("srv", "2026-11-05T10:00:00Z")) }
        inbox.sync()
        assertEquals(1, recoveryDao.retries.size)
    }

    @Test fun inAGroupTheAskGoesOnlyToTheSender() = runSuspending {
        groupChat()
        val bytes = WireFormat.encode(WireFormat.Type.SenderKeyMessage, "garbage".encodeToByteArray())
        // A group message that can't open: its sender key never came. After a day it is unreadable, then asked for.
        inboxOf(dto("g-1", Base64.encode(bytes), chat = "g1"))
        inbox.sync()
        clock += 25L * 60 * 60 * 1000
        inbox.sync()
        val (_, request, _) = retryRequestsSent().single()
        assertEquals(MessagingWire.KindSenderKey, request.kind, "the one kind the server lets go to one member of a group")
        assertEquals(listOf(amina), request.recipients!!.map { it.userId })
    }

    // ---- part 2: answering

    private suspend fun mySentText(id: String = "m-mine", text: String = "Habari") {
        messagesDao.insert(
            MessageEntity(
                clientMessageId = "c-$id", messageId = id, chatId = "c1", senderId = me, outgoing = true, kind = MessageKind.Text, text = text,
                replyTo = null, status = MessageStatus.Delivered, errorCode = null, cachedRequest = null, sentAt = "2026-11-05T09:00:00Z", orderAt = clock, createdAt = clock,
            ),
        )
    }

    private fun askFrom(id: String, asking: String = amina, chat: String = "c1", kind: String = MessagingWire.KindControl) =
        dto("ask-$id-${messagesRemote.sent.size}-$clock", Base64.encode(WireFormat.retryRequest(id)), kind = kind, chat = chat, from = asking)

    private fun payloadOf(ciphertext: String): ChatPayload? {
        val frame = WireFormat.decode(Base64.decode(ciphertext)) ?: return null
        if (frame.type != WireFormat.Type.WhisperMessage && frame.type != WireFormat.Type.PreKeyMessage) return null
        return ChatPayload.decode(frame.body.copyOfRange(3, frame.body.size))
    }

    @Test fun askedForMyMessageISendItAgainOnAFreshSessionUnderItsId() = runSuspending {
        directChat()
        mySentText()
        store.saveSession(amina, "old-session".encodeToByteArray(), 0)
        inboxOf(askFrom("m-mine"))
        inbox.sync()
        assertEquals(listOf(amina), bundlesFetched, "their current bundle, for a fresh session")
        val (chatId, request, _) = messagesRemote.sent.single()
        assertEquals("c1", chatId)
        assertEquals(MessagingWire.KindControl, request.kind)
        assertFalse(request.pushable, "an old message sent again doesn't ring their phone")
        val payload = payloadOf(request.recipients!!.single().ciphertext)!!
        assertEquals("m-mine", payload.resendOf)
        assertEquals("Habari", payload.text)
    }

    @Test fun eachMessageIsSentAgainOncePerPerson() = runSuspending {
        directChat()
        mySentText()
        inboxOf(askFrom("m-mine"))
        inbox.sync()
        inboxOf(askFrom("m-mine"))
        inbox.sync()
        assertEquals(1, messagesRemote.sent.size)
    }

    @Test fun answersAreRateLimitedPerPerson() = runSuspending {
        directChat()
        repeat(12) { mySentText("m-$it") }
        inboxOf(*Array(12) { askFrom("m-$it") })
        inbox.sync()
        assertEquals(RetryReceipts.AnswersPerMinute, messagesRemote.sent.size)
        assertEquals(1, bundlesFetched.size, "one fresh session however many messages they ask for")
    }

    @Test fun askingForSomeoneElsesMessageOrFromOutsideTheChatGetsNothing() = runSuspending {
        directChat()
        mySentText()
        messagesDao.insert(
            MessageEntity(
                clientMessageId = "theirs", messageId = "m-theirs", chatId = "c1", senderId = amina, outgoing = false, kind = MessageKind.Text, text = "Mine",
                replyTo = null, status = MessageStatus.Received, errorCode = null, cachedRequest = null, sentAt = null, orderAt = clock, createdAt = clock,
            ),
        )
        inboxOf(askFrom("m-theirs"), askFrom("m-mine", asking = "stranger"), askFrom("m-nothing"))
        inbox.sync()
        assertTrue(messagesRemote.sent.isEmpty())
    }

    @Test fun aGroupAskHandsMySenderKeyAgainAndSendsTheMessage1to1() = runSuspending {
        groupChat()
        messagesDao.insert(
            MessageEntity(
                clientMessageId = "c-g", messageId = "g-mine", chatId = "g1", senderId = me, outgoing = true, kind = MessageKind.Text, text = "Mkutano kesho",
                replyTo = null, status = MessageStatus.Delivered, errorCode = null, cachedRequest = null, sentAt = null, orderAt = clock, createdAt = clock,
            ),
        )
        // My sender key went to Amina's old phone; this phone counts her as holding it.
        groupKeys.ensureDistributed("g1", listOf(me, amina, "wanjiru"))
        assertTrue(chatsDao.deliveries.any { it.userId == amina })
        messagesRemote.sent.clear()
        inboxOf(askFrom("g-mine", chat = "g1", kind = MessagingWire.KindSenderKey))
        inbox.sync()
        val kinds = messagesRemote.sent.map { (_, r, _) -> r.kind to r.recipients!!.map { it.userId } }
        assertEquals(listOf(MessagingWire.KindSenderKey to listOf(amina), MessagingWire.KindSenderKey to listOf(amina)), kinds, "the key, then the message, both to her alone")
        assertEquals(ChatPayload.SenderKey, payloadOf(messagesRemote.sent[0].second.recipients!!.single().ciphertext)!!.type)
        assertEquals("g-mine", payloadOf(messagesRemote.sent[1].second.recipients!!.single().ciphertext)!!.resendOf)
        assertTrue(chatsDao.deliveries.any { it.userId == amina }, "her new phone counts as holding my key now")
    }

    @Test fun aNewIdentityForgetsWhichOfMyGroupSenderKeysThatPersonHeld() = runSuspending {
        // The wiring in MessagingModule: SessionCipher's identityChanged forgets the person's deliveries in every group.
        val forgetting = SessionCipher(store, crypto, keys, now = { clock }, identityChanged = { chatsDao.deleteDeliveriesTo(it) })
        chatsDao.insertDeliveries(listOf(SenderKeyDeliveryEntity("g1", amina, "d1", 0), SenderKeyDeliveryEntity("g2", amina, "d2", 0), SenderKeyDeliveryEntity("g1", "wanjiru", "d1", 0)))
        store.rememberPeerIdentity(amina, "old-identity".encodeToByteArray(), 0)
        forgetting.seal(amina, "x".encodeToByteArray()) // no session: her bundle, with her new identity
        assertEquals(listOf("wanjiru"), chatsDao.deliveries.map { it.userId })
    }

    // ---- part 2: what was sent again replaces the unreadable row

    @Test fun theMessageSentAgainTakesTheUnreadableRowsPlaceAndId() = runSuspending {
        directChat()
        inboxOf(dto("m-1", broken, sentAt = "2026-11-05T09:00:00Z"))
        inbox.sync()
        val before = messages.thread("c1").first().single()
        assertIs<Message.Unreadable>(before)

        inboxOf(dto("srv-resend", sealed(ChatPayload(type = ChatPayload.Text, text = "Habari", resendOf = "m-1")), kind = MessagingWire.KindControl, sentAt = "2026-11-05T11:00:00Z"))
        inbox.sync()
        val after = assertIs<Message.Text>(messages.thread("c1").first().single())
        assertEquals("Habari", after.text)
        assertEquals("m-1", after.messageId, "the original id: receipts and replies still point at it")
        assertEquals(before.orderAt, after.orderAt, "its place in the thread")
    }

    @Test fun theUnreadablePaymentSentAgainBecomesOneCardWithTheWalletsCard() = runSuspending {
        directChat()
        inboxOf(dto("m-pay", broken))
        inbox.sync()
        cards.fromWallet(seen())
        inboxOf(dto("srv-resend", sealed(payment.copy(resendOf = "m-pay")), kind = MessagingWire.KindControl))
        inbox.sync()
        val card = assertIs<Message.Payment>(messages.thread("c1").first().single())
        assertEquals("For the shamba", card.note)
        assertEquals("m-pay", card.messageId)
    }

    @Test fun aResendCantReplaceAMessageThatOpenedOrIsSomeoneElses() = runSuspending {
        directChat()
        inboxOf(dto("m-ok", sealed(ChatPayload(type = ChatPayload.Text, text = "Real"))))
        inbox.sync()
        inboxOf(dto("srv-x", sealed(ChatPayload(type = ChatPayload.Text, text = "Fake", resendOf = "m-ok")), kind = MessagingWire.KindControl))
        inbox.sync()
        assertEquals("Real", assertIs<Message.Text>(messages.thread("c1").first().single()).text)
    }

    @Test fun aNewIdentityRestartsTheSessionAndSaysTheSecurityCodeChanged() = runSuspending {
        directChat()
        mySentText()
        // Amina's phone was replaced: the bundle now carries a new identity.
        store.saveSession(amina, "old".encodeToByteArray(), 0)
        store.rememberPeerIdentity(amina, "old-identity".encodeToByteArray(), 0)
        inboxOf(askFrom("m-mine"))
        inbox.sync()
        assertTrue(messages.thread("c1").first().any { it is Message.System && it.text.contains("security code changed") })
        assertEquals(1, messagesRemote.sent.size)
    }
}
