package com.circaso.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** What the phone's library holds that a moment can be made from (Pasaka, 2026-11-03: photos, videos and audio). */
enum class LibraryKind { Photo, Video, Audio }

/**
 * One item in the phone's library, for the recents strip under the camera (WhatsApp-style). [id] is the platform's own
 * reference (a content URI on Android, a PHAsset id on iOS); [title] names an audio file; [addedAt] (epoch millis, 0
 * when unknown) orders photos and videos together in the gallery's All tab.
 */
data class LibraryItem(val id: String, val kind: LibraryKind, val durationMs: Long? = null, val title: String? = null, val addedAt: Long = 0)

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
     * when it was never answered (each kind has its own on Android 13+, so photo access alone doesn't show videos).
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
     * The system picker for photos and videos together, several at once (at most [max]), in the order they were
     * chosen. Empty when the person closes it. No permission is needed.
     */
    suspend fun pickMany(max: Int): List<CapturedMedia> = listOfNotNull(pick(LibraryKind.Photo))

    /**
     * The bytes of a file this library handed over by path. Picked videos come with empty [CapturedMedia.bytes] and a
     * path, so several can wait in the composer without all being held in memory; each is read when it is posted.
     */
    suspend fun read(path: String): ByteArray? = null

    /** [count] small JPEG frames spread evenly through the video at [path], for the trim bar (M3b). */
    suspend fun filmstrip(path: String, count: Int): List<ByteArray> = emptyList()

    /**
     * A copy of the video at [path] from [startMs] to [endMs] (MP4, ready to post, with a fresh thumb); null when the
     * phone can't make one. The platform's own tools: Media3 Transformer (already in the app) on Android,
     * AVAssetExportSession on iOS. No new library.
     */
    suspend fun trim(path: String, startMs: Long, endMs: Long): CapturedMedia? = null

    /**
     * The system's Files picker for any document (a chat's Document, no permission needed); null when the person
     * closes it. The file is copied to this app's cache and handed over by [PickedFile.path], read when it is sent.
     */
    suspend fun pickFile(): PickedFile? = null
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
 * the stage is never a black void; [onUnplayable] tells the screen so it can say so.
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
)

/** Moment audio and video are at most a minute; longer videos open the trimmer, longer audio is refused. */
const val MomentMediaMaxMs = 60_000L

/** Picks from the gallery at once (design M3: up to 10, each its own moment). */
const val MomentPickMax = 10
