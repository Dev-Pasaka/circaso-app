package com.circaso.feature.assistant.presentation

import com.circaso.core.common.AppError
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.platform.localDayIndex
import com.circaso.feature.assistant.domain.AshaUsage
import com.circaso.feature.assistant.domain.UsageMeter

/** Which page of Asha's menu sheet is showing (the ⋮ / ellipsis button in her chat's header). */
enum class AshaSheet {
    /** New chat, the latest chats, usage at a glance, settings. */
    Menu,
    /** Every conversation, newest first; long-press one to delete it. */
    Recents,
    /** This period's use against the plan's allowance (A7). */
    Usage,
}

/** Asha's usage as the menu shows it. Read when the menu opens; online only. */
sealed interface UsageUi {
    data object Loading : UsageUi
    data class Loaded(val usage: AshaUsage) : UsageUi
    /** The server has no usage route yet (404): a calm note, not an error. */
    data object Unavailable : UsageUi
    /** Usage needs the server. */
    data object Offline : UsageUi
    data class Failed(val message: String) : UsageUi

    companion object {
        fun from(error: AppError): UsageUi = when {
            error is AppError.Api && error.httpStatus == 404 -> Unavailable
            error is AppError.Unexpected && error.httpStatus == 404 -> Unavailable
            error == AppError.Network -> Offline
            else -> Failed(ErrorMessages.forError(error))
        }
    }
}

/** How many of the latest chats the menu lists before "See all". */
const val MenuRecentCount = 4

/**
 * When a conversation was last active, as the recents list says it: "Just now", "12 min ago", "3 h ago" today, then
 * "Yesterday", the weekday this week, else the date.
 */
fun relativeTime(epochMillis: Long, now: Long): String {
    val minutes = (now - epochMillis) / 60_000
    val days = localDayIndex(now) - localDayIndex(epochMillis)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 && days <= 0 -> "$minutes min ago"
        days <= 0 -> "${minutes / 60} h ago"
        days == 1L -> "Yesterday"
        days < 7 -> formatLocalTime(epochMillis, LocalTimeStyle.Weekday)
        else -> formatLocalTime(epochMillis, LocalTimeStyle.ShortDate)
    }
}

/** "Resets in 5 h" / "Resets in 3 days" from the server's `resetsAt`; null when it sent none or it can't be read. */
fun resetsLabel(resetsAt: String?, now: Long): String? {
    val at = resetsAt?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: return null
    val minutes = ((at - now) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Resets now"
        minutes < 60 -> "Resets in $minutes min"
        minutes < 48 * 60 -> "Resets in ${(minutes + 59) / 60} h"
        else -> "Resets in ${(minutes + 1439) / 1440} days"
    }
}

/** "12 of 50 messages" / "12 messages" when there is no cap. */
fun messagesLine(m: UsageMeter): String = if (m.limit == null) "${m.used} ${plural(m.used, "message")}" else "${m.used} of ${m.limit} ${plural(m.limit, "message")}"

/** Voice time in minutes, rounded up so a short call never reads as zero: "3 of 10 min". */
fun voiceLine(m: UsageMeter): String {
    fun min(s: Int) = (s + 59) / 60
    return if (m.limit == null) "${min(m.used)} min" else "${min(m.used)} of ${min(m.limit)} min"
}

/** The plan's name as people read it ("free" → "Free"). */
fun planName(plan: String): String = plan.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }.ifEmpty { "Free" }

private fun plural(n: Int, word: String) = if (n == 1) word else word + "s"
