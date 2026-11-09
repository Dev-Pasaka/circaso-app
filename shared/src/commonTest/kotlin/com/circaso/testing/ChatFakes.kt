package com.circaso.testing

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.messaging.data.local.ChatEntity
import com.circaso.feature.messaging.data.local.ChatMemberEntity
import com.circaso.feature.messaging.data.local.ChatUnread
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.GroupMetaKeyEntity
import com.circaso.feature.messaging.data.local.MessageEntity
import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.MessageReceiptEntity
import com.circaso.feature.messaging.data.local.MessagesDao
import com.circaso.feature.messaging.data.local.PendingInboxEntity
import com.circaso.feature.messaging.data.local.SenderKeyDeliveryEntity
import com.circaso.feature.messaging.data.remote.AckRequest
import com.circaso.feature.messaging.data.remote.AckResponse
import com.circaso.feature.messaging.data.remote.AddMembersRequest
import com.circaso.feature.messaging.data.remote.ChatDto
import com.circaso.feature.messaging.data.remote.ChatsRemote
import com.circaso.feature.messaging.data.remote.ChatsResponse
import com.circaso.feature.messaging.data.remote.CreateGroupRequest
import com.circaso.feature.messaging.data.remote.InboxResponse
import com.circaso.feature.messaging.data.remote.KeyBundleResponse
import com.circaso.feature.messaging.data.remote.KeyStatusResponse
import com.circaso.feature.messaging.data.remote.KeysRemote
import com.circaso.feature.messaging.data.remote.MemberRemovedResponse
import com.circaso.feature.messaging.data.remote.MessageDeletedResponse
import com.circaso.feature.messaging.data.remote.MessagesRemote
import com.circaso.feature.messaging.data.remote.MessagingSettingsRequest
import com.circaso.feature.messaging.data.remote.MessagingSettingsResponse
import com.circaso.feature.messaging.data.remote.OneTimePreKeysLeftResponse
import com.circaso.feature.messaging.data.remote.SendChatMessageRequest
import com.circaso.feature.messaging.data.remote.SentMessageResponse
import com.circaso.feature.messaging.data.remote.SetRoleRequest
import com.circaso.feature.messaging.data.remote.UpdateGroupRequest
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeChatsDao : ChatsDao {
    val chats = MutableStateFlow<Map<String, ChatEntity>>(emptyMap())
    val members = MutableStateFlow<List<ChatMemberEntity>>(emptyList())
    val metaKeys = mutableMapOf<Pair<String, Int>, GroupMetaKeyEntity>()
    val deliveries = mutableSetOf<SenderKeyDeliveryEntity>()

    override fun observeChats() = chats.map { it.values.filter { c -> !c.left }.sortedByDescending { c -> c.updatedAt } }
    override fun observeChat(chatId: String) = chats.map { it[chatId] }
    override suspend fun chat(chatId: String) = chats.value[chatId]
    override suspend fun chats() = chats.value.values.toList()
    override suspend fun upsertChat(chat: ChatEntity) { chats.value = chats.value + (chat.chatId to chat) }
    override suspend fun markLeft(chatId: String) { chats.value[chatId]?.let { upsertChat(it.copy(left = true)) } }
    override suspend fun markReadUpTo(chatId: String, orderAt: Long) { chats.value[chatId]?.let { if (it.readUpToOrderAt < orderAt) upsertChat(it.copy(readUpToOrderAt = orderAt)) } }
    override suspend fun touch(chatId: String, at: Long) { chats.value[chatId]?.let { if (it.updatedAt < at) upsertChat(it.copy(updatedAt = at)) } }
    override suspend fun deleteChat(chatId: String) { chats.value = chats.value - chatId }
    override fun observeMembers(chatId: String) = members.map { it.filter { m -> m.chatId == chatId } }
    override suspend fun members(chatId: String) = members.value.filter { it.chatId == chatId }
    override fun observeAllMembers() = members
    override suspend fun insertMembers(members: List<ChatMemberEntity>) { this.members.value = this.members.value.filterNot { m -> members.any { it.chatId == m.chatId && it.userId == m.userId } } + members }
    override suspend fun deleteMembers(chatId: String) { members.value = members.value.filterNot { it.chatId == chatId } }
    override suspend fun deleteMember(chatId: String, userId: String) { members.value = members.value.filterNot { it.chatId == chatId && it.userId == userId } }
    override suspend fun updateMemberProfile(userId: String, displayName: String, handle: String?, avatarUrl: String?) {
        members.value = members.value.map { if (it.userId == userId) it.copy(displayName = displayName, handle = handle, avatarUrl = avatarUrl) else it }
    }
    override suspend fun metaKey(chatId: String, metaVersion: Int) = metaKeys[chatId to metaVersion]
    override suspend fun latestMetaKey(chatId: String) = metaKeys.values.filter { it.chatId == chatId }.maxByOrNull { it.metaVersion }
    override suspend fun upsertMetaKey(key: GroupMetaKeyEntity) { metaKeys[key.chatId to key.metaVersion] = key }
    override suspend fun pendingMetaKeys() = metaKeys.values.filter { it.chatId.startsWith("pending:") }
    override suspend fun deleteMetaKeys(chatId: String) { metaKeys.keys.removeAll { it.first == chatId } }
    override suspend fun deletePendingMetaKeysBefore(before: Long) { metaKeys.values.removeAll { it.chatId.startsWith("pending:") && it.createdAt < before } }
    override suspend fun deliveredTo(chatId: String, distributionId: String) = deliveries.filter { it.chatId == chatId && it.distributionId == distributionId }.map { it.userId }
    override suspend fun insertDeliveries(rows: List<SenderKeyDeliveryEntity>) { deliveries += rows }
    override suspend fun deleteDeliveries(chatId: String) { deliveries.removeAll { it.chatId == chatId } }
    override suspend fun deleteDelivery(chatId: String, userId: String) { deliveries.removeAll { it.chatId == chatId && it.userId == userId } }
    override suspend fun deleteDeliveriesTo(userId: String) { deliveries.removeAll { it.userId == userId } }
    override suspend fun clearChats() { chats.value = emptyMap() }
    override suspend fun clearMembers() { members.value = emptyList() }
    override suspend fun clearMetaKeys() = metaKeys.clear()
    override suspend fun clearDeliveries() = deliveries.clear()
}

class FakeMessagesDao(private val chats: FakeChatsDao? = null) : MessagesDao {
    val rows = MutableStateFlow<List<MessageEntity>>(emptyList())
    val receipts = mutableMapOf<Pair<String, String>, MessageReceiptEntity>()
    val pending = mutableMapOf<String, PendingInboxEntity>()
    private var nextId = 1L

    private fun update(localId: Long, change: (MessageEntity) -> MessageEntity) { rows.value = rows.value.map { if (it.localId == localId) change(it) else it } }

    override fun observeThread(chatId: String) = rows.map { it.filter { m -> m.chatId == chatId }.sortedWith(compareBy({ it.orderAt }, { it.localId })) }
    override fun observeRecent(chatId: String, limit: Int) = rows.map { it.filter { m -> m.chatId == chatId }.sortedWith(compareByDescending<MessageEntity> { it.orderAt }.thenByDescending { it.localId }).take(limit) }
    override fun observeLatestPerChat(): Flow<List<MessageEntity>> = rows.map { list -> list.groupBy { it.chatId }.values.map { it.maxWith(compareBy({ m -> m.orderAt }, { m -> m.localId })) } }
    override fun observeUnread(): Flow<List<ChatUnread>> =
        kotlinx.coroutines.flow.combine(rows, chats?.chats ?: MutableStateFlow(emptyMap())) { list, chatRows ->
            list.filter { m -> !m.outgoing && m.kind != MessageKind.System && m.orderAt > (chatRows[m.chatId]?.readUpToOrderAt ?: 0L) }
                .groupBy { it.chatId }.map { (chat, ms) -> ChatUnread(chat, ms.size) }
        }
    override suspend fun byLocalId(localId: Long) = rows.value.firstOrNull { it.localId == localId }
    override suspend fun byMessageId(messageId: String) = rows.value.firstOrNull { it.messageId == messageId }
    override suspend fun byClientMessageId(clientMessageId: String) = rows.value.firstOrNull { it.clientMessageId == clientMessageId }
    override suspend fun newestIncoming(chatId: String) = rows.value.filter { it.chatId == chatId && !it.outgoing && it.kind != MessageKind.System && it.messageId != null }.maxByOrNull { it.orderAt }
    override suspend fun paymentCardLocalId(chatId: String, transferId: String) = rows.value.filter { it.chatId == chatId && it.kind == MessageKind.Payment && it.transferId == transferId }.minOfOrNull { it.localId }
    override suspend fun lastCallLocalId(chatId: String) = rows.value.filter { it.chatId == chatId && it.kind == MessageKind.Call }.maxWithOrNull(compareBy({ it.orderAt }, { it.localId }))?.localId
    override suspend fun newestIncomingOrderAt(chatId: String) = rows.value.filter { it.chatId == chatId && !it.outgoing && it.kind != MessageKind.System }.maxOfOrNull { it.orderAt }
    override suspend fun outgoingAwaitingReceipt(chatId: String) = rows.value.filter { it.chatId == chatId && it.outgoing && it.messageId != null && it.status in setOf("sent", "delivered") }
    override suspend fun liveShare(chatId: String, senderId: String, liveId: String) =
        rows.value.firstOrNull { it.chatId == chatId && it.senderId == senderId && it.kind == MessageKind.Location && it.text?.contains("\"liveId\":\"$liveId\"") == true }
    override suspend fun ownLiveShares() = rows.value.filter { it.outgoing && it.kind == MessageKind.Location && it.text?.contains("\"liveId\"") == true }
    override fun observeLiveShares(chatId: String) = rows.map { list -> list.filter { it.chatId == chatId && it.kind == MessageKind.Location && it.text?.contains("\"liveId\"") == true }.sortedBy { it.orderAt } }
    override suspend fun insert(message: MessageEntity): Long { val id = nextId++; rows.value = rows.value + message.copy(localId = id); return id }
    override suspend fun insertIfAbsent(message: MessageEntity): Long =
        if (rows.value.any { it.clientMessageId == message.clientMessageId }) -1 else insert(message)
    override suspend fun upsert(message: MessageEntity) { if (rows.value.any { it.localId == message.localId }) update(message.localId) { message } else insert(message) }
    override suspend fun setStatus(localId: Long, status: String, errorCode: String?) = update(localId) { it.copy(status = status, errorCode = errorCode) }
    override suspend fun advanceStatus(messageId: String, status: String, from: List<String>) { rows.value = rows.value.map { if (it.messageId == messageId && it.outgoing && it.status in from) it.copy(status = status) else it } }
    override suspend fun setCachedRequest(localId: Long, request: String?) = update(localId) { it.copy(cachedRequest = request) }
    override suspend fun markSent(localId: Long, messageId: String, sentAt: String, orderAt: Long, status: String) = update(localId) { it.copy(messageId = messageId, sentAt = sentAt, orderAt = orderAt, status = status, errorCode = null, cachedRequest = null) }
    override suspend fun setClientMessageId(localId: Long, clientMessageId: String) = update(localId) { it.copy(clientMessageId = clientMessageId) }
    override suspend fun setText(localId: Long, text: String) = update(localId) { it.copy(text = text, cachedRequest = null) }
    override suspend fun markDeleted(messageId: String) { rows.value = rows.value.map { if (it.messageId == messageId) it.copy(status = "deleted", text = null, cachedRequest = null) else it } }
    override suspend fun delete(localId: Long) { rows.value = rows.value.filterNot { it.localId == localId } }
    override suspend fun deleteThread(chatId: String) { rows.value = rows.value.filterNot { it.chatId == chatId } }
    override suspend fun upsertReceipts(receipts: List<MessageReceiptEntity>) { receipts.forEach { this.receipts[it.messageId to it.userId] = it } }
    override suspend fun receipts(messageId: String) = receipts.values.filter { it.messageId == messageId }
    override fun observeReceipts(messageIds: List<String>): Flow<List<MessageReceiptEntity>> = MutableStateFlow(receipts.values.filter { it.messageId in messageIds })
    override suspend fun upsertPending(pending: PendingInboxEntity) { this.pending[pending.messageId] = pending }
    override suspend fun pendingFrom(chatId: String, senderId: String) = pending.values.filter { it.chatId == chatId && it.senderId == senderId }.sortedBy { it.sentAt }
    override suspend fun pendingOlderThan(before: Long) = pending.values.filter { it.receivedAt < before }
    override suspend fun deletePending(messageId: String) { pending.remove(messageId) }
    override suspend fun clearMessages() { rows.value = emptyList() }
    override suspend fun clearReceipts() = receipts.clear()
    override suspend fun clearPending() = pending.clear()
}

class FakeMessagesRemote : MessagesRemote {
    val sent = mutableListOf<Triple<String, SendChatMessageRequest, String>>()
    /** Server ids count from zero in the order the sends arrive. */
    var sendResult: (SendChatMessageRequest) -> AppResult<SentMessageResponse> = { AppResult.Success(SentMessageResponse("srv-${sent.size - 1}", "2026-10-28T10:00:00Z")) }
    var inboxPages = mutableListOf<InboxResponse>()
    val acked = mutableListOf<String>()
    var deleteResult: AppResult<MessageDeletedResponse> = AppResult.Success(MessageDeletedResponse(true))

    override suspend fun send(chatId: String, request: SendChatMessageRequest, idempotencyKey: String): AppResult<SentMessageResponse> { sent += Triple(chatId, request, idempotencyKey); return sendResult(request) }
    override suspend fun inbox(after: String?, limit: Int): AppResult<InboxResponse> = AppResult.Success(if (inboxPages.isEmpty()) InboxResponse(emptyList(), null) else inboxPages.removeAt(0))
    override suspend fun ack(request: AckRequest): AppResult<AckResponse> { acked += request.messageIds; return AppResult.Success(AckResponse(request.messageIds.size)) }
    override suspend fun delete(chatId: String, messageId: String) = deleteResult
    override suspend fun settings(request: MessagingSettingsRequest) = AppResult.Success(MessagingSettingsResponse(request.readReceipts ?: true, request.typing ?: true))
}

class FakeChatsRemote : ChatsRemote {
    var chats: List<ChatDto> = emptyList()
    var openDirectResult: (String) -> AppResult<ChatDto> = { AppResult.Failure(AppError.Network) }
    override suspend fun chats() = AppResult.Success(ChatsResponse(chats))
    override suspend fun chat(chatId: String): AppResult<ChatDto> = chats.firstOrNull { it.chatId == chatId }?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.Api("messaging.chat_not_found", 404, null, null, emptyMap()))
    override suspend fun openDirect(userId: String) = openDirectResult(userId)
    var createGroupResult: (CreateGroupRequest, String) -> AppResult<ChatDto> = { _, _ -> AppResult.Failure(AppError.Network) }
    var updateGroupResult: (String, UpdateGroupRequest) -> AppResult<ChatDto> = { _, _ -> AppResult.Failure(AppError.Network) }
    val updates = mutableListOf<Pair<String, UpdateGroupRequest>>()
    override suspend fun createGroup(request: CreateGroupRequest, idempotencyKey: String): AppResult<ChatDto> = createGroupResult(request, idempotencyKey)
    override suspend fun updateGroup(chatId: String, request: UpdateGroupRequest): AppResult<ChatDto> { updates += chatId to request; return updateGroupResult(chatId, request) }
    var addMembersResult: (String, AddMembersRequest) -> AppResult<ChatDto> = { _, _ -> AppResult.Failure(AppError.Network) }
    override suspend fun addMembers(chatId: String, request: AddMembersRequest): AppResult<ChatDto> = addMembersResult(chatId, request)
    override suspend fun removeMember(chatId: String, userId: String): AppResult<MemberRemovedResponse> = AppResult.Success(MemberRemovedResponse(true))
    override suspend fun setRole(chatId: String, userId: String, request: SetRoleRequest): AppResult<ChatDto> = AppResult.Failure(AppError.Network)
}

/** A key directory that always has a bundle for everyone (the fake crypto does not check it). */
class FakeKeysRemote : KeysRemote {
    var bundleFor: (String) -> AppResult<KeyBundleResponse> = { id ->
        AppResult.Success(
            KeyBundleResponse(
                id, "aWQ=", com.circaso.feature.messaging.data.remote.SignedPreKeyDto(1, "cw==", "cw=="),
                com.circaso.feature.messaging.data.remote.KyberPreKeyDto(2, "aw==", "aw=="), com.circaso.feature.messaging.data.remote.OneTimePreKeyDto(100, "bw=="),
            ),
        )
    }
    override suspend fun publish(body: ByteArray, proof: SignedKeyUpload.Proof) = AppResult.Success(OneTimePreKeysLeftResponse(100))
    override suspend fun addOneTime(body: ByteArray, proof: SignedKeyUpload.Proof) = AppResult.Success(OneTimePreKeysLeftResponse(100))
    override suspend fun status() = AppResult.Success(KeyStatusResponse(100, "2026-10-28T09:00:00Z"))
    override suspend fun bundle(userId: String) = bundleFor(userId)
}
