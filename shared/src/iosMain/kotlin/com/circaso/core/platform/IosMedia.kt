@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.circaso.core.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.posix.memcpy
import kotlin.coroutines.resume

/** CoreLocation lives on the Swift side (`LocationImpl.swift`), installed at launch like the other bridges. */
interface LocationBridgeProvider {
    /** [completion]: latitude, longitude, accuracy in metres; all null when refused or no fix. */
    fun current(completion: (Double?, Double?, Double?) -> Unit)

    /**
     * Live location: fixes until [stopUpdates], with the app in the background too (iOS shows its blue location
     * pill meanwhile). [onFix]: latitude, longitude, accuracy in metres.
     */
    fun startUpdates(onFix: (Double, Double, Double) -> Unit)
    fun stopUpdates()
}

object LocationBridge {
    var provider: LocationBridgeProvider? = null
}

class IosLocation : LocationProvider {
    override val available: Boolean get() = LocationBridge.provider != null
    override suspend fun current(): GeoPoint? {
        val p = LocationBridge.provider ?: return null
        return suspendCancellableCoroutine { cont ->
            p.current { lat, lng, acc -> if (cont.isActive) cont.resume(if (lat != null && lng != null) GeoPoint(lat, lng, acc) else null) }
        }
    }
}

actual fun openMap(latitude: Double, longitude: Double, label: String?) {
    val q = label?.let { "&q=" + it.encodeUrl() } ?: ""
    val url = NSURL.URLWithString("https://maps.apple.com/?ll=$latitude,$longitude$q") ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
}

actual fun exitApp(): Boolean = false

actual val canExitApp: Boolean = false

actual fun openInSystem(url: String) {
    val link = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(link, options = emptyMap<Any?, Any?>(), completionHandler = null)
}

actual fun openAppSettings() {
    val link = NSURL.URLWithString(platform.UIKit.UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(link, options = emptyMap<Any?, Any?>(), completionHandler = null)
}

private fun String.encodeUrl(): String = buildString { this@encodeUrl.encodeToByteArray().forEach { b -> val c = b.toInt().toChar(); if (c.isLetterOrDigit()) append(c) else append("%" + (b.toInt() and 0xff).toString(16).padStart(2, '0').uppercase()) } }

/** Decrypted media in Application Support (excluded from backups), under `media/`. */
class IosMediaFiles : MediaFiles {
    private fun filePath(name: String) = applicationSupportPath("media/$name")

    override suspend fun read(name: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(filePath(name)) ?: return null
        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)
        return ByteArray(size).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
    }

    override suspend fun write(name: String, bytes: ByteArray) {
        NSFileManager.defaultManager.createDirectoryAtPath(applicationSupportPath("media"), withIntermediateDirectories = true, attributes = null, error = null)
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        data.writeToFile(filePath(name), atomically = true)
    }

    override suspend fun clear() {
        NSFileManager.defaultManager.removeItemAtPath(applicationSupportPath("media"), error = null)
    }

    override suspend fun path(name: String): String? = filePath(name).takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
    override suspend fun delete(name: String) { NSFileManager.defaultManager.removeItemAtPath(filePath(name), error = null) }

    // Streaming through POSIX file calls: only the piece asked for is in memory, and nothing waits on an autorelease
    // pool (a loop of NSData pieces would keep every piece alive until the pool drained).

    override suspend fun size(name: String): Long? = fileSizeAt(filePath(name))

    override suspend fun readAt(name: String, offset: Long, count: Int): ByteArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val f = platform.posix.fopen(filePath(name), "rb") ?: return@withContext null
        try {
            if (count <= 0) return@withContext ByteArray(0)
            platform.posix.fseeko(f, offset, platform.posix.SEEK_SET)
            val buf = ByteArray(count)
            val n = buf.usePinned { platform.posix.fread(it.addressOf(0), 1u, count.toULong(), f).toInt() }
            if (n == count) buf else buf.copyOf(n)
        } finally {
            platform.posix.fclose(f)
        }
    }

    override suspend fun append(name: String, bytes: ByteArray) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        NSFileManager.defaultManager.createDirectoryAtPath(applicationSupportPath("media"), withIntermediateDirectories = true, attributes = null, error = null)
        val f = platform.posix.fopen(filePath(name), "ab") ?: throw IllegalStateException("can't open a media file to write")
        try {
            if (bytes.isNotEmpty()) {
                val n = bytes.usePinned { platform.posix.fwrite(it.addressOf(0), 1u, bytes.size.toULong(), f).toInt() }
                if (n != bytes.size) throw IllegalStateException("a media file write fell short (disk full?)")
            }
        } finally {
            platform.posix.fclose(f)
        }
    }

    override suspend fun move(from: String, to: String): Boolean {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(filePath(from))) return false
        fm.removeItemAtPath(filePath(to), error = null)
        return fm.moveItemAtPath(filePath(from), toPath = filePath(to), error = null)
    }

    /** A copy through the file system (on APFS a clone, instant and without reading the file). */
    override suspend fun copyIn(path: String, name: String): Boolean {
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(applicationSupportPath("media"), withIntermediateDirectories = true, attributes = null, error = null)
        fm.removeItemAtPath(filePath(name), error = null)
        return fm.copyItemAtPath(path, toPath = filePath(name), error = null)
    }
}

actual fun fileSizeAt(path: String): Long? = kotlinx.cinterop.memScoped {
    val st = alloc<platform.posix.stat>()
    if (platform.posix.stat(path, st.ptr) != 0) null else st.st_size
}
