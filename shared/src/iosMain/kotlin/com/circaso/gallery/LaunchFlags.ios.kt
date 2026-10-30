package com.circaso.gallery

import platform.Foundation.NSProcessInfo

internal actual fun launchFlag(name: String): Boolean = NSProcessInfo.processInfo.arguments.contains(name)

internal actual fun launchValue(name: String): String? =
    NSProcessInfo.processInfo.arguments.filterIsInstance<String>().firstOrNull { it.startsWith("$name=") }?.substringAfter("=")
