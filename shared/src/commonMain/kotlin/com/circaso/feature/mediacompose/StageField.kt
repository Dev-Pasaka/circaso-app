package com.circaso.feature.mediacompose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.components.ashaAware
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/**
 * The text field on the dark stage (a moment's caption, a reply to a moment): the chat composer's field (decision
 * 2026-10-30: bigger, borderless, radius 24, at least 48 dp) on glass instead of `fill`, since it sits over a photo
 * or video rather than a bar (2026-11-14, Pasaka: the moments' inputs should be the design system's, not one-offs).
 * One line that grows to [maxLines] as the person writes, then scrolls; the high-contrast themes keep the hairline
 * edge. [trailing] sits inside the field at its end (a send arrow), centred on one line and at the bottom as it grows.
 */
@Composable
fun StageField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 4,
    onFocus: ((Boolean) -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = StageFieldMin).clip(StageFieldShape).glass(StageFieldShape, edge = false)
            .padding(start = CircasoSpacing.s4, end = if (trailing != null) CircasoSpacing.s1 else CircasoSpacing.s4),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        Box(Modifier.weight(1f).defaultMinSize(minHeight = StageFieldMin).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) BasicText(placeholder, style = t.body.copy(color = c.inkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value, ashaAware(value, onValueChange), enabled = enabled, maxLines = maxLines,
                textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
                    .then(if (onFocus != null) Modifier.onFocusChanged { onFocus(it.isFocused) } else Modifier),
            )
        }
        trailing?.invoke(this)
    }
}

private val StageFieldMin = 48.dp
private val StageFieldShape = RoundedCornerShape(24.dp)
