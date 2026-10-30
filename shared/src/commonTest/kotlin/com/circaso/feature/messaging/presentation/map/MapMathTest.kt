package com.circaso.feature.messaging.presentation.map

import com.circaso.feature.messaging.data.repository.MapPalette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pins on the fallback map and the style handed to Google both come from here. */
class MapMathTest {
    private fun near(a: Double, b: Double) = assertTrue(abs(a - b) < 1e-9, "$a vs $b")

    @Test fun mercatorPutsTheEquatorAndMeridiansWhereGoogleDoes() {
        val (x, y) = mercator(0.0, 0.0)
        near(0.5, x); near(0.5, y)
        near(0.0, mercator(0.0, -180.0).first)
        near(1.0, mercator(0.0, 180.0).first)
        assertTrue(mercator(-1.29, 36.82).second > 0.5, "south of the equator is further down")
        near(0.0, mercator(90.0, 0.0).second) // clamped to Web Mercator's edge, never infinite
    }

    @Test fun theStyleIsValidJsonAndHidesShopsAndTransit() {
        val rules = Json.parseToJsonElement(mapStyleJson(MapPalette("F2F2F7", "FFFFFF", "DCDCE0", "DCDCE0", "636366"))) as JsonArray
        val hidden = rules.mapNotNull { it.jsonObject["featureType"]?.jsonPrimitive?.content }
        assertTrue("poi" in hidden && "transit" in hidden)
        assertEquals("#F2F2F7", rules.first().jsonObject["stylers"].toString().substringAfter("\"color\":\"").take(7))
    }
}
