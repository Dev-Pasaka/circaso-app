package com.circaso

import android.app.Application
import com.circaso.di.initKoinAndroid

class CircasoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoinAndroid(this)
        // The Circle SDK lives in its own module; the shared wallet only ever asks it to run a challenge.
        com.circaso.circlewallet.CircleWallet.install(this, com.circaso.di.currentActivityProvider())
    }
}
