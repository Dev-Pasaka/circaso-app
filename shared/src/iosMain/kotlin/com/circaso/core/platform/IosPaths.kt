@file:OptIn(ExperimentalForeignApi::class)

package com.circaso.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * Path of a file in Application Support (not visible in the Files app). The folder is excluded from
 * iCloud and device backups: this app's data is tied to the phone's own keys, so a restored copy would not work.
 */
fun applicationSupportPath(fileName: String): String {
    val url = NSFileManager.defaultManager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
    requireNotNull(url) { "Application Support folder is not available" }
    url.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    return "${requireNotNull(url.path)}/$fileName"
}
