package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

// Material's menu width range (112 to 280 dp); a group's name and balance fit on one line at the default size.
private val MenuMaxWidth = 300.dp

/**
 * Android: the vertical three dots (Material "more vert") in a 44 dp target with the ripple, and Material's own
 * anchored dropdown, dressed in Circaso's surfaces: groups first (picture, name, balance, a tick on the one shown),
 * a divider, then the actions. Back or a tap outside closes it; Material plays its own open and close.
 */
@Composable
internal actual fun NativeMoreMenu(
    title: String,
    sections: List<List<NativeMenuItem>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    contentDescription: String,
    modifier: Modifier,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(modifier) {
        Box(
            Modifier.size(CircasoSizes.controlMd).clip(CircasoShapes.circle)
                .pressable(role = Role.Button, onClick = { onExpandedChange(!expanded) })
                .semantics {
                    this.contentDescription = contentDescription
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            contentAlignment = Alignment.Center,
        ) {
            CircasoIconImage(CircasoIcon.More, c.ink, Modifier.size(22.dp).graphicsLayer { rotationZ = 90f }, strokeWidth = 3f)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            offset = DpOffset(0.dp, CircasoSpacing.s1),
            shape = CircasoShapes.md,
            containerColor = c.surfaceRaised,
            modifier = Modifier.widthIn(max = MenuMaxWidth).semantics { paneTitle = title },
        ) {
            sections.forEachIndexed { i, section ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = CircasoSpacing.s1), color = c.separator)
                section.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                BasicText(
                                    item.title, style = t.body.copy(color = c.ink, fontWeight = if (item.checked) FontWeight.SemiBold else FontWeight.Normal),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                item.subtitle?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        },
                        onClick = { onSelect(item.id) },
                        leadingIcon = when {
                            item.avatarName != null -> ({ Avatar(item.avatarName, size = CircasoSizes.avatarSm, imageUrl = item.avatarUrl) })
                            item.icon != null -> ({ CircasoIconImage(item.icon.circaso(), c.ink, Modifier.size(20.dp)) })
                            else -> null
                        },
                        trailingIcon = if (item.checked) ({ CircasoIconImage(CircasoIcon.Check, c.action, Modifier.size(18.dp), strokeWidth = 2.4f) }) else null,
                        colors = MenuDefaults.itemColors(textColor = c.ink, leadingIconColor = c.ink, trailingIconColor = c.action),
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            if (item.avatarName != null) selected = item.checked
                            item.accessibilityLabel?.let { this.contentDescription = it }
                        },
                    )
                }
            }
        }
    }
}

private fun NativeMenuIcon.circaso() = when (this) {
    NativeMenuIcon.Chat -> CircasoIcon.Chat
    NativeMenuIcon.Info -> CircasoIcon.Info
    NativeMenuIcon.List -> CircasoIcon.People
}
