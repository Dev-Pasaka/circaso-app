package com.circaso.feature.assistant.data.remote

/** One server-sent event: its name (`message`, `tool_request`, `done`) and the data text. */
data class SseEvent(val name: String, val data: String)

/**
 * Turns the lines of a `text/event-stream` into events. Lines `event:` and `data:` build an event that ends at a blank
 * line; several `data:` lines are joined with a newline; comment lines (starting with `:`) and unknown fields are ignored.
 * A missing event name means `message`, as the format says.
 */
class SseParser {
    private var name: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Feed one line (without its line ending). Returns a finished event when this line was the blank line ending one. */
    fun feed(rawLine: String): SseEvent? {
        val line = rawLine.trimEnd('\r')
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> name = value
            "data" -> { if (hasData) data.append('\n'); data.append(value); hasData = true }
        }
        return null
    }

    /** The stream ended: an event with data but no closing blank line still counts. */
    fun finish(): SseEvent? = flush()

    private fun flush(): SseEvent? {
        if (!hasData) { name = null; return null }
        val event = SseEvent(name ?: "message", data.toString())
        name = null; data.clear(); hasData = false
        return event
    }
}
