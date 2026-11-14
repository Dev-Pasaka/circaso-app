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
    /**
     * True while someone is signed in, with or without a connection (2026-11-14): moments posted before the app was
     * closed and still being got ready (a video part-way made smaller) carry on, so none is lost to a restart.
     */
    private val signedIn: Flow<Boolean>? = null,
) {
    fun start() {
        signedIn?.let { s -> scope.launch { s.distinctUntilChanged().filter { it }.collect { repository().resume() } } }
        // Each reconnect too (coming back to Circaso is one): an encoder that couldn't in the background is tried again.
        scope.launch { merge(ready.distinctUntilChanged(), socket.distinctUntilChanged()).filter { it }.collect { repository().resume() } }
        scope.launch {
            merge(ready.distinctUntilChanged(), socket.distinctUntilChanged()).filter { it }.collect { repository().refresh() }
        }
    }
}
