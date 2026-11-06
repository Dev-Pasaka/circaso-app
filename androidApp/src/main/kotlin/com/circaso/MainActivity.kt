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

        setContent {
            App()
        }
        shareHost()?.let(com.circaso.feature.share.domain.IncomingLinks::addHost)
        openCall(intent)
        if (savedInstanceState == null) openShareLink(intent) // not again when the activity is recreated
        debugUpload(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openCall(intent)
        openShareLink(intent)
        debugUpload(intent)
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