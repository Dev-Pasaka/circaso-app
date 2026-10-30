@file:OptIn(ExperimentalForeignApi::class)

package com.circaso.core.platform

import circaso.shared.generated.resources.Res
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.circaso.core.crypto.toNSData
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile

/**
 * Puts the money chime where iOS looks for a push's sound (`Library/Sounds` in the app's container), so a
 * "money received" push naming `money_received.wav` plays it, or takes it away when the person turned the sound off.
 * Off the main thread; a failure only means the default sound.
 */
@OptIn(ExperimentalResourceApi::class)
actual fun setMoneySound(on: Boolean) {
    CoroutineScope(Dispatchers.Default).launch {
        runCatching {
            val library = NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return@launch
            val dir = "$library/Sounds"
            val path = "$dir/money_received.wav"
            val files = NSFileManager.defaultManager
            if (!on) { files.removeItemAtPath(path, error = null); return@launch }
            if (files.fileExistsAtPath(path)) return@launch
            files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
            Res.readBytes("files/money_received.wav").toNSData().writeToFile(path, atomically = true)
        }
    }
}

private var preview: platform.AVFAudio.AVAudioPlayer? = null

@OptIn(ExperimentalResourceApi::class)
actual fun playMoneySound() {
    CoroutineScope(Dispatchers.Main).launch {
        runCatching {
            val bytes = Res.readBytes("files/money_received.wav").toNSData()
            // Kept in a field: the player stops if it is released while it plays.
            preview = platform.AVFAudio.AVAudioPlayer(data = bytes, error = null).apply { play() }
        }
    }
}
