@file:OptIn(ExperimentalForeignApi::class)

package com.circaso.core.platform

import circaso.shared.generated.resources.Res
import com.circaso.core.crypto.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.AudioToolbox.AudioServicesCreateSystemSoundID
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.SystemSoundIDVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFURLRef
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile

/**
 * The payment chime on iOS, through **System Sound Services**: the sound is silenced by the Ring/Silent switch, plays
 * at the ringer's volume, mixes with whatever else is playing, and never touches the app's `AVAudioSession` (which
 * voice notes, calls and Asha's voice set to categories that would ignore the switch). The file is copied once from
 * the app's resources to Caches and registered as a system sound off the main thread, so playing it is immediate.
 */
class IosPaymentChime : PaymentChime {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val lock = Mutex()
    private var soundId: UInt = 0u

    override fun prepare() {
        scope.launch { ready() }
    }

    override fun play() {
        scope.launch {
            // Waits for a preparation still under way, so an early play is late by its few milliseconds, not lost.
            val id = ready()
            if (id != 0u) AudioServicesPlaySystemSound(id)
        }
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun ready(): UInt = lock.withLock {
        if (soundId != 0u) return@withLock soundId
        runCatching {
            val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return@runCatching
            val path = "$caches/payment_chime.wav"
            if (!NSFileManager.defaultManager.fileExistsAtPath(path)) Res.readBytes("files/payment_chime.wav").toNSData().writeToFile(path, atomically = true)
            val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef?
            memScoped {
                val out = alloc<SystemSoundIDVar>()
                if (AudioServicesCreateSystemSoundID(url, out.ptr) == 0) soundId = out.value
            }
            url?.let { CFRelease(it) }
        }
        soundId
    }
}
