package com.circaso.feature.spaces.domain

import com.circaso.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Spaces core (references/spaces-api.md). Reads come from Room (my spaces, each opened space, its latest posts, my
 * invites) and refresh from the server; **posts and reactions are queueable writes** (outbox, `clientPostId`), and
 * everything else is online-only: the screens disable it offline before the tap. Money is never here: joining is
 * SP13 then the wallet's W4 through `PayViewModel`, and a membership exists only once the transfer is confirmed.
 *
 * Action classes (offline-first.md): Read: [mySpaces], [space], [posts], [invites]. Queueable: [sendPost], [react].
 * Online-only: everything else. Money: none (W4 lives in the wallet).
 */
interface SpacesRepository {
    /** [com.circaso.core.flags.SpacesFlag.enabled]: spaces are on for this phone and this server. */
    val enabled: StateFlow<Boolean>

    /** SD1 "Your spaces", newest activity first, from the phone. */
    val mySpaces: Flow<List<MySpace>>

    /** SI2: live invites for the top of Chats, newest first, from the phone. */
    val invites: Flow<List<ReceivedInvite>>

    /** One space as the phone last read it (SS6b offline), null before the first read. */
    fun space(spaceId: String): Flow<Space?>

    /** The space chat on the phone, oldest first, with my posts still on their way. */
    fun posts(spaceId: String): Flow<List<SpacePost>>

    /** Realtime news for me: membership changes, invites, requests, reports. */
    val events: SharedFlow<SpaceEvent>

    /** When "Your spaces" was last read from the server (offline "as of"); 0 before the first read. */
    val mineAsOf: Flow<Long>

    suspend fun refreshMine(): AppResult<Unit>
    suspend fun refreshInvites(): AppResult<Unit>
    suspend fun suggested(topic: SpaceTopic?, cursor: String? = null): AppResult<Page<Suggestion>>
    suspend fun search(query: String, topic: SpaceTopic?): AppResult<SearchResult>

    /** SP3, saved to the phone. `spaces.space_unavailable` and `spaces.space_not_found` come back as errors (SS6d). */
    suspend fun refreshSpace(spaceId: String): AppResult<Space>
    suspend fun spaceByHandle(handle: String): AppResult<Space>

    // ---- Create and change (SN)
    suspend fun checkHandle(handle: String): AppResult<HandleCheck>

    /** SP2 with [idempotencyKey] kept by the caller, so Try Again can never make two spaces (SN7b). */
    suspend fun create(draft: NewSpace, idempotencyKey: String): AppResult<Space>
    suspend fun update(spaceId: String, change: SpaceChange): AppResult<SpaceUpdated>
    suspend fun delete(spaceId: String): AppResult<Unit>

    // ---- Joining (SS, SM)
    suspend fun follow(spaceId: String): AppResult<SpaceMe>
    suspend fun leave(spaceId: String): AppResult<Unit>
    suspend fun setNotifications(spaceId: String, level: NotifyLevel): AppResult<SpaceMe>
    /** SP13: a fresh quote for joining, renewing or rejoining (held 15 minutes). */
    suspend fun membershipPayment(spaceId: String): AppResult<MembershipPayment>
    /** After my W4 for a membership was signed: kept so the receipt can go to the private chat once it confirms (SM2). */
    suspend fun rememberMembershipPayment(spaceId: String, transferId: String, amountMinor: String, blockchain: String, note: String)

    // ---- Invites and requests (SI)
    suspend fun request(spaceId: String): AppResult<SpaceMe>
    suspend fun cancelRequest(spaceId: String): AppResult<SpaceMe>
    suspend fun spaceInvites(spaceId: String): AppResult<SpaceInvites>
    suspend fun answerRequest(spaceId: String, userId: String, accept: Boolean): AppResult<Unit>
    suspend fun invite(spaceId: String, userIds: List<String>): AppResult<Map<String, InviteOutcome>>
    suspend fun cancelInvite(spaceId: String, inviteId: String): AppResult<Unit>
    suspend fun acceptInvite(inviteId: String): AppResult<Space>
    /** SI3b: called after the few seconds of Undo; nobody is told. */
    suspend fun declineInvite(inviteId: String): AppResult<Unit>

    // ---- Members, roles, moderation (SS5, SR)
    suspend fun members(spaceId: String, filter: MemberFilter = MemberFilter.All, query: String? = null, cursor: String? = null): AppResult<MembersPageResult>
    suspend fun setRole(spaceId: String, userId: String, role: SpaceRole): AppResult<SpaceMember>
    suspend fun mute(spaceId: String, userId: String, duration: MuteDuration): AppResult<SpaceMember>
    suspend fun unmute(spaceId: String, userId: String): AppResult<SpaceMember>
    suspend fun remove(spaceId: String, userId: String): AppResult<Unit>
    suspend fun ban(spaceId: String, userId: String, banned: Boolean): AppResult<Unit>

    // ---- Space chat (SH1)
    /** The newest page, or what came after the newest the phone holds. Visitors of a public space get the latest 20. */
    suspend fun refreshPosts(spaceId: String): AppResult<Unit>
    /** One page older than the oldest on the phone; false when there are no more. */
    suspend fun loadOlder(spaceId: String): AppResult<Boolean>
    /** Queued: shows at once as Waiting and sends in order, also after an offline spell. */
    suspend fun sendPost(spaceId: String, text: String, replyTo: SpacePost? = null)
    /** Queued, last write wins per post; [emoji] null takes mine back. */
    suspend fun react(spaceId: String, localKey: String, emoji: String?)
    suspend fun removePost(spaceId: String, postId: String): AppResult<Unit>
    suspend fun markRead(spaceId: String, postId: String)
    suspend fun retryPost(localKey: String)
    suspend fun discardPost(localKey: String)
    /** The space chat on screen: its new posts don't count as unread. */
    fun setOpen(spaceId: String?)

    // ---- Reports (SR3, SR4)
    suspend fun report(spaceId: String, postId: String?, reason: ReportReason, detail: String?): AppResult<String>
    suspend fun reports(spaceId: String, open: Boolean, cursor: String? = null): AppResult<Page<ReportGroup>>
    suspend fun resolve(spaceId: String, postId: String, action: ReportAction, muteDuration: MuteDuration? = null): AppResult<Unit>

    /** A `spaces` realtime frame; `resync` reloads mine and invites. */
    suspend fun onFrame(type: String, data: kotlinx.serialization.json.JsonObject?)
}
