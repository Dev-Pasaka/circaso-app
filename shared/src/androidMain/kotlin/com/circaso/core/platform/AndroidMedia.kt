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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

    private fun allowed(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun manager() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** The providers to listen to: the system's fused one where it exists, else GPS (precise access only) and network. */
    private fun providers(manager: LocationManager): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { manager.hasProvider(LocationManager.FUSED_PROVIDER) && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false)) {
            return listOf(LocationManager.FUSED_PROVIDER)
        }
        return listOfNotNull(LocationManager.GPS_PROVIDER.takeIf { allowed(Manifest.permission.ACCESS_FINE_LOCATION) }, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    override fun access(): LocationAccess = when {
        // "Approximate" is enough for a distance; the screen says "about" when the fix is rough.
        !allowed(Manifest.permission.ACCESS_FINE_LOCATION) && !allowed(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.NotAsked
        providers(manager()).isEmpty() -> LocationAccess.Off
        else -> LocationAccess.Granted
    }

    override suspend fun requestAccess(): LocationAccess {
        if (access() == LocationAccess.NotAsked) {
            // Both together, so Android 12+ offers "Precise" and "Approximate".
            val answer = PermissionBridge.requestAll(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            if (answer.values.none { it }) return LocationAccess.Denied
        }
        return access()
    }

    /**
     * Fixes every few seconds while collected, starting with the last known one when it is recent. Balanced for a
     * map that is being looked at: [NearbyIntervalMs] apart and only after [NearbyMinMoveMeters] of movement, and
     * the listener is removed the moment collection stops.
     */
    @SuppressLint("MissingPermission")
    override fun updates(): Flow<GeoPoint> = callbackFlow {
        val manager = manager()
        var best: Location? = null
        fun offer(l: Location) {
            val b = best
            // A rougher fix (network after GPS) doesn't replace a good recent one: the pin would jump about.
            if (b != null && l.accuracy > b.accuracy * 2 && l.time - b.time < 20_000) return
            best = l
            trySend(GeoPoint(l.latitude, l.longitude, l.accuracy.toDouble()))
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = offer(location)
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        val providers = providers(manager)
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }
            .minByOrNull { it.accuracy }?.let(::offer)
        providers.forEach { runCatching { manager.requestLocationUpdates(it, NearbyIntervalMs, NearbyMinMoveMeters, listener, Looper.getMainLooper()) } }
        awaitClose { runCatching { manager.removeUpdates(listener) } }
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

/** The map's "you are here": a fix about every five seconds, and only once the phone has moved a few metres. */
private const val NearbyIntervalMs = 5_000L
private const val NearbyMinMoveMeters = 5f

actual fun openDirections(latitude: Double, longitude: Double) {
    val activity = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get() }.getOrNull() ?: return
    val directions = Uri.parse(googleDirectionsUrl(latitude, longitude))
    // Google Maps when it's installed; else any maps app at the place (its own Directions is one tap away); else the
    // directions page in the browser.
    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, directions).setPackage("com.google.android.apps.maps")) }
        .recoverCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$latitude,$longitude"))) }
        .recoverCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, directions)) }
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

    override suspend fun size(name: String): Long? = withContext(Dispatchers.IO) { File(dir, name).takeIf { it.exists() }?.length() }

    override suspend fun readAt(name: String, offset: Long, count: Int): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(dir, name).takeIf { it.exists() } ?: return@withContext null
        java.io.RandomAccessFile(file, "r").use { raf ->
            val n = (raf.length() - offset).coerceIn(0, count.toLong()).toInt()
            ByteArray(n).also { if (n > 0) { raf.seek(offset); raf.readFully(it) } }
        }
    }

    override suspend fun append(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        java.io.FileOutputStream(File(dir, name), true).use { it.write(bytes) }
    }

    override suspend fun move(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        val source = File(dir, from)
        if (!source.exists()) return@withContext false
        val target = File(dir, to)
        target.delete()
        source.renameTo(target) || (runCatching { source.copyTo(target, overwrite = true); source.delete() }.isSuccess)
    }

    override suspend fun copyIn(path: String, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            // Streamed through a 256 KB buffer: a 100 MB video never sits in memory.
            File(path).inputStream().use { input -> File(dir, name).outputStream().use { input.copyTo(it, 256 * 1024) } }
            true
        }.getOrDefault(false)
    }
}

actual fun fileSizeAt(path: String): Long? = File(path).takeIf { it.isFile }?.length()
