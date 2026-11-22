package com.circaso.core.platform

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import kotlin.coroutines.resume

// The Custom Tabs protocol's extras, so a browser that speaks it (Chrome, and most others) opens the page as a tab
// inside Circaso's task with a close button, instead of sending the person off to the full browser. Sent by hand so
// the shared module needs no library for one intent; a browser that doesn't know them opens the page as usual.
private const val CustomTabSession = "android.support.customtabs.extra.SESSION"
private const val CustomTabShowTitle = "android.support.customtabs.extra.TITLE_VISIBILITY"
private const val CustomTabShareState = "androidx.browser.customtabs.extra.SHARE_STATE"
private const val ShareOff = 2

actual suspend fun openInSystemBrowserAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get() }.getOrNull() ?: return@withContext false
    val link = runCatching { Uri.parse(url.trim()) }.getOrNull()?.takeIf { it.scheme == "https" } ?: return@withContext false
    val intent = Intent(Intent.ACTION_VIEW, link).addCategory(Intent.CATEGORY_BROWSABLE).putExtras(
        Bundle().apply {
            putBinder(CustomTabSession, null)
            putInt(CustomTabShowTitle, 1)
            // The link opens one person's identity check: the tab offers no "Share".
            putInt(CustomTabShareState, ShareOff)
        },
    )
    suspendCancellableCoroutine { continuation ->
        val app = activity.application
        // Back in Circaso: the activity that opened the tab is in front again.
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            private var left = false
            override fun onActivityPaused(a: Activity) { if (a === activity) left = true }
            override fun onActivityResumed(a: Activity) {
                if (a === activity && left) { app.unregisterActivityLifecycleCallbacks(this); if (continuation.isActive) continuation.resume(true) }
            }
            override fun onActivityDestroyed(a: Activity) {
                if (a === activity) { app.unregisterActivityLifecycleCallbacks(this); if (continuation.isActive) continuation.resume(true) }
            }
            override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        continuation.invokeOnCancellation { app.unregisterActivityLifecycleCallbacks(callbacks) }
        val opened = runCatching { activity.startActivity(intent) }.isSuccess
        if (!opened) { app.unregisterActivityLifecycleCallbacks(callbacks); if (continuation.isActive) continuation.resume(false) }
    }
}
