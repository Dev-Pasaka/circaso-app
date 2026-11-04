package com.circaso.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** Grouped list container: rounded surface on the grouped background. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(CircasoShapes.lg).background(CircasoTheme.colors.surface)) { content() }
}

/** Title, optional sub line and meta text, with slots on either end. Taps only when [onClick] is set. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    meta: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            BasicText(title, style = t.body.copy(color = if (destructive) c.critical else c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) BasicText(sub, style = t.subhead.copy(color = c.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (meta != null) BasicText(meta, style = t.footnote.copy(color = c.inkTertiary))
        trailing?.invoke()
    }
}

/** Key on the left, value on the right. */
@Composable
fun DetailRow(key: String, value: String, modifier: Modifier = Modifier) = DetailRow(key, modifier) {
    BasicText(value, style = CircasoTheme.typography.subhead.copy(color = CircasoTheme.colors.ink))
}

/** A detail row whose value is drawn (a spinner while it is worked out, an action when it couldn't be). */
@Composable
fun DetailRow(key: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(key, style = t.subhead.copy(color = c.inkSecondary))
        Box(Modifier.padding(start = CircasoSpacing.s4).weight(1f, fill = false)) { value() }
    }
}

/** Hairline between rows inside a [ListGroup]. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(start = CircasoSpacing.s4).height(0.5.dp).background(CircasoTheme.colors.separator))
}
