@file:OptIn(ExperimentalForeignApi::class)

package com.circaso.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.darwin.dispatch_get_main_queue

class IosClientInfo : ClientInfo {
    override val platform = "ios"
    override val appVersion: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0.0.0"
    override val model: String = UIDevice.currentDevice.model
    override val osVersion: String = UIDevice.currentDevice.systemVersion
    override val manufacturer: String = "Apple"

    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    override val debugBuild: Boolean = kotlin.native.Platform.isDebugBinary
}

/** Watches the network path (NWPathMonitor). "Satisfied" means the system believes the internet is reachable. */
class IosConnectivityMonitor : ConnectivityMonitor {
    private val _status = MutableStateFlow(ConnectionStatus.Online)
    override val osStatus: StateFlow<ConnectionStatus> = _status
    private val monitor = nw_path_monitor_create()

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            _status.value =
                if (nw_path_get_status(path) == nw_path_status_satisfied) ConnectionStatus.Online
                else ConnectionStatus.Offline
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }
}

/** Opens the App Store page. The numeric App Store id comes from Info.plist (`CircasoAppStoreId`); until it is set, nothing opens. */
class IosStoreLauncher : StoreLauncher {
    override fun openStorePage() {
        val id = NSBundle.mainBundle.objectForInfoDictionaryKey("CircasoAppStoreId") as? String ?: return
        val url = NSURL.URLWithString("itms-apps://itunes.apple.com/app/id$id") ?: return
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>(), null)
    }
}
