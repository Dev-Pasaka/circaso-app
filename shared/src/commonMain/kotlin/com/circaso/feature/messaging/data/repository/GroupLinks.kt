package com.circaso.feature.messaging.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.platform.GroupPhotoEncoder
import com.circaso.core.platform.NoGroupPhotoEncoder
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.remote.GroupLinkDto
import com.circaso.feature.messaging.data.remote.GroupLinksRemote
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.remote.PublishGroupLinkRequest
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.GroupLink
import com.circaso.feature.messaging.domain.GroupLinkErrors
import com.circaso.feature.messaging.domain.GroupLinkPreview
import com.circaso.feature.messaging.domain.GroupLinksRepository
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the links of the groups I administer wait between launches, so an existing link shows offline. */
interface GroupLinkCache {
    suspend fun read(): String?
    suspend fun write(value: String?)
}

class PreferencesGroupLinkCache(private val prefs: AppPreferences) : GroupLinkCache {
    override suspend fun read(): String? = prefs.text(AppPreferences.GroupLinksText).first()
    override suspend fun write(value: String?) = prefs.setText(AppPreferences.GroupLinksText, value)
}

/**
 * A group's link as this phone keeps it: the link, and what was last published for its page. [photoId] is the group
 * photo (its sealed reference's id) the published picture was made from, null when none was published, so a changed
 * or newly arrived photo is noticed.
 */
@Serializable
internal data class StoredGroupLink(
    val token: String,
    val url: String,
    val name: String,
    val description: String? = null,
    val photoId: String? = null,
    val hasPhoto: Boolean = false,
)

@Serializable
internal data class StoredGroupLinks(val links: Map<String, StoredGroupLink> = emptyMap())

/**
 * Group join links (backend M28–M32, 2026-11-14). The link itself is the server's; this phone keeps a copy of the
 * links of groups it administers so the link screen opens offline. The preview on the link's page is published from
 * here, because only a member's phone can read the group's name, description and photo: [publish] sends them (the
 * photo as a small JPEG made from the group photo on this phone) and nothing else, and only when an admin makes a
 * link or one is already on. Making, resetting, turning off, looking up and joining are online-only.
 */
class GroupLinksRepositoryImpl(
    private val remote: GroupLinksRemote,
    private val chats: ChatsRepositoryImpl,
    private val chatsDao: ChatsDao,
    private val groupKeys: GroupKeys,
    private val cache: GroupLinkCache,
    private val json: Json,
    private val ownUserId: suspend () -> String?,
    private val photos: GroupPhotos? = null,
    private val encoder: GroupPhotoEncoder = NoGroupPhotoEncoder,
) : GroupLinksRepository {
    /** chatId to its link; null until the cache is read. */
    private val stored = MutableStateFlow<Map<String, StoredGroupLink>?>(null)
    private val lock = Mutex()

    override fun link(chatId: String): Flow<GroupLink?> =
        stored.onStart { loadCache() }.filterNotNull().map { it[chatId]?.toDomain(chatId) }

    private suspend fun loadCache() = lock.withLock {
        if (stored.value != null) return@withLock
        stored.value = cache.read()?.let { runCatching { json.decodeFromString(StoredGroupLinks.serializer(), it).links }.getOrNull() }.orEmpty()
    }

    private suspend fun save(chatId: String, link: StoredGroupLink?) {
        loadCache()
        lock.withLock {
            val next = stored.value.orEmpty().let { if (link == null) it - chatId else it + (chatId to link) }
            if (next == stored.value) return@withLock
            stored.value = next
            cache.write(if (next.isEmpty()) null else json.encodeToString(StoredGroupLinks.serializer(), StoredGroupLinks(next)))
        }
    }

    private suspend fun storedLink(chatId: String): StoredGroupLink? { loadCache(); return stored.value?.get(chatId) }

    override suspend fun refresh(chatId: String): AppResult<GroupLink?> = when (val r = remote.link(chatId)) {
        is AppResult.Failure -> {
            // No longer an admin, or no longer in the group: this phone has no business keeping its link.
            if (isGone(r.error)) save(chatId, null)
            r
        }
        is AppResult.Success -> {
            val dto = r.value.link
            val known = storedLink(chatId)
            // The photo it was made from is this phone's own note; kept while the link is the same one.
            val link = dto?.toStored(photoId = known?.takeIf { it.token == dto.token && dto.photoUrl != null }?.photoId)
            save(chatId, link)
            AppResult.Success(link?.toDomain(chatId))
        }
    }

    override suspend fun create(chatId: String): AppResult<GroupLink> = publish(chatId, reset = false)

    override suspend fun reset(chatId: String): AppResult<GroupLink> = publish(chatId, reset = true)

    /**
     * M29. What is published is what this phone reads from the group's sealed metadata: nothing is typed here, and a
     * phone that can't read the name publishes nothing ([GroupLinkErrors.NameUnknown]).
     */
    private suspend fun publish(chatId: String, reset: Boolean): AppResult<GroupLink> {
        val current = currentPreview(chatId) ?: return AppResult.Failure(AppError.Api(GroupLinkErrors.NameUnknown, 0, null, null, emptyMap()))
        val picture = current.photo?.let { ref -> photoFor(ref, current.avatarMediaId) }
        val request = PublishGroupLinkRequest(
            name = current.name.take(MaxLinkNameChars),
            description = current.description,
            photo = picture?.let { Base64.encode(it) },
            reset = reset,
        )
        return when (val r = remote.publish(chatId, request)) {
            is AppResult.Failure -> r
            is AppResult.Success -> {
                val link = r.value.toStored(photoId = current.photo?.id?.takeIf { picture != null })
                save(chatId, link)
                AppResult.Success(link.toDomain(chatId))
            }
        }
    }

    override suspend fun turnOff(chatId: String): AppResult<Unit> = when (val r = remote.turnOff(chatId)) {
        is AppResult.Failure -> r
        is AppResult.Success -> { save(chatId, null); AppResult.Success(Unit) }
    }

    override suspend fun republishIfStale(chatId: String) {
        val known = storedLink(chatId) ?: return
        if (!amAdmin(chatId)) return
        val current = currentPreview(chatId) ?: return
        val stale = known.name != current.name.take(MaxLinkNameChars) || known.description != current.description || known.photoId != current.photo?.id
        if (stale) publish(chatId, reset = false)
    }

    /** `link.changed`: another admin made, reset, republished or turned off the link. */
    suspend fun onLinkChanged(chatId: String) { refresh(chatId) }

    override suspend fun preview(token: String): AppResult<GroupLinkPreview> = when (val r = remote.preview(token)) {
        is AppResult.Failure -> r
        is AppResult.Success -> AppResult.Success(
            r.value.let { GroupLinkPreview(token, it.name, it.description?.takeIf { d -> d.isNotBlank() }, it.photoUrl, it.memberCount, it.member, it.chatId) },
        )
    }

    override suspend fun join(token: String, preview: GroupLinkPreview?): AppResult<Chat> = when (val r = remote.join(token)) {
        is AppResult.Failure -> r
        is AppResult.Success -> {
            // In the group now. Its key is on its way from the members' phones (they got `member.added`); until it
            // lands, the chat is shown under the name the link published rather than as an unnamed group.
            val chat = chats.store(r.value)
            if (preview == null || chat.nameKnown) AppResult.Success(chat)
            else {
                chatsDao.chat(chat.chatId)?.let { chatsDao.upsertChat(it.copy(name = preview.name, description = it.description ?: preview.description)) }
                AppResult.Success(chat.copy(title = preview.name, nameKnown = true, description = chat.description ?: preview.description))
            }
        }
    }

    // ---- what this phone reads of the group

    private class CurrentPreview(val name: String, val description: String?, val photo: GroupPhotoRef?, val avatarMediaId: String?)

    /** The group's name, description and photo as this phone reads them; null without a name it can vouch for. */
    private suspend fun currentPreview(chatId: String): CurrentPreview? {
        val chat = chatsDao.chat(chatId)?.takeIf { it.kind == MessagingWire.Group && !it.left } ?: return null
        val meta = groupKeys.openGroupMeta(chatId, chat.sealedMeta, chat.metaVersion)
        val name = (meta?.name ?: chat.name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CurrentPreview(name, keptDescription(meta, chat.description)?.take(MaxGroupDescriptionChars), meta?.photo, chat.avatarMediaId)
    }

    /** The published picture: a small JPEG of the group photo on this phone; null when it can't be made right now. */
    private suspend fun photoFor(ref: GroupPhotoRef, serverId: String?): ByteArray? {
        val full = photos?.fullBytes(ref, serverId) ?: return null
        return runCatching { encoder.preview(full) }.getOrNull()
    }

    private suspend fun amAdmin(chatId: String): Boolean {
        val me = ownUserId() ?: return false
        val role = chatsDao.members(chatId).firstOrNull { it.userId == me }?.role
        return role == MessagingWire.Owner || role == MessagingWire.Admin
    }

    private fun isGone(error: AppError): Boolean = (error as? AppError.Api)?.code in GoneCodes

    private fun GroupLinkDto.toStored(photoId: String?) =
        StoredGroupLink(token, url, name, description?.takeIf { it.isNotBlank() }, photoId, hasPhoto = photoUrl != null)

    private fun StoredGroupLink.toDomain(chatId: String) = GroupLink(chatId, token, url, name, description, hasPhoto)

    companion object {
        /** The server's limit for a published name. */
        const val MaxLinkNameChars = 100
        private val GoneCodes = setOf("messaging.chat_not_found", "messaging.not_admin", "messaging.not_a_group")
    }
}
