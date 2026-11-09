package com.circaso.gallery

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.nowMillis
import com.circaso.feature.calls.LiveCallPerson
import com.circaso.feature.calls.LiveChatCall
import com.circaso.feature.calls.presentation.CallBarAction
import com.circaso.feature.calls.presentation.CallBarFace
import com.circaso.feature.calls.presentation.ChatCallBar
import com.circaso.feature.messaging.presentation.ChatsContent
import com.circaso.feature.messaging.presentation.ChatsUiState
import com.circaso.feature.messaging.presentation.ThreadContent
import com.circaso.feature.messaging.presentation.ThreadUiState

/**
 * Debug previews of a call going on in a chat (2026-11-09): `-preview=chats-call` (the list's "Call in progress"),
 * `chat-call-group` (join a group call), `chat-call-switch` (I'm on a call in another chat), `chat-call-offline`,
 * `chat-call-return` (my own call, minimised), `chat-call-calling` (ringing out, minimised) and `chat-call-elsewhere`
 * (on my other phone). Large text: run any of them with the phone's font size at its largest.
 */
@Composable
internal fun ChatCallPreview(name: String): Boolean {
    val now = nowMillis()
    val mum = chatList[0].chat
    val fund = chatList[1].chat
    val groupCall = LiveChatCall(
        "c1", fund.chatId, video = false,
        joined = listOf(LiveCallPerson("3", "Wanjiru Kamau"), LiveCallPerson("7", "Juma"), LiveCallPerson("8", "Faith")),
        sinceMillis = now - 252_000, meOn = false,
    )
    val faces = listOf(CallBarFace("Wanjiru Kamau", null), CallBarFace("Juma", null), CallBarFace("Faith", null))
    when (name) {
        "chats-call" -> ChatsContent(
            ChatsUiState(
                chats = chatList, loaded = true,
                calls = mapOf(
                    fund.chatId to groupCall,
                    mum.chatId to LiveChatCall("c2", mum.chatId, video = true, joined = listOf(LiveCallPerson("2", "Mum")), sinceMillis = now - 40_000, meOn = false),
                ),
            ),
            PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {},
        )
        "chat-call-group" -> ThreadContent(
            ThreadUiState(chat = fund, messages = chatList[1].latest?.let { listOf(it) }.orEmpty()), {}, {}, null, {}, {}, {}, {}, {}, {}, {},
            callBar = ChatCallBar(false, "Wanjiru and 2 others", faces, now - 252_000, CallBarAction.Join),
        )
        "chat-call-return" -> ThreadContent(
            ThreadUiState(chat = mum, messages = chatList[0].latest?.let { listOf(it) }.orEmpty()), {}, {}, null, {}, {}, {}, {}, {}, {}, {},
            callBar = ChatCallBar(true, "Mum", listOf(CallBarFace("Mum", null)), now - 3_725_000, CallBarAction.Return),
        )
        "chat-call-elsewhere" -> ThreadContent(
            ThreadUiState(chat = mum, messages = chatList[0].latest?.let { listOf(it) }.orEmpty()), {}, {}, null, {}, {}, {}, {}, {}, {}, {},
            callBar = ChatCallBar(false, "Mum", listOf(CallBarFace("Mum", null)), now - 61_000, CallBarAction.None, note = "On your other phone"),
        )
        "chat-call-switch" -> ThreadContent(
            ThreadUiState(chat = fund, messages = chatList[1].latest?.let { listOf(it) }.orEmpty()), {}, {}, null, {}, {}, {}, {}, {}, {}, {},
            callBar = ChatCallBar(false, "Wanjiru and 2 others", faces, now - 252_000, CallBarAction.Switch),
        )
        "chat-call-offline" -> ThreadContent(
            ThreadUiState(chat = fund, messages = chatList[1].latest?.let { listOf(it) }.orEmpty(), online = false), {}, {}, null, {}, {}, {}, {}, {}, {}, {},
            callBar = ChatCallBar(false, "Wanjiru and 2 others", faces, now - 252_000, CallBarAction.None, note = "Needs a connection"),
        )
        "chat-call-calling" -> ThreadContent(
            ThreadUiState(chat = mum, messages = chatList[0].latest?.let { listOf(it) }.orEmpty()), {}, {}, null, {}, {}, {}, {}, {}, {}, {},
            callBar = ChatCallBar(false, "Mum", listOf(CallBarFace("Mum", null)), null, CallBarAction.Return, note = "Calling…"),
        )
        else -> return false
    }
    return true
}
