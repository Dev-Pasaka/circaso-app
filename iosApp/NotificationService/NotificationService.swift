import Intents
import Shared
import UserNotifications

/// Words every chat push the way Android does (2026-11-09): who sent it, with their photo, the group's name, and
/// what was said ("📷 Photo", "Sent you 5.00 USDC"…). The server sends ids and the message's ciphertext only; the
/// shared Kotlin code opens it in memory against the snapshot the app wrote to the App Group when it last left the
/// screen (`NotificationServiceEntry`, see NotificationSnapshot.kt). Nothing is written here: the app opens the same
/// message for real when it next runs. With Notification privacy on, or when nothing can be read, the server's own
/// "Circaso / New message" stays. A chat push becomes a communication notification (INSendMessageIntent), so iOS
/// shows the sender's photo, or their initials when there's none.
final class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var fallback: UNMutableNotificationContent?
    private var finished = false
    private let lock = NSLock()

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        if let chatId = content.userInfo["chatId"] as? String { content.threadIdentifier = chatId }
        fallback = content
        SignalBridge.shared.api = SignalCryptoImpl() // libsignal, as in the app (SignalCryptoImpl.swift is in both targets)
        NotificationServiceEntry.shared.word(userInfo: content.userInfo) { [weak self] wording in
            guard let self else { return }
            guard let wording else { self.finish(content); return }
            content.title = wording.title
            content.body = wording.body
            guard let name = wording.senderName else { self.finish(content); return }
            self.photo(wording.senderPhoto) { image in
                let plain = content.mutableCopy() as! UNMutableNotificationContent
                // The communication notification names the sender beside the words: the body is the words alone.
                if let line = wording.line { content.body = line }
                self.finish(self.communication(content, wording: wording, name: name, image: image) ?? plain)
            }
        }
    }

    /// Out of time: whatever was worded so far, else the server's words.
    override func serviceExtensionTimeWillExpire() {
        if let fallback { finish(fallback) }
    }

    private func finish(_ content: UNNotificationContent) {
        lock.lock()
        defer { lock.unlock() }
        guard !finished else { return }
        finished = true
        contentHandler?(content)
    }

    /// The sender's profile photo, small; nil when there's none or it can't be had in a few seconds (then initials).
    private func photo(_ link: String?, done: @escaping (INImage?) -> Void) {
        guard let link, let url = URL(string: link), url.scheme == "https" else { done(nil); return }
        var request = URLRequest(url: url)
        request.timeoutInterval = 4
        URLSession.shared.dataTask(with: request) { data, response, _ in
            let ok = (response as? HTTPURLResponse).map { (200..<300).contains($0.statusCode) } ?? false
            // Profile photos are small; anything big is skipped rather than risk the extension's memory.
            guard ok, let data, !data.isEmpty, data.count < 400_000 else { done(nil); return }
            done(INImage(imageData: data))
        }.resume()
    }

    /// The same words as a communication notification: the sender's name and photo, the group's name for a group.
    /// Nil when iOS refuses it (no Communication Notifications entitlement, say); the caller keeps the plain words.
    private func communication(_ content: UNMutableNotificationContent, wording: NotificationWording, name: String, image: INImage?) -> UNNotificationContent? {
        let handle = INPersonHandle(value: wording.senderId ?? name, type: .unknown)
        let sender = INPerson(personHandle: handle, nameComponents: nil, displayName: name, image: image, contactIdentifier: nil, customIdentifier: wording.senderId)
        let me = INPerson(personHandle: INPersonHandle(value: "me", type: .unknown), nameComponents: nil, displayName: nil, image: nil, contactIdentifier: nil, customIdentifier: nil, isMe: true)
        let intent = INSendMessageIntent(
            recipients: wording.group ? [me, sender] : [me],
            outgoingMessageType: .outgoingMessageText,
            content: wording.body,
            speakableGroupName: wording.group ? wording.groupName.map { INSpeakableString(spokenPhrase: $0) } : nil,
            conversationIdentifier: wording.chatId,
            serviceName: nil,
            sender: sender,
            attachments: nil
        )
        let interaction = INInteraction(intent: intent, response: nil)
        interaction.direction = .incoming
        interaction.donate(completion: nil)
        return try? content.updating(from: intent)
    }
}
