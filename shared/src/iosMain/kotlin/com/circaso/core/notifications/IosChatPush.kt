package com.circaso.core.notifications

import com.circaso.core.crypto.IosSignalCrypto
import com.circaso.core.crypto.toByteArray
import com.circaso.core.crypto.toNSData
import com.circaso.core.platform.ChatNotifications
import com.circaso.core.platform.IosLocalSealer
import com.circaso.core.platform.IosSecureStorage
import com.circaso.core.platform.PushTokenProvider
import com.circaso.core.platform.SecureStorage
import com.circaso.core.push.PushTokenRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Intents.INInteraction
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

// ---- the APNs token (chat, money and group pushes; calls ring through PushKit, see IosCallKit.kt)

/** Swift hands over this phone's APNs device token here (AppDelegate, `didRegisterForRemoteNotificationsWithDeviceToken`). */
object IosPushBridge {
    internal val token = MutableStateFlow<String?>(null)

    fun tokenUpdated(hex: String) {
        if (token.value == hex) return
        token.value = hex
        // The registrar sends it now if someone is signed in, and again at every sign-in.
        runCatching { KoinPlatform.getKoin().get<PushTokenRegistrar>().onNewToken(hex) }
    }
}

/** The APNs token, hex, for `PUT /v1/devices/current/push-token`; the server sends iPhone pushes straight to APNs. */
object IosApnsTokenProvider : PushTokenProvider {
    override suspend fun token(): String? = withTimeoutOrNull(15_000) { IosPushBridge.token.filterNotNull().first() }
}

// ---- notifications already shown

/**
 * iOS's [ChatNotifications]: pushes are shown by the system (worded by the Notification Service Extension), so this
 * only knows the chat on screen (its pushes aren't shown, see [shouldPresent]) and takes a chat's notifications down
 * when it opens. Each chat's notifications share its id as their thread identifier.
 */
class IosChatNotifications : ChatNotifications {
    override var activeChatId: String? = null

    override suspend fun ensurePermission() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        suspendCancellableCoroutine { done ->
            center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge) { _, _ ->
                if (done.isActive) done.resume(Unit)
            }
        }
    }

    override fun show(chatId: String, title: String, text: String, messageId: String?) {}

    override fun clear(chatId: String) = removeDelivered { it == chatId }

    /**
     * Privacy turned on, or signed out: the notifications go, and so do the conversations the extension donated to
     * iOS (names, photos and words that Siri Suggestions and the share sheet would otherwise keep).
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

// ---- the snapshot for the extension

private fun appGroupPath(): String? =
    NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(AppGroupId)?.path

/** The [NotificationSnapshot] in the App Group container; off (nothing written) until the App Group is there. */
class IosNotificationSnapshotFile : NotificationSnapshotFile {
    override val enabled: Boolean get() = appGroupPath() != null

    override suspend fun write(sealed: ByteArray?) {
        val path = "${appGroupPath() ?: return}/${NotificationSnapshot.FileName}"
        if (sealed == null) NSFileManager.defaultManager.removeItemAtPath(path, null)
        else sealed.toNSData().writeToFile(path, atomically = true)
    }

    /** Leaving the screen: iOS gives a few seconds to finish writing before it suspends Circaso. */
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
}

// ---- the Notification Service Extension's entry

/** What the extension shows: the title and body, and the parts of a communication notification (sender, group). */
class NotificationWording(
    val title: String,
    val body: String,
    val chatId: String?,
    val senderId: String?,
    val senderName: String?,
    /** The sender's profile photo link (signed, short-lived; the extension tries it and falls back to initials). */
    val senderPhoto: String?,
    val group: Boolean,
    val groupName: String?,
    /** What was said without the sender's name, for the communication notification, which names them itself. */
    val line: String?,
)

/**
 * Called by the Notification Service Extension (NotificationService.swift) for each alert push. Reads the snapshot
 * from the App Group, opens the message in memory with keys from the shared Keychain item, and words it as Android
 * does. Never writes anything: the Keychain is read-only here, the snapshot is only read. [done] gets null when the
 * push isn't a chat message or nothing could be read; the extension then keeps the server's words.
 */
object NotificationServiceEntry {
    fun word(userInfo: Map<Any?, *>, done: (NotificationWording?) -> Unit) {
        val data = userInfo.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? String)?.let { key to it } } }.toMap()
        CoroutineScope(Dispatchers.Default).launch {
            val worded = runCatching {
                withTimeoutOrNull(WordMillis) {
                    val private = NSUserDefaults(suiteName = AppGroupId).boolForKey(NotificationPrivacyDefaultsKey)
                    val sealed = appGroupPath()?.let { NSData.dataWithContentsOfFile("$it/${NotificationSnapshot.FileName}") }?.toByteArray()
                    val crypto = IosSignalCrypto()
                    NotificationPeek(crypto, IosLocalSealer(ReadOnlyStorage(IosSecureStorage()), crypto)).word(data, sealed, private)
                }
            }.getOrNull()
            done(
                worded?.let { w ->
                    val c = w.conversation
                    NotificationWording(
                        title = w.content.title, body = w.content.text, chatId = data["chatId"],
                        senderId = c?.senderId, senderName = c?.senderName, senderPhoto = c?.senderPhoto,
                        group = c?.group == true, groupName = c?.title, line = c?.line,
                    )
                },
            )
        }
    }

    /** The extension has about 30 seconds; the photo download comes after this, on the Swift side. */
    private const val WordMillis = 8_000L
}

/**
 * The extension may only read the Keychain: if the wrapping key weren't readable, [IosLocalSealer] would make a new
 * one, and a second key beside the app's would cost it every sealed record. Here a missing key is just a failure.
 */
private class ReadOnlyStorage(private val storage: SecureStorage) : SecureStorage {
    override suspend fun get(key: String): String? = storage.get(key)
    override suspend fun put(key: String, value: String): Unit = throw IllegalStateException("read-only")
    override suspend fun remove(key: String): Unit = throw IllegalStateException("read-only")
}
