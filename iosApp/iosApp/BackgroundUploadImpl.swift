import Foundation
import Shared
import UIKit

/// Uploads that outlive the app (see BackgroundUploads.kt): photos, videos, voice notes, moments and profile photos
/// go up through a background URL session, which iOS finishes even when Circaso is suspended or closed.
final class BackgroundUploadImpl: NSObject, BackgroundUploadProvider, URLSessionTaskDelegate {
    static let shared = BackgroundUploadImpl()
    static let identifier = "com.circaso.app.uploads"

    private let lock = NSLock()
    private var waiting: [Int: (path: String, done: (KotlinBoolean) -> Void)] = [:]
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

    func upload(url: String, headers: [String: String], path: String, done: @escaping (KotlinBoolean) -> Void) {
        guard let target = URL(string: url) else { done(KotlinBoolean(value: false)); return }
        var request = URLRequest(url: target)
        request.httpMethod = "PUT"
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        let task = session.uploadTask(with: request, fromFile: URL(fileURLWithPath: path))
        lock.lock(); waiting[task.taskIdentifier] = (path, done); lock.unlock()
        task.resume()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        let ok = error == nil && (200..<300).contains(status)
        lock.lock(); let entry = waiting.removeValue(forKey: task.taskIdentifier); lock.unlock()
        guard let entry else { return } // finished after Circaso was closed: the outbox sends it again on launch
        try? FileManager.default.removeItem(atPath: entry.path)
        entry.done(KotlinBoolean(value: ok))
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.finishedEvents?()
            self.finishedEvents = nil
        }
    }
}

/// The one UIKit hook SwiftUI doesn't offer: iOS waking Circaso because background uploads finished.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        if identifier == BackgroundUploadImpl.identifier {
            BackgroundUploadImpl.shared.finishedEvents = completionHandler
        } else {
            completionHandler()
        }
    }
}
