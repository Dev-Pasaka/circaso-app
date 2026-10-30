package com.circaso.core.platform

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

actual fun formatLocalTime(epochMillis: Long, style: LocalTimeStyle): String {
    val local = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return when (style) {
        LocalTimeStyle.Clock -> local.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
        LocalTimeStyle.Weekday -> local.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        LocalTimeStyle.ShortDate -> local.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    }
}

actual fun localDayIndex(epochMillis: Long): Long = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
