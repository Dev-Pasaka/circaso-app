package com.circaso.feature.names.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Names I give people on my phone (2026-11-12, "save them as I want"), WhatsApp-style: only I see them. Kept on the
 * phone first, so a change shows everywhere at once and works offline, then backed up sealed with my key-backup root.
 */
interface SavedNames {
    /** userId -> the name I saved for them (cleared names are not in it). */
    val names: StateFlow<Map<String, String>>

    /** Saves [name] for [userId]: trimmed, at most [MaxLength] characters; blank clears it. */
    suspend fun set(userId: String, name: String)

    /** Back to their own profile name. */
    suspend fun clear(userId: String)

    companion object {
        const val MaxLength = 40

        /** What [set] keeps for [name], or null when that clears it. */
        fun normalise(name: String): String? = name.trim().replace(Regex("\\s+"), " ").take(MaxLength).trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * The one place a person's shown name is decided: the name I saved for them, else their own profile name. Mappers
 * that build what the screens show take this, so a saved name reaches chats, contacts, calls, money and notices alike.
 */
class PeopleNames(val saved: StateFlow<Map<String, String>>) {
    /** The name to show for [userId], whose own name is [profileName]. */
    fun of(userId: String?, profileName: String): String = resolve(saved.value, userId, profileName)

    /** As [of], for a name that may be unknown: a saved name still shows when the profile name is missing. */
    fun orNull(userId: String?, profileName: String?): String? = userId?.let { saved.value[it] } ?: profileName

    /** True when I saved a name for [userId]. */
    fun has(userId: String?): Boolean = userId != null && userId in saved.value

    companion object {
        /** No saved names (tests and previews). */
        val None = PeopleNames(MutableStateFlow(emptyMap()))

        fun resolve(saved: Map<String, String>, userId: String?, profileName: String): String =
            userId?.let { saved[it] }?.takeIf { it.isNotBlank() } ?: profileName
    }
}
