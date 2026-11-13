@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.circaso.core.notifications

import com.circaso.core.platform.ChatNotifications
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.mp.KoinPlatform
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Intents.INInteraction
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS's [ChatNotifications]: pushes are shown by the system, worded by the Notification Service Extension
 * (iosApp/NotificationService), so this only knows the chat on screen (its pushes aren't shown, see [shouldPresent])
 * and takes notifications down: a chat's when it opens (each carries the chat's id as its thread), everything when
 * Notification privacy is turned on or the person signs out.
 */
class IosChatNotifications : ChatNotifications {
    override var activeChatId: String? = null

    /** The permission sheet is asked with the push token (PushNotificationsImpl.swift), once someone is signed in. */
    override suspend fun ensurePermission() {}

    override fun show(chatId: String, title: String, text: String, messageId: String?) {}

    override fun clear(chatId: String) = removeDelivered { it == chatId }

    /**
     * Privacy turned on, or signed out: the notifications go, and so do the conversations the extension donated to iOS
     * (names and photos that Siri Suggestions and the share sheet would otherwise keep).
     */
    override fun clearShown() {
        removeDelivered { true }
        INInteraction.deleteAllInteractionsWithCompletion(null)
    }

    private fun removeDelivered(matches: (String) -> Boolean) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.getDeliveredNotificationsWithCompletionHandler { delivered ->
            val ids = delivered.orEmpty().filterIsInstance<UNNotification>()
                .filter { matches(it.request.content.threadIdentifier) }
                .map { it.request.identifier }
            if (ids.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(ids)
        }
    }
}

/** Called by the notification delegate (AppDelegate) before showing a push while Circaso is open: not for the chat on screen. */
fun shouldPresent(userInfo: Map<Any?, *>): Boolean {
    val chatId = userInfo["chatId"] as? String ?: return true
    val open = runCatching { KoinPlatform.getKoin().get<ChatNotifications>().activeChatId }.getOrNull()
    return chatId != open
}

private fun appGroupPath(): String? =
    NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(AppGroupId)?.path

/**
 * The [NotificationSnapshot] and the photos in the App Group container, where the extension reads them. Off (nothing
 * written) when the App Group isn't in this build's entitlements.
 */
class IosNotificationSnapshotFile : NotificationSnapshotFile {
    override val enabled: Boolean get() = appGroupPath() != null

    override suspend fun write(sealed: ByteArray?) = withContext(Dispatchers.Default) {
        val root = appGroupPath() ?: return@withContext
        val path = "$root/${NotificationSnapshot.FileName}"
        if (sealed == null) {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
            NSFileManager.defaultManager.removeItemAtPath("$root/${NotificationSnapshot.PhotosDir}", null)
        } else {
            sealed.toNSData().writeToFile(path, atomically = true)
        }
        Unit
    }

    override suspend fun writeArrived(sealed: ByteArray?) = withContext(Dispatchers.Default) {
        val path = "${appGroupPath() ?: return@withContext}/${NotificationSnapshot.ArrivedFileName}"
        if (sealed == null) NSFileManager.defaultManager.removeItemAtPath(path, null) else sealed.toNSData().writeToFile(path, atomically = true)
        Unit
    }

    override suspend fun readArrived(): ByteArray? = withContext(Dispatchers.Default) {
        val path = "${appGroupPath() ?: return@withContext null}/${NotificationSnapshot.ArrivedFileName}"
        runCatching { FileSystem.SYSTEM.read(path.toPath()) { readByteArray() } }.getOrNull()
    }

    override suspend fun photos(sources: Map<String, String>): Map<String, String> = withContext(Dispatchers.Default) {
        val root = appGroupPath() ?: return@withContext emptyMap()
        val dir = "$root/${NotificationSnapshot.PhotosDir}"
        val fs = FileSystem.SYSTEM
        fs.createDirectories(dir.toPath())
        val cache = runCatching { coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE).diskCache }.getOrNull()
        val kept = mutableMapOf<String, String>()
        sources.forEach { (id, source) ->
            // A profile photo's link is pre-signed and changes hourly: its path is the cache key, as Avatar keys it.
            val key = source.substringBefore('?')
            val name = "${id.replace(':', '-')}-${key.hashCode().toUInt().toString(16)}.img"
            val target = "$dir/$name".toPath()
            if (fs.exists(target)) { kept[id] = name; return@forEach }
            runCatching {
                val local = key.removePrefix("file://").takeIf { it.startsWith("/") }?.toPath()
                val bytes = if (local != null) {
                    fs.read(local) { readByteArray() }
                } else {
                    val snapshot = cache?.openSnapshot(key) ?: return@forEach
                    snapshot.use { fs.read(it.data) { readByteArray() } }
                }
                // Profile photos are small; anything big is left out rather than weigh on the extension.
                if (bytes.isEmpty() || bytes.size > MaxPhotoBytes) return@forEach
                fs.write(target) { write(bytes) }
                kept[id] = name
            }
        }
        // Photos no longer used (changed, or the person left every chat) go.
        runCatching { fs.list(dir.toPath()).filter { it.name !in kept.values }.forEach { fs.delete(it) } }
        kept
    }

    /** Leaving the screen: iOS gives some seconds to finish writing before it suspends Circaso. */
    override suspend fun finishing(block: suspend () -> Unit) {
        val app = UIApplication.sharedApplication
        var task = UIBackgroundTaskInvalid
        task = app.beginBackgroundTaskWithName("notification-snapshot") {
            app.endBackgroundTask(task)
            task = UIBackgroundTaskInvalid
        }
        try {
            block()
        } finally {
            if (task != UIBackgroundTaskInvalid) app.endBackgroundTask(task)
        }
    }

    private companion object {
        const val MaxPhotoBytes = 2 * 1024 * 1024
    }
}

private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}
