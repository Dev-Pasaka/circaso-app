package com.circaso.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/**
 * The text field on the dark stage (a moment's caption; the reply to a moment is the chat composer itself, drawn the
 * same way by `ComposerBar(onStage = true)`): the chat composer's field (decision
 * 2026-10-30: bigger, borderless, radius 24, at least 48 dp; the same shape and height as [ComposerBar]'s) on glass
 * instead of `fill`, since it sits over a photo or video rather than a bar (2026-11-14, Pasaka: the moments' inputs
 * should be the design system's, not one-offs). This departs from V1's outlined 46 dp pill and M4's 44 dp caption.
 *
 * One line that grows to [maxLines] as the person writes, then scrolls. The whole pill is the field (a tap anywhere
 * in it puts the cursor in), it keeps its own cursor (state that comes back a frame late never eats a key), and the
 * placeholder is light enough to read on glass over a bright photo. The high-contrast themes keep the hairline edge.
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
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // Only a text this field didn't type is taken from outside (cleared after sending, `/asha` taken out).
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    val onChange = ashaAwareValue(field, { next -> field = next; if (next.text != value) onValueChange(next.text) })
    BasicTextField(
        value = field, onValueChange = onChange, enabled = enabled, maxLines = maxLines,
        textStyle = t.body.copy(color = if (enabled) c.ink else c.inkTertiary), cursorBrush = SolidColor(c.ink),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label }
            .then(if (onFocus != null) Modifier.onFocusChanged { onFocus(it.isFocused) } else Modifier),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = StageFieldMin).clip(ComposerFieldShape).glass(ComposerFieldShape, edge = false)
                    .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                contentAlignment = Alignment.CenterStart,
            ) {
                // V1's placeholder: white at 80%, read on glass over any photo; the field's label speaks for it.
                if (value.isEmpty()) BasicText(
                    placeholder, Modifier.clearAndSetSemantics { }, style = t.body.copy(color = c.ink.copy(alpha = 0.8f)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                inner()
            }
        },
    )
}

/** The stage field's height on one line: the composer field's. Buttons beside it take the same size. */
val StageFieldMin: Dp get() = ComposerFieldMin
