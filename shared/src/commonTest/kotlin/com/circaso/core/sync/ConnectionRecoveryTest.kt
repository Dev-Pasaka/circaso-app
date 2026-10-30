package com.circaso.core.sync

import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A blip that doesn't change the network must not leave the app offline until it is restarted. */
class ConnectionRecoveryTest {
    private val os = MutableStateFlow(ConnectionStatus.Online)
    private val monitor = object : ConnectivityMonitor { override val osStatus = os }

    @Test fun stuckOfflineOnAWorkingNetworkComesBackWhenTheServerAnswers() = runTest {
        val health = NetworkHealth()
        var serverUp = false
        var probes = 0
        val state = ConnectionState(monitor, health, backgroundScope, offlineDelayMs = 0, probe = { probes++; serverUp })
        repeat(3) { health.onNetworkFailure() }
        runCurrent()
        assertEquals(ConnectionStatus.Offline, state.status.value)

        advanceTimeBy(5_001); runCurrent()
        assertEquals(1, probes, "first probe after 5 s")
        assertEquals(ConnectionStatus.Offline, state.status.value)

        serverUp = true
        advanceTimeBy(10_001); runCurrent()
        assertEquals(ConnectionStatus.Online, state.status.value)
        val after = probes
        advanceTimeBy(120_000); runCurrent()
        assertEquals(after, probes, "no probing once online")
    }

    @Test fun anOsThatMissedANetworkSwitchIsOverruledByTheServer() = runTest {
        val health = NetworkHealth()
        val state = ConnectionState(monitor, health, backgroundScope, offlineDelayMs = 0, probe = { true })
        os.value = ConnectionStatus.Offline // Android reported the old network lost after the new one came up
        runCurrent()
        assertEquals(ConnectionStatus.Offline, state.status.value)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(ConnectionStatus.Online, state.status.value, "the server answered, so we are online")
    }

    @Test fun whenTheServerDoesNotAnswerAnOfflineOsStaysOffline() = runTest {
        val health = NetworkHealth()
        val state = ConnectionState(monitor, health, backgroundScope, offlineDelayMs = 0, probe = { false })
        os.value = ConnectionStatus.Offline
        advanceTimeBy(120_000); runCurrent()
        assertEquals(ConnectionStatus.Offline, state.status.value)
    }

    @Test fun probeBackoffGrowsAndCaps() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (0..5).map { probeDelayMillis(it) })
    }
}
