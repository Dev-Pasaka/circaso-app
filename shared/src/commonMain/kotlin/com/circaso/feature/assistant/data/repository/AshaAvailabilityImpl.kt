package com.circaso.feature.assistant.data.repository

import com.circaso.core.common.AppResult
import com.circaso.feature.assistant.data.remote.AshaRemote
import com.circaso.feature.assistant.domain.AshaAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Kept in memory: a fresh start reads A14 when Asha's screens open, so nothing stale survives a restart. One polling
 * loop for the app, running only while an Asha screen is visible ([watch]) and she is down, and only online.
 */
class AshaAvailabilityImpl(
    private val remote: AshaRemote,
    private val scope: CoroutineScope,
    private val isOnline: () -> Boolean = { true },
) : AshaAvailability {
    private val down = MutableStateFlow(false)
    override val isDown: StateFlow<Boolean> = down.asStateFlow()

    override var willTellWhenBack: Boolean = false
        private set

    private var watchers = 0
    private var polling: Job? = null

    override fun reportDown() {
        willTellWhenBack = true
        down.value = true
    }

    override fun reportBack() = set(down = false)

    override suspend fun refresh() {
        (remote.status() as? AppResult.Success)?.let { set(down = !it.value.available) }
    }

    private fun set(down: Boolean) {
        if (!down) willTellWhenBack = false
        this.down.value = down
    }

    override fun watch() {
        watchers++
        if (watchers == 1) {
            polling = scope.launch {
                down.collectLatest { isDown ->
                    while (isDown) {
                        delay(AshaAvailability.PollMillis)
                        if (isOnline()) refresh()
                    }
                }
            }
        }
    }

    override fun unwatch() {
        if (watchers == 0) return
        watchers--
        if (watchers == 0) {
            polling?.cancel()
            polling = null
        }
    }
}
