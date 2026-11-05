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
) : ChatsRepository {
    override val chats: Flow<List<ChatSummary>> =
        combine(dao.observeChats(), dao.observeAllMembers(), messages.observeLatestPerChat(), messages.observeUnread(), ownUserIdFlow()) { chats, members, latest, unread, me ->
            val byChat = members.groupBy { it.chatId }
            val latestByChat = latest.associateBy { it.chatId }
            val unreadByChat = unread.associate { it.chatId to it.count }
            chats.map { chat ->
                val chatMembers = byChat[chat.chatId].orEmpty()
                ChatSummary(
                    chat = chat.toDomain(chatMembers, me),
                    latest = latestByChat[chat.chatId]?.toDomain(chatMembers, me),
                    unread = unreadByChat[chat.chatId] ?: 0,
                )
            }
        }

    private fun ownUserIdFlow(): Flow<String?> = kotlinx.coroutines.flow.flow { emit(ownUserId()) }

    override fun chat(chatId: String): Flow<Chat?> =
        combine(dao.observeChat(chatId), dao.observeMembers(chatId), ownUserIdFlow()) { chat, members, me -> chat?.toDomain(members, me) }

    override suspend fun refresh(): AppResult<Unit> = when (val result = remote.chats()) {
        is AppResult.Failure -> result
        is AppResult.Success -> {
            val seen = result.value.chats.map { it.chatId }.toSet()
            result.value.chats.forEach { store(it) }
            // A chat the server no longer lists is one this phone was removed from (or that was deleted).
            dao.chats().filter { it.chatId !in seen && !it.left }.forEach { dao.markLeft(it.chatId) }
            AppResult.Success(Unit)
        }
    }

    override suspend fun refreshChat(chatId: String): AppResult<Chat> = when (val result = remote.chat(chatId)) {
        is AppResult.Failure -> {
            if ((result.error as? AppError.Api)?.code == "messaging.chat_not_found") dao.markLeft(chatId)
            result
        }
        is AppResult.Success -> AppResult.Success(store(result.value))
    }

    override suspend fun openDirect(userId: String): AppResult<Chat> = remote.openDirect(userId).map { store(it) }


    override suspend fun createGroup(draft: GroupDraft, idempotencyKey: String): AppResult<GroupCreated> {
        val key = groupKeys.newMetaKey()
        val request = CreateGroupRequest(sealedMeta = groupKeys.sealMeta(key, draft.name), memberIds = draft.memberIds)
        return when (val result = remote.createGroup(request, idempotencyKey)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val dto = result.value
                groupKeys.rememberMetaKey(dto.chatId, dto.metaVersion, key)
                val chat = store(dto)
                groupKeys.ensureDistributed(dto.chatId, chat.members.map { it.userId }) // best effort now; the first send makes sure
                AppResult.Success(GroupCreated(chat, dto.notAdded.orEmpty()))
            }
        }
    }

    override suspend fun rename(chatId: String, name: String): AppResult<Chat> {
        val key = groupKeys.latestMetaKey(chatId) ?: return AppResult.Failure(AppError.Unexpected(null))
        return patchWithRetry(chatId) { version -> UpdateGroupRequest(sealedMeta = groupKeys.sealMeta(key, name), version = version) }
            .also { r -> if (r is AppResult.Success) groupKeys.rememberMetaKey(chatId, r.value.metaVersion, key) }
    }

    override suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat> =
        patchWithRetry(chatId) { version -> UpdateGroupRequest(settings = ChatSettingsDto(allowed), version = version) }

    /** `PATCH` carries the version last read; `messaging.version_conflict` means reload and try once more. */
    private suspend fun patchWithRetry(chatId: String, request: (version: Int) -> UpdateGroupRequest): AppResult<Chat> {
        val current = dao.chat(chatId) ?: return AppResult.Failure(AppError.Unexpected(null))
        val first = remote.updateGroup(chatId, request(current.version))
        if (first is AppResult.Success) return AppResult.Success(store(first.value))
        if (((first as AppResult.Failure).error as? AppError.Api)?.code != "messaging.version_conflict") return first
        val reloaded = refreshChat(chatId)
        if (reloaded is AppResult.Failure) return reloaded
        return remote.updateGroup(chatId, request((reloaded as AppResult.Success).value.version)).map { store(it) }
    }

    override suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated> =
        when (val result = remote.addMembers(chatId, AddMembersRequest(userIds))) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val chat = store(result.value)
                groupKeys.ensureDistributed(chatId, chat.members.map { it.userId })
                AppResult.Success(GroupCreated(chat, result.value.notAdded.orEmpty()))
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

    /** `member.added`: newcomers need my sender key and the group key; if I am the newcomer, I need the chat. */
    suspend fun onMemberAdded(chatId: String, userIds: List<String>) {
        val me = ownUserId()
        if (me in userIds || dao.chat(chatId) == null) { refreshChat(chatId); return }
        refreshChat(chatId)
        groupKeys.ensureDistributed(chatId, userIds)
    }

    /** `member.removed`: if it was me the chat is gone; otherwise everyone turns their sender key over. */
    suspend fun onMemberRemoved(chatId: String, userId: String, byUserId: String?) {
        val me = ownUserId()
        if (userId == me) { dao.markLeft(chatId); groupKeys.forget(chatId); return }
        dao.deleteMember(chatId, userId)
        afterSomeoneLeft(chatId, iRemovedThem = byUserId == me)
    }

    /** `chat.updated`: a newer version means reload (name, settings, roles). */
    suspend fun onChatUpdated(chatId: String, version: Int) {
        val current = dao.chat(chatId)
        if (current == null || current.version < version) refreshChat(chatId)
    }

    /**
     * A new sender key for me, and, when I am the one who should (the admin who removed them, or the owner after a
     * leave), a new metadata key and a re-sealed name, so the person who left can read nothing more.
     */
    private suspend fun afterSomeoneLeft(chatId: String, iRemovedThem: Boolean) {
        groupKeys.rotateMySenderKey(chatId)
        val me = ownUserId()
        val chat = (refreshChat(chatId) as? AppResult.Success)?.value ?: return
        val amOwner = chat.myRole == MemberRole.Owner
        if (iRemovedThem || amOwner) {
            val key = groupKeys.newMetaKey()
            val name = chat.title
            val patched = patchWithRetry(chatId) { version -> UpdateGroupRequest(sealedMeta = groupKeys.sealMeta(key, name), version = version) }
            if (patched is AppResult.Success) groupKeys.rememberMetaKey(chatId, patched.value.metaVersion, key)
        }
        groupKeys.ensureDistributed(chatId, chat.members.map { it.userId }.filter { it != me })
    }

    // ---- storage

    /** Saves a chat as the server describes it and returns it as the screens see it. */
    suspend fun store(dto: ChatDto): Chat {
        val previous = dao.chat(dto.chatId)
        val name = if (dto.kind == MessagingWire.Group) groupKeys.openMeta(dto.chatId, dto.sealedMeta, dto.metaVersion) ?: previous?.name else null
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
        )
        dao.upsertChat(entity)
        val members = dto.members.map { ChatMemberEntity(dto.chatId, it.user.userId, it.role, it.user.displayName, it.user.handle, it.user.avatarUrl, it.joinedAt) }
        dao.deleteMembers(dto.chatId)
        dao.insertMembers(members)
        return entity.toDomain(members, ownUserId())
    }

    private fun MemberRole.wire() = when (this) { MemberRole.Owner -> MessagingWire.Owner; MemberRole.Admin -> MessagingWire.Admin; MemberRole.Member -> MessagingWire.Member }
}

internal fun ChatEntity.toDomain(members: List<ChatMemberEntity>, me: String?): Chat {
    val others = members.filter { it.userId != me }
    val direct = kind == MessagingWire.Direct
    return Chat(
        chatId = chatId,
        kind = if (direct) ChatKind.Direct else ChatKind.Group,
        title = if (direct) others.firstOrNull()?.displayName ?: "Chat" else name ?: "Group",
        avatarUrl = if (direct) others.firstOrNull()?.avatarUrl else null,
        members = members.map { ChatMember(it.userId, it.displayName, it.handle, it.avatarUrl, it.role.toRole(), it.joinedAt) },
        membersCanAdd = membersCanAdd,
        version = version,
        metaVersion = metaVersion,
        myRole = members.firstOrNull { it.userId == me }?.role?.toRole() ?: MemberRole.Member,
        peerId = if (direct) others.firstOrNull()?.userId else null,
        viaSpaceId = viaSpaceId,
    )
}

internal fun String.toRole() = when (this) { MessagingWire.Owner -> MemberRole.Owner; MessagingWire.Admin -> MemberRole.Admin; else -> MemberRole.Member }
