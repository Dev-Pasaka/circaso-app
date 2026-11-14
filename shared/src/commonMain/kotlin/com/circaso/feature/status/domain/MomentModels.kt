package com.circaso.feature.status.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** What a moment is. Its content is end to end encrypted; the server sees only who it is for and when it expires. */
enum class MomentKind { Text, Image, Video, Voice }

/** A text moment's font (cycled by Aa); the background is a palette id (presentation's `MomentPalette`). */
enum class MomentTextStyle { Bold, Regular, Serif, Rounded, Mono, Script }

/** The encrypted media a moment points at: the server's id, the key and digest only the audience has. */
data class MomentMedia(
    val mediaId: String,
    val key: String,
    val digest: String,
    val mime: String,
    val sizeBytes: Long,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** One readable moment on this phone (decrypted, or one of mine). */
data class Moment(
    val momentId: String,
    val posterId: String,
    val kind: MomentKind,
    val text: String? = null,
    /** A `MomentPalette` id; unknown ids (from newer phones) fall back to Ink. */
    val background: String = "Ink",
    val style: MomentTextStyle = MomentTextStyle.Bold,
    val caption: String? = null,
    val media: MomentMedia? = null,
    /** A tiny blurred JPEG (base64) that holds the place while the media loads (V5). */
    val thumb: String? = null,
    val postedAt: Long,
    val expiresAt: Long,
    val mine: Boolean,
    val viewed: Boolean,
    val reacted: Boolean,
    val viewCount: Int = 0,
    /** My emoji on someone else's moment; on mine, how many viewers reacted. */
    val reaction: String? = null,
    val reactionCount: Int = 0,
    /** A voice moment's waveform as recorded (0..1 each, about [VoiceLevelCount]); null for older or picked audio. */
    val levels: List<Float>? = null,
)

/**
 * This moment as a chat quotes it (V8): a text reply and money sent for it (2026-11-14) both carry this, so the chat
 * keeps the picture and the words after the moment ends.
 */
fun Moment.toQuote() = com.circaso.core.crypto.MomentQuote(momentId, kind.name.uppercase(), (text ?: caption)?.take(140), thumb, expiresAt)

/** How many bars a voice moment's waveform keeps (sampled while recording, sent in the encrypted body). */
const val VoiceLevelCount = 48

/**
 * Evens out the levels sampled while recording into [count] bars (the loudest sample in each slice), scaled so the
 * loudest bar is full height. Empty in, empty out.
 */
fun voiceLevels(samples: List<Float>, count: Int = VoiceLevelCount): List<Float> {
    if (samples.isEmpty() || count <= 0) return emptyList()
    val bars = List(count) { i ->
        val from = i * samples.size / count
        val to = maxOf(from + 1, (i + 1) * samples.size / count)
        samples.subList(from.coerceAtMost(samples.size - 1), to.coerceAtMost(samples.size)).maxOrNull() ?: 0f
    }
    val top = bars.maxOrNull()?.takeIf { it > 0f } ?: return List(count) { 0f }
    return bars.map { (it / top).coerceIn(0f, 1f) }
}

/**
 * My moment that hasn't reached the server yet (M6): posting, waiting for a connection, or failed. [progress] is how
 * much of its media has gone up (0..1, bytes sent over bytes in all) while it uploads; null before the upload starts
 * and for a text moment, which has nothing to upload.
 */
data class PendingMoment(val localId: String, val kind: MomentKind, val state: PendingState, val error: String?, val createdAt: Long, val progress: Float? = null)

enum class PendingState { Posting, Waiting, Failed }

/**
 * Someone's live moment this phone can't open yet. On a new phone the copies the poster sent were sealed for the old
 * phone's keys, so this one asks the poster's phone for fresh ones (key request); until they come the moment is
 * [LockState.Waiting]. [LockState.Unavailable]: the poster's phone answered without it (shared from their earlier
 * phone, which no phone of theirs can seal again), so it never opens here.
 */
data class LockedMoment(val momentId: String, val posterId: String, val postedAt: Long, val expiresAt: Long, val state: LockState)

enum class LockState { Waiting, Unavailable }

/**
 * One person's ring in the Moments list (the Moments chip in Chats): their live moments, oldest first, and the ones
 * this phone can't open yet ([locked]), so a new phone shows the person instead of nothing.
 */
data class MomentRing(
    val posterId: String,
    val name: String,
    val avatarUrl: String?,
    val moments: List<Moment>,
    val muted: Boolean,
    val locked: List<LockedMoment> = emptyList(),
) {
    val unseen: Boolean get() = moments.any { !it.viewed }
    val latestAt: Long get() = (moments.map { it.postedAt } + locked.map { it.postedAt }).maxOrNull() ?: 0L
    /** Moments still on their way from the poster's phone. */
    val waiting: Int get() = locked.count { it.state == LockState.Waiting }
    /** Nothing here opens yet: the row explains instead of opening the viewer. */
    val allLocked: Boolean get() = moments.isEmpty() && locked.isNotEmpty()
}

/** Who sees my moments (P1). Chosen and kept on this phone; the server never learns anyone's contacts. */
enum class MomentAudienceMode { Contacts, ContactsExcept, OnlyShareWith }

data class MomentPrivacy(val mode: MomentAudienceMode = MomentAudienceMode.Contacts, val except: Set<String> = emptySet(), val only: Set<String> = emptySet())

data class MomentViewer(val userId: String, val viewedAt: Long, val reacted: Boolean, val reaction: String? = null)

/** What the composer hands over to post. Media is the plain file on this phone; it is encrypted before it leaves. */
sealed interface MomentDraft {
    data class Text(val text: String, val background: String, val style: MomentTextStyle) : MomentDraft
    data class Media(
        val kind: MomentKind,
        val bytes: ByteArray,
        val mime: String,
        val caption: String?,
        val thumb: ByteArray?,
        val durationMs: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        /** A voice moment's background (a `MomentPalette` id) and its recorded waveform. */
        val background: String? = null,
        val levels: List<Float>? = null,
    ) : MomentDraft
}

/** Loading the media of a moment for the viewer. */
sealed interface MomentMediaLoad {
    class Loaded(val bytes: ByteArray, val path: String?) : MomentMediaLoad
    /** Not on the phone and no connection (V6). */
    data object NeedsConnection : MomentMediaLoad
    /** Download or decryption failed after retries (V5b). */
    /** [gone]: the moment was deleted or expired (V7), not a fault. */
    class Failed(val requestId: String?, val gone: Boolean = false) : MomentMediaLoad
}

interface MomentsRepository {
    /** My moments and everyone else's I may see, from the phone; refreshed in the background. */
    val moments: Flow<List<Moment>>
    /** Others' live moments this phone can't open yet (a new phone), so their rings show while the keys come. */
    val locked: Flow<List<LockedMoment>> get() = kotlinx.coroutines.flow.flowOf(emptyList())
    val pending: Flow<List<PendingMoment>>
    val privacy: Flow<MomentPrivacy>
    val muted: Flow<Set<String>>

    /** ST4: fetch, decrypt and store. Offline it fails and the phone's copy stays. */
    suspend fun refresh(): AppResult<Unit>

    /** Posts through the outbox: optimistic, waits offline, retried with the same key (not money). */
    suspend fun post(draft: MomentDraft): AppResult<Unit>
    suspend fun retry(localId: String)
    suspend fun discard(localId: String)

    /** ST5. A view is recorded once; [reacted] changes the heart. */
    suspend fun markViewed(momentId: String, reacted: Boolean? = null): AppResult<Unit>
    /** My emoji on someone's moment (any emoji); null takes it back. Online-only. */
    suspend fun react(momentId: String, emoji: String?): AppResult<Unit>
    suspend fun viewers(momentId: String): AppResult<List<MomentViewer>>
    suspend fun delete(momentId: String): AppResult<Unit>
    suspend fun media(moment: Moment): MomentMediaLoad

    suspend fun setPrivacy(privacy: MomentPrivacy)
    suspend fun setMuted(userId: String, muted: Boolean)

    /**
     * The id of my moment each time a `status.viewed` frame tells of a view or a reaction on it (after the phone's
     * copy is refreshed), so an open viewer can show a new reaction as it lands.
     */
    val viewedFrames: Flow<String> get() = kotlinx.coroutines.flow.emptyFlow()

    /** A `status.*` frame from the realtime socket; [requesterId] comes with `status.key_requested`. */
    suspend fun onFrame(type: String, momentId: String?, posterId: String?, requesterId: String? = null)
}

/**
 * Which reactions on my moments this phone has already shown (keys from `reactionKey`: who and which emoji), so each
 * one splashes over my moment once, the first time I'm looking when it's there. Kept for live moments only.
 */
interface SeenReactions {
    suspend fun seen(momentId: String): Set<String>
    /** [keys] are shown on [momentId]; anything kept for a moment not in [live] is forgotten. */
    suspend fun markSeen(momentId: String, keys: Set<String>, live: Set<String>)

    /** For tests and previews: forgotten when the process ends. */
    class InMemory : SeenReactions {
        private val byMoment = mutableMapOf<String, Set<String>>()
        override suspend fun seen(momentId: String): Set<String> = byMoment[momentId].orEmpty()
        override suspend fun markSeen(momentId: String, keys: Set<String>, live: Set<String>) {
            byMoment[momentId] = byMoment[momentId].orEmpty() + keys
            byMoment.keys.retainAll(live + momentId)
        }
    }
}
