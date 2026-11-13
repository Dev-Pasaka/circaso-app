import CryptoKit
import Foundation
import LibSignalClient
import Security

/// The app's notification snapshot (shared/…/core/notifications/NotificationSnapshot.kt, keep the two in step): this
/// person's Signal records as they were when Circaso last left the screen, the names they see (saved names first),
/// photos in the App Group and their currency. Sealed with AES-256-GCM under a Keychain key the app shares with
/// this extension. Read-only here: nothing is ever written back, so the app's chats are never touched.
struct Snapshot: Decodable {
    struct Identity: Decodable { let keyPair: String; let registrationId: Int }
    struct Record: Decodable { let id: Int; let record: String }
    struct Session: Decodable { let userId: String; let record: String }
    struct SenderKey: Decodable { let chatId: String; let senderId: String; let distributionId: String; let record: String }
    struct Chat: Decodable { let id: String; let group: Bool; let title: String?; let photo: String?; let members: [String]? }
    struct Person: Decodable { let id: String; let name: String; let photo: String? }
    struct Fiat: Decodable { let currency: String; let rate: String }

    let v: Int
    let me: String?
    let identity: Identity
    let preKeys: [Record]?
    let signedPreKeys: [Record]?
    let kyberPreKeys: [Record]?
    let sessions: [Session]?
    let senderKeys: [SenderKey]?
    let chats: [Chat]?
    let people: [Person]?
    let fiat: Fiat?

    static let appGroup = "group.com.circaso.app"
    private static let fileName = "notification-snapshot.bin"
    private static let photosDir = "NotificationPhotos"
    private static let label = Data("notification-snapshot:1".utf8)
    private static let keychainService = "com.circaso.secure"
    private static let keyName = "notifications.snapshot_key"

    static var container: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) }

    /// Nil when there's none yet (Circaso hasn't left the screen since it was installed), when the Keychain is locked
    /// (before the first unlock after a restart) or when it doesn't open: the push then keeps the server's words.
    static func load() -> Snapshot? {
        guard let url = container?.appendingPathComponent(fileName),
              let sealed = try? Data(contentsOf: url),
              let key = keychainKey(),
              let box = try? AES.GCM.SealedBox(combined: sealed),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: label),
              let snapshot = try? JSONDecoder().decode(Snapshot.self, from: plain),
              snapshot.v == 1 else { return nil }
        return snapshot
    }

    private static func keychainKey() -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keyName,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let text = String(data: data, encoding: .utf8),
              let key = Data(base64Encoded: text), key.count == 32 else { return nil }
        return key
    }

    /// A message the app already opened from the socket while in the background, worded as its notification
    /// (ArrivedMessages in NotificationSnapshot.kt). Its push words from this: the message can't be opened twice.
    struct Arrived: Decodable {
        let messageId: String
        let chatId: String?
        let title: String
        let body: String
        let senderId: String?
        let group: Bool?
        let groupTitle: String?
        let money: Bool?
        let transferId: String?
    }

    private struct ArrivedMessages: Decodable { let v: Int; let messages: [Arrived]? }

    static func arrived(_ messageId: String) -> Arrived? {
        guard let url = container?.appendingPathComponent("notification-arrived.bin"),
              let sealed = try? Data(contentsOf: url),
              let key = keychainKey(),
              let box = try? AES.GCM.SealedBox(combined: sealed),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: Data("notification-arrived:1".utf8)),
              let list = try? JSONDecoder().decode(ArrivedMessages.self, from: plain) else { return nil }
        return list.messages?.last { $0.messageId == messageId }
    }

    // MARK: lookups

    func chat(_ id: String?) -> Chat? { id.flatMap { id in chats?.first { $0.id == id } } }
    func person(_ id: String?) -> Person? { id.flatMap { id in people?.first { $0.id == id } } }

    /// The other person in a direct chat.
    func peer(of chat: Chat) -> Person? {
        guard !chat.group else { return nil }
        return person(chat.members?.first { $0 != me })
    }

    func photoURL(_ file: String?) -> URL? {
        guard let file, let root = Snapshot.container else { return nil }
        return root.appendingPathComponent(Snapshot.photosDir).appendingPathComponent(file)
    }

    // MARK: opening a message

    /// The message's plaintext, opened in memory against these records; nil when they can't open it.
    func open(ciphertext: String, senderId: String, chatId: String) -> Data? {
        guard let wire = Data(base64Encoded: ciphertext), wire.count > 2, wire[wire.startIndex] == 1 else { return nil }
        let type = wire[wire.startIndex + 1]
        let body = wire.subdata(in: (wire.startIndex + 2)..<wire.endIndex)
        let context = NullContext()
        do {
            let peer = try ProtocolAddress(name: senderId, deviceId: 1)
            switch type {
            case 1, 2: // a PreKey or Whisper message, 1:1
                let pair = try IdentityKeyPair(bytes: Data(base64Encoded: identity.keyPair) ?? Data())
                let store = InMemorySignalProtocolStore(identity: pair, registrationId: UInt32(identity.registrationId))
                if let session = sessions?.first(where: { $0.userId == senderId }), let bytes = Data(base64Encoded: session.record) {
                    try store.storeSession(SessionRecord(bytes: bytes), for: peer, context: context)
                }
                let me = try ProtocolAddress(name: "self", deviceId: 1)
                if type == 1 {
                    let message = try PreKeySignalMessage(bytes: body)
                    // Only the keys this message names (the extension's memory is small); Kyber keys are few.
                    if let id = try message.preKeyId(), let r = preKeys?.first(where: { $0.id == Int(id) }), let bytes = Data(base64Encoded: r.record) {
                        try store.storePreKey(PreKeyRecord(bytes: bytes), id: id, context: context)
                    }
                    if let r = signedPreKeys?.first(where: { $0.id == Int(message.signedPreKeyId) }), let bytes = Data(base64Encoded: r.record) {
                        try store.storeSignedPreKey(SignedPreKeyRecord(bytes: bytes), id: message.signedPreKeyId, context: context)
                    }
                    for r in kyberPreKeys ?? [] {
                        guard let bytes = Data(base64Encoded: r.record) else { continue }
                        try store.storeKyberPreKey(KyberPreKeyRecord(bytes: bytes), id: UInt32(r.id), context: context)
                    }
                    return try Padding.unpad(signalDecryptPreKey(
                        message: message, from: peer, localAddress: me,
                        sessionStore: store, identityStore: store, preKeyStore: store, signedPreKeyStore: store, kyberPreKeyStore: store, context: context
                    ))
                }
                return try Padding.unpad(signalDecrypt(message: SignalMessage(bytes: body), from: peer, to: me, sessionStore: store, identityStore: store, context: context))
            case 3: // a group message under the sender's key
                let distribution = try SenderKeyMessage(bytes: body).distributionId
                let id = distribution.uuidString.lowercased()
                guard let key = senderKeys?.first(where: { $0.chatId == chatId && $0.senderId == senderId && $0.distributionId.lowercased() == id }),
                      let bytes = Data(base64Encoded: key.record) else { return nil }
                let store = InMemorySignalProtocolStore()
                try store.storeSenderKey(from: peer, distributionId: distribution, record: SenderKeyRecord(bytes: bytes), context: context)
                return try Padding.unpad(groupDecrypt(body, from: peer, store: store, context: context))
            default:
                return nil
            }
        } catch {
            return nil
        }
    }
}

/// Signal-style padding to 160-byte buckets, the last byte saying how much was added (WireFormat.kt's Padding).
enum Padding {
    struct Bad: Error {}

    static func unpad(_ padded: Data) throws -> Data {
        guard !padded.isEmpty, padded.count % 160 == 0, let last = padded.last else { throw Bad() }
        let added = Int(last)
        guard (1...160).contains(added), added <= padded.count else { throw Bad() }
        return padded.prefix(padded.count - added)
    }
}

/// What's inside a message (ChatPayload in WireFormat.kt): only the fields a notification needs.
struct Payload: Decodable {
    struct Moment: Decodable { let momentId: String? }
    let type: String
    let text: String?
    let moment: Moment?
    let replyTo: String?
    let transferId: String?
    let amountMinor: String?
    let asset: String?
    let durationMs: Int64?
    let label: String?
    let liveId: String?

    static func decode(_ data: Data) -> Payload? { try? JSONDecoder().decode(Payload.self, from: data) }
}
