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
        openCall(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openCall(intent)
    }

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