package com.circaso.feature.messaging.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.map
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.data.local.ChatEntity
import com.circaso.feature.messaging.data.local.ChatMemberEntity
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.MessagesDao
import com.circaso.feature.messaging.data.remote.AddMembersRequest
import com.circaso.feature.messaging.data.remote.AvatarChange
import com.circaso.feature.messaging.data.remote.ChatDto
import com.circaso.feature.messaging.data.remote.ChatSettingsDto
import com.circaso.feature.messaging.data.remote.ChatsRemote
import com.circaso.feature.messaging.data.remote.CreateGroupRequest
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.remote.SetRoleRequest
import com.circaso.feature.messaging.data.remote.UpdateGroupRequest
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.GroupCreated
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.GroupKeyErrors
import com.circaso.feature.messaging.domain.GroupNameState
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.SecurityCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Chats from Room, refreshed from the server. Group membership changes also arrive as realtime frames; the
 * `on*` methods are what the frame handler calls, and they do the key work the guide asks for on each one.
 */
class ChatsRepositoryImpl(
    private val dao: ChatsDao,
    private val messages: MessagesDao,
    private val remote: ChatsRemote,
    private val groupKeys: GroupKeys,
    private val sessions: SessionCipher,
    private val ownUserId: suspend () -> String?,
    private val now: () -> Long = ::nowMillis,
    /** Group photos (null in tests that don't need them: groups then show initials). */
    private val photos: GroupPhotos? = null,
    /** Names I saved for people: a direct chat's title and every member's name follow them. */
    private val names: com.circaso.feature.names.domain.PeopleNames = com.circaso.feature.names.domain.PeopleNames.None,
) : ChatsRepository {
    /**
     * The chat rows, again whenever a group photo lands on this phone (the list swaps the thumbnail for it) and when a
     * group's key turns out to be on no member's phone ([GroupKeys.unavailable]: its row stops saying "waiting").
     */
    private fun chatRows(): Flow<List<ChatEntity>> =
        if (photos == null) combine(dao.observeChats(), groupKeys.unavailable) { rows, _ -> rows }
        else combine(dao.observeChats(), photos.changes, groupKeys.unavailable) { rows, _, _ -> rows }

    override val chats: Flow<List<ChatSummary>> =
        combine(chatRows(), combine(dao.observeAllMembers(), names.saved) { m, n -> m to n }, messages.observeLatestPerChat(), messages.observeUnread(), ownUserIdFlow()) { chats, (members, saved), latest, unread, me ->
            val byChat = members.groupBy { it.chatId }
            val latestByChat = latest.associateBy { it.chatId }
            val unreadByChat = unread.associate { it.chatId to it.count }
            chats.map { chat ->
                val chatMembers = byChat[chat.chatId].orEmpty()
                ChatSummary(
                    chat = chat.toDomain(chatMembers, me, groupPhoto(chat), keyUnavailable = chat.chatId in groupKeys.unavailable.value, saved = saved),
                    latest = latestByChat[chat.chatId]?.toDomain(chatMembers, me, saved),
                    unread = unreadByChat[chat.chatId] ?: 0,
                )
            }
        }

    private fun ownUserIdFlow(): Flow<String?> = kotlinx.coroutines.flow.flow { emit(ownUserId()) }

    override fun chat(chatId: String): Flow<Chat?> =
        combine(dao.observeChat(chatId), combine(dao.observeMembers(chatId), names.saved) { m, n -> m to n }, ownUserIdFlow(), photos?.changes ?: kotlinx.coroutines.flow.flowOf(0), groupKeys.unavailable) { chat, (members, saved), me, _, unavailable ->
            chat?.toDomain(members, me, groupPhoto(chat), keyUnavailable = chat.chatId in unavailable, saved = saved)
        }

    /**
     * The group's photo on this phone (full, else its thumbnail), from the sealed metadata; null for none or a direct
     * chat. The sealed reference holds the key, so it decides what is shown; the server's [ChatEntity.avatarMediaId] is
     * where the full photo is fetched from when it names another attachment. A server without the field, or an older
     * group it says null for, still shows the sealed photo.
     */
    private suspend fun groupPhoto(chat: ChatEntity): String? {
        if (photos == null || chat.kind != MessagingWire.Group) return null
        val ref = groupKeys.openGroupMeta(chat.chatId, chat.sealedMeta, chat.metaVersion)?.photo ?: return null
        return photos.localPath(ref, chat.avatarMediaId)
    }

    override suspend fun refresh(): AppResult<Unit> = when (val result = remote.chats()) {
        is AppResult.Failure -> result
        is AppResult.Success -> {
            val seen = result.value.chats.map { it.chatId }.toSet()
            result.value.chats.forEach { store(it) }
            // A chat the server no longer lists is one this phone was removed from (or that was deleted).
            dao.chats().filter { it.chatId !in seen && !it.left }.forEach { dao.markLeft(it.chatId) }
            // A group this phone can't name: ask the members for its key.
            askForMissingKeys(result.value.chats.filter { it.kind == MessagingWire.Group }.map { it.chatId })
            AppResult.Success(Unit)
        }
    }

    override suspend fun refreshChat(chatId: String): AppResult<Chat> = when (val result = remote.chat(chatId)) {
        is AppResult.Failure -> {
            if ((result.error as? AppError.Api)?.code == "messaging.chat_not_found") dao.markLeft(chatId)
            result
        }
        is AppResult.Success -> AppResult.Success(store(result.value)).also { if (result.value.kind == MessagingWire.Group) askForMissingKeys(listOf(chatId)) }
    }

    override suspend fun openDirect(userId: String): AppResult<Chat> = remote.openDirect(userId).map { store(it) }


    override suspend fun createGroup(draft: GroupDraft, idempotencyKey: String): AppResult<GroupCreated> {
        // On disk before the request leaves: if the answer is lost, the group still turns up in the list and opens.
        val key = groupKeys.keyForCreate(idempotencyKey)
        val sealed = groupKeys.sealMetaWithin(key, draft.name, null) ?: return AppResult.Failure(groupInvalid())
        // The people asked for are invited (2026-11-09): each gets the key sealed 1:1 so their invite shows the name.
        // A new group's name is sealed as version 1, the version the server gives it.
        val inviteKeys = groupKeys.sealInviteKeys(key, CreatedMetaVersion, draft.memberIds).takeIf { it.isNotEmpty() }
        val request = CreateGroupRequest(sealedMeta = sealed, memberIds = draft.memberIds, inviteKeys = inviteKeys)
        return when (val result = remote.createGroup(request, idempotencyKey)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val dto = result.value
                groupKeys.createdWith(idempotencyKey, dto.chatId, dto.metaVersion, key)
                val chat = store(dto)
                groupKeys.ensureDistributed(dto.chatId, chat.members.map { it.userId }) // best effort now; the first send makes sure
                AppResult.Success(GroupCreated(chat, dto.notAdded.orEmpty(), dto.invited.orEmpty()))
            }
        }
    }

    override suspend fun rename(chatId: String, name: String): AppResult<Chat> = edit(chatId, name, currentDescription(chatId))

    override suspend fun editGroup(chatId: String, name: String, description: String): AppResult<Chat> =
        edit(chatId, name, description.trim().take(MaxGroupDescriptionChars).ifEmpty { null })

    /** A new name or description keeps the group's photo: all three live in the one sealed value. */
    private suspend fun edit(chatId: String, name: String, description: String?): AppResult<Chat> {
        val key = groupKeys.latestMetaKey(chatId) ?: return withoutKey(chatId, name, photo = null, description = description ?: "")
        val sealed = groupKeys.sealMetaWithin(key, name, currentPhoto(chatId), description) ?: return AppResult.Failure(groupInvalid())
        return patchSealed(chatId, key) { version -> UpdateGroupRequest(sealedMeta = sealed, version = version) }
    }

    /** The description this phone last read for the group; carried into every new seal so nothing drops it. */
    private suspend fun currentDescription(chatId: String): String? = dao.chat(chatId)?.description

    /**
     * M8 for the photo: [image] is encrypted and uploaded into the group's own attachments, then the name and a small
     * reference to it (with its thumbnail) are sealed again, and the same PATCH names the attachment as the group's
     * `avatarMediaId` so the server keeps it. Null removes the photo (`avatarMediaId: null`). Online-only, admins only.
     */
    override suspend fun setGroupPhoto(chatId: String, image: com.circaso.core.platform.GroupPhotoImage?, name: String?): AppResult<Chat> {
        val key = groupKeys.latestMetaKey(chatId)
        if (key == null) {
            // No key: ask the members, or (an admin, once they had their chance) give the group a new key. That seals
            // the name too, so it must be typed; asked for before anything is uploaded.
            if (!mayRekey(chatId)) return withoutKey(chatId, name, photo = null)
            val typed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return AppResult.Failure(groupNameNeeded())
            val photo = if (image == null) null else when (val up = upload(chatId, image)) { is AppResult.Success -> up.value; is AppResult.Failure -> return up }
            return withoutKey(chatId, typed, photo)
        }
        val current = dao.chat(chatId) ?: return AppResult.Failure(AppError.Unexpected(null))
        val currentName = groupKeys.openGroupMeta(chatId, current.sealedMeta, current.metaVersion)?.name ?: current.name
            ?: name?.trim()?.takeIf { it.isNotEmpty() } ?: return AppResult.Failure(groupNameNeeded())
        val photo = if (image == null) null else when (val up = upload(chatId, image)) { is AppResult.Success -> up.value; is AppResult.Failure -> return up }
        val sealed = groupKeys.sealMetaWithin(key, currentName, photo, current.description) ?: return AppResult.Failure(groupInvalid())
        val avatar = if (photo == null) AvatarChange.Clear else AvatarChange.set(photo.id)
        return patchSealed(chatId, key) { version -> UpdateGroupRequest(sealedMeta = sealed, version = version, avatarMediaId = avatar) }
    }

    private suspend fun upload(chatId: String, image: com.circaso.core.platform.GroupPhotoImage): AppResult<GroupPhotoRef> {
        val uploader = photos ?: return AppResult.Failure(AppError.Unexpected(null))
        return uploader.upload(chatId, image)
    }

    // ---- a group whose metadata key this phone doesn't have

    /**
     * True when an admin may give [chatId] a new metadata key: this phone has asked the members and none has answered
     * for [RekeyAfterMillis], or every one asked said they have no key either. A member who still holds the old key
     * loses nothing they can't read again: the new key reaches them right after.
     */
    private suspend fun mayRekey(chatId: String): Boolean {
        val me = ownUserId() ?: return false
        val myRole = dao.members(chatId).firstOrNull { it.userId == me }?.role
        if (myRole != MessagingWire.Owner && myRole != MessagingWire.Admin) return false
        if (chatId in groupKeys.unavailable.value) return true
        val asked = groupKeys.askedSince(chatId) ?: return false
        return now() - asked >= RekeyAfterMillis
    }

    /**
     * A change to the group's name or photo on a phone without its key. Until an admin may re-key ([mayRekey]) this
     * asks the members for the key and says so (`messaging.meta_key_missing`, or `messaging.meta_key_asked` for an
     * admin, who can try again in a minute). Then a fresh key seals [name] (and [photo], else none: the old one can't
     * be read here), goes up as the next meta version, and is handed to every member.
     */
    private suspend fun withoutKey(chatId: String, name: String?, photo: GroupPhotoRef?, description: String? = null): AppResult<Chat> {
        val chat = dao.chat(chatId) ?: return AppResult.Failure(AppError.Unexpected(null))
        if (!mayRekey(chatId)) {
            val members = dao.members(chatId)
            when (val asked = groupKeys.askForMetaKey(chatId, members.map { it.userId to (it.role != MessagingWire.Member) }, chat.metaVersion)) {
                is AppResult.Failure -> if (asked.error is AppError.Network) return asked
                is AppResult.Success -> Unit
            }
            val me = ownUserId()
            val admin = members.firstOrNull { it.userId == me }?.role.let { it == MessagingWire.Owner || it == MessagingWire.Admin }
            return AppResult.Failure(localError(if (admin) GroupKeyErrors.Asked else GroupKeyErrors.Missing))
        }
        val typed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return AppResult.Failure(groupNameNeeded())
        val key = groupKeys.newMetaKey()
        val sealed = groupKeys.sealMetaWithin(key, typed, photo, description ?: chat.description) ?: return AppResult.Failure(groupInvalid())
        // The server's photo goes with the old seal: kept only when a new one is set now.
        val avatar = if (photo == null) AvatarChange.Clear else AvatarChange.set(photo.id)
        val patched = patchSealed(chatId, key) { version -> UpdateGroupRequest(sealedMeta = sealed, version = version, avatarMediaId = avatar) }
        if (patched is AppResult.Success) groupKeys.distributeMetaKey(chatId, patched.value.members.map { it.userId })
        return patched
    }

    override suspend fun askForMissingGroupKeys() =
        askForMissingKeys(dao.chats().filter { it.kind == MessagingWire.Group && !it.left }.map { it.chatId })

    /**
     * Asks the members for the key of each group stored without one (best effort, rate-limited per group), except where
     * I joined in the last [KeyGraceMillis]: the key is on its way with the members' sender keys. A failure (offline,
     * or this phone's keys not on the server yet) stops the round; the next one (each connection, and every
     * [com.circaso.feature.messaging.data.repository.MessagingStarter.AskAgainMillis] while connected) tries again.
     */
    private suspend fun askForMissingKeys(chatIds: Collection<String>) {
        if (chatIds.isEmpty()) return
        val me = ownUserId() ?: return
        for (chatId in chatIds) {
            val chat = dao.chat(chatId) ?: continue
            if (chat.left || chat.kind != MessagingWire.Group || chat.sealedMeta == null) continue
            if (groupKeys.openGroupMeta(chatId, chat.sealedMeta, chat.metaVersion) != null) continue
            val members = dao.members(chatId)
            val joined = isoToMillis(members.firstOrNull { it.userId == me }?.joinedAt)
            if (joined != null && now() - joined < KeyGraceMillis) continue
            if (groupKeys.askForMetaKey(chatId, members.map { it.userId to (it.role != MessagingWire.Member) }, chat.metaVersion) is AppResult.Failure) return
        }
    }

    private suspend fun currentPhoto(chatId: String): GroupPhotoRef? =
        dao.chat(chatId)?.let { groupKeys.openGroupMeta(chatId, it.sealedMeta, it.metaVersion)?.photo }

    private fun groupInvalid() = AppError.Api("messaging.group_invalid", 400, null, null, emptyMap())
    private fun groupNameNeeded() = localError(GroupKeyErrors.NameNeeded)
    /** An error made on this phone, worded by its code in [com.circaso.core.common.ErrorMessages] like the server's. */
    private fun localError(code: String) = AppError.Api(code, 0, null, null, emptyMap())

    override suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat> =
        patchWithRetry(chatId) { version -> UpdateGroupRequest(settings = ChatSettingsDto(allowed), version = version) }

    /**
     * A PATCH with a new seal under [key]: the key is kept for the version the server gives it before the chat is
     * stored, so the stored chat opens with it (a new key would otherwise leave the name unread until the next load).
     */
    private suspend fun patchSealed(chatId: String, key: ByteArray, request: (version: Int) -> UpdateGroupRequest): AppResult<Chat> =
        when (val r = patchDto(chatId, request)) {
            is AppResult.Failure -> r
            is AppResult.Success -> {
                groupKeys.rememberMetaKey(chatId, r.value.metaVersion, key)
                val chat = store(r.value)
                photos?.notifyChanged()
                runCatching { metaChanged?.invoke(chatId) }
                AppResult.Success(chat)
            }
        }

    private suspend fun patchWithRetry(chatId: String, request: (version: Int) -> UpdateGroupRequest): AppResult<Chat> =
        patchDto(chatId, request).map { store(it) }

    /** `PATCH` carries the version last read; `messaging.version_conflict` means reload and try once more. */
    private suspend fun patchDto(chatId: String, request: (version: Int) -> UpdateGroupRequest): AppResult<ChatDto> {
        val current = dao.chat(chatId) ?: return AppResult.Failure(AppError.Unexpected(null))
        val first = remote.updateGroup(chatId, request(current.version))
        if (first is AppResult.Success) return first
        if (((first as AppResult.Failure).error as? AppError.Api)?.code != "messaging.version_conflict") return first
        val reloaded = refreshChat(chatId)
        if (reloaded is AppResult.Failure) return reloaded
        return remote.updateGroup(chatId, request((reloaded as AppResult.Success).value.version))
    }

    /**
     * M9 invites the people (2026-11-09): they join when they accept, and their phones get my sender key then, on
     * `member.added`. The group's current key is sealed for each so the invite shows its name; a phone without the key
     * (lost, being asked for) invites without it, and the invite says "A group".
     */
    override suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated> {
        val inviteKeys = groupKeys.latestMetaKeyVersioned(chatId)
            ?.let { (version, key) -> groupKeys.sealInviteKeys(key, version, userIds) }
            ?.takeIf { it.isNotEmpty() }
        return when (val result = remote.addMembers(chatId, AddMembersRequest(userIds, inviteKeys))) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val chat = store(result.value)
                groupKeys.ensureDistributed(chatId, chat.members.map { it.userId })
                AppResult.Success(GroupCreated(chat, result.value.notAdded.orEmpty(), result.value.invited.orEmpty()))
            }
        }
    }

    override suspend fun removeMember(chatId: String, userId: String): AppResult<Unit> =
        when (val result = remote.removeMember(chatId, userId)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                dao.deleteMember(chatId, userId)
                afterSomeoneLeft(chatId, iRemovedThem = true)
                AppResult.Success(Unit)
            }
        }

    override suspend fun leave(chatId: String): AppResult<Unit> {
        val me = ownUserId() ?: return AppResult.Failure(AppError.Unexpected(null))
        return when (val result = remote.removeMember(chatId, me)) {
            is AppResult.Failure -> result
            is AppResult.Success -> { dao.markLeft(chatId); groupKeys.forget(chatId); AppResult.Success(Unit) }
        }
    }

    override suspend fun setRole(chatId: String, userId: String, role: MemberRole): AppResult<Chat> =
        remote.setRole(chatId, userId, SetRoleRequest(role.wire())).map { store(it) }

    override suspend fun securityCode(userId: String): SecurityCode? {
        val me = ownUserId() ?: return null
        return sessions.securityCode(me, userId)?.let { SecurityCode(it) }
    }

    // ---- what the realtime frames ask for (guide, section 8)

    /**
     * `member.added`: newcomers need my sender key and the group key; if I am the newcomer, I need the chat. [joined]
     * (group invites, 2026-11-09): the one person accepted an invite, and the chat says so ("Amina joined", or "You
     * joined" on their own phone), in the thread where it happened.
     */
    suspend fun onMemberAdded(chatId: String, userIds: List<String>, joined: Boolean = false, viaLink: Boolean = false) {
        val me = ownUserId()
        // Group join links (2026-11-14): the chat says how they came in, so admins see who the link brought.
        val how = if (viaLink) JoinedViaLinkSuffix else JoinedSuffix
        if (me in userIds || dao.chat(chatId) == null) {
            refreshChat(chatId)
            if (joined && me in userIds) insertSystemRow(messages, chatId, me!!, "You$how", now())
            return
        }
        refreshChat(chatId)
        if (joined) {
            for (userId in userIds) {
                val name = dao.members(chatId).firstOrNull { it.userId == userId }?.displayName ?: "Someone" // their own name: the thread shows my saved name over it (systemText)
                insertSystemRow(messages, chatId, userId, "$name$how", now())
            }
        }
        groupKeys.ensureDistributed(chatId, userIds)
    }

    /** `member.removed`: if it was me the chat is gone; otherwise everyone turns their sender key over. */
    suspend fun onMemberRemoved(chatId: String, userId: String, byUserId: String?) {
        val me = ownUserId()
        if (userId == me) { dao.markLeft(chatId); groupKeys.forget(chatId); return }
        dao.deleteMember(chatId, userId)
        afterSomeoneLeft(chatId, iRemovedThem = byUserId == me)
    }

    /**
     * `chat.updated`: a newer version means reload (name, photo, settings, roles). [avatarMediaId] is the frame's
     * photo id when it carries the key (null inside means "no photo"); a different one than stored reloads too.
     */
    suspend fun onChatUpdated(chatId: String, version: Int, avatarMediaId: Avatar? = null) {
        val current = dao.chat(chatId)
        if (current == null || current.version < version || (avatarMediaId != null && avatarMediaId.id != current.avatarMediaId)) {
            refreshChat(chatId)
            // Someone else changed the group: an admin's phone keeps its join link's published preview in step.
            metaChanged?.invoke(chatId)
        }
    }

    /**
     * Told after the group's name, description or photo changed (here, or by another admin): group join links
     * publish the link's preview again while a link is on. Set by [GroupLinksRepositoryImpl]'s wiring; best effort.
     */
    var metaChanged: (suspend (chatId: String) -> Unit)? = null

    /** A frame's `avatarMediaId` when the key is there ([id] null: the group has no photo). */
    data class Avatar(val id: String?)

    /**
     * A new sender key for me, and, when I am the one who should (the admin who removed them, or the owner after a
     * leave), a new metadata key and a re-sealed name, so the person who left can read nothing more.
     */
    private suspend fun afterSomeoneLeft(chatId: String, iRemovedThem: Boolean) {
        groupKeys.rotateMySenderKey(chatId)
        val me = ownUserId()
        val chat = (refreshChat(chatId) as? AppResult.Success)?.value ?: return
        val amOwner = chat.myRole == MemberRole.Owner
        // Only with the name in hand: this phone without the group's key would otherwise seal the placeholder "Group" as
        // its name for everyone. The key is asked for instead, and the next rename or photo change re-keys.
        val name = dao.chat(chatId)?.name
        if ((iRemovedThem || amOwner) && name != null) {
            val key = groupKeys.newMetaKey()
            // The photo's reference goes into the new seal too; whoever left can no longer fetch the file (AT3 is members only).
            val description = currentDescription(chatId)
            val sealed = groupKeys.sealMetaWithin(key, name, currentPhoto(chatId), description) ?: groupKeys.sealMeta(key, name, description = description)
            patchSealed(chatId, key) { version -> UpdateGroupRequest(sealedMeta = sealed, version = version) }
        }
        groupKeys.ensureDistributed(chatId, chat.members.map { it.userId }.filter { it != me })
    }

    // ---- storage

    /** Saves a chat as the server describes it and returns it as the screens see it. */
    suspend fun store(dto: ChatDto): Chat {
        val previous = dao.chat(dto.chatId)
        val meta = if (dto.kind != MessagingWire.Group) null else {
            groupKeys.openGroupMeta(dto.chatId, dto.sealedMeta, dto.metaVersion)
                // A group this phone made whose create answer never arrived: the key kept for that attempt opens it.
                ?: (if (groupKeys.adoptPendingKey(dto.chatId, dto.sealedMeta, dto.metaVersion)) groupKeys.openGroupMeta(dto.chatId, dto.sealedMeta, dto.metaVersion) else null)
        }
        val name = if (dto.kind != MessagingWire.Group) null else meta?.name ?: previous?.name
        // The description rides in the same seal; one an older app sealed without keeps what this phone knew.
        val description = if (dto.kind != MessagingWire.Group) null else keptDescription(meta, previous?.description)
        val entity = ChatEntity(
            chatId = dto.chatId,
            kind = dto.kind,
            name = name,
            sealedMeta = dto.sealedMeta,
            metaVersion = dto.metaVersion,
            version = dto.version,
            membersCanAdd = dto.settings.membersCanAdd ?: false,
            lastMessageId = dto.lastMessageId ?: previous?.lastMessageId, // null means nothing in 30 days: keep the last id seen
            left = false,
            readUpToOrderAt = previous?.readUpToOrderAt ?: 0L,
            updatedAt = previous?.updatedAt ?: now(),
            viaSpaceId = dto.viaSpaceId ?: previous?.viaSpaceId,
            avatarMediaId = dto.avatarMediaId,
            description = description,
        )
        dao.upsertChat(entity)
        val members = dto.members.map { ChatMemberEntity(dto.chatId, it.user.userId, it.role, it.user.displayName, it.user.handle, it.user.avatarUrl, it.joinedAt) }
        dao.deleteMembers(dto.chatId)
        dao.insertMembers(members)
        return entity.toDomain(members, ownUserId(), groupPhoto(entity), saved = names.saved.value)
    }

    companion object {
        /** How long the members have to answer before an admin's rename or photo change gives the group a new key. */
        const val RekeyAfterMillis = 60_000L
        /** Someone who joined this recently is sent the key with the members' sender keys: no need to ask yet. */
        const val KeyGraceMillis = 10L * 60 * 1000
        /** The metadata version the server gives a new group's first seal (M5). */
        const val CreatedMetaVersion = 1
        /** What follows a name ("Amina", "You") in the chat's line when someone joins. */
        const val JoinedSuffix = " joined"
        const val JoinedViaLinkSuffix = " joined using the group link"
    }

    private fun MemberRole.wire() = when (this) { MemberRole.Owner -> MessagingWire.Owner; MemberRole.Admin -> MessagingWire.Admin; MemberRole.Member -> MessagingWire.Member }
}

internal fun ChatEntity.toDomain(
    members: List<ChatMemberEntity>, me: String?, groupPhoto: String? = null, keyUnavailable: Boolean = false,
    /** Names I saved for people (userId -> name): shown in place of theirs. */
    saved: Map<String, String> = emptyMap(),
): Chat {
    val shown = members.map { if (it.userId == me) it else saved[it.userId]?.let { n -> it.copy(displayName = n) } ?: it }
    val others = shown.filter { it.userId != me }
    val direct = kind == MessagingWire.Direct
    val nameKnown = direct || name != null
    return Chat(
        chatId = chatId,
        kind = if (direct) ChatKind.Direct else ChatKind.Group,
        title = if (direct) others.firstOrNull()?.displayName ?: "Chat" else name ?: unnamedGroupTitle(others.map { it.displayName }),
        avatarUrl = if (direct) others.firstOrNull()?.avatarUrl else groupPhoto,
        members = members.zip(shown) { own, it -> ChatMember(it.userId, it.displayName, it.handle, it.avatarUrl, it.role.toRole(), it.joinedAt, profileName = own.displayName) },
        membersCanAdd = membersCanAdd,
        version = version,
        metaVersion = metaVersion,
        myRole = members.firstOrNull { it.userId == me }?.role?.toRole() ?: MemberRole.Member,
        peerId = if (direct) others.firstOrNull()?.userId else null,
        viaSpaceId = viaSpaceId,
        nameKnown = nameKnown,
        nameState = when { nameKnown -> GroupNameState.Known; keyUnavailable -> GroupNameState.Unavailable; else -> GroupNameState.Waiting },
        waitingFor = if (nameKnown) null else others.singleOrNull()?.displayName?.let { com.circaso.designsystem.components.firstNameWord(it) }?.takeIf { it.isNotEmpty() },
        initialsFrom = if (nameKnown) null else others.take(2).joinToString(" ") { com.circaso.designsystem.components.firstNameWord(it.displayName) }.takeIf { it.isNotBlank() },
        description = if (direct) null else description,
    )
}

/**
 * A group whose name this phone can't read yet (no key: a new phone) is titled by who is in it, as WhatsApp does an
 * unnamed group, so it is recognisable and never shown under a made-up name (it was "Group" for every one, 2026-11-09).
 * The list row and the thread say the real name is on its way.
 */
internal fun unnamedGroupTitle(others: List<String>): String {
    val names = others.map { it.trim().substringBefore(' ') }.filter { it.isNotEmpty() }
    return when (names.size) {
        0 -> "Group"
        1 -> "You and ${names[0]}"
        2 -> "${names[0]} and ${names[1]}"
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
    }
}

internal fun String.toRole() = when (this) { MessagingWire.Owner -> MemberRole.Owner; MessagingWire.Admin -> MemberRole.Admin; else -> MemberRole.Member }
