package com.circaso.feature.messaging.data.remote

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/** Chats and members (M4–M11). */
interface ChatsRemote {
    suspend fun chats(): AppResult<ChatsResponse>
    suspend fun chat(chatId: String): AppResult<ChatDto>
    suspend fun openDirect(userId: String): AppResult<ChatDto>
    suspend fun createGroup(request: CreateGroupRequest, idempotencyKey: String): AppResult<ChatDto>
    suspend fun updateGroup(chatId: String, request: UpdateGroupRequest): AppResult<ChatDto>
    suspend fun addMembers(chatId: String, request: AddMembersRequest): AppResult<ChatDto>
    suspend fun removeMember(chatId: String, userId: String): AppResult<MemberRemovedResponse>
    suspend fun setRole(chatId: String, userId: String, request: SetRoleRequest): AppResult<ChatDto>
}

/** Messages and delivery (M12–M14, M22). */
interface MessagesRemote {
    suspend fun send(chatId: String, request: SendChatMessageRequest, idempotencyKey: String): AppResult<SentMessageResponse>
    suspend fun inbox(after: String?, limit: Int = 100): AppResult<InboxResponse>
    suspend fun ack(request: AckRequest): AppResult<AckResponse>
    suspend fun delete(chatId: String, messageId: String): AppResult<MessageDeletedResponse>
    suspend fun settings(request: MessagingSettingsRequest): AppResult<MessagingSettingsResponse>
}

class KtorChatsRemote(private val api: CircasoApi) : ChatsRemote {
    override suspend fun chats() = api.call<ChatsResponse> { method = HttpMethod.Get; url("/v1/chats") }
    override suspend fun chat(chatId: String) = api.call<ChatDto> { method = HttpMethod.Get; url("/v1/chats/$chatId") }
    override suspend fun openDirect(userId: String) = api.call<ChatDto> {
        method = HttpMethod.Post; url("/v1/chats/direct"); contentType(ContentType.Application.Json); setBody(OpenDirectChatRequest(userId))
    }
    override suspend fun createGroup(request: CreateGroupRequest, idempotencyKey: String) = api.call<ChatDto> {
        method = HttpMethod.Post; url("/v1/chats/groups"); idempotencyKey(idempotencyKey); contentType(ContentType.Application.Json); setBody(request)
    }
    override suspend fun updateGroup(chatId: String, request: UpdateGroupRequest) = api.call<ChatDto> {
        method = HttpMethod.Patch; url("/v1/chats/$chatId"); contentType(ContentType.Application.Json); setBody(request)
    }
    override suspend fun addMembers(chatId: String, request: AddMembersRequest) = api.call<ChatDto> {
        method = HttpMethod.Post; url("/v1/chats/$chatId/members"); contentType(ContentType.Application.Json); setBody(request)
    }
    override suspend fun removeMember(chatId: String, userId: String) = api.call<MemberRemovedResponse> {
        method = HttpMethod.Delete; url("/v1/chats/$chatId/members/$userId")
    }
    override suspend fun setRole(chatId: String, userId: String, request: SetRoleRequest) = api.call<ChatDto> {
        method = HttpMethod.Put; url("/v1/chats/$chatId/members/$userId/role"); contentType(ContentType.Application.Json); setBody(request)
    }
}

/** Group invites (M23–M27): adding someone to a group invites them; they join when they accept. */
interface InvitesRemote {
    suspend fun mine(): AppResult<InvitesResponse>
    suspend fun accept(inviteId: String): AppResult<ChatDto>
    suspend fun decline(inviteId: String): AppResult<InviteDeclinedResponse>
    suspend fun ofChat(chatId: String): AppResult<ChatInvitesResponse>
    suspend fun cancel(chatId: String, userId: String): AppResult<InviteCancelledResponse>
}

class KtorInvitesRemote(private val api: CircasoApi) : InvitesRemote {
    override suspend fun mine() = api.call<InvitesResponse> { method = HttpMethod.Get; url("/v1/chats/invites") }
    override suspend fun accept(inviteId: String) = api.call<ChatDto> { method = HttpMethod.Post; url("/v1/chats/invites/$inviteId/accept") }
    override suspend fun decline(inviteId: String) = api.call<InviteDeclinedResponse> { method = HttpMethod.Post; url("/v1/chats/invites/$inviteId/decline") }
    override suspend fun ofChat(chatId: String) = api.call<ChatInvitesResponse> { method = HttpMethod.Get; url("/v1/chats/$chatId/invites") }
    override suspend fun cancel(chatId: String, userId: String) = api.call<InviteCancelledResponse> {
        method = HttpMethod.Delete; url("/v1/chats/$chatId/invites/$userId")
    }
}

class KtorMessagesRemote(private val api: CircasoApi) : MessagesRemote {
    override suspend fun send(chatId: String, request: SendChatMessageRequest, idempotencyKey: String) = api.call<SentMessageResponse> {
        method = HttpMethod.Post; url("/v1/chats/$chatId/messages"); idempotencyKey(idempotencyKey); contentType(ContentType.Application.Json); setBody(request)
    }
    override suspend fun inbox(after: String?, limit: Int) = api.call<InboxResponse> {
        method = HttpMethod.Get; url("/v1/inbox"); after?.let { parameter("after", it) }; parameter("limit", limit)
    }
    override suspend fun ack(request: AckRequest) = api.call<AckResponse> {
        method = HttpMethod.Post; url("/v1/inbox/ack"); contentType(ContentType.Application.Json); setBody(request)
    }
    override suspend fun delete(chatId: String, messageId: String) = api.call<MessageDeletedResponse> {
        method = HttpMethod.Delete; url("/v1/chats/$chatId/messages/$messageId")
    }
    override suspend fun settings(request: MessagingSettingsRequest) = api.call<MessagingSettingsResponse> {
        method = HttpMethod.Patch; url("/v1/messaging/settings"); contentType(ContentType.Application.Json); setBody(request)
    }
}
