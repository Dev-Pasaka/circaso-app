package com.circaso.core.platform

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidClientInfo(context: Context) : ClientInfo {
    override val platform = "android"
    override val appVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"
    override val model: String = Build.MODEL
    override val osVersion: String = Build.VERSION.RELEASE
    override val manufacturer: String = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    override val debugBuild: Boolean = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

/** Watches the default network. "Online" needs the system's own VALIDATED flag, so a Wi-Fi with no internet counts as offline. */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(current())
    override val osStatus: StateFlow<ConnectionStatus> = _status

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _status.value = status(caps)
            }

            override fun onLost(network: Network) {
                // Switching Wi-Fi ↔ mobile data reports the old network lost, sometimes after the new one is already
                // up: read what is current rather than assume nothing is, or the app stays "offline" on good Wi-Fi.
                _status.value = current()
            }

            override fun onAvailable(network: Network) {
                _status.value = current()
            }
        })
    }

    private fun current(): ConnectionStatus =
        status(manager.getNetworkCapabilities(manager.activeNetwork))

    private fun status(caps: NetworkCapabilities?) =
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) ConnectionStatus.Online
        else ConnectionStatus.Offline
}

class AndroidStoreLauncher(private val context: Context) : StoreLauncher {
    override fun openStorePage() {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.onFailure { runCatching { context.startActivity(web) } }
    }
}
