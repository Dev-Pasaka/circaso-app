package com.circaso.core.notifications

import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.WireFormat
import com.circaso.feature.messaging.data.local.ChatEntity
import com.circaso.feature.messaging.data.local.ChatMemberEntity
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.local.SenderKeyEntity
import com.circaso.feature.messaging.data.local.SessionEntity
import com.circaso.feature.messaging.data.local.SignalIdentityEntity
import com.circaso.testing.FakeChatsDao
import com.circaso.testing.FakeSealer
import com.circaso.testing.FakeSignalCrypto
import com.circaso.testing.FakeSignalDao
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationPeekTest {
    private val sealer = FakeSealer()
    private val crypto = FakeSignalCrypto()
    private val signal = FakeSignalDao()
    private val chats = FakeChatsDao()
    private val peek = NotificationPeek(crypto, sealer)
    private val distribution = "11111111-2222-4333-8444-555555555555"

    private suspend fun setUp() {
        signal.identityRow = SignalIdentityEntity(keyPair = sealer.seal("idpriv".encodeToByteArray(), "identity:1"), publicKey = "idpub".encodeToByteArray(), registrationId = 7, createdAt = 0, publishedAt = 0)
        signal.sessions["amina"] = SessionEntity("amina", sealer.seal("s".encodeToByteArray(), "session:amina"), 0)
        signal.senderKeys[Triple("fund", "otieno", distribution)] = SenderKeyEntity("fund", "otieno", distribution, sealer.seal("skr".encodeToByteArray(), "sender:fund:otieno:$distribution"), 0)
        chats.upsertChat(chat("dm", "DIRECT", null))
        chats.upsertChat(chat("fund", "GROUP", "Holiday Fund"))
        chats.insertMembers(listOf(member("dm", "amina", "Amina Wanjiku", "https://cdn/amina.jpg?sig=1"), member("fund", "otieno", "Otieno", null)))
    }

    private fun chat(id: String, kind: String, name: String?) = ChatEntity(id, kind, name, null, 0, 1, true, null, false, 0, 0)
    private fun member(chatId: String, userId: String, name: String, photo: String?) = ChatMemberEntity(chatId, userId, "MEMBER", name, null, photo, "2026-11-01T00:00:00Z")

    private fun direct(payload: ChatPayload) = Base64.encode(WireFormat.encode(WireFormat.Type.WhisperMessage, "ct:".encodeToByteArray() + ChatPayload.encode(payload)))
    private fun group(payload: ChatPayload) = Base64.encode(WireFormat.encode(WireFormat.Type.SenderKeyMessage, "gct:".encodeToByteArray() + ChatPayload.encode(payload)))

    private fun push(chatId: String, sender: String, ciphertext: String?) =
        buildMap { put("chatId", chatId); put("messageId", "m1"); put("senderId", sender); put("sentAt", "2026-11-09T10:15:30Z"); ciphertext?.let { put("ciphertext", it) } }

    private suspend fun snapshot() = NotificationSnapshot.build(signal, chats, sealer)

    @Test fun aDirectMessageShowsTheSenderTheirPhotoAndWhatTheySaid() = runTest {
        setUp()
        val worded = peek.word(push("dm", "amina", direct(ChatPayload(type = ChatPayload.Text, text = "On my way"))), snapshot(), private = false)!!
        assertEquals(NotificationContent("Amina Wanjiku", "On my way"), worded.content)
        assertEquals("https://cdn/amina.jpg?sig=1", worded.conversation?.senderPhoto)
        assertEquals("On my way", worded.conversation?.line)
        assertEquals(false, worded.conversation?.group)
    }

    @Test fun aGroupMessageIsTitledByTheGroupAndNamesTheSender() = runTest {
        setUp()
        val worded = peek.word(push("fund", "otieno", group(ChatPayload(type = ChatPayload.Voice, attachmentId = "a", mediaKey = "k", digest = "d", durationMs = 12_000))), snapshot(), private = false)!!
        assertEquals(NotificationContent("Holiday Fund", "Otieno: 🎤 Voice message (0:12)"), worded.content)
        assertEquals("Holiday Fund", worded.conversation?.title)
        assertEquals("Otieno", worded.conversation?.senderName)
    }

    @Test fun aPaymentCardIsWordedAsMoney() = runTest {
        setUp()
        val worded = peek.word(push("dm", "amina", direct(ChatPayload(type = ChatPayload.Payment, transferId = "t1", amountMinor = "5000000", asset = "USDC", text = "lunch"))), snapshot(), private = false)!!
        assertEquals(NotificationContent("Amina Wanjiku", "Sent you 5.00 USDC · lunch"), worded.content)
    }

    @Test fun whatTheSnapshotCantOpenStillNamesTheSender() = runTest {
        setUp()
        // No sender key for this sender: name only, never the server's blank words.
        val worded = peek.word(push("fund", "wanjiru", group(ChatPayload(type = ChatPayload.Text, text = "hi"))), snapshot(), private = false)!!
        assertEquals(NotificationContent("Holiday Fund", "New message"), worded.content)
        val noCiphertext = peek.word(push("dm", "amina", null), snapshot(), private = false)!!
        assertEquals(NotificationContent("Amina Wanjiku", "New message"), noCiphertext.content)
    }

    @Test fun privacyAndUnknownChatsKeepTheGenericWords() = runTest {
        setUp()
        val private = peek.word(push("dm", "amina", direct(ChatPayload(type = ChatPayload.Text, text = "secret"))), snapshot(), private = true)!!
        assertEquals(NotificationContent("Circaso", "New message"), private.content)
        assertNull(private.conversation)
        assertNull(peek.word(push("elsewhere", "amina", null), snapshot(), private = false))
        assertNull(peek.word(push("dm", "amina", null), null, private = false))
        assertNull(peek.word(mapOf("kind" to "payment.received", "chatId" to "dm", "messageId" to "m"), snapshot(), private = false))
    }

    @Test fun signedOutThereIsNoSnapshot() = runTest {
        assertNull(snapshot())
        // the sealed kinds stay as the store names them
        assertEquals("ec", SealedRecordStore.Ec)
    }
}
