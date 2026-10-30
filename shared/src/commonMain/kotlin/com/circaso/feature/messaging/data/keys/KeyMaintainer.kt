package com.circaso.feature.messaging.data.keys

import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.session.AuthManager
import com.circaso.core.session.AuthState
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.messaging.domain.KeysRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Runs [KeysRepository.maintain] whenever a signed-in phone is online: at launch, right after device registration
 * (the session turns active) and on every reconnect. The repository itself decides whether anything needs doing.
 */
class KeyMaintainer(
    private val auth: AuthManager,
    private val connection: ConnectionState,
    private val keys: KeysRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            combine(auth.state, connection.status) { state, status -> state == AuthState.Active && status == ConnectionStatus.Online }
                .distinctUntilChanged()
                .filter { it }
                .collect { keys.maintain() }
        }
    }
}
