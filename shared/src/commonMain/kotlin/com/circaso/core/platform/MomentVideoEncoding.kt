package com.circaso.core.platform

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * How a moment's video is encoded when it has to be (Pasaka, 2026-11-07: "the videos I upload on moments are not as
 * clear as the ones on the device"). One rule for both phones and for the camera, the trimmer and the shrink before
 * posting:
 *
 * - **Size:** the source's own, up to 1080 on the short side; never upscaled. Smaller only when the 32 MiB limit
 *   would leave too few bits for that size (see [MinBitsPerPixel]).
 * - **Codec:** H.264 High profile (the encoders' default where they support it). Not HEVC: every phone decodes H.264
 *   in hardware, and a moment must play on the cheapest phone it reaches.
 * - **Frame rate:** the source's, never changed.
 * - **Bit rate:** [BitRate1080p30] at 1080p30 (YouTube's figure for 1080p30 SDR), scaling with pixels and frame rate,
 *   never above the source's own, and never more than the 32 MiB limit allows for the clip's length (the trade-off:
 *   a full minute gets about 4 Mbit/s, which still holds 1080p30 well).
 */
object MomentVideoEncoding {
    const val MaxShortSide = 1080
    const val BitRate1080p30 = 8_000_000
    const val MaxBitRate = 14_000_000
    const val MinBitRate = 1_000_000
    /** What the AAC track takes from the budget (the encoders' usual 128 kbit/s stereo). */
    const val AudioBitRate = 128_000
    /** Below this many bits per pixel per frame H.264 starts to smear detail: drop a size step instead. */
    const val MinBitsPerPixel = 0.05
    /**
     * The share of the limit the clip aims for: VBR encoders overshoot, and MP4 boxes take some room. 0.85 since
     * 2026-11-14: at 0.92 a 57 s clip came out over 32 MiB from a Redmi's (MediaTek) hardware encoder, which runs
     * well past the bit rate it is asked for. An encode that still comes out over is done again smaller
     * ([smallerBudget]); this only makes the first pass fit on more phones.
     */
    const val BudgetShare = 0.85
    /** How much under the size it aims for a second pass goes, after the first came out over the limit. */
    const val RetryMargin = 0.9
    /** The least a pass is asked to fit in: below this a minute of video isn't worth sending. */
    const val MinBudgetBytes = 2L * 1024 * 1024

    /**
     * The byte budget for the next pass after one planned for [budgetBytes] came out at [outBytes], over [maxBytes]:
     * scaled by how far over the limit this phone's encoder really went, less [RetryMargin]. Planned through [plan],
     * a smaller budget lowers the bit rate and, once that would smear the picture, the size too, so even an encoder
     * that ignores the bit rate it is given comes out smaller.
     */
    fun smallerBudget(budgetBytes: Long, outBytes: Long, maxBytes: Long = MomentMediaMaxBytes): Long {
        if (outBytes <= 0) return budgetBytes
        val next = minOf(budgetBytes, maxBytes).toDouble() * maxBytes / outBytes * RetryMargin
        return next.roundToLong().coerceIn(minOf(MinBudgetBytes, maxBytes), maxBytes)
    }
    /** The short sides tried, largest first. */
    private val Steps = listOf(1080, 720, 540, 360)

    /** What the camera records at: the target for its own size, no length limit (a long clip is shrunk before posting). */
    fun cameraBitRate(width: Int = 1080, height: Int = 1920, frameRate: Float = 30f): Int = targetBitRate(width, height, frameRate)

    /** The bit rate a clip of [width]x[height] at [frameRate] deserves, before any limit. */
    fun targetBitRate(width: Int, height: Int, frameRate: Float): Int {
        val pixels = width.toDouble() * height / (1920.0 * 1080.0)
        val fps = (frameRate.takeIf { it.isFinite() && it > 0f } ?: 30f).coerceIn(1f, 120f) / 30.0
        return (BitRate1080p30 * pixels.pow(0.75) * fps.pow(0.6)).roundToInt().coerceIn(MinBitRate, MaxBitRate)
    }

    /**
     * The encode for a source of [width]x[height] (as shown, rotation applied) at [frameRate] and [sourceBitRate]
     * (video track, bits per second; null when unknown), cut to [durationMs], so the MP4 stays under [maxBytes].
     */
    fun plan(width: Int, height: Int, frameRate: Float?, sourceBitRate: Long?, durationMs: Long, maxBytes: Long = MomentMediaMaxBytes): VideoPlan {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        val fps = frameRate?.takeIf { it.isFinite() && it > 0f } ?: 30f
        val seconds = durationMs.coerceAtLeast(1_000) / 1000.0
        val budget = ((maxBytes * BudgetShare * 8) / seconds - AudioBitRate).roundToLong().coerceAtLeast(MinBitRate.toLong())
        val sourceShort = min(w, h)
        val sides = (Steps.filter { it < sourceShort } + min(sourceShort, MaxShortSide)).distinct().sortedDescending()
        var chosen: VideoPlan? = null
        for (side in sides) {
            val (ow, oh) = sized(w, h, side)
            var rate = min(targetBitRate(ow, oh, fps).toLong(), budget)
            sourceBitRate?.takeIf { it > 0 }?.let { rate = min(rate, it) }
            val plan = VideoPlan(ow, oh, rate.toInt().coerceAtLeast(MinBitRate))
            chosen = plan
            if (plan.bitRate >= MinBitsPerPixel * ow * oh * fps || sourceBitRate?.let { plan.bitRate >= it } == true) break
        }
        return chosen ?: VideoPlan(even(w), even(h), MinBitRate)
    }

    /** [w]x[h] scaled so the short side is [shortSide] (never up), both even as encoders need. */
    private fun sized(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val scale = min(1.0, shortSide.toDouble() / min(w, h))
        return even((w * scale).roundToInt()) to even((h * scale).roundToInt())
    }

    private fun even(v: Int) = max(2, v / 2 * 2)

    /**
     * Whether a [videoW]x[videoH] video should fill a [viewW]x[viewH] stage (cropped) or fit it (letterboxed). A
     * portrait clip on a phone fills, as short-form video should; a landscape one would lose most of its picture and
     * be blown up twice over, so it fits. [MaxFillZoom] is how much more than fitting filling may enlarge it.
     */
    fun fills(videoW: Int, videoH: Int, viewW: Int, viewH: Int): Boolean {
        if (videoW <= 0 || videoH <= 0 || viewW <= 0 || viewH <= 0) return true
        val fill = max(viewW.toDouble() / videoW, viewH.toDouble() / videoH)
        val fit = min(viewW.toDouble() / videoW, viewH.toDouble() / videoH)
        return fill / fit <= MaxFillZoom
    }

    /** A 9:16 clip on a 20:9 phone fills (1.25); 3:4 on 9:16 still fills (1.33); 16:9 on a phone fits (about 4). */
    const val MaxFillZoom = 1.4
}

/** A video encode: output size (even, as shown) and video bit rate in bits per second. */
data class VideoPlan(val width: Int, val height: Int, val bitRate: Int)
