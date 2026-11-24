package com.circaso.designsystem.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Where a zoomable picture is: its [scale] about the container's centre, then moved by [offset] (pixels). The
 * identity is how it first shows: fitted, centred.
 */
data class ZoomTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    val zoomed get() = scale > 1f + Epsilon

    companion object {
        val Identity = ZoomTransform()
        internal const val Epsilon = 0.01f
    }
}

/**
 * The arithmetic of pinch, pan and double-tap zoom, kept apart from gestures so it can be tested (2026-11-24). Every
 * answer is finite: anything not a number (a gesture's centroid once the fingers are up is `Offset.Unspecified`,
 * which is NaN) leaves the transform as it was, so the picture can never be moved off to NaN and vanish.
 */
object ZoomMath {
    const val MinScale = 1f
    const val MaxScale = 5f
    /** Double-tap goes this far in, about the tapped point. */
    const val DoubleTapScale = 2.5f
    /** While the fingers are down a pinch may go a little past the limits, and springs back on release. */
    const val Overshoot = 1.15f

    /**
     * The furthest [content] (as fitted, at scale 1) may move each way at [scale] inside [container] so no empty band
     * opens at an edge: half of how much wider (taller) it is than the container. Zero on an axis where it fits.
     */
    fun bounds(scale: Float, container: Size, content: Size = container): Offset {
        if (!container.isUsable() || !content.isUsable() || !scale.isFinite()) return Offset.Zero
        return Offset(max(0f, (content.width * scale - container.width) / 2f), max(0f, (content.height * scale - container.height) / 2f))
    }

    /** [offset] held inside [bounds]. */
    fun clampOffset(offset: Offset, scale: Float, container: Size, content: Size = container): Offset {
        val b = bounds(scale, container, content)
        val x = if (offset.x.isFinite()) offset.x.coerceIn(-b.x, b.x) else 0f
        val y = if (offset.y.isFinite()) offset.y.coerceIn(-b.y, b.y) else 0f
        return Offset(x, y)
    }

    /**
     * One step of a gesture: zoom by [factor] about [focus] (a point in the container, from its top-left) and move by
     * [pan]. The point under the fingers stays under them. While the gesture runs the scale may go [Overshoot] past
     * the limits and the picture may be dragged past its edges with resistance; [settle] puts it back.
     */
    fun gesture(t: ZoomTransform, factor: Float, focus: Offset, pan: Offset, container: Size, content: Size = container, maxScale: Float = MaxScale): ZoomTransform {
        if (!container.isUsable()) return t
        val safeFactor = if (factor.isFinite() && factor > 0f) factor else 1f
        val safePan = if (pan.isSpecified && pan.x.isFinite() && pan.y.isFinite()) pan else Offset.Zero
        // Without a usable focus (no finger still down), zoom about the centre: never about NaN.
        val f = if (focus.isSpecified && focus.x.isFinite() && focus.y.isFinite()) focus - container.center() else Offset.Zero
        val scale = (t.scale * safeFactor).coerceIn(MinScale / Overshoot, maxScale * Overshoot)
        val grown = scale / t.scale
        // The point under the fingers is at f; it was at (f - offset) / scale in the picture. Keep it at f.
        val moved = (t.offset - f) * grown + f + safePan
        val b = bounds(scale, container, content)
        return ZoomTransform(scale, Offset(resist(t.offset.x, moved.x, b.x, container.width), resist(t.offset.y, moved.y, b.y, container.height)))
    }

    /** Where a transform comes to rest: scale in [MinScale]..[maxScale], the picture covering what it can. */
    fun settle(t: ZoomTransform, container: Size, content: Size = container, maxScale: Float = MaxScale): ZoomTransform {
        if (!t.scale.isFinite()) return ZoomTransform.Identity
        val scale = t.scale.coerceIn(MinScale, maxScale)
        if (scale <= MinScale + ZoomTransform.Epsilon) return ZoomTransform.Identity
        // Coming back from past the limit, the picture shrinks about the same middle it grew about.
        val shrunk = t.offset * (scale / t.scale)
        return ZoomTransform(scale, clampOffset(shrunk, scale, container, content))
    }

    /** Double-tap: zoomed in, back to fitted; fitted, in to [DoubleTapScale] about the tapped point [at]. */
    fun doubleTap(t: ZoomTransform, at: Offset, container: Size, content: Size = container): ZoomTransform {
        if (t.zoomed || !container.isUsable()) return ZoomTransform.Identity
        val f = if (at.isSpecified && at.x.isFinite() && at.y.isFinite()) at - container.center() else Offset.Zero
        val scale = DoubleTapScale
        return ZoomTransform(scale, clampOffset(f * (1f - scale), scale, container, content))
    }

    /**
     * A move from [from] to [to] on one axis: free inside [limit]; past it the edge resists (a third of the movement),
     * never more than a third of [extent] past it. Coming back in is free again.
     */
    private fun resist(from: Float, to: Float, limit: Float, extent: Float): Float {
        if (!to.isFinite()) return if (from.isFinite()) from.coerceIn(-limit, limit) else 0f
        if (abs(to) <= limit) return to
        val sign = if (to > 0f) 1f else -1f
        val already = if (from.isFinite() && from * sign > limit) abs(from) else limit
        if (abs(to) <= already) return to
        return sign * minOf(already + (abs(to) - already) / 3f, limit + extent / 3f)
    }

    /** [intrinsic] fitted inside [container] (ContentScale.Fit), or the container itself when either isn't a real size. */
    fun fit(intrinsic: Size, container: Size): Size {
        if (!intrinsic.isSpecified || !intrinsic.isUsable() || !container.isSpecified || !container.isUsable()) return container
        val k = minOf(container.width / intrinsic.width, container.height / intrinsic.height)
        return Size(intrinsic.width * k, intrinsic.height * k)
    }

    private fun Size.isUsable() = width.isFinite() && height.isFinite() && width > 0f && height > 0f
    private fun Size.center() = Offset(width / 2f, height / 2f)
}

/**
 * A zoomable picture's live state ([ZoomMath] driven by gestures), shared by the chat's photo viewer and the PDF
 * viewer. With its [intrinsic] size known, a pan stops at the picture's own edges (a wide photo on a tall screen has
 * black above and below); left unknown the picture is taken to fill the container.
 */
@Stable
class ZoomState(private val scope: CoroutineScope, val maxScale: Float = ZoomMath.MaxScale) {
    var transform by mutableStateOf(ZoomTransform.Identity)
        private set
    var container by mutableStateOf(Size.Zero)
        internal set
    /** The picture's own size (any units), when known: it is fitted inside the container, as ContentScale.Fit draws it. */
    var intrinsic by mutableStateOf(Size.Unspecified)
    private var running: Job? = null

    val zoomed get() = transform.zoomed

    private val fitted get() = ZoomMath.fit(intrinsic, container)

    /** Back to fitted at once (a new picture), or to [to]. */
    fun reset(to: ZoomTransform = ZoomTransform.Identity) {
        running?.cancel()
        transform = to
    }

    internal fun step(factor: Float, focus: Offset, pan: Offset) {
        running?.cancel()
        transform = ZoomMath.gesture(transform, factor, focus, pan, container, fitted, maxScale)
    }

    /** The fingers are up: spring back inside the limits. */
    fun settle() = animateTo(ZoomMath.settle(transform, container, fitted, maxScale))

    fun doubleTap(at: Offset) = animateTo(ZoomMath.doubleTap(transform, at, container, fitted))

    fun animateTo(target: ZoomTransform, spec: AnimationSpec<Float> = ZoomSpring) {
        val from = transform
        if (from == target) return
        running?.cancel()
        running = scope.launch {
            animate(0f, 1f, animationSpec = spec) { f, _ ->
                transform = ZoomTransform(from.scale + (target.scale - from.scale) * f, from.offset + (target.offset - from.offset) * f)
            }
        }
    }

    companion object {
        /** A quick, soft settle with a touch of give (no visible bounce at the edges). */
        val ZoomSpring = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    }
}

/**
 * Pinch to zoom (about the fingers), pan while zoomed, double-tap to zoom in at the tap or back out, and a spring back
 * inside the limits when the fingers lift. Not zoomed, a single finger's vertical drag goes to [onDrag] (a viewer's
 * swipe down to close) and its end to [onDragEnd]; [onTap] is a single tap. Apply [zoomed] to the picture itself.
 */
fun Modifier.zoomGestures(
    state: ZoomState,
    onTap: (() -> Unit)? = null,
    onDrag: ((Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
): Modifier = this
    .onSizeChanged { state.container = Size(it.width.toFloat(), it.height.toFloat()) }
    .pointerInput(state) {
        detectTapGestures(onTap = onTap?.let { tap -> { tap() } }, onDoubleTap = { state.doubleTap(it) })
    }
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var travelled = 0f
            var transforming = false
            var dragging = false
            var panned = 0f
            do {
                val event = awaitPointerEvent()
                // Only fingers that are down now and were down before say anything about this step. On the event that
                // lifts the last finger there are none: its centroid is Unspecified (NaN). Using it was the blank photo.
                val down = event.changes.count { it.pressed && it.previousPressed }
                if (down > 0) {
                    val factor = event.calculateZoom()
                    val pan = event.calculatePan()
                    // A pinch starts at once; one finger on a zoomed picture pans only past the touch slop, so a tap or
                    // a slightly shaky double-tap still reaches the tap detector.
                    if (!transforming && down == 1 && factor == 1f && state.zoomed) panned += pan.getDistance()
                    val pinch = down > 1 || factor != 1f
                    if (!dragging && (pinch || transforming || (state.zoomed && panned > slop))) {
                        transforming = true
                        state.step(factor, event.calculateCentroid(useCurrent = true), pan)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } else if (!transforming && down == 1 && onDrag != null && !state.zoomed) {
                        // A drag to close starts only past the touch slop, so a shaky tap still taps.
                        travelled += abs(pan.y)
                        if (dragging || travelled > slop) {
                            dragging = true
                            onDrag(pan.y)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
            if (transforming) state.settle()
            if (dragging) onDragEnd?.invoke()
        }
    }

/** Draws the picture where [state] has it. */
fun Modifier.zoomed(state: ZoomState, extraY: () -> Float = { 0f }): Modifier = graphicsLayer {
    val t = state.transform
    scaleX = t.scale
    scaleY = t.scale
    translationX = t.offset.x
    translationY = t.offset.y + extraY()
}
