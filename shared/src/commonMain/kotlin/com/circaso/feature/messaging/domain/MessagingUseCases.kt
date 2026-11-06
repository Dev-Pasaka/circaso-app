package com.circaso.feature.messaging.domain

/* One use case per user action (app-architecture.md, rule 3). Classes per `offline-first.md` are in the KDoc. */

/** Read. */
class ObserveChatsUseCase(private val chats: ChatsRepository) { operator fun invoke() = chats.chats }

/** Read. */
class ObserveChatUseCase(private val chats: ChatsRepository) { operator fun invoke(chatId: String) = chats.chat(chatId) }

/** Read; refreshes quietly in the background. */
class RefreshChatsUseCase(private val chats: ChatsRepository) { suspend operator fun invoke() = chats.refresh() }

/** Online-only. */
class OpenDirectChatUseCase(private val chats: ChatsRepository) { suspend operator fun invoke(userId: String) = chats.openDirect(userId) }

/** Online-only; the same key on every retry of one attempt. */
class CreateGroupUseCase(private val chats: ChatsRepository) { suspend operator fun invoke(draft: GroupDraft, idempotencyKey: String) = chats.createGroup(draft, idempotencyKey) }

/** Online-only. */
class RenameGroupUseCase(private val chats: ChatsRepository) { suspend operator fun invoke(chatId: String, name: String) = chats.rename(chatId, name) }

/** Online-only (admins): a new group photo, or null to remove it. */
class SetGroupPhotoUseCase(private val chats: ChatsRepository) { suspend operator fun invoke(chatId: String, image: com.circaso.core.platform.GroupPhotoImage?, name: String? = null) = chats.setGroupPhoto(chatId, image, name) }

/** Online-only. */
class SetMembersCanAddUseCase(private val chats: ChatsRepository) { suspend operator fun invoke(chatId: String, allowed: Boolean) = chats.setMembersCanAdd(chatId, allowed) }

/** Online-only. */
class AddMembersUseCase(private val chats: ChatsRepository) { suspend operator fun invoke(chatId: String, userIds: List<String>) = chats.addMembers(chatId, userIds) }

/** Online-only. */
class RemoveMemberUseCase(private val chats: ChatsRepository) { suspend operator fun invoke(chatId: String, userId: String) = chats.removeMember(chatId, userId) }

/** Online-only. */
class LeaveGroupUseCase(private val chats: ChatsRepository) { suspend operator fun invoke(chatId: String) = chats.leave(chatId) }

/** Online-only. */
class SetRoleUseCase(private val chats: ChatsRepository) { suspend operator fun invoke(chatId: String, userId: String, role: MemberRole) = chats.setRole(chatId, userId, role) }

/** Read. */
class SecurityCodeUseCase(private val chats: ChatsRepository) { suspend operator fun invoke(userId: String) = chats.securityCode(userId) }

/** Read. */
class ObserveThreadUseCase(private val messages: MessagesRepository) {
    operator fun invoke(chatId: String) = messages.thread(chatId)
    operator fun invoke(chatId: String, limit: Int) = messages.thread(chatId, limit)
}

/** Queueable: saved as Waiting, sent in order when online (design G3). */
class SendMessageUseCase(private val messages: MessagesRepository) { suspend operator fun invoke(chatId: String, text: String, replyTo: String? = null) = messages.send(chatId, text, replyTo) }
class SendPaymentCardUseCase(private val messages: MessagesRepository) { suspend operator fun invoke(chatId: String, card: PaymentCard) = messages.sendPayment(chatId, card) }

/** Queueable. */
class RetryMessageUseCase(private val messages: MessagesRepository) { suspend operator fun invoke(localId: Long) = messages.retry(localId) }

class DiscardMessageUseCase(private val messages: MessagesRepository) { suspend operator fun invoke(localId: Long) = messages.discard(localId) }

/** Online-only. */
class DeleteForEveryoneUseCase(private val messages: MessagesRepository) { suspend operator fun invoke(localId: Long) = messages.deleteForEveryone(localId) }

/** A light signal: sent only while connected, never queued. */
class MarkReadUseCase(private val messages: MessagesRepository) { suspend operator fun invoke(chatId: String) = messages.markRead(chatId) }

/** A light signal: sent only while connected, never queued. */
class SetTypingUseCase(private val messages: MessagesRepository) { suspend operator fun invoke(chatId: String, typing: Boolean) = messages.setTyping(chatId, typing) }

/** Read. */
class ObserveTypingUseCase(private val messages: MessagesRepository) { operator fun invoke(chatId: String) = messages.typing(chatId) }

/** Online-only; the server is the source of truth for these two switches. */
class MessagingSettingsUseCase(private val messages: MessagesRepository) {
    suspend fun get() = messages.settings()
    suspend fun set(readReceipts: Boolean?, typing: Boolean?) = messages.updateSettings(readReceipts, typing)
}
