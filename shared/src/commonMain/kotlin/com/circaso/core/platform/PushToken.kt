package com.circaso.core.platform

/** This phone's push address (FCM on Android, APNs on iOS), or null when the platform has none yet. */
interface PushTokenProvider {
    suspend fun token(): String?
}

/** Used where push is not set up yet: the phone has no address to give, so nothing is sent. */
object NoPushToken : PushTokenProvider {
    override suspend fun token(): String? = null
}
