package com.circaso.core.platform

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/** What is uploading, for the notification that shows while it carries on in the background. */
enum class UploadKind(val sending: String, val failed: String) {
    Moment("Posting your moment", "Your moment didn't post"),
    Photo("Sending a photo", "A photo didn't send"),
    Video("Sending a video", "A video didn't send"),
    VoiceNote("Sending a voice note", "A voice note didn't send"),
    ProfilePhoto("Updating your profile photo", "Your profile photo didn't update"),
}

/**
 * Every file upload keeps going when the person leaves Circaso mid-upload (Pasaka, 2026-11-03: "flawless UX").
 * Android runs a foreground service (type `dataSync`) with a quiet "Sending…" notification while anything uploads;
 * iOS asks for background time and sends the file itself through a background URL session, which the system
 * finishes even if Circaso is suspended. A failure the person must act on is told by a notification when they are
 * not looking at Circaso; a lost connection is not a failure (the outbox waits and retries, offline-first.md).
 */
interface BackgroundUploads {
    /** Runs [work] (the upload and the calls that finish it) as background work the system lets finish. */
    suspend fun <T> keep(kind: UploadKind, work: suspend () -> T): T

    /**
     * PUTs [bytes] to a pre-signed storage URL through the platform's own uploader where that carries on without the
     * app (iOS). Null: this platform has none, use the app's HTTP client.
     */
    suspend fun put(url: String, headers: Map<String, String>, bytes: ByteArray): Boolean? = null

    /** An upload stopped for good (the person must send it again): told when Circaso isn't on screen. */
    fun failed(kind: UploadKind) {}
}

object NoBackgroundUploads : BackgroundUploads {
    override suspend fun <T> keep(kind: UploadKind, work: suspend () -> T): T = work()
}

/** One PUT to pre-signed storage: the platform's background uploader when there is one, else [client]. */
suspend fun BackgroundUploads.putFile(client: HttpClient, url: String, headers: Map<String, String>, bytes: ByteArray): Boolean {
    put(url, headers, bytes)?.let { return it }
    return try {
        client.put(url) { headers.forEach { (k, v) -> header(k, v) }; setBody(bytes) }.status.isSuccess()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }
}
