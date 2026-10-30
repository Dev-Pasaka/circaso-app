package com.circaso.designsystem.motion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

/** Shared spring specs so motion feels the same everywhere. */
object CircasoSpring {
    /** iOS sheet: quick, lightly damped, no visible overshoot. */
    fun sheet(): SpringSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))
}
