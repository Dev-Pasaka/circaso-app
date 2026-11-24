package com.circaso.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import com.circaso.designsystem.motion.pressable
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.circaso.designsystem.theme.DarkColors
import com.circaso.designsystem.theme.DarkHighContrastColors
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.compose.LocalPlatformContext
import coil3.request.crossfade
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.LineHeightStyle
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.designsystem.platform.avatarPlatformTextStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The people whose identity Circaso verified (the verified badge, 2026-11-24), by user id, as last stored on this phone
 * (contacts, chat members, the person's own profile). Provided once at the app's root from Room, so every [Avatar]
 * given a `userId` draws the seal without each screen threading the flag through.
 */
val LocalVerifiedPeople = compositionLocalOf<Set<String>> { emptySet() }

/** What screen readers say for the seal (on the avatar, and on the profile's row). */
const val VerifiedLabel = "Verified"

/**
 * Initials avatar, with the person's picture over it once it loads (so offline, slow or failed loads still show a
 * proper avatar). The picture link is signed for one hour and its query changes on every profile load, so the image
 * is cached under the link without its query: the same picture is never downloaded twice.
 *
 * **The verified seal** (2026-11-24, redrawn 2026-11-24 after Pasaka's review): X's scalloped rosette in titanium with a
 * bold check, about 30 % of the avatar, its centre on the circle at 45° bottom-right (half over the photo, half off it),
 * with an even ring of ground between it and the photo. It is drawn when [verified] is true, or, when [verified] is left
 * null, when [userId] is among [LocalVerifiedPeople]. Screen readers hear "Verified" after the avatar.
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = CircasoSizes.avatarMd,
    imageUrl: String? = null,
    bytes: ByteArray? = null,
    /** The person this avatar is: their seal comes from [LocalVerifiedPeople]. Null for groups and things. */
    userId: String? = null,
    /** Overrides the lookup by [userId] when the screen already knows. */
    verified: Boolean? = null,
    /** The avatar sits on a black stage (a call, a moment): the seal takes the dark themes' silver whatever the theme. */
    onDarkStage: Boolean = false,
    /**
     * Applied to the round picture itself, under the seal: what must follow the circle (a clip for round press
     * feedback, a shadow, a ring) goes here, so it never cuts the seal off. [modifier] is the whole avatar's.
     */
    discModifier: Modifier = Modifier,
    /** A tap on the seal (a profile's large photo): opens what the badge means. Lists leave it to the row. */
    onSealClick: (() -> Unit)? = null,
    /**
     * The seal's ring painted in this colour instead of cleared to the ground: for an avatar that wears its own ring of
     * that colour over a busy ground (the You hero's page-coloured ring over its photo glow), so the two rings are one.
     */
    sealRing: Color? = null,
) {
    val c = CircasoTheme.colors
    val sealed = verified ?: (userId != null && userId in LocalVerifiedPeople.current)
    // "Shiru (cousin)" gives "SC"; a name that is only emoji shows its first emoji whole (never half of one).
    val initials = avatarInitials(name)
    val seal = sealSize(size)
    val sealInk = sealColors(onDarkStage)
    Box(modifier.size(size)) {
        Box(
            // The disc and what follows its circle (a shadow, a ring) in one layer, so the seal's gap is cut out of all
            // of them at once. The layer reaches past the avatar so a shadow or the seal itself is never clipped.
            if (sealed) Modifier.requiredSize(size + SealLayerMargin * 2).sealLayer(size, seal, sealInk, sealRing).padding(SealLayerMargin)
            else Modifier,
        ) {
            Box(
                discModifier.size(size).clip(CircasoShapes.circle).background(c.fillStrong),
                contentAlignment = Alignment.Center,
            ) {
                // Sized from the disc, not the person's text size (the disc doesn't grow), on one line, and centred on the
                // glyph itself so an emoji (whose font sits differently from Inter/SF) stays in the middle.
                val fontSize = with(LocalDensity.current) { (size * 0.36f).toSp() }
                BasicText(
                    initials, maxLines = 1, softWrap = false,
                    style = CircasoTheme.typography.headline.copy(
                        color = c.ink, fontSize = fontSize, lineHeight = fontSize,
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                        platformStyle = avatarPlatformTextStyle,
                    ),
                )
                if (bytes != null) {
                    // A picture only this phone has yet (a group photo before it is sent): drawn from memory.
                    AsyncImage(model = bytes, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
                } else if (imageUrl != null) {
                    val key = imageUrl.substringBefore('?')
                    // One file on disk; in memory one copy per size drawn, so a small chat-list avatar is never stretched
                    // into the large profile photo or the full-screen view (it looked soft).
                    AsyncImage(
                        model = ImageRequest.Builder(LocalPlatformContext.current).data(imageUrl).memoryCacheKey("$key@${size.value.toInt()}")
                            // While this size decodes, the chat list's copy stands in, so switching lists never flashes a photo in.
                            .placeholderMemoryCacheKey("$key@${CircasoSizes.avatarMd.value.toInt()}").diskCacheKey(key).crossfade(true).build(),
                        contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size),
                    )
                }
            }
        }
        if (sealed && onSealClick != null) {
            // A profile's large photo: a 44 dp target centred on the seal (drawn by the layer above). Screen readers
            // get the badge from the name's mark beside it ([VerifiedName]), so it is said once, not twice.
            val at = sealCenter(size) - CircasoSizes.controlMd / 2
            Spacer(
                Modifier.offset(at, at).size(CircasoSizes.controlMd)
                    .pressable(role = Role.Button, onClickLabel = "What this means", pressedScale = 1f, onClick = onSealClick)
                    .clearAndSetSemantics {},
            )
        } else if (sealed) {
            // The seal is drawn by the layer above; this is where it is for screen readers.
            val at = sealCenter(size) - seal / 2
            Spacer(Modifier.offset(at, at).size(seal).semantics { contentDescription = VerifiedLabel })
        }
    }
}

/**
 * The seal's diameter for an avatar of [avatar]: about 28–32 % of it through the common sizes (44 dp gives 14, 64
 * gives 20, 96 gives 28), never under 12 dp so it still reads on a 28–32 dp row, never over 32 dp so it doesn't
 * shout over a 112–120 dp profile photo.
 */
fun sealSize(avatar: Dp): Dp = (avatar * 0.26f + 3.dp).coerceIn(12.dp, 32.dp)

/**
 * Where the seal's centre is, from the avatar's top-left corner, on both axes: on the circle at 45° bottom-right, so
 * exactly half the seal is over the photo and half outside it, as X and Instagram place theirs.
 */
fun sealCenter(avatar: Dp): Dp = avatar / 2 + avatar / 2 * Cos45

/** The ring of ground between the seal and the photo: a tenth of the seal, 1.5–3 dp. */
fun sealGap(seal: Dp): Dp = (seal * 0.1f).coerceIn(1.5.dp, 3.dp)

private const val Cos45 = 0.70710677f

/** How far the seal's layer reaches past the avatar: room for a shadow or a ring around the disc, and the seal. */
private val SealLayerMargin = 16.dp

/** The seal's metal and its check: [VerifiedSeal]'s colours. */
@Immutable
data class SealColors(val top: Color, val bottom: Color, val check: Color)

/**
 * Titanium, as the money card: graphite on a light page, brushed silver on a dark one, the check in the card's ink.
 * On a black stage (a call, a moment) the dark theme's silver whatever the theme, so it never sinks into the black.
 */
@Composable
fun sealColors(onDarkStage: Boolean = false): SealColors {
    val theme = CircasoTheme.colors
    val c = if (onDarkStage && !theme.isDark) (if (theme.isHighContrast) DarkHighContrastColors else DarkColors) else theme
    // High contrast: one flat metal, the most contrast against the check and the ground.
    return if (c.isHighContrast) SealColors(c.moneyCard, c.moneyCard, c.onMoney)
    else SealColors(c.moneyCardSheen, c.moneyCard, c.onMoney)
}

/**
 * The avatar's layer: draws the disc, clears a clean circle of ground around where the seal goes (a true gap through
 * the photo, its ring and its shadow, so it is the colour of whatever the avatar sits on: a row, a sheet, glass, a
 * call's stage), then the seal in it. Pixel-snapped: an even diameter on a whole-pixel centre, so the check is sharp
 * and the ring even all round.
 */
private fun Modifier.sealLayer(avatar: Dp, seal: Dp, colors: SealColors, ring: Color?): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
        val margin = SealLayerMargin.toPx()
        val diameter = (seal.toPx() / 2f).roundToInt() * 2f
        val centre = (margin + sealCenter(avatar).toPx()).roundToInt().toFloat()
        val at = Offset(centre, centre)
        val gap = sealGap(seal).toPx().roundToInt().coerceAtLeast(1).toFloat()
        val shape = sealShapeFor(diameter, at)
        onDrawWithContent {
            drawContent()
            if (ring != null) drawCircle(ring, radius = diameter / 2f + gap, center = at)
            else drawCircle(Color.Black, radius = diameter / 2f + gap, center = at, blendMode = BlendMode.DstOut)
            drawSealShape(shape, colors)
        }
    }

/** The seal's outline and check, made once per size. */
internal class SealShape(val outline: Path, val check: Path, val stroke: Stroke, val top: Float, val bottom: Float)

/**
 * X's scalloped rosette, in Circaso's titanium: eight soft lobes (each a rounded swell meeting the next in a shallow
 * notch), a bold rounded check placed for the eye (a little left and low of the geometric centre, as a check's mass
 * sits).
 */
internal fun androidx.compose.ui.unit.Density.sealShapeFor(diameter: Float, centre: Offset): SealShape {
    val r = diameter / 2f
    val outline = Path()
    for (i in 0 until SealSteps) {
        val t = (i.toDouble() / SealSteps) * 2 * PI
        // 1 at the middle of a lobe, falling to a notch between two; the power rounds the lobes and keeps the notch crisp.
        val swell = abs(cos(SealLobes * t / 2)).pow(SealLobeRoundness)
        val radius = r * (1f - SealNotchDepth * (1f - swell.toFloat()))
        val angle = t - PI / 2
        val x = centre.x + (radius * cos(angle)).toFloat()
        val y = centre.y + (radius * sin(angle)).toFloat()
        if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
    }
    outline.close()
    val left = centre.x - r
    val top = centre.y - r
    val check = Path().apply {
        moveTo(left + diameter * 0.29f, top + diameter * 0.52f)
        lineTo(left + diameter * 0.44f, top + diameter * 0.665f)
        lineTo(left + diameter * 0.72f, top + diameter * 0.38f)
    }
    val stroke = Stroke(width = max(1.2.dp.toPx(), diameter * 0.12f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    return SealShape(outline, check, stroke, top, top + diameter)
}

internal fun DrawScope.drawSealShape(shape: SealShape, colors: SealColors) {
    val metal = if (colors.top == colors.bottom) SolidColor(colors.bottom)
    else Brush.verticalGradient(0f to colors.top, 0.55f to colors.bottom, 1f to colors.bottom, startY = shape.top, endY = shape.bottom)
    drawPath(shape.outline, metal)
    drawPath(shape.check, colors.check, style = shape.stroke)
}

/**
 * The verified seal on its own (the name's mark, the explanation sheet): the same rosette and check as on the avatar.
 * Decorative here: the caller says "Verified" for screen readers.
 */
@Composable
fun VerifiedSeal(size: Dp, modifier: Modifier = Modifier, onDarkStage: Boolean = false) {
    val colors = sealColors(onDarkStage)
    Spacer(
        modifier.size(size).drawWithCache {
            val diameter = (this.size.minDimension / 2f).roundToInt() * 2f
            val shape = sealShapeFor(diameter, Offset((this.size.width / 2f).roundToInt().toFloat(), (this.size.height / 2f).roundToInt().toFloat()))
            onDrawBehind { drawSealShape(shape, colors) }
        },
    )
}

private const val SealLobes = 8
private const val SealNotchDepth = 0.12f
private const val SealLobeRoundness = 0.6
private const val SealSteps = 192
