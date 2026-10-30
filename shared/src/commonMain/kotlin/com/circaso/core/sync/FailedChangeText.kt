package com.circaso.core.sync

import com.circaso.core.common.AppError
import com.circaso.core.common.ErrorMessages

/** A queued change the server refused or that waited too long, in words a person understands. */
data class FailedChange(val id: Long, val title: String, val reason: String)

object FailedChangeText {
    fun title(type: String): String = when (type) {
        "profile.update" -> "Profile change"
        "contact.remove" -> "Removing a contact"
        "block.set" -> "Blocking someone"
        "block.clear" -> "Unblocking someone"
        "device.push_token" -> "Notification setup"
        "asha.delete_message", "asha.delete_all" -> "Deleting Asha messages"
        "chat.message" -> "A message"
        else -> "A change"
    }

    fun reason(code: String?): String = when (code) {
        OutboxProcessor.Stale -> "It waited too long to send, so it needs your OK before it goes."
        OutboxProcessor.UnknownType -> "This version of Circaso can't send it."
        null, "" -> "It couldn't be sent."
        else -> ErrorMessages.forError(AppError.Api(code, 0, null, null, emptyMap()))
    }

    fun from(item: OutboxEntity) = FailedChange(item.id, title(item.type), reason(item.lastErrorCode))
}
