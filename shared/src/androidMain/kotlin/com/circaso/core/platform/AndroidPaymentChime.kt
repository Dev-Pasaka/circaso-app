package com.circaso.core.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool

/**
 * The payment chime on Android: a [SoundPool] on the notification stream (its volume, its routing, Do Not Disturb's
 * rules), loaded ahead so it starts on the frame the card settles. It only plays in the normal ringer mode: on silent
 * or vibrate the phone has asked for quiet. The sound ships in the app module's `res/raw` (`payment_chime.wav`).
 */
class AndroidPaymentChime(private val context: Context) : PaymentChime {
    private val lock = Any()
    private var pool: SoundPool? = null
    private var soundId = 0
    @Volatile private var loaded = false
    @Volatile private var playWhenLoaded = false

    override fun prepare() {
        synchronized(lock) {
            if (pool != null) return
            val res = context.resources.getIdentifier("payment_chime", "raw", context.packageName).takeIf { it != 0 } ?: return
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            pool = runCatching { SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build() }.getOrNull()?.also { p ->
                // Decoding happens on SoundPool's own thread; a play asked for before it is ready waits for it.
                p.setOnLoadCompleteListener { sp, id, status ->
                    if (status == 0 && id == soundId) {
                        loaded = true
                        if (playWhenLoaded) { playWhenLoaded = false; if (audible()) sp.play(id, 1f, 1f, 1, 0, 1f) }
                    }
                }
                soundId = p.load(context, res, 1)
            }
        }
    }

    override fun play() {
        runCatching {
            if (!audible()) return
            prepare()
            val p = pool ?: return
            if (loaded) p.play(soundId, 1f, 1f, 1, 0, 1f) else playWhenLoaded = true
        }
    }

    /** The phone isn't on silent or vibrate. */
    private fun audible(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }
}
