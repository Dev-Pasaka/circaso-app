package com.circaso.feature.messaging.data.repository

import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.MessagesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Each time the socket connects (launch, a fresh sign-in, reconnect), reload the chats and pull the inbox (guide, M13:
 * "call it after connecting the socket"). A push wake and a foreground return call `syncInbox` through the same
 * repository. The reload asks members for the key of each group this phone can't name (a new phone), and while the
 * socket stays up that is tried again every [AskAgainMillis], so an ask that found no one (or went out before this
 * phone's keys were on the server) is not the last.
 */
class MessagingStarter(
    /** The realtime socket's state ([com.circaso.core.realtime.RealtimeClient.connected]). */
    private val connected: Flow<Boolean>,
    private val chats: ChatsRepository,
    private val messages: MessagesRepository,
    private val scope: CoroutineScope,
    /** A live location share outlives the app: on launch, its tracker is started again if one is still running. */
    private val live: LiveLocations? = null,
    private val askAgainMillis: Long = AskAgainMillis,
    /** My group invites (2026-11-09), reloaded with the chats: one may have come while the socket was down. */
    private val invites: com.circaso.feature.messaging.domain.GroupInvitesRepository? = null,
) {
    fun start() {
        scope.launch {
            connected.distinctUntilChanged().collectLatest { connected ->
                if (!connected) return@collectLatest
                chats.refresh()
                invites?.refresh()
                messages.syncInbox()
                messages.flushReads() // chats marked read while the socket was down (from a notification)
                live?.flushStops()
                while (true) {
                    delay(askAgainMillis)
                    chats.askForMissingGroupKeys()
                }
            }
        }
        live?.let { scope.launch { it.refreshTracker() } }
    }

    companion object {
        /** As often as a group may be asked ([GroupKeys.AskEveryMillis]): one more ask per waiting group, no more. */
        const val AskAgainMillis = GroupKeys.AskEveryMillis
    }
}
