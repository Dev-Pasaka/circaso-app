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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Rings while a call is incoming on screen (design H1): the phone's own ringtone, looping, and the vibration pattern
 * of a call, as the phone's ringer mode allows (silent: nothing; vibrate: vibration only). The ringing notification
 * stops its own sound once the app opens onto the call, so without this the answer screen was silent.
 */
class CallRinger(private val context: Context, private val controller: CallController, private val scope: CoroutineScope) {
    private var ringtone: Ringtone? = null
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }

    fun start() {
        scope.launch {
            controller.state.map { it?.phase == CallPhase.Incoming }.distinctUntilChanged().collect { if (it) ring() else stop() }
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
