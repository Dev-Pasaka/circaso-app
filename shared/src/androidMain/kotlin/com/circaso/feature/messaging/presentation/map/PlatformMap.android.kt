package com.circaso.feature.messaging.presentation.map

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch

/**
 * Google Maps (maps-compose), Circaso-styled. Pins aren't Google markers: the camera's projection goes to
 * [MapCamera] and Compose draws them on top, so they track every pan and zoom.
 */
@Composable
internal actual fun PlatformMap(camera: MapCamera, styleJson: String, startLatitude: Double, startLongitude: Double, modifier: Modifier): Boolean {
    val context = LocalContext.current
    val hasKey = remember {
        runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank().not()
        }.getOrDefault(false)
    }
    if (!hasKey) return false
    val position = rememberCameraPositionState { this.position = CameraPosition.fromLatLngZoom(LatLng(startLatitude, startLongitude), 16f) }
    val scope = rememberCoroutineScope()
    val padding = with(LocalDensity.current) { 96.dp.roundToPx() }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = position,
        properties = remember(styleJson) { MapProperties(mapStyleOptions = MapStyleOptions(styleJson), isBuildingEnabled = false, isIndoorEnabled = false) },
        uiSettings = remember {
            MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false, compassEnabled = false, indoorLevelPickerEnabled = false)
        },
    )
    SideEffect {
        camera.canMove = true
        camera.projector = { lat, lng -> position.projection?.toScreenLocation(LatLng(lat, lng))?.let { Offset(it.x.toFloat(), it.y.toFloat()) } }
        camera.mover = { lat, lng, zoom ->
            scope.launch { runCatching { position.animate(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom ?: position.position.zoom), CameraMs) } }
        }
        camera.fitter = { points, animate ->
            val bounds = LatLngBounds.builder().apply { points.forEach { include(LatLng(it.first, it.second)) } }.build()
            val update = CameraUpdateFactory.newLatLngBounds(bounds, padding)
            scope.launch { runCatching { if (animate) position.animate(update, CameraMs) else position.move(update) } }
        }
    }
    LaunchedEffect(position) {
        snapshotFlow { position.position to position.projection }.collect { camera.moves++ }
    }
    return true
}

/** Camera moves: a touch longer than the app's "moment" so a pan across town reads as travel, not a jump. */
private val CameraMs = com.circaso.designsystem.theme.CircasoMotion.MomentMs * 2


