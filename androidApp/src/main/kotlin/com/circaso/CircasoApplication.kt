package com.circaso

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import com.circaso.di.initKoinAndroid

class CircasoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoinAndroid(this)
        // The Circle SDK lives in its own module; the shared wallet only ever asks it to run a challenge.
        com.circaso.circlewallet.CircleWallet.install(this, com.circaso.di.currentActivityProvider())
        hideCircleScreensFromCapture()
    }

    /**
     * The PIN and security questions are Circle's own screens. With screen sharing in calls (2026-11-09) a PIN typed
     * mid-call would otherwise go out to everyone watching: Circle's screens are secure, so a share, a screenshot or
     * the recents thumbnail shows them blank.
     */
    private fun hideCircleScreensFromCapture() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity.javaClass.name.startsWith(CirclePackage)) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private companion object {
        const val CirclePackage = "circle.programmablewallet."
    }
}
