package com.circaso.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/** iOS's background task scheduler lives on the Swift side (`BackgroundSyncImpl.swift`), installed at launch. */
interface BackgroundTaskScheduler {
    /** Asks iOS for a background run that needs the network; iOS picks the moment. */
    fun schedule()
}

object BackgroundSyncBridge {
    var scheduler: BackgroundTaskScheduler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The Swift task handler's work: send the outbox; [done] is true when everything went. */
    fun drain(done: (Boolean) -> Unit) {
        scope.launch {
            val result = runCatching { KoinPlatform.getKoin().get<OutboxProcessor>().drain() }.getOrNull()
            done(result is DrainResult.Drained)
        }
    }
}

class IosBackgroundSync : BackgroundSync {
    override fun schedule() { BackgroundSyncBridge.scheduler?.schedule() }
}
