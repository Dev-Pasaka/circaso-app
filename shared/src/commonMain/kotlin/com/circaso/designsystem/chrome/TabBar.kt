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
fun CircasoTabBar(items: List<TabItem>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    Row(
        modifier.fillMaxWidth().glass(CircasoShapes.pill, floating = true).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            Column(
                Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 64.dp)
                    .height(54.dp)
                    .clip(CircasoShapes.pill)
                    .background(if (selected) c.fill else androidx.compose.ui.graphics.Color.Transparent)
                    .pressable(role = Role.Tab, pressedScale = 0.96f, onClick = { onSelect(index) })
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
                BasicText(
                    item.label,
                    style = CircasoTheme.typography.caption2.copy(
                        color = if (selected) c.ink else c.inkSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}
