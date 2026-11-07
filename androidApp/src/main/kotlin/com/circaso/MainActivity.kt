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

class MainActivity : ComponentActivity() {
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
        ImagePickerBridge.register(this)
        com.circaso.core.platform.MomentPickerBridge.register(this)
        com.circaso.core.platform.AndroidShareIntake.install(this)

        setContent {
            App()
        }
        shareHost()?.let(com.circaso.feature.share.domain.IncomingLinks::addHost)
        openCall(intent)
        // A tapped notification that started Circaso (cold start); not again when the activity is only recreated.
        if (savedInstanceState == null) openNotification(intent)
        if (savedInstanceState == null) openShareLink(intent) // not again when the activity is recreated
        // Photos, videos and files shared from another app's share sheet (not again when the activity is recreated).
        if (savedInstanceState == null) com.circaso.core.platform.AndroidShareIntake.handle(this, intent)
        debugUpload(intent)
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

    /** From the incoming-call notification (design H1): show that call's answer screen. */
    private fun openCall(intent: android.content.Intent?) {
        val callId = intent?.getStringExtra(com.circaso.core.calls.AndroidCallNotifier.ExtraCallId) ?: return
        com.circaso.di.openRingingCall(callId)
    }
}

private const val MaxSplashMillis = 1_500L

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}