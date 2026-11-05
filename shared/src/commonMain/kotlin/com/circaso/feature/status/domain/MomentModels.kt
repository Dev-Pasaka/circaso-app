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

/** One person's ring in the Moments list (the Moments chip in Chats): their live moments, oldest first. */
data class MomentRing(val posterId: String, val name: String, val avatarUrl: String?, val moments: List<Moment>, val muted: Boolean) {
    val unseen: Boolean get() = moments.any { !it.viewed }
    val latestAt: Long get() = moments.maxOf { it.postedAt }
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

    /** A `status.*` frame from the realtime socket; [requesterId] comes with `status.key_requested`. */
    suspend fun onFrame(type: String, momentId: String?, posterId: String?, requesterId: String? = null)
}
