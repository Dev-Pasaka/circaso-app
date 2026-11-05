package com.circaso.feature.spaces.presentation.chat

import com.circaso.core.common.AppError
import com.circaso.core.common.ErrorMessages
import com.circaso.feature.spaces.domain.SpaceMe
import com.circaso.feature.spaces.presentation.clockTime
import com.circaso.feature.spaces.presentation.shortDate

/** Words for the spaces errors this area meets, keyed on the code (never the server's message). */
internal fun spacesErrorText(error: AppError): String = ErrorMessages.forError(error, SpacesCopy)

internal val SpacesCopy = mapOf(
    "spaces.cannot_moderate" to "You can’t do that to someone with the same role as you or higher.",
    "spaces.not_admin" to "Only admins can do that.",
    "spaces.not_moderator" to "Only moderators and admins can do that.",
    "spaces.not_owner" to "Only the owner can do that.",
    "spaces.not_member" to "They’re not in this space any more.",
    "spaces.members_only" to "Only members can see this.",
    "spaces.post_not_found" to "That post isn’t there any more.",
    "spaces.report_not_found" to "Someone already handled that report.",
    "spaces.report_invalid" to "That report couldn’t be sent. Check the reason and try again.",
    "spaces.too_many_reports" to "You’ve sent a lot of reports today. Try again tomorrow.",
    "spaces.invite_invalid" to "Members-only spaces can’t invite. Share the link instead.",
    "spaces.request_not_found" to "That request was already answered or cancelled.",
    "spaces.banned" to "You can’t take part in this space.",
    "spaces.space_not_found" to "This space isn’t available.",
    "spaces.space_unavailable" to "This space isn’t available.",
    "spaces.unavailable" to "Spaces aren’t available yet.",
    "messaging.not_reachable" to "You can’t message the owner of this space.",
)


/** SH1e: "until 20:00 today", "until 4 Dec, 20:00", or "until an admin unmutes you". */
internal fun mutedUntilText(until: Long, now: Long): String = when {
    until >= SpaceMe.UntilUnmuted -> "until an admin unmutes you"
    com.circaso.core.platform.localDayIndex(until) == com.circaso.core.platform.localDayIndex(now) -> "until ${clockTime(until)} today"
    else -> "until ${shortDate(until)}, ${clockTime(until)}"
}
