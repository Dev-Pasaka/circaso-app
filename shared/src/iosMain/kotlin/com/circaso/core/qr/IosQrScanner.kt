package com.circaso.core.qr

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** VisionKit's scanner lives on the Swift side (`QrScannerImpl.swift`), installed at launch like the other bridges. */
interface QrScannerProvider {
    val supported: Boolean
    /** [completion] gets the code's text, or null when closed. */
    fun scan(completion: (String?) -> Unit)
}

object QrScannerBridge {
    var provider: QrScannerProvider? = null
}

class IosQrScanner : QrScanner {
    override val available: Boolean get() = QrScannerBridge.provider?.supported == true
    override suspend fun scan(): String? {
        val p = QrScannerBridge.provider ?: return null
        return suspendCancellableCoroutine { cont -> p.scan { if (cont.isActive) cont.resume(it) } }
    }
}
