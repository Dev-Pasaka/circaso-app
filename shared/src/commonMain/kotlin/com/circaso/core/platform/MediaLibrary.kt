package com.circaso.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** What the phone's library holds that a moment can be made from (Pasaka, 2026-11-03: photos, videos and audio). */
enum class LibraryKind { Photo, Video, Audio }

/**
 * One item in the phone's library, for the recents strip under the camera (WhatsApp-style). [id] is the platform's own
 * reference (a content URI on Android, a PHAsset id on iOS); [title] names an audio file.
 */
data class LibraryItem(val id: String, val kind: LibraryKind, val durationMs: Long? = null, val title: String? = null)

enum class LibraryAccess { Granted, Limited, Denied }

/**
 * The phone's photos, videos and audio for Moments: the most recent ones for the strip (this needs the library
 * permission, asked the first time the strip shows), and the system picker for everything else (no permission).
 */
interface MediaLibrary {
    suspend fun access(): LibraryAccess
    suspend fun recent(kind: LibraryKind, limit: Int = 30): List<LibraryItem>
    /** Ready to post: a photo as JPEG at most [MomentPhotoMaxSide] long, a video or audio file as it is, with a thumb. */
    suspend fun load(item: LibraryItem): CapturedMedia?
    /** The system picker for [kind]; null when the person closes it. */
    suspend fun pick(kind: LibraryKind): CapturedMedia?
}

/** A library item's thumbnail, cropped to fill [modifier]; audio draws nothing (the strip shows its own tile). */
@Composable
expect fun LibraryThumbnail(item: LibraryItem, modifier: Modifier)

/**
 * A moment's video: fills [modifier], no player controls (short-form, Pasaka 2026-11-03). [playing] false pauses
 * (hold to pause), [muted] silences it, [loop] repeats (the composer's review).
 */
@Composable
expect fun MomentVideo(path: String, playing: Boolean, muted: Boolean, modifier: Modifier, loop: Boolean = false)

/** Moment audio and video are at most a minute; longer files from the library are refused with a plain reason. */
const val MomentMediaMaxMs = 60_000L
