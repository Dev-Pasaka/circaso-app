package com.circaso.feature.messaging.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Hold-to-record. Starting can take a moment (the microphone prompt), so a release that lands before the
 * recorder is running still stops it: the start job is awaited, then the clip is dropped or sent.
 *
 * [show] reports whether a recording is live and, when there is one, a message for the person.
 */
internal class VoiceHold(
    private val scope: CoroutineScope,
    private val start: suspend () -> String?,
    private val finish: suspend () -> String?,
    private val cancel: suspend () -> Unit,
    private val show: (recording: Boolean, message: String?) -> Unit,
) {
    private var held = false
    private var recording = false
    private var starting: Job? = null

    fun press() {
        if (held) return
        held = true
        starting = scope.launch {
            val error = start()
            when {
                error != null -> { held = false; show(false, error) }
                !held -> { cancel(); show(false, ThreadMedia.HoldToRecord) }
                else -> { recording = true; show(true, null) }
            }
        }
    }

    fun release(send: Boolean) {
        if (!held) return
        held = false
        val start = starting
        scope.launch {
            start?.join()
            if (!recording) return@launch
            recording = false
            show(false, null)
            if (send) finish()?.let { show(false, it) } else cancel()
        }
    }
}
