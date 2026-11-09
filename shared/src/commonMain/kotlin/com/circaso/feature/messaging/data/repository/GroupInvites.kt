package com.circaso.feature.messaging.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.remote.ChatProfileDto
import com.circaso.feature.messaging.data.remote.InviteDto
import com.circaso.feature.messaging.data.remote.InvitesRemote
import com.circaso.feature.messaging.data.remote.InvitesResponse
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.GroupInvite
import com.circaso.feature.messaging.domain.GroupInvitesRepository
import com.circaso.feature.messaging.domain.InvitePerson
import com.circaso.feature.messaging.domain.PendingInvite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Where my invites wait between launches, so they show offline (the server's list as it sent it). */
interface InviteCache {
    suspend fun read(): String?
    suspend fun write(value: String?)
}

class PreferencesInviteCache(private val prefs: AppPreferences) : InviteCache {
    override suspend fun read(): String? = prefs.text(AppPreferences.GroupInvitesText).first()
    override suspend fun write(value: String?) = prefs.setText(AppPreferences.GroupInvitesText, value)
}

/**
 * Group invites (backend PR #83, 2026-11-09). My invites come from the server (M23) and wait on this phone so the Chats
 * list shows them offline; each carries the group's metadata key sealed for me by the inviter's phone, which is opened
 * once and kept as that group's key, so the card shows the real name and photo before I decide. Answering, and a
 * group's Invited list, are online-only: nothing is queued or faked.
 */
class GroupInvitesRepositoryImpl(
    private val remote: InvitesRemote,
    private val chats: ChatsRepositoryImpl,
    private val chatsDao: ChatsDao,
    private val groupKeys: GroupKeys,
    private val cache: InviteCache,
    private val json: Json,
    private val photos: GroupPhotos? = null,
    private val now: () -> Long = ::nowMillis,
) : GroupInvitesRepository {
    /** The server's list as last seen; null until the cache is read. */
    private val raw = MutableStateFlow<List<InviteDto>?>(null)
    private val lock = Mutex()
    private val _changed = MutableSharedFlow<String>(extraBufferCapacity = 8)

    override val changed: Flow<String> = _changed.asSharedFlow()

    override val invites: Flow<List<GroupInvite>> =
        combine(raw.onStart { loadCache() }.filterNotNull(), photos?.changes ?: flowOf(0)) { list, _ ->
            val t = now()
            list.filter { (isoToMillis(it.expiresAt) ?: Long.MAX_VALUE) > t }.map { it.toDomain() }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default) // names open off the main thread, however many invites wait

    private suspend fun loadCache() = lock.withLock {
        if (raw.value != null) return@withLock
        val saved = cache.read()?.let { runCatching { json.decodeFromString(InvitesResponse.serializer(), it).invites }.getOrNull() }
        raw.value = saved.orEmpty()
    }

    private suspend fun save(list: List<InviteDto>) {
        raw.value = list
        cache.write(if (list.isEmpty()) null else json.encodeToString(InvitesResponse.serializer(), InvitesResponse(list)))
    }

    override suspend fun refresh(): AppResult<Unit> = when (val r = remote.mine()) {
        is AppResult.Failure -> r
        is AppResult.Success -> {
            val list = r.value.invites
            // Each invite's key is opened before the list is shown, so the cards appear named rather than renaming.
            for (invite in list) {
                runCatching { groupKeys.openInviteKey(invite.chatId, invite.invitedBy.userId, invite.sealedKey, invite.sealedMeta, invite.metaVersion) }
            }
            lock.withLock { save(list) }
            AppResult.Success(Unit)
        }
    }

    override suspend fun accept(inviteId: String): AppResult<Chat> = when (val r = remote.accept(inviteId)) {
        is AppResult.Failure -> { if (isGone(r.error)) drop(inviteId); r }
        is AppResult.Success -> {
            // In the group now: its chat is stored like any group's; the members' phones send their keys on the
            // `member.added` frame the server sent them, and the "joined" line arrives with mine.
            val chat = chats.store(r.value)
            drop(inviteId)
            AppResult.Success(chat)
        }
    }

    override suspend fun decline(inviteId: String): AppResult<Unit> {
        val invite = current().firstOrNull { it.inviteId == inviteId }
        return when (val r = remote.decline(inviteId)) {
            is AppResult.Failure -> { if (isGone(r.error)) forget(inviteId, invite); r }
            is AppResult.Success -> { forget(inviteId, invite); AppResult.Success(Unit) }
        }
    }

    override suspend fun pending(chatId: String): AppResult<List<PendingInvite>> = when (val r = remote.ofChat(chatId)) {
        is AppResult.Failure -> r
        is AppResult.Success -> AppResult.Success(r.value.invites.map { PendingInvite(it.inviteId, it.user.toPerson(), it.invitedBy.toPerson()) })
    }

    override suspend fun cancel(chatId: String, userId: String): AppResult<Unit> = when (val r = remote.cancel(chatId, userId)) {
        is AppResult.Failure -> r
        is AppResult.Success -> { _changed.tryEmit(chatId); AppResult.Success(Unit) }
    }

    // ---- what the realtime frames ask for

    /** `invite.received`: someone invited me; the list is loaded again (the frame carries ids, the list the rest). */
    suspend fun onInviteReceived() { refresh() }

    /** `invite.removed`: an admin or the inviter took it back. */
    suspend fun onInviteRemoved(inviteId: String) = forget(inviteId, current().firstOrNull { it.inviteId == inviteId })

    /** `invites.changed`: a group I administer, or invited someone to, has a different Invited list. */
    fun onInvitesChanged(chatId: String) { _changed.tryEmit(chatId) }

    // ----

    private suspend fun current(): List<InviteDto> { loadCache(); return raw.value.orEmpty() }

    private suspend fun drop(inviteId: String) = lock.withLock { save(raw.value.orEmpty().filterNot { it.inviteId == inviteId }) }

    /**
     * An invite gone without my joining: off the list, and the group's key the invite gave this phone with it (it
     * opened only the name and photo; a later invite brings its own).
     */
    private suspend fun forget(inviteId: String, invite: InviteDto?) {
        drop(inviteId)
        if (invite != null && chatsDao.chat(invite.chatId)?.left != false) chatsDao.deleteMetaKeys(invite.chatId)
    }

    /** The server says the invite is no longer open (answered elsewhere, cancelled, or run out). */
    private fun isGone(error: AppError) = (error as? AppError.Api)?.code == InviteNotFound

    private suspend fun InviteDto.toDomain(): GroupInvite {
        val meta = groupKeys.openGroupMeta(chatId, sealedMeta, metaVersion)
        val photo = meta?.photo?.let { ref -> photos?.localPath(ref, avatarMediaId) }
        return GroupInvite(
            inviteId = inviteId,
            chatId = chatId,
            groupName = meta?.name,
            groupPhoto = photo,
            memberCount = memberCount,
            invitedBy = invitedBy.toPerson(),
            createdAt = isoToMillis(createdAt),
            expiresAt = isoToMillis(expiresAt),
        )
    }

    private fun ChatProfileDto.toPerson() = InvitePerson(userId, displayName, avatarUrl)

    companion object {
        const val InviteNotFound = "messaging.invite_not_found"
    }
}
