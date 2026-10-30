package com.circaso.feature.messaging.data.repository

import com.circaso.core.realtime.RealtimeClient
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.MessagesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Each time the socket connects (launch, reconnect), reload the chats and pull the inbox (guide, M13: "call it after
 * connecting the socket"). A push wake and a foreground return call `syncInbox` through the same repository.
 */
class MessagingStarter(
    private val realtime: RealtimeClient,
    private val chats: ChatsRepository,
    private val messages: MessagesRepository,
    private val scope: CoroutineScope,
    /** A live location share outlives the app: on launch, its tracker is started again if one is still running. */
    private val live: LiveLocations? = null,
) {
    fun start() {
        scope.launch { realtime.connected.filter { it }.collect { chats.refresh(); messages.syncInbox(); live?.flushStops() } }
        live?.let { scope.launch { it.refreshTracker() } }
    }
}
