package com.circaso.core.platform

import kotlinx.coroutines.flow.StateFlow

enum class ConnectionStatus { Online, Offline }

/** What the operating system says about the network. Whether our server is reachable is judged in `core/sync`. */
interface ConnectivityMonitor {
    val osStatus: StateFlow<ConnectionStatus>
}

/** Who and what this build is. Filled in per platform. */
interface ClientInfo {
    /** `android` or `ios`. */
    val platform: String
    val appVersion: String
    val model: String
    val osVersion: String

    /** A debug build (Android debuggable, iOS debug binary): developer-only readouts such as Asha's voice timing show. */
    val debugBuild: Boolean get() = false
}

/** Opens this app's store page (the force-update screen). Does nothing if the page cannot be opened. */
interface StoreLauncher {
    fun openStorePage()
}
