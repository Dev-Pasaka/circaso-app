package com.circaso.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** What the phone's library holds that a moment can be made from (Pasaka, 2026-11-03: photos, videos and audio). */
enum class LibraryKind { Photo, Video, Audio }

/**
 * One item in the phone's library, for the recents strip under the camera (WhatsApp-style). [id] is the platform's own
 * reference (a content URI on Android, a PHAsset id on iOS); [title] names an audio file; [addedAt] (epoch millis, 0
 * when unknown) orders photos and videos together in the gallery's All tab. [sizeBytes] is the file's size where the
 * library says it without opening the file (2026-11-14: shown on a video's tile, and one too large to send is greyed
 * out before it is picked); null when unknown, which is never treated as too large.
 */
data class LibraryItem(val id: String, val kind: LibraryKind, val durationMs: Long? = null, val title: String? = null, val addedAt: Long = 0, val sizeBytes: Long? = null)

/**
 * How much of the library Circaso may read for one kind. [Limited]: only what the person chose (iPhone's limited
 * library, Android 14's "selected photos and videos"), so the screen offers Manage to choose more.
 */
enum class LibraryAccess { Granted, Limited, Denied }

/** The recents for one kind and how much the phone let us see; [items] are newest first. */
data class LibraryRecents(val items: List<LibraryItem>, val access: LibraryAccess)

/**
 * The phone's photos, videos and audio for Moments: the most recent ones for the strip and the gallery (this needs the
 * library permission for that kind, asked the first time that kind shows), and the system picker for everything else
 * (no permission).
 */
interface MediaLibrary {
    suspend fun access(): LibraryAccess

    /**
     * The newest [limit] items of [kind] and the access behind them. With [ask], the permission for that kind is asked
     * when it was never answered (Android asks for photos and videos together; audio has its own).
     */
    suspend fun recents(kind: LibraryKind, limit: Int = 60, ask: Boolean = true): LibraryRecents

    /** Photos and videos for the gallery (M3), asking for both in one prompt where the platform asks per kind. */
    suspend fun recentsVisual(limit: Int, ask: Boolean = true): Pair<LibraryRecents, LibraryRecents> =
        recents(LibraryKind.Photo, limit, ask) to recents(LibraryKind.Video, limit, ask)

    /**
     * Limited access: lets the person choose more (Android 14 asks again with its own "selected photos and videos"
     * sheet; iPhone shows the limited-library picker). Denied for good: the app's settings. Returns the access after.
     */
    suspend fun manage(kind: LibraryKind): LibraryAccess

    /**
     * Photos and videos together, for the one strip they share (2026-11-14): whichever of the two is short of full
     * access is asked for; denied for good opens the app's settings. Returns the weaker access of the two after.
     */
    suspend fun manageVisual(): LibraryAccess = manage(LibraryKind.Photo)

    /**
     * True where limited access leads to allowing everything (Android 14+, in the app's settings: asking again there
     * only reopens the selection screen); iPhone's limited library chooses more.
     */
    val limitedOffersAllowAll: Boolean get() = false

    /** True where access refused once can only be turned on in the phone's Settings (iPhone): the button says so. */
    val deniedOpensSettings: Boolean get() = false

    /** Ready to post: a photo as JPEG at most [MomentPhotoMaxSide] long, a video or audio file as it is, with a thumb. */
    suspend fun load(item: LibraryItem): CapturedMedia?

    /**
     * A library photo for a profile picture, at avatar quality ([ImagePicker]'s rules: up to [AvatarMaxSide], under
     * [AvatarMaxBytes]), never a moment's [MomentPhotoMaxSide]. Null for a video or audio item, or one that can't be read.
     */
    suspend fun loadAvatar(item: LibraryItem): PickedImage? = null

    /** The system picker for [kind]; null when the person closes it. */
    suspend fun pick(kind: LibraryKind): CapturedMedia?

    /**
     * [limit] items of [kind] after the newest [offset], newest first, for the gallery's next page. Throws when the
     * library can't be read (the gallery keeps what it has); fewer than [limit] means that's all of them.
     */
    suspend fun page(kind: LibraryKind, offset: Int, limit: Int): List<LibraryItem> =
        recents(kind, offset + limit, ask = false).items.drop(offset)

    /**
     * The bytes of a file this library handed over by path. Picked videos come with empty [CapturedMedia.bytes] and a
     * path, so several can wait in the composer without all being held in memory; each is read when it is posted.
     */
    suspend fun read(path: String): ByteArray? = null

    /** The size of the file at [path] in bytes (a picked video, a document's copy), or null when it isn't there. */
    suspend fun size(path: String): Long? = fileSizeAt(path)

    /** [count] small JPEG frames spread evenly through the video at [path], for the trim bar (M3b). */
    suspend fun filmstrip(path: String, count: Int): List<ByteArray> = emptyList()

    /**
     * A copy of the video at [path] from [startMs] to [endMs] (MP4, ready to post, with a fresh thumb), at most
     * [maxBytes] (a moment's 32 MiB; a chat's 100 MB, so a cut from a long video keeps its quality); null when the
     * phone can't make one. The platform's own tools: Media3 Transformer (already in the app) on Android,
     * AVAssetExportSession on iOS. No new library.
     */
    suspend fun trim(path: String, startMs: Long, endMs: Long, maxBytes: Long = MomentMediaMaxBytes): CapturedMedia? = null

    /**
     * [trim] for a moment being got ready after Post (the whole clip made smaller, in the background): the same copy,
     * with [onProgress] told how far the encoder has got (0..1) where the phone says (Android's Transformer). Where it
     * doesn't, [onProgress] is never called and the ring moves on when the copy is ready. [budgetBytes] is the size the
     * encode is planned for, [maxBytes] or less: after a pass that came out over the limit (a hardware encoder that
     * runs past its bit rate) the caller asks again with a smaller one ([MomentVideoEncoding.smallerBudget]). The
     * result may still be over [maxBytes]; the caller checks.
     */
    suspend fun shrink(
        path: String, startMs: Long, endMs: Long, maxBytes: Long = MomentMediaMaxBytes, budgetBytes: Long = maxBytes, onProgress: (Float) -> Unit = {},
    ): CapturedMedia? = trim(path, startMs, endMs, minOf(maxBytes, budgetBytes))

    /**
     * The system's Files picker for any document (a chat's Document, no permission needed); null when the person
     * closes it. The file is copied to this app's cache and handed over by [PickedFile.path], read when it is sent.
     */
    suspend fun pickFile(): PickedFile? = null

    /**
     * The first frame of the video at [path] as a small JPEG (about 240 px, under ~12 KB) for a chat bubble's poster,
     * which travels inside the encrypted message; null when it can't be made (the moment's tiny thumb is used then).
     */
    suspend fun poster(path: String): ByteArray? = null

    /**
     * A photo or video that is already a file of Circaso's (shared in from another app), ready for the review exactly
     * as [load] makes a library item: a photo as JPEG at most [MomentPhotoMaxSide] long, a video by its path with its
     * size, length and a thumb. Null when it can't be read.
     */
    suspend fun loadFile(path: String, mime: String): CapturedMedia? = null
}

/** A document picked from Files: its own name, type and size; [path] is this app's copy (or [bytes] in memory). */
class PickedFile(val name: String, val mime: String, val size: Long, val path: String? = null, val bytes: ByteArray? = null)

/** A library item's thumbnail, cropped to fill [modifier]; audio draws nothing (the strip shows its own tile). */
@Composable
expect fun LibraryThumbnail(item: LibraryItem, modifier: Modifier)

/**
 * A moment's video: fills [modifier], no player controls (short-form, Pasaka 2026-11-03). [playing] false pauses
 * (hold to pause), [muted] silences it, [loop] repeats (the composer's review). [poster] (a JPEG, usually the
 * thumbnail) is drawn until the first frame is on screen, and stays if the video can't be played on this phone, so
 * the stage is never a black void; [onUnplayable] tells the screen so it can say so. [positionMs], when set, holds the
 * video still at that moment, at full resolution (the trimmer's stage follows the edge being dragged).
 */
@Composable
expect fun MomentVideo(
    path: String,
    playing: Boolean,
    muted: Boolean,
    modifier: Modifier,
    loop: Boolean = false,
    poster: ByteArray? = null,
    onUnplayable: () -> Unit = {},
    positionMs: Long? = null,
)

/** Moment audio and video are at most a minute; longer videos open the trimmer, longer audio is refused. */
const val MomentMediaMaxMs = 60_000L

/** Picks from the gallery at once (design M3: up to 10, each its own moment). */
const val MomentPickMax = 10

/**
 * The largest chat attachment, as encrypted and uploaded: the server's `MESSAGING_MAX_ATTACHMENT_BYTES` (Pasaka,
 * 2026-11-07: "up to 100 MB"; first said 500). Chat videos and documents go as they are, untrimmed and not
 * re-encoded, up to this. Moments keep [MomentMediaMaxBytes].
 */
const val ChatAttachmentMaxBytes: Long = 100L * 1024 * 1024

/**
 * The largest video or document a chat sends, as the person's file: [ChatAttachmentMaxBytes] less the encryption's
 * 28 bytes per 1 MiB chunk ([com.circaso.core.crypto.ChunkedFileCipher]), so a file this size seals to exactly the limit.
 */
const val ChatFileMaxBytes: Long = ChatAttachmentMaxBytes -
    (ChatAttachmentMaxBytes / com.circaso.core.crypto.ChunkedFileCipher.ChunkBytes) * com.circaso.core.crypto.ChunkedFileCipher.ChunkOverhead
