package com.circaso.feature.messaging.data.repository

import com.circaso.core.common.StartupGate
import com.circaso.core.session.AuthState
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The chat list, read from this phone as soon as Circaso starts and kept in memory while signed in (2026-11-14,
 * "never a blank Chats"): the Chats tab opens on it at once instead of starting empty and filling a moment later,
 * and the launch splash waits for its first read ([StartupGate.firstScreenReady]).
 *
 * The first list waits for the saved names to be read, so a chat never shows its profile name ("Chat") before the
 * name the person saved ("Mum").
 */
class ChatListWarmup(
    private val authState: StateFlow<AuthState>,
    private val chats: ChatsRepository,
    private val namesLoaded: Flow<Boolean>,
    private val gate: StartupGate,
    private val scope: CoroutineScope,
) {
    private val _list = MutableStateFlow<List<ChatSummary>?>(null)

    /** The chat list on this phone; null until it has been read (and while signed out). */
    val list: StateFlow<List<ChatSummary>?> = _list.asStateFlow()

    fun start() {
        scope.launch {
            authState.map { it == AuthState.Active }.distinctUntilChanged().collectLatest { active ->
                if (!active) { _list.value = null; return@collectLatest }
                namesLoaded.first { it }
                chats.chats.collect { rows ->
                    _list.value = rows
                    gate.firstScreenReady.value = true
                }
            }
        }
    }
}
