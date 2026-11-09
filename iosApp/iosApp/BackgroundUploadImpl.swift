import Foundation
import Shared
import UIKit
import UserNotifications

/// Uploads that outlive the app (see BackgroundUploads.kt): photos, videos, voice notes, moments and profile photos
/// go up through a background URL session, which iOS finishes even when Circaso is suspended or closed.
final class BackgroundUploadImpl: NSObject, BackgroundUploadProvider, URLSessionTaskDelegate {
    static let shared = BackgroundUploadImpl()
    static let identifier = "com.circaso.app.uploads"
    /// PUTs that finished with nobody waiting (Circaso was closed): URL -> when. Read by IosBackgroundUploads.kt.
    static let finishedKey = "circaso.uploads.finished"

    private let lock = NSLock()
    private var waiting: [Int: (path: String, progress: (KotlinLong, KotlinLong) -> Void, done: (KotlinBoolean) -> Void)] = [:]
    /// iOS's handler from `handleEventsForBackgroundURLSession`, called once the session's events are delivered.
    var finishedEvents: (() -> Void)?

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        config.isDiscretionary = false // the person is waiting for it: not when convenient, now
        config.sessionSendsLaunchEvents = true
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    /// Must run at launch, so a session finished while Circaso was closed reconnects to its delegate.
    static func install() {
        BackgroundUploadBridge.shared.provider = shared
        _ = shared.session
    }

    func upload(url: String, headers: [String: String], path: String, progress: @escaping (KotlinLong, KotlinLong) -> Void, done: @escaping (KotlinBoolean) -> Void) {
        guard let target = URL(string: url) else { done(KotlinBoolean(value: false)); return }
        var request = URLRequest(url: target)
        request.httpMethod = "PUT"
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        let task = session.uploadTask(with: request, fromFile: URL(fileURLWithPath: path))
        task.taskDescription = path // so the file can be cleaned up even if Circaso is relaunched before it finishes
        lock.lock(); waiting[task.taskIdentifier] = (path, progress, done); lock.unlock()
        task.resume()
    }

    /// The body going up, for the determinate ring on "My moment" (M6). Only while Circaso is running to hear it.
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        lock.lock(); let entry = waiting[task.taskIdentifier]; lock.unlock()
        guard let entry, totalBytesExpectedToSend > 0 else { return }
        entry.progress(KotlinLong(value: totalBytesSent), KotlinLong(value: totalBytesExpectedToSend))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        let ok = error == nil && (200..<300).contains(status)
        lock.lock(); let entry = waiting.removeValue(forKey: task.taskIdentifier); lock.unlock()
        guard let entry else {
            // Finished after Circaso was closed: write it down, so the resumed send completes it instead of
            // uploading again (ResumableUpload asks BackgroundUploads.finished).
            if let path = task.taskDescription { try? FileManager.default.removeItem(atPath: path) }
            if ok, let url = task.originalRequest?.url?.absoluteString { Self.recordFinished(url) }
            return
        }
        try? FileManager.default.removeItem(atPath: entry.path)
        entry.done(KotlinBoolean(value: ok))
    }

    private static func recordFinished(_ url: String) {
        let defaults = UserDefaults.standard
        let now = Date().timeIntervalSince1970
        var done = (defaults.dictionary(forKey: finishedKey) as? [String: Double]) ?? [:]
        done = done.filter { now - $0.value < 24 * 60 * 60 } // a pre-signed URL lives 15 minutes; a day is plenty
        done[url] = now
        defaults.set(done, forKey: finishedKey)
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.finishedEvents?()
            self.finishedEvents = nil
        }
    }
}

/// The UIKit hooks SwiftUI doesn't offer: iOS waking Circaso because background uploads finished, and notification taps.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    /// Set before launch finishes, so a tap that started Circaso (cold) is delivered too.
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        // The APNs token for chat, money and group pushes (no prompt: showing them waits for the person's yes,
        // asked when the Chats tab first shows). The server sends iPhone pushes straight to APNs.
        application.registerForRemoteNotifications()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        IosPushBridge.shared.tokenUpdated(hex: deviceToken.map { String(format: "%02x", $0) }.joined())
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // No token now (simulator, no network); iOS tries again at the next launch.
    }

    /// Tapped, cold, in the background or on screen: open where it points (NotificationTaps → the navigation).
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
            _ = NotificationTaps_iosKt.openNotification(userInfo: response.notification.request.content.userInfo)
        }
        completionHandler()
    }

    /// On screen: still show it, except a push for the chat that is open (it is already on screen).
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler(IosChatPushKt.shouldPresent(userInfo: notification.request.content.userInfo) ? [.banner, .list, .sound] : [])
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        if identifier == BackgroundUploadImpl.identifier {
            BackgroundUploadImpl.shared.finishedEvents = completionHandler
        } else {
            completionHandler()
        }
    }
}
