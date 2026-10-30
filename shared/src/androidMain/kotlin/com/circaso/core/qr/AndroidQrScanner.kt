package com.circaso.core.qr

import android.app.Activity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Google's code scanner: its own full-screen camera UI from Play services, so the app needs no camera permission. */
class AndroidQrScanner(private val activity: () -> Activity?) : QrScanner {
    override val available = true

    override suspend fun scan(): String? {
        val host = activity() ?: return null
        val scanner = GmsBarcodeScanning.getClient(host, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        return suspendCancellableCoroutine { cont ->
            scanner.startScan()
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.rawValue) }
                .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    }
}
