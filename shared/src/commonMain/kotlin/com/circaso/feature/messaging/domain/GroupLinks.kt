package com.circaso.feature.messaging.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * A group's join link (decided by Pasaka, 2026-11-14: "people can share links and people can join from those links",
 * so a group moves over from another chat app without the admin adding everyone). One per group, made by an admin.
 * The server can't read a group's sealed name, so the admin's phone publishes what the link's page shows, for that
 * link only: [name], [description] and a small photo. Never members, messages or anything about money.
 */
data class GroupLink(
    val chatId: String,
    val token: String,
    /** What is shared: `https://<share host>/s/{token}`. */
    val url: String,
    /** The copy published for the link's page, as this phone last knew it. */
    val name: String,
    val description: String?,
    val hasPhoto: Boolean,
)

/** A join link as someone sees it before joining (M31). [chatId] only when they are already in the group. */
data class GroupLinkPreview(
    val token: String,
    val name: String,
    val description: String?,
    val photoUrl: String?,
    val memberCount: Int,
    val member: Boolean,
    val chatId: String?,
)

/**
 * Join links. Showing a group's existing link is a **read** from this phone's copy (it works offline); making,
 * resetting and turning it off, looking a link up and joining are **online-only**: nothing is queued or faked.
 */
interface GroupLinksRepository {
    /** The group's link as this phone last saw it; null when it has none (or this phone never loaded it). */
    fun link(chatId: String): Flow<GroupLink?>

    /** M28: loads the link from the server into this phone's copy. */
    suspend fun refresh(chatId: String): AppResult<GroupLink?>

    /** M29: makes the link, publishing the group's name, description and photo for its page. */
    suspend fun create(chatId: String): AppResult<GroupLink>

    /** M29 with `reset`: a new link; the old one stops working. */
    suspend fun reset(chatId: String): AppResult<GroupLink>

    /** M30: the link stops working and the published copy is deleted. */
    suspend fun turnOff(chatId: String): AppResult<Unit>

    /**
     * Publishes the preview again when the group's name, description or photo differ from the copy last published
     * (after an admin changes them, on `chat.updated`, and when the link screen opens). Best effort; admins only.
     */
    suspend fun republishIfStale(chatId: String) {}

    /** M31: what the link shows, before joining. */
    suspend fun preview(token: String): AppResult<GroupLinkPreview>

    /** M32: joins the group; its chat is stored and returned. [preview] names the chat until its key arrives. */
    suspend fun join(token: String, preview: GroupLinkPreview?): AppResult<Chat>
}

object GroupLinkErrors {
    /** This phone can't read the group's name (its key is lost), so it has nothing true to publish. Made on the phone. */
    const val NameUnknown = "messaging.link_name_unknown"
    const val NotFound = "messaging.link_not_found"
    const val NotAllowed = "messaging.link_not_allowed"
}

/** What goes to the share sheet with the link (WhatsApp shows `*bold*`): one line, then the link's own preview. */
object GroupLinkText {
    fun message(link: GroupLink): String = "Join *${link.name}* on Circaso: ${link.url}"
    fun subject(link: GroupLink): String = "Join ${link.name} on Circaso"
}
