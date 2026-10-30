import BackgroundTasks
import Shared

/// Sends what was queued offline once the phone has a network, even after Circaso was closed (see BackgroundSync.kt):
/// one processing task that needs connectivity, handled by the shared outbox.
final class BackgroundSyncImpl: NSObject, BackgroundTaskScheduler {
    static let identifier = "com.circaso.app.outbox"

    /// Must run before the app finishes launching.
    static func install() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let task = task as? BGProcessingTask else { return }
            task.expirationHandler = { task.setTaskCompleted(success: false) }
            BackgroundSyncBridge.shared.drain { done in
                // Something still waiting (the network went again): ask for another run.
                if !done.boolValue { BackgroundSyncImpl().schedule() }
                task.setTaskCompleted(success: done.boolValue)
            }
        }
        BackgroundSyncBridge.shared.scheduler = BackgroundSyncImpl()
    }

    func schedule() {
        let request = BGProcessingTaskRequest(identifier: BackgroundSyncImpl.identifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        try? BGTaskScheduler.shared.submit(request)
    }
}
