package com.circaso.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
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
 * **The verified seal** (2026-11-24): a small titanium rosette with a check on the disc's edge, bottom-right, with a
 * real gap cut out of the picture around it (not a painted ring), so it reads over any photo on any ground. It is drawn when [verified] is true, or, when [verified]
 * is left null, when [userId] is among [LocalVerifiedPeople]. Screen readers hear "Verified" after the avatar.
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
) {
    val c = CircasoTheme.colors
    val sealed = verified ?: (userId != null && userId in LocalVerifiedPeople.current)
    // "Shiru (cousin)" gives "SC"; a name that is only emoji shows its first emoji whole (never half of one).
    val initials = avatarInitials(name)
    val seal = sealSize(size)
    val sealAt = sealOffset(size, seal)
    Box(modifier.size(size)) {
        Box(
            discModifier.size(size)
                .then(if (sealed) Modifier.sealCutOut(sealAt, seal) else Modifier)
                .clip(CircasoShapes.circle).background(c.fillStrong),
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
        if (sealed) {
            VerifiedSeal(
                seal,
                Modifier.offset(sealAt, sealAt).semantics { contentDescription = VerifiedLabel },
                onDarkStage = onDarkStage,
            )
        }
    }
}

/**
 * The seal's size for an avatar of [avatar]: one curve, growing with the avatar but more slowly (28 dp gives 12, 44
 * gives 16, 64 gives 20, 96 gives 27), never under 12 dp (a seal, not a speck) nor over 28 dp (it must not shout over
 * a profile photo).
 */
fun sealSize(avatar: Dp): Dp = (avatar * 0.22f + 6.dp).coerceIn(12.dp, 28.dp)

/**
 * Where the seal's top-left corner goes (both axes): its centre on the disc's edge at 45°, pulled in by a quarter of
 * the seal, so it sits on the rim at every size and never leaves the avatar's square (nothing clips it).
 */
fun sealOffset(avatar: Dp, seal: Dp): Dp {
    val r = avatar / 2
    return r + (r - seal * 0.25f) * 0.7071f - seal / 2
}

/** Cuts the gap around the seal out of the picture itself, so the ground shows through, whatever it is. */
private fun Modifier.sealCutOut(at: Dp, seal: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val half = seal.toPx() / 2
        val center = Offset(at.toPx() + half, at.toPx() + half)
        drawCircle(Color.Black, radius = half + sealGap(half), center = center, blendMode = BlendMode.DstOut)
    }

private fun androidx.compose.ui.unit.Density.sealGap(radius: Float): Float = max(1.5.dp.toPx(), radius * 0.18f)

/**
 * The verified seal on its own: a twelve-point rosette in the app's titanium (the money card's metal, blended from its
 * sheen at the edges, so it is graphite on a light page and brushed silver on a dark one; always silver on a dark
 * stage) with a check in the card's ink. The gap around it is the avatar's ([sealCutOut]). Decorative here: the
 * caller says "Verified" for screen readers.
 */
@Composable
fun VerifiedSeal(size: Dp, modifier: Modifier = Modifier, onDarkStage: Boolean = false) {
    val theme = CircasoTheme.colors
    val c = if (onDarkStage && !theme.isDark) (if (theme.isHighContrast) DarkHighContrastColors else DarkColors) else theme
    val sheen = c.moneyCardSheen
    val card = c.moneyCard
    val ink = c.onMoney
    Spacer(
        modifier.size(size).drawWithCache {
            val r = this.size.minDimension / 2f
            val center = Offset(r, r)
            val outer = r
            val inner = outer * 0.86f
            val rosette = Path()
            val steps = RosettePoints * 2
            for (i in 0 until steps) {
                val angle = (i.toDouble() / steps) * 2 * PI - PI / 2
                val radius = if (i % 2 == 0) outer else inner
                val x = center.x + (radius * cos(angle)).toFloat()
                val y = center.y + (radius * sin(angle)).toFloat()
                if (i == 0) rosette.moveTo(x, y) else rosette.lineTo(x, y)
            }
            rosette.close()
            val metal = Brush.linearGradient(
                0f to sheen, 0.45f to card, 0.7f to card, 1f to sheen,
                start = Offset(0f, 0f), end = Offset(this.size.width, this.size.height),
            )
            val s = inner
            val check = Path().apply {
                moveTo(center.x - s * 0.42f, center.y + s * 0.02f)
                lineTo(center.x - s * 0.12f, center.y + s * 0.32f)
                lineTo(center.x + s * 0.45f, center.y - s * 0.30f)
            }
            val stroke = Stroke(width = max(1.2.dp.toPx(), s * 0.2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                drawPath(rosette, metal)
                drawPath(check, ink, style = stroke)
            }
        },
    )
}

private const val RosettePoints = 12
