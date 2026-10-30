package com.circaso.core.sync

import com.circaso.core.platform.ConnectionStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** How many changes went out after the phone was offline; the app shows "Back online · N sent" once. */
class SyncAnnouncements {
    private val _sentAfterOffline = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val sentAfterOffline: SharedFlow<Int> = _sentAfterOffline.asSharedFlow()
    fun announce(sent: Int) { _sentAfterOffline.tryEmit(sent) }
}

/**
 * Sends the queue whenever the phone is online (at launch and on every reconnect) and announces how many changes
 * went out, but only when the phone really was offline and something really was waiting (design G4).
 */
class ReconnectCoordinator(
    private val drain: suspend () -> DrainResult,
    private val announcements: SyncAnnouncements,
) {
    private var wasOffline = false

    suspend fun onStatus(status: ConnectionStatus) {
        if (status == ConnectionStatus.Offline) { wasOffline = true; return }
        val result = drain()
        val sent = (result as? DrainResult.Drained)?.sent ?: (result as? DrainResult.Paused)?.sent ?: 0
        if (wasOffline && sent > 0) announcements.announce(sent)
        wasOffline = false
    }
}
