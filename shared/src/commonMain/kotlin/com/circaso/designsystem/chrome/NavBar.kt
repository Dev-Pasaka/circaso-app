package com.circaso.designsystem.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.platform.PlatformStyle
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoTheme

/**
 * Top level screens (Chats, Wallet, You): actions on the right, then the 34 pt large title.
 * The collapsing-on-scroll behaviour is not built yet.
 */
@Composable
fun LargeTitleBar(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlMd),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
        BasicText(
            title,
            Modifier.semantics { heading() },
            style = CircasoTheme.typography.largeTitle.copy(color = CircasoTheme.colors.ink),
        )
    }
}

/**
 * Pushed screens: back button, title, optional trailing actions. The design centres the title (iOS);
 * on Android the title sits next to the back arrow, as users there expect.
 */
@Composable
fun CircasoNavBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /**
     * The way out is Close (a cross), not Back: a page that ends a flow or stands apart from it (design E3, an order's
     * status), where there is nothing to go back to. [onBack] is still what it does.
     */
    close: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val centered = CircasoTheme.platform == PlatformStyle.Ios
    val leaveIcon = if (close) CircasoIcon.Close else CircasoIcon.Back
    val leaveLabel = if (close) "Close" else "Back"
    if (com.circaso.designsystem.adaptive.LocalTopBarOnSide.current) {
        // Down the left side on a phone on its side: Back, the title, then the actions, all within thumb reach.
        Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBack != null && !com.circaso.core.navigation.LocalIsDetailRoot.current) CircasoIconButton(leaveIcon, leaveLabel, onBack)
            Column(Modifier.semantics { heading() }) {
                BasicText(title, style = t.headline.copy(color = c.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) BasicText(subtitle, style = t.caption1.copy(color = c.inkSecondary), maxLines = 2)
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
        }
        return
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlMd).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The first screen beside a list has nothing to go back to (iPad): no Back there.
        if (onBack != null && !com.circaso.core.navigation.LocalIsDetailRoot.current) CircasoIconButton(leaveIcon, leaveLabel, onBack)
        else if (centered) Spacer(Modifier.width(CircasoSizes.iconButton))
        Column(
            // A bar with no title (design E3) has no heading to announce.
            Modifier.weight(1f).then(if (title.isNotBlank()) Modifier.semantics { heading() } else Modifier),
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            BasicText(
                title,
                style = t.headline.copy(color = c.ink, textAlign = if (centered) TextAlign.Center else TextAlign.Start),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) BasicText(subtitle, style = t.caption1.copy(color = c.inkSecondary), maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}
