package com.circaso.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme

/**
 * An on/off switch for a setting that applies at once (no Save), as `.sw`: the track fills with `action` when on and
 * the thumb takes the background colour (white off; on a dark phone the track is light, so the thumb goes dark). Spoken as a switch with its state; the row around it should carry the label.
 */
@Composable
fun CircasoSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = CircasoTheme.colors
    val still = CircasoTheme.reduceMotion
    val track by animateColorAsState(if (checked) c.action else c.fillStrong, tween(CircasoMotion.QuickMs), label = "track")
    val x by animateDpAsState(
        if (checked) 20.dp else 0.dp,
        if (still) tween(0) else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
        label = "thumb",
    )
    Box(
        // Disabled: the whole switch fades, so it reads as unavailable whichever way it is set.
        modifier.minimumInteractiveComponentSize().size(51.dp, 31.dp).alpha(if (enabled) 1f else 0.38f).clip(CircasoShapes.pill).background(track)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = x).size(27.dp).shadow(2.dp, CircasoShapes.circle).clip(CircasoShapes.circle).background(if (checked) c.bg else Color.White))
    }
}
