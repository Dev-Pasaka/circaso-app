@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.circaso.core.notifications

import com.circaso.core.crypto.toByteArray
import com.circaso.core.platform.ChatNotifications
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
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

    /**
     * Each photo from the image cache, where the app keeps every photo it has shown, keyed by its link without the
     * query as Avatar keys it. A photo the app never drew (2026-11-14: iPhone notifications showed initials because
     * most senders' photos had never been on screen) is downloaded once now, into the same cache, at most
     * [MaxFetches] per write and within [FetchMillis] in all, so the extension can show it from the next push.
     */
    override suspend fun photos(sources: Map<String, String>): Map<String, String> = withContext(Dispatchers.Default) {
        val root = appGroupPath() ?: return@withContext emptyMap()
        val dir = "$root/${NotificationSnapshot.PhotosDir}"
        val fs = FileSystem.SYSTEM
        fs.createDirectories(dir.toPath())
        val loader = runCatching { coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE) }.getOrNull()
        val cache = loader?.diskCache
        val kept = mutableMapOf<String, String>()
        var reused = 0
        var cached = 0
        var fetched = 0
        var missing = 0
        val fetchUntil = kotlin.time.TimeSource.Monotonic.markNow() + FetchMillis.milliseconds
        suspend fun fromCache(key: String): ByteArray? = cache?.openSnapshot(key)?.use { fs.read(it.data) { readByteArray() } }
        sources.forEach { (id, source) ->
            // A profile photo's link is pre-signed and changes hourly: its path is the cache key, as Avatar keys it.
            val key = source.substringBefore('?')
            val name = "${id.replace(':', '-')}-${key.hashCode().toUInt().toString(16)}.img"
            val target = "$dir/$name".toPath()
            if (fs.exists(target)) { kept[id] = name; reused++; return@forEach }
            val bytes = runCatching {
                val local = key.removePrefix("file://").takeIf { it.startsWith("/") }?.toPath()
                when {
                    local != null -> fs.read(local) { readByteArray() }.also { cached++ }
                    else -> fromCache(key)?.also { cached++ } ?: run {
                        if (loader == null || fetched >= MaxFetches || fetchUntil.hasPassedNow()) return@run null
                        fetched++
                        // Into the disk cache under the same key (small decode only; the file keeps the original).
                        kotlinx.coroutines.withTimeoutOrNull(OneFetchMillis) {
                            loader.execute(
                                coil3.request.ImageRequest.Builder(coil3.PlatformContext.INSTANCE).data(source).diskCacheKey(key)
                                    .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).size(64).build(),
                            )
                        }
                        fromCache(key)
                    }
                }
            }.getOrNull()
            // A profile photo is often the camera's full picture (several MB, 2026-11-14: these were left out, so the
            // iPhone drew initials): stored as a small square JPEG, which also keeps the extension's memory low.
            val small = bytes?.takeIf { it.isNotEmpty() && it.size <= MaxSourceBytes }?.let { notificationThumb(it) ?: it.takeIf { b -> b.size <= MaxPhotoBytes } }
            if (small == null) { missing++; return@forEach }
            runCatching { fs.write(target) { write(small) } }.onSuccess { kept[id] = name }.onFailure { missing++ }
        }
        // Photos no longer used (changed, or the person left every chat) go.
        runCatching { fs.list(dir.toPath()).filter { it.name !in kept.values }.forEach { fs.delete(it) } }
        com.circaso.core.common.Diagnostics.note(
            "notification snapshot: photos ${kept.size}/${sources.size} (kept $reused, cached $cached, fetched $fetched, missing $missing)",
        )
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

    /** The centre square at [ThumbSide] px as JPEG; UIKit applies the rotation. Null when UIKit can't read it. */
    private fun notificationThumb(bytes: ByteArray): ByteArray? = runCatching {
        val image = platform.UIKit.UIImage(data = bytes.toNSData()) ?: return null
        val (w, h) = image.size.useContents { width to height }
        val short = kotlin.math.min(w, h)
        if (short <= 0.0) return null
        val side = kotlin.math.min(short, ThumbSide)
        val k = side / short
        val format = platform.UIKit.UIGraphicsImageRendererFormat.defaultFormat().apply { setScale(1.0) }
        val square = platform.UIKit.UIGraphicsImageRenderer(size = platform.CoreGraphics.CGSizeMake(side, side), format = format).imageWithActions {
            image.drawInRect(platform.CoreGraphics.CGRectMake(-(w - short) / 2 * k, -(h - short) / 2 * k, w * k, h * k))
        }
        val jpeg = platform.UIKit.UIImageJPEGRepresentation(square, 0.85) ?: return null
        jpeg.toByteArray()
    }.getOrNull()

    private companion object {
        const val MaxPhotoBytes = 2 * 1024 * 1024
        /** A source photo bigger than this isn't read at all. */
        const val MaxSourceBytes = 25 * 1024 * 1024
        /** The stored square's side: the extension shows it at most 256 px. */
        const val ThumbSide = 256.0
        /** Photos downloaded per snapshot: the rest come on later writes (each one leaving Circaso). */
        const val MaxFetches = 24
        /** All downloads together, inside the seconds iOS gives after Circaso leaves the screen. */
        const val FetchMillis = 12_000L
        const val OneFetchMillis = 5_000L
    }
}

private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}
