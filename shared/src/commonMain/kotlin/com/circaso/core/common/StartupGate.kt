package com.circaso.core.common

import com.circaso.core.datastore.AppPreferences
import com.circaso.core.session.AuthManager
import com.circaso.core.session.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * True once the first real screen can be drawn correctly: the saved session is read, the saved theme is loaded and,
 * signed in, the chat list on this phone has been read (2026-11-14: an offline-first app never opens on an empty
 * Chats that fills a moment later). The launch splash stays up until then, with a time limit, so the person never
 * sees a blank, half-filled or wrong-theme frame.
 */
class StartupGate(auth: AuthManager, prefs: AppPreferences, scope: CoroutineScope) {
    /** Set once the first screen's own data is in memory (the chat list, see ChatListWarmup). Stays true. */
    val firstScreenReady = MutableStateFlow(false)

    val ready: StateFlow<Boolean> = combine(auth.state, prefs.themeOverrides, firstScreenReady) { state, _, content ->
        state != AuthState.Restoring && (state != AuthState.Active || content)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    companion object {
        /** The longest the splash waits for [ready]: past it the app opens anyway, never stuck on the logo. */
        const val MaxWaitMillis = 1_500L
    }
}
