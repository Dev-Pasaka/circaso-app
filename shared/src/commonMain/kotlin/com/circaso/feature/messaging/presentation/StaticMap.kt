package com.circaso.feature.messaging.presentation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.circaso.designsystem.theme.CircasoColors
import com.circaso.feature.messaging.data.repository.MapPalette

/**
 * Circaso's map colours, from the theme tokens so dark and the contrast themes follow: grey land, quiet roads,
 * deeper water, secondary-ink labels. The server hides shops, bus stops and icons, so the Circaso pin is the one
 * thing that stands out.
 */
internal fun CircasoColors.mapPalette(): MapPalette {
    val dark = ink.luminance() > 0.5f
    val land = if (dark) surface else bgGrouped
    return MapPalette(
        land = hex(land),
        road = hex(if (dark) fill else surface),
        highway = hex(fillStrong),
        water = hex(if (dark) bg else fillStrong),
        label = hex(inkSecondary),
    )
}

/** Loads a map picture (MAP1) for the tiles below it; null in previews, where tiles keep their drawn grid. */
typealias MapPictureLoader = suspend (lat: Double, lng: Double, widthDp: Int, heightDp: Int, zoom: Int, palette: MapPalette) -> ByteArray?

/** The chat bubble's map: street level. */
internal const val TileZoom = 16

internal val LocalMapPicture = staticCompositionLocalOf<MapPictureLoader?> { null }

private fun hex(c: Color) = (c.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
