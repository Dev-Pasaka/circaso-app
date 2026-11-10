package com.circaso.core.platform

import com.circaso.core.push.PushTokenRegistrar
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform
import kotlin.coroutines.resume

/**
 * Firebase Messaging on the Swift side (PushNotificationsImpl.swift, 2026-11-10): asks to show notifications, registers
 * with APNs and answers this phone's FCM token. The server already sends iPhones their message, payment and
 * missed-call pushes through FCM with an APNs block, so the FCM token is the only address it needs.
 */
interface IosPushApi {
    /** Asks for permission the first time, then the FCM token, or null while APNs hasn't given one yet. */
    fun fetchToken(completion: (String?) -> Unit)
}

object IosPushBridge {
    /** Null in a build without GoogleService-Info.plist: no push there, as before. */
    var api: IosPushApi? = null

    /** Firebase issued a token (first launch after APNs answers, or a refresh): the registrar sends it if signed in. */
    fun tokenRefreshed(token: String) {
        runCatching { KoinPlatform.getKoin().get<PushTokenRegistrar>().onNewToken(token) }
    }
}

/** This iPhone's FCM token. Null without Firebase, or until APNs answers (the refresh above sends it then). */
class IosPushTokenProvider : PushTokenProvider {
    override suspend fun token(): String? {
        val api = IosPushBridge.api ?: return null
        // The permission sheet waits for the person; the token itself follows APNs, which can take a while offline.
        return withTimeoutOrNull(120_000) {
            suspendCancellableCoroutine { continuation ->
                api.fetchToken { token -> if (continuation.isActive) continuation.resume(token) }
            }
        }
    }
}
