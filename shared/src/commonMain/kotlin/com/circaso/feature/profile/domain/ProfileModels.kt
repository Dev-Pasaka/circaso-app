package com.circaso.feature.profile.domain

data class Privacy(
    /** Who sees the picture / about line (the API's own words: for example `everyone`, `contacts`, `nobody`). */
    val avatar: String,
    val about: String,
    /** Whether people can find this account by exact @handle. */
    val handleLookup: Boolean,
)

data class Profile(
    val userId: String,
    val displayName: String,
    val about: String?,
    val handle: String?,
    /** When the @handle may be changed again (once every 30 days). */
    val handleChangeableAt: String?,
    /** A link valid for one hour: reload the profile instead of caching it. */
    val avatarUrl: String?,
    val privacy: Privacy,
    val version: Int,
    /** True while an edit is saved on this phone but not yet confirmed by the server. */
    val pendingSync: Boolean,
)

/** Only the fields being changed are set. `about = ""` clears the about line. */
data class ProfileChange(
    val displayName: String? = null,
    val about: String? = null,
    val avatarPrivacy: String? = null,
    val aboutPrivacy: String? = null,
    val handleLookup: Boolean? = null,
) {
    val isEmpty get() = displayName == null && about == null && avatarPrivacy == null && aboutPrivacy == null && handleLookup == null
}

data class PublicProfile(
    val userId: String,
    val displayName: String,
    val handle: String?,
    val avatarUrl: String?,
    val about: String?,
)

enum class HandleStatus { Available, Taken, Invalid, Unknown }

data class HandleAvailability(val handle: String, val available: Boolean, val status: HandleStatus, val suggestion: String?)

/** Raw picture bytes with the type that was declared (JPEG, PNG or WebP, up to 5 MB). */
class AvatarImage(val bytes: ByteArray, val contentType: String)
