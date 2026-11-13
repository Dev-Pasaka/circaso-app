package com.circaso.feature.calls.presentation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import com.circaso.core.calls.LocalVideoClip
import com.circaso.core.calls.VideoClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * Who is big on a call (Pasaka, 2026-11-09): "If my tiny feed is up there, I should be able to tap and switch view …
 * in group calls, if I click on my feed, I should see my feed as a bigger one, and the others as small feeds." Local
 * to this phone; nobody else's screen changes.
 */

/** A group tile tapped: it is spotlit, or, when it already was, everyone goes back to the grid. */
internal fun spotlightAfterTap(current: String?, tapped: String): String? = if (current == tapped) null else tapped

/** The spotlight while its person is still on the call; once they leave, everyone is back in the grid. */
internal fun spotlightFor(current: String?, onCall: List<String>): String? = current?.takeIf { it in onCall }

/**
 * The spotlight after who is sharing a screen changed, and whether the share put it there ([auto]). Someone starting
 * to share takes the spotlight unless this phone already chose one (Meet and FaceTime do the same; a screen in a grid
 * tile is too small to read); when their share ends, a spotlight the share put there goes back to the grid, while one
 * the person tapped stays. Returns the spotlight and the new [auto].
 */
internal fun spotlightAfterShare(current: String?, auto: String?, sharer: String?, previousSharer: String?): Pair<String?, String?> {
    // The person tapped something else since: the share no longer owns the spotlight.
    val owned = auto != null && auto == current
    return when {
        sharer != null && sharer != previousSharer && (current == null || owned) -> sharer to sharer
        sharer == null && owned -> null to null
        else -> current to auto.takeIf { owned }
    }
}

/**
 * This phone's choices about who is big, for one call. Held above the call screen so minimising and coming back keeps
 * them (the call screen itself leaves composition while minimised).
 */
@Stable
internal class CallStageState {
    /** 1:1: my picture is the big one. */
    var selfBig by mutableStateOf(false)
    var corner by mutableStateOf(Corner.TopEnd)
    /** Group: the spotlit tile, null for the grid. */
    var chosen by mutableStateOf<String?>(null)
    /** The spotlight a screen share put there (see [spotlightAfterShare]). */
    var auto: String? = null
    var lastSharer: String? = null
}

/** Where the 1:1 self-view rests. */
internal enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

/**
 * Where a flung self-view settles (FaceTime): the corner nearest to where its centre would coast to, so a flick toward
 * a corner lands there even if the finger lifted early. [x], [y] are its centre, [vx], [vy] its velocity per second.
 */
internal fun cornerFor(x: Float, y: Float, vx: Float, vy: Float, width: Float, height: Float): Corner {
    val px = x + vx * FlingProjectionSeconds
    val py = y + vy * FlingProjectionSeconds
    val left = px < width / 2
    val top = py < height / 2
    return when {
        top && left -> Corner.TopStart
        top -> Corner.TopEnd
        left -> Corner.BottomStart
        else -> Corner.BottomEnd
    }
}

/** How far ahead a fling is projected; about where UIKit's normal deceleration comes to rest. */
private const val FlingProjectionSeconds = 0.2f

/**
 * One tile's motion when the layout moves or resizes it (a swap, a spotlight, someone joining): the layout puts it in
 * its new place at once and this draws it from where it was on screen to there, as a transform only, so the video
 * inside is neither measured again each frame nor torn down. A change mid-flight carries on from where it is. [drag]
 * is a finger moving it (the self-view); a new place takes the drag over so a release never jumps.
 *
 * The picture is scaled by one factor only (never squashed: a face stays a face while its frame changes shape) and
 * cropped by a rounded rectangle that moves from the old frame to the new one, its corners kept at screen size.
 */
@Stable
internal class TileMotion(private val scope: CoroutineScope) {
    var target by mutableStateOf<Rect?>(null)
        private set
    private var from by mutableStateOf<Rect?>(null)
    private var fromRadius = 0f
    private var targetRadius by mutableFloatStateOf(0f)
    private var progress by mutableFloatStateOf(1f)
    var drag by mutableStateOf(Offset.Zero)
    private var job: Job? = null

    /** Where the tile is drawn now, in its parent. */
    fun visual(): Rect? {
        val t = target ?: return null
        val f = from
        val r = if (f == null || progress >= 1f) t else lerp(f, t, progress)
        return r.translate(drag)
    }

    /** The corner radius now, in pixels on screen. */
    fun radius(): Float = if (from == null || progress >= 1f) targetRadius else fromRadius + (targetRadius - fromRadius) * progress

    fun placed(rect: Rect, radius: Float, still: Boolean, spec: AnimationSpec<Float>) {
        val before = target
        if (before == rect) {
            if (radius != targetRadius && (from == null || progress >= 1f)) targetRadius = radius
            return
        }
        val now = visual()
        val nowRadius = radius()
        target = rect
        targetRadius = radius
        job?.cancel()
        drag = Offset.Zero
        if (before == null || now == null || still) { from = null; progress = 1f; return }
        from = now
        fromRadius = nowRadius
        progress = 0f
        job = scope.launch { animate(0f, 1f, animationSpec = spec) { v, _ -> progress = v } }
    }

    /** A drag let go where the layout keeps the tile: it springs back. */
    fun settleDrag(still: Boolean, spec: AnimationSpec<Offset>) {
        job?.cancel()
        if (still) { drag = Offset.Zero; return }
        val start = drag
        job = scope.launch { animate(Offset.VectorConverter, start, Offset.Zero, animationSpec = spec) { v, _ -> drag = v } }
    }

    /**
     * A finger takes the tile: whatever was moving it stops where it is. A move still under way is folded into the
     * drag, so letting go springs back to its place instead of leaving it stranded partway.
     */
    fun grab() {
        val v = visual()
        val t = target
        job?.cancel()
        if (v != null && t != null) {
            // Its position where it was cut short goes on in the drag; a size still changing settles at once (rare:
            // a grab in the few frames of a swap).
            drag = Offset(v.center.x - t.center.x, v.center.y - t.center.y)
            from = null
            progress = 1f
        }
    }

    /** How the tile is drawn now: one scale, the centre's shift, and the visible rectangle in the tile's own space. */
    internal fun frame(): Frame? {
        val t = target ?: return null
        val v = visual() ?: return null
        if (t.width <= 0f || t.height <= 0f) return null
        val s = maxOf(v.width / t.width, v.height / t.height)
        val w = v.width / s
        val h = v.height / s
        val clip = Rect(Offset((t.width - w) / 2, (t.height - h) / 2), Size(w, h))
        return Frame(s, Offset(v.center.x - t.center.x, v.center.y - t.center.y), clip, radius() / s)
    }

    internal class Frame(val scale: Float, val shift: Offset, val clip: Rect, val radius: Float)
}

/**
 * Hands the tile's rounded crop (the one [tileMotion] draws) to the video inside it, so a native video view is cut to
 * the same shape, at rest and through a swap (see [LocalVideoClip]).
 */
@Composable
internal fun TileVideoClip(motion: TileMotion, content: @Composable () -> Unit) {
    val clip = remember(motion) { { motion.frame()?.let { VideoClip(it.clip, it.radius) } } }
    CompositionLocalProvider(LocalVideoClip provides clip, content = content)
}

/**
 * Draws the tile through [motion]: from its old place and size to its new one. [radius] is its resting corner radius
 * in pixels (0 for a full-screen picture); [hairline] draws H3's thin outline at screen size while it is rounded.
 */
internal fun Modifier.tileMotion(motion: TileMotion, still: Boolean, spec: AnimationSpec<Float>, radius: Float = 0f, hairline: Color? = null, hairlinePx: Float = 1f): Modifier =
    this
        .onPlaced { c -> motion.placed(Rect(c.positionInParent(), c.size.toSize()), radius, still, spec) }
        .graphicsLayer {
            val f = motion.frame() ?: return@graphicsLayer
            transformOrigin = TransformOrigin.Center
            scaleX = f.scale
            scaleY = f.scale
            translationX = f.shift.x
            translationY = f.shift.y
            shape = RectShape(f.clip, f.radius)
            clip = true
        }
        .then(
            if (hairline == null) Modifier
            else Modifier.drawWithContent {
                drawContent()
                val f = motion.frame() ?: return@drawWithContent
                if (f.radius <= 0f) return@drawWithContent
                val stroke = hairlinePx / f.scale
                drawRoundRect(
                    hairline, topLeft = f.clip.topLeft + Offset(stroke / 2, stroke / 2),
                    size = Size(f.clip.width - stroke, f.clip.height - stroke),
                    cornerRadius = CornerRadius(f.radius), style = Stroke(stroke),
                )
            },
        )

/** A rounded rectangle at a given place inside the layer (the visible part of a moving tile). */
private class RectShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        if (radius <= 0f && rect.left <= 0f && rect.top <= 0f && rect.width >= size.width && rect.height >= size.height) Outline.Rectangle(Rect(Offset.Zero, size))
        else Outline.Rounded(RoundRect(rect, CornerRadius(radius)))

    override fun equals(other: Any?) = other is RectShape && other.rect == rect && other.radius == radius
    override fun hashCode() = rect.hashCode() * 31 + radius.hashCode()
}
