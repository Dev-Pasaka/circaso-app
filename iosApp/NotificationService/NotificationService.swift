import ImageIO
import Intents
import UIKit
import UserNotifications

/// Words every push on the iPhone the way Android does (2026-11-13). The server sends ids, and for a chat message the
/// sender and the end-to-end ciphertext; this opens it in memory against the snapshot the app left in the App Group
/// (Snapshot.swift), names the sender as this person saved them, and shows their photo as a communication
/// notification (INSendMessageIntent), the group's name over a group's. Money reads in the person's own currency.
///
/// With Notification privacy on (You → Notification privacy, mirrored into the App Group's defaults) every push says
/// only "New message", "You received money", "Missed call"…, whatever the server wrote. When nothing can be read (no
/// snapshot yet, the phone not unlocked since a restart, a message the snapshot can't open) the push names who it can
/// and otherwise keeps the server's own words, which never carry chat text or amounts.
///
/// Nothing is written anywhere but the notification: the app opens the same message for real when it next runs.
final class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var fallback: UNNotificationContent?
    private var finished = false
    private let lock = NSLock()

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        let info = Info(content.userInfo)
        if let chatId = info.chatId { content.threadIdentifier = chatId }
        // One moment's reactions stack together, apart from the chats.
        if info.kind == Info.momentReaction { content.threadIdentifier = "moment:\(info.string("momentId") ?? "mine")" }
        let privacy = UserDefaults(suiteName: Snapshot.appGroup)?.bool(forKey: "notificationPrivacy") ?? false
        // A chat message, 1:1 or group: Reply and Mark as Read (ChatNotificationActions.swift in the app). With privacy
        // on too, as on Android; that Reply asks for Face ID or the passcode first. Money cards opened below lose it;
        // under privacy, or out of time, one can't be told from words, so it keeps it (a reply is just a message).
        if info.isChatMessage { content.categoryIdentifier = privacy ? Info.privateChatCategory : Info.chatCategory }
        fallback = content.copy() as? UNNotificationContent
        Task {
            // One moment buzzes at most once a minute, privacy on or off, words opened or not.
            if info.kind == Info.momentReaction { await Self.quietMoment(content, info: info) }
            if privacy {
                if let words = Wording.privateWords(kind: info.kind, hasMessage: info.messageId != nil) {
                    content.title = words.0
                    content.subtitle = ""
                    content.body = words.1
                }
                self.finish(content)
                return
            }
            let worded = await Worder(content: content, info: info).word()
            self.finish(worded)
        }
    }

    /// A reaction on my moment: the same person's earlier one goes (they changed it) and this one makes no sound, as
    /// does any reaction within a minute of the moment's last notification.
    private static func quietMoment(_ content: UNMutableNotificationContent, info: Info) async {
        let center = UNUserNotificationCenter.current()
        let earlier = await center.deliveredNotifications().filter { $0.request.content.threadIdentifier == content.threadIdentifier }
        var quiet = earlier.contains { $0.date > Date().addingTimeInterval(-60) }
        if let userId = info.string("userId") {
            let mine = earlier.filter { $0.request.content.userInfo["userId"] as? String == userId }.map(\.request.identifier)
            if !mine.isEmpty {
                quiet = true
                center.removeDeliveredNotifications(withIdentifiers: mine)
            }
        }
        if quiet {
            content.sound = nil
            content.interruptionLevel = .passive
        }
    }

    /// Out of time: the server's words (never chat text or an amount), with the chat's thread.
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
}

/// A push's data: FCM puts it at the top of `userInfo`, beside `aps`.
struct Info {
    let raw: [AnyHashable: Any]
    init(_ raw: [AnyHashable: Any]) { self.raw = raw }
    func string(_ key: String) -> String? { (raw[key] as? String)?.nonEmpty }
    var kind: String? { string("kind") }
    var chatId: String? { string("chatId") }
    var messageId: String? { string("messageId") }
    /** A message in a chat (pushes for money, calls and the rest carry a `kind`). */
    var isChatMessage: Bool { kind == nil && chatId != nil && messageId != nil }

    /// The app's categories (ChatNotificationActions.swift), registered at its launch.
    static let momentReaction = "moment.loved"
    static let chatCategory = "CHAT_MESSAGE"
    static let privateChatCategory = "CHAT_MESSAGE_PRIVATE"
}

private struct Worder {
    let content: UNMutableNotificationContent
    let info: Info

    func word() async -> UNNotificationContent {
        guard let snapshot = Snapshot.load() else { return content }
        switch info.kind {
        case nil where info.messageId != nil && info.chatId != nil: return await message(snapshot)
        case "payment.received": return await moneyReceived(snapshot)
        case "chama.contribution": return await contribution(snapshot)
        case "call.missed", "call.ended": return missedCall(snapshot)
        case Info.momentReaction: return momentReaction(snapshot)
        case "contact.joined", "connection.added": return newPerson(snapshot)
        default: return content
        }
    }

    // MARK: chat messages

    private func message(_ s: Snapshot) async -> UNNotificationContent {
        guard let chatId = info.chatId else { return content }
        let chat = s.chat(chatId)
        let group = chat?.group ?? false
        let senderId = info.string("senderId")
        let sender = s.person(senderId) ?? (group ? nil : chat.flatMap { s.peer(of: $0) })
        // Already opened by the app from the socket (it was in the background, still connected): its words, as the
        // app wrote them. Opening it again would fail as a duplicate.
        if let messageId = info.messageId, let copy = Snapshot.arrived(messageId) {
            content.title = copy.title
            content.body = copy.body
            if copy.money == true { content.categoryIdentifier = "" } // money: no Reply, as on Android
            if copy.money == true, let transferId = copy.transferId {
                var extra = content.userInfo
                extra["transferId"] = transferId
                extra["moneyCard"] = "1"
                if let id = copy.senderId { extra["cardFrom"] = id }
                content.userInfo = extra
            }
            guard let who = s.person(copy.senderId) ?? sender else { return content }
            return communication(s, chat: chat, sender: who) ?? content
        }
        let payload = info.string("ciphertext").flatMap { c in senderId.flatMap { s.open(ciphertext: c, senderId: $0, chatId: chatId) } }.flatMap(Payload.decode)
        if payload?.type == "payment", let p = payload, let minor = p.amountMinor {
            return await paymentCard(s, chat: chat, sender: sender, payload: p, minor: minor)
        }
        // Never shown as a message (live location's moves, keys, controls): nothing to say. Pushes don't carry them.
        if let type = payload?.type, Wording.silentTypes.contains(type) { return UNMutableNotificationContent() }
        if payload?.type == "reaction" {
            // A reaction taken back has nothing to say: an empty notification isn't shown.
            guard let emoji = payload?.text?.nonEmpty else { return UNMutableNotificationContent() }
            let words = Wording.reaction(emoji: emoji, target: nil)
            if group {
                // As Android: "Amina reacted 👍 to your message".
                content.title = chat?.title ?? "Group"
                content.body = "\(sender?.name ?? "Someone") r" + words.dropFirst()
            } else {
                content.title = sender?.name ?? content.title
                content.body = words
            }
        } else {
            let said = payload.flatMap(Wording.preview)
            let line = Wording.line(said, momentReply: payload?.moment != nil)
            if group {
                content.title = chat?.title ?? "Group"
                // As Android: "Amina: hi", "Amina sent a message" when it can't be read, else the words alone.
                switch (sender, said) {
                case let (who?, _?): content.body = "\(who.name): \(line)"
                case let (who?, nil): content.body = "\(who.name) sent a message"
                default: content.body = line
                }
            } else {
                content.title = sender?.name ?? content.title
                content.body = line
            }
        }
        guard let sender else { return content }
        return communication(s, chat: chat, sender: sender) ?? content
    }

    /// A money card in a chat: the amount in the person's currency, from the sender, with their photo.
    private func paymentCard(_ s: Snapshot, chat: Snapshot.Chat?, sender: Snapshot.Person?, payload p: Payload, minor: String) async -> UNNotificationContent {
        let sum = Wording.amount(minor: minor, asset: p.asset ?? "USDC", fiat: s.fiat)
        content.categoryIdentifier = "" // money: no Reply, as on Android
        let note = p.text?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty.flatMap { $0.hasPrefix("Paid from") ? nil : " · \($0)" } ?? ""
        let name = sender?.name
        if chat?.group == true {
            content.title = chat?.title ?? "Your group"
            content.body = p.text?.hasPrefix("Paid from") == true
                ? (name.map { "\($0) sent you " } ?? "You received ") + sum
                : "\(name ?? "Someone") added \(sum)"
        } else if let name {
            content.title = name
            content.body = "Sent you \(sum)\(note)"
        } else {
            content.title = Wording.appName
            content.body = "You received \(sum)\(note)"
        }
        var extra = content.userInfo
        if let transferId = p.transferId { extra["transferId"] = transferId }
        if let id = sender?.id { extra["cardFrom"] = id }
        extra["moneyCard"] = "1"
        extra["cardKind"] = chat?.group == true && p.text?.hasPrefix("Paid from") != true ? "contribution" : "payment"
        content.userInfo = extra
        // A group's "Wanjiru added money" (chama.contribution) may already show for this card: one notification.
        if chat?.group == true, let id = sender?.id {
            await removeDelivered { $0.request.content.threadIdentifier == chat?.id && $0.request.content.userInfo["kind"] as? String == "chama.contribution" && $0.request.content.userInfo["userId"] as? String == id }
        }
        // The wallet's own "payment.received" for the same money may already show: one notification, this one.
        if let transferId = p.transferId {
            await removeDelivered { $0.request.content.userInfo["transferId"] as? String == transferId && $0.request.content.userInfo["moneyCard"] == nil }
        }
        guard let sender else { return content }
        return communication(s, chat: chat, sender: sender) ?? content
    }

    /// The wallet says money arrived: the chat's card may have said so already, with the amount; then its words stay.
    private func moneyReceived(_ s: Snapshot) async -> UNNotificationContent {
        let chat = s.chat(info.chatId)
        let transferId = info.string("transferId")
        if let transferId, let card = await delivered({ $0.request.content.userInfo["transferId"] as? String == transferId && $0.request.content.userInfo["moneyCard"] != nil }) {
            content.title = card.request.content.title
            content.body = card.request.content.body
            quiet()
            await removeDelivered { $0.request.identifier == card.request.identifier }
        }
        var extra = content.userInfo
        if let transferId { extra["transferId"] = transferId }
        content.userInfo = extra
        guard let chat, !chat.group, let sender = s.peer(of: chat) else { return content }
        if content.body.hasSuffix("sent you money") || content.title == Wording.appName && !content.body.contains(sender.name) {
            content.title = sender.name
            content.body = "Sent you money"
        }
        return communication(s, chat: chat, sender: sender) ?? content
    }

    /// Someone put money into a group: their card in the chat, if it showed already, has the amount.
    private func contribution(_ s: Snapshot) async -> UNNotificationContent {
        let chat = s.chat(info.chatId)
        let member = s.person(info.string("userId"))
        let recent = Date().addingTimeInterval(-120)
        if let member, let card = await delivered({
            $0.request.content.threadIdentifier == info.chatId && $0.date > recent &&
                $0.request.content.userInfo["cardKind"] as? String == "contribution" && $0.request.content.userInfo["cardFrom"] as? String == member.id
        }) {
            content.title = card.request.content.title
            content.body = card.request.content.body
            quiet()
            await removeDelivered { $0.request.identifier == card.request.identifier }
        } else {
            content.title = chat?.title ?? content.title
            if let member { content.body = "\(member.name) added money" }
        }
        guard let member else { return content }
        return communication(s, chat: chat, sender: member) ?? content
    }

    /// The same money already buzzed once: this one replaces it without a second sound.
    private func quiet() {
        content.sound = nil
        content.interruptionLevel = .passive
    }

    /// The caller as this person saved them, with their photo (2026-11-14: the push names the caller's `userId`).
    private func missedCall(_ s: Snapshot) -> UNNotificationContent {
        let chat = s.chat(info.chatId)
        let video = info.string("callKind") == "VIDEO"
        let callerId = info.string("userId") ?? info.string("callerId")
        let caller = s.person(callerId) ?? chat.flatMap { s.peer(of: $0) }
        let name = caller?.name ?? info.string("callerName") ?? chat?.title
        guard let name else { return content }
        content.title = Wording.privateMissedCall
        content.body = "Missed \(video ? "video call" : "call") from \(name)"
        guard let caller else { return content }
        // Beside their name and photo, as Android: "Missed video call".
        let plain = content.copy() as? UNNotificationContent
        content.title = caller.name
        content.body = Wording.missedCallLine(video: video)
        return communication(s, chat: chat, sender: caller) ?? plain ?? content
    }

    /// "Amina reacted ❤️ to your moment" beside Amina's photo, as Android words it (the sound was settled before).
    private func momentReaction(_ s: Snapshot) -> UNNotificationContent {
        let userId = info.string("userId")
        let emoji = info.string("reaction")
        let thread = content.threadIdentifier
        // Not known on this phone: the server's own words ("Amina reacted 😂 to your moment").
        guard let who = s.person(userId) else { return content }
        content.title = who.name
        content.body = Wording.momentReactionLine(emoji: emoji)
        return communication(s, chat: nil, sender: who, conversation: thread) ?? content
    }

    /// A contact who joined, someone who connected with me: named as I saved them, with their photo.
    private func newPerson(_ s: Snapshot) -> UNNotificationContent {
        guard let who = s.person(info.string("userId")) else { return content }
        let joined = info.kind == "contact.joined"
        let plain = content.copy() as? UNNotificationContent
        content.title = who.name
        content.body = joined ? Wording.joinedLine : Wording.connectedLine
        return communication(s, chat: nil, sender: who, conversation: "person:\(who.id)") ?? plain ?? content
    }

    // MARK: communication notifications

    /// The same words as a communication notification: the sender's photo (initials when there's none) as the
    /// notification's icon, the group's name and photo for a group. Nil when iOS refuses it; the plain words stay.
    private func communication(_ s: Snapshot, chat: Snapshot.Chat?, sender: Snapshot.Person, conversation: String? = nil) -> UNNotificationContent? {
        let group = chat?.group == true
        let handle = INPersonHandle(value: sender.id, type: .unknown)
        let person = INPerson(
            personHandle: handle, nameComponents: PersonNameComponentsFormatter().personNameComponents(from: sender.name), displayName: sender.name,
            image: image(s.photoURL(sender.photo)) ?? Initials.image(sender.name), contactIdentifier: nil, customIdentifier: sender.id
        )
        let me = INPerson(
            personHandle: INPersonHandle(value: s.me ?? "me", type: .unknown), nameComponents: nil, displayName: nil,
            image: nil, contactIdentifier: nil, customIdentifier: nil, isMe: true
        )
        let intent = INSendMessageIntent(
            recipients: group ? [me] + others(s, chat: chat, sender: sender.id) : [me],
            outgoingMessageType: .outgoingMessageText,
            content: content.body,
            speakableGroupName: group ? INSpeakableString(spokenPhrase: chat?.title ?? "Group") : nil,
            conversationIdentifier: conversation ?? chat?.id ?? info.chatId,
            serviceName: nil,
            sender: person,
            attachments: nil
        )
        if group, let photo = image(s.photoURL(chat?.photo)) ?? Initials.image(chat?.title ?? "Group") {
            intent.setImage(photo, forParameterNamed: \.speakableGroupName)
        }
        let interaction = INInteraction(intent: intent, response: nil)
        interaction.direction = .incoming
        interaction.donate(completion: nil)
        return try? content.updating(from: intent)
    }

    /// Up to two other members, so iOS draws the notification as a group's.
    private func others(_ s: Snapshot, chat: Snapshot.Chat?, sender: String) -> [INPerson] {
        (chat?.members ?? []).filter { $0 != sender && $0 != s.me }.prefix(2).compactMap { id in
            s.person(id).map { INPerson(personHandle: INPersonHandle(value: $0.id, type: .unknown), nameComponents: nil, displayName: $0.name, image: nil, contactIdentifier: nil, customIdentifier: $0.id) }
        }
    }

    /// A photo from the App Group, made small as it's read (ImageIO never decodes the full picture).
    private func image(_ url: URL?) -> INImage? {
        guard let url, let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 256,
        ]
        guard let thumb = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              let data = UIImage(cgImage: thumb).jpegData(compressionQuality: 0.85) else { return nil }
        return INImage(imageData: data)
    }

    // MARK: notifications already showing

    private func delivered(_ matches: (UNNotification) -> Bool) async -> UNNotification? {
        await UNUserNotificationCenter.current().deliveredNotifications().first(where: matches)
    }

    private func removeDelivered(_ matches: (UNNotification) -> Bool) async {
        let ids = await UNUserNotificationCenter.current().deliveredNotifications().filter(matches).map(\.request.identifier)
        if !ids.isEmpty { UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids) }
    }
}
