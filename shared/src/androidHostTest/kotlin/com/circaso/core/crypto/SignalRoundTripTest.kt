package com.circaso.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real libsignal (the JVM artifact) through [AndroidSignalCrypto], the way the messaging layer will drive it: records
 * in, records out, nothing kept inside the bridge. Each "phone" below holds its records in plain maps; in the app they
 * sit sealed in Room.
 */
class SignalRoundTripTest {
    private val crypto = AndroidSignalCrypto()

    /** One phone: its published keys and the records the messaging layer would keep. */
    private inner class Phone(val userId: String) {
        val address = PeerAddress(userId)
        val identity = crypto.generateIdentity()
        val signedPreKey = crypto.generateSignedPreKey(identity, 1, 1_000L)
        val kyberPreKey = crypto.generateKyberPreKey(identity, 1, 1_000L)
        val preKeys = crypto.generatePreKeys(100, 5).associateBy { it.id }.toMutableMap()
        /** Ids the server has already given out in a bundle; their private halves stay until a message uses them. */
        val handedOut = mutableSetOf<Int>()
        val sessions = mutableMapOf<String, ByteArray>()
        val senderKeys = mutableMapOf<Pair<String, String>, ByteArray>() // (senderId, distributionId) -> record
        val peerIdentities = mutableMapOf<String, ByteArray>()

        /** What the server would hand out for me: one one-time prekey is used up each time. */
        fun bundle(): PeerBundle {
            val oneTime = preKeys.values.firstOrNull { it.id !in handedOut }?.also { handedOut += it.id }
            return PeerBundle(
                registrationId = identity.registrationId,
                identityKey = identity.publicKey,
                signedPreKeyId = signedPreKey.id, signedPreKey = signedPreKey.publicKey, signedPreKeySignature = signedPreKey.signature,
                kyberPreKeyId = kyberPreKey.id, kyberPreKey = kyberPreKey.publicKey, kyberPreKeySignature = kyberPreKey.signature,
                oneTimePreKeyId = oneTime?.id, oneTimePreKey = oneTime?.publicKey,
            )
        }

        fun ownKeys() = OwnKeys(
            preKeys = preKeys.mapValues { it.value.record },
            signedPreKeys = mapOf(signedPreKey.id to signedPreKey.record),
            kyberPreKeys = mapOf(kyberPreKey.id to kyberPreKey.record),
        )

        fun send(to: Phone, text: String): Pair<CiphertextKind, ByteArray> {
            val session = sessions[to.userId] ?: crypto.startSession(identity, to.address, to.bundle(), null).also { peerIdentities[to.userId] = it.peerIdentityKey }.session
            val out = crypto.encrypt(identity, to.address, session, ChatPayload.encode(ChatPayload(type = ChatPayload.Text, text = text)))
            sessions[to.userId] = out.session
            return out.kind to out.ciphertext
        }

        fun receive(from: Phone, message: Pair<CiphertextKind, ByteArray>): String {
            val (kind, bytes) = message
            val needs = crypto.inspect(kind, bytes)
            val result = crypto.decrypt(identity, from.address, sessions[from.userId], kind, bytes, ownKeys())
            sessions[from.userId] = result.session
            peerIdentities[from.userId] = result.peerIdentityKey
            result.consumedPreKeyId?.let { id -> assertEquals(needs.preKeyId, id); preKeys.remove(id) }
            return assertNotNull(ChatPayload.decode(result.plaintext)).text!!
        }
    }

    @Test fun firstMessageIsAPreKeyMessageAndRepliesAreWhisperMessages() {
        val alice = Phone("alice")
        val bob = Phone("bob")
        val first = alice.send(bob, "Habari Bob")
        assertEquals(CiphertextKind.PreKey, first.first)
        assertEquals(1, bob.handedOut.size, "the bundle used one of Bob's one-time prekeys")
        assertEquals("Habari Bob", bob.receive(alice, first))
        assertEquals(4, bob.preKeys.size, "and decrypting consumed its private half")
        val reply = bob.send(alice, "Poa, Alice")
        assertEquals(CiphertextKind.Whisper, reply.first)
        assertEquals("Poa, Alice", alice.receive(bob, reply))
        assertContentEquals(bob.identity.publicKey, alice.peerIdentities["bob"])
        assertContentEquals(alice.identity.publicKey, bob.peerIdentities["alice"])
    }

    @Test fun outOfOrderDeliveryWorksAndAReplayIsADuplicate() {
        val alice = Phone("alice")
        val bob = Phone("bob")
        bob.receive(alice, alice.send(bob, "0"))
        alice.receive(bob, bob.send(alice, "0 back"))
        val m1 = alice.send(bob, "1")
        val m2 = alice.send(bob, "2")
        val m3 = alice.send(bob, "3")
        assertEquals("3", bob.receive(alice, m3))
        assertEquals("1", bob.receive(alice, m1))
        assertEquals("2", bob.receive(alice, m2))
        assertFailsWith<SignalException.Duplicate> { bob.receive(alice, m1) }
    }

    @Test fun aWhisperMessageWithoutASessionCannotBeRead() {
        val alice = Phone("alice")
        val bob = Phone("bob")
        bob.receive(alice, alice.send(bob, "hi"))
        alice.receive(bob, bob.send(alice, "hi back")) // Alice only drops to Whisper messages once Bob has answered
        val later = alice.send(bob, "again")
        assertEquals(CiphertextKind.Whisper, later.first)
        val stranger = Phone("bob") // same id, fresh phone: no session
        assertFailsWith<SignalException.NoSession> { stranger.receive(alice, later) }
    }

    @Test fun senderKeysReachEveryMemberAndARotationLocksOutWhoeverMissedIt() {
        val alice = Phone("alice"); val bob = Phone("bob"); val carol = Phone("carol")
        val members = listOf(bob, carol)
        var distributionId = "11111111-2222-4333-8444-555555555555"
        val created = crypto.createSenderKeyDistribution(alice.address, distributionId, null)
        alice.senderKeys["alice" to distributionId] = created.record
        assertEquals(distributionId, crypto.distributionIdOf(GroupBytes.Distribution, created.distribution))
        members.forEach { m ->
            val learned = crypto.processSenderKeyDistribution(alice.address, created.distribution, null)
            m.senderKeys["alice" to learned.distributionId] = learned.record
        }

        val sealed = crypto.groupEncrypt(alice.address, distributionId, alice.senderKeys["alice" to distributionId]!!, "to the group".encodeToByteArray())
        alice.senderKeys["alice" to distributionId] = sealed.record
        assertEquals(distributionId, crypto.distributionIdOf(GroupBytes.Message, sealed.ciphertext))
        members.forEach { m ->
            val opened = crypto.groupDecrypt(alice.address, distributionId, m.senderKeys["alice" to distributionId]!!, sealed.ciphertext)
            m.senderKeys["alice" to distributionId] = opened.record
            assertEquals("to the group", opened.plaintext.decodeToString())
        }

        // Carol is removed: Alice makes a new sender key and hands it to Bob only.
        val oldId = distributionId
        distributionId = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        val rotated = crypto.createSenderKeyDistribution(alice.address, distributionId, null)
        alice.senderKeys["alice" to distributionId] = rotated.record
        bob.senderKeys["alice" to distributionId] = crypto.processSenderKeyDistribution(alice.address, rotated.distribution, null).record
        val after = crypto.groupEncrypt(alice.address, distributionId, alice.senderKeys["alice" to distributionId]!!, "without carol".encodeToByteArray())
        assertEquals("without carol", crypto.groupDecrypt(alice.address, distributionId, bob.senderKeys["alice" to distributionId]!!, after.ciphertext).plaintext.decodeToString())
        assertNull(carol.senderKeys["alice" to distributionId])
        assertFailsWith<SignalException> { crypto.groupDecrypt(alice.address, oldId, carol.senderKeys["alice" to oldId]!!, after.ciphertext) }
    }

    @Test fun fingerprintsMatchFromBothSides() {
        val alice = Phone("alice"); val bob = Phone("bob")
        val fromAlice = crypto.fingerprint("alice", alice.identity.publicKey, "bob", bob.identity.publicKey)
        val fromBob = crypto.fingerprint("bob", bob.identity.publicKey, "alice", alice.identity.publicKey)
        assertEquals(fromAlice, fromBob)
        assertEquals(60, fromAlice.length)
        assertTrue(fromAlice.all { it.isDigit() })
    }

    @Test fun aesGcmRoundTripsAndRefusesTampering() {
        val key = crypto.randomBytes(32)
        val sealed = crypto.aesGcmSeal(key, "group name".encodeToByteArray(), "meta:1".encodeToByteArray())
        assertEquals("group name", crypto.aesGcmOpen(key, sealed, "meta:1".encodeToByteArray()).decodeToString())
        assertFailsWith<SignalException.Invalid> { crypto.aesGcmOpen(key, sealed, "meta:2".encodeToByteArray()) }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<SignalException.Invalid> { crypto.aesGcmOpen(key, tampered, "meta:1".encodeToByteArray()) }
        assertEquals(32, crypto.sha256(ByteArray(0)).size)
    }

    @Test fun theWireFormatCarriesAMessageEndToEnd() {
        val alice = Phone("alice"); val bob = Phone("bob")
        val (kind, bytes) = alice.send(bob, "over the wire")
        val wire = WireFormat.encode(WireFormat.typeOf(kind), bytes)
        val frame = assertNotNull(WireFormat.decode(wire))
        assertEquals(WireFormat.Type.PreKeyMessage, frame.type)
        assertEquals("over the wire", bob.receive(alice, CiphertextKind.PreKey to frame.body))
    }
}
