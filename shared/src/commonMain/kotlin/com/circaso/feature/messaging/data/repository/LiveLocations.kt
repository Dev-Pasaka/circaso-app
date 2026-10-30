package com.circaso.feature.messaging.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.ChatPayload
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.GeoPoint
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.MessageEntity
import com.circaso.feature.messaging.data.local.MessagesDao
import com.circaso.feature.messaging.data.remote.MessagesRemote
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The platform half of live location: keeps fixes coming while a share is on, with the app in the background too,
 * and hands each to [LiveLocations.onFix]. It shows the platform's own notice while it runs (Android's ongoing
 * notification with Stop, iOS's blue location pill), so sharing is never silent.
 */
interface LiveTracker {
    /** Starts, or keeps running; [chats] is how many chats are being shared with, for the notice. */
    fun start(chats: Int)
    fun stop()
}

object NoLiveTracker : LiveTracker {
    override fun start(chats: Int) = Unit
    override fun stop() = Unit
}

/**
 * Live location, WhatsApp-style. A share is one message (a location with a [MediaDetails.liveId] and an end time)
 * that goes through the outbox like any other; after that, each move is a small end-to-end encrypted update sealed
 * like a message but never shown as one, never pushed and never queued: if one is lost, the next carries the current
 * point. Updates go out when the phone has moved [MinMoveMeters] or [HeartbeatMillis] has passed, never more often
 * than every [MinGapMillis]. Ending (Stop, or the time running out) is one more update, and the tracker stops when
 * no share is left.
 */
class LiveLocations(
    private val dao: MessagesDao,
    private val chats: ChatsDao,
    private val remote: MessagesRemote,
    private val sealer: ChatSealer,
    /** Saves and queues the share's own message (MediaMessages.sendLive); its local id, or null. */
    private val startShare: suspend (chatId: String, point: GeoPoint, liveId: String, until: Long) -> Long?,
    private val tracker: LiveTracker,
    private val now: () -> Long = ::nowMillis,
) {
    private val lock = Mutex()
    private val lastSent = mutableMapOf<String, Sent>()

    private class Sent(val point: GeoPoint, val at: Long)

    /** Starts sharing in [chatId] for [durationMs]; a share already running there is stopped first (one per chat). */
    suspend fun start(chatId: String, point: GeoPoint, durationMs: Long): AppResult<Unit> {
        active().filter { it.chatId == chatId }.forEach { stop(it.localId) }
        startShare(chatId, point, newIdempotencyKey(), now() + durationMs) ?: return AppResult.Failure(AppError.Unexpected(null))
        refreshTracker()
        return AppResult.Success(Unit)
    }

    /** A fix from the tracker: moves every running share's pin, here and (when due) for the people it's shared with. */
    suspend fun onFix(point: GeoPoint) = lock.withLock {
        val running = active()
        for (row in running) {
            val details = MediaDetails.read(row.text) ?: continue
            val liveId = details.liveId ?: continue
            val at = now()
            dao.setText(row.localId, MediaDetails.write(details.copy(latitude = point.latitude, longitude = point.longitude, accuracy = point.accuracyMeters, liveAt = at)))
            // Until the share's own message has gone, people can't place an update; it will carry the latest point.
            if (row.messageId == null || !due(liveId, point, at)) continue
            val update = ChatPayload(
                type = ChatPayload.LiveUpdate, liveId = liveId, liveAt = at,
                latitude = point.latitude, longitude = point.longitude, accuracy = point.accuracyMeters,
            )
            if (send(row.chatId, update)) lastSent[liveId] = Sent(point, at)
        }
        expireAndRefresh()
    }

    /** Stop sharing: the pin stays where it was, marked ended, for everyone. */
    suspend fun stop(localId: Long) {
        val row = dao.byLocalId(localId) ?: return
        val details = MediaDetails.read(row.text) ?: return
        val liveId = details.liveId ?: return
        if (details.liveEnded == true) return
        lastSent.remove(liveId)
        if (row.messageId == null) {
            // Never reached anyone: withdraw it (the outbox skips a row that is gone) rather than start and stop.
            dao.delete(row.localId)
        } else {
            val told = send(row.chatId, ChatPayload(type = ChatPayload.LiveStop, liveId = liveId, liveAt = now()))
            dao.setText(row.localId, MediaDetails.write(details.copy(liveEnded = true, liveStopSent = told)))
        }
        refreshTracker()
    }

    /** Stops that couldn't go (offline at the time) go now; called when the connection comes back. */
    suspend fun flushStops() {
        dao.ownLiveShares().forEach { row ->
            val details = MediaDetails.read(row.text) ?: return@forEach
            val liveId = details.liveId ?: return@forEach
            if (details.liveEnded != true || details.liveStopSent != false || row.messageId == null) return@forEach
            if (send(row.chatId, ChatPayload(type = ChatPayload.LiveStop, liveId = liveId, liveAt = now()))) {
                dao.setText(row.localId, MediaDetails.write(details.copy(liveStopSent = true)))
            }
        }
    }

    /** The tracker's clock: ends shares whose time ran out even when no fix comes (no signal indoors). */
    suspend fun tick() = lock.withLock { expireAndRefresh() }

    /** "Stop sharing" in the notification: every share on this phone ends. */
    suspend fun stopAll() = active().forEach { stop(it.localId) }

    /** After the app starts (or the tracker was restarted by the system): keeps the tracker in step with the shares. */
    suspend fun refreshTracker() {
        val running = active()
        if (running.isEmpty()) tracker.stop() else tracker.start(running.map { it.chatId }.distinct().size)
    }

    /** Shares whose time ran out are told to the others once, so their pins stop at "ended" rather than going stale. */
    private suspend fun expireAndRefresh() {
        val at = now()
        dao.ownLiveShares().forEach { row ->
            val details = MediaDetails.read(row.text) ?: return@forEach
            if (details.liveId != null && details.liveEnded != true && (details.liveUntil ?: 0) <= at) stop(row.localId)
        }
        refreshTracker()
    }

    private suspend fun active(): List<MessageEntity> =
        dao.ownLiveShares().filter { MediaDetails.read(it.text)?.liveNow(now()) == true }

    private fun due(liveId: String, point: GeoPoint, at: Long): Boolean {
        val last = lastSent[liveId] ?: return true
        if (at - last.at < MinGapMillis) return false
        return at - last.at >= HeartbeatMillis || metersBetween(last.point, point) >= MinMoveMeters
    }

    /** Best effort: false when it didn't go (offline, a refused seal); the next fix tries again. */
    private suspend fun send(chatId: String, payload: ChatPayload): Boolean {
        val chat = chats.chat(chatId) ?: return false
        val sealed = sealer.seal(chat, ChatPayload.encode(payload), newIdempotencyKey(), pushable = false)
        val request = (sealed as? AppResult.Success)?.value ?: return false
        return remote.send(chat.chatId, request, newIdempotencyKey()) is AppResult.Success
    }

    companion object {
        const val MinMoveMeters = 25.0
        const val MinGapMillis = 15_000L
        const val HeartbeatMillis = 120_000L

        /** WhatsApp's choices. */
        val Durations = listOf(15 * 60_000L, 60 * 60_000L, 8 * 60 * 60_000L)
    }
}

/** Great-circle distance (haversine), plenty for "has the phone moved 25 m". */
internal fun metersBetween(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val dLat = (b.latitude - a.latitude) * PI / 180
    val dLng = (b.longitude - a.longitude) * PI / 180
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(a.latitude * PI / 180) * cos(b.latitude * PI / 180) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * asin(sqrt(h))
}
