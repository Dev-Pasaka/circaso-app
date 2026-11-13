package com.circaso.core.notifications

import com.circaso.core.notifications.NotificationDestination.Companion.toExtras
import platform.Foundation.NSUserDefaults

/** The App Group Circaso shares with its Notification Service Extension (iosApp/NotificationService). */
const val AppGroupId = "group.com.circaso.app"

/** The key, in [AppGroupId]'s defaults, the extension reads: true means word every push privately. */
const val NotificationPrivacyDefaultsKey = "notificationPrivacy"

/**
 * Written to the App Group's defaults (and the app's own, for the app itself), on every change and at each launch, so
 * the extension words a push as "You received money", "New message" or "Incoming call" when it's on. Until the App
 * Group entitlement exists the suite is the app's own; the next launch after it's added writes the shared copy.
 */
actual fun mirrorNotificationPrivacy(on: Boolean) {
    NSUserDefaults(suiteName = AppGroupId).setBool(on, forKey = NotificationPrivacyDefaultsKey)
    NSUserDefaults.standardUserDefaults.setBool(on, forKey = NotificationPrivacyDefaultsKey)
}

/** The Notification Service Extension (2026-11-13) reads [NotificationPrivacyDefaultsKey] from the App Group. */
actual val notificationPrivacySupported: Boolean = true

/** A local notification's `userInfo`: where its tap lands. */
fun NotificationDestination.toUserInfo(): Map<Any?, Any?> = toExtras().mapKeys { it.key as Any? }

/**
 * Called by the notification delegate (iOSApp.swift) when a notification is tapped, cold, in the background or on
 * screen: its `userInfo` (a local one's destination, or a push's data). True when it named somewhere to go.
 */
fun openNotification(userInfo: Map<Any?, *>): Boolean {
    val extras = userInfo.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? String)?.let { key to it } } }.toMap()
    if (NotificationTaps.openFrom(extras)) return true
    // A push from the server (FCM puts its data at the top of userInfo, beside `aps`): ids and `kind` only.
    val destination = NotificationDestination.fromPush(extras) ?: return false
    NotificationTaps.open(destination)
    return true
}
