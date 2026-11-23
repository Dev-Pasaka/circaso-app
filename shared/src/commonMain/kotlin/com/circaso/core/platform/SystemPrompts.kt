package com.circaso.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A system permission prompt (contacts, notifications) is up (2026-11-23): a sheet that rises by itself (the chat
 * backup's) waits until it is answered, so neither covers the other.
 */
object SystemPrompts {
    private val open = MutableStateFlow(0)
    val up: StateFlow<Int> = open

    suspend fun <T> during(block: suspend () -> T): T {
        open.update { it + 1 }
        try { return block() } finally { open.update { it - 1 } }
    }
}
