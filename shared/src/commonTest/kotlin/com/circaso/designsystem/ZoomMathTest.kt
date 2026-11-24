package com.circaso.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.ZoomMath
import com.circaso.designsystem.components.ZoomTransform
import com.circaso.designsystem.components.sealCenter
import com.circaso.designsystem.components.sealGap
import com.circaso.designsystem.components.sealSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The photo viewer's zoom (2026-11-24). The bug: zoom in, lift the fingers, and the photo went blank. On the event that
 * lifts the last finger no pointer is down, so the gesture's centroid is `Offset.Unspecified` (NaN); zooming "about"
 * it made the offset NaN, and a NaN translation draws nothing, for good.
 */
class ZoomMathTest {
    private val screen = Size(1080f, 2340f)

    private fun ZoomTransform.assertFinite() {
        assertTrue(scale.isFinite() && offset.x.isFinite() && offset.y.isFinite(), "not finite: $this")
    }

    @Test
    fun liftingTheFingersWhileZoomedKeepsThePicture() {
        val zoomed = ZoomTransform(3f, Offset(200f, -150f))
        // The last event of a pinch: no finger down, so no centroid (NaN), no zoom and no pan.
        val after = ZoomMath.gesture(zoomed, factor = 1f, focus = Offset.Unspecified, pan = Offset.Zero, container = screen)
        after.assertFinite()
        assertEquals(zoomed, after)
    }

    @Test
    fun nothingNotANumberGetsIn() {
        val t = ZoomTransform(2f, Offset(10f, 10f))
        listOf(
            ZoomMath.gesture(t, Float.NaN, Offset(500f, 500f), Offset.Zero, screen),
            ZoomMath.gesture(t, Float.POSITIVE_INFINITY, Offset(500f, 500f), Offset.Zero, screen),
            ZoomMath.gesture(t, 0f, Offset(500f, 500f), Offset.Zero, screen),
            ZoomMath.gesture(t, 1.2f, Offset(Float.NaN, 3f), Offset.Unspecified, screen),
            ZoomMath.gesture(t, 1.2f, Offset(500f, 500f), Offset(Float.NaN, Float.NaN), screen),
            ZoomMath.gesture(t, 1.2f, Offset(500f, 500f), Offset.Zero, Size.Zero),
            ZoomMath.settle(ZoomTransform(Float.NaN, Offset(Float.NaN, 0f)), screen),
            ZoomMath.doubleTap(ZoomTransform.Identity, Offset.Unspecified, screen),
        ).forEach { it.assertFinite() }
        assertEquals(ZoomTransform.Identity, ZoomMath.settle(ZoomTransform(Float.NaN, Offset.Zero), screen))
    }

    @Test
    fun thePointUnderTheFingersStaysThere() {
        val focus = Offset(300f, 900f)
        val after = ZoomMath.gesture(ZoomTransform.Identity, 2f, focus, Offset.Zero, screen)
        assertEquals(2f, after.scale)
        // A picture point p (from the centre) is drawn at p * scale + offset; the one under the fingers stays put.
        val p = focus - Offset(screen.width / 2, screen.height / 2)
        val drawn = p * after.scale + after.offset
        assertEquals(p.x, drawn.x, 0.01f)
        assertEquals(p.y, drawn.y, 0.01f)
    }

    @Test
    fun releaseSettlesInsideTheLimits() {
        // Pinched past the top and dragged past the edge: back to 5× and inside the picture's bounds.
        val over = ZoomTransform(5.6f, Offset(9_000f, -9_000f))
        val settled = ZoomMath.settle(over, screen)
        assertEquals(ZoomMath.MaxScale, settled.scale)
        val b = ZoomMath.bounds(settled.scale, screen)
        assertTrue(kotlin.math.abs(settled.offset.x) <= b.x && kotlin.math.abs(settled.offset.y) <= b.y)
        // Pinched below fitted: back to fitted, centred.
        assertEquals(ZoomTransform.Identity, ZoomMath.settle(ZoomTransform(0.9f, Offset(40f, 40f)), screen))
    }

    @Test
    fun aPinchCanOvershootALittleWhileTheFingersAreDown() {
        var t = ZoomTransform.Identity
        repeat(20) { t = ZoomMath.gesture(t, 1.5f, Offset(540f, 1170f), Offset.Zero, screen) }
        assertEquals(ZoomMath.MaxScale * ZoomMath.Overshoot, t.scale, 0.001f)
        repeat(40) { t = ZoomMath.gesture(t, 0.5f, Offset(540f, 1170f), Offset.Zero, screen) }
        assertEquals(ZoomMath.MinScale / ZoomMath.Overshoot, t.scale, 0.001f)
    }

    @Test
    fun aWidePhotoPansOnlyAsFarAsItsOwnEdges() {
        // A 4:3 landscape photo on a tall phone: fitted it is 1080 × 810, with black above and below.
        val fitted = ZoomMath.fit(Size(4000f, 3000f), screen)
        assertEquals(1080f, fitted.width, 0.01f)
        assertEquals(810f, fitted.height, 0.01f)
        val b = ZoomMath.bounds(2f, screen, fitted)
        assertEquals(540f, b.x, 0.01f) // twice as wide as the screen: half the extra each way
        assertEquals(0f, b.y) // at 2× it is still shorter than the screen: no vertical pan
        val panned = ZoomMath.settle(ZoomTransform(2f, Offset(0f, 400f)), screen, fitted)
        assertEquals(0f, panned.offset.y)
    }

    @Test
    fun draggingPastAnEdgeResistsAndHoldingStillDoesNotCreep() {
        var t = ZoomTransform(2f, Offset.Zero)
        val limit = ZoomMath.bounds(2f, screen).x
        t = ZoomMath.gesture(t, 1f, Offset(540f, 1170f), Offset(limit + 300f, 0f), screen)
        assertEquals(limit + 100f, t.offset.x, 0.01f)
        // Fingers still, no movement: it stays where it was (it used to be pulled back a little every frame).
        val still = ZoomMath.gesture(t, 1f, Offset(540f, 1170f), Offset.Zero, screen)
        assertEquals(t.offset.x, still.offset.x, 0.01f)
        // Coming back in is free.
        val back = ZoomMath.gesture(still, 1f, Offset(540f, 1170f), Offset(-200f, 0f), screen)
        assertEquals(limit - 100f, back.offset.x, 0.01f)
    }

    @Test
    fun doubleTapZoomsInAtTheTapAndBackOut() {
        val tap = Offset(800f, 600f)
        val inAt = ZoomMath.doubleTap(ZoomTransform.Identity, tap, screen)
        assertEquals(ZoomMath.DoubleTapScale, inAt.scale)
        val p = tap - Offset(screen.width / 2, screen.height / 2)
        val drawn = p * inAt.scale + inAt.offset
        assertEquals(p.x, drawn.x, 0.5f)
        assertEquals(ZoomTransform.Identity, ZoomMath.doubleTap(inAt, tap, screen))
    }

    @Test
    fun theSealIsAboutAThirdOfTheAvatarAndHalfOnTheRim() {
        assertEquals(12.dp, sealSize(28.dp))
        assertEquals(12.dp, sealSize(32.dp))
        assertEquals(32.dp, sealSize(120.dp))
        listOf(44.dp, 52.dp, 64.dp, 88.dp, 96.dp).forEach { a ->
            val ratio = sealSize(a) / a
            assertTrue(ratio in 0.28f..0.335f, "$a gives $ratio")
        }
        // Its centre is on the circle at 45°: half in, half out.
        val a = 96.dp
        val fromMiddle = (sealCenter(a) - a / 2).value * kotlin.math.sqrt(2f)
        assertEquals(a.value / 2, fromMiddle, 0.01f)
        assertTrue(sealGap(12.dp) >= 1.5.dp && sealGap(32.dp) <= 3.dp)
    }
}
