package com.circaso.core.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.circaso.feature.messaging.data.repository.LiveLocations
import com.circaso.feature.messaging.data.repository.LiveTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/** Starts and stops [LiveLocationService]; the shares themselves are [LiveLocations]'. */
class AndroidLiveTracker(private val context: Context) : LiveTracker {
    override fun start(chats: Int) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) return
        runCatching { ContextCompat.startForegroundService(context, Intent(context, LiveLocationService::class.java).putExtra(LiveLocationService.Chats, chats)) }
    }

    override fun stop() {
        context.stopService(Intent(context, LiveLocationService::class.java))
    }
}

/**
 * Live location in the background (WhatsApp-style): a foreground service of type `location` with an ongoing
 * "Sharing live location" notification and a Stop button, so sharing is never silent. It needs only the
 * while-in-use permission because it always starts while Circaso is open (the person just tapped Share). Fixes come
 * from the platform's own location service about every 10 seconds and go to [LiveLocations.onFix], which
 * decides what to send and stops this service when no share is left.
 */
class LiveLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var manager: LocationManager? = null
    private var ticking: kotlinx.coroutines.Job? = null
    private val listener = LocationListener { fix: Location ->
        val point = GeoPoint(fix.latitude, fix.longitude, fix.accuracy.toDouble())
        scope.launch { runCatching { live()?.onFix(point) } }
    }

    private fun live(): LiveLocations? = runCatching { KoinPlatform.getKoin().get<LiveLocations>() }.getOrNull()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            scope.launch { runCatching { live()?.stopAll() }; stopSelf() }
            return START_NOT_STICKY
        }
        if (intent == null) {
            // Restarted by Android after it stopped the process: carry on only if a share is still running.
            scope.launch { runCatching { live()?.refreshTracker() } }
        }
        val chats = intent?.getIntExtra(Chats, 1) ?: 1
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching {
            ServiceCompat.startForeground(this, NotificationId, notification(chats), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        }
        if (started.isFailure) { stopSelf(); return START_NOT_STICKY }
        listen()
        if (ticking == null) ticking = scope.launch { while (true) { kotlinx.coroutines.delay(TickMillis); runCatching { live()?.tick() } } }
        return START_STICKY
    }

    private fun notification(chats: Int): android.app.Notification {
        val notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(NotificationChannel(ChannelId, "Live location", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while you share your live location" })
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val tap = open?.let { PendingIntent.getActivity(this, 11, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val stop = PendingIntent.getService(this, 12, Intent(this, LiveLocationService::class.java).setAction(ActionStop), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing live location")
            .setContentText(if (chats == 1) "In 1 chat. Tap to open Circaso." else "In $chats chats. Tap to open Circaso.")
            .setOngoing(true)
            .setContentIntent(tap)
            .addAction(0, "Stop sharing", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        if (manager != null) return
        val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = m
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
            .forEach { provider -> runCatching { m.requestLocationUpdates(provider, IntervalMillis, IntervalMeters, listener, Looper.getMainLooper()) } }
    }

    override fun onDestroy() {
        manager?.removeUpdates(listener)
        manager = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val Chats = "chats"
        const val ActionStop = "com.circaso.live.STOP"
        const val ChannelId = "live_location"
        const val NotificationId = 4402
        private const val IntervalMillis = 10_000L
        /** Every fix, moving or not: LiveLocations decides what is worth sending (and a standing phone still says so). */
        private const val IntervalMeters = 0f
        private const val TickMillis = 30_000L
    }
}
