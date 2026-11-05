package com.circaso.feature.spaces.presentation.create

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.core.common.AppError
import com.circaso.core.common.ErrorMessages
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.presentation.SpaceTile
import com.circaso.feature.wallet.presentation.RadioMark

// Parts of the create and settings screens (SN1–SN8b): the step bar, option cards, topic chips and the wallet badge.

/** SN1–SN3's progress bar (canvas `.steps`): one segment per step, the done ones in ink. */
@Composable
fun StepsBar(step: Int, total: Int = 3, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "Step $step of $total"
            progressBarRangeInfo = ProgressBarRangeInfo(step.toFloat(), 1f..total.toFloat(), total - 1)
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total) { i -> Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i < step) c.ink else c.fillStrong)) }
    }
}

/** A pill chip (canvas `.chip` / `.chip-on`): a topic, a handle suggestion, a role. */
@Composable
fun SpaceChip(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, role: Role = Role.RadioButton, trailing: CircasoIcon? = null, label: String? = null, enabled: Boolean = true) {
    val c = CircasoTheme.colors
    Row(
        modifier.heightIn(min = 34.dp).clip(CircasoShapes.pill).background(if (on) c.action else c.fill)
            .pressable(enabled = enabled, role = role, onClick = onClick)
            .semantics(mergeDescendants = true) { if (role == Role.RadioButton) selected = on; label?.let { contentDescription = it } }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(text, style = CircasoTheme.typography.subhead.copy(color = if (on) c.onAction else c.ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium))
        trailing?.let { CircasoIconImage(it, if (on) c.onAction else c.inkSecondary, Modifier.size(14.dp)) }
    }
}

/** The one topic list (decision), as radio chips that wrap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicChips(selected: SpaceTopic?, onSelect: (SpaceTopic) -> Unit, enabled: Boolean = true) {
    FlowRow(Modifier.fillMaxWidth().semantics { contentDescription = "Topic" }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SpaceTopic.entries.forEach { t -> SpaceChip(t.label, t == selected, { onSelect(t) }, enabled = enabled) }
    }
}

/** A field's small label (canvas `.cx-field-label`). */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier.padding(horizontal = CircasoSpacing.s1), style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary))
}

/** The line under a field: plain, a tick when good, red when wrong. */
@Composable
fun FieldHint(text: String, tone: HintTone = HintTone.Quiet, icon: CircasoIcon? = null, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val color = when (tone) { HintTone.Quiet -> c.inkSecondary; HintTone.Critical -> c.critical; HintTone.Good -> c.ink }
    Row(modifier.padding(horizontal = CircasoSpacing.s1), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { CircasoIconImage(it, color, Modifier.size(13.dp), strokeWidth = 2.6f) }
        BasicText(text, style = CircasoTheme.typography.footnote.copy(color = color))
    }
}

enum class HintTone { Quiet, Good, Critical }

/** SN2's option card (canvas `.opt`): an icon, what it means, the radio; the chosen one opens [body]. */
@Composable
fun OptionCard(icon: CircasoIcon, title: String, sub: String, on: Boolean, onClick: () -> Unit, enabled: Boolean = true, body: (@Composable ColumnScope.() -> Unit)? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface)
            .border(if (on) 2.dp else 1.dp, if (on) c.ink else c.separator, CircasoShapes.md),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Row(
            Modifier.fillMaxWidth().pressable(enabled = enabled, role = Role.RadioButton, onClick = onClick).semantics(mergeDescendants = true) { selected = on }
                .padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = 14.dp, bottom = if (on && body != null) 0.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(36.dp).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
                CircasoIconImage(icon, c.ink, Modifier.size(16.dp), strokeWidth = 1.8f)
            }
            Column(Modifier.weight(1f)) {
                BasicText(title, style = t.headline.copy(color = c.ink))
                BasicText(sub, style = t.subhead.copy(color = c.inkSecondary))
            }
            Box(Modifier.padding(top = 2.dp)) { RadioMark(on) }
        }
        if (on && body != null) Column(Modifier.padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), content = body)
    }
}

/** A small icon and a quiet line (the trust lines under SN2's price and SN3's team). */
@Composable
fun InfoLine(text: String, icon: CircasoIcon = CircasoIcon.Info) {
    val c = CircasoTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircasoIconImage(icon, c.inkSecondary, Modifier.padding(top = 2.dp).size(14.dp))
        BasicText(text, style = CircasoTheme.typography.footnote.copy(color = c.inkSecondary))
    }
}

/** SN4–SN5b: the space's tile with the wallet mark on it, so the PIN can't be mistaken for the personal one. */
@Composable
fun SpaceWalletBadge(name: String, photoUrl: String?, size: Dp = 64.dp) {
    val c = CircasoTheme.colors
    Box {
        SpaceTile(name, photoUrl, size)
        Box(
            Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(34.dp).clip(CircasoShapes.circle).background(c.bg),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(28.dp).clip(CircasoShapes.circle).background(c.action), contentAlignment = Alignment.Center) {
                CircasoIconImage(CircasoIcon.Wallet, c.onAction, Modifier.size(15.dp))
            }
        }
    }
}

/** A calm centred message (canvas `center`): an icon, a heading, a line and an optional action. */
@Composable
fun CenterMessage(icon: CircasoIcon, title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s8), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CircasoIconImage(icon, c.inkTertiary, Modifier.size(40.dp), strokeWidth = 1.6f)
        BasicText(title, Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(text, Modifier.widthIn(max = 300.dp), style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        action?.invoke()
    }
}

/**
 * Words for what the server said about creating or changing a space, keyed on the code (never the message). Codes
 * with their own place on the screen (a taken handle, too new an account) are handled there; this is the rest.
 */
fun createErrorText(error: AppError): String = ErrorMessages.forError(
    error,
    mapOf(
        "spaces.too_many_spaces" to "You’ve started 3 spaces today. You can start another tomorrow.",
        "spaces.account_restricted" to "Your account can’t start spaces right now. Ask Asha if you think this is a mistake.",
        "spaces.handle_invalid" to "Handles are 3 to 30 letters, numbers, dots or underscores, starting with a letter.",
        "spaces.handle_taken" to "That handle is taken. Pick another.",
        "spaces.details_invalid" to "Some details aren’t right. Check the name and about.",
        "spaces.price_invalid" to "The price must be from 0.10 to 10,000 USDC.",
        "spaces.team_invalid" to "Someone in the team can’t be added. Check who you picked.",
        "spaces.media_not_found" to "The photo didn’t upload. Pick it again.",
        "spaces.media_invalid" to "That photo can’t be used. Pick another under 10 MB.",
        "spaces.not_owner" to "Only the space’s owner can change that.",
        "spaces.not_admin" to "Only admins can change that.",
        "spaces.wallet_not_empty" to "Empty the space wallet first: send what’s in it, then delete the space.",
        "spaces.space_not_found" to "This space isn’t available any more.",
        "spaces.unavailable" to "Spaces aren’t available on this server yet.",
    ),
)

/** The local rule for handles (contract SP1), checked before asking the server. Null when it is fine. */
fun handleProblem(handle: String): String? = when {
    handle.isEmpty() -> null
    handle.length < 3 -> "At least 3 characters."
    handle.length > 30 -> "At most 30 characters."
    !handle.first().isLetter() -> "Start with a letter."
    handle.any { !(it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_') } -> "Letters, numbers, dots and underscores only."
    else -> null
}

/** What the person types into the handle field, as the server will store it: lowercase, no `@`, no spaces. */
fun cleanHandle(text: String): String = text.trim().trimStart('@').lowercase().filter { !it.isWhitespace() }.take(30)
