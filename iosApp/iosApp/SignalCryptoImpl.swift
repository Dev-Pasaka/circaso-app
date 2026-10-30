import CryptoKit
import Foundation
import LibSignalClient
import Shared

/// libsignal's Swift package lives here; the shared Kotlin code drives it through `SignalBridgeApi` (see
/// `SignalBridge.kt`). Every call builds an in-memory store from the records it was given, runs one operation and
/// hands the changed records back. Nothing is kept between calls and nothing here is ever logged.
final class SignalCryptoImpl: NSObject, SignalBridgeApi {
    private let context = NullContext()
    /// Our own address on every cipher: with one device per account it only has to differ from the peer's.
    private let selfName = "self"
    private let noId: Int32 = -1

    // MARK: keys

    func makeIdentity() throws -> BridgeIdentity {
        let pair = IdentityKeyPair.generate()
        let registrationId = Int32.random(in: 1...16380)
        return BridgeIdentity(keyPair: pair.serialize(), publicKey: pair.publicKey.serialize(), registrationId: registrationId)
    }

    func generateSignedPreKey(identityKeyPair: Data, id: Int32, timestampMillis: Int64) throws -> BridgeSignedKey {
        try bridged {
            let identity = try IdentityKeyPair(bytes: identityKeyPair)
            let key = PrivateKey.generate()
            let signature = identity.privateKey.generateSignature(message: key.publicKey.serialize())
            let record = try SignedPreKeyRecord(id: UInt32(id), timestamp: UInt64(timestampMillis), privateKey: key, signature: signature)
            return BridgeSignedKey(id: id, record: record.serialize(), publicKey: key.publicKey.serialize(), signature: signature)
        }
    }

    func generateKyberPreKey(identityKeyPair: Data, id: Int32, timestampMillis: Int64) throws -> BridgeSignedKey {
        try bridged {
            let identity = try IdentityKeyPair(bytes: identityKeyPair)
            let pair = KEMKeyPair.generate()
            let signature = identity.privateKey.generateSignature(message: pair.publicKey.serialize())
            let record = try KyberPreKeyRecord(id: UInt32(id), timestamp: UInt64(timestampMillis), keyPair: pair, signature: signature)
            return BridgeSignedKey(id: id, record: record.serialize(), publicKey: pair.publicKey.serialize(), signature: signature)
        }
    }

    func generatePreKey(id: Int32) throws -> BridgePreKey {
        try bridged {
            let key = PrivateKey.generate()
            let record = try PreKeyRecord(id: UInt32(id), privateKey: key)
            return BridgePreKey(id: id, record: record.serialize(), publicKey: key.publicKey.serialize())
        }
    }

    // MARK: 1:1

    func startSession(identityKeyPair: Data, registrationId: Int32, peerUserId: String, peerDeviceId: Int32, bundle: BridgeBundle, currentSession: Data?) throws -> Data {
        try bridged {
            let store = try self.store(identityKeyPair, registrationId)
            let peer = try ProtocolAddress(name: peerUserId, deviceId: UInt32(peerDeviceId))
            if let current = currentSession { try store.storeSession(SessionRecord(bytes: current), for: peer, context: context) }
            let identity = try IdentityKey(bytes: bundle.identityKey)
            let signed = try PublicKey(bundle.signedPreKey)
            let kyber = try KEMPublicKey(bundle.kyberPreKey)
            let signalBundle: PreKeyBundle
            if let oneTime = bundle.oneTimePreKey, bundle.oneTimePreKeyId != noId {
                signalBundle = try PreKeyBundle(
                    registrationId: UInt32(bundle.registrationId), deviceId: UInt32(peerDeviceId),
                    prekeyId: UInt32(bundle.oneTimePreKeyId), prekey: try PublicKey(oneTime),
                    signedPrekeyId: UInt32(bundle.signedPreKeyId), signedPrekey: signed, signedPrekeySignature: bundle.signedPreKeySignature,
                    identity: identity,
                    kyberPrekeyId: UInt32(bundle.kyberPreKeyId), kyberPrekey: kyber, kyberPrekeySignature: bundle.kyberPreKeySignature
                )
            } else {
                signalBundle = try PreKeyBundle(
                    registrationId: UInt32(bundle.registrationId), deviceId: UInt32(peerDeviceId),
                    signedPrekeyId: UInt32(bundle.signedPreKeyId), signedPrekey: signed, signedPrekeySignature: bundle.signedPreKeySignature,
                    identity: identity,
                    kyberPrekeyId: UInt32(bundle.kyberPreKeyId), kyberPrekey: kyber, kyberPrekeySignature: bundle.kyberPreKeySignature
                )
            }
            try processPreKeyBundle(signalBundle, for: peer, ourAddress: try selfAddress(), sessionStore: store, identityStore: store, context: context)
            return try sessionBytes(store, peer)
        }
    }

    func encrypt(identityKeyPair: Data, registrationId: Int32, peerUserId: String, peerDeviceId: Int32, session: Data, plaintext: Data) throws -> BridgeEncrypted {
        try bridged {
            let store = try self.store(identityKeyPair, registrationId)
            let peer = try ProtocolAddress(name: peerUserId, deviceId: UInt32(peerDeviceId))
            try store.storeSession(SessionRecord(bytes: session), for: peer, context: context)
            let message = try signalEncrypt(message: plaintext, for: peer, localAddress: try selfAddress(), sessionStore: store, identityStore: store, context: context)
            return BridgeEncrypted(isPreKeyMessage: message.messageType == .preKey, ciphertext: message.serialize(), session: try sessionBytes(store, peer))
        }
    }

    func inspect(isPreKeyMessage: Bool, ciphertext: Data) throws -> BridgeNeeds {
        try bridged {
            if !isPreKeyMessage {
                _ = try SignalMessage(bytes: ciphertext)
                return BridgeNeeds(preKeyId: noId, signedPreKeyId: noId, senderIdentityKey: nil)
            }
            let message = try PreKeySignalMessage(bytes: ciphertext)
            let preKeyId = try message.preKeyId().map { Int32($0) } ?? noId
            return BridgeNeeds(preKeyId: preKeyId, signedPreKeyId: Int32(message.signedPreKeyId), senderIdentityKey: message.identityKey.serialize())
        }
    }

    func decrypt(
        identityKeyPair: Data, registrationId: Int32, peerUserId: String, peerDeviceId: Int32, session: Data?,
        isPreKeyMessage: Bool, ciphertext: Data, preKeys: [BridgeRecord], signedPreKeys: [BridgeRecord], kyberPreKeys: [BridgeRecord]
    ) throws -> BridgeDecrypted {
        try bridged {
            let store = try self.store(identityKeyPair, registrationId)
            let peer = try ProtocolAddress(name: peerUserId, deviceId: UInt32(peerDeviceId))
            if let session { try store.storeSession(SessionRecord(bytes: session), for: peer, context: context) }
            for k in preKeys { try store.storePreKey(PreKeyRecord(bytes: k.record), id: UInt32(k.id), context: context) }
            for k in signedPreKeys { try store.storeSignedPreKey(SignedPreKeyRecord(bytes: k.record), id: UInt32(k.id), context: context) }
            for k in kyberPreKeys { try store.storeKyberPreKey(KyberPreKeyRecord(bytes: k.record), id: UInt32(k.id), context: context) }
            let plaintext: Data
            var consumed = noId
            if isPreKeyMessage {
                let message = try PreKeySignalMessage(bytes: ciphertext)
                plaintext = try signalDecryptPreKey(
                    message: message, from: peer, localAddress: try selfAddress(),
                    sessionStore: store, identityStore: store, preKeyStore: store, signedPreKeyStore: store, kyberPreKeyStore: store, context: context
                )
                // libsignal removes a one-time prekey from the store once it has been used.
                if let used = try message.preKeyId(), (try? store.loadPreKey(id: used, context: context)) == nil { consumed = Int32(used) }
            } else {
                plaintext = try signalDecrypt(message: try SignalMessage(bytes: ciphertext), from: peer, to: try selfAddress(), sessionStore: store, identityStore: store, context: context)
            }
            guard let record = try store.loadSession(for: peer, context: context), let identity = try store.identity(for: peer, context: context) else {
                throw SignalError.sessionNotFound("no session after decrypt")
            }
            return BridgeDecrypted(plaintext: plaintext, session: record.serialize(), peerIdentityKey: identity.serialize(), consumedPreKeyId: consumed)
        }
    }

    // MARK: groups

    func createSenderKeyDistribution(selfUserId: String, selfDeviceId: Int32, distributionId: String, current: Data?) throws -> BridgeSenderKey {
        try bridged {
            let store = InMemorySignalProtocolStore()
            let me = try ProtocolAddress(name: selfUserId, deviceId: UInt32(selfDeviceId))
            let uuid = try self.uuid(distributionId)
            if let current { try store.storeSenderKey(from: me, distributionId: uuid, record: SenderKeyRecord(bytes: current), context: context) }
            let distribution = try SenderKeyDistributionMessage(from: me, distributionId: uuid, store: store, context: context)
            return BridgeSenderKey(distributionId: distributionId, bytes: distribution.serialize(), record: try senderKeyBytes(store, me, uuid))
        }
    }

    func distributionIdOf(isDistribution: Bool, bytes: Data) throws -> String {
        try bridged {
            if isDistribution { return try SenderKeyDistributionMessage(bytes: bytes).distributionId.uuidString.lowercased() }
            return try SenderKeyMessage(bytes: bytes).distributionId.uuidString.lowercased()
        }
    }

    func processSenderKeyDistribution(senderUserId: String, senderDeviceId: Int32, distribution: Data, current: Data?) throws -> BridgeSenderKey {
        try bridged {
            let store = InMemorySignalProtocolStore()
            let sender = try ProtocolAddress(name: senderUserId, deviceId: UInt32(senderDeviceId))
            let message = try SenderKeyDistributionMessage(bytes: distribution)
            if let current { try store.storeSenderKey(from: sender, distributionId: message.distributionId, record: SenderKeyRecord(bytes: current), context: context) }
            try processSenderKeyDistributionMessage(message, from: sender, store: store, context: context)
            return BridgeSenderKey(distributionId: message.distributionId.uuidString.lowercased(), bytes: Data(), record: try senderKeyBytes(store, sender, message.distributionId))
        }
    }

    func groupEncrypt(selfUserId: String, selfDeviceId: Int32, distributionId: String, record: Data, plaintext: Data) throws -> BridgeSenderKey {
        try bridged {
            let store = InMemorySignalProtocolStore()
            let me = try ProtocolAddress(name: selfUserId, deviceId: UInt32(selfDeviceId))
            let uuid = try self.uuid(distributionId)
            try store.storeSenderKey(from: me, distributionId: uuid, record: SenderKeyRecord(bytes: record), context: context)
            let message = try LibSignalClient.groupEncrypt(plaintext, from: me, distributionId: uuid, store: store, context: context)
            return BridgeSenderKey(distributionId: distributionId, bytes: message.serialize(), record: try senderKeyBytes(store, me, uuid))
        }
    }

    func groupDecrypt(senderUserId: String, senderDeviceId: Int32, distributionId: String, record: Data, ciphertext: Data) throws -> BridgeSenderKey {
        try bridged {
            let store = InMemorySignalProtocolStore()
            let sender = try ProtocolAddress(name: senderUserId, deviceId: UInt32(senderDeviceId))
            let uuid = try self.uuid(distributionId)
            try store.storeSenderKey(from: sender, distributionId: uuid, record: SenderKeyRecord(bytes: record), context: context)
            let plaintext = try LibSignalClient.groupDecrypt(ciphertext, from: sender, store: store, context: context)
            return BridgeSenderKey(distributionId: distributionId, bytes: plaintext, record: try senderKeyBytes(store, sender, uuid))
        }
    }

    // MARK: helpers

    func safetyNumber(localId: String, localIdentityKey: Data, remoteId: String, remoteIdentityKey: Data) throws -> String {
        try bridged {
            let generator = NumericFingerprintGenerator(iterations: 5200)
            let fingerprint = try generator.create(
                version: 2,
                localIdentifier: Data(localId.utf8), localKey: try PublicKey(localIdentityKey),
                remoteIdentifier: Data(remoteId.utf8), remoteKey: try PublicKey(remoteIdentityKey)
            )
            return fingerprint.displayable.formatted
        }
    }

    /// `nonce(12) || ciphertext || tag(16)`: CryptoKit's combined box, the same layout the Android side writes.
    func gcmSeal(key: Data, plaintext: Data, associatedData: Data) throws -> Data {
        try bridged {
            let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key), authenticating: associatedData)
            guard let combined = box.combined else { throw SignalError.internalError("no combined box") }
            return combined
        }
    }

    func gcmOpen(key: Data, sealed: Data, associatedData: Data) throws -> Data {
        do {
            let box = try AES.GCM.SealedBox(combined: sealed)
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: associatedData)
        } catch {
            throw Self.bridgeError("invalidMessage")
        }
    }

    func digestSha256(bytes: Data) throws -> Data {
        Data(SHA256.hash(data: bytes))
    }

    func secureRandomBytes(count: Int32) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: Int(count))
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }

    // MARK: plumbing

    private func store(_ identityKeyPair: Data, _ registrationId: Int32) throws -> InMemorySignalProtocolStore {
        InMemorySignalProtocolStore(identity: try IdentityKeyPair(bytes: identityKeyPair), registrationId: UInt32(registrationId))
    }

    private func selfAddress() throws -> ProtocolAddress { try ProtocolAddress(name: selfName, deviceId: 1) }

    private func uuid(_ text: String) throws -> UUID {
        guard let uuid = UUID(uuidString: text) else { throw SignalError.invalidArgument("distributionId") }
        return uuid
    }

    private func sessionBytes(_ store: InMemorySignalProtocolStore, _ peer: ProtocolAddress) throws -> Data {
        guard let record = try store.loadSession(for: peer, context: context) else { throw SignalError.sessionNotFound("no session") }
        return record.serialize()
    }

    private func senderKeyBytes(_ store: InMemorySignalProtocolStore, _ sender: ProtocolAddress, _ uuid: UUID) throws -> Data {
        guard let record = try store.loadSenderKey(from: sender, distributionId: uuid, context: context) else { throw SignalError.invalidSenderKeySession(distributionId: uuid, message: "no sender key") }
        return record.serialize()
    }

    /// libsignal's `SignalError` cases cross to Kotlin by name (as the NSError description), where `IosSignalCrypto`
    /// maps them like Android does. The payload text of the error never leaves this process.
    private func bridged<T>(_ block: () throws -> T) throws -> T {
        do {
            return try block()
        } catch let error as NSError where error.domain == Self.errorDomain {
            throw error
        } catch let error as SignalError {
            throw Self.bridgeError(Self.caseName(error))
        } catch {
            throw Self.bridgeError("internalError")
        }
    }

    private static let errorDomain = "CircasoSignalBridge"

    private static func bridgeError(_ code: String) -> NSError {
        NSError(domain: errorDomain, code: 1, userInfo: [NSLocalizedDescriptionKey: code])
    }

    private static func caseName(_ error: SignalError) -> String {
        // "duplicatedMessage(\"…\")" -> "duplicatedMessage"
        let text = String(describing: error)
        return String(text.prefix { $0 != "(" })
    }
}
