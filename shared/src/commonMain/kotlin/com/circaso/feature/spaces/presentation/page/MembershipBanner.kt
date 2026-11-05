package com.circaso.feature.spaces.presentation.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.MembershipState
import com.circaso.feature.spaces.domain.PricePeriod
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.presentation.shortDate

/** What the banner says, or null when there is nothing to renew (SM3 reminder, SM4 grace). */
fun membershipBannerText(space: Space, now: Long): Pair<String, Boolean>? {
    val m = space.me.membership ?: return null
    if (!space.me.isMember || m.complimentary || m.period == PricePeriod.OneTime) return null
    return when {
        m.state == MembershipState.Grace -> {
            val ended = m.paidUntil?.let { " on ${shortDate(it)}" }.orEmpty()
            val until = m.graceUntil?.let { " until ${shortDate(it)}" }.orEmpty()
            "Your membership ended$ended. You can still read and post$until." to true
        }
        m.state == MembershipState.Active && m.renewFrom != null && m.renewFrom <= now && m.paidUntil != null ->
            "Your membership ends on ${shortDate(m.paidUntil)}." to false
        else -> null
    }
}

/**
 * SM3 renewal reminder and SM4 grace: the warning banner a members-only space shows its paying member, on the space
 * page and in the space chat. Amber (the only warning colour), a clock before the end and a warning in grace, with
 * Renew opening the renewal card. Nothing is ever deducted. Draws nothing when there is nothing to renew.
 */
@Composable
fun MembershipBanner(space: Space, now: Long, onRenew: () -> Unit, modifier: Modifier = Modifier) {
    val (text, grace) = membershipBannerText(space, now) ?: return
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        modifier.fillMaxWidth().glass(CircasoShapes.md).padding(start = CircasoSpacing.s4, end = CircasoSpacing.s2, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircasoIconImage(if (grace) CircasoIcon.Warning else CircasoIcon.Clock, c.warning, Modifier.size(18.dp))
        BasicText(text, Modifier.weight(1f).padding(vertical = 4.dp), style = t.subhead.copy(color = c.ink))
        BasicText(
            "Renew",
            Modifier.heightIn(min = 44.dp).pressable(onClick = onRenew).padding(horizontal = CircasoSpacing.s2, vertical = 12.dp),
            style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold),
        )
    }
}
