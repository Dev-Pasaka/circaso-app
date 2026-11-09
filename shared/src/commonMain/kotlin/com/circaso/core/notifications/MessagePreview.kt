package com.circaso.core.notifications

import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.MessageStatus
import com.circaso.feature.messaging.data.repository.MediaDetails

/**
 * A received message's own words in a notification (WhatsApp-style): text as written; a photo, video, voice note,
 * document or location as its mark and what it is ("📷 Photo", "🎤 Voice message (0:12)"), with the caption or the
 * place's name when there is one. Null when there's nothing to say about it (a message this phone couldn't open, a
 * deleted one), so the notification falls back to "New message". Payment cards are money and worded as money
 * ([NotificationEvent.MoneyReceived]), never here.
 */
object MessagePreview {
    const val Photo = "📷 Photo"
    const val Video = "🎥 Video"
    const val Voice = "🎤 Voice message"
    const val Document = "📄 Document"
    const val Location = "📍 Location"
    const val LiveLocation = "📍 Live location"

    /** [kind], [text] and [status] are a message row's ([com.circaso.feature.messaging.data.local.MessageEntity]). */
    fun of(kind: String, text: String?, status: String = MessageStatus.Received): String? {
        if (status == MessageStatus.Undecryptable || status == MessageStatus.Deleted) return null
        return when (kind) {
            MessageKind.Image, MessageKind.Video, MessageKind.Voice, MessageKind.File, MessageKind.Location -> media(MediaDetails.read(text) ?: return null)
            MessageKind.Text -> text?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    fun media(d: MediaDetails): String {
        val caption = d.caption?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            d.kind == MediaDetails.Image -> caption?.let { "📷 $it" } ?: Photo
            d.kind == MediaDetails.Video -> caption?.let { "🎥 $it" } ?: (Video + duration(d.durationMs))
            d.kind == MediaDetails.Voice -> Voice + duration(d.durationMs)
            d.kind == MediaDetails.File -> (caption ?: d.label?.trim()?.takeIf { it.isNotEmpty() })?.let { "📄 $it" } ?: Document
            d.isLive -> LiveLocation
            else -> d.label?.trim()?.takeIf { it.isNotEmpty() }?.let { "📍 $it" } ?: Location
        }
    }

    private fun duration(ms: Long?): String {
        val seconds = (ms ?: return "") / 1000
        if (seconds <= 0) return ""
        return " (${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')})"
    }
}
