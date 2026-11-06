package com.circaso.core.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform
import java.io.File
import kotlin.coroutines.resume

/** The platform's own location service (no Play services library): a fresh fix, or the last known one if it's recent. */
class AndroidLocation(private val context: Context) : LocationProvider {
    override val available = true

    @SuppressLint("MissingPermission")
    override suspend fun current(): GeoPoint? {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(context, fine) != PackageManager.PERMISSION_GRANTED && !PermissionBridge.request(fine)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        val fresh = withTimeoutOrNull(12_000) { fix(manager, provider) }
        val best = fresh ?: listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
            .minByOrNull { it.accuracy }
        return best?.let { GeoPoint(it.latitude, it.longitude, it.accuracy.toDouble()) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fix(manager: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancel = CancellationSignal()
            manager.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(context)) { if (cont.isActive) cont.resume(it) }
            cont.invokeOnCancellation { cancel.cancel() }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { manager.removeUpdates(this); if (cont.isActive) cont.resume(location) }
                @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            cont.invokeOnCancellation { manager.removeUpdates(listener) }
        }
    }
}

actual fun openMap(latitude: Double, longitude: Double, label: String?) {
    val activity = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get() }.getOrNull() ?: return
    val q = "$latitude,$longitude" + (label?.let { "(${Uri.encode(it)})" } ?: "")
    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$q"))) }
        .onFailure { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$latitude,$longitude"))) } }
}

actual fun setMoneySound(on: Boolean) = Unit

actual fun playMoneySound() {
    val context = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get()?.applicationContext }.getOrNull() ?: return
    // The sound ships in the app module's res/raw, so it is found by name.
    val id = context.resources.getIdentifier("money_received", "raw", context.packageName).takeIf { it != 0 } ?: return
    runCatching {
        android.media.MediaPlayer.create(context, id)?.apply {
            setOnCompletionListener { it.release() }
            start()
        }
    }
}

actual val canExitApp: Boolean = true

/** Sends the app to the background, as the system's own Back does on Android 12+, so coming back is instant. */
actual fun exitApp(): Boolean {
    val activity = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get() }.getOrNull() ?: return false
    activity.moveTaskToBack(true)
    return true
}

actual fun openInSystem(url: String) {
    val activity = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get() }.getOrNull() ?: return
    // BROWSABLE: only activities that agree to be opened from a link (a browser, the dialer, a mail app).
    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
}

actual fun openAppSettings() {
    val activity = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get() }.getOrNull() ?: return
    runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))) }
}

/** Decrypted media in the app's private files (never on shared storage, never backed up). */
class AndroidMediaFiles(context: Context) : MediaFiles {
    private val dir = File(context.filesDir, "media")
    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) { File(dir, name).takeIf { it.exists() }?.readBytes() }
    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) { dir.mkdirs(); File(dir, name).writeBytes(bytes) }
    override suspend fun clear() { withContext(Dispatchers.IO) { dir.deleteRecursively() } }
    override suspend fun path(name: String): String? = File(dir, name).takeIf { it.exists() }?.absolutePath
    override suspend fun delete(name: String) { withContext(Dispatchers.IO) { File(dir, name).delete() } }
}
