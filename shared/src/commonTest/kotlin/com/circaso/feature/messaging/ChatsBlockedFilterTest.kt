package com.circaso.feature.messaging

import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.presentation.ChatFilter
import com.circaso.feature.messaging.presentation.ChatsUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatsBlockedFilterTest {
    private fun summary(id: String, kind: ChatKind, peer: String?, unread: Int = 0) = ChatSummary(
        chat = Chat(
            chatId = id, kind = kind, title = id, avatarUrl = null, members = emptyList(), membersCanAdd = false,
            version = 1, metaVersion = 1, myRole = MemberRole.Member, peerId = peer,
        ),
        latest = null,
        unread = unread,
    )

    private val chats = listOf(
        summary("amina", ChatKind.Direct, "u-amina", unread = 2),
        summary("brian", ChatKind.Direct, "u-brian", unread = 1),
        summary("trip", ChatKind.Group, null),
    )

    @Test
    fun blockedPeopleLeaveEveryList() {
        val s = ChatsUiState(chats = chats, blocked = setOf("u-brian"))
        assertEquals(listOf("amina", "trip"), s.shown.map { it.chat.chatId })
        assertEquals(listOf("amina"), s.copy(filter = ChatFilter.Unread).shown.map { it.chat.chatId })
        assertEquals(emptyList(), s.copy(query = "bri").shown.map { it.chat.chatId })
    }

    @Test
    fun groupsStayAndUnblockingBringsTheChatBack() {
        assertEquals(listOf("trip"), ChatsUiState(chats = chats, blocked = setOf("u-amina", "u-brian")).shown.map { it.chat.chatId })
        assertEquals(3, ChatsUiState(chats = chats).shown.size)
    }
}
