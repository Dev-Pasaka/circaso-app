package com.circaso.feature.status.data.repository

import com.circaso.feature.status.domain.MomentsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Contacts' moments are on screen from the first frame (Pasaka, 2026-11-08: "Immediately when the app launches, or
 * someone signs in on a new device, their contacts' moments should be visible"). ST4 is fetched the moment a signed-in
 * phone is online (launch, a fresh sign-in, every reconnect) and each time the socket connects (frames missed while
 * away), whatever screen is showing, not only when the Moments chip is picked. The fetch asks posters for keys
 * to what this phone can't open (ST9) in the same step, so a new phone's request goes out at once.
 */
class MomentsStarter(
    /** True while a signed-in phone is online (the session active, the connection up). */
    private val ready: Flow<Boolean>,
    /** The realtime socket's state: each new connection may follow frames missed while away. */
    private val socket: Flow<Boolean>,
    private val repository: () -> MomentsRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            merge(ready.distinctUntilChanged(), socket.distinctUntilChanged()).filter { it }.collect { repository().refresh() }
        }
    }
}
