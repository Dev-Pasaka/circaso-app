@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.circaso.feature.messaging.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.UIKitView
import platform.UIKit.UIView

/** A point on the map view, in UIKit points from its top left. */
class MapPoint(val x: Double, val y: Double)

/** One Google map view (GMSMapView) on the Swift side. */
interface NativeMap {
    val view: UIView
    fun project(latitude: Double, longitude: Double): MapPoint?
    /** [zoom] below zero keeps the current zoom. */
    fun moveTo(latitude: Double, longitude: Double, zoom: Double)
    fun fit(latitudes: List<Double>, longitudes: List<Double>, animated: Boolean)
    fun setStyle(json: String)

    /**
     * The dashed straight line through the points, in the colour given as red, green, blue and alpha (each 0 to 1);
     * fewer than two points removes it.
     */
    fun setLine(latitudes: List<Double>, longitudes: List<Double>, red: Double, green: Double, blue: Double, alpha: Double)

    /** What the screen lays over the map's top and bottom edges, in points: framing and centring keep clear of them. */
    fun setInsets(top: Double, bottom: Double)
}

/** Google Maps for iOS lives on the Swift side (`MapImpl.swift`), installed at launch like the other bridges. */
interface MapViewProvider {
    /** A map at the point, styled with [styleJson]; [onCamera] runs on every camera change. */
    fun makeMap(styleJson: String, latitude: Double, longitude: Double, onCamera: () -> Unit): NativeMap
}

object MapBridge {
    /** The Maps SDK key from local.properties; empty means no native map in this build. */
    val apiKey: String get() = MapsIosKey
    var provider: MapViewProvider? = null
}

/**
 * Google Maps (GMSMapView) behind Compose: Circaso's pins are drawn over it from its projection, so they track
 * every pan and zoom, and their photos load like anywhere else in the app.
 */
@Composable
internal actual fun PlatformMap(camera: MapCamera, styleJson: String, startLatitude: Double, startLongitude: Double, line: MapLine?, lineColor: Color, modifier: Modifier): Boolean {
    val provider = MapBridge.provider ?: return false
    val density = LocalDensity.current.density
    val map = remember(provider) { provider.makeMap(styleJson, startLatitude, startLongitude) { camera.moves++ } }
    UIKitView(factory = { map.view }, modifier = modifier)
    SideEffect {
        camera.canMove = true
        camera.projector = { lat, lng -> map.project(lat, lng)?.let { Offset((it.x * density).toFloat(), (it.y * density).toFloat()) } }
        camera.mover = { lat, lng, zoom -> map.moveTo(lat, lng, zoom?.toDouble() ?: -1.0) }
        camera.fitter = { points, animate -> map.fit(points.map { it.first }, points.map { it.second }, animate) }
    }
    LaunchedEffect(styleJson) { map.setStyle(styleJson) }
    LaunchedEffect(line, lineColor) {
        if (line == null) map.setLine(emptyList(), emptyList(), 0.0, 0.0, 0.0, 0.0)
        else map.setLine(
            listOf(line.fromLatitude, line.toLatitude), listOf(line.fromLongitude, line.toLongitude),
            lineColor.red.toDouble(), lineColor.green.toDouble(), lineColor.blue.toDouble(), lineColor.alpha.toDouble(),
        )
    }
    val top = camera.topInset.value.toDouble()
    val bottom = camera.bottomInset.value.toDouble()
    LaunchedEffect(top, bottom) { map.setInsets(top, bottom) }
    return true
}
