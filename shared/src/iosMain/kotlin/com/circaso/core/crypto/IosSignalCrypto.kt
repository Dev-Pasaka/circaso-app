package com.circaso.core.crypto

/**
 * [SignalCrypto] on iOS: every call goes to the Swift implementation installed in [SignalBridge] at launch.
 * Converts bytes both ways and turns the Swift side's error names into [SignalException]s.
 */
class IosSignalCrypto : SignalCrypto {
    private val api: SignalBridgeApi
        get() = SignalBridge.api ?: throw IllegalStateException("SignalBridge.api not installed; set it in iOSApp.swift at launch")

    override fun generateIdentity(): OwnIdentity = guarded {
        val r = api.makeIdentity()
        OwnIdentity(r.keyPair.toByteArray(), r.publicKey.toByteArray(), r.registrationId)
    }

    override fun generateSignedPreKey(identity: OwnIdentity, id: Int, timestampMillis: Long): SignedPreKey = guarded {
        val r = api.generateSignedPreKey(identity.keyPair.toNSData(), id, timestampMillis)
        SignedPreKey(r.id, r.record.toByteArray(), r.publicKey.toByteArray(), r.signature.toByteArray())
    }

    override fun generateKyberPreKey(identity: OwnIdentity, id: Int, timestampMillis: Long): KyberPreKey = guarded {
        val r = api.generateKyberPreKey(identity.keyPair.toNSData(), id, timestampMillis)
        KyberPreKey(r.id, r.record.toByteArray(), r.publicKey.toByteArray(), r.signature.toByteArray())
    }

    override fun generatePreKeys(startId: Int, count: Int): List<OneTimePreKey> = guarded {
        List(count) { i -> api.generatePreKey(startId + i).let { OneTimePreKey(it.id, it.record.toByteArray(), it.publicKey.toByteArray()) } }
    }

    override fun startSession(identity: OwnIdentity, peer: PeerAddress, bundle: PeerBundle, current: ByteArray?): SessionStart = guarded {
        val b = BridgeBundle(
            registrationId = bundle.registrationId, identityKey = bundle.identityKey.toNSData(),
            signedPreKeyId = bundle.signedPreKeyId, signedPreKey = bundle.signedPreKey.toNSData(), signedPreKeySignature = bundle.signedPreKeySignature.toNSData(),
            kyberPreKeyId = bundle.kyberPreKeyId, kyberPreKey = bundle.kyberPreKey.toNSData(), kyberPreKeySignature = bundle.kyberPreKeySignature.toNSData(),
            oneTimePreKeyId = bundle.oneTimePreKeyId ?: NoId, oneTimePreKey = bundle.oneTimePreKey?.toNSData(),
        )
        val session = api.startSession(identity.keyPair.toNSData(), identity.registrationId, peer.userId, peer.deviceId, b, current?.toNSData())
        SessionStart(session.toByteArray(), bundle.identityKey)
    }

    override fun encrypt(identity: OwnIdentity, peer: PeerAddress, session: ByteArray, plaintext: ByteArray): Encrypted = guarded {
        val r = api.encrypt(identity.keyPair.toNSData(), identity.registrationId, peer.userId, peer.deviceId, session.toNSData(), plaintext.toNSData())
        Encrypted(if (r.isPreKeyMessage) CiphertextKind.PreKey else CiphertextKind.Whisper, r.ciphertext.toByteArray(), r.session.toByteArray())
    }

    override fun inspect(kind: CiphertextKind, ciphertext: ByteArray): CiphertextNeeds = guarded {
        val r = api.inspect(kind == CiphertextKind.PreKey, ciphertext.toNSData())
        CiphertextNeeds(r.preKeyId.takeIf { it != NoId }, r.signedPreKeyId.takeIf { it != NoId }, null, r.senderIdentityKey?.toByteArray())
    }

    override fun decrypt(identity: OwnIdentity, peer: PeerAddress, session: ByteArray?, kind: CiphertextKind, ciphertext: ByteArray, keys: OwnKeys): Decrypted = guarded {
        if (kind == CiphertextKind.Whisper && session == null) throw SignalException.NoSession()
        val r = api.decrypt(
            identity.keyPair.toNSData(), identity.registrationId, peer.userId, peer.deviceId, session?.toNSData(),
            kind == CiphertextKind.PreKey, ciphertext.toNSData(),
            keys.preKeys.map { (id, bytes) -> BridgeRecord(id, bytes.toNSData()) },
            keys.signedPreKeys.map { (id, bytes) -> BridgeRecord(id, bytes.toNSData()) },
            keys.kyberPreKeys.map { (id, bytes) -> BridgeRecord(id, bytes.toNSData()) },
        )
        Decrypted(r.plaintext.toByteArray(), r.session.toByteArray(), r.peerIdentityKey.toByteArray(), r.consumedPreKeyId.takeIf { it != NoId })
    }

    override fun createSenderKeyDistribution(self: PeerAddress, distributionId: String, current: ByteArray?): SenderKeyDistribution = guarded {
        val r = api.createSenderKeyDistribution(self.userId, self.deviceId, distributionId, current?.toNSData())
        SenderKeyDistribution(r.bytes.toByteArray(), r.record.toByteArray())
    }

    override fun distributionIdOf(kind: GroupBytes, bytes: ByteArray): String = guarded {
        api.distributionIdOf(kind == GroupBytes.Distribution, bytes.toNSData())
    }

    override fun processSenderKeyDistribution(sender: PeerAddress, distribution: ByteArray, current: ByteArray?): SenderKeyRecord = guarded {
        val r = api.processSenderKeyDistribution(sender.userId, sender.deviceId, distribution.toNSData(), current?.toNSData())
        SenderKeyRecord(r.distributionId, r.record.toByteArray())
    }

    override fun groupEncrypt(self: PeerAddress, distributionId: String, record: ByteArray, plaintext: ByteArray): GroupEncrypted = guarded {
        val r = api.groupEncrypt(self.userId, self.deviceId, distributionId, record.toNSData(), plaintext.toNSData())
        GroupEncrypted(r.bytes.toByteArray(), r.record.toByteArray())
    }

    override fun groupDecrypt(sender: PeerAddress, distributionId: String, record: ByteArray, ciphertext: ByteArray): GroupDecrypted = guarded {
        val r = api.groupDecrypt(sender.userId, sender.deviceId, distributionId, record.toNSData(), ciphertext.toNSData())
        GroupDecrypted(r.bytes.toByteArray(), r.record.toByteArray())
    }

    override fun fingerprint(localId: String, localIdentityKey: ByteArray, remoteId: String, remoteIdentityKey: ByteArray): String = guarded {
        api.safetyNumber(localId, localIdentityKey.toNSData(), remoteId, remoteIdentityKey.toNSData())
    }

    override fun aesGcmSeal(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray = guarded {
        api.gcmSeal(key.toNSData(), plaintext.toNSData(), associatedData.toNSData()).toByteArray()
    }

    override fun aesGcmOpen(key: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray = guarded {
        api.gcmOpen(key.toNSData(), sealed.toNSData(), associatedData.toNSData()).toByteArray()
    }

    override fun sha256(bytes: ByteArray): ByteArray = api.digestSha256(bytes.toNSData()).toByteArray()

    /**
     * libsignal's incremental AES-256-GCM through the Swift bridge (the same Rust code, at the same version, as
     * Android's): the box [aesGcmSeal] makes and [aesGcmOpen] reads, a piece at a time. Offered only once this phone
     * has shown, on a small box, that the two agree both ways ([streamAgrees]); otherwise null, and the one-shot is
     * used as before.
     */
    override fun gcmStream(key: ByteArray, nonce: ByteArray, seal: Boolean): GcmStream? {
        if (key.size != 32 || nonce.size != 12 || !streamAgrees) return null
        return rawStream(key, nonce, seal)
    }

    private fun rawStream(key: ByteArray, nonce: ByteArray, seal: Boolean): GcmStream? {
        val stream = api.gcmStream(key.toNSData(), nonce.toNSData(), seal) ?: return null
        return object : GcmStream {
            override fun update(piece: ByteArray) {
                if (piece.isEmpty()) return
                // Each piece's NSData copies are let go before the next piece, so a 64 MiB file stays a few MiB in memory.
                kotlinx.cinterop.autoreleasepool {
                    val out = stream.update(piece.toNSData())?.toByteArray()
                    check(out != null && out.size == piece.size) { "the streaming cipher failed" }
                    out.copyInto(piece)
                }
            }
            override fun tag(): ByteArray = checkNotNull(stream.tag()) { "the streaming cipher made no tag" }.toByteArray()
            override fun verify(tag: ByteArray): Boolean = stream.verify(tag.toNSData())
        }
    }

    override fun sha256Stream(): HashStream? {
        if (!streamAgrees) return null
        val hash = api.sha256Stream()
        return object : HashStream {
            override fun update(bytes: ByteArray) { if (bytes.isNotEmpty()) kotlinx.cinterop.autoreleasepool { hash.update(bytes.toNSData()) } }
            override fun finish(): ByteArray = hash.finish().toByteArray()
        }
    }

    /**
     * Checked once per run, before the streaming cipher is trusted with anything: a small box made in pieces opens
     * with the one-shot (CryptoKit), a box made by the one-shot opens in pieces to the same bytes with its tag
     * accepted and a changed tag refused, and the streamed SHA-256 equals the one-shot's. Anything else (a bridge
     * that isn't there, a mismatch) turns streaming off for this run and says so in the diagnostics.
     */
    private val streamAgrees: Boolean by lazy {
        val ok = runCatching {
            val key = randomBytes(32)
            val plain = ByteArray(40_000) { (it * 31 + 7).toByte() }
            // Sealed in two pieces, opened by the one-shot.
            val nonce = randomBytes(12)
            val sealing = rawStream(key, nonce, seal = true) ?: return@runCatching false
            val first = plain.copyOfRange(0, 25_000).also { sealing.update(it) }
            val second = plain.copyOfRange(25_000, plain.size).also { sealing.update(it) }
            val box = nonce + first + second + sealing.tag()
            if (!aesGcmOpen(key, box).contentEquals(plain)) return@runCatching false
            // Sealed by the one-shot, opened in two pieces.
            val theirs = aesGcmSeal(key, plain)
            val opening = rawStream(key, theirs.copyOfRange(0, 12), seal = false) ?: return@runCatching false
            val a = theirs.copyOfRange(12, 20_012).also { opening.update(it) }
            val b = theirs.copyOfRange(20_012, theirs.size - 16).also { opening.update(it) }
            val tag = theirs.copyOfRange(theirs.size - 16, theirs.size)
            if (!(a + b).contentEquals(plain) || !opening.verify(tag)) return@runCatching false
            // A changed tag is refused.
            val again = rawStream(key, theirs.copyOfRange(0, 12), seal = false) ?: return@runCatching false
            theirs.copyOfRange(12, theirs.size - 16).also { again.update(it) }
            if (again.verify(tag.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })) return@runCatching false
            // The streamed hash is the one-shot's.
            val hash = api.sha256Stream()
            hash.update(box.copyOfRange(0, 1_000).toNSData()); hash.update(box.copyOfRange(1_000, box.size).toNSData())
            hash.finish().toByteArray().contentEquals(sha256(box))
        }.getOrDefault(false)
        com.circaso.core.common.Diagnostics.note(if (ok) "crypto: file sealing in pieces agrees with the one-shot" else "crypto: file sealing in pieces does NOT agree with the one-shot; using the one-shot")
        ok
    }

    override fun randomBytes(count: Int): ByteArray = api.secureRandomBytes(count).toByteArray()

    /** The Swift side throws an `NSError` whose description is libsignal's `SignalError` case name; map it like Android does. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SignalException) {
        throw e
    } catch (e: IllegalStateException) {
        throw e // the bridge is not installed: a programming error, not a message problem
    } catch (e: Throwable) {
        throw when (val code = e.message?.substringBefore(' ')?.trim()) {
            "duplicatedMessage" -> SignalException.Duplicate()
            "sessionNotFound", "invalidSession", "invalidSenderKeySession" -> SignalException.NoSession()
            "untrustedIdentity" -> SignalException.UntrustedIdentity()
            "invalidKeyIdentifier" -> SignalException.MissingKey(code)
            else -> SignalException.Invalid()
        }
    }

    private companion object {
        /** The bridge has no optionals for ints: -1 stands for "none" on both sides. */
        const val NoId = -1
    }
}
