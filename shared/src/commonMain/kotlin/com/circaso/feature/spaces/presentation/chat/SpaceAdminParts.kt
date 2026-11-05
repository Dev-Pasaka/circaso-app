package com.circaso.feature.spaces.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.SpacePerson
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.presentation.RoleBadge

// Small pieces the admin screens share (SR, SI): search field, filter chips, a person row, section labels, the calm
// centred states and the error with its reference.

/** The canvas's search box (`search_bar` without Cancel): 44 dp on fill, the search glyph, a clear button. */
@Composable
internal fun SpaceSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2)
            .heightIn(min = CircasoSizes.controlMd).clip(CircasoShapes.md).background(c.fill).padding(horizontal = CircasoSpacing.s3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        CircasoIconImage(CircasoIcon.Search, c.inkSecondary, Modifier.size(16.dp))
        androidx.compose.foundation.layout.Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) BasicText(placeholder, style = t.body.copy(color = c.inkSecondary), maxLines = 1)
            BasicTextField(
                value, onValueChange, Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                textStyle = t.body.copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink),
            )
        }
        if (value.isNotEmpty()) CircasoIconButton(CircasoIcon.Close, "Clear", { onValueChange("") })
    }
}

/** A filter chip (canvas `.chip`): fill, or the action fill when on; a radio for screen readers. */
@Composable
internal fun FilterChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    BasicText(
        text,
        Modifier.heightIn(min = CircasoSizes.controlSm).clip(CircasoShapes.pill).background(if (on) c.action else c.fill)
            .pressable(role = Role.RadioButton, onClick = onClick).semantics { selected = on }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        style = CircasoTheme.typography.subhead.copy(color = if (on) c.onAction else c.ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
        maxLines = 1,
    )
}

/** A section's quiet label (canvas `.sec`). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier.padding(horizontal = CircasoSpacing.s1).semantics { heading() }, style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary))
}

/** One person (canvas `member_row`): photo, name with a role badge, a sub line, something at the end. */
@Composable
internal fun PersonRow(
    person: SpacePerson,
    sub: String?,
    role: SpaceRole? = null,
    onClick: (() -> Unit)? = null,
    end: @Composable () -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.pressable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Avatar(person.displayName, size = CircasoSizes.avatarMd, imageUrl = person.avatarUrl)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(person.displayName, Modifier.weight(1f, fill = false), style = t.body.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                RoleBadge(role)
            }
            sub?.let { BasicText(it, style = t.subhead.copy(color = c.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        end()
    }
}

/** The calm centred state (canvas `center`): an icon, a title, a line and an optional action. */
@Composable
internal fun CenterState(icon: CircasoIcon, title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s8),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircasoIconImage(icon, c.inkTertiary, Modifier.size(40.dp), strokeWidth = 1.6f)
        BasicText(title, Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(text, Modifier.widthIn(max = 300.dp), style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        action?.invoke()
    }
}

/** Failed after retries with nothing on the phone (canvas `err_box`): plain words, Try Again and the reference. */
@Composable
internal fun ErrorState(title: String, text: String, reference: String?, onRetry: () -> Unit, enabled: Boolean = true) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CenterState(CircasoIcon.Warning, title, text) {
        CircasoButton("Try Again", onRetry, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = enabled)
        reference?.let { BasicText("Ref $it", style = t.caption2.copy(color = c.inkTertiary)) }
    }
}

/** The design's `.radio` row in a list: the title and a ring filled when chosen (SR3's reasons). */
@Composable
internal fun RadioRow(title: String, on: Boolean, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlMd).pressable(role = Role.RadioButton, onClick = onClick).semantics(mergeDescendants = true) { selected = on }
            .padding(horizontal = CircasoSpacing.s4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        BasicText(title, Modifier.weight(1f), style = CircasoTheme.typography.body.copy(color = c.ink))
        com.circaso.feature.wallet.presentation.RadioMark(on)
    }
}

/** A white-on-surface grouped list (canvas `.cx-list`), with hairlines between its rows. */
@Composable
internal fun GroupedList(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(CircasoShapes.lg).background(CircasoTheme.colors.surface)) { content() }
}
