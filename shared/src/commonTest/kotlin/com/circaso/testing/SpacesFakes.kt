package com.circaso.testing

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.spaces.data.local.MySpaceEntity
import com.circaso.feature.spaces.data.local.SpaceEntity
import com.circaso.feature.spaces.data.local.SpaceInviteEntity
import com.circaso.feature.spaces.data.local.SpaceMetaEntity
import com.circaso.feature.spaces.data.local.SpacePostEntity
import com.circaso.feature.spaces.data.local.SpaceReceiptEntity
import com.circaso.feature.spaces.data.local.SpacesDao
import com.circaso.feature.spaces.data.remote.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Spaces' Room tables in memory, with the same queries' meaning. */
class FakeSpacesDao : SpacesDao {
    val spaces = MutableStateFlow<Map<String, SpaceEntity>>(emptyMap())
    val mine = MutableStateFlow<Map<String, MySpaceEntity>>(emptyMap())
    val posts = MutableStateFlow<Map<String, SpacePostEntity>>(emptyMap())
    val invites = MutableStateFlow<Map<String, SpaceInviteEntity>>(emptyMap())
    val receipts = MutableStateFlow<Map<String, SpaceReceiptEntity>>(emptyMap())
    val meta = MutableStateFlow<Map<String, Long>>(emptyMap())

    override fun observeSpace(spaceId: String): Flow<SpaceEntity?> = spaces.map { it[spaceId] }
    override suspend fun space(spaceId: String) = spaces.value[spaceId]
    override suspend fun saveSpace(row: SpaceEntity) { spaces.value = spaces.value + (row.spaceId to row) }
    override suspend fun deleteSpace(spaceId: String) { spaces.value = spaces.value - spaceId }
    override fun observeMine(): Flow<List<MySpaceEntity>> = mine.map { m -> m.values.sortedByDescending { it.activityAt } }
    override suspend fun mine(spaceId: String) = mine.value[spaceId]
    override suspend fun saveMine(rows: List<MySpaceEntity>) { mine.value = mine.value + rows.associateBy { it.spaceId } }
    override suspend fun keepMine(keep: List<String>) { mine.value = mine.value.filterKeys { it in keep } }
    override suspend fun clearMine() { mine.value = emptyMap() }
    override suspend fun deleteMine(spaceId: String) { mine.value = mine.value - spaceId }
    override fun observePosts(spaceId: String): Flow<List<SpacePostEntity>> = posts.map { p -> p.values.filter { it.spaceId == spaceId }.sortedBy { it.createdAt } }
    override suspend fun post(localKey: String) = posts.value[localKey]
    override suspend fun postById(spaceId: String, postId: String) = posts.value.values.firstOrNull { it.spaceId == spaceId && it.postId == postId }
    override suspend fun newestSent(spaceId: String) = posts.value.values.filter { it.spaceId == spaceId && it.postId != null }.maxByOrNull { it.createdAt }
    override suspend fun oldestSent(spaceId: String) = posts.value.values.filter { it.spaceId == spaceId && it.postId != null }.minByOrNull { it.createdAt }
    override suspend fun savePosts(rows: List<SpacePostEntity>) { posts.value = posts.value + rows.associateBy { it.localKey } }
    override suspend fun setPostState(localKey: String, state: String, error: String?) { posts.value[localKey]?.let { savePosts(listOf(it.copy(state = state, error = error))) } }
    override suspend fun setPendingEmoji(localKey: String, emoji: String?) { posts.value[localKey]?.let { savePosts(listOf(it.copy(pendingEmoji = emoji))) } }
    override suspend fun deletePost(localKey: String) { posts.value = posts.value - localKey }
    override suspend fun clearSentPosts(spaceId: String) { posts.value = posts.value.filterValues { !(it.spaceId == spaceId && it.state == "sent") } }
    override suspend fun trimPosts(spaceId: String, keep: Int) {
        val drop = posts.value.values.filter { it.spaceId == spaceId && it.state == "sent" }.sortedByDescending { it.createdAt }.drop(keep).map { it.localKey }.toSet()
        posts.value = posts.value.filterKeys { it !in drop }
    }
    override fun observeInvites(): Flow<List<SpaceInviteEntity>> = invites.map { m -> m.values.sortedByDescending { it.invitedAt } }
    override suspend fun saveInvites(rows: List<SpaceInviteEntity>) { invites.value = invites.value + rows.associateBy { it.inviteId } }
    override suspend fun clearInvites() { invites.value = emptyMap() }
    override suspend fun deleteInvite(inviteId: String) { invites.value = invites.value - inviteId }
    override suspend fun deleteInvitesFor(spaceId: String) { invites.value = invites.value.filterValues { it.spaceId != spaceId } }
    override suspend fun receipt(spaceId: String) = receipts.value[spaceId]
    override suspend fun saveReceipt(row: SpaceReceiptEntity) { receipts.value = receipts.value + (row.spaceId to row) }
    override suspend fun deleteReceipt(spaceId: String) { receipts.value = receipts.value - spaceId }
    override fun observeMeta(key: String): Flow<Long?> = meta.map { it[key] }
    override suspend fun saveMeta(row: SpaceMetaEntity) { meta.value = meta.value + (row.key to row.value) }
    override suspend fun clearSpaces() { spaces.value = emptyMap() }
    override suspend fun clearPosts() { posts.value = emptyMap() }
    override suspend fun clearReceipts() { receipts.value = emptyMap() }
    override suspend fun clearMeta() { meta.value = emptyMap() }
}

/** An API error as the envelope gives it, for fakes. */
fun apiError(code: String, status: Int = 400, fields: Map<String, String> = emptyMap(), requestId: String? = "req_test") =
    AppResult.Failure(AppError.Api(code, status, requestId, null, fields))

/**
 * Every SP route answers from a field the test sets; unset routes answer `request.not_found`-free `spaces.test_unset`
 * so a test notices a call it didn't expect. [calls] records each call's name and main argument, in order.
 */
class FakeSpacesRemote : SpacesRemote {
    val calls = mutableListOf<String>()
    private fun <T> unset(): AppResult<T> = apiError("spaces.test_unset", 500)

    var handle: (String) -> AppResult<HandleAvailabilityDto> = { AppResult.Success(HandleAvailabilityDto(it, true)) }
    var createAnswer: AppResult<SpaceDto>? = null
    val createKeys = mutableListOf<String>()
    var spaceAnswer: (String) -> AppResult<SpaceDto>? = { null }
    var byHandleAnswer: (String) -> AppResult<SpaceDto>? = { null }
    var updateAnswer: AppResult<UpdatedSpaceDto>? = null
    var mineAnswer: AppResult<MySpacesPage> = AppResult.Success(MySpacesPage())
    var suggestedAnswer: AppResult<SuggestionsPage> = AppResult.Success(SuggestionsPage())
    var searchAnswer: (String) -> AppResult<SearchPage> = { AppResult.Success(SearchPage()) }
    var meAnswer: AppResult<MeDto> = AppResult.Success(MeDto(relation = "MEMBER", role = "MEMBER"))
    var leaveAnswer: AppResult<LeftDto> = AppResult.Success(LeftDto())
    var paymentAnswer: AppResult<MembershipPaymentDto>? = null
    var invitesAnswer: AppResult<InvitesDto> = AppResult.Success(InvitesDto())
    var myInvitesAnswer: AppResult<ReceivedInvitesDto> = AppResult.Success(ReceivedInvitesDto())
    var acceptAnswer: AppResult<SpaceDto>? = null
    var declineAnswer: AppResult<DeclinedDto> = AppResult.Success(DeclinedDto())
    var inviteAnswer: AppResult<InviteResultsDto> = AppResult.Success(InviteResultsDto())
    var membersAnswer: AppResult<MembersPage> = AppResult.Success(MembersPage())
    var memberAnswer: (String) -> AppResult<MemberDto> = { AppResult.Success(MemberDto(it, "Someone")) }
    var postsAnswer: (before: String?, after: String?) -> AppResult<PostsPage> = { _, _ -> AppResult.Success(PostsPage()) }
    var postAnswer: (String, NewPostRequest) -> AppResult<PostDto> = { spaceId, r ->
        AppResult.Success(PostDto("p-${r.clientPostId}", spaceId, r.clientPostId, PostAuthorDto("me", "Pasaka Mutuku"), r.text, createdAt = "2026-11-04T10:00:00Z"))
    }
    var reactAnswer: (String, String?) -> AppResult<ReactionAnswerDto> = { postId, emoji ->
        AppResult.Success(ReactionAnswerDto(postId, listOfNotNull(emoji?.let { ReactionCountDto(it, 1) }), emoji))
    }
    var removePostAnswer: (String) -> AppResult<PostDto>? = { null }
    var reportAnswer: AppResult<ReportIdDto> = AppResult.Success(ReportIdDto("rep-1"))
    var reportsAnswer: AppResult<ReportsPage> = AppResult.Success(ReportsPage())
    var resolveAnswer: AppResult<ResolvedDto> = AppResult.Success(ResolvedDto())
    var simple: AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun handleAvailability(handle: String) = this.handle(handle).also { calls += "handle:$handle" }
    override suspend fun create(request: CreateSpaceRequest, idempotencyKey: String): AppResult<SpaceDto> { calls += "create:${request.handle}"; createKeys += idempotencyKey; return createAnswer ?: unset() }
    override suspend fun space(spaceId: String): AppResult<SpaceDto> { calls += "space:$spaceId"; return spaceAnswer(spaceId) ?: unset() }
    override suspend fun byHandle(handle: String): AppResult<SpaceDto> { calls += "byHandle:$handle"; return byHandleAnswer(handle) ?: unset() }
    override suspend fun update(spaceId: String, request: UpdateSpaceRequest, idempotencyKey: String): AppResult<UpdatedSpaceDto> { calls += "update:$spaceId"; return updateAnswer ?: unset() }
    override suspend fun delete(spaceId: String, idempotencyKey: String): AppResult<DeletedDto> { calls += "delete:$spaceId"; return simple.let { if (it is AppResult.Failure) it else AppResult.Success(DeletedDto()) } }
    override suspend fun mine(cursor: String?, limit: Int) = mineAnswer.also { calls += "mine" }
    override suspend fun suggested(topic: String?, cursor: String?, limit: Int) = suggestedAnswer.also { calls += "suggested:${topic.orEmpty()}" }
    override suspend fun search(q: String, topic: String?, cursor: String?, limit: Int) = searchAnswer(q).also { calls += "search:$q" }
    override suspend fun follow(spaceId: String, idempotencyKey: String) = meAnswer.also { calls += "follow:$spaceId" }
    override suspend fun leave(spaceId: String, idempotencyKey: String) = leaveAnswer.also { calls += "leave:$spaceId" }
    override suspend fun notifications(spaceId: String, level: String, idempotencyKey: String) = meAnswer.also { calls += "notifications:$level" }
    override suspend fun membershipPayment(spaceId: String, idempotencyKey: String): AppResult<MembershipPaymentDto> { calls += "payment:$spaceId"; return paymentAnswer ?: unset() }
    override suspend fun request(spaceId: String, idempotencyKey: String) = meAnswer.also { calls += "request:$spaceId" }
    override suspend fun cancelRequest(spaceId: String, idempotencyKey: String) = meAnswer.also { calls += "cancelRequest:$spaceId" }
    override suspend fun invites(spaceId: String) = invitesAnswer.also { calls += "invites:$spaceId" }
    override suspend fun answerRequest(spaceId: String, userId: String, accept: Boolean, idempotencyKey: String): AppResult<RequestAnswerDto> {
        calls += "answer:$userId:$accept"; return simple.let { if (it is AppResult.Failure) it else AppResult.Success(RequestAnswerDto(if (accept) "ACCEPTED" else "DECLINED")) }
    }
    override suspend fun invite(spaceId: String, userIds: List<String>, idempotencyKey: String) = inviteAnswer.also { calls += "invite:${userIds.joinToString(",")}" }
    override suspend fun cancelInvite(spaceId: String, inviteId: String, idempotencyKey: String): AppResult<CancelledDto> { calls += "cancelInvite:$inviteId"; return simple.let { if (it is AppResult.Failure) it else AppResult.Success(CancelledDto()) } }
    override suspend fun myInvites() = myInvitesAnswer.also { calls += "myInvites" }
    override suspend fun acceptInvite(inviteId: String, idempotencyKey: String): AppResult<SpaceDto> { calls += "accept:$inviteId"; return acceptAnswer ?: unset() }
    override suspend fun declineInvite(inviteId: String, idempotencyKey: String) = declineAnswer.also { calls += "decline:$inviteId" }
    override suspend fun members(spaceId: String, filter: String?, q: String?, cursor: String?, limit: Int) = membersAnswer.also { calls += "members:${filter.orEmpty()}:${q.orEmpty()}" }
    override suspend fun setRole(spaceId: String, userId: String, role: String, idempotencyKey: String) = memberAnswer(userId).also { calls += "role:$userId:$role" }
    override suspend fun mute(spaceId: String, userId: String, duration: String, idempotencyKey: String) = memberAnswer(userId).also { calls += "mute:$userId:$duration" }
    override suspend fun unmute(spaceId: String, userId: String, idempotencyKey: String) = memberAnswer(userId).also { calls += "unmute:$userId" }
    override suspend fun remove(spaceId: String, userId: String, idempotencyKey: String): AppResult<RemovedDto> { calls += "remove:$userId"; return simple.let { if (it is AppResult.Failure) it else AppResult.Success(RemovedDto()) } }
    override suspend fun ban(spaceId: String, userId: String, banned: Boolean, idempotencyKey: String): AppResult<BannedDto> { calls += "ban:$userId:$banned"; return simple.let { if (it is AppResult.Failure) it else AppResult.Success(BannedDto(banned)) } }
    override suspend fun posts(spaceId: String, before: String?, after: String?, limit: Int) = postsAnswer(before, after).also { calls += "posts:${before.orEmpty()}:${after.orEmpty()}" }
    override suspend fun post(spaceId: String, request: NewPostRequest) = postAnswer(spaceId, request).also { calls += "post:${request.text}" }
    override suspend fun removePost(spaceId: String, postId: String, idempotencyKey: String): AppResult<PostDto> { calls += "removePost:$postId"; return removePostAnswer(postId) ?: unset() }
    override suspend fun react(spaceId: String, postId: String, emoji: String?, idempotencyKey: String) = reactAnswer(postId, emoji).also { calls += "react:$postId:${emoji.orEmpty()}" }
    override suspend fun read(spaceId: String, postId: String, idempotencyKey: String): AppResult<UnreadDto> { calls += "read:$postId"; return AppResult.Success(UnreadDto(0)) }
    override suspend fun prepareMedia(request: SpaceMediaRequest, idempotencyKey: String): AppResult<SpaceMediaUploadDto> = unset()
    override suspend fun completeMedia(mediaId: String, idempotencyKey: String): AppResult<SpaceMediaDoneDto> = unset()
    override suspend fun report(spaceId: String, request: ReportRequest, idempotencyKey: String) = reportAnswer.also { calls += "report:${request.postId.orEmpty()}:${request.reason}" }
    override suspend fun reports(spaceId: String, state: String, cursor: String?) = reportsAnswer.also { calls += "reports:$state" }
    override suspend fun resolve(spaceId: String, postId: String, request: ResolveRequest, idempotencyKey: String): AppResult<ResolvedDto> { calls += "resolve:$postId:${request.action}"; return resolveAnswer }
}

/** Sample answers, named as the canvas's spaces. */
object SpaceSamples {
    fun summary(id: String = "s1", name: String = "Nairobi Sneaker Drop", handle: String = "sneakerdrop", visibility: String = "PUBLIC", price: PriceDto? = null, members: Int = 1204) =
        SpaceSummaryDto(id, handle, name, "Original sneakers in Nairobi CBD.", "SHOPPING", visibility = visibility, price = price, memberCount = members)

    fun space(
        id: String = "s1", name: String = "Nairobi Sneaker Drop", handle: String = "sneakerdrop", visibility: String = "PUBLIC",
        me: MeDto = MeDto(), price: PriceDto? = null, state: String = "LIVE", postingMode: String = "EVERYONE",
        owner: PersonDto = PersonDto("baraka", "Baraka Mwita", "barakam"), wallet: SpaceWalletRefDto? = null,
    ) = SpaceDto(
        id, handle, name, "Original sneakers in Nairobi CBD.", "SHOPPING", visibility = visibility, price = price, memberCount = 1204, state = state,
        owner = owner, postingMode = postingMode, version = 1, known = KnownDto(listOf("Juma", "Faith"), 3), wallet = wallet, me = me,
    )

    val books = PriceDto(amountMinor = "5000000", amount = "5", period = "MONTHLY")

    fun post(id: String, text: String, author: String = "baraka", name: String = "Baraka Mwita", role: String? = "OWNER", at: String = "2026-11-04T09:00:00Z", client: String? = null) =
        PostDto(id, "s1", client, PostAuthorDto(author, name, null, null, role), text, reactions = listOf(ReactionCountDto("🔥", 48)), createdAt = at)
}
