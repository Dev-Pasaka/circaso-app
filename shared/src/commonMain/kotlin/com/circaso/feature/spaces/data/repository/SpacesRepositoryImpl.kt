package com.circaso.feature.spaces.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.map
import com.circaso.core.flags.SpacesFlag
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxEntity
import com.circaso.core.sync.OutboxHandler
import com.circaso.core.sync.nowMillis
import com.circaso.feature.spaces.data.local.MySpaceEntity
import com.circaso.feature.spaces.data.local.SpaceEntity
import com.circaso.feature.spaces.data.local.SpaceInviteEntity
import com.circaso.feature.spaces.data.local.SpaceMetaEntity
import com.circaso.feature.spaces.data.local.SpacePostEntity
import com.circaso.feature.spaces.data.local.SpaceReceiptEntity
import com.circaso.feature.spaces.data.local.SpacesDao
import com.circaso.feature.spaces.data.remote.CreateSpaceRequest
import com.circaso.feature.spaces.data.remote.LastPostDto
import com.circaso.feature.spaces.data.remote.MySpaceDto
import com.circaso.feature.spaces.data.remote.NewPostRequest
import com.circaso.feature.spaces.data.remote.PostAuthorDto
import com.circaso.feature.spaces.data.remote.PostDto
import com.circaso.feature.spaces.data.remote.PriceRequest
import com.circaso.feature.spaces.data.remote.ReactionCountDto
import com.circaso.feature.spaces.data.remote.RemovedMarkDto
import com.circaso.feature.spaces.data.remote.ReplyToDto
import com.circaso.feature.spaces.data.remote.ReportRequest
import com.circaso.feature.spaces.data.remote.ResolveRequest
import com.circaso.feature.spaces.data.remote.SpaceDto
import com.circaso.feature.spaces.data.remote.SpaceMediaRequest
import com.circaso.feature.spaces.data.remote.SpacesRemote
import com.circaso.feature.spaces.data.remote.TeamPickDto
import com.circaso.feature.spaces.data.remote.UpdateSpaceRequest
import com.circaso.feature.spaces.domain.HandleCheck
import com.circaso.feature.spaces.domain.InviteOutcome
import com.circaso.feature.spaces.domain.MemberFilter
import com.circaso.feature.spaces.domain.MembersPageResult
import com.circaso.feature.spaces.domain.MembershipPayment
import com.circaso.feature.spaces.domain.MuteDuration
import com.circaso.feature.spaces.domain.MySpace
import com.circaso.feature.spaces.domain.NewSpace
import com.circaso.feature.spaces.domain.NotifyLevel
import com.circaso.feature.spaces.domain.Page
import com.circaso.feature.spaces.domain.PostAuthor
import com.circaso.feature.spaces.domain.ReceivedInvite
import com.circaso.feature.spaces.domain.ReportAction
import com.circaso.feature.spaces.domain.ReportGroup
import com.circaso.feature.spaces.domain.ReportReason
import com.circaso.feature.spaces.domain.SearchResult
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpaceChange
import com.circaso.feature.spaces.domain.SpaceEvent
import com.circaso.feature.spaces.domain.SpaceInvites
import com.circaso.feature.spaces.domain.SpaceMe
import com.circaso.feature.spaces.domain.SpaceMember
import com.circaso.feature.spaces.domain.SpacePost
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpaceTopic
import com.circaso.feature.spaces.domain.SpaceUpdated
import com.circaso.feature.spaces.domain.SpacesRepository
import com.circaso.feature.spaces.domain.Suggestion
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

object SpacesOutbox {
    const val Post = "space.post"
    const val Reaction = "space.reaction"
}

@Serializable internal data class PostJob(val localKey: String)
@Serializable internal data class ReactionJob(val spaceId: String, val localKey: String, val postId: String, val emoji: String? = null)

/** Sends queued space posts ([SpacesRepositoryImpl.sendQueuedPost]); the repository is found only when one is sent. */
class SpacePostHandler(private val repository: () -> SpacesRepositoryImpl) : OutboxHandler {
    override val type: String = SpacesOutbox.Post
    override suspend fun send(item: OutboxEntity): AppResult<Unit> = repository().sendQueuedPost(item)
}

class SpaceReactionHandler(private val repository: () -> SpacesRepositoryImpl) : OutboxHandler {
    override val type: String = SpacesOutbox.Reaction
    override suspend fun send(item: OutboxEntity): AppResult<Unit> = repository().sendQueuedReaction(item)
}

/** One photo to upload for a space (SP33 → PUT → SP34). */
fun interface SpaceMediaUploader {
    suspend fun upload(purpose: String, bytes: ByteArray, contentType: String): AppResult<String>
}

/**
 * Spaces on the phone and on the server. Room is the read source; posts and reactions go through the outbox with the
 * post's `clientPostId` (also its idempotency key); everything else is a live call, never queued, and nothing here is
 * ever optimistic about membership: following, joining and accepting show the server's answer.
 */
class SpacesRepositoryImpl(
    private val dao: SpacesDao,
    private val remote: SpacesRemote,
    private val flag: SpacesFlag,
    private val outbox: Outbox,
    private val kickOutbox: () -> Unit,
    private val json: Json,
    private val ownUserId: suspend () -> String?,
    /** How my own waiting posts are signed until the server's copy comes back. */
    private val me: suspend () -> PostAuthor?,
    private val uploader: SpaceMediaUploader? = null,
    /**
     * Posts the membership receipt (SM2) into the private chat with the owner, once the payment confirmed. False when it
     * couldn't (offline, no chat yet): the receipt stays on the phone and is tried again on the next membership frame.
     */
    private val postReceipt: (suspend (space: Space, receipt: SpaceReceiptEntity) -> Boolean)? = null,
    private val now: () -> Long = ::nowMillis,
) : SpacesRepository {
    override val enabled: StateFlow<Boolean> = flag.enabled

    private val _events = MutableSharedFlow<SpaceEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SpaceEvent> = _events.asSharedFlow()

    private var openSpaceId: String? = null

    override val mySpaces: Flow<List<MySpace>> = dao.observeMine().map { rows ->
        rows.mapNotNull { r -> decode<MySpaceDto>(r.json)?.toDomain(now()) }
    }

    override val mineAsOf: Flow<Long> = dao.observeMeta(MetaMine).map { it ?: 0L }

    override val invites: Flow<List<ReceivedInvite>> = dao.observeInvites().map { rows ->
        val at = now()
        rows.mapNotNull { r -> decode<com.circaso.feature.spaces.data.remote.ReceivedInviteDto>(r.json)?.toDomain() }.filter { it.expiresAt == 0L || it.expiresAt > at }
    }

    override fun space(spaceId: String): Flow<Space?> = dao.observeSpace(spaceId).map { row -> row?.let { r -> decode<SpaceDto>(r.json)?.toDomain(r.savedAt) } }

    override fun posts(spaceId: String): Flow<List<SpacePost>> = dao.observePosts(spaceId).map { rows ->
        rows.mapNotNull { r -> decode<PostDto>(r.json)?.toDomain(r.localKey, r.mine, r.state, r.error, r.pendingEmoji) }
    }

    // ---------------------------------------------------------------- reading

    override suspend fun refreshMine(): AppResult<Unit> {
        val all = mutableListOf<MySpaceDto>()
        var cursor: String? = null
        do {
            val page = when (val r = remote.mine(cursor)) { is AppResult.Failure -> return r; is AppResult.Success -> r.value }
            all += page.spaces
            cursor = page.next
        } while (cursor != null && all.size < 1000)
        dao.saveMine(all.map { MySpaceEntity(it.space.spaceId, json.encodeToString(MySpaceDto.serializer(), it), it.activityAt?.let(::iso) ?: it.lastPost?.let { p -> iso(p.at) } ?: 0) })
        if (all.isEmpty()) dao.clearMine() else dao.keepMine(all.map { it.space.spaceId })
        dao.saveMeta(SpaceMetaEntity(MetaMine, now()))
        return AppResult.Success(Unit)
    }

    override suspend fun refreshInvites(): AppResult<Unit> = remote.myInvites().map { r ->
        dao.clearInvites()
        dao.saveInvites(r.invites.map { SpaceInviteEntity(it.inviteId, it.space.spaceId, json.encodeToString(com.circaso.feature.spaces.data.remote.ReceivedInviteDto.serializer(), it), iso(it.invitedAt) ?: 0) })
    }

    override suspend fun suggested(topic: SpaceTopic?, cursor: String?): AppResult<Page<Suggestion>> =
        remote.suggested(topic?.wire, cursor).map { p -> Page(p.spaces.map { it.toDomain() }, p.next) }

    override suspend fun search(query: String, topic: SpaceTopic?): AppResult<SearchResult> {
        val q = query.trim().take(60)
        if (q.isEmpty() || q == "@") return AppResult.Success(SearchResult(null, emptyList(), null))
        return remote.search(q, topic?.wire).map { p -> SearchResult(p.exact?.toDomain(), p.spaces.map { it.toDomain() }.filter { it.spaceId != p.exact?.spaceId }, p.next) }
    }

    override suspend fun refreshSpace(spaceId: String): AppResult<Space> {
        val r = remote.space(spaceId)
        if (r is AppResult.Failure) {
            val code = (r.error as? AppError.Api)?.code
            // Gone or not visible any more: the phone forgets it, so it can't keep showing as if nothing happened.
            if (code == "spaces.space_unavailable" || code == "spaces.space_not_found") forget(spaceId)
        }
        return r.map { save(it) }
    }

    override suspend fun spaceByHandle(handle: String): AppResult<Space> = remote.byHandle(handle.trimStart('@').lowercase()).map { save(it) }

    private suspend fun save(dto: SpaceDto): Space {
        val at = now()
        dao.saveSpace(SpaceEntity(dto.spaceId, dto.handle.lowercase(), json.encodeToString(SpaceDto.serializer(), dto), at))
        // Keep "Your spaces" in step with what the space page just learned (role, membership, unread, details).
        dao.mine(dto.spaceId)?.let { row ->
            decode<MySpaceDto>(row.json)?.let { m ->
                val next = m.copy(space = dto.summary(), me = dto.me, unreadCount = dto.me.unreadCount)
                dao.saveMine(listOf(row.copy(json = json.encodeToString(MySpaceDto.serializer(), next))))
            }
        }
        return dto.toDomain(at)
    }

    private suspend fun forget(spaceId: String) {
        dao.deleteMine(spaceId)
        dao.deleteInvitesFor(spaceId)
    }

    // ---------------------------------------------------------------- create and change

    override suspend fun checkHandle(handle: String): AppResult<HandleCheck> =
        remote.handleAvailability(handle.trimStart('@').lowercase()).map { HandleCheck(it.handle, it.available, it.suggestions) }

    override suspend fun create(draft: NewSpace, idempotencyKey: String): AppResult<Space> {
        val photoId = draft.photo?.let { bytes ->
            when (val r = uploader?.upload("SPACE_PHOTO", bytes, "image/jpeg") ?: return AppResult.Failure(AppError.Unexpected(null))) {
                is AppResult.Failure -> return r
                is AppResult.Success -> r.value
            }
        }
        val request = CreateSpaceRequest(
            name = draft.name.trim(), handle = draft.handle.trimStart('@').lowercase(), about = draft.about?.trim()?.takeIf { it.isNotEmpty() },
            topic = draft.topic.wire, photoMediaId = photoId, visibility = draft.visibility.wire,
            price = draft.price?.takeIf { draft.visibility == com.circaso.feature.spaces.domain.SpaceVisibility.MembersOnly }?.let { PriceRequest(amountMinor = it.amountMinor, period = it.period.wire) },
            postingMode = draft.postingMode.wire,
            privateMessages = if (draft.visibility == com.circaso.feature.spaces.domain.SpaceVisibility.InviteOnly) "MEMBERS" else draft.privateMessages.wire,
            team = draft.team.map { TeamPickDto(it.person.userId, it.role.wire) },
        )
        val r = remote.create(request, idempotencyKey)
        if (r is AppResult.Success) refreshMine()
        return r.map { save(it) }
    }

    override suspend fun update(spaceId: String, change: SpaceChange): AppResult<SpaceUpdated> {
        val photoId = change.photo?.let { bytes ->
            when (val r = uploader?.upload("SPACE_PHOTO", bytes, "image/jpeg") ?: return AppResult.Failure(AppError.Unexpected(null))) {
                is AppResult.Failure -> return r
                is AppResult.Success -> r.value
            }
        }
        val request = UpdateSpaceRequest(
            name = change.name?.trim(), about = change.about?.trim(), topic = change.topic?.wire, photoMediaId = photoId,
            postingMode = change.postingMode?.wire, handle = change.handle?.trimStart('@')?.lowercase(), visibility = change.visibility?.wire,
            price = change.price?.let { PriceRequest(amountMinor = it.amountMinor, period = it.period.wire) }, privateMessages = change.privateMessages?.wire,
            removePhoto = true.takeIf { change.removePhoto && photoId == null }, removeCover = true.takeIf { change.removeCover },
        )
        return remote.update(spaceId, request, newIdempotencyKey()).map { SpaceUpdated(save(it.space), it.notified) }
    }

    override suspend fun delete(spaceId: String): AppResult<Unit> = remote.delete(spaceId, newIdempotencyKey()).map {
        forget(spaceId); dao.deleteSpace(spaceId)
    }

    // ---------------------------------------------------------------- joining

    override suspend fun follow(spaceId: String): AppResult<SpaceMe> = remote.follow(spaceId, newIdempotencyKey()).map { me -> afterMe(spaceId, me) }

    override suspend fun leave(spaceId: String): AppResult<Unit> = remote.leave(spaceId, newIdempotencyKey()).map {
        dao.deleteMine(spaceId)
        refreshSpace(spaceId)
        Unit
    }

    override suspend fun setNotifications(spaceId: String, level: NotifyLevel): AppResult<SpaceMe> =
        remote.notifications(spaceId, level.wire, newIdempotencyKey()).map { me -> afterMe(spaceId, me) }

    /** A new `Me` from the server: the space and the list follow it at once (SP3 a moment later fills the rest). */
    private suspend fun afterMe(spaceId: String, me: com.circaso.feature.spaces.data.remote.MeDto): SpaceMe {
        dao.space(spaceId)?.let { row -> decode<SpaceDto>(row.json)?.let { save(it.copy(me = me)) } }
        if (me.relation == "MEMBER") refreshMine()
        refreshSpace(spaceId)
        return me.toDomain()
    }

    override suspend fun membershipPayment(spaceId: String): AppResult<MembershipPayment> =
        remote.membershipPayment(spaceId, newIdempotencyKey()).map { it.toDomain() }

    override suspend fun rememberMembershipPayment(spaceId: String, transferId: String, amountMinor: String, blockchain: String, note: String) =
        dao.saveReceipt(SpaceReceiptEntity(spaceId, transferId, amountMinor, blockchain, note, now()))

    // ---------------------------------------------------------------- invites and requests

    override suspend fun request(spaceId: String): AppResult<SpaceMe> = remote.request(spaceId, newIdempotencyKey()).map { afterMe(spaceId, it) }
    override suspend fun cancelRequest(spaceId: String): AppResult<SpaceMe> = remote.cancelRequest(spaceId, newIdempotencyKey()).map { afterMe(spaceId, it) }

    override suspend fun spaceInvites(spaceId: String): AppResult<SpaceInvites> =
        remote.invites(spaceId).map { r -> SpaceInvites(r.requests.map { it.toDomain() }, r.invites.map { it.toDomain() }) }

    override suspend fun answerRequest(spaceId: String, userId: String, accept: Boolean): AppResult<Unit> =
        remote.answerRequest(spaceId, userId, accept, newIdempotencyKey()).map { }

    override suspend fun invite(spaceId: String, userIds: List<String>): AppResult<Map<String, InviteOutcome>> =
        remote.invite(spaceId, userIds.distinct().take(50), newIdempotencyKey()).map { r ->
            r.results.associate { it.userId to when (it.outcome) { "INVITED" -> InviteOutcome.Invited; "ALREADY_MEMBER" -> InviteOutcome.AlreadyMember; "BANNED" -> InviteOutcome.Banned; else -> InviteOutcome.Unreachable } }
        }

    override suspend fun cancelInvite(spaceId: String, inviteId: String): AppResult<Unit> = remote.cancelInvite(spaceId, inviteId, newIdempotencyKey()).map { }

    override suspend fun acceptInvite(inviteId: String): AppResult<Space> {
        val r = remote.acceptInvite(inviteId, newIdempotencyKey())
        if (r is AppResult.Success || (r as? AppResult.Failure)?.let { (it.error as? AppError.Api)?.code == "spaces.invite_not_found" } == true) dao.deleteInvite(inviteId)
        return r.map { dto -> save(dto).also { refreshMine() } }
    }

    override suspend fun declineInvite(inviteId: String): AppResult<Unit> {
        val r = remote.declineInvite(inviteId, newIdempotencyKey())
        if (r is AppResult.Success || (r as? AppResult.Failure)?.let { (it.error as? AppError.Api)?.code == "spaces.invite_not_found" } == true) dao.deleteInvite(inviteId)
        return r.map { }
    }

    // ---------------------------------------------------------------- members

    override suspend fun members(spaceId: String, filter: MemberFilter, query: String?, cursor: String?): AppResult<MembersPageResult> =
        remote.members(spaceId, filter.wire.takeIf { filter != MemberFilter.All }, query, cursor).map { p ->
            MembersPageResult(p.team.map { it.toDomain() }, p.contacts.map { it.toDomain() }, p.members.map { it.toDomain() }, p.count, p.next)
        }

    override suspend fun setRole(spaceId: String, userId: String, role: SpaceRole): AppResult<SpaceMember> =
        remote.setRole(spaceId, userId, role.wire, newIdempotencyKey()).map { it.toDomain() }

    override suspend fun mute(spaceId: String, userId: String, duration: MuteDuration): AppResult<SpaceMember> =
        remote.mute(spaceId, userId, duration.wire, newIdempotencyKey()).map { it.toDomain() }

    override suspend fun unmute(spaceId: String, userId: String): AppResult<SpaceMember> = remote.unmute(spaceId, userId, newIdempotencyKey()).map { it.toDomain() }
    override suspend fun remove(spaceId: String, userId: String): AppResult<Unit> = remote.remove(spaceId, userId, newIdempotencyKey()).map { }
    override suspend fun ban(spaceId: String, userId: String, banned: Boolean): AppResult<Unit> = remote.ban(spaceId, userId, banned, newIdempotencyKey()).map { }

    // ---------------------------------------------------------------- the space chat

    override suspend fun refreshPosts(spaceId: String): AppResult<Unit> {
        val newest = dao.newestSent(spaceId)?.postId
        val own = ownUserId()
        if (newest == null) {
            return remote.posts(spaceId).map { p -> savePosts(p.posts, own) }
        }
        // Catch up from the newest held, oldest first, page by page; a long gap is better re-read from the top.
        var after: String? = newest
        var pages = 0
        while (after != null && pages < 5) {
            val p = when (val r = remote.posts(spaceId, after = after)) { is AppResult.Failure -> return r; is AppResult.Success -> r.value }
            savePosts(p.posts, own)
            after = p.next
            pages++
        }
        if (after != null) { dao.clearSentPosts(spaceId); return remote.posts(spaceId).map { p -> savePosts(p.posts, own) } }
        dao.trimPosts(spaceId, KeepPosts)
        return AppResult.Success(Unit)
    }

    override suspend fun loadOlder(spaceId: String): AppResult<Boolean> {
        val oldest = dao.oldestSent(spaceId)?.postId ?: return refreshPosts(spaceId).map { true }
        val own = ownUserId()
        return remote.posts(spaceId, before = oldest).map { p -> savePosts(p.posts, own); p.next != null }
    }

    private suspend fun savePosts(posts: List<PostDto>, own: String?) {
        if (posts.isEmpty()) return
        val rows = posts.map { p ->
            val mine = p.author.userId == own
            val key = p.clientPostId?.takeIf { mine } ?: p.postId
            // A reaction of mine still waiting keeps waiting (the server's copy predates it).
            val pending = dao.post(key)?.pendingEmoji
            SpacePostEntity(key, p.spaceId, p.postId, iso(p.createdAt) ?: now(), json.encodeToString(PostDto.serializer(), p), mine, "sent", null, pending)
        }
        dao.savePosts(rows)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun sendPost(spaceId: String, text: String, replyTo: SpacePost?) {
        val body = text.trim().take(4000)
        if (body.isEmpty()) return
        val clientPostId = Uuid.random().toString()
        val author = me() ?: PostAuthor(ownUserId().orEmpty(), "You", null, null, null)
        val role = dao.space(spaceId)?.let { decode<SpaceDto>(it.json)?.me?.role }
        val at = now()
        val dto = PostDto(
            postId = "", spaceId = spaceId, clientPostId = clientPostId,
            author = PostAuthorDto(author.userId, author.displayName, author.handle, author.avatarUrl, role),
            text = body,
            replyTo = replyTo?.postId?.let { ReplyToDto(it, replyTo.author.displayName, replyTo.text?.take(120), replyTo.removed != null) },
            createdAt = Instant.fromEpochMilliseconds(at).toString(),
        )
        dao.savePosts(listOf(SpacePostEntity(clientPostId, spaceId, null, at, json.encodeToString(PostDto.serializer(), dto), true, "waiting", null, null)))
        outbox.enqueue(SpacesOutbox.Post, json.encodeToString(PostJob.serializer(), PostJob(clientPostId)))
        kickOutbox()
    }

    /** The outbox's turn for one post: SP29 with its client id; a repeat answers the first post. */
    internal suspend fun sendQueuedPost(item: OutboxEntity): AppResult<Unit> {
        val job = runCatching { json.decodeFromString(PostJob.serializer(), item.payload) }.getOrNull() ?: return AppResult.Success(Unit)
        val row = dao.post(job.localKey) ?: return AppResult.Success(Unit) // discarded meanwhile
        val dto = decode<PostDto>(row.json) ?: return AppResult.Success(Unit)
        val r = remote.post(row.spaceId, NewPostRequest(job.localKey, dto.text, dto.replyTo?.postId))
        when (r) {
            is AppResult.Success -> {
                val p = r.value
                dao.savePosts(listOf(row.copy(postId = p.postId, createdAt = iso(p.createdAt) ?: row.createdAt, json = json.encodeToString(PostDto.serializer(), p), state = "sent", error = null)))
            }
            is AppResult.Failure -> {
                val e = r.error
                val retryable = e is AppError.Network || e is AppError.Unexpected || (e is AppError.Api && (e.httpStatus >= 500 || e.httpStatus == 429))
                if (!retryable) dao.setPostState(row.localKey, "failed", (e as? AppError.Api)?.code)
            }
        }
        return r.map { }
    }

    override suspend fun react(spaceId: String, localKey: String, emoji: String?) {
        val row = dao.post(localKey) ?: return
        val postId = row.postId ?: return // a post still on its way can't be reacted to yet
        dao.setPendingEmoji(localKey, emoji ?: "")
        outbox.enqueue(SpacesOutbox.Reaction, json.encodeToString(ReactionJob.serializer(), ReactionJob(spaceId, localKey, postId, emoji)))
        kickOutbox()
    }

    internal suspend fun sendQueuedReaction(item: OutboxEntity): AppResult<Unit> {
        val job = runCatching { json.decodeFromString(ReactionJob.serializer(), item.payload) }.getOrNull() ?: return AppResult.Success(Unit)
        val r = remote.react(job.spaceId, job.postId, job.emoji, item.idempotencyKey)
        val row = dao.post(job.localKey)
        when (r) {
            is AppResult.Success -> if (row != null) {
                val dto = decode<PostDto>(row.json)
                val stillMine = row.pendingEmoji == (job.emoji ?: "")
                if (dto != null) dao.savePosts(listOf(row.copy(
                    json = json.encodeToString(PostDto.serializer(), dto.copy(reactions = r.value.reactions, myReaction = r.value.myReaction)),
                    // A newer tap queued after this one keeps waiting; this answer is the latest only if nothing changed since.
                    pendingEmoji = if (stillMine) null else row.pendingEmoji,
                )))
            }
            is AppResult.Failure -> {
                val e = r.error
                val retryable = e is AppError.Network || e is AppError.Unexpected || (e is AppError.Api && (e.httpStatus >= 500 || e.httpStatus == 429))
                // Refused for good (not a member any more, post gone): show the server's state, not a reaction that never landed.
                if (!retryable && row != null) dao.setPendingEmoji(row.localKey, null)
            }
        }
        return r.map { }
    }

    override suspend fun removePost(spaceId: String, postId: String): AppResult<Unit> = remote.removePost(spaceId, postId, newIdempotencyKey()).map { p ->
        dao.postById(spaceId, postId)?.let { row -> dao.savePosts(listOf(row.copy(json = json.encodeToString(PostDto.serializer(), p)))) }
        Unit
    }

    override suspend fun markRead(spaceId: String, postId: String) {
        val r = remote.read(spaceId, postId, newIdempotencyKey())
        if (r is AppResult.Success) patchMine(spaceId) { it.copy(unreadCount = r.value.unreadCount, me = it.me.copy(unreadCount = r.value.unreadCount)) }
    }

    override suspend fun retryPost(localKey: String) {
        val row = dao.post(localKey) ?: return
        dao.setPostState(localKey, "waiting", null)
        // The failed outbox item is replaced by a fresh one with the same client id, so the server still dedupes it.
        outbox.enqueue(SpacesOutbox.Post, json.encodeToString(PostJob.serializer(), PostJob(row.localKey)))
        kickOutbox()
    }

    override suspend fun discardPost(localKey: String) = dao.deletePost(localKey)

    override fun setOpen(spaceId: String?) { openSpaceId = spaceId }

    // ---------------------------------------------------------------- reports

    override suspend fun report(spaceId: String, postId: String?, reason: ReportReason, detail: String?): AppResult<String> =
        remote.report(spaceId, ReportRequest(postId, reason.wire, detail?.trim()?.take(500)?.takeIf { it.isNotEmpty() }), newIdempotencyKey()).map { it.reportId }

    override suspend fun reports(spaceId: String, open: Boolean, cursor: String?): AppResult<Page<ReportGroup>> {
        val own = ownUserId()
        return remote.reports(spaceId, if (open) "OPEN" else "DONE", cursor).map { p -> Page(p.reports.map { it.toDomain(own) }, p.next) }
    }

    override suspend fun resolve(spaceId: String, postId: String, action: ReportAction, muteDuration: MuteDuration?): AppResult<ReportGroup> {
        val own = ownUserId()
        return remote.resolve(spaceId, postId, ResolveRequest(action.wire, muteDuration?.wire), newIdempotencyKey()).map { g ->
            if (action == ReportAction.RemovePost) dao.postById(spaceId, postId)?.let { row -> dao.savePosts(listOf(row.copy(json = json.encodeToString(PostDto.serializer(), g.post)))) }
            g.toDomain(own)
        }
    }

    // ---------------------------------------------------------------- realtime

    override suspend fun onFrame(type: String, data: JsonObject?) {
        if (type == "resync") { refreshMine(); refreshInvites(); return }
        val spaceId = data.str("spaceId")
        when (type) {
            "space.post_new" -> {
                val post = (data?.get("post") as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<PostDto>(it) }.getOrNull() } ?: return
                val own = ownUserId()
                savePosts(listOf(post), own)
                val mine = post.author.userId == own
                patchMine(post.spaceId) { m ->
                    val unread = if (mine || openSpaceId == post.spaceId) m.unreadCount else (m.unreadCount + 1).coerceAtMost(99)
                    m.copy(
                        lastPost = LastPostDto(post.postId, post.author.displayName.substringBefore(' '), post.text, if (post.media.isEmpty()) "TEXT" else post.media.first().kind, post.createdAt),
                        unreadCount = unread, activityAt = post.createdAt,
                    )
                }
            }
            "space.post_updated" -> {
                val postId = data.str("postId") ?: return
                val sid = spaceId ?: return
                val row = dao.postById(sid, postId) ?: return
                val dto = decode<PostDto>(row.json) ?: return
                val reactions = (data?.get("reactions") as? JsonArray)?.let { runCatching { json.decodeFromJsonElement<List<ReactionCountDto>>(it) }.getOrNull() }
                val removed = (data?.get("removed") as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<RemovedMarkDto>(it) }.getOrNull() }
                val next = dto.copy(
                    reactions = reactions ?: dto.reactions,
                    removed = removed ?: dto.removed,
                    text = if (removed != null) null else dto.text,
                    media = if (removed != null) emptyList() else dto.media,
                )
                dao.savePosts(listOf(row.copy(json = json.encodeToString(PostDto.serializer(), next))))
            }
            "space.updated" -> {
                val sid = spaceId ?: return
                refreshSpace(sid)
                if (data.str("state") != null) refreshMine()
            }
            "space.membership_updated" -> {
                val sid = spaceId ?: return
                val reason = data.str("reason").orEmpty()
                val space = (refreshSpace(sid) as? AppResult.Success)?.value
                refreshMine()
                // Out of the chat (removed, banned, left, ended): the phone stops holding its posts.
                if (reason in setOf("REMOVED", "BANNED", "LEFT", "ENDED")) dao.clearSentPosts(sid)
                _events.tryEmit(SpaceEvent.Membership(sid, data.str("spaceName") ?: space?.name.orEmpty(), reason, iso(data.str("until"))))
                if (space != null && reason in setOf("JOINED", "RENEWED")) sendReceipt(space)
            }
            "space.price_changed" -> {
                val sid = spaceId ?: return
                refreshSpace(sid)
                val price = (data?.get("price") as? JsonObject).str("amountMinor")
                val previous = (data?.get("previous") as? JsonObject).str("amountMinor")
                if (price != null && previous != null) _events.tryEmit(SpaceEvent.PriceChanged(sid, price, previous))
            }
            "space.invited" -> {
                refreshInvites()
                _events.tryEmit(SpaceEvent.Invited(spaceId.orEmpty(), data.str("spaceName").orEmpty(), data.str("inviterName").orEmpty()))
            }
            "space.invite_removed" -> data.str("inviteId")?.let { dao.deleteInvite(it) }
            "space.invite_accepted" -> _events.tryEmit(SpaceEvent.InviteAccepted(spaceId.orEmpty(), data.str("spaceName").orEmpty(), data.str("name").orEmpty()))
            "space.request_new" -> _events.tryEmit(SpaceEvent.RequestNew(spaceId.orEmpty(), data.str("spaceName").orEmpty(), data.str("name").orEmpty()))
            "space.report_new" -> _events.tryEmit(SpaceEvent.ReportNew(spaceId.orEmpty(), data.str("postId").orEmpty(), data.str("count")?.toIntOrNull() ?: 1))
            "space.report_resolved" -> _events.tryEmit(SpaceEvent.ReportResolved(spaceId.orEmpty(), data.str("action").orEmpty()))
        }
    }

    /** SM2: the confirmed membership's card goes to the private chat with the owner, once. */
    private suspend fun sendReceipt(space: Space) {
        val receipt = dao.receipt(space.spaceId) ?: return
        val post = postReceipt ?: return
        if (post(space, receipt)) dao.deleteReceipt(space.spaceId)
    }

    private suspend fun patchMine(spaceId: String, change: (MySpaceDto) -> MySpaceDto) {
        val row = dao.mine(spaceId) ?: return
        val m = decode<MySpaceDto>(row.json) ?: return
        val next = change(m)
        dao.saveMine(listOf(MySpaceEntity(spaceId, json.encodeToString(MySpaceDto.serializer(), next), next.activityAt?.let(::iso) ?: row.activityAt)))
    }

    private inline fun <reified T> decode(text: String): T? = runCatching { json.decodeFromString<T>(text) }.getOrNull()

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

    companion object {
        const val MetaMine = "mine"
        /** Sent posts kept per space on the phone; older ones load from the server when scrolled to. */
        const val KeepPosts = 300
    }
}

/** SP33 → PUT the bytes with exactly the headers given → SP34 (space content isn't end to end encrypted). */
class KtorSpaceMediaUploader(private val remote: SpacesRemote, private val storage: HttpClient) : SpaceMediaUploader {
    override suspend fun upload(purpose: String, bytes: ByteArray, contentType: String): AppResult<String> {
        val prepared = when (val r = remote.prepareMedia(SpaceMediaRequest(purpose, "IMAGE", contentType, bytes.size.toLong()), newIdempotencyKey())) {
            is AppResult.Failure -> return r
            is AppResult.Success -> r.value
        }
        val ok = runCatching {
            storage.put(prepared.uploadUrl) {
                prepared.uploadHeaders.forEach { (k, v) -> header(k, v) }
                setBody(bytes)
            }.status.isSuccess()
        }.getOrDefault(false)
        if (!ok) return AppResult.Failure(AppError.Network)
        return remote.completeMedia(prepared.mediaId, newIdempotencyKey()).map { it.mediaId }
    }
}
