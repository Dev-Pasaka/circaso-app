package com.circaso.designsystem.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.circaso.designsystem.motion.pressable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme

data class TabItem(val label: String, val icon: CircasoIcon, val unread: Boolean = false)

/** Floating glass pill (design `cx-tabbar`): 54 dp tabs, selected tab on a filled pill, optional unread dot. */
@Composable
fun CircasoTabBar(
    items: List<TabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Stacked down the left side (a phone on its side, a tablet): Material's navigation rail, iPad's sidebar. */
    vertical: Boolean = false,
) {
    if (vertical) {
        Column(
            modifier.glass(androidx.compose.foundation.shape.RoundedCornerShape(32.dp), floating = true).padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEachIndexed { index, item -> TabButton(item, index == selectedIndex, Modifier.size(width = 68.dp, height = 58.dp)) { onSelect(index) } }
        }
        return
    }
    Row(
        modifier.fillMaxWidth().glass(CircasoShapes.pill, floating = true).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, item ->
            TabButton(item, index == selectedIndex, Modifier.weight(1f).defaultMinSize(minWidth = 64.dp).height(54.dp)) { onSelect(index) }
        }
    }
}

@Composable
private fun TabButton(item: TabItem, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val c = CircasoTheme.colors
    run {
        run {
            Column(
                modifier
                    .clip(CircasoShapes.pill)
                    .background(if (selected) c.fill else androidx.compose.ui.graphics.Color.Transparent)
                    .pressable(role = Role.Tab, pressedScale = 0.96f, onClick = onSelect)
                    .semantics {
                        this.selected = selected
                        if (item.unread) contentDescription = "${item.label}, new activity"
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    CircasoIconImage(item.icon, if (selected) c.ink else c.inkSecondary, Modifier.size(22.dp))
                    if (item.unread) {
                        Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-1).dp).size(8.dp).clip(CircasoShapes.circle).background(c.action))
                    }
                }
                // Tab labels grow only a little with the text size, as UIKit's tab bar labels don't grow at all: at the
                // largest sizes the label would no longer fit its 54 dp tab (screen readers still read it in full). A
                // plain linear scale here: at a 1.2 cap Android 14's non-linear font curve makes no visible difference.
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, minOf(density.fontScale, MaxTabLabelScale)),
                ) {
                    BasicText(
                        item.label,
                        style = CircasoTheme.typography.caption2.copy(
                            color = if (selected) c.ink else c.inkSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The most a tab's label grows with the person's text size. */
private const val MaxTabLabelScale = 1.2f
