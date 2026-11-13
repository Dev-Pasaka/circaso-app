package com.circaso

import com.circaso.gallery.AndroidLaunchArgs

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.circaso.core.platform.ImagePickerBridge
import com.circaso.core.platform.PermissionBridge
import com.circaso.di.isStartupReady
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Google Play In-App Updates (Pasaka, 2026-11-08/09). Play is asked at launch, on every return to the app and every
    // 10 minutes while it's open. At launch a newer version starts downloading in the background (flexible); found
    // while the app is in use, the bar offers "Update" instead of a Play dialog. A release published with priority 4-5
    // (CI) asks for the update at once (immediate). Only builds installed from Play see updates.
    private val playUpdates by lazy { com.google.android.play.core.appupdate.AppUpdateManagerFactory.create(this) }
    private val updateProgress = com.google.android.play.core.install.InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            com.google.android.play.core.install.model.InstallStatus.DOWNLOADING -> {
                val total = state.totalBytesToDownload()
                val percent = if (total > 0) (state.bytesDownloaded() * 100 / total).toInt().coerceIn(0, 100) else null
                com.circaso.core.platform.AppUpdates.report(com.circaso.core.platform.AppUpdates.Status.Downloading(percent))
            }
            com.google.android.play.core.install.model.InstallStatus.DOWNLOADED ->
                com.circaso.core.platform.AppUpdates.report(com.circaso.core.platform.AppUpdates.Status.Ready { playUpdates.completeUpdate() })
            com.google.android.play.core.install.model.InstallStatus.FAILED,
            com.google.android.play.core.install.model.InstallStatus.CANCELED -> checkForUpdate(startFlexible = false)
            else -> Unit
        }
    }

    private fun startUpdate(info: com.google.android.play.core.appupdate.AppUpdateInfo, type: Int) {
        runCatching {
            playUpdates.startUpdateFlowForResult(info, this, com.google.android.play.core.appupdate.AppUpdateOptions.defaultOptions(type), UpdateRequestCode)
        }
    }

    /** Asks Play what it has. [startFlexible]: at launch a flexible update starts by itself; later the bar offers it. */
    private fun checkForUpdate(startFlexible: Boolean) {
        playUpdates.appUpdateInfo.addOnSuccessListener { info ->
            val immediate = com.google.android.play.core.install.model.AppUpdateType.IMMEDIATE
            val flexible = com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE
            val availability = info.updateAvailability()
            when {
                info.installStatus() == com.google.android.play.core.install.model.InstallStatus.DOWNLOADED ->
                    com.circaso.core.platform.AppUpdates.report(com.circaso.core.platform.AppUpdates.Status.Ready { playUpdates.completeUpdate() })
                info.installStatus() == com.google.android.play.core.install.model.InstallStatus.DOWNLOADING -> Unit // the listener reports progress
                // An immediate update the person left half way: Play requires it to be resumed.
                availability == com.google.android.play.core.install.model.UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> startUpdate(info, immediate)
                availability != com.google.android.play.core.install.model.UpdateAvailability.UPDATE_AVAILABLE ->
                    com.circaso.core.platform.AppUpdates.report(com.circaso.core.platform.AppUpdates.Status.None)
                info.updatePriority() >= ImmediateUpdatePriority && info.isUpdateTypeAllowed(immediate) -> startUpdate(info, immediate)
                info.isUpdateTypeAllowed(flexible) && startFlexible -> startUpdate(info, flexible)
                info.isUpdateTypeAllowed(flexible) ->
                    com.circaso.core.platform.AppUpdates.report(com.circaso.core.platform.AppUpdates.Status.Available { startUpdate(info, flexible) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkForUpdate(startFlexible = false)
    }

    override fun onDestroy() {
        playUpdates.unregisterListener(updateProgress)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hold the launch splash until the saved session and theme are read, but never longer than 1.5 s.
        val started = SystemClock.uptimeMillis()
        installSplashScreen().setKeepOnScreenCondition {
            !isStartupReady() && SystemClock.uptimeMillis() - started < MaxSplashMillis
        }
        enableEdgeToEdge()
        // Draw under the camera cut-out in every orientation, so landscape is as edge to edge as portrait (some
        // phones otherwise leave a black band down the side with the camera).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        }
        super.onCreate(savedInstanceState)
        // Debug previews (`--es preview <name>`): read before the first composition so the navigation sees them.
        intent?.extras?.let { bundle -> AndroidLaunchArgs.extras = bundle.keySet().mapNotNull { k -> bundle.getString(k)?.let { k to it } }.toMap() }
        PermissionBridge.register(this) // must happen while the activity is being created
        com.circaso.core.calls.ScreenCaptureBridge.register(this) // a call's Share screen prompt
        ImagePickerBridge.register(this)
        com.circaso.core.platform.MomentPickerBridge.register(this)
        com.circaso.core.platform.AndroidShareIntake.install(this)

        setContent {
            App()
        }
        // On its side the status bar steps aside; a swipe from the top shows it for a moment.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        immersiveOnItsSide(resources.configuration)
        showOverLockScreenDuringCalls()
        shareHost()?.let(com.circaso.feature.share.domain.IncomingLinks::addHost)
        openCall(intent)
        // A tapped notification that started Circaso (cold start); not again when the activity is only recreated.
        if (savedInstanceState == null) openNotification(intent)
        if (savedInstanceState == null) openShareLink(intent) // not again when the activity is recreated
        // Photos, videos and files shared from another app's share sheet (not again when the activity is recreated).
        if (savedInstanceState == null) com.circaso.core.platform.AndroidShareIntake.handle(this, intent)
        debugUpload(intent)
        playUpdates.registerListener(updateProgress)
        if (savedInstanceState == null) checkForUpdate(startFlexible = true) // at launch, not again when recreated
        // While Circaso is open, ask Play again every 10 minutes (onResume covers coming back to it).
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                while (true) {
                    kotlinx.coroutines.delay(UpdateCheckEveryMillis)
                    checkForUpdate(startFlexible = false)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openCall(intent)
        // Circaso was already running, in the background or on screen.
        openNotification(intent)
        openShareLink(intent)
        com.circaso.core.platform.AndroidShareIntake.handle(this, intent)
        debugUpload(intent)
    }

    /**
     * Tapping a notification opens where it points (Pasaka, 2026-11-06): the chat at the message or the payment's card,
     * the moment, Asha, Security… Circaso's own notifications carry the place in their extras; one the system showed
     * from a push carries the push's data. The navigation shows it once someone is signed in.
     */
    private fun openNotification(intent: android.content.Intent?) {
        if (intent == null || (intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        val bundle = intent.extras ?: return
        val extras = bundle.keySet().mapNotNull { k -> bundle.getString(k)?.let { k to it } }.toMap()
        // Once: a later configuration change or a launch from Recents must not open it again.
        if (com.circaso.core.notifications.NotificationTaps.openFrom(extras)) intent.replaceExtras(null as Bundle?)
    }

    /**
     * Debug builds only: `--es debugUpload <megabytes>` queues a dummy upload through the real pipeline (never posted,
     * see DebugUploadHandler), to prove on a phone that uploads survive leaving Circaso and the process dying.
     */
    private fun debugUpload(intent: android.content.Intent?) {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val megabytes = intent?.getStringExtra("debugUpload")?.toIntOrNull() ?: return
        intent.removeExtra("debugUpload") // once, not again on a configuration change
        com.circaso.di.queueDebugUpload(megabytes)
    }

    /**
     * A share link (App Links `https://<share host>/s/{code}` or `circaso://s/{code}`): handed to IncomingLinks, which
     * opens it once someone is signed in.
     */
    private fun openShareLink(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_VIEW) return
        val url = intent.dataString ?: return
        if (com.circaso.feature.share.domain.IncomingLinks.open(url)) intent.data = null
    }

    private fun shareHost(): String? =
        runCatching {
            packageManager.getActivityInfo(componentName, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.getString("com.circaso.SHARE_HOST")
        }.getOrNull()

    /** From the incoming-call notification (design H1): show that call's answer screen, or answer it (its Accept). */
    private fun openCall(intent: android.content.Intent?) {
        val callId = intent?.getStringExtra(com.circaso.core.calls.AndroidCallNotifier.ExtraCallId) ?: return
        val answer = intent.getBooleanExtra(com.circaso.core.calls.AndroidCallNotifier.ExtraAnswer, false)
        intent.removeExtra(com.circaso.core.calls.AndroidCallNotifier.ExtraAnswer) // answered once, never again on a re-create
        overLockScreen(true) // the full-screen intent may be opening us on a locked phone
        com.circaso.di.openRingingCall(callId, answer)
    }

    /**
     * While a call rings or is on, Circaso shows over the lock screen and wakes the screen, as a phone call does;
     * the rest of the app stays behind the lock once the call ends.
     */
    private fun showOverLockScreenDuringCalls() {
        lifecycleScope.launch { com.circaso.di.callOnScreen().distinctUntilChanged().collect(::overLockScreen) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        immersiveOnItsSide(newConfig) // rotation doesn't recreate the activity (configChanges)
    }

    /**
     * A phone on its side is fully immersive, as an iPhone is in landscape: the status bar steps aside (a swipe from
     * the top brings it back for a moment) and the content gets the whole height. Tablets and portrait keep it. Short
     * means the same as WindowLayout.short: under 480 dp tall.
     */
    private fun immersiveOnItsSide(config: android.content.res.Configuration) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        val onItsSide = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && config.screenHeightDp < 480
        if (onItsSide) controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        else controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
    }

    private fun overLockScreen(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
}

private const val MaxSplashMillis = 1_500L

/** A release published with this in-app update priority or higher (0–5, set by CI) must be installed before use. */
private const val ImmediateUpdatePriority = 4

/** Request code for Play's update screen (its result needs no handling: a declined flexible update simply waits). */
private const val UpdateRequestCode = 4_201

/** How often Play is asked again while Circaso stays open. */
private const val UpdateCheckEveryMillis = 10 * 60 * 1000L

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}