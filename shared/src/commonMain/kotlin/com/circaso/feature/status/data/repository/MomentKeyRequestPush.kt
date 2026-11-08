package com.circaso.feature.status.data.repository

import com.circaso.feature.status.domain.MomentsRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The silent push the server sends a poster whose phone has no socket when someone's new phone asks for keys to their
 * moments (`kind` `moment.keys_requested`, `userId` the requester; backend 2026-11-08). Without it the request waited
 * until the poster opened Circaso, and the new phone showed nothing. Shows nothing either: it only seals fresh copies.
 */
object MomentKeyRequestPush {
    const val Kind = "moment.keys_requested"

    /**
     * Queues the answer as the `status.key_requested` frame would, then sends it now with [drain] (the outbox) within
     * [windowMillis], the time the platform gives a push. What doesn't finish stays queued for the next drain.
     * True when [data] was this push (handled, whatever the outcome).
     */
    suspend fun handle(data: Map<String, String>, repository: () -> MomentsRepository, drain: suspend () -> Unit, windowMillis: Long): Boolean {
        if (data["kind"] != Kind) return false
        val requester = data["userId"]?.takeIf { it.isNotBlank() } ?: return true
        withTimeoutOrNull(windowMillis) {
            repository().onFrame("status.key_requested", null, null, requesterId = requester)
            drain()
        }
        return true
    }
}
