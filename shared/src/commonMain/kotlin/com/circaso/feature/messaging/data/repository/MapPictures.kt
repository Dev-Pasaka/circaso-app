package com.circaso.feature.messaging.data.repository

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** `RRGGBB` colours for a map picture: land, ordinary roads, highways, water and label text. */
data class MapPalette(val land: String, val road: String, val highway: String, val water: String, val label: String)

/**
 * MAP1's map pictures for location messages. The server draws them (the map key never ships in the app); the last
 * few are kept in memory so a thread scrolls without asking again. Nothing is written to disk.
 */
class MapPictures(private val api: CircasoApi) {
    private val lock = Mutex()
    private val kept = LinkedHashMap<String, ByteArray>()

    /** The picture around [lat],[lng], [widthDp] × [heightDp] points; null offline or when the server has no map. */
    suspend fun picture(lat: Double, lng: Double, widthDp: Int, heightDp: Int, zoom: Int, palette: MapPalette): ByteArray? {
        val w = widthDp.coerceIn(16, 640)
        val h = heightDp.coerceIn(16, 640)
        val key = "$lat,$lng,$w,$h,$zoom,$palette"
        lock.withLock { kept[key] }?.let { return it }
        val result = api.bytes {
            method = HttpMethod.Get
            url("/v1/maps/static")
            parameter("lat", lat); parameter("lng", lng); parameter("zoom", zoom); parameter("w", w); parameter("h", h)
            parameter("land", palette.land); parameter("road", palette.road); parameter("highway", palette.highway)
            parameter("water", palette.water); parameter("label", palette.label)
        }
        val bytes = (result as? AppResult.Success)?.value ?: return null
        lock.withLock {
            kept[key] = bytes
            if (kept.size > KEEP) kept.remove(kept.keys.first())
        }
        return bytes
    }

    private companion object {
        const val KEEP = 24
    }
}
