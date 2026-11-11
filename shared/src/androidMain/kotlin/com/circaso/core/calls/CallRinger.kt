package com.circaso.core.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.circaso.feature.calls.CallController
import com.circaso.feature.calls.CallPhase
import com.circaso.feature.calls.data.CallsRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Rings an incoming call (design H1) one way at a time, by where Circaso is (2026-11-11):
 * - **In front**: the answer screen, with the phone's own ringtone looping and a call's vibration, as the ringer mode
 *   allows (silent: nothing; vibrate: vibration only). A call notification for the same call is taken down, since
 *   it would ring and show over the answer screen.
 * - **Not in front** (the person just left the app while its socket was still open, so the ring came as a frame): the
 *   full-screen call notification, which rings by itself, so this ringtone stays off. The server pushes the same ring
 *   too; both post the same notification id, so it shows and rings once.
 */
class CallRinger(
    private val context: Context,
    private val controller: CallController,
    private val scope: CoroutineScope,
    private val notifier: AndroidCallNotifier,
    private val activities: com.circaso.core.platform.CurrentActivity,
    private val words: suspend (callerName: String?, video: Boolean) -> com.circaso.core.notifications.NotificationContent,
) {
    private var ringtone: Ringtone? = null
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }

    private sealed interface Ring {
        data object None : Ring
        data class OnScreen(val callId: String) : Ring
        data class Notification(val callId: String, val title: String, val video: Boolean) : Ring
    }

    fun start() {
        scope.launch {
            combine(controller.state, activities.inFront) { call, inFront ->
                val callId = call?.callId
                when {
                    call == null || callId == null || call.phase != CallPhase.Incoming -> Ring.None
                    inFront -> Ring.OnScreen(callId)
                    else -> Ring.Notification(callId, call.title, call.video)
                }
            }.distinctUntilChanged().collect { ring ->
                when (ring) {
                    Ring.None -> stop()
                    is Ring.OnScreen -> { notifier.clear(ring.callId); ring() }
                    is Ring.Notification -> {
                        stop()
                        if (!notifier.showing(ring.callId)) {
                            val content = words(ring.title.takeIf { it.isNotBlank() }, ring.video)
                            notifier.ringing(ring.callId, content.title, content.text)
                        }
                    }
                }
            }
        }
    }

    private fun ring() {
        val am = context.getSystemService(AudioManager::class.java)
        val mode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = runCatching {
                RingtoneManager.getRingtone(context, RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            }.getOrNull()?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                runCatching { play() }
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1_200), 0)) }
        }
    }

    private fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
    }
}

/**
 * "Decline" on the ringing notification, without opening Circaso: CL6 with the app's own session, then the
 * notification goes. Works with the app closed (the process starts for the broadcast; Koin starts with it).
 */
class CallDeclineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(AndroidCallNotifier.ExtraCallId) ?: return
        val pending = goAsync()
        val koin = KoinPlatform.getKoin()
        koin.get<AndroidCallNotifier>().clear(callId)
        koin.get<CoroutineScope>().launch {
            try {
                val controller = koin.get<CallController>()
                // Ringing on screen too (the app was open): decline through the controller so its screen closes.
                if (controller.state.value?.callId == callId) controller.decline() else koin.get<CallsRemote>().decline(callId)
            } finally {
                pending.finish()
            }
        }
    }
}
