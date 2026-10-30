package com.circaso.core.platform

import com.circaso.feature.messaging.data.repository.LiveLocations
import com.circaso.feature.messaging.data.repository.LiveTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Live location on iOS: CoreLocation's background updates (the `location` background mode, with the blue pill
 * showing) feed [LiveLocations.onFix]; a 30-second tick ends shares whose time ran out while the app is awake.
 */
class IosLiveTracker : LiveTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false
    private var ticking: Job? = null

    private fun live(): LiveLocations? = runCatching { KoinPlatform.getKoin().get<LiveLocations>() }.getOrNull()

    override fun start(chats: Int) {
        if (running) return
        val bridge = LocationBridge.provider ?: return
        running = true
        bridge.startUpdates { lat, lng, acc -> scope.launch { runCatching { live()?.onFix(GeoPoint(lat, lng, acc)) } } }
        ticking = scope.launch { while (true) { delay(30_000); runCatching { live()?.tick() } } }
    }

    override fun stop() {
        if (!running) return
        running = false
        ticking?.cancel()
        ticking = null
        LocationBridge.provider?.stopUpdates()
    }
}
