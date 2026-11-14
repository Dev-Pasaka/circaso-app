package com.circaso.feature.messaging.presentation.map

import com.circaso.core.platform.GeoPoint
import com.circaso.core.platform.LocationAccess
import com.circaso.feature.messaging.data.repository.metersBetween
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.Message
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One row of the map's panel: a person sharing (or who shared) live, or the place that was tapped.
 * [distanceMeters] is how far it is from the person holding the phone, in a straight line; null when that isn't
 * known (no location yet, or refused) and for their own share.
 */
data class LocationEntry(
    val message: Message.Media,
    val name: String,
    val avatarUrl: String?,
    val place: Boolean,
    val initialsName: String = name,
    val distanceMeters: Double? = null,
) {
    val id: Long get() = message.localId
    val point: GeoPoint get() = GeoPoint(message.details.latitude!!, message.details.longitude!!, message.details.accuracy)

    /** My own live share, moving or ended: nothing to measure to, and no directions to myself. */
    val mine: Boolean get() = message.outgoing && !place
}

/**
 * [loaded] false until the thread has been read once: nothing is drawn before then (no map at 0,0, no "nobody").
 * [focus] is the entry the line, the framing and the distance are about. [me] is where the person holding the phone
 * is: from their own live share when one is running ([sharingLive]), else from the phone, read for this screen only
 * and never sent. [access] says why [me] may be missing.
 */
data class LocationMapUiState(
    val chat: Chat? = null,
    val entries: List<LocationEntry> = emptyList(),
    val focus: Long? = null,
    val loaded: Boolean = false,
    val me: GeoPoint? = null,
    val sharingLive: Boolean = false,
    val access: LocationAccess = LocationAccess.Off,
    val myName: String = "You",
    val myAvatarUrl: String? = null,
) {
    val focused: LocationEntry? get() = entries.firstOrNull { it.id == focus }

    /** The line from me to whoever is focused; none until both ends are known. */
    val line: MapLine? get() {
        val from = me ?: return null
        val to = focused?.point ?: return null
        return MapLine(from.latitude, from.longitude, to.latitude, to.longitude, to = focus.toString())
    }

    /** How rough my own position is, in metres, when that is rough enough to matter to a distance; else null. */
    val roughness: Double? get() = me?.accuracyMeters?.takeIf { it > RoughFixMeters }

    /** True when there is someone or somewhere to measure to, so asking for location has a point. */
    val canMeasure: Boolean get() = entries.any { !it.mine }
}

/** A fix no better than this is "about": a city block. */
internal const val RoughFixMeters = 100.0

/**
 * The map's state from the chat's messages. [opened] is the message that was tapped; [focus] the entry chosen since
 * (the same at first). [fix] is the phone's own position when this screen may read it.
 *
 * Order: the tapped one first, then the others in the order their shares started. It stays put as people move
 * (rows are tap targets; nearest-first would shuffle them under a finger), and each row carries its own distance.
 */
internal fun locationMapState(
    chat: Chat?, messages: List<Message>, opened: Long, focus: Long, fix: GeoPoint?, access: LocationAccess, myUserId: String?, now: Long,
): LocationMapUiState {
    val located = messages.filterIsInstance<Message.Media>().filter { it.details.latitude != null && it.details.longitude != null }
    // One pin per person: their newest live share.
    val live = located.filter { it.details.isLive }.groupBy { it.senderId }.values.map { it.last() }
    val tapped = located.firstOrNull { it.localId == opened }?.takeIf { !it.details.isLive }
    // The tapped one first: the map opens on it.
    val ordered = (listOfNotNull(tapped) + live).sortedByDescending { it.localId == opened }
    // While I'm sharing live, I am where my share says I am: the same pin everyone else sees.
    val sharing = live.firstOrNull { it.outgoing && it.details.liveNow(now) }
    val me = sharing?.let { GeoPoint(it.details.latitude!!, it.details.longitude!!, it.details.accuracy) } ?: fix
    val entries = ordered.map { m ->
        val member = chat?.members?.firstOrNull { it.userId == m.senderId }
        val real = member?.displayName ?: m.senderName
        val entry = LocationEntry(m, if (m.outgoing) "You" else real, member?.avatarUrl, place = !m.details.isLive, initialsName = real)
        if (me == null || entry.mine) entry else entry.copy(distanceMeters = metersBetween(me, entry.point))
    }
    val others = entries.filter { !it.mine }
    val focused = others.firstOrNull { it.id == focus } ?: others.firstOrNull { it.id == opened } ?: others.firstOrNull()
    val self = chat?.members?.firstOrNull { it.userId == myUserId }
    return LocationMapUiState(
        chat, entries, focused?.id, loaded = true, me = me, sharingLive = sharing != null, access = access,
        myName = self?.displayName ?: "You", myAvatarUrl = self?.avatarUrl,
    )
}

/**
 * A distance the way a person would say it: "350 m", "2.4 km", "24 km", "1,240 km". Metric everywhere: the app has
 * no setting or convention for miles. Under ten metres is inside a phone's own error, so it isn't given a number.
 */
internal fun formatDistance(meters: Double): String {
    val m = meters.coerceAtLeast(0.0)
    if (m < 10) return "under 10 m"
    // To the nearest ten metres; 995 m and up is already "1.0 km".
    if (m < 995) return "${(m / 10).roundToInt() * 10} m"
    val tenths = (m / 100).roundToLong()
    if (tenths < 100) return "${tenths / 10}.${tenths % 10} km"
    return grouped((m / 1000).roundToLong()) + " km"
}

/**
 * "2.4 km away": a straight line, so never "route" or "drive". From a rough fix ([roughness] metres, as with
 * Android's "Approximate" location) it claims no more than the fix can know: a distance inside the fix's own error
 * is "Within about 2 km", and a longer one is rounded to the size of that error ("About 5 km away").
 */
internal fun distanceAway(meters: Double, roughness: Double? = null): String {
    if (roughness == null) return if (meters < 10) "Less than 10 m away" else formatDistance(meters) + " away"
    if (meters <= roughness) return "Within about " + formatDistance(roughness)
    // The largest of 100 m, 1 km, 10 km … that isn't bigger than the error.
    var step = 100.0
    while (step * 10 <= roughness) step *= 10
    return "About " + formatDistance((meters / step).roundToLong() * step) + " away"
}

private fun grouped(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

/** What the panel says about the phone's own location, under the rows. */
internal sealed interface LocationNote {
    /** Not asked yet: the reason, and the button that brings up the phone's own question. */
    data object Ask : LocationNote
    /** Refused: said once, with the way to change it. Never asked again. */
    data object Refused : LocationNote
    /** Location is off for the whole phone. */
    data object PhoneOff : LocationNote
    data object Finding : LocationNote
    /**
     * Known: what the distance is (a straight line) and, when my pin comes from the phone rather than a live share
     * here ([private]), that this map doesn't share it. (It says nothing wider than that: a live share may be
     * running in another chat.)
     */
    data class Known(val private: Boolean) : LocationNote
}

internal fun locationNote(state: LocationMapUiState): LocationNote? = when {
    !state.canMeasure -> null
    state.me != null -> LocationNote.Known(private = !state.sharingLive)
    state.access == LocationAccess.Granted -> LocationNote.Finding
    state.access == LocationAccess.NotAsked -> LocationNote.Ask
    state.access == LocationAccess.Denied -> LocationNote.Refused
    else -> LocationNote.PhoneOff
}
