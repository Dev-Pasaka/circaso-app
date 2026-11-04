package com.circaso.feature.assistant.data.remote

import com.circaso.feature.assistant.domain.AshaActivity
import com.circaso.feature.assistant.domain.AshaMood
import com.circaso.feature.assistant.domain.AshaReaction
import com.circaso.feature.assistant.domain.AshaScene
import com.circaso.feature.assistant.domain.AshaTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The mascot's optional fields on a status event (`tool_status` / `tool_done`, on the SSE stream and the live voice
 * socket; asha-next.md part B): `scene`, `mood`, `activity`, `reaction`, and `sensitive`. All additive: missing,
 * malformed or unknown values are ignored, and null comes back when there is nothing to use.
 */
object AshaTagsParser {
    fun parse(o: JsonObject): AshaTags? {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val tags = AshaTags(
            scene = AshaScene.parse(str("scene")),
            mood = AshaMood.parse(str("mood")),
            activity = AshaActivity.parse(str("activity")),
            reaction = AshaReaction.parse(str("reaction")),
            sensitive = (o["sensitive"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
        return tags.takeUnless { it.isEmpty }
    }
}
