package com.circaso.core.common

import com.circaso.core.datastore.AppPreferences
import com.circaso.core.session.AuthManager
import com.circaso.core.session.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * True once the first real screen can be drawn correctly: the saved session is read and the saved theme is loaded.
 * The Android launch splash stays up until then (with a time limit), so the person never sees a blank or wrong-theme frame.
 */
class StartupGate(auth: AuthManager, prefs: AppPreferences, scope: CoroutineScope) {
    val ready: StateFlow<Boolean> = combine(auth.state, prefs.themeOverrides) { state, _ -> state != AuthState.Restoring }
        .stateIn(scope, SharingStarted.Eagerly, false)
}
