package com.circaso.core.platform

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970

private fun date(epochMillis: Long) = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)

actual fun formatLocalTime(epochMillis: Long, style: LocalTimeStyle): String {
    val formatter = NSDateFormatter()
    when (style) {
        LocalTimeStyle.Clock -> { formatter.dateStyle = NSDateFormatterNoStyle; formatter.timeStyle = NSDateFormatterShortStyle }
        LocalTimeStyle.Weekday -> formatter.setLocalizedDateFormatFromTemplate("EEE")
        LocalTimeStyle.ShortDate -> formatter.setLocalizedDateFormatFromTemplate("d MMM")
    }
    return formatter.stringFromDate(date(epochMillis))
}

actual fun localDayIndex(epochMillis: Long): Long {
    val calendar = NSCalendar.currentCalendar
    val start = calendar.startOfDayForDate(date(epochMillis))
    // Days since the epoch in the local calendar: the start of this day, measured in whole days from 1970 in local time.
    val epochStart = calendar.startOfDayForDate(NSDate.dateWithTimeIntervalSince1970(0.0))
    return calendar.components(NSCalendarUnitDay, epochStart, start, 0u).day
}
