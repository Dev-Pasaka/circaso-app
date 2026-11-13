import Foundation
import Shared
import UIKit
import UserNotifications

/// Reply and Mark as Read on an iPhone chat notification, as on Android (2026-11-13). The Notification Service
/// Extension puts chat messages, 1:1 and groups, in one of these categories (not money or calls); the typed reply goes
/// to shared Kotlin (NotificationActions.ios.kt → NotificationReplies), which queues it in the outbox exactly as the
/// chat does and marks the chat read, without opening Circaso.
///
/// With Notification privacy on, Reply is still offered, as on Android, but asks for Face ID or the passcode first:
/// the words it answers aren't on the lock screen, so neither is a way to answer them. Under privacy the extension
/// can't tell a money card from words without opening it (and it doesn't), so every "New message" offers Reply; the
/// same when the extension runs out of time. A reply to a money card is an ordinary message in that chat.
enum ChatNotificationActions {
    /// Also in NotificationService.swift, which sets them.
    static let category = "CHAT_MESSAGE"
    static let privateCategory = "CHAT_MESSAGE_PRIVATE"
    static let reply = "REPLY"
    static let markRead = "MARK_READ"
    private static let appGroup = "group.com.circaso.app"
    /// The answer to iOS by then at the latest (Kotlin gives up queuing at 8 s).
    private static let answerDeadline: TimeInterval = 10
    /// Circaso's background time is given back by then at the latest (iOS lends about 30 s).
    private static let workDeadline: TimeInterval = 27

    /// At launch, every launch: iOS keeps the categories, but the app is where they are defined.
    static func register() {
        let markAsRead = UNNotificationAction(identifier: markRead, title: "Mark as Read", options: [])
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: category, actions: [replyAction(unlock: false), markAsRead], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: privateCategory, actions: [replyAction(unlock: true), markAsRead], intentIdentifiers: [], options: []),
        ])
    }

    /// No `.foreground`: the reply is sent from the background, Circaso stays closed.
    private static func replyAction(unlock: Bool) -> UNTextInputNotificationAction {
        UNTextInputNotificationAction(
            identifier: reply, title: "Reply", options: unlock ? [.authenticationRequired] : [],
            textInputButtonTitle: "Send", textInputPlaceholder: "Message"
        )
    }

    /// True when [response] was one of these actions; it then calls [completionHandler] itself, once the reply is
    /// queued or couldn't be. A queued reply takes the chat's notifications down (the reply reads as sent, as in
    /// Messages); one that couldn't be queued leaves them and adds "Reply not sent".
    static func handle(_ response: UNNotificationResponse, completionHandler: @escaping () -> Void) -> Bool {
        let action = response.actionIdentifier
        guard action == reply || action == markRead else { return false }
        let content = response.notification.request.content
        guard let chatId = (content.userInfo["chatId"] as? String).flatMap({ $0.isEmpty ? nil : $0 }) else {
            completionHandler()
            return true
        }
        // iOS's completion handler is called on every path, exactly once: the answer from Kotlin (which always comes,
        // exceptions included), the background time running out, or this deadline, well inside that time.
        let background = BackgroundTime(name: action == reply ? "notification-reply" : "notification-read")
        let once = Once(completionHandler)
        if action == reply {
            let text = (response as? UNTextInputNotificationResponse)?.userText ?? ""
            // Circaso's background time ran out before Kotlin answered: whether it was queued isn't known, so the
            // person is told to look. (Past the answer deadline iOS is only let go; the answer may still come.)
            let unanswered = Once { replyFailed(chatId: chatId, title: nil, retry: false) }
            background.onExpiry = { unanswered.run(); once.run() }
            DispatchQueue.main.asyncAfter(deadline: .now() + answerDeadline) { once.run() }
            DispatchQueue.main.asyncAfter(deadline: .now() + workDeadline) { unanswered.run(); background.end() }
            NotificationActions.shared.reply(
                chatId: chatId, text: text,
                queued: { ok, title, retry in
                    let late = unanswered.done
                    unanswered.cancel()
                    if !ok.boolValue && !late { replyFailed(chatId: chatId, title: title, retry: retry.boolValue) }
                    once.run()
                },
                finished: { refused, title in
                    // Queued, then refused by the server for good (removed from the group on another phone).
                    if refused.boolValue { replyFailed(chatId: chatId, title: title, retry: false) }
                    background.end()
                }
            )
        } else {
            background.onExpiry = { once.run() }
            DispatchQueue.main.asyncAfter(deadline: .now() + answerDeadline) { once.run() }
            DispatchQueue.main.asyncAfter(deadline: .now() + workDeadline) { background.end() }
            NotificationActions.shared.markRead(chatId: chatId) {
                once.run()
                background.end()
            }
        }
        return true
    }

    /// The reply didn't go (a chat I've left, someone I blocked, signed out, refused, out of time): say so; a tap opens
    /// the chat. [retry]: Reply is offered again on it, when another try could help.
    private static func replyFailed(chatId: String, title: String?, retry: Bool) {
        let privacy = UserDefaults(suiteName: appGroup)?.bool(forKey: "notificationPrivacy") ?? false
        let content = UNMutableNotificationContent()
        content.title = "Reply not sent"
        if !privacy, let title, !title.isEmpty {
            content.body = "Couldn't send your reply to \(title). Tap to open."
        } else {
            content.body = "Couldn't send your reply. Tap to open."
        }
        content.userInfo = ["chatId": chatId]
        content.threadIdentifier = chatId
        content.sound = .default
        if retry { content.categoryIdentifier = privacy ? privateCategory : category }
        let request = UNNotificationRequest(identifier: "reply-failed-\(chatId)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}

/// The background time iOS lends Circaso to finish an action; given back exactly once.
private final class BackgroundTime {
    private var task: UIBackgroundTaskIdentifier = .invalid
    var onExpiry: (() -> Void)?

    init(name: String) {
        task = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            self?.onExpiry?()
            self?.end()
        }
    }

    func end() {
        guard task != .invalid else { return }
        UIApplication.shared.endBackgroundTask(task)
        task = .invalid
    }
}

/// A block run at most once, whichever caller comes first (iOS's completion handler: the answer or running out of
/// time). Main thread only.
private final class Once {
    private var block: (() -> Void)?
    private(set) var done = false
    init(_ block: @escaping () -> Void) { self.block = block }
    func run() {
        guard let block else { return }
        self.block = nil
        done = true
        block()
    }
    /// Never to run now.
    func cancel() { block = nil }
}
