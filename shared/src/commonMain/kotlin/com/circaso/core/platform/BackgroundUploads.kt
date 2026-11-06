package com.circaso.core.platform

import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** What is uploading, for the notification that shows while it carries on in the background. */
enum class UploadKind(val sending: String, val failed: String, private val toWhom: Boolean = false) {
    Moment("Posting your moment", "Your moment didn't post"),
    Photo("Sending a photo", "A photo didn't send", toWhom = true),
    Video("Sending a video", "A video didn't send", toWhom = true),
    VoiceNote("Sending a voice note", "A voice note didn't send", toWhom = true),
    ProfilePhoto("Updating your profile photo", "Your profile photo didn't update"),
    ;

    /** "Sending a video to Amina", or [sending] when there's no name (or the kind has no one it goes to). */
    fun sendingTo(label: String?): String = if (toWhom && !label.isNullOrBlank()) "$sending to $label" else sending
}

/**
 * The bytes of the upload [BackgroundUploads.keep] is running, reported by [putFile] from inside it: the platform
 * turns them into the notification's percent. Carried in the coroutine context so no caller has to pass it along.
 */
class UploadProgress(val report: (sent: Long, total: Long) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<UploadProgress>
}

/**
 * Every file upload keeps going when the person leaves Circaso mid-upload (Pasaka, 2026-11-03: "flawless UX").
 * Android runs a foreground service (type `dataSync`) with a quiet "Sending…" notification while anything uploads;
 * iOS asks for background time and sends the file itself through a background URL session, which the system
 * finishes even if Circaso is suspended. A failure the person must act on is told by a notification when they are
 * not looking at Circaso; a lost connection is not a failure (the outbox waits and retries, offline-first.md).
 */
interface BackgroundUploads {
    /**
     * Runs [work] (preparing the file, the upload and the calls that finish it) as background work the system lets
     * finish. [label] is who it goes to ("Amina"), for "Sending a video to Amina…".
     */
    suspend fun <T> keep(kind: UploadKind, label: String? = null, work: suspend () -> T): T

    /**
     * PUTs [bytes] to a pre-signed storage URL through the platform's own uploader where that carries on without the
     * app (iOS). Null: this platform has none, use the app's HTTP client. [onProgress] gets (bytes sent, bytes in
     * all) as the body goes up, from any thread.
     */
    suspend fun put(url: String, headers: Map<String, String>, bytes: ByteArray, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): Boolean? = null

    /**
     * True when the platform's own uploader already finished a PUT to [url] while Circaso wasn't running to hear it
     * (iOS: the background session carried on after Circaso was closed), so the job goes straight to completing it.
     */
    suspend fun finished(url: String): Boolean = false

    /**
     * An upload stopped for good (the person must send it again): told when Circaso isn't on screen. A chat's photo,
     * video or voice note names its [chatId] and row ([localId]), so the tap opens the chat at it.
     */
    fun failed(kind: UploadKind, chatId: String? = null, localId: Long? = null) {}
}

object NoBackgroundUploads : BackgroundUploads {
    override suspend fun <T> keep(kind: UploadKind, label: String?, work: suspend () -> T): T = work()
}

/**
 * One PUT to pre-signed storage: the platform's background uploader when there is one, else [client]. [onProgress]
 * reports (bytes sent, bytes in all) as it goes, for a determinate progress ring (Moments M6); it may be called from
 * any thread and should only publish the numbers.
 */
suspend fun BackgroundUploads.putFile(
    client: HttpClient, url: String, headers: Map<String, String>, bytes: ByteArray,
    onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
): Boolean {
    val sink = currentCoroutineContext()[UploadProgress]
    val progress: (Long, Long) -> Unit = { sent, total -> onProgress(sent, total); sink?.report(sent, total) }
    put(url, headers, bytes, progress)?.let { return it }
    return try {
        client.put(url) {
            headers.forEach { (k, v) -> header(k, v) }
            setBody(bytes)
            onUpload { sent, total -> progress(sent, total ?: bytes.size.toLong()) }
        }.status.isSuccess()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }
}

/** Where tapping an "it didn't send" notification lands: the chat at the message, my moments, or my profile. */
fun UploadKind.failedDestination(chatId: String?, localId: Long?): com.circaso.core.notifications.NotificationDestination = when {
    this == UploadKind.Moment -> com.circaso.core.notifications.NotificationDestination.Moment(null)
    this == UploadKind.ProfilePhoto -> com.circaso.core.notifications.NotificationDestination.Profile
    chatId != null -> com.circaso.core.notifications.NotificationDestination.Chat(chatId, localId = localId)
    else -> com.circaso.core.notifications.NotificationDestination.Inbox
}
