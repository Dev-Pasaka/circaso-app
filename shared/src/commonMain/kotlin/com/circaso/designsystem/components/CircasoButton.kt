package com.circaso.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

enum class ButtonStyle { Primary, Secondary, Plain, Destructive }
enum class ButtonSize { Large, Medium, Small }

/** Pill button. While [busy] it shows a spinner and ignores taps but keeps its colours (per the design). */
@Composable
fun CircasoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    size: ButtonSize = ButtonSize.Medium,
    enabled: Boolean = true,
    busy: Boolean = false,
    block: Boolean = false,
    /** A leading glyph (the design's "+ Add"). */
    icon: CircasoIcon? = null,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val active = enabled && !busy
    val (bg, fg) = when {
        !enabled -> (if (style == ButtonStyle.Plain || style == ButtonStyle.Destructive) androidx.compose.ui.graphics.Color.Transparent else c.fill) to c.inkTertiary
        style == ButtonStyle.Primary -> c.action to c.onAction
        style == ButtonStyle.Secondary -> c.fill to c.ink
        style == ButtonStyle.Plain -> androidx.compose.ui.graphics.Color.Transparent to c.ink
        else -> androidx.compose.ui.graphics.Color.Transparent to c.critical
    }
    val height = when (size) {
        ButtonSize.Large -> CircasoSizes.controlLg
        ButtonSize.Medium -> CircasoSizes.controlMd
        ButtonSize.Small -> CircasoSizes.controlSm
    }
    val padding = when (size) {
        ButtonSize.Large -> 28.dp
        ButtonSize.Medium -> 20.dp
        ButtonSize.Small -> 14.dp
    }
    val textStyle = (if (size == ButtonSize.Small) t.subhead else t.headline)
        .copy(color = fg, textAlign = TextAlign.Center)
    Row(
        modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .defaultMinSize(minWidth = height)
            .clip(CircasoShapes.pill)
            .background(bg)
            .pressable(enabled = active, onClick = onClick)
            .padding(horizontal = padding)
            .semantics { if (busy) contentDescription = "$text, loading" },
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) Spinner(fg) else if (icon != null) CircasoIconImage(icon, fg, Modifier.size(if (size == ButtonSize.Small) 14.dp else 18.dp))
        BasicText(text, style = textStyle, maxLines = 1)
    }
}

/** Round 36 dp button used in bars. */
@Composable
fun CircasoIconButton(
    icon: CircasoIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    enabled: Boolean = true,
) {
    val c = CircasoTheme.colors
    Box(
        modifier
            .size(CircasoSizes.iconButton)
            .clip(CircasoShapes.circle)
            .background(if (emphasised) c.action else c.fill)
            .pressable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        CircasoIconImage(icon, when { !enabled -> c.inkTertiary; emphasised -> c.onAction; else -> c.ink }, Modifier.size(18.dp))
    }
}
