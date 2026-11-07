package com.circaso.feature.mediacompose

import com.circaso.core.platform.MomentMediaMaxBytes
import com.circaso.core.platform.MomentPickMax
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * One item shared into Circaso from another app (the system share sheet), already copied into Circaso's own storage
 * at [path] so it outlives the other app's permission. [size] in bytes; -1 when unknown.
 */
@Serializable
data class SharedFile(val path: String, val name: String, val mime: String, val size: Long)

/** What a shared item becomes on the review: a photo, a video, or a document. */
enum class SharedKind { Photo, Video, Document }

/** A photo or a video only when it is one the composer can show; anything else (a GIF, a HEIC sticker, a PDF) is a document. */
fun sharedKind(mime: String, name: String): SharedKind {
    val m = mime.lowercase()
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        m in setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif") || (m.isBlank() && ext in setOf("jpg", "jpeg", "png", "webp", "heic")) -> SharedKind.Photo
        m.startsWith("video/") || (m.isBlank() && ext in setOf("mp4", "mov", "m4v", "3gp")) -> SharedKind.Video
        else -> SharedKind.Document
    }
}

/**
 * What can be done with a share before any destination is picked: which items fit (at most [MomentPickMax], each
 * under the attachment limit), which were left out and why, and whether it can be a moment (photos and videos only).
 */
data class ShareCheck(val usable: List<SharedFile>, val tooLarge: List<SharedFile>, val overCount: Int) {
    val canBeMoment: Boolean get() = usable.isNotEmpty() && usable.all { sharedKind(it.mime, it.name) != SharedKind.Document }

    /** The polite line for what was left out, or null when everything fits. */
    val notice: String?
        get() = listOfNotNull(
            tooLarge.takeIf { it.isNotEmpty() }?.let {
                if (it.size == 1) "${it[0].name} is over 32 MB, so it can’t be sent." else "${it.size} items are over 32 MB, so they can’t be sent."
            },
            overCount.takeIf { it > 0 }?.let { "You can send up to $MomentPickMax at a time; the first $MomentPickMax are here." },
        ).joinToString(" ").ifBlank { null }
}

fun checkShare(files: List<SharedFile>, maxBytes: Long = MomentMediaMaxBytes, maxItems: Int = MomentPickMax): ShareCheck {
    val (big, fit) = files.partition { it.size > maxBytes }
    return ShareCheck(fit.take(maxItems), big, (fit.size - maxItems).coerceAtLeast(0))
}

/** Where a share goes: one moment, or one or more chats (each gets the same items). */
sealed interface ShareDestination {
    data object Moment : ShareDestination
    data class Chats(val chatIds: List<String>) : ShareDestination
}

/**
 * Items shared into Circaso from other apps (Android's share sheet, the iOS Share Extension). The platform copies the
 * files into Circaso's storage and hands them here; they wait in [pending] until someone is signed in, then the
 * navigation opens the destination picker, and the composer [take]s them. An object, not a Koin binding, because the
 * system can hand a share over before Koin has started. [onChange] lets the platform keep a copy of the list, so a
 * share survives Circaso being closed during sign-in.
 */
object IncomingShares {
    private val _pending = MutableStateFlow<List<SharedFile>?>(null)
    val pending: StateFlow<List<SharedFile>?> = _pending.asStateFlow()

    /** Set by the platform: called with the list whenever it changes (null when it is taken or let go). */
    var onChange: (List<SharedFile>?) -> Unit = {}

    /** A new share replaces one still waiting (the person shared again). */
    fun offer(files: List<SharedFile>) {
        if (files.isEmpty()) return
        _pending.value = files
        onChange(files)
    }

    /** The items, once, for the composer that sends them. */
    fun take(): List<SharedFile>? = _pending.value.also { if (it != null) { _pending.value = null; onChange(null) } }

    /** The person closed the picker without sending. */
    fun clear() { _pending.value = null; onChange(null) }
}

/**
 * The name a shared item is kept under: its own name with no folders or characters a file name can't hold, else
 * "Shared 1.jpg" from its type. Never empty, never starting with a dot.
 */
fun shareFileName(name: String?, mime: String, index: Int): String {
    val clean = name?.substringAfterLast('/')?.replace(Regex("[\\\\:*?\"<>|\\u0000-\\u001f]"), "_")?.trim()?.trimStart('.')?.take(120)
    if (!clean.isNullOrBlank()) return clean
    val ext = when {
        mime == "image/jpeg" -> ".jpg"; mime == "image/png" -> ".png"; mime.startsWith("video/") -> ".mp4"; mime == "application/pdf" -> ".pdf"
        else -> ""
    }
    return "Shared ${index + 1}$ext"
}
