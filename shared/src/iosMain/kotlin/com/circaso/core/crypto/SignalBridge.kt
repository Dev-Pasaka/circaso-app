package com.circaso.core.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * libsignal's Swift package lives on the Swift side of the iOS app. The app implements [SignalBridgeApi] (in
 * `iosApp/iosApp/SignalCryptoImpl.swift`) and installs it at launch, the same way Google sign-in is installed; the
 * shared code talks to it through [IosSignalCrypto]. Bytes cross as `NSData` so Swift sees `Data`, not `KotlinByteArray`.
 * Every method is `@Throws` so Swift may throw: it throws an `NSError` whose description is libsignal's `SignalError`
 * case name (`duplicatedMessage`, `sessionNotFound`, …), which reaches Kotlin as the exception message.
 */
/* Method names differ from [SignalCrypto]'s on purpose: Kotlin/Native would otherwise mangle the clashing Swift names. */
interface SignalBridgeApi {
    @Throws(Throwable::class)
    fun makeIdentity(): BridgeIdentity
    @Throws(Throwable::class)
    fun generateSignedPreKey(identityKeyPair: NSData, id: Int, timestampMillis: Long): BridgeSignedKey
    @Throws(Throwable::class)
    fun generateKyberPreKey(identityKeyPair: NSData, id: Int, timestampMillis: Long): BridgeSignedKey
    @Throws(Throwable::class)
    fun generatePreKey(id: Int): BridgePreKey

    @Throws(Throwable::class)
    fun startSession(identityKeyPair: NSData, registrationId: Int, peerUserId: String, peerDeviceId: Int, bundle: BridgeBundle, currentSession: NSData?): NSData
    @Throws(Throwable::class)
    fun encrypt(identityKeyPair: NSData, registrationId: Int, peerUserId: String, peerDeviceId: Int, session: NSData, plaintext: NSData): BridgeEncrypted
    @Throws(Throwable::class)
    fun inspect(isPreKeyMessage: Boolean, ciphertext: NSData): BridgeNeeds
    @Throws(Throwable::class)
    fun decrypt(
        identityKeyPair: NSData, registrationId: Int, peerUserId: String, peerDeviceId: Int, session: NSData?,
        isPreKeyMessage: Boolean, ciphertext: NSData, preKeys: List<BridgeRecord>, signedPreKeys: List<BridgeRecord>, kyberPreKeys: List<BridgeRecord>,
    ): BridgeDecrypted

    @Throws(Throwable::class)
    fun createSenderKeyDistribution(selfUserId: String, selfDeviceId: Int, distributionId: String, current: NSData?): BridgeSenderKey
    @Throws(Throwable::class)
    fun distributionIdOf(isDistribution: Boolean, bytes: NSData): String
    @Throws(Throwable::class)
    fun processSenderKeyDistribution(senderUserId: String, senderDeviceId: Int, distribution: NSData, current: NSData?): BridgeSenderKey
    @Throws(Throwable::class)
    fun groupEncrypt(selfUserId: String, selfDeviceId: Int, distributionId: String, record: NSData, plaintext: NSData): BridgeSenderKey
    @Throws(Throwable::class)
    fun groupDecrypt(senderUserId: String, senderDeviceId: Int, distributionId: String, record: NSData, ciphertext: NSData): BridgeSenderKey

    @Throws(Throwable::class)
    fun safetyNumber(localId: String, localIdentityKey: NSData, remoteId: String, remoteIdentityKey: NSData): String
    @Throws(Throwable::class)
    fun gcmSeal(key: NSData, plaintext: NSData, associatedData: NSData): NSData
    @Throws(Throwable::class)
    fun gcmOpen(key: NSData, sealed: NSData, associatedData: NSData): NSData
    @Throws(Throwable::class)
    fun digestSha256(bytes: NSData): NSData
    @Throws(Throwable::class)
    fun secureRandomBytes(count: Int): NSData
}

class BridgeIdentity(val keyPair: NSData, val publicKey: NSData, val registrationId: Int)
class BridgeSignedKey(val id: Int, val record: NSData, val publicKey: NSData, val signature: NSData)
class BridgePreKey(val id: Int, val record: NSData, val publicKey: NSData)
class BridgeRecord(val id: Int, val record: NSData)
class BridgeBundle(
    val registrationId: Int, val identityKey: NSData,
    val signedPreKeyId: Int, val signedPreKey: NSData, val signedPreKeySignature: NSData,
    val kyberPreKeyId: Int, val kyberPreKey: NSData, val kyberPreKeySignature: NSData,
    /** -1 and null when the person has no one-time prekeys left. */
    val oneTimePreKeyId: Int, val oneTimePreKey: NSData?,
)
class BridgeEncrypted(val isPreKeyMessage: Boolean, val ciphertext: NSData, val session: NSData)
/** -1 for an id the message does not carry. */
class BridgeNeeds(val preKeyId: Int, val signedPreKeyId: Int, val senderIdentityKey: NSData?)
class BridgeDecrypted(val plaintext: NSData, val session: NSData, val peerIdentityKey: NSData, val consumedPreKeyId: Int)
/** [bytes] is the distribution message or the ciphertext, depending on the call; [record] the sender-key record to keep. */
class BridgeSenderKey(val distributionId: String, val bytes: NSData, val record: NSData)

object SignalBridge {
    var api: SignalBridgeApi? = null
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
