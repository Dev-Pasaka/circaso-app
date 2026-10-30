package com.circaso.core.platform

/** How a moment is written in the person's own time zone and locale. The platforms format; common code decides which. */
enum class LocalTimeStyle { Clock, Weekday, ShortDate }

expect fun formatLocalTime(epochMillis: Long, style: LocalTimeStyle): String

/** The local calendar day as a number that rises by one per day, for "today / yesterday / this week" decisions. */
expect fun localDayIndex(epochMillis: Long): Long

/** The chat list's meta column (design C1): "21:14" today, "Yesterday", "Mon" this week, else "12 Oct". */
fun chatListTime(epochMillis: Long, now: Long): String {
    val days = localDayIndex(now) - localDayIndex(epochMillis)
    return when {
        days <= 0 -> formatLocalTime(epochMillis, LocalTimeStyle.Clock)
        days == 1L -> "Yesterday"
        days < 7 -> formatLocalTime(epochMillis, LocalTimeStyle.Weekday)
        else -> formatLocalTime(epochMillis, LocalTimeStyle.ShortDate)
    }
}

/** The day separator inside a thread (design C2): "Today", "Yesterday", else the weekday or date. */
fun threadDayLabel(epochMillis: Long, now: Long): String {
    val days = localDayIndex(now) - localDayIndex(epochMillis)
    return when {
        days <= 0 -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> formatLocalTime(epochMillis, LocalTimeStyle.Weekday)
        else -> formatLocalTime(epochMillis, LocalTimeStyle.ShortDate)
    }
}
