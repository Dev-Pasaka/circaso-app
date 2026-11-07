package com.circaso.feature.assistant.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether Asha can answer, app-wide (backend decision 2026-11-07: Claude only, no fallback model). When her model is
 * down the server answers `assistant.unavailable` (chat) or closes the voice call with it, and Asha's screens show a
 * calm "down for maintenance" state until she is back: A14 `GET /v1/assistant/status` says so (read on opening and
 * resuming her screens, and every [PollMillis] while one of them is visible and she is down), and so does the socket's
 * `assistant` `status` frame or the "Asha is back" push.
 */
interface AshaAvailability {
    /** True while Asha is down for maintenance. */
    val isDown: StateFlow<Boolean>

    /**
     * True when the server recorded this person meeting her down (a turn of theirs came back `assistant.unavailable`),
     * so it will tell them she is back. Only then may the app promise "we'll let you know".
     */
    val willTellWhenBack: Boolean

    /** A turn came back `assistant.unavailable`. */
    fun reportDown()

    /** The server said she is back (the socket's `assistant` `status` frame). */
    fun reportBack()

    /** Reads A14; a failure (offline, a timeout) leaves the state as it was. */
    suspend fun refresh()

    /**
     * One of Asha's screens is visible: while any is, and she is down, A14 is read every [PollMillis] (online only).
     * Counted, so the chat and the voice screen over it poll once between them; each [watch] needs its [unwatch].
     */
    fun watch()

    fun unwatch()

    companion object {
        /** The server's code for Asha down for maintenance, on A2/A9 and as the live socket's closing reason. */
        const val UnavailableCode = "assistant.unavailable"

        /** How often A14 is read while her screens show her as down. */
        const val PollMillis = 30_000L
    }
}

/** One screen's [AshaAvailability.watch], idempotent: on while the screen is visible, off when it isn't or is gone. */
class AvailabilityWatch(private val availability: AshaAvailability?) {
    private var on = false

    fun set(visible: Boolean) {
        val a = availability ?: return
        if (visible == on) return
        on = visible
        if (visible) a.watch() else a.unwatch()
    }
}
