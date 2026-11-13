package com.circaso.designsystem.motion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

/** Shared spring specs so motion feels the same everywhere. */
object CircasoSpring {
    /** iOS sheet: quick, lightly damped, no visible overshoot. */
    fun sheet(): SpringSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))

    /** Something settling into place as it appears (a sheet's icon badge): a touch of life, no visible bounce. */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

    /** Things moving to a new place in a layout that changed (call tiles reflowing): UIKit's push speed, no bounce. */
    fun reflow(): SpringSpec<IntOffset> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f, visibilityThreshold = IntOffset(1, 1))

    /** A floating notice dropping in under the top bar: settles without a visible bounce, like iOS's own banners. */
    fun <T> notice(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    /** A size settling after the screen turned (the call self-view). */
    fun <T> resize(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)

    /**
     * [reflow]'s motion driven as a 0..1 progress (a call tile moving and resizing at once): the same spring, settling
     * to within a thousandth so the last pixels of a long move glide in instead of snapping.
     */
    fun progress(): SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f, visibilityThreshold = 0.001f)
}
