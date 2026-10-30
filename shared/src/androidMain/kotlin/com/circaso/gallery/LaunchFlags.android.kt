package com.circaso.gallery

/**
 * Debug launch arguments on Android come as intent extras, set by `MainActivity` before Koin starts:
 * `adb shell am start -n com.circaso.app/com.circaso.MainActivity --es preview chats` is the same as `-preview=chats` on iOS.
 */
object AndroidLaunchArgs {
    @Volatile var extras: Map<String, String> = emptyMap()
}

internal actual fun launchFlag(name: String): Boolean = AndroidLaunchArgs.extras[name.removePrefix("-")] == "true"

internal actual fun launchValue(name: String): String? = AndroidLaunchArgs.extras[name.removePrefix("-")]
