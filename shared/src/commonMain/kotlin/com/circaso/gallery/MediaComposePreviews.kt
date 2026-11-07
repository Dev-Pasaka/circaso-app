package com.circaso.gallery

import androidx.compose.runtime.Composable
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.GroupCreated
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.messaging.presentation.ChatComposeStart
import com.circaso.feature.messaging.presentation.ChatMediaComposerScreen
import com.circaso.feature.messaging.presentation.ChatMediaComposerViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Debug previews of the shared media composer (`--es preview compose-…`): the real camera and library of this phone,
 * a chat named Wanjiru, and a sender that keeps nothing. For checking the chat and moment composers side by side.
 */
@Composable
internal fun MediaComposePreview(name: String) {
    when (name) {
        "compose-moment" -> com.circaso.feature.status.presentation.MomentComposerScreen(org.koin.compose.viewmodel.koinViewModel(), onClose = {}, onAudience = {})
        else -> {
            val start = when (name) { "compose-chat-gallery" -> ChatComposeStart.Gallery; "compose-chat-document" -> ChatComposeStart.Document; else -> ChatComposeStart.Camera }
            val library = org.koin.compose.koinInject<com.circaso.core.platform.MediaLibrary>()
            val camera = org.koin.compose.koinInject<com.circaso.core.platform.MomentCamera>()
            val vm = androidx.compose.runtime.remember(name) {
                ChatMediaComposerViewModel("preview", start, library, camera, PreviewChat, { _, _, _ -> true })
            }
            ChatMediaComposerScreen(vm, onClose = {})
        }
    }
}

private object PreviewChat : ChatsRepository {
    private val chat = Chat("preview", ChatKind.Direct, "Wanjiru", null, emptyList(), false, 1, 1, MemberRole.Member, "u2")
    override val chats: Flow<List<ChatSummary>> = flowOf(listOf(ChatSummary(chat, null, 0)))
    override fun chat(chatId: String): Flow<Chat?> = flowOf(chat)
    override suspend fun refresh() = AppResult.Success(Unit)
    override suspend fun refreshChat(chatId: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun openDirect(userId: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun createGroup(draft: GroupDraft, idempotencyKey: String): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    override suspend fun rename(chatId: String, name: String): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated> = AppResult.Failure(AppError.Network)
    override suspend fun removeMember(chatId: String, userId: String) = AppResult.Success(Unit)
    override suspend fun leave(chatId: String) = AppResult.Success(Unit)
    override suspend fun setRole(chatId: String, userId: String, role: MemberRole): AppResult<Chat> = AppResult.Failure(AppError.Network)
    override suspend fun securityCode(userId: String): SecurityCode? = null
}
