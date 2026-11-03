package com.circaso.core.platform

import com.circaso.core.crypto.toNSData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.Foundation.writeToFile
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/** The background URL session lives on the Swift side (`BackgroundUploadImpl.swift`), installed at launch. */
interface BackgroundUploadProvider {
    /** PUTs the file at [path] through a background session iOS finishes even if Circaso is suspended; [done] gets true on a 2xx. */
    fun upload(url: String, headers: Map<String, String>, path: String, done: (Boolean) -> Unit)
}

object BackgroundUploadBridge {
    var provider: BackgroundUploadProvider? = null
}

/**
 * iOS's side of [BackgroundUploads]: background time for the whole send (the upload and the calls that finish it),
 * and the file itself through a background URL session, which iOS carries on with when Circaso is suspended.
 */
class IosBackgroundUploads : BackgroundUploads {
    override suspend fun <T> keep(kind: UploadKind, work: suspend () -> T): T {
        askForNotificationsOnce()
        val app = UIApplication.sharedApplication
        var task = UIBackgroundTaskInvalid
        task = app.beginBackgroundTaskWithName(kind.name) {
            // Out of time: iOS will suspend us. The file's upload goes on in the background session; the rest
            // resumes from the outbox when Circaso next runs.
            app.endBackgroundTask(task)
            task = UIBackgroundTaskInvalid
        }
        return try {
            work()
        } finally {
            if (task != UIBackgroundTaskInvalid) app.endBackgroundTask(task)
        }
    }

    override suspend fun put(url: String, headers: Map<String, String>, bytes: ByteArray): Boolean? {
        val provider = BackgroundUploadBridge.provider ?: return null
        // A background session uploads from a file, never from memory.
        val path = NSTemporaryDirectory() + "upload-" + NSUUID().UUIDString
        val written = withContext(Dispatchers.Default) { bytes.toNSData().writeToFile(path, atomically = true) }
        if (!written) return null
        return suspendCancellableCoroutine { cont ->
            provider.upload(url, headers, path) { ok -> if (cont.isActive) cont.resume(ok) }
        }
    }

    override fun failed(kind: UploadKind) {
        platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
            if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) return@dispatch_async // the screen shows it
            val content = UNMutableNotificationContent().apply {
                setTitle(kind.failed)
                setBody("Open Circaso to send it again.")
            }
            val request = UNNotificationRequest.requestWithIdentifier("upload-failed-${kind.name}", content, null)
            UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
        }
    }

    /** Asked the first time something uploads, while Circaso is open: it's when a "didn't send" notice can matter. */
    private fun askForNotificationsOnce() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.getNotificationSettingsWithCompletionHandler { settings ->
            if (settings?.authorizationStatus == UNAuthorizationStatusNotDetermined) {
                center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge) { _, _ -> }
            }
        }
    }
}
