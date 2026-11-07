@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.circaso.core.platform

import com.circaso.feature.mediacompose.IncomingShares
import com.circaso.feature.mediacompose.SharedFile
import com.circaso.feature.mediacompose.shareFileName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Photos, videos and files shared into Circaso from other apps on iPhone (2026-11-07). The Share Extension
 * (`iosApp/CircasoShare`) copies each item into the App Group container under `IncomingShares/<batch>/` and writes
 * `IncomingShares/<batch>/items.json`; the app [collect]s them when it comes to the front (or is opened with
 * `circaso://share`), moves them into its own storage and hands them to [IncomingShares]. Until the extension target is
 * added to the Xcode project and the App Group is on both targets, the container is missing and this does nothing.
 */
object IosShareInbox {
    /** The App Group both targets share (the notification extension's too). */
    const val AppGroup = "group.com.circaso.app"

    /** What the extension writes for each item: the file's name in its batch folder, its type and size. */
    @Serializable
    data class Item(val file: String, val name: String, val mime: String = "", val size: Long = -1)

    private val json = Json { ignoreUnknownKeys = true }
    private val fm get() = NSFileManager.defaultManager

    private fun ownDir(): String? {
        val base = (fm.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).firstOrNull() as? NSURL)?.path ?: return null
        val dir = "$base/incoming-shares"
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    /** At launch: brings back a share still waiting (Circaso closed during sign-in) and keeps the copy in step. */
    fun install() {
        val manifest = ownDir()?.let { "$it/pending.json" } ?: return
        IncomingShares.onChange = { files ->
            if (files == null) fm.removeItemAtPath(manifest, null)
            else (json.encodeToString(ListSerializer(SharedFile.serializer()), files) as NSString).writeToFile(manifest, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        }
        if (IncomingShares.pending.value == null) {
            val kept = NSString.stringWithContentsOfFile(manifest, NSUTF8StringEncoding, null)
                ?.let { runCatching { json.decodeFromString(ListSerializer(SharedFile.serializer()), it) }.getOrNull() }
                ?.filter { fm.fileExistsAtPath(it.path) }
            if (!kept.isNullOrEmpty()) IncomingShares.offer(kept)
        }
        collect()
    }

    /** Takes every batch the extension left, newest last; the newest becomes the pending share. Called from Swift. */
    fun collect() {
        val group = fm.containerURLForSecurityApplicationGroupIdentifier(AppGroup)?.path ?: return
        val inbox = "$group/IncomingShares"
        val own = ownDir() ?: return
        val batches = (fm.contentsOfDirectoryAtPath(inbox, null) as? List<*>)?.filterIsInstance<String>()?.sorted().orEmpty()
        for (batch in batches) {
            val dir = "$inbox/$batch"
            val items = NSString.stringWithContentsOfFile("$dir/items.json", NSUTF8StringEncoding, null)
                ?.let { runCatching { json.decodeFromString(ListSerializer(Item.serializer()), it) }.getOrNull() }
            if (items == null) { fm.removeItemAtPath(dir, null); continue }
            val target = "$own/${NSUUID().UUIDString}"
            fm.createDirectoryAtPath(target, withIntermediateDirectories = true, attributes = null, error = null)
            val files = items.mapIndexedNotNull { i, item ->
                val name = shareFileName(item.name, item.mime, i)
                val from = "$dir/${item.file.substringAfterLast('/')}"
                val to = "$target/$name"
                if (!fm.moveItemAtPath(from, to, null)) return@mapIndexedNotNull null
                val size = (fm.attributesOfItemAtPath(to, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: item.size
                SharedFile(to, name, item.mime, maxOf(size, item.size))
            }
            fm.removeItemAtPath(dir, null)
            IncomingShares.offer(files)
        }
    }
}
