package com.circaso.feature.messaging.presentation.map

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.core.platform.GeoPoint
import com.circaso.feature.messaging.data.repository.MapPalette
import com.circaso.feature.messaging.data.repository.metersBetween
import com.circaso.feature.messaging.presentation.LocalMapPicture
import com.circaso.feature.messaging.presentation.mapPalette
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** One person (or one shared place) on the map. */
data class MapPin(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val avatarUrl: String?,
    /** Moving now (a live share that hasn't ended); still pins show without the pulse. */
    val live: Boolean,
    /** A plain shared place rather than a person: the Circaso pin instead of a photo. */
    val place: Boolean = false,
    /** The person holding the phone: an ink ring and a "You" tag, so it is never mistaken for someone sharing. */
    val me: Boolean = false,
)

/** The straight line between the person holding the phone and whoever (or whatever place) the map is focused on. */
data class MapLine(val fromLatitude: Double, val fromLongitude: Double, val toLatitude: Double, val toLongitude: Double)

/**
 * The native map under Circaso's pins. The platform map (Google Maps on both platforms) reports its projection and
 * camera here; the pins are ordinary Compose views laid over it, so a profile photo loads like it does anywhere else.
 */
class MapCamera {
    /** Bumped on every camera change, so the pins (which read it) move with the map in the same frame. */
    var moves by mutableLongStateOf(0L)
    internal var projector: ((Double, Double) -> Offset?)? by mutableStateOf(null)

    /** False on the fallback picture, which can't pan or zoom: controls that would move the map are hidden. */
    var canMove by mutableStateOf(false)
        internal set
    internal var mover: ((Double, Double, Float?) -> Unit)? = null
    internal var fitter: ((List<Pair<Double, Double>>, Boolean) -> Unit)? = null

    /** What the screen lays over the map's top and bottom edges (its bar, its panel): framing keeps clear of them. */
    var topInset by mutableStateOf(0.dp)
    var bottomInset by mutableStateOf(0.dp)

    /** Where a point is on screen, in pixels from the map's top left; null before the map has laid out. */
    fun project(latitude: Double, longitude: Double): Offset? {
        moves
        return projector?.invoke(latitude, longitude)
    }

    fun moveTo(latitude: Double, longitude: Double, zoom: Float? = null) = mover?.invoke(latitude, longitude, zoom)

    /**
     * Frames every point, with room for the pins and the panel; [animate] false jumps there (the first framing).
     * Points that are almost on top of each other get street level around their middle rather than the map's
     * deepest zoom, where there is nothing left to recognise.
     */
    fun fit(points: List<Pair<Double, Double>>, animate: Boolean = true) {
        when (val plan = fitPlan(points)) {
            is FitPlan.Bounds -> fitter?.invoke(plan.points, animate)
            is FitPlan.Centre -> if (animate || points.size > 1) moveTo(plan.latitude, plan.longitude, plan.zoom)
            null -> Unit
        }
    }
}

/** How a set of points is framed: by its bounds, or (one point, or several within [CloseTogetherMeters]) around a centre. */
internal sealed interface FitPlan {
    data class Bounds(val points: List<Pair<Double, Double>>) : FitPlan
    data class Centre(val latitude: Double, val longitude: Double, val zoom: Float) : FitPlan
}

/** Closer together than this, bounds would zoom in until the streets disappear. */
internal const val CloseTogetherMeters = 150.0

internal fun fitPlan(points: List<Pair<Double, Double>>): FitPlan? {
    val distinct = points.distinct()
    if (distinct.isEmpty()) return null
    if (distinct.size == 1) return FitPlan.Centre(distinct[0].first, distinct[0].second, 16f)
    val south = distinct.minOf { it.first }
    val north = distinct.maxOf { it.first }
    val west = distinct.minOf { it.second }
    val east = distinct.maxOf { it.second }
    val span = metersBetween(GeoPoint(south, west), GeoPoint(north, east))
    return if (span < CloseTogetherMeters) FitPlan.Centre((south + north) / 2, (west + east) / 2, 17f) else FitPlan.Bounds(distinct)
}

/**
 * Google Maps on this platform, styled with [styleJson], reporting to [camera]. [line] is drawn by the map itself
 * (a dashed geodesic in [lineColor]), under Circaso's pins. False when it isn't set up (no key).
 */
@Composable
internal expect fun PlatformMap(camera: MapCamera, styleJson: String, startLatitude: Double, startLongitude: Double, line: MapLine?, lineColor: Color, modifier: Modifier): Boolean

/** The line's stroke, and its dashes: broken, so it never reads as a road route. */
internal val MapLineWidth = 3.dp
internal val MapLineDash = 8.dp
internal val MapLineGap = 6.dp

/**
 * The map with everyone's pins. Without a native map (no key in this build) it is the server's map picture of the
 * first pin's surroundings, with the pins placed on it by the same Web Mercator maths Google uses.
 */
@Composable
fun CircasoMap(pins: List<MapPin>, camera: MapCamera, modifier: Modifier = Modifier, line: MapLine? = null, onPinTap: (MapPin) -> Unit = {}) {
    val c = CircasoTheme.colors
    val style = remember(c) { mapStyleJson(c.mapPalette()) }
    val start = pins.firstOrNull()
    val still = CircasoTheme.reduceMotion
    // Ink is the one colour that stands clear of the map's land, roads and water in all four themes.
    val lineColor = c.ink.copy(alpha = 0.8f)
    Box(modifier) {
        // The line's ends glide exactly as the pins do, so it never leaves a pin behind mid-move.
        val glide = if (still) androidx.compose.animation.core.snap<Float>() else tween(CircasoMotion.BaseMs)
        val drawn = line?.let {
            val fromLat by androidx.compose.animation.core.animateFloatAsState(it.fromLatitude.toFloat(), glide, label = "lineFromLat")
            val fromLng by androidx.compose.animation.core.animateFloatAsState(it.fromLongitude.toFloat(), glide, label = "lineFromLng")
            val toLat by androidx.compose.animation.core.animateFloatAsState(it.toLatitude.toFloat(), glide, label = "lineToLat")
            val toLng by androidx.compose.animation.core.animateFloatAsState(it.toLongitude.toFloat(), glide, label = "lineToLng")
            MapLine(fromLat.toDouble(), fromLng.toDouble(), toLat.toDouble(), toLng.toDouble())
        }
        val native = PlatformMap(camera, style, start?.latitude ?: 0.0, start?.longitude ?: 0.0, drawn, lineColor, Modifier.fillMaxSize())
        if (!native && start != null) {
            StaticMapUnder(pins, camera)
            // The picture can't draw a line of its own: Compose draws it with the picture's projection.
            if (drawn != null) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val from = camera.project(drawn.fromLatitude, drawn.fromLongitude) ?: return@Canvas
                val to = camera.project(drawn.toLatitude, drawn.toLongitude) ?: return@Canvas
                drawLine(
                    lineColor, from, to, strokeWidth = MapLineWidth.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(MapLineDash.toPx(), MapLineGap.toPx())),
                )
            }
        }
        pins.forEach { pin ->
            key(pin.id) {
                // A live update glides the pin to its new place (instant under reduced motion).
                val lat by androidx.compose.animation.core.animateFloatAsState(pin.latitude.toFloat(), if (still) androidx.compose.animation.core.snap() else tween(CircasoMotion.BaseMs), label = "lat")
                val lng by androidx.compose.animation.core.animateFloatAsState(pin.longitude.toFloat(), if (still) androidx.compose.animation.core.snap() else tween(CircasoMotion.BaseMs), label = "lng")
                PinView(
                    pin,
                    Modifier.offset {
                        val p = camera.project(lat.toDouble(), lng.toDouble()) ?: return@offset IntOffset(-10_000, -10_000)
                        // The pin's point sits on the place: centred across, its tip at the bottom.
                        IntOffset(p.x.roundToInt() - (PinWidth / 2).roundToPx(), p.y.roundToInt() - PinHeight.roundToPx())
                    },
                    onTap = { onPinTap(pin) },
                )
            }
        }
    }
}

private val PinWidth = 52.dp
private val PinHeight = 62.dp

/**
 * No native map: the server's picture of the area around every pin, at the largest zoom that fits them all (street
 * level for one), with a projection worked out from the same centre and zoom so each pin lands on its place.
 */
@Composable
private fun StaticMapUnder(pins: List<MapPin>, camera: MapCamera) {
    val load = LocalMapPicture.current
    val c = CircasoTheme.colors
    val palette = remember(c) { c.mapPalette() }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bgGrouped)) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        // Google draws at most 640 points a side; a bigger screen gets the same picture scaled up.
        val scale = max(1f, max(wDp, hDp) / 640f)
        val askW = (wDp / scale).toInt()
        val askH = (hDp / scale).toInt()
        val points = pins.map { mercator(it.latitude, it.longitude) }
        val frame = remember(points, askW, askH) { fallbackFrame(points, askW, askH) }
        val picture by produceState<ByteArray?>(null, frame, askW, askH, palette, load) {
            if (load != null) value = load(frame.latitude, frame.longitude, askW, askH, frame.zoom, palette)
        }
        picture?.let { coil3.compose.AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        val worldPx = 256.0 * 2.0.pow(frame.zoom) * scale * density.density
        val wPx = wDp * density.density
        val hPx = hDp * density.density
        androidx.compose.runtime.DisposableEffect(frame, wPx, hPx, worldPx) {
            camera.canMove = false
            camera.projector = { pLat, pLng ->
                val p = mercator(pLat, pLng)
                Offset((wPx / 2 + (p.first - frame.x) * worldPx).toFloat(), (hPx / 2 + (p.second - frame.y) * worldPx).toFloat())
            }
            camera.moves++
            onDispose { camera.projector = null }
        }
    }
}

/** Where the fallback picture is centred (as degrees and as Mercator x, y) and its zoom. */
internal class FallbackFrame(val latitude: Double, val longitude: Double, val x: Double, val y: Double, val zoom: Int)

/** The middle of all [points] and the largest zoom (3 to 16) at which they fit in [w] × [h] points with a margin for the pins. */
internal fun fallbackFrame(points: List<Pair<Double, Double>>, w: Int, h: Int): FallbackFrame {
    val minX = points.minOf { it.first }
    val maxX = points.maxOf { it.first }
    val minY = points.minOf { it.second }
    val maxY = points.maxOf { it.second }
    val x = (minX + maxX) / 2
    val y = (minY + maxY) / 2
    // Room for a pin (62 points tall) on every side.
    val usableW = (w - 2 * 64).coerceAtLeast(32).toDouble()
    val usableH = (h - 2 * 64).coerceAtLeast(32).toDouble()
    var zoom = 16
    while (zoom > 3 && ((maxX - minX) * 256 * 2.0.pow(zoom) > usableW || (maxY - minY) * 256 * 2.0.pow(zoom) > usableH)) zoom--
    val lng = x * 360.0 - 180.0
    val lat = kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2 * y))) * 180 / PI
    return FallbackFrame(lat, lng, x, y, zoom)
}

/** Web Mercator, as a fraction of the world: x and y in 0..1. */
internal fun mercator(lat: Double, lng: Double): Pair<Double, Double> {
    val x = (lng + 180.0) / 360.0
    val r = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180
    val y = (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2
    return x to y
}

/**
 * A person's pin: their photo (or initials) in a ring with a point beneath, WhatsApp-style. A live share pulses
 * softly behind; a share that ended, or a place, is still.
 */
@Composable
private fun PinView(pin: MapPin, modifier: Modifier, onTap: () -> Unit) {
    val c = CircasoTheme.colors
    val reduce = CircasoTheme.reduceMotion
    Box(
        modifier.size(PinWidth, PinHeight).pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = onTap)
            .semantics {
                contentDescription = when {
                    pin.me -> if (pin.live) "You, sharing live location" else "You. Only you can see this"
                    pin.place -> "Shared place"
                    else -> pin.name + if (pin.live) ", sharing live location" else ", last location"
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // My own pin pulses only while I'm sharing: the pulse means "others can see this moving".
        if (pin.live && !reduce) {
            val pulse = rememberInfiniteTransition(label = "pin")
            val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(CircasoMotion.MomentMs * 4), RepeatMode.Restart), label = "pulse")
            Box(
                Modifier.size(PinWidth).graphicsLayer { scaleX = 0.8f + t * 0.5f; scaleY = 0.8f + t * 0.5f; alpha = (1f - t) * 0.35f }
                    .clip(CircasoShapes.circle).background(c.action),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.padding(top = 4.dp).size(44.dp).clip(CircasoShapes.circle).background(c.surface)
                    .border(3.dp, if (pin.me) c.ink else if (pin.live || pin.place) c.action else c.inkTertiary, CircasoShapes.circle).padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (pin.place) {
                    Box(Modifier.fillMaxSize().clip(CircasoShapes.circle).background(c.action), contentAlignment = Alignment.Center) {
                        com.circaso.designsystem.components.CircasoIconImage(com.circaso.designsystem.components.CircasoIcon.Pin, c.onAction, Modifier.size(18.dp))
                    }
                } else {
                    Avatar(pin.name, Modifier.graphicsLayer { alpha = if (pin.live || pin.me) 1f else 0.6f }, size = 38.dp, imageUrl = pin.avatarUrl)
                }
            }
            PinPoint(if (pin.me) c.ink else if (pin.live || pin.place) c.action else c.inkTertiary)
        }
        // "You", hung under the pin's tip (outside its box, so the tip still sits on the place).
        if (pin.me) BasicText(
            "You",
            style = CircasoTheme.typography.caption2.copy(color = c.bg, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            modifier = Modifier.align(Alignment.TopCenter).wrapContentSize(unbounded = true).offset(y = PinHeight + 2.dp)
                .clip(CircasoShapes.pill).background(c.ink).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun PinPoint(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(12.dp, 10.dp)) {
        val path = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }
        drawPath(path, color)
    }
}

/** Google's map style JSON from Circaso's palette: the same rules as the server's preview pictures. */
internal fun mapStyleJson(p: MapPalette): String {
    fun rule(feature: String?, element: String?, vararg stylers: String) = buildString {
        append('{')
        feature?.let { append("\"featureType\":\"").append(it).append("\",") }
        element?.let { append("\"elementType\":\"").append(it).append("\",") }
        append("\"stylers\":[").append(stylers.joinToString(",") { "{$it}" }).append("]}")
    }
    val off = "\"visibility\":\"off\""
    fun color(hex: String) = "\"color\":\"#$hex\""
    return listOf(
        rule(null, "geometry", color(p.land)),
        rule(null, "labels.icon", off),
        rule(null, "labels.text.fill", color(p.label)),
        rule(null, "labels.text.stroke", color(p.land)),
        rule("poi", null, off),
        rule("transit", null, off),
        rule("administrative", "geometry", off),
        rule("road", "geometry", color(p.road)),
        rule("road.highway", "geometry", color(p.highway)),
        rule("road.local", "labels", off),
        rule("water", "geometry", color(p.water)),
        rule("water", "labels", off),
    ).joinToString(",", "[", "]")
}
