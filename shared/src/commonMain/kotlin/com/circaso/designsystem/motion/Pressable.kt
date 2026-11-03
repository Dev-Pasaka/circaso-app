package com.circaso.designsystem.motion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.circaso.designsystem.platform.LocalPlatformStyle
import com.circaso.designsystem.platform.PlatformStyle

/**
 * Tap handling that feels native: iOS gets a spring scale-down and dim (no ripple),
 * Android gets the ripple. [pressedScale] is the iOS scale while held.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    pressedScale: Float = 0.97f,
    /** A long press (reactions on a card, WhatsApp-style); null keeps plain clicks. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val style = LocalPlatformStyle.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val still = com.circaso.designsystem.platform.LocalReduceMotion.current
    if (style == PlatformStyle.Ios) {
        val target = if (pressed && enabled) 1f else 0f
        val p by animateFloatAsState(target, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium))
        this
            .graphicsLayer {
                val s = if (still) 1f else 1f - (1f - pressedScale) * p
                scaleX = s
                scaleY = s
                alpha = 1f - 0.15f * p
            }
            .combinedClickable(interactionSource = source, indication = null, enabled = enabled, role = role, onLongClick = onLongClick, onClick = onClick)
    } else {
        this.combinedClickable(interactionSource = source, indication = ripple(), enabled = enabled, role = role, onLongClick = onLongClick, onClick = onClick)
    }
}

/** [pressable] as a value, for rows that are tappable only sometimes. */
fun pressableModifierFor(onClick: () -> Unit): Modifier = Modifier.pressable(role = Role.Button, onClick = onClick)
